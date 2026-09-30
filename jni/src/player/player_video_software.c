#include "player_video_software.h"
#include "player.h"
#include "player_diagnostics.h"
#include "player_timing.h"
#include "player_video_mediacodec.h"
#include "util.h"
#include <libswscale/swscale.h>
#include <libavutil/imgutils.h>
#include <libavutil/cpu.h>
#ifdef __clang__
#pragma clang diagnostic push
#pragma clang diagnostic ignored "-Wstrict-prototypes"
#endif
#include <libyuv.h>
#ifdef __clang__
#pragma clang diagnostic pop
#endif
#include <android/native_window_jni.h>
#include <inttypes.h>
#include <string.h>
#define GAINING_THRESHOLD 100
#define SOFTWARE_GOVERNOR_SLOW_CONVERSION_US 25000
#define SOFTWARE_GOVERNOR_SLOW_CONVERSIONS 6
#define SOFTWARE_SEEK_FAST_MIN_GAP_MS 600
#define SOFTWARE_SEEK_FAST_RESTORE_MARGIN_MS 500
#define MAX_FPS 60

// Callers hold decode.video.frameMutex. The normal late-frame governor may
// already require non-reference discard, so seek acceleration always restores
// the decoder to that session baseline instead of unconditionally disabling it.
static enum AVDiscard getSoftwareFrameDiscardBaseline(Player * player) {
	return player->video.softwareDecoderDiscardActive ? AVDISCARD_NONREF : AVDISCARD_DEFAULT;
}

// libdav1d consumes skip_frame at open, not as a live overload control.
// Only use FFmpeg's live skip control for the native decoders we support here.
static int supportsLiveDiscard(const AVCodecContext * context) {
	const char * name = context->codec ? context->codec->name : "";
	return !strcmp(name, "h264") || !strcmp(name, "hevc") || !strcmp(name, "vp8")
			|| !strcmp(name, "vp9") || !strcmp(name, "mpeg2video");
}

void playerVideoSoftwareConfigureThreads(AVCodecContext * context, const AVCodec * codec) {
	int parallel = codec && ((codec->capabilities &
			(AV_CODEC_CAP_FRAME_THREADS | AV_CODEC_CAP_SLICE_THREADS)) || !strcmp(codec->name, "libdav1d"));
	context->thread_count = parallel ? videoSoftwareThreadCount(av_cpu_count()) : 1;
	context->thread_type = FF_THREAD_FRAME | FF_THREAD_SLICE;
}

// Callers hold video.frameMutex. No clock/PCM reset: recovery must preserve A/V time.
void playerVideoSoftwareResetGovernorLocked(Player * player, AVCodecContext * context,
		const char * reason) {
	VideoLoadControl * c = &player->video.softwareLoad;
	int oldState = c->state;
	videoLoadReset(c, getPlaybackSpeed(player));
	player->video.softwareObservedRateRevision =
			__atomic_load_n(&player->video.softwareRateRevision, __ATOMIC_ACQUIRE);
	player->video.softwareDecoderDiscardActive = 0;
	player->video.softwareDecoderDiscardStartedAt = 0;
	if (!player->video.softwareSeekFastActive) context->skip_frame = AVDISCARD_DEFAULT;
	diagnosticsLog("player=%u software_load reset=%s previous_state=%d speed_milli=%d"
			" threads=%d live_discard_supported=%d audio_reset=0",
			player->meta.diagnosticsId, reason, oldState, c->speed,
			context->thread_count, supportsLiveDiscard(context));
}

static void updateSoftwareLoadController(Player * player,
		int synchronized, int64_t framePosition, int64_t lateness) {
	pthread_mutex_lock(&player->decode.video.frameMutex);
	VideoLoadControl * c = &player->video.softwareLoad;
	int oldState = c->state;
	if (synchronized && player->play.playing) {
		int64_t now = getTime();
		videoLoadObserve(c, getPlaybackSpeed(player), lateness, now);
		c->decodedPosition = framePosition;
		if (oldState != c->state) {
			diagnosticsLog(
				"player=%u software_load transition=%d->%d late_media_ms=%" PRId64
				" late_wall_ms=%" PRId64 " speed_milli=%d pts_ms=%" PRId64 " previous_state_ms=%" PRId64,
				player->meta.diagnosticsId, oldState, c->state, lateness,
				c->lateWallMs, c->speed, framePosition, c->stateSince ? now - c->stateSince : 0);
			c->stateSince = now;
		}
	} else {
		c->lateSince = c->stableSince = 0;
	}
	pthread_mutex_unlock(&player->decode.video.frameMutex);
}

