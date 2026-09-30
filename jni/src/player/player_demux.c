#include "player_demux.h"
#include "player_audio.h"
#include "player_diagnostics.h"
#include "player_duration.h"
#include "player_timing.h"
#include "player_video_software.h"

#include <libavformat/avformat.h>
#include <libavutil/mathematics.h>

#include <inttypes.h>
#include <limits.h>
#include <stdint.h>
#include <unistd.h>

#define REBUFFER_VIEWING_MS 3000
// Bound compressed packets, not decoded pictures. Malformed/missing timestamps or
// extremely uneven interleaving must not grow the queues without limit.
#define REBUFFER_MAX_BYTES (32LL * 1024 * 1024)
#define REBUFFER_MAX_PACKETS 8192

typedef struct {
	int64_t spanMs;
	int64_t bytes;
	int count;
} BufferedPackets;

static BufferedPackets measureBufferedPackets(BlockingQueue * queue, AVStream * stream) {
	BufferedPackets result = {0};
	int64_t first = AV_NOPTS_VALUE;
	int64_t end = AV_NOPTS_VALUE;
	pthread_mutex_lock(&queue->mutex);
	for (QueueItem * item = queue->queue.first; item; item = item->next) {
		PacketHolder * holder = item->data;
		if (!holder || !holder->packet) continue;
		AVPacket * packet = holder->packet;
		result.count++;
		result.bytes += max64(packet->size, 0);
		int64_t timestamp = packet->dts != AV_NOPTS_VALUE ? packet->dts : packet->pts;
		if (timestamp != AV_NOPTS_VALUE) {
			if (first == AV_NOPTS_VALUE) first = timestamp;
			// DTS measures contiguous decode order, without mistaking reordered B-frame
			// presentation timestamps for additional buffered time.
			int64_t duration = max64(packet->duration, 0);
			end = timestamp <= INT64_MAX - duration ? timestamp + duration : timestamp;
		}
	}
	pthread_mutex_unlock(&queue->mutex);
	if (first != AV_NOPTS_VALUE && end != AV_NOPTS_VALUE && end > first
			&& (first >= 0 || end <= INT64_MAX + first)) {
		result.spanMs = av_rescale_q(end - first, stream->time_base, (AVRational) {1, 1000});
	}
	return result;
}

// Called only by playback demux (under readMutex or flowMutex), or by a seek
// holding BOTH locks. The callback only posts to Java; never pauses OpenSL or
// takes playback/decoder locks while demux/file locks are held.
static void setNetworkBuffering(Player * player, Bridge * bridge, int buffering, const char * reason) {
	if (player->decode.packets.buffering == buffering) return;
	player->decode.packets.buffering = buffering;
	uint64_t serial = ++player->decode.packets.bufferingSerial;
	player->decode.packets.bufferingReportTime = 0;
	diagnosticsLog("player=%u network_buffering state=%d serial=%" PRIu64
			" reason=%s target_viewing_ms=%d speed_milli=%d",
			player->meta.diagnosticsId, buffering, serial, reason,
			REBUFFER_VIEWING_MS, getPlaybackSpeed(player));
	(*bridge->env)->CallVoidMethod(bridge->env, player->bridge.native,
			bridge->methodOnNetworkBuffering, (jlong) serial, (jboolean) !!buffering);
}

void playerDemuxResetBuffering(Player * player, Bridge * bridge) {
	setNetworkBuffering(player, bridge, 0, "seek_reset");
}

static int isFileComplete(Player * player) {
	pthread_mutex_lock(&player->file.controlMutex);
	int complete = player->file.total > 0 && player->file.start == 0
			&& player->file.end >= player->file.total;
	pthread_mutex_unlock(&player->file.controlMutex);
	return complete;
}

static int packetReserveLow(Player * player) {
	int64_t lowWater = (int64_t) 250 * getPlaybackSpeed(player) / PLAYBACK_SPEED_DEFAULT;
	return (HAS_STREAM(player, video) && measureBufferedPackets(&player->video.packetQueue,
			GET_STREAM(player, video)).spanMs < lowWater)
			|| (HAS_STREAM(player, audio) && measureBufferedPackets(&player->audio.packetQueue,
			GET_STREAM(player, audio)).spanMs < lowWater);
}

