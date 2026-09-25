#include "player.h"
#include "player_diagnostics.h"
#include "player_timing.h"
#include "util.h"

#include <inttypes.h>
#include <errno.h>
#include <limits.h>
#include <stdarg.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <time.h>

#include <libavutil/ffversion.h>
#include <libavutil/pixdesc.h>

#ifndef DASHCHAN_FFMPEG_FLAVOR
#define DASHCHAN_FFMPEG_FLAVOR "ffmpeg"
#endif

#define DIAGNOSTICS_BUFFER_SIZE (96 * 1024 * 1024)
#define DIAGNOSTICS_SUMMARY_RESERVE (4 * 1024)
#define DIAGNOSTICS_CHUNK_SIZE (64 * 1024)
#define DIAGNOSTICS_MAX_PLAYERS 16

typedef struct DiagnosticsChunk {
	struct DiagnosticsChunk * next;
	size_t length;
	char data[DIAGNOSTICS_CHUNK_SIZE];
} DiagnosticsChunk;

typedef struct {
	Player * player;
	int64_t sampledUs, lastDecodedUs, lastPresentedUs, lastAudioPosition, lastAudioUs, presentedPosition;
	int64_t rangeStartedUs, rangeTotalUs, lastStallUs;
	uint64_t decoded, presented, dropped, consecutiveLate;
	struct {
		int64_t startedUs, totalUs, maxUs;
		uint64_t calls, errors;
	} codec[4];
} DiagnosticsPlayer;

static int64_t diagnosticsNowUs(void) {
	struct timespec value;
	clock_gettime(CLOCK_MONOTONIC, &value);
	return (int64_t) value.tv_sec * 1000000 + value.tv_nsec / 1000;
}

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
	int extended;
	int truncated;
	int64_t startedAt;
	size_t length;
	DiagnosticsChunk * first;
	DiagnosticsChunk * last;
	size_t chunks;
	int finalized;
	unsigned int skippedSamples;
	DiagnosticsPlayer players[DIAGNOSTICS_MAX_PLAYERS];
	DiagnosticsStats stats;
} diagnostics = {
	.mutex = PTHREAD_MUTEX_INITIALIZER
};
static unsigned int nextDiagnosticsPlayerId;

static int diagnosticsActive(void) {
	return __atomic_load_n(&diagnostics.active, __ATOMIC_RELAXED);
}

