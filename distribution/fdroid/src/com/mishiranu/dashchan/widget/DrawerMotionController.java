package com.mishiranu.dashchan.widget;

import android.view.View;
import androidx.drawerlayout.widget.DrawerLayout;

/** F-Droid keeps normal drag/Back behavior; surface shape follows the shared rounding setting. */
public final class DrawerMotionController {
	private final DrawerLayout layout;
	private final DrawerAppearance appearance;
	private final DrawerBackController back;

	public DrawerMotionController(DrawerLayout layout, View panel, View drawerContent) {
		this.layout = layout;
		appearance = new DrawerAppearance(panel);
		back = new DrawerBackController(layout, panel, this::updateScrim);
		updatePolicy();
	}

	private void updateScrim() { layout.setScrimColor(Math.round(153f * (1f - .8f * back.progress())) << 24); }
	public void updatePolicy() { appearance.update(); back.update(); updateScrim(); }
	public void resume() { back.resume(); }
	public void suspend() { back.suspend(); }
	public void onLockModeChanged() { back.update(); }
	public boolean start(boolean fromLeft) { return back.start(); }
	public boolean isTracking() { return back.isTracking(); }
	public boolean isBusy() { return back.isBusy(); }
	public void progress(float value, boolean fromLeft) { back.progress(value); }
	public void recover(boolean animate) { back.recover(animate); }
	public void reset() { back.reset(); }
	public boolean commitBack() {
		if (back.isTracking()) { back.commit(); return true; }
		return back.isBusy();
	}
}
