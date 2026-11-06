package com.mishiranu.dashchan.widget;

import android.widget.TextView;
import com.mishiranu.dashchan.content.Preferences;

/** History and its settings preview share the drawer scale and the existing font-only scaler. */
public final class HistoryTextAppearance {
	private HistoryTextAppearance() {}

	public static void row(ViewFactory.TwoLinesViewHolder holder) {
		row(holder, Preferences.getDrawerTextScalePercent());
	}

	public static void row(ViewFactory.TwoLinesViewHolder holder, int percent) {
		SettingsTextAppearance.restore(holder.text1, holder.text2, holder.text2End);
		InterfaceAppearance.serviceRow(holder.view, holder.text1, holder.text2, holder.text2End);
		SettingsTextAppearance.apply(percent, holder.text1, holder.text2, holder.text2End);
		if (holder.text2.getParent() instanceof AdaptiveMetadataLayout) {
			((AdaptiveMetadataLayout) holder.text2.getParent())
					.setWrapWhenLegacy(percent != Preferences.DEFAULT_TEXT_SCALE);
		}
	}

	public static void header(TextView text) {
		header(text, Preferences.getDrawerTextScalePercent());
	}

	public static void header(TextView text, int percent) {
		SettingsTextAppearance.apply(percent, text);
	}
}
