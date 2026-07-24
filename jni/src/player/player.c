#include "player.h"
#include "util.h"
#ifdef DASHCHAN_HAS_ATEMPO
#include "tempo.h"
#endif

#include <libavcodec/avcodec.h>
#ifdef DASHCHAN_HAS_MEDIACODEC
#include <libavcodec/jni.h>
#include <libavcodec/mediacodec.h>
#endif
#include <libavformat/avformat.h>
#include <libswresample/swresample.h>
#include <libswscale/swscale.h>
#include <libavutil/imgutils.h>
#ifdef DASHCHAN_HAS_MEDIACODEC
#include <libavutil/hwcontext.h>
#include <libavutil/hwcontext_mediacodec.h>
#endif
#include <libavutil/mathematics.h>
#include <libavutil/pixdesc.h>
#include <libavutil/ffversion.h>
#include <libavutil/opt.h>
#include <libavutil/samplefmt.h>

// Bundled libyuv headers still contain old C no-argument prototypes.
// Keep the NDK 29 strict-prototypes suppression scoped to this third-party include.
#ifdef __clang__
#pragma clang diagnostic push
#pragma clang diagnostic ignored "-Wstrict-prototypes"
#endif
#include <libyuv.h>
#ifdef __clang__
#pragma clang diagnostic pop
#endif

#include <SLES/OpenSLES.h>
#include <SLES/OpenSLES_Android.h>
#include <android/native_window_jni.h>

#include <inttypes.h>
#include <stdarg.h>
#include <stdint.h>
#include <stdio.h>
#include <string.h>
#include <time.h>
#include <unistd.h>

#define POINTER_CAST(addr) (void *) (long) addr
#define UNLOCK_AND_GOTO(mutex, label) {pthread_mutex_unlock(mutex); goto label;}
#define SEND_MESSAGE(env, p, b, what) (*(env))->CallVoidMethod(env, (p)->bridge.native, (b)->methodOnMessage, what)

#define ERROR_LOAD_IO 1
#define ERROR_LOAD_FORMAT 2
#define ERROR_START_THREAD 3
#define ERROR_FIND_STREAM_INFO 4
#define ERROR_FIND_STREAM 5
#define ERROR_FIND_CODEC 6
#define ERROR_OPEN_CODEC 7

#define BRIDGE_MESSAGE_PLAYBACK_COMPLETE 1
#define BRIDGE_MESSAGE_SIZE_CHANGED 2
#define BRIDGE_MESSAGE_START_SEEKING 3
#define BRIDGE_MESSAGE_END_SEEKING 4

#define INDEX_NO_STREAM -1
#define GAINING_THRESHOLD 100
#define MEDIACODEC_MAX_SCHEDULE_AHEAD_MS 50
#define AUDIO_MAX_ENQUEUE_SIZE 256
#define WINDOW_FORMAT_YV12 0x32315659
#define MAX_FPS 60
#define PLAYBACK_SPEED_DEFAULT 1000
#define PLAYBACK_SPEED_MIN 100
#define PLAYBACK_SPEED_MAX 4000

#define HAS_STREAM(p, stream) ((p)->av.stream##StreamIndex != INDEX_NO_STREAM)
#define GET_STREAM(p, stream) ((p)->av.format->streams[(p)->av.stream##StreamIndex])
#define GET_CONTEXT(p, stream) ((p)->av.stream##Context)

#ifndef DASHCHAN_FFMPEG_FLAVOR
#define DASHCHAN_FFMPEG_FLAVOR "ffmpeg"
#endif

#if LIBAVUTIL_VERSION_MAJOR >= 57
#define USE_AV_CHANNEL_LAYOUT 1
#else
#define USE_AV_CHANNEL_LAYOUT 0
#endif

static JavaVM * loadJavaVM;
static SLEngineItf slEngine;

#define DIAGNOSTICS_BUFFER_SIZE (512 * 1024)
#define DIAGNOSTICS_SUMMARY_RESERVE (4 * 1024)

typedef struct {
	uint64_t videoPackets;
	uint64_t videoKeyPackets;
	uint64_t packetsSubmitted;
	uint64_t outputFrames;
	uint64_t renderedScheduled;
	uint64_t renderedImmediate;
	uint64_t droppedLate;
	uint64_t droppedSeek;
	uint64_t droppedState;
	uint64_t outputWithoutBuffer;
	uint64_t releaseErrors;
	uint64_t decoderErrors;
	uint64_t surfaceAttached;
	uint64_t surfaceDetached;
	uint64_t decoderEnabled;
	uint64_t decoderUnavailable;
	uint64_t softwareFallbacks;
	int64_t firstOutputElapsedMs;
	int64_t minWaitMs;
	int64_t maxWaitMs;
} DiagnosticsStats;

static struct {
	pthread_mutex_t mutex;
	int active;
	int truncated;
	int64_t startedAt;
	size_t length;
	char buffer[DIAGNOSTICS_BUFFER_SIZE];
	DiagnosticsStats stats;
} diagnostics = {
	.mutex = PTHREAD_MUTEX_INITIALIZER
};
static unsigned int nextDiagnosticsPlayerId;

static int diagnosticsActive(void) {
	return __atomic_load_n(&diagnostics.active, __ATOMIC_RELAXED);
}

static void diagnosticsAppendVLineLocked(size_t limit, const char * format, va_list arguments) {
	if (diagnostics.length >= limit - 1) {
		diagnostics.truncated = 1;
		return;
	}
	int64_t elapsed = diagnostics.startedAt > 0 ? getTime() - diagnostics.startedAt : 0;
	int prefix = snprintf(diagnostics.buffer + diagnostics.length, limit - diagnostics.length,
			"[+%" PRId64 "ms] ", elapsed);
	if (prefix < 0 || (size_t) prefix >= limit - diagnostics.length) {
		diagnostics.length = limit - 1;
		diagnostics.buffer[diagnostics.length] = '\0';
		diagnostics.truncated = 1;
		return;
	}
	diagnostics.length += (size_t) prefix;
	int written = vsnprintf(diagnostics.buffer + diagnostics.length, limit - diagnostics.length,
			format, arguments);
	if (written < 0 || (size_t) written >= limit - diagnostics.length) {
		diagnostics.length = limit - 1;
		diagnostics.buffer[diagnostics.length] = '\0';
		diagnostics.truncated = 1;
		return;
	}
	diagnostics.length += (size_t) written;
	if (diagnostics.length + 1 < limit) {
		diagnostics.buffer[diagnostics.length++] = '\n';
		diagnostics.buffer[diagnostics.length] = '\0';
	} else {
		diagnostics.truncated = 1;
	}
}

static void diagnosticsAppendLineLocked(size_t limit, const char * format, ...) {
	va_list arguments;
	va_start(arguments, format);
	diagnosticsAppendVLineLocked(limit, format, arguments);
	va_end(arguments);
}

static void diagnosticsLog(const char * format, ...) {
	if (!diagnosticsActive()) {
		return;
	}
	pthread_mutex_lock(&diagnostics.mutex);
	if (diagnosticsActive()) {
		va_list arguments;
		va_start(arguments, format);
		diagnosticsAppendVLineLocked(DIAGNOSTICS_BUFFER_SIZE - DIAGNOSTICS_SUMMARY_RESERVE,
				format, arguments);
		va_end(arguments);
	}
	pthread_mutex_unlock(&diagnostics.mutex);
}

void startPlayerDiagnostics(void) {
	if (diagnosticsActive()) {
		return;
	}
	pthread_mutex_lock(&diagnostics.mutex);
	if (!diagnosticsActive()) {
		memset(&diagnostics.stats, 0, sizeof(diagnostics.stats));
		diagnostics.stats.firstOutputElapsedMs = -1;
		diagnostics.stats.minWaitMs = INT64_MAX;
		diagnostics.stats.maxWaitMs = INT64_MIN;
		diagnostics.truncated = 0;
		diagnostics.length = 0;
		diagnostics.buffer[0] = '\0';
		diagnostics.startedAt = getTime();
		__atomic_store_n(&diagnostics.active, 1, __ATOMIC_RELAXED);
		diagnosticsAppendLineLocked(DIAGNOSTICS_BUFFER_SIZE - DIAGNOSTICS_SUMMARY_RESERVE,
				"capture_started=true");
	}
	pthread_mutex_unlock(&diagnostics.mutex);
}

jstring stopPlayerDiagnostics(JNIEnv * env) {
	__atomic_store_n(&diagnostics.active, 0, __ATOMIC_RELAXED);
	pthread_mutex_lock(&diagnostics.mutex);
	diagnosticsAppendLineLocked(DIAGNOSTICS_BUFFER_SIZE, "capture_stopped=true");
	diagnosticsAppendLineLocked(DIAGNOSTICS_BUFFER_SIZE,
			"summary video_packets=%" PRIu64 " key_packets=%" PRIu64
			" submitted=%" PRIu64 " output_frames=%" PRIu64,
			diagnostics.stats.videoPackets, diagnostics.stats.videoKeyPackets,
			diagnostics.stats.packetsSubmitted, diagnostics.stats.outputFrames);
	diagnosticsAppendLineLocked(DIAGNOSTICS_BUFFER_SIZE,
			"summary rendered_scheduled=%" PRIu64 " rendered_immediate=%" PRIu64
			" dropped_late=%" PRIu64 " dropped_seek=%" PRIu64 " dropped_state=%" PRIu64,
			diagnostics.stats.renderedScheduled, diagnostics.stats.renderedImmediate,
			diagnostics.stats.droppedLate, diagnostics.stats.droppedSeek, diagnostics.stats.droppedState);
	diagnosticsAppendLineLocked(DIAGNOSTICS_BUFFER_SIZE,
			"summary no_output_buffer=%" PRIu64 " release_errors=%" PRIu64
			" decoder_errors=%" PRIu64,
			diagnostics.stats.outputWithoutBuffer, diagnostics.stats.releaseErrors,
			diagnostics.stats.decoderErrors);
	diagnosticsAppendLineLocked(DIAGNOSTICS_BUFFER_SIZE,
			"summary surfaces_attached=%" PRIu64 " surfaces_detached=%" PRIu64
			" decoder_enabled=%" PRIu64 " decoder_unavailable=%" PRIu64
			" software_fallbacks=%" PRIu64,
			diagnostics.stats.surfaceAttached, diagnostics.stats.surfaceDetached,
			diagnostics.stats.decoderEnabled, diagnostics.stats.decoderUnavailable,
			diagnostics.stats.softwareFallbacks);
	if (diagnostics.stats.outputFrames > 0) {
		diagnosticsAppendLineLocked(DIAGNOSTICS_BUFFER_SIZE,
				"summary first_output_ms=%" PRId64 " min_wait_ms=%" PRId64 " max_wait_ms=%" PRId64,
				diagnostics.stats.firstOutputElapsedMs, diagnostics.stats.minWaitMs,
				diagnostics.stats.maxWaitMs);
	} else {
		diagnosticsAppendLineLocked(DIAGNOSTICS_BUFFER_SIZE,
				"summary first_output_ms=none min_wait_ms=none max_wait_ms=none");
	}
	diagnosticsAppendLineLocked(DIAGNOSTICS_BUFFER_SIZE,
			"summary truncated=%s", diagnostics.truncated ? "true" : "false");
	jstring result = (*env)->NewStringUTF(env, diagnostics.buffer);
	pthread_mutex_unlock(&diagnostics.mutex);
	return result;
}

typedef struct Player Player;
typedef struct Bridge Bridge;
typedef struct PacketHolder PacketHolder;
typedef struct AudioBuffer AudioBuffer;
typedef struct VideoFrameExtra VideoFrameExtra;
typedef struct ScaleHolder ScaleHolder;

struct Player {
	struct {
		int interrupt;
		int errorCode;
		int seekAnyFrame;
		int audioEnabled;
		unsigned int diagnosticsId;
	} meta;

	struct {
		int fd;
		long start;
		long end;
		long total;
		int cancelSeek;
		pthread_cond_t controlCond;
		pthread_mutex_t controlMutex;
	} file;

	struct {
		jobject native;
		SparseArray array;
	} bridge;

	struct {
		AVFormatContext * format;
		int audioStreamIndex;
		int videoStreamIndex;
		AVCodecContext * audioContext;
		AVCodecContext * videoContext;
		int64_t timelineOffsetMs;
	} av;

	struct {
		struct {
			int finished;
			int threadStarted;
			pthread_t thread;
			pthread_mutex_t readMutex;
			pthread_cond_t flowCond;
			pthread_mutex_t flowMutex;
		} packets;

		struct {
			int threadStarted;
			pthread_t thread;
			pthread_mutex_t frameMutex;
		} audio;

		struct {
			int threadStarted;
			pthread_t thread;
			pthread_mutex_t frameMutex;
		} video;
	} decode;

	struct {
		int playing;
		pthread_cond_t finishCond;
		pthread_mutex_t finishMutex;
	} play;

