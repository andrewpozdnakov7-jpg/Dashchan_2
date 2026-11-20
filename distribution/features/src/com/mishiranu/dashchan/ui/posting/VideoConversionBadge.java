package com.mishiranu.dashchan.ui.posting;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Color;
import android.graphics.Rect;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.Drawable;
import android.os.SystemClock;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.view.ViewTreeObserver;
import android.widget.LinearLayout;
import android.widget.TextView;
import com.google.android.material.progressindicator.CircularProgressIndicator;
import com.mishiranu.dashchan.util.InterfaceMotion;
import com.mishiranu.dashchan.util.ResourceUtils;
import com.mishiranu.dashchan.R;

/** Decoration only. Never starts work, owns files, or intercepts the preview's actions. */
final class VideoConversionBadge extends LinearLayout {
	private Runnable visibilityChanged;
	void setVisibilityChanged(Runnable listener) { visibilityChanged = listener; if (listener != null) listener.run(); }
	@Override protected void onVisibilityChanged(View changedView, int visibility) {
		super.onVisibilityChanged(changedView, visibility);
		if (changedView == this && visibilityChanged != null) visibilityChanged.run();
	}
	enum Stage {NONE, QUEUED, PREPARING, PROGRESS, OPTIMIZING, CHECKING, SUCCESS, ERROR}
	private final CircularProgressIndicator ring;
	private final TextView label;
	private final View preview;
	private final Drawable successIcon, errorIcon;
	private Stage stage = Stage.NONE;
	private String token;
	private long generation, acceptedAt;
	private ValueAnimator arc;
	private Runnable expiry;
	private int percent;
	private final ViewTreeObserver.OnPreDrawListener visibility = () -> { syncRing(); return true; };