// video.frameMutex -> packet queue. Never acquire flowMutex from this function.
// Only discard a contiguous old-generation-consistent prefix when a later,
// already-buffered keyframe is known. No seeking the demuxer or touching audio.
static void catchUpSoftwareDecoderLocked(Player * player, AVCodecContext * context,
		AVStream * stream, PacketHolder ** pending, AVFrame * frame) {
	VideoLoadControl * c = &player->video.softwareLoad;
	int64_t now = getTime();
	int speed = getPlaybackSpeed(player);
	int64_t clock = calculatePosition(player, 1);
	int64_t late = clock - c->decodedPosition;
	if (c->state != VIDEO_LOAD_CATCHUP || c->decodedPosition < 0 ||
			!player->play.playing || !HAS_STREAM(player, audio) || player->sync.audioPositionNotSync ||
			player->sync.videoPositionNotSync || player->video.softwareSeekFastActive ||
			playerVideoHasPendingSurface(player) || playerGetSkipFlag(&player->sync.skip.videoWorkFrame) ||
			!(*pending) || (*pending)->type != PACKET_HOLDER_MEDIA || !(*pending)->packet ||
			!hardwareCatchupDue(late, speed, c->lateSince, c->lastCatchup, now) ||
			now - c->scanAt < 100) return;
	c->scanAt = now;
	uint64_t generation = __atomic_load_n(&player->decode.packets.generation, __ATOMIC_ACQUIRE);
	if ((*pending)->generation != generation) return;
	BlockingQueue * queue = &player->video.packetQueue;
	QueueItem * best = NULL;
	int64_t keyPosition = -1;
	int scanned = 0, dropped = 0;
	pthread_mutex_lock(&queue->mutex);
	for (QueueItem * item = queue->queue.first; item && scanned++ < 4096; item = item->next) {
		PacketHolder * holder = item->data;
		if (!holder || holder->type != PACKET_HOLDER_MEDIA || holder->generation != generation) break;
		AVPacket * packet = holder->packet;
		if (packet && (packet->flags & AV_PKT_FLAG_KEY)) {
			int64_t position = getTimestampPositionMs(player,
					packet->pts != AV_NOPTS_VALUE ? packet->pts : packet->dts, stream->time_base);
			if (position > keyPosition && hardwareCatchupKeyEligible(position, c->decodedPosition, clock, speed)) {
				best = item; keyPosition = position;
			}
		}
	}
	PacketHolder * replacement = NULL;
	if (best && generation == __atomic_load_n(&player->decode.packets.generation, __ATOMIC_ACQUIRE)) {
		while (queue->queue.first != best) {
			playerPacketQueueFreeCallback(queueGet(&queue->queue)); dropped++;
		}
		replacement = queueGet(&queue->queue);
	}
	pthread_mutex_unlock(&queue->mutex);
	if (!replacement) {
		c->keyWaits++;
		if (!c->reportAt || now - c->reportAt >= 1000) {
			c->reportAt = now;
			diagnosticsLog(
				"player=%u software_catchup_wait reason=no_eligible_keyframe scanned=%d"
				" late_media_ms=%" PRId64 " speed_milli=%d", player->meta.diagnosticsId, scanned, late, speed);
		}
		return;
	}
	playerPacketQueueFreeCallback(*pending);
	*pending = replacement;
	av_frame_unref(frame);
	avcodec_flush_buffers(context);
	context->skip_frame = AVDISCARD_DEFAULT;
	// Already-converted old frames, including the draw thread's held frame,
	// must not reappear after this jump. Do not free a seized output buffer.
	pthread_mutex_lock(&player->video.sleepDrawMutex);
	__atomic_add_fetch(&player->video.softwareOutputEpoch, 1, __ATOMIC_RELEASE);
	pthread_cond_broadcast(&player->video.sleepCond);
	pthread_mutex_unlock(&player->video.sleepDrawMutex);
	c->lastCatchup = now;
	c->catchups++;
	c->skippedPackets += dropped + 1;
	c->decodedPosition = keyPosition;
	c->outputSlot = -1;
	c->lateSince = c->stableSince = 0;
	c->state = VIDEO_LOAD_RECOVERING;
	c->stateSince = now;
	diagnosticsLog("player=%u software_catchup key_ms=%" PRId64 " clock_ms=%" PRId64
			" skipped_packets=%d scanned=%d epoch=%u audio_reset=0",
			player->meta.diagnosticsId, keyPosition, clock, dropped + 1, scanned,
			__atomic_load_n(&player->video.softwareOutputEpoch, __ATOMIC_ACQUIRE));
}
void playerVideoSoftwareRestoreSeekFastLocked(Player * player, AVCodecContext * context,
		const char * reason, int64_t packetPosition) {
	if (!player->video.softwareSeekFastActive) {
		context->skip_frame = getSoftwareFrameDiscardBaseline(player);
		return;
	}
	int64_t elapsed = getTime() - player->video.softwareSeekFastStartedAt;
	context->skip_frame = getSoftwareFrameDiscardBaseline(player);
	player->video.softwareSeekFastActive = 0;
	diagnosticsIncrement(PLAYER_DIAGNOSTICS_SOFTWARE_SEEK_FAST_RESTORED);
	diagnosticsLog("player=%u software_seek_fast restored reason=%s packet_ms=%" PRId64
			" target_ms=%" PRId64 " elapsed_ms=%" PRId64 " packets=%d frames=%d baseline=%s",
			player->meta.diagnosticsId, reason, packetPosition,
			player->video.softwareSeekFastTargetPosition, elapsed,
			player->video.softwareSeekFastPackets, player->video.softwareSeekFastFrames,
			player->video.softwareDecoderDiscardActive ? "nonref" : "default");
}

void playerVideoSoftwareStartSeekFastLocked(Player * player, AVCodecContext * context,
		int64_t keyframePosition, int64_t targetPosition) {
	playerVideoSoftwareRestoreSeekFastLocked(player, context, "new_seek", keyframePosition);
	player->video.softwareSeekFastPackets = 0;
	player->video.softwareSeekFastFrames = 0;
	int64_t gap = targetPosition - keyframePosition;
	if (!supportsLiveDiscard(context) || player->video.hardwareDecoderActive || player->video.softwareDecoderDiscardActive ||
			gap < SOFTWARE_SEEK_FAST_MIN_GAP_MS) {
		diagnosticsLog("player=%u software_seek_fast skipped keyframe_ms=%" PRId64
				" target_ms=%" PRId64 " gap_ms=%" PRId64 " hardware=%d baseline=%s live_discard_supported=%d",
				player->meta.diagnosticsId, keyframePosition, targetPosition, gap,
				player->video.hardwareDecoderActive,
				player->video.softwareDecoderDiscardActive ? "nonref" : "default", supportsLiveDiscard(context));
		return;
	}
	player->video.softwareSeekFastActive = 1;
	player->video.softwareSeekFastTargetPosition = targetPosition;
	player->video.softwareSeekFastRestorePosition =
			max64(targetPosition - SOFTWARE_SEEK_FAST_RESTORE_MARGIN_MS, keyframePosition);
	player->video.softwareSeekFastStartedAt = getTime();
	context->skip_frame = AVDISCARD_NONREF;
	diagnosticsIncrement(PLAYER_DIAGNOSTICS_SOFTWARE_SEEK_FAST_STARTED);
	diagnosticsLog("player=%u software_seek_fast started keyframe_ms=%" PRId64
			" target_ms=%" PRId64 " gap_ms=%" PRId64 " restore_ms=%" PRId64
			" discard=nonref",
			player->meta.diagnosticsId, keyframePosition, targetPosition, gap,
			player->video.softwareSeekFastRestorePosition);
}

static void updateSoftwareSeekFastDecodeForPacketLocked(Player * player, AVCodecContext * context,
		AVStream * stream, AVPacket * packet) {
	if (!player->video.softwareSeekFastActive) {
		return;
	}
	if (!packet) {
		playerVideoSoftwareRestoreSeekFastLocked(player, context, "end_of_stream", -1);
		return;
	}
	player->video.softwareSeekFastPackets++;
	int64_t timestamp = packet->dts != AV_NOPTS_VALUE ? packet->dts : packet->pts;
	int64_t packetPosition = getTimestampPositionMs(player, timestamp, stream->time_base);
	if (packetPosition >= 0 && packetPosition >= player->video.softwareSeekFastRestorePosition) {
		playerVideoSoftwareRestoreSeekFastLocked(player, context, "restore_margin", packetPosition);
	}
}

void playerVideoBufferQueueFreeCallback(BufferItem * bufferItem) {
	if (bufferItem->extra) {
		free(bufferItem->extra);
		bufferItem->extra = NULL;
	}
}

