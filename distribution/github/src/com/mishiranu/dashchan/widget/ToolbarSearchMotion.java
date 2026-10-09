package com.mishiranu.dashchan.widget;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ValueAnimator;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.drawable.Drawable;
import android.view.View;
import android.view.ViewGroup;
import android.view.animation.LinearInterpolator;
import androidx.core.view.OneShotPreDrawListener;
import com.mishiranu.dashchan.R;
import com.mishiranu.dashchan.util.InterfaceMotion;
import com.mishiranu.dashchan.util.ResourceUtils;
import java.util.ArrayList;

/** A bounded outgoing toolbar image; the real search keeps its query, focus and IME ownership. */
public final class ToolbarSearchMotion implements Runnable, View.OnAttachStateChangeListener, View.OnLayoutChangeListener {
	private final ViewGroup toolbar;
	private final Bitmap image;
	private final boolean expanding;
	private final ArrayList<Frame> frames = new ArrayList<>();
	private OneShotPreDrawListener preDraw;
	private ValueAnimator animator;
	private float clock;
	private boolean stopped, committed;
	private final Drawable layer = new Drawable() {
		private final Paint paint = new Paint(Paint.FILTER_BITMAP_FLAG);
		@Override public void draw(Canvas canvas) {
			if (stopped || image.isRecycled()) return;
			float alpha = 1f - Math.min(clock / .3f, 1f);
			if (alpha <= 0f) return;
			paint.setAlpha(Math.round(255f * alpha));
			canvas.drawBitmap(image, null, getBounds(), paint);
		}
		@Override public void setAlpha(int alpha) {}
		@Override public void setColorFilter(ColorFilter filter) {}
		@SuppressWarnings("deprecation") @Override public int getOpacity() { return PixelFormat.TRANSLUCENT; }
	};
	ToolbarSearchMotion(ViewGroup toolbar, Bitmap image, boolean expanding) {
		this.toolbar = toolbar; this.image = image; this.expanding = expanding;
		layer.setBounds(0, 0, toolbar.getWidth(), toolbar.getHeight());
		toolbar.setTag(R.id.toolbar_search_motion_owner, this);
		toolbar.addOnAttachStateChangeListener(this); toolbar.addOnLayoutChangeListener(this);
		toolbar.postDelayed(this, 600);
	}
	public static ToolbarSearchMotion prepare(ViewGroup toolbar, boolean expanding) {
		finish(toolbar);
		if (!InterfaceMotion.isEnabled() || InterfaceMotion.duration(1) == 0 || toolbar == null ||
				!toolbar.isAttachedToWindow() || !toolbar.isShown() || toolbar.getAlpha() < .99f ||
				toolbar.getTag(R.id.screen_motion_owner) != null) return null;
		Bitmap image = capture(toolbar);
		return image != null ? new ToolbarSearchMotion(toolbar, image, expanding) : null;
	}
	static Bitmap capture(ViewGroup toolbar) {
		int width = toolbar.getWidth(), height = toolbar.getHeight();
		if (width <= 0 || height <= 0) return null;
		float scale = Math.min(1f, (float) Math.sqrt(180000d / ((long) width * height)));
		Bitmap bitmap = null;
		try {
			bitmap = Bitmap.createBitmap(Math.max(1, (int) (width * scale)), Math.max(1, (int) (height * scale)), Bitmap.Config.ARGB_8888);
			Canvas canvas = new Canvas(bitmap); canvas.scale((float) bitmap.getWidth() / width, (float) bitmap.getHeight() / height);
			toolbar.draw(canvas); return bitmap;
		} catch (RuntimeException | OutOfMemoryError error) { if (bitmap != null) bitmap.recycle(); return null; }
	}
	public void commit() {
		if (stopped || committed) return; committed = true;
		toolbar.getOverlay().add(layer); // Opaque first frame covers Toolbar's immediate child replacement.
		preDraw = OneShotPreDrawListener.add(toolbar, () -> {
			preDraw = null;
			if (!valid()) { run(); return; }
			for (int i = 0; i < toolbar.getChildCount(); i++) {
				View child = toolbar.getChildAt(i);
				if (child.getVisibility() == View.VISIBLE) {
					if (child.getTag(R.id.screen_motion_owner) != null) { run(); return; }
					Frame frame = new Frame(child); frames.add(frame); child.setAlpha(0f);
				}
			}
			toolbar.removeCallbacks(this);
			animator = ValueAnimator.ofFloat(0f, 1f); animator.setDuration(InterfaceMotion.duration(300));
			animator.setInterpolator(new LinearInterpolator());
			animator.addUpdateListener(value -> {
				if (!valid()) { run(); return; }
				clock = (float) value.getAnimatedValue();
				float alpha = Math.max(0f, Math.min((clock - .3f) / .7f, 1f));
				float eased = InterfaceMotion.STANDARD.getInterpolation(alpha);
				float distance = (expanding ? 20f : -12f) * ResourceUtils.obtainDensity(toolbar)
						* (toolbar.getLayoutDirection() == View.LAYOUT_DIRECTION_RTL ? -1f : 1f);
				for (Frame frame : frames) frame.apply(alpha, distance * (1f - eased));
				layer.invalidateSelf();
			});
			animator.addListener(new AnimatorListenerAdapter() {
				@Override public void onAnimationCancel(Animator animation) { run(); }
				@Override public void onAnimationEnd(Animator animation) { run(); }
			});
			animator.start();
		});
	}
	private boolean valid() {
		return !stopped && InterfaceMotion.isEnabled() && InterfaceMotion.duration(1) > 0 && toolbar.isAttachedToWindow()
				&& toolbar.getTag(R.id.toolbar_search_motion_owner) == this && toolbar.getTag(R.id.screen_motion_owner) == null;
	}
	public static void finish(View toolbar) {
		Object tag = toolbar != null ? toolbar.getTag(R.id.toolbar_search_motion_owner) : null;
		if (tag instanceof ToolbarSearchMotion) ((ToolbarSearchMotion) tag).run();
	}
	@Override public void run() {
		if (stopped) return; stopped = true;
		toolbar.removeCallbacks(this); toolbar.removeOnAttachStateChangeListener(this); toolbar.removeOnLayoutChangeListener(this);
		if (preDraw != null) { preDraw.removeListener(); preDraw = null; }
		for (Frame frame : frames) frame.release(); frames.clear();
		toolbar.getOverlay().remove(layer);
		if (toolbar.getTag(R.id.toolbar_search_motion_owner) == this) toolbar.setTag(R.id.toolbar_search_motion_owner, null);
		image.recycle();
		if (animator != null) { ValueAnimator old = animator; animator = null; old.cancel(); }
	}
	@Override public void onViewAttachedToWindow(View view) {}
	@Override public void onViewDetachedFromWindow(View view) { run(); }
	@Override public void onLayoutChange(View view, int l, int t, int r, int b, int oldL, int oldT, int oldR, int oldB) {
		if (r - l != oldR - oldL || b - t != oldB - oldT) run();
	}
	private final class Frame {
		final View view; final float alpha, x; final int accessibility;
		Frame(View view) {
			this.view = view; alpha = view.getAlpha(); x = view.getTranslationX(); accessibility = view.getImportantForAccessibility();
			view.setTag(R.id.toolbar_search_motion_owner, ToolbarSearchMotion.this);
			view.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS);
		}
		void apply(float amount, float distance) {
			if (view.getTag(R.id.toolbar_search_motion_owner) == ToolbarSearchMotion.this && view.getTag(R.id.screen_motion_owner) == null) {
				view.setAlpha(alpha * amount); view.setTranslationX(x + distance);
			}
		}
		void release() {
			if (view.getTag(R.id.toolbar_search_motion_owner) == ToolbarSearchMotion.this) {
				view.setTag(R.id.toolbar_search_motion_owner, null);
				if (view.getTag(R.id.screen_motion_owner) == null) { view.setAlpha(alpha); view.setTranslationX(x); view.setImportantForAccessibility(accessibility); }
			}
		}
	}
}
