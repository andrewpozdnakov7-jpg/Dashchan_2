package com.mishiranu.dashchan.widget;

import android.content.res.ColorStateList;
import android.graphics.Rect;
import android.graphics.drawable.ClipDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.LayerDrawable;
import android.view.Gravity;
import android.widget.SeekBar;

/** Shared slider geometry. The caller owns its snapshot; this helper never changes values or listeners. */
final class StandardSlider {
	private StandardSlider() {}

	static void apply(SeekBar seek, ColorStateList trackColor, ColorStateList progressColor,
			ColorStateList thumbColor, int minimumHeight) {
		GradientDrawable track = shape(seek, 4, trackColor);
		track.setSize(StandardAppearance.dp(seek, 1), StandardAppearance.dp(seek, 6));
		GradientDrawable progress = shape(seek, 4, progressColor);
		progress.setSize(StandardAppearance.dp(seek, 1), StandardAppearance.dp(seek, 6));
		// AbsSeekBar mirrors its progress canvas in RTL; keep the shared clip in that canvas's coordinates.
		LayerDrawable layers = new LayerDrawable(new Drawable[] {track,
				new ClipDrawable(progress, Gravity.LEFT, ClipDrawable.HORIZONTAL)});
		layers.setId(0, android.R.id.background); layers.setId(1, android.R.id.progress);
		GradientDrawable thumb = shape(seek, 10, thumbColor);
		thumb.setSize(StandardAppearance.dp(seek, 20), StandardAppearance.dp(seek, 20));
		seek.setProgressTintList(null); seek.setProgressBackgroundTintList(null); seek.setThumbTintList(null);
		seek.setProgressDrawable(layers);
		seek.setMinHeight(StandardAppearance.dp(seek, 6)); seek.setMaxHeight(StandardAppearance.dp(seek, 6));
		seek.setMinimumHeight(Math.max(minimumHeight, StandardAppearance.dp(seek, 48)));
		seek.setThumb(thumb); seek.setSplitTrack(false); seek.setThumbOffset(StandardAppearance.dp(seek, 10));
	}

	private static GradientDrawable shape(SeekBar seek, int radius, ColorStateList color) {
		GradientDrawable drawable = new GradientDrawable();
		drawable.setCornerRadius(StandardAppearance.dp(seek, radius)); drawable.setColor(color);
		return drawable;
	}

	static final class Snapshot {
		final Drawable progress, thumb;
		final Rect progressBounds, thumbBounds;
		final ColorStateList progressTint, backgroundTint, thumbTint;
		final int thumbOffset, minHeight, maxHeight, width, height, value, secondaryValue;
		final boolean splitTrack;

		Snapshot(SeekBar seek) {
			progress = seek.getProgressDrawable(); thumb = seek.getThumb();
			progressBounds = progress != null ? new Rect(progress.getBounds()) : null;
			thumbBounds = thumb != null ? new Rect(thumb.getBounds()) : null;
			progressTint = seek.getProgressTintList(); backgroundTint = seek.getProgressBackgroundTintList();
			thumbTint = seek.getThumbTintList(); thumbOffset = seek.getThumbOffset(); splitTrack = seek.getSplitTrack();
			minHeight = seek.getMinHeight(); maxHeight = seek.getMaxHeight(); width = seek.getWidth(); height = seek.getHeight();
			value = seek.getProgress(); secondaryValue = seek.getSecondaryProgress();
		}

		void restore(SeekBar seek) {
			seek.setProgressDrawable(progress); seek.setMinHeight(minHeight); seek.setMaxHeight(maxHeight); seek.setThumb(thumb);
			seek.setProgressTintList(progressTint); seek.setProgressBackgroundTintList(backgroundTint);
			seek.setThumbTintList(thumbTint); seek.setThumbOffset(thumbOffset); seek.setSplitTrack(splitTrack);
			// Layout or user input may change while Material is active. Do not restore an old thumb position.
			if (width == seek.getWidth() && height == seek.getHeight()
					&& value == seek.getProgress() && secondaryValue == seek.getSecondaryProgress()) {
				if (progress != null) progress.setBounds(progressBounds);
				if (thumb != null) thumb.setBounds(thumbBounds);
			}
		}
	}
}
