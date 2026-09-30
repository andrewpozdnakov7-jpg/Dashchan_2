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
	checkCadence(4000);
	assert(videoSoftwareThreadCount(0) == 1);
	assert(videoSoftwareThreadCount(1) == 1);
	assert(videoSoftwareThreadCount(8) == 8);
	assert(videoSoftwareThreadCount(64) == 8);
	VideoLoadControl c = {0};
	videoLoadReset(&c, 4000);
	assert(c.decodedPosition == -1 && c.outputSlot == -1);
	// 1000 ms of media at 4x is only 250 ms of real lateness.
	videoLoadObserve(&c, 4000, 1000, 1000);
	videoLoadObserve(&c, 4000, 1000, 2000);
	assert(c.state == VIDEO_LOAD_NORMAL);
	videoLoadObserve(&c, 4000, 1004, 2100);
	videoLoadObserve(&c, 4000, 1200, 2399);
	assert(c.state == VIDEO_LOAD_NORMAL);
	videoLoadObserve(&c, 4000, 1200, 2400);
	assert(c.state == VIDEO_LOAD_CATCHUP);
	// Rate reduction preserves actual lag, but old timing evidence must not stick.
	c.outputSlot = 100;
	videoLoadObserve(&c, 1000, 300, 2500);
	assert(c.state == VIDEO_LOAD_NORMAL && c.lateSince == 2500 && c.outputSlot == -1);
	videoLoadObserve(&c, 1000, 300, 2800);
	assert(c.state == VIDEO_LOAD_CATCHUP);
	videoLoadObserve(&c, 1000, 70, 2900);
	assert(c.state == VIDEO_LOAD_RECOVERING);
	videoLoadObserve(&c, 1000, 70, 3399);
	assert(c.state == VIDEO_LOAD_RECOVERING);
	videoLoadObserve(&c, 1000, 70, 3400);
	assert(c.state == VIDEO_LOAD_NORMAL);
	// A brief recovery must not permanently disable overload handling.
	videoLoadObserve(&c, 1000, 500, 3500);
	videoLoadObserve(&c, 1000, 500, 3800);
	videoLoadObserve(&c, 1000, 40, 3900);
	videoLoadObserve(&c, 1000, 150, 4100);
	assert(c.state == VIDEO_LOAD_CATCHUP && c.stableSince == 0);
	videoLoadReset(&c, 1250); // pause/seek resets evidence, not the media clock
	assert(c.state == VIDEO_LOAD_NORMAL && c.lateSince == 0 && c.lastCatchup == 0);
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
