// Standalone host C test; no Android or FFmpeg dependency. Not part of the APK.
#include "../src/player/player_video_policy.h"
#include <assert.h>
#include <stdio.h>

static void checkCadence(int speed) {
	int64_t last = -1;
	int outputs = 0;
	// 60 FPS media at 1.25x/1.5x/2x must not request 75/90/120 display slots.
	int frames = 60 * speed / 1000;
	for (int i = 0; i < frames; i++) {
		int64_t slot = hardwareOutputSlot((int64_t) i * 1000 / 60, speed);
		if (slot != last) { outputs++; last = slot; }
	}
	assert(outputs == 60);
}

int main(void) {
	checkCadence(1250);
	checkCadence(1500);
	checkCadence(2000);
	assert(hardwareOutputSlot(-1, 1500) == -1);
	assert(hardwareOutputSlot(100, 0) == -1);
	// Transient lateness is not a codec reset; thresholds are in wall time.
	assert(!hardwareCatchupDue(376, 1500, 1000, 0, 1299));
	assert(!hardwareCatchupDue(375, 1500, 1000, 0, 1300));
	assert(hardwareCatchupDue(376, 1500, 1000, 0, 1300));
	assert(!hardwareCatchupDue(500, 2000, 1000, 0, 1300));
	assert(hardwareCatchupDue(501, 2000, 1000, 0, 1300));
	assert(!hardwareCatchupDue(1000, 2000, 1000, 1200, 2199));
	assert(hardwareCatchupDue(1000, 2000, 1000, 1200, 2200));
	assert(!hardwareCatchupDue(1000, 2000, 0, 0, 2200));
	// A prefix may be discarded only for a later keyframe already behind the clock.
	assert(!hardwareCatchupKeyEligible(-1, 1000, 2000, 1500));
	assert(!hardwareCatchupKeyEligible(900, 1000, 2000, 1500));
	assert(!hardwareCatchupKeyEligible(1149, 1000, 2000, 1500));
	assert(hardwareCatchupKeyEligible(1150, 1000, 2000, 1500));
	assert(hardwareCatchupKeyEligible(2000, 1000, 2000, 1500));
	assert(!hardwareCatchupKeyEligible(2001, 1000, 2000, 1500));
	puts("player_video_policy: PASS");
	return 0;
}
