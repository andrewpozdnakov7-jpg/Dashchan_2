package com.mishiranu.dashchan.widget;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ValueAnimator;
import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.drawable.Drawable;
import android.view.View;
import android.view.ViewGroup;
import com.mishiranu.dashchan.ui.posting.PostingFormDiagnostics;
import com.mishiranu.dashchan.util.InterfaceMotion;
import com.mishiranu.dashchan.util.ResourceUtils;

/** Presentation only: commit layout/scroll first, then animate live views from their last displayed pose.
 * Never resizes text, requests focus, delays button availability or stores an image of the form. */
final class PostingImeMotion implements View.OnAttachStateChangeListener {
	private final ViewGroup root;
	private final View form, editor, primary, utilitySlot, utility;
	private final ViewTreeFocus focus = new ViewTreeFocus();
	private Frame last, pending;
	private ValueAnimator animator;
	private UtilityExit outgoing;
	private float formY, primaryY, utilityY, utilityAlpha, primaryAlpha;
	private int runningWidth, runningHeight;
	private boolean closed;

	PostingImeMotion(ViewGroup root, View form, View editor, View primary, View utilitySlot, View utility) {
		this.root = root; this.form = form; this.editor = editor; this.primary = primary;
		this.utilitySlot = utilitySlot; this.utility = utility;
		root.addOnAttachStateChangeListener(this);
		root.getViewTreeObserver().addOnWindowFocusChangeListener(focus);
	}

	private final class ViewTreeFocus implements android.view.ViewTreeObserver.OnWindowFocusChangeListener {
		@Override public void onWindowFocusChanged(boolean hasFocus) { if (!hasFocus) finish(); }
	}
	private boolean allowed() {
		return !closed && root.isAttachedToWindow() && root.hasWindowFocus() && root.isShown()
				&& InterfaceMotion.isEnabled() && InterfaceMotion.duration(1) > 0 && !ElementMotion.busy(root);
	}
	private static int top(View view, View relative) {
		int[] at = new int[2], base = new int[2];
		view.getLocationInWindow(at); relative.getLocationInWindow(base); return at[1] - base[1];
	}
	private final class Frame {
		final int editorTop = top(editor, root), primaryTop = top(primary, root), width = root.getWidth();
		final boolean utilityVisible = utilitySlot.getVisibility() == View.VISIBLE && utility.isShown();
		final Rect utilityBounds = new Rect(), utilityClip = new Rect();
		Frame() {
			if (utilityVisible && utility.getGlobalVisibleRect(utilityClip)) {
				int[] at = new int[2], base = new int[2];
				utility.getLocationOnScreen(at); root.getLocationOnScreen(base);
				utilityBounds.set(at[0] - base[0], at[1] - base[1],
						at[0] - base[0] + utility.getWidth(), at[1] - base[1] + utility.getHeight());
				utilityClip.offset(-base[0], -base[1]);
			}
		}
	}

	/** Called BEFORE changing visibility/order, using the last displayed frame, not stale resized bounds. */
	void begin() {
		Frame before = pending != null ? pending : animator != null ? new Frame() : last;
		finish();
		if (before != null && allowed()) pending = before;
	}

	/** Called after the form owner has completed measurement and its single native scroll alignment. */
	void onPreDraw() {
		if (closed) return;
		if (animator != null && (!allowed() || root.getWidth() != runningWidth || root.getHeight() != runningHeight)) finish();
		if (pending != null && hasMeasuredBounds(root, form, editor, primary)) {
			Frame before = pending; pending = null;
			if (allowed() && before.width == root.getWidth()) start(before);
		}
		if (pending == null && animator == null && editor.getHeight() > 0 && root.isShown()) last = new Frame();
	}
	/** A persistent ancestor layout request is not evidence that these bounds are stale. */
	static boolean hasMeasuredBounds(View... views) {
		for (View view : views) {
			if (view == null || view.getWidth() <= 0 || view.getHeight() <= 0
					|| view.getWidth() != view.getMeasuredWidth() || view.getHeight() != view.getMeasuredHeight()) return false;
		}
		return true;
	}