static void drawWindow(Player * player, uint8_t * buffer, int width, int height,
		int lastWidth, int lastHeight) {
	if (player->video.window) {
		if (width != lastWidth || height != lastHeight) {
			ANativeWindow_setBuffersGeometry(player->video.window, width, height,
					ANativeWindow_getFormat(player->video.window));
		}
		ANativeWindow_Buffer canvas;
		DiagnosticsWorkStamp windowStamp = diagnosticsWorkBegin();
		int lockResult = ANativeWindow_lock(player->video.window, &canvas, NULL);
		diagnosticsWorkEnd(player, DIAGNOSTICS_WORK_WINDOW_LOCK, windowStamp, lockResult);
		if (lockResult == 0) {
			windowStamp = diagnosticsWorkBegin();
			if (canvas.width >= width && canvas.height >= height) {
				// Width and height can be smaller in the moment of surface changing and before it was handled
				uint8_t * to = canvas.bits;
				if (player->video.format == AV_PIX_FMT_YUV420P) {
					for (int i = 0; i < height; i++) {
						memcpy(to, buffer, width);
						to += canvas.stride;
						buffer += width;
					}
					memset(to, 127, canvas.stride * height / 2);
					for (int i = 0; i < height / 2; i++) {
						memcpy(to, buffer, width / 2);
						to += canvas.stride / 2;
						buffer += width / 2;
					}
					if (canvas.stride % 32 != 0) {
						to += height / 2 * 8; // Align to 16
					}
					for (int i = 0; i < height / 2; i++) {
						memcpy(to, buffer, width / 2);
						to += canvas.stride / 2;
						buffer += width / 2;
					}
				} else {
					int bytesPerPixel = getBytesPerPixel(player->video.format);
					if (bytesPerPixel > 0) {
						size_t rowBytes = (size_t) bytesPerPixel * (size_t) width;
						for (int i = 0; i < height; i++) {
							memcpy(to, buffer, rowBytes);
							to += (size_t) bytesPerPixel * (size_t) canvas.stride;
							buffer += rowBytes;
						}
					}
				}
			}
			diagnosticsWorkEnd(player, DIAGNOSTICS_WORK_WINDOW_COPY, windowStamp,
					canvas.width >= width && canvas.height >= height ? 0 : -1);
			windowStamp = diagnosticsWorkBegin();
			int postResult = ANativeWindow_unlockAndPost(player->video.window);
			diagnosticsWorkEnd(player, DIAGNOSTICS_WORK_WINDOW_POST, windowStamp, postResult);
		}
	}
}

