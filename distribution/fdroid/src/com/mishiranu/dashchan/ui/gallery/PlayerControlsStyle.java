package com.mishiranu.dashchan.ui.gallery;

import android.content.Context;
import android.graphics.drawable.GradientDrawable;
import android.view.View;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.SeekBar;
import android.widget.TextView;

/** Unavailable experimental presentation contract. VideoUnit uses its legacy layout in F-Droid. */
final class PlayerControlsStyle {
	private PlayerControlsStyle() {}

	static int dp(float density, float value) { return Math.round(density * value); }
	static View createBar(Context context, ImageButton play, LinearLayout time, SeekBar seek,
			View[] actions) { return null; }
	static View createButtonGrid(Context context, java.util.List<View> buttons, int columns) { return null; }
	static GradientDrawable panel(float density, float radius) { return null; }
	static void text(TextView view, int size) {}
	static void button(ImageButton view, float density, boolean center) {}
	static void playbackReadiness(ImageButton view, boolean retainAppearance) {}
	static void slider(SeekBar view, float density) {}
}
