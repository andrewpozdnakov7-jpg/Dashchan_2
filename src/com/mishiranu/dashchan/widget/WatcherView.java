package com.mishiranu.dashchan.widget;

import android.annotation.SuppressLint;
import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.InsetDrawable;
import android.graphics.drawable.RippleDrawable;
import android.view.accessibility.AccessibilityNodeInfo;
import android.os.Bundle;
import android.view.MotionEvent;
import android.view.SoundEffectConstants;
import androidx.core.view.accessibility.AccessibilityNodeInfoCompat;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.ProgressBar;
import com.mishiranu.dashchan.content.service.WatcherService;
import com.mishiranu.dashchan.R;
import com.mishiranu.dashchan.util.ResourceUtils;

@SuppressLint("ViewConstructor")
public class WatcherView extends FrameLayout {
	// Visible legacy badge, not the hit target. Previously these were implicit in a 48x36 row.
	private static final int LEGACY_CAPSULE_WIDTH = 32, LEGACY_CAPSULE_MIN_HEIGHT = 12;
	public static class ColorSet {
		public final int enabledColor;
		public final int unavailableColor;
		public final int disabledColor;

		public ColorSet(int enabledColor, int unavailableColor, int disabledColor) {
			this.enabledColor = enabledColor;
			this.unavailableColor = unavailableColor;
			this.disabledColor = disabledColor;
		}
	}

	private final ProgressBar progressBar;
	private final ColorSet colorSet;
	private final WatcherChipView chip;
	private final DrawerStyle style;

	private String text = "";
	private boolean hasNew = false;
	private WatcherService.Counter.State state;
	private boolean running;
	private boolean deleted;
	private boolean error;
	private int newCount;
	private boolean progressAnimationEnabled = true;
	private int color;
	private OnClickListener refreshClickListener;
	private boolean refreshClickPending;
	private long boundThreadId = Long.MIN_VALUE;
	private boolean touchActionPending;
	private long touchThreadId;

	public WatcherView(Context context, ColorSet colorSet) {
		this(context, colorSet, null);
	}

	public WatcherView(Context context, ColorSet colorSet, DrawerStyle style) {
		super(context);
		this.colorSet = colorSet;
		this.style = style;
		if (style != null) {
			progressBar = null;
			chip = new WatcherChipView(context, style);
			// Decorative content owns its intrinsic metrics. The parent owns the larger hit target.
			addView(chip, new FrameLayout.LayoutParams(style.chipWidth, style.chipHeight, Gravity.CENTER));
			setMinimumHeight(style.rowHeight); setFocusable(true);
			update(WatcherService.Counter.INITIAL);
			return;
		}
		chip = null;
		setMinimumHeight(DrawerStyle.minimumRowHeight(context));

		com.mishiranu.dashchan.util.ViewUtils.setSelectableItemBackground(this);
		progressBar = new ProgressBar(context, null, android.R.attr.progressBarStyleSmall);
		progressBar.setIndeterminateTintList(ColorStateList.valueOf(Color.WHITE));
		addView(progressBar, new FrameLayout.LayoutParams(FrameLayout.LayoutParams.WRAP_CONTENT,
				FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.CENTER));
		update(WatcherService.Counter.INITIAL);
	}

	public void bindThread(long id, String title) {
		if (boundThreadId != id) { refreshClickPending = false; boundThreadId = id; }
		if (chip != null) chip.bind(id, title);
	}
	public void clearBinding() {
		refreshClickPending = false; boundThreadId = Long.MIN_VALUE;
		if (chip != null) {
			chip.clear(); setContentDescription(null); setStateDescription(null); setTooltipText(null);
		}
	}

	public void setOnRefreshClickListener(OnClickListener listener) { refreshClickListener = listener; }

