package com.mishiranu.dashchan.widget;

import android.animation.ValueAnimator;
import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.drawable.Animatable;
import android.graphics.drawable.Drawable;
import android.view.View;
import android.widget.ProgressBar;
import com.google.android.material.loadingindicator.LoadingIndicator;
import com.google.android.material.loadingindicator.LoadingIndicatorDrawable;
import com.mishiranu.dashchan.util.InterfaceMotion;
import java.lang.ref.WeakReference;
import java.util.HashSet;
import java.util.Set;

/** One drawable owner per ProgressBar. The detached Material View only configures its public drawable. */
final class StandardLoadingDrawable extends Drawable implements Animatable, Drawable.Callback {
	private final WeakReference<ProgressBar> owner;
	private final LoadingIndicator material;
	private final LoadingIndicatorDrawable child;
	private final int size;
	private final Set<Runnable> scheduled = new HashSet<>();
	private boolean requested;
	private int mode = -1, background, ink;
	StandardLoadingDrawable(ProgressBar view) {
		owner = new WeakReference<>(view); size = StandardAppearance.dp(view, 48);
		material = PullLoadingIndicator.createMaterial(view.getContext()); child = material.getDrawable();
		material.setContainerWidth(size); material.setContainerHeight(size);
		material.setIndicatorSize(StandardAppearance.dp(view, 34));
		child.setVisible(false, false, false); child.setCallback(this);
	}
	void palette(int background, int ink) {
		if (mode < 0 || this.background != background || this.ink != ink) {
			this.background = background; this.ink = ink;
			material.setContainerColor(background); material.setIndicatorColor(ink); invalidateSelf();
		}
	}
	void sync() {
		ProgressBar view = owner.get();
		boolean active = requested && isVisible() && view != null && view.isAttachedToWindow() && view.isShown()
				&& view.getWindowVisibility() == View.VISIBLE && view.hasWindowFocus();
		int next = !active ? 0 : ValueAnimator.areAnimatorsEnabled() && InterfaceMotion.isEnabled() ? 2 : 1;
		if (mode != next) {
			mode = next; child.setVisible(next != 0, false, next == 2);
			if (next != 2) { for (Runnable task : scheduled) unscheduleSelf(task); scheduled.clear(); }
			invalidateSelf();
		}
	}
	@Override public void start() { requested = true; sync(); }
	@Override public void stop() { requested = false; sync(); }
	@Override public boolean isRunning() { return mode == 2; }
	@Override public boolean setVisible(boolean visible, boolean restart) {
		boolean changed = super.setVisible(visible, restart); sync(); return changed;
	}
	@Override protected void onBoundsChange(Rect bounds) {
		int side = Math.min(size, Math.min(bounds.width(), bounds.height()));
		int left = bounds.centerX() - side / 2, top = bounds.centerY() - side / 2;
		child.setBounds(left, top, left + side, top + side);
	}
	@Override public int getIntrinsicWidth() { return size; }
	@Override public int getIntrinsicHeight() { return size; }
	Rect indicatorBounds() { return new Rect(child.getBounds()); }
	@Override public void draw(Canvas canvas) { sync(); child.draw(canvas); }
	@Override public void setAlpha(int alpha) { child.setAlpha(alpha); }
	@Override public void setColorFilter(ColorFilter filter) { child.setColorFilter(filter); }
	@Override public int getOpacity() { return PixelFormat.TRANSLUCENT; }
	@Override public void invalidateDrawable(Drawable who) { invalidateSelf(); }
	@Override public void scheduleDrawable(Drawable who, Runnable task, long when) { scheduled.add(task); scheduleSelf(task, when); }
	@Override public void unscheduleDrawable(Drawable who, Runnable task) { scheduled.remove(task); unscheduleSelf(task); }
}
