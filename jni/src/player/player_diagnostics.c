#include "player.h"
#include "player_diagnostics.h"
#include "util.h"

#include <inttypes.h>
#include <limits.h>
#include <stdarg.h>
#include <stdint.h>
#include <stdio.h>
#include <string.h>

#include <libavutil/ffversion.h>
#include <libavutil/pixdesc.h>

#ifndef DASHCHAN_FFMPEG_FLAVOR
#define DASHCHAN_FFMPEG_FLAVOR "ffmpeg"
#endif

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

void diagnosticsLog(const char * format, ...) {
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
			diagnostics.stats.droppedLate, diagnostics.stats.droppedSeek,
			diagnostics.stats.droppedState);
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
				"summary first_output_ms=%" PRId64 " min_wait_ms=%" PRId64
				" max_wait_ms=%" PRId64,
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

unsigned int diagnosticsNextPlayerId(void) {
	return __atomic_add_fetch(&nextDiagnosticsPlayerId, 1, __ATOMIC_RELAXED);
}

void diagnosticsIncrement(enum PlayerDiagnosticsCounter counter) {
	if (!diagnosticsActive()) {
		return;
	}
	pthread_mutex_lock(&diagnostics.mutex);
	if (diagnosticsActive()) {
		uint64_t * value = NULL;
		switch (counter) {
			case PLAYER_DIAGNOSTICS_SURFACE_ATTACHED: value = &diagnostics.stats.surfaceAttached; break;
			case PLAYER_DIAGNOSTICS_SURFACE_DETACHED: value = &diagnostics.stats.surfaceDetached; break;
			case PLAYER_DIAGNOSTICS_DECODER_ENABLED: value = &diagnostics.stats.decoderEnabled; break;
			case PLAYER_DIAGNOSTICS_DECODER_UNAVAILABLE: value = &diagnostics.stats.decoderUnavailable; break;
			case PLAYER_DIAGNOSTICS_SOFTWARE_FALLBACK: value = &diagnostics.stats.softwareFallbacks; break;
			case PLAYER_DIAGNOSTICS_SOFTWARE_DECODED: value = &diagnostics.stats.softwareDecodedFrames; break;
			case PLAYER_DIAGNOSTICS_SOFTWARE_RENDERED: value = &diagnostics.stats.softwareRenderedFrames; break;
			case PLAYER_DIAGNOSTICS_SOFTWARE_OUTPUT_DOWNGRADE: value = &diagnostics.stats.softwareOutputDowngrades; break;
			case PLAYER_DIAGNOSTICS_SOFTWARE_DECODER_DISCARD_ENABLED: value = &diagnostics.stats.softwareDecoderDiscardEnabled; break;
			case PLAYER_DIAGNOSTICS_SOFTWARE_DECODER_DISCARD_RESTORED: value = &diagnostics.stats.softwareDecoderDiscardRestored; break;
			case PLAYER_DIAGNOSTICS_SOFTWARE_SEEK_FAST_STARTED: value = &diagnostics.stats.softwareSeekFastStarted; break;
			case PLAYER_DIAGNOSTICS_SOFTWARE_SEEK_FAST_RESTORED: value = &diagnostics.stats.softwareSeekFastRestored; break;
		}
		if (value) {
			(*value)++;
		}
	}
	pthread_mutex_unlock(&diagnostics.mutex);
}

static const char * getDiagnosticsAudioStageName(int stage) {
	switch (stage) {
		case DIAGNOSTICS_AUDIO_STAGE_WAIT_FRAME_MUTEX: return "wait_frame_mutex";
		case DIAGNOSTICS_AUDIO_STAGE_DECODE_FRAME: return "decode_frame";
		case DIAGNOSTICS_AUDIO_STAGE_WAIT_SLEEP_BUFFER_MUTEX: return "wait_sleep_buffer_mutex";
		case DIAGNOSTICS_AUDIO_STAGE_QUEUE_BUFFER: return "queue_buffer";
		default: return "idle";
	}
}

const char * diagnosticsGetMediaCodecStageName(int stage) {
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

void diagnosticsLogSeekLock(Player * player, const char * phase,
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
			diagnosticsGetMediaCodecStageName(videoStage), frameSerial, framePosition);
}

void diagnosticsRecordAudioChunk(Player * player, int size, int depth) {
	if (!diagnosticsActive()) {
		return;
	}
	pthread_mutex_lock(&diagnostics.mutex);
	if (diagnosticsActive()) {
		uint64_t count = ++diagnostics.stats.audioChunksSubmitted;
		if (count <= 8) {
			diagnosticsAppendLineLocked(DIAGNOSTICS_BUFFER_SIZE - DIAGNOSTICS_SUMMARY_RESERVE,
					"player=%u audio_output chunk_submitted count=%" PRIu64
					" bytes=%d depth=%d", player->meta.diagnosticsId, count, size, depth);
		}
	}
	pthread_mutex_unlock(&diagnostics.mutex);
}

void diagnosticsRecordAudioUnderrun(Player * player) {
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

void diagnosticsRecordAudioMasterResumed(Player * player, int64_t position) {
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

void diagnosticsRecordSoftwareDrop(Player * player, int decodeStage,
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

void diagnosticsRecordSoftwareLateAnchor(Player * player, int rendered,
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

void diagnosticsRecordVideoPacket(Player * player, AVPacket * packet) {
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
void diagnosticsRecordPacketSubmitted(void) {
	if (!diagnosticsActive()) {
		return;
	}
	pthread_mutex_lock(&diagnostics.mutex);
	if (diagnosticsActive()) {
		diagnostics.stats.packetsSubmitted++;
	}
	pthread_mutex_unlock(&diagnostics.mutex);
}

void diagnosticsRecordDecoderError(Player * player, const char * stage, int error) {
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

void diagnosticsRecordOutput(Player * player, AVFrame * frame, int64_t framePosition,
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

void diagnosticsRecordMediaInfo(Player * player) {
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
			" start_us=%" PRId64 " streams=%u", player->meta.diagnosticsId,
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
				" time_base=%d/%d extradata_size=%d", player->meta.diagnosticsId,
				avcodec_get_name(audioParameters->codec_id), audioParameters->profile,
				audioParameters->sample_rate, audio->time_base.num, audio->time_base.den,
				audioParameters->extradata_size);
	} else {
		diagnosticsLog("player=%u audio=absent_or_disabled", player->meta.diagnosticsId);
	}
}
