package com.mishiranu.dashchan.widget;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ValueAnimator;
import android.content.res.ColorStateList;
import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.PixelFormat;
import android.graphics.PorterDuff;
import android.graphics.Rect;
import android.graphics.drawable.Drawable;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import com.mishiranu.dashchan.R;
import com.mishiranu.dashchan.util.InterfaceMotion;
import com.mishiranu.dashchan.util.ResourceUtils;
import java.util.function.BooleanSupplier;

/** Stage 6 local presentation. Never performs playback, navigation or form actions. */
public final class ElementMotion {
	private ElementMotion() {}
	public static boolean busy(View view) {
		for (View current = view; current != null; current = current.getParent() instanceof View ? (View) current.getParent() : null) {
			if (InterfaceMotion.isAnimating(current) || current.getTag(R.id.gallery_motion_owner) != null
					|| current.getTag(R.id.dialog_motion_owner) != null) return true;
		}
		return false;
	}
	public static void finish(View view) {
		if (view == null) return;
		Object owner = view.getTag(R.id.element_motion_owner);
		if (owner instanceof Owner) ((Owner) owner).finish();
		Object icon = view.getTag(R.id.icon_motion_owner);
		if (icon instanceof Icon) ((Icon) icon).finish();
	}
	public static void finishTree(View view) {
		if (view == null) return;
		ContentStateMotion.finish(view); ImePanelMotion.finish(view); finish(view);
		if (view instanceof ViewGroup) {
			ViewGroup group = (ViewGroup) view;
			for (int i = 0; i < group.getChildCount(); i++) finishTree(group.getChildAt(i));
		}
	}
	public static boolean blocksInput(View view) {
		Object owner = view.getTag(R.id.element_motion_owner);
		return owner instanceof Visibility && !((Visibility) owner).visible;
	}
	public static void visibility(View view, boolean visible, float distanceDp, boolean scale, BooleanSupplier valid) {
		if (view == null) return;
		if (busy(view)) {
			finish(view);
			// Commit the requested UI state; the existing scene still owns alpha and geometry.
			view.setVisibility(visible ? View.VISIBLE : View.GONE); return;
		}
		float alpha = view.getAlpha(), y = view.getTranslationY(), sx = view.getScaleX(), sy = view.getScaleY();
		boolean presented = view.getVisibility() == View.VISIBLE;
		Object previous = view.getTag(R.id.element_motion_owner);
		Visibility old = previous instanceof Visibility ? (Visibility) previous : null;
		float baseY = old != null ? old.y : 0f, baseXScale = old != null ? old.sx : 1f, baseYScale = old != null ? old.sy : 1f;
		finish(view);
		if (!InterfaceMotion.isEnabled() || InterfaceMotion.duration(1) == 0 || !view.isAttachedToWindow() || busy(view) || !valid.getAsBoolean()) {
			view.setVisibility(visible ? View.VISIBLE : View.GONE);
			if (!busy(view)) { view.setAlpha(1f); view.setTranslationY(baseY); view.setScaleX(baseXScale); view.setScaleY(baseYScale); }
			return;
		}
		Visibility owner = new Visibility(view, visible, baseY, baseXScale, baseYScale, valid);
		float distance = distanceDp * ResourceUtils.obtainDensity(view);
		if (!presented) { alpha = 0f; y = baseY + distance; sx = baseXScale * (scale ? .9f : 1f); sy = baseYScale * (scale ? .9f : 1f); }
		view.setVisibility(View.VISIBLE); view.setAlpha(alpha); view.setTranslationY(y); view.setScaleX(sx); view.setScaleY(sy);
		owner.start(alpha, y, sx, sy, distance, scale);
	}
	public static Animator height(View view, int from, int to, int finalHeight) {
		finish(view);
		Height owner = new Height(view, finalHeight);
		ValueAnimator animator = ValueAnimator.ofInt(from, to); owner.animator = animator;
		animator.setDuration(InterfaceMotion.duration(240)); animator.setInterpolator(InterfaceMotion.STANDARD);
		animator.addUpdateListener(value -> {
			if (!owner.valid() || !InterfaceMotion.isEnabled() || busy(view)) { owner.finish(); return; }
			owner.apply((int) value.getAnimatedValue());
		});
		animator.addListener(new AnimatorListenerAdapter() { @Override public void onAnimationEnd(Animator animation) { owner.finish(); } });
		if (!view.isAttachedToWindow() || busy(view)) animator.setDuration(0);
		return animator;
	}
	public static void icon(ImageView view, int resource) {
		if (view == null) return;
		Object tag = view.getTag(R.id.icon_motion_owner);
		Icon old = tag instanceof Icon ? (Icon) tag : null;
		if (old != null && old.resource == resource && (view.getDrawable() == old.target || view.getDrawable() == old.drawable)) {
			if (!InterfaceMotion.isEnabled() || InterfaceMotion.duration(1) == 0 || busy(view)) old.finish();
			return;
		}
		if (old != null) old.finish();
		Drawable from = view.getDrawable();
		view.setImageResource(resource);
		Drawable target = view.getDrawable();
		Icon owner = new Icon(view, resource, target); view.setTag(R.id.icon_motion_owner, owner);
		if (from == null || target == null || from == target || !InterfaceMotion.isEnabled() || InterfaceMotion.duration(1) == 0
				|| !view.isAttachedToWindow() || !view.isShown() || busy(view)) return;
		owner.drawable = new IconDrawable(from, target);
		view.setImageDrawable(owner.drawable); view.addOnAttachStateChangeListener(owner);
		owner.animator = ValueAnimator.ofFloat(0f, 1f); owner.animator.setDuration(InterfaceMotion.duration(180));
		owner.animator.setInterpolator(new android.view.animation.LinearInterpolator());
		owner.animator.addUpdateListener(value -> {
			if (view.getTag(R.id.icon_motion_owner) != owner || view.getDrawable() != owner.drawable
					|| !InterfaceMotion.isEnabled() || busy(view)) { owner.finish(); return; }
			owner.drawable.progress = (float) value.getAnimatedValue(); owner.drawable.invalidateSelf();
		});
		owner.animator.addListener(new AnimatorListenerAdapter() { @Override public void onAnimationEnd(Animator animation) { owner.finish(); } });
		owner.animator.start();
	}
	abstract static class Owner implements View.OnAttachStateChangeListener {
		final View view; ValueAnimator animator; boolean stopped;
		Owner(View view) { this.view = view; view.setTag(R.id.element_motion_owner, this); view.addOnAttachStateChangeListener(this); }
		boolean owns() { return view.getTag(R.id.element_motion_owner) == this; }
		void finish() {
			if (stopped) return; stopped = true;
			if (owns()) { if (!busy(view)) settle(); view.setTag(R.id.element_motion_owner, null); }
			view.removeOnAttachStateChangeListener(this);
			if (animator != null) { ValueAnimator old = animator; animator = null; old.cancel(); }
		}
		abstract void settle();
		@Override public void onViewAttachedToWindow(View view) {}
		@Override public void onViewDetachedFromWindow(View view) { finish(); }
	}
	static final class Visibility extends Owner {
		final boolean visible; final float y, sx, sy; final BooleanSupplier valid; final int accessibility;
		Visibility(View view, boolean visible, float y, float sx, float sy, BooleanSupplier valid) {
			super(view); this.visible = visible; this.y = y; this.sx = sx; this.sy = sy; this.valid = valid;
			accessibility = view.getImportantForAccessibility();
			if (!visible) view.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS);
		}
		void start(float alpha, float fromY, float fromXScale, float fromYScale, float distance, boolean scale) {
			animator = ValueAnimator.ofFloat(0f, 1f); animator.setDuration(InterfaceMotion.duration(visible ? 240 : 160)); animator.setInterpolator(InterfaceMotion.STANDARD);
			animator.addUpdateListener(value -> {
				if (!owns() || !InterfaceMotion.isEnabled() || !valid.getAsBoolean() || busy(view)) { finish(); return; }
				float t = (float) value.getAnimatedValue();
				view.setAlpha(alpha + ((visible ? 1f : 0f) - alpha) * t);
				view.setTranslationY(fromY + (y + (visible ? 0f : distance) - fromY) * t);
				view.setScaleX(fromXScale + (sx * (!visible && scale ? .94f : 1f) - fromXScale) * t);
				view.setScaleY(fromYScale + (sy * (!visible && scale ? .94f : 1f) - fromYScale) * t);
			});
			animator.addListener(new AnimatorListenerAdapter() { @Override public void onAnimationEnd(Animator animation) { finish(); } }); animator.start();
		}
		@Override void settle() {
			view.setVisibility(visible ? View.VISIBLE : View.GONE); view.setAlpha(1f); view.setTranslationY(y);
			view.setScaleX(sx); view.setScaleY(sy); view.setImportantForAccessibility(accessibility);
		}
	}
	private static final class Height extends Owner {
		final ViewGroup.LayoutParams params; final int finalHeight;
		Height(View view, int finalHeight) { super(view); params = view.getLayoutParams(); this.finalHeight = finalHeight; }
		boolean valid() { return owns() && !stopped && view.getLayoutParams() == params; }
		void apply(int height) { if (valid()) { params.height = height; view.requestLayout(); } }
		@Override void settle() { if (view.getLayoutParams() == params) { params.height = finalHeight; view.requestLayout(); } }
	}
	static final class Icon implements View.OnAttachStateChangeListener {
		final ImageView view; final int resource; final Drawable target; IconDrawable drawable; ValueAnimator animator;
		Icon(ImageView view, int resource, Drawable target) { this.view = view; this.resource = resource; this.target = target; }
		void finish() {
			IconDrawable oldDrawable = drawable; drawable = null;
			if (oldDrawable != null) {
				oldDrawable.release();
				if (view.getTag(R.id.icon_motion_owner) == this && view.getDrawable() == oldDrawable) view.setImageDrawable(target);
			}
			view.removeOnAttachStateChangeListener(this);
			if (animator != null) { ValueAnimator old = animator; animator = null; old.cancel(); }
		}
		@Override public void onViewAttachedToWindow(View view) {}
		@Override public void onViewDetachedFromWindow(View view) { finish(); }
	}
	static final class IconDrawable extends Drawable implements Drawable.Callback {
		final Drawable from, to; float progress; int alpha = 255;
		IconDrawable(Drawable from, Drawable to) { this.from = from; this.to = to; from.setCallback(this); to.setCallback(this); }
		void release() { if (from.getCallback() == this) from.setCallback(null); if (to.getCallback() == this) to.setCallback(null); }
		@Override public void draw(Canvas canvas) {
			boolean incoming = progress >= .4f;
			float fraction = incoming ? (progress - .4f) / .6f : progress / .4f;
			float eased = InterfaceMotion.STANDARD.getInterpolation(Math.max(0f, Math.min(fraction, 1f)));
			float opacity = incoming ? eased : 1f - eased, scale = incoming ? .9f + .1f * eased : 1f - .1f * eased;
			Rect bounds = getBounds(); int clip = canvas.save(); canvas.clipRect(bounds);
			int layer = canvas.saveLayerAlpha(bounds.left, bounds.top, bounds.right, bounds.bottom, Math.round(alpha * opacity));
			canvas.scale(scale, scale, bounds.exactCenterX(), bounds.exactCenterY()); (incoming ? to : from).draw(canvas);
			canvas.restoreToCount(layer); canvas.restoreToCount(clip);
		}
		@Override protected void onBoundsChange(Rect bounds) { from.setBounds(bounds); to.setBounds(bounds); }
		@Override public void setAlpha(int alpha) { this.alpha = alpha; invalidateSelf(); }
		@Override public void setColorFilter(ColorFilter filter) { from.setColorFilter(filter); to.setColorFilter(filter); invalidateSelf(); }
		@Override public void setTintList(ColorStateList tint) { from.setTintList(tint); to.setTintList(tint); invalidateSelf(); }
		@Override public void setTintMode(PorterDuff.Mode mode) { from.setTintMode(mode); to.setTintMode(mode); invalidateSelf(); }
		@Override public void setTintBlendMode(android.graphics.BlendMode mode) { from.setTintBlendMode(mode); to.setTintBlendMode(mode); invalidateSelf(); }
		@Override public boolean isStateful() { return from.isStateful() || to.isStateful(); }
		@Override protected boolean onStateChange(int[] state) { boolean a = from.setState(state), b = to.setState(state); return a || b; }
		@Override protected boolean onLevelChange(int level) { boolean a = from.setLevel(level), b = to.setLevel(level); return a || b; }
		@Override public boolean onLayoutDirectionChanged(int direction) { boolean a = from.setLayoutDirection(direction), b = to.setLayoutDirection(direction); return a || b; }
		@Override public int getIntrinsicWidth() { return to.getIntrinsicWidth(); }
		@Override public int getIntrinsicHeight() { return to.getIntrinsicHeight(); }
		@SuppressWarnings("deprecation") @Override public int getOpacity() { return PixelFormat.TRANSLUCENT; }
		@Override public void invalidateDrawable(Drawable who) { invalidateSelf(); }
		@Override public void scheduleDrawable(Drawable who, Runnable what, long when) { scheduleSelf(what, when); }
		@Override public void unscheduleDrawable(Drawable who, Runnable what) { unscheduleSelf(what); }
	}
}
