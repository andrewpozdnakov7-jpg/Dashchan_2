package com.mishiranu.dashchan.widget;

import android.app.AlertDialog;
import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.Point;
import android.graphics.Rect;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.InsetDrawable;
import android.graphics.drawable.RippleDrawable;
import android.graphics.drawable.StateListDrawable;
import android.view.ContextThemeWrapper;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ListAdapter;
import android.widget.ListView;
import android.widget.SearchView;
import android.widget.TextView;
import android.widget.Toolbar;
import com.mishiranu.dashchan.R;
import com.mishiranu.dashchan.content.Preferences;
import com.mishiranu.dashchan.util.OpaqueColors;
import com.mishiranu.dashchan.util.ResourceUtils;
import com.mishiranu.dashchan.util.ViewUtils;
import java.util.ArrayList;

/** Scoped layout adapters for stages 3–5. Palette/type/shape ownership stays in stage 2. */
final class StandardAppearance {
	private StandardAppearance() {}
	static int dp(View view, float value) { return Math.round(value * ResourceUtils.obtainDensity(view)); }
	static AppearanceColors colors(Context context) {
		ThemeEngine.Theme theme = ThemeEngine.getTheme(context);
		return AppearanceColors.standardInterface(theme.base == ThemeEngine.Theme.Base.DARK,
				theme.window, theme.accent, ResourceUtils.getColor(context, android.R.attr.textColorPrimary),
				ResourceUtils.getColor(context, R.attr.colorTextError));
	}
	static GradientDrawable shape(View view, int color, float radius) {
		GradientDrawable result = new GradientDrawable(); result.setColor(color);
		result.setCornerRadius(dp(view, radius)); return result;
	}
	static ColorStateList textColors(int text, int surface) {
		return new ColorStateList(new int[][]{{-android.R.attr.state_enabled}, {}},
				new int[]{OpaqueColors.blend(surface, text, .38f), text});
	}
	static ColorStateList serviceTextColors(int normal, int background, AppearanceColors c) {
		int selected = InterfaceAppearance.contrasting(normal, c.primaryContainer, 4.5);
		return new ColorStateList(new int[][]{{-android.R.attr.state_enabled}, {android.R.attr.state_checked},
				{android.R.attr.state_activated}, {android.R.attr.state_selected}, {}}, new int[]{
				com.mishiranu.dashchan.util.OpaqueColors.blend(background, normal, .38f), selected, selected, selected, normal});
	}
	static Drawable rowBackground(View view, AppearanceColors c) {
		StateListDrawable fill = new StateListDrawable();
		fill.addState(new int[]{-android.R.attr.state_enabled}, shape(view, Color.TRANSPARENT, 0));
		fill.addState(new int[]{android.R.attr.state_checked}, shape(view, c.primaryContainer, 0));
		fill.addState(new int[]{android.R.attr.state_selected}, shape(view, c.primaryContainer, 0));
		fill.addState(new int[]{android.R.attr.state_activated}, shape(view, c.primaryContainer, 0));
		fill.addState(new int[]{}, shape(view, Color.TRANSPARENT, 0));
		return new RippleDrawable(new ColorStateList(new int[][]{{-android.R.attr.state_enabled}, {}},
				new int[]{Color.TRANSPARENT, c.ripple}), fill, shape(view, Color.WHITE, 0));
	}