	struct {
		struct {
			SLObjectItf outputMix;
			SLObjectItf player;
			SLPlayItf play;
			SLAndroidSimpleBufferQueueItf queue;
			SLVolumeItf volume;
		} sl;

		BlockingQueue packetQueue;
		int finished;
		int resampleSampleRate;
		uint64_t resampleChannels;
		int bufferNeedEnqueueAfterDecode;
		BlockingQueue bufferQueue;
		AudioBuffer * buffer;
		pthread_cond_t sleepCond;
		pthread_cond_t bufferCond;
		pthread_mutex_t sleepBufferMutex;
	} audio;

	struct {
		BlockingQueue packetQueue;
		int finished;
		int hardwareAccelerationRequested;
		int hardwareDecoderActive;
		int hardwareDecoderFailed;
		int hardwareSurfaceInitialized;
		int hardwareDecodeErrors;
		pthread_cond_t sleepCond;
		pthread_mutex_t sleepDrawMutex;
		pthread_cond_t queueCond;
		pthread_mutex_t queueMutex;
		BufferQueue * bufferQueue;
		int drawThreadStarted;
		pthread_t drawThread;
		ANativeWindow * window;
		int useLibyuv;
		int format;

		struct {
			uint8_t * data;
			int width;
			int height;
			int size;
		} lastBuffer;
	} video;

	struct {
		int playbackSpeed;
		int64_t audioPosition;
		int64_t videoPosition;
		int audioPositionNotSync;
		int videoPositionNotSync;
		int64_t startTime;
		int64_t pausedPosition;
		int64_t lastDrawTimes[2];

		struct {
			int readFrame;
			int audioWorkFrame;
			int videoWorkFrame;
			int drawWorkFrame;
		} skip;
	} sync;
};

struct Bridge {
	JNIEnv * env;
	jmethodID methodOnSeek;
	jmethodID methodOnMessage;
};

struct PacketHolder {
	AVPacket * packet;
};

struct AudioBuffer {
	uint8_t * buffer;
	int size;
	int index;
	int64_t position;
	int64_t divider;
};

struct VideoFrameExtra {
	int width;
	int height;
	int64_t position;
};

struct ScaleHolder {
	int bufferSize;
	uint8_t * scaleBuffer;
	uint8_t * scaleData[4];
	int scaleLinesize[4];
};

static void diagnosticsIncrement(uint64_t * value) {
	if (!diagnosticsActive()) {
		return;
	}
	pthread_mutex_lock(&diagnostics.mutex);
	if (diagnosticsActive()) {
		(*value)++;
	}
	pthread_mutex_unlock(&diagnostics.mutex);
}

static void diagnosticsRecordVideoPacket(Player * player, AVPacket * packet) {
	if (!diagnosticsActive() || !packet) {
		return;
	}
	pthread_mutex_lock(&diagnostics.mutex);
	if (diagnosticsActive()) {
		uint64_t count = ++diagnostics.stats.videoPackets;
		int key = !!(packet->flags & AV_PKT_FLAG_KEY);
		if (key) {
			diagnostics.stats.videoKeyPackets++;
		}
		if (count <= 12 || key || count % 120 == 0) {
			diagnosticsAppendLineLocked(DIAGNOSTICS_BUFFER_SIZE - DIAGNOSTICS_SUMMARY_RESERVE,
					"player=%u video_packet count=%" PRIu64 " pts=%" PRId64
					" dts=%" PRId64 " duration=%" PRId64 " size=%d key=%d",
					player->meta.diagnosticsId, count, packet->pts, packet->dts,
					packet->duration, packet->size, key);
		}
	}
	pthread_mutex_unlock(&diagnostics.mutex);
}

#ifdef DASHCHAN_HAS_MEDIACODEC
#define DIAGNOSTICS_OUTPUT_SCHEDULED 0
#define DIAGNOSTICS_OUTPUT_IMMEDIATE 1
#define DIAGNOSTICS_OUTPUT_DROPPED_LATE 2
#define DIAGNOSTICS_OUTPUT_DROPPED_SEEK 3
#define DIAGNOSTICS_OUTPUT_DROPPED_STATE 4
#define DIAGNOSTICS_OUTPUT_NO_BUFFER 5

static void diagnosticsRecordPacketSubmitted(void) {
	if (!diagnosticsActive()) {
		return;
	}
	pthread_mutex_lock(&diagnostics.mutex);
	if (diagnosticsActive()) {
		diagnostics.stats.packetsSubmitted++;
	}
	pthread_mutex_unlock(&diagnostics.mutex);
}

static void diagnosticsRecordDecoderError(Player * player, const char * stage, int error) {
	if (!diagnosticsActive()) {
		return;
	}
	pthread_mutex_lock(&diagnostics.mutex);
	if (diagnosticsActive()) {
		diagnostics.stats.decoderErrors++;
		diagnosticsAppendLineLocked(DIAGNOSTICS_BUFFER_SIZE - DIAGNOSTICS_SUMMARY_RESERVE,
				"player=%u decoder_error stage=%s code=%d",
				player->meta.diagnosticsId, stage, error);
	}
	pthread_mutex_unlock(&diagnostics.mutex);
}

static void diagnosticsRecordOutput(Player * player, AVFrame * frame, int64_t framePosition,
		int64_t waitTime, int action, int result) {
	if (!diagnosticsActive()) {
		return;
	}
	const char * actionName;
	pthread_mutex_lock(&diagnostics.mutex);
	if (diagnosticsActive()) {
		uint64_t count = ++diagnostics.stats.outputFrames;
		if (diagnostics.stats.firstOutputElapsedMs < 0) {
			diagnostics.stats.firstOutputElapsedMs = getTime() - diagnostics.startedAt;
		}
		if (waitTime < diagnostics.stats.minWaitMs) {
			diagnostics.stats.minWaitMs = waitTime;
		}
		if (waitTime > diagnostics.stats.maxWaitMs) {
			diagnostics.stats.maxWaitMs = waitTime;
		}
		switch (action) {
			case DIAGNOSTICS_OUTPUT_SCHEDULED:
				diagnostics.stats.renderedScheduled++;
				actionName = "render_scheduled";
				break;
			case DIAGNOSTICS_OUTPUT_IMMEDIATE:
				diagnostics.stats.renderedImmediate++;
				actionName = "render_immediate";
				break;
			case DIAGNOSTICS_OUTPUT_DROPPED_LATE:
				diagnostics.stats.droppedLate++;
				actionName = "drop_late";
				break;
			case DIAGNOSTICS_OUTPUT_DROPPED_SEEK:
				diagnostics.stats.droppedSeek++;
				actionName = "drop_seek";
				break;
			case DIAGNOSTICS_OUTPUT_DROPPED_STATE:
				diagnostics.stats.droppedState++;
				actionName = "drop_state";
				break;
			default:
				diagnostics.stats.outputWithoutBuffer++;
				actionName = "no_output_buffer";
				break;
		}
		if (result < 0) {
			diagnostics.stats.releaseErrors++;
		}
		if (count <= 12 || count % 120 == 0 || result < 0) {
			diagnosticsAppendLineLocked(DIAGNOSTICS_BUFFER_SIZE - DIAGNOSTICS_SUMMARY_RESERVE,
					"player=%u video_output count=%" PRIu64 " pts=%" PRId64
					" best=%" PRId64 " pos_ms=%" PRId64 " wait_ms=%" PRId64
					" width=%d height=%d format=%d action=%s release_result=%d",
					player->meta.diagnosticsId, count, frame->pts, frame->best_effort_timestamp,
					framePosition, waitTime, frame->width, frame->height, frame->format,
					actionName, result);
		}
	}
	pthread_mutex_unlock(&diagnostics.mutex);
}
#endif

static void diagnosticsRecordMediaInfo(Player * player) {
	if (!diagnosticsActive() || !HAS_STREAM(player, video)) {
		return;
	}
	AVFormatContext * format = player->av.format;
	AVStream * video = GET_STREAM(player, video);
	AVCodecParameters * parameters = video->codecpar;
	const char * pixelFormat = parameters->format >= 0
			? av_get_pix_fmt_name(parameters->format) : NULL;
	diagnosticsLog("player=%u native_build ffmpeg=%s flavor=%s",
			player->meta.diagnosticsId, FFMPEG_VERSION, DASHCHAN_FFMPEG_FLAVOR);
	diagnosticsLog("player=%u media format=%s duration_us=%" PRId64
			" start_us=%" PRId64 " streams=%u",
			player->meta.diagnosticsId,
			format->iformat && format->iformat->name ? format->iformat->name : "unknown",
			format->duration, format->start_time, format->nb_streams);
	diagnosticsLog("player=%u video codec=%s profile=%d level=%d width=%d height=%d"
			" pixel_format=%s time_base=%d/%d avg_frame_rate=%d/%d extradata_size=%d",
			player->meta.diagnosticsId, avcodec_get_name(parameters->codec_id),
			parameters->profile, parameters->level, parameters->width, parameters->height,
			pixelFormat ? pixelFormat : "unknown", video->time_base.num, video->time_base.den,
			video->avg_frame_rate.num, video->avg_frame_rate.den, parameters->extradata_size);
	if (HAS_STREAM(player, audio)) {
		AVStream * audio = GET_STREAM(player, audio);
		AVCodecParameters * audioParameters = audio->codecpar;
		diagnosticsLog("player=%u audio codec=%s profile=%d sample_rate=%d"
				" time_base=%d/%d extradata_size=%d",
				player->meta.diagnosticsId, avcodec_get_name(audioParameters->codec_id),
				audioParameters->profile, audioParameters->sample_rate,
				audio->time_base.num, audio->time_base.den, audioParameters->extradata_size);
	} else {
		diagnosticsLog("player=%u audio=absent_or_disabled", player->meta.diagnosticsId);
	}
}

#ifdef DASHCHAN_HAS_MEDIACODEC
static int fallbackMediaCodecToSoftware(Player * player, JNIEnv * env);
#endif

static Bridge * obtainBridge(Player * player, JNIEnv * env) {
	int index = pthread_self();
	Bridge * bridge = sparseArrayGet(&player->bridge.array, index);
	if (!bridge) {
		bridge = malloc(sizeof(Bridge));
		jclass class = (*env)->GetObjectClass(env, player->bridge.native);
		bridge->env = env;
		bridge->methodOnSeek = (*env)->GetMethodID(env, class, "onSeek", "(J)V");
		bridge->methodOnMessage = (*env)->GetMethodID(env, class, "onMessage", "(I)V");
		sparseArrayAdd(&player->bridge.array, index, bridge);
	}
	return bridge;
}

static int getBytesPerPixel(int videoFormat) {
	switch (videoFormat) {
		case AV_PIX_FMT_YUV420P: return 1;
		case AV_PIX_FMT_RGBA: return 4;
		case AV_PIX_FMT_RGB565LE: return 2;
	}
	return 0;
}

static int64_t getTimestampPositionMs(Player * player, int64_t timestamp, AVRational timeBase) {
	if (timestamp == AV_NOPTS_VALUE) {
		return -1;
	}
	AVRational msTimeBase = {1, 1000};
	int64_t position = av_rescale_q(timestamp, timeBase, msTimeBase) - player->av.timelineOffsetMs;
	return max64(position, 0);
}

static int64_t getFramePositionMs(Player * player, AVFrame * frame, AVStream * stream) {
	int64_t timestamp = frame->best_effort_timestamp;
	if (timestamp == AV_NOPTS_VALUE) {
		timestamp = frame->pts;
	}
	if (timestamp == AV_NOPTS_VALUE) {
		timestamp = frame->pkt_dts;
	}
	return getTimestampPositionMs(player, timestamp, stream->time_base);
}

static int64_t getSeekTimestampUs(Player * player, int64_t position) {
	AVRational msTimeBase = {1, 1000};
	return av_rescale_q(position + player->av.timelineOffsetMs, msTimeBase, AV_TIME_BASE_Q);
}

static int getAudioContextChannels(const AVCodecContext * context) {
#if USE_AV_CHANNEL_LAYOUT
	return context->ch_layout.nb_channels;
#else
	return context->channels;
#endif
}

static int getCodecParametersChannels(const AVCodecParameters * parameters) {
#if USE_AV_CHANNEL_LAYOUT
	return parameters->ch_layout.nb_channels;
#else
	return parameters->channels;
#endif
}

#if USE_AV_CHANNEL_LAYOUT
static int getFrameChannels(const AVFrame * frame) {
	return frame->ch_layout.nb_channels;
}

static int copyFrameChannelLayout(AVChannelLayout * channelLayout, const AVFrame * frame, int fallbackChannels) {
	if (frame->ch_layout.nb_channels > 0) {
		return av_channel_layout_copy(channelLayout, &frame->ch_layout);
	}
	av_channel_layout_default(channelLayout, fallbackChannels > 0 ? fallbackChannels : 2);
	return 0;
}

