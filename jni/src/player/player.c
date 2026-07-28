#include "player.h"
#include "player_audio.h"
#include "player_diagnostics.h"
#include "player_duration.h"
#include "player_internal.h"
#include "player_timing.h"
#include "player_video_mediacodec.h"
#include "player_video_software.h"
#include "util.h"
#ifdef DASHCHAN_HAS_ATEMPO
#include "tempo.h"
#endif

#include <libavcodec/avcodec.h>
#ifdef DASHCHAN_HAS_MEDIACODEC
#include <libavcodec/jni.h>
#endif
#include <libavformat/avformat.h>
#include <libavutil/mathematics.h>
#include <libavutil/pixdesc.h>
#include <libavutil/ffversion.h>

#include <inttypes.h>
#include <limits.h>
#include <math.h>
#include <stdarg.h>
#include <stdint.h>
#include <stdio.h>
#include <string.h>
#include <time.h>
#include <unistd.h>

#define ERROR_LOAD_IO 1
#define ERROR_LOAD_FORMAT 2
#define ERROR_START_THREAD 3
#define ERROR_FIND_STREAM_INFO 4
#define ERROR_FIND_STREAM 5
#define ERROR_FIND_CODEC 6
#define ERROR_OPEN_CODEC 7

#define SEEK_KEYFRAME_DISCOVERY_THRESHOLD_MS 3000

#ifndef DASHCHAN_FFMPEG_FLAVOR
#define DASHCHAN_FFMPEG_FLAVOR "ffmpeg"
#endif

static JavaVM * loadJavaVM;

JavaVM * playerGetJavaVM(void) {
	return loadJavaVM;
}

int playerGetSkipFlag(int * flag) {
	return __atomic_load_n(flag, __ATOMIC_ACQUIRE);
}

void playerSetSkipFlag(int * flag, int value) {
	__atomic_store_n(flag, value, __ATOMIC_RELEASE);
}

void playerSetDiagnosticsAudioStage(Player * player, int stage) {
	__atomic_store_n(&player->decode.audio.diagnosticsStage, stage, __ATOMIC_RELAXED);
}

void playerSetDiagnosticsMediaCodecStage(Player * player, int stage, int64_t framePosition) {
	if (framePosition >= 0) {
		__atomic_store_n(&player->decode.video.diagnosticsFramePosition,
				framePosition, __ATOMIC_RELAXED);
	}
	__atomic_store_n(&player->decode.video.diagnosticsStage, stage, __ATOMIC_RELEASE);
}