	private enum Kind {SETTINGS, SECTION, SERVICE, MENU, MODAL_CHOICE, STATE, ERROR, PAGE, INPUT, MESSAGE, SPINNER, CUSTOM_CONTENT, CUSTOM_UNTITLED, CUSTOM_STACK, CUSTOM_ROWS, CUSTOM_CHOICE_ROW}
	static final class Saved {
		final View view; final int start, top, end, bottom, minHeight, minWidth, height;
		final int marginStart, marginEnd, marginTop, marginBottom, textMinHeight, textMinWidth, textMaxWidth;
		final boolean duplicateParent;
		final int textMinLines, textMaxLines, textMaxHeight, gravity, textMinEms, textMaxEms;
		final boolean hadLayoutParams, textSingleLine;
		final Drawable background; final ColorStateList backgroundTint;
		final ColorStateList compoundTint, checkMarkTint;
		final android.text.TextUtils.TruncateAt ellipsize;
		final android.text.method.TransformationMethod transformation;
		Saved(View view) {
			this.view = view; start = view.getPaddingStart(); top = view.getPaddingTop();
			end = view.getPaddingEnd(); bottom = view.getPaddingBottom(); minHeight = view.getMinimumHeight();
			minWidth = view.getMinimumWidth(); background = view.getBackground(); backgroundTint = view.getBackgroundTintList();
			textMinHeight = view instanceof TextView ? ((TextView) view).getMinHeight() : -1;
			textMinLines = view instanceof TextView ? ((TextView) view).getMinLines() : -1;
			textMaxLines = view instanceof TextView ? ((TextView) view).getMaxLines() : -1;
			textMaxHeight = view instanceof TextView ? ((TextView) view).getMaxHeight() : -1;
			textSingleLine = view instanceof TextView && ((TextView) view).isSingleLine();
			textMinEms = view instanceof TextView ? ((TextView) view).getMinEms() : -1;
			textMaxEms = view instanceof TextView ? ((TextView) view).getMaxEms() : -1;
			gravity = view instanceof TextView ? ((TextView) view).getGravity() : 0;
			textMinWidth = view instanceof TextView ? ((TextView) view).getMinWidth() : -1;
			textMaxWidth = view instanceof TextView ? ((TextView) view).getMaxWidth() : -1;
			duplicateParent = view.isDuplicateParentStateEnabled();
			compoundTint = view instanceof TextView ? ((TextView) view).getCompoundDrawableTintList() : null;
			checkMarkTint = view instanceof android.widget.CheckedTextView ? ((android.widget.CheckedTextView) view).getCheckMarkTintList() : null;
			ellipsize = view instanceof TextView ? ((TextView) view).getEllipsize() : null;
			transformation = view instanceof TextView ? ((TextView) view).getTransformationMethod() : null;
			ViewGroup.LayoutParams params = view.getLayoutParams(); height = params != null ? params.height : 0;
			hadLayoutParams = params != null;
			ViewGroup.MarginLayoutParams margin = params instanceof ViewGroup.MarginLayoutParams ? (ViewGroup.MarginLayoutParams) params : null;
			marginStart = margin != null ? margin.getMarginStart() : 0; marginEnd = margin != null ? margin.getMarginEnd() : 0;
			marginTop = margin != null ? margin.topMargin : 0; marginBottom = margin != null ? margin.bottomMargin : 0;
		}
		void restore() {
			restoreSpacing(); view.setMinimumHeight(minHeight); view.setMinimumWidth(minWidth);
			if (textMinHeight >= 0) ((TextView) view).setMinHeight(textMinHeight);
			else if (textMinLines >= 0) ((TextView) view).setMinLines(textMinLines);
			if (view instanceof TextView) ((TextView) view).setGravity(gravity);
			if (textMinWidth >= 0) ((TextView) view).setMinWidth(textMinWidth);
			else if (textMinEms >= 0) ((TextView) view).setMinEms(textMinEms);
			if (textMaxWidth >= 0) ((TextView) view).setMaxWidth(textMaxWidth);
			else if (textMaxEms >= 0) ((TextView) view).setMaxEms(textMaxEms);
			view.setDuplicateParentStateEnabled(duplicateParent);
			if (view instanceof TextView) ((TextView) view).setCompoundDrawableTintList(compoundTint);
			if (view instanceof TextView) ((TextView) view).setEllipsize(ellipsize);
			if (view instanceof android.widget.CheckedTextView) ((android.widget.CheckedTextView) view).setCheckMarkTintList(checkMarkTint);
			view.setBackground(background); view.setBackgroundTintList(backgroundTint);
		}
		void restoreSpacing() {
			view.setPaddingRelative(start, top, end, bottom);
			restoreMargins();
		}
		void restoreMargins() {
			ViewGroup.LayoutParams params = view.getLayoutParams();
			if (params instanceof ViewGroup.MarginLayoutParams) {
				ViewGroup.MarginLayoutParams m = (ViewGroup.MarginLayoutParams) params;
				m.setMarginStart(marginStart); m.setMarginEnd(marginEnd); m.topMargin = marginTop; m.bottomMargin = marginBottom;
			}
		}
		void restoreHeight() {
			if (hadLayoutParams && view.getLayoutParams() != null) view.getLayoutParams().height = height;
		}
		void restoreTextBounds() {
			if (!(view instanceof TextView)) return;
			TextView text = (TextView) view;
			text.setSingleLine(textSingleLine);
			if (textMinHeight >= 0) text.setMinHeight(textMinHeight);
			else if (textMinLines >= 0) text.setMinLines(textMinLines);
			if (textMaxLines >= 0) text.setMaxLines(textMaxLines);
			else if (textMaxHeight >= 0) text.setMaxHeight(textMaxHeight);
			text.setTransformationMethod(transformation);
		}
	}
	private static final class Layout {
		final View root; final ArrayList<Saved> saved = new ArrayList<>();
		final java.util.IdentityHashMap<View, Integer> stackMargins = new java.util.IdentityHashMap<>();
		Kind kind; TextView title, summary, end; View widgets; boolean applied;
		View adapterContent;
		ImageView errorIcon; int errorIconWidth, errorIconHeight;
		Layout(View root) { this.root = root; remember(root); }
		void remember(View view) {
			if (view == null) return;
			for (Saved s : saved) if (s.view == view) return;
			saved.add(new Saved(view));
		}
		void apply() {
			applyResolved(InterfaceAppearance.isEnabled(root.getContext()));
		}
		void applyResolved(boolean enabled) {
			if (applied) for (Saved s : saved) s.restore();
			boolean choice = kind == Kind.MENU || kind == Kind.MODAL_CHOICE || kind == Kind.CUSTOM_CHOICE_ROW;
			if (applied && choice) for (Saved s : saved) s.restoreHeight();
			if (applied) for (java.util.Map.Entry<View, Integer> entry : stackMargins.entrySet()) {
				ViewGroup.LayoutParams p = entry.getKey().getLayoutParams();
				if (p instanceof ViewGroup.MarginLayoutParams) ((ViewGroup.MarginLayoutParams) p).topMargin = entry.getValue();
			}
			if (applied && errorIcon != null) {
				ViewGroup.LayoutParams params = errorIcon.getLayoutParams();
				params.width = errorIconWidth; params.height = errorIconHeight;
				errorIcon.setLayoutParams(params);
			}
			applied = enabled;
			if (!applied) return;
			AppearanceColors c = colors(root.getContext());
			if (kind == Kind.SPINNER) {
				root.setMinimumHeight(dp(root, 48));
				root.setPaddingRelative(dp(root, 24), dp(root, 8), dp(root, 24), dp(root, 8));
				root.setBackgroundTintList(null); root.setBackground(rowBackground(root, c));
				styleSpinnerLabels(widgets, root.isEnabled(), c);
				if (title != null) { title.setPadding(0, 0, 0, 0); title.setMinHeight(0); title.setMinimumHeight(0); }
				if (end instanceof android.widget.CheckedTextView) ((android.widget.CheckedTextView) end).setCheckMarkTintList(choiceColors(c));
				return;
			}
			if (kind == Kind.MESSAGE) {
				title.setPaddingRelative(dp(root, 24), dp(root, 24), dp(root, 24), dp(root, 16));
				ViewGroup actions = (ViewGroup) widgets;
				summary.setPaddingRelative(dp(root, 24), title.getVisibility() == View.GONE ? dp(root, 24) : 0,
						dp(root, 24), dp(root, actions.getChildCount() == 0 ? 24 : 16));
				title.setTextColor(textColors(InterfaceAppearance.contrasting(c.onSurface, c.surfaceContainer, 4.5), c.surfaceContainer));
				summary.setTextColor(title.getTextColors());
				widgets.setPaddingRelative(dp(root, 24), 0, dp(root, 24), dp(root, actions.getChildCount() == 0 ? 0 : 16));
				for (int i = 0; i < actions.getChildCount(); i++) {
					Button button = (Button) actions.getChildAt(i);
					button.setMinHeight(dp(root, 48)); button.setMinimumHeight(dp(root, 48));
					button.setMinWidth(dp(root, 48)); button.setMinimumWidth(dp(root, 48));
					button.setTextColor(textColors(InterfaceAppearance.contrasting(c.primary, c.surfaceContainer, 4.5), c.surfaceContainer));
					button.setBackgroundTintList(null);
					button.setBackground(new RippleDrawable(ColorStateList.valueOf(c.ripple), null, shape(button, Color.WHITE, 28)));
					button.setPaddingRelative(dp(root, 12), dp(root, 4), dp(root, 12), dp(root, 4));
				}
				return;
			}
			if (kind == Kind.PAGE) { root.setBackgroundTintList(null); root.setBackgroundColor(c.window); return; }
			if (kind == Kind.CUSTOM_CONTENT || kind == Kind.CUSTOM_UNTITLED || kind == Kind.CUSTOM_STACK || kind == Kind.CUSTOM_ROWS) {
				if (kind != Kind.CUSTOM_ROWS) root.setPaddingRelative(dp(root, 24), kind == Kind.CUSTOM_UNTITLED ? dp(root, 24) : 0, dp(root, 24), dp(root, 8));
				if (kind == Kind.CUSTOM_STACK || kind == Kind.CUSTOM_ROWS) {
					ViewGroup group = (ViewGroup) root; boolean first = true;
					for (int i = 0; i < group.getChildCount(); i++) {
						View child = group.getChildAt(i); if (child.getVisibility() == View.GONE) continue;
						if (child.getLayoutParams() instanceof ViewGroup.MarginLayoutParams) {
							ViewGroup.MarginLayoutParams p = (ViewGroup.MarginLayoutParams) child.getLayoutParams();
							p.topMargin = first ? 0 : dp(root, child instanceof Button ? 16 : 8);
						}
						first = false;
					}
				}
				return;
			}
			if (kind == Kind.INPUT) { root.setMinimumHeight(dp(root, AppearanceShapes.STANDARD_FIELD_MIN_HEIGHT)); return; }
			if (kind == Kind.STATE) {
				// ExpandedLayout remains the sole owner of the host's system/toolbar insets.
				title.setPaddingRelative(dp(root, 24), dp(root, 24), dp(root, 24), dp(root, 24));
				return;
			}
			if (kind == Kind.ERROR) {
				// Group owns outer vertical breathing room; the icon/text gap has ONE owner.
				root.setPaddingRelative(0, dp(root, 24), 0, dp(root, 24));
				title.setPaddingRelative(dp(root, 24), 0, dp(root, 24), 0);
				if (errorIcon != null) {
					ViewGroup.LayoutParams params = errorIcon.getLayoutParams();
					params.width = dp(root, 40); params.height = dp(root, 40);
					errorIcon.setLayoutParams(params);
					((ViewGroup.MarginLayoutParams) title.getLayoutParams()).topMargin = dp(root, 12);
				}
				return;
			}
			if (kind == Kind.SECTION) {
				root.setBackgroundTintList(null); root.setBackground(null); root.setMinimumHeight(0);
				root.setPaddingRelative(dp(root, 16), dp(root, 16), dp(root, 16), dp(root, 8));
				title.setPadding(0, 0, 0, 0); title.setMinHeight(0); title.setMinimumHeight(0); return;
			}
			boolean summaryVisible = summary != null && summary.getVisibility() != View.GONE && summary.length() > 0;
			root.setMinimumHeight(dp(root, kind == Kind.SETTINGS ? summaryVisible
					? AppearanceShapes.SETTING_SUMMARY_MIN_HEIGHT : AppearanceShapes.SETTING_MIN_HEIGHT
					: summaryVisible ? AppearanceShapes.SERVICE_METADATA_MIN_HEIGHT : AppearanceShapes.SERVICE_MIN_HEIGHT));
			root.setBackgroundTintList(null); root.setBackground(rowBackground(root, c));
			View textContainer = title != null && title != root && title.getParent() instanceof View
					? (View) title.getParent() : root;
			// Cookie titles have a small icon row; metadata is its sibling. The common
			// content container, not that icon row, owns the vertical padding.
			if (kind == Kind.SERVICE && summary != null) {
				while (textContainer != root && !contains(textContainer, summary)
						&& textContainer.getParent() instanceof View) textContainer = (View) textContainer.getParent();
			}
			int horizontal = kind == Kind.CUSTOM_CHOICE_ROW || kind == Kind.MODAL_CHOICE ? 24 : 16;
			if (textContainer != root) {
				root.setPaddingRelative(dp(root, horizontal), 0, dp(root, horizontal), 0);
				textContainer.setPaddingRelative(0, dp(root, 8), 0, dp(root, 8));
			} else root.setPaddingRelative(dp(root, horizontal), dp(root, 8), dp(root, horizontal), dp(root, 8));
			if (choice && title != null) {
				// Only the row owns the 48dp target. Native labels can carry two minima.
				if (title != root) title.setPadding(0, 0, 0, 0);
				title.setMinHeight(0); title.setMinimumHeight(title == root ? dp(root, 48) : 0);
				title.setGravity((title.getGravity() & ~android.view.Gravity.VERTICAL_GRAVITY_MASK) | android.view.Gravity.CENTER_VERTICAL);
				if (title.getLayoutParams() != null) title.getLayoutParams().height = ViewGroup.LayoutParams.WRAP_CONTENT;
				if (root.getLayoutParams() != null) root.getLayoutParams().height = ViewGroup.LayoutParams.WRAP_CONTENT;
			}
			if (kind == Kind.SERVICE || choice) {
				for (TextView text : new TextView[]{title, summary, end}) {
					if (text == null) continue;
					if (text != root) text.setDuplicateParentStateEnabled(text.isEnabled() == root.isEnabled());
					int normal = text == title ? c.onSurface : c.onSurfaceVariant;
					int surface = kind == Kind.MODAL_CHOICE ? c.surfaceContainer : c.surface;
					if (kind == Kind.MODAL_CHOICE) normal = InterfaceAppearance.contrasting(normal, surface, 4.5);
					text.setTextColor(serviceTextColors(normal, surface, c));
					if (kind == Kind.CUSTOM_CHOICE_ROW && text instanceof android.widget.CheckedTextView)
						((android.widget.CheckedTextView) text).setCheckMarkTintList(choiceColors(c));
				}
			}
			if (summary != null && summary.getLayoutParams() instanceof ViewGroup.MarginLayoutParams) {
				((ViewGroup.MarginLayoutParams) summary.getLayoutParams()).topMargin = summaryVisible ? dp(root, 2) : 0;
			}
			if (widgets != null && widgets.getLayoutParams() instanceof ViewGroup.MarginLayoutParams) {
				((ViewGroup.MarginLayoutParams) widgets.getLayoutParams()).setMarginStart(dp(root, 12));
			}
		}
	}
	private static boolean contains(View parent, View child) {
		for (View current = child; current != null;) {
			if (current == parent) return true;
			current = current.getParent() instanceof View ? (View) current.getParent() : null;
		}
		return false;
	}
	private static void layout(View root, Kind kind, TextView title, TextView summary, TextView end) {
		if (root == null) return;
		Object tag = root.getTag(R.id.standard_layout_appearance);
		Layout state = tag instanceof Layout ? (Layout) tag : new Layout(root);
		state.kind = kind; state.title = title; state.summary = summary; state.end = end;
		state.remember(title); state.remember(summary); state.remember(end);
		if (title != null && title != root && title.getParent() instanceof View) state.remember((View) title.getParent());
		state.remember(root.findViewById(android.R.id.widget_frame));
		root.setTag(R.id.standard_layout_appearance, state); state.apply();
	}
	/** Capture legacy geometry BEFORE the compatibility row renderer changes its background. */
	static void prepare(View root, TextView title, TextView summary, TextView end, View widgets) {
		if (root == null) return;
		Object tag = root.getTag(R.id.standard_layout_appearance);
		Layout state = tag instanceof Layout ? (Layout) tag : new Layout(root);
		state.remember(title); state.remember(summary); state.remember(end); state.remember(widgets);
		if (title != null && title != root && title.getParent() instanceof View) state.remember((View) title.getParent());
		root.setTag(R.id.standard_layout_appearance, state);
	}
	static void settings(View root, TextView title, TextView summary, View widgets, boolean header) {
		layout(root, header ? Kind.SECTION : Kind.SETTINGS, title, summary, null);
		Layout state = (Layout) root.getTag(R.id.standard_layout_appearance);
		state.remember(widgets); state.widgets = widgets; state.apply();
	}
	static void service(View root, TextView title, TextView metadata, TextView end) { layout(root, Kind.SERVICE, title, metadata, end); }
	static void menu(View root, TextView title) { layout(root, Kind.MENU, title, null, null); }
	static void modalChoice(View root, TextView title) { layout(root, Kind.MODAL_CHOICE, title, null, null); }
	static void customInsets(View root) { layout(root, Kind.CUSTOM_CONTENT, null, null, null); }
	static void untitledInsets(View root) { layout(root, Kind.CUSTOM_UNTITLED, null, null, null); }
	static void customStack(ViewGroup root, boolean outerInsets) {
		// Capture child margins before opting in; OFF restores them through the same layout state.
		prepare(root, null, null, null, null);
		Layout state = (Layout) root.getTag(R.id.standard_layout_appearance);
		state.stackMargins.keySet().removeIf(child -> child.getParent() != root);
		for (int i = 0; i < root.getChildCount(); i++) {
			View child = root.getChildAt(i);
			if (!state.stackMargins.containsKey(child) && child.getLayoutParams() instanceof ViewGroup.MarginLayoutParams)
				state.stackMargins.put(child, ((ViewGroup.MarginLayoutParams) child.getLayoutParams()).topMargin);
		}
		state.kind = outerInsets ? Kind.CUSTOM_STACK : Kind.CUSTOM_ROWS; state.apply();
	}
	static void customChoiceRow(View root, TextView title) { layout(root, Kind.CUSTOM_CHOICE_ROW, title, null, null); }
	static void messageContent(View root, TextView title, TextView message, ViewGroup actions) {
		prepare(root, title, message, null, actions);
		Layout state = (Layout) root.getTag(R.id.standard_layout_appearance);
		state.kind = Kind.MESSAGE; state.title = title; state.summary = message; state.widgets = actions;
		InterfaceAppearance.standardRole(title, InterfaceAppearance.Role.DIALOG_TITLE);
		InterfaceAppearance.standardRole(message, InterfaceAppearance.Role.DIALOG_BODY);
		for (int i = 0; i < actions.getChildCount(); i++) {
			View button = actions.getChildAt(i); state.remember(button);
			InterfaceAppearance.standardRole(button, InterfaceAppearance.Role.STANDARD_BUTTON);
		}
		state.apply();
	}
	/** Only labels receive typography. Semantic bitmaps and compound drawables keep their colors. */
	static void spinnerText(View view) {
		if (view instanceof TextView && !(view instanceof EditText)) {
			InterfaceAppearance.standardRole(view, InterfaceAppearance.Role.CUSTOM_CHOICE_TEXT);
		} else if (view instanceof ViewGroup) {
			ViewGroup group = (ViewGroup) view;
			for (int i = 0; i < group.getChildCount(); i++) spinnerText(group.getChildAt(i));
		}
	}
	static void spinnerChoice(View root, View content, TextView marker) {
		root.setTag(R.id.standard_spinner_choice, Boolean.TRUE);
		View label = content instanceof ViewGroup && ((ViewGroup) content).getChildCount() == 1
				? ((ViewGroup) content).getChildAt(0) : content;
		prepare(root, null, null, marker, content);
		Layout state = (Layout) root.getTag(R.id.standard_layout_appearance);
		if (state.adapterContent != label) {
			// Adapters may replace even their convertView. Do not retain detached row trees.
			state.saved.removeIf(s -> s.view != root && s.view != content && s.view != marker);
			state.adapterContent = label;
		}
		rememberSpinnerLabels(state, content);
		state.kind = Kind.SPINNER; state.title = label instanceof TextView ? (TextView) label : null;
		state.widgets = content; state.end = marker;
		spinnerText(content); state.apply();
	}
	private static void rememberSpinnerLabels(Layout state, View view) {
		if (view instanceof TextView) state.remember(view);
		else if (view instanceof ViewGroup) {
			ViewGroup group = (ViewGroup) view;
			for (int i = 0; i < group.getChildCount(); i++) rememberSpinnerLabels(state, group.getChildAt(i));
		}
	}
	static void resetSpinnerChoice(View root) {
		Object tag = root.getTag(R.id.standard_layout_appearance);
		if (!(tag instanceof Layout) || ((Layout) tag).kind != Kind.SPINNER) return;
		Layout state = (Layout) tag;
		for (Saved saved : state.saved) saved.restore();
		resetSpinnerLabels(state.widgets);
		state.saved.clear(); state.remember(root); state.applied = false; state.adapterContent = null;
	}
	private static void resetSpinnerLabels(View view) {
		if (view instanceof TextView && !(view instanceof EditText)) {
			Object tag = view.getTag(R.id.interface_appearance_state);
			if (tag instanceof InterfaceAppearance.State) {
				((InterfaceAppearance.State) tag).restore(view);
				view.setTag(R.id.interface_appearance_state, null);
			}
		} else if (view instanceof ViewGroup) {
			ViewGroup group = (ViewGroup) view;
			for (int i = 0; i < group.getChildCount(); i++) resetSpinnerLabels(group.getChildAt(i));
		}
	}
	private static void styleSpinnerLabels(View view, boolean enabled, AppearanceColors c) {
		view.setEnabled(enabled);
		if (view instanceof TextView && !(view instanceof EditText)) {
			TextView text = (TextView) view;
			text.setSingleLine(false); text.setMaxLines(Integer.MAX_VALUE); text.setEllipsize(null);
			text.setTextColor(textColors(InterfaceAppearance.contrasting(c.onSurface, c.surfaceContainer, 4.5), c.surfaceContainer));
			if (text instanceof android.widget.CheckedTextView) ((android.widget.CheckedTextView) text).setCheckMarkTintList(choiceColors(c));
		} else if (view instanceof ViewGroup) {
			ViewGroup group = (ViewGroup) view;
			for (int i = 0; i < group.getChildCount(); i++) styleSpinnerLabels(group.getChildAt(i), enabled, c);
		}
	}
	static void state(View root, TextView message) { layout(root, Kind.STATE, message, null, null); }
	static void errorState(View root, ImageView icon, TextView message) {
		layout(root, Kind.ERROR, message, null, null);
		Layout state = (Layout) root.getTag(R.id.standard_layout_appearance);
		if (state.errorIcon == null) {
			state.errorIcon = icon; state.errorIconWidth = icon.getLayoutParams().width;
			state.errorIconHeight = icon.getLayoutParams().height;
		}
		state.apply();
	}
	static void page(View root) { layout(root, Kind.PAGE, null, null, null); }
	/** Detached fixture seam; does not modify app preferences, callbacks or values. */
	static void resolveLayout(View root, boolean enabled) {
		Object tag = root.getTag(R.id.standard_layout_appearance); if (tag instanceof Layout) ((Layout) tag).applyResolved(enabled);
	}
	static void resolveDialog(AlertDialog dialog, boolean enabled) {
		Object state = dialog.getWindow().getDecorView().getTag(R.id.standard_dialog_appearance);
		if (state instanceof DialogState) ((DialogState) state).applyResolved(enabled);
	}
	static void refresh(View view) {
		Object tag = view.getTag(R.id.standard_layout_appearance); if (tag instanceof Layout) ((Layout) tag).apply();
		Object toolbar = view.getTag(R.id.standard_toolbar_appearance); if (toolbar instanceof ToolbarState) ((ToolbarState) toolbar).apply();
		Object search = view.getTag(R.id.standard_search_appearance); if (search instanceof SearchState) ((SearchState) search).apply();
		Object loading = view.getTag(R.id.standard_loading_appearance); if (loading instanceof LoadingState) ((LoadingState) loading).apply();
		Object dialog = view.getTag(R.id.standard_dialog_appearance); if (dialog instanceof DialogState) ((DialogState) dialog).apply();
		ServiceAppearance.refresh(view);
		SettingsContentAppearance.refresh(view);
		NativeContentAppearance.refresh(view);
		if (view instanceof ProgressDialog.Content) ((ProgressDialog.Content) view).refreshAppearance();
		if (view instanceof MessageDialog.Content) ((MessageDialog.Content) view).refreshAppearance();
		if (view instanceof SecureSpinner.ChoiceRow) ((SecureSpinner.ChoiceRow) view).refreshAppearance();
		if (view instanceof com.mishiranu.dashchan.chan.e444.E444PostView)
			((com.mishiranu.dashchan.chan.e444.E444PostView) view).refreshAppearance();
	}

