#include "player_video_mediacodec.h"
#include "player.h"
#include "player_diagnostics.h"
#include "player_timing.h"
#include "player_video_software.h"
#include "util.h"
#include <libavcodec/avcodec.h>
#ifdef DASHCHAN_HAS_MEDIACODEC
#include <libavcodec/mediacodec.h>
#include <libavutil/hwcontext.h>
#include <libavutil/hwcontext_mediacodec.h>
#include <libavutil/opt.h>
#include <libavutil/pixdesc.h>
#endif
#include <android/native_window_jni.h>
#include <inttypes.h>
#define GAINING_THRESHOLD 100
#define MEDIACODEC_MAX_SCHEDULE_AHEAD_MS 50

int playerVideoHasPendingSurface(Player * player) {
	return __atomic_load_n(&player->video.surfaceRequestPending, __ATOMIC_ACQUIRE);
}
void playerVideoReleaseSurface(Player * player) {
	if (player->video.window) {
		ANativeWindow_release(player->video.window);
		player->video.window = NULL;
	}
}

#ifdef DASHCHAN_HAS_MEDIACODEC
static int decodeMediaCodecFrame(Player * player, AVCodecContext * context, AVPacket * packet,
		AVFrame * frame, int * packetSent) {
	if (!*packetSent) {
		playerSetDiagnosticsMediaCodecStage(player, DIAGNOSTICS_MEDIACODEC_STAGE_SEND_PACKET, -1);
		int result = avcodec_send_packet(context, packet);
		if (result == 0) {
			*packetSent = 1;
			if (packet) {
				diagnosticsRecordPacketSubmitted();
			}
		} else if (result != AVERROR(EAGAIN) && result != AVERROR_EOF) {
			LOGP("MediaCodec packet submission failed: %d", result);
			diagnosticsRecordDecoderError(player, "send_packet", result);
			return -1;
		}
	}
	playerSetDiagnosticsMediaCodecStage(player, DIAGNOSTICS_MEDIACODEC_STAGE_RECEIVE_FRAME, -1);
	int result = avcodec_receive_frame(context, frame);
	if (result == 0) {
		return 1;
	}
	if (result == AVERROR(EAGAIN) || result == AVERROR_EOF) {
		return 0;
	}
	LOGP("MediaCodec frame receive failed: %d", result);
	diagnosticsRecordDecoderError(player, "receive_frame", result);
	return -1;
}

