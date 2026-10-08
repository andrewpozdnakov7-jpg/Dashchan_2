package com.mishiranu.dashchan.widget;

import android.view.View;
import androidx.drawerlayout.widget.DrawerLayout;

/** F-Droid keeps DrawerLayout's normal appearance, drag and Back ownership. */
public final class DrawerMotionController {
	private final DrawerLayout layout;

	public DrawerMotionController(DrawerLayout layout, View panel) {
		this.layout = layout;
		updatePolicy();
	}

	public void updatePolicy() { layout.setScrimColor(0x99000000); }
	public void resume() {}
	public void suspend() {}
	public void onLockModeChanged() {}
	public boolean start(boolean fromLeft) { return false; }
	public boolean isTracking() { return false; }
	public void progress(float value, boolean fromLeft) {}
	public void recover(boolean animate) {}
	public void reset() {}
}