	static Context dialogContext(Context context) {
		return InterfaceAppearance.isEnabled(context) ? new ContextThemeWrapper(context, R.style.Theme_Standard_Dialog) : context;
	}
	static void dialog(AlertDialog dialog) {
		dialog(dialog, false);
	}
	static void dialog(AlertDialog dialog, boolean page) {
		installDialog(dialog, page);
	}
	static void textDialog(android.app.Dialog dialog) { installDialog(dialog, false); }
	private static void installDialog(android.app.Dialog dialog, boolean page) {
		Window window = dialog.getWindow(); if (window == null) return;
		View decor = window.getDecorView();
		DialogState state = new DialogState(dialog, page);
		decor.setTag(R.id.standard_dialog_appearance, state);
		// WindowManager must receive final bounds/background on its initial addView.
		// Do not eagerly call dialog.create(): callers may still set buttons/views.
		state.prepareWindow();
		decor.addOnAttachStateChangeListener(new View.OnAttachStateChangeListener() {
			@Override public void onViewAttachedToWindow(View view) {
				// AlertController installed its content; initial measure/relayout has not run.
				state.apply();
			}
			@Override public void onViewDetachedFromWindow(View view) {}
		});
	}
	static void dialogMenu(AlertDialog dialog, View menu) {
		Window window = dialog.getWindow(); if (window == null) return;
		Object tag = window.getDecorView().getTag(R.id.standard_dialog_appearance);
		if (tag instanceof DialogState) {
			DialogState state = (DialogState) tag; state.menu = menu; state.prepareWindow();
		}
	}
	private static final class DialogState {
		final android.app.Dialog dialog; final ArrayList<Saved> buttons = new ArrayList<>();
		final boolean page;
		ButtonBarState bar;
		TitlePanelState titlePanel;
		Saved listPadding;
		View menu;
		Drawable background; int width, height, preparedWidth, preparedColor, preparedRadius;
		boolean applied, windowOwned, backgroundPrepared;
		DialogState(android.app.Dialog dialog, boolean page) { this.dialog = dialog; this.page = page; }
		void prepareWindow() {
			prepareWindow(InterfaceAppearance.isEnabled(dialog.getContext()));
		}
		void prepareWindow(boolean enabled) {
			Window window = dialog.getWindow();
			if (com.mishiranu.dashchan.ui.posting.PostingFormDiagnostics.isRecording()) {
				com.mishiranu.dashchan.util.ScreenDiagnostics.note(window, "standard_prepare",
						"effective=" + InterfaceAppearance.isEnabled(dialog.getContext()) + " menu=" + (menu != null));
			}
			if (window == null || !enabled) return;
			View decor = window.getDecorView();
			if (!windowOwned) {
				background = decor.getBackground(); width = window.getAttributes().width; height = window.getAttributes().height;
				windowOwned = true;
			}
			AppearanceColors c = colors(dialog.getContext());
			int radius = page ? 0 : menu != null ? 12 : 28;
			Point available = ViewUtils.getWindowContentSize(dialog.getContext());
			int margin = dp(decor, available.x < dp(decor, 328) ? 16 : 24);
			preparedWidth = page ? available.x : Math.max(1, Math.min(dp(decor, AppearanceShapes.STANDARD_DIALOG_MAX_WIDTH), available.x - 2 * margin));
			int surface = page ? c.window : c.surfaceContainer;
			if (!backgroundPrepared || preparedColor != surface || preparedRadius != radius) {
				window.setBackgroundDrawable(shape(decor, surface, radius));
				preparedColor = surface; preparedRadius = radius; backgroundPrepared = true;
			}
			int targetWidth = page ? WindowManager.LayoutParams.MATCH_PARENT : preparedWidth;
			int targetHeight = page ? WindowManager.LayoutParams.MATCH_PARENT : WindowManager.LayoutParams.WRAP_CONTENT;
			if (window.getAttributes().width != targetWidth || window.getAttributes().height != targetHeight) {
				window.setLayout(targetWidth, targetHeight);
			}
		}
		void apply() {
			applyResolved(InterfaceAppearance.isEnabled(dialog.getContext()));
		}
		void applyResolved(boolean enabled) {
			Window window = dialog.getWindow(); if (window == null) return;
			if (!enabled) {
				if (windowOwned) {
					window.setBackgroundDrawable(background); window.setLayout(width, height);
					windowOwned = false; backgroundPrepared = false;
				}
				if (applied && dialog instanceof AlertDialog) {
					if (bar != null) bar.restore();
					for (Saved button : buttons) { button.restore(); button.restoreHeight(); }
					if (titlePanel != null) titlePanel.restore();
					if (listPadding != null) listPadding.restore();
					ListView list = ((AlertDialog) dialog).getListView();
					if (list != null && list.getAdapter() instanceof ChoiceAdapter) {
						replaceAdapter(list, ((ChoiceAdapter) list.getAdapter()).delegate);
					}
					applied = false;
				}
				return;
			}
			prepareWindow(enabled);
			if (!(dialog instanceof AlertDialog)) return;
			AlertDialog alertDialog = (AlertDialog) dialog;
			if (!applied) {
				for (int which : new int[]{AlertDialog.BUTTON_POSITIVE, AlertDialog.BUTTON_NEGATIVE, AlertDialog.BUTTON_NEUTRAL}) {
					Button button = alertDialog.getButton(which); if (button != null && buttons.stream().noneMatch(s -> s.view == button)) buttons.add(new Saved(button));
				}
			}
			applied = true; applyDialog(alertDialog);
			if (com.mishiranu.dashchan.ui.posting.PostingFormDiagnostics.isRecording()) {
				com.mishiranu.dashchan.util.ScreenDiagnostics.note(window, "standard_applied",
						"widthPx=" + preparedWidth + " radiusDp=" + preparedRadius);
			}
		}
	}
	/** Only the direct parent of our own title and decorative spacer views are adjusted. */
	private static final class TitlePanelState {
		final Saved parent;
		final ArrayList<View> spacers = new ArrayList<>();
		final ArrayList<Integer> visibility = new ArrayList<>();
		TitlePanelState(ViewGroup panel, View title) {
			parent = new Saved(panel);
			for (int i = 0; i < panel.getChildCount(); i++) {
				View child = panel.getChildAt(i);
				if (child != title && (child instanceof android.widget.Space || child.getClass() == View.class)
						&& !child.isClickable() && !child.isFocusable()) {
					spacers.add(child); visibility.add(child.getVisibility());
				}
			}
		}
		void apply() {
			parent.view.setPadding(0, 0, 0, 0);
			parent.view.setMinimumHeight(0);
			if (parent.view.getLayoutParams() != null) parent.view.getLayoutParams().height = ViewGroup.LayoutParams.WRAP_CONTENT;
			for (View spacer : spacers) spacer.setVisibility(View.GONE);
		}
		void restore() {
			parent.restore(); parent.restoreHeight(); for (int i = 0; i < spacers.size(); i++) spacers.get(i).setVisibility(visibility.get(i));
		}
	}
	/** Only public button references and a validated LinearLayout parent; no internal IDs/order assumptions. */
	private static final class ButtonBarState {
		final LinearLayout parent; final int orientation, gravity;
		final Saved saved, scroll;
		final ArrayList<View> children = new ArrayList<>();
		final ArrayList<LinearLayout.LayoutParams> params = new ArrayList<>();
		final ArrayList<Integer> visibility = new ArrayList<>();
		ButtonBarState(LinearLayout parent) {
			this.parent = parent; orientation = parent.getOrientation(); gravity = parent.getGravity();
			saved = new Saved(parent);
			View p = parent.getParent() instanceof android.widget.ScrollView ? (View) parent.getParent() : null;
			scroll = p instanceof ViewGroup && ((ViewGroup) p).getChildCount() == 1 ? new Saved(p) : null;
			for (int i = 0; i < parent.getChildCount(); i++) {
				View child = parent.getChildAt(i); children.add(child);
				params.add(new LinearLayout.LayoutParams((LinearLayout.LayoutParams) child.getLayoutParams())); visibility.add(child.getVisibility());
			}
		}
		void restore() {
			saved.restore(); if (scroll != null) scroll.restore();
			parent.setOrientation(orientation); parent.setGravity(gravity);
			for (int i = 0; i < children.size(); i++) {
				View child = children.get(i); child.setLayoutParams(new LinearLayout.LayoutParams(params.get(i)));
				if (!(child instanceof Button)) child.setVisibility(visibility.get(i));
			}
		}
		void reflow(int width) {
			restore();
			parent.setMinimumHeight(0);
			// 12dp container + 12dp button text inset = the content's 24dp edge.
			parent.setPaddingRelative(dp(parent, 12), dp(parent, 8), dp(parent, 12), dp(parent, 12));
			if (scroll != null) scroll.view.setPadding(0, 0, 0, 0);
			int desired = 0, visibleButtons = 0;
			for (View child : children) if (child instanceof Button && child.getVisibility() != View.GONE) {
				Button button = (Button) child;
				button.getLayoutParams().height = ViewGroup.LayoutParams.WRAP_CONTENT;
				desired += Math.max(dp(parent, 48), (int) Math.ceil(android.text.Layout.getDesiredWidth(button.getText(), button.getPaint())) + dp(parent, 24));
				visibleButtons++;
			}
			if (desired + Math.max(0, visibleButtons - 1) * dp(parent, 8) <= width) return;
			parent.setOrientation(LinearLayout.VERTICAL); parent.setGravity(android.view.Gravity.END);
			for (View child : children) {
				if (child instanceof Button) {
					LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
					p.topMargin = dp(parent, 4); p.bottomMargin = dp(parent, 4); child.setLayoutParams(p);
					((Button) child).setMaxWidth(Math.max(1, width));
				} else child.setVisibility(View.GONE);
			}
		}
	}
	private static void applyDialog(AlertDialog dialog) {
		Window window = dialog.getWindow(); View decor = window.getDecorView(); AppearanceColors c = colors(dialog.getContext());
		Object windowState = decor.getTag(R.id.standard_dialog_appearance);
		int width = windowState instanceof DialogState ? ((DialogState) windowState).preparedWidth : window.getAttributes().width;
		TextView message = dialog.findViewById(android.R.id.message);
		if (message != null) {
			InterfaceAppearance.standardRole(message, InterfaceAppearance.Role.DIALOG_BODY);
			message.setTextColor(InterfaceAppearance.contrasting(c.onSurface, c.surfaceContainer, 4.5));
		}
		TextView title = dialog.findViewById(R.id.standard_dialog_title);
		if (title != null) {
			InterfaceAppearance.standardRole(title, InterfaceAppearance.Role.DIALOG_TITLE);
			title.setTextColor(InterfaceAppearance.contrasting(c.onSurface, c.surfaceContainer, 4.5));
		}
		int buttonCount = 0;
		for (int which : new int[]{AlertDialog.BUTTON_POSITIVE, AlertDialog.BUTTON_NEGATIVE, AlertDialog.BUTTON_NEUTRAL}) {
			Button button = dialog.getButton(which); if (button != null && button.getVisibility() != View.GONE) buttonCount++;
		}
		for (int which : new int[]{AlertDialog.BUTTON_POSITIVE, AlertDialog.BUTTON_NEGATIVE, AlertDialog.BUTTON_NEUTRAL}) {
			Button button = dialog.getButton(which); if (button == null) continue;
			InterfaceAppearance.standardRole(button, InterfaceAppearance.Role.STANDARD_BUTTON);
			button.setSingleLine(false); button.setMaxLines(Integer.MAX_VALUE); button.setAllCaps(false);
			button.setMinHeight(dp(button, 48)); button.setMinimumHeight(dp(button, 48));
			if (button.getLayoutParams() != null) button.getLayoutParams().height = ViewGroup.LayoutParams.WRAP_CONTENT;
			button.setMinimumWidth(0); button.setMinWidth(0);
			button.setMaxWidth(Math.max(1, (width - dp(decor, 48)) / Math.max(1, buttonCount) - dp(decor, 8)));
			boolean destructive = which == AlertDialog.BUTTON_POSITIVE && Boolean.TRUE.equals(decor.getTag(R.id.standard_destructive_action));
			button.setTextColor(textColors(InterfaceAppearance.contrasting(destructive ? c.error : c.primary, c.surfaceContainer, 4.5), c.surfaceContainer));
			button.setBackgroundTintList(null); button.setBackground(new RippleDrawable(ColorStateList.valueOf(c.ripple), null,
					new InsetDrawable(shape(button, Color.WHITE, 28), 0, dp(button, 4), 0, dp(button, 4))));
			button.setPaddingRelative(dp(button, 12), dp(button, 4), dp(button, 12), dp(button, 4));
		}
		Object dialogTag = decor.getTag(R.id.standard_dialog_appearance);
		if (dialogTag instanceof DialogState) {
			DialogState state = (DialogState) dialogTag;
			if (title != null && title.getParent() instanceof LinearLayout
					&& ((LinearLayout) title.getParent()).getOrientation() == LinearLayout.VERTICAL) {
				if (state.titlePanel == null) state.titlePanel = new TitlePanelState((ViewGroup) title.getParent(), title);
				state.titlePanel.apply();
			}
			ListView choiceList = dialog.getListView();
			if (choiceList != null) {
				if (state.listPadding == null) state.listPadding = new Saved(choiceList);
				// Header and button bar already own the outside spacing; do not stack native list padding.
				choiceList.setPaddingRelative(choiceList.getPaddingStart(), title != null ? 0 : dp(choiceList, 16),
						choiceList.getPaddingEnd(), buttonCount > 0 ? 0 : dp(choiceList, 16));
			}
			if (state.bar == null) {
				Button first = dialog.getButton(AlertDialog.BUTTON_POSITIVE);
				if (first == null) first = dialog.getButton(AlertDialog.BUTTON_NEGATIVE);
				if (first != null && first.getParent() instanceof LinearLayout) {
					LinearLayout parent = (LinearLayout) first.getParent(); boolean valid = true;
					for (int i = 0; i < parent.getChildCount(); i++) {
						View child = parent.getChildAt(i);
						if (!(child instanceof Button) && !(child instanceof android.widget.Space)
								&& !(child.getClass() == View.class && !child.isClickable() && !child.isFocusable()
								&& ((LinearLayout.LayoutParams) child.getLayoutParams()).weight > 0)) valid = false;
					}
					if (valid) state.bar = new ButtonBarState(parent);
				}
			}
			if (state.bar != null) state.bar.reflow(Math.max(1, width - dp(decor, 24)));
		}
		View custom = dialog.findViewById(android.R.id.custom); styleDialogInputs(custom);
		ListView list = dialog.getListView();
		if (list != null && list.getAdapter() != null && !(list.getAdapter() instanceof ChoiceAdapter)) {
			replaceAdapter(list, new ChoiceAdapter(list.getAdapter()));
		}
	}
	private static void replaceAdapter(ListView list, ListAdapter adapter) {
		android.util.SparseBooleanArray checked = list.getCheckedItemPositions() != null ? list.getCheckedItemPositions().clone() : null;
		int first = list.getFirstVisiblePosition(), selected = list.getSelectedItemPosition();
		int anchor = selected >= first && selected < first + list.getChildCount() ? selected : first;
		View row = list.getChildAt(anchor - first); int top = row != null ? row.getTop() : 0;
		list.setAdapter(adapter);
		if (checked != null) for (int i = 0; i < checked.size(); i++) list.setItemChecked(checked.keyAt(i), checked.valueAt(i));
		if (anchor >= 0) list.setSelectionFromTop(anchor, top);
	}
	private static void styleDialogInputs(View view) {
		if (view == null) return;
		if (view instanceof EditText) {
			InterfaceAppearance.field((EditText) view); layout(view, Kind.INPUT, null, null, null);
		} else if (view instanceof android.widget.CompoundButton) InterfaceAppearance.check((android.widget.CompoundButton) view);
		else if (view instanceof android.widget.SeekBar) InterfaceAppearance.slider((android.widget.SeekBar) view);
		if (view instanceof ViewGroup) {
			ViewGroup group = (ViewGroup) view; for (int i = 0; i < group.getChildCount(); i++) styleDialogInputs(group.getChildAt(i));
		}
	}
	private static final class ChoiceAdapter implements android.widget.WrapperListAdapter {
		final ListAdapter delegate;
		ChoiceAdapter(ListAdapter delegate) { this.delegate = delegate; }
		@Override public ListAdapter getWrappedAdapter() { return delegate; }
		@Override public int getCount() { return delegate.getCount(); }
		@Override public Object getItem(int position) { return delegate.getItem(position); }
		@Override public long getItemId(int position) { return delegate.getItemId(position); }
		@Override public boolean hasStableIds() { return delegate.hasStableIds(); }
		@Override public int getItemViewType(int position) { return delegate.getItemViewType(position); }
		@Override public int getViewTypeCount() { return delegate.getViewTypeCount(); }
		@Override public boolean isEmpty() { return delegate.isEmpty(); }
		@Override public boolean areAllItemsEnabled() { return delegate.areAllItemsEnabled(); }
		@Override public boolean isEnabled(int position) { return delegate.isEnabled(position); }
		@Override public void registerDataSetObserver(android.database.DataSetObserver observer) { delegate.registerDataSetObserver(observer); }
		@Override public void unregisterDataSetObserver(android.database.DataSetObserver observer) { delegate.unregisterDataSetObserver(observer); }
		@Override public View getView(int position, View convertView, ViewGroup parent) {
			View row = delegate.getView(position, convertView, parent);
			if (Boolean.TRUE.equals(row.getTag(R.id.standard_spinner_choice))) return row;
			TextView text = row instanceof TextView ? (TextView) row : row.findViewById(android.R.id.text1);
			if (text != null) {
				InterfaceAppearance.modalChoiceRow(row, text);
				if (InterfaceAppearance.isEnabled(row.getContext())) {
					AppearanceColors c = colors(row.getContext());
					ColorStateList tint = choiceColors(c);
					if (text instanceof android.widget.CheckedTextView) ((android.widget.CheckedTextView) text).setCheckMarkTintList(tint);
				}
			}
			return row;
		}
	}

