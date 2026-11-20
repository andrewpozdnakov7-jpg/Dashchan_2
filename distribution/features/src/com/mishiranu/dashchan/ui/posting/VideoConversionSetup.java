package com.mishiranu.dashchan.ui.posting;

import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.text.TextUtils;
import android.view.ContextThemeWrapper;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import androidx.core.graphics.ColorUtils;
import androidx.core.widget.NestedScrollView;
import chan.util.StringUtils;
import com.google.android.material.bottomsheet.BottomSheetBehavior;
import com.google.android.material.bottomsheet.BottomSheetDialog;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.checkbox.MaterialCheckBox;
import com.mishiranu.dashchan.R;
import com.mishiranu.dashchan.content.FontManager;
import com.mishiranu.dashchan.content.security.AppSecurity;
import com.mishiranu.dashchan.util.InterfaceMotion;
import com.mishiranu.dashchan.util.ResourceUtils;
import com.mishiranu.dashchan.widget.AppearanceColors;
import com.mishiranu.dashchan.widget.CarryLayout;
import com.mishiranu.dashchan.widget.ThemeEngine;
import java.util.function.Supplier;

/** Content-sized options only. The caller owns the attachment, job and lifecycle observation. */
public final class VideoConversionSetup {
	private VideoConversionSetup() {}
	public static Context materialContext(Context host) {
		return new ContextThemeWrapper(host, ThemeEngine.getTheme(host).base == ThemeEngine.Theme.Base.LIGHT
				? R.style.Theme_Slooop_StartupNotice_Light : R.style.Theme_Slooop_StartupNotice_Dark);
	}
	private static int dp(Context context, float value) { return Math.round(value * ResourceUtils.obtainDensity(context)); }
	private static TextView text(Context context, int sp, int color) {
		TextView text = new TextView(context); text.setTextSize(sp); text.setTextColor(color);
		FontManager.applyTypeface(text); return text;
	}
	private static ColorStateList enabledColors(int enabled, int disabled) {
		return new ColorStateList(new int[][] {{-android.R.attr.state_enabled}, {}}, new int[] {disabled, enabled});
	}
	private static int lightButtonColor(int primary) {
		if (ColorUtils.calculateContrast(Color.WHITE, primary) >= 4.5) return primary;
		// Local tone adjustment of the active accent, never a fixed color for other themes.
		for (int step = 1; step <= 20; step++) {
			int toned = ColorUtils.blendARGB(primary, Color.BLACK, step / 20f);
			if (ColorUtils.calculateContrast(Color.WHITE, toned) >= 4.5) return toned;
		}
		return Color.BLACK;
	}
	public static Panel create(Context host, AttachmentHolder holder, Supplier<VideoSizeSummary> summary, Runnable convert) {
		ThemeEngine.Theme theme = ThemeEngine.getTheme(host);
		AppearanceColors colors = AppearanceColors.standardInterface(theme.base == ThemeEngine.Theme.Base.DARK,
				theme.window, theme.accent, ResourceUtils.getColor(host, android.R.attr.textColorPrimary),
				ResourceUtils.getColor(host, R.attr.colorTextError));
		return new Panel(materialContext(host), holder, summary, convert, colors, theme.base == ThemeEngine.Theme.Base.DARK);
	}