	VideoConversionBadge(Context context, Context materialContext, View preview) {
		super(context); this.preview = preview;
		setOrientation(HORIZONTAL); setGravity(Gravity.CENTER_VERTICAL);
		setPadding(dp(6), 0, dp(6), 0); setMinimumHeight(dp(24));
		setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS);
		GradientDrawable background = new GradientDrawable(); background.setColor(0xcc1b1f24);
		background.setCornerRadius(dp(6)); setBackground(background);
		ring = new CircularProgressIndicator(materialContext);
		ring.setTrackThickness(dp(2)); ring.setIndicatorSize(dp(16)); ring.setIndicatorInset(0);
		ring.setIndicatorColor(Color.WHITE); ring.setTrackColor(0x55ffffff);
		addView(ring, new LayoutParams(dp(16), dp(16)));
		label = new TextView(context); label.setTextColor(Color.WHITE); label.setTextSize(12);
		label.setTypeface(ResourceUtils.TYPEFACE_MEDIUM); label.setSingleLine();
		label.setEllipsize(TextUtils.TruncateAt.END); label.setFontFeatureSettings("tnum");
		successIcon = context.getDrawable(R.drawable.ic_editor_done).mutate();
		errorIcon = ResourceUtils.getDrawable(context, R.attr.iconAttachmentWarning, 0).mutate();
		for (Drawable icon : new Drawable[] {successIcon, errorIcon}) { icon.setTint(Color.WHITE); icon.setBounds(0, 0, dp(14), dp(14)); }
		label.setCompoundDrawablePadding(dp(4));
		LayoutParams text = new LayoutParams(-2, -2); text.setMarginStart(dp(4)); addView(label, text);
		setVisibility(GONE);
	}
	private int dp(float value) { return Math.round(value * ResourceUtils.obtainDensity(this)); }
	private boolean motion() { return InterfaceMotion.isEnabled() && InterfaceMotion.duration(160) > 0; }

	void bind(String nextToken, Stage next, int value, CharSequence text, CharSequence description, long time) {
		boolean changed = !TextUtils.equals(token, nextToken) || stage != next;
		if (changed) {
			cancelCosmetics(); generation++;
			if (arc != null) { arc.cancel(); arc = null; }
		}
		token = nextToken; stage = next; acceptedAt = time; percent = value;
		// Keep the current value available on focus, but do not announce every percentage to a focused reader.
		if (changed || !preview.isAccessibilityFocused()) preview.setStateDescription(next == Stage.NONE ? null : description);
		if (next == Stage.NONE || next == Stage.SUCCESS && (!motion() || SystemClock.uptimeMillis() >= time + 610)) {
			setVisibility(GONE); preview.setStateDescription(null); syncRing(); return;
		}
		boolean entering = getVisibility() != VISIBLE;
		setVisibility(VISIBLE);
		CharSequence shown = next == Stage.SUCCESS ? "" : text;
		if (!TextUtils.equals(label.getText(), shown)) label.setText(shown);
		if (changed) label.setCompoundDrawablesRelative(next == Stage.SUCCESS ? successIcon : next == Stage.ERROR ? errorIcon : null, null, null, null);
		setMinimumWidth(next == Stage.PROGRESS ? dp(56) : 0);
		if (changed && motion()) {
			setAlpha(entering ? 0f : .35f);
			animate().alpha(1f).setDuration(entering ? 160 : 120).start();
		} else if (changed) setAlpha(1f);
		syncRing();
		if (next == Stage.PROGRESS) {
			int target = Math.max(0, Math.min(100, value));
			if (ring.getProgress() != target) {
				if (arc != null) arc.cancel();
				if (!changed && motion() && target > ring.getProgress() && isShown()) {
					arc = ValueAnimator.ofInt(ring.getProgress(), target); arc.setDuration(120);
					arc.addUpdateListener(a -> ring.setProgressCompat(Math.min(target, (Integer) a.getAnimatedValue()), false));
					arc.start();
				} else ring.setProgressCompat(target, false);
			}
		}
		if (next == Stage.SUCCESS && changed) scheduleExpiry();
	}
	private void scheduleExpiry() {
		if (expiry != null) removeCallbacks(expiry);
		long owner = generation;
		expiry = () -> {
			if (owner != generation || stage != Stage.SUCCESS) return;
			long remaining = acceptedAt + 610 - SystemClock.uptimeMillis();
			if (!motion() || remaining <= 0) { setVisibility(GONE); preview.setStateDescription(null); return; }
			setAlpha(Math.min(1f, remaining / 160f));
			animate().alpha(0f).setDuration(Math.min(160, remaining)).withEndAction(() -> {
				if (owner == generation && stage == Stage.SUCCESS) { setVisibility(GONE); preview.setStateDescription(null); syncRing(); }
			}).start();
		};
		postDelayed(expiry, Math.max(0, acceptedAt + 450 - SystemClock.uptimeMillis()));
	}
	private void syncRing() {
		boolean hasRing = stage == Stage.PREPARING || stage == Stage.PROGRESS
				|| stage == Stage.OPTIMIZING || stage == Stage.CHECKING;
		boolean drawn = hasRing && isShown() && getWindowVisibility() == VISIBLE && hasWindowFocus() && getGlobalVisibleRect(new Rect());
		int visible = hasRing ? drawn ? VISIBLE : INVISIBLE : GONE;
		boolean spinning = drawn && stage != Stage.PROGRESS && motion();
		if (ring.isIndeterminate() != spinning) { ring.setVisibility(INVISIBLE); ring.setIndeterminate(spinning); }
		if (ring.getVisibility() != visible) ring.setVisibility(visible);
		if (hasRing && stage != Stage.PROGRESS && !spinning) ring.setProgressCompat(25, false);
		LayoutParams params = (LayoutParams) label.getLayoutParams();
		int margin = hasRing ? dp(4) : 0;
		if (params.getMarginStart() != margin) { params.setMarginStart(margin); label.setLayoutParams(params); }
	}
	private void cancelCosmetics() {
		if (expiry != null) { removeCallbacks(expiry); expiry = null; }
		animate().cancel();
	}
	void release() {
		generation++; cancelCosmetics(); if (arc != null) { arc.cancel(); arc = null; }
		stage = Stage.NONE; setVisibility(GONE);
		visibilityChanged = null;
		ring.setVisibility(GONE); ring.setIndeterminate(false); preview.setStateDescription(null);
	}
	@Override protected void onAttachedToWindow() {
		super.onAttachedToWindow(); getViewTreeObserver().addOnPreDrawListener(visibility);
		setAlpha(1f); syncRing();
		if (stage == Stage.PROGRESS) ring.setProgressCompat(Math.max(0, Math.min(100, percent)), false);
		if (stage == Stage.SUCCESS) {
			if (SystemClock.uptimeMillis() >= acceptedAt + 610 || !motion()) { setVisibility(GONE); preview.setStateDescription(null); }
			else scheduleExpiry();
		}
	}
	@Override protected void onDetachedFromWindow() {
		getViewTreeObserver().removeOnPreDrawListener(visibility);
		generation++; cancelCosmetics(); if (arc != null) { arc.cancel(); arc = null; }
		ring.setVisibility(INVISIBLE); ring.setIndeterminate(false); super.onDetachedFromWindow();
	}
	@Override public void onWindowFocusChanged(boolean hasFocus) { super.onWindowFocusChanged(hasFocus); if (ring != null) syncRing(); }
	@Override protected void onWindowVisibilityChanged(int visibility) { super.onWindowVisibilityChanged(visibility); if (ring != null) syncRing(); }
	@Override public void onVisibilityAggregated(boolean visible) { super.onVisibilityAggregated(visible); if (ring != null) syncRing(); }
}