static int copyOrMaskChannelLayout(AVChannelLayout * channelLayout,
		const AVChannelLayout * fallbackChannelLayout, uint64_t channelMask) {
	if (channelMask != 0) {
		return av_channel_layout_from_mask(channelLayout, channelMask);
	}
	return av_channel_layout_copy(channelLayout, fallbackChannelLayout);
}
#endif

static void closeAndFreeCodecContext(AVCodecContext ** context) {
	if (!context || !*context) {
		return;
	}
#if LIBAVCODEC_VERSION_MAJOR < 59
	avcodec_close(*context);
#endif
	avcodec_free_context(context);
}

static void closeAndFreeVideoCodecContext(Player * player, AVCodecContext ** context) {
	if (!context || !*context) {
		return;
	}
	(void) player;
	closeAndFreeCodecContext(context);
}

static void packetQueueFreeCallback(void * data) {
	PacketHolder * packetHolder = (PacketHolder *) data;
	if (packetHolder->packet) {
		av_packet_free(&packetHolder->packet);
	}
	free(packetHolder);
}

static void audioBufferQueueFreeCallback(void * data) {
	AudioBuffer * audioBuffer = (AudioBuffer *) data;
	if (audioBuffer) {
		av_freep(&audioBuffer->buffer);
		free(audioBuffer);
	}
}

static void videoBufferQueueFreeCallback(BufferItem * bufferItem) {
	if (bufferItem->extra) {
		free(bufferItem->extra);
		bufferItem->extra = NULL;
	}
}

static int clampPlaybackSpeed(int speed) {
	if (speed < PLAYBACK_SPEED_MIN) {
		return PLAYBACK_SPEED_MIN;
	}
	if (speed > PLAYBACK_SPEED_MAX) {
		return PLAYBACK_SPEED_MAX;
	}
	return speed;
}

static int getPlaybackSpeed(Player * player) {
	int speed = player->sync.playbackSpeed;
	return speed > 0 ? speed : PLAYBACK_SPEED_DEFAULT;
}

static int64_t scalePlaybackElapsed(Player * player, int64_t elapsed) {
	return elapsed * getPlaybackSpeed(player) / PLAYBACK_SPEED_DEFAULT;
}

static int64_t unscalePlaybackPosition(Player * player, int64_t position) {
	return position * PLAYBACK_SPEED_DEFAULT / getPlaybackSpeed(player);
}

static int getPlaybackSampleRateForSpeed(int sampleRate, int speed) {
	int result = (int) (sampleRate * (int64_t) PLAYBACK_SPEED_DEFAULT / speed);
	return result > 0 ? result : 1;
}

#ifndef DASHCHAN_HAS_ATEMPO
static int getPlaybackSampleRate(Player * player, int sampleRate) {
	return getPlaybackSampleRateForSpeed(sampleRate, getPlaybackSpeed(player));
}
#endif

static void updateAudioPositionSurrogate(Player * player, int64_t position, int forceUpdate) {
	if (forceUpdate || player->sync.audioPositionNotSync) {
		player->sync.startTime = getTime() - unscalePlaybackPosition(player, position);
		if ((!HAS_STREAM(player, audio) || player->audio.finished) && !forceUpdate) {
			player->sync.audioPositionNotSync = 0;
		}
	}
}

static int64_t calculatePosition(Player * player, int mayCalculateStartTime) {
	if (!HAS_STREAM(player, audio) || player->audio.finished) {
		if (player->play.playing) {
			if (mayCalculateStartTime || !player->video.finished) {
				return scalePlaybackElapsed(player, getTime() - player->sync.startTime);
			} else {
				return player->sync.videoPosition;
			}
		} else {
			return player->sync.pausedPosition;
		}
	} else {
		return player->sync.audioPosition;
	}
}

static void markStreamFinished(Player * player, int video) {
	if (video) {
		int decodedCount = player->video.bufferQueue ? bufferQueueCount(player->video.bufferQueue) : 0;
		if (decodedCount == 0 &&
				blockingQueueCount(&player->video.packetQueue) == 0) {
			player->video.finished = 1;
			condBroadcastLocked(&player->play.finishCond, &player->play.finishMutex);
		}
	} else {
		if (!player->audio.buffer && blockingQueueCount(&player->audio.bufferQueue) == 0
				&& blockingQueueCount(&player->audio.packetQueue) == 0) {
			player->audio.finished = 1;
			condBroadcastLocked(&player->play.finishCond, &player->play.finishMutex);
		}
	}
}

static int64_t calculateFrameTime(Player * player, int64_t waitTime) {
	int64_t scaledWaitTime = unscalePlaybackPosition(player, waitTime);
	return getTime() + scaledWaitTime - min64(max64(scaledWaitTime / 2, 25), 100);
}

#ifdef DASHCHAN_HAS_MEDIACODEC
static int64_t getMonotonicTimeNs(void) {
	struct timespec time;
	clock_gettime(CLOCK_MONOTONIC, &time);
	return (int64_t) time.tv_sec * 1000000000LL + time.tv_nsec;
}
#endif