static void checkNetworkBuffering(Player * player, Bridge * bridge, int complete, int eof) {
	if (!player->decode.packets.buffering) return;
	BufferedPackets video = {0}, audio = {0};
	if (HAS_STREAM(player, video)) {
		video = measureBufferedPackets(&player->video.packetQueue, GET_STREAM(player, video));
	}
	if (HAS_STREAM(player, audio)) {
		audio = measureBufferedPackets(&player->audio.packetQueue, GET_STREAM(player, audio));
	}
	int speed = getPlaybackSpeed(player);
	int64_t target = (int64_t) REBUFFER_VIEWING_MS * speed / PLAYBACK_SPEED_DEFAULT;
	int ready = (!HAS_STREAM(player, video) || video.spanMs >= target)
			&& (!HAS_STREAM(player, audio) || audio.spanMs >= target);
	int bounded = video.bytes + audio.bytes >= REBUFFER_MAX_BYTES
			|| video.count + audio.count >= REBUFFER_MAX_PACKETS;
	int64_t now = getTime();
	if (ready || complete || eof || bounded || now - player->decode.packets.bufferingReportTime >= 1000) {
		player->decode.packets.bufferingReportTime = now;
		diagnosticsLog("player=%u network_buffering_progress target_media_ms=%" PRId64
				" video_ms=%" PRId64 " audio_ms=%" PRId64 " bytes=%" PRId64
				" packets=%d speed_milli=%d complete=%d eof=%d safety_limit=%d",
				player->meta.diagnosticsId, target, video.spanMs, audio.spanMs,
				video.bytes + audio.bytes, video.count + audio.count, speed, complete, eof, bounded);
	}
	if (ready || complete || eof || bounded) {
		setNetworkBuffering(player, bridge, 0, complete ? "file_complete"
				: eof ? "eof" : ready ? "three_seconds_ready" : "packet_safety_limit");
	}
}

void playerDemuxRequestSeekWorkersStop(Player * player) {
	// The caller holds packets.flowMutex. Once the current decode iteration observes
	// these flags, it cannot start another packet before the seek has reset decoder
	// state. Publish the flags before waiting for either frame mutex so a renderer
	// sleeping on a future MediaCodec presentation time can release video.frameMutex.
	playerSetSkipFlag(&player->sync.skip.audioWorkFrame, 1);
	playerSetSkipFlag(&player->sync.skip.videoWorkFrame, 1);
	playerSetSkipFlag(&player->sync.skip.drawWorkFrame, 1);
	diagnosticsLogSeekLock(player, "prepare", "workers", "stop_requested");

	pthread_mutex_lock(&player->audio.sleepBufferMutex);
	pthread_cond_broadcast(&player->audio.sleepCond);
	pthread_cond_broadcast(&player->audio.bufferCond);
	pthread_mutex_unlock(&player->audio.sleepBufferMutex);

	pthread_mutex_lock(&player->video.sleepDrawMutex);
	pthread_cond_broadcast(&player->video.sleepCond);
	pthread_mutex_unlock(&player->video.sleepDrawMutex);

	pthread_mutex_lock(&player->video.queueMutex);
	pthread_cond_broadcast(&player->video.queueCond);
	pthread_mutex_unlock(&player->video.queueMutex);
	diagnosticsLogSeekLock(player, "prepare", "workers", "wake_finished");
}

void playerDemuxJoinWorkers(Player * player) {
	if (player->decode.audio.threadStarted) {
		playerLogDestroyStage(player, "join_audio_started");
		pthread_join(player->decode.audio.thread, NULL);
		player->decode.audio.threadStarted = 0;
		playerLogDestroyStage(player, "join_audio_finished");
	}
	if (player->decode.video.threadStarted) {
		playerLogDestroyStage(player, "join_video_started");
		pthread_join(player->decode.video.thread, NULL);
		player->decode.video.threadStarted = 0;
		playerLogDestroyStage(player, "join_video_finished");
	}
	if (player->video.drawThreadStarted) {
		playerLogDestroyStage(player, "join_draw_started");
		pthread_join(player->video.drawThread, NULL);
		player->video.drawThreadStarted = 0;
		playerLogDestroyStage(player, "join_draw_finished");
	}
}

PacketHolder * playerDemuxCreatePacketHolder(int allocPacket) {
	PacketHolder * packetHolder = malloc(sizeof(PacketHolder));
	packetHolder->packet = allocPacket ? av_packet_alloc() : NULL;
	packetHolder->type = allocPacket ? PACKET_HOLDER_MEDIA : PACKET_HOLDER_END_OF_STREAM;
	packetHolder->generation = 0;
	return packetHolder;
}

