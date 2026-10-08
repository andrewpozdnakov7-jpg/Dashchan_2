package com.mishiranu.dashchan.ui;

import android.animation.Animator;
import android.view.View;
import android.view.ViewGroup;
import com.mishiranu.dashchan.widget.ThreadMotionLayout;

/** F-Droid navigation contract: screen/chrome motion is not included in this distribution. */
public final class ScreenMotionController {
	public ScreenMotionController(ThreadMotionLayout host, ViewGroup chromeHost, View toolbar, View toolbarExtra) {}
	public void finish() {}
	public Session prepare(ContentFragment outgoing, ContentFragment incoming, int transition, boolean container) {
		return null;
	}

	public static final class Session {
		private Session() {}
		public void bind(View view) {}
		public Animator createAnimator(Animator primary) { return primary; }
		public void finish() {}
	}
}