static void diagnosticsAppendVLineLocked(size_t limit, const char * format, va_list arguments) {
	(void) limit; // Chunk budget includes summary lines; no contiguous 100 MiB allocation.
	char line[4096];
	int64_t elapsed = diagnostics.startedAt > 0 ? diagnosticsNowUs() / 1000 - diagnostics.startedAt : 0;
	int prefix = snprintf(line, sizeof(line), "[+%" PRId64 "ms] ", elapsed);
	if (prefix < 0 || (size_t) prefix >= sizeof(line) - 1) return;
	int written = vsnprintf(line + prefix, sizeof(line) - prefix - 1, format, arguments);
	if (written < 0) return;
	size_t length = (size_t) prefix + (size_t) written;
	if (length >= sizeof(line) - 1) {
		length = sizeof(line) - 2;
		diagnostics.truncated = 1;
	}
	line[length++] = '\n';
	if (!diagnostics.last || diagnostics.last->length + length > DIAGNOSTICS_CHUNK_SIZE) {
		DiagnosticsChunk * chunk = NULL;
		size_t budget = diagnostics.extended ? DIAGNOSTICS_BUFFER_SIZE : 512 * 1024;
		if (diagnostics.chunks < budget / sizeof(DiagnosticsChunk)) {
			chunk = malloc(sizeof(DiagnosticsChunk));
			if (chunk) diagnostics.chunks++;
		}
		if (!chunk && diagnostics.first && diagnostics.first->next) {
			// Keep initial context, recycle the oldest subsequent chunk without copying the journal.
			chunk = diagnostics.first->next;
			diagnostics.first->next = chunk->next;
			if (diagnostics.last == chunk) diagnostics.last = diagnostics.first;
			diagnostics.length -= chunk->length;
			diagnostics.truncated = 1;
		}
		if (!chunk) { diagnostics.truncated = 1; return; }
		chunk->next = NULL;
		chunk->length = 0;
		if (diagnostics.last) diagnostics.last->next = chunk;
		else diagnostics.first = chunk;
		diagnostics.last = chunk;
	}
	memcpy(diagnostics.last->data + diagnostics.last->length, line, length);
	diagnostics.last->length += length;
	diagnostics.length += length;
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

static void startPlayerDiagnosticsMode(int extended) {
	if (diagnosticsActive()) {
		return;
	}
	pthread_mutex_lock(&diagnostics.mutex);
	if (!diagnosticsActive()) {
		__atomic_store_n(&diagnostics.extended, extended, __ATOMIC_RELAXED);
		memset(&diagnostics.stats, 0, sizeof(diagnostics.stats));
		diagnostics.stats.firstOutputElapsedMs = -1;
		diagnostics.stats.minWaitMs = INT64_MAX;
		diagnostics.stats.maxWaitMs = INT64_MIN;
		diagnostics.truncated = 0;
		while (diagnostics.first) {
			DiagnosticsChunk * next = diagnostics.first->next;
			free(diagnostics.first);
			diagnostics.first = next;
		}
		diagnostics.last = NULL;
		diagnostics.chunks = diagnostics.length = 0;
		diagnostics.finalized = 0;
		__atomic_store_n(&diagnostics.skippedSamples, 0, __ATOMIC_RELAXED);
		for (int i = 0; i < DIAGNOSTICS_MAX_PLAYERS; i++) {
			Player * player = diagnostics.players[i].player;
			memset(&diagnostics.players[i], 0, sizeof(DiagnosticsPlayer));
			diagnostics.players[i].player = player;
		}
		diagnostics.startedAt = diagnosticsNowUs() / 1000;
		__atomic_store_n(&diagnostics.active, 1, __ATOMIC_RELAXED);
		diagnosticsAppendLineLocked(DIAGNOSTICS_BUFFER_SIZE - DIAGNOSTICS_SUMMARY_RESERVE,
				"capture_started=true");
		diagnosticsAppendLineLocked(DIAGNOSTICS_BUFFER_SIZE,
				"mode=%s periodic_sample_ms=%d native_budget_kib=%d",
				extended ? "extended" : "standard", extended ? 1000 : 0, extended ? 96 * 1024 : 512);
		diagnosticsAppendLineLocked(DIAGNOSTICS_BUFFER_SIZE - DIAGNOSTICS_SUMMARY_RESERVE,
				"diagnostics_schema=13 chunk_kib=64"
				" monotonic_clock=1 nonblocking_samples=1 playback_speed=1 speed_scale=1000 native_seek_locks=1 mediacodec_stages=1"
				" seek_worker_stop=1 surface_queue=1 duration_probe=1 packet_generation=1"
				" software_output_scaling=1 software_late_drop=1 software_decode_governor=1"
				" software_governor_recovery=2 software_late_anchor_ms=200"
				" software_seek_fast_decode=1 audio_master_clock=1 audio_output_seek_restart=1"
				" audio_output_queue_state=1"
				" audio_output_prefill=2 audio_chunk_target_ms=40 audio_output_reconcile=1"
				" audio_output_stall_recovery=1");
	}
	pthread_mutex_unlock(&diagnostics.mutex);
}

void startPlayerDiagnostics(void) { startPlayerDiagnosticsMode(0); }
void startExtendedPlayerDiagnostics(void) { startPlayerDiagnosticsMode(1); }

void releasePlayerDiagnostics(void) {
	pthread_mutex_lock(&diagnostics.mutex);
	if (!diagnosticsActive()) {
		while (diagnostics.first) {
			DiagnosticsChunk * next = diagnostics.first->next;
			free(diagnostics.first);
			diagnostics.first = next;
		}
		diagnostics.last = NULL;
		diagnostics.chunks = diagnostics.length = 0;
	}
	pthread_mutex_unlock(&diagnostics.mutex);
}

jint finishPlayerDiagnostics(void) {
	__atomic_store_n(&diagnostics.active, 0, __ATOMIC_RELAXED);
	pthread_mutex_lock(&diagnostics.mutex);
	if (diagnostics.finalized) {
		jint length = (jint) diagnostics.length;
		pthread_mutex_unlock(&diagnostics.mutex);
		return length;
	}
	diagnostics.finalized = 1;
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
	jint result = (jint) diagnostics.length;
	pthread_mutex_unlock(&diagnostics.mutex);
	return result;
}

jbyteArray readPlayerDiagnosticsChunk(JNIEnv * env, jint offset, jint length) {
	if (offset < 0 || length <= 0 || length > DIAGNOSTICS_CHUNK_SIZE) return NULL;
	pthread_mutex_lock(&diagnostics.mutex);
	if (!diagnostics.finalized || (size_t) offset >= diagnostics.length) {
		pthread_mutex_unlock(&diagnostics.mutex);
		return NULL;
	}
	size_t count = diagnostics.length - offset;
	if (count > (size_t) length) count = (size_t) length;
	jbyteArray result = (*env)->NewByteArray(env, (jsize) count);
	if (result) {
		size_t remaining = count, skip = (size_t) offset;
		for (DiagnosticsChunk * chunk = diagnostics.first; chunk && remaining; chunk = chunk->next) {
			if (skip >= chunk->length) { skip -= chunk->length; continue; }
			size_t take = chunk->length - skip;
			if (take > remaining) take = remaining;
			(*env)->SetByteArrayRegion(env, result, (jsize) (count - remaining), (jsize) take,
					(const jbyte *) (chunk->data + skip));
			remaining -= take;
			skip = 0;
		}
	}
	pthread_mutex_unlock(&diagnostics.mutex);
	return result;
}

// Kept for older Java clients. The current client streams byte chunks instead.
jstring stopPlayerDiagnostics(JNIEnv * env) {
	finishPlayerDiagnostics();
	pthread_mutex_lock(&diagnostics.mutex);
	char * buffer = malloc(diagnostics.length + 1);
	jstring result = NULL;
	if (buffer) {
		size_t offset = 0;
		for (DiagnosticsChunk * chunk = diagnostics.first; chunk; chunk = chunk->next) {
			memcpy(buffer + offset, chunk->data, chunk->length);
			offset += chunk->length;
		}
		buffer[offset] = '\0';
		result = (*env)->NewStringUTF(env, buffer);
		free(buffer);
	}
	pthread_mutex_unlock(&diagnostics.mutex);
	return result;
}

unsigned int diagnosticsNextPlayerId(void) {
	return __atomic_add_fetch(&nextDiagnosticsPlayerId, 1, __ATOMIC_RELAXED);
}

static DiagnosticsPlayer * diagnosticsFindPlayerLocked(Player * player) {
	for (int i = 0; i < DIAGNOSTICS_MAX_PLAYERS; i++) {
		if (diagnostics.players[i].player == player) return &diagnostics.players[i];
	}
	return NULL;
}

void diagnosticsRegisterPlayer(Player * player) {
	pthread_mutex_lock(&diagnostics.mutex);
	for (int i = 0; i < DIAGNOSTICS_MAX_PLAYERS; i++) {
		if (!diagnostics.players[i].player) {
			memset(&diagnostics.players[i], 0, sizeof(DiagnosticsPlayer));
			diagnostics.players[i].player = player;
			break;
		}
	}
	pthread_mutex_unlock(&diagnostics.mutex);
}

void diagnosticsUnregisterPlayer(Player * player) {
	pthread_mutex_lock(&diagnostics.mutex);
	DiagnosticsPlayer * entry = diagnosticsFindPlayerLocked(player);
	if (entry) entry->player = NULL;
	pthread_mutex_unlock(&diagnostics.mutex);
}

static int diagnosticsTrySampleLock(void) {
	if (!diagnosticsActive() || !__atomic_load_n(&diagnostics.extended, __ATOMIC_RELAXED)) return 0;
	if (pthread_mutex_trylock(&diagnostics.mutex)) {
		__atomic_add_fetch(&diagnostics.skippedSamples, 1, __ATOMIC_RELAXED);
		return 0;
	}
	if (!diagnosticsActive()) { pthread_mutex_unlock(&diagnostics.mutex); return 0; }
	return 1;
}

int64_t diagnosticsCodecBegin(Player * player, int operation) {
	if (operation < 0 || operation >= 4 || !diagnosticsTrySampleLock()) return 0;
	DiagnosticsPlayer * entry = diagnosticsFindPlayerLocked(player);
	int64_t now = diagnosticsNowUs();
	if (entry) entry->codec[operation].startedUs = now;
	pthread_mutex_unlock(&diagnostics.mutex);
	return entry ? now : 0;
}

void diagnosticsCodecEnd(Player * player, int operation, int64_t startedUs, int result) {
	if (!startedUs || operation < 0 || operation >= 4 || !diagnosticsActive()) return;
	// Completion must clear the in-flight marker even when a periodic snapshot is running.
	pthread_mutex_lock(&diagnostics.mutex);
	DiagnosticsPlayer * entry = diagnosticsFindPlayerLocked(player);
	if (diagnosticsActive() && entry && entry->codec[operation].startedUs == startedUs) {
		int64_t now = diagnosticsNowUs(), elapsed = now - startedUs;
		entry->codec[operation].startedUs = 0;
		entry->codec[operation].calls++;
		entry->codec[operation].totalUs += elapsed;
		if (elapsed > entry->codec[operation].maxUs) entry->codec[operation].maxUs = elapsed;
		if (result < 0 && result != AVERROR(EAGAIN) && result != AVERROR_EOF) entry->codec[operation].errors++;
		if (operation == 1 && result >= 0) { entry->decoded++; entry->lastDecodedUs = now; }
	}
	pthread_mutex_unlock(&diagnostics.mutex);
}

void diagnosticsPresentation(Player * player, int64_t position, int action) {
	if (!diagnosticsTrySampleLock()) return;
	DiagnosticsPlayer * entry = diagnosticsFindPlayerLocked(player);
	if (entry) {
		if (action == DIAGNOSTICS_OUTPUT_IMMEDIATE || action == DIAGNOSTICS_OUTPUT_SCHEDULED) {
			entry->presented++;
			entry->lastPresentedUs = diagnosticsNowUs();
			entry->presentedPosition = position;
			entry->consecutiveLate = 0;
		} else {
			entry->dropped++;
			if (action == DIAGNOSTICS_OUTPUT_DROPPED_LATE) entry->consecutiveLate++;
			else entry->consecutiveLate = 0;
		}
	}
	pthread_mutex_unlock(&diagnostics.mutex);
}

void diagnosticsRangeWait(Player * player, int waiting) {
	if (!diagnosticsActive() || !__atomic_load_n(&diagnostics.extended, __ATOMIC_RELAXED)) return;
	pthread_mutex_lock(&diagnostics.mutex);
	DiagnosticsPlayer * entry = diagnosticsFindPlayerLocked(player);
	if (diagnosticsActive() && entry) {
		int64_t now = diagnosticsNowUs();
		if (waiting) { if (!entry->rangeStartedUs) entry->rangeStartedUs = now; }
		else if (entry->rangeStartedUs) {
			entry->rangeTotalUs += now - entry->rangeStartedUs;
			entry->rangeStartedUs = 0;
		}
	}
	pthread_mutex_unlock(&diagnostics.mutex);
}

static int diagnosticsQueueDepth(BlockingQueue * queue) {
	if (pthread_mutex_trylock(&queue->mutex)) return -1;
	int count = queue->queue.count;
	pthread_mutex_unlock(&queue->mutex);
	return count;
}

void samplePlayerDiagnostics(void) {
	if (!diagnosticsTrySampleLock()) return;
	int64_t now = diagnosticsNowUs();
	for (int i = 0; i < DIAGNOSTICS_MAX_PLAYERS; i++) {
		DiagnosticsPlayer * entry = &diagnostics.players[i];
		Player * player = entry->player;
		if (!player) continue;
		// Never wait for a core lock while holding the diagnostics registry lock. Destroy unregisters
		// the player before destroying these locks. A busy field is explicitly unavailable (-1).
		int playing = -1, seeking = -1, speed = -1, audioDepth = -1;
		int64_t audioPosition = -1, audioAdvance = -1, audioElapsed = -1;
		if (!pthread_mutex_trylock(&player->play.finishMutex)) {
			playing = player->play.playing;
			seeking = player->play.pausedSeekState;
			pthread_mutex_unlock(&player->play.finishMutex);
		}
		if (!pthread_mutex_trylock(&player->audio.sleepBufferMutex)) {
			speed = getPlaybackSpeed(player);
			audioPosition = player->sync.audioPosition;
			audioDepth = player->audio.outputChunkCount;
			pthread_mutex_unlock(&player->audio.sleepBufferMutex);
			if (entry->lastAudioUs) {
				audioAdvance = audioPosition - entry->lastAudioPosition;
				audioElapsed = (now - entry->lastAudioUs) / 1000;
			}
			entry->lastAudioUs = now;
			entry->lastAudioPosition = audioPosition;
		}
		long rangeStart = -1, rangeEnd = -1, rangeTotal = -1;
		if (!pthread_mutex_trylock(&player->file.controlMutex)) {
			rangeStart = player->file.start; rangeEnd = player->file.end; rangeTotal = player->file.total;
			pthread_mutex_unlock(&player->file.controlMutex);
		}
		int videoQueue = -1;
		// Output creation owns sleepDrawMutex; buffer enqueue/dequeue owns queueMutex.
		if (!pthread_mutex_trylock(&player->video.sleepDrawMutex)) {
			if (!pthread_mutex_trylock(&player->video.queueMutex)) {
				videoQueue = player->video.bufferQueue ? bufferQueueCount(player->video.bufferQueue) : 0;
				pthread_mutex_unlock(&player->video.queueMutex);
			}
			pthread_mutex_unlock(&player->video.sleepDrawMutex);
		}
		int64_t interval = entry->sampledUs ? (now - entry->sampledUs) / 1000 : 0;
		int64_t decodedAge = entry->lastDecodedUs ? (now - entry->lastDecodedUs) / 1000 : -1;
		int64_t presentedAge = entry->lastPresentedUs ? (now - entry->lastPresentedUs) / 1000 : -1;
		diagnosticsAppendLineLocked(DIAGNOSTICS_BUFFER_SIZE,
				"player=%u sample interval_ms=%" PRId64 " playing=%d paused_seek_state=%d speed_milli=%d"
				" decoded=%" PRIu64 " presented=%" PRIu64 " dropped=%" PRIu64 " consecutive_late=%" PRIu64
				" decoded_age_ms=%" PRId64 " presented_age_ms=%" PRId64,
				player->meta.diagnosticsId, interval, playing, seeking, speed, entry->decoded,
				entry->presented, entry->dropped, entry->consecutiveLate, decodedAge, presentedAge);
		diagnosticsAppendLineLocked(DIAGNOSTICS_BUFFER_SIZE,
				"player=%u clocks audio_ms=%" PRId64 " presented_video_ms=%" PRId64
				" audio_minus_video_ms=%" PRId64 " audio_advance_ms=%" PRId64 " audio_interval_ms=%" PRId64
				" video_packets=%d audio_packets=%d video_buffers=%d audio_buffers=%d audio_output_depth=%d"
				" video_stage=%d audio_stage=%d samples_skipped=%u",
				player->meta.diagnosticsId, audioPosition, entry->lastPresentedUs ? entry->presentedPosition : -1,
				audioPosition >= 0 && entry->lastPresentedUs ? audioPosition - entry->presentedPosition : -1,
				audioAdvance, audioElapsed, diagnosticsQueueDepth(&player->video.packetQueue),
				diagnosticsQueueDepth(&player->audio.packetQueue), videoQueue,
				diagnosticsQueueDepth(&player->audio.bufferQueue), audioDepth,
				__atomic_load_n(&player->decode.video.diagnosticsStage, __ATOMIC_RELAXED),
				__atomic_load_n(&player->decode.audio.diagnosticsStage, __ATOMIC_RELAXED),
				__atomic_load_n(&diagnostics.skippedSamples, __ATOMIC_RELAXED));
		diagnosticsAppendLineLocked(DIAGNOSTICS_BUFFER_SIZE,
				"player=%u range start=%ld end=%ld total=%ld waiting=%d wait_age_ms=%" PRId64
				" completed_wait_ms=%" PRId64, player->meta.diagnosticsId, rangeStart, rangeEnd, rangeTotal,
				entry->rangeStartedUs != 0, entry->rangeStartedUs ? (now - entry->rangeStartedUs) / 1000 : 0,
				entry->rangeTotalUs / 1000);
		for (int op = 0; op < 4; op++) {
			uint64_t calls = entry->codec[op].calls;
			diagnosticsAppendLineLocked(DIAGNOSTICS_BUFFER_SIZE,
					"player=%u codec_timing op=%d calls=%" PRIu64 " avg_us=%" PRId64 " max_us=%" PRId64
					" inflight_ms=%" PRId64 " errors=%" PRIu64, player->meta.diagnosticsId, op, calls,
					calls ? entry->codec[op].totalUs / (int64_t) calls : 0, entry->codec[op].maxUs,
					entry->codec[op].startedUs ? (now - entry->codec[op].startedUs) / 1000 : 0,
					entry->codec[op].errors);
			entry->codec[op].calls = entry->codec[op].errors = 0;
			entry->codec[op].totalUs = entry->codec[op].maxUs = 0;
		}
		// A hint, not a diagnosis: paused/seeking/finished video may legitimately have no frames.
		if (playing == 1 && seeking == PAUSED_SEEK_NONE && audioAdvance > 0 && presentedAge > 2000
				&& now - entry->lastStallUs > 5000000) {
			diagnosticsAppendLineLocked(DIAGNOSTICS_BUFFER_SIZE,
					"player=%u stall_suspected=1 reason=audio_advances_without_presented_frame",
					player->meta.diagnosticsId);
			entry->lastStallUs = now;
		}
		entry->sampledUs = now;
		entry->decoded = entry->presented = entry->dropped = 0;
	}
	pthread_mutex_unlock(&diagnostics.mutex);
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
	diagnosticsPresentation(player, framePosition, DIAGNOSTICS_OUTPUT_DROPPED_LATE);
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
					" dts=%" PRId64 " duration=%" PRId64 " size=%d key=%d speed_milli=%d",
					player->meta.diagnosticsId, count, packet->pts, packet->dts,
					packet->duration, packet->size, key, getPlaybackSpeed(player));
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
	diagnosticsPresentation(player, framePosition, result >= 0 ? action : DIAGNOSTICS_OUTPUT_NO_BUFFER);
	if (!diagnosticsActive()) {
		return;
	}
	const char * actionName;
	pthread_mutex_lock(&diagnostics.mutex);
	if (diagnosticsActive()) {
		uint64_t count = ++diagnostics.stats.outputFrames;
		if (diagnostics.stats.firstOutputElapsedMs < 0) {
			diagnostics.stats.firstOutputElapsedMs = diagnosticsNowUs() / 1000 - diagnostics.startedAt;
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
					" width=%d height=%d format=%d action=%s release_result=%d speed_milli=%d",
					player->meta.diagnosticsId, count, frame->pts, frame->best_effort_timestamp,
					framePosition, waitTime, frame->width, frame->height, frame->format,
					actionName, result, getPlaybackSpeed(player));
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
	diagnosticsLog("player=%u playback_speed_initial speed_milli=%d speed_percent=%d",
			player->meta.diagnosticsId, getPlaybackSpeed(player), getPlaybackSpeed(player) / 10);
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
