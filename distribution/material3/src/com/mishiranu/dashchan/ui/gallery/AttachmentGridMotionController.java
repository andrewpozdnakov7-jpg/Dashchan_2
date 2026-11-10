package com.mishiranu.dashchan.ui.gallery;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ValueAnimator;
import android.graphics.Rect;
import android.graphics.RectF;
import android.view.View;
import android.view.Window;
import androidx.core.view.OneShotPreDrawListener;
import com.mishiranu.dashchan.R;
import com.mishiranu.dashchan.util.InterfaceMotion;
import com.mishiranu.dashchan.widget.AttachmentView;
import com.mishiranu.dashchan.widget.PredictiveTransition;
import java.lang.ref.WeakReference;

/** Attachment picker shares media geometry/dim timing, without copying bitmaps or changing selection. */
public final class AttachmentGridMotionController {
	private final View content;
	private final Window window;
	private final WeakReference<AttachmentView> source;
	private final long binding;
	private Scene scene;
	private PredictiveTransition predictive;
	private int predictiveGeneration;

	public AttachmentGridMotionController(View content, Window window, View source) {
		this.content = content; this.window = window;
		AttachmentView thumbnail = source instanceof AttachmentView ? (AttachmentView) source : null;
		this.source = new WeakReference<>(thumbnail);
		binding = thumbnail != null ? thumbnail.getImageBindingGeneration() : -1;
		content.addOnAttachStateChangeListener(new View.OnAttachStateChangeListener() {
			@Override public void onViewAttachedToWindow(View view) {}
			@Override public void onViewDetachedFromWindow(View view) { dispose(); }
		});
		content.addOnLayoutChangeListener((v, l, t, r, b, ol, ot, or, ob) -> {
			if (scene != null && or > ol && ob > ot && (r - l != or - ol || b - t != ob - ot)) finish();
		});
	}

	private boolean enabled() { return InterfaceMotion.isEnabled() && ValueAnimator.areAnimatorsEnabled(); }
	public void open() {
		dispose();
		// A restored picker has no origin; do not replay an opening transition.
		if (!enabled() || source.get() == null || window == null) return;
		install(new Scene(false, null));
	}
	public boolean close(Runnable cancel) {
		if (isPredictiveBackCommitting()) return true;
		if (scene != null && scene.exit) return true;
		dispose();
		if (!enabled() || window == null || !content.isAttachedToWindow()) return false;
		window.setWindowAnimations(0);
		install(new Scene(true, cancel));
		return true;
	}
	void install(Scene next) { scene = next; next.prepare(); }
	public boolean isRunning() { return scene != null || predictive != null && predictive.isBusy(); }
	public boolean isPredictiveBackCommitting() { return predictive != null && predictive.isCommitting(); }
	public void finish() { finishPredictiveBack(); if (scene != null) scene.release(true); }
	public void dispose() {
		// Forced removal must not turn an accepted animation into an OnCancel callback.
		predictiveGeneration++; finishPredictiveBack(); if (scene != null) scene.release(false);
	}
	private void finishPredictiveBack() {
		PredictiveTransition old = predictive; predictive = null; if (old != null) old.finish();
	}
	private boolean predictiveEnabled() {
		return InterfaceMotion.isPredictiveBackEnabled() && ValueAnimator.areAnimatorsEnabled();
	}
	public boolean startPredictiveBack() {
		if (isPredictiveBackCommitting() || scene != null && scene.exit) return false;
		finish(); // Settle an opening or cancelled gesture before capturing neutral properties.
		if (!predictiveEnabled() || window == null || !content.isAttachedToWindow()
				|| content.getWidth() <= 0 || content.getHeight() <= 0
				|| content.getTag(R.id.gallery_motion_owner) != null) return false;
		predictiveGeneration++;
		BackPresentation presentation = new BackPresentation();
		predictive = new PredictiveTransition(content, presentation);
		return true;
	}
	public void progressPredictiveBack(float progress) { if (predictive != null) predictive.progress(progress); }
	public void cancelPredictiveBack() { if (predictive != null) predictive.cancel(); }
	public boolean commitPredictiveBack(Runnable cancel) {
		int generation = predictiveGeneration;
		return predictive != null && predictive.commit(() -> {
			if (generation != predictiveGeneration) return;
			window.setWindowAnimations(0); cancel.run();
		});
	}

	private void applyFrame(Object owner, GalleryMotionController.PresentationFrame frame,
			RectF image, RectF thumbnail, RectF clip, float dim, float amount) {
		setDim(dim * amount);
		if (thumbnail != null) {
			clip.set(GalleryMotionSpec.lerp(thumbnail.left, image.left, amount),
					GalleryMotionSpec.lerp(thumbnail.top, image.top, amount),
					GalleryMotionSpec.lerp(thumbnail.right, image.right, amount),
					GalleryMotionSpec.lerp(thumbnail.bottom, image.bottom, amount));
			frame.frame(owner, image, clip, true); frame.fade(owner, 1f);
		} else frame.fade(owner, amount);
	}