	private static final class ToolbarState {
		final Toolbar toolbar; final TextView title, subtitle; final Saved saved, parent;
		final int popupTheme; final int height; final ArrayList<IconSaved> icons = new ArrayList<>();
		final ArrayList<ToolbarTextSaved> texts = new ArrayList<>();
		final ToolbarTextSaved titleColors, subtitleColors;
		ToolbarState(Toolbar toolbar, TextView title, TextView subtitle) {
			this.toolbar = toolbar; this.title = title; this.subtitle = subtitle; saved = new Saved(toolbar);
			parent = toolbar.getParent() instanceof View ? new Saved((View) toolbar.getParent()) : null;
			popupTheme = toolbar.getPopupTheme(); height = toolbar.getLayoutParams() != null ? toolbar.getLayoutParams().height : -2;
			titleColors = title != null ? new ToolbarTextSaved(title) : null;
			subtitleColors = subtitle != null ? new ToolbarTextSaved(subtitle) : null;
		}
		void apply() {
			boolean enabled = InterfaceAppearance.isEnabled(toolbar.getContext());
			if (!enabled) {
				saved.restore(); if (parent != null) parent.restore();
				setHeight(height);
				if (titleColors != null) titleColors.restore();
				if (subtitleColors != null) subtitleColors.restore();
				if (toolbar.getPopupTheme() != popupTheme) toolbar.setPopupTheme(popupTheme);
				for (IconSaved icon : icons) icon.restore(); for (ToolbarTextSaved text : texts) text.restore(); return;
			}
			ToolbarPalette palette = ToolbarPalette.resolve(toolbar.getContext());
			toolbar.setBackgroundTintList(null); toolbar.setBackgroundColor(palette.background);
			if (parent != null) { parent.view.setBackgroundTintList(null); parent.view.setBackgroundColor(palette.background); }
			toolbar.setMinimumHeight(dp(toolbar, AppearanceShapes.STANDARD_TOOLBAR_MIN_HEIGHT));
			// Preserve the original height budget used by the user's adaptive title fitting.
			// Unlimited WRAP_CONTENT let a three-line title stay at its largest size.
			setHeight(Preferences.isToolbarTitleCustomizationEnabled() ? height : ViewGroup.LayoutParams.WRAP_CONTENT);
			// ToolbarTitleView is the sole owner of size/wrapping/line metrics. Do not
			// reapply a fixed 20sp/24sp role after its measurement; keep the selected font.
			if (title != null) title.setTextColor(textColors(palette.title, palette.background));
			InterfaceAppearance.standardRole(subtitle, InterfaceAppearance.Role.TOOLBAR_SUBTITLE);
			if (subtitle != null) subtitle.setTextColor(textColors(palette.subtitle, palette.background));
			if (toolbar.getPopupTheme() != R.style.Theme_Standard_Popup) {
				toolbar.setPopupTheme(R.style.Theme_Standard_Popup);
			}
			for (int i = 0; i < toolbar.getChildCount(); i++) tintToolbarChild(toolbar.getChildAt(i), palette.icon, palette.background);
		}
		void setHeight(int height) {
			ViewGroup.LayoutParams params = toolbar.getLayoutParams();
			if (params != null && params.height != height) { params.height = height; toolbar.setLayoutParams(params); }
		}
		void tintToolbarChild(View view, int color, int surface) {
			if (view instanceof CustomSearchView || view == title || view == subtitle) return;
			if (view instanceof ImageView) {
				ImageView image = (ImageView) view; boolean found = false;
				for (IconSaved icon : icons) if (icon.image == image) { found = true; break; }
				if (!found) icons.add(new IconSaved(image));
				image.setImageTintList(textColors(color, surface)); image.setMinimumWidth(dp(view, 48)); image.setMinimumHeight(dp(view, 48));
			} else if (view instanceof TextView && !(view instanceof EditText)) {
				TextView text = (TextView) view; boolean found = false;
				for (ToolbarTextSaved old : texts) if (old.view == text) { found = true; break; }
				if (!found) texts.add(new ToolbarTextSaved(text));
				((TextView) view).setTextColor(textColors(color, surface));
				((TextView) view).setCompoundDrawableTintList(textColors(color, surface));
			} else if (view instanceof ViewGroup) {
				ViewGroup group = (ViewGroup) view; for (int i = 0; i < group.getChildCount(); i++) tintToolbarChild(group.getChildAt(i), color, surface);
			}
		}
	}
	private static final class ToolbarTextSaved {
		final TextView view; final ColorStateList colors, compound;
		ToolbarTextSaved(TextView view) { this.view = view; colors = view.getTextColors(); compound = view.getCompoundDrawableTintList(); }
		void restore() { view.setTextColor(colors); view.setCompoundDrawableTintList(compound); }
	}
	private static final class IconSaved {
		final ImageView image; final ColorStateList tint; final int width, height;
		IconSaved(ImageView image) { this.image = image; tint = image.getImageTintList(); width = image.getMinimumWidth(); height = image.getMinimumHeight(); }
		void restore() { image.setImageTintList(tint); image.setMinimumWidth(width); image.setMinimumHeight(height); }
	}
	static void toolbar(Toolbar toolbar, TextView title, TextView subtitle) {
		Object tag = toolbar.getTag(R.id.standard_toolbar_appearance);
		ToolbarState state = tag instanceof ToolbarState ? (ToolbarState) tag : new ToolbarState(toolbar, title, subtitle);
		if (!(tag instanceof ToolbarState)) {
			toolbar.setTag(R.id.standard_toolbar_appearance, state);
			toolbar.addOnLayoutChangeListener((v, l, t, r, b, ol, ot, or, ob) -> state.apply());
		}
		state.apply();
	}
	static boolean isPopup(Context context) {
		android.content.res.TypedArray a = context.obtainStyledAttributes(new int[]{R.attr.standardPopupAppearance});
		try { return a.getBoolean(0, false); } finally { a.recycle(); }
	}
	static boolean popup(View decor, View background) {
		if (!InterfaceAppearance.isEnabled(decor.getContext()) || !isPopup(decor.getContext())) return false;
		AppearanceColors c = colors(decor.getContext());
		background.setBackgroundTintList(null);
		background.setBackground(popupBackground(background, c.surfaceContainer));
		background.setClipToOutline(true); return true;
	}
	static Drawable popupBackground(View view, int color) {
		int inset = dp(view, 8);
		return new InsetDrawable(new PaddedSurface(shape(view, color, 12), inset), inset, inset, inset, inset);
	}
	static ColorStateList choiceColors(AppearanceColors c) {
		int normal = InterfaceAppearance.contrasting(c.onSurfaceVariant, c.surfaceContainer, 3);
		int checked = InterfaceAppearance.contrasting(c.primary, c.surfaceContainer, 3);
		return new ColorStateList(new int[][]{{-android.R.attr.state_enabled}, {android.R.attr.state_checked}, {}},
				new int[]{OpaqueColors.blend(c.surfaceContainer, normal, .38f), checked, normal});
	}
	private static final class PaddedSurface extends android.graphics.drawable.DrawableWrapper {
		final int padding;
		PaddedSurface(Drawable drawable, int padding) { super(drawable); this.padding = padding; }
		@Override public boolean getPadding(Rect rect) { rect.set(0, padding, 0, padding); return true; }
	}
	static void popupView(View view) {
		if (!isPopup(view.getContext()) || !InterfaceAppearance.isEnabled(view.getContext())) return;
		AppearanceColors c = colors(view.getContext());
		if (view instanceof android.widget.CompoundButton) {
			// Native popup choice controls are siblings of the label, not text rows.
			((android.widget.CompoundButton) view).setButtonTintList(choiceColors(c));
		} else if (view instanceof TextView && !(view instanceof EditText)) {
			// Keep native single-line/ellipsize and wrap-content label height. The popup
			// row centers this label beside its checkbox/arrow and owns the touch target.
			InterfaceAppearance.standardRole(view, InterfaceAppearance.Role.POPUP_TEXT);
			((TextView) view).setTextColor(textColors(InterfaceAppearance.contrasting(c.onSurface, c.surfaceContainer, 4.5), c.surfaceContainer));
		} else if (view instanceof ImageView) {
			((ImageView) view).setImageTintList(textColors(
					InterfaceAppearance.contrasting(c.onSurfaceVariant, c.surfaceContainer, 3), c.surfaceContainer));
		} else if (view instanceof android.widget.AbsListView) {
			// Popup background padding owns the symmetric vertical space, not a second native list inset.
			view.setPaddingRelative(view.getPaddingStart(), 0, view.getPaddingEnd(), 0);
		}
	}

