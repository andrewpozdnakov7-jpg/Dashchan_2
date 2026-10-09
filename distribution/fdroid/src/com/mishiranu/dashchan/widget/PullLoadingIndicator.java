package com.mishiranu.dashchan.widget;

import android.graphics.Canvas;
import android.view.View;

/** F-Droid keeps the original refresh indicator without a Material dependency. */
public final class PullLoadingIndicator {
	public PullLoadingIndicator(PullableWrapper.Wrapped wrapped, boolean top) {}
	public static void refreshTree(View view) {}
	public void setColor(int color) {}
	public void setHostActive(boolean active) {}
	public void setState(PullableWrapper.PullView.State state, int padding) {}
	public void setPullStrain(int strain, int padding) {}
	public boolean draw(Canvas canvas, int padding) { return false; }
}
