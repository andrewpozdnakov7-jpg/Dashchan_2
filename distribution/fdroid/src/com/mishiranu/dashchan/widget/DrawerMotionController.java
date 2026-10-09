package com.mishiranu.dashchan.widget;

import android.view.View;
import androidx.drawerlayout.widget.DrawerLayout;

/** F-Droid keeps normal drag/Back behavior; surface shape follows the shared rounding setting. */
public final class DrawerMotionController {
	private final DrawerLayout layout;
	private final DrawerAppearance appearance;

	public DrawerMotionController(DrawerLayout layout, View panel, View drawerContent) {
		this.layout = layout;
		appearance = new DrawerAppearance(panel);
		updatePolicy();
	}

	public void updatePolicy() { appearance.update(); layout.setScrimColor(0x99000000); }
	public void resume() {}
	public void suspend() {}
	public void onLockModeChanged() {}
	public boolean start(boolean fromLeft) { return false; }
	public boolean isTracking() { return false; }
	public void progress(float value, boolean fromLeft) {}
	public void recover(boolean animate) {}
	public void reset() {}
}