	public static final class Panel extends BottomSheetDialog {
		private final Supplier<VideoSizeSummary> summary;
		private final TextView size;
		private final MaterialButton start;
		private boolean conversionRequested;
		private Panel(Context context, AttachmentHolder holder, Supplier<VideoSizeSummary> summary, Runnable convert,
				AppearanceColors colors, boolean dark) {
			super(context); this.summary = summary;
			AppSecurity.protectDialog(this);
			int primary = dark ? colors.primary : lightButtonColor(colors.primary);
			int onPrimary = dark ? colors.onPrimary : Color.WHITE;
			LinearLayout content = new LinearLayout(context); content.setOrientation(LinearLayout.VERTICAL);
			content.setPadding(dp(context, 16), dp(context, 8), dp(context, 16), dp(context, 12));
			View handle = new View(context); GradientDrawable handleShape = new GradientDrawable();
			handleShape.setColor(colors.onSurfaceVariant); handleShape.setCornerRadius(dp(context, 2)); handle.setBackground(handleShape);
			handle.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
			LinearLayout.LayoutParams handleParams = new LinearLayout.LayoutParams(dp(context, 32), dp(context, 4));
			handleParams.gravity = Gravity.CENTER_HORIZONTAL; handleParams.bottomMargin = dp(context, 8); content.addView(handle, handleParams);
			TextView title = text(context, 20, colors.onSurface); title.setText(R.string.slooop_video_options_title);
			title.setTypeface(FontManager.resolveTypeface(context, true)); title.setAccessibilityHeading(true);
			content.addView(title, new LinearLayout.LayoutParams(-1, -2));
			LinearLayout file = new LinearLayout(context); file.setGravity(Gravity.CENTER_VERTICAL);
			file.setMinimumHeight(dp(context, 48));
			ImageView preview = new ImageView(context); preview.setScaleType(ImageView.ScaleType.CENTER_CROP);
			preview.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
			Drawable original = holder.imageView.getDrawable();
			if (original != null && original.getConstantState() != null) {
				preview.setImageDrawable(original.getConstantState().newDrawable(context.getResources()).mutate());
			}
			GradientDrawable previewShape = new GradientDrawable(); previewShape.setColor(colors.surface);
			previewShape.setCornerRadius(dp(context, 2)); preview.setBackground(previewShape); preview.setClipToOutline(true);
			file.addView(preview, new LinearLayout.LayoutParams(dp(context, 56), dp(context, 40)));
			LinearLayout names = new LinearLayout(context); names.setOrientation(LinearLayout.VERTICAL);
			TextView name = text(context, 14, colors.onSurface); name.setSingleLine(); name.setEllipsize(TextUtils.TruncateAt.END);
			name.setText(holder.name); name.setContentDescription(holder.name); names.addView(name);
			size = text(context, 12, colors.onSurfaceVariant); size.setSingleLine(); names.addView(size);
			LinearLayout.LayoutParams namesParams = new LinearLayout.LayoutParams(0, -2, 1);
			namesParams.setMarginStart(dp(context, 12)); file.addView(names, namesParams);
			LinearLayout.LayoutParams fileParams = new LinearLayout.LayoutParams(-1, -2);
			fileParams.topMargin = dp(context, 12); content.addView(file, fileParams);
			CarryLayout checks = new CarryLayout(context); checks.setMinimumHeight(dp(context, 48)); checks.setHorizontalSpacing(dp(context, 12));
			MaterialCheckBox unique = checkbox(context, R.string.unique_hash, holder.optionUniqueHash, colors, primary, onPrimary);
			MaterialCheckBox removeName = checkbox(context, R.string.remove_file_name, holder.optionRemoveFileName, colors, primary, onPrimary);
			unique.setOnCheckedChangeListener((button, checked) -> {
				if (summary.get() != null) holder.optionUniqueHash = checked; else dismiss();
			});
			removeName.setOnCheckedChangeListener((button, checked) -> {
				if (summary.get() != null) holder.optionRemoveFileName = checked; else dismiss();
			});
			checks.addView(unique, new ViewGroup.LayoutParams(-2, -2));
			checks.addView(removeName, new ViewGroup.LayoutParams(-2, -2));
			LinearLayout.LayoutParams checkParams = new LinearLayout.LayoutParams(-1, -2);
			checkParams.topMargin = dp(context, 4); content.addView(checks, checkParams);
			ActionRow actions = new ActionRow(context); actions.setGravity(Gravity.END | Gravity.CENTER_VERTICAL);
			MaterialButton close = new MaterialButton(context); close.setText(R.string.slooop_video_options_close); close.setAllCaps(false);
			FontManager.applyTypeface(close); close.setTextSize(14); close.setMinWidth(0); close.setMinimumWidth(0);
			close.setSingleLine(false); close.setEllipsize(null);
			close.setBackgroundTintList(ColorStateList.valueOf(Color.TRANSPARENT)); close.setStrokeWidth(0);
			close.setTextColor(enabledColors(primary, colors.disabledContent)); close.setRippleColor(ColorStateList.valueOf(colors.ripple));
			close.setMinimumHeight(dp(context, 48)); close.setOnClickListener(v -> dismiss());
			actions.addView(close, new LinearLayout.LayoutParams(-2, -2));
			start = new MaterialButton(context); start.setText(R.string.compression_convert); start.setAllCaps(false);
			FontManager.applyTypeface(start); start.setTextSize(14); start.setMinWidth(0); start.setMinimumWidth(0);
			start.setSingleLine(false); start.setEllipsize(null);
			start.setBackgroundTintList(enabledColors(primary, colors.disabledContainer));
			start.setTextColor(enabledColors(onPrimary, colors.disabledContent)); start.setRippleColor(ColorStateList.valueOf(colors.ripple));
			start.setMinimumHeight(dp(context, 48));
			start.setOnClickListener(v -> {
				VideoSizeSummary current = summary.get();
				if (!conversionRequested && current != null && current.convertible && !current.busy) {
					conversionRequested = true; start.setEnabled(false); dismiss(); convert.run();
				} else refresh();
			});
			LinearLayout.LayoutParams startParams = new LinearLayout.LayoutParams(0, -2, 1);
			startParams.setMarginStart(dp(context, 8)); actions.addView(start, startParams);
			LinearLayout.LayoutParams actionParams = new LinearLayout.LayoutParams(-1, -2);
			actionParams.topMargin = dp(context, 8); content.addView(actions, actionParams);
			NestedScrollView scroll = new NestedScrollView(context); scroll.setFillViewport(false); scroll.addView(content);
			setContentView(scroll);
			setDismissWithAnimation(InterfaceMotion.isEnabled() && InterfaceMotion.duration(200) > 0);
			getBehavior().setFitToContents(true); getBehavior().setSkipCollapsed(true);
			getBehavior().setState(BottomSheetBehavior.STATE_EXPANDED);
			if ((!InterfaceMotion.isEnabled() || InterfaceMotion.duration(200) == 0) && getWindow() != null) getWindow().setWindowAnimations(0);
			setOnShowListener(ignored -> {
				View sheet = findViewById(com.google.android.material.R.id.design_bottom_sheet);
				if (sheet != null) {
					GradientDrawable shape = new GradientDrawable(); shape.setColor(colors.surface);
					float radius = dp(context, 28); shape.setCornerRadii(new float[] {radius, radius, radius, radius, 0, 0, 0, 0});
					sheet.setBackground(shape); sheet.getLayoutParams().height = ViewGroup.LayoutParams.WRAP_CONTENT;
				}
				if ((!InterfaceMotion.isEnabled() || InterfaceMotion.duration(200) == 0) && getWindow() != null) getWindow().setWindowAnimations(0);
			});
			refresh();
		}
		public void refresh() {
			VideoSizeSummary current = summary.get();
			if (current == null) { start.setEnabled(false); if (isShowing()) dismiss(); return; }
			size.setText(current.converted ? getContext().getString(R.string.slooop_video_options_size_pair,
					StringUtils.formatFileSize(current.beforeBytes, false), StringUtils.formatFileSize(current.afterBytes, false))
					: current.currentBytes >= 0 ? StringUtils.formatFileSize(current.currentBytes, false) : "\u2014");
			start.setEnabled(!conversionRequested && current.convertible && !current.busy);
		}
	}
	/** Same natural-width fallback as the app's message dialogs; no shrinking accessibility text. */
	private static final class ActionRow extends LinearLayout {
		ActionRow(Context context) { super(context); }
		@Override protected void onMeasure(int widthSpec, int heightSpec) {
			int gap = dp(getContext(), 8), natural = gap;
			for (int i = 0; i < getChildCount(); i++) {
				View child = getChildAt(i);
				child.measure(MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED),
						MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED));
				natural += child.getMeasuredWidth();
			}
			boolean stacked = MeasureSpec.getMode(widthSpec) != MeasureSpec.UNSPECIFIED && natural > MeasureSpec.getSize(widthSpec);
			setOrientation(stacked ? VERTICAL : HORIZONTAL);
			for (int i = 0; i < getChildCount(); i++) {
				LayoutParams params = (LayoutParams) getChildAt(i).getLayoutParams();
				params.width = stacked ? LayoutParams.MATCH_PARENT : i == 0 ? LayoutParams.WRAP_CONTENT : 0;
				params.weight = !stacked && i > 0 ? 1 : 0;
				params.setMarginStart(!stacked && i > 0 ? gap : 0);
				params.topMargin = stacked && i > 0 ? gap : 0;
			}
			super.onMeasure(widthSpec, heightSpec);
		}
	}
	private static MaterialCheckBox checkbox(Context context, int label, boolean checked,
			AppearanceColors colors, int primary, int onPrimary) {
		// This local control never registers the settings-row styler which fills checked labels.
		MaterialCheckBox box = new MaterialCheckBox(context); box.setText(label); box.setChecked(checked);
		FontManager.applyTypeface(box); box.setTextSize(14); box.setTextColor(enabledColors(colors.onSurface, colors.disabledContent));
		box.setSingleLine(false); box.setEllipsize(null);
		box.setUseMaterialThemeColors(false);
		box.setButtonTintList(new ColorStateList(new int[][] {{-android.R.attr.state_enabled}, {android.R.attr.state_checked}, {}},
				new int[] {colors.disabledContent, primary, colors.outline}));
		box.setButtonIconTintList(enabledColors(onPrimary, colors.surface));
		GradientDrawable mask = new GradientDrawable(); mask.setColor(Color.WHITE); mask.setCornerRadius(dp(context, 4));
		box.setBackgroundTintList(null); box.setBackground(new RippleDrawable(ColorStateList.valueOf(colors.ripple), null, mask));
		box.setMinHeight(dp(context, 48)); box.setMinimumHeight(dp(context, 48)); box.setMinWidth(0); box.setMinimumWidth(0);
		box.setPadding(0, 0, 0, 0); box.setCompoundDrawablePadding(dp(context, 8)); return box;
	}
}
