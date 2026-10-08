package com.mishiranu.dashchan.ui;

import android.animation.Animator;
import android.view.View;
import com.mishiranu.dashchan.util.ThreadMotionKey;
import com.mishiranu.dashchan.widget.ThreadMotionLayout;

/** Legacy navigation contract; no snapshots, overlay, gesture interception or animation in F-Droid. */
public final class ThreadMotionController {
	public static final class Opening {
		public Opening(String sourceRetainId, ThreadMotionKey key, View card) {}
	}

	public ThreadMotionController(ThreadMotionLayout host) {}
	public void finish() {}
	public void clear() {}
	public Session prepare(ContentFragment outgoing, ContentFragment incoming, int transition, Opening opening) {
		return null;
	}

	public static final class Session {
		private Session() {}
		public void bind(View view) {}
		public Animator createAnimator() { return null; }
		public void finish() {}
	}
}
