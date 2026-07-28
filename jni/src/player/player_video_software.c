#include "player_video_software.h"
#include "player.h"
#include "player_diagnostics.h"
#include "player_timing.h"
#include "player_video_mediacodec.h"
#include "util.h"
#include <libswscale/swscale.h>
#include <libavutil/imgutils.h>
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
#define SOFTWARE_LATE_DROP_THRESHOLD_MS 100
#define SOFTWARE_GOVERNOR_LATE_THRESHOLD_MS 250
#define SOFTWARE_GOVERNOR_LATE_FRAMES 8
#define SOFTWARE_GOVERNOR_RECOVERY_THRESHOLD_MS 80
#define SOFTWARE_GOVERNOR_RECOVERY_FRAMES 6
#define SOFTWARE_GOVERNOR_MIN_DISCARD_MS 500
#define SOFTWARE_GOVERNOR_SLOW_CONVERSION_US 25000
#define SOFTWARE_GOVERNOR_SLOW_CONVERSIONS 6
#define SOFTWARE_LATE_ANCHOR_INTERVAL_MS 200
#define SOFTWARE_SEEK_FAST_MIN_GAP_MS 600
#define SOFTWARE_SEEK_FAST_RESTORE_MARGIN_MS 500
#define MAX_FPS 60

// Callers hold decode.video.frameMutex. The normal late-frame governor may
// already require non-reference discard, so seek acceleration always restores
// the decoder to that session baseline instead of unconditionally disabling it.
static enum AVDiscard getSoftwareFrameDiscardBaseline(Player * player) {
	return player->video.softwareDecoderDiscardActive ? AVDISCARD_NONREF : AVDISCARD_DEFAULT;
}

// Callers hold decode.video.frameMutex. Non-reference discard is a temporary
// catch-up mode, not a permanent quality setting. Keep seek acceleration and
// the normal playback governor independent so either one can restore its own
// state without accidentally disabling the other.
static void setSoftwareDecoderDiscardLocked(Player * player, AVCodecContext * context,
		int active, const char * reason, int64_t lateness) {
	active = !!active;
	if (player->video.softwareDecoderDiscardActive == active) {
		return;
	}
	if (active) {
		player->video.softwareDecoderDiscardActive = 1;
		player->video.softwareDecoderDiscardStartedAt = getTime();
		player->video.softwareConsecutiveLateFrames = 0;
		player->video.softwareConsecutiveRecoveryFrames = 0;
		context->skip_frame = AVDISCARD_NONREF;
		diagnosticsIncrement(PLAYER_DIAGNOSTICS_SOFTWARE_DECODER_DISCARD_ENABLED);
		diagnosticsLog("player=%u software_governor decoder_discard=nonref"
				" reason=%s late_ms=%" PRId64,
				player->meta.diagnosticsId, reason, lateness);
	} else {
		int64_t activeTime = player->video.softwareDecoderDiscardStartedAt > 0
				? getTime() - player->video.softwareDecoderDiscardStartedAt : 0;
		player->video.softwareDecoderDiscardActive = 0;
		player->video.softwareDecoderDiscardStartedAt = 0;
		player->video.softwareConsecutiveLateFrames = 0;
		player->video.softwareConsecutiveRecoveryFrames = 0;
		context->skip_frame = player->video.softwareSeekFastActive
				? AVDISCARD_NONREF : AVDISCARD_DEFAULT;
		diagnosticsIncrement(PLAYER_DIAGNOSTICS_SOFTWARE_DECODER_DISCARD_RESTORED);
		diagnosticsLog("player=%u software_governor decoder_discard=default"
				" reason=%s late_ms=%" PRId64 " active_ms=%" PRId64
				" seek_fast=%d", player->meta.diagnosticsId, reason, lateness,
				activeTime, player->video.softwareSeekFastActive);
	}
}

// Callers hold decode.video.frameMutex.
void playerVideoSoftwareResetGovernorLocked(Player * player, AVCodecContext * context,
		const char * reason) {
	player->video.softwareConsecutiveLateFrames = 0;
	player->video.softwareConsecutiveRecoveryFrames = 0;
	__atomic_store_n(&player->video.softwareLastFrameQueuedAt, 0, __ATOMIC_RELEASE);
	if (player->video.softwareDecoderDiscardActive) {
		setSoftwareDecoderDiscardLocked(player, context, 0, reason, 0);
	} else if (!player->video.softwareSeekFastActive) {
		context->skip_frame = AVDISCARD_DEFAULT;
	}
}

