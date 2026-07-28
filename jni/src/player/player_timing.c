#include "player_timing.h"
#include "util.h"

#include <limits.h>
#include <math.h>
#include <time.h>

#include <libavutil/mathematics.h>

#define SOFTWARE_OUTPUT_MAX_LONG_SIDE 1920
#define SOFTWARE_OUTPUT_MAX_PIXELS (1920 * 1080)
#define SOFTWARE_OUTPUT_FALLBACK_MAX_LONG_SIDE 1280
#define SOFTWARE_OUTPUT_FALLBACK_MAX_PIXELS (1280 * 720)

int getBytesPerPixel(int videoFormat) {
	switch (videoFormat) {
		case AV_PIX_FMT_YUV420P: return 1;
		case AV_PIX_FMT_RGBA: return 4;
		case AV_PIX_FMT_RGB565LE: return 2;
	}
	return 0;
}

int64_t getTimestampPositionMs(Player * player, int64_t timestamp, AVRational timeBase) {
	if (timestamp == AV_NOPTS_VALUE) {
		return -1;
	}
	AVRational msTimeBase = {1, 1000};
	int64_t position = av_rescale_q(timestamp, timeBase, msTimeBase) - player->av.timelineOffsetMs;
	return max64(position, 0);
}

int64_t getFramePositionMs(Player * player, AVFrame * frame, AVStream * stream) {
	int64_t timestamp = frame->best_effort_timestamp;
	if (timestamp == AV_NOPTS_VALUE) {
		timestamp = frame->pts;
	}
	if (timestamp == AV_NOPTS_VALUE) {
		timestamp = frame->pkt_dts;
	}
	return getTimestampPositionMs(player, timestamp, stream->time_base);
}

int clampPlaybackSpeed(int speed) {
	if (speed < PLAYBACK_SPEED_MIN) {
		return PLAYBACK_SPEED_MIN;
	}
	if (speed > PLAYBACK_SPEED_MAX) {
		return PLAYBACK_SPEED_MAX;
	}
	return speed;
}

int getPlaybackSpeed(Player * player) {
	int speed = player->sync.playbackSpeed;
	return speed > 0 ? speed : PLAYBACK_SPEED_DEFAULT;
}

int64_t scalePlaybackElapsed(Player * player, int64_t elapsed) {
	return elapsed * getPlaybackSpeed(player) / PLAYBACK_SPEED_DEFAULT;
}

int64_t unscalePlaybackPosition(Player * player, int64_t position) {
	return position * PLAYBACK_SPEED_DEFAULT / getPlaybackSpeed(player);
}

int getPlaybackSampleRateForSpeed(int sampleRate, int speed) {
	int result = (int) (sampleRate * (int64_t) PLAYBACK_SPEED_DEFAULT / speed);
	return result > 0 ? result : 1;
}

#ifndef DASHCHAN_HAS_ATEMPO
int getPlaybackSampleRate(Player * player, int sampleRate) {
	return getPlaybackSampleRateForSpeed(sampleRate, getPlaybackSpeed(player));
}
#endif

void updateAudioPositionSurrogate(Player * player, int64_t position, int forceUpdate) {
	if (forceUpdate || player->sync.audioPositionNotSync) {
		player->sync.startTime = getTime() - unscalePlaybackPosition(player, position);
		if ((!HAS_STREAM(player, audio) || player->audio.finished) && !forceUpdate) {
			player->sync.audioPositionNotSync = 0;
		}
	}
}

int64_t calculatePosition(Player * player, int mayCalculateStartTime) {
	if (!HAS_STREAM(player, audio) || player->audio.finished) {
		if (player->play.playing) {
			if (mayCalculateStartTime || !player->video.finished) {
				return scalePlaybackElapsed(player, getTime() - player->sync.startTime);
			}
			return player->sync.videoPosition;
		}
		return player->sync.pausedPosition;
	}
	return player->sync.audioPosition;
}

int64_t calculateFrameTime(Player * player, int64_t waitTime) {
	int64_t scaledWaitTime = unscalePlaybackPosition(player, waitTime);
	return getTime() + scaledWaitTime - min64(max64(scaledWaitTime / 2, 25), 100);
}

#ifdef DASHCHAN_HAS_MEDIACODEC
int64_t getMonotonicTimeNs(void) {
	struct timespec time;
	clock_gettime(CLOCK_MONOTONIC, &time);
	return (int64_t) time.tv_sec * 1000000000LL + time.tv_nsec;
}
#endif

int getVideoBufferSize(int videoFormat, int width, int height) {
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

void calculateSoftwareOutputSize(Player * player, int sourceWidth, int sourceHeight,
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
	if (width > 1) {
		width &= ~1;
	}
	if (height > 1) {
		height &= ~1;
	}
	*outputWidth = width;
	*outputHeight = height;
}
