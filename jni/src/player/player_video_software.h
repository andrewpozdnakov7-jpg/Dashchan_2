#ifndef PLAYER_VIDEO_SOFTWARE_H
#define PLAYER_VIDEO_SOFTWARE_H

#include "player_internal.h"

void * playerVideoDrawThread(void * data);
void * playerVideoDecodeThread(void * data);
void playerVideoBufferQueueFreeCallback(BufferItem * bufferItem);
int playerVideoSoftwareGetFormat(int windowFormat);
int playerVideoSoftwarePrepareOutputLocked(Player * player);
#ifdef DASHCHAN_HAS_MEDIACODEC
AVCodecContext * playerVideoSoftwareCreateCodecContext(Player * player);
#endif
void playerVideoSoftwareResetGovernorLocked(Player * player, AVCodecContext * context,
		const char * reason);
void playerVideoSoftwareRestoreSeekFastLocked(Player * player, AVCodecContext * context,
		const char * reason, int64_t packetPosition);
void playerVideoSoftwareStartSeekFastLocked(Player * player, AVCodecContext * context,
		int64_t keyframePosition, int64_t targetPosition);

#endif // PLAYER_VIDEO_SOFTWARE_H
