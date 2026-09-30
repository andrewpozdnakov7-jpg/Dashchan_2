#ifndef PLAYER_DIAGNOSTICS_H
#define PLAYER_DIAGNOSTICS_H

#include "player_internal.h"

enum PlayerDiagnosticsCounter {
	PLAYER_DIAGNOSTICS_SURFACE_ATTACHED,
	PLAYER_DIAGNOSTICS_SURFACE_DETACHED,
	PLAYER_DIAGNOSTICS_DECODER_ENABLED,
	PLAYER_DIAGNOSTICS_DECODER_UNAVAILABLE,
	PLAYER_DIAGNOSTICS_SOFTWARE_FALLBACK,
	PLAYER_DIAGNOSTICS_SOFTWARE_DECODED,
	PLAYER_DIAGNOSTICS_SOFTWARE_RENDERED,
	PLAYER_DIAGNOSTICS_SOFTWARE_OUTPUT_DOWNGRADE,
	PLAYER_DIAGNOSTICS_SOFTWARE_DECODER_DISCARD_ENABLED,
	PLAYER_DIAGNOSTICS_SOFTWARE_DECODER_DISCARD_RESTORED,
	PLAYER_DIAGNOSTICS_SOFTWARE_SEEK_FAST_STARTED,
	PLAYER_DIAGNOSTICS_SOFTWARE_SEEK_FAST_RESTORED
};

enum {
	DIAGNOSTICS_OUTPUT_SCHEDULED,
	DIAGNOSTICS_OUTPUT_IMMEDIATE,
	DIAGNOSTICS_OUTPUT_DROPPED_LATE,
	DIAGNOSTICS_OUTPUT_DROPPED_SEEK,
	DIAGNOSTICS_OUTPUT_DROPPED_STATE,
	DIAGNOSTICS_OUTPUT_DROPPED_CADENCE,
	DIAGNOSTICS_OUTPUT_DROPPED_SUPERSEDED,
	DIAGNOSTICS_OUTPUT_NO_BUFFER
};

unsigned int diagnosticsNextPlayerId(void);
void diagnosticsRegisterPlayer(Player * player);
void diagnosticsUnregisterPlayer(Player * player);
void samplePlayerDiagnostics(void);
// Operation: video send/receive = 0/1, audio send/receive = 2/3.
int64_t diagnosticsCodecBegin(Player * player, int operation);
void diagnosticsCodecEnd(Player * player, int operation, int64_t startedUs, int result);
// Called with the corresponding decoder's frameMutex held. No media bytes/URLs.
void diagnosticsCodecResult(Player * player, int operation, AVCodecContext * context,
		const PacketHolder * holder, const AVFrame * frame, int packetAccepted, int result);
void diagnosticsPresentation(Player * player, int64_t position, int action);
void diagnosticsRangeWait(Player * player, int waiting);
const char * diagnosticsGetMediaCodecStageName(int stage);
void diagnosticsLog(const char * format, ...);
void diagnosticsIncrement(enum PlayerDiagnosticsCounter counter);
void diagnosticsLogSeekLock(Player * player, const char * phase,
		const char * lock, const char * state);
void diagnosticsRecordAudioChunk(Player * player, int size, int depth);
void diagnosticsRecordAudioUnderrun(Player * player);
void diagnosticsRecordAudioMasterResumed(Player * player, int64_t position);
void diagnosticsRecordSoftwareDrop(Player * player, int decodeStage,
		int64_t framePosition, int64_t playbackPosition, int64_t lateness);
void diagnosticsRecordSoftwareLateAnchor(Player * player, int rendered,
		int64_t framePosition, int64_t playbackPosition, int64_t lateness);
void diagnosticsRecordVideoPacket(Player * player, AVPacket * packet);
void diagnosticsRecordMediaInfo(Player * player);

// Extended-only aggregate timings. These never wait for the diagnostics mutex.
enum DiagnosticsWork {
	DIAGNOSTICS_WORK_PACKET_WAIT,
	DIAGNOSTICS_WORK_DECODE_LOCK,
	DIAGNOSTICS_WORK_DECODE,
	DIAGNOSTICS_WORK_CONVERT,
	DIAGNOSTICS_WORK_QUEUE_WAIT,
	DIAGNOSTICS_WORK_QUEUE_COPY,
	DIAGNOSTICS_WORK_DRAW_WAIT,
	DIAGNOSTICS_WORK_DRAW_LOCK,
	DIAGNOSTICS_WORK_SCHEDULE_WAIT,
	DIAGNOSTICS_WORK_LAST_FRAME_COPY,
	DIAGNOSTICS_WORK_WINDOW_LOCK,
	DIAGNOSTICS_WORK_WINDOW_COPY,
	DIAGNOSTICS_WORK_WINDOW_POST,
	DIAGNOSTICS_WORK_COUNT
};
typedef struct { int64_t wallUs, cpuUs; } DiagnosticsWorkStamp;
DiagnosticsWorkStamp diagnosticsWorkBegin(void);
void diagnosticsWorkEnd(Player * player, int operation, DiagnosticsWorkStamp stamp, int result);
void diagnosticsSoftwareFrame(Player * player, int64_t position, int64_t lateness, int canDrop,
		int width, int height, int outputWidth, int outputHeight, int useLibyuv, int outputLevel);
void diagnosticsSoftwarePolicy(Player * player, int lateMs, int anchorMs, int maxFps,
		int governorLateMs, int governorFrames, int recoveryMs, int recoveryFrames, int conversionUs);

#ifdef DASHCHAN_HAS_MEDIACODEC
void diagnosticsRecordHardwareLateAnchor(Player * player, int64_t framePosition,
		int64_t lateness, int64_t gapMs);
void diagnosticsRecordPacketSubmitted(void);
void diagnosticsRecordDecoderError(Player * player, const char * stage, int error);
void diagnosticsRecordOutput(Player * player, AVFrame * frame, int64_t framePosition,
		int64_t waitTime, int action, int result);
#endif

#endif // PLAYER_DIAGNOSTICS_H