static int renderMediaCodecFrame(Player * player, JNIEnv * env, AVStream * stream, AVFrame * frame) {
	AVMediaCodecBuffer * buffer = (AVMediaCodecBuffer *) frame->data[3];
	int64_t framePosition = getFramePositionMs(player, frame, stream);
	playerSetDiagnosticsMediaCodecStage(player, DIAGNOSTICS_MEDIACODEC_STAGE_WAIT_SLEEP_DRAW_MUTEX,
			framePosition);
	if (frame->format != AV_PIX_FMT_MEDIACODEC || !buffer) {
		diagnosticsLog("player=%u mediacodec_output invalid_format=%d expected_format=%d"
				" output_buffer=%d",
				player->meta.diagnosticsId, frame->format, AV_PIX_FMT_MEDIACODEC,
				buffer != NULL);
		diagnosticsRecordOutput(player, frame, framePosition, 0,
				DIAGNOSTICS_OUTPUT_NO_BUFFER, 0);
		playerSetDiagnosticsMediaCodecStage(player, DIAGNOSTICS_MEDIACODEC_STAGE_UNREF_FRAME,
				framePosition);
		av_frame_unref(frame);
		playerSetDiagnosticsMediaCodecStage(player, DIAGNOSTICS_MEDIACODEC_STAGE_IDLE,
				framePosition);
		return -1;
	}
	int render = 1;
	int renderResult = 0;
	int outputAction = DIAGNOSTICS_OUTPUT_IMMEDIATE;
	int64_t waitTime = 0;
	int finishSeeking = 0;
	pthread_mutex_lock(&player->video.sleepDrawMutex);
	playerSetDiagnosticsMediaCodecStage(player, DIAGNOSTICS_MEDIACODEC_STAGE_RENDER_FRAME,
			framePosition);
	if (player->meta.interrupt || playerGetSkipFlag(&player->sync.skip.videoWorkFrame)) {
		render = 0;
		outputAction = DIAGNOSTICS_OUTPUT_DROPPED_STATE;
		goto RELEASE_BUFFER;
	}
	if (framePosition >= 0 && player->sync.seekDiscardBeforeTarget &&
			player->sync.videoPositionNotSync &&
			framePosition < player->sync.videoPosition) {
		render = 0;
		outputAction = DIAGNOSTICS_OUTPUT_DROPPED_SEEK;
		goto RELEASE_BUFFER;
	}
	int64_t position = calculatePosition(player, 1);
	if (framePosition >= 0) {
		player->sync.videoPosition = framePosition;
		waitTime = framePosition - position;
		if (player->sync.videoPositionNotSync) {
			finishSeeking = 1;
			diagnosticsLog("player=%u seek_first_frame position_ms=%" PRId64
					" target_ms=%" PRId64 " hardware=1",
					player->meta.diagnosticsId, framePosition,
					player->sync.seekTargetPosition);
			// Render the first post-seek output immediately. Scheduling it against
			// the still-running clock leaves the old frame visible while audio has
			// already resumed, especially when a sparse keyframe is slightly ahead.
			waitTime = 0;
		}
	}
	if (waitTime < -GAINING_THRESHOLD && HAS_STREAM(player, audio)) {
		render = 0;
		outputAction = DIAGNOSTICS_OUTPUT_DROPPED_LATE;
	} else if (!HAS_STREAM(player, audio) && -waitTime > GAINING_THRESHOLD) {
		player->sync.startTime -= waitTime;
		waitTime = 0;
	}
	while (render && waitTime > 0) {
		int64_t scaledWaitTime = unscalePlaybackPosition(player, waitTime);
		if (scaledWaitTime <= MEDIACODEC_MAX_SCHEDULE_AHEAD_MS) {
			int64_t renderTimeNs = getMonotonicTimeNs() + scaledWaitTime * 1000000LL;
			playerSetDiagnosticsMediaCodecStage(player, DIAGNOSTICS_MEDIACODEC_STAGE_SCHEDULE_BUFFER,
					framePosition);
			renderResult = av_mediacodec_render_buffer_at_time(buffer, renderTimeNs);
			outputAction = DIAGNOSTICS_OUTPUT_SCHEDULED;
			buffer = NULL;
			break;
		}
		int64_t wakeTime = getTime() + scaledWaitTime - MEDIACODEC_MAX_SCHEDULE_AHEAD_MS;
		playerSetDiagnosticsMediaCodecStage(player, DIAGNOSTICS_MEDIACODEC_STAGE_WAIT_RENDER_TIME,
				framePosition);
		condSleepUntilMs(&player->video.sleepCond, &player->video.sleepDrawMutex, wakeTime);
		if (player->meta.interrupt || playerGetSkipFlag(&player->sync.skip.videoWorkFrame)) {
			render = 0;
			outputAction = DIAGNOSTICS_OUTPUT_DROPPED_STATE;
			break;
		}
		position = calculatePosition(player, 1);
		waitTime = framePosition - position;
	}

	RELEASE_BUFFER:
	if (buffer) {
		playerSetDiagnosticsMediaCodecStage(player, DIAGNOSTICS_MEDIACODEC_STAGE_RELEASE_BUFFER,
				framePosition);
		renderResult = av_mediacodec_release_buffer(buffer, render);
	}
	if (renderResult < 0) {
		LOGP("MediaCodec output buffer release failed: %d", renderResult);
	}
	if (finishSeeking && render && renderResult >= 0) {
		player->sync.videoPositionNotSync = 0;
		playerVideoCompletePausedSeekFrame(player);
		diagnosticsLog("player=%u seek_first_frame_rendered position_ms=%" PRId64 " hardware=1",
				player->meta.diagnosticsId, framePosition);
		Bridge * bridge = playerObtainBridge(player, env);
		PLAYER_SEND_MESSAGE(env, player, bridge, BRIDGE_MESSAGE_END_SEEKING);
		pthread_mutex_unlock(&player->video.sleepDrawMutex);
		playerSetDiagnosticsMediaCodecStage(player, DIAGNOSTICS_MEDIACODEC_STAGE_FINISH_SEEK,
				framePosition);
		condBroadcastLocked(&player->audio.sleepCond, &player->audio.sleepBufferMutex);
		playerSetDiagnosticsMediaCodecStage(player, DIAGNOSTICS_MEDIACODEC_STAGE_WAIT_SLEEP_DRAW_MUTEX,
				framePosition);
		pthread_mutex_lock(&player->video.sleepDrawMutex);
		playerSetDiagnosticsMediaCodecStage(player, DIAGNOSTICS_MEDIACODEC_STAGE_RENDER_FRAME,
				framePosition);
	}
	playerSetDiagnosticsMediaCodecStage(player, DIAGNOSTICS_MEDIACODEC_STAGE_RECORD_OUTPUT,
			framePosition);
	diagnosticsRecordOutput(player, frame, framePosition, waitTime, outputAction, renderResult);
	playerSetDiagnosticsMediaCodecStage(player, DIAGNOSTICS_MEDIACODEC_STAGE_UNREF_FRAME,
			framePosition);
	av_frame_unref(frame);
	pthread_mutex_unlock(&player->video.sleepDrawMutex);
	playerSetDiagnosticsMediaCodecStage(player, DIAGNOSTICS_MEDIACODEC_STAGE_UNLOCK_FRAME_MUTEX,
			framePosition);
	return 1;
}

