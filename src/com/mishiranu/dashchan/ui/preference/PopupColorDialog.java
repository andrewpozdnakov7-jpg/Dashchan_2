package com.mishiranu.dashchan.ui.preference;

import android.app.AlertDialog;
import android.app.Dialog;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Shader;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.text.Editable;
import android.text.InputFilter;
import android.text.InputType;
import android.text.TextWatcher;
import android.view.MotionEvent;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.TextView;
import androidx.annotation.NonNull;
import androidx.core.graphics.ColorUtils;
import androidx.fragment.app.DialogFragment;
import com.mishiranu.dashchan.R;
import com.mishiranu.dashchan.content.Preferences;
import com.mishiranu.dashchan.util.ResourceUtils;
import com.mishiranu.dashchan.widget.SafePasteEditText;
import com.mishiranu.dashchan.widget.ThemeEngine;
import java.util.Locale;

/** Local draft: only OK commits the selected opaque color. */
public class PopupColorDialog extends DialogFragment {
	private final float[] hsv = new float[3];
	private TextView preview;
	private TextView warning;
	private SafePasteEditText hex;
	private SeekBar hue;
	private ColorField field;
	private boolean syncing;
	private int color;

	public static PopupColorDialog newInstance(String key, int title) {
		PopupColorDialog dialog = new PopupColorDialog();
		Bundle args = new Bundle();
		args.putString("key", key);
		args.putInt("title", title);
		dialog.setArguments(args);
		return dialog;
	}

	private boolean isBackground() {
		return Preferences.KEY_POPUP_BACKGROUND.equals(requireArguments().getString("key"));
	}

	private int dp(float value) { return Math.round(value * ResourceUtils.obtainDensity(requireContext())); }

