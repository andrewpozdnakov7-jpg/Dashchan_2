#ifndef PLAYER_DURATION_H
#define PLAYER_DURATION_H

#include "player_internal.h"

#define DURATION_PROBE_TOLERANCE_MS 1000

int64_t getFormatDurationMs(AVFormatContext * formatContext);
int64_t getStreamDurationMs(AVStream * stream);
void maybeStartDurationProbeLocked(Player * player);

#endif // PLAYER_DURATION_H
