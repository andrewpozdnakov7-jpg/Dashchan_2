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
#include <limits.h>
#include <math.h>
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

#define PACKET_HOLDER_MEDIA 0
#define PACKET_HOLDER_END_OF_STREAM 1
#define PACKET_HOLDER_SURFACE_REQUEST 2

#define INDEX_NO_STREAM -1
#define GAINING_THRESHOLD 100
#define MEDIACODEC_MAX_SCHEDULE_AHEAD_MS 50
#define SEEK_KEYFRAME_DISCOVERY_THRESHOLD_MS 3000
#define DURATION_PROBE_TOLERANCE_MS 1000
#define SOFTWARE_OUTPUT_MAX_LONG_SIDE 1920
#define SOFTWARE_OUTPUT_MAX_PIXELS (1920 * 1080)
#define SOFTWARE_OUTPUT_FALLBACK_MAX_LONG_SIDE 1280
#define SOFTWARE_OUTPUT_FALLBACK_MAX_PIXELS (1280 * 720)
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
#define AUDIO_MAX_BOOST_DB 12
#define AUDIO_OUTPUT_QUEUE_CAPACITY 2
#define AUDIO_TARGET_CHUNK_MS 40
#define AUDIO_MIN_ENQUEUE_SIZE 256
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
	uint64_t softwareDecodedFrames;
	uint64_t softwareRenderedFrames;
	uint64_t softwareDroppedDecodeLate;
	uint64_t softwareDroppedDrawLate;
	uint64_t softwareLateAnchorsQueued;
	uint64_t softwareLateAnchorsRendered;
	uint64_t softwareOutputDowngrades;
	uint64_t softwareDecoderDiscardEnabled;
	uint64_t softwareDecoderDiscardRestored;
	uint64_t softwareSeekFastStarted;
	uint64_t softwareSeekFastRestored;
	uint64_t audioChunksSubmitted;
	uint64_t audioOutputUnderruns;
	uint64_t audioMasterResumed;
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
		diagnosticsAppendLineLocked(DIAGNOSTICS_BUFFER_SIZE - DIAGNOSTICS_SUMMARY_RESERVE,
				"diagnostics_schema=11 native_seek_locks=1 mediacodec_stages=1"
				" seek_worker_stop=1 surface_queue=1 duration_probe=1 packet_generation=1"
				" software_output_scaling=1 software_late_drop=1 software_decode_governor=1"
				" software_governor_recovery=2 software_late_anchor_ms=200"
				" software_seek_fast_decode=1 audio_master_clock=1"
				" audio_output_prefill=2 audio_chunk_target_ms=40");
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
	diagnosticsAppendLineLocked(DIAGNOSTICS_BUFFER_SIZE,
			"summary software_decoded=%" PRIu64 " software_rendered=%" PRIu64
			" software_drop_decode_late=%" PRIu64 " software_drop_draw_late=%" PRIu64
			" software_late_anchor_queued=%" PRIu64 " software_late_anchor_rendered=%" PRIu64,
			diagnostics.stats.softwareDecodedFrames, diagnostics.stats.softwareRenderedFrames,
			diagnostics.stats.softwareDroppedDecodeLate,
			diagnostics.stats.softwareDroppedDrawLate,
			diagnostics.stats.softwareLateAnchorsQueued,
			diagnostics.stats.softwareLateAnchorsRendered);
	diagnosticsAppendLineLocked(DIAGNOSTICS_BUFFER_SIZE,
			"summary software_output_downgrades=%" PRIu64
			" software_decoder_discard_enabled=%" PRIu64
			" software_decoder_discard_restored=%" PRIu64
			" software_seek_fast_started=%" PRIu64 " software_seek_fast_restored=%" PRIu64,
			diagnostics.stats.softwareOutputDowngrades,
			diagnostics.stats.softwareDecoderDiscardEnabled,
			diagnostics.stats.softwareDecoderDiscardRestored,
			diagnostics.stats.softwareSeekFastStarted,
			diagnostics.stats.softwareSeekFastRestored);
	diagnosticsAppendLineLocked(DIAGNOSTICS_BUFFER_SIZE,
			"summary audio_chunks_submitted=%" PRIu64
			" audio_output_underruns=%" PRIu64 " audio_master_resumed=%" PRIu64,
			diagnostics.stats.audioChunksSubmitted,
			diagnostics.stats.audioOutputUnderruns,
			diagnostics.stats.audioMasterResumed);
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

enum {
	DIAGNOSTICS_AUDIO_STAGE_IDLE,
	DIAGNOSTICS_AUDIO_STAGE_WAIT_FRAME_MUTEX,
	DIAGNOSTICS_AUDIO_STAGE_DECODE_FRAME,
	DIAGNOSTICS_AUDIO_STAGE_WAIT_SLEEP_BUFFER_MUTEX,
	DIAGNOSTICS_AUDIO_STAGE_QUEUE_BUFFER
};