	@NonNull
	@Override
	public Dialog onCreateDialog(Bundle savedInstanceState) {
		Context context = requireContext();
		color = savedInstanceState != null ? savedInstanceState.getInt("color")
				: isBackground() ? Preferences.getPopupBackground() : Preferences.getPopupForeground();
		Color.colorToHSV(color, hsv);
		if (savedInstanceState != null) {
			float[] savedHsv = savedInstanceState.getFloatArray("hsv");
			if (savedHsv != null && savedHsv.length == 3) System.arraycopy(savedHsv, 0, hsv, 0, 3);
		}
		LinearLayout layout = new LinearLayout(context);
		layout.setOrientation(LinearLayout.VERTICAL);
		layout.setPadding(dp(24), dp(12), dp(24), dp(8));
		preview = new TextView(context);
		preview.setForceDarkAllowed(false);
		preview.setText(R.string.popup_preview_message);
		preview.setTextSize(14);
		preview.setPadding(dp(12), dp(12), dp(12), dp(12));
		layout.addView(preview, new LinearLayout.LayoutParams(-1, -2));
		warning = new TextView(context);
		ThemeEngine.applyStyle(warning);
		warning.setText(R.string.popup_low_contrast);
		layout.addView(warning);
		field = new ColorField(context);
		LinearLayout.LayoutParams fieldParams = new LinearLayout.LayoutParams(-1, dp(160));
		fieldParams.topMargin = dp(12);
		layout.addView(field, fieldParams);
		TextView hueLabel = new TextView(context);
		ThemeEngine.applyStyle(hueLabel);
		hueLabel.setText(R.string.popup_color_hue);
		hueLabel.setPadding(0, dp(12), 0, dp(4));
		layout.addView(hueLabel);
		View spectrum = new View(context);
		spectrum.setForceDarkAllowed(false);
		spectrum.setBackground(new GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT,
				new int[] {Color.RED, Color.YELLOW, Color.GREEN, Color.CYAN, Color.BLUE, Color.MAGENTA, Color.RED}));
		layout.addView(spectrum, new LinearLayout.LayoutParams(-1, dp(8)));
		hue = new SeekBar(context);
		hue.setMax(359);
		hue.setContentDescription(getString(R.string.popup_color_hue));
		hue.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
			@Override public void onStartTrackingTouch(SeekBar bar) {}
			@Override public void onStopTrackingTouch(SeekBar bar) {}
			@Override public void onProgressChanged(SeekBar bar, int progress, boolean fromUser) {
				if (fromUser) {
					hsv[0] = progress;
					updateColor(Color.HSVToColor(hsv), true);
				}
			}
		});
		layout.addView(hue, new LinearLayout.LayoutParams(-1, dp(48)));
		int[] presets = {0xffffffff, 0xff303030, 0xff000000, 0xff616161,
				0xff1976d2, 0xff4cae4f, 0xfff9a825, 0xff7b1fa2};
		for (int row = 0; row < 2; row++) {
			LinearLayout palette = new LinearLayout(context);
			for (int column = 0; column < 4; column++) {
				int preset = presets[row * 4 + column];
				Button swatch = new Button(context);
				swatch.setForceDarkAllowed(false);
				GradientDrawable background = new GradientDrawable();
				background.setColor(preset);
				background.setCornerRadius(dp(8));
				background.setStroke(dp(1), ThemeEngine.getTheme(context).meta);
				swatch.setBackground(background);
				swatch.setContentDescription(formatColor(preset));
				swatch.setOnClickListener(v -> {
					Color.colorToHSV(preset, hsv);
					updateColor(preset, true);
				});
				LinearLayout.LayoutParams cell = new LinearLayout.LayoutParams(0, dp(48), 1);
				cell.setMargins(dp(3), dp(3), dp(3), dp(3));
				palette.addView(swatch, cell);
			}
			layout.addView(palette);
		}
		Button code = new Button(context, null, android.R.attr.borderlessButtonStyle);
		code.setText(R.string.popup_color_code);
		layout.addView(code);
		hex = new SafePasteEditText(context);
		hex.setSingleLine(true);
		hex.setHint("#RRGGBB");
		hex.setContentDescription(getString(R.string.popup_color_code));
		hex.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS
				| InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
		hex.setFilters(new InputFilter[] {new InputFilter.AllCaps(), new InputFilter.LengthFilter(7)});
		hex.setVisibility(savedInstanceState != null && savedInstanceState.getBoolean("hexVisible")
				? View.VISIBLE : View.GONE);
		layout.addView(hex, new LinearLayout.LayoutParams(-1, -2));
		code.setOnClickListener(v -> {
			hex.setVisibility(hex.getVisibility() == View.VISIBLE ? View.GONE : View.VISIBLE);
		});
		hex.addTextChangedListener(new TextWatcher() {
			@Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
			@Override public void afterTextChanged(Editable s) {}
			@Override public void onTextChanged(CharSequence s, int start, int before, int count) {
				if (!syncing) {
					Integer parsed = parseColor(s.toString());
					if (parsed != null) {
						Color.colorToHSV(parsed, hsv);
						updateColor(parsed, false);
					}
				}
			}
		});
		updateColor(color, true);
		if (savedInstanceState != null) hex.setText(savedInstanceState.getString("hex", formatColor(color)));
		ScrollView scroll = new ScrollView(context);
		scroll.addView(layout);
		return new AlertDialog.Builder(context).setTitle(requireArguments().getInt("title"))
				.setView(scroll).setNegativeButton(android.R.string.cancel, null)
				.setPositiveButton(android.R.string.ok, null).create();
	}

	private void updateColor(int selected, boolean updateHex) {
		color = selected | 0xff000000;
		syncing = true;
		hue.setProgress(Math.round(hsv[0]));
		if (updateHex) {
			hex.setText(formatColor(color));
			hex.setError(null);
		}
		syncing = false;
		int background = isBackground() ? color : Preferences.getPopupBackground();
		int foreground = isBackground() ? Preferences.getPopupForeground() : color;
		GradientDrawable drawable = new GradientDrawable();
		drawable.setColor(background);
		drawable.setCornerRadius(dp(8));
		preview.setBackground(drawable);
		preview.setTextColor(foreground);
		warning.setVisibility(ColorUtils.calculateContrast(foreground, background) < 4.5 ? View.VISIBLE : View.GONE);
		field.invalidate();
	}

	@Override
	public void onStart() {
		super.onStart();
		AlertDialog dialog = (AlertDialog) getDialog();
		if (dialog != null) dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
			if (hex.getVisibility() == View.VISIBLE && parseColor(hex.getText().toString()) == null) {
				hex.setError(getString(R.string.invalid_hex_color));
				return;
			}
			if (getParentFragment() instanceof PopupAppearanceFragment) {
				((PopupAppearanceFragment) getParentFragment()).setCustomColor(
						requireArguments().getString("key"), color);
			}
			dismiss();
		});
	}

	@Override
	public void onSaveInstanceState(@NonNull Bundle outState) {
		super.onSaveInstanceState(outState);
		outState.putInt("color", color);
		outState.putFloatArray("hsv", hsv);
		outState.putBoolean("hexVisible", hex.getVisibility() == View.VISIBLE);
		outState.putString("hex", hex.getText().toString());
	}

	private static String formatColor(int value) { return String.format(Locale.US, "#%06X", value & 0xffffff); }

	private static Integer parseColor(String text) {
		String value = text.trim();
		if (value.startsWith("#")) value = value.substring(1);
		return value.matches("[0-9a-fA-F]{6}") ? 0xff000000 | Integer.parseInt(value, 16) : null;
	}

	private class ColorField extends View {
		private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);

		ColorField(Context context) {
			super(context);
			setForceDarkAllowed(false);
			setContentDescription(getString(R.string.popup_color_field));
		}

		@Override protected void onDraw(Canvas canvas) {
			float w = getWidth(), h = getHeight();
			if (w <= 0 || h <= 0) return;
			paint.setStyle(Paint.Style.FILL);
			paint.setShader(new LinearGradient(0, 0, w, 0, Color.WHITE,
					Color.HSVToColor(new float[] {hsv[0], 1, 1}), Shader.TileMode.CLAMP));
			canvas.drawRect(0, 0, w, h, paint);
			paint.setShader(new LinearGradient(0, 0, 0, h, Color.TRANSPARENT, Color.BLACK, Shader.TileMode.CLAMP));
			canvas.drawRect(0, 0, w, h, paint);
			paint.setShader(null);
			paint.setStyle(Paint.Style.STROKE);
			paint.setStrokeWidth(dp(2));
			paint.setColor(Color.BLACK);
			canvas.drawCircle(hsv[1] * w, (1 - hsv[2]) * h, dp(7), paint);
			paint.setColor(Color.WHITE);
			canvas.drawCircle(hsv[1] * w, (1 - hsv[2]) * h, dp(5), paint);
		}

		@Override public boolean onTouchEvent(MotionEvent event) {
			int action = event.getActionMasked();
			if (action == MotionEvent.ACTION_DOWN || action == MotionEvent.ACTION_MOVE || action == MotionEvent.ACTION_UP) {
				getParent().requestDisallowInterceptTouchEvent(action != MotionEvent.ACTION_UP);
				hsv[1] = Math.max(0, Math.min(1, event.getX() / Math.max(1, getWidth())));
				hsv[2] = 1 - Math.max(0, Math.min(1, event.getY() / Math.max(1, getHeight())));
				updateColor(Color.HSVToColor(hsv), true);
				if (action == MotionEvent.ACTION_UP) performClick();
				return true;
			}
			if (action == MotionEvent.ACTION_CANCEL) getParent().requestDisallowInterceptTouchEvent(false);
			return true;
		}

		@Override public boolean performClick() { super.performClick(); return true; }
	}
}