static void updateSoftwareDecoderGovernor(Player * player, AVCodecContext * context,
		int synchronized, int64_t lateness) {
	pthread_mutex_lock(&player->decode.video.frameMutex);
	if (!synchronized || !player->play.playing) {
		player->video.softwareConsecutiveLateFrames = 0;
		player->video.softwareConsecutiveRecoveryFrames = 0;
		pthread_mutex_unlock(&player->decode.video.frameMutex);
		return;
	}
	if (!player->video.softwareDecoderDiscardActive) {
		if (lateness > SOFTWARE_GOVERNOR_LATE_THRESHOLD_MS) {
			player->video.softwareConsecutiveLateFrames++;
		} else {
			player->video.softwareConsecutiveLateFrames = 0;
		}
		if (player->video.softwareConsecutiveLateFrames >= SOFTWARE_GOVERNOR_LATE_FRAMES) {
			setSoftwareDecoderDiscardLocked(player, context, 1, "sustained_lateness", lateness);
		}
	} else {
		if (lateness <= SOFTWARE_GOVERNOR_RECOVERY_THRESHOLD_MS) {
			player->video.softwareConsecutiveRecoveryFrames++;
		} else {
			player->video.softwareConsecutiveRecoveryFrames = 0;
		}
		int64_t activeTime = player->video.softwareDecoderDiscardStartedAt > 0
				? getTime() - player->video.softwareDecoderDiscardStartedAt : 0;
		int caughtUp = player->video.softwareConsecutiveRecoveryFrames >=
				SOFTWARE_GOVERNOR_RECOVERY_FRAMES;
		if (activeTime >= SOFTWARE_GOVERNOR_MIN_DISCARD_MS && caughtUp) {
			setSoftwareDecoderDiscardLocked(player, context, 0, "caught_up", lateness);
		}
	}
	pthread_mutex_unlock(&player->decode.video.frameMutex);
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
	if (player->video.hardwareDecoderActive || player->video.softwareDecoderDiscardActive ||
			gap < SOFTWARE_SEEK_FAST_MIN_GAP_MS) {
		diagnosticsLog("player=%u software_seek_fast skipped keyframe_ms=%" PRId64
				" target_ms=%" PRId64 " gap_ms=%" PRId64 " hardware=%d baseline=%s",
				player->meta.diagnosticsId, keyframePosition, targetPosition, gap,
				player->video.hardwareDecoderActive,
				player->video.softwareDecoderDiscardActive ? "nonref" : "default");
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
		if (ANativeWindow_lock(player->video.window, &canvas, NULL) == 0) {
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
						for (int i = 0; i < height; i++) {
							memcpy(to, buffer, bytesPerPixel * width);
							to += bytesPerPixel * canvas.stride;
							buffer += bytesPerPixel * width;
						}
					}
				}
			}
			ANativeWindow_unlockAndPost(player->video.window);
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
		if (player->meta.interrupt) {
			goto SKIP_DRAW_FRAME;
		}

		pthread_mutex_lock(&player->play.finishMutex);
		while (!player->meta.interrupt && !player->play.playing) {
			pthread_cond_wait(&player->play.finishCond, &player->play.finishMutex);
		}
		pthread_mutex_unlock(&player->play.finishMutex);
		if (player->meta.interrupt) {
			goto SKIP_DRAW_FRAME;
		}

		pthread_mutex_lock(&player->video.sleepDrawMutex);
		if (playerGetSkipFlag(&player->sync.skip.drawWorkFrame)) {
			UNLOCK_AND_GOTO(&player->video.sleepDrawMutex, SKIP_DRAW_FRAME);
		}
		VideoFrameExtra * extra = bufferItem->extra;
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
			LOG("sleep video %" PRId64 " %" PRId64 " %" PRId64, waitTime, player->sync.videoPosition, position);
			int64_t time = calculateFrameTime(player, waitTime);
			while (!player->meta.interrupt && !playerGetSkipFlag(&player->sync.skip.drawWorkFrame)) {
				if (condSleepUntilMs(&player->video.sleepCond, &player->video.sleepDrawMutex, time)) {
					break;
				}
			}
			position = calculatePosition(player, 1);
			waitTime = extra->position >= 0 ? extra->position - position : 0;
		}
		if (playerGetSkipFlag(&player->sync.skip.drawWorkFrame)) {
			UNLOCK_AND_GOTO(&player->video.sleepDrawMutex, SKIP_DRAW_FRAME);
		}
		if (!finishSeeking && !extra->forcePresent && HAS_STREAM(player, audio) &&
				!player->sync.audioPositionNotSync &&
				waitTime < -SOFTWARE_LATE_DROP_THRESHOLD_MS) {
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
		if (bufferSize > player->video.lastBuffer.size) {
			player->video.lastBuffer.data = realloc(player->video.lastBuffer.data, bufferSize);
			player->video.lastBuffer.size = bufferSize;
		}
		memcpy(player->video.lastBuffer.data, bufferItem->buffer, bufferSize);
		player->video.lastBuffer.dataSize = bufferSize;
		player->video.lastBuffer.width = extra->width;
		player->video.lastBuffer.height = extra->height;
		int rendered = 0;
		if (extra->forcePresent ||
				(player->sync.lastDrawTimes[0] - player->sync.lastDrawTimes[1]) * MAX_FPS >= 1000
				|| (getTime() - player->sync.lastDrawTimes[0]) * MAX_FPS >= 1000) {
			// Avoid FPS > MAX_FPS
			drawWindow(player, bufferItem->buffer, extra->width, extra->height,
					lastWidth, lastHeight);
			rendered = 1;
			diagnosticsIncrement(PLAYER_DIAGNOSTICS_SOFTWARE_RENDERED);
			lastWidth = extra->width;
			lastHeight = extra->height;
			player->sync.lastDrawTimes[1] = player->sync.lastDrawTimes[0];
			player->sync.lastDrawTimes[0] = getTime();
			if (extra->forcePresent) {
				diagnosticsRecordSoftwareLateAnchor(player, 1, extra->position,
						position, position - extra->position);
			}
		}
		if (finishSeeking && rendered) {
			player->sync.videoPositionNotSync = 0;
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
	scaleHolder->scaleData[0] = scaleHolder->scaleBuffer;
	scaleHolder->scaleData[1] = isYUV ? scaleHolder->scaleBuffer + width * height + width * height / 4 : NULL;
	scaleHolder->scaleData[2] = isYUV ? scaleHolder->scaleBuffer + width * height : NULL;
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
		packetHolder = (PacketHolder *) blockingQueueGet(&player->video.packetQueue, 1);
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
		while (!player->meta.interrupt && !player->play.playing && !playerVideoHasPendingSurface(player)) {
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
			VideoFrameExtra * extra = NULL;
			int64_t decodedFramePosition = -1;
			if (playerGetSkipFlag(&player->sync.skip.videoWorkFrame)) {
				goto SKIP_VIDEO_FRAME;
			}
			pthread_mutex_lock(&player->decode.video.frameMutex);
			if (playerGetSkipFlag(&player->sync.skip.videoWorkFrame)) {
				UNLOCK_AND_GOTO(&player->decode.video.frameMutex, SKIP_VIDEO_FRAME);
			}
			if (!packetSent) {
				updateSoftwareSeekFastDecodeForPacketLocked(player, context, stream,
						packetHolder->packet);
			}
			int ready = playerDecodeFrame(context, packetHolder->packet, frame, &packetSent);
			if (ready) {
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
				extra->position = decodedFramePosition;
				extra->forcePresent = 0;
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
				updateSoftwareDecoderGovernor(player, context, canDropLate, lateness);
				if (canDropLate && lateness > SOFTWARE_LATE_DROP_THRESHOLD_MS) {
					int64_t now = getTime();
					int64_t lastQueuedAt = __atomic_load_n(
							&player->video.softwareLastFrameQueuedAt, __ATOMIC_ACQUIRE);
					if (lastQueuedAt <= 0 || now - lastQueuedAt >= SOFTWARE_LATE_ANCHOR_INTERVAL_MS) {
						extra->forcePresent = 1;
					} else {
						diagnosticsRecordSoftwareDrop(player, 1, extra->position,
								playbackPosition, lateness);
						success = 1;
						goto SKIP_VIDEO_FRAME;
					}
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
				if (useLibyuv) {
					I420ToABGR(frame->data[0], frame->linesize[0], frame->data[1], frame->linesize[1],
							frame->data[2], frame->linesize[2], scaleHolder.scaleBuffer, 4 * outputWidth,
							outputWidth, outputHeight);
				} else {
					scaleContext = sws_getCachedContext(scaleContext,
							frame->width, frame->height, frame->format,
							outputWidth, outputHeight, player->video.format,
							SWS_FAST_BILINEAR, NULL, NULL, NULL);
					if (!scaleContext) {
						goto SKIP_VIDEO_FRAME;
					}
					sws_scale(scaleContext, (uint8_t const * const *) frame->data, frame->linesize,
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

				pthread_mutex_lock(&player->video.queueMutex);
				if (playerGetSkipFlag(&player->sync.skip.videoWorkFrame)) {
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
				if (bufferItem) {
					int forcePresent = extra->forcePresent;
					memcpy(bufferItem->buffer, scaleHolder.scaleBuffer, outputBufferSize);
					bufferItem->dataSize = outputBufferSize;
					bufferItem->extra = extra;
					extra = NULL;
					bufferQueueAdd(player->video.bufferQueue, bufferItem);
					__atomic_store_n(&player->video.softwareLastFrameQueuedAt,
							getTime(), __ATOMIC_RELEASE);
					if (forcePresent) {
						diagnosticsRecordSoftwareLateAnchor(player, 0,
								((VideoFrameExtra *) bufferItem->extra)->position,
								playbackPosition, lateness);
					}
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
			int count = 4 * width * height;
			memset(player->video.lastBuffer.data, 0x00, count);
			for (int i = 3; i < count; i += 4) {
				player->video.lastBuffer.data[i] = 0xff;
			}
		} else if (videoFormat == AV_PIX_FMT_RGB565LE) {
			// RGB_565 "black" buffer
			memset(player->video.lastBuffer.data, 0x00, 2 * width * height);
		} else if (videoFormat == AV_PIX_FMT_YUV420P) {
			// YV12 "black" buffer
			memset(player->video.lastBuffer.data, 0, width * height);
			memset(player->video.lastBuffer.data + width * height, 0x7f, width * height / 2);
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
