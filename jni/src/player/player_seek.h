#ifndef PLAYER_SEEK_H
#define PLAYER_SEEK_H

#include "player_internal.h"

#include <jni.h>

void playerSeekSetPosition(JNIEnv * env, Player * player, int64_t position);

#endif // PLAYER_SEEK_H