PacketHolder * playerCreateSurfaceRequestPacketHolder(void) {
	PacketHolder * packetHolder = playerDemuxCreatePacketHolder(0);
	packetHolder->type = PACKET_HOLDER_SURFACE_REQUEST;
	return packetHolder;
}

void * playerDemuxRun(void * data) {
	Player * player = (Player *) data;
	JNIEnv * env;
	(*playerGetJavaVM())->AttachCurrentThread(playerGetJavaVM(), &env, NULL);
	Bridge * bridge = playerObtainBridge(player, env);
	AVPacket packet;
	while (!player->meta.interrupt) {
		uint64_t endGeneration = __atomic_load_n(&player->decode.packets.generation,
				__ATOMIC_ACQUIRE);
		while (!player->meta.interrupt) {
			// Never hold readMutex while waiting: the replacement seek needs it.
			pthread_mutex_lock(&player->decode.packets.flowMutex);
			while (!player->meta.interrupt &&
					__atomic_load_n(&player->decode.packets.seekPending, __ATOMIC_ACQUIRE)) {
				pthread_cond_wait(&player->decode.packets.flowCond, &player->decode.packets.flowMutex);
			}
			pthread_mutex_unlock(&player->decode.packets.flowMutex);
			if (player->meta.interrupt) break;
			pthread_mutex_lock(&player->decode.packets.readMutex);
			// A seek may have started between the gate and acquiring readMutex.
			if (__atomic_load_n(&player->decode.packets.seekPending, __ATOMIC_ACQUIRE)) {
				pthread_mutex_unlock(&player->decode.packets.readMutex);
				continue;
			}
			// Capture the generation only after obtaining readMutex. A reader blocked
			// behind a seek belongs to the new generation, while a packet read before
			// the seek keeps the old generation and is discarded below.
			uint64_t packetGeneration = __atomic_load_n(&player->decode.packets.generation,
					__ATOMIC_ACQUIRE);
			player->decode.packets.readingForPlayback = 1;
			int success = av_read_frame(player->av.format, &packet) >= 0;
			player->decode.packets.readingForPlayback = 0;
			pthread_mutex_unlock(&player->decode.packets.readMutex);
			endGeneration = packetGeneration;
			if (!success) {
				break;
			}
			pthread_mutex_lock(&player->decode.packets.flowMutex);
			uint64_t currentGeneration = __atomic_load_n(&player->decode.packets.generation,
					__ATOMIC_ACQUIRE);
			if (packetGeneration != currentGeneration) {
				diagnosticsLog("player=%u packet_discarded_stale_generation"
						" packet_generation=%" PRIu64 " current_generation=%" PRIu64
						" stream=%d pts=%" PRId64,
						player->meta.diagnosticsId, packetGeneration, currentGeneration,
						packet.stream_index, packet.pts);
				goto SKIP_FRAME;
			}
			while (!player->meta.interrupt && !player->decode.packets.buffering &&
					(!HAS_STREAM(player, video) || blockingQueueCount(&player->video.packetQueue) >= 10) &&
					(!HAS_STREAM(player, audio) || blockingQueueCount(&player->audio.packetQueue) >= 20)) {
				pthread_cond_wait(&player->decode.packets.flowCond, &player->decode.packets.flowMutex);
			}
			currentGeneration = __atomic_load_n(&player->decode.packets.generation,
					__ATOMIC_ACQUIRE);
			if (packetGeneration != currentGeneration) {
				diagnosticsLog("player=%u packet_discarded_stale_generation"
						" packet_generation=%" PRIu64 " current_generation=%" PRIu64
						" stream=%d pts=%" PRId64,
						player->meta.diagnosticsId, packetGeneration, currentGeneration,
						packet.stream_index, packet.pts);
				goto SKIP_FRAME;
			}
			int isAudio = packet.stream_index == player->av.audioStreamIndex;
			int isVideo = packet.stream_index == player->av.videoStreamIndex;
			if (isAudio || isVideo) {
				PacketHolder * packetHolder = playerDemuxCreatePacketHolder(1);
				packetHolder->generation = packetGeneration;
				av_packet_ref(packetHolder->packet, &packet);
				if (isAudio) {
					blockingQueueAdd(&player->audio.packetQueue, packetHolder);
					player->audio.finished = 0;
					LOG("enqueue audio %" PRId64, packet.pts);
				} else if (isVideo) {
					blockingQueueAdd(&player->video.packetQueue, packetHolder);
					player->video.finished = 0;
					if (player->sync.seekFirstVideoPacketPending) {
						player->sync.seekFirstVideoPacketPending = 0;
						diagnosticsLog("player=%u seek_first_video_packet pts=%" PRId64
								" dts=%" PRId64 " key=%d target_ms=%" PRId64,
								player->meta.diagnosticsId, packet.pts, packet.dts,
								!!(packet.flags & AV_PKT_FLAG_KEY),
								player->sync.seekTargetPosition);
					}
					diagnosticsRecordVideoPacket(player, &packet);
					LOG("enqueue video %" PRId64, packet.pts);
				}
			}
			if (player->decode.packets.buffering) {
				checkNetworkBuffering(player, bridge, isFileComplete(player), 0);
			}
			SKIP_FRAME:
			av_packet_unref(&packet);
			pthread_mutex_unlock(&player->decode.packets.flowMutex);
		}
		if (player->meta.interrupt) {
			break;
		}
		pthread_mutex_lock(&player->decode.packets.flowMutex);
		uint64_t currentGeneration = __atomic_load_n(&player->decode.packets.generation,
				__ATOMIC_ACQUIRE);
		int staleEnd = endGeneration != currentGeneration ||
				__atomic_load_n(&player->decode.packets.seekPending, __ATOMIC_ACQUIRE);
		if (!staleEnd) {
			checkNetworkBuffering(player, bridge, 0, 1);
			if (HAS_STREAM(player, audio)) {
				blockingQueueAdd(&player->audio.packetQueue, playerDemuxCreatePacketHolder(0));
				player->audio.finished = 0;
			}
			if (HAS_STREAM(player, video)) {
				PacketHolder * end = playerDemuxCreatePacketHolder(0);
				end->generation = currentGeneration;
				blockingQueueAdd(&player->video.packetQueue, end);
				player->video.finished = 0;
			}
		}
		pthread_mutex_unlock(&player->decode.packets.flowMutex);
		if (staleEnd) {
			diagnosticsLog("player=%u packet_eof_discarded_stale_generation"
					" packet_generation=%" PRIu64 " current_generation=%" PRIu64,
					player->meta.diagnosticsId, endGeneration, currentGeneration);
			continue;
		}
		pthread_mutex_lock(&player->play.finishMutex);
		currentGeneration = __atomic_load_n(&player->decode.packets.generation,
				__ATOMIC_ACQUIRE);
		if (endGeneration != currentGeneration ||
				__atomic_load_n(&player->decode.packets.seekPending, __ATOMIC_ACQUIRE)) {
			pthread_mutex_unlock(&player->play.finishMutex);
			continue;
		}
		player->decode.packets.finished = 1;
		int needSendFinishMessage = 1;
		while (!player->meta.interrupt && player->decode.packets.finished) {
			if (endGeneration != __atomic_load_n(&player->decode.packets.generation, __ATOMIC_ACQUIRE) ||
					__atomic_load_n(&player->decode.packets.seekPending, __ATOMIC_ACQUIRE)) break;
			if (needSendFinishMessage &&
					(player->audio.finished || !HAS_STREAM(player, audio)) &&
					(player->video.finished || !HAS_STREAM(player, video))) {
				needSendFinishMessage = 0;
				diagnosticsLog("player=%u playback_complete emitted generation=%" PRIu64,
						player->meta.diagnosticsId, endGeneration);
				(*env)->CallVoidMethod(env, player->bridge.native,
						bridge->methodOnPlaybackComplete, (jlong) endGeneration);
			}
			pthread_cond_wait(&player->play.finishCond, &player->play.finishMutex);
		}
		pthread_mutex_unlock(&player->play.finishMutex);
	}
	blockingQueueAdd(&player->audio.packetQueue, NULL);
	blockingQueueAdd(&player->video.packetQueue, NULL);
	playerLogDestroyStage(player, "packet_thread_join_workers");
	playerDemuxJoinWorkers(player);
	playerLogDestroyStage(player, "packet_thread_finished");
	(*playerGetJavaVM())->DetachCurrentThread(playerGetJavaVM());
	return NULL;
}