void * playerVideoDrawThread(void * data) {
	Player * player = (Player *) data;
	JNIEnv * env;
	(*playerGetJavaVM())->AttachCurrentThread(playerGetJavaVM(), &env, NULL);
	AVCodecContext * context = GET_CONTEXT(player, video);
	int lastWidth = player->video.lastBuffer.width > 0
			? player->video.lastBuffer.width : context->width;
	int lastHeight = player->video.lastBuffer.height > 0
			? player->video.lastBuffer.height : context->height;
	while (!player->meta.interrupt) {
		BufferItem * bufferItem = NULL;
		DiagnosticsWorkStamp drawWaitStamp = diagnosticsWorkBegin();
		pthread_mutex_lock(&player->video.queueMutex);
		while (!player->meta.interrupt && !bufferItem) {
			if (player->video.bufferQueue) {
				bufferItem = bufferQueueSeize(player->video.bufferQueue);
			}
			if (!bufferItem) {
				pthread_cond_wait(&player->video.queueCond, &player->video.queueMutex);
			}
		}
		playerSetSkipFlag(&player->sync.skip.drawWorkFrame, 0);
		pthread_mutex_unlock(&player->video.queueMutex);
		diagnosticsWorkEnd(player, DIAGNOSTICS_WORK_DRAW_WAIT, drawWaitStamp, 0);
		if (player->meta.interrupt) {
			goto SKIP_DRAW_FRAME;
		}

		pthread_mutex_lock(&player->play.finishMutex);
		while (!player->meta.interrupt && !playerVideoCanPresent(player)) {
			pthread_cond_wait(&player->play.finishCond, &player->play.finishMutex);
		}
		pthread_mutex_unlock(&player->play.finishMutex);
		if (player->meta.interrupt) {
			goto SKIP_DRAW_FRAME;
		}

		DiagnosticsWorkStamp drawLockStamp = diagnosticsWorkBegin();
		pthread_mutex_lock(&player->video.sleepDrawMutex);
		diagnosticsWorkEnd(player, DIAGNOSTICS_WORK_DRAW_LOCK, drawLockStamp, 0);
		if (playerGetSkipFlag(&player->sync.skip.drawWorkFrame)) {
			UNLOCK_AND_GOTO(&player->video.sleepDrawMutex, SKIP_DRAW_FRAME);
		}
		VideoFrameExtra * extra = bufferItem->extra;
		if (extra->outputEpoch != __atomic_load_n(&player->video.softwareOutputEpoch, __ATOMIC_ACQUIRE)) {
			diagnosticsPresentation(player, extra->position, DIAGNOSTICS_OUTPUT_DROPPED_STATE);
			UNLOCK_AND_GOTO(&player->video.sleepDrawMutex, SKIP_DRAW_FRAME);
		}
		int64_t position = calculatePosition(player, 1);
		int64_t waitTime = 0;
		int finishSeeking = 0;
		if (extra->position >= 0) {
			player->sync.videoPosition = extra->position;
			waitTime = extra->position - position;
			if (player->sync.videoPositionNotSync) {
				finishSeeking = 1;
				diagnosticsLog("player=%u seek_first_frame position_ms=%" PRId64
						" target_ms=%" PRId64 " hardware=0",
						player->meta.diagnosticsId, extra->position,
						player->sync.seekTargetPosition);
				// The old frame is still on screen until this one is drawn. Do not
				// schedule the first post-seek frame into the future or hide the busy
				// indicator before it actually replaces the old image.
				waitTime = 0;
			}
		}
		if (waitTime > 0) {
			DiagnosticsWorkStamp scheduleStamp = diagnosticsWorkBegin();
			LOG("sleep video %" PRId64 " %" PRId64 " %" PRId64, waitTime, player->sync.videoPosition, position);
			unsigned int revision = __atomic_load_n(&player->video.softwareRateRevision, __ATOMIC_ACQUIRE);
			int64_t deadline = calculateFrameTime(player, waitTime);
			while (!player->meta.interrupt && !playerGetSkipFlag(&player->sync.skip.drawWorkFrame)) {
				if (extra->outputEpoch != __atomic_load_n(&player->video.softwareOutputEpoch, __ATOMIC_ACQUIRE)) break;
				unsigned int currentRevision = __atomic_load_n(&player->video.softwareRateRevision, __ATOMIC_ACQUIRE);
				if (revision != currentRevision) {
					revision = currentRevision;
					waitTime = extra->position - calculatePosition(player, 1);
					if (waitTime <= 0) break;
					deadline = calculateFrameTime(player, waitTime);
				}
				// Keep the established scheduling margin for the chunked audio clock.
				// Only a rate revision changes this deadline, not a spurious wake.
				if (condSleepUntilMs(&player->video.sleepCond, &player->video.sleepDrawMutex, deadline)) break;
			}
			position = calculatePosition(player, 1);
			diagnosticsWorkEnd(player, DIAGNOSTICS_WORK_SCHEDULE_WAIT, scheduleStamp, 0);
			waitTime = extra->position >= 0 ? extra->position - position : 0;
		}
		if (playerGetSkipFlag(&player->sync.skip.drawWorkFrame)) {
			UNLOCK_AND_GOTO(&player->video.sleepDrawMutex, SKIP_DRAW_FRAME);
		}
		if (extra->outputEpoch != __atomic_load_n(&player->video.softwareOutputEpoch, __ATOMIC_ACQUIRE)) {
			diagnosticsPresentation(player, extra->position, DIAGNOSTICS_OUTPUT_DROPPED_STATE);
			UNLOCK_AND_GOTO(&player->video.sleepDrawMutex, SKIP_DRAW_FRAME);
		}
		// Drop an old output only when a newer, already-due output is available.
		// Lateness alone must never turn an overloaded decoder into a 5 FPS gate.
		int superseded = 0;
		pthread_mutex_lock(&player->video.queueMutex);
		QueueItem * next = player->video.bufferQueue ? player->video.bufferQueue->busyQueue.first : NULL;
		VideoFrameExtra * newer = next ? ((BufferItem *) next->data)->extra : NULL;
		if (newer && newer->outputEpoch == extra->outputEpoch && extra->position >= 0 &&
				newer->position > extra->position && newer->position <= position) superseded = 1;
		pthread_mutex_unlock(&player->video.queueMutex);
		if (!finishSeeking && superseded) {
			diagnosticsRecordSoftwareDrop(player, 0, extra->position, position, -waitTime);
			UNLOCK_AND_GOTO(&player->video.sleepDrawMutex, SKIP_DRAW_FRAME);
		}
		if (player->sync.audioPositionNotSync) {
			updateAudioPositionSurrogate(player, position, 0);
		} else {
			int64_t gaining = -waitTime;
			if (!HAS_STREAM(player, audio) && gaining > GAINING_THRESHOLD) {
				player->sync.startTime += gaining;
			}
		}
		LOG("draw video %" PRId64, player->sync.videoPosition);
		int bufferSize = bufferItem->dataSize;
		if (bufferSize <= 0 || bufferSize > bufferItem->bufferSize) {
			UNLOCK_AND_GOTO(&player->video.sleepDrawMutex, SKIP_DRAW_FRAME);
		}
		DiagnosticsWorkStamp lastCopyStamp = diagnosticsWorkBegin();
		if (bufferSize > player->video.lastBuffer.size) {
			player->video.lastBuffer.data = realloc(player->video.lastBuffer.data, bufferSize);
			player->video.lastBuffer.size = bufferSize;
		}
		memcpy(player->video.lastBuffer.data, bufferItem->buffer, bufferSize);
		diagnosticsWorkEnd(player, DIAGNOSTICS_WORK_LAST_FRAME_COPY, lastCopyStamp, 0);
		player->video.lastBuffer.dataSize = bufferSize;
		player->video.lastBuffer.width = extra->width;
		player->video.lastBuffer.height = extra->height;
		player->video.lastBuffer.frameGeneration++;
		int rendered = 0;
		if (finishSeeking || getTime() * MAX_FPS / 1000 > player->sync.lastDrawTimes[0] * MAX_FPS / 1000) {
			// Avoid FPS > MAX_FPS
			drawWindow(player, bufferItem->buffer, extra->width, extra->height,
					lastWidth, lastHeight);
			rendered = 1;
			diagnosticsIncrement(PLAYER_DIAGNOSTICS_SOFTWARE_RENDERED);
			diagnosticsPresentation(player, extra->position, DIAGNOSTICS_OUTPUT_IMMEDIATE);
			lastWidth = extra->width;
			lastHeight = extra->height;
			player->sync.lastDrawTimes[1] = player->sync.lastDrawTimes[0];
			player->sync.lastDrawTimes[0] = getTime();
		}
		if (!rendered) diagnosticsPresentation(player, extra->position, DIAGNOSTICS_OUTPUT_DROPPED_CADENCE);
		if (finishSeeking && rendered) {
			player->sync.videoPositionNotSync = 0;
			playerVideoCompletePausedSeekFrame(player);
			diagnosticsLog("player=%u seek_first_frame_rendered position_ms=%" PRId64 " hardware=0",
					player->meta.diagnosticsId, extra->position);
			Bridge * bridge = playerObtainBridge(player, env);
			PLAYER_SEND_MESSAGE(env, player, bridge, BRIDGE_MESSAGE_END_SEEKING);
			pthread_mutex_unlock(&player->video.sleepDrawMutex);
			condBroadcastLocked(&player->audio.sleepCond, &player->audio.sleepBufferMutex);
			pthread_mutex_lock(&player->video.sleepDrawMutex);
		}
		pthread_mutex_unlock(&player->video.sleepDrawMutex);

		SKIP_DRAW_FRAME:
		if (bufferItem) {
			free(bufferItem->extra);
			bufferItem->extra = NULL;
			pthread_mutex_lock(&player->video.queueMutex);
			bufferQueueRelease(player->video.bufferQueue, bufferItem);
			pthread_cond_broadcast(&player->video.queueCond);
			pthread_mutex_unlock(&player->video.queueMutex);
			playerMarkStreamFinished(player, 1);
		}
	}
	(*playerGetJavaVM())->DetachCurrentThread(playerGetJavaVM());
	return NULL;
}

static void extendScaleHolder(ScaleHolder * scaleHolder, int bufferSize, int width, int height,
		int bytesPerPixel, int isYUV) {
	if (bufferSize > scaleHolder->bufferSize) {
		scaleHolder->bufferSize = bufferSize;
		if (scaleHolder->scaleBuffer) {
			av_free(scaleHolder->scaleBuffer);
		}
		scaleHolder->scaleBuffer = av_malloc(bufferSize);
	}
	size_t lumaSize = (size_t) width * (size_t) height;
	scaleHolder->scaleData[0] = scaleHolder->scaleBuffer;
	scaleHolder->scaleData[1] = isYUV ? scaleHolder->scaleBuffer + lumaSize + lumaSize / 4 : NULL;
	scaleHolder->scaleData[2] = isYUV ? scaleHolder->scaleBuffer + lumaSize : NULL;
	scaleHolder->scaleData[3] = NULL;
	scaleHolder->scaleLinesize[0] = bytesPerPixel * width;
	scaleHolder->scaleLinesize[1] = isYUV ? width / 2 : 0;
	scaleHolder->scaleLinesize[2] = isYUV ? width / 2 : 0;
	scaleHolder->scaleLinesize[3] = 0;
}