void playerVideoDecodeMediaCodec(Player * player, JNIEnv * env, AVStream * stream) {
	AVFrame * frame = av_frame_alloc();
	PacketHolder * packetHolder = NULL;
	while (!player->meta.interrupt && player->video.hardwareDecoderActive) {
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
		if (!player->video.hardwareDecoderActive) {
			if (packetHolder) {
				playerPacketQueueFreeCallback(packetHolder);
				packetHolder = NULL;
			}
			break;
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
		while (!player->meta.interrupt && !playerGetSkipFlag(&player->sync.skip.videoWorkFrame)) {
			playerSetDiagnosticsMediaCodecStage(player, DIAGNOSTICS_MEDIACODEC_STAGE_WAIT_FRAME_MUTEX, -1);
			pthread_mutex_lock(&player->decode.video.frameMutex);
			if (player->meta.interrupt || playerGetSkipFlag(&player->sync.skip.videoWorkFrame)) {
				pthread_mutex_unlock(&player->decode.video.frameMutex);
				playerSetDiagnosticsMediaCodecStage(player, DIAGNOSTICS_MEDIACODEC_STAGE_IDLE, -1);
				break;
			}
			__atomic_add_fetch(&player->decode.video.diagnosticsFrameSerial, 1, __ATOMIC_RELAXED);
			AVCodecContext * context = GET_CONTEXT(player, video);
			int decodeResult = decodeMediaCodecFrame(player, context, packetHolder->packet, frame, &packetSent);
			int renderResult = decodeResult > 0
					? renderMediaCodecFrame(player, env, stream, frame) : 0;
			if (decodeResult > 0 && renderResult > 0) {
				player->video.hardwareDecodeErrors = 0;
			} else if ((decodeResult < 0 || renderResult < 0) &&
					++player->video.hardwareDecodeErrors >= 3) {
				playerVideoFallbackMediaCodecToSoftware(player);
			}
			pthread_mutex_unlock(&player->decode.video.frameMutex);
			playerSetDiagnosticsMediaCodecStage(player, DIAGNOSTICS_MEDIACODEC_STAGE_IDLE, -1);
			if (!playerVideoCanDecode(player)) {
				break;
			}
			if (decodeResult <= 0 || renderResult <= 0) {
				break;
			}
		}
		if (!packetHolder->packet) {
			playerMarkStreamFinished(player, 1);
		}
		playerPacketQueueFreeCallback(packetHolder);
		packetHolder = NULL;
	}
	if (packetHolder) {
		playerPacketQueueFreeCallback(packetHolder);
	}
	playerSetDiagnosticsMediaCodecStage(player, DIAGNOSTICS_MEDIACODEC_STAGE_IDLE, -1);
	av_frame_free(&frame);
}
#endif


#ifdef DASHCHAN_HAS_MEDIACODEC
static const char * getMediaCodecDecoderName(enum AVCodecID codecId) {
	switch (codecId) {
		case AV_CODEC_ID_H264: return "h264_mediacodec";
		case AV_CODEC_ID_HEVC: return "hevc_mediacodec";
		case AV_CODEC_ID_VP8: return "vp8_mediacodec";
		case AV_CODEC_ID_VP9: return "vp9_mediacodec";
		case AV_CODEC_ID_AV1: return "av1_mediacodec";
		default: return NULL;
	}
}

static const char * getMediaCodecMimeType(enum AVCodecID codecId) {
	switch (codecId) {
		case AV_CODEC_ID_H264: return "video/avc";
		case AV_CODEC_ID_HEVC: return "video/hevc";
		case AV_CODEC_ID_VP8: return "video/x-vnd.on2.vp8";
		case AV_CODEC_ID_VP9: return "video/x-vnd.on2.vp9";
		case AV_CODEC_ID_AV1: return "video/av01";
		default: return NULL;
	}
}

static int getCodecBitDepth(AVCodecParameters * parameters) {
	if (parameters->bits_per_raw_sample > 0) {
		return parameters->bits_per_raw_sample;
	}
	if (parameters->format == AV_PIX_FMT_NONE) {
		return 0;
	}
	const AVPixFmtDescriptor * descriptor = av_pix_fmt_desc_get(parameters->format);
	int bitDepth = 0;
	if (descriptor) {
		for (int i = 0; i < descriptor->nb_components; i++) {
			if (descriptor->comp[i].depth > bitDepth) {
				bitDepth = descriptor->comp[i].depth;
			}
		}
	}
	return bitDepth;
}

static int isHardwareMediaCodecSupported(Player * player, JNIEnv * env,
		const char * mimeType, AVCodecParameters * parameters, AVRational frameRate) {
	if (!mimeType) {
		return 0;
	}
	Bridge * bridge = playerObtainBridge(player, env);
	jstring mimeTypeString = (*env)->NewStringUTF(env, mimeType);
	if (!mimeTypeString) {
		diagnosticsLog("player=%u mediacodec_preflight failed_stage=create_mime",
				player->meta.diagnosticsId);
		return 0;
	}
	float frameRateValue = frameRate.den > 0 && frameRate.num > 0
			? (float) frameRate.num / (float) frameRate.den : 0.f;
	int bitDepth = getCodecBitDepth(parameters);
	jstring decoderName = (jstring) (*env)->CallObjectMethod(env, player->bridge.native,
			bridge->methodFindHardwareVideoDecoder, mimeTypeString,
			(jint) parameters->width, (jint) parameters->height, (jfloat) frameRateValue,
			(jint) parameters->profile, (jint) parameters->level, (jint) bitDepth,
			(jint) parameters->color_trc);
	(*env)->DeleteLocalRef(env, mimeTypeString);
	if ((*env)->ExceptionCheck(env)) {
		(*env)->ExceptionClear(env);
		diagnosticsLog("player=%u mediacodec_preflight failed_stage=java_exception mime=%s",
				player->meta.diagnosticsId, mimeType);
		return 0;
	}
	if (!decoderName) {
		diagnosticsLog("player=%u mediacodec_preflight unsupported mime=%s size=%dx%d fps=%.3f"
				" profile=%d level=%d bit_depth=%d color_trc=%d",
				player->meta.diagnosticsId, mimeType, parameters->width, parameters->height,
				frameRateValue, parameters->profile, parameters->level, bitDepth,
				parameters->color_trc);
		return 0;
	}
	const char * decoderNameChars = (*env)->GetStringUTFChars(env, decoderName, NULL);
	if (decoderNameChars) {
		diagnosticsLog("player=%u mediacodec_preflight supported mime=%s decoder=%s"
				" size=%dx%d fps=%.3f profile=%d level=%d bit_depth=%d color_trc=%d",
				player->meta.diagnosticsId, mimeType, decoderNameChars,
				parameters->width, parameters->height, frameRateValue, parameters->profile,
				parameters->level, bitDepth, parameters->color_trc);
		(*env)->ReleaseStringUTFChars(env, decoderName, decoderNameChars);
	}
	(*env)->DeleteLocalRef(env, decoderName);
	return 1;
}

static AVCodecContext * createMediaCodecVideoContext(Player * player, JNIEnv * env, jobject surface) {
	AVStream * stream = GET_STREAM(player, video);
	AVCodecParameters * parameters = stream->codecpar;
	const char * decoderName = getMediaCodecDecoderName(stream->codecpar->codec_id);
	const char * mimeType = getMediaCodecMimeType(stream->codecpar->codec_id);
	diagnosticsLog("player=%u mediacodec_capability_check mode=configure_original_stream"
			" codec=%s profile=%d level=%d size=%dx%d fps=%d/%d",
			player->meta.diagnosticsId, avcodec_get_name(parameters->codec_id),
			parameters->profile, parameters->level, parameters->width, parameters->height,
			stream->avg_frame_rate.num, stream->avg_frame_rate.den);
	if (!decoderName || !isHardwareMediaCodecSupported(player, env, mimeType,
			parameters, stream->avg_frame_rate)) {
		diagnosticsLog("player=%u mediacodec_open failed_stage=capability_preflight requested=%s",
				player->meta.diagnosticsId, decoderName ? decoderName : "unsupported_codec");
		return NULL;
	}
	const AVCodec * codec = decoderName ? avcodec_find_decoder_by_name(decoderName) : NULL;
	if (!codec) {
		diagnosticsLog("player=%u mediacodec_open failed_stage=find_decoder requested=%s",
				player->meta.diagnosticsId, decoderName ? decoderName : "unsupported_codec");
		return NULL;
	}
	AVCodecContext * context = avcodec_alloc_context3(codec);
	if (!context) {
		diagnosticsLog("player=%u mediacodec_open failed_stage=allocate_context",
				player->meta.diagnosticsId);
		return NULL;
	}
	int result = avcodec_parameters_to_context(context, stream->codecpar);
	if (result != 0) {
		diagnosticsLog("player=%u mediacodec_open failed_stage=copy_parameters code=%d",
				player->meta.diagnosticsId, result);
		playerCloseAndFreeCodecContext(&context);
		return NULL;
	}
	context->pkt_timebase = stream->time_base;
	context->hw_device_ctx = av_hwdevice_ctx_alloc(AV_HWDEVICE_TYPE_MEDIACODEC);
	if (!context->hw_device_ctx) {
		diagnosticsLog("player=%u mediacodec_open failed_stage=allocate_device_context",
				player->meta.diagnosticsId);
		playerCloseAndFreeCodecContext(&context);
		return NULL;
	}
	AVHWDeviceContext * deviceContext = (AVHWDeviceContext *) context->hw_device_ctx->data;
	AVMediaCodecDeviceContext * mediaCodecContext =
			(AVMediaCodecDeviceContext *) deviceContext->hwctx;
	/*
	 * The decoder uses FFmpeg's Java MediaCodec backend when a JVM is available.
	 * That backend needs android.view.Surface itself; an ANativeWindow is only
	 * consumed by the NDK backend and otherwise produces output buffers with no
	 * visible Surface attached.
	 */
	mediaCodecContext->surface = surface;
	result = av_opt_set_int(context->priv_data, "ndk_codec", 0, 0);
	if (result < 0) {
		diagnosticsLog("player=%u mediacodec_open failed_stage=select_java_backend code=%d",
				player->meta.diagnosticsId, result);
		playerCloseAndFreeCodecContext(&context);
		return NULL;
	}
	result = av_hwdevice_ctx_init(context->hw_device_ctx);
	if (result < 0) {
		diagnosticsLog("player=%u mediacodec_open failed_stage=initialize_device_context code=%d",
				player->meta.diagnosticsId, result);
		playerCloseAndFreeCodecContext(&context);
		return NULL;
	}
	diagnosticsLog("player=%u mediacodec_surface configured_via=java_surface"
			" java_surface=%d hw_device=%d",
			player->meta.diagnosticsId, surface != NULL, context->hw_device_ctx != NULL);
	result = avcodec_open2(context, codec, NULL);
	if (result < 0) {
		diagnosticsLog("player=%u mediacodec_open failed_stage=open_codec code=%d",
				player->meta.diagnosticsId, result);
		playerCloseAndFreeCodecContext(&context);
		return NULL;
	}
	diagnosticsLog("player=%u mediacodec_open success decoder=%s pixel_format=%d"
			" hw_device=%d",
			player->meta.diagnosticsId, codec->name, context->pix_fmt,
			context->hw_device_ctx != NULL);
	return context;
}

static int configureMediaCodecSurface(Player * player, JNIEnv * env, jobject surface) {
	if (!player->video.hardwareAccelerationRequested || player->video.hardwareDecoderFailed) {
		diagnosticsLog("player=%u mediacodec_configure skipped requested=%d failed=%d",
				player->meta.diagnosticsId, player->video.hardwareAccelerationRequested,
				player->video.hardwareDecoderFailed);
		return 0;
	}
	int decoderReset = player->video.hardwareSurfaceInitialized;
	diagnosticsLog("player=%u mediacodec_configure started reset=%d",
			player->meta.diagnosticsId, decoderReset);
	AVCodecContext * context = createMediaCodecVideoContext(player, env, surface);
	if (context) {
		playerCloseAndFreeVideoCodecContext(player, &player->av.videoContext);
		player->av.videoContext = context;
		player->video.hardwareDecoderActive = 1;
		player->video.hardwareSurfaceInitialized = 1;
		diagnosticsIncrement(PLAYER_DIAGNOSTICS_DECODER_ENABLED);
		diagnosticsLog("player=%u mediacodec_configure success decoder=%s",
				player->meta.diagnosticsId, context->codec->name);
		LOGP("MediaCodec video decoder enabled: %s", context->codec->name);
		return decoderReset;
	}
	player->video.hardwareDecoderFailed = 1;
	diagnosticsIncrement(PLAYER_DIAGNOSTICS_DECODER_UNAVAILABLE);
	diagnosticsLog("player=%u mediacodec_configure failed", player->meta.diagnosticsId);
	LOGP("MediaCodec video decoder unavailable, using software decoder");
	if (player->video.hardwareDecoderActive) {
		context = playerVideoSoftwareCreateCodecContext(player);
		if (context) {
			playerCloseAndFreeVideoCodecContext(player, &player->av.videoContext);
			player->av.videoContext = context;
			player->video.hardwareDecoderActive = 0;
		}
	}
	return decoderReset;
}

int playerVideoFallbackMediaCodecToSoftware(Player * player) {
	if (!player->video.window ||
			playerVideoSoftwareGetFormat(ANativeWindow_getFormat(player->video.window)) < 0) {
		return 0;
	}
	AVCodecContext * context = playerVideoSoftwareCreateCodecContext(player);
	if (!context) {
		return 0;
	}
	pthread_mutex_lock(&player->video.sleepDrawMutex);
	playerCloseAndFreeVideoCodecContext(player, &player->av.videoContext);
	player->av.videoContext = context;
	player->video.hardwareDecoderActive = 0;
	player->video.hardwareDecoderFailed = 1;
	player->video.hardwareDecodeErrors = 0;
	int outputPrepared = playerVideoSoftwarePrepareOutputLocked(player);
	pthread_mutex_unlock(&player->video.sleepDrawMutex);
	diagnosticsIncrement(PLAYER_DIAGNOSTICS_SOFTWARE_FALLBACK);
	diagnosticsLog("player=%u mediacodec_runtime_fallback output_prepared=%d",
			player->meta.diagnosticsId, outputPrepared);
	LOGP("MediaCodec failed during playback, switched to software decoder");
	return outputPrepared;
}
#endif
static int setPlayerSurfaceLocked(JNIEnv * env, Player * player, jobject surface) {
	int decoderReset = 0;
	if (surface) {
		player->video.window = ANativeWindow_fromSurface(env, surface);
		if ((*env)->ExceptionCheck(env)) {
			(*env)->ExceptionClear(env);
			diagnosticsLog("player=%u surface_attach failed_stage=create_native_window_exception",
					player->meta.diagnosticsId);
		}
		if (!player->video.window) {
			diagnosticsLog("player=%u surface_attach failed_stage=create_native_window",
					player->meta.diagnosticsId);
			return 0;
		}
#ifdef DASHCHAN_HAS_MEDIACODEC
		decoderReset = configureMediaCodecSurface(player, env, surface);
#endif
		if (player->video.hardwareDecoderActive) {
			pthread_cond_broadcast(&player->video.sleepCond);
			return decoderReset;
		}
		playerVideoSoftwarePrepareOutputLocked(player);
	}
	return decoderReset;
}

void playerVideoApplyPendingSurface(Player * player, JNIEnv * env) {
	// The decoder thread is the sole consumer. MediaCodec replacement and decoder locks
	// therefore never run from TextureView callbacks on Android's main thread.
	jobject surface = NULL;
	int64_t generation = 0;
	int surfaceWidth = 0;
	int surfaceHeight = 0;
	pthread_mutex_lock(&player->video.surfaceMutex);
	if (player->video.surfaceRequestPending) {
		surface = player->video.pendingSurface;
		generation = player->video.pendingSurfaceGeneration;
		surfaceWidth = player->video.pendingSurfaceWidth;
		surfaceHeight = player->video.pendingSurfaceHeight;
		player->video.pendingSurface = NULL;
		__atomic_store_n(&player->video.surfaceRequestPending, 0, __ATOMIC_RELEASE);
	}
	pthread_mutex_unlock(&player->video.surfaceMutex);
	if (!surface) {
		return;
	}

	int mediaCodecStage = __atomic_load_n(&player->decode.video.diagnosticsStage,
			__ATOMIC_ACQUIRE);
	int64_t startedAt = getTime();
	int wasPlaying = player->play.playing;
	if (wasPlaying) {
		playerApplyPlaying(player, 0);
	}
	int64_t position = getPosition((jlong) (long) player);
	diagnosticsLog("player=%u surface_apply_started generation=%" PRId64
			" position_ms=%" PRId64 " was_playing=%d mediacodec_stage=%s",
			player->meta.diagnosticsId, generation, position, wasPlaying,
			diagnosticsGetMediaCodecStageName(mediaCodecStage));

	pthread_mutex_lock(&player->decode.video.frameMutex);
	pthread_mutex_lock(&player->video.sleepDrawMutex);
	if (surfaceWidth > 0 && surfaceHeight > 0) {
		__atomic_store_n(&player->video.surfaceWidth, surfaceWidth, __ATOMIC_RELEASE);
		__atomic_store_n(&player->video.surfaceHeight, surfaceHeight, __ATOMIC_RELEASE);
	}
	jobject oldSurface = player->video.activeSurface;
	playerVideoReleaseSurface(player);
	int decoderReset = setPlayerSurfaceLocked(env, player, surface);
	int attached = player->video.window != NULL;
	player->video.activeSurface = attached ? surface : NULL;
	if (attached) {
		diagnosticsIncrement(PLAYER_DIAGNOSTICS_SURFACE_ATTACHED);
	}
	pthread_mutex_unlock(&player->video.sleepDrawMutex);
	pthread_mutex_unlock(&player->decode.video.frameMutex);

	if (oldSurface) {
		(*env)->DeleteGlobalRef(env, oldSurface);
	}
	if (!attached) {
		(*env)->DeleteGlobalRef(env, surface);
	}
	diagnosticsLog("player=%u surface_apply_finished generation=%" PRId64
			" attached=%d decoder_reset=%d size=%dx%d elapsed_ms=%" PRId64,
			player->meta.diagnosticsId, generation, attached, decoderReset,
			surfaceWidth, surfaceHeight, getTime() - startedAt);
	Bridge * bridge = playerObtainBridge(player, env);
	(*env)->CallVoidMethod(env, player->bridge.native, bridge->methodOnSurfaceApplied,
			(jlong) generation, (jlong) position, (jboolean) !!decoderReset);
}

void setHardwareAcceleration(jlong pointer, jboolean hardwareAcceleration) {
	Player * player = POINTER_CAST(pointer);
#ifdef DASHCHAN_HAS_MEDIACODEC
	player->video.hardwareAccelerationRequested = !!hardwareAcceleration;
#else
	(void) hardwareAcceleration;
	player->video.hardwareAccelerationRequested = 0;
#endif
	diagnosticsLog("player=%u hardware_acceleration_requested=%d available_in_build=%d",
			player->meta.diagnosticsId, player->video.hardwareAccelerationRequested,
#ifdef DASHCHAN_HAS_MEDIACODEC
			1
#else
			0
#endif
	);
}

void requestSurface(JNIEnv * env, jlong pointer, jobject surface, jlong generation,
		jint width, jint height) {
	Player * player = POINTER_CAST(pointer);
	if (!surface || player->meta.interrupt) {
		return;
	}
	jobject surfaceReference = (*env)->NewGlobalRef(env, surface);
	if (!surfaceReference) {
		diagnosticsLog("player=%u surface_request_rejected generation=%" PRId64
				" reason=global_reference", player->meta.diagnosticsId, (int64_t) generation);
		return;
	}

	// The caller only publishes the latest generation under this short mutex. A queue
	// marker wakes a decoder blocked on packets; the actual Surface switch happens there.
	pthread_mutex_lock(&player->video.surfaceMutex);
	jobject replacedSurface = player->video.pendingSurface;
	player->video.pendingSurface = surfaceReference;
	player->video.pendingSurfaceGeneration = generation;
	player->video.pendingSurfaceWidth = width;
	player->video.pendingSurfaceHeight = height;
	__atomic_store_n(&player->video.surfaceRequestPending, 1, __ATOMIC_RELEASE);
	pthread_mutex_unlock(&player->video.surfaceMutex);
	if (replacedSurface) {
		(*env)->DeleteGlobalRef(env, replacedSurface);
	}

	int mediaCodecStage = __atomic_load_n(&player->decode.video.diagnosticsStage,
			__ATOMIC_ACQUIRE);
	diagnosticsLog("player=%u surface_request generation=%" PRId64
			" replaced=%d size=%dx%d mediacodec_stage=%s",
			player->meta.diagnosticsId, (int64_t) generation,
			replacedSurface != NULL, width, height,
			diagnosticsGetMediaCodecStageName(mediaCodecStage));
	playerSetSkipFlag(&player->sync.skip.videoWorkFrame, 1);
	playerSetSkipFlag(&player->sync.skip.drawWorkFrame, 1);
	pthread_cond_broadcast(&player->video.sleepCond);
	pthread_cond_broadcast(&player->video.queueCond);
	pthread_cond_broadcast(&player->play.finishCond);
	blockingQueueAdd(&player->video.packetQueue, playerCreateSurfaceRequestPacketHolder());
}

void setSurfaceSize(jlong pointer, jint width, jint height) {
	Player * player = POINTER_CAST(pointer);
	if (width <= 0 || height <= 0 || player->meta.interrupt) {
		return;
	}
	pthread_mutex_lock(&player->video.surfaceMutex);
	if (player->video.surfaceRequestPending) {
		player->video.pendingSurfaceWidth = width;
		player->video.pendingSurfaceHeight = height;
	}
	__atomic_store_n(&player->video.surfaceWidth, width, __ATOMIC_RELEASE);
	__atomic_store_n(&player->video.surfaceHeight, height, __ATOMIC_RELEASE);
	pthread_mutex_unlock(&player->video.surfaceMutex);
	diagnosticsLog("player=%u surface_size width=%d height=%d",
			player->meta.diagnosticsId, width, height);
}

