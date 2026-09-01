#include "player_audio.h"
#include "player.h"
#include "player_diagnostics.h"
#include "player_timing.h"
#include "util.h"
#ifdef DASHCHAN_HAS_ATEMPO
#include "tempo.h"
#endif
#include <libswresample/swresample.h>
#include <libavutil/mathematics.h>
#include <libavutil/opt.h>
#include <libavutil/samplefmt.h>
#include <inttypes.h>
#include <limits.h>
#include <math.h>
#include <stdint.h>
#include <string.h>
#define AUDIO_MAX_BOOST_DB 12
#define AUDIO_TARGET_CHUNK_MS 40
#define AUDIO_MIN_ENQUEUE_SIZE 256
#define NEED_RESAMPLE_NO 0
#define NEED_RESAMPLE_MAY_48000 1
#define NEED_RESAMPLE_FORCE_44100 2
#if LIBAVUTIL_VERSION_MAJOR >= 57
#define USE_AV_CHANNEL_LAYOUT 1
#else
#define USE_AV_CHANNEL_LAYOUT 0
#endif
static SLEngineItf slEngine;

int playerAudioGetContextChannels(const AVCodecContext * context) {
#if USE_AV_CHANNEL_LAYOUT
	return context->ch_layout.nb_channels;
#else
	return context->channels;
#endif
}

