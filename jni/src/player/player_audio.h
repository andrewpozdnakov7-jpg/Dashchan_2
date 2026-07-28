#ifndef PLAYER_AUDIO_H
#define PLAYER_AUDIO_H

#include "player_internal.h"

int playerAudioGetContextChannels(const AVCodecContext * context);
int playerAudioGetCodecParametersChannels(const AVCodecParameters * parameters);
int playerAudioInitialize(Player * player, AVStream * stream);
void playerAudioInitializeLibrary(void);
void * playerAudioDecodeThread(void * data);
void playerAudioClearOutputLocked(Player * player, int clearDecodedBuffers);
int playerAudioEnqueueBuffer(Player * player);
void playerAudioBufferQueueFreeCallback(void * data);
void playerAudioDestroy(Player * player);

#endif // PLAYER_AUDIO_H
