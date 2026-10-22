package com.mishiranu.dashchan.widget;

import android.view.View;
import android.widget.ImageView;
import android.widget.LinearLayout;
import com.mishiranu.dashchan.R;
import com.mishiranu.dashchan.ui.preference.core.Preference;
import com.mishiranu.dashchan.util.ResourceUtils;

/** Shared presentation only; selection/install/dismiss remain owned by the two adapters. */
public final class ThemeChoiceRow {
	private final Preference.Runtime.IconViewHolder holder;
	private final ImageView check;
	private final boolean dialog;
	private ThemeEngine.Theme boundTheme;
	private boolean installed, currentAtBind;
	public ThemeChoiceRow(Preference.Runtime.IconViewHolder holder, boolean dialog) {
		this.holder = holder; this.dialog = dialog;
		holder.view.setTag(R.id.screen_theme_choice, this);
		check = new ImageView(holder.view.getContext());
		check.setImageResource(R.drawable.ic_editor_done);
		check.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
		int size = Math.round(24 * ResourceUtils.obtainDensity(holder.view));
		holder.widgetFrame.addView(check, new LinearLayout.LayoutParams(size, size));
		InterfaceAppearance.themeChoice(holder.view, holder.title, holder.icon, check, false, dialog);
	}
	public void bind(ThemeEngine.Theme theme, boolean installed) {
		boundTheme = theme; this.installed = installed;
		currentAtBind = installed && ThemeEngine.isCurrentTheme(holder.view.getContext(), theme);
		holder.icon.setImageDrawable(theme.createThemeChoiceDrawable());
		holder.title.setText(theme.name);
		InterfaceAppearance.themeChoice(holder.view, holder.title, holder.icon, check,
				currentAtBind, dialog);
	}

	/** Geometry/state only. Never include theme names, JSON, labels or other user content. */
	public String diagnosticState() {
		ThemeEngine.Theme current = ThemeEngine.peekTheme(holder.view.getContext());
		StringBuilder out = new StringBuilder("installed=").append(installed)
				.append(" builtIn=").append(boundTheme != null && boundTheme.builtIn)
				.append(" currentAtBind=").append(currentAtBind)
				.append(" currentNow=").append(boundTheme != null && ThemeEngine.peekTheme(holder.view.getContext()) != null
						&& installed && ThemeEngine.isCurrentTheme(holder.view.getContext(), boundTheme))
				.append(" sameObject=").append(boundTheme != null && boundTheme == current)
				.append(" sameName=").append(boundTheme != null && current != null && boundTheme.name.equals(current.name))
				.append(" configuredMatch=").append(boundTheme != null && installed && boundTheme.name.equals(
						com.mishiranu.dashchan.content.Preferences.getTheme(holder.view.getContext())))
				.append(" serviceTag=").append(holder.view.getTag(R.id.standard_service_appearance) != null)
				.append(" material=").append(InterfaceAppearance.isEnabled(holder.view.getContext()));
		facts(out, "row", holder.view); facts(out, "slot", holder.widgetFrame); facts(out, "check", check);
		out.append(" drawable=").append(check.getDrawable() != null)
				.append(" drawableAlpha=").append(check.getDrawable() != null ? check.getDrawable().getAlpha() : -1)
				.append(" iconTint=").append(check.getImageTintList() != null
						? Integer.toHexString(check.getImageTintList().getColorForState(check.getDrawableState(), 0)) : "none");
		return out.toString();
	}
	private static void facts(StringBuilder out, String name, View view) {
		android.graphics.Rect visible = new android.graphics.Rect();
		boolean onScreen = view.getGlobalVisibleRect(visible);
		out.append(' ').append(name).append("{vis=").append(view.getVisibility())
				.append(" shown=").append(view.isShown()).append(" selected=").append(view.isSelected())
				.append(" alpha=").append(view.getAlpha()).append(" sizePx=").append(view.getWidth()).append('x').append(view.getHeight())
				.append(" measuredPx=").append(view.getMeasuredWidth()).append('x').append(view.getMeasuredHeight())
				.append(" xyLocalPx=").append(view.getLeft()).append(',').append(view.getTop())
				.append(" onScreen=").append(onScreen).append(" visibleRectPx=").append(visible.toShortString());
		android.view.ViewGroup.LayoutParams params = view.getLayoutParams();
		if (params != null) out.append(" lpPx=").append(params.width).append('x').append(params.height);
		if (params instanceof LinearLayout.LayoutParams) out.append(" weight=").append(((LinearLayout.LayoutParams) params).weight);
		out.append('}');
	}
}
