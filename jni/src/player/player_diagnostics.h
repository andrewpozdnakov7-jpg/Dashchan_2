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
	DIAGNOSTICS_OUTPUT_NO_BUFFER
};

unsigned int diagnosticsNextPlayerId(void);
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

#ifdef DASHCHAN_HAS_MEDIACODEC
void diagnosticsRecordPacketSubmitted(void);
void diagnosticsRecordDecoderError(Player * player, const char * stage, int error);
void diagnosticsRecordOutput(Player * player, AVFrame * frame, int64_t framePosition,
		int64_t waitTime, int action, int result);
#endif

#endif // PLAYER_DIAGNOSTICS_H
