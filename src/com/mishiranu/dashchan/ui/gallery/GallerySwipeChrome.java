package com.mishiranu.dashchan.ui.gallery;

import android.view.View;
import com.mishiranu.dashchan.R;
import java.util.IdentityHashMap;

/** Swipe presentation only: never shows hidden controls or changes their layout. */
final class GallerySwipeChrome {
	private static final class Frame {
		final float alpha;
		float applied;
		Frame(View view) { alpha = applied = view.getAlpha(); }
	}
	private final IdentityHashMap<View, Frame> frames = new IdentityHashMap<>();

	void fade(float amount, View... views) {
		amount = Math.max(0f, Math.min(1f, amount));
		if (amount == 1f) { reset(); return; }
		for (View view : views) {
			if (view == null || view.getTag(R.id.gallery_motion_owner) != null) continue;
			Frame frame = frames.get(view);
			if (frame == null) {
				frame = new Frame(view); frames.put(view, frame);
			} else if (view.getAlpha() != frame.applied) {
				// Another presentation took ownership; do not overwrite it.
				continue;
			}
			frame.applied = frame.alpha * amount;
			view.setAlpha(frame.applied);
		}
	}

	void reset() {
		frames.forEach((view, frame) -> {
			if (view.getTag(R.id.gallery_motion_owner) == null && view.getAlpha() == frame.applied) {
				view.setAlpha(frame.alpha);
			}
		});
		frames.clear();
	}
}
