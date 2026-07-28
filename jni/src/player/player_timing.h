#ifndef PLAYER_TIMING_H
#define PLAYER_TIMING_H

#include "player_internal.h"

int getBytesPerPixel(int videoFormat);
int64_t getTimestampPositionMs(Player * player, int64_t timestamp, AVRational timeBase);
int64_t getFramePositionMs(Player * player, AVFrame * frame, AVStream * stream);
int clampPlaybackSpeed(int speed);
int getPlaybackSpeed(Player * player);
int64_t scalePlaybackElapsed(Player * player, int64_t elapsed);
int64_t unscalePlaybackPosition(Player * player, int64_t position);
int getPlaybackSampleRateForSpeed(int sampleRate, int speed);
#ifndef DASHCHAN_HAS_ATEMPO
int getPlaybackSampleRate(Player * player, int sampleRate);
#endif
void updateAudioPositionSurrogate(Player * player, int64_t position, int forceUpdate);
int64_t calculatePosition(Player * player, int mayCalculateStartTime);
int64_t calculateFrameTime(Player * player, int64_t waitTime);
#ifdef DASHCHAN_HAS_MEDIACODEC
int64_t getMonotonicTimeNs(void);
#endif
int getVideoBufferSize(int videoFormat, int width, int height);
void calculateSoftwareOutputSize(Player * player, int sourceWidth, int sourceHeight,
		int * outputWidth, int * outputHeight);

#endif // PLAYER_TIMING_H
