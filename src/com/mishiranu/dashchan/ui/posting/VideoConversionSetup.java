package com.mishiranu.dashchan.ui.posting;

import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.text.TextUtils;
import android.view.ContextThemeWrapper;
import android.view.Gravity;
import android.view.View;
import android.widget.CheckBox;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import androidx.core.graphics.ColorUtils;
import androidx.core.widget.NestedScrollView;
import com.google.android.material.bottomsheet.BottomSheetBehavior;
import com.google.android.material.bottomsheet.BottomSheetDialog;
import com.google.android.material.button.MaterialButton;
import com.mishiranu.dashchan.R;
import com.mishiranu.dashchan.content.security.AppSecurity;
import com.mishiranu.dashchan.util.InterfaceMotion;
import com.mishiranu.dashchan.util.ResourceUtils;
import com.mishiranu.dashchan.widget.CarryLayout;
import com.mishiranu.dashchan.widget.ThemeEngine;

/** Small, content-sized setup. The caller owns attachment options and conversion actions. */
public final class VideoConversionSetup {
	private VideoConversionSetup() {}
	public static Context materialContext(Context host) {
		return new ContextThemeWrapper(host, ThemeEngine.getTheme(host).base == ThemeEngine.Theme.Base.LIGHT
				? R.style.Theme_Slooop_StartupNotice_Light : R.style.Theme_Slooop_StartupNotice_Dark);
	}
	private static int dp(Context context, float value) { return Math.round(value * ResourceUtils.obtainDensity(context)); }
	private static TextView text(Context context, int sp, int color) {
		TextView text = new TextView(context); text.setTextSize(sp); text.setTextColor(color); return text;
	}
	public static BottomSheetDialog create(Context host, AttachmentHolder holder, int position, int count,
			boolean convertible, boolean hasDetails, Runnable convert, Runnable details, Runnable more) {
		Context themed = materialContext(host);
		ThemeEngine.Theme theme = ThemeEngine.getTheme(host);
		int surface = ColorUtils.setAlphaComponent(theme.window, 255);
		int ink = ResourceUtils.getColor(host, android.R.attr.textColorPrimary);
		int secondary = ResourceUtils.getColor(host, android.R.attr.textColorSecondary);
		int primary = ColorUtils.setAlphaComponent(theme.accent, 255);
		BottomSheetDialog dialog = new BottomSheetDialog(themed);
		AppSecurity.protectDialog(dialog);
		LinearLayout content = new LinearLayout(host); content.setOrientation(LinearLayout.VERTICAL);
		content.setPadding(dp(host, 16), dp(host, 8), dp(host, 16), dp(host, 12));
		View handle = new View(host); GradientDrawable handleShape = new GradientDrawable();
		handleShape.setColor(secondary); handleShape.setCornerRadius(dp(host, 2)); handle.setBackground(handleShape);
		handle.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
		LinearLayout.LayoutParams handleParams = new LinearLayout.LayoutParams(dp(host, 32), dp(host, 4));
		handleParams.gravity = Gravity.CENTER_HORIZONTAL; handleParams.bottomMargin = dp(host, 4); content.addView(handle, handleParams);
		LinearLayout header = new LinearLayout(host); header.setGravity(Gravity.CENTER_VERTICAL);
		TextView title = text(host, 20, ink); title.setText(R.string.slooop_converter_ui_title);
		title.setTypeface(ResourceUtils.TYPEFACE_MEDIUM); title.setAccessibilityHeading(true);
		header.addView(title, new LinearLayout.LayoutParams(0, -2, 1));
		if (count > 0) {
			TextView counter = text(host, 12, secondary);
			counter.setText(host.getString(R.string.slooop_converter_ui_file_position_compact, position, count));
			LinearLayout.LayoutParams counterParams = new LinearLayout.LayoutParams(-2, -2); counterParams.setMarginStart(dp(host, 8));
			header.addView(counter, counterParams);
		}
		content.addView(header, new LinearLayout.LayoutParams(-1, -2));
		LinearLayout file = new LinearLayout(host); file.setGravity(Gravity.CENTER_VERTICAL);
		file.setMinimumHeight(dp(host, 48)); file.setContentDescription(holder.name + ", " + host.getString(R.string.slooop_converter_ui_more_options));
		com.mishiranu.dashchan.util.ViewUtils.setSelectableItemBackground(file);
		file.setOnClickListener(v -> { dialog.dismiss(); more.run(); });
		ImageView preview = new ImageView(host); preview.setScaleType(ImageView.ScaleType.CENTER_CROP);
		Drawable original = holder.imageView.getDrawable();
		if (original != null && original.getConstantState() != null) {
			preview.setImageDrawable(original.getConstantState().newDrawable(host.getResources()).mutate());
		}
		GradientDrawable previewShape = new GradientDrawable(); previewShape.setColor(surface);
		previewShape.setCornerRadius(dp(host, 2)); preview.setBackground(previewShape); preview.setClipToOutline(true);
		file.addView(preview, new LinearLayout.LayoutParams(dp(host, 56), dp(host, 40)));
		LinearLayout names = new LinearLayout(host); names.setOrientation(LinearLayout.VERTICAL);
		TextView name = text(host, 14, ink); name.setSingleLine(); name.setEllipsize(TextUtils.TruncateAt.END);
		name.setText(holder.name); name.setContentDescription(holder.name); names.addView(name);
		TextView size = text(host, 12, secondary); size.setText(holder.fileSize.getText()); names.addView(size);
		LinearLayout.LayoutParams namesParams = new LinearLayout.LayoutParams(0, -2, 1); namesParams.setMarginStart(dp(host, 12)); file.addView(names, namesParams);
		ImageView options = new ImageView(host); options.setImageDrawable(ResourceUtils.getDrawable(host, R.attr.iconButtonMore, 0));
		options.setImageTintList(ColorStateList.valueOf(ink)); options.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
		file.addView(options, new LinearLayout.LayoutParams(dp(host, 24), dp(host, 24)));
		LinearLayout.LayoutParams fileParams = new LinearLayout.LayoutParams(-1, -2); fileParams.topMargin = dp(host, 8); content.addView(file, fileParams);
		CarryLayout checks = new CarryLayout(host); checks.setMinimumHeight(dp(host, 48)); checks.setHorizontalSpacing(dp(host, 12));
		CheckBox unique = checkbox(host, R.string.unique_hash, holder.optionUniqueHash);
		CheckBox removeName = checkbox(host, R.string.remove_file_name, holder.optionRemoveFileName);
		unique.setOnCheckedChangeListener((button, checked) -> holder.optionUniqueHash = checked);
		removeName.setOnCheckedChangeListener((button, checked) -> holder.optionRemoveFileName = checked);
		checks.addView(unique, new android.view.ViewGroup.LayoutParams(-2, -2));
		checks.addView(removeName, new android.view.ViewGroup.LayoutParams(-2, -2));
		LinearLayout.LayoutParams checkParams = new LinearLayout.LayoutParams(-1, -2); checkParams.topMargin = dp(host, 4); content.addView(checks, checkParams);
		if (hasDetails) {
			TextView result = text(host, 14, primary); result.setText(R.string.slooop_converter_ui_details);
			result.setMinimumHeight(dp(host, 48)); result.setGravity(Gravity.CENTER_VERTICAL);
			result.setOnClickListener(v -> { dialog.dismiss(); details.run(); }); content.addView(result, new LinearLayout.LayoutParams(-1, -2));
		}
		LinearLayout actions = new LinearLayout(themed); actions.setGravity(Gravity.END | Gravity.CENTER_VERTICAL);
		MaterialButton cancel = new MaterialButton(themed); cancel.setText(android.R.string.cancel); cancel.setAllCaps(false);
		cancel.setBackgroundTintList(ColorStateList.valueOf(Color.TRANSPARENT)); cancel.setTextColor(primary); cancel.setMinimumHeight(dp(host, 48));
		cancel.setOnClickListener(v -> dialog.dismiss()); actions.addView(cancel, new LinearLayout.LayoutParams(-2, -2));
		MaterialButton start = new MaterialButton(themed); start.setText(R.string.compression_convert); start.setAllCaps(false);
		start.setBackgroundTintList(ColorStateList.valueOf(primary)); start.setTextColor(ColorUtils.calculateContrast(Color.WHITE, primary) >= 4.5 ? Color.WHITE : Color.BLACK);
		start.setMinimumHeight(dp(host, 48)); start.setEnabled(convertible);
		start.setOnClickListener(v -> { dialog.dismiss(); convert.run(); });
		LinearLayout.LayoutParams startParams = new LinearLayout.LayoutParams(0, -2, 1); startParams.setMarginStart(dp(host, 8)); actions.addView(start, startParams);
		LinearLayout.LayoutParams actionParams = new LinearLayout.LayoutParams(-1, -2); actionParams.topMargin = dp(host, 8); content.addView(actions, actionParams);
		NestedScrollView scroll = new NestedScrollView(themed); scroll.setFillViewport(false); scroll.addView(content);
		dialog.setContentView(scroll);
		dialog.setDismissWithAnimation(InterfaceMotion.isEnabled() && InterfaceMotion.duration(200) > 0);
		dialog.getBehavior().setFitToContents(true); dialog.getBehavior().setSkipCollapsed(true);
		dialog.getBehavior().setState(BottomSheetBehavior.STATE_EXPANDED);
		if ((!InterfaceMotion.isEnabled() || InterfaceMotion.duration(200) == 0) && dialog.getWindow() != null) dialog.getWindow().setWindowAnimations(0);
		dialog.setOnShowListener(ignored -> {
			View sheet = dialog.findViewById(com.google.android.material.R.id.design_bottom_sheet);
			if (sheet != null) {
				GradientDrawable shape = new GradientDrawable(); shape.setColor(surface);
				float radius = dp(host, 28); shape.setCornerRadii(new float[] {radius, radius, radius, radius, 0, 0, 0, 0});
				sheet.setBackground(shape); sheet.getLayoutParams().height = android.view.ViewGroup.LayoutParams.WRAP_CONTENT;
			}
			if ((!InterfaceMotion.isEnabled() || InterfaceMotion.duration(200) == 0) && dialog.getWindow() != null) dialog.getWindow().setWindowAnimations(0);
		});
		return dialog;
	}
	private static CheckBox checkbox(Context host, int text, boolean checked) {
		CheckBox box = new CheckBox(host); box.setText(text); box.setChecked(checked);
		ThemeEngine.applyStyle(box); com.mishiranu.dashchan.widget.InterfaceAppearance.standardFormChoice(box);
		box.setTextSize(14); box.setMinHeight(dp(host, 48)); box.setPadding(0, 0, 0, 0); return box;
	}
}
