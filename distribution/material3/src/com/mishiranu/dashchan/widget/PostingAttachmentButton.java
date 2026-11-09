package com.mishiranu.dashchan.widget;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.util.TypedValue;
import androidx.appcompat.widget.AppCompatImageButton;
import com.mishiranu.dashchan.R;
import com.mishiranu.dashchan.ui.posting.PostingAttachmentState;
import com.mishiranu.dashchan.util.ResourceUtils;
import com.mishiranu.dashchan.util.InterfaceMotion;
import java.text.NumberFormat;

/** A single hit/accessibility target. The live button and its IME flight use the same renderer. */
final class PostingAttachmentButton extends AppCompatImageButton {
	private final Drawable clip, plus;
	private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG), text = new Paint(Paint.ANTI_ALIAS_FLAG);
	private final Paint.FontMetrics metrics = new Paint.FontMetrics();
	private final RectF badge = new RectF();
	private PostingAttachmentState state = new PostingAttachmentState(0, 0, false);
	private boolean compact;
	private String number = "";
	private int badgeFill, badgeText;
	private float badgeAlpha;
	private Runnable badgeChanged;

	PostingAttachmentButton(Context context) {
		super(context, null, android.R.attr.borderlessButtonStyle);
		clip = context.getDrawable(R.drawable.ic_attach_file_badge_clip).mutate();
		plus = context.getDrawable(R.drawable.ic_attach_file_badge_plus).mutate();
		text.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
		text.setTextAlign(Paint.Align.CENTER);
	}

	void setColors(int icon, int badgeFill, int badgeText) {
		clip.setTint(icon); plus.setTint(icon);
		this.badgeFill = badgeFill; this.badgeText = badgeText;
		invalidate();
	}

	void setPresentation(PostingAttachmentState next, boolean compact) {
		boolean changed = state.count != next.count || state.limit != next.limit || state.importing != next.importing
				|| this.compact != compact;
		state = next; this.compact = compact;
		if (next.count == 0 || !badgeMotionAllowed()) setImeBadgeAlpha(isBadgeRequested() ? 1f : 0f);
		// While an IME transition owns the frame, preserve its current alpha. Reparenting
		// and count/import updates must not start a second clock or reveal the badge early.
		if (!changed) return;
		NumberFormat format = NumberFormat.getIntegerInstance(getResources().getConfiguration().getLocales().get(0));
		format.setGroupingUsed(false);
		number = next.count > 0 ? format.format(next.count) : "";
		String action = getContext().getString(compact && next.atLimit() ? R.string.posting_show_attachments : R.string.attach);
		String description = next.count > 0 ? getResources().getQuantityString(
				R.plurals.posting_attachment_action_count, next.count, action, next.count) : action;
		if (next.importing) description += ". " + getContext().getString(R.string.processing_data__ellipsis);
		setContentDescription(description); setTooltipText(description);
		invalidate(); // Count/action updates never remeasure or move the button or CTA.
	}

	float getBadgeAlpha() { return badgeAlpha; }
	void setBadgeChanged(Runnable listener) { badgeChanged = listener; }

	boolean isBadgeRequested() { return compact && state.count > 0; }

	/** The IME presentation is the only animation owner; steady layouts settle immediately. */
	void updateBadge(boolean transitionRunning) {
		if (state.count == 0 || !badgeMotionAllowed() || !transitionRunning) {
			setImeBadgeAlpha(isBadgeRequested() ? 1f : 0f);
		}
	}

	void setImeBadgeAlpha(float alpha) {
		float next = state.count > 0 ? Math.max(0f, Math.min(1f, alpha)) : 0f;
		if (badgeAlpha != next) { badgeAlpha = next; invalidateBadge(); }
	}

	private boolean badgeMotionAllowed() {
		// A GONE endpoint may still be drawn by the existing IME overlay (at the limit).
		return isAttachedToWindow() && hasWindowFocus()
				&& InterfaceMotion.isEnabled() && InterfaceMotion.duration(120) > 0;
	}

	private void invalidateBadge() {
		invalidate();
		// During the existing IME flight the real View is transparent. Refresh its
		// shared renderer in the overlay too, without creating another flight.
		if (badgeChanged != null) badgeChanged.run();
	}

	void close() { badgeChanged = null; }

	@Override protected void onDraw(Canvas canvas) {
		drawContents(canvas, getWidth(), getHeight(), badgeAlpha);
	}

	/** Background remains owned by the existing ripple; all foreground geometry is shared. */
	void drawContents(Canvas canvas, float width, float height, float badgeOpacity) {
		float fraction = Math.max(0f, Math.min(1f, badgeOpacity));
		float density = ResourceUtils.obtainDensity(this);
		int size = Math.round(24 * density);
		int left = Math.round((width - size) / 2), top = Math.round((height - size) / 2);
		if (state.count == 0) {
			drawAt(getDrawable(), canvas, left, top, left + size, top + size, 255);
			return;
		}
		clip.setLayoutDirection(getLayoutDirection()); plus.setLayoutDirection(getLayoutDirection());
		if (state.hasRoom()) {
			// Work in the 48dp button, not a 24dp bounding box for the whole group. Each
			// contour retains its original scale. In LTR the clip ends near x=22dp and
			// the plus starts near x=27dp; its visible y range is 26..36dp.
			int clipLeft = Math.round((width - 48 * density) / 2 + 9 * density);
			// Actual filled path edges in the unchanged 24-unit vectors: clip maxX=13,
			// plus minX=12. Account for partially covered edge pixels before adding space.
			int clipEnd = (int) Math.ceil(clipLeft + size * 13f / 24f);
			// One pixel of guard also covers fractional translation of the IME overlay.
			int gap = Math.max(Math.round(5 * density), (int) Math.ceil(4 * density) + 1);
			int plusLeft = (int) Math.ceil(clipEnd + gap - size * 12f / 24f);
			int plusTop = Math.round((height - 48 * density) / 2 + 23 * density);
			if (getLayoutDirection() == LAYOUT_DIRECTION_RTL) {
				clipLeft = Math.round(width) - clipLeft - size;
				plusLeft = Math.round(width) - plusLeft - size;
			}
			// Keep the same clear gap throughout the existing IME flight; never squeeze
			// the larger group back into the old 24dp viewport during interpolation.
			drawAt(clip, canvas, clipLeft, top, clipLeft + size, top + size, 255);
			drawAt(plus, canvas, plusLeft, plusTop, plusLeft + size, plusTop + size, 255);
		} else {
			// The already approved full-limit icon and its badge keep their exact position.
			drawAt(clip, canvas, left, top, left + size, top + size, 255);
		}
		if (state.count > 0) drawBadge(canvas, width, density, fraction);
	}

	private void drawBadge(Canvas canvas, float width, float density, float fraction) {
		float height = 16 * density, inset = 4 * density;
		text.setTextSize(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, 11, getResources().getDisplayMetrics()));
		text.getFontMetrics(metrics);
		// Keep the specified fixed badge/48dp slot readable even at large system font scales.
		float fontHeight = metrics.descent - metrics.ascent;
		if (fontHeight > height - 2 * density) text.setTextSize(text.getTextSize() * (height - 2 * density) / fontHeight);
		float measured = text.measureText(number), maximum = Math.max(16 * density, width - 2 * inset);
		if (measured > maximum - 4 * density) {
			text.setTextSize(text.getTextSize() * (maximum - 4 * density) / measured);
			measured = text.measureText(number);
		}
		float badgeWidth = Math.min(maximum, Math.max(height, measured + 4 * density));
		boolean rtl = getLayoutDirection() == LAYOUT_DIRECTION_RTL;
		float left = rtl ? inset : width - inset - badgeWidth;
		badge.set(left, inset, left + badgeWidth, inset + height);
		fill.setColor(badgeFill); fill.setAlpha(Math.round(255 * fraction));
		canvas.drawRoundRect(badge, 8 * density, 8 * density, fill);
		text.setColor(badgeText); text.setAlpha(Math.round(255 * fraction));
		text.getFontMetrics(metrics);
		canvas.drawText(number, badge.centerX(), badge.centerY() - (metrics.ascent + metrics.descent) / 2, text);
	}

	private static void drawAt(Drawable drawable, Canvas canvas, int left, int top, int right, int bottom, int alpha) {
		if (drawable == null || alpha <= 0) return;
		Rect previous = new Rect(drawable.getBounds()); int previousAlpha = drawable.getAlpha();
		try { drawable.setBounds(left, top, right, bottom); drawable.setAlpha(alpha); drawable.draw(canvas); }
		finally { drawable.setBounds(previous); drawable.setAlpha(previousAlpha); }
	}
}
