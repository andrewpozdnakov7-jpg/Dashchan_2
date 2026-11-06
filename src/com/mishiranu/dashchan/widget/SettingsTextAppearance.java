package com.mishiranu.dashchan.widget;

import android.text.TextUtils;
import android.text.method.TransformationMethod;
import android.util.TypedValue;
import android.widget.TextView;
import com.mishiranu.dashchan.R;
import com.mishiranu.dashchan.content.Preferences;

/** Font-only scaling for preferences and opted-in service labels. Restore before rebinding. */
public final class SettingsTextAppearance {
	private SettingsTextAppearance() {}

	private static final class State {
		final float size, extra, multiplier;
		final int maxLines, maxHeight;
		final boolean singleLine;
		final TextUtils.TruncateAt ellipsize;
		final TransformationMethod transformation;
		boolean applied;

		State(TextView view) {
			size = view.getTextSize(); extra = view.getLineSpacingExtra(); multiplier = view.getLineSpacingMultiplier();
			maxLines = view.getMaxLines(); maxHeight = view.getMaxHeight(); singleLine = view.isSingleLine();
			ellipsize = view.getEllipsize(); transformation = view.getTransformationMethod();
		}

		void restore(TextView view) {
			if (!applied) return;
			view.setTextSize(TypedValue.COMPLEX_UNIT_PX, size);
			view.setLineSpacing(extra, multiplier);
			view.setSingleLine(singleLine);
			if (maxLines >= 0) view.setMaxLines(maxLines);
			else view.setMaxHeight(maxHeight);
			view.setEllipsize(ellipsize);
			view.setTransformationMethod(transformation);
			applied = false;
		}
	}

	public static void restore(TextView... views) {
		for (TextView view : views) if (view != null) {
			Object tag = view.getTag(R.id.settings_text_scale_state);
			if (tag instanceof State) ((State) tag).restore(view);
		}
	}

	/** Apply after the current theme's typography and layout, also usable by a detached preview. */
	public static void apply(int percent, TextView... views) {
		float scale = Math.max(Preferences.MIN_TEXT_SCALE, Math.min(percent, Preferences.MAX_TEXT_SCALE)) / 100f;
		for (TextView view : views) if (view != null) {
			restore(view);
			State state = new State(view);
			view.setTag(R.id.settings_text_scale_state, state);
			if (scale == 1f) continue;
			view.setTextSize(TypedValue.COMPLEX_UNIT_PX, state.size * scale);
			view.setLineSpacing(state.extra * scale, state.multiplier);
			view.setSingleLine(false);
			view.setMaxLines(Integer.MAX_VALUE);
			view.setEllipsize(null);
			state.applied = true;
		}
	}
}
