package com.mishiranu.dashchan.widget;

import android.animation.ValueAnimator;
import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Canvas;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.view.Gravity;
import android.view.View;
import android.view.animation.PathInterpolator;
import android.widget.FrameLayout;
import android.widget.ProgressBar;
import androidx.core.graphics.ColorUtils;
import com.mishiranu.dashchan.R;
import com.mishiranu.dashchan.content.service.WatcherService;
import com.mishiranu.dashchan.graphics.DrawerGlyph;
import com.mishiranu.dashchan.util.OpaqueColors;
import java.util.EnumMap;

/** Decorative child of one accessible WatcherView toggle. Owns only bounded visual work. */
final class WatcherChipView extends FrameLayout {
	private final DrawerStyle style;
	private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
	private final RectF rect = new RectF();
	private final ProgressBar progress;
	private final float textBaselineOffset;
	private final EnumMap<DrawerGlyph.Kind, DrawerGlyph> glyphs = new EnumMap<>(DrawerGlyph.Kind.class);
	private final PathInterpolator easing = new PathInterpolator(.2f, 0, 0, 1);
	private ValueAnimator transition;
	private boolean allowed;
	private boolean loaderScheduled;
	private boolean loaderVisible;
	private long boundId = Long.MIN_VALUE;
	private String title = "";
	private WatcherPresentation state;
	private Bitmap snapshot, scratch;
	private final Paint snapshotPaint = new Paint();
	private int background, foreground, fromBackground, fromForeground, toBackground, toForeground;
	private float crossfade = 1f;

