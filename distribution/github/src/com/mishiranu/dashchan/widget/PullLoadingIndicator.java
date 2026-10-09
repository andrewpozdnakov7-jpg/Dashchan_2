package com.mishiranu.dashchan.widget;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.drawable.Drawable;
import android.os.SystemClock;
import android.view.ContextThemeWrapper;
import android.view.View;
import android.view.ViewGroup;
import android.view.animation.Interpolator;
import android.view.animation.OvershootInterpolator;
import androidx.core.graphics.ColorUtils;
import com.google.android.material.loadingindicator.LoadingIndicator;
import com.google.android.material.loadingindicator.LoadingIndicatorDrawable;
import com.mishiranu.dashchan.R;
import com.mishiranu.dashchan.util.InterfaceMotion;
import com.mishiranu.dashchan.util.ResourceUtils;
import java.lang.ref.WeakReference;
import java.util.HashSet;
import java.util.Set;

/** Presentation adapter for the real Material drawable. Never starts or cancels a refresh request. */
public final class PullLoadingIndicator implements Drawable.Callback {
	private static final int HIDDEN = 0, STATIC = 1, ANIMATED = 2;
	private static final Interpolator SETTLE = new OvershootInterpolator(.45f);
	private final WeakReference<View> host;
	private final boolean top;
	private final Paint staticContainer = new Paint(Paint.ANTI_ALIAS_FLAG);
	private final Set<Runnable> scheduled = new HashSet<>();
	private PullableWrapper.PullView.State state = PullableWrapper.PullView.State.IDLE;
	private int pullStrain, accent;
	private boolean hostActive, policyActive;
	private float position, visibility, targetPosition, targetVisibility;
	private ValueAnimator transition;
	private LoadingIndicator material;
	private LoadingIndicatorDrawable drawable;
	private ThemeEngine.Theme paletteTheme;
	private int paletteAccent, drawableMode = HIDDEN;

	public PullLoadingIndicator(PullableWrapper.Wrapped wrapped, boolean top) {
		host = new WeakReference<>(wrapped instanceof View ? (View) wrapped : null);
		this.top = top;
	}

	/** Refresh only existing wrappers; do not create indicators for every list in the hierarchy. */
	public static void refreshTree(View view) {
		if (view instanceof PaddedRecyclerView) ((PaddedRecyclerView) view).refreshPullIndicator();
		if (view instanceof ViewGroup) {
			ViewGroup group = (ViewGroup) view;
			for (int i = 0; i < group.getChildCount(); i++) refreshTree(group.getChildAt(i));
		}
	}

	public void setColor(int color) { accent = color; }

	private boolean enabled(View view) {
		return view != null && InterfaceAppearance.isEnabled(view.getContext());
	}

	private boolean active(View view) {
		return hostActive && view != null && view.isAttachedToWindow() && view.isShown()
				&& view.getWindowVisibility() == View.VISIBLE && view.hasWindowFocus();
	}

	public void setHostActive(boolean active) {
		hostActive = active;
		if (!active) deactivate();
		else invalidateHost();
	}

	public void setState(PullableWrapper.PullView.State next, int padding) {
		if (state == next) return;
		PullableWrapper.PullView.State previous = state;
		state = next;
		View view = host.get();
		if (!enabled(view) || !active(view)) { deactivate(); return; }
		policyActive = true;
		switch (next) {
			case PULL: cancelTransition(); break;
			case LOADING: animateTo(1f, 1f, 280, SETTLE); break;
			case IDLE:
				animateTo(previous == PullableWrapper.PullView.State.LOADING ? position : 0f,
						0f, 180, InterfaceMotion.STANDARD);
				break;
		}
	}

	public void setPullStrain(int strain, int padding) {
		pullStrain = strain;
		View view = host.get();
		if (state == PullableWrapper.PullView.State.PULL && enabled(view) && active(view)) {
			cancelTransition(); policyActive = true;
			position = pullPosition(strain); visibility = Math.min(1f, strain / 1000f);
			targetPosition = position; targetVisibility = visibility;
			invalidateHost();
		}
	}

	private static float pullPosition(int strain) {
		float fraction = Math.max(0f, Math.min(2f, strain / 1000f));
		return fraction <= 1f ? fraction : 1f + .18f * (fraction - 1f);
	}

	private void synchronize() {
		position = state == PullableWrapper.PullView.State.LOADING ? 1f
				: state == PullableWrapper.PullView.State.PULL ? pullPosition(pullStrain) : 0f;
		visibility = state == PullableWrapper.PullView.State.LOADING ? 1f
				: state == PullableWrapper.PullView.State.PULL ? Math.min(1f, pullStrain / 1000f) : 0f;
		targetPosition = position; targetVisibility = visibility; policyActive = true;
	}

	private void animateTo(float endPosition, float endVisibility, int duration, Interpolator interpolator) {
		cancelTransition(); targetPosition = endPosition; targetVisibility = endVisibility;
		if (InterfaceMotion.duration(duration) == 0 || position == endPosition && visibility == endVisibility) {
			position = endPosition; visibility = endVisibility; invalidateHost(); return;
		}
		float startPosition = position, startVisibility = visibility;
		ValueAnimator animator = ValueAnimator.ofFloat(0f, 1f);
		transition = animator;
		animator.setDuration(duration); animator.setInterpolator(interpolator);
		animator.addUpdateListener(value -> {
			if (transition != animator) return;
			View view = host.get();
			if (!active(view) || !enabled(view)) { deactivate(); return; }
			float fraction = (float) value.getAnimatedValue();
			position = startPosition + (endPosition - startPosition) * fraction;
			visibility = Math.max(0f, Math.min(1f, startVisibility + (endVisibility - startVisibility) * fraction));
			invalidateHost();
		});
		animator.addListener(new AnimatorListenerAdapter() {
			@Override public void onAnimationEnd(Animator animation) {
				if (transition == animator) {
					transition = null; position = endPosition; visibility = endVisibility;
					if (endVisibility == 0f) mode(HIDDEN);
					invalidateHost();
				}
			}
		});
		animator.start();
	}