int playerDemuxRead(void * opaque, uint8_t * buf, int bufSize) {
	int result = -1;
	Player * player = opaque;
	pthread_mutex_lock(&player->file.controlMutex);
	int64_t offset = lseek(player->file.fd, 0, SEEK_CUR);
	LOG("read data from=%" PRId64 " size=%d range=[%ld-%ld/%ld]",
		offset, bufSize, player->file.start, player->file.end, player->file.total);
	if (offset >= 0) {
		int request = 1;
		int waitedForRange = 0;
		while (!player->meta.interrupt && !player->file.cancelSeek) {
			if (player->file.total >= 0 && offset >= player->file.total) {
				break;
			}
			if (offset >= player->file.start && offset < player->file.end) {
				int64_t maxCount64 = player->file.end - offset;
				int maxCount = bufSize > maxCount64 ? maxCount64 : bufSize;
				result = read(player->file.fd, buf, maxCount);
				break;
			}
			if (request) {
				request = 0;
				waitedForRange = 1;
				diagnosticsRangeWait(player, 1);
				diagnosticsLog("player=%u range_wait_started offset=%" PRId64
						" available=[%ld-%ld/%ld]", player->meta.diagnosticsId, offset,
						player->file.start, player->file.end, player->file.total);
				Bridge * bridge = sparseArrayGet(&player->bridge.array, (int) pthread_self());
				if (bridge) {
					LOG("read data request");
					(*bridge->env)->CallVoidMethod(bridge->env, player->bridge.native, bridge->methodOnSeek, offset);
				}
			}
			LOG("read data wait");
			// Recheck the speed-dependent target even when the connection stops sending
			// bytes. The already queued media may suffice after a speed decrease.
			if (player->decode.packets.readingForPlayback) {
				Bridge * bridge = sparseArrayGet(&player->bridge.array, (int) pthread_self());
				if (!player->decode.packets.buffering && packetReserveLow(player)) {
					setNetworkBuffering(player, bridge, 1, "range_starved");
				}
				checkNetworkBuffering(player, bridge, player->file.total > 0
						&& player->file.start == 0 && player->file.end >= player->file.total, 0);
				condSleepUntilMs(&player->file.controlCond, &player->file.controlMutex, getTime() + 100);
			} else {
				pthread_cond_wait(&player->file.controlCond, &player->file.controlMutex);
			}
		}
		if (waitedForRange) {
			diagnosticsRangeWait(player, 0);
			diagnosticsLog("player=%u range_wait_finished offset=%" PRId64
					" cancelled=%d interrupt=%d available=[%ld-%ld/%ld]", player->meta.diagnosticsId,
					offset, player->file.cancelSeek, player->meta.interrupt,
					player->file.start, player->file.end, player->file.total);
		}
	}
	LOG("read data result size=%d", result);
	pthread_mutex_unlock(&player->file.controlMutex);
	return result;
}

