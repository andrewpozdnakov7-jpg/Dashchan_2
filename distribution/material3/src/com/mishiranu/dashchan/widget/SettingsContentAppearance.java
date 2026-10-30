package com.mishiranu.dashchan.widget;

import android.graphics.Color;
import android.content.res.ColorStateList;
import android.graphics.drawable.RippleDrawable;
import android.graphics.drawable.Drawable;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.CompoundButton;
import android.widget.AutoCompleteTextView;
import android.widget.LinearLayout;
import android.widget.TextView;
import com.mishiranu.dashchan.R;
import java.util.ArrayList;

/** Explicit consumers only. No networking, values, click handlers or semantic-image styling. */
final class SettingsContentAppearance {
	private enum Kind {CHOICE, HEADING, CARD, AUTOHIDE, MIXED, DESCRIPTION, DOWNLOAD}
	private static final class State {
		final View root;
		final ArrayList<StandardAppearance.Saved> saved = new ArrayList<>();
		Kind kind; TextView title, metadata; CompoundButton check; boolean quick, applied;
		View spacer; int spacerVisibility;
		AutoCompleteTextView pathField; Drawable popupBackground;
		State(View root) { this.root = root; remember(root); }
		void remember(View view) {
			if (view == null) return;
			for (StandardAppearance.Saved old : saved) if (old.view == view) return;
			saved.add(new StandardAppearance.Saved(view));
		}
		void apply(boolean enabled) {
			if (applied) {
				for (StandardAppearance.Saved old : saved) {
					// Form children own their field/control rendering. The parent changes only
					// their spacing; restoring backgrounds or singleLine here would undo that
					// rendering and could disturb an editor's selection/IME.
					if ((kind == Kind.AUTOHIDE || kind == Kind.DOWNLOAD) && old.view != root && old.view != title) {
						if (old.view instanceof LinearLayout || old.view.getId() == R.id.error_text || old.view.getId() == R.id.matcher_result)
							old.restoreSpacing();
						else old.restoreMargins();
					}
					else { old.restore(); old.restoreHeight(); old.restoreTextBounds(); }
				}
				if (spacer != null) spacer.setVisibility(spacerVisibility);
				if (pathField != null) pathField.setDropDownBackgroundDrawable(popupBackground);
			}
			applied = enabled;
			if (!enabled) return;
			AppearanceColors c = StandardAppearance.colors(root.getContext());
			if (kind == Kind.CHOICE) {
				check.setMinHeight(0); check.setMinimumHeight(dp(root, AppearanceShapes.STANDARD_TARGET));
				check.setMinimumWidth(dp(root, AppearanceShapes.STANDARD_TARGET));
				check.setSingleLine(false); check.setMaxLines(Integer.MAX_VALUE); check.setEllipsize(null);
				check.setGravity((check.getGravity() & ~Gravity.VERTICAL_GRAVITY_MASK) | Gravity.CENTER_VERTICAL);
				check.setPaddingRelative(check.getPaddingStart(), dp(root, AppearanceShapes.SPACE_8),
						check.getPaddingEnd(), dp(root, AppearanceShapes.SPACE_8));
				if (check.getLayoutParams() != null) check.getLayoutParams().height = ViewGroup.LayoutParams.WRAP_CONTENT;
				check.setBackgroundTintList(null); check.setBackground(StandardAppearance.rowBackground(check, c));
			} else if (kind == Kind.HEADING) {
				title.setMinHeight(0); title.setMinimumHeight(dp(root, AppearanceShapes.STANDARD_TARGET));
				title.setSingleLine(false); title.setMaxLines(Integer.MAX_VALUE); title.setEllipsize(null);
				title.setGravity(Gravity.START | Gravity.CENTER_VERTICAL);
				title.setPaddingRelative(dp(root, AppearanceShapes.SPACE_8), dp(root, AppearanceShapes.SPACE_8),
						dp(root, AppearanceShapes.SPACE_8), dp(root, AppearanceShapes.SPACE_8));
				if (title.getLayoutParams() != null) title.getLayoutParams().height = ViewGroup.LayoutParams.WRAP_CONTENT;
				title.setBackgroundTintList(null); title.setBackground(StandardAppearance.rowBackground(title, c));
			} else if (kind == Kind.CARD) {
				title.setSingleLine(false); title.setMaxLines(Integer.MAX_VALUE);
				metadata.setSingleLine(false); metadata.setMaxLines(Integer.MAX_VALUE);
				root.setPadding(0, 0, 0, 0); root.setBackgroundTintList(null);
				root.setBackground(new RippleDrawable(ColorStateList.valueOf(c.ripple),
						StandardAppearance.shape(root, c.surfaceContainer, AppearanceShapes.MEDIUM),
						StandardAppearance.shape(root, Color.WHITE, AppearanceShapes.MEDIUM)));
				if (root.getLayoutParams() instanceof ViewGroup.MarginLayoutParams) {
					ViewGroup.MarginLayoutParams p = (ViewGroup.MarginLayoutParams) root.getLayoutParams();
					p.setMargins(dp(root, AppearanceShapes.SPACE_8), dp(root, AppearanceShapes.SPACE_8),
							dp(root, AppearanceShapes.SPACE_8), dp(root, AppearanceShapes.SPACE_8));
				}
				title.setPaddingRelative(dp(root, AppearanceShapes.SPACE_16), dp(root, AppearanceShapes.SPACE_12), dp(root, AppearanceShapes.SPACE_16), 0);
				metadata.setPaddingRelative(dp(root, AppearanceShapes.SPACE_16), dp(root, AppearanceShapes.SPACE_4), dp(root, AppearanceShapes.SPACE_16), dp(root, AppearanceShapes.SPACE_12));
				title.setEllipsize(null); metadata.setEllipsize(null);
				title.setTextColor(StandardAppearance.textColors(InterfaceAppearance.contrasting(c.onSurface, c.surfaceContainer, 4.5), c.surfaceContainer));
				metadata.setTextColor(StandardAppearance.textColors(InterfaceAppearance.contrasting(c.onSurfaceVariant, c.surfaceContainer, 4.5), c.surfaceContainer));
			} else if (kind == Kind.MIXED) {
				root.setBackground(StandardAppearance.shape(root, check.isEnabled() ? c.primary :
						com.mishiranu.dashchan.util.OpaqueColors.blend(c.surface, c.onSurface, .38f), 0));
			} else if (kind == Kind.DESCRIPTION) {
				title.setPaddingRelative(0, 0, 0, dp(root, AppearanceShapes.SPACE_8));
				title.setMinHeight(0); title.setMinimumHeight(0);
				title.setSingleLine(false); title.setMaxLines(Integer.MAX_VALUE); title.setEllipsize(null);
			} else if (kind == Kind.DOWNLOAD) {
				root.setPaddingRelative(dp(root, 24), quick ? 0 : dp(root, 24), dp(root, 24), dp(root, 8));
				ViewGroup group = (ViewGroup) root; boolean first = true;
				for (int i = 0; i < group.getChildCount(); i++) {
					View child = group.getChildAt(i);
					if (child == spacer || child.getVisibility() == View.GONE) continue;
					if (child.getLayoutParams() instanceof ViewGroup.MarginLayoutParams) {
						ViewGroup.MarginLayoutParams p = (ViewGroup.MarginLayoutParams) child.getLayoutParams();
						p.setMarginStart(0); p.setMarginEnd(0); p.topMargin = first ? 0 : dp(root, 8); p.bottomMargin = 0;
					}
					if (child instanceof LinearLayout) child.setPadding(0, 0, 0, 0);
					first = false;
				}
				if (spacer != null) spacer.setVisibility(View.GONE);
				if (pathField != null) pathField.setDropDownBackgroundDrawable(StandardAppearance.shape(root, c.surfaceContainer, AppearanceShapes.MEDIUM));
			} else if (kind == Kind.AUTOHIDE) {
				root.setPaddingRelative(dp(root, AppearanceShapes.SPACE_24), quick ? 0 : dp(root, AppearanceShapes.SPACE_24),
						dp(root, AppearanceShapes.SPACE_24), dp(root, AppearanceShapes.SPACE_8));
				ViewGroup group = (ViewGroup) root; boolean first = true;
				for (int i = 0; i < group.getChildCount(); i++) {
					View child = group.getChildAt(i);
					if (child.getLayoutParams() instanceof ViewGroup.MarginLayoutParams) {
						ViewGroup.MarginLayoutParams p = (ViewGroup.MarginLayoutParams) child.getLayoutParams();
						p.setMarginStart(0); p.setMarginEnd(0);
						p.topMargin = first ? 0 : dp(root, AppearanceShapes.SPACE_8); p.bottomMargin = 0;
					}
					if (child instanceof LinearLayout) child.setPadding(0, 0, 0, 0);
					if (child.getVisibility() != View.GONE) first = false;
				}
				if (title != null) {
					title.setMinHeight(0); title.setMinimumHeight(dp(root, AppearanceShapes.STANDARD_TARGET));
					title.getLayoutParams().height = ViewGroup.LayoutParams.WRAP_CONTENT;
					title.setPadding(0, 0, 0, 0); title.setEllipsize(null);
					title.setBackgroundTintList(null); title.setBackground(StandardAppearance.rowBackground(title, c));
				}
				for (int id : new int[]{R.id.error_text, R.id.matcher_result}) {
					View semantic = root.findViewById(id);
					if (semantic != null) semantic.setPadding(0, 0, 0, 0);
				}
				if (spacer != null) spacer.setVisibility(View.GONE);
			}
		}
	}
	private static int dp(View v, int value) { return StandardAppearance.dp(v, value); }
	private static State state(View root, Kind kind) {
		Object tag = root.getTag(R.id.settings_content_appearance);
		State state = tag instanceof State ? (State) tag : new State(root);
		state.kind = kind; root.setTag(R.id.settings_content_appearance, state); return state;
	}
	static void choice(CompoundButton button) {
		State state = state(button, Kind.CHOICE); state.check = button;
		InterfaceAppearance.standardCustomContent(button); state.apply(InterfaceAppearance.isEnabled(button.getContext()));
	}
	static void heading(TextView text) {
		State state = state(text, Kind.HEADING); state.title = text;
		InterfaceAppearance.standardContentHeading(text); state.apply(InterfaceAppearance.isEnabled(text.getContext()));
	}
	static void description(TextView text) {
		State state = state(text, Kind.DESCRIPTION); state.title = text;
		InterfaceAppearance.standardRole(text, InterfaceAppearance.Role.DIALOG_BODY);
		state.apply(InterfaceAppearance.isEnabled(text.getContext()));
	}
	static void download(ViewGroup root, boolean titled) {
		State state = state(root, Kind.DOWNLOAD); state.quick = titled;
		for (int i = 0; i < root.getChildCount(); i++) state.remember(root.getChildAt(i));
		state.spacer = root.findViewById(R.id.download_width_spacer);
		if (state.spacer != null && !state.applied) state.spacerVisibility = state.spacer.getVisibility();
		AutoCompleteTextView field = root.findViewById(android.R.id.text1);
		if (field != null && state.pathField == null) { state.pathField = field; state.popupBackground = field.getDropDownBackground(); }
		InterfaceAppearance.standardCustomContent(root);
		for (int i = 0; i < root.getChildCount(); i++) {
			View child = root.getChildAt(i);
			if (child instanceof TextView && !(child instanceof CompoundButton) && !(child instanceof android.widget.EditText))
				InterfaceAppearance.standardRole(child, InterfaceAppearance.Role.DIALOG_BODY);
		}
		styleChoices(root);
		state.apply(InterfaceAppearance.isEnabled(root.getContext()));
	}
	static void card(View root, TextView title, TextView metadata) {
		State state = state(root, Kind.CARD); state.title = title; state.metadata = metadata;
		state.remember(title); state.remember(metadata);
		InterfaceAppearance.standardRole(title, InterfaceAppearance.Role.CATALOG_TITLE);
		InterfaceAppearance.standardRole(metadata, InterfaceAppearance.Role.SERVICE_META);
		state.apply(InterfaceAppearance.isEnabled(root.getContext()));
	}
	static void mixed(View indicator, CompoundButton check) {
		State state = state(indicator, Kind.MIXED); state.check = check;
		state.apply(InterfaceAppearance.isEnabled(indicator.getContext()));
	}
	static void autohide(View outer, boolean quick) {
		View root = quick ? outer : outer.findViewById(R.id.autohide_form_content);
		State state = state(root, Kind.AUTOHIDE); state.quick = quick;
		ViewGroup group = (ViewGroup) root;
		for (int i = 0; i < group.getChildCount(); i++) state.remember(group.getChildAt(i));
		state.title = root.findViewById(R.id.chan_name);
		state.spacer = root.findViewById(R.id.autohide_width_spacer);
		if (state.spacer != null && !state.applied) state.spacerVisibility = state.spacer.getVisibility();
		View error = root.findViewById(R.id.error_text), matcher = root.findViewById(R.id.matcher_result);
		InterfaceAppearance.standardCustomContent(root, error, matcher, state.title);
		TextView caption = root.findViewById(R.id.quick_filter_instructions);
		if (caption != null) InterfaceAppearance.standardCustomCaption(caption);
		if (state.title != null) InterfaceAppearance.standardContentHeading(state.title);
		styleChoices(root);
		state.apply(InterfaceAppearance.isEnabled(root.getContext()));
	}
	private static void styleChoices(View view) {
		if (view instanceof ScopeCheckBox) ((ScopeCheckBox) view).refreshAppearance();
		else if (view instanceof CompoundButton) choice((CompoundButton) view);
		else if (view instanceof ViewGroup) for (int i = 0; i < ((ViewGroup) view).getChildCount(); i++)
			styleChoices(((ViewGroup) view).getChildAt(i));
	}
	static void refresh(View view) { resolve(view, InterfaceAppearance.isEnabled(view.getContext())); }
	static void resolve(View view, boolean enabled) {
		Object tag = view.getTag(R.id.settings_content_appearance);
		if (tag instanceof State) ((State) tag).apply(enabled);
	}
}