void * playerVideoDecodeThread(void * data) {
	Player * player = (Player *) data;
	JNIEnv * env;
	(*playerGetJavaVM())->AttachCurrentThread(playerGetJavaVM(), &env, NULL);
	AVStream * stream = GET_STREAM(player, video);
	while (!player->meta.interrupt && !player->video.bufferQueue && !player->video.hardwareDecoderActive) {
		if (playerVideoHasPendingSurface(player)) {
			playerVideoApplyPendingSurface(player, env);
			continue;
		}
		pthread_mutex_lock(&player->video.sleepDrawMutex);
		if (!player->meta.interrupt && !player->video.bufferQueue
				&& !player->video.hardwareDecoderActive && !playerVideoHasPendingSurface(player)) {
			pthread_cond_wait(&player->video.sleepCond, &player->video.sleepDrawMutex);
		}
		pthread_mutex_unlock(&player->video.sleepDrawMutex);
	}
	if (player->meta.interrupt) {
		(*playerGetJavaVM())->DetachCurrentThread(playerGetJavaVM());
		return NULL;
	}
#ifdef DASHCHAN_HAS_MEDIACODEC
	if (player->video.hardwareDecoderActive) {
		playerVideoDecodeMediaCodec(player, env, stream);
		if (player->meta.interrupt || player->video.hardwareDecoderActive) {
			(*playerGetJavaVM())->DetachCurrentThread(playerGetJavaVM());
			return NULL;
		}
	}
#endif

	AVCodecContext * context = GET_CONTEXT(player, video);
	int bytesPerPixel = getBytesPerPixel(player->video.format);
	pthread_mutex_lock(&player->decode.video.frameMutex);
	playerVideoSoftwareResetGovernorLocked(player, context, "decoder_start");
	pthread_mutex_unlock(&player->decode.video.frameMutex);
	diagnosticsLog("player=%u software_policy controller=1 budgets=wall_clock max_output_fps=%d"
			" enter_ms=%d persist_ms=%d recover_ms=%d stable_ms=%d fixed_late_gate=0",
			player->meta.diagnosticsId, MAX_FPS, VIDEO_LOAD_ENTER_MS, VIDEO_LOAD_ENTER_PERSIST_MS,
			VIDEO_LOAD_RECOVER_MS, VIDEO_LOAD_STABLE_MS);
	int isYUV = player->video.format == AV_PIX_FMT_YUV420P;
	AVFrame * frame = av_frame_alloc();
	ScaleHolder scaleHolder;
	scaleHolder.bufferSize = 0;
	scaleHolder.scaleBuffer = NULL;
	int lastSourceWidth = context->width;
	int lastSourceHeight = context->height;
	int lastOutputWidth;
	int lastOutputHeight;
	calculateSoftwareOutputSize(player, lastSourceWidth, lastSourceHeight,
			&lastOutputWidth, &lastOutputHeight);
	int initialBufferSize = getVideoBufferSize(player->video.format,
			lastOutputWidth, lastOutputHeight);
	extendScaleHolder(&scaleHolder, initialBufferSize, lastOutputWidth, lastOutputHeight,
			bytesPerPixel, isYUV);
	struct SwsContext * scaleContext = NULL;
	PacketHolder * packetHolder = NULL;

	int totalMeasurements = 10;
	int currentMeasurement = 0;
	int measurements[2 * totalMeasurements];

	while (!player->meta.interrupt) {
		if (playerVideoHasPendingSurface(player)) {
			playerVideoApplyPendingSurface(player, env);
			continue;
		}
		DiagnosticsWorkStamp packetStamp = diagnosticsWorkBegin();
		packetHolder = (PacketHolder *) blockingQueueGet(&player->video.packetQueue, 1);
		diagnosticsWorkEnd(player, DIAGNOSTICS_WORK_PACKET_WAIT, packetStamp, 0);
		if (packetHolder && packetHolder->type == PACKET_HOLDER_SURFACE_REQUEST) {
			playerPacketQueueFreeCallback(packetHolder);
			packetHolder = NULL;
			playerVideoApplyPendingSurface(player, env);
			continue;
		}
		if (playerGetSkipFlag(&player->sync.skip.videoWorkFrame)) {
			playerSetSkipFlag(&player->sync.skip.videoWorkFrame, 0);
		}
		if (!packetHolder || player->meta.interrupt) {
			break;
		}
		condBroadcastLocked(&player->decode.packets.flowCond, &player->decode.packets.flowMutex);
		if (player->meta.interrupt) {
			break;
		}

		pthread_mutex_lock(&player->play.finishMutex);
		while (!player->meta.interrupt && !playerVideoCanDecode(player)
				&& !playerVideoHasPendingSurface(player)) {
			pthread_cond_wait(&player->play.finishCond, &player->play.finishMutex);
		}
		pthread_mutex_unlock(&player->play.finishMutex);
		if (player->meta.interrupt) {
			break;
		}
		if (playerVideoHasPendingSurface(player)) {
			playerPacketQueueFreeCallback(packetHolder);
			packetHolder = NULL;
			playerVideoApplyPendingSurface(player, env);
			continue;
		}

		int packetSent = 0;
		while (1) {
			int success = 0;
			int pausedSeekFrameQueued = 0;
			VideoFrameExtra * extra = NULL;
			int64_t decodedFramePosition = -1;
			unsigned int outputEpoch = 0;
			if (playerGetSkipFlag(&player->sync.skip.videoWorkFrame)) {
				goto SKIP_VIDEO_FRAME;
			}
			DiagnosticsWorkStamp decodeLockStamp = diagnosticsWorkBegin();
			pthread_mutex_lock(&player->decode.video.frameMutex);
			diagnosticsWorkEnd(player, DIAGNOSTICS_WORK_DECODE_LOCK, decodeLockStamp, 0);
			if (playerGetSkipFlag(&player->sync.skip.videoWorkFrame)) {
				UNLOCK_AND_GOTO(&player->decode.video.frameMutex, SKIP_VIDEO_FRAME);
			}
			if (packetHolder->generation != __atomic_load_n(&player->decode.packets.generation, __ATOMIC_ACQUIRE)) {
				UNLOCK_AND_GOTO(&player->decode.video.frameMutex, SKIP_VIDEO_FRAME);
			}
			if (player->video.softwareObservedRateRevision !=
					__atomic_load_n(&player->video.softwareRateRevision, __ATOMIC_ACQUIRE)) {
				playerVideoSoftwareResetGovernorLocked(player, context, "playback_speed");
			}
			if (!packetSent) {
				catchUpSoftwareDecoderLocked(player, context, stream, &packetHolder, frame);
				updateSoftwareSeekFastDecodeForPacketLocked(player, context, stream,
						packetHolder->packet);
			}
			DiagnosticsWorkStamp decodeStamp = diagnosticsWorkBegin();
			int ready = playerDecodeFrame(player, 1, context, packetHolder, frame, &packetSent);
			diagnosticsWorkEnd(player, DIAGNOSTICS_WORK_DECODE, decodeStamp, 0);
			if (ready) {
				outputEpoch = __atomic_load_n(&player->video.softwareOutputEpoch, __ATOMIC_ACQUIRE);
				decodedFramePosition = getFramePositionMs(player, frame, stream);
				if (player->video.softwareSeekFastActive) {
					player->video.softwareSeekFastFrames++;
					if (decodedFramePosition >= player->video.softwareSeekFastRestorePosition) {
						playerVideoSoftwareRestoreSeekFastLocked(player, context,
								"decoded_restore_margin", decodedFramePosition);
					}
				}
			}
			pthread_mutex_unlock(&player->decode.video.frameMutex);
			if (!ready) {
				break;
			}

			if (ready) {
				extra = malloc(sizeof(VideoFrameExtra));
				extra->outputEpoch = outputEpoch;
				extra->position = decodedFramePosition;
				diagnosticsIncrement(PLAYER_DIAGNOSTICS_SOFTWARE_DECODED);
				LOG("video frame pts=%" PRId64 " best=%" PRId64 " pkt_dts=%" PRId64
						" pos=%" PRId64 " tb=%d/%d", frame->pts, frame->best_effort_timestamp,
						frame->pkt_dts, extra->position, stream->time_base.num, stream->time_base.den);
				if (extra->position >= 0 && player->sync.seekDiscardBeforeTarget &&
						player->sync.videoPositionNotSync &&
						extra->position < player->sync.videoPosition) {
					success = 1;
					goto SKIP_VIDEO_FRAME;
				}
				if (playerGetSkipFlag(&player->sync.skip.videoWorkFrame)) {
					goto SKIP_VIDEO_FRAME;
				}

				int64_t playbackPosition = calculatePosition(player, 1);
				int64_t lateness = extra->position >= 0 ? playbackPosition - extra->position : 0;
				int canDropLate = extra->position >= 0 && HAS_STREAM(player, audio) &&
						!player->sync.audioPositionNotSync && !player->sync.videoPositionNotSync;
				diagnosticsSoftwareFrame(player, extra->position, lateness, canDropLate,
						frame->width, frame->height, 0, 0, 0, player->video.softwareOutputLevel);
				updateSoftwareLoadController(player, canDropLate, extra->position, lateness);
				pthread_mutex_lock(&player->decode.video.frameMutex);
				VideoLoadControl * control = &player->video.softwareLoad;
				int64_t slot = hardwareOutputSlot(extra->position, getPlaybackSpeed(player));
				int cadenceDrop = canDropLate && slot >= 0 && slot == control->outputSlot;
				if (cadenceDrop) control->cadenceDrops++;
				else control->outputSlot = slot;
				pthread_mutex_unlock(&player->decode.video.frameMutex);
				if (cadenceDrop) {
					diagnosticsPresentation(player, extra->position, DIAGNOSTICS_OUTPUT_DROPPED_CADENCE);
					success = 1;
					goto SKIP_VIDEO_FRAME;
				}

				int outputWidth;
				int outputHeight;
				calculateSoftwareOutputSize(player, frame->width, frame->height,
						&outputWidth, &outputHeight);
				int outputBufferSize = getVideoBufferSize(player->video.format,
						outputWidth, outputHeight);
				if (outputBufferSize <= 0) {
					goto SKIP_VIDEO_FRAME;
				}
				extra->width = outputWidth;
				extra->height = outputHeight;
				int sourceChanged = lastSourceWidth != frame->width ||
						lastSourceHeight != frame->height;
				int outputChanged = sourceChanged || lastOutputWidth != outputWidth ||
						lastOutputHeight != outputHeight;
				if (outputChanged) {
					extendScaleHolder(&scaleHolder, outputBufferSize, outputWidth, outputHeight,
							bytesPerPixel, isYUV);
					diagnosticsLog("player=%u software_output source=%dx%d surface=%dx%d"
							" output=%dx%d level=%s buffer_bytes=%d",
							player->meta.diagnosticsId, frame->width, frame->height,
							__atomic_load_n(&player->video.surfaceWidth, __ATOMIC_ACQUIRE),
							__atomic_load_n(&player->video.surfaceHeight, __ATOMIC_ACQUIRE),
							outputWidth, outputHeight,
							player->video.softwareOutputLevel > 0 ? "hd" : "fhd",
							outputBufferSize);
					lastSourceWidth = frame->width;
					lastSourceHeight = frame->height;
					lastOutputWidth = outputWidth;
					lastOutputHeight = outputHeight;
					if (sourceChanged) {
						Bridge * bridge = playerObtainBridge(player, env);
						PLAYER_SEND_MESSAGE(env, player, bridge, BRIDGE_MESSAGE_SIZE_CHANGED);
					}
				}
				int useLibyuv = frame->format == AV_PIX_FMT_YUV420P &&
						player->video.format == AV_PIX_FMT_RGBA && frame->width == outputWidth &&
						frame->height == outputHeight;
				DiagnosticsWorkStamp convertStamp = diagnosticsWorkBegin();
				uint64_t conversionStartedAt = getTimeUs();
				uint64_t measurementStartedAt = 0;
				if (useLibyuv) {
					if (player->video.useLibyuv >= 0) {
						useLibyuv = player->video.useLibyuv;
					} else {
						if (currentMeasurement < totalMeasurements) {
							useLibyuv = 0;
						}
						if (currentMeasurement < 2 * totalMeasurements) {
							measurementStartedAt = getTimeUs();
						}
					}
				}
				int conversionResult;
				if (useLibyuv) {
					conversionResult = I420ToABGR(frame->data[0], frame->linesize[0], frame->data[1], frame->linesize[1],
							frame->data[2], frame->linesize[2], scaleHolder.scaleBuffer, 4 * outputWidth,
							outputWidth, outputHeight);
				} else {
					scaleContext = sws_getCachedContext(scaleContext,
							frame->width, frame->height, frame->format,
							outputWidth, outputHeight, player->video.format,
							SWS_FAST_BILINEAR, NULL, NULL, NULL);
					if (!scaleContext) {
						diagnosticsWorkEnd(player, DIAGNOSTICS_WORK_CONVERT, convertStamp, -1);
						goto SKIP_VIDEO_FRAME;
					}
					conversionResult = sws_scale(scaleContext, (uint8_t const * const *) frame->data, frame->linesize,
							0, frame->height, scaleHolder.scaleData, scaleHolder.scaleLinesize);
				}
				int64_t conversionTime = getTimeUs() - conversionStartedAt;
				if (measurementStartedAt != 0) {
					if (currentMeasurement < 2 * totalMeasurements) {
						measurements[currentMeasurement++] = (int) (getTimeUs() - measurementStartedAt);
						if (currentMeasurement == 2 * totalMeasurements) {
							int avg1 = 0;
							int avg2 = 0;
							for (int i = 0; i < totalMeasurements; i++) {
								avg1 += measurements[i];
							}
							for (int i = totalMeasurements; i < 2 * totalMeasurements; i++) {
								avg2 += measurements[i];
							}
							player->video.useLibyuv = avg2 <= avg1 ? 1 : 0;
						}
					}
				}
				// Do not include diagnostic bookkeeping in the existing converter-selection benchmark.
				diagnosticsWorkEnd(player, DIAGNOSTICS_WORK_CONVERT, convertStamp, conversionResult);
				diagnosticsSoftwareFrame(player, extra->position, lateness, canDropLate,
						frame->width, frame->height, outputWidth, outputHeight, useLibyuv,
						player->video.softwareOutputLevel);
				if (conversionTime >= SOFTWARE_GOVERNOR_SLOW_CONVERSION_US) {
					player->video.softwareSlowConversions++;
				} else if (player->video.softwareSlowConversions > 0) {
					player->video.softwareSlowConversions--;
				}
				if (player->video.softwareOutputLevel == 0 &&
						player->video.softwareSlowConversions >= SOFTWARE_GOVERNOR_SLOW_CONVERSIONS) {
					player->video.softwareOutputLevel = 1;
					diagnosticsIncrement(PLAYER_DIAGNOSTICS_SOFTWARE_OUTPUT_DOWNGRADE);
					diagnosticsLog("player=%u software_governor output_level=hd"
							" conversion_us=%" PRId64 " slow_frames=%d",
							player->meta.diagnosticsId, conversionTime,
							player->video.softwareSlowConversions);
				}

				DiagnosticsWorkStamp queueStamp = diagnosticsWorkBegin();
				pthread_mutex_lock(&player->video.queueMutex);
				if (playerGetSkipFlag(&player->sync.skip.videoWorkFrame)) {
					diagnosticsWorkEnd(player, DIAGNOSTICS_WORK_QUEUE_WAIT, queueStamp, 0);
					UNLOCK_AND_GOTO(&player->video.queueMutex, SKIP_VIDEO_FRAME);
				}
				bufferQueueExtend(player->video.bufferQueue, outputBufferSize);
				BufferItem * bufferItem = NULL;
				while (!player->meta.interrupt && !playerGetSkipFlag(&player->sync.skip.videoWorkFrame)
						&& !bufferItem) {
					bufferItem = bufferQueuePrepare(player->video.bufferQueue);
					if (!bufferItem) {
						pthread_cond_wait(&player->video.queueCond, &player->video.queueMutex);
					}
				}
				diagnosticsWorkEnd(player, DIAGNOSTICS_WORK_QUEUE_WAIT, queueStamp, 0);
				if (bufferItem) {
					if (decodedFramePosition >= 0 && player->sync.videoPositionNotSync) {
						pausedSeekFrameQueued = playerVideoMarkPausedSeekFrameQueued(player);
					}
					DiagnosticsWorkStamp copyStamp = diagnosticsWorkBegin();
					memcpy(bufferItem->buffer, scaleHolder.scaleBuffer, outputBufferSize);
					diagnosticsWorkEnd(player, DIAGNOSTICS_WORK_QUEUE_COPY, copyStamp, 0);
					bufferItem->dataSize = outputBufferSize;
					bufferItem->extra = extra;
					extra = NULL;
					bufferQueueAdd(player->video.bufferQueue, bufferItem);
					pthread_cond_broadcast(&player->video.queueCond);
					success = 1;
				}
				pthread_mutex_unlock(&player->video.queueMutex);
			}

			SKIP_VIDEO_FRAME:
			if (extra) {
				free(extra);
			}
			if (!success) {
				break;
			}
			if (pausedSeekFrameQueued) {
				diagnosticsLog("player=%u paused_seek_preview frame_queued position_ms=%" PRId64,
						player->meta.diagnosticsId, decodedFramePosition);
				break;
			}
		}
		playerMarkStreamFinished(player, 1);
		playerPacketQueueFreeCallback(packetHolder);
		packetHolder = NULL;
	}
	if (packetHolder) {
		playerPacketQueueFreeCallback(packetHolder);
	}
	sws_freeContext(scaleContext);
	av_free(scaleHolder.scaleBuffer);
	av_frame_free(&frame);
	(*playerGetJavaVM())->DetachCurrentThread(playerGetJavaVM());
	return NULL;
}

