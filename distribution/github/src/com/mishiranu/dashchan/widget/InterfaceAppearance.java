package com.mishiranu.dashchan.widget;

import android.animation.StateListAnimator;
import android.app.AlertDialog;
import android.content.Context;
import android.content.ContextWrapper;
import android.content.res.ColorStateList;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.ColorFilter;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.drawable.ClipDrawable;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.LayerDrawable;
import android.graphics.drawable.RippleDrawable;
import android.graphics.drawable.StateListDrawable;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.CompoundButton;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.SeekBar;
import android.widget.Switch;
import android.widget.TextView;
import androidx.core.graphics.ColorUtils;
import androidx.core.view.OneShotPreDrawListener;
import com.mishiranu.dashchan.R;
import com.mishiranu.dashchan.util.InterfaceMotion;
import com.mishiranu.dashchan.util.ResourceUtils;
import com.mishiranu.dashchan.util.OpaqueColors;
import java.util.Objects;

/** Explicit component roles. Does not replace widgets, listeners, data or the application's theme. */
public final class InterfaceAppearance {
	private InterfaceAppearance() {}
	enum Role {ROW, HEADER, TEXT, SECONDARY, ACTION, TEXT_ACTION, FIELD, POSTING_FORM, POSTING_FIELD, CHECK, SWITCH, SLIDER, ICON, AUDIO_TITLE,
		SETTING_TITLE, SETTING_SUMMARY, SECTION_TITLE, MENU_TITLE, POPUP_TEXT, SERVICE_TITLE, SERVICE_META, DIALOG_TITLE, DIALOG_BODY,
		STANDARD_BUTTON, TOOLBAR_TITLE, TOOLBAR_SUBTITLE, SEARCH_TEXT, STATE_TITLE, SERVICE_DOCUMENT,
		CUSTOM_BODY, CUSTOM_CAPTION, CUSTOM_FIELD, CUSTOM_CHOICE, CUSTOM_ACTION, CUSTOM_TEXT_ACTION, CUSTOM_SLIDER, CUSTOM_CHOICE_TEXT}
	public static boolean isEnabled(Context context) {
		return InterfaceMotion.isEnabled() && supported(context);
	}
	private static boolean supported(Context context) {
		for (Context current = context; current != null;) {
			if (current.getClass().getName().equals("com.mishiranu.dashchan.ui.posting.ExperimentalImageEditorActivity")) return false;
			Context next = current instanceof ContextWrapper ? ((ContextWrapper) current).getBaseContext() : null;
			if (next == current) break; current = next;
		}
		return true;
	}
	static int opaque(int color, int background) { return OpaqueColors.over(color, background); }
	static int contrasting(int requested, int background, double ratio) {
		return OpaqueColors.readable(requested, background, ratio, OpaqueColors.Policy.INTERFACE_COMPATIBILITY);
	}
	static int postingBackground(int window) {
		// Leave room for a lighter field even in nearly white custom themes.
		return ColorUtils.setAlphaComponent(ColorUtils.calculateLuminance(window) > .9
				? OpaqueColors.blend(window, Color.BLACK, .04f) : window, 255);
	}
	static int lighterPostingFill(int requested, int background) {
		int fill = opaque(requested, background);
		double backgroundLuminance = ColorUtils.calculateLuminance(background);
		// A gentle surface distinction, not the text accessibility contrast threshold.
		for (int i = 0; i <= 20; i++) {
			// Float interpolation can truncate an opaque alpha to 254. Contrast requires
			// an opaque background, and these are intentionally opaque field surfaces.
			int candidate = OpaqueColors.blend(fill, Color.WHITE, i / 20f);
			if (ColorUtils.calculateLuminance(candidate) > backgroundLuminance
					&& OpaqueColors.contrast(candidate, background) >= 1.08) return candidate;
		}
		return Color.WHITE;
	}
	private static final class Palette {
		final AppearanceColors colors; final int window, surface, text, secondary, accent, onAccent, outline, tonal, error;
		Palette(AppearanceColors colors) {
			this.colors = colors; window = colors.window; surface = colors.surfaceContainer;
			text = colors.onSurface; secondary = colors.onSurfaceVariant; accent = contrasting(colors.primary, surface, 3);
			onAccent = contrasting(colors.onPrimary, accent, 4.5); outline = colors.outline; tonal = colors.primaryContainer; error = colors.error;
		}
		Palette(Context context, ThemeEngine.Theme theme) {
			colors = AppearanceColors.interfaceCompatibility(theme.base == ThemeEngine.Theme.Base.DARK,
					theme.window, theme.card, theme.accent, ResourceUtils.getColor(context, android.R.attr.textColorPrimary),
					ResourceUtils.getColor(context, R.attr.colorTextError));
			window = colors.window; surface = colors.surface; text = colors.onSurface;
			secondary = colors.onSurfaceVariant; accent = colors.primary; onAccent = colors.onPrimary;
			outline = colors.outline; tonal = colors.primaryContainer;
			error = colors.errorIcon; // Legacy stroke recipe; text is contrasted against its actual field fill.
		}
	}
	private static Palette palette(View view) {
		Context context = view.getContext();
		return new Palette(context, ThemeEngine.getTheme(context));
	}
	private static int dp(View view, float amount) { return Math.round(ResourceUtils.obtainDensity(view) * amount); }
	private static ColorStateList enabled(int color, int surface) {
		// Transparent text-action containers remain transparent/translucent by design.
		int disabled = Color.alpha(color) == 255 ? OpaqueColors.blend(surface, color, .38f)
				: ColorUtils.blendARGB(surface, color, .38f);
		return new ColorStateList(new int[][] {{-android.R.attr.state_enabled}, {}}, new int[] {disabled, color});
	}
	private static ColorStateList fieldStates(int normal, int focused, int window) {
		return new ColorStateList(new int[][] {{-android.R.attr.state_enabled}, {android.R.attr.state_focused}, {}},
				new int[] {OpaqueColors.blend(window, normal, .38f), focused, normal});
	}
	private static void setBottomMargin(View view, int margin) {
		if (view.getLayoutParams() instanceof ViewGroup.MarginLayoutParams) {
			ViewGroup.MarginLayoutParams params = (ViewGroup.MarginLayoutParams) view.getLayoutParams();
			if (params.bottomMargin != margin) {
				params.bottomMargin = margin;
				view.setLayoutParams(params);
			}
		}
	}
	private static GradientDrawable shape(View view, float radius, ColorStateList color) {
		GradientDrawable drawable = new GradientDrawable(); drawable.setColor(color); drawable.setCornerRadius(dp(view, radius)); return drawable;
	}
	private static Drawable ripple(View view, Drawable content, float radius, int color) {
		return new RippleDrawable(new ColorStateList(new int[][] {{-android.R.attr.state_enabled}, {}},
				new int[] {Color.TRANSPARENT, ColorUtils.setAlphaComponent(color, 36)}), content,
				shape(view, radius, ColorStateList.valueOf(Color.WHITE)));
	}
	private static void role(View view, Role role) {
		if (view == null || !supported(view.getContext())) return;
		Object tag = view.getTag(R.id.interface_appearance_state);
		State state = tag instanceof State ? (State) tag : new State(view);
		state.role = role; view.setTag(R.id.interface_appearance_state, state); render(view, state);
	}
	public static void row(View view, TextView title, TextView summary, boolean header) {
		role(view, header ? Role.HEADER : Role.ROW); if (title != view) role(title, header ? Role.HEADER : Role.TEXT); role(summary, Role.SECONDARY);
	}
	public static void action(Button view, boolean primary) { role(view, primary ? Role.ACTION : Role.TEXT_ACTION); }
	public static void field(EditText view) { role(view, hasRole(view, Role.CUSTOM_FIELD) ? Role.CUSTOM_FIELD : Role.FIELD); }
	/** Local backdrop only; does not change the application's window or theme. */
	public static void postingForm(View view) { role(view, Role.POSTING_FORM); }
	/** Filled, borderless fields scoped to the real posting form and its offline preview. */
	public static void postingField(EditText view) { role(view, Role.POSTING_FIELD); }
	public static void check(CompoundButton view) { role(view, hasRole(view, Role.CUSTOM_CHOICE) ? Role.CUSTOM_CHOICE : view instanceof Switch ? Role.SWITCH : Role.CHECK); }
	private static boolean hasRole(View view, Role role) {
		Object state = view != null ? view.getTag(R.id.interface_appearance_state) : null;
		return state instanceof State && ((State) state).role == role;
	}
	/** Explicit opt-in for custom dialog/page content. Semantic previews are excluded by identity. */
	public static void standardCustomContent(View view, View... previews) {
		if (view == null) return;
		for (View preview : previews) if (view == preview) return;
		if (view instanceof EditText) role(view, Role.CUSTOM_FIELD);
		else if (view instanceof CompoundButton) role(view, Role.CUSTOM_CHOICE);
		else if (view instanceof Button) role(view, Role.CUSTOM_ACTION);
		else if (view instanceof SeekBar) role(view, Role.CUSTOM_SLIDER);
		else if (view instanceof TextView && !hasRole(view, Role.CUSTOM_CAPTION) && !hasRole(view, Role.SECTION_TITLE)) role(view, Role.CUSTOM_BODY);
		if (view instanceof ViewGroup) {
			ViewGroup group = (ViewGroup) view;
			for (int i = 0; i < group.getChildCount(); i++) standardCustomContent(group.getChildAt(i), previews);
		}
	}
	public static void standardCustomCaption(TextView view) { role(view, Role.CUSTOM_CAPTION); }
	public static void standardCustomInsets(View view) { StandardAppearance.customInsets(view); }
	public static void standardChoiceText(TextView view) { role(view, Role.CUSTOM_CHOICE_TEXT); }
	public static void standardCustomAction(Button view, boolean primary) { role(view, primary ? Role.CUSTOM_ACTION : Role.CUSTOM_TEXT_ACTION); }
	public static void standardChoiceRow(View row, TextView text) {
		menuRow(row, text);
		standardChoiceText(text);
		StandardAppearance.customChoiceRow(row, text);
		if (isEnabled(row.getContext()) && text instanceof android.widget.CheckedTextView)
			((android.widget.CheckedTextView) text).setCheckMarkTintList(StandardAppearance.choiceColors(StandardAppearance.colors(row.getContext())));
	}
	/** SummaryLayout owns its metric spans; do not apply a global line height or line-limit setters. */
	public static int[] standardSummaryColors(Context context) {
		if (!isEnabled(context)) return null;
		AppearanceColors c = StandardAppearance.colors(context);
		return new int[] {contrasting(c.onSurface, c.surfaceContainer, 4.5),
				contrasting(c.onSurfaceVariant, c.surfaceContainer, 4.5), c.outlineVariant};
	}
	public static void slider(SeekBar view) { role(view, hasRole(view, Role.CUSTOM_SLIDER) ? Role.CUSTOM_SLIDER : Role.SLIDER); }
	public static void icon(ImageView view) { role(view, Role.ICON); }
	public static void secondary(TextView view) { role(view, Role.SECONDARY); }
	public static void audioTitle(TextView view) { role(view, Role.AUDIO_TITLE); }
	public static void settingsRow(View view, TextView title, TextView summary, View widgets, boolean header) {
		StandardAppearance.prepare(view, title, summary, null, widgets);
		row(view, title, summary, header);
		role(title, header ? Role.SECTION_TITLE : Role.SETTING_TITLE); role(summary, Role.SETTING_SUMMARY);
		StandardAppearance.settings(view, title, summary, widgets, header);
	}
	public static void serviceRow(View view, TextView title, TextView metadata, TextView end) {
		StandardAppearance.prepare(view, title, metadata, end, null);
		row(view, title, metadata, false); role(title, Role.SERVICE_TITLE);
		role(metadata, Role.SERVICE_META); role(end, Role.SERVICE_META);
		StandardAppearance.service(view, title, metadata, end);
	}
	public static void serviceHeader(TextView text) {
		ServiceAppearance.State state = ServiceAppearance.prepare(text, text); state.kind = ServiceAppearance.Kind.HEADER;
		settingsRow(text, text, null, null, true); state.apply();
	}
	public static void serviceTableCell(TextView text, boolean header) { role(text, header ? Role.SECTION_TITLE : Role.SERVICE_TITLE); }
	public static void serviceText(TextView text, boolean nested, boolean medium) {
		ServiceAppearance.State state = ServiceAppearance.prepare(text, text);
		state.kind = ServiceAppearance.Kind.TEXT; state.nested = nested; state.medium = medium;
		state.legacyTypeface = text.getTypeface();
		serviceRow(text, text, null, null); state.apply();
	}
	public static void serviceCategory(View root, TextView title, ImageView arrow) {
		ServiceAppearance.State state = ServiceAppearance.prepare(root, title);
		if (state.icon == null) { state.icon = arrow; state.iconTint = arrow.getImageTintList(); }
		state.kind = ServiceAppearance.Kind.CATEGORY;
		serviceRow(root, title, null, null); state.apply();
	}
	public static void servicePreview(View root, TextView title, TextView metadata, TextView date,
			boolean muted, boolean emphasized, float legacyAlpha) {
		ServiceAppearance.State state = ServiceAppearance.prepare(root, title);
		state.kind = ServiceAppearance.Kind.PREVIEW; state.metadata = metadata; state.date = date;
		state.muted = muted; state.medium = emphasized; state.legacyAlpha = legacyAlpha; state.legacyTypeface = title.getTypeface();
		serviceRow(root, title, metadata, date); state.apply();
	}
	public static void serviceAction(TextView text, boolean empty) {
		ServiceAppearance.State state = ServiceAppearance.prepare(text, text);
		state.kind = empty ? ServiceAppearance.Kind.EMPTY : ServiceAppearance.Kind.ACTION;
		if (empty) serviceState(text, text);
		else { serviceRow(text, text, null, null); role(text, Role.STANDARD_BUTTON); }
		state.apply();
	}
	public static void serviceTab(View root, TextView text, View indicator, boolean selected) {
		ServiceAppearance.State state = ServiceAppearance.prepare(root, text);
		state.kind = ServiceAppearance.Kind.TAB; state.indicator = indicator; state.selected = selected;
		role(text, Role.STANDARD_BUTTON); state.apply();
	}
	public static void themeChoice(View root, TextView title, ImageView preview, ImageView check, boolean selected, boolean dialog) {
		ServiceAppearance.State state = ServiceAppearance.prepare(root, title);
		if (state.icon == null) { state.icon = preview; state.iconMarginEnd = ((ViewGroup.MarginLayoutParams) preview.getLayoutParams()).getMarginEnd(); }
		state.kind = ServiceAppearance.Kind.THEME; state.check = check; state.selected = selected; state.dialog = dialog;
		settingsRow(root, title, null, (View) check.getParent(), false); state.apply();
	}
	/** Only TextFragment's document widgets opt in; spans and selection remain intact. */
	public static void serviceDocument(TextView text) { role(text, Role.SERVICE_DOCUMENT); }
	public static com.mishiranu.dashchan.graphics.ColorScheme serviceDocumentColors(Context context) {
		return documentColors(context, isEnabled(context));
	}
	private static com.mishiranu.dashchan.graphics.ColorScheme documentColors(Context context, boolean enabled) {
		ThemeEngine.Theme theme = ThemeEngine.getTheme(context);
		if (!enabled) return ThemeEngine.getColorScheme(context);
		AppearanceColors c = StandardAppearance.colors(context);
		return new com.mishiranu.dashchan.graphics.ColorScheme(context, theme, c.window,
				contrasting(opaque(theme.link, c.window), c.window, 4.5));
	}
	public static int serviceWarningColor(Context context) {
		return isEnabled(context) ? StandardAppearance.colors(context).error
				: ResourceUtils.getColor(context, R.attr.colorTextError);
	}
	public static void menuRow(View view, TextView title) {
		StandardAppearance.prepare(view, title, null, null, null);
		row(view, title, null, false); role(title, Role.MENU_TITLE); StandardAppearance.menu(view, title);
	}
	public static void serviceState(View view, TextView message) {
		role(message, Role.STATE_TITLE); StandardAppearance.state(view, message);
	}
	public static void serviceErrorState(View view, ImageView icon, TextView message) {
		role(message, Role.STATE_TITLE); StandardAppearance.errorState(view, icon, message);
	}
	public static void standardPage(View view) { StandardAppearance.page(view); }
	/** Main chrome only; null means retain legacy/F-Droid status-bar ownership. */
	public static Integer standardStatusBarSurface(Context context) {
		return isEnabled(context) ? ToolbarPalette.resolve(context).background : null;
	}
	public static void standardLoading(android.widget.ProgressBar view) { StandardAppearance.loading(view); }
	public static ProgressDialog.Content standardProgressContent(Context context, String format) {
		return isEnabled(context) ? new StandardProgressContent(context, format) : null;
	}
	public static void standardProgressText(TextView text, boolean caption) { role(text, caption ? Role.SERVICE_META : Role.DIALOG_BODY); }
	public static void standardToolbar(android.widget.Toolbar toolbar, TextView title, TextView subtitle) {
		StandardAppearance.toolbar(toolbar, title, subtitle);
	}
	public static void standardSearch(View root, android.widget.SearchView search) { StandardAppearance.search(root, search); }
	public static Context standardDialogContext(Context context) { return StandardAppearance.dialogContext(context); }
	public static void configureStandardDialog(AlertDialog dialog) { StandardAppearance.dialog(dialog); }
	public static void prepareStandardMenuDialog(AlertDialog dialog, View menu) { StandardAppearance.dialogMenu(dialog, menu); }
	public static void standardDialogTitle(TextView title) { role(title, Role.DIALOG_TITLE); }
	public static void standardFieldLabel(TextView label) { role(label, Role.SECTION_TITLE); }
	public static void updateStandardDialogTitle(AlertDialog dialog, CharSequence title) {
		TextView view = dialog.findViewById(R.id.standard_dialog_title);
		if (view != null) { view.setText(title); view.setVisibility(title == null || title.length() == 0 ? View.GONE : View.VISIBLE); }
	}
	public static void popupView(View view) { StandardAppearance.popupView(view); }
	public static boolean standardPopup(View decor, View background) { return StandardAppearance.popup(decor, background); }
	public static void fieldError(EditText view, boolean error) {
		if (PostingFormAppearance.fieldError(view, error)) return;
		Object tag = view.getTag(R.id.interface_appearance_state);
		if (tag instanceof State && (((State) tag).role == Role.FIELD || ((State) tag).role == Role.CUSTOM_FIELD || ((State) tag).role == Role.POSTING_FIELD)) {
			State state = (State) tag; state.rendered = false; state.error = error; state.legacyError = error ? view.getBackground() : null;
			if (isEnabled(view.getContext())) render(view, state);
			StandardAppearance.refresh(view);
		}
	}
	public static void refreshTree(View view) {
		if (view != null) PostingFormAppearance.refreshView(view);
		if (view == null) return;
		Object state = view.getTag(R.id.interface_appearance_state);
		if (state instanceof State) render(view, (State) state);
		if (view instanceof ViewGroup) {
			ViewGroup group = (ViewGroup) view;
			for (int i = 0; i < group.getChildCount(); i++) refreshTree(group.getChildAt(i));
		}
		// Parent-owned layout must run after child typography restore, not be undone by it.
		StandardAppearance.refresh(view);
	}
	public static void configureDialog(AlertDialog dialog) {
		if (!supported(dialog.getContext())) return;
		View decor = dialog.getWindow() != null ? dialog.getWindow().getDecorView() : null;
		if (decor == null) return;
		decor.addOnAttachStateChangeListener(new View.OnAttachStateChangeListener() {
			private OneShotPreDrawListener pending;
			@Override public void onViewAttachedToWindow(View view) {
				pending = OneShotPreDrawListener.add(view, () -> {
					pending = null;
					if (!dialog.isShowing()) return;
					action(dialog.getButton(AlertDialog.BUTTON_POSITIVE), true);
					action(dialog.getButton(AlertDialog.BUTTON_NEGATIVE), false);
					action(dialog.getButton(AlertDialog.BUTTON_NEUTRAL), false);
					View custom = dialog.findViewById(android.R.id.custom); styleInputs(custom);
				});
			}
			@Override public void onViewDetachedFromWindow(View view) { if (pending != null) { pending.removeListener(); pending = null; } }
		});
	}
	private static void styleInputs(View view) {
		if (view instanceof EditText) field((EditText) view);
		else if (view instanceof CheckBox || view instanceof Switch) check((CompoundButton) view);
		else if (view instanceof SeekBar) slider((SeekBar) view);
		if (view instanceof ViewGroup) {
			ViewGroup group = (ViewGroup) view; for (int i = 0; i < group.getChildCount(); i++) styleInputs(group.getChildAt(i));
		}
	}
	private static void render(View view, State state) {
		renderResolved(view, state, isEnabled(view.getContext()));
	}
	static void renderResolved(View view, State state, boolean enabled) {
		boolean custom = state.role == Role.CUSTOM_BODY || state.role == Role.CUSTOM_CAPTION
				|| state.role == Role.CUSTOM_FIELD || state.role == Role.CUSTOM_CHOICE || state.role == Role.CUSTOM_ACTION
				|| state.role == Role.CUSTOM_TEXT_ACTION || state.role == Role.CUSTOM_SLIDER || state.role == Role.CUSTOM_CHOICE_TEXT;
		Palette p = enabled ? custom ? new Palette(StandardAppearance.colors(view.getContext())) : palette(view) : null;
		AppearanceTypography.Configuration typography = enabled ? new AppearanceTypography.Configuration(view.getContext()) : null;
		if (state.rendered && state.applied == enabled && state.appliedRole == state.role
				&& Objects.equals(state.appliedColors, p != null ? p.colors : null)
				&& Objects.equals(state.appliedTypography, typography) && state.appliedError == state.error) return;
		state.restore(view); state.rendered = true; state.applied = enabled; state.appliedRole = state.role;
		state.appliedColors = p != null ? p.colors : null; state.appliedTypography = typography; state.appliedError = state.error;
		if (!enabled) {
			if (state.role == Role.SERVICE_DOCUMENT) documentColors(view.getContext(), false).apply(((TextView) view).getText());
			return;
		}
		AppearanceTypography.Role typographyRole = standardTypography(state.role);
		if (typographyRole != null) {
			TextView text = (TextView) view;
			AppearanceTypography.apply(text, typographyRole);
			AppearanceColors standard = StandardAppearance.colors(view.getContext());
			boolean secondary = state.role == Role.SETTING_SUMMARY || state.role == Role.SERVICE_META || state.role == Role.TOOLBAR_SUBTITLE || state.role == Role.CUSTOM_CAPTION;
			if (state.role == Role.TOOLBAR_SUBTITLE) {
				ToolbarPalette toolbar = ToolbarPalette.resolve(view.getContext());
				text.setTextColor(enabled(toolbar.subtitle, toolbar.background));
			} else {
				int surface = custom ? standard.surfaceContainer : standard.surface;
				int color = secondary ? standard.onSurfaceVariant : standard.onSurface;
				text.setTextColor(enabled(custom ? contrasting(color, surface, 4.5) : color, surface));
			}
			if (supportsStandardLineLimits(text) && (state.role == Role.SETTING_TITLE || state.role == Role.SETTING_SUMMARY || state.role == Role.MENU_TITLE
					|| state.role == Role.SERVICE_TITLE || state.role == Role.SERVICE_META || state.role == Role.DIALOG_BODY
					|| state.role == Role.DIALOG_TITLE || state.role == Role.STATE_TITLE || state.role == Role.SERVICE_DOCUMENT)) {
				text.setSingleLine(false); text.setMaxLines(Integer.MAX_VALUE);
			}
			if (state.role == Role.SERVICE_DOCUMENT) {
				com.mishiranu.dashchan.graphics.ColorScheme scheme = documentColors(view.getContext(), true);
				text.setLinkTextColor(scheme.linkColor); scheme.apply(text.getText());
			}
			return;
		}
		switch (state.role) {
			case TEXT: case SECONDARY: case AUDIO_TITLE: {
				TextView text = (TextView) view; text.setTextColor(enabled(state.role == Role.SECONDARY ? p.secondary : p.text, p.surface));
				if (state.role == Role.AUDIO_TITLE) {
					if (supportsStandardLineLimits(text)) { text.setSingleLine(false); text.setMaxLines(2); }
					AppearanceTypography.apply(text, AppearanceTypography.Role.COMPAT_AUDIO_TITLE);
				}
				break;
			}
			case HEADER: {
				if (view instanceof TextView) ((TextView) view).setTextColor(contrasting(p.accent, p.surface, 4.5));
				break;
			}
			case ROW: {
				if (view instanceof TextView) ((TextView) view).setTextColor(enabled(p.text, p.surface));
				// Keep the original row height; appearance must not enlarge menus or preferences.
				ColorStateList fill = new ColorStateList(new int[][] {{android.R.attr.state_selected}, {android.R.attr.state_activated}, {}},
						new int[] {p.tonal, p.tonal, Color.TRANSPARENT});
				view.setBackgroundTintList(null); view.setBackground(ripple(view, shape(view, AppearanceShapes.MEDIUM, fill), AppearanceShapes.MEDIUM, p.text)); break;
			}
			case ACTION: case TEXT_ACTION: case ICON: case CUSTOM_ACTION: case CUSTOM_TEXT_ACTION: {
				boolean filled = state.role != Role.TEXT_ACTION && state.role != Role.CUSTOM_TEXT_ACTION;
				view.setBackgroundTintList(null); view.setBackground(ripple(view, shape(view, AppearanceShapes.EXTRA_LARGE,
						enabled(filled ? p.accent : Color.TRANSPARENT, p.surface)), AppearanceShapes.EXTRA_LARGE, filled ? p.onAccent : p.text));
				view.setElevation(0); view.setStateListAnimator(null);
				if (view instanceof Button) {
					Button button = (Button) view; button.setAllCaps(false); button.setTextColor(enabled(filled ? p.onAccent : contrasting(p.accent, p.surface, 4.5), filled ? p.accent : p.surface));
					AppearanceTypography.apply(button, custom ? AppearanceTypography.Role.LABEL_LARGE : AppearanceTypography.Role.COMPAT_ACTION);
					if (custom) button.setCompoundDrawableTintList(enabled(filled ? p.onAccent : contrasting(p.accent, p.surface, 4.5), filled ? p.accent : p.surface));
					button.setMinimumHeight(dp(view, AppearanceShapes.ACTION_MIN_HEIGHT));
					button.setPaddingRelative(dp(view, AppearanceShapes.SPACE_16), dp(view, AppearanceShapes.SPACE_8),
							dp(view, AppearanceShapes.SPACE_16), dp(view, AppearanceShapes.SPACE_8));
				} else ((ImageView) view).setImageTintList(enabled(p.onAccent, p.accent));
				break;
			}
			case FIELD: case CUSTOM_FIELD: {
				EditText edit = (EditText) view; edit.setBackgroundTintList(null);
				StateListDrawable background = new StateListDrawable();
				GradientDrawable focus = shape(view, AppearanceShapes.MEDIUM, ColorStateList.valueOf(p.surface)); focus.setStroke(dp(view, AppearanceShapes.FIELD_FOCUS_STROKE), state.error ? p.error : contrasting(p.accent, p.surface, 3));
				GradientDrawable normal = shape(view, AppearanceShapes.MEDIUM, enabled(p.tonal, p.surface)); normal.setStroke(dp(view, AppearanceShapes.FIELD_STROKE), state.error ? p.error : contrasting(p.outline, p.tonal, 3));
				background.addState(new int[] {android.R.attr.state_enabled, android.R.attr.state_focused}, focus); background.addState(new int[0], normal);
				edit.setBackground(background); edit.setMinimumHeight(Math.max(state.minimumHeight, dp(view, 48)));
				edit.setPaddingRelative(dp(view, 12), dp(view, 10), dp(view, 12), dp(view, 10));
				edit.setTextColor(enabled(contrasting(p.text, p.tonal, 4.5), p.surface)); edit.setHintTextColor(enabled(contrasting(p.secondary, p.tonal, 4.5), p.surface));
				if (state.role == Role.CUSTOM_FIELD) {
					android.graphics.Typeface original = edit.getTypeface();
					AppearanceTypography.apply(edit, AppearanceTypography.Role.BODY_LARGE);
					if (android.graphics.Typeface.MONOSPACE.equals(original)) edit.setTypeface(original);
				}
				break;
			}
			case POSTING_FORM: {
				view.setBackgroundTintList(null); view.setBackground(new ColorDrawable(postingBackground(p.window)));
				return; // The container itself must not receive control touch feedback.
			}
			case POSTING_FIELD: {
				EditText edit = (EditText) view;
				int background = postingBackground(p.window);
				// Both light and dark themes use a lighter writing surface, without an outline.
				int fill = lighterPostingFill(ColorUtils.blendARGB(background, Color.WHITE,
						ColorUtils.calculateLuminance(background) < .5 ? .10f : .80f), background);
				if (state.error) fill = lighterPostingFill(ColorUtils.blendARGB(fill, p.error, .08f), background);
				int focused = lighterPostingFill(ColorUtils.blendARGB(fill, state.error ? p.error : p.accent, .06f), background);
				edit.setBackgroundTintList(null);
				edit.setBackground(shape(view, 12, fieldStates(fill, focused, background)));
				edit.setMinimumHeight(Math.max(state.minimumHeight, dp(view, 48)));
				edit.setPaddingRelative(dp(view, 12), dp(view, 10), dp(view, 12), dp(view, 10));
				edit.setTextColor(fieldStates(contrasting(p.text, fill, 4.5), contrasting(p.text, focused, 4.5), background));
				int hint = state.error ? p.error : p.secondary;
				edit.setHintTextColor(fieldStates(contrasting(hint, fill, 4.5), contrasting(hint, focused, 4.5), background));
				setBottomMargin(view, Math.max(state.bottomMargin, dp(view, 8)));
				// Keep the themed cursor, text size, editor bounds, selection and IME behavior.
				break;
			}
			case CHECK: case CUSTOM_CHOICE: {
				CompoundButton check = (CompoundButton) view; check.setButtonTintList(null);
				check.setButtonDrawable(new Mark(view, p, state.role == Role.CUSTOM_CHOICE && view instanceof android.widget.RadioButton));
				if (state.role == Role.CUSTOM_CHOICE) {
					AppearanceTypography.apply(check, AppearanceTypography.Role.BODY_LARGE);
					check.setTextColor(enabled(p.text, p.surface)); check.setMinimumHeight(Math.max(state.minimumHeight, dp(view, 48)));
				}
				break;
			}
			case SWITCH: {
				Switch control = (Switch) view; control.setTrackTintList(null); control.setThumbTintList(null);
				GradientDrawable track = shape(view, 16, new ColorStateList(new int[][] {{-android.R.attr.state_enabled}, {android.R.attr.state_checked}, {}},
						new int[] {OpaqueColors.blend(p.surface, p.text, .12f), p.accent, p.tonal}));
				track.setSize(dp(view, 52), dp(view, 32)); track.setStroke(dp(view, 1), p.outline);
				GradientDrawable thumb = shape(view, 12, new ColorStateList(new int[][] {{-android.R.attr.state_enabled}, {android.R.attr.state_checked}, {}},
						new int[] {p.secondary, p.onAccent, p.outline})); thumb.setSize(dp(view, 24), dp(view, 24));
				control.setTrackDrawable(track); control.setThumbDrawable(new android.graphics.drawable.InsetDrawable(thumb, 0, dp(view, 4), 0, dp(view, 4))); control.setSwitchMinWidth(dp(view, 52)); control.setSplitTrack(false); break;
			}
			case SLIDER: case CUSTOM_SLIDER: {
				SeekBar seek = (SeekBar) view;
				GradientDrawable track = shape(view, 4, enabled(OpaqueColors.blend(p.surface, p.text, .16f), p.surface)); track.setSize(dp(view, 1), dp(view, 6));
				GradientDrawable progress = shape(view, 4, enabled(p.accent, p.surface)); progress.setSize(dp(view, 1), dp(view, 6));
				LayerDrawable layers = new LayerDrawable(new Drawable[] {track, new ClipDrawable(progress, Gravity.LEFT, ClipDrawable.HORIZONTAL)});
				layers.setId(0, android.R.id.background); layers.setId(1, android.R.id.progress);
				GradientDrawable thumb = shape(view, 10, enabled(contrasting(p.accent, p.surface, 3), p.surface)); thumb.setSize(dp(view, 20), dp(view, 20));
				seek.setProgressTintList(null); seek.setProgressBackgroundTintList(null); seek.setThumbTintList(null);
				seek.setProgressDrawable(layers); seek.setThumb(thumb); seek.setSplitTrack(false); seek.setThumbOffset(dp(view, 10));
				seek.setMinHeight(dp(view, 6)); seek.setMaxHeight(dp(view, 6)); seek.setMinimumHeight(Math.max(state.minimumHeight, dp(view, 48))); break;
			}
		}
		TouchFeedback.apply(view);
	}
	private static boolean supportsStandardLineLimits(TextView text) {
		// CommentTextView owns folding via setLinesLimit and deliberately rejects setMaxLines.
		// TextView.setSingleLine also invokes that virtual setter, even for false.
		return !(text instanceof CommentTextView);
	}
	private static AppearanceTypography.Role standardTypography(Role role) {
		if (role == null) return null;
		switch (role) {
			case SETTING_TITLE: case SEARCH_TEXT: return AppearanceTypography.Role.BODY_LARGE;
			case SETTING_SUMMARY: case MENU_TITLE: case POPUP_TEXT: case SERVICE_TITLE: case DIALOG_BODY: case SERVICE_DOCUMENT: return AppearanceTypography.Role.BODY_MEDIUM;
			case SECTION_TITLE: case STANDARD_BUTTON: return AppearanceTypography.Role.LABEL_LARGE;
			case SERVICE_META: case TOOLBAR_SUBTITLE: return AppearanceTypography.Role.BODY_SMALL;
			case CUSTOM_BODY: return AppearanceTypography.Role.BODY_MEDIUM;
			case CUSTOM_CAPTION: return AppearanceTypography.Role.BODY_SMALL;
			case CUSTOM_CHOICE_TEXT: return AppearanceTypography.Role.BODY_LARGE;
			case DIALOG_TITLE: return AppearanceTypography.Role.TITLE_LARGE;
			case TOOLBAR_TITLE: return AppearanceTypography.Role.COMPACT_TOOLBAR;
			case STATE_TITLE: return AppearanceTypography.Role.TITLE_MEDIUM;
			default: return null;
		}
	}
	static void standardRole(View view, Role role) { role(view, role); }
	static final class State {
		Role role, appliedRole; boolean error, rendered, applied, appliedError; Drawable legacyError;
		AppearanceColors appliedColors; AppearanceTypography.Configuration appliedTypography;
		final Drawable background; final ColorStateList backgroundTint; final StateListAnimator animator; final float elevation;
		final int start, top, end, bottom, minimumHeight, bottomMargin;
		final ColorStateList text, hint, imageTint, buttonTint, trackTint, thumbTint, progressTint, progressBackgroundTint;
		final ColorStateList linkText, compoundTint;
		final AppearanceTypography.Snapshot typography; final int minLines, maxLines;
		final Drawable mark, track, thumb, progress; final int switchWidth, thumbOffset, minTrackHeight, maxTrackHeight; final boolean splitTrack;
		State(View view) {
			background = view.getBackground(); backgroundTint = view.getBackgroundTintList(); animator = view.getStateListAnimator(); elevation = view.getElevation();
			start = view.getPaddingStart(); top = view.getPaddingTop(); end = view.getPaddingEnd(); bottom = view.getPaddingBottom(); minimumHeight = view.getMinimumHeight();
			bottomMargin = view.getLayoutParams() instanceof ViewGroup.MarginLayoutParams
					? ((ViewGroup.MarginLayoutParams) view.getLayoutParams()).bottomMargin : 0;
			TextView t = view instanceof TextView ? (TextView) view : null; text = t != null ? t.getTextColors() : null; hint = t != null ? t.getHintTextColors() : null;
			linkText = t != null ? t.getLinkTextColors() : null;
			compoundTint = t != null ? t.getCompoundDrawableTintList() : null;
			typography = t != null ? new AppearanceTypography.Snapshot(t) : null;
			minLines = t != null ? t.getMinLines() : -1; maxLines = t != null ? t.getMaxLines() : -1;
			imageTint = view instanceof ImageView ? ((ImageView) view).getImageTintList() : null;
			CompoundButton c = view instanceof CompoundButton ? (CompoundButton) view : null; mark = c != null ? c.getButtonDrawable() : null; buttonTint = c != null ? c.getButtonTintList() : null;
			Switch s = view instanceof Switch ? (Switch) view : null; SeekBar b = view instanceof SeekBar ? (SeekBar) view : null;
			track = s != null ? s.getTrackDrawable() : null; thumb = s != null ? s.getThumbDrawable() : b != null ? b.getThumb() : null;
			trackTint = s != null ? s.getTrackTintList() : null; thumbTint = s != null ? s.getThumbTintList() : b != null ? b.getThumbTintList() : null;
			switchWidth = s != null ? s.getSwitchMinWidth() : 0; splitTrack = s != null ? s.getSplitTrack() : b != null && b.getSplitTrack();
			progress = b != null ? b.getProgressDrawable() : null; progressTint = b != null ? b.getProgressTintList() : null;
			progressBackgroundTint = b != null ? b.getProgressBackgroundTintList() : null; thumbOffset = b != null ? b.getThumbOffset() : 0;
			minTrackHeight = b != null ? b.getMinHeight() : 0; maxTrackHeight = b != null ? b.getMaxHeight() : 0;
		}
		void restore(View view) {
			view.setBackground((role == Role.FIELD || role == Role.CUSTOM_FIELD || role == Role.POSTING_FIELD) && error && legacyError != null ? legacyError : background); view.setBackgroundTintList(backgroundTint);
			view.setPaddingRelative(start, top, end, bottom); view.setMinimumHeight(minimumHeight);
			if (role == Role.POSTING_FIELD || appliedRole == Role.POSTING_FIELD) setBottomMargin(view, bottomMargin);
			if (view instanceof TextView) {
				TextView t = (TextView) view; t.setTextColor(text); if (hint != null) t.setHintTextColor(hint);
				if (role == Role.CUSTOM_ACTION || appliedRole == Role.CUSTOM_ACTION || role == Role.CUSTOM_TEXT_ACTION || appliedRole == Role.CUSTOM_TEXT_ACTION) t.setCompoundDrawableTintList(compoundTint);
				if (role == Role.CUSTOM_FIELD || appliedRole == Role.CUSTOM_FIELD) typography.restoreTextMetrics(t);
				else if (role == Role.CUSTOM_CHOICE || role == Role.CUSTOM_ACTION || appliedRole == Role.CUSTOM_CHOICE
						|| appliedRole == Role.CUSTOM_ACTION || role == Role.CUSTOM_TEXT_ACTION || appliedRole == Role.CUSTOM_TEXT_ACTION)
					typography.restore(t);
				if (role == Role.SERVICE_DOCUMENT && linkText != null) t.setLinkTextColor(linkText);
				if (standardTypography(role) != null || standardTypography(appliedRole) != null) {
					if (role == Role.SEARCH_TEXT || appliedRole == Role.SEARCH_TEXT) typography.restoreTextMetrics(t);
					else {
						typography.restore(t);
						if (supportsStandardLineLimits(t)) {
							t.setSingleLine(maxLines == 1);
							if (minLines >= 0) t.setMinLines(minLines); if (maxLines >= 0) t.setMaxLines(maxLines);
						}
					}
				}
				if (role == Role.ACTION || role == Role.TEXT_ACTION || role == Role.AUDIO_TITLE
						|| appliedRole == Role.ACTION || appliedRole == Role.TEXT_ACTION || appliedRole == Role.AUDIO_TITLE) {
					if (supportsStandardLineLimits(t) && (role == Role.AUDIO_TITLE || appliedRole == Role.AUDIO_TITLE)) {
						t.setSingleLine(maxLines == 1);
						if (minLines >= 0) t.setMinLines(minLines);
						if (maxLines >= 0) t.setMaxLines(maxLines);
					}
					typography.restoreSizeAndTransformation(t);
				}
			}
			if (role == Role.ACTION || role == Role.TEXT_ACTION || role == Role.ICON || role == Role.CUSTOM_ACTION
					|| appliedRole == Role.ACTION || appliedRole == Role.TEXT_ACTION || appliedRole == Role.ICON || appliedRole == Role.CUSTOM_ACTION
					|| role == Role.CUSTOM_TEXT_ACTION || appliedRole == Role.CUSTOM_TEXT_ACTION) {
				view.setStateListAnimator(animator); view.setElevation(elevation);
			}
			if (view instanceof ImageView) ((ImageView) view).setImageTintList(imageTint);
			if (view instanceof CompoundButton) { CompoundButton c = (CompoundButton) view; c.setButtonDrawable(mark); c.setButtonTintList(buttonTint); }
			if (view instanceof Switch) {
				Switch s = (Switch) view; s.setTrackDrawable(track); s.setThumbDrawable(thumb); s.setTrackTintList(trackTint); s.setThumbTintList(thumbTint); s.setSwitchMinWidth(switchWidth); s.setSplitTrack(splitTrack);
			} else if (view instanceof SeekBar) {
				SeekBar b = (SeekBar) view; b.setProgressDrawable(progress); b.setThumb(thumb); b.setProgressTintList(progressTint); b.setProgressBackgroundTintList(progressBackgroundTint);
				b.setThumbTintList(thumbTint); b.setThumbOffset(thumbOffset); b.setSplitTrack(splitTrack); b.setMinHeight(minTrackHeight); b.setMaxHeight(maxTrackHeight);
			}
		}
	}
	private static final class Mark extends Drawable {
		final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG); final Path path = new Path(); final Palette palette; final int size; int alpha = 255;
		final boolean radio;
		Mark(View view, Palette palette, boolean radio) { this.palette = palette; this.radio = radio; size = dp(view, 24); }
		@Override public boolean isStateful() { return true; }
		@Override protected boolean onStateChange(int[] state) { invalidateSelf(); return true; }
		@Override public int getIntrinsicWidth() { return size; }
		@Override public int getIntrinsicHeight() { return size; }
		@Override public void setAlpha(int alpha) { this.alpha = alpha; invalidateSelf(); }
		@Override public void setColorFilter(ColorFilter filter) { paint.setColorFilter(filter); invalidateSelf(); }
		@SuppressWarnings("deprecation") @Override public int getOpacity() { return PixelFormat.TRANSLUCENT; }
		@Override public void draw(Canvas canvas) {
			boolean checked = false, enabled = false; for (int state : getState()) { if (state == android.R.attr.state_checked) checked = true; if (state == android.R.attr.state_enabled) enabled = true; }
			Rect b = getBounds(); int save = canvas.save(); canvas.translate(b.left, b.top); canvas.scale(b.width() / 24f, b.height() / 24f);
			int color = checked ? palette.accent : palette.outline; if (!enabled) color = OpaqueColors.blend(palette.surface, color, .38f);
			paint.setColor(color); paint.setAlpha(alpha); paint.setStyle(checked ? Paint.Style.FILL : Paint.Style.STROKE); paint.setStrokeWidth(2);
			if (radio) {
				paint.setStyle(Paint.Style.STROKE); canvas.drawCircle(12, 12, 9, paint);
				if (checked) { paint.setStyle(Paint.Style.FILL); canvas.drawCircle(12, 12, 5, paint); }
			} else canvas.drawRoundRect(3, 3, 21, 21, 4, 4, paint);
			if (checked && !radio) { paint.setColor(palette.onAccent); paint.setAlpha(alpha); paint.setStyle(Paint.Style.STROKE); paint.setStrokeWidth(2); paint.setStrokeCap(Paint.Cap.ROUND); paint.setStrokeJoin(Paint.Join.ROUND);
				path.reset(); path.moveTo(7, 12); path.lineTo(10.5f, 15.5f); path.lineTo(17, 9); canvas.drawPath(path, paint); }
			canvas.restoreToCount(save);
		}
	}
}