	private static final class SearchState {
		final View root; final SearchView search; final Saved saved;
		final ArrayList<Saved> frames = new ArrayList<>(); final ArrayList<IconSaved> icons = new ArrayList<>();
		SearchState(View root, SearchView search) { this.root = root; this.search = search; saved = new Saved(root); }
		void apply() {
			applyResolved(InterfaceAppearance.isEnabled(root.getContext()));
		}
		void applyResolved(boolean enabled) {
			if (!enabled) {
				saved.restore(); for (Saved frame : frames) frame.restore(); for (IconSaved icon : icons) icon.restore(); return;
			}
			AppearanceColors c = colors(root.getContext()); root.setBackgroundTintList(null);
			root.setBackground(new InsetDrawable(shape(root, c.surfaceContainer, 28), 0, dp(root, 4), 0, dp(root, 4)));
			root.setMinimumHeight(dp(root, AppearanceShapes.STANDARD_TARGET)); style(search, c);
		}
		void style(View view, AppearanceColors c) {
			boolean remembered = false; for (Saved old : frames) if (old.view == view) { remembered = true; break; }
			if (!remembered) frames.add(new Saved(view));
			if (!(view instanceof ImageView)) { view.setBackgroundTintList(null); view.setBackground(null); }
			if (view instanceof EditText) {
				TextView text = (TextView) view; InterfaceAppearance.standardRole(text, InterfaceAppearance.Role.SEARCH_TEXT);
				text.setTextColor(InterfaceAppearance.contrasting(c.onSurface, c.surfaceContainer, 4.5));
				text.setHintTextColor(InterfaceAppearance.contrasting(c.onSurfaceVariant, c.surfaceContainer, 4.5));
				text.setMinimumHeight(dp(root, AppearanceShapes.STANDARD_SEARCH_MIN_HEIGHT));
			} else if (view instanceof ImageView) {
				ImageView image = (ImageView) view; boolean found = false; for (IconSaved old : icons) if (old.image == image) { found = true; break; }
				if (!found) icons.add(new IconSaved(image)); image.setImageTintList(textColors(c.onSurface, c.surfaceContainer));
				image.setMinimumWidth(dp(root, 48)); image.setMinimumHeight(dp(root, 48));
			} else if (view instanceof ViewGroup) {
				ViewGroup group = (ViewGroup) view; for (int i = 0; i < group.getChildCount(); i++) style(group.getChildAt(i), c);
			}
		}
	}
	static void search(View root, SearchView search) {
		Object tag = root.getTag(R.id.standard_search_appearance);
		SearchState state = tag instanceof SearchState ? (SearchState) tag : new SearchState(root, search);
		root.setTag(R.id.standard_search_appearance, state); state.apply();
	}
	static void resolveSearch(View root, boolean enabled) {
		Object tag = root.getTag(R.id.standard_search_appearance); if (tag instanceof SearchState) ((SearchState) tag).applyResolved(enabled);
	}
	private static final class LoadingState implements View.OnAttachStateChangeListener,
			android.view.ViewTreeObserver.OnPreDrawListener, android.view.ViewTreeObserver.OnWindowFocusChangeListener {
		final android.widget.ProgressBar view; final Drawable legacy; final ColorStateList legacyTint; final int width, height;
		final boolean compact, dialogSurface;
		StandardLoadingDrawable drawable;
		android.view.ViewTreeObserver tree;
		boolean modern;
		LoadingState(android.widget.ProgressBar view) {
			this(view, false, false);
		}
		LoadingState(android.widget.ProgressBar view, boolean compact, boolean dialogSurface) {
			this.compact = compact; this.dialogSurface = dialogSurface;
			this.view = view; legacy = view.getIndeterminateDrawable(); legacyTint = view.getIndeterminateTintList();
			width = view.getMinimumWidth(); height = view.getMinimumHeight();
			view.addOnAttachStateChangeListener(this);
			if (view.isAttachedToWindow()) onViewAttachedToWindow(view);
		}
		void apply() {
			if (!InterfaceAppearance.isEnabled(view.getContext())) {
				if (modern) { drawable.stop(); view.setIndeterminateDrawable(legacy); view.setIndeterminateTintList(legacyTint); view.setMinimumWidth(width); view.setMinimumHeight(height); modern = false; }
				return;
			}
			AppearanceColors c = colors(view.getContext());
			view.setIndeterminateTintList(null);
			if (drawable == null) drawable = compact ? new StandardLoadingDrawable(view, 20, 14) : new StandardLoadingDrawable(view);
			boolean dialog = dialogSurface;
			for (android.view.ViewParent p = view.getParent(); p instanceof View; p = p.getParent()) {
				if (p instanceof ProgressDialog.Content) { dialog = true; break; }
			}
			int background = compact ? ToolbarPalette.resolve(view.getContext()).background : dialog ? c.surfaceContainer : c.window;
			drawable.palette(background, InterfaceAppearance.contrasting(c.primary, background, 3));
			if (view.getIndeterminateDrawable() != drawable) view.setIndeterminateDrawable(drawable);
			view.setMinimumWidth(compact ? width : dp(view, 48)); view.setMinimumHeight(compact ? height : dp(view, 48)); modern = true;
			drawable.sync();
		}
		@Override public void onViewAttachedToWindow(View v) {
			tree = view.getViewTreeObserver(); tree.addOnPreDrawListener(this); tree.addOnWindowFocusChangeListener(this);
			apply();
		}
		@Override public void onViewDetachedFromWindow(View v) {
			if (tree != null && tree.isAlive()) { tree.removeOnPreDrawListener(this); tree.removeOnWindowFocusChangeListener(this); }
			tree = null; if (drawable != null) drawable.stop();
		}
		@Override public boolean onPreDraw() {
			if (modern != InterfaceAppearance.isEnabled(view.getContext())) apply();
			if (drawable != null) drawable.sync(); return true;
		}
		@Override public void onWindowFocusChanged(boolean focused) { if (drawable != null) drawable.sync(); }
	}
	static void loading(android.widget.ProgressBar view) {
		loading(view, false, false);
	}
	static void loading(android.widget.ProgressBar view, boolean compact, boolean dialogSurface) {
		Object tag = view.getTag(R.id.standard_loading_appearance);
		LoadingState state = tag instanceof LoadingState ? (LoadingState) tag : new LoadingState(view, compact, dialogSurface);
		view.setTag(R.id.standard_loading_appearance, state); state.apply();
	}
}