int playerVideoSoftwareGetFormat(int windowFormat) {
	switch (windowFormat) {
		case WINDOW_FORMAT_RGBA_8888:
		case WINDOW_FORMAT_RGBX_8888: return AV_PIX_FMT_RGBA;
		case WINDOW_FORMAT_RGB_565: return AV_PIX_FMT_RGB565LE;
		case WINDOW_FORMAT_YV12: return AV_PIX_FMT_YUV420P;
		default: return -1;
	}
}

int playerVideoSoftwarePrepareOutputLocked(Player * player) {
	if (!player->video.window) {
		return 0;
	}
	int windowFormat = ANativeWindow_getFormat(player->video.window);
	int videoFormat = playerVideoSoftwareGetFormat(windowFormat);
	if (videoFormat < 0) {
		return 0;
	}
	AVCodecContext * context = GET_CONTEXT(player, video);
	int sourceWidth = context->width;
	int sourceHeight = context->height;
	int width;
	int height;
	calculateSoftwareOutputSize(player, sourceWidth, sourceHeight, &width, &height);
	if (!player->video.bufferQueue) {
		int videoBufferSize = getVideoBufferSize(videoFormat, width, height);
		if (videoBufferSize <= 0) {
			return 0;
		}
		player->video.format = videoFormat;
		player->video.bufferQueue = malloc(sizeof(BufferQueue));
		bufferQueueInit(player->video.bufferQueue, videoBufferSize, 3);
		player->video.lastBuffer.data = malloc(videoBufferSize);
		player->video.lastBuffer.size = videoBufferSize;
		player->video.lastBuffer.dataSize = videoBufferSize;
		player->video.lastBuffer.width = width;
		player->video.lastBuffer.height = height;
		if (videoFormat == AV_PIX_FMT_RGBA) {
			// RGBA_8888 "black" buffer
			int count = videoBufferSize;
			memset(player->video.lastBuffer.data, 0x00, count);
			for (int i = 3; i < count; i += 4) {
				player->video.lastBuffer.data[i] = 0xff;
			}
		} else if (videoFormat == AV_PIX_FMT_RGB565LE) {
			// RGB_565 "black" buffer
			memset(player->video.lastBuffer.data, 0x00, (size_t) videoBufferSize);
		} else if (videoFormat == AV_PIX_FMT_YUV420P) {
			// YV12 "black" buffer
			size_t lumaSize = (size_t) width * (size_t) height;
			memset(player->video.lastBuffer.data, 0, lumaSize);
			memset(player->video.lastBuffer.data + lumaSize, 0x7f,
					(size_t) videoBufferSize - lumaSize);
		}
		pthread_cond_broadcast(&player->video.sleepCond);
		diagnosticsLog("player=%u software_output source=%dx%d surface=%dx%d"
				" output=%dx%d level=fhd buffer_bytes=%d initialized=1",
				player->meta.diagnosticsId, sourceWidth, sourceHeight,
				__atomic_load_n(&player->video.surfaceWidth, __ATOMIC_ACQUIRE),
				__atomic_load_n(&player->video.surfaceHeight, __ATOMIC_ACQUIRE),
				width, height, videoBufferSize);
	}
	if (player->video.lastBuffer.width >= 0) {
		width = player->video.lastBuffer.width;
	}
	if (player->video.lastBuffer.height >= 0) {
		height = player->video.lastBuffer.height;
	}
	ANativeWindow_setBuffersGeometry(player->video.window, width, height, windowFormat);
	if (player->video.lastBuffer.data) {
		drawWindow(player, player->video.lastBuffer.data, width, height, width, height);
	}
	return 1;
}


