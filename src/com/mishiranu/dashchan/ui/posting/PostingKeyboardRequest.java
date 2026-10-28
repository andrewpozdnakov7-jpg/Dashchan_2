package com.mishiranu.dashchan.ui.posting;

import android.view.View;
import android.view.ViewTreeObserver;
import android.view.Window;
import android.widget.EditText;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowCompat;
import androidx.core.view.WindowInsetsCompat;
import com.mishiranu.dashchan.util.InterfaceMotion;
import java.util.function.BooleanSupplier;

/** One view-lifetime IME request. Never steals focus or forces the keyboard to stay open. */
final class PostingKeyboardRequest implements ViewTreeObserver.OnPreDrawListener,
		ViewTreeObserver.OnWindowFocusChangeListener, ViewTreeObserver.OnGlobalFocusChangeListener,
		View.OnAttachStateChangeListener {
	private final View root;
	private final EditText editor;
	private final BooleanSupplier current;
	private final Runnable showIme;
	private final Runnable show = this::showWhenReady;
	// A cancellation bound, not a delay before showing the keyboard.
	private final Runnable timeout = () -> cancel("timeout");
	private ViewTreeObserver observer;
	private boolean started, finished, posted;

	PostingKeyboardRequest(Window window, View root, EditText editor, BooleanSupplier current) {
		this(root, editor, current,
				() -> WindowCompat.getInsetsController(window, editor).show(WindowInsetsCompat.Type.ime()));
	}

	PostingKeyboardRequest(View root, EditText editor, BooleanSupplier current, Runnable showIme) {
		this.root = root;
		this.editor = editor;
		this.current = current;
		this.showIme = showIme;
	}

	void start() {
		if (started || finished) return;
		started = true;
		root.addOnAttachStateChangeListener(this);
		root.postDelayed(timeout, 10000L);
		if (root.isAttachedToWindow()) observe();
		record("waiting");
	}

	private void observe() {
		if (finished || observer != null) return;
		observer = root.getViewTreeObserver();
		observer.addOnPreDrawListener(this);
		observer.addOnWindowFocusChangeListener(this);
		observer.addOnGlobalFocusChangeListener(this);
		root.postInvalidateOnAnimation();
	}

	private boolean ready() {
		return root.isAttachedToWindow() && root.isLaidOut() && root.isShown()
				&& root.hasWindowFocus() && editor.isAttachedToWindow() && editor.isShown()
				&& editor.hasFocus() && !InterfaceMotion.isAnimating(root) && root.getAlpha() >= 0.999f;
	}

	@Override public boolean onPreDraw() {
		if (!finished) {
			if (!current.getAsBoolean()) cancel("stale_view");
			else if (!posted && ready()) {
				posted = true;
				root.post(show);
			}
		}
		return true;
	}

	private void showWhenReady() {
		posted = false;
		if (finished) return;
		if (!current.getAsBoolean()) { cancel("stale_view"); return; }
		if (!ready()) return;
		WindowInsetsCompat insets = ViewCompat.getRootWindowInsets(root);
		if (insets != null && insets.isVisible(WindowInsetsCompat.Type.ime())) {
			cancel("already_visible");
			return;
		}
		// Complete before calling the platform: focus/inset callbacks may run synchronously.
		cancel("request");
		try { showIme.run(); }
		catch (RuntimeException e) { record("unavailable_" + e.getClass().getSimpleName()); }
	}

	@Override public void onWindowFocusChanged(boolean hasFocus) {
		if (!hasFocus) cancel("window_focus_lost");
		else root.postInvalidateOnAnimation();
	}

	@Override public void onGlobalFocusChanged(View before, View after) {
		// Respect another field selected by the user; do not pull focus back to the comment.
		if (after != null && after != editor) cancel("focus_changed");
	}

	@Override public void onViewAttachedToWindow(View view) { observe(); }
	@Override public void onViewDetachedFromWindow(View view) { cancel("detached"); }

	void cancel(String reason) {
		if (finished) return;
		finished = true;
		root.removeCallbacks(show);
		root.removeCallbacks(timeout);
		root.removeOnAttachStateChangeListener(this);
		if (observer != null && observer.isAlive()) {
			observer.removeOnPreDrawListener(this);
			observer.removeOnWindowFocusChangeListener(this);
			observer.removeOnGlobalFocusChangeListener(this);
		}
		observer = null;
		record(reason);
	}

	private void record(String phase) {
		PostingFormDiagnostics.trace(root, "posting_ime_fix", "phase=" + phase
				+ " editorFocus=" + editor.hasFocus() + " windowFocus=" + root.hasWindowFocus());
	}
}