	@Override public boolean onTouchEvent(MotionEvent event) {
		if (chip != null) {
			if (event.getActionMasked() == MotionEvent.ACTION_DOWN) {
				// Keep the selected action for the entire gesture, including a deferred performClick.
				touchActionPending = true; touchThreadId = boundThreadId;
				boolean rtl = getLayoutDirection() == LAYOUT_DIRECTION_RTL;
				float fromStart = rtl ? getWidth() - event.getX() : event.getX();
				getCapsuleBounds(rectF);
				float capsuleInset = rtl ? getWidth() - rectF.right : rectF.left;
				refreshClickPending = fromStart >= capsuleInset + style.numberStart();
			} else if (event.getActionMasked() == MotionEvent.ACTION_CANCEL) {
				refreshClickPending = false; touchActionPending = false;
			}
		}
		return super.onTouchEvent(event);
	}

	@Override public boolean performClick() {
		boolean refresh = refreshClickPending;
		boolean stale = touchActionPending && touchThreadId != boundThreadId;
		refreshClickPending = false; touchActionPending = false;
		if (stale || !isEnabled()) return false;
		return refresh && chip != null ? performRefreshClick() : super.performClick();
	}

	private boolean performRefreshClick() {
		if (!isEnabled() || !chip.isChecked() || refreshClickListener == null) return false;
		playSoundEffect(SoundEffectConstants.CLICK);
		refreshClickListener.onClick(this);
		sendAccessibilityEvent(android.view.accessibility.AccessibilityEvent.TYPE_VIEW_CLICKED);
		return true;
	}

	@Override public boolean performLongClick() {
		boolean handled = super.performLongClick();
		if (handled) { refreshClickPending = false; touchActionPending = false; }
		return handled;
	}

	@Override public boolean performAccessibilityAction(int action, Bundle arguments) {
		refreshClickPending = false; touchActionPending = false;
		if (chip != null && action == R.id.drawer_refresh_favorite) return performRefreshClick();
		return super.performAccessibilityAction(action, arguments);
	}

	@Override protected void onSizeChanged(int w, int h, int oldw, int oldh) {
		super.onSizeChanged(w, h, oldw, oldh);
		if (style != null) {
			GradientDrawable mask = new GradientDrawable();
			mask.setColor(Color.WHITE); mask.setCornerRadius(style.chipRadius());
			int start = (w - style.chipWidth) / 2, end = w - style.chipWidth - start;
			int inset = (h - style.chipHeight) / 2;
			int bottomInset = h - style.chipHeight - inset;
			setForeground(new RippleDrawable(ColorStateList.valueOf((style.onSurface & 0xffffff) | 0x18000000),
					null, new InsetDrawable(mask, start, inset, end, bottomInset)));
		}
	}

	@Override public void onInitializeAccessibilityNodeInfo(AccessibilityNodeInfo info) {
		super.onInitializeAccessibilityNodeInfo(info);
		if (chip != null) {
			info.setClassName("android.widget.ToggleButton"); info.setCheckable(true);
			AccessibilityNodeInfoCompat.wrap(info).setChecked(chip.isChecked()
					? AccessibilityNodeInfoCompat.CHECKED_STATE_TRUE : AccessibilityNodeInfoCompat.CHECKED_STATE_FALSE);
			if (isEnabled() && chip.isChecked() && refreshClickListener != null) {
				info.addAction(new AccessibilityNodeInfo.AccessibilityAction(R.id.drawer_refresh_favorite,
						getContext().getString(R.string.drawer_refresh_favorite)));
			}
		}
	}

