package com.mishiranu.dashchan.widget;

import android.annotation.SuppressLint;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.util.AttributeSet;
import android.view.MotionEvent;
import android.view.View;
import android.widget.LinearLayout;
import androidx.annotation.NonNull;

public class PostLinearLayout extends LinearLayout {
	public static final int MARK_NONE = 0;
	public static final int MARK_USER_POST = 1;
	public static final int MARK_REPLY = 2;

	public PostLinearLayout(Context context) {
		super(context);
	}

	public PostLinearLayout(Context context, AttributeSet attrs) {
		super(context, attrs);
	}

	public PostLinearLayout(Context context, AttributeSet attrs, int defStyleAttr) {
		super(context, attrs, defStyleAttr);
	}

	@Override
	public boolean hasOverlappingRendering() {
		// Makes setAlpha faster, see https://plus.google.com/+RomanNurik/posts/NSgQvbfXGQN
		// Thumbnails will become strange with alpha because background alpha and image alpha are separate now
		return false;
	}

	@SuppressLint("ClickableViewAccessibility")
	@Override
	public boolean onTouchEvent(MotionEvent event) {
		View focusedView = findFocus();
		if (focusedView instanceof CommentTextView && ((CommentTextView) focusedView).isSelectionMode()) {
			// Don't draw selection background
			return false;
		}
		return super.onTouchEvent(event);
	}

	private Drawable secondaryBackground;
	private final Paint postMarkPaint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.DITHER_FLAG);
	private int postMark = MARK_NONE;

	public void setSecondaryBackgroundColor(int color) {
		if (secondaryBackground instanceof ColorDrawable) {
			((ColorDrawable) secondaryBackground.mutate()).setColor(color);
		} else {
			setSecondaryBackground(new ColorDrawable(color));
		}
	}

	public void setSecondaryBackground(Drawable drawable) {
		if (secondaryBackground != drawable) {
			if (secondaryBackground != null) {
				secondaryBackground.setCallback(null);
				unscheduleDrawable(secondaryBackground);
			}
			secondaryBackground = drawable;
			if (drawable != null) {
				drawable.setCallback(this);
			}
			invalidate();
		}
	}

	public void setPostMark(int postMark, int color) {
		if (this.postMark != postMark || postMarkPaint.getColor() != color) {
			this.postMark = postMark;
			postMarkPaint.setColor(color);
			invalidate();
		}
	}

	@Override
	protected boolean verifyDrawable(@NonNull Drawable who) {
		return super.verifyDrawable(who) || who == secondaryBackground;
	}

	@Override
	public void draw(Canvas canvas) {
		super.draw(canvas);

		Drawable secondaryBackground = this.secondaryBackground;
		if (secondaryBackground != null) {
			secondaryBackground.setBounds(0, 0, getWidth(), getHeight());
			secondaryBackground.draw(canvas);
		}
		drawPostMark(canvas);
	}

	private void drawPostMark(Canvas canvas) {
		if (postMark == MARK_NONE) {
			return;
		}
		float density = getResources().getDisplayMetrics().density;
		float markWidth = 4f * density;
		float horizontalInset = 4f * density;
		boolean rtl = getLayoutDirection() == LAYOUT_DIRECTION_RTL;
		float markX = rtl ? getWidth() - horizontalInset - markWidth / 2f : horizontalInset + markWidth / 2f;
		if (postMark == MARK_USER_POST) {
			postMarkPaint.setStrokeCap(Paint.Cap.ROUND);
			postMarkPaint.setStrokeWidth(markWidth);
			float verticalInset = 8f * density;
			float bottom = Math.max(verticalInset, getHeight() - verticalInset);
			canvas.drawLine(markX, verticalInset, markX, bottom, postMarkPaint);
		} else if (postMark == MARK_REPLY) {
			postMarkPaint.setStrokeCap(Paint.Cap.BUTT);
			float radius = 2f * density;
			float step = 8f * density;
			float start = 8f * density;
			float end = Math.max(start, getHeight() - start);
			for (float y = start; y <= end; y += step) {
				canvas.drawCircle(markX, y, radius, postMarkPaint);
			}
		}
	}
}