	private void cancelTransition() {
		ValueAnimator owned = transition;
		transition = null;
		if (owned != null) { owned.removeAllUpdateListeners(); owned.removeAllListeners(); owned.cancel(); }
	}

	private void deactivate() {
		policyActive = false; cancelTransition(); mode(HIDDEN);
		View view = host.get();
		if (view != null) for (Runnable runnable : scheduled) view.removeCallbacks(runnable);
		scheduled.clear();
	}

	/** true means this renderer owns presentation, including a fully hidden idle frame. */
	public boolean draw(Canvas canvas, int padding) {
		View view = host.get();
		if (!enabled(view)) { if (policyActive || drawableMode != HIDDEN) deactivate(); return false; }
		if (!active(view)) { deactivate(); return true; }
		if (!policyActive) synchronize();
		boolean animations = ValueAnimator.areAnimatorsEnabled();
		if (!animations && transition != null) {
			cancelTransition(); position = targetPosition; visibility = targetVisibility;
		}
		if (visibility <= 0f || view.getWidth() <= 0 || view.getHeight() <= 0) { mode(HIDDEN); return true; }
		prepare(view);
		mode(state == PullableWrapper.PullView.State.LOADING && animations ? ANIMATED : STATIC);
		float density = ResourceUtils.obtainDensity(view);
		float size = material.getContainerWidth(), half = size / 2f;
		float hiddenDistance = -half * .65f * .65f;
		float distance = hiddenDistance + (32f * density - hiddenDistance) * position;
		float centerX = view.getWidth() / 2f;
		float centerY = top ? padding + distance : view.getHeight() - padding - distance;
		float pullScale = .65f + .35f * Math.min(1f, Math.max(0f, position));
		float scale = pullScale * (.65f + .35f * visibility);
		int save = canvas.save();
		canvas.translate(centerX - half, centerY - half);
		canvas.scale(scale, scale, half, half);
		int layer = canvas.saveLayerAlpha(0f, 0f, size, size, Math.round(255f * visibility));
		// Material's reduced-motion drawable is an arrow without its normal contained background.
		if (!animations) canvas.drawCircle(half, half, half, staticContainer);
		if (state == PullableWrapper.PullView.State.PULL) {
			canvas.rotate((top ? 1f : -1f) * 90f * Math.min(1f, position), half, half);
		}
		drawable.draw(canvas);
		canvas.restoreToCount(layer); canvas.restoreToCount(save);
		return true;
	}

	static LoadingIndicator createMaterial(Context context) {
		// This view configures the public drawable only; it is never added as a list child or touch target.
		return new LoadingIndicator(new ContextThemeWrapper(context, R.style.Theme_Slooop_LoadingIndicator));
	}

	private void prepare(View view) {
		if (material == null) {
			material = createMaterial(view.getContext()); drawable = material.getDrawable();
			drawable.setVisible(false, false, false); drawable.setCallback(this);
			int size = Math.round(48f * ResourceUtils.obtainDensity(view));
			material.setContainerWidth(size); material.setContainerHeight(size);
			material.setIndicatorSize(Math.round(34f * ResourceUtils.obtainDensity(view)));
			drawable.setBounds(0, 0, size, size);
		}
		ThemeEngine.Theme theme = ThemeEngine.getTheme(view.getContext());
		if (paletteTheme != theme || paletteAccent != accent) {
			paletteTheme = theme; paletteAccent = accent;
			int window = InterfaceAppearance.opaque(theme.window,
					theme.base == ThemeEngine.Theme.Base.LIGHT ? Color.WHITE : Color.BLACK);
			int surface = InterfaceAppearance.opaque(theme.card, window);
			int tonal = ColorUtils.blendARGB(surface, InterfaceAppearance.opaque(accent, surface), .12f);
			material.setContainerColor(tonal);
			// Initialize the idle preview even when the user's accent equals the Material default.
			material.setIndicatorColor(Color.TRANSPARENT);
			material.setIndicatorColor(InterfaceAppearance.contrasting(accent, tonal, 3));
			staticContainer.setColor(tonal);
		}
	}

	private void mode(int next) {
		if (drawable == null || drawableMode == next) return;
		drawableMode = next;
		// Do not call this every frame: Material restarts its animator even with restart=false.
		drawable.setVisible(next != HIDDEN, false, next == ANIMATED);
	}

	private void invalidateHost() {
		View view = host.get();
		if (active(view)) view.postInvalidateOnAnimation();
	}

	@Override public void invalidateDrawable(Drawable who) {
		View view = host.get();
		if (active(view) && enabled(view)) invalidateHost();
		else if (policyActive || drawableMode != HIDDEN) deactivate();
	}
	@Override public void scheduleDrawable(Drawable who, Runnable what, long when) {
		View view = host.get();
		if (active(view) && enabled(view)) {
			scheduled.add(what); view.postDelayed(what, Math.max(0L, when - SystemClock.uptimeMillis()));
		}
	}
	@Override public void unscheduleDrawable(Drawable who, Runnable what) {
		View view = host.get(); if (view != null) view.removeCallbacks(what); scheduled.remove(what);
	}
}
