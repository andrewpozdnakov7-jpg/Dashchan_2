#ifndef PLAYER_DEMUX_H
#define PLAYER_DEMUX_H

#include "player_internal.h"

#include <jni.h>
#include <stdint.h>

void playerDemuxRequestSeekWorkersStop(Player * player);
void playerDemuxJoinWorkers(Player * player);
PacketHolder * playerDemuxCreatePacketHolder(int allocPacket);
void * playerDemuxRun(void * data);
int playerDemuxRead(void * opaque, uint8_t * buf, int bufSize);
int64_t playerDemuxSeek(void * opaque, int64_t offset, int whence);
void playerDemuxSetRange(Player * player, int64_t start, int64_t end, int64_t total);
void playerDemuxSetCancelSeek(Player * player, int cancelSeek);
// Caller holds readMutex and flowMutex, before clearing queues for a seek.
void playerDemuxResetBuffering(Player * player, Bridge * bridge);

#endif // PLAYER_DEMUX_H