	/** The same grid geometry/dim as ordinary close, driven by Back instead of a second clock. */
	final class BackPresentation implements PredictiveTransition.Presentation {
		final GalleryMotionController.PresentationFrame frame = new GalleryMotionController.PresentationFrame(content);
		final float dim = window.getAttributes().dimAmount;
		final int accessibility = content.getImportantForAccessibility();
		final int width = content.getWidth(), height = content.getHeight();
		final RectF image = new RectF(0, 0, width, height), thumbnail = target(), clip = new RectF();
		final Rect sourceBounds = new Rect(), currentSourceBounds = new Rect();
		boolean restored;
		BackPresentation() {
			AttachmentView view = source.get(); if (thumbnail != null && view != null) view.getGlobalVisibleRect(sourceBounds);
			frame.own(this);
			content.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS);
		}
		@Override public boolean valid() {
			if (restored || !predictiveEnabled() || !content.isAttachedToWindow()
					|| content.getWidth() != width || content.getHeight() != height
					|| content.getTag(R.id.gallery_motion_owner) != this) return false;
			AttachmentView view = source.get();
			return thumbnail == null || view != null && view.isAttachedToWindow() && view.isShown()
					&& view.getImageBindingGeneration() == binding && view.getGlobalVisibleRect(currentSourceBounds)
					&& sourceBounds.equals(currentSourceBounds);
		}
		@Override public void apply(float fraction) { applyFrame(this, frame, image, thumbnail, clip, dim, 1f - fraction); }
		@Override public void restore() {
			if (restored) return; restored = true;
			if (content.getTag(R.id.gallery_motion_owner) == this) {
				frame.release(this); content.setImportantForAccessibility(accessibility); setDim(dim);
			}
		}
	}

	private void setDim(float amount) {
		// During detach, do not dispatch a WindowManager layout update to a removed decor.
		if (content.isAttachedToWindow()) window.setDimAmount(amount);
		else window.getAttributes().dimAmount = amount;
	}

	private RectF target() {
		AttachmentView view = source.get();
		if (view == null || view.getImageBindingGeneration() != binding || !view.isAttachedToWindow()
				|| !view.isShown() || view.getWidth() <= 0 || view.getHeight() <= 0) return null;
		Rect visible = new Rect();
		if (!view.getGlobalVisibleRect(visible) || visible.width() < view.getWidth() - 1
				|| visible.height() < view.getHeight() - 1) return null;
		int[] from = new int[2], to = new int[2];
		view.getLocationOnScreen(from); content.getLocationOnScreen(to);
		return new RectF(from[0] - to[0], from[1] - to[1],
				from[0] - to[0] + view.getWidth(), from[1] - to[1] + view.getHeight());
	}

	final class Scene {
		final boolean exit;
		final Runnable cancel;
		final GalleryMotionController.PresentationFrame frame = new GalleryMotionController.PresentationFrame(content);
		final float dim = window.getAttributes().dimAmount;
		final int accessibility = content.getImportantForAccessibility();
		final Runnable watchdog = () -> release(true);
		OneShotPreDrawListener preDraw;
		ValueAnimator animator;
		RectF image, thumbnail;
		final RectF clip = new RectF();
		boolean released;

		Scene(boolean exit, Runnable cancel) { this.exit = exit; this.cancel = cancel; }
		void prepare() {
			frame.own(this);
			content.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS);
			if (!exit) { frame.fade(this, 0f); setDim(0f); }
			content.postDelayed(watchdog, 600);
			preDraw = OneShotPreDrawListener.add(content, () -> {
				preDraw = null;
				if (released) return;
				content.removeCallbacks(watchdog);
				if (!enabled() || !content.isAttachedToWindow() || content.getWidth() <= 0 || content.getHeight() <= 0) {
					release(true); return;
				}
				image = new RectF(0, 0, content.getWidth(), content.getHeight()); thumbnail = target();
				apply(0f);
				animator = ValueAnimator.ofFloat(0f, 1f);
				animator.setDuration(exit ? GalleryMotionSpec.EXIT : GalleryMotionSpec.ENTER);
				animator.setInterpolator(InterfaceMotion.STANDARD);
				animator.addUpdateListener(value -> {
					if (!enabled() || !content.isAttachedToWindow() || content.getTag(R.id.gallery_motion_owner) != this) {
						release(true); return;
					}
					if (thumbnail != null && target() == null) { release(true); return; }
					apply((float) value.getAnimatedValue());
				});
				animator.addListener(new AnimatorListenerAdapter() {
					@Override public void onAnimationEnd(Animator animation) { release(true); }
				});
				animator.start();
			});
			content.invalidate();
		}
		void apply(float progress) {
			float amount = exit ? 1f - progress : progress;
			applyFrame(this, frame, image, thumbnail, clip, dim, amount);
		}
		void release(boolean complete) {
			if (released) return; released = true;
			if (scene == this) scene = null;
			content.removeCallbacks(watchdog);
			if (preDraw != null) { preDraw.removeListener(); preDraw = null; }
			if (animator != null) { animator.cancel(); animator = null; }
			if (content.getTag(R.id.gallery_motion_owner) == this) {
				frame.release(this); content.setImportantForAccessibility(accessibility); setDim(dim);
			}
			if (complete && cancel != null) cancel.run();
		}
	}
}