static void requestSeekWorkersStop(Player * player) {
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

Bridge * playerObtainBridge(Player * player, JNIEnv * env) {
	int index = pthread_self();
	Bridge * bridge = sparseArrayGet(&player->bridge.array, index);
	if (!bridge) {
		bridge = malloc(sizeof(Bridge));
		jclass class = (*env)->GetObjectClass(env, player->bridge.native);
		bridge->env = env;
		bridge->methodOnSeek = (*env)->GetMethodID(env, class, "onSeek", "(J)V");
		bridge->methodOnMessage = (*env)->GetMethodID(env, class, "onMessage", "(I)V");
		bridge->methodOnDurationChanged = (*env)->GetMethodID(env, class,
				"onDurationChanged", "(J)V");
		bridge->methodOnSurfaceApplied = (*env)->GetMethodID(env, class,
				"onSurfaceApplied", "(JJZ)V");
		sparseArrayAdd(&player->bridge.array, index, bridge);
	}
	return bridge;
}

void playerNotifyDurationChanged(Player * player, int64_t duration) {
	JNIEnv * env;
	if ((*loadJavaVM)->AttachCurrentThread(loadJavaVM, &env, NULL) == JNI_OK) {
		Bridge * bridge = playerObtainBridge(player, env);
		(*env)->CallVoidMethod(env, player->bridge.native,
				bridge->methodOnDurationChanged, (jlong) duration);
		(*loadJavaVM)->DetachCurrentThread(loadJavaVM);
	}
}

static int64_t getSeekTimestampUs(Player * player, int64_t position) {
	AVRational msTimeBase = {1, 1000};
	return av_rescale_q(position + player->av.timelineOffsetMs, msTimeBase, AV_TIME_BASE_Q);
}

static int getSeekReferenceStreamIndex(Player * player) {
	if (HAS_STREAM(player, video)) {
		return player->av.videoStreamIndex;
	}
	if (HAS_STREAM(player, audio)) {
		return player->av.audioStreamIndex;
	}
	return -1;
}

static int64_t getSeekTimestamp(Player * player, int streamIndex, int64_t position) {
	if (streamIndex < 0) {
		return getSeekTimestampUs(player, position);
	}
	AVRational msTimeBase = {1, 1000};
	AVStream * stream = player->av.format->streams[streamIndex];
	return av_rescale_q(position + player->av.timelineOffsetMs, msTimeBase, stream->time_base);
}

static int seekFrame(Player * player, int64_t position, int flags,
		int * usedStreamIndex, int * usedGlobalFallback) {
	int streamIndex = getSeekReferenceStreamIndex(player);
	int globalFallback = 0;
	int result = av_seek_frame(player->av.format, streamIndex,
			getSeekTimestamp(player, streamIndex, position), flags);
	if (result < 0 && streamIndex >= 0) {
		// Some demuxers only implement global timestamp seeking. Prefer a component
		// stream so video seeks land on a decodable keyframe, but retain compatibility.
		diagnosticsLog("player=%u seek_stream_fallback stream=%d code=%d",
				player->meta.diagnosticsId, streamIndex, result);
		streamIndex = -1;
		globalFallback = 1;
		result = av_seek_frame(player->av.format, streamIndex,
				getSeekTimestamp(player, streamIndex, position), flags);
	}
	if (usedStreamIndex) {
		*usedStreamIndex = streamIndex;
	}
	if (usedGlobalFallback) {
		*usedGlobalFallback = globalFallback;
	}
	return result;
}

void playerCloseAndFreeCodecContext(AVCodecContext ** context) {
	if (!context || !*context) {
		return;
	}
#if LIBAVCODEC_VERSION_MAJOR < 59
	avcodec_close(*context);
#endif
	avcodec_free_context(context);
}

void playerCloseAndFreeVideoCodecContext(Player * player, AVCodecContext ** context) {
	if (!context || !*context) {
		return;
	}
	(void) player;
	playerCloseAndFreeCodecContext(context);
}

void playerPacketQueueFreeCallback(void * data) {
	PacketHolder * packetHolder = (PacketHolder *) data;
	if (packetHolder->packet) {
		av_packet_free(&packetHolder->packet);
	}
	free(packetHolder);
}

void playerMarkStreamFinished(Player * player, int video) {
	if (video) {
		int decodedCount = player->video.bufferQueue ? bufferQueueCount(player->video.bufferQueue) : 0;
		if (decodedCount == 0 &&
				blockingQueueCount(&player->video.packetQueue) == 0) {
			player->video.finished = 1;
			condBroadcastLocked(&player->play.finishCond, &player->play.finishMutex);
		}
	} else {
		if (!player->audio.buffer && player->audio.outputChunkCount == 0 &&
				blockingQueueCount(&player->audio.bufferQueue) == 0
				&& blockingQueueCount(&player->audio.packetQueue) == 0) {
			player->audio.finished = 1;
			condBroadcastLocked(&player->play.finishCond, &player->play.finishMutex);
		}
	}
}

int playerDecodeFrame(AVCodecContext * context, AVPacket * packet, AVFrame * frame, int * packetSent) {
	if (!*packetSent) {
		int result = avcodec_send_packet(context, packet);
		if (result == 0) {
			*packetSent = 1;
		} else if (result != AVERROR(EAGAIN)) {
			if (result != AVERROR_EOF) {
				LOG("send packet failed %d", result);
			}
			return 0;
		}
	}

	int result = avcodec_receive_frame(context, frame);
	if (result == 0) {
		return 1;
	}
	if (result == AVERROR(EAGAIN)) {
		if (!*packetSent) {
			// FFmpeg guarantees that send and receive cannot both return EAGAIN without progress.
			LOG("decoder did not accept input or produce output");
		}
	} else if (result != AVERROR_EOF) {
		LOG("receive frame failed %d", result);
	}
	return 0;
}

void playerLogDestroyStage(Player * player, const char * stage) {
	diagnosticsLog("player=%u destroy_stage=%s", player->meta.diagnosticsId, stage);
	LOGP("player=%u destroy stage=%s", player->meta.diagnosticsId, stage);
}

static void joinStartedWorkerThreads(Player * player) {
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

static PacketHolder * createPacketHolder(int allocPacket) {
	PacketHolder * packetHolder = malloc(sizeof(PacketHolder));
	packetHolder->packet = allocPacket ? av_packet_alloc() : NULL;
	packetHolder->type = allocPacket ? PACKET_HOLDER_MEDIA : PACKET_HOLDER_END_OF_STREAM;
	return packetHolder;
}

PacketHolder * playerCreateSurfaceRequestPacketHolder(void) {
	PacketHolder * packetHolder = createPacketHolder(0);
	packetHolder->type = PACKET_HOLDER_SURFACE_REQUEST;
	return packetHolder;
}

static void * performDecodePackets(void * data) {
	Player * player = (Player *) data;
	JNIEnv * env;
	(*loadJavaVM)->AttachCurrentThread(loadJavaVM, &env, NULL);
	Bridge * bridge = playerObtainBridge(player, env);
	AVPacket packet;
	while (!player->meta.interrupt) {
		uint64_t endGeneration = __atomic_load_n(&player->decode.packets.generation,
				__ATOMIC_ACQUIRE);
		while (!player->meta.interrupt) {
			pthread_mutex_lock(&player->decode.packets.readMutex);
			// Capture the generation only after obtaining readMutex. A reader blocked
			// behind a seek belongs to the new generation, while a packet read before
			// the seek keeps the old generation and is discarded below.
			uint64_t packetGeneration = __atomic_load_n(&player->decode.packets.generation,
					__ATOMIC_ACQUIRE);
			int success = av_read_frame(player->av.format, &packet) >= 0;
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
			while (!player->meta.interrupt &&
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
				PacketHolder * packetHolder = createPacketHolder(1);
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
		int staleEnd = endGeneration != currentGeneration;
		if (!staleEnd) {
			if (HAS_STREAM(player, audio)) {
				blockingQueueAdd(&player->audio.packetQueue, createPacketHolder(0));
				player->audio.finished = 0;
			}
			if (HAS_STREAM(player, video)) {
				blockingQueueAdd(&player->video.packetQueue, createPacketHolder(0));
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
		if (endGeneration != currentGeneration) {
			pthread_mutex_unlock(&player->play.finishMutex);
			continue;
		}
		player->decode.packets.finished = 1;
		int needSendFinishMessage = 1;
		while (!player->meta.interrupt && player->decode.packets.finished) {
			if (needSendFinishMessage &&
					(player->audio.finished || !HAS_STREAM(player, audio)) &&
					(player->video.finished || !HAS_STREAM(player, video))) {
				needSendFinishMessage = 0;
				PLAYER_SEND_MESSAGE(env, player, bridge, BRIDGE_MESSAGE_PLAYBACK_COMPLETE);
			}
			pthread_cond_wait(&player->play.finishCond, &player->play.finishMutex);
		}
		pthread_mutex_unlock(&player->play.finishMutex);
	}
	blockingQueueAdd(&player->audio.packetQueue, NULL);
	blockingQueueAdd(&player->video.packetQueue, NULL);
	playerLogDestroyStage(player, "packet_thread_join_workers");
	joinStartedWorkerThreads(player);
	playerLogDestroyStage(player, "packet_thread_finished");
	(*loadJavaVM)->DetachCurrentThread(loadJavaVM);
	return NULL;
}

static int bufferReadData(void * opaque, uint8_t * buf, int bufSize) {
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
			pthread_cond_wait(&player->file.controlCond, &player->file.controlMutex);
		}
		if (waitedForRange) {
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

static int64_t bufferSeekData(void * opaque, int64_t offset, int whence) {
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

static Player * createPlayer(void) {
	Player * player = malloc(sizeof(Player));
	memset(player, 0, sizeof(Player));
	player->meta.diagnosticsId = diagnosticsNextPlayerId();
	player->file.total = -1;
	player->meta.audioEnabled = 1;
	player->av.audioStreamIndex = INDEX_NO_STREAM;
	player->av.videoStreamIndex = INDEX_NO_STREAM;
	player->video.useLibyuv = -1;
	player->video.lastBuffer.width = -1;
	player->video.lastBuffer.height = -1;
	__atomic_store_n(&player->decode.video.diagnosticsFramePosition, -1, __ATOMIC_RELAXED);
	player->audio.localVolume = 100;
	player->sync.playbackSpeed = PLAYBACK_SPEED_DEFAULT;
	sparseArrayInit(&player->bridge.array, 4);
	pthread_mutex_init(&player->file.controlMutex, NULL);
	pthread_cond_init(&player->file.controlCond, NULL);
	pthread_mutex_init(&player->decode.packets.readMutex, NULL);
	pthread_cond_init(&player->decode.packets.flowCond, NULL);
	pthread_mutex_init(&player->decode.packets.flowMutex, NULL);
	pthread_mutex_init(&player->decode.audio.frameMutex, NULL);
	pthread_mutex_init(&player->decode.video.frameMutex, NULL);
	pthread_mutex_init(&player->video.surfaceMutex, NULL);
	pthread_cond_init(&player->play.finishCond, NULL);
	pthread_mutex_init(&player->play.finishMutex, NULL);
	pthread_cond_init(&player->audio.sleepCond, NULL);
	pthread_cond_init(&player->audio.bufferCond, NULL);
	pthread_mutex_init(&player->audio.sleepBufferMutex, NULL);
	pthread_cond_init(&player->video.sleepCond, NULL);
	pthread_mutex_init(&player->video.sleepDrawMutex, NULL);
	pthread_cond_init(&player->video.queueCond, NULL);
	pthread_mutex_init(&player->video.queueMutex, NULL);
	blockingQueueInit(&player->audio.packetQueue);
	blockingQueueInit(&player->video.packetQueue);
	blockingQueueInit(&player->audio.bufferQueue);
	return player;
}

jlong preInit(UNUSED JNIEnv * env, jint fd) {
	Player * player = createPlayer();
	player->file.fd = fd;
	diagnosticsLog("player=%u created", player->meta.diagnosticsId);
	return (jlong) (long) player;
}

void setAudioEnabled(jlong pointer, jboolean audioEnabled) {
	Player * player = POINTER_CAST(pointer);
	player->meta.audioEnabled = !!audioEnabled;
}

void init(JNIEnv * env, jlong pointer, jobject nativeBridge, jboolean seekAnyFrame) {
	Player * player = POINTER_CAST(pointer);
	diagnosticsLog("player=%u init_started seek_any_frame=%d",
			player->meta.diagnosticsId, !!seekAnyFrame);
	player->meta.seekAnyFrame = !!seekAnyFrame;
	player->bridge.native = (*env)->NewGlobalRef(env, nativeBridge);
	playerObtainBridge(player, env);
	int contextBufferSize = 8 * 1024;
	uint8_t * contextBuffer = av_malloc(contextBufferSize);
	AVIOContext * ioContext = avio_alloc_context(contextBuffer, contextBufferSize, 0, player,
			&bufferReadData, NULL, &bufferSeekData);
	if (!ioContext) {
		av_free(contextBuffer);
		player->meta.errorCode = ERROR_LOAD_IO;
		return;
	}
	AVFormatContext * formatContext = avformat_alloc_context();
	formatContext->pb = ioContext;
	LOG("start avformat_open_input");
	if (avformat_open_input(&formatContext, "", NULL, NULL) != 0) {
		avformat_close_input(&formatContext);
		av_free(ioContext->buffer);
		av_free(ioContext);
		player->meta.errorCode = ERROR_LOAD_FORMAT;
		return;
	}
	LOG("end avformat_open_input");
	player->av.format = formatContext;
	LOG("start avformat_find_stream_info");
	if (avformat_find_stream_info(formatContext, NULL) < 0) {
		player->meta.errorCode = ERROR_FIND_STREAM_INFO;
		return;
	}
	LOG("end avformat_find_stream_info");
	if (formatContext->start_time != AV_NOPTS_VALUE) {
		AVRational msTimeBase = {1, 1000};
		player->av.timelineOffsetMs = av_rescale_q(formatContext->start_time, AV_TIME_BASE_Q, msTimeBase);
	}
	LOG("timeline offset=%" PRId64 " ms", player->av.timelineOffsetMs);
	int audioStreamIndex = INDEX_NO_STREAM;
	int videoStreamIndex = INDEX_NO_STREAM;
	for (int i = 0; i < (int) formatContext->nb_streams; i++) {
		int codecType = formatContext->streams[i]->codecpar->codec_type;
		if (audioStreamIndex == INDEX_NO_STREAM && codecType == AVMEDIA_TYPE_AUDIO) {
			audioStreamIndex = i;
		} else if (videoStreamIndex == INDEX_NO_STREAM && codecType == AVMEDIA_TYPE_VIDEO) {
			videoStreamIndex = i;
		}
	}
	if (videoStreamIndex == INDEX_NO_STREAM) {
		player->meta.errorCode = ERROR_FIND_STREAM;
		return;
	}
	AVStream * audioStream = audioStreamIndex != INDEX_NO_STREAM ? formatContext->streams[audioStreamIndex] : NULL;
	AVStream * videoStream = videoStreamIndex != INDEX_NO_STREAM ? formatContext->streams[videoStreamIndex] : NULL;
	if (!player->meta.audioEnabled) {
		audioStreamIndex = INDEX_NO_STREAM;
		audioStream = NULL;
	}
	const AVCodec * audioCodec = audioStream ? avcodec_find_decoder(audioStream->codecpar->codec_id) : NULL;
	const AVCodec * videoCodec = videoStream ? avcodec_find_decoder(videoStream->codecpar->codec_id) : NULL;
	if (!audioCodec) {
		audioStreamIndex = INDEX_NO_STREAM;
		audioStream = NULL;
	}
	if (!videoCodec) {
		player->meta.errorCode = ERROR_FIND_CODEC;
		return;
	}
	if (audioCodec) {
		AVCodecContext * audioContext = avcodec_alloc_context3(audioCodec);
		if (!audioContext || avcodec_parameters_to_context(audioContext, audioStream->codecpar)) {
			avcodec_free_context(&audioContext);
			player->meta.errorCode = ERROR_OPEN_CODEC;
			return;
		}
		audioContext->pkt_timebase = audioStream->time_base;
		if (avcodec_open2(audioContext, audioCodec, NULL) < 0) {
			avcodec_free_context(&audioContext);
			player->meta.errorCode = ERROR_OPEN_CODEC;
			return;
		}
		player->av.audioStreamIndex = audioStreamIndex;
		player->av.audioContext = audioContext;
	}
	if (videoCodec) {
		AVCodecContext * videoContext = avcodec_alloc_context3(videoCodec);
		if (!videoContext || avcodec_parameters_to_context(videoContext, videoStream->codecpar)) {
			avcodec_free_context(&videoContext);
			player->meta.errorCode = ERROR_OPEN_CODEC;
			return;
		}
		videoContext->pkt_timebase = videoStream->time_base;
		if (avcodec_open2(videoContext, videoCodec, NULL) < 0) {
			avcodec_free_context(&videoContext);
			player->meta.errorCode = ERROR_OPEN_CODEC;
			return;
		}
		player->av.videoStreamIndex = videoStreamIndex;
		player->av.videoContext = videoContext;
	}
	// A truncated WebM may retain a plausible container duration while every
	// active stream has no duration at all. Treat that value as unverified until
	// a packet-only pass reaches the physical end of the complete file.
	int64_t declaredDuration = getFormatDurationMs(formatContext);
	int64_t streamDuration = max64(getStreamDurationMs(audioStream), getStreamDurationMs(videoStream));
	int64_t effectiveDuration = declaredDuration > 0 ? declaredDuration : streamDuration;
	int64_t durationDifference = declaredDuration >= streamDuration
			? declaredDuration - streamDuration : streamDuration - declaredDuration;
	player->av.duration.probeRequired = streamDuration <= 0 ||
			(declaredDuration > 0 && durationDifference > DURATION_PROBE_TOLERANCE_MS);
	__atomic_store_n(&player->av.duration.declaredMs, declaredDuration, __ATOMIC_RELEASE);
	__atomic_store_n(&player->av.duration.effectiveMs, effectiveDuration, __ATOMIC_RELEASE);
	diagnosticsLog("player=%u duration_initialized declared_ms=%" PRId64
			" stream_ms=%" PRId64 " effective_ms=%" PRId64 " probe_required=%d",
			player->meta.diagnosticsId, declaredDuration, streamDuration,
			effectiveDuration, player->av.duration.probeRequired);
	diagnosticsRecordMediaInfo(player);
	if (audioStream && !playerAudioInitialize(player, audioStream)) {
		audioStreamIndex = INDEX_NO_STREAM;
		player->av.audioStreamIndex = INDEX_NO_STREAM;
		audioStream = NULL;
		audioCodec = NULL;
	}
	if (videoStream) {
		if (pthread_create(&player->video.drawThread, NULL, &playerVideoDrawThread, player) != 0) {
			player->meta.errorCode = ERROR_START_THREAD;
			return;
		}
		player->video.drawThreadStarted = 1;
	}
	if (audioStream) {
		if (pthread_create(&player->decode.audio.thread, NULL, &playerAudioDecodeThread, player) != 0) {
			player->meta.errorCode = ERROR_START_THREAD;
			return;
		}
		player->decode.audio.threadStarted = 1;
	}
	if (videoStream) {
		if (pthread_create(&player->decode.video.thread, NULL, &playerVideoDecodeThread, player) != 0) {
			player->meta.errorCode = ERROR_START_THREAD;
			return;
		}
		player->decode.video.threadStarted = 1;
	}
	if (pthread_create(&player->decode.packets.thread, NULL, &performDecodePackets, player) != 0) {
		player->meta.errorCode = ERROR_START_THREAD;
		return;
	}
	player->decode.packets.threadStarted = 1;
	pthread_mutex_lock(&player->file.controlMutex);
	player->av.duration.initialized = 1;
	maybeStartDurationProbeLocked(player);
	pthread_mutex_unlock(&player->file.controlMutex);
}

void destroy(JNIEnv * env, jlong pointer, jboolean initOnly) {
	Player * player = POINTER_CAST(pointer);
	diagnosticsLog("player=%u destroy init_only=%d error_code=%d hardware_active=%d",
			player->meta.diagnosticsId, !!initOnly, player->meta.errorCode,
			player->video.hardwareDecoderActive);
	playerLogDestroyStage(player, "started");
	player->meta.interrupt = 1;
	condBroadcastLocked(&player->file.controlCond, &player->file.controlMutex);
	if (!!initOnly) {
		playerLogDestroyStage(player, "init_only_finished");
		return;
	}

	blockingQueueInterrupt(&player->audio.packetQueue);
	blockingQueueInterrupt(&player->video.packetQueue);
	blockingQueueInterrupt(&player->audio.bufferQueue);

	condBroadcastLocked(&player->audio.sleepCond, &player->audio.sleepBufferMutex);
	condBroadcastLocked(&player->audio.bufferCond, &player->audio.sleepBufferMutex);
	condBroadcastLocked(&player->video.sleepCond, &player->video.sleepDrawMutex);
	condBroadcastLocked(&player->video.queueCond, &player->video.queueMutex);
	condBroadcastLocked(&player->play.finishCond, &player->play.finishMutex);
	condBroadcastLocked(&player->decode.packets.flowCond, &player->decode.packets.flowMutex);
	playerLogDestroyStage(player, "workers_signaled");

	if (player->decode.packets.threadStarted) {
		playerLogDestroyStage(player, "join_packets_started");
		pthread_join(player->decode.packets.thread, NULL);
		player->decode.packets.threadStarted = 0;
		playerLogDestroyStage(player, "join_packets_finished");
	} else {
		joinStartedWorkerThreads(player);
	}
	if (player->av.duration.probeThreadStarted) {
		playerLogDestroyStage(player, "join_duration_probe_started");
		pthread_join(player->av.duration.probeThread, NULL);
		player->av.duration.probeThreadStarted = 0;
		playerLogDestroyStage(player, "join_duration_probe_finished");
	}
	jobject pendingSurface = NULL;
	jobject activeSurface = NULL;
	pthread_mutex_lock(&player->video.surfaceMutex);
	pendingSurface = player->video.pendingSurface;
	activeSurface = player->video.activeSurface;
	player->video.pendingSurface = NULL;
	player->video.activeSurface = NULL;
	__atomic_store_n(&player->video.surfaceRequestPending, 0, __ATOMIC_RELEASE);
	pthread_mutex_unlock(&player->video.surfaceMutex);
	pthread_mutex_lock(&player->audio.sleepBufferMutex);
	playerAudioClearOutputLocked(player, 0);
	pthread_mutex_unlock(&player->audio.sleepBufferMutex);
	playerLogDestroyStage(player, "synchronization_cleanup_started");
	pthread_mutex_destroy(&player->decode.packets.readMutex);
	pthread_mutex_destroy(&player->decode.packets.flowMutex);
	pthread_mutex_destroy(&player->decode.audio.frameMutex);
	pthread_mutex_destroy(&player->decode.video.frameMutex);
	pthread_mutex_destroy(&player->play.finishMutex);
	pthread_mutex_destroy(&player->audio.sleepBufferMutex);
	pthread_mutex_destroy(&player->video.sleepDrawMutex);
	pthread_mutex_destroy(&player->video.queueMutex);
	pthread_mutex_destroy(&player->video.surfaceMutex);
	pthread_mutex_destroy(&player->file.controlMutex);
	pthread_cond_destroy(&player->decode.packets.flowCond);
	pthread_cond_destroy(&player->play.finishCond);
	pthread_cond_destroy(&player->audio.sleepCond);
	pthread_cond_destroy(&player->audio.bufferCond);
	pthread_cond_destroy(&player->video.sleepCond);
	pthread_cond_destroy(&player->video.queueCond);
	pthread_cond_destroy(&player->file.controlCond);
	playerLogDestroyStage(player, "synchronization_cleanup_finished");

	blockingQueueDestroy(&player->audio.packetQueue, playerPacketQueueFreeCallback);
	blockingQueueDestroy(&player->video.packetQueue, playerPacketQueueFreeCallback);
	blockingQueueDestroy(&player->audio.bufferQueue, playerAudioBufferQueueFreeCallback);
	if (player->video.bufferQueue) {
		bufferQueueDestroy(player->video.bufferQueue, playerVideoBufferQueueFreeCallback);
		free(player->video.bufferQueue);
		free(player->video.lastBuffer.data);
	}
	playerLogDestroyStage(player, "buffers_cleanup_finished");

	playerAudioDestroy(player);
	if (HAS_STREAM(player, video)) {
		playerLogDestroyStage(player, "video_codec_close_started");
		playerCloseAndFreeVideoCodecContext(player, &player->av.videoContext);
		playerLogDestroyStage(player, "video_codec_close_finished");
	}
	if (player->av.format) {
		playerLogDestroyStage(player, "format_close_started");
		AVIOContext * ioContext = player->av.format->pb;
		avformat_close_input(&player->av.format);
		av_free(ioContext->buffer);
		av_free(ioContext);
		playerLogDestroyStage(player, "format_close_finished");
	}
	playerVideoReleaseSurface(player);
	if (pendingSurface) {
		(*env)->DeleteGlobalRef(env, pendingSurface);
	}
	if (activeSurface) {
		(*env)->DeleteGlobalRef(env, activeSurface);
	}
	playerLogDestroyStage(player, "surface_released");
	sparseArrayDestroy(&player->bridge.array, free);
	if (player->bridge.native) {
		(*env)->DeleteGlobalRef(env, player->bridge.native);
	}
	if (player->file.fd > 0) {
		close(player->file.fd);
	}
	playerLogDestroyStage(player, "finished");
	free(player);
}

jint getErrorCode(jlong pointer) {
	Player * player = POINTER_CAST(pointer);
	diagnosticsLog("player=%u init_finished error_code=%d audio_stream=%d video_stream=%d",
			player->meta.diagnosticsId, player->meta.errorCode,
			player->av.audioStreamIndex, player->av.videoStreamIndex);
	return player->meta.errorCode;
}

void getSummary(JNIEnv * env, jlong pointer, jintArray output) {
	Player * player = POINTER_CAST(pointer);
	jint result[3];
	AVCodecContext * context = GET_CONTEXT(player, video);
	result[0] = context->width;
	result[1] = context->height;
	result[2] = HAS_STREAM(player, audio);
	(*env)->SetIntArrayRegion(env, output, 0, 3, result);
}

jlong getDuration(jlong pointer) {
	Player * player = POINTER_CAST(pointer);
	return max64(__atomic_load_n(&player->av.duration.effectiveMs, __ATOMIC_ACQUIRE), 0);
}

jlong getPosition(jlong pointer) {
	Player * player = POINTER_CAST(pointer);
	return max64(calculatePosition(player, 0), 0);
}

static int isSeekCancelled(Player * player) {
	pthread_mutex_lock(&player->file.controlMutex);
	int cancelled = player->meta.interrupt || player->file.cancelSeek;
	pthread_mutex_unlock(&player->file.controlMutex);
	return cancelled;
}

static int getIndexedVideoKeyframePosition(Player * player, int64_t targetPosition,
		int64_t * keyframePosition) {
	if (!HAS_STREAM(player, video)) {
		return 0;
	}
	AVStream * stream = GET_STREAM(player, video);
	int64_t timestamp = getSeekTimestamp(player, player->av.videoStreamIndex, targetPosition);
	const AVIndexEntry * entry = NULL;
#if LIBAVFORMAT_VERSION_MAJOR >= 59
	entry = avformat_index_get_entry_from_timestamp(stream, timestamp, AVSEEK_FLAG_BACKWARD);
#else
	int index = av_index_search_timestamp(stream, timestamp, AVSEEK_FLAG_BACKWARD);
	if (index >= 0 && index < stream->nb_index_entries) {
		entry = &stream->index_entries[index];
	}
#endif
	if (!entry || !(entry->flags & AVINDEX_KEYFRAME)) {
		return 0;
	}
	*keyframePosition = getTimestampPositionMs(player, entry->timestamp, stream->time_base);
	return *keyframePosition >= 0;
}

static int probeVideoKeyframe(Player * player, int64_t targetPosition, int64_t minimumPosition,
		int acceptAfterTarget, int64_t * keyframePosition, AVPacket ** keyframePacket) {
	AVStream * stream = GET_STREAM(player, video);
	AVPacket packet;
	int firstVideoPacket = 1;
	while (!isSeekCancelled(player)) {
		int readResult = av_read_frame(player->av.format, &packet);
		if (readResult < 0) {
			diagnosticsLog("player=%u seek_keyframe_probe eof code=%d",
					player->meta.diagnosticsId, readResult);
			return 0;
		}
		if (packet.stream_index == player->av.videoStreamIndex) {
			int64_t packetPosition = getTimestampPositionMs(player, packet.pts, stream->time_base);
			int key = !!(packet.flags & AV_PKT_FLAG_KEY);
			if (firstVideoPacket) {
				firstVideoPacket = 0;
				diagnosticsLog("player=%u seek_keyframe_probe first_packet_ms=%" PRId64
						" key=%d target_ms=%" PRId64,
						player->meta.diagnosticsId, packetPosition, key, targetPosition);
			}
			if (key) {
				// A small positive tolerance covers streams whose first frame starts a few
				// milliseconds after the zero-based requested position.
				int afterMinimum = minimumPosition < 0 || packetPosition < 0
						|| packetPosition + 100 >= minimumPosition;
				int beforeTarget = acceptAfterTarget || packetPosition < 0
						|| packetPosition <= targetPosition + 100;
				if (afterMinimum && beforeTarget) {
					*keyframePosition = packetPosition >= 0 ? packetPosition : targetPosition;
					*keyframePacket = av_packet_alloc();
					if (*keyframePacket) {
						av_packet_move_ref(*keyframePacket, &packet);
					}
					av_packet_unref(&packet);
					return *keyframePacket != NULL;
				}
				// av_seek_frame may land on the previous Matroska cluster even when an
				// exact keyframe timestamp was requested. Keep reading until the verified
				// keyframe selected by packet discovery is reached.
				if (!afterMinimum) {
					av_packet_unref(&packet);
					continue;
				}
				av_packet_unref(&packet);
				return 0;
			}
		}
		av_packet_unref(&packet);
	}
	return 0;
}

static int discoverCloserVideoKeyframe(Player * player, int64_t targetPosition,
		int64_t initialPosition, int64_t * discoveredPosition) {
	if (initialPosition < 0 || targetPosition - initialPosition < SEEK_KEYFRAME_DISCOVERY_THRESHOLD_MS) {
		return 0;
	}
	AVStream * stream = GET_STREAM(player, video);
	AVPacket packet;
	int64_t bestPosition = initialPosition;
	int scannedVideoPackets = 0;
	int64_t startedAt = getTime();
	while (!isSeekCancelled(player)) {
		int readResult = av_read_frame(player->av.format, &packet);
		if (readResult < 0) {
			break;
		}
		if (packet.stream_index == player->av.videoStreamIndex) {
			scannedVideoPackets++;
			int64_t packetPosition = getTimestampPositionMs(player, packet.pts, stream->time_base);
			int64_t decodePosition = getTimestampPositionMs(player, packet.dts, stream->time_base);
			if ((packet.flags & AV_PKT_FLAG_KEY) && packetPosition >= 0
					&& packetPosition <= targetPosition + 100 && packetPosition > bestPosition) {
				bestPosition = packetPosition;
			}
			int64_t progressPosition = decodePosition >= 0 ? decodePosition : packetPosition;
			if (progressPosition > targetPosition + 100) {
				av_packet_unref(&packet);
				break;
			}
		}
		av_packet_unref(&packet);
	}
	if (isSeekCancelled(player)) {
		return -1;
	}
	*discoveredPosition = bestPosition;
	diagnosticsLog("player=%u seek_keyframe_discovery initial_ms=%" PRId64
			" selected_ms=%" PRId64 " target_ms=%" PRId64
			" video_packets=%d elapsed_ms=%" PRId64,
			player->meta.diagnosticsId, initialPosition, bestPosition, targetPosition,
			scannedVideoPackets, getTime() - startedAt);
	return 1;
}

static int seekToDecodableVideoPosition(Player * player, int64_t targetPosition,
		int * usedStreamIndex, int64_t * keyframePosition, int * recoveredFromNonKey,
		AVPacket ** primedVideoPacket) {
	int64_t indexedPosition = -1;
	int hasIndexedPosition = getIndexedVideoKeyframePosition(player, targetPosition, &indexedPosition);
	if (hasIndexedPosition) {
		diagnosticsLog("player=%u seek_keyframe_index target_ms=%" PRId64 " indexed_ms=%" PRId64,
				player->meta.diagnosticsId, targetPosition, indexedPosition);
	}
	int directAttempt = 1;
	int backoffAttempt = 1;
	int64_t lastCandidate = -1;
	while (!isSeekCancelled(player)) {
		int64_t candidate;
		if (directAttempt) {
			// Ask the demuxer for its nearest preceding random-access point first. In
			// Matroska/WebM the AVStream index may initially contain only the first cue
			// and becomes more complete as packets are read, so treating that partial
			// index as authoritative can force decoding from the beginning of the file.
			candidate = targetPosition;
			directAttempt = 0;
		} else if (hasIndexedPosition) {
			// Use the exact indexed timestamp. Subtracting even one millisecond makes
			// cluster-based demuxers select the keyframe preceding the intended one.
			candidate = indexedPosition;
			hasIndexedPosition = 0;
		} else {
			int64_t step = (int64_t) backoffAttempt * backoffAttempt * 1000;
			candidate = max64(targetPosition - step, 0);
			backoffAttempt++;
		}
		if (candidate == lastCandidate) {
			if (candidate <= 0) {
				break;
			}
			continue;
		}
		lastCandidate = candidate;
		int streamIndex;
		int globalFallback;
		int seekResult = seekFrame(player, candidate, AVSEEK_FLAG_BACKWARD,
				&streamIndex, &globalFallback);
		diagnosticsLog("player=%u seek_keyframe_probe candidate_ms=%" PRId64
				" result=%d stream=%d global_fallback=%d",
				player->meta.diagnosticsId, candidate, seekResult, streamIndex, globalFallback);
		int64_t foundKeyframePosition = -1;
		if (seekResult >= 0 && probeVideoKeyframe(player, targetPosition, -1, candidate <= 0,
				&foundKeyframePosition, primedVideoPacket)) {
			int64_t discoveredPosition;
			int discoveryResult = discoverCloserVideoKeyframe(player, targetPosition,
					foundKeyframePosition, &discoveredPosition);
			if (discoveryResult != 0) {
				av_packet_free(primedVideoPacket);
				if (discoveryResult < 0) {
					break;
				}
				seekResult = seekFrame(player, discoveredPosition, AVSEEK_FLAG_BACKWARD,
						&streamIndex, &globalFallback);
				foundKeyframePosition = -1;
				if (seekResult < 0 || !probeVideoKeyframe(player, targetPosition, discoveredPosition,
						discoveredPosition <= 0, &foundKeyframePosition, primedVideoPacket)) {
					diagnosticsLog("player=%u seek_keyframe_discovery_reseek_failed selected_ms=%" PRId64
							" result=%d", player->meta.diagnosticsId, discoveredPosition, seekResult);
					continue;
				}
				diagnosticsLog("player=%u seek_keyframe_discovery_reseek selected_ms=%" PRId64
						" primed_ms=%" PRId64 " result=%d",
						player->meta.diagnosticsId, discoveredPosition, foundKeyframePosition, seekResult);
			}
			diagnosticsLog("player=%u seek_keyframe_selected candidate_ms=%" PRId64
					" keyframe_ms=%" PRId64 " result=%d primed=1",
					player->meta.diagnosticsId, candidate, foundKeyframePosition, seekResult);
			*usedStreamIndex = streamIndex;
			*keyframePosition = foundKeyframePosition;
			*recoveredFromNonKey = foundKeyframePosition < targetPosition;
			return seekResult;
		}
		if (candidate <= 0) {
			break;
		}
	}
	if (isSeekCancelled(player)) {
		av_packet_free(primedVideoPacket);
		*usedStreamIndex = player->av.videoStreamIndex;
		*keyframePosition = targetPosition;
		*recoveredFromNonKey = 0;
		return -1;
	}

	// A missing or malformed index must not leave a flushed inter-frame decoder
	// waiting forever. Beginning-of-file decoding is the universal safe fallback.
	int streamIndex;
	int globalFallback;
	int result = seekFrame(player, 0, AVSEEK_FLAG_BACKWARD, &streamIndex, &globalFallback);
	diagnosticsLog("player=%u seek_keyframe_fallback_start result=%d stream=%d",
			player->meta.diagnosticsId, result, streamIndex);
	*usedStreamIndex = streamIndex;
	*keyframePosition = 0;
	*recoveredFromNonKey = 1;
	return result;
}

void setPosition(JNIEnv * env, jlong pointer, jlong position) {
	Player * player = POINTER_CAST(pointer);
	if (position < 0) {
		return;
	}
	int64_t originalPosition = position;
	int64_t duration = getDuration(pointer);
	if (duration > 0) {
		position = min64(position, duration);
	}
	int64_t requestedPosition = position;
	diagnosticsLog("player=%u seek requested_position_ms=%" PRId64
			" clamped_position_ms=%" PRId64 " duration_ms=%" PRId64,
			player->meta.diagnosticsId, originalPosition, requestedPosition, duration);

	// The read mutex serializes demuxer access for the whole seek. Decoder, audio and
	// rendering locks are held only while their state is reset, so pausing and closing
	// the player never wait for precise packet scanning or a streaming range request.
	Bridge * bridge = playerObtainBridge(player, env);
	diagnosticsLogSeekLock(player, "prepare", "packets.read", "waiting");
	pthread_mutex_lock(&player->decode.packets.readMutex);
	diagnosticsLogSeekLock(player, "prepare", "packets.read", "acquired");
	diagnosticsLog("player=%u seek_phase=prepare_started", player->meta.diagnosticsId);
	diagnosticsLogSeekLock(player, "prepare", "packets.flow", "waiting");
	pthread_mutex_lock(&player->decode.packets.flowMutex);
	diagnosticsLogSeekLock(player, "prepare", "packets.flow", "acquired");
	uint64_t packetGeneration = __atomic_add_fetch(&player->decode.packets.generation, 1,
			__ATOMIC_ACQ_REL);
	diagnosticsLog("player=%u seek_packet_generation=%" PRIu64,
			player->meta.diagnosticsId, packetGeneration);
	requestSeekWorkersStop(player);
	diagnosticsLogSeekLock(player, "prepare", "audio.frame", "waiting");
	pthread_mutex_lock(&player->decode.audio.frameMutex);
	diagnosticsLogSeekLock(player, "prepare", "audio.frame", "acquired");
	diagnosticsLogSeekLock(player, "prepare", "video.frame", "waiting");
	pthread_mutex_lock(&player->decode.video.frameMutex);
	diagnosticsLogSeekLock(player, "prepare", "video.frame", "acquired");
	playerSetSkipFlag(&player->sync.skip.audioWorkFrame, 1);
	playerSetSkipFlag(&player->sync.skip.videoWorkFrame, 1);
	diagnosticsLog("player=%u seek_phase=packet_queues_clear_started",
			player->meta.diagnosticsId);
	blockingQueueClear(&player->audio.packetQueue, playerPacketQueueFreeCallback);
	blockingQueueClear(&player->video.packetQueue, playerPacketQueueFreeCallback);
	diagnosticsLog("player=%u seek_phase=packet_queues_clear_finished",
			player->meta.diagnosticsId);
	player->decode.packets.finished = 0;
	player->audio.finished = 0;
	player->video.finished = 0;

	diagnosticsLogSeekLock(player, "prepare", "audio.sleep_buffer", "waiting");
	pthread_mutex_lock(&player->audio.sleepBufferMutex);
	diagnosticsLogSeekLock(player, "prepare", "audio.sleep_buffer", "acquired");
	diagnosticsLog("player=%u seek_phase=audio_output_clear_started",
			player->meta.diagnosticsId);
	playerAudioClearOutputLocked(player, 1);
	pthread_cond_broadcast(&player->audio.sleepCond);
	pthread_cond_broadcast(&player->audio.bufferCond);
	diagnosticsLog("player=%u seek_phase=audio_output_clear_finished",
			player->meta.diagnosticsId);
	pthread_mutex_unlock(&player->audio.sleepBufferMutex);

	diagnosticsLogSeekLock(player, "prepare", "video.sleep_draw", "waiting");
	pthread_mutex_lock(&player->video.sleepDrawMutex);
	diagnosticsLogSeekLock(player, "prepare", "video.sleep_draw", "acquired");
	diagnosticsLogSeekLock(player, "prepare", "video.queue", "waiting");
	pthread_mutex_lock(&player->video.queueMutex);
	diagnosticsLogSeekLock(player, "prepare", "video.queue", "acquired");
	diagnosticsLog("player=%u seek_phase=video_output_clear_started",
			player->meta.diagnosticsId);
	playerSetSkipFlag(&player->sync.skip.drawWorkFrame, 1);
	if (player->video.bufferQueue) {
		bufferQueueClear(player->video.bufferQueue, playerVideoBufferQueueFreeCallback);
	}
	pthread_cond_broadcast(&player->video.sleepCond);
	pthread_cond_broadcast(&player->video.queueCond);
	diagnosticsLog("player=%u seek_phase=video_output_clear_finished",
			player->meta.diagnosticsId);
	pthread_mutex_unlock(&player->video.queueMutex);
	pthread_mutex_unlock(&player->video.sleepDrawMutex);

	if (HAS_STREAM(player, audio)) {
		diagnosticsLog("player=%u seek_phase=audio_flush_started", player->meta.diagnosticsId);
		avcodec_flush_buffers(GET_CONTEXT(player, audio));
		diagnosticsLog("player=%u seek_phase=audio_flush_finished", player->meta.diagnosticsId);
	}
	if (HAS_STREAM(player, video)) {
		diagnosticsLog("player=%u seek_phase=video_flush_started hardware=%d",
				player->meta.diagnosticsId, player->video.hardwareDecoderActive);
		if (!player->video.hardwareDecoderActive) {
			playerVideoSoftwareRestoreSeekFastLocked(player, GET_CONTEXT(player, video),
					"seek_prepare", -1);
			playerVideoSoftwareResetGovernorLocked(player, GET_CONTEXT(player, video),
					"seek_prepare");
		}
		avcodec_flush_buffers(GET_CONTEXT(player, video));
		diagnosticsLog("player=%u seek_phase=video_flush_finished hardware=%d",
				player->meta.diagnosticsId, player->video.hardwareDecoderActive);
	}
	pthread_cond_broadcast(&player->decode.packets.flowCond);
	pthread_mutex_unlock(&player->decode.video.frameMutex);
	pthread_mutex_unlock(&player->decode.audio.frameMutex);
	pthread_mutex_unlock(&player->decode.packets.flowMutex);
	diagnosticsLog("player=%u seek_phase=prepare_finished", player->meta.diagnosticsId);

	if (isSeekCancelled(player)) {
		diagnosticsLog("player=%u seek_cancelled phase=after_prepare", player->meta.diagnosticsId);
		pthread_mutex_unlock(&player->decode.packets.readMutex);
		return;
	}

	if (player->meta.seekAnyFrame) {
		diagnosticsLog("player=%u seek_phase=precise_scan_started target_ms=%" PRId64,
				player->meta.diagnosticsId, (int64_t) position);
		int64_t audioPosition = HAS_STREAM(player, audio) ? -1 : position;
		int64_t videoPosition = HAS_STREAM(player, video) ? -1 : position;
		AVPacket packet;
		for (int i = 1; (audioPosition == -1 || videoPosition == -1) && !isSeekCancelled(player); i++) {
			int64_t step = (int64_t) i * i * 1000;
			int64_t previousStep = (int64_t) (i - 1) * (i - 1) * 1000;
			int64_t seekPosition = max64(position - step, 0);
			int64_t maxPosition = max64(position - previousStep, 0);
			seekFrame(player, seekPosition, AVSEEK_FLAG_BACKWARD | AVSEEK_FLAG_ANY, NULL, NULL);
			while (!isSeekCancelled(player)) {
				if (av_read_frame(player->av.format, &packet) < 0) {
					break;
				}
				if (packet.pts != AV_NOPTS_VALUE) {
					int64_t * outPosition = NULL;
					if (packet.stream_index == player->av.audioStreamIndex) {
						outPosition = &audioPosition;
					} else if (packet.stream_index == player->av.videoStreamIndex) {
						outPosition = &videoPosition;
					}
					if (outPosition) {
						AVRational timeBase = player->av.format->streams[packet.stream_index]->time_base;
						int64_t timestamp = getTimestampPositionMs(player, packet.pts, timeBase);
						if (timestamp > maxPosition) {
							av_packet_unref(&packet);
							break;
						}
						if (timestamp > *outPosition) {
							*outPosition = timestamp;
						}
					}
				}
				av_packet_unref(&packet);
			}
			if (seekPosition <= 0) {
				break;
			}
		}
		if (isSeekCancelled(player)) {
			diagnosticsLog("player=%u seek_cancelled phase=precise_scan", player->meta.diagnosticsId);
			pthread_mutex_unlock(&player->decode.packets.readMutex);
			return;
		}
		if (audioPosition == -1) {
			audioPosition = position;
		}
		if (videoPosition == -1) {
			videoPosition = position;
		}
		position = min64(audioPosition, videoPosition);
		diagnosticsLog("player=%u seek_phase=precise_scan_finished resolved_ms=%" PRId64,
				player->meta.diagnosticsId, (int64_t) position);
	}

	int seekStreamIndex;
	int64_t keyframePosition = position;
	int recoveredFromNonKey = 0;
	AVPacket * primedVideoPacket = NULL;
	int seekResult;
	if (HAS_STREAM(player, video)) {
		seekResult = seekToDecodableVideoPosition(player, position,
				&seekStreamIndex, &keyframePosition, &recoveredFromNonKey, &primedVideoPacket);
	} else {
		seekResult = seekFrame(player, position, AVSEEK_FLAG_BACKWARD,
				&seekStreamIndex, NULL);
	}
	diagnosticsLog("player=%u seek_phase=final_seek result=%d stream=%d requested_ms=%" PRId64
			" resolved_ms=%" PRId64 " keyframe_ms=%" PRId64 " recovered=%d",
			player->meta.diagnosticsId, seekResult, seekStreamIndex,
			requestedPosition, (int64_t) position, keyframePosition, recoveredFromNonKey);
	if (isSeekCancelled(player)) {
		diagnosticsLog("player=%u seek_cancelled phase=after_final_seek", player->meta.diagnosticsId);
		av_packet_free(&primedVideoPacket);
		pthread_mutex_unlock(&player->decode.packets.readMutex);
		return;
	}

	diagnosticsLogSeekLock(player, "commit", "play.finish", "waiting");
	pthread_mutex_lock(&player->play.finishMutex);
	diagnosticsLogSeekLock(player, "commit", "play.finish", "acquired");
	diagnosticsLogSeekLock(player, "commit", "packets.flow", "waiting");
	pthread_mutex_lock(&player->decode.packets.flowMutex);
	diagnosticsLogSeekLock(player, "commit", "packets.flow", "acquired");
	diagnosticsLogSeekLock(player, "commit", "audio.frame", "waiting");
	pthread_mutex_lock(&player->decode.audio.frameMutex);
	diagnosticsLogSeekLock(player, "commit", "audio.frame", "acquired");
	diagnosticsLogSeekLock(player, "commit", "video.frame", "waiting");
	pthread_mutex_lock(&player->decode.video.frameMutex);
	diagnosticsLogSeekLock(player, "commit", "video.frame", "acquired");
	diagnosticsLogSeekLock(player, "commit", "audio.sleep_buffer", "waiting");
	pthread_mutex_lock(&player->audio.sleepBufferMutex);
	diagnosticsLogSeekLock(player, "commit", "audio.sleep_buffer", "acquired");
	diagnosticsLogSeekLock(player, "commit", "video.sleep_draw", "waiting");
	pthread_mutex_lock(&player->video.sleepDrawMutex);
	diagnosticsLogSeekLock(player, "commit", "video.sleep_draw", "acquired");
	diagnosticsLogSeekLock(player, "commit", "video.queue", "waiting");
	pthread_mutex_lock(&player->video.queueMutex);
	diagnosticsLogSeekLock(player, "commit", "video.queue", "acquired");
	updateAudioPositionSurrogate(player, position, 1);
	player->sync.audioPosition = position;
	player->sync.videoPosition = position;
	player->sync.pausedPosition = position;
	player->sync.audioPositionNotSync = 1;
	player->sync.videoPositionNotSync = 1;
	player->sync.seekFirstVideoPacketPending = HAS_STREAM(player, video);
	player->sync.seekDiscardBeforeTarget = player->meta.seekAnyFrame || recoveredFromNonKey;
	player->sync.seekTargetPosition = requestedPosition;
	if (HAS_STREAM(player, video) && seekResult >= 0 && recoveredFromNonKey &&
			!player->video.hardwareDecoderActive) {
		playerVideoSoftwareStartSeekFastLocked(player, GET_CONTEXT(player, video),
				keyframePosition, position);
	}
	playerSetSkipFlag(&player->sync.skip.audioWorkFrame, 1);
	playerSetSkipFlag(&player->sync.skip.videoWorkFrame, 1);
	playerSetSkipFlag(&player->sync.skip.drawWorkFrame, 1);
	player->sync.lastDrawTimes[0] = 0;
	player->sync.lastDrawTimes[1] = 0;
	if (primedVideoPacket) {
		PacketHolder * packetHolder = createPacketHolder(0);
		packetHolder->packet = primedVideoPacket;
		primedVideoPacket = NULL;
		blockingQueueAdd(&player->video.packetQueue, packetHolder);
		player->video.finished = 0;
		player->sync.seekFirstVideoPacketPending = 0;
		diagnosticsLog("player=%u seek_first_video_packet pts=%" PRId64
				" dts=%" PRId64 " key=%d target_ms=%" PRId64 " primed=1",
				player->meta.diagnosticsId, packetHolder->packet->pts, packetHolder->packet->dts,
				!!(packetHolder->packet->flags & AV_PKT_FLAG_KEY), player->sync.seekTargetPosition);
		diagnosticsRecordVideoPacket(player, packetHolder->packet);
	}
	if (HAS_STREAM(player, video)) {
		PLAYER_SEND_MESSAGE(env, player, bridge, BRIDGE_MESSAGE_START_SEEKING);
	}
	pthread_cond_broadcast(&player->play.finishCond);
	pthread_cond_broadcast(&player->decode.packets.flowCond);
	pthread_cond_broadcast(&player->audio.sleepCond);
	pthread_cond_broadcast(&player->audio.bufferCond);
	pthread_cond_broadcast(&player->video.sleepCond);
	pthread_cond_broadcast(&player->video.queueCond);
	pthread_mutex_unlock(&player->video.queueMutex);
	pthread_mutex_unlock(&player->video.sleepDrawMutex);
	pthread_mutex_unlock(&player->audio.sleepBufferMutex);
	pthread_mutex_unlock(&player->decode.video.frameMutex);
	pthread_mutex_unlock(&player->decode.audio.frameMutex);
	pthread_mutex_unlock(&player->decode.packets.flowMutex);
	pthread_mutex_unlock(&player->play.finishMutex);
	pthread_mutex_unlock(&player->decode.packets.readMutex);
	diagnosticsLog("player=%u seek_completed position_ms=%" PRId64,
			player->meta.diagnosticsId, (int64_t) position);
}

void setRange(jlong pointer, jlong start, jlong end, jlong total) {
	Player * player = POINTER_CAST(pointer);
	pthread_mutex_lock(&player->file.controlMutex);
	player->file.start = start;
	player->file.end = end;
	player->file.total = total;
	LOG("set range range=[%ld-%ld/%ld]", player->file.start, player->file.end, player->file.total);
	maybeStartDurationProbeLocked(player);
	pthread_cond_broadcast(&player->file.controlCond);
	pthread_mutex_unlock(&player->file.controlMutex);
}

void setCancelSeek(jlong pointer, jboolean cancelSeek) {
	Player * player = POINTER_CAST(pointer);
	pthread_mutex_lock(&player->file.controlMutex);
	player->file.cancelSeek = !!cancelSeek;
	pthread_cond_broadcast(&player->file.controlCond);
	pthread_mutex_unlock(&player->file.controlMutex);
}

void setPlaybackSpeed(jlong pointer, jint speed) {
	Player * player = POINTER_CAST(pointer);
	speed = clampPlaybackSpeed(speed);
	if (player->sync.playbackSpeed != speed) {
		pthread_mutex_lock(&player->play.finishMutex);
		pthread_mutex_lock(&player->audio.sleepBufferMutex);
		pthread_mutex_lock(&player->video.sleepDrawMutex);
		int64_t position = calculatePosition(player, 1);
		player->sync.playbackSpeed = speed;
		player->sync.audioPosition = position;
		player->sync.pausedPosition = position;
		updateAudioPositionSurrogate(player, position, 1);
		if (HAS_STREAM(player, audio)) {
			playerSetSkipFlag(&player->sync.skip.audioWorkFrame, 1);
			playerAudioClearOutputLocked(player, 1);
			player->sync.audioPositionNotSync = 0;
		}
		pthread_cond_broadcast(&player->audio.sleepCond);
		pthread_cond_broadcast(&player->audio.bufferCond);
		pthread_cond_broadcast(&player->video.sleepCond);
		pthread_mutex_unlock(&player->video.sleepDrawMutex);
		pthread_mutex_unlock(&player->audio.sleepBufferMutex);
		pthread_mutex_unlock(&player->play.finishMutex);
	}
}

void setPlaying(jlong pointer, jboolean playing) {
	Player * player = POINTER_CAST(pointer);
	playing = !!playing;
	if (player->play.playing != playing) {
		diagnosticsLog("player=%u playing=%d", player->meta.diagnosticsId, playing);
		LOG("switch playing %d", playing);
		pthread_mutex_lock(&player->play.finishMutex);
		if (playing) {
			updateAudioPositionSurrogate(player, player->sync.pausedPosition, 1);
		} else {
			player->sync.pausedPosition = calculatePosition(player, 1);
		}
		player->play.playing = playing;
		pthread_cond_broadcast(&player->play.finishCond);
		pthread_mutex_unlock(&player->play.finishMutex);
		if (!playing && HAS_STREAM(player, video) && !player->video.hardwareDecoderActive) {
			pthread_mutex_lock(&player->decode.video.frameMutex);
			playerVideoSoftwareResetGovernorLocked(player, GET_CONTEXT(player, video), "pause");
			pthread_mutex_unlock(&player->decode.video.frameMutex);
		}
		if (HAS_STREAM(player, audio)) {
			pthread_mutex_lock(&player->audio.sleepBufferMutex);
			(*player->audio.sl.play)->SetPlayState(player->audio.sl.play,
					playing ? SL_PLAYSTATE_PLAYING : SL_PLAYSTATE_PAUSED);
			if (playing && player->audio.bufferNeedEnqueueAfterDecode
					&& blockingQueueCount(&player->audio.bufferQueue) > 0) {
				// Queue count checked to free from obligation to handle audio finish flag
				playerAudioEnqueueBuffer(player);
			}
			pthread_mutex_unlock(&player->audio.sleepBufferMutex);
		}
	}
}

static jstring newUtfStringSafe(JNIEnv * env, char * string) {
    // Fixes "input is not valid Modified UTF-8" error
    if (string) {
        int length = strlen(string);
        jbyteArray array = (*env)->NewByteArray(env, length);
        (*env)->SetByteArrayRegion(env, array, 0, length, (void *) string);
        jclass class = (*env)->FindClass(env, "java/lang/String");
        jmethodID constructor = (*env)->GetMethodID(env, class, "<init>", "([B)V");
        jstring result = (*env)->NewObject(env, class, constructor, array);
        (*env)->DeleteLocalRef(env, array);
        return result;
    }
    return 0;
}

jobjectArray getMetadata(JNIEnv * env, jlong pointer) {
	char buffer[24];
	Player * player = POINTER_CAST(pointer);
	int entries = av_dict_count(player->av.format->metadata) + 3;
	if (HAS_STREAM(player, video)) {
		// Format, decoder backend, width, height, frame rate, pixel format, canvas format, conversion
		entries += 8;
	}
	if (HAS_STREAM(player, audio)) {
		// Format, channels, sample rate
		entries += 3;
	}
	jobjectArray result = (*env)->NewObjectArray(env, 2 * entries, (*env)->FindClass(env, "java/lang/String"), NULL);
	int index = 0;
	(*env)->SetObjectArrayElement(env, result, index++, (*env)->NewStringUTF(env, "player_ffmpeg"));
	(*env)->SetObjectArrayElement(env, result, index++, (*env)->NewStringUTF(env,
			FFMPEG_VERSION " (" DASHCHAN_FFMPEG_FLAVOR ")"));
	(*env)->SetObjectArrayElement(env, result, index++, (*env)->NewStringUTF(env, "player_libavformat"));
	(*env)->SetObjectArrayElement(env, result, index++, (*env)->NewStringUTF(env, LIBAVFORMAT_IDENT));
	(*env)->SetObjectArrayElement(env, result, index++, (*env)->NewStringUTF(env, "speed_processing"));
#ifdef DASHCHAN_HAS_ATEMPO
	(*env)->SetObjectArrayElement(env, result, index++, (*env)->NewStringUTF(env, "FFmpeg atempo"));
#else
	(*env)->SetObjectArrayElement(env, result, index++, (*env)->NewStringUTF(env, "sample-rate fallback"));
#endif
	if (HAS_STREAM(player, video)) {
		AVStream * videoStream = GET_STREAM(player, video);
		AVCodecContext * videoContext = GET_CONTEXT(player, video);
		(*env)->SetObjectArrayElement(env, result, index++, (*env)->NewStringUTF(env, "video_format"));
		(*env)->SetObjectArrayElement(env, result, index++, (*env)->NewStringUTF(env,
				videoContext->codec->long_name));
		(*env)->SetObjectArrayElement(env, result, index++, (*env)->NewStringUTF(env, "video_decoder_backend"));
		(*env)->SetObjectArrayElement(env, result, index++, (*env)->NewStringUTF(env,
				player->video.hardwareDecoderActive ? "MediaCodec" : "FFmpeg software"));
		sprintf(buffer, "%d", videoContext->width);
		(*env)->SetObjectArrayElement(env, result, index++, (*env)->NewStringUTF(env, "width"));
		(*env)->SetObjectArrayElement(env, result, index++, (*env)->NewStringUTF(env, buffer));
		sprintf(buffer, "%d", videoContext->height);
		(*env)->SetObjectArrayElement(env, result, index++, (*env)->NewStringUTF(env, "height"));
		(*env)->SetObjectArrayElement(env, result, index++, (*env)->NewStringUTF(env, buffer));
		sprintf(buffer, "%.3lf", av_q2d(videoStream->r_frame_rate));
		(*env)->SetObjectArrayElement(env, result, index++, (*env)->NewStringUTF(env, "frame_rate"));
		(*env)->SetObjectArrayElement(env, result, index++, (*env)->NewStringUTF(env, buffer));
		const AVPixFmtDescriptor * pixFmtDesctiptor = av_pix_fmt_desc_get(videoContext->pix_fmt);
		(*env)->SetObjectArrayElement(env, result, index++, (*env)->NewStringUTF(env, "pixel_format"));
		(*env)->SetObjectArrayElement(env, result, index++, (*env)->NewStringUTF(env,
				pixFmtDesctiptor ? pixFmtDesctiptor->name : "Unknown"));
		(*env)->SetObjectArrayElement(env, result, index++, (*env)->NewStringUTF(env, "surface_format"));
		int format = player->video.window ? ANativeWindow_getFormat(player->video.window) : -1;
		switch (format) {
			case WINDOW_FORMAT_RGBA_8888: {
				(*env)->SetObjectArrayElement(env, result, index++, (*env)->NewStringUTF(env, "RGBA 8888"));
				break;
			}
			case WINDOW_FORMAT_RGBX_8888: {
				(*env)->SetObjectArrayElement(env, result, index++, (*env)->NewStringUTF(env, "RGBX 8888"));
				break;
			}
			case WINDOW_FORMAT_RGB_565: {
				(*env)->SetObjectArrayElement(env, result, index++, (*env)->NewStringUTF(env, "RGB 565"));
				break;
			}
			case WINDOW_FORMAT_YV12: {
				(*env)->SetObjectArrayElement(env, result, index++, (*env)->NewStringUTF(env, "YV12"));
				break;
			}
			default: {
				(*env)->SetObjectArrayElement(env, result, index++, NULL);
				break;
			}
		}
		(*env)->SetObjectArrayElement(env, result, index++, (*env)->NewStringUTF(env, "frame_conversion"));
		(*env)->SetObjectArrayElement(env, result, index++, (*env)->NewStringUTF(env,
				player->video.hardwareDecoderActive ? "MediaCodec surface" :
				player->video.useLibyuv == 1 ? "libyuv" :
				player->video.useLibyuv == 0 ? "libswscale" : "Unknown"));
	}
	if (HAS_STREAM(player, audio)) {
		AVCodecContext * audioContext = GET_CONTEXT(player, audio);
		(*env)->SetObjectArrayElement(env, result, index++, (*env)->NewStringUTF(env, "audio_format"));
		(*env)->SetObjectArrayElement(env, result, index++, (*env)->NewStringUTF(env,
				audioContext->codec->long_name));
		sprintf(buffer, "%d", playerAudioGetContextChannels(audioContext));
		(*env)->SetObjectArrayElement(env, result, index++, (*env)->NewStringUTF(env, "channels"));
		(*env)->SetObjectArrayElement(env, result, index++, (*env)->NewStringUTF(env, buffer));
		sprintf(buffer, "%d", audioContext->sample_rate);
		(*env)->SetObjectArrayElement(env, result, index++, (*env)->NewStringUTF(env, "sample_rate"));
		(*env)->SetObjectArrayElement(env, result, index++, (*env)->NewStringUTF(env, buffer));
	}
	AVDictionaryEntry * entry = NULL;
	while ((entry = av_dict_get(player->av.format->metadata, "", entry, AV_DICT_IGNORE_SUFFIX))) {
		(*env)->SetObjectArrayElement(env, result, index++, newUtfStringSafe(env, entry->key));
		(*env)->SetObjectArrayElement(env, result, index++, newUtfStringSafe(env, entry->value));
	}
	return result;
}

void initLibs(JavaVM * javaVM) {
	loadJavaVM = javaVM;
#ifdef DASHCHAN_HAS_MEDIACODEC
	if (av_jni_set_java_vm(javaVM, NULL) < 0) {
		LOGP("Cannot register Java VM for MediaCodec");
	}
#endif
	playerAudioInitializeLibrary();
}