enum {
	DIAGNOSTICS_MEDIACODEC_STAGE_IDLE,
	DIAGNOSTICS_MEDIACODEC_STAGE_WAIT_FRAME_MUTEX,
	DIAGNOSTICS_MEDIACODEC_STAGE_SEND_PACKET,
	DIAGNOSTICS_MEDIACODEC_STAGE_RECEIVE_FRAME,
	DIAGNOSTICS_MEDIACODEC_STAGE_WAIT_SLEEP_DRAW_MUTEX,
	DIAGNOSTICS_MEDIACODEC_STAGE_RENDER_FRAME,
	DIAGNOSTICS_MEDIACODEC_STAGE_WAIT_RENDER_TIME,
	DIAGNOSTICS_MEDIACODEC_STAGE_SCHEDULE_BUFFER,
	DIAGNOSTICS_MEDIACODEC_STAGE_RELEASE_BUFFER,
	DIAGNOSTICS_MEDIACODEC_STAGE_FINISH_SEEK,
	DIAGNOSTICS_MEDIACODEC_STAGE_RECORD_OUTPUT,
	DIAGNOSTICS_MEDIACODEC_STAGE_UNREF_FRAME,
	DIAGNOSTICS_MEDIACODEC_STAGE_UNLOCK_FRAME_MUTEX
};

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

		struct {
			int initialized;
			int probeRequired;
			int probeThreadStarted;
			pthread_t probeThread;
			int64_t declaredMs __attribute__((aligned(8)));
			int64_t effectiveMs __attribute__((aligned(8)));
		} duration;
	} av;

	struct {
		struct {
			int finished;
			int threadStarted;
			pthread_t thread;
			pthread_mutex_t readMutex;
			pthread_cond_t flowCond;
			pthread_mutex_t flowMutex;
			uint64_t generation __attribute__((aligned(8)));
		} packets;

		struct {
			int threadStarted;
			pthread_t thread;
			pthread_mutex_t frameMutex;
			int diagnosticsStage;
		} audio;

		struct {
			int threadStarted;
			pthread_t thread;
			pthread_mutex_t frameMutex;
			int diagnosticsStage;
			uint64_t diagnosticsFrameSerial __attribute__((aligned(8)));
			int64_t diagnosticsFramePosition __attribute__((aligned(8)));
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
		int localVolume;
		int boostDb;
		int bufferNeedEnqueueAfterDecode;
		BlockingQueue bufferQueue;
		AudioBuffer * buffer;
		struct {
			AudioBuffer * buffer;
			int offset;
			int size;
		} outputChunks[AUDIO_OUTPUT_QUEUE_CAPACITY];
		int outputChunkHead;
		int outputChunkCount;
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
		jobject activeSurface;
		jobject pendingSurface;
		int64_t pendingSurfaceGeneration __attribute__((aligned(8)));
		int pendingSurfaceWidth;
		int pendingSurfaceHeight;
		int surfaceRequestPending;
		int surfaceWidth;
		int surfaceHeight;
		pthread_mutex_t surfaceMutex;
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
		int softwareOutputLevel;
		int softwareConsecutiveLateFrames;
		int softwareConsecutiveRecoveryFrames;
		int softwareSlowConversions;
		int softwareDecoderDiscardActive;
		int64_t softwareDecoderDiscardStartedAt __attribute__((aligned(8)));
		int64_t softwareLastFrameQueuedAt __attribute__((aligned(8)));
		int softwareSeekFastActive;
		int softwareSeekFastPackets;
		int softwareSeekFastFrames;
		int64_t softwareSeekFastTargetPosition __attribute__((aligned(8)));
		int64_t softwareSeekFastRestorePosition __attribute__((aligned(8)));
		int64_t softwareSeekFastStartedAt __attribute__((aligned(8)));

		struct {
			uint8_t * data;
			int width;
			int height;
			int size;
			int dataSize;
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
		int seekFirstVideoPacketPending;
		int seekDiscardBeforeTarget;
		int64_t seekTargetPosition;

		struct {
			int audioWorkFrame;
			int videoWorkFrame;
			int drawWorkFrame;
		} skip;
	} sync;
};

static int getSkipFlag(int * flag) {
	return __atomic_load_n(flag, __ATOMIC_ACQUIRE);
}

static void setSkipFlag(int * flag, int value) {
	__atomic_store_n(flag, value, __ATOMIC_RELEASE);
}

static int hasPendingSurface(Player * player) {
	return __atomic_load_n(&player->video.surfaceRequestPending, __ATOMIC_ACQUIRE);
}

static void applyPendingSurface(Player * player, JNIEnv * env);

static const char * getDiagnosticsAudioStageName(int stage) {
	switch (stage) {
		case DIAGNOSTICS_AUDIO_STAGE_WAIT_FRAME_MUTEX: return "wait_frame_mutex";
		case DIAGNOSTICS_AUDIO_STAGE_DECODE_FRAME: return "decode_frame";
		case DIAGNOSTICS_AUDIO_STAGE_WAIT_SLEEP_BUFFER_MUTEX: return "wait_sleep_buffer_mutex";
		case DIAGNOSTICS_AUDIO_STAGE_QUEUE_BUFFER: return "queue_buffer";
		default: return "idle";
	}
}

static const char * getDiagnosticsMediaCodecStageName(int stage) {
	switch (stage) {
		case DIAGNOSTICS_MEDIACODEC_STAGE_WAIT_FRAME_MUTEX: return "wait_frame_mutex";
		case DIAGNOSTICS_MEDIACODEC_STAGE_SEND_PACKET: return "send_packet";
		case DIAGNOSTICS_MEDIACODEC_STAGE_RECEIVE_FRAME: return "receive_frame";
		case DIAGNOSTICS_MEDIACODEC_STAGE_WAIT_SLEEP_DRAW_MUTEX: return "wait_sleep_draw_mutex";
		case DIAGNOSTICS_MEDIACODEC_STAGE_RENDER_FRAME: return "render_frame";
		case DIAGNOSTICS_MEDIACODEC_STAGE_WAIT_RENDER_TIME: return "wait_render_time";
		case DIAGNOSTICS_MEDIACODEC_STAGE_SCHEDULE_BUFFER: return "schedule_buffer";
		case DIAGNOSTICS_MEDIACODEC_STAGE_RELEASE_BUFFER: return "release_buffer";
		case DIAGNOSTICS_MEDIACODEC_STAGE_FINISH_SEEK: return "finish_seek";
		case DIAGNOSTICS_MEDIACODEC_STAGE_RECORD_OUTPUT: return "record_output";
		case DIAGNOSTICS_MEDIACODEC_STAGE_UNREF_FRAME: return "unref_frame";
		case DIAGNOSTICS_MEDIACODEC_STAGE_UNLOCK_FRAME_MUTEX: return "unlock_frame_mutex";
		default: return "idle";
	}
}

static void setDiagnosticsAudioStage(Player * player, int stage) {
	__atomic_store_n(&player->decode.audio.diagnosticsStage, stage, __ATOMIC_RELAXED);
}

static void setDiagnosticsMediaCodecStage(Player * player, int stage, int64_t framePosition) {
	if (framePosition >= 0) {
		__atomic_store_n(&player->decode.video.diagnosticsFramePosition,
				framePosition, __ATOMIC_RELAXED);
	}
	__atomic_store_n(&player->decode.video.diagnosticsStage, stage, __ATOMIC_RELEASE);
}

static void diagnosticsLogSeekLock(Player * player, const char * phase,
		const char * lock, const char * state) {
	int audioStage = __atomic_load_n(&player->decode.audio.diagnosticsStage, __ATOMIC_RELAXED);
	int videoStage = __atomic_load_n(&player->decode.video.diagnosticsStage, __ATOMIC_ACQUIRE);
	uint64_t frameSerial = __atomic_load_n(&player->decode.video.diagnosticsFrameSerial,
			__ATOMIC_RELAXED);
	int64_t framePosition = __atomic_load_n(&player->decode.video.diagnosticsFramePosition,
			__ATOMIC_RELAXED);
	diagnosticsLog("player=%u seek_lock phase=%s lock=%s state=%s"
			" audio_stage=%s mediacodec_stage=%s frame_serial=%" PRIu64
			" frame_position_ms=%" PRId64,
			player->meta.diagnosticsId, phase, lock, state,
			getDiagnosticsAudioStageName(audioStage),
			getDiagnosticsMediaCodecStageName(videoStage), frameSerial, framePosition);
}

static void requestSeekWorkersStop(Player * player) {
	// The caller holds packets.flowMutex. Once the current decode iteration observes
	// these flags, it cannot start another packet before the seek has reset decoder
	// state. Publish the flags before waiting for either frame mutex so a renderer
	// sleeping on a future MediaCodec presentation time can release video.frameMutex.
	setSkipFlag(&player->sync.skip.audioWorkFrame, 1);
	setSkipFlag(&player->sync.skip.videoWorkFrame, 1);
	setSkipFlag(&player->sync.skip.drawWorkFrame, 1);
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

struct Bridge {
	JNIEnv * env;
	jmethodID methodOnSeek;
	jmethodID methodOnMessage;
	jmethodID methodOnDurationChanged;
	jmethodID methodOnSurfaceApplied;
};

struct PacketHolder {
	AVPacket * packet;
	int type;
};

struct AudioBuffer {
	uint8_t * buffer;
	int size;
	int index;
	int pendingChunks;
	int frameSize;
	int64_t position;
	int64_t divider;
};

struct VideoFrameExtra {
	int width;
	int height;
	int64_t position;
	int forcePresent;
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

static void diagnosticsRecordAudioChunk(Player * player, int size, int depth) {
	if (!diagnosticsActive()) {
		return;
	}
	pthread_mutex_lock(&diagnostics.mutex);
	if (diagnosticsActive()) {
		uint64_t count = ++diagnostics.stats.audioChunksSubmitted;
		if (count <= 8) {
			diagnosticsAppendLineLocked(DIAGNOSTICS_BUFFER_SIZE - DIAGNOSTICS_SUMMARY_RESERVE,
					"player=%u audio_output chunk_submitted count=%" PRIu64
					" bytes=%d depth=%d",
					player->meta.diagnosticsId, count, size, depth);
		}
	}
	pthread_mutex_unlock(&diagnostics.mutex);
}

static void diagnosticsRecordAudioUnderrun(Player * player) {
	if (!diagnosticsActive()) {
		return;
	}
	pthread_mutex_lock(&diagnostics.mutex);
	if (diagnosticsActive()) {
		uint64_t count = ++diagnostics.stats.audioOutputUnderruns;
		if (count <= 12 || count % 30 == 0) {
			diagnosticsAppendLineLocked(DIAGNOSTICS_BUFFER_SIZE - DIAGNOSTICS_SUMMARY_RESERVE,
					"player=%u audio_output underrun count=%" PRIu64,
					player->meta.diagnosticsId, count);
		}
	}
	pthread_mutex_unlock(&diagnostics.mutex);
}

static void diagnosticsRecordAudioMasterResumed(Player * player, int64_t position) {
	if (!diagnosticsActive()) {
		return;
	}
	pthread_mutex_lock(&diagnostics.mutex);
	if (diagnosticsActive()) {
		diagnostics.stats.audioMasterResumed++;
		diagnosticsAppendLineLocked(DIAGNOSTICS_BUFFER_SIZE - DIAGNOSTICS_SUMMARY_RESERVE,
				"player=%u audio_master resumed position_ms=%" PRId64,
				player->meta.diagnosticsId, position);
	}
	pthread_mutex_unlock(&diagnostics.mutex);
}

static void diagnosticsRecordSoftwareDrop(Player * player, int decodeStage,
		int64_t framePosition, int64_t playbackPosition, int64_t lateness) {
	if (!diagnosticsActive()) {
		return;
	}
	pthread_mutex_lock(&diagnostics.mutex);
	if (diagnosticsActive()) {
		uint64_t * counter = decodeStage ? &diagnostics.stats.softwareDroppedDecodeLate
				: &diagnostics.stats.softwareDroppedDrawLate;
		uint64_t count = ++*counter;
		if (count <= 12 || count % 60 == 0) {
			diagnosticsAppendLineLocked(DIAGNOSTICS_BUFFER_SIZE - DIAGNOSTICS_SUMMARY_RESERVE,
					"player=%u software_frame_drop stage=%s count=%" PRIu64
					" position_ms=%" PRId64 " clock_ms=%" PRId64 " late_ms=%" PRId64,
					player->meta.diagnosticsId, decodeStage ? "decode" : "draw", count,
					framePosition, playbackPosition, lateness);
		}
	}
	pthread_mutex_unlock(&diagnostics.mutex);
}

static void diagnosticsRecordSoftwareLateAnchor(Player * player, int rendered,
		int64_t framePosition, int64_t playbackPosition, int64_t lateness) {
	if (!diagnosticsActive()) {
		return;
	}
	pthread_mutex_lock(&diagnostics.mutex);
	if (diagnosticsActive()) {
		uint64_t * counter = rendered ? &diagnostics.stats.softwareLateAnchorsRendered
				: &diagnostics.stats.softwareLateAnchorsQueued;
		uint64_t count = ++*counter;
		if (count <= 12 || count % 30 == 0) {
			diagnosticsAppendLineLocked(DIAGNOSTICS_BUFFER_SIZE - DIAGNOSTICS_SUMMARY_RESERVE,
					"player=%u software_late_anchor stage=%s count=%" PRIu64
					" position_ms=%" PRId64 " clock_ms=%" PRId64 " late_ms=%" PRId64,
					player->meta.diagnosticsId, rendered ? "render" : "queue", count,
					framePosition, playbackPosition, lateness);
		}
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
static int fallbackMediaCodecToSoftware(Player * player);
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
		bridge->methodOnDurationChanged = (*env)->GetMethodID(env, class,
				"onDurationChanged", "(J)V");
		bridge->methodOnSurfaceApplied = (*env)->GetMethodID(env, class,
				"onSurfaceApplied", "(JJZ)V");
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
		diagnosticsIncrement(&diagnostics.stats.softwareDecoderDiscardEnabled);
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
		diagnosticsIncrement(&diagnostics.stats.softwareDecoderDiscardRestored);
		diagnosticsLog("player=%u software_governor decoder_discard=default"
				" reason=%s late_ms=%" PRId64 " active_ms=%" PRId64
				" seek_fast=%d", player->meta.diagnosticsId, reason, lateness,
				activeTime, player->video.softwareSeekFastActive);
	}
}

// Callers hold decode.video.frameMutex.
static void resetSoftwareDecoderGovernorLocked(Player * player, AVCodecContext * context,
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

static void restoreSoftwareSeekFastDecodeLocked(Player * player, AVCodecContext * context,
		const char * reason, int64_t packetPosition) {
	if (!player->video.softwareSeekFastActive) {
		context->skip_frame = getSoftwareFrameDiscardBaseline(player);
		return;
	}
	int64_t elapsed = getTime() - player->video.softwareSeekFastStartedAt;
	context->skip_frame = getSoftwareFrameDiscardBaseline(player);
	player->video.softwareSeekFastActive = 0;
	diagnosticsIncrement(&diagnostics.stats.softwareSeekFastRestored);
	diagnosticsLog("player=%u software_seek_fast restored reason=%s packet_ms=%" PRId64
			" target_ms=%" PRId64 " elapsed_ms=%" PRId64 " packets=%d frames=%d baseline=%s",
			player->meta.diagnosticsId, reason, packetPosition,
			player->video.softwareSeekFastTargetPosition, elapsed,
			player->video.softwareSeekFastPackets, player->video.softwareSeekFastFrames,
			player->video.softwareDecoderDiscardActive ? "nonref" : "default");
}

static void startSoftwareSeekFastDecodeLocked(Player * player, AVCodecContext * context,
		int64_t keyframePosition, int64_t targetPosition) {
	restoreSoftwareSeekFastDecodeLocked(player, context, "new_seek", keyframePosition);
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
	diagnosticsIncrement(&diagnostics.stats.softwareSeekFastStarted);
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
		restoreSoftwareSeekFastDecodeLocked(player, context, "end_of_stream", -1);
		return;
	}
	player->video.softwareSeekFastPackets++;
	int64_t timestamp = packet->dts != AV_NOPTS_VALUE ? packet->dts : packet->pts;
	int64_t packetPosition = getTimestampPositionMs(player, timestamp, stream->time_base);
	if (packetPosition >= 0 && packetPosition >= player->video.softwareSeekFastRestorePosition) {
		restoreSoftwareSeekFastDecodeLocked(player, context, "restore_margin", packetPosition);
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

typedef struct {
	Player * player;
	int64_t offset;
	int64_t total;
} DurationProbeIO;

static int64_t getFormatDurationMs(AVFormatContext * formatContext) {
	return formatContext->duration != AV_NOPTS_VALUE && formatContext->duration > 0
			? formatContext->duration / 1000 : 0;
}

static int64_t getStreamDurationMs(AVStream * stream) {
	if (!stream || stream->duration == AV_NOPTS_VALUE || stream->duration <= 0) {
		return 0;
	}
	AVRational msTimeBase = {1, 1000};
	return av_rescale_q(stream->duration, stream->time_base, msTimeBase);
}

static int durationProbeReadData(void * opaque, uint8_t * buffer, int bufferSize) {
	DurationProbeIO * io = opaque;
	if (io->player->meta.interrupt || io->offset >= io->total) {
		return AVERROR_EOF;
	}
	int64_t remaining = io->total - io->offset;
	int count = remaining < bufferSize ? (int) remaining : bufferSize;
	ssize_t result = pread(io->player->file.fd, buffer, count, io->offset);
	if (result <= 0) {
		return AVERROR_EOF;
	}
	io->offset += result;
	return (int) result;
}

static int64_t durationProbeSeekData(void * opaque, int64_t offset, int whence) {
	DurationProbeIO * io = opaque;
	if (whence == AVSEEK_SIZE) {
		return io->total;
	}
	int origin = whence & ~AVSEEK_FORCE;
	int64_t target;
	switch (origin) {
		case SEEK_SET: target = offset; break;
		case SEEK_CUR: target = io->offset + offset; break;
		case SEEK_END: target = io->total + offset; break;
		default: return -1;
	}
	if (target < 0 || target > io->total) {
		return -1;
	}
	io->offset = target;
	return target;
}

static int durationProbeInterrupt(void * opaque) {
	DurationProbeIO * io = opaque;
	return io->player->meta.interrupt;
}

static int64_t getPacketEndMs(AVFormatContext * formatContext, AVPacket * packet) {
	if (packet->stream_index < 0 || packet->stream_index >= (int) formatContext->nb_streams) {
		return -1;
	}
	AVStream * stream = formatContext->streams[packet->stream_index];
	int codecType = stream->codecpar->codec_type;
	if ((codecType != AVMEDIA_TYPE_AUDIO && codecType != AVMEDIA_TYPE_VIDEO) ||
			(stream->disposition & AV_DISPOSITION_ATTACHED_PIC)) {
		return -1;
	}
	int64_t timestamp = packet->pts;
	if (timestamp == AV_NOPTS_VALUE) {
		timestamp = packet->dts;
	}
	if (timestamp == AV_NOPTS_VALUE) {
		return -1;
	}
	if (packet->duration > 0 && timestamp <= INT64_MAX - packet->duration) {
		timestamp += packet->duration;
	}
	AVRational msTimeBase = {1, 1000};
	int64_t end = av_rescale_q(timestamp, stream->time_base, msTimeBase);
	if (formatContext->start_time != AV_NOPTS_VALUE) {
		end -= av_rescale_q(formatContext->start_time, AV_TIME_BASE_Q, msTimeBase);
	}
	return max64(end, 0);
}

static void * performDurationProbe(void * data) {
	Player * player = data;
	int64_t total;
	pthread_mutex_lock(&player->file.controlMutex);
	total = player->file.total;
	pthread_mutex_unlock(&player->file.controlMutex);
	// pread keeps this demuxer's byte position independent from the playback
	// demuxer, so duration verification cannot disturb decoding or seeking.
	DurationProbeIO probeIO = {player, 0, total};
	AVIOContext * ioContext = NULL;
	AVFormatContext * formatContext = NULL;
	AVPacket * packet = NULL;
	int64_t observedDuration = 0;
	int readResult = -1;
	int packets = 0;
	int contextBufferSize = 32 * 1024;
	uint8_t * contextBuffer = av_malloc(contextBufferSize);
	if (!contextBuffer) {
		goto FINISH;
	}
	ioContext = avio_alloc_context(contextBuffer, contextBufferSize, 0, &probeIO,
			&durationProbeReadData, NULL, &durationProbeSeekData);
	if (!ioContext) {
		av_free(contextBuffer);
		goto FINISH;
	}
	formatContext = avformat_alloc_context();
	if (!formatContext) {
		goto FINISH;
	}
	formatContext->pb = ioContext;
	formatContext->interrupt_callback.callback = &durationProbeInterrupt;
	formatContext->interrupt_callback.opaque = &probeIO;
	if (avformat_open_input(&formatContext, "", NULL, NULL) < 0 ||
			avformat_find_stream_info(formatContext, NULL) < 0) {
		goto FINISH;
	}
	packet = av_packet_alloc();
	if (!packet) {
		goto FINISH;
	}
	while (!player->meta.interrupt && (readResult = av_read_frame(formatContext, packet)) >= 0) {
		int64_t packetEnd = getPacketEndMs(formatContext, packet);
		if (packetEnd > observedDuration) {
			observedDuration = packetEnd;
		}
		packets++;
		av_packet_unref(packet);
	}

	FINISH:
	if (packet) {
		av_packet_free(&packet);
	}
	if (formatContext) {
		avformat_close_input(&formatContext);
	}
	if (ioContext) {
		av_free(ioContext->buffer);
		av_free(ioContext);
	}
	int reachedPhysicalEnd = probeIO.offset >= probeIO.total;
	int64_t declaredDuration = __atomic_load_n(&player->av.duration.declaredMs, __ATOMIC_ACQUIRE);
	int64_t effectiveDuration = __atomic_load_n(&player->av.duration.effectiveMs, __ATOMIC_ACQUIRE);
	int64_t difference = declaredDuration >= observedDuration
			? declaredDuration - observedDuration : observedDuration - declaredDuration;
	int changed = !player->meta.interrupt && reachedPhysicalEnd && observedDuration > 0 &&
			(declaredDuration <= 0 || difference > DURATION_PROBE_TOLERANCE_MS) &&
			effectiveDuration != observedDuration;
	if (changed) {
		__atomic_store_n(&player->av.duration.effectiveMs, observedDuration, __ATOMIC_RELEASE);
	}
	diagnosticsLog("player=%u duration_probe declared_ms=%" PRId64
			" observed_ms=%" PRId64 " effective_ms=%" PRId64
			" packets=%d read_result=%d physical_end=%d changed=%d",
			player->meta.diagnosticsId, declaredDuration, observedDuration,
			changed ? observedDuration : effectiveDuration, packets, readResult,
			reachedPhysicalEnd, changed);
	if (changed) {
		JNIEnv * env;
		if ((*loadJavaVM)->AttachCurrentThread(loadJavaVM, &env, NULL) == JNI_OK) {
			Bridge * bridge = obtainBridge(player, env);
			(*env)->CallVoidMethod(env, player->bridge.native,
					bridge->methodOnDurationChanged, (jlong) observedDuration);
			(*loadJavaVM)->DetachCurrentThread(loadJavaVM);
		}
	}
	return NULL;
}

static void maybeStartDurationProbeLocked(Player * player) {
	if (!player->av.duration.initialized || !player->av.duration.probeRequired ||
			player->av.duration.probeThreadStarted || player->meta.interrupt ||
			player->file.total <= 0 || player->file.start > 0 ||
			player->file.end < player->file.total) {
		return;
	}
	if (pthread_create(&player->av.duration.probeThread, NULL, &performDurationProbe, player) == 0) {
		player->av.duration.probeThreadStarted = 1;
		diagnosticsLog("player=%u duration_probe_started total=%ld",
				player->meta.diagnosticsId, player->file.total);
	} else {
		diagnosticsLog("player=%u duration_probe_start_failed", player->meta.diagnosticsId);
	}
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

// Callers hold audio.sleepBufferMutex. OpenSL keeps submitted PCM memory by
// reference until the matching callback, so every distinct owner must remain
// alive while its chunks are present in outputChunks.
static void clearAudioOutputLocked(Player * player, int clearDecodedBuffers) {
	if (player->audio.sl.queue) {
		(*player->audio.sl.queue)->Clear(player->audio.sl.queue);
	}
	if (clearDecodedBuffers) {
		blockingQueueClear(&player->audio.bufferQueue, audioBufferQueueFreeCallback);
	}
	AudioBuffer * buffers[AUDIO_OUTPUT_QUEUE_CAPACITY + 1];
	int bufferCount = 0;
	if (player->audio.buffer) {
		buffers[bufferCount++] = player->audio.buffer;
	}
	for (int i = 0; i < player->audio.outputChunkCount; i++) {
		int index = (player->audio.outputChunkHead + i) % AUDIO_OUTPUT_QUEUE_CAPACITY;
		AudioBuffer * buffer = player->audio.outputChunks[index].buffer;
		int duplicate = 0;
		for (int j = 0; j < bufferCount; j++) {
			if (buffers[j] == buffer) {
				duplicate = 1;
				break;
			}
		}
		if (buffer && !duplicate) {
			buffers[bufferCount++] = buffer;
		}
	}
	memset(player->audio.outputChunks, 0, sizeof(player->audio.outputChunks));
	player->audio.buffer = NULL;
	player->audio.outputChunkHead = 0;
	player->audio.outputChunkCount = 0;
	player->audio.bufferNeedEnqueueAfterDecode = 1;
	for (int i = 0; i < bufferCount; i++) {
		audioBufferQueueFreeCallback(buffers[i]);
	}
}

static int16_t limitBoostedAudioSample(float sample) {
	const float maximum = 32767.f;
	const float knee = maximum * 0.85f;
	float magnitude = fabsf(sample);
	if (magnitude <= knee) {
		return (int16_t) lroundf(sample);
	}
	float limited = knee + (maximum - knee) *
			(1.f - expf(-(magnitude - knee) / (maximum - knee)));
	if (limited > maximum) {
		limited = maximum;
	}
	return sample < 0.f ? (int16_t) -lroundf(limited) : (int16_t) lroundf(limited);
}

static void applyAudioBoost(Player * player, uint8_t * buffer, int size) {
	if (player->audio.boostDb <= 0 || !buffer || size < (int) sizeof(int16_t)) {
		return;
	}
	if (player->audio.localVolume <= 0) {
		memset(buffer, 0, size);
		return;
	}
	float gain = player->audio.localVolume / 100.f * powf(10.f, player->audio.boostDb / 20.f);
	int16_t * samples = (int16_t *) buffer;
	int count = size / (int) sizeof(int16_t);
	if (gain <= 1.f) {
		for (int i = 0; i < count; i++) {
			samples[i] = (int16_t) lroundf(samples[i] * gain);
		}
	} else {
		for (int i = 0; i < count; i++) {
			samples[i] = limitBoostedAudioSample(samples[i] * gain);
		}
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
		if (!player->audio.buffer && player->audio.outputChunkCount == 0 &&
				blockingQueueCount(&player->audio.bufferQueue) == 0
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

static int getAudioChunkSize(AudioBuffer * audioBuffer) {
	int remaining = audioBuffer->size - audioBuffer->index;
	int64_t target = audioBuffer->divider * AUDIO_TARGET_CHUNK_MS / 1000;
	target = max64(target, AUDIO_MIN_ENQUEUE_SIZE);
	target = min64(target, INT_MAX);
	int frameSize = audioBuffer->frameSize > 0 ? audioBuffer->frameSize : 1;
	target -= target % frameSize;
	target = max64(target, frameSize);
	return min32(remaining, (int) target);
}

static void updateAudioPositionForChunkLocked(Player * player, int index) {
	AudioBuffer * audioBuffer = player->audio.outputChunks[index].buffer;
	if (audioBuffer && audioBuffer->position >= 0 && audioBuffer->divider > 0) {
		player->sync.audioPosition = audioBuffer->position +
				player->audio.outputChunks[index].offset * 1000 / audioBuffer->divider;
		player->sync.audioPositionNotSync = 0;
		LOG("play audio %" PRId64, player->sync.audioPosition);
	}
}

// Remove the chunk that OpenSL has just finished. Returns its end position so
// the surrogate clock can continue at EOF if no replacement data is available.
static int64_t completeAudioChunkLocked(Player * player) {
	if (player->audio.outputChunkCount <= 0) {
		return -1;
	}
	int index = player->audio.outputChunkHead;
	AudioBuffer * audioBuffer = player->audio.outputChunks[index].buffer;
	int offset = player->audio.outputChunks[index].offset;
	int size = player->audio.outputChunks[index].size;
	player->audio.outputChunks[index].buffer = NULL;
	player->audio.outputChunks[index].offset = 0;
	player->audio.outputChunks[index].size = 0;
	player->audio.outputChunkHead = (index + 1) % AUDIO_OUTPUT_QUEUE_CAPACITY;
	player->audio.outputChunkCount--;
	int64_t endPosition = -1;
	if (audioBuffer) {
		audioBuffer->pendingChunks--;
		if (audioBuffer->position >= 0 && audioBuffer->divider > 0) {
			endPosition = audioBuffer->position + (offset + size) * 1000 / audioBuffer->divider;
		}
		if (audioBuffer->index >= audioBuffer->size && audioBuffer->pendingChunks <= 0 &&
				player->audio.buffer != audioBuffer) {
			audioBufferQueueFreeCallback(audioBuffer);
		}
	}
	if (player->audio.outputChunkCount > 0) {
		updateAudioPositionForChunkLocked(player, player->audio.outputChunkHead);
	}
	return endPosition;
}

// Fill both OpenSL slots whenever possible. Decoded buffers and every submitted
// PCM owner stay alive until their completion callbacks, avoiding the one tiny
// 256-byte buffer cadence that could repeatedly starve AudioTrack under load.
static int enqueueAudioBuffer(Player * player) {
	if (!player->play.playing || !player->audio.sl.queue) {
		player->audio.bufferNeedEnqueueAfterDecode = 1;
		return 0;
	}
	int enqueued = 0;
	while (player->audio.outputChunkCount < AUDIO_OUTPUT_QUEUE_CAPACITY) {
		if (!player->audio.buffer) {
			player->audio.buffer = blockingQueueGet(&player->audio.bufferQueue, 0);
		}
		AudioBuffer * audioBuffer = player->audio.buffer;
		if (!audioBuffer) {
			break;
		}
		if (audioBuffer->index >= audioBuffer->size) {
			player->audio.buffer = NULL;
			if (audioBuffer->pendingChunks <= 0) {
				audioBufferQueueFreeCallback(audioBuffer);
			}
			continue;
		}
		int offset = audioBuffer->index;
		int enqueueSize = getAudioChunkSize(audioBuffer);
		if (enqueueSize <= 0) {
			break;
		}
		SLresult result = (*player->audio.sl.queue)->Enqueue(player->audio.sl.queue,
				audioBuffer->buffer + offset, enqueueSize);
		if (result != SL_RESULT_SUCCESS) {
			diagnosticsLog("player=%u audio_output enqueue_failed result=%d bytes=%d depth=%d",
					player->meta.diagnosticsId, (int) result, enqueueSize,
					player->audio.outputChunkCount);
			break;
		}
		int index = (player->audio.outputChunkHead + player->audio.outputChunkCount) %
				AUDIO_OUTPUT_QUEUE_CAPACITY;
		player->audio.outputChunks[index].buffer = audioBuffer;
		player->audio.outputChunks[index].offset = offset;
		player->audio.outputChunks[index].size = enqueueSize;
		if (player->audio.outputChunkCount == 0) {
			updateAudioPositionForChunkLocked(player, index);
		}
		player->audio.outputChunkCount++;
		audioBuffer->pendingChunks++;
		audioBuffer->index += enqueueSize;
		if (audioBuffer->index >= audioBuffer->size) {
			player->audio.buffer = NULL;
		}
		enqueued++;
		diagnosticsRecordAudioChunk(player, enqueueSize, player->audio.outputChunkCount);
	}
	player->audio.bufferNeedEnqueueAfterDecode =
			player->audio.outputChunkCount < AUDIO_OUTPUT_QUEUE_CAPACITY;
	return enqueued;
}

static void audioPlayerCallback(UNUSED SLAndroidSimpleBufferQueueItf slQueue, void * context) {
	Player * player = (Player *) context;
	if (player->meta.interrupt) {
		return;
	}
	LOG("audio callback");
	pthread_mutex_lock(&player->audio.sleepBufferMutex);
	if (getSkipFlag(&player->sync.skip.audioWorkFrame)) {
		pthread_mutex_unlock(&player->audio.sleepBufferMutex);
		return;
	}
	int64_t endAudioPosition = completeAudioChunkLocked(player);
	int result = enqueueAudioBuffer(player);
	if (result > 0) {
		pthread_cond_broadcast(&player->audio.bufferCond);
	}
	int reachedEnd = player->decode.packets.finished && !player->audio.buffer &&
			blockingQueueCount(&player->audio.bufferQueue) == 0 &&
			blockingQueueCount(&player->audio.packetQueue) == 0;
	if (player->audio.outputChunkCount == 0 &&
			blockingQueueCount(&player->audio.packetQueue) == 0 && endAudioPosition >= 0) {
		updateAudioPositionSurrogate(player, endAudioPosition, 1);
	}
	if (player->audio.outputChunkCount == 0 && player->play.playing &&
			!player->sync.videoPositionNotSync && !player->audio.finished && !reachedEnd) {
		diagnosticsRecordAudioUnderrun(player);
	}
	pthread_mutex_unlock(&player->audio.sleepBufferMutex);
	markStreamFinished(player, 0);
}

static int queueDecodedAudio(Player * player, uint8_t * buffer, int size,
		int frameSize, int64_t position, int64_t divider, int * silentAudioLength) {
	if (!buffer || size <= 0 || divider <= 0) {
		return 0;
	}
	setDiagnosticsAudioStage(player, DIAGNOSTICS_AUDIO_STAGE_WAIT_SLEEP_BUFFER_MUTEX);
	pthread_mutex_lock(&player->audio.sleepBufferMutex);
	setDiagnosticsAudioStage(player, DIAGNOSTICS_AUDIO_STAGE_QUEUE_BUFFER);
	if (player->meta.interrupt || getSkipFlag(&player->sync.skip.audioWorkFrame)) {
		pthread_mutex_unlock(&player->audio.sleepBufferMutex);
		setDiagnosticsAudioStage(player, DIAGNOSTICS_AUDIO_STAGE_IDLE);
		return 0;
	}
	while (!player->meta.interrupt && !getSkipFlag(&player->sync.skip.audioWorkFrame) &&
			blockingQueueCount(&player->audio.bufferQueue) >= 5) {
		pthread_cond_wait(&player->audio.bufferCond, &player->audio.sleepBufferMutex);
	}
	if (player->meta.interrupt || getSkipFlag(&player->sync.skip.audioWorkFrame)) {
		pthread_mutex_unlock(&player->audio.sleepBufferMutex);
		setDiagnosticsAudioStage(player, DIAGNOSTICS_AUDIO_STAGE_IDLE);
		return 0;
	}
	int waitedForVideo = player->sync.videoPositionNotSync;
	while (!player->meta.interrupt && !getSkipFlag(&player->sync.skip.audioWorkFrame)
			&& player->sync.videoPositionNotSync) {
		pthread_cond_wait(&player->audio.sleepCond, &player->audio.sleepBufferMutex);
	}
	if (player->meta.interrupt || getSkipFlag(&player->sync.skip.audioWorkFrame)) {
		pthread_mutex_unlock(&player->audio.sleepBufferMutex);
		setDiagnosticsAudioStage(player, DIAGNOSTICS_AUDIO_STAGE_IDLE);
		return 0;
	}
	if (waitedForVideo) {
		// The first post-seek video frame is the only synchronization gate. Once
		// it is visible, audio becomes the master clock and must not be paused to
		// match a software decoder that cannot sustain the source frame rate.
		diagnosticsRecordAudioMasterResumed(player, position);
	}
	AudioBuffer * audioBuffer = malloc(sizeof(AudioBuffer));
	if (!audioBuffer) {
		pthread_mutex_unlock(&player->audio.sleepBufferMutex);
		setDiagnosticsAudioStage(player, DIAGNOSTICS_AUDIO_STAGE_IDLE);
		return 0;
	}
	audioBuffer->buffer = buffer;
	audioBuffer->index = 0;
	audioBuffer->size = size;
	audioBuffer->pendingChunks = 0;
	audioBuffer->frameSize = frameSize;
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
	applyAudioBoost(player, audioBuffer->buffer, audioBuffer->size);
	int needEnqueue = player->audio.bufferNeedEnqueueAfterDecode;
	blockingQueueAdd(&player->audio.bufferQueue, audioBuffer);
	if (needEnqueue) {
		enqueueAudioBuffer(player);
	}
	pthread_mutex_unlock(&player->audio.sleepBufferMutex);
	setDiagnosticsAudioStage(player, DIAGNOSTICS_AUDIO_STAGE_IDLE);
	return 1;
}

#ifdef DASHCHAN_HAS_ATEMPO
static int drainTempoProcessor(Player * player, TempoProcessor * processor,
		int sampleRate, int channels, int speed, int64_t startPosition,
		int64_t * outputSamples, int * silentAudioLength) {
	while (!player->meta.interrupt && !getSkipFlag(&player->sync.skip.audioWorkFrame)) {
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
		int frameSize = av_get_bytes_per_sample(AV_SAMPLE_FMT_S16) * channels;
		if (!queueDecodedAudio(player, buffer, size, frameSize,
				position, divider, silentAudioLength)) {
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
		if (getSkipFlag(&player->sync.skip.audioWorkFrame)) {
			setSkipFlag(&player->sync.skip.audioWorkFrame, 0);
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
			if (getSkipFlag(&player->sync.skip.audioWorkFrame)) {
				goto SKIP_AUDIO_FRAME;
			}
			setDiagnosticsAudioStage(player, DIAGNOSTICS_AUDIO_STAGE_WAIT_FRAME_MUTEX);
			pthread_mutex_lock(&player->decode.audio.frameMutex);
			if (getSkipFlag(&player->sync.skip.audioWorkFrame)) {
				setDiagnosticsAudioStage(player, DIAGNOSTICS_AUDIO_STAGE_IDLE);
				UNLOCK_AND_GOTO(&player->decode.audio.frameMutex, SKIP_AUDIO_FRAME);
			}
			setDiagnosticsAudioStage(player, DIAGNOSTICS_AUDIO_STAGE_DECODE_FRAME);
			int ready = decodeFrame(context, packetHolder->packet, frame, &packetSent);
			pthread_mutex_unlock(&player->decode.audio.frameMutex);
			setDiagnosticsAudioStage(player, DIAGNOSTICS_AUDIO_STAGE_IDLE);
			if (!ready) {
				break;
			}

			if (ready) {
				int64_t position = getFramePositionMs(player, frame, stream);
				if (position >= 0 && player->sync.seekDiscardBeforeTarget &&
						player->sync.audioPositionNotSync &&
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
				if (swr_init(resampleContext) < 0
						|| getSkipFlag(&player->sync.skip.audioWorkFrame)) {
					goto SKIP_AUDIO_FRAME;
				}
				int dstSamples = av_rescale_rnd(srcSamples, dstSampleRate, srcSampleRate, AV_ROUND_UP);
				int result = av_samples_alloc_array_and_samples(&dstData, frame->linesize, dstChannels,
						dstSamples, dstFormat, 0);
				if (result < 0 || getSkipFlag(&player->sync.skip.audioWorkFrame)) {
					goto SKIP_AUDIO_FRAME;
				}
				dstSamples = av_rescale_rnd(swr_get_delay(resampleContext, srcSampleRate) + srcSamples,
						dstSampleRate, srcSampleRate, AV_ROUND_UP);
				result = swr_convert(resampleContext, dstData, dstSamples, (const uint8_t **) frame->data, srcSamples);
				if (result < 0 || getSkipFlag(&player->sync.skip.audioWorkFrame)) {
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
					int frameSize = av_get_bytes_per_sample(dstFormat) * dstChannels;
					if (queueDecodedAudio(player, dstData[0], size, frameSize,
							position, divider, &silentAudioLength)) {
						dstData[0] = NULL;
						success = 1;
					}
				}
#else
				int64_t divider = av_get_bytes_per_sample(dstFormat) * dstChannels * (int64_t) dstSampleRate;
				int frameSize = av_get_bytes_per_sample(dstFormat) * dstChannels;
				if (queueDecodedAudio(player, dstData[0], size, frameSize,
						position, divider, &silentAudioLength)) {
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

static void * performDraw(void * data) {
	Player * player = (Player *) data;
	JNIEnv * env;
	(*loadJavaVM)->AttachCurrentThread(loadJavaVM, &env, NULL);
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
		setSkipFlag(&player->sync.skip.drawWorkFrame, 0);
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
		if (getSkipFlag(&player->sync.skip.drawWorkFrame)) {
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
			while (!player->meta.interrupt && !getSkipFlag(&player->sync.skip.drawWorkFrame)) {
				if (condSleepUntilMs(&player->video.sleepCond, &player->video.sleepDrawMutex, time)) {
					break;
				}
			}
			position = calculatePosition(player, 1);
			waitTime = extra->position >= 0 ? extra->position - position : 0;
		}
		if (getSkipFlag(&player->sync.skip.drawWorkFrame)) {
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
			diagnosticsIncrement(&diagnostics.stats.softwareRenderedFrames);
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
			Bridge * bridge = obtainBridge(player, env);
			SEND_MESSAGE(env, player, bridge, BRIDGE_MESSAGE_END_SEEKING);
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
			markStreamFinished(player, 1);
		}
	}
	(*loadJavaVM)->DetachCurrentThread(loadJavaVM);
	return NULL;
}

static int getVideoBufferSize(int videoFormat, int width, int height) {
	if (width <= 0 || height <= 0) {
		return 0;
	}
	int64_t pixels = (int64_t) width * height;
	int64_t size;
	switch (videoFormat) {
		case AV_PIX_FMT_RGBA: size = pixels * 4; break;
		case AV_PIX_FMT_RGB565LE: size = pixels * 2; break;
		case AV_PIX_FMT_YUV420P: size = pixels * 3 / 2; break;
		default: return 0;
	}
	return size > 0 && size <= INT_MAX ? (int) size : 0;
}

static void calculateSoftwareOutputSize(Player * player, int sourceWidth, int sourceHeight,
		int * outputWidth, int * outputHeight) {
	if (sourceWidth <= 0 || sourceHeight <= 0) {
		*outputWidth = 1;
		*outputHeight = 1;
		return;
	}
	int surfaceWidth = __atomic_load_n(&player->video.surfaceWidth, __ATOMIC_ACQUIRE);
	int surfaceHeight = __atomic_load_n(&player->video.surfaceHeight, __ATOMIC_ACQUIRE);
	double scale = 1.0;
	if (surfaceWidth > 0 && surfaceHeight > 0) {
		double horizontalScale = (double) surfaceWidth / sourceWidth;
		double verticalScale = (double) surfaceHeight / sourceHeight;
		if (horizontalScale < scale) {
			scale = horizontalScale;
		}
		if (verticalScale < scale) {
			scale = verticalScale;
		}
	}
	int maxLongSide = player->video.softwareOutputLevel > 0
			? SOFTWARE_OUTPUT_FALLBACK_MAX_LONG_SIDE : SOFTWARE_OUTPUT_MAX_LONG_SIDE;
	int maxPixels = player->video.softwareOutputLevel > 0
			? SOFTWARE_OUTPUT_FALLBACK_MAX_PIXELS : SOFTWARE_OUTPUT_MAX_PIXELS;
	int sourceLongSide = sourceWidth > sourceHeight ? sourceWidth : sourceHeight;
	if (sourceLongSide * scale > maxLongSide) {
		scale = (double) maxLongSide / sourceLongSide;
	}
	double scaledPixels = (double) sourceWidth * sourceHeight * scale * scale;
	if (scaledPixels > maxPixels) {
		scale *= sqrt((double) maxPixels / scaledPixels);
	}
	int width = max64((int) floor(sourceWidth * scale), 1);
	int height = max64((int) floor(sourceHeight * scale), 1);
	// Even output dimensions keep YUV plane sizes and common Surface formats valid.
	if (width > 1) {
		width &= ~1;
	}
	if (height > 1) {
		height &= ~1;
	}
	*outputWidth = width;
	*outputHeight = height;
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
		setDiagnosticsMediaCodecStage(player, DIAGNOSTICS_MEDIACODEC_STAGE_SEND_PACKET, -1);
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
	setDiagnosticsMediaCodecStage(player, DIAGNOSTICS_MEDIACODEC_STAGE_RECEIVE_FRAME, -1);
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
	setDiagnosticsMediaCodecStage(player, DIAGNOSTICS_MEDIACODEC_STAGE_WAIT_SLEEP_DRAW_MUTEX,
			framePosition);
	if (frame->format != AV_PIX_FMT_MEDIACODEC || !buffer) {
		diagnosticsLog("player=%u mediacodec_output invalid_format=%d expected_format=%d"
				" output_buffer=%d",
				player->meta.diagnosticsId, frame->format, AV_PIX_FMT_MEDIACODEC,
				buffer != NULL);
		diagnosticsRecordOutput(player, frame, framePosition, 0,
				DIAGNOSTICS_OUTPUT_NO_BUFFER, 0);
		setDiagnosticsMediaCodecStage(player, DIAGNOSTICS_MEDIACODEC_STAGE_UNREF_FRAME,
				framePosition);
		av_frame_unref(frame);
		setDiagnosticsMediaCodecStage(player, DIAGNOSTICS_MEDIACODEC_STAGE_IDLE,
				framePosition);
		return -1;
	}
	int render = 1;
	int renderResult = 0;
	int outputAction = DIAGNOSTICS_OUTPUT_IMMEDIATE;
	int64_t waitTime = 0;
	int finishSeeking = 0;
	pthread_mutex_lock(&player->video.sleepDrawMutex);
	setDiagnosticsMediaCodecStage(player, DIAGNOSTICS_MEDIACODEC_STAGE_RENDER_FRAME,
			framePosition);
	if (player->meta.interrupt || getSkipFlag(&player->sync.skip.videoWorkFrame)) {
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
			setDiagnosticsMediaCodecStage(player, DIAGNOSTICS_MEDIACODEC_STAGE_SCHEDULE_BUFFER,
					framePosition);
			renderResult = av_mediacodec_render_buffer_at_time(buffer, renderTimeNs);
			outputAction = DIAGNOSTICS_OUTPUT_SCHEDULED;
			buffer = NULL;
			break;
		}
		int64_t wakeTime = getTime() + scaledWaitTime - MEDIACODEC_MAX_SCHEDULE_AHEAD_MS;
		setDiagnosticsMediaCodecStage(player, DIAGNOSTICS_MEDIACODEC_STAGE_WAIT_RENDER_TIME,
				framePosition);
		condSleepUntilMs(&player->video.sleepCond, &player->video.sleepDrawMutex, wakeTime);
		if (player->meta.interrupt || getSkipFlag(&player->sync.skip.videoWorkFrame)) {
			render = 0;
			outputAction = DIAGNOSTICS_OUTPUT_DROPPED_STATE;
			break;
		}
		position = calculatePosition(player, 1);
		waitTime = framePosition - position;
	}

	RELEASE_BUFFER:
	if (buffer) {
		setDiagnosticsMediaCodecStage(player, DIAGNOSTICS_MEDIACODEC_STAGE_RELEASE_BUFFER,
				framePosition);
		renderResult = av_mediacodec_release_buffer(buffer, render);
	}
	if (renderResult < 0) {
		LOGP("MediaCodec output buffer release failed: %d", renderResult);
	}
	if (finishSeeking && render && renderResult >= 0) {
		player->sync.videoPositionNotSync = 0;
		diagnosticsLog("player=%u seek_first_frame_rendered position_ms=%" PRId64 " hardware=1",
				player->meta.diagnosticsId, framePosition);
		Bridge * bridge = obtainBridge(player, env);
		SEND_MESSAGE(env, player, bridge, BRIDGE_MESSAGE_END_SEEKING);
		pthread_mutex_unlock(&player->video.sleepDrawMutex);
		setDiagnosticsMediaCodecStage(player, DIAGNOSTICS_MEDIACODEC_STAGE_FINISH_SEEK,
				framePosition);
		condBroadcastLocked(&player->audio.sleepCond, &player->audio.sleepBufferMutex);
		setDiagnosticsMediaCodecStage(player, DIAGNOSTICS_MEDIACODEC_STAGE_WAIT_SLEEP_DRAW_MUTEX,
				framePosition);
		pthread_mutex_lock(&player->video.sleepDrawMutex);
		setDiagnosticsMediaCodecStage(player, DIAGNOSTICS_MEDIACODEC_STAGE_RENDER_FRAME,
				framePosition);
	}
	setDiagnosticsMediaCodecStage(player, DIAGNOSTICS_MEDIACODEC_STAGE_RECORD_OUTPUT,
			framePosition);
	diagnosticsRecordOutput(player, frame, framePosition, waitTime, outputAction, renderResult);
	setDiagnosticsMediaCodecStage(player, DIAGNOSTICS_MEDIACODEC_STAGE_UNREF_FRAME,
			framePosition);
	av_frame_unref(frame);
	pthread_mutex_unlock(&player->video.sleepDrawMutex);
	setDiagnosticsMediaCodecStage(player, DIAGNOSTICS_MEDIACODEC_STAGE_UNLOCK_FRAME_MUTEX,
			framePosition);
	return 1;
}

static void performDecodeVideoMediaCodec(Player * player, JNIEnv * env, AVStream * stream) {
	AVFrame * frame = av_frame_alloc();
	PacketHolder * packetHolder = NULL;
	while (!player->meta.interrupt && player->video.hardwareDecoderActive) {
		if (hasPendingSurface(player)) {
			applyPendingSurface(player, env);
			continue;
		}
		packetHolder = (PacketHolder *) blockingQueueGet(&player->video.packetQueue, 1);
		if (packetHolder && packetHolder->type == PACKET_HOLDER_SURFACE_REQUEST) {
			packetQueueFreeCallback(packetHolder);
			packetHolder = NULL;
			applyPendingSurface(player, env);
			continue;
		}
		if (!player->video.hardwareDecoderActive) {
			if (packetHolder) {
				packetQueueFreeCallback(packetHolder);
				packetHolder = NULL;
			}
			break;
		}
		if (getSkipFlag(&player->sync.skip.videoWorkFrame)) {
			setSkipFlag(&player->sync.skip.videoWorkFrame, 0);
		}
		if (!packetHolder || player->meta.interrupt) {
			break;
		}
		condBroadcastLocked(&player->decode.packets.flowCond, &player->decode.packets.flowMutex);
		if (player->meta.interrupt) {
			break;
		}
		pthread_mutex_lock(&player->play.finishMutex);
		while (!player->meta.interrupt && !player->play.playing && !hasPendingSurface(player)) {
			pthread_cond_wait(&player->play.finishCond, &player->play.finishMutex);
		}
		pthread_mutex_unlock(&player->play.finishMutex);
		if (player->meta.interrupt) {
			break;
		}
		if (hasPendingSurface(player)) {
			packetQueueFreeCallback(packetHolder);
			packetHolder = NULL;
			applyPendingSurface(player, env);
			continue;
		}
		int packetSent = 0;
		while (!player->meta.interrupt && !getSkipFlag(&player->sync.skip.videoWorkFrame)) {
			setDiagnosticsMediaCodecStage(player, DIAGNOSTICS_MEDIACODEC_STAGE_WAIT_FRAME_MUTEX, -1);
			pthread_mutex_lock(&player->decode.video.frameMutex);
			if (player->meta.interrupt || getSkipFlag(&player->sync.skip.videoWorkFrame)) {
				pthread_mutex_unlock(&player->decode.video.frameMutex);
				setDiagnosticsMediaCodecStage(player, DIAGNOSTICS_MEDIACODEC_STAGE_IDLE, -1);
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
				fallbackMediaCodecToSoftware(player);
			}
			pthread_mutex_unlock(&player->decode.video.frameMutex);
			setDiagnosticsMediaCodecStage(player, DIAGNOSTICS_MEDIACODEC_STAGE_IDLE, -1);
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
	setDiagnosticsMediaCodecStage(player, DIAGNOSTICS_MEDIACODEC_STAGE_IDLE, -1);
	av_frame_free(&frame);
}
#endif

static void * performDecodeVideo(void * data) {
	Player * player = (Player *) data;
	JNIEnv * env;
	(*loadJavaVM)->AttachCurrentThread(loadJavaVM, &env, NULL);
	AVStream * stream = GET_STREAM(player, video);
	while (!player->meta.interrupt && !player->video.bufferQueue && !player->video.hardwareDecoderActive) {
		if (hasPendingSurface(player)) {
			applyPendingSurface(player, env);
			continue;
		}
		pthread_mutex_lock(&player->video.sleepDrawMutex);
		if (!player->meta.interrupt && !player->video.bufferQueue
				&& !player->video.hardwareDecoderActive && !hasPendingSurface(player)) {
			pthread_cond_wait(&player->video.sleepCond, &player->video.sleepDrawMutex);
		}
		pthread_mutex_unlock(&player->video.sleepDrawMutex);
	}
	if (player->meta.interrupt) {
		(*loadJavaVM)->DetachCurrentThread(loadJavaVM);
		return NULL;
	}
#ifdef DASHCHAN_HAS_MEDIACODEC
	if (player->video.hardwareDecoderActive) {
		performDecodeVideoMediaCodec(player, env, stream);
		if (player->meta.interrupt || player->video.hardwareDecoderActive) {
			(*loadJavaVM)->DetachCurrentThread(loadJavaVM);
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
		if (hasPendingSurface(player)) {
			applyPendingSurface(player, env);
			continue;
		}
		packetHolder = (PacketHolder *) blockingQueueGet(&player->video.packetQueue, 1);
		if (packetHolder && packetHolder->type == PACKET_HOLDER_SURFACE_REQUEST) {
			packetQueueFreeCallback(packetHolder);
			packetHolder = NULL;
			applyPendingSurface(player, env);
			continue;
		}
		if (getSkipFlag(&player->sync.skip.videoWorkFrame)) {
			setSkipFlag(&player->sync.skip.videoWorkFrame, 0);
		}
		if (!packetHolder || player->meta.interrupt) {
			break;
		}
		condBroadcastLocked(&player->decode.packets.flowCond, &player->decode.packets.flowMutex);
		if (player->meta.interrupt) {
			break;
		}

		pthread_mutex_lock(&player->play.finishMutex);
		while (!player->meta.interrupt && !player->play.playing && !hasPendingSurface(player)) {
			pthread_cond_wait(&player->play.finishCond, &player->play.finishMutex);
		}
		pthread_mutex_unlock(&player->play.finishMutex);
		if (player->meta.interrupt) {
			break;
		}
		if (hasPendingSurface(player)) {
			packetQueueFreeCallback(packetHolder);
			packetHolder = NULL;
			applyPendingSurface(player, env);
			continue;
		}

		int packetSent = 0;
		while (1) {
			int success = 0;
			VideoFrameExtra * extra = NULL;
			int64_t decodedFramePosition = -1;
			if (getSkipFlag(&player->sync.skip.videoWorkFrame)) {
				goto SKIP_VIDEO_FRAME;
			}
			pthread_mutex_lock(&player->decode.video.frameMutex);
			if (getSkipFlag(&player->sync.skip.videoWorkFrame)) {
				UNLOCK_AND_GOTO(&player->decode.video.frameMutex, SKIP_VIDEO_FRAME);
			}
			if (!packetSent) {
				updateSoftwareSeekFastDecodeForPacketLocked(player, context, stream,
						packetHolder->packet);
			}
			int ready = decodeFrame(context, packetHolder->packet, frame, &packetSent);
			if (ready) {
				decodedFramePosition = getFramePositionMs(player, frame, stream);
				if (player->video.softwareSeekFastActive) {
					player->video.softwareSeekFastFrames++;
					if (decodedFramePosition >= player->video.softwareSeekFastRestorePosition) {
						restoreSoftwareSeekFastDecodeLocked(player, context,
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
				diagnosticsIncrement(&diagnostics.stats.softwareDecodedFrames);
				LOG("video frame pts=%" PRId64 " best=%" PRId64 " pkt_dts=%" PRId64
						" pos=%" PRId64 " tb=%d/%d", frame->pts, frame->best_effort_timestamp,
						frame->pkt_dts, extra->position, stream->time_base.num, stream->time_base.den);
				if (extra->position >= 0 && player->sync.seekDiscardBeforeTarget &&
						player->sync.videoPositionNotSync &&
						extra->position < player->sync.videoPosition) {
					success = 1;
					goto SKIP_VIDEO_FRAME;
				}
				if (getSkipFlag(&player->sync.skip.videoWorkFrame)) {
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
						Bridge * bridge = obtainBridge(player, env);
						SEND_MESSAGE(env, player, bridge, BRIDGE_MESSAGE_SIZE_CHANGED);
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
					diagnosticsIncrement(&diagnostics.stats.softwareOutputDowngrades);
					diagnosticsLog("player=%u software_governor output_level=hd"
							" conversion_us=%" PRId64 " slow_frames=%d",
							player->meta.diagnosticsId, conversionTime,
							player->video.softwareSlowConversions);
				}

				pthread_mutex_lock(&player->video.queueMutex);
				if (getSkipFlag(&player->sync.skip.videoWorkFrame)) {
					UNLOCK_AND_GOTO(&player->video.queueMutex, SKIP_VIDEO_FRAME);
				}
				bufferQueueExtend(player->video.bufferQueue, outputBufferSize);
				BufferItem * bufferItem = NULL;
				while (!player->meta.interrupt && !getSkipFlag(&player->sync.skip.videoWorkFrame)
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
		markStreamFinished(player, 1);
		packetQueueFreeCallback(packetHolder);
		packetHolder = NULL;
	}
	if (packetHolder) {
		packetQueueFreeCallback(packetHolder);
	}
	sws_freeContext(scaleContext);
	av_free(scaleHolder.scaleBuffer);
	av_frame_free(&frame);
	(*loadJavaVM)->DetachCurrentThread(loadJavaVM);
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
	packetHolder->type = allocPacket ? PACKET_HOLDER_MEDIA : PACKET_HOLDER_END_OF_STREAM;
	return packetHolder;
}

static PacketHolder * createSurfaceRequestPacketHolder(void) {
	PacketHolder * packetHolder = createPacketHolder(0);
	packetHolder->type = PACKET_HOLDER_SURFACE_REQUEST;
	return packetHolder;
}

static void * performDecodePackets(void * data) {
	Player * player = (Player *) data;
	JNIEnv * env;
	(*loadJavaVM)->AttachCurrentThread(loadJavaVM, &env, NULL);
	Bridge * bridge = obtainBridge(player, env);
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

static int prepareSoftwareVideoOutputLocked(Player * player) {
	if (!player->video.window) {
		return 0;
	}
	int windowFormat = ANativeWindow_getFormat(player->video.window);
	int videoFormat = getSoftwareVideoFormat(windowFormat);
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
	AVCodecParameters * parameters = stream->codecpar;
	const char * decoderName = getMediaCodecDecoderName(stream->codecpar->codec_id);
	diagnosticsLog("player=%u mediacodec_capability_check mode=configure_original_stream"
			" codec=%s profile=%d level=%d size=%dx%d fps=%d/%d",
			player->meta.diagnosticsId, avcodec_get_name(parameters->codec_id),
			parameters->profile, parameters->level, parameters->width, parameters->height,
			stream->avg_frame_rate.num, stream->avg_frame_rate.den);
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

static int fallbackMediaCodecToSoftware(Player * player) {
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
	int outputPrepared = prepareSoftwareVideoOutputLocked(player);
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
		decoderReset = configureMediaCodecSurface(player, surface);
#endif
		if (player->video.hardwareDecoderActive) {
			pthread_cond_broadcast(&player->video.sleepCond);
			return decoderReset;
		}
		prepareSoftwareVideoOutputLocked(player);
	}
	return decoderReset;
}

static void applyPendingSurface(Player * player, JNIEnv * env) {
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
		setPlaying((jlong) (long) player, 0);
	}
	int64_t position = getPosition((jlong) (long) player);
	diagnosticsLog("player=%u surface_apply_started generation=%" PRId64
			" position_ms=%" PRId64 " was_playing=%d mediacodec_stage=%s",
			player->meta.diagnosticsId, generation, position, wasPlaying,
			getDiagnosticsMediaCodecStageName(mediaCodecStage));

	pthread_mutex_lock(&player->decode.video.frameMutex);
	pthread_mutex_lock(&player->video.sleepDrawMutex);
	if (surfaceWidth > 0 && surfaceHeight > 0) {
		__atomic_store_n(&player->video.surfaceWidth, surfaceWidth, __ATOMIC_RELEASE);
		__atomic_store_n(&player->video.surfaceHeight, surfaceHeight, __ATOMIC_RELEASE);
	}
	jobject oldSurface = player->video.activeSurface;
	releasePlayerSurface(player);
	int decoderReset = setPlayerSurfaceLocked(env, player, surface);
	int attached = player->video.window != NULL;
	player->video.activeSurface = attached ? surface : NULL;
	if (attached) {
		diagnosticsIncrement(&diagnostics.stats.surfaceAttached);
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
	Bridge * bridge = obtainBridge(player, env);
	(*env)->CallVoidMethod(env, player->bridge.native, bridge->methodOnSurfaceApplied,
			(jlong) generation, (jlong) position, (jboolean) !!decoderReset);
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
	player->meta.diagnosticsId = __atomic_add_fetch(&nextDiagnosticsPlayerId, 1, __ATOMIC_RELAXED);
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
	if (player->av.duration.probeThreadStarted) {
		logDestroyStage(player, "join_duration_probe_started");
		pthread_join(player->av.duration.probeThread, NULL);
		player->av.duration.probeThreadStarted = 0;
		logDestroyStage(player, "join_duration_probe_finished");
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
	clearAudioOutputLocked(player, 0);
	pthread_mutex_unlock(&player->audio.sleepBufferMutex);
	logDestroyStage(player, "synchronization_cleanup_started");
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
	releasePlayerSurface(player);
	if (pendingSurface) {
		(*env)->DeleteGlobalRef(env, pendingSurface);
	}
	if (activeSurface) {
		(*env)->DeleteGlobalRef(env, activeSurface);
	}
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
	Bridge * bridge = obtainBridge(player, env);
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
	setSkipFlag(&player->sync.skip.audioWorkFrame, 1);
	setSkipFlag(&player->sync.skip.videoWorkFrame, 1);
	diagnosticsLog("player=%u seek_phase=packet_queues_clear_started",
			player->meta.diagnosticsId);
	blockingQueueClear(&player->audio.packetQueue, packetQueueFreeCallback);
	blockingQueueClear(&player->video.packetQueue, packetQueueFreeCallback);
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
	clearAudioOutputLocked(player, 1);
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
	setSkipFlag(&player->sync.skip.drawWorkFrame, 1);
	if (player->video.bufferQueue) {
		bufferQueueClear(player->video.bufferQueue, videoBufferQueueFreeCallback);
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
			restoreSoftwareSeekFastDecodeLocked(player, GET_CONTEXT(player, video),
					"seek_prepare", -1);
			resetSoftwareDecoderGovernorLocked(player, GET_CONTEXT(player, video),
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
		startSoftwareSeekFastDecodeLocked(player, GET_CONTEXT(player, video),
				keyframePosition, position);
	}
	setSkipFlag(&player->sync.skip.audioWorkFrame, 1);
	setSkipFlag(&player->sync.skip.videoWorkFrame, 1);
	setSkipFlag(&player->sync.skip.drawWorkFrame, 1);
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
			setSkipFlag(&player->sync.skip.audioWorkFrame, 1);
			clearAudioOutputLocked(player, 1);
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

jboolean setVolume(jlong pointer, jint volume, jint boostDb) {
	Player * player = POINTER_CAST(pointer);
	if (!HAS_STREAM(player, audio) || !player->audio.sl.volume) {
		return 0;
	}
	if (volume < 0) {
		volume = 0;
	} else if (volume > 100) {
		volume = 100;
	}
	if (boostDb < 0) {
		boostDb = 0;
	} else if (boostDb > AUDIO_MAX_BOOST_DB) {
		boostDb = AUDIO_MAX_BOOST_DB;
	}
	SLmillibel level;
	if (volume <= 0) {
		level = SL_MILLIBEL_MIN;
	} else if (boostDb > 0) {
		// Positive gain is applied once to each decoded PCM buffer before it enters
		// the OpenSL queue. The system media volume stays untouched and remains the
		// final output control.
		level = 0;
	} else {
		// OpenSL ES uses millibels. Convert a linear 0..100 amplitude to dB
		// so the UI percentage remains useful across the whole audible range.
		level = (SLmillibel) lroundf(2000.f * log10f(volume / 100.f));
	}
	pthread_mutex_lock(&player->audio.sleepBufferMutex);
	SLmillibel maximumLevel = 0;
	if ((*player->audio.sl.volume)->GetMaxVolumeLevel(player->audio.sl.volume,
			&maximumLevel) == SL_RESULT_SUCCESS && level > maximumLevel) {
		level = maximumLevel;
	}
	SLresult result = (*player->audio.sl.volume)->SetVolumeLevel(player->audio.sl.volume, level);
	if (result == SL_RESULT_SUCCESS) {
		player->audio.localVolume = volume;
		player->audio.boostDb = boostDb;
		diagnosticsLog("player=%u local_volume=%d boost_db=%d",
				player->meta.diagnosticsId, volume, boostDb);
	}
	pthread_mutex_unlock(&player->audio.sleepBufferMutex);
	return result == SL_RESULT_SUCCESS;
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

void stopAudio(jlong pointer) {
	Player * player = POINTER_CAST(pointer);
	if (!HAS_STREAM(player, audio) || !player->audio.sl.play) {
		return;
	}
	diagnosticsLog("player=%u audio_stop_immediate started", player->meta.diagnosticsId);
	pthread_mutex_lock(&player->audio.sleepBufferMutex);
	(*player->audio.sl.play)->SetPlayState(player->audio.sl.play, SL_PLAYSTATE_PAUSED);
	clearAudioOutputLocked(player, 0);
	pthread_mutex_unlock(&player->audio.sleepBufferMutex);
	diagnosticsLog("player=%u audio_stop_immediate finished", player->meta.diagnosticsId);
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
			resetSoftwareDecoderGovernorLocked(player, GET_CONTEXT(player, video), "pause");
			pthread_mutex_unlock(&player->decode.video.frameMutex);
		}
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
			getDiagnosticsMediaCodecStageName(mediaCodecStage));
	setSkipFlag(&player->sync.skip.videoWorkFrame, 1);
	setSkipFlag(&player->sync.skip.drawWorkFrame, 1);
	pthread_cond_broadcast(&player->video.sleepCond);
	pthread_cond_broadcast(&player->video.queueCond);
	pthread_cond_broadcast(&player->play.finishCond);
	blockingQueueAdd(&player->video.packetQueue, createSurfaceRequestPacketHolder());
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
