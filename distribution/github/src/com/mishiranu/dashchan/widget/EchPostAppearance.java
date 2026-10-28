package com.mishiranu.dashchan.widget;

import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.InsetDrawable;
import android.graphics.drawable.RippleDrawable;
import android.graphics.drawable.StateListDrawable;
import android.util.TypedValue;
import android.view.Gravity;
import android.widget.TextView;
import com.mishiranu.dashchan.R;
import com.mishiranu.dashchan.content.Preferences;
import com.mishiranu.dashchan.util.OpaqueColors;

/** ECH-only presentation. No post binding, icon loading, requests or preference writes. */
public final class EchPostAppearance {
	private EchPostAppearance() {}
	private static final class Legacy {
		final AppearanceTypography.Snapshot typography;
		final Typeface typeface;
		final Drawable background;
		final ColorStateList tint, text;
		final int start, top, end, bottom, width, height, minWidth, minHeight, gravity, minLines, maxLines;
		final android.text.TextUtils.TruncateAt ellipsize;
		Legacy(TextView view) {
			typography = new AppearanceTypography.Snapshot(view); typeface = view.getTypeface();
			background = view.getBackground(); tint = view.getBackgroundTintList(); text = view.getTextColors();
			start = view.getPaddingStart(); top = view.getPaddingTop(); end = view.getPaddingEnd(); bottom = view.getPaddingBottom();
			width = view.getMinimumWidth(); height = view.getMinimumHeight();
			minWidth = view.getMinWidth(); minHeight = view.getMinHeight(); gravity = view.getGravity();
			minLines = view.getMinLines(); maxLines = view.getMaxLines(); ellipsize = view.getEllipsize();
		}
		void restore(TextView view, boolean action) {
			typography.restore(view); view.setTextColor(text); view.setBackgroundTintList(tint); view.setBackground(background);
			view.setPaddingRelative(start, top, end, bottom); view.setMinimumWidth(width); view.setMinimumHeight(height);
			view.setMinWidth(minWidth); view.setMinHeight(minHeight); view.setGravity(gravity);
			view.setSingleLine(maxLines == 1); if (minLines >= 0) view.setMinLines(minLines);
			if (maxLines >= 0) view.setMaxLines(maxLines); view.setEllipsize(ellipsize);
			if (action) view.setTypeface(Typeface.create(typeface, view.isSelected() ? Typeface.BOLD : Typeface.NORMAL));
		}
	}
	private static Legacy legacy(TextView view) {
		Object tag = view.getTag(R.id.ech_post_appearance);
		Legacy state = tag instanceof Legacy ? (Legacy) tag : new Legacy(view);
		view.setTag(R.id.ech_post_appearance, state); return state;
	}
	private static void typography(TextView view, AppearanceTypography.Role role) {
		AppearanceTypography.apply(view, role);
		float scale = Preferences.getTextScale(); int line = view.getLineHeight();
		view.setTextSize(TypedValue.COMPLEX_UNIT_PX, view.getTextSize() * scale);
		view.setLineHeight(Math.max(1, Math.round(line * scale)));
		view.setSingleLine(false); view.setMaxLines(Integer.MAX_VALUE); view.setEllipsize(null);
	}
	public static void section(TextView view) {
		Legacy state = legacy(view);
		if (!InterfaceAppearance.isEnabled(view.getContext())) { state.restore(view, false); return; }
		typography(view, AppearanceTypography.Role.BODY_MEDIUM);
		AppearanceColors c = StandardAppearance.colors(view.getContext());
		view.setTextColor(StandardAppearance.textColors(c.onSurfaceVariant, c.window));
		view.setBackground(null); view.setPaddingRelative(StandardAppearance.dp(view, 8), StandardAppearance.dp(view, 8),
				StandardAppearance.dp(view, 8), StandardAppearance.dp(view, 4));
	}
	public static void action(TextView view, boolean poll) {
		Legacy state = legacy(view);
		if (!InterfaceAppearance.isEnabled(view.getContext())) { state.restore(view, true); return; }
		typography(view, poll ? AppearanceTypography.Role.BODY_LARGE : AppearanceTypography.Role.LABEL_LARGE);
		AppearanceColors c = StandardAppearance.colors(view.getContext());
		int normal = InterfaceAppearance.contrasting(c.onSurface, c.surfaceContainer, 4.5);
		int selectedFill = OpaqueColors.blend(c.primaryContainer, c.primary, .16f);
		int selected = InterfaceAppearance.contrasting(c.onPrimaryContainer, selectedFill, 4.5);
		view.setTextColor(new ColorStateList(new int[][]{{-android.R.attr.state_enabled}, {android.R.attr.state_selected}, {}},
				new int[]{OpaqueColors.blend(c.surfaceContainer, normal, .38f), selected, normal}));
		StateListDrawable fill = new StateListDrawable();
		GradientDrawable selectedShape = shape(view, selectedFill, c.primary, poll);
		selectedShape.setStroke(Math.max(1, StandardAppearance.dp(view, 2)), c.primary);
		fill.addState(new int[]{android.R.attr.state_selected}, selectedShape);
		fill.addState(new int[]{}, shape(view, c.surfaceContainer, c.outlineVariant, poll));
		Drawable compact = poll ? fill : new InsetDrawable(fill, 0, StandardAppearance.dp(view, 4), 0, StandardAppearance.dp(view, 4));
		view.setBackgroundTintList(null);
		view.setBackground(new RippleDrawable(new ColorStateList(new int[][]{{-android.R.attr.state_enabled}, {}},
				new int[]{Color.TRANSPARENT, c.ripple}), compact, StandardAppearance.shape(view, Color.WHITE, poll ? 8 : 16)));
		int target = StandardAppearance.dp(view, AppearanceShapes.STANDARD_TARGET);
		view.setMinHeight(target); view.setMinimumHeight(target); view.setMinWidth(target); view.setMinimumWidth(target);
		view.setPaddingRelative(StandardAppearance.dp(view, 12), StandardAppearance.dp(view, 8),
				StandardAppearance.dp(view, 12), StandardAppearance.dp(view, 8));
		view.setGravity((poll ? Gravity.START : Gravity.CENTER_HORIZONTAL) | Gravity.CENTER_VERTICAL);
		// Semantic reaction bitmaps/compound drawables are deliberately never tinted.
	}
	private static GradientDrawable shape(TextView view, int fill, int stroke, boolean poll) {
		GradientDrawable drawable = StandardAppearance.shape(view, fill, poll ? AppearanceShapes.SMALL : AppearanceShapes.LARGE);
		drawable.setStroke(Math.max(1, StandardAppearance.dp(view, 1)), stroke); return drawable;
	}
}
