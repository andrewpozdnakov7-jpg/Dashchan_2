#include "player_duration.h"
#include "player_diagnostics.h"
#include "util.h"

#include <inttypes.h>
#include <limits.h>
#include <stdio.h>
#include <unistd.h>

#include <libavutil/mathematics.h>
#include <libavutil/mem.h>

typedef struct {
	Player * player;
	int64_t offset;
	int64_t total;
} DurationProbeIO;

int64_t getFormatDurationMs(AVFormatContext * formatContext) {
	return formatContext->duration != AV_NOPTS_VALUE && formatContext->duration > 0
			? formatContext->duration / 1000 : 0;
}

int64_t getStreamDurationMs(AVStream * stream) {
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
		playerNotifyDurationChanged(player, observedDuration);
	}
	return NULL;
}

void maybeStartDurationProbeLocked(Player * player) {
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
