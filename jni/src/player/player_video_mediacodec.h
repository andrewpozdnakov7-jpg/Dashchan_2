#ifndef PLAYER_VIDEO_MEDIACODEC_H
#define PLAYER_VIDEO_MEDIACODEC_H

#include "player_internal.h"

int playerVideoHasPendingSurface(Player * player);
void playerVideoApplyPendingSurface(Player * player, JNIEnv * env);
void playerVideoReleaseSurface(Player * player);
#ifdef DASHCHAN_HAS_MEDIACODEC
void playerVideoDecodeMediaCodec(Player * player, JNIEnv * env, AVStream * stream);
int playerVideoFallbackMediaCodecToSoftware(Player * player);
#endif

#endif // PLAYER_VIDEO_MEDIACODEC_H
