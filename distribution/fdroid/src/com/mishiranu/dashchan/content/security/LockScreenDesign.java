package com.mishiranu.dashchan.content.security;

import android.app.Activity;
import android.view.Gravity;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.ScrollView;

/** No experimental security artwork or motion in this source set. */
final class LockScreenDesign extends LockScreenForm {
	LockScreenDesign(Activity activity) {
		super(activity);
		int padding = Math.round(24 * activity.getResources().getDisplayMetrics().density);
		LinearLayout layout = new LinearLayout(activity);
		layout.setOrientation(LinearLayout.VERTICAL); layout.setGravity(Gravity.CENTER);
		layout.setPadding(padding, padding, padding, padding);
		title.setTextSize(22); layout.addView(title);
		status.setPadding(0, padding, 0, padding); layout.addView(status);
		layout.addView(input, new LinearLayout.LayoutParams(-1, -2));
		layout.addView(submit); layout.addView(biometric); layout.addView(usePin); layout.addView(additional);
		ScrollView scroll = new ScrollView(activity); scroll.setFillViewport(true);
		scroll.addView(layout, new ScrollView.LayoutParams(-1, -2)); root = scroll;
	}
	@Override void render(int heading, int description, int button, boolean pin, boolean bio,
			boolean extra, boolean welcome, boolean pinFallback) {
		pin |= welcome && pinFallback;
		title.setText(heading); status.setText(description); submit.setText(button);
		input.setVisibility(pin ? View.VISIBLE : View.GONE);
		submit.setVisibility(pin ? View.VISIBLE : View.GONE);
		biometric.setVisibility(bio ? View.VISIBLE : View.GONE);
		additional.setVisibility(extra ? View.VISIBLE : View.GONE);
	}
}
