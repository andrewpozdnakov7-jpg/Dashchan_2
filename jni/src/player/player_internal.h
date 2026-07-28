#ifndef PLAYER_INTERNAL_H
#define PLAYER_INTERNAL_H

#include "util.h"

#include <jni.h>
#include <pthread.h>
#include <stdint.h>

#include <SLES/OpenSLES.h>
#include <SLES/OpenSLES_Android.h>
#include <android/native_window_jni.h>

#include <libavcodec/avcodec.h>
#include <libavformat/avformat.h>

#define INDEX_NO_STREAM -1
#define AUDIO_OUTPUT_QUEUE_CAPACITY 2
#define PLAYBACK_SPEED_DEFAULT 1000
#define PLAYBACK_SPEED_MIN 100
#define PLAYBACK_SPEED_MAX 4000
#define WINDOW_FORMAT_YV12 0x32315659

#define POINTER_CAST(addr) (void *) (long) (addr)
#define UNLOCK_AND_GOTO(mutex, label) {pthread_mutex_unlock(mutex); goto label;}

#define BRIDGE_MESSAGE_PLAYBACK_COMPLETE 1
#define BRIDGE_MESSAGE_SIZE_CHANGED 2
#define BRIDGE_MESSAGE_START_SEEKING 3
#define BRIDGE_MESSAGE_END_SEEKING 4

#define PACKET_HOLDER_MEDIA 0
#define PACKET_HOLDER_END_OF_STREAM 1
#define PACKET_HOLDER_SURFACE_REQUEST 2

#define PLAYER_SEND_MESSAGE(env, p, b, what) \
	(*(env))->CallVoidMethod((env), (p)->bridge.native, (b)->methodOnMessage, (what))

#define HAS_STREAM(p, stream) ((p)->av.stream##StreamIndex != INDEX_NO_STREAM)
#define GET_STREAM(p, stream) ((p)->av.format->streams[(p)->av.stream##StreamIndex])
#define GET_CONTEXT(p, stream) ((p)->av.stream##Context)

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

JavaVM * playerGetJavaVM(void);
int playerGetSkipFlag(int * flag);
void playerSetSkipFlag(int * flag, int value);
void playerSetDiagnosticsAudioStage(Player * player, int stage);
void playerSetDiagnosticsMediaCodecStage(Player * player, int stage, int64_t framePosition);
Bridge * playerObtainBridge(Player * player, JNIEnv * env);
void playerCloseAndFreeCodecContext(AVCodecContext ** context);
void playerCloseAndFreeVideoCodecContext(Player * player, AVCodecContext ** context);
void playerPacketQueueFreeCallback(void * data);
void playerMarkStreamFinished(Player * player, int video);
int playerDecodeFrame(AVCodecContext * context, AVPacket * packet, AVFrame * frame,
		int * packetSent);
PacketHolder * playerCreateSurfaceRequestPacketHolder(void);
void playerLogDestroyStage(Player * player, const char * stage);
void playerNotifyDurationChanged(Player * player, int64_t duration);

#endif // PLAYER_INTERNAL_H
