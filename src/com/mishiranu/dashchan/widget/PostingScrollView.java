package com.mishiranu.dashchan.widget;

import android.content.Context;
import android.content.ContextWrapper;
import android.graphics.Rect;
import android.os.Build;
import android.util.AttributeSet;
import android.view.MotionEvent;
import android.view.KeyEvent;
import android.view.View;
import android.widget.ScrollView;
import com.mishiranu.dashchan.ui.posting.PostingFormDiagnostics;
import java.util.function.BooleanSupplier;
import java.util.function.Predicate;

/** Native scrolling unless the posting editor explicitly owns keyboard viewport alignment. */
public class PostingScrollView extends ScrollView {
	public interface BackCallback {
		boolean canHandlePostingBack(View owner);
		void onPostingBack(View owner);
	}
	private BooleanSupplier editorViewportOwner;
	private Runnable cancelEditorMotion;
	private Predicate<MotionEvent> editorMotionTouchHandler;
	private BackCallback legacyBackOwner;

	public PostingScrollView(Context context) { super(context); }
	public PostingScrollView(Context context, AttributeSet attrs) { super(context, attrs); }

	public void setEditorViewportOwner(BooleanSupplier owner) { editorViewportOwner = owner; }
	public void setCancelEditorMotion(Runnable cancel) { cancelEditorMotion = cancel; }
	public void setEditorMotionTouchHandler(Predicate<MotionEvent> handler) { editorMotionTouchHandler = handler; }

	private BackCallback backCallback() {
		Context context = getContext();
		while (context != null) {
			if (context instanceof BackCallback) return (BackCallback) context;
			if (!(context instanceof ContextWrapper)) break;
			Context next = ((ContextWrapper) context).getBaseContext();
			if (next == context) break;
			context = next;
		}
		return null;
	}
	@Override public boolean dispatchKeyEventPreIme(KeyEvent event) {
		// API33+ uses the scoped platform callback, including predictive progress on 34+.
		// Older gesture navigation arrives as a Back key before the IME consumes it.
		if (Build.VERSION.SDK_INT < 33 && dispatchPostingBackBeforeIme(event, backCallback())) return true;
		return super.dispatchKeyEventPreIme(event);
	}
	boolean dispatchPostingBackBeforeIme(KeyEvent event, BackCallback callback) {
		if (event.getKeyCode() != KeyEvent.KEYCODE_BACK) return false;
		if (event.getAction() == KeyEvent.ACTION_DOWN) {
			if (event.getRepeatCount() == 0) legacyBackOwner = callback != null && callback.canHandlePostingBack(this) ? callback : null;
			return legacyBackOwner != null;
		}
		BackCallback owner = legacyBackOwner;
		if (owner == null) return false;
		if (event.getAction() == KeyEvent.ACTION_UP) {
			legacyBackOwner = null;
			if (callback == owner && owner.canHandlePostingBack(this) && BackKeyPolicy.isConfirmedRelease(event)) owner.onPostingBack(this);
		}
		return true;
	}
	@Override public void onWindowFocusChanged(boolean hasWindowFocus) {
		super.onWindowFocusChanged(hasWindowFocus);
		if (!hasWindowFocus) legacyBackOwner = null;
	}
	@Override protected void onDetachedFromWindow() {
		legacyBackOwner = null; super.onDetachedFromWindow();
	}

	@Override protected int computeScrollDeltaToGetChildRectOnScreen(Rect rect) {
		// Used by native onSizeChanged, focus reveal and child-rectangle requests.
		// Let the owner align the complete editor after measurement instead of
		// starting another focus animation using the old text-field bounds.
		boolean owned = editorViewportOwner != null && editorViewportOwner.getAsBoolean();
		int delta = owned ? 0 : super.computeScrollDeltaToGetChildRectOnScreen(rect);
		if (PostingFormDiagnostics.isRecording()) PostingFormDiagnostics.traceState(this, "native_focus_delta",
				"owned=" + owned + " rect=" + rect.toShortString() + " delta=" + delta + " scrollY=" + getScrollY());
		return delta;
	}

	public void alignEditorViewport(int y) {
		int before = getScrollY();
		scrollTo(getScrollX(), Math.max(0, y));
		// Replace/abort any older smooth-scroll target through the public API.
		// Do this AFTER scrollTo: a zero-distance animation must end at the new
		// position, not return to the position before alignment on its next frame.
		smoothScrollBy(0, 0);
		if (PostingFormDiagnostics.isRecording()) PostingFormDiagnostics.trace(this, "align_editor",
				"before=" + before + " requested=" + y + " applied=" + getScrollY());
	}

	@Override protected void onSizeChanged(int w, int h, int oldw, int oldh) {
		if (PostingFormDiagnostics.isRecording()) PostingFormDiagnostics.trace(this, "native_resize_begin",
				"from=" + oldw + "x" + oldh + " to=" + w + "x" + h + " scrollY=" + getScrollY());
		super.onSizeChanged(w, h, oldw, oldh);
		if (PostingFormDiagnostics.isRecording()) PostingFormDiagnostics.trace(this, "native_resize_end", "scrollY=" + getScrollY());
	}
	@Override protected void onScrollChanged(int x, int y, int oldx, int oldy) {
		super.onScrollChanged(x, y, oldx, oldy);
		if (PostingFormDiagnostics.isRecording()) PostingFormDiagnostics.trace(this, "native_scroll",
				"from=" + oldx + "," + oldy + " to=" + x + "," + y);
	}
	@Override public boolean requestChildRectangleOnScreen(View child, Rect rectangle, boolean immediate) {
		if (PostingFormDiagnostics.isRecording()) PostingFormDiagnostics.traceState(this, "child_rectangle",
				"child=" + PostingFormDiagnostics.viewLabel(child) + " rect=" + rectangle.toShortString() + " immediate=" + immediate);
		return super.requestChildRectangleOnScreen(child, rectangle, immediate);
	}
	@Override public boolean dispatchTouchEvent(MotionEvent event) {
		int action = event.getActionMasked();
		if (editorMotionTouchHandler != null && editorMotionTouchHandler.test(event)) {
			// A moving action owns this tap, not the button which ends up underneath it.
			// Keep delivering drags to the native scroller; the presentation owner cancels
			// the mapped click after touch slop without creating a second child gesture.
			super.onTouchEvent(event);
			return true;
		}
		if (action == MotionEvent.ACTION_DOWN && cancelEditorMotion != null) cancelEditorMotion.run();
		if (PostingFormDiagnostics.isRecording() && (action == MotionEvent.ACTION_DOWN
				|| action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL)) {
			PostingFormDiagnostics.trace(this, "form_touch", "action=" + action + " pointers=" + event.getPointerCount());
		}
		return super.dispatchTouchEvent(event);
	}
}
