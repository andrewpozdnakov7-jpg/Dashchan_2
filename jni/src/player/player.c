#include "player.h"
#include "player_audio.h"
#include "player_diagnostics.h"
#include "player_demux.h"
#include "player_duration.h"
#include "player_internal.h"
#include "player_seek.h"
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
		bridge->methodFindHardwareVideoDecoder = (*env)->GetMethodID(env, class,
				"findHardwareVideoDecoder", "(Ljava/lang/String;IIFIIII)Ljava/lang/String;");
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
			&playerDemuxRead, NULL, &playerDemuxSeek);
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
	if (pthread_create(&player->decode.packets.thread, NULL, &playerDemuxRun, player) != 0) {
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
		playerDemuxJoinWorkers(player);
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

void setPosition(JNIEnv * env, jlong pointer, jlong position) {
	playerSeekSetPosition(env, POINTER_CAST(pointer), position);
}

void setRange(jlong pointer, jlong start, jlong end, jlong total) {
	playerDemuxSetRange(POINTER_CAST(pointer), start, end, total);
}

void setCancelSeek(jlong pointer, jboolean cancelSeek) {
	playerDemuxSetCancelSeek(POINTER_CAST(pointer), !!cancelSeek);
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
			playerAudioPrepareOutputResetLocked(player, 1, "playback_speed");
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

void playerApplyPlaying(Player * player, int playing) {
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
		if (playing) {
			__atomic_store_n(&player->play.pausedSeekState, PAUSED_SEEK_NONE, __ATOMIC_RELEASE);
		}
		pthread_cond_broadcast(&player->play.finishCond);
		pthread_mutex_unlock(&player->play.finishMutex);
		if (!playing && HAS_STREAM(player, video) && !player->video.hardwareDecoderActive) {
			pthread_mutex_lock(&player->decode.video.frameMutex);
			playerVideoSoftwareResetGovernorLocked(player, GET_CONTEXT(player, video), "pause");
			pthread_mutex_unlock(&player->decode.video.frameMutex);
		}
		if (HAS_STREAM(player, audio)) {
			pthread_mutex_lock(&player->audio.sleepBufferMutex);
			if (playing && player->audio.outputRestartPending) {
				// A seek/speed reset resumes OpenSL only after fresh PCM is queued.
				// Calling the enqueue helper also handles a queue that was prepared
				// while playback was paused.
				playerAudioEnqueueBuffer(player);
			} else {
				(*player->audio.sl.play)->SetPlayState(player->audio.sl.play,
						playing ? SL_PLAYSTATE_PLAYING : SL_PLAYSTATE_PAUSED);
			}
			if (playing && player->audio.bufferNeedEnqueueAfterDecode
					&& blockingQueueCount(&player->audio.bufferQueue) > 0) {
				// Queue count checked to free from obligation to handle audio finish flag
				playerAudioEnqueueBuffer(player);
			}
			pthread_mutex_unlock(&player->audio.sleepBufferMutex);
		}
	}
}

int playerVideoCanDecode(Player * player) {
	return player->play.playing || __atomic_load_n(&player->play.pausedSeekState,
			__ATOMIC_ACQUIRE) == PAUSED_SEEK_DECODING;
}

int playerVideoCanPresent(Player * player) {
	return player->play.playing || __atomic_load_n(&player->play.pausedSeekState,
			__ATOMIC_ACQUIRE) != PAUSED_SEEK_NONE;
}

void playerVideoSetPausedSeekPending(Player * player, int pending) {
	__atomic_store_n(&player->play.pausedSeekState,
			pending ? PAUSED_SEEK_DECODING : PAUSED_SEEK_NONE, __ATOMIC_RELEASE);
}

int playerVideoMarkPausedSeekFrameQueued(Player * player) {
	if (player->play.playing) {
		return 0;
	}
	int expected = PAUSED_SEEK_DECODING;
	return __atomic_compare_exchange_n(&player->play.pausedSeekState, &expected,
			PAUSED_SEEK_FRAME_QUEUED, 0, __ATOMIC_ACQ_REL, __ATOMIC_ACQUIRE);
}

void playerVideoCompletePausedSeekFrame(Player * player) {
	if (!player->play.playing) {
		int previous = __atomic_exchange_n(&player->play.pausedSeekState,
				PAUSED_SEEK_NONE, __ATOMIC_ACQ_REL);
		if (previous != PAUSED_SEEK_NONE) {
			diagnosticsLog("player=%u paused_seek_preview rendered", player->meta.diagnosticsId);
		}
	}
}

void playerSetPlaying(jlong pointer, jboolean playing) {
	playerApplyPlaying(POINTER_CAST(pointer), playing);
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
