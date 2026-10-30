package com.mishiranu.dashchan.widget;

import android.content.Context;
import android.graphics.Canvas;
import android.util.AttributeSet;
import android.widget.FrameLayout;

/** Canvas-only page presentation. Child View properties and navigation remain untouched. */
public final class PredictiveBackLayout extends FrameLayout {
	public interface Content { void draw(Canvas canvas); }
	public interface Presentation { void draw(Canvas canvas, Content content); }
	private Presentation presentation;
	private final Content content = this::drawContent;

	public PredictiveBackLayout(Context context) { this(context, null); }
	public PredictiveBackLayout(Context context, AttributeSet attrs) {
		super(context, attrs);
		// Ensure ViewGroup's fast drawing path does not skip our draw override.
		setWillNotDraw(false);
	}
	public void setPresentation(Presentation presentation) {
		this.presentation = presentation;
		invalidate();
	}
	@Override public void draw(Canvas canvas) {
		Presentation current = presentation;
		if (current != null) current.draw(canvas, content); else super.draw(canvas);
	}
	private void drawContent(Canvas canvas) { super.draw(canvas); }
}
