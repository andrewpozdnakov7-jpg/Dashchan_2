#include "player_seek.h"
#include "player_audio.h"
#include "player_demux.h"
#include "player_diagnostics.h"
#include "player_timing.h"
#include "player_video_software.h"

#include <libavformat/avformat.h>

#include <inttypes.h>
#include <stdint.h>

#define SEEK_KEYFRAME_DISCOVERY_THRESHOLD_MS 3000

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

void playerSeekSetPosition(JNIEnv * env, Player * player, int64_t position) {
	if (position < 0) {
		return;
	}
	int64_t originalPosition = position;
	int64_t duration = max64(__atomic_load_n(&player->av.duration.effectiveMs, __ATOMIC_ACQUIRE), 0);
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
	Bridge * bridge = playerObtainBridge(player, env);
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
	playerDemuxRequestSeekWorkersStop(player);
	diagnosticsLogSeekLock(player, "prepare", "audio.frame", "waiting");
	pthread_mutex_lock(&player->decode.audio.frameMutex);
	diagnosticsLogSeekLock(player, "prepare", "audio.frame", "acquired");
	diagnosticsLogSeekLock(player, "prepare", "video.frame", "waiting");
	pthread_mutex_lock(&player->decode.video.frameMutex);
	diagnosticsLogSeekLock(player, "prepare", "video.frame", "acquired");
	playerSetSkipFlag(&player->sync.skip.audioWorkFrame, 1);
	playerSetSkipFlag(&player->sync.skip.videoWorkFrame, 1);
	diagnosticsLog("player=%u seek_phase=packet_queues_clear_started",
			player->meta.diagnosticsId);
	blockingQueueClear(&player->audio.packetQueue, playerPacketQueueFreeCallback);
	blockingQueueClear(&player->video.packetQueue, playerPacketQueueFreeCallback);
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
	playerAudioPrepareOutputResetLocked(player, 1, "seek");
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
	playerSetSkipFlag(&player->sync.skip.drawWorkFrame, 1);
	if (player->video.bufferQueue) {
		bufferQueueClear(player->video.bufferQueue, playerVideoBufferQueueFreeCallback);
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
			playerVideoSoftwareRestoreSeekFastLocked(player, GET_CONTEXT(player, video),
					"seek_prepare", -1);
			playerVideoSoftwareResetGovernorLocked(player, GET_CONTEXT(player, video),
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
		playerVideoSoftwareStartSeekFastLocked(player, GET_CONTEXT(player, video),
				keyframePosition, position);
	}
	playerSetSkipFlag(&player->sync.skip.audioWorkFrame, 1);
	playerSetSkipFlag(&player->sync.skip.videoWorkFrame, 1);
	playerSetSkipFlag(&player->sync.skip.drawWorkFrame, 1);
	player->sync.lastDrawTimes[0] = 0;
	player->sync.lastDrawTimes[1] = 0;
	if (primedVideoPacket) {
		PacketHolder * packetHolder = playerDemuxCreatePacketHolder(0);
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
		PLAYER_SEND_MESSAGE(env, player, bridge, BRIDGE_MESSAGE_START_SEEKING);
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