int playerAudioGetCodecParametersChannels(const AVCodecParameters * parameters) {
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

void playerAudioBufferQueueFreeCallback(void * data) {
	AudioBuffer * audioBuffer = (AudioBuffer *) data;
	if (audioBuffer) {
		av_freep(&audioBuffer->buffer);
		free(audioBuffer);
	}
}

// Callers hold audio.sleepBufferMutex. OpenSL keeps submitted PCM memory by
// reference until the matching callback, so every distinct owner must remain
// alive while its chunks are present in outputChunks.
void playerAudioClearOutputLocked(Player * player, int clearDecodedBuffers) {
	if (player->audio.sl.queue) {
		(*player->audio.sl.queue)->Clear(player->audio.sl.queue);
	}
	player->audio.outputRestartPending = 0;
	if (clearDecodedBuffers) {
		blockingQueueClear(&player->audio.bufferQueue, playerAudioBufferQueueFreeCallback);
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
		playerAudioBufferQueueFreeCallback(buffers[i]);
	}
}

// Callers hold audio.sleepBufferMutex. Android documents STOPPED -> Clear() as
// the portable way to return a simple buffer queue to a known state. Defer
// PLAYING until current-generation PCM has been queued, so seek and speed
// changes cannot leave the audio master clock advancing at a fraction of real
// time on vendor OpenSL implementations.
void playerAudioPrepareOutputResetLocked(Player * player, int clearDecodedBuffers,
		const char * reason) {
	int restartWhenReady = player->play.playing && player->audio.sl.play;
	SLresult stopResult = SL_RESULT_SUCCESS;
	if (player->audio.sl.play) {
		stopResult = (*player->audio.sl.play)->SetPlayState(player->audio.sl.play,
				SL_PLAYSTATE_STOPPED);
	}
	playerAudioClearOutputLocked(player, clearDecodedBuffers);
	player->audio.outputRestartPending = restartWhenReady;
	diagnosticsLog("player=%u audio_output reset reason=%s was_playing=%d"
			" stop_result=%d restart_pending=%d",
			player->meta.diagnosticsId, reason ? reason : "unknown",
			restartWhenReady, (int) stopResult, player->audio.outputRestartPending);
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
			playerAudioBufferQueueFreeCallback(audioBuffer);
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
int playerAudioEnqueueBuffer(Player * player) {
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
				playerAudioBufferQueueFreeCallback(audioBuffer);
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
	if (player->audio.outputRestartPending && player->play.playing &&
			player->audio.outputChunkCount > 0 && player->audio.sl.play) {
		SLresult result = (*player->audio.sl.play)->SetPlayState(player->audio.sl.play,
				SL_PLAYSTATE_PLAYING);
		if (result == SL_RESULT_SUCCESS) {
			player->audio.outputRestartPending = 0;
		}
		diagnosticsLog("player=%u audio_output resume_after_reset result=%d depth=%d"
				" restart_pending=%d", player->meta.diagnosticsId, (int) result,
				player->audio.outputChunkCount, player->audio.outputRestartPending);
	}
	return enqueued;
}

static void audioPlayerCallback(SLAndroidSimpleBufferQueueItf slQueue, void * context) {
	Player * player = (Player *) context;
	if (player->meta.interrupt) {
		return;
	}
	LOG("audio callback");
	pthread_mutex_lock(&player->audio.sleepBufferMutex);
	if (playerGetSkipFlag(&player->sync.skip.audioWorkFrame)) {
		pthread_mutex_unlock(&player->audio.sleepBufferMutex);
		return;
	}
	SLAndroidSimpleBufferQueueState queueState = {0};
	SLresult stateResult = (*slQueue)->GetState(slQueue, &queueState);
	int completedChunk = stateResult != SL_RESULT_SUCCESS ||
			queueState.count < (SLuint32) player->audio.outputChunkCount;
	int64_t endAudioPosition = completedChunk ? completeAudioChunkLocked(player) : -1;
	if (!completedChunk) {
		// STOPPED and Clear() are both allowed to trigger a callback. If fresh
		// buffers won the mutex race, GetState still reports all tracked chunks;
		// do not consume one of the new owners as if an old buffer had finished.
		diagnosticsLog("player=%u audio_output callback_without_completion depth=%d"
				" native_depth=%u", player->meta.diagnosticsId,
				player->audio.outputChunkCount, (unsigned int) queueState.count);
	}
	int result = playerAudioEnqueueBuffer(player);
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
	playerMarkStreamFinished(player, 0);
}

static int queueDecodedAudio(Player * player, uint8_t * buffer, int size,
		int frameSize, int64_t position, int64_t divider, int * silentAudioLength) {
	if (!buffer || size <= 0 || divider <= 0) {
		return 0;
	}
	playerSetDiagnosticsAudioStage(player, DIAGNOSTICS_AUDIO_STAGE_WAIT_SLEEP_BUFFER_MUTEX);
	pthread_mutex_lock(&player->audio.sleepBufferMutex);
	playerSetDiagnosticsAudioStage(player, DIAGNOSTICS_AUDIO_STAGE_QUEUE_BUFFER);
	if (player->meta.interrupt || playerGetSkipFlag(&player->sync.skip.audioWorkFrame)) {
		pthread_mutex_unlock(&player->audio.sleepBufferMutex);
		playerSetDiagnosticsAudioStage(player, DIAGNOSTICS_AUDIO_STAGE_IDLE);
		return 0;
	}
	while (!player->meta.interrupt && !playerGetSkipFlag(&player->sync.skip.audioWorkFrame) &&
			blockingQueueCount(&player->audio.bufferQueue) >= 5) {
		pthread_cond_wait(&player->audio.bufferCond, &player->audio.sleepBufferMutex);
	}
	if (player->meta.interrupt || playerGetSkipFlag(&player->sync.skip.audioWorkFrame)) {
		pthread_mutex_unlock(&player->audio.sleepBufferMutex);
		playerSetDiagnosticsAudioStage(player, DIAGNOSTICS_AUDIO_STAGE_IDLE);
		return 0;
	}
	int waitedForVideo = player->sync.videoPositionNotSync;
	while (!player->meta.interrupt && !playerGetSkipFlag(&player->sync.skip.audioWorkFrame)
			&& player->sync.videoPositionNotSync) {
		pthread_cond_wait(&player->audio.sleepCond, &player->audio.sleepBufferMutex);
	}
	if (player->meta.interrupt || playerGetSkipFlag(&player->sync.skip.audioWorkFrame)) {
		pthread_mutex_unlock(&player->audio.sleepBufferMutex);
		playerSetDiagnosticsAudioStage(player, DIAGNOSTICS_AUDIO_STAGE_IDLE);
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
		playerSetDiagnosticsAudioStage(player, DIAGNOSTICS_AUDIO_STAGE_IDLE);
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
		playerAudioEnqueueBuffer(player);
	}
	pthread_mutex_unlock(&player->audio.sleepBufferMutex);
	playerSetDiagnosticsAudioStage(player, DIAGNOSTICS_AUDIO_STAGE_IDLE);
	return 1;
}

#ifdef DASHCHAN_HAS_ATEMPO
static int drainTempoProcessor(Player * player, TempoProcessor * processor,
		int sampleRate, int channels, int speed, int64_t startPosition,
		int64_t * outputSamples, int * silentAudioLength) {
	while (!player->meta.interrupt && !playerGetSkipFlag(&player->sync.skip.audioWorkFrame)) {
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
		int64_t divider = (int64_t) av_get_bytes_per_sample(AV_SAMPLE_FMT_S16) * channels *
				getPlaybackSampleRateForSpeed(sampleRate, speed);
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

void * playerAudioDecodeThread(void * data) {
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
		if (playerGetSkipFlag(&player->sync.skip.audioWorkFrame)) {
			playerSetSkipFlag(&player->sync.skip.audioWorkFrame, 0);
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

		int endOfStream = packetHolder->type == PACKET_HOLDER_END_OF_STREAM;
		int packetSent = 0;
		while (1) {
			int success = 0;
			uint8_t ** dstData = NULL;
#if USE_AV_CHANNEL_LAYOUT
			AVChannelLayout srcChannelLayout = {0};
			AVChannelLayout dstChannelLayout = {0};
			int channelLayoutsInitialized = 0;
#endif
			if (playerGetSkipFlag(&player->sync.skip.audioWorkFrame)) {
				goto SKIP_AUDIO_FRAME;
			}
			playerSetDiagnosticsAudioStage(player, DIAGNOSTICS_AUDIO_STAGE_WAIT_FRAME_MUTEX);
			pthread_mutex_lock(&player->decode.audio.frameMutex);
			if (playerGetSkipFlag(&player->sync.skip.audioWorkFrame)) {
				playerSetDiagnosticsAudioStage(player, DIAGNOSTICS_AUDIO_STAGE_IDLE);
				UNLOCK_AND_GOTO(&player->decode.audio.frameMutex, SKIP_AUDIO_FRAME);
			}
			playerSetDiagnosticsAudioStage(player, DIAGNOSTICS_AUDIO_STAGE_DECODE_FRAME);
			int ready = playerDecodeFrame(context, packetHolder->packet, frame, &packetSent);
			pthread_mutex_unlock(&player->decode.audio.frameMutex);
			playerSetDiagnosticsAudioStage(player, DIAGNOSTICS_AUDIO_STAGE_IDLE);
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
					srcChannels = playerAudioGetContextChannels(context);
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
						|| playerGetSkipFlag(&player->sync.skip.audioWorkFrame)) {
					goto SKIP_AUDIO_FRAME;
				}
				int dstSamples = av_rescale_rnd(srcSamples, dstSampleRate, srcSampleRate, AV_ROUND_UP);
				int result = av_samples_alloc_array_and_samples(&dstData, frame->linesize, dstChannels,
						dstSamples, dstFormat, 0);
				if (result < 0 || playerGetSkipFlag(&player->sync.skip.audioWorkFrame)) {
					goto SKIP_AUDIO_FRAME;
				}
				dstSamples = av_rescale_rnd(swr_get_delay(resampleContext, srcSampleRate) + srcSamples,
						dstSampleRate, srcSampleRate, AV_ROUND_UP);
				result = swr_convert(resampleContext, dstData, dstSamples, (const uint8_t **) frame->data, srcSamples);
				if (result < 0 || playerGetSkipFlag(&player->sync.skip.audioWorkFrame)) {
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
					int64_t divider = (int64_t) av_get_bytes_per_sample(dstFormat) * dstChannels *
							getPlaybackSampleRateForSpeed(dstSampleRate, playbackSpeed);
					int frameSize = av_get_bytes_per_sample(dstFormat) * dstChannels;
					if (queueDecodedAudio(player, dstData[0], size, frameSize,
							position, divider, &silentAudioLength)) {
						dstData[0] = NULL;
						success = 1;
					}
				}
#else
				int64_t divider = (int64_t) av_get_bytes_per_sample(dstFormat) * dstChannels * dstSampleRate;
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
		playerPacketQueueFreeCallback(packetHolder);
		packetHolder = NULL;
		if (endOfStream) {
			// OpenSL normally performs the final finished-state check from its
			// completion callback. After a seek beyond a shorter audio track there
			// is no PCM to submit, therefore no callback will arrive. Re-evaluate
			// the stream directly after draining the decoder so video can fall back
			// to the monotonic clock instead of waiting on a silent audio master.
			playerMarkStreamFinished(player, 0);
			diagnosticsLog("player=%u audio_end_of_stream finished=%d"
					" packet_queue=%d decoded_queue=%d output_chunks=%d",
					player->meta.diagnosticsId, player->audio.finished,
					blockingQueueCount(&player->audio.packetQueue),
					blockingQueueCount(&player->audio.bufferQueue),
					player->audio.outputChunkCount);
		}
	}
	if (packetHolder) {
		playerPacketQueueFreeCallback(packetHolder);
	}
#ifdef DASHCHAN_HAS_ATEMPO
	tempoProcessorFree(&tempoProcessor);
#endif
	swr_free(&resampleContext);
	av_frame_free(&frame);
	return NULL;
}


int playerAudioInitialize(Player * player, AVStream * audioStream) {
	SLresult result;
	int success = 0;
	int sourceChannels = playerAudioGetContextChannels(player->av.audioContext);
	int channels = sourceChannels;
	int streamChannels = playerAudioGetCodecParametersChannels(audioStream->codecpar);
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
		playerCloseAndFreeCodecContext(&player->av.audioContext);
		player->av.audioContext = NULL;
		player->av.audioStreamIndex = INDEX_NO_STREAM;
	}
	return success;
}
void playerAudioInitializeLibrary(void) {
	SLObjectItf engineObject;
	slCreateEngine(&engineObject, 0, NULL, 0, NULL, NULL);
	(*engineObject)->Realize(engineObject, SL_BOOLEAN_FALSE);
	(*engineObject)->GetInterface(engineObject, SL_IID_ENGINE, &slEngine);
}
void playerAudioDestroy(Player * player) {
	if (player->audio.sl.player) {
		playerLogDestroyStage(player, "opensl_player_destroy_started");
		(*player->audio.sl.player)->Destroy(player->audio.sl.player);
		playerLogDestroyStage(player, "opensl_player_destroy_finished");
	}
	if (player->audio.sl.outputMix) {
		playerLogDestroyStage(player, "opensl_output_mix_destroy_started");
		(*player->audio.sl.outputMix)->Destroy(player->audio.sl.outputMix);
		playerLogDestroyStage(player, "opensl_output_mix_destroy_finished");
	}
	if (HAS_STREAM(player, audio)) {
		playerLogDestroyStage(player, "audio_codec_close_started");
		playerCloseAndFreeCodecContext(&player->av.audioContext);
		playerLogDestroyStage(player, "audio_codec_close_finished");
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
	(*player->audio.sl.play)->SetPlayState(player->audio.sl.play, SL_PLAYSTATE_STOPPED);
	playerAudioClearOutputLocked(player, 0);
	pthread_mutex_unlock(&player->audio.sleepBufferMutex);
	diagnosticsLog("player=%u audio_stop_immediate finished", player->meta.diagnosticsId);
}