	private void start(Frame before) {
		Frame after = new Frame();
		formY = form.getTranslationY(); primaryY = primary.getTranslationY();
		utilityY = utility.getTranslationY(); utilityAlpha = utility.getAlpha(); primaryAlpha = primary.getAlpha();
		float contentDelta = before.editorTop - after.editorTop;
		boolean inForm = false;
		for (View view = primary; view != null; view = view.getParent() instanceof View ? (View) view.getParent() : null) {
			if (view == form) { inForm = true; break; }
		}
		boolean reveal = before.primaryTop >= root.getHeight() || before.primaryTop + primary.getHeight() <= 0;
		float primaryDelta = reveal ? 24 * ResourceUtils.obtainDensity(root)
				: before.primaryTop - after.primaryTop;
		final float panelDelta = primaryDelta - (inForm ? contentDelta : 0);
		final boolean showUtility = !before.utilityVisible && after.utilityVisible;
		if (before.utilityVisible && !after.utilityVisible && !before.utilityClip.isEmpty()) {
			outgoing = new UtilityExit(before); root.getOverlay().add(outgoing);
		}
		runningWidth = root.getWidth(); runningHeight = root.getHeight();
		ValueAnimator running = ValueAnimator.ofFloat(0f, 1f); animator = running;
		running.setDuration(InterfaceMotion.duration(260)); running.setInterpolator(InterfaceMotion.STANDARD);
		java.util.function.Consumer<Float> pose = fraction -> {
			float fade = InterfaceMotion.STANDARD.getInterpolation(Math.min(1f, running.getCurrentPlayTime() / 160f));
			form.setTranslationY(formY + contentDelta * (1f - fraction));
			primary.setTranslationY(primaryY + panelDelta * (1f - fraction));
			if (reveal) primary.setAlpha(primaryAlpha * fade);
			if (showUtility) {
				utility.setAlpha(utilityAlpha * fade);
				utility.setTranslationY(utilityY + 8 * ResourceUtils.obtainDensity(root) * (1f - fraction));
			}
			if (outgoing != null) { outgoing.fraction = fade; outgoing.invalidateSelf(); }
		};
		pose.accept(0f); // First draw uses the old pose, never one flash of the final scroll position.
		running.addUpdateListener(value -> {
			if (animator != value) return;
			if (!allowed() || root.getWidth() != runningWidth || root.getHeight() != runningHeight) { finish(); return; }
			pose.accept((float) value.getAnimatedValue());
		});
		running.addListener(new AnimatorListenerAdapter() {
			@Override public void onAnimationEnd(Animator animation) { if (animator == animation) finish(); }
		});
		PostingFormDiagnostics.trace(root, "ime_form_motion", "durationMs=260 contentDelta=" + contentDelta
				+ " primaryDelta=" + primaryDelta + " utilityReveal=" + showUtility);
		running.start();
	}

	/** Touch, reversal, resize, loss of focus and detach settle only this owner's presentation. */
	void finish() {
		pending = null;
		ValueAnimator old = animator; animator = null;
		if (old != null) {
			old.removeAllUpdateListeners(); old.removeAllListeners(); old.cancel();
			form.setTranslationY(formY); primary.setTranslationY(primaryY); primary.setAlpha(primaryAlpha);
			utility.setTranslationY(utilityY); utility.setAlpha(utilityAlpha);
		}
		if (outgoing != null) { root.getOverlay().remove(outgoing); outgoing = null; }
		if (old != null && root.isAttachedToWindow() && editor.getHeight() > 0) last = new Frame();
	}
	void close() {
		finish(); closed = true; last = null; root.removeOnAttachStateChangeListener(this);
		if (root.getViewTreeObserver().isAlive()) root.getViewTreeObserver().removeOnWindowFocusChangeListener(focus);
	}
	@Override public void onViewAttachedToWindow(View view) {}
	@Override public void onViewDetachedFromWindow(View view) { finish(); last = null; }

	/** Draw the existing utility row only, clipped to its previously visible area; no bitmap/content capture. */
	private final class UtilityExit extends Drawable {
		final Frame frame; float fraction;
		UtilityExit(Frame frame) { this.frame = frame; setBounds(0, 0, root.getWidth(), root.getHeight()); }
		@Override public void draw(Canvas canvas) {
			int saved = canvas.save(); canvas.clipRect(frame.utilityClip);
			int layer = canvas.saveLayerAlpha(frame.utilityBounds.left, frame.utilityBounds.top,
					frame.utilityBounds.right, frame.utilityBounds.bottom, Math.round(255 * (1f - fraction)));
			canvas.translate(frame.utilityBounds.left, frame.utilityBounds.top);
			utility.draw(canvas); canvas.restoreToCount(layer); canvas.restoreToCount(saved);
		}
		@Override public void setAlpha(int alpha) {}
		@Override public void setColorFilter(ColorFilter filter) {}
		@SuppressWarnings("deprecation") @Override public int getOpacity() { return PixelFormat.TRANSLUCENT; }
	}
}
