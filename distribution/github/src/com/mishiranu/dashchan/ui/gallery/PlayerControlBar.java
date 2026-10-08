package com.mishiranu.dashchan.ui.gallery;

import android.content.Context;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import com.mishiranu.dashchan.util.ResourceUtils;

/**
 * Measures the actual controls rather than choosing two rows merely because the phone is in portrait.
 * Children keep their identity and parent during resize and progress updates, including an active seek.
 */
final class PlayerControlBar extends ViewGroup {
	private final View play;
	private final LinearLayout time;
	private final View seek;
	private final View mute;
	private final View fullscreen;
	private final int buttonSize;
	private final int seekHeight;
	private final int minimumSeekWidth;
	private final int minimumTimeRowHeight;
	private boolean inline;
	private int timeRowHeight;
	private int playbackRowHeight;

	PlayerControlBar(Context context, View play, LinearLayout time, View seek, View mute, View fullscreen) {
		super(context);
		this.play = play;
		this.time = time;
		this.seek = seek;
		this.mute = mute;
		this.fullscreen = fullscreen;
		float density = ResourceUtils.obtainDensity(context);
		buttonSize = PlayerControlsStyle.dp(density, 32);
		seekHeight = PlayerControlsStyle.dp(density, 24);
		minimumSeekWidth = PlayerControlsStyle.dp(density, 72);
		minimumTimeRowHeight = PlayerControlsStyle.dp(density, 28);
		int horizontalPadding = PlayerControlsStyle.dp(density, 8);
		int verticalPadding = PlayerControlsStyle.dp(density, 4);
		setPadding(horizontalPadding, verticalPadding, horizontalPadding, verticalPadding);
		setBackground(PlayerControlsStyle.panel(density, 16));
		setClickable(true);
		addView(play);
		addView(time);
		addView(seek);
		addView(mute);
		addView(fullscreen);
	}

	@Override
	protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
		int natural = MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED);
		time.measure(natural, natural);
		int naturalTimeWidth = time.getMeasuredWidth();
		int paddingWidth = getPaddingLeft() + getPaddingRight();
		int requestedWidth = buttonSize * 3 + naturalTimeWidth + minimumSeekWidth + paddingWidth;
		int width = resolveSize(requestedWidth, widthMeasureSpec);
		int available = Math.max(0, width - paddingWidth);
		inline = available >= buttonSize * 3 + naturalTimeWidth + minimumSeekWidth;

		int buttonWidth = Math.min(buttonSize, available / 3);
		int buttonSpec = MeasureSpec.makeMeasureSpec(buttonWidth, MeasureSpec.EXACTLY);
		int buttonHeightSpec = MeasureSpec.makeMeasureSpec(buttonSize, MeasureSpec.EXACTLY);
		play.measure(buttonSpec, buttonHeightSpec);
		mute.measure(buttonSpec, buttonHeightSpec);
		fullscreen.measure(buttonSpec, buttonHeightSpec);
		int timeWidth = inline ? naturalTimeWidth : Math.min(naturalTimeWidth, available);
		time.measure(MeasureSpec.makeMeasureSpec(timeWidth, MeasureSpec.EXACTLY), natural);
		int seekWidth = Math.max(0, available - buttonWidth * 3 - (inline ? timeWidth : 0));
		seek.measure(MeasureSpec.makeMeasureSpec(seekWidth, MeasureSpec.EXACTLY),
				MeasureSpec.makeMeasureSpec(seekHeight, MeasureSpec.EXACTLY));

		playbackRowHeight = buttonSize;
		if (inline) playbackRowHeight = Math.max(playbackRowHeight, time.getMeasuredHeight());
		timeRowHeight = inline ? 0 : Math.max(minimumTimeRowHeight, time.getMeasuredHeight());
		int height = getPaddingTop() + timeRowHeight + playbackRowHeight + getPaddingBottom();
		setMeasuredDimension(width, resolveSize(height, heightMeasureSpec));
	}

	@Override
	protected void onLayout(boolean changed, int left, int top, int right, int bottom) {
		boolean rtl = getLayoutDirection() == LAYOUT_DIRECTION_RTL;
		int cursor = rtl ? getWidth() - getPaddingRight() : getPaddingLeft();
		int rowTop = getPaddingTop() + timeRowHeight;
		cursor = place(play, cursor, rowTop, playbackRowHeight, rtl);
		if (inline) {
			cursor = place(time, cursor, rowTop, playbackRowHeight, rtl);
		} else {
			int timeCursor = rtl ? getWidth() - getPaddingRight() : getPaddingLeft();
			place(time, timeCursor, getPaddingTop(), timeRowHeight, rtl);
		}
		cursor = place(seek, cursor, rowTop, playbackRowHeight, rtl);
		cursor = place(mute, cursor, rowTop, playbackRowHeight, rtl);
		place(fullscreen, cursor, rowTop, playbackRowHeight, rtl);
	}

	private static int place(View child, int cursor, int top, int rowHeight, boolean rtl) {
		int width = child.getMeasuredWidth();
		int height = child.getMeasuredHeight();
		int childLeft = rtl ? cursor - width : cursor;
		int childTop = top + (rowHeight - height) / 2;
		child.layout(childLeft, childTop, childLeft + width, childTop + height);
		return rtl ? childLeft : childLeft + width;
	}

	@Override
	protected LayoutParams generateDefaultLayoutParams() {
		return new LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT);
	}
}
