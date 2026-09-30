#ifndef PLAYER_VIDEO_POLICY_H
#define PLAYER_VIDEO_POLICY_H

#include <stdint.h>

// Wall-clock budgets, independent of playback speed. No Android/FFmpeg dependencies.
#define HARDWARE_OUTPUT_CAPACITY 2
#define HARDWARE_OUTPUT_FPS 60
#define HARDWARE_CATCHUP_LATE_MS 250
#define HARDWARE_CATCHUP_PERSIST_MS 300
#define HARDWARE_CATCHUP_COOLDOWN_MS 1000

// One software overload policy, measured in real time (not media milliseconds).
enum { VIDEO_LOAD_NORMAL, VIDEO_LOAD_CATCHUP, VIDEO_LOAD_RECOVERING };
#define VIDEO_LOAD_ENTER_MS 250
#define VIDEO_LOAD_ENTER_PERSIST_MS 300
#define VIDEO_LOAD_RECOVER_MS 80
#define VIDEO_LOAD_STABLE_MS 500

typedef struct VideoLoadControl {
	int state;
	int speed;
	int64_t lateSince;
	int64_t stableSince;
	int64_t lastCatchup;
	int64_t scanAt;
	int64_t decodedPosition;
	int64_t outputSlot;
	int64_t lateWallMs;
	int64_t stateSince;
	int64_t reportAt;
	uint64_t catchups;
	uint64_t keyWaits;
	uint64_t skippedPackets;
	uint64_t cadenceDrops;
} VideoLoadControl;

static inline int videoSoftwareThreadCount(int cpuCount) {
	return cpuCount < 1 ? 1 : cpuCount > 8 ? 8 : cpuCount;
}

static inline void videoLoadReset(VideoLoadControl * c, int speed) {
	c->state = VIDEO_LOAD_NORMAL;
	c->speed = speed;
	c->lateSince = c->stableSince = c->lastCatchup = c->scanAt = 0;
	c->decodedPosition = c->outputSlot = -1;
	c->lateWallMs = 0;
	c->stateSince = c->reportAt = 0;
}

static inline void videoLoadObserve(VideoLoadControl * c, int speed, int64_t lateMediaMs, int64_t now) {
	if (c->speed != speed) videoLoadReset(c, speed);
	c->lateWallMs = speed > 0 ? lateMediaMs * 1000 / speed : 0;
	if (c->lateWallMs > VIDEO_LOAD_ENTER_MS) {
		c->stableSince = 0;
		if (!c->lateSince) c->lateSince = now;
		if (now - c->lateSince >= VIDEO_LOAD_ENTER_PERSIST_MS) c->state = VIDEO_LOAD_CATCHUP;
	} else {
		c->lateSince = 0;
		if (c->state != VIDEO_LOAD_NORMAL) {
			if (c->lateWallMs <= VIDEO_LOAD_RECOVER_MS) {
				if (!c->stableSince) c->stableSince = now;
				c->state = VIDEO_LOAD_RECOVERING;
				if (now - c->stableSince >= VIDEO_LOAD_STABLE_MS) c->state = VIDEO_LOAD_NORMAL;
			} else {
				c->stableSince = 0;
				c->state = VIDEO_LOAD_CATCHUP;
			}
		}
	}
}

static inline int64_t hardwareOutputSlot(int64_t positionMs, int speed) {
	// In milliseconds the speed scale (1000) cancels the milliseconds-per-second.
	return positionMs < 0 || speed <= 0 ? -1 : positionMs * HARDWARE_OUTPUT_FPS / speed;
}

static inline int hardwareCatchupDue(int64_t lateMediaMs, int speed,
		int64_t lateSinceMs, int64_t lastCatchupMs, int64_t nowMs) {
	return speed > 0 && lateMediaMs > (int64_t) HARDWARE_CATCHUP_LATE_MS * speed / 1000
			&& lateSinceMs > 0 && nowMs - lateSinceMs >= HARDWARE_CATCHUP_PERSIST_MS
			&& (lastCatchupMs == 0 || nowMs - lastCatchupMs >= HARDWARE_CATCHUP_COOLDOWN_MS);
}

static inline int hardwareCatchupKeyEligible(int64_t keyMs, int64_t decodedMs, int64_t clockMs, int speed) {
	// Never jump into the future or flush just to restart at the same/older GOP.
	return keyMs >= 0 && speed > 0 && keyMs <= clockMs
			&& keyMs - decodedMs >= (int64_t) 100 * speed / 1000;
}

#endif
