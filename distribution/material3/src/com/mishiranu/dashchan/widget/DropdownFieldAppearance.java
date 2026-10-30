package com.mishiranu.dashchan.widget;

import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.Spinner;
import android.widget.TextView;
import com.mishiranu.dashchan.R;

/** Closed selector uses the same field role as its neighboring EditTexts; popup stays SecureSpinner-owned. */
final class DropdownFieldAppearance {
	private static final class State {
		final StandardAppearance.Saved root, spinner;
		final EditText field;
		AppearanceColors palette;
		AppearanceTypography.Configuration typography;
		boolean applied, initialized;
		int direction;
		State(DropdownView root, Spinner spinner) {
			this.root = new StandardAppearance.Saved(root); this.spinner = new StandardAppearance.Saved(spinner);
			field = new EditText(root.getContext()); ThemeEngine.applyStyle(field);
		}
	}
	private static final class LabelState {
		final StandardAppearance.Saved saved;
		AppearanceColors palette;
		AppearanceTypography.Configuration typography;
		boolean applied, initialized;
		LabelState(View view) { saved = new StandardAppearance.Saved(view); }
	}
	static void apply(DropdownView root, Spinner spinner, View selected) {
		Object tag = root.getTag(R.id.dropdown_field_appearance);
		State state = tag instanceof State ? (State) tag : new State(root, spinner);
		root.setTag(R.id.dropdown_field_appearance, state);
		boolean enabled = InterfaceAppearance.isEnabled(root.getContext());
		AppearanceColors palette = enabled ? StandardAppearance.colors(root.getContext()) : null;
		AppearanceTypography.Configuration typography = enabled ? new AppearanceTypography.Configuration(root.getContext()) : null;
		if (!state.initialized || enabled != state.applied || !java.util.Objects.equals(palette, state.palette)
				|| !java.util.Objects.equals(typography, state.typography)
				|| state.direction != root.getLayoutDirection()) {
			if (state.applied) { state.root.restore(); state.spinner.restore(); }
			state.applied = enabled; state.initialized = true; state.palette = palette; state.direction = root.getLayoutDirection();
			state.typography = typography;
			if (enabled) {
				InterfaceAppearance.standardCustomContent(state.field);
				root.setBackgroundTintList(null); root.setBackground(state.field.getBackground()); root.setPadding(0, 0, 0, 0);
				root.setMinimumHeight(state.field.getMinimumHeight());
				spinner.setBackgroundTintList(null); spinner.setBackground(null);
				spinner.setPaddingRelative(StandardAppearance.dp(root, 12), StandardAppearance.dp(root, 10),
						StandardAppearance.dp(root, 40), StandardAppearance.dp(root, 10));
				spinner.setMinimumHeight(StandardAppearance.dp(root, 48));
				if (spinner.getLayoutParams() instanceof ViewGroup.MarginLayoutParams) {
					ViewGroup.MarginLayoutParams params = (ViewGroup.MarginLayoutParams) spinner.getLayoutParams();
					params.setMarginStart(0); params.setMarginEnd(0); params.topMargin = params.bottomMargin = 0;
				}
				root.setStandardArrow(StandardAppearance.textColors(palette.onSurfaceVariant, palette.surface));
			} else root.setStandardArrow(null);
		}
		if (selected instanceof TextView) {
			Object labelTag = selected.getTag(R.id.dropdown_field_appearance);
			LabelState label = labelTag instanceof LabelState ? (LabelState) labelTag : new LabelState(selected);
			selected.setTag(R.id.dropdown_field_appearance, label);
			InterfaceAppearance.standardSpinnerField(selected);
			// Spinner asks the adapter for temporary measurement views as well as its real child.
			// Rebinding must not restore/reapply the whole root and schedule another layout forever.
			if (label.initialized && label.applied == enabled && java.util.Objects.equals(label.palette, palette)
					&& java.util.Objects.equals(label.typography, typography)) return;
			if (label.applied) { label.saved.restore(); label.saved.restoreTextBounds(); }
			label.initialized = true; label.applied = enabled; label.palette = palette;
			label.typography = typography;
			if (!enabled) return;
			TextView text = (TextView) selected;
			text.setPadding(0, 0, 0, 0); text.setMinHeight(0); text.setMinimumHeight(0);
			text.setSingleLine(false); text.setMaxLines(Integer.MAX_VALUE); text.setEllipsize(null);
		}
	}
}
