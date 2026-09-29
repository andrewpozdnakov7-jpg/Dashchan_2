#ifndef PLAYER_VIDEO_POLICY_H
#define PLAYER_VIDEO_POLICY_H

#include <stdint.h>

// Wall-clock budgets, independent of playback speed. No Android/FFmpeg dependencies.
#define HARDWARE_OUTPUT_CAPACITY 2
#define HARDWARE_OUTPUT_FPS 60
#define HARDWARE_CATCHUP_LATE_MS 250
#define HARDWARE_CATCHUP_PERSIST_MS 300
#define HARDWARE_CATCHUP_COOLDOWN_MS 1000

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