static int decodeFrame(AVCodecContext * context, AVPacket * packet, AVFrame * frame, int * packetSent) {
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

static int enqueueAudioBuffer(Player * player) {
	if (!player->play.playing) {
		player->audio.bufferNeedEnqueueAfterDecode = 1;
		return 0;
	}
	int64_t endAudioPosition = -1;
	if (player->audio.buffer) {
		AudioBuffer * audioBuffer = player->audio.buffer;
		if (audioBuffer->index >= audioBuffer->size) {
			endAudioPosition = audioBuffer->position + audioBuffer->size * 1000 / audioBuffer->divider;
			player->audio.buffer = NULL;
			audioBufferQueueFreeCallback(audioBuffer);
		}
	}
	if (!player->audio.buffer) {
		player->audio.buffer = blockingQueueGet(&player->audio.bufferQueue, 0);
	}
	if (player->audio.buffer) {
		AudioBuffer * audioBuffer = player->audio.buffer;
		if (audioBuffer->position >= 0) {
			player->sync.audioPosition = audioBuffer->position + audioBuffer->index * 1000 / audioBuffer->divider;
			player->sync.audioPositionNotSync = 0;
			LOG("play audio %" PRId64, player->sync.audioPosition);
		}
		int enqueueSize = min32(audioBuffer->size - audioBuffer->index, AUDIO_MAX_ENQUEUE_SIZE);
		(*player->audio.sl.queue)->Enqueue(player->audio.sl.queue,
				audioBuffer->buffer + audioBuffer->index, enqueueSize);
		audioBuffer->index += enqueueSize;
		player->audio.bufferNeedEnqueueAfterDecode = 0;
		return 1;
	} else {
		player->audio.bufferNeedEnqueueAfterDecode = 1;
		if (blockingQueueCount(&player->audio.packetQueue) == 0 && endAudioPosition >= 0) {
			updateAudioPositionSurrogate(player, endAudioPosition, 1);
		}
		return 0;
	}
}

static void audioPlayerCallback(UNUSED SLAndroidSimpleBufferQueueItf slQueue, void * context) {
	Player * player = (Player *) context;
	if (player->meta.interrupt) {
		return;
	}
	LOG("audio callback");
	pthread_mutex_lock(&player->audio.sleepBufferMutex);
	int result = enqueueAudioBuffer(player);
	if (result) {
		pthread_cond_broadcast(&player->audio.bufferCond);
	}
	pthread_mutex_unlock(&player->audio.sleepBufferMutex);
	markStreamFinished(player, 0);
}

static int queueDecodedAudio(Player * player, uint8_t * buffer, int size,
		int64_t position, int64_t divider, int * silentAudioLength) {
	if (!buffer || size <= 0 || divider <= 0) {
		return 0;
	}
	pthread_mutex_lock(&player->audio.sleepBufferMutex);
	if (player->meta.interrupt || player->sync.skip.audioWorkFrame) {
		pthread_mutex_unlock(&player->audio.sleepBufferMutex);
		return 0;
	}
	while (!player->meta.interrupt && !player->sync.skip.audioWorkFrame &&
			blockingQueueCount(&player->audio.bufferQueue) >= 5) {
		pthread_cond_wait(&player->audio.bufferCond, &player->audio.sleepBufferMutex);
	}
	if (player->meta.interrupt || player->sync.skip.audioWorkFrame) {
		pthread_mutex_unlock(&player->audio.sleepBufferMutex);
		return 0;
	}
	if (position >= 0 && player->audio.bufferNeedEnqueueAfterDecode) {
		player->sync.audioPosition = position;
		player->sync.audioPositionNotSync = 0;
	}
	while (!player->meta.interrupt && !player->sync.skip.audioWorkFrame && player->sync.videoPositionNotSync) {
		pthread_cond_wait(&player->audio.sleepCond, &player->audio.sleepBufferMutex);
	}
	if (player->meta.interrupt || player->sync.skip.audioWorkFrame) {
		pthread_mutex_unlock(&player->audio.sleepBufferMutex);
		return 0;
	}
	int64_t videoPosition = player->sync.videoPosition;
	int64_t gaining = position >= 0 && !player->video.finished ? position - videoPosition : 0;
	if (gaining > GAINING_THRESHOLD) {
		LOG("sleep audio %" PRId64 " %" PRId64, gaining, position);
		int64_t time = calculateFrameTime(player, gaining);
		while (!player->meta.interrupt && !player->sync.skip.audioWorkFrame) {
			if (condSleepUntilMs(&player->audio.sleepCond, &player->audio.sleepBufferMutex, time)) {
				break;
			}
		}
	}
	if (player->meta.interrupt || player->sync.skip.audioWorkFrame) {
		pthread_mutex_unlock(&player->audio.sleepBufferMutex);
		return 0;
	}
	AudioBuffer * audioBuffer = malloc(sizeof(AudioBuffer));
	if (!audioBuffer) {
		pthread_mutex_unlock(&player->audio.sleepBufferMutex);
		return 0;
	}
	audioBuffer->buffer = buffer;
	audioBuffer->index = 0;
	audioBuffer->size = size;
	audioBuffer->position = position;
	audioBuffer->divider = divider;
	// Fix loud click on video start even on low sound level by muting sound buffer for 40 milliseconds.
	if (*silentAudioLength < 0) {
		*silentAudioLength = 40 * divider / 1000;
	}
	if (*silentAudioLength > 0) {
		int count = *silentAudioLength >= size ? size : *silentAudioLength;
		memset(audioBuffer->buffer, 0, count);
		*silentAudioLength -= count;
	}
	int needEnqueue = player->audio.bufferNeedEnqueueAfterDecode;
	blockingQueueAdd(&player->audio.bufferQueue, audioBuffer);
	if (needEnqueue) {
		enqueueAudioBuffer(player);
	}
	pthread_mutex_unlock(&player->audio.sleepBufferMutex);
	return 1;
}

#ifdef DASHCHAN_HAS_ATEMPO
static int drainTempoProcessor(Player * player, TempoProcessor * processor,
		int sampleRate, int channels, int speed, int64_t startPosition,
		int64_t * outputSamples, int * silentAudioLength) {
	while (!player->meta.interrupt && !player->sync.skip.audioWorkFrame) {
		uint8_t * buffer = NULL;
		int size = 0;
		int samples = 0;
		int result = tempoProcessorPull(processor, &buffer, &size, &samples);
		if (result < 0) {
			LOG("atempo pull failed %d", result);
			return 0;
		}
		if (result == 0) {
			return 1;
		}
		int64_t position = startPosition >= 0
				? startPosition + av_rescale(*outputSamples, speed, sampleRate) : -1;
		*outputSamples += samples;
		int64_t divider = av_get_bytes_per_sample(AV_SAMPLE_FMT_S16) * channels *
				(int64_t) getPlaybackSampleRateForSpeed(sampleRate, speed);
		if (!queueDecodedAudio(player, buffer, size, position, divider, silentAudioLength)) {
			av_free(buffer);
			return 0;
		}
	}
	return 0;
}
#endif

static void * performDecodeAudio(void * data) {
	Player * player = (Player *) data;
	player->audio.bufferNeedEnqueueAfterDecode = 1;
	AVStream * stream = GET_STREAM(player, audio);
	AVCodecContext * context = GET_CONTEXT(player, audio);
	AVFrame * frame = av_frame_alloc();
	SwrContext * resampleContext = swr_alloc();
	int silentAudioLength = -1;
	PacketHolder * packetHolder = NULL;
#ifdef DASHCHAN_HAS_ATEMPO
	TempoProcessor * tempoProcessor = NULL;
	int tempoSampleRate = 0;
	int tempoChannels = 0;
	int tempoSpeed = PLAYBACK_SPEED_DEFAULT;
	int64_t tempoStartPosition = -1;
	int64_t tempoOutputSamples = 0;
#endif

	while (!player->meta.interrupt) {
		packetHolder = (PacketHolder *) blockingQueueGet(&player->audio.packetQueue, 1);
		if (player->sync.skip.audioWorkFrame) {
			player->sync.skip.audioWorkFrame = 0;
#ifdef DASHCHAN_HAS_ATEMPO
			tempoProcessorFree(&tempoProcessor);
			tempoStartPosition = -1;
			tempoOutputSamples = 0;
#endif
		}
		if (!packetHolder || player->meta.interrupt) {
			break;
		}
		condBroadcastLocked(&player->decode.packets.flowCond, &player->decode.packets.flowMutex);
		if (player->meta.interrupt) {
			break;
		}

		pthread_mutex_lock(&player->play.finishMutex);
		while (!player->meta.interrupt && !player->play.playing) {
			pthread_cond_wait(&player->play.finishCond, &player->play.finishMutex);
		}
		pthread_mutex_unlock(&player->play.finishMutex);
		if (player->meta.interrupt) {
			break;
		}

		int packetSent = 0;
		while (1) {
			int success = 0;
			uint8_t ** dstData = NULL;
#if USE_AV_CHANNEL_LAYOUT
			AVChannelLayout srcChannelLayout = {0};
			AVChannelLayout dstChannelLayout = {0};
			int channelLayoutsInitialized = 0;
#endif
			if (player->sync.skip.audioWorkFrame) {
				goto SKIP_AUDIO_FRAME;
			}
			pthread_mutex_lock(&player->decode.audio.frameMutex);
			if (player->sync.skip.audioWorkFrame) {
				UNLOCK_AND_GOTO(&player->decode.audio.frameMutex, SKIP_AUDIO_FRAME);
			}
			int ready = decodeFrame(context, packetHolder->packet, frame, &packetSent);
			pthread_mutex_unlock(&player->decode.audio.frameMutex);
			if (!ready) {
				break;
			}

			if (ready) {
				int64_t position = getFramePositionMs(player, frame, stream);
				if (position >= 0 && player->meta.seekAnyFrame && player->sync.audioPositionNotSync &&
						position < player->sync.audioPosition) {
					success = 1;
					goto SKIP_AUDIO_FRAME;
				}

#if USE_AV_CHANNEL_LAYOUT
				int srcChannels = getFrameChannels(frame);
				if (srcChannels <= 0) {
					srcChannels = getAudioContextChannels(context);
				}
				if (copyFrameChannelLayout(&srcChannelLayout, frame, srcChannels) < 0) {
					goto SKIP_AUDIO_FRAME;
				}
				channelLayoutsInitialized = 1;
				if (copyOrMaskChannelLayout(&dstChannelLayout, &srcChannelLayout,
						player->audio.resampleChannels) < 0) {
					goto SKIP_AUDIO_FRAME;
				}
				int dstChannels = dstChannelLayout.nb_channels;
				if (srcChannels <= 0) {
					srcChannels = srcChannelLayout.nb_channels;
				}
#else
				if (frame->channel_layout == 0) {
					frame->channel_layout = av_get_default_channel_layout(frame->channels);
				}
				uint64_t srcChannelLayout = frame->channel_layout;
				uint64_t dstChannelLayout = player->audio.resampleChannels != 0
						? player->audio.resampleChannels : srcChannelLayout;
				int srcChannels = frame->channels;
				int dstChannels = av_get_channel_layout_nb_channels(dstChannelLayout);
				(void) srcChannels;
#endif
				int srcSamples = frame->nb_samples;
				int srcSampleRate = frame->sample_rate;
				int outputSampleRate = player->audio.resampleSampleRate != 0
						? player->audio.resampleSampleRate : srcSampleRate;
#ifdef DASHCHAN_HAS_ATEMPO
				int playbackSpeed = getPlaybackSpeed(player);
				int dstSampleRate = outputSampleRate;
#else
				int dstSampleRate = getPlaybackSampleRate(player, outputSampleRate);
#endif
				int dstFormat = AV_SAMPLE_FMT_S16;
				LOG("audio frame pts=%" PRId64 " best=%" PRId64 " pkt_dts=%" PRId64
						" pos=%" PRId64 " tb=%d/%d srcRate=%d srcCh=%d dstRate=%d dstCh=%d",
						frame->pts, frame->best_effort_timestamp, frame->pkt_dts, position,
						stream->time_base.num, stream->time_base.den, srcSampleRate, srcChannels,
						dstSampleRate, dstChannels);
#if USE_AV_CHANNEL_LAYOUT
				av_opt_set_chlayout(resampleContext, "in_chlayout", &srcChannelLayout, 0);
				av_opt_set_chlayout(resampleContext, "out_chlayout", &dstChannelLayout, 0);
#else
				av_opt_set_int(resampleContext, "in_channel_layout", srcChannelLayout, 0);
				av_opt_set_int(resampleContext, "out_channel_layout", dstChannelLayout,  0);
#endif
				av_opt_set_int(resampleContext, "in_sample_rate", srcSampleRate, 0);
				av_opt_set_int(resampleContext, "out_sample_rate", dstSampleRate, 0);
				av_opt_set_sample_fmt(resampleContext, "in_sample_fmt", frame->format, 0);
				av_opt_set_sample_fmt(resampleContext, "out_sample_fmt", dstFormat,  0);
				if (swr_init(resampleContext) < 0 || player->sync.skip.audioWorkFrame) {
					goto SKIP_AUDIO_FRAME;
				}
				int dstSamples = av_rescale_rnd(srcSamples, dstSampleRate, srcSampleRate, AV_ROUND_UP);
				int result = av_samples_alloc_array_and_samples(&dstData, frame->linesize, dstChannels,
						dstSamples, dstFormat, 0);
				if (result < 0 || player->sync.skip.audioWorkFrame) {
					goto SKIP_AUDIO_FRAME;
				}
				dstSamples = av_rescale_rnd(swr_get_delay(resampleContext, srcSampleRate) + srcSamples,
						dstSampleRate, srcSampleRate, AV_ROUND_UP);
				result = swr_convert(resampleContext, dstData, dstSamples, (const uint8_t **) frame->data, srcSamples);
				if (result < 0 || player->sync.skip.audioWorkFrame) {
					goto SKIP_AUDIO_FRAME;
				}

				int size = av_samples_get_buffer_size(NULL, dstChannels, result, dstFormat, 1);
				if (size < 0) {
					goto SKIP_AUDIO_FRAME;
				}
#ifdef DASHCHAN_HAS_ATEMPO
				if (playbackSpeed != PLAYBACK_SPEED_DEFAULT) {
					if (!tempoProcessor || tempoSampleRate != dstSampleRate ||
							tempoChannels != dstChannels || tempoSpeed != playbackSpeed) {
						tempoProcessorFree(&tempoProcessor);
						tempoProcessor = tempoProcessorCreate(dstSampleRate, dstChannels, playbackSpeed);
						if (!tempoProcessor) {
							LOG("atempo create failed rate=%d channels=%d speed=%d",
									dstSampleRate, dstChannels, playbackSpeed);
							goto SKIP_AUDIO_FRAME;
						}
						tempoSampleRate = dstSampleRate;
						tempoChannels = dstChannels;
						tempoSpeed = playbackSpeed;
						tempoStartPosition = position;
						tempoOutputSamples = 0;
					}
					int tempoResult = tempoProcessorPush(tempoProcessor, dstData[0], result);
					if (tempoResult < 0) {
						LOG("atempo push failed %d", tempoResult);
						goto SKIP_AUDIO_FRAME;
					}
					if (!drainTempoProcessor(player, tempoProcessor, tempoSampleRate, tempoChannels,
							tempoSpeed, tempoStartPosition, &tempoOutputSamples, &silentAudioLength)) {
						goto SKIP_AUDIO_FRAME;
					}
					success = 1;
				} else {
					tempoProcessorFree(&tempoProcessor);
					tempoStartPosition = -1;
					tempoOutputSamples = 0;
					int64_t divider = av_get_bytes_per_sample(dstFormat) * dstChannels *
							(int64_t) getPlaybackSampleRateForSpeed(dstSampleRate, playbackSpeed);
					if (queueDecodedAudio(player, dstData[0], size, position, divider, &silentAudioLength)) {
						dstData[0] = NULL;
						success = 1;
					}
				}
#else
				int64_t divider = av_get_bytes_per_sample(dstFormat) * dstChannels * (int64_t) dstSampleRate;
				if (queueDecodedAudio(player, dstData[0], size, position, divider, &silentAudioLength)) {
					dstData[0] = NULL;
					success = 1;
				}
#endif
			}

			SKIP_AUDIO_FRAME:
#if USE_AV_CHANNEL_LAYOUT
			if (channelLayoutsInitialized) {
				av_channel_layout_uninit(&srcChannelLayout);
				av_channel_layout_uninit(&dstChannelLayout);
			}
#endif
			if (dstData) {
				av_freep(&dstData[0]);
				av_freep(&dstData);
			}
			if (!success) {
				break;
			}
		}
#ifdef DASHCHAN_HAS_ATEMPO
		if (!packetHolder->packet && tempoProcessor) {
			int result = tempoProcessorFinish(tempoProcessor);
			if (result < 0) {
				LOG("atempo finish failed %d", result);
			} else {
				drainTempoProcessor(player, tempoProcessor, tempoSampleRate, tempoChannels,
						tempoSpeed, tempoStartPosition, &tempoOutputSamples, &silentAudioLength);
			}
			tempoProcessorFree(&tempoProcessor);
			tempoStartPosition = -1;
			tempoOutputSamples = 0;
		}
#endif
		packetQueueFreeCallback(packetHolder);
		packetHolder = NULL;
	}
	if (packetHolder) {
		packetQueueFreeCallback(packetHolder);
	}
#ifdef DASHCHAN_HAS_ATEMPO
	tempoProcessorFree(&tempoProcessor);
#endif
	swr_free(&resampleContext);
	av_frame_free(&frame);
	return NULL;
}

static void drawWindow(Player * player, uint8_t * buffer, int width, int height, int lastWidth, int lastHeight,
		JNIEnv * env) {
	if (player->video.window) {
		if (width != lastWidth || height != lastHeight) {
			ANativeWindow_setBuffersGeometry(player->video.window, width, height,
					ANativeWindow_getFormat(player->video.window));
			Bridge * bridge = obtainBridge(player, env);
			SEND_MESSAGE(env, player, bridge, BRIDGE_MESSAGE_SIZE_CHANGED);
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

static void * performDraw(void * data) {
	Player * player = (Player *) data;
	JNIEnv * env;
	(*loadJavaVM)->AttachCurrentThread(loadJavaVM, &env, NULL);
	AVCodecContext * context = GET_CONTEXT(player, video);
	int lastWidth = context->width;
	int lastHeight = context->height;
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
		player->sync.skip.drawWorkFrame = 0;
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
		if (player->sync.skip.drawWorkFrame) {
			UNLOCK_AND_GOTO(&player->video.sleepDrawMutex, SKIP_DRAW_FRAME);
		}
		VideoFrameExtra * extra = bufferItem->extra;
		int64_t position = calculatePosition(player, 1);
		int64_t waitTime = 0;
		if (extra->position >= 0) {
			player->sync.videoPosition = extra->position;
			waitTime = extra->position - position;
			if (player->sync.videoPositionNotSync) {
				player->sync.videoPositionNotSync = 0;
				Bridge * bridge = obtainBridge(player, env);
				SEND_MESSAGE(env, player, bridge, BRIDGE_MESSAGE_END_SEEKING);
				pthread_mutex_unlock(&player->video.sleepDrawMutex);
				condBroadcastLocked(&player->audio.sleepCond, &player->audio.sleepBufferMutex);
				pthread_mutex_lock(&player->video.sleepDrawMutex);
				if (player->sync.skip.drawWorkFrame) {
					UNLOCK_AND_GOTO(&player->video.sleepDrawMutex, SKIP_DRAW_FRAME);
				}
			}
		}
		if (waitTime > 0) {
			LOG("sleep video %" PRId64 " %" PRId64 " %" PRId64, waitTime, player->sync.videoPosition, position);
			int64_t time = calculateFrameTime(player, waitTime);
			while (!player->meta.interrupt && !player->sync.skip.drawWorkFrame) {
				if (condSleepUntilMs(&player->video.sleepCond, &player->video.sleepDrawMutex, time)) {
					break;
				}
			}
			waitTime = 0;
		}
		if (player->sync.skip.drawWorkFrame) {
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
		int bufferSize = bufferItem->bufferSize;
		if (bufferSize > player->video.lastBuffer.size) {
			player->video.lastBuffer.data = realloc(player->video.lastBuffer.data, bufferSize);
			player->video.lastBuffer.size = bufferSize;
		}
		memcpy(player->video.lastBuffer.data, bufferItem->buffer, bufferSize);
		player->video.lastBuffer.width = extra->width;
		player->video.lastBuffer.height = extra->height;
		if ((player->sync.lastDrawTimes[0] - player->sync.lastDrawTimes[1]) * MAX_FPS >= 1000
				|| (getTime() - player->sync.lastDrawTimes[0]) * MAX_FPS >= 1000) {
			// Avoid FPS > MAX_FPS
			drawWindow(player, bufferItem->buffer, extra->width, extra->height, lastWidth, lastHeight, env);
			lastWidth = extra->width;
			lastHeight = extra->height;
			player->sync.lastDrawTimes[1] = player->sync.lastDrawTimes[0];
			player->sync.lastDrawTimes[0] = getTime();
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
			markStreamFinished(player, 1);
		}
	}
	(*loadJavaVM)->DetachCurrentThread(loadJavaVM);
	return NULL;
}

static int getVideoBufferSize(int videoFormat, int width, int height) {
	switch (videoFormat) {
		case AV_PIX_FMT_RGBA: return width * height * 4;
		case AV_PIX_FMT_RGB565LE: return width * height * 2;
		case AV_PIX_FMT_YUV420P: return width * height * 3 / 2;
		default: return 0;
	}
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

#ifdef DASHCHAN_HAS_MEDIACODEC
static int decodeMediaCodecFrame(Player * player, AVCodecContext * context, AVPacket * packet,
		AVFrame * frame, int * packetSent) {
	if (!*packetSent) {
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
	if (frame->format != AV_PIX_FMT_MEDIACODEC || !buffer) {
		diagnosticsLog("player=%u mediacodec_output invalid_format=%d expected_format=%d"
				" output_buffer=%d",
				player->meta.diagnosticsId, frame->format, AV_PIX_FMT_MEDIACODEC,
				buffer != NULL);
		diagnosticsRecordOutput(player, frame, framePosition, 0,
				DIAGNOSTICS_OUTPUT_NO_BUFFER, 0);
		av_frame_unref(frame);
		return -1;
	}
	int render = 1;
	int renderResult = 0;
	int outputAction = DIAGNOSTICS_OUTPUT_IMMEDIATE;
	int64_t waitTime = 0;
	pthread_mutex_lock(&player->video.sleepDrawMutex);
	if (player->meta.interrupt || player->sync.skip.videoWorkFrame) {
		render = 0;
		outputAction = DIAGNOSTICS_OUTPUT_DROPPED_STATE;
		goto RELEASE_BUFFER;
	}
	if (framePosition >= 0 && player->meta.seekAnyFrame && player->sync.videoPositionNotSync &&
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
			player->sync.videoPositionNotSync = 0;
			Bridge * bridge = obtainBridge(player, env);
			SEND_MESSAGE(env, player, bridge, BRIDGE_MESSAGE_END_SEEKING);
			pthread_mutex_unlock(&player->video.sleepDrawMutex);
			condBroadcastLocked(&player->audio.sleepCond, &player->audio.sleepBufferMutex);
			pthread_mutex_lock(&player->video.sleepDrawMutex);
			if (player->meta.interrupt || player->sync.skip.videoWorkFrame) {
				render = 0;
				outputAction = DIAGNOSTICS_OUTPUT_DROPPED_STATE;
				goto RELEASE_BUFFER;
			}
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
			renderResult = av_mediacodec_render_buffer_at_time(buffer, renderTimeNs);
			outputAction = DIAGNOSTICS_OUTPUT_SCHEDULED;
			buffer = NULL;
			break;
		}
		int64_t wakeTime = getTime() + scaledWaitTime - MEDIACODEC_MAX_SCHEDULE_AHEAD_MS;
		condSleepUntilMs(&player->video.sleepCond, &player->video.sleepDrawMutex, wakeTime);
		if (player->meta.interrupt || player->sync.skip.videoWorkFrame) {
			render = 0;
			outputAction = DIAGNOSTICS_OUTPUT_DROPPED_STATE;
			break;
		}
		position = calculatePosition(player, 1);
		waitTime = framePosition - position;
	}

	RELEASE_BUFFER:
	if (buffer) {
		renderResult = av_mediacodec_release_buffer(buffer, render);
	}
	if (renderResult < 0) {
		LOGP("MediaCodec output buffer release failed: %d", renderResult);
	}
	diagnosticsRecordOutput(player, frame, framePosition, waitTime, outputAction, renderResult);
	av_frame_unref(frame);
	pthread_mutex_unlock(&player->video.sleepDrawMutex);
	return 1;
}

static void performDecodeVideoMediaCodec(Player * player, AVStream * stream) {
	JNIEnv * env;
	(*loadJavaVM)->AttachCurrentThread(loadJavaVM, &env, NULL);
	AVFrame * frame = av_frame_alloc();
	PacketHolder * packetHolder = NULL;
	while (!player->meta.interrupt && player->video.hardwareDecoderActive) {
		packetHolder = (PacketHolder *) blockingQueueGet(&player->video.packetQueue, 1);
		if (!player->video.hardwareDecoderActive) {
			if (packetHolder) {
				packetQueueFreeCallback(packetHolder);
				packetHolder = NULL;
			}
			break;
		}
		if (player->sync.skip.videoWorkFrame) {
			player->sync.skip.videoWorkFrame = 0;
		}
		if (!packetHolder || player->meta.interrupt) {
			break;
		}
		condBroadcastLocked(&player->decode.packets.flowCond, &player->decode.packets.flowMutex);
		if (player->meta.interrupt) {
			break;
		}
		pthread_mutex_lock(&player->play.finishMutex);
		while (!player->meta.interrupt && !player->play.playing) {
			pthread_cond_wait(&player->play.finishCond, &player->play.finishMutex);
		}
		pthread_mutex_unlock(&player->play.finishMutex);
		if (player->meta.interrupt) {
			break;
		}
		int packetSent = 0;
		while (!player->meta.interrupt && !player->sync.skip.videoWorkFrame) {
			pthread_mutex_lock(&player->decode.video.frameMutex);
			AVCodecContext * context = GET_CONTEXT(player, video);
			int decodeResult = decodeMediaCodecFrame(player, context, packetHolder->packet, frame, &packetSent);
			int renderResult = decodeResult > 0
					? renderMediaCodecFrame(player, env, stream, frame) : 0;
			if (decodeResult > 0 && renderResult > 0) {
				player->video.hardwareDecodeErrors = 0;
			} else if ((decodeResult < 0 || renderResult < 0) &&
					++player->video.hardwareDecodeErrors >= 3) {
				fallbackMediaCodecToSoftware(player, env);
			}
			pthread_mutex_unlock(&player->decode.video.frameMutex);
			if (decodeResult <= 0 || renderResult <= 0) {
				break;
			}
		}
		if (!packetHolder->packet) {
			markStreamFinished(player, 1);
		}
		packetQueueFreeCallback(packetHolder);
		packetHolder = NULL;
	}
	if (packetHolder) {
		packetQueueFreeCallback(packetHolder);
	}
	av_frame_free(&frame);
	(*loadJavaVM)->DetachCurrentThread(loadJavaVM);
}
#endif

static void * performDecodeVideo(void * data) {
	Player * player = (Player *) data;
	AVStream * stream = GET_STREAM(player, video);
	pthread_mutex_lock(&player->video.sleepDrawMutex);
	while (!player->meta.interrupt && !player->video.bufferQueue && !player->video.hardwareDecoderActive) {
		pthread_cond_wait(&player->video.sleepCond, &player->video.sleepDrawMutex);
	}
	pthread_mutex_unlock(&player->video.sleepDrawMutex);
	if (player->meta.interrupt) {
		return NULL;
	}
#ifdef DASHCHAN_HAS_MEDIACODEC
	if (player->video.hardwareDecoderActive) {
		performDecodeVideoMediaCodec(player, stream);
		if (player->meta.interrupt || player->video.hardwareDecoderActive) {
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
	int lastWidth = context->width;
	int lastHeight = context->height;
	extendScaleHolder(&scaleHolder, player->video.bufferQueue->bufferSize,
			lastWidth, lastHeight, bytesPerPixel, isYUV);
	SparseArray scaleContexts;
	sparseArrayInit(&scaleContexts, 1);
	PacketHolder * packetHolder = NULL;

	int totalMeasurements = 10;
	int currentMeasurement = 0;
	int measurements[2 * totalMeasurements];

	while (!player->meta.interrupt) {
		packetHolder = (PacketHolder *) blockingQueueGet(&player->video.packetQueue, 1);
		if (player->sync.skip.videoWorkFrame) {
			player->sync.skip.videoWorkFrame = 0;
		}
		if (!packetHolder || player->meta.interrupt) {
			break;
		}
		condBroadcastLocked(&player->decode.packets.flowCond, &player->decode.packets.flowMutex);
		if (player->meta.interrupt) {
			break;
		}

		pthread_mutex_lock(&player->play.finishMutex);
		while (!player->meta.interrupt && !player->play.playing) {
			pthread_cond_wait(&player->play.finishCond, &player->play.finishMutex);
		}
		pthread_mutex_unlock(&player->play.finishMutex);
		if (player->meta.interrupt) {
			break;
		}

		int packetSent = 0;
		while (1) {
			int success = 0;
			VideoFrameExtra * extra = NULL;
			if (player->sync.skip.videoWorkFrame) {
				goto SKIP_VIDEO_FRAME;
			}
			pthread_mutex_lock(&player->decode.video.frameMutex);
			if (player->sync.skip.videoWorkFrame) {
				UNLOCK_AND_GOTO(&player->decode.video.frameMutex, SKIP_VIDEO_FRAME);
			}
			int ready = decodeFrame(context, packetHolder->packet, frame, &packetSent);
			pthread_mutex_unlock(&player->decode.video.frameMutex);
			if (!ready) {
				break;
			}

			if (ready) {
				extra = malloc(sizeof(VideoFrameExtra));
				extra->width = frame->width;
				extra->height = frame->height;
				extra->position = getFramePositionMs(player, frame, stream);
				LOG("video frame pts=%" PRId64 " best=%" PRId64 " pkt_dts=%" PRId64
						" pos=%" PRId64 " tb=%d/%d", frame->pts, frame->best_effort_timestamp,
						frame->pkt_dts, extra->position, stream->time_base.num, stream->time_base.den);
				if (extra->position >= 0 && player->meta.seekAnyFrame && player->sync.videoPositionNotSync &&
						extra->position < player->sync.videoPosition) {
					success = 1;
					goto SKIP_VIDEO_FRAME;
				}

				int extendedBufferSize = 0;
				if (lastWidth != frame->width || lastHeight != frame->height) {
					extendedBufferSize = getVideoBufferSize(player->video.format, frame->width, frame->height);
					extendScaleHolder(&scaleHolder, extendedBufferSize, frame->width, frame->height,
							bytesPerPixel, isYUV);
					lastWidth = frame->width;
					lastHeight = frame->height;
				}
				int useLibyuv = frame->format == AV_PIX_FMT_YUV420P && player->video.format == AV_PIX_FMT_RGBA;
				uint64_t startTime = 0;
				if (useLibyuv) {
					if (player->video.useLibyuv >= 0) {
						useLibyuv = player->video.useLibyuv;
					} else {
						if (currentMeasurement < totalMeasurements) {
							useLibyuv = 0;
						}
						if (currentMeasurement < 2 * totalMeasurements) {
							startTime = getTimeUs();
						}
					}
				}
				if (useLibyuv) {
					I420ToABGR(frame->data[0], frame->linesize[0], frame->data[1], frame->linesize[1],
							frame->data[2], frame->linesize[2], scaleHolder.scaleBuffer, 4 * frame->width,
							frame->width, frame->height);
				} else {
					int scaleContextIndex = (frame->width) << 16 | frame->height;
					struct SwsContext * scaleContext = sparseArrayGet(&scaleContexts, scaleContextIndex);
					if (!scaleContext) {
						scaleContext = sws_getContext(frame->width, frame->height, frame->format,
								frame->width, frame->height, player->video.format, SWS_FAST_BILINEAR, NULL, NULL, NULL);
						sparseArrayAdd(&scaleContexts, scaleContextIndex, scaleContext);
					}
					sws_scale(scaleContext, (uint8_t const * const *) frame->data, frame->linesize,
							0, frame->height, scaleHolder.scaleData, scaleHolder.scaleLinesize);
				}
				if (startTime != 0) {
					if (currentMeasurement < 2 * totalMeasurements) {
						measurements[currentMeasurement++] = (int) (getTimeUs() - startTime);
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

				pthread_mutex_lock(&player->video.queueMutex);
				if (player->sync.skip.videoWorkFrame) {
					UNLOCK_AND_GOTO(&player->video.queueMutex, SKIP_VIDEO_FRAME);
				}
				if (extendedBufferSize > 0) {
					bufferQueueExtend(player->video.bufferQueue, extendedBufferSize);
				}
				BufferItem * bufferItem = NULL;
				while (!player->meta.interrupt && !player->sync.skip.videoWorkFrame && !bufferItem) {
					bufferItem = bufferQueuePrepare(player->video.bufferQueue);
					if (!bufferItem) {
						pthread_cond_wait(&player->video.queueCond, &player->video.queueMutex);
					}
				}
				if (bufferItem) {
					memcpy(bufferItem->buffer, scaleHolder.scaleBuffer, player->video.bufferQueue->bufferSize);
					bufferItem->extra = extra;
					bufferQueueAdd(player->video.bufferQueue, bufferItem);
					pthread_cond_broadcast(&player->video.queueCond);
					success = 1;
				}
				pthread_mutex_unlock(&player->video.queueMutex);
			}

			SKIP_VIDEO_FRAME:
			if (!success && extra) {
				free(extra);
			}
			if (!success) {
				break;
			}
		}
		markStreamFinished(player, 1);
		packetQueueFreeCallback(packetHolder);
		packetHolder = NULL;
	}
	if (packetHolder) {
		packetQueueFreeCallback(packetHolder);
	}
	sparseArrayDestroy(&scaleContexts, (SparseArrayDestroyCallback) sws_freeContext);
	av_free(scaleHolder.scaleBuffer);
	av_frame_free(&frame);
	return NULL;
}

static void logDestroyStage(Player * player, const char * stage) {
	diagnosticsLog("player=%u destroy_stage=%s", player->meta.diagnosticsId, stage);
	LOGP("player=%u destroy stage=%s", player->meta.diagnosticsId, stage);
}

static void joinStartedWorkerThreads(Player * player) {
	if (player->decode.audio.threadStarted) {
		logDestroyStage(player, "join_audio_started");
		pthread_join(player->decode.audio.thread, NULL);
		player->decode.audio.threadStarted = 0;
		logDestroyStage(player, "join_audio_finished");
	}
	if (player->decode.video.threadStarted) {
		logDestroyStage(player, "join_video_started");
		pthread_join(player->decode.video.thread, NULL);
		player->decode.video.threadStarted = 0;
		logDestroyStage(player, "join_video_finished");
	}
	if (player->video.drawThreadStarted) {
		logDestroyStage(player, "join_draw_started");
		pthread_join(player->video.drawThread, NULL);
		player->video.drawThreadStarted = 0;
		logDestroyStage(player, "join_draw_finished");
	}
}

static PacketHolder * createPacketHolder(int allocPacket) {
	PacketHolder * packetHolder = malloc(sizeof(PacketHolder));
	packetHolder->packet = allocPacket ? av_packet_alloc() : NULL;
	return packetHolder;
}

static void * performDecodePackets(void * data) {
	Player * player = (Player *) data;
	JNIEnv * env;
	(*loadJavaVM)->AttachCurrentThread(loadJavaVM, &env, NULL);
	Bridge * bridge = obtainBridge(player, env);
	AVPacket packet;
	while (!player->meta.interrupt) {
		while (!player->meta.interrupt) {
			player->sync.skip.readFrame = 0;
			pthread_mutex_lock(&player->decode.packets.readMutex);
			int success = av_read_frame(player->av.format, &packet) >= 0;
			pthread_mutex_unlock(&player->decode.packets.readMutex);
			if (!success) {
				break;
			}
			pthread_mutex_lock(&player->decode.packets.flowMutex);
			if (player->sync.skip.readFrame) {
				goto SKIP_FRAME;
			}
			while (!player->meta.interrupt &&
					(!HAS_STREAM(player, video) || blockingQueueCount(&player->video.packetQueue) >= 10) &&
					(!HAS_STREAM(player, audio) || blockingQueueCount(&player->audio.packetQueue) >= 20)) {
				pthread_cond_wait(&player->decode.packets.flowCond, &player->decode.packets.flowMutex);
			}
			if (player->sync.skip.readFrame) {
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
					diagnosticsRecordVideoPacket(player, &packet);
					LOG("enqueue video %" PRId64, packet.pts);
				}
			}
			SKIP_FRAME:
			av_packet_unref(&packet);
			pthread_mutex_unlock(&player->decode.packets.flowMutex);
		}
		pthread_mutex_lock(&player->decode.packets.flowMutex);
		if (!player->sync.skip.readFrame) {
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
		pthread_mutex_lock(&player->play.finishMutex);
		player->decode.packets.finished = 1;
		int needSendFinishMessage = 1;
		while (!player->meta.interrupt && player->decode.packets.finished) {
			if (needSendFinishMessage &&
					(player->audio.finished || !HAS_STREAM(player, audio)) &&
					(player->video.finished || !HAS_STREAM(player, video))) {
				needSendFinishMessage = 0;
				SEND_MESSAGE(env, player, bridge, BRIDGE_MESSAGE_PLAYBACK_COMPLETE);
			}
			pthread_cond_wait(&player->play.finishCond, &player->play.finishMutex);
		}
		pthread_mutex_unlock(&player->play.finishMutex);
	}
	blockingQueueAdd(&player->audio.packetQueue, NULL);
	blockingQueueAdd(&player->video.packetQueue, NULL);
	logDestroyStage(player, "packet_thread_join_workers");
	joinStartedWorkerThreads(player);
	logDestroyStage(player, "packet_thread_finished");
	(*loadJavaVM)->DetachCurrentThread(loadJavaVM);
	return NULL;
}

static void releasePlayerSurface(Player * player) {
	if (player->video.window) {
		ANativeWindow_release(player->video.window);
		player->video.window = NULL;
	}
}

static int getSoftwareVideoFormat(int windowFormat) {
	switch (windowFormat) {
		case WINDOW_FORMAT_RGBA_8888:
		case WINDOW_FORMAT_RGBX_8888: return AV_PIX_FMT_RGBA;
		case WINDOW_FORMAT_RGB_565: return AV_PIX_FMT_RGB565LE;
		case WINDOW_FORMAT_YV12: return AV_PIX_FMT_YUV420P;
		default: return -1;
	}
}

static int prepareSoftwareVideoOutputLocked(JNIEnv * env, Player * player) {
	if (!player->video.window) {
		return 0;
	}
	int windowFormat = ANativeWindow_getFormat(player->video.window);
	int videoFormat = getSoftwareVideoFormat(windowFormat);
	if (videoFormat < 0) {
		return 0;
	}
	AVCodecContext * context = GET_CONTEXT(player, video);
	int width = context->width;
	int height = context->height;
	if (!player->video.bufferQueue) {
		int videoBufferSize = getVideoBufferSize(videoFormat, width, height);
		player->video.format = videoFormat;
		player->video.bufferQueue = malloc(sizeof(BufferQueue));
		bufferQueueInit(player->video.bufferQueue, videoBufferSize, 3);
		player->video.lastBuffer.data = malloc(videoBufferSize);
		player->video.lastBuffer.size = videoBufferSize;
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
	}
	if (player->video.lastBuffer.width >= 0) {
		width = player->video.lastBuffer.width;
	}
	if (player->video.lastBuffer.height >= 0) {
		height = player->video.lastBuffer.height;
	}
	ANativeWindow_setBuffersGeometry(player->video.window, width, height, windowFormat);
	if (player->video.lastBuffer.data) {
		drawWindow(player, player->video.lastBuffer.data, width, height, width, height, env);
	}
	return 1;
}

#ifdef DASHCHAN_HAS_MEDIACODEC
static AVCodecContext * createSoftwareVideoCodecContext(Player * player) {
	AVStream * stream = GET_STREAM(player, video);
	const AVCodec * codec = avcodec_find_decoder(stream->codecpar->codec_id);
	if (!codec) {
		return NULL;
	}
	AVCodecContext * context = avcodec_alloc_context3(codec);
	if (!context || avcodec_parameters_to_context(context, stream->codecpar) != 0) {
		closeAndFreeCodecContext(&context);
		return NULL;
	}
	context->pkt_timebase = stream->time_base;
	if (avcodec_open2(context, codec, NULL) < 0) {
		closeAndFreeCodecContext(&context);
		return NULL;
	}
	return context;
}

static const char * getMediaCodecDecoderName(enum AVCodecID codecId) {
	switch (codecId) {
		case AV_CODEC_ID_H264: return "h264_mediacodec";
		case AV_CODEC_ID_HEVC: return "hevc_mediacodec";
		default: return NULL;
	}
}

static AVCodecContext * createMediaCodecVideoContext(Player * player, jobject surface) {
	AVStream * stream = GET_STREAM(player, video);
	const char * decoderName = getMediaCodecDecoderName(stream->codecpar->codec_id);
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
		closeAndFreeCodecContext(&context);
		return NULL;
	}
	context->pkt_timebase = stream->time_base;
	context->hw_device_ctx = av_hwdevice_ctx_alloc(AV_HWDEVICE_TYPE_MEDIACODEC);
	if (!context->hw_device_ctx) {
		diagnosticsLog("player=%u mediacodec_open failed_stage=allocate_device_context",
				player->meta.diagnosticsId);
		closeAndFreeCodecContext(&context);
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
		closeAndFreeCodecContext(&context);
		return NULL;
	}
	result = av_hwdevice_ctx_init(context->hw_device_ctx);
	if (result < 0) {
		diagnosticsLog("player=%u mediacodec_open failed_stage=initialize_device_context code=%d",
				player->meta.diagnosticsId, result);
		closeAndFreeCodecContext(&context);
		return NULL;
	}
	diagnosticsLog("player=%u mediacodec_surface configured_via=java_surface"
			" java_surface=%d hw_device=%d",
			player->meta.diagnosticsId, surface != NULL, context->hw_device_ctx != NULL);
	result = avcodec_open2(context, codec, NULL);
	if (result < 0) {
		diagnosticsLog("player=%u mediacodec_open failed_stage=open_codec code=%d",
				player->meta.diagnosticsId, result);
		closeAndFreeCodecContext(&context);
		return NULL;
	}
	diagnosticsLog("player=%u mediacodec_open success decoder=%s pixel_format=%d"
			" hw_device=%d",
			player->meta.diagnosticsId, codec->name, context->pix_fmt,
			context->hw_device_ctx != NULL);
	return context;
}

static int configureMediaCodecSurface(Player * player, jobject surface) {
	if (!player->video.hardwareAccelerationRequested || player->video.hardwareDecoderFailed) {
		diagnosticsLog("player=%u mediacodec_configure skipped requested=%d failed=%d",
				player->meta.diagnosticsId, player->video.hardwareAccelerationRequested,
				player->video.hardwareDecoderFailed);
		return 0;
	}
	int decoderReset = player->video.hardwareSurfaceInitialized;
	diagnosticsLog("player=%u mediacodec_configure started reset=%d",
			player->meta.diagnosticsId, decoderReset);
	AVCodecContext * context = createMediaCodecVideoContext(player, surface);
	if (context) {
		closeAndFreeVideoCodecContext(player, &player->av.videoContext);
		player->av.videoContext = context;
		player->video.hardwareDecoderActive = 1;
		player->video.hardwareSurfaceInitialized = 1;
		diagnosticsIncrement(&diagnostics.stats.decoderEnabled);
		diagnosticsLog("player=%u mediacodec_configure success decoder=%s",
				player->meta.diagnosticsId, context->codec->name);
		LOGP("MediaCodec video decoder enabled: %s", context->codec->name);
		return decoderReset;
	}
	player->video.hardwareDecoderFailed = 1;
	diagnosticsIncrement(&diagnostics.stats.decoderUnavailable);
	diagnosticsLog("player=%u mediacodec_configure failed", player->meta.diagnosticsId);
	LOGP("MediaCodec video decoder unavailable, using software decoder");
	if (player->video.hardwareDecoderActive) {
		context = createSoftwareVideoCodecContext(player);
		if (context) {
			closeAndFreeVideoCodecContext(player, &player->av.videoContext);
			player->av.videoContext = context;
			player->video.hardwareDecoderActive = 0;
		}
	}
	return decoderReset;
}

static int fallbackMediaCodecToSoftware(Player * player, JNIEnv * env) {
	if (!player->video.window ||
			getSoftwareVideoFormat(ANativeWindow_getFormat(player->video.window)) < 0) {
		return 0;
	}
	AVCodecContext * context = createSoftwareVideoCodecContext(player);
	if (!context) {
		return 0;
	}
	pthread_mutex_lock(&player->video.sleepDrawMutex);
	closeAndFreeVideoCodecContext(player, &player->av.videoContext);
	player->av.videoContext = context;
	player->video.hardwareDecoderActive = 0;
	player->video.hardwareDecoderFailed = 1;
	player->video.hardwareDecodeErrors = 0;
	int outputPrepared = prepareSoftwareVideoOutputLocked(env, player);
	pthread_mutex_unlock(&player->video.sleepDrawMutex);
	diagnosticsIncrement(&diagnostics.stats.softwareFallbacks);
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
		if (!player->video.window) {
			diagnosticsLog("player=%u surface_attach failed_stage=create_native_window",
					player->meta.diagnosticsId);
			return 0;
		}
#ifdef DASHCHAN_HAS_MEDIACODEC
		decoderReset = configureMediaCodecSurface(player, surface);
#endif
		if (player->video.hardwareDecoderActive) {
			pthread_cond_broadcast(&player->video.sleepCond);
			return decoderReset;
		}
		prepareSoftwareVideoOutputLocked(env, player);
	}
	return decoderReset;
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
				Bridge * bridge = sparseArrayGet(&player->bridge.array, (int) pthread_self());
				if (bridge) {
					LOG("read data request");
					(*bridge->env)->CallVoidMethod(bridge->env, player->bridge.native, bridge->methodOnSeek, offset);
				}
			}
			LOG("read data wait");
			pthread_cond_wait(&player->file.controlCond, &player->file.controlMutex);
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
	player->meta.diagnosticsId = __atomic_add_fetch(&nextDiagnosticsPlayerId, 1, __ATOMIC_RELAXED);
	player->file.total = -1;
	player->meta.audioEnabled = 1;
	player->av.audioStreamIndex = INDEX_NO_STREAM;
	player->av.videoStreamIndex = INDEX_NO_STREAM;
	player->video.useLibyuv = -1;
	player->video.lastBuffer.width = -1;
	player->video.lastBuffer.height = -1;
	player->sync.playbackSpeed = PLAYBACK_SPEED_DEFAULT;
	sparseArrayInit(&player->bridge.array, 4);
	pthread_mutex_init(&player->file.controlMutex, NULL);
	pthread_cond_init(&player->file.controlCond, NULL);
	pthread_mutex_init(&player->decode.packets.readMutex, NULL);
	pthread_cond_init(&player->decode.packets.flowCond, NULL);
	pthread_mutex_init(&player->decode.packets.flowMutex, NULL);
	pthread_mutex_init(&player->decode.audio.frameMutex, NULL);
	pthread_mutex_init(&player->decode.video.frameMutex, NULL);
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

#define NEED_RESAMPLE_NO 0
#define NEED_RESAMPLE_MAY_48000 1
#define NEED_RESAMPLE_FORCE_44100 2

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

void init(JNIEnv * env, jlong pointer, jobject nativeBridge, jboolean seekAnyFrame) {
	Player * player = POINTER_CAST(pointer);
	diagnosticsLog("player=%u init_started seek_any_frame=%d",
			player->meta.diagnosticsId, !!seekAnyFrame);
	player->meta.seekAnyFrame = !!seekAnyFrame;
	player->bridge.native = (*env)->NewGlobalRef(env, nativeBridge);
	obtainBridge(player, env);
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
	diagnosticsRecordMediaInfo(player);
	if (audioStream) {
		SLresult result;
		int success = 0;
		int sourceChannels = getAudioContextChannels(player->av.audioContext);
		int channels = sourceChannels;
		int streamChannels = getCodecParametersChannels(audioStream->codecpar);
		if (streamChannels > channels) {
			channels = streamChannels;
		}
#if USE_AV_CHANNEL_LAYOUT
		int sourceLayoutChannels = sourceChannels > streamChannels ? sourceChannels : streamChannels;
#else
		uint64_t sourceChannelLayout = player->av.audioContext->channel_layout != 0
				? player->av.audioContext->channel_layout : audioStream->codecpar->channel_layout;
		if (sourceChannelLayout != 0) {
			int layoutChannels = av_get_channel_layout_nb_channels(sourceChannelLayout);
			if (layoutChannels > channels) {
				channels = layoutChannels;
			}
		}
#endif
		if (channels != 1 && channels != 2) {
			channels = 2;
		}
		uint64_t outputChannelLayout = channels == 2
				? AV_CH_FRONT_LEFT | AV_CH_FRONT_RIGHT : AV_CH_FRONT_CENTER;
		player->audio.resampleChannels = outputChannelLayout;
		int channelMask = channels == 2 ? SL_SPEAKER_FRONT_LEFT | SL_SPEAKER_FRONT_RIGHT : SL_SPEAKER_FRONT_CENTER;
		const SLInterfaceID volumeIds[] = {SL_IID_VOLUME};
		const SLboolean volumeRequired[] = {SL_BOOLEAN_FALSE};
		result = (*slEngine)->CreateOutputMix(slEngine, &player->audio.sl.outputMix, 1, volumeIds, volumeRequired);
		if (result != SL_RESULT_SUCCESS) {
			LOGP("SLES CreateOutputMix: result=%d", (int) result);
			goto HANDLE_SL_INIT_ERROR;
		}
		result = (*player->audio.sl.outputMix)->Realize(player->audio.sl.outputMix, SL_BOOLEAN_FALSE);
		if (result != SL_RESULT_SUCCESS) {
			LOGP("SLES outputMix.Realize: result=%d", (int) result);
			goto HANDLE_SL_INIT_ERROR;
		}
		SLDataLocator_AndroidSimpleBufferQueue locatorQueue = {SL_DATALOCATOR_ANDROIDSIMPLEBUFFERQUEUE, 2};
		SLDataFormat_PCM formatPCM = {SL_DATAFORMAT_PCM, channels, 0, SL_PCMSAMPLEFORMAT_FIXED_16,
				SL_PCMSAMPLEFORMAT_FIXED_16, channelMask, SL_BYTEORDER_LITTLEENDIAN};
		SLDataSource dataSource = {&locatorQueue, &formatPCM};
		SLDataLocator_OutputMix locatorOutputMix = {SL_DATALOCATOR_OUTPUTMIX, player->audio.sl.outputMix};
		SLDataSink dataSink = {&locatorOutputMix, NULL};
		const SLInterfaceID playerIds[] = {SL_IID_ANDROIDSIMPLEBUFFERQUEUE, SL_IID_VOLUME};
		const SLboolean playerRequired[] = {SL_BOOLEAN_TRUE, SL_BOOLEAN_FALSE};
		int needResampleSR = NEED_RESAMPLE_NO;
		int slSampleRate = 0;
		int sampleRate = player->av.audioContext->sample_rate;
		int outputSampleRate = sampleRate;
		switch (sampleRate) {
			case 8000: slSampleRate = SL_SAMPLINGRATE_8; break;
			case 11025: slSampleRate = SL_SAMPLINGRATE_11_025; break;
			case 12000: slSampleRate = SL_SAMPLINGRATE_12; break;
			case 16000: slSampleRate = SL_SAMPLINGRATE_16; break;
			case 22050: slSampleRate = SL_SAMPLINGRATE_22_05; break;
			case 24000: slSampleRate = SL_SAMPLINGRATE_24; break;
			case 32000: slSampleRate = SL_SAMPLINGRATE_32; break;
			case 44100: slSampleRate = SL_SAMPLINGRATE_44_1; break;
			case 48000: slSampleRate = SL_SAMPLINGRATE_48; break;
			case 64000: slSampleRate = SL_SAMPLINGRATE_64; break;
			case 88200: slSampleRate = SL_SAMPLINGRATE_88_2; break;
			case 96000: slSampleRate = SL_SAMPLINGRATE_96; break;
			case 192000: slSampleRate = SL_SAMPLINGRATE_192; break;
			default: needResampleSR = NEED_RESAMPLE_MAY_48000;
		}
		while (1) {
			int mayRepeat = 1;
			outputSampleRate = sampleRate;
			if (needResampleSR == NEED_RESAMPLE_MAY_48000 && sampleRate % 48000 == 0) {
				slSampleRate = SL_SAMPLINGRATE_48;
				outputSampleRate = 48000;
			} else if (needResampleSR == NEED_RESAMPLE_MAY_48000 || needResampleSR == NEED_RESAMPLE_FORCE_44100) {
				slSampleRate = SL_SAMPLINGRATE_44_1;
				outputSampleRate = 44100;
				mayRepeat = 0;
			}
			player->audio.resampleSampleRate = outputSampleRate;
			formatPCM.samplesPerSec = slSampleRate;
			result = (*slEngine)->CreateAudioPlayer(slEngine, &player->audio.sl.player,
					&dataSource, &dataSink, 2, playerIds, playerRequired);
#if USE_AV_CHANNEL_LAYOUT
			LOGP("SLES CreateAudioPlayer: result=%d, sourceChannels=%d, outputChannels=%d, "
					"sourceLayoutChannels=%d, sourceSampleRate=%d, outputSampleRate=%d",
					(int) result, sourceChannels, channels, sourceLayoutChannels,
					sampleRate, player->audio.resampleSampleRate);
#else
			LOGP("SLES CreateAudioPlayer: result=%d, sourceChannels=%d, outputChannels=%d, "
					"sourceLayout=%llu, sourceSampleRate=%d, outputSampleRate=%d",
					(int) result, sourceChannels, channels, (unsigned long long) sourceChannelLayout,
					sampleRate, player->audio.resampleSampleRate);
#endif
			if (result == SL_RESULT_CONTENT_UNSUPPORTED && mayRepeat) {
				if (needResampleSR == NEED_RESAMPLE_NO) {
					needResampleSR = NEED_RESAMPLE_MAY_48000;
				} else if (needResampleSR == NEED_RESAMPLE_MAY_48000) {
					needResampleSR = NEED_RESAMPLE_FORCE_44100;
				}
			} else {
				break;
			}
		}
		if (result != SL_RESULT_SUCCESS) {
			goto HANDLE_SL_INIT_ERROR;
		}
		result = (*player->audio.sl.player)->Realize(player->audio.sl.player, SL_BOOLEAN_FALSE);
		if (result != SL_RESULT_SUCCESS) {
			LOGP("SLES player.Realize: result=%d", (int) result);
			goto HANDLE_SL_INIT_ERROR;
		}
		result = (*player->audio.sl.player)->GetInterface(player->audio.sl.player,
				SL_IID_BUFFERQUEUE, &player->audio.sl.queue);
		if (result != SL_RESULT_SUCCESS) {
			LOGP("SLES player.GetInterface(SL_IID_BUFFERQUEUE): result=%d", (int) result);
			goto HANDLE_SL_INIT_ERROR;
		}
		result = (*player->audio.sl.player)->GetInterface(player->audio.sl.player,
				SL_IID_PLAY, &player->audio.sl.play);
		if (result != SL_RESULT_SUCCESS) {
			LOGP("SLES player.GetInterface(SL_IID_PLAY): result=%d", (int) result);
			goto HANDLE_SL_INIT_ERROR;
		}
		result = (*player->audio.sl.player)->GetInterface(player->audio.sl.player,
				SL_IID_VOLUME, &player->audio.sl.volume);
		if (result != SL_RESULT_SUCCESS) {
			LOGP("SLES player.GetInterface(SL_IID_VOLUME): result=%d", (int) result);
			player->audio.sl.volume = NULL;
		}
		result = (*player->audio.sl.queue)->RegisterCallback(player->audio.sl.queue, audioPlayerCallback, player);
		if (result != SL_RESULT_SUCCESS) {
			LOGP("SLES player.RegisterCallback: result=%d", (int) result);
			goto HANDLE_SL_INIT_ERROR;
		}
		result = (*player->audio.sl.play)->SetPlayState(player->audio.sl.play, SL_PLAYSTATE_PLAYING);
		if (result != SL_RESULT_SUCCESS) {
			LOGP("SLES player.SetPlayState: result=%d", (int) result);
			goto HANDLE_SL_INIT_ERROR;
		}
		success = 1;
		HANDLE_SL_INIT_ERROR:
		if (!success) {
			closeAndFreeCodecContext(&player->av.audioContext);
			player->av.audioContext = NULL;
			audioStreamIndex = INDEX_NO_STREAM;
			player->av.audioStreamIndex = INDEX_NO_STREAM;
			audioStream = NULL;
			audioCodec = NULL;
		}
	}
	if (videoStream) {
		if (pthread_create(&player->video.drawThread, NULL, &performDraw, player) != 0) {
			player->meta.errorCode = ERROR_START_THREAD;
			return;
		}
		player->video.drawThreadStarted = 1;
	}
	if (audioStream) {
		if (pthread_create(&player->decode.audio.thread, NULL, &performDecodeAudio, player) != 0) {
			player->meta.errorCode = ERROR_START_THREAD;
			return;
		}
		player->decode.audio.threadStarted = 1;
	}
	if (videoStream) {
		if (pthread_create(&player->decode.video.thread, NULL, &performDecodeVideo, player) != 0) {
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
}

void destroy(JNIEnv * env, jlong pointer, jboolean initOnly) {
	Player * player = POINTER_CAST(pointer);
	diagnosticsLog("player=%u destroy init_only=%d error_code=%d hardware_active=%d",
			player->meta.diagnosticsId, !!initOnly, player->meta.errorCode,
			player->video.hardwareDecoderActive);
	logDestroyStage(player, "started");
	player->meta.interrupt = 1;
	condBroadcastLocked(&player->file.controlCond, &player->file.controlMutex);
	if (!!initOnly) {
		logDestroyStage(player, "init_only_finished");
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
	logDestroyStage(player, "workers_signaled");

	if (player->decode.packets.threadStarted) {
		logDestroyStage(player, "join_packets_started");
		pthread_join(player->decode.packets.thread, NULL);
		player->decode.packets.threadStarted = 0;
		logDestroyStage(player, "join_packets_finished");
	} else {
		joinStartedWorkerThreads(player);
	}
	logDestroyStage(player, "synchronization_cleanup_started");
	pthread_mutex_destroy(&player->decode.packets.readMutex);
	pthread_mutex_destroy(&player->decode.packets.flowMutex);
	pthread_mutex_destroy(&player->decode.audio.frameMutex);
	pthread_mutex_destroy(&player->decode.video.frameMutex);
	pthread_mutex_destroy(&player->play.finishMutex);
	pthread_mutex_destroy(&player->audio.sleepBufferMutex);
	pthread_mutex_destroy(&player->video.sleepDrawMutex);
	pthread_mutex_destroy(&player->video.queueMutex);
	pthread_mutex_destroy(&player->file.controlMutex);
	pthread_cond_destroy(&player->decode.packets.flowCond);
	pthread_cond_destroy(&player->play.finishCond);
	pthread_cond_destroy(&player->audio.sleepCond);
	pthread_cond_destroy(&player->audio.bufferCond);
	pthread_cond_destroy(&player->video.sleepCond);
	pthread_cond_destroy(&player->video.queueCond);
	pthread_cond_destroy(&player->file.controlCond);
	logDestroyStage(player, "synchronization_cleanup_finished");

	blockingQueueDestroy(&player->audio.packetQueue, packetQueueFreeCallback);
	blockingQueueDestroy(&player->video.packetQueue, packetQueueFreeCallback);
	blockingQueueDestroy(&player->audio.bufferQueue, audioBufferQueueFreeCallback);
	if (player->video.bufferQueue) {
		bufferQueueDestroy(player->video.bufferQueue, videoBufferQueueFreeCallback);
		free(player->video.bufferQueue);
		free(player->video.lastBuffer.data);
	}
	logDestroyStage(player, "buffers_cleanup_finished");

	if (player->audio.sl.player) {
		logDestroyStage(player, "opensl_player_destroy_started");
		(*player->audio.sl.player)->Destroy(player->audio.sl.player);
		logDestroyStage(player, "opensl_player_destroy_finished");
	}
	if (player->audio.sl.outputMix) {
		logDestroyStage(player, "opensl_output_mix_destroy_started");
		(*player->audio.sl.outputMix)->Destroy(player->audio.sl.outputMix);
		logDestroyStage(player, "opensl_output_mix_destroy_finished");
	}
	if (HAS_STREAM(player, audio)) {
		logDestroyStage(player, "audio_codec_close_started");
		AVCodecContext * audioContext = GET_CONTEXT(player, audio);
		closeAndFreeCodecContext(&audioContext);
		logDestroyStage(player, "audio_codec_close_finished");
	}
	if (HAS_STREAM(player, video)) {
		logDestroyStage(player, "video_codec_close_started");
		closeAndFreeVideoCodecContext(player, &player->av.videoContext);
		logDestroyStage(player, "video_codec_close_finished");
	}
	if (player->av.format) {
		logDestroyStage(player, "format_close_started");
		AVIOContext * ioContext = player->av.format->pb;
		avformat_close_input(&player->av.format);
		av_free(ioContext->buffer);
		av_free(ioContext);
		logDestroyStage(player, "format_close_finished");
	}
	if (player->audio.buffer) {
		audioBufferQueueFreeCallback(player->audio.buffer);
		player->audio.buffer = NULL;
	}
	releasePlayerSurface(player);
	logDestroyStage(player, "surface_released");
	sparseArrayDestroy(&player->bridge.array, free);
	if (player->bridge.native) {
		(*env)->DeleteGlobalRef(env, player->bridge.native);
	}
	if (player->file.fd > 0) {
		close(player->file.fd);
	}
	logDestroyStage(player, "finished");
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
	return max64(player->av.format->duration / 1000, 0);
}

jlong getPosition(jlong pointer) {
	Player * player = POINTER_CAST(pointer);
	return max64(calculatePosition(player, 0), 0);
}

void setPosition(JNIEnv * env, jlong pointer, jlong position) {
	Player * player = POINTER_CAST(pointer);
	diagnosticsLog("player=%u seek requested_position_ms=%" PRId64,
			player->meta.diagnosticsId, (int64_t) position);
	if (position >= 0) {
		// Leave the call below even without variable declaration to init a bridge here
		Bridge * bridge = obtainBridge(player, env);
		pthread_mutex_lock(&player->play.finishMutex);
		pthread_mutex_lock(&player->decode.packets.readMutex);
		pthread_mutex_lock(&player->decode.packets.flowMutex);
		pthread_mutex_lock(&player->decode.audio.frameMutex);
		pthread_mutex_lock(&player->decode.video.frameMutex);
		pthread_mutex_lock(&player->audio.sleepBufferMutex);
		pthread_mutex_lock(&player->video.sleepDrawMutex);
		pthread_mutex_lock(&player->video.queueMutex);
		blockingQueueClear(&player->audio.packetQueue, packetQueueFreeCallback);
		blockingQueueClear(&player->video.packetQueue, packetQueueFreeCallback);
		blockingQueueClear(&player->audio.bufferQueue, audioBufferQueueFreeCallback);
		audioBufferQueueFreeCallback(player->audio.buffer);
		if (player->audio.sl.queue) {
			(*player->audio.sl.queue)->Clear(player->audio.sl.queue);
			player->audio.bufferNeedEnqueueAfterDecode = 1;
		}
		player->audio.buffer = NULL;
		if (player->video.bufferQueue) {
			bufferQueueClear(player->video.bufferQueue, videoBufferQueueFreeCallback);
		}
		if (HAS_STREAM(player, audio)) {
			avcodec_flush_buffers(GET_CONTEXT(player, audio));
		}
		if (HAS_STREAM(player, video)) {
			avcodec_flush_buffers(GET_CONTEXT(player, video));
		}
		if (player->meta.seekAnyFrame) {
			int64_t audioPosition = HAS_STREAM(player, audio) ? -1 : position;
			int64_t videoPosition = HAS_STREAM(player, video) ? -1 : position;
			AVPacket packet;
			for (int i = 1; audioPosition == -1 || videoPosition == -1; i++) {
				int64_t seekPosition = max64(position - i * i * 1000, 0);
				int64_t maxPosition = max64(position - (i - 1) * (i - 1) * 1000, 0);
				av_seek_frame(player->av.format, -1, getSeekTimestampUs(player, seekPosition),
						AVSEEK_FLAG_BACKWARD | AVSEEK_FLAG_ANY);
				while (1) {
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
			if (audioPosition == -1) {
				audioPosition = position;
			}
			if (videoPosition == -1) {
				videoPosition = position;
			}
			position = min64(audioPosition, videoPosition);
		}
		av_seek_frame(player->av.format, -1, getSeekTimestampUs(player, position), AVSEEK_FLAG_BACKWARD);
		player->decode.packets.finished = 0;
		player->audio.finished = 0;
		player->video.finished = 0;
		updateAudioPositionSurrogate(player, position, 1);
		player->sync.audioPosition = position;
		player->sync.videoPosition = position;
		player->sync.pausedPosition = position;
		player->sync.audioPositionNotSync = 1;
		player->sync.videoPositionNotSync = 1;
		player->sync.skip.readFrame = 1;
		player->sync.skip.audioWorkFrame = 1;
		player->sync.skip.videoWorkFrame = 1;
		player->sync.skip.drawWorkFrame = 1;
		player->sync.lastDrawTimes[0] = 0;
		player->sync.lastDrawTimes[1] = 0;
		if (HAS_STREAM(player, video)) {
			SEND_MESSAGE(env, player, bridge, BRIDGE_MESSAGE_START_SEEKING);
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
		pthread_mutex_unlock(&player->decode.packets.readMutex);
		pthread_mutex_unlock(&player->play.finishMutex);
	}
}

void setRange(jlong pointer, jlong start, jlong end, jlong total) {
	Player * player = POINTER_CAST(pointer);
	pthread_mutex_lock(&player->file.controlMutex);
	player->file.start = start;
	player->file.end = end;
	player->file.total = total;
	LOG("set range range=[%ld-%ld/%ld]", player->file.start, player->file.end, player->file.total);
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
			blockingQueueClear(&player->audio.bufferQueue, audioBufferQueueFreeCallback);
			audioBufferQueueFreeCallback(player->audio.buffer);
			player->audio.buffer = NULL;
			if (player->audio.sl.queue) {
				(*player->audio.sl.queue)->Clear(player->audio.sl.queue);
			}
			player->audio.bufferNeedEnqueueAfterDecode = 1;
			player->sync.audioPositionNotSync = 0;
			player->sync.skip.audioWorkFrame = 1;
		}
		pthread_cond_broadcast(&player->audio.sleepCond);
		pthread_cond_broadcast(&player->audio.bufferCond);
		pthread_cond_broadcast(&player->video.sleepCond);
		pthread_mutex_unlock(&player->video.sleepDrawMutex);
		pthread_mutex_unlock(&player->audio.sleepBufferMutex);
		pthread_mutex_unlock(&player->play.finishMutex);
	}
}

jboolean setMuted(jlong pointer, jboolean muted) {
	Player * player = POINTER_CAST(pointer);
	if (!HAS_STREAM(player, audio) || !player->audio.sl.volume) {
		return 0;
	}
	pthread_mutex_lock(&player->audio.sleepBufferMutex);
	SLresult result = (*player->audio.sl.volume)->SetMute(player->audio.sl.volume,
			muted ? SL_BOOLEAN_TRUE : SL_BOOLEAN_FALSE);
	pthread_mutex_unlock(&player->audio.sleepBufferMutex);
	return result == SL_RESULT_SUCCESS;
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
		if (HAS_STREAM(player, audio)) {
			pthread_mutex_lock(&player->audio.sleepBufferMutex);
			(*player->audio.sl.play)->SetPlayState(player->audio.sl.play,
					playing ? SL_PLAYSTATE_PLAYING : SL_PLAYSTATE_PAUSED);
			if (playing && player->audio.bufferNeedEnqueueAfterDecode
					&& blockingQueueCount(&player->audio.bufferQueue) > 0) {
				// Queue count checked to free from obligation to handle audio finish flag
				enqueueAudioBuffer(player);
			}
			pthread_mutex_unlock(&player->audio.sleepBufferMutex);
		}
	}
}

jboolean setSurface(JNIEnv * env, jlong pointer, jobject surface) {
	Player * player = POINTER_CAST(pointer);
	diagnosticsLog("player=%u set_surface_started attached=%d",
			player->meta.diagnosticsId, surface != NULL);
	LOGP("player=%u set surface started attached=%d",
			player->meta.diagnosticsId, surface != NULL);
	pthread_mutex_lock(&player->decode.video.frameMutex);
	pthread_mutex_lock(&player->video.sleepDrawMutex);
	releasePlayerSurface(player);
	int decoderReset = setPlayerSurfaceLocked(env, player, surface);
	if (surface) {
		diagnosticsIncrement(&diagnostics.stats.surfaceAttached);
	} else {
		diagnosticsIncrement(&diagnostics.stats.surfaceDetached);
	}
	diagnosticsLog("player=%u surface attached=%d decoder_reset=%d hardware_active=%d"
			" native_window=%d",
			player->meta.diagnosticsId, surface != NULL, decoderReset,
			player->video.hardwareDecoderActive, player->video.window != NULL);
	pthread_mutex_unlock(&player->video.sleepDrawMutex);
	pthread_mutex_unlock(&player->decode.video.frameMutex);
	LOGP("player=%u set surface finished attached=%d",
			player->meta.diagnosticsId, surface != NULL);
	return decoderReset;
}

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
			if (player->video.lastBuffer.size < 4 * sourceWidth * sourceHeight) {
				goto RELEASE_PRIMITIVE_ARRAY;
			}
			const uint8_t * const oldData[4] = {buffer, 0, 0, 0};
			int oldLinesize[4] = {4 * sourceWidth, 0, 0, 0};
			sws_scale(scaleContext, oldData, oldLinesize, 0, sourceHeight, newData, newLinesize);
		} else if (player->video.format == AV_PIX_FMT_RGB565LE) {
			if (player->video.lastBuffer.size < 2 * sourceWidth * sourceHeight) {
				goto RELEASE_PRIMITIVE_ARRAY;
			}
			const uint8_t * const oldData[4] = {buffer, 0, 0, 0};
			int oldLinesize[4] = {2 * sourceWidth, 0, 0, 0};
			sws_scale(scaleContext, oldData, oldLinesize, 0, sourceHeight, newData, newLinesize);
		} else if (player->video.format == AV_PIX_FMT_YUV420P) {
			if (player->video.lastBuffer.size < sourceWidth * sourceHeight * 3 / 2) {
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
		sprintf(buffer, "%d", getAudioContextChannels(audioContext));
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
	SLObjectItf engineObject;
	slCreateEngine(&engineObject, 0, NULL, 0, NULL, NULL);
	(*engineObject)->Realize(engineObject, SL_BOOLEAN_FALSE);
	(*engineObject)->GetInterface(engineObject, SL_IID_ENGINE, &slEngine);
}