	WatcherChipView(Context context, DrawerStyle style) {
		super(context); this.style = style;
		setWillNotDraw(false);
		setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS);
		paint.setTypeface(style.counterTypeface); paint.setTextSize(style.counterTextSize);
		paint.setFontFeatureSettings("tnum"); paint.setTextAlign(Paint.Align.CENTER);
		Paint.FontMetrics metrics = paint.getFontMetrics();
		textBaselineOffset = -(metrics.ascent + metrics.descent) / 2f;
		progress = new ProgressBar(context, null, android.R.attr.progressBarStyleSmall);
		FrameLayout.LayoutParams params = new FrameLayout.LayoutParams(style.dp(DrawerStyle.CHIP_ICON), style.dp(DrawerStyle.CHIP_ICON),
				Gravity.START | Gravity.CENTER_VERTICAL);
		params.setMarginStart(style.dp(DrawerStyle.CHIP_PADDING)); addView(progress, params);
		progress.setVisibility(INVISIBLE);
	}

	void bind(long id, String title) {
		if (boundId != id) {
			cancelVisuals(); state = null; crossfade = 1f; boundId = id;
		}
		this.title = title != null ? title : "";
	}

	void clear() { cancelVisuals(); state = null; boundId = Long.MIN_VALUE; title = ""; }

	void update(WatcherService.Counter counter) {
		WatcherPresentation next = WatcherPresentation.of(counter.state != WatcherService.Counter.State.DISABLED,
				counter.state == WatcherService.Counter.State.UNAVAILABLE, counter.running,
				counter.newCount, counter.deleted, counter.error);
		if (state != null && state.mode == next.mode && state.enabled == next.enabled && state.count == next.count) {
			if (!canAnimateChip()) { cancelVisuals(); snap(); }
			updateLoader(); updateAccessibility(); return;
		}
		boolean wasChecking = state != null && state.mode == WatcherPresentation.Mode.CHECKING;
		// Capture BEFORE replacing state/cancelling: includes the exact current alpha mixture,
		// not a fully opaque version of the previous target. Never captures the live child loader.
		boolean animate = state != null && canAnimateChip() && captureCurrentContent();
		if (transition != null) { transition.cancel(); transition = null; }
		state = next;
		fromBackground = background; fromForeground = foreground;
		boolean neutral = next.mode == WatcherPresentation.Mode.DISABLED || next.mode == WatcherPresentation.Mode.DELETED
				|| next.mode == WatcherPresentation.Mode.UNAVAILABLE;
		toBackground = neutral ? style.neutral : next.mode == WatcherPresentation.Mode.ERROR ? style.error
				: next.count > 0 ? style.unread : style.active;
		toForeground = neutral ? style.onNeutral : next.mode == WatcherPresentation.Mode.ERROR ? style.onError
				: next.count > 0 ? style.onUnread : style.onActive;
		if (animate) {
			crossfade = 0f;
			transition = ValueAnimator.ofFloat(0, 1);
			transition.setDuration(200);
			transition.setInterpolator(new android.view.animation.LinearInterpolator());
			transition.addUpdateListener(a -> {
				float fraction = (float) a.getAnimatedValue();
				background = OpaqueColors.blend(fromBackground, toBackground, easing.getInterpolation(fraction));
				foreground = OpaqueColors.blend(fromForeground, toForeground, easing.getInterpolation(fraction));
				crossfade = Math.min(1f, fraction * 200 / 120);
				if (crossfade >= 1f) releaseSnapshots();
				progress.setIndeterminateTintList(ColorStateList.valueOf(foreground));
				invalidate();
				if (fraction >= 1f) transition = null;
			});
			transition.start();
		} else snap();
		if (!wasChecking || next.mode != WatcherPresentation.Mode.CHECKING) updateLoader();
		updateAccessibility(); invalidate();
	}

	private boolean canAnimateChip() {
		return allowed && isAttachedToWindow() && isShown() && getWindowVisibility() == VISIBLE
				&& ValueAnimator.areAnimatorsEnabled();
	}

	void setAnimationAllowed(boolean allowed) {
		if (this.allowed != allowed) {
			this.allowed = allowed;
			if (!canAnimateChip()) { cancelVisuals(); snap(); }
			updateLoader();
		}
	}

	private final Runnable showLoader = this::showLoadingIndicator;

	private void showLoadingIndicator() {
		loaderScheduled = false;
		if (state != null && state.mode == WatcherPresentation.Mode.CHECKING && canAnimateChip()) {
			loaderVisible = true; progress.setVisibility(VISIBLE); invalidate();
		}
	}

	private void updateLoader() {
		if (state != null && state.mode == WatcherPresentation.Mode.CHECKING && canAnimateChip()) {
			if (!loaderVisible && !loaderScheduled) { loaderScheduled = true; postDelayed(showLoader, 150); }
		} else {
			removeCallbacks(showLoader); loaderScheduled = false; loaderVisible = false;
			progress.setVisibility(INVISIBLE);
		}
		invalidate();
	}

	private void cancelVisuals() {
		removeCallbacks(showLoader); loaderScheduled = false; loaderVisible = false;
		progress.setVisibility(INVISIBLE);
		if (transition != null) { transition.cancel(); transition = null; }
		releaseSnapshots();
	}

	private void snap() {
		background = toBackground; foreground = toForeground; crossfade = 1f;
		releaseSnapshots();
		progress.setIndeterminateTintList(ColorStateList.valueOf(foreground)); invalidate();
	}

	/** Two bounded, reusable buffers per interrupted transition; never allocate in onDraw/frame updates. */
	private boolean captureCurrentContent() {
		int width = getWidth(), height = getHeight();
		if (width <= 0 || height <= 0 || (long) width * height > 512_000) return false;
		if (scratch == null || scratch.getWidth() != width || scratch.getHeight() != height) {
			scratch = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
			scratch.setDensity(Bitmap.DENSITY_NONE);
		}
		scratch.eraseColor(Color.TRANSPARENT);
		Canvas frozen = new Canvas(scratch);
		drawContent(frozen);
		Bitmap old = snapshot; snapshot = scratch; scratch = old;
		return true;
	}
	private void releaseSnapshots() {
		// Let Android release pixel buffers after any pending hardware display-list references.
		// No history is retained, and a recycled bitmap can never reach a later Canvas draw.
		snapshot = null; scratch = null;
	}
	private void drawContent(Canvas canvas) {
		if (snapshot != null && crossfade < 1f) {
			int save = canvas.save();
			if (loaderVisible) {
				boolean rtl = getLayoutDirection() == LAYOUT_DIRECTION_RTL;
				canvas.clipRect(rtl ? style.dp(DrawerStyle.CHIP_PADDING) : style.numberStart(), 0,
						rtl ? getWidth() - style.numberStart() : getWidth() - style.dp(DrawerStyle.CHIP_PADDING), getHeight());
			}
			snapshotPaint.setAlpha(Math.round(255 * (1f - crossfade)));
			canvas.drawBitmap(snapshot, 0, 0, snapshotPaint);
			canvas.restoreToCount(save);
		}
		if (state != null) drawState(canvas, state, crossfade);
	}
	@Override protected void onSizeChanged(int w, int h, int oldw, int oldh) {
		super.onSizeChanged(w, h, oldw, oldh);
		if (progress != null) { cancelVisuals(); snap(); updateLoader(); }
	}
	@Override public void onRtlPropertiesChanged(int layoutDirection) {
		super.onRtlPropertiesChanged(layoutDirection);
		if (progress != null) { cancelVisuals(); snap(); updateLoader(); }
	}

	@Override protected void onAttachedToWindow() { super.onAttachedToWindow(); snap(); updateLoader(); }
	@Override protected void onDetachedFromWindow() { cancelVisuals(); snap(); super.onDetachedFromWindow(); }
	@Override public void onVisibilityAggregated(boolean visible) {
		super.onVisibilityAggregated(visible);
		if (progress != null) {
			if (!visible) { cancelVisuals(); snap(); } else updateLoader();
		}
	}
	@Override protected void onWindowVisibilityChanged(int visibility) {
		super.onWindowVisibilityChanged(visibility);
		if (progress != null) {
			if (visibility != VISIBLE) { cancelVisuals(); snap(); } else updateLoader();
		}
	}

	private DrawerGlyph.Kind icon(WatcherPresentation s) {
		switch (s.mode) {
			case DELETED: return DrawerGlyph.Kind.BLOCK;
			case ERROR: return DrawerGlyph.Kind.ERROR;
			case UNAVAILABLE: return DrawerGlyph.Kind.PAUSE;
			case CHECKING: return DrawerGlyph.Kind.SYNC;
			default: return s.enabled ? DrawerGlyph.Kind.EYE_FILLED : DrawerGlyph.Kind.EYE;
		}
	}

	@Override protected void onDraw(Canvas canvas) {
		super.onDraw(canvas);
		if (state == null) return;
		if (loaderVisible && !canAnimateChip()) { cancelVisuals(); snap(); }
		// Intrinsic child bounds, independent of the containing row/hit target.
		rect.set(0, 0, style.chipWidth, style.chipHeight);
		paint.setColor(background); paint.setAlpha(255);
		canvas.drawRoundRect(rect, style.chipRadius(), style.chipRadius(), paint);
		drawContent(canvas);
	}

	private void drawState(Canvas canvas, WatcherPresentation s, float alpha) {
		boolean rtl = getLayoutDirection() == LAYOUT_DIRECTION_RTL;
		int iconSize = style.dp(DrawerStyle.CHIP_ICON);
		int iconLeft = rtl ? getWidth() - style.dp(DrawerStyle.CHIP_PADDING) - iconSize : style.dp(DrawerStyle.CHIP_PADDING);
		int iconTop = (getHeight() - iconSize) / 2;
		if (!loaderVisible) {
			DrawerGlyph.Kind kind = icon(s);
			DrawerGlyph glyph = glyphs.get(kind);
			if (glyph == null) { glyph = new DrawerGlyph(kind, foreground, 24, 1.6f); glyphs.put(kind, glyph); }
			glyph.setColor(ColorUtils.setAlphaComponent(foreground, Math.round(255 * alpha)));
			glyph.setBounds(iconLeft, iconTop, iconLeft + iconSize, iconTop + iconSize); glyph.draw(canvas);
		}
		float left = rtl ? style.dp(DrawerStyle.CHIP_PADDING) : style.numberStart();
		float right = rtl ? getWidth() - style.numberStart() : getWidth() - style.dp(DrawerStyle.CHIP_PADDING);
		paint.setColor(foreground); paint.setAlpha(Math.round(255 * alpha));
		canvas.drawText(s.text, (left + right) / 2f, getHeight() / 2f + textBaselineOffset, paint);
		paint.setAlpha(255);
	}

	boolean isChecked() { return state != null && state.enabled; }

	private void updateAccessibility() {
		if (!(getParent() instanceof WatcherView) || state == null) return;
		WatcherView owner = (WatcherView) getParent();
		int status = !state.enabled ? R.string.drawer_tracking_off : R.string.drawer_tracking_on;
		String detail = getContext().getString(status);
		if (state.enabled) detail = getContext().getString(state.count < 0 ? R.string.drawer_tracking_count_delta_format
				: R.string.drawer_tracking_count_format, detail, state.count);
		int extra = state.mode == WatcherPresentation.Mode.DELETED ? R.string.drawer_tracking_deleted
				: state.mode == WatcherPresentation.Mode.ERROR ? R.string.drawer_tracking_error
				: state.mode == WatcherPresentation.Mode.UNAVAILABLE ? R.string.drawer_tracking_paused
				: state.mode == WatcherPresentation.Mode.CHECKING ? R.string.drawer_tracking_checking : 0;
		if (extra != 0) detail = getContext().getString(R.string.drawer_tracking_detail_format, detail, getContext().getString(extra));
		owner.setContentDescription(title); owner.setStateDescription(detail);
		owner.setTooltipText(getContext().getString(R.string.drawer_tracking_detail_format, title, detail));
	}
}