#ifdef DASHCHAN_HAS_MEDIACODEC
AVCodecContext * playerVideoSoftwareCreateCodecContext(Player * player) {
	AVStream * stream = GET_STREAM(player, video);
	const AVCodec * codec = avcodec_find_decoder(stream->codecpar->codec_id);
	if (!codec) {
		return NULL;
	}
	AVCodecContext * context = avcodec_alloc_context3(codec);
	if (!context || avcodec_parameters_to_context(context, stream->codecpar) != 0) {
		playerCloseAndFreeCodecContext(&context);
		return NULL;
	}
	context->pkt_timebase = stream->time_base;
	playerVideoSoftwareConfigureThreads(context, codec);
	if (avcodec_open2(context, codec, NULL) < 0) {
		playerCloseAndFreeCodecContext(&context);
		return NULL;
	}
	return context;
}

#endif
jintArray getCurrentFrame(JNIEnv * env, jlong pointer, jintArray dimensions) {
	Player * player = POINTER_CAST(pointer);
	pthread_mutex_lock(&player->video.sleepDrawMutex);
	if (player->video.lastBuffer.frameGeneration == 0) {
		pthread_mutex_unlock(&player->video.sleepDrawMutex);
		return 0;
	}
	uint8_t * buffer = player->video.lastBuffer.data;
	int sourceWidth = player->video.lastBuffer.width;
	int sourceHeight = player->video.lastBuffer.height;
	int destWidth = sourceWidth;
	int destHeight = sourceHeight;
	int maxDimension = 1000;
	if (destWidth > maxDimension || destHeight > maxDimension) {
		int sampleHorizontal = (destWidth + maxDimension - 1) / maxDimension;
		int sampleVertical = (destHeight + maxDimension - 1) / maxDimension;
		int sample = sampleHorizontal > sampleVertical ? sampleHorizontal : sampleVertical;
		if (sample >= 2) {
			destWidth = (destWidth + sample - 1) / sample;
			destHeight = (destHeight + sample - 1) / sample;
		}
	}
	(*env)->SetIntArrayRegion(env, dimensions, 0, 1, &destWidth);
	(*env)->SetIntArrayRegion(env, dimensions, 1, 1, &destHeight);
	jintArray result = 0;
	int success = 0;
	if (buffer != 0 && destWidth > 0 && destHeight > 0) {
		if (player->video.format != AV_PIX_FMT_RGB565LE && player->video.format != AV_PIX_FMT_YUV420P
				&& player->video.format != AV_PIX_FMT_RGBA) {
			goto RESULT;
		}
		result = (*env)->NewIntArray(env, destWidth * destHeight);
		if (!result) {
			goto RESULT;
		}
		struct SwsContext * scaleContext = sws_getContext(sourceWidth, sourceHeight, player->video.format,
				destWidth, destHeight, AV_PIX_FMT_BGRA, SWS_FAST_BILINEAR, NULL, NULL, NULL);
		if (!scaleContext) {
			goto RESULT;
		}
		uint8_t * newBuffer = (*env)->GetPrimitiveArrayCritical(env, result, NULL);
		if (!newBuffer) {
			goto SWS_FREE_CONTEXT;
		}
		uint8_t * newData[4] = {newBuffer, 0, 0, 0};
		int newLinesize[4] = {4 * destWidth, 0, 0, 0};
		if (player->video.format == AV_PIX_FMT_RGBA) {
			if (player->video.lastBuffer.dataSize < 4 * sourceWidth * sourceHeight) {
				goto RELEASE_PRIMITIVE_ARRAY;
			}
			const uint8_t * const oldData[4] = {buffer, 0, 0, 0};
			int oldLinesize[4] = {4 * sourceWidth, 0, 0, 0};
			sws_scale(scaleContext, oldData, oldLinesize, 0, sourceHeight, newData, newLinesize);
		} else if (player->video.format == AV_PIX_FMT_RGB565LE) {
			if (player->video.lastBuffer.dataSize < 2 * sourceWidth * sourceHeight) {
				goto RELEASE_PRIMITIVE_ARRAY;
			}
			const uint8_t * const oldData[4] = {buffer, 0, 0, 0};
			int oldLinesize[4] = {2 * sourceWidth, 0, 0, 0};
			sws_scale(scaleContext, oldData, oldLinesize, 0, sourceHeight, newData, newLinesize);
		} else if (player->video.format == AV_PIX_FMT_YUV420P) {
			if (player->video.lastBuffer.dataSize < sourceWidth * sourceHeight * 3 / 2) {
				goto RELEASE_PRIMITIVE_ARRAY;
			}
			const uint8_t * const oldData[4] = {buffer, buffer + sourceWidth * sourceHeight +
					sourceWidth * sourceHeight / 4, buffer + sourceWidth * sourceHeight, 0};
			int oldLinesize[4] = {sourceWidth, sourceWidth / 2, sourceWidth / 2, 0};
			sws_scale(scaleContext, oldData, oldLinesize, 0, sourceHeight, newData, newLinesize);
		}
		success = 1;
		RELEASE_PRIMITIVE_ARRAY:
		(*env)->ReleasePrimitiveArrayCritical(env, result, newBuffer, 0);
		SWS_FREE_CONTEXT:
		sws_freeContext(scaleContext);
	}
	RESULT:
	pthread_mutex_unlock(&player->video.sleepDrawMutex);
	if (!success && result) {
		(*env)->DeleteLocalRef(env, result);
		result = 0;
	}
	return result;
}
