package com.mishiranu.dashchan.widget;

import android.view.View;
import android.view.ViewTreeObserver;

/** Keep enabled-state current before the system chooses a callback, including IME/focus/layout changes. */
public final class BackCallbackPolicy implements AutoCloseable, View.OnAttachStateChangeListener {
	private final View host;
	private final Runnable update;
	private ViewTreeObserver observer;
	private final ViewTreeObserver.OnPreDrawListener draw = () -> { update(); return true; };
	public BackCallbackPolicy(View host, Runnable update) {
		this.host = host; this.update = update; host.addOnAttachStateChangeListener(this);
		if (host.isAttachedToWindow()) onViewAttachedToWindow(host);
		update();
	}
	private void update() { update.run(); }
	@Override public void onViewAttachedToWindow(View view) {
		observer = host.getViewTreeObserver(); observer.addOnPreDrawListener(draw); update();
	}
	@Override public void onViewDetachedFromWindow(View view) {
		if (observer != null && observer.isAlive()) observer.removeOnPreDrawListener(draw); observer = null;
	}
	@Override public void close() { onViewDetachedFromWindow(host); host.removeOnAttachStateChangeListener(this); }
}