int64_t playerDemuxSeek(void * opaque, int64_t offset, int whence) {
	int64_t result = -1;
	LOG("seek data offset=%" PRId64 " whence=%d", offset, whence);
	Player * player = opaque;
	pthread_mutex_lock(&player->file.controlMutex);
	if (whence == SEEK_SET || whence == SEEK_CUR) {
		result = lseek(player->file.fd, offset, whence);
	} else if (whence == SEEK_END && player->file.total >= 0) {
		result = lseek(player->file.fd, player->file.total + offset, SEEK_SET);
	} else if (whence == AVSEEK_SIZE && player->file.total >= 0) {
		result = player->file.total;
	}
	LOG("seek data result offset=%" PRId64, result);
	pthread_mutex_unlock(&player->file.controlMutex);
	return result;
}

void playerDemuxSetRange(Player * player, int64_t start, int64_t end, int64_t total) {
	pthread_mutex_lock(&player->file.controlMutex);
	player->file.start = start;
	player->file.end = end;
	player->file.total = total;
	LOG("set range range=[%ld-%ld/%ld]", player->file.start, player->file.end, player->file.total);
	maybeStartDurationProbeLocked(player);
	pthread_cond_broadcast(&player->file.controlCond);
	pthread_mutex_unlock(&player->file.controlMutex);
}

void playerDemuxSetCancelSeek(Player * player, int cancelSeek) {
	pthread_mutex_lock(&player->file.controlMutex);
	player->file.cancelSeek = !!cancelSeek;
	pthread_cond_broadcast(&player->file.controlCond);
	pthread_mutex_unlock(&player->file.controlMutex);
}