	private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.LINEAR_TEXT_FLAG);
	private final RectF rectF = new RectF();
	private final Rect rect = new Rect();

	/** Position may follow the host; visible size depends only on badge/font metrics. */
	void getCapsuleBounds(RectF out) {
		float density = ResourceUtils.obtainDensity(this);
		int width, height;
		if (style != null) { width = style.chipWidth; height = style.chipHeight; }
		else {
			paint.setTextSize((int) (TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, 12,
					getResources().getDisplayMetrics()) + .5f));
			// A stable numeric glyph sample avoids size changes while the count/loader changes.
			paint.getTextBounds("0X?", 0, 3, rect);
			width = Math.round(LEGACY_CAPSULE_WIDTH * density);
			height = Math.max(Math.round(LEGACY_CAPSULE_MIN_HEIGHT * density), rect.height() + Math.round(2 * density));
		}
		int left = (getWidth() - width) / 2, top = (getHeight() - height) / 2;
		out.set(left, top, left + width, top + height);
	}

	@Override
	public void draw(Canvas canvas) {
		if (chip != null) { super.draw(canvas); return; }
		float density = ResourceUtils.obtainDensity(this);
		getCapsuleBounds(rectF);
		int cornerRadius = (int) density;
		paint.setColor(color);
		canvas.drawRoundRect(rectF, cornerRadius, cornerRadius, paint);
		canvas.save();
		canvas.clipRect(rectF);
		super.draw(canvas);

		if (running && !progressAnimationEnabled) {
			paint.setColor(Color.WHITE);
			paint.setAlpha(0xff);
			if (!hasNew) {
				paint.setAlpha(0x99);
			}
			canvas.drawCircle(rectF.centerX(), rectF.centerY(), (int) (3f * density), paint);
		} else if (progressBar.getVisibility() != View.VISIBLE) {
			paint.setColor(Color.WHITE);
			paint.setAlpha(0xff);
			if (!hasNew) {
				paint.setAlpha(0x99);
			}
			paint.setTextAlign(Paint.Align.CENTER);
			paint.getTextBounds(text, 0, text.length(), rect);
			canvas.drawText(text, rectF.centerX(), rectF.centerY() + rect.height() / 2f, paint);
		} else if (hasNew) {
			paint.setColor(Color.WHITE);
			canvas.drawCircle(rectF.centerX(), rectF.centerY(), (int) (4f * density), paint);
		}
		canvas.restore();
	}

	public void setProgressAnimationEnabled(boolean enabled) {
		if (chip != null) { chip.setAnimationAllowed(enabled); return; }
		if (progressAnimationEnabled != enabled) {
			progressAnimationEnabled = enabled;
			progressBar.setVisibility(running && enabled ? View.VISIBLE : View.INVISIBLE);
			invalidate();
		}
	}

	public void update(WatcherService.Counter counter) {
		if (chip != null) { chip.update(counter); return; }
		// A thread may still be refreshed for another reason, such as tracking replies to the user's posts.
		// Do not present that shared activity or its post count as an enabled favorites watcher.
		boolean watcherEnabled = counter.state != WatcherService.Counter.State.DISABLED;
		boolean running = watcherEnabled && counter.running;
		int newCount = watcherEnabled ? counter.newCount : 0;
		boolean error = watcherEnabled && counter.error;
		if (state == counter.state && this.running == running && this.newCount == newCount &&
				deleted == counter.deleted && this.error == error) {
			return;
		}
		state = counter.state;
		this.running = running;
		this.newCount = newCount;
		deleted = counter.deleted;
		this.error = error;
		progressBar.setVisibility(running && progressAnimationEnabled ? View.VISIBLE : View.INVISIBLE);
		switch (counter.state) {
			case ENABLED: {
				color = colorSet.enabledColor;
				break;
			}
			case UNAVAILABLE: {
				color = colorSet.unavailableColor;
				break;
			}
			case DISABLED: {
				color = colorSet.disabledColor;
				break;
			}
			default: {
				throw new IllegalStateException();
			}
		}
		String text;
		if (newCount <= 0 && counter.deleted) {
			text = "X";
		} else {
			if (Math.abs(newCount) >= 1000) {
				text = (newCount / 1000) + "K+";
			} else {
				text = Integer.toString(newCount);
			}
			if (counter.deleted) {
				text += "X";
			} else if (counter.error) {
				text += "?";
			}
		}
		this.text = text;
		this.hasNew = newCount > 0;
		invalidate();
	}
}
