package com.mishiranu.dashchan.widget;

import android.app.AlertDialog;
import android.content.Context;
import android.view.Gravity;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;
import com.mishiranu.dashchan.util.ResourceUtils;
import java.text.NumberFormat;

public class ProgressDialog extends AlertDialog {
	/** Presentation only. Implementations must not own task or dialog listeners. */
	public abstract static class Content extends android.widget.FrameLayout {
		public Content(Context context) { super(context); }
		public abstract void message(CharSequence value);
		public abstract void state(int value, int max, boolean indeterminate);
		public abstract void refreshAppearance();
	}
	final Content standardContent;
	private final ProgressValues values = new ProgressValues();
	public static ProgressDialog standard(Context context, String progressFormat) {
		return new ProgressDialog(context, progressFormat, InterfaceAppearance.isEnabled(context));
	}
	private final String progressFormat;
	private final NumberFormat numberFormat;
	private final ProgressBar progressBar;
	private final TextView message;
	private final TextView percent;
	private final TextView progress;

	public ProgressDialog(Context context, String progressFormat) {
		this(context, progressFormat, false);
	}
	private ProgressDialog(Context context, String progressFormat, boolean standard) {
		super(standard ? InterfaceAppearance.standardDialogContext(context) : context);
		SurfaceMotion.configureDialog(this);
		context = getContext();
		setCanceledOnTouchOutside(false);

		this.progressFormat = progressFormat;
		standardContent = standard ? InterfaceAppearance.standardProgressContent(context, progressFormat) : null;
		if (standardContent != null) {
			progressBar = null; message = null; percent = null; progress = null; numberFormat = null;
			values.indeterminate = progressFormat == null;
			// Public AlertDialog API, not the inaccessible Builder overload. One padding owner.
			setView(standardContent, 0, 0, 0, 0);
			InterfaceAppearance.configureStandardDialog(this);
			standardContent.state(values.value, values.max, values.indeterminate);
			return;
		}
		if (progressFormat != null) {
			numberFormat = NumberFormat.getPercentInstance();
			numberFormat.setMaximumFractionDigits(0);
			// Ensure message TextView is created
			setMessage("");
		} else {
			numberFormat = null;
		}

		LinearLayout layout = new LinearLayout(context);
		float density = ResourceUtils.obtainDensity(context);
		int horizontalPadding = (int) ((numberFormat != null ? 24f : 20f) * density);
		int verticalPadding = (int) (18f * density);
		layout.setPadding(horizontalPadding, verticalPadding, horizontalPadding, verticalPadding);
		setView(layout);

		if (progressFormat == null) {
			layout.setOrientation(LinearLayout.HORIZONTAL);
			layout.setGravity(Gravity.CENTER_VERTICAL);
			progressBar = new ProgressBar(context, null, android.R.attr.progressBarStyle);
			ThemeEngine.applyStyle(progressBar);
			layout.addView(progressBar, LinearLayout.LayoutParams.WRAP_CONTENT,
					LinearLayout.LayoutParams.WRAP_CONTENT);
			message = new TextView(context);
			ThemeEngine.applyStyle(message);
			layout.addView(message, LinearLayout.LayoutParams.MATCH_PARENT,
					LinearLayout.LayoutParams.WRAP_CONTENT);
			if (message.getLayoutDirection() == TextView.LAYOUT_DIRECTION_RTL) {
				message.setPadding(0, 0, horizontalPadding, 0);
			} else {
				message.setPadding(horizontalPadding, 0, 0, 0);
			}
			percent = null;
			progress = null;
		} else {
			layout.setOrientation(LinearLayout.VERTICAL);
			progressBar = new ProgressBar(context, null, android.R.attr.progressBarStyleHorizontal);
			ThemeEngine.applyStyle(progressBar);
			layout.addView(progressBar, LinearLayout.LayoutParams.MATCH_PARENT,
					LinearLayout.LayoutParams.WRAP_CONTENT);
			message = null;
			LinearLayout inner = new LinearLayout(context);
			layout.addView(inner, LinearLayout.LayoutParams.MATCH_PARENT,
					LinearLayout.LayoutParams.WRAP_CONTENT);
			inner.setOrientation(LinearLayout.HORIZONTAL);
			percent = new TextView(context);
			ThemeEngine.applyStyle(percent);
			inner.addView(percent, LinearLayout.LayoutParams.WRAP_CONTENT,
					LinearLayout.LayoutParams.WRAP_CONTENT);
			progress = new TextView(context);
			ThemeEngine.applyStyle(progress);
			inner.addView(progress, 0, LinearLayout.LayoutParams.WRAP_CONTENT);
			((LinearLayout.LayoutParams) progress.getLayoutParams()).weight = 1f;
			progress.setGravity(Gravity.END);
		}
		updateProgress();
	}

	@Override
	public void setMessage(CharSequence message) {
		if (standardContent != null) { standardContent.message(message); return; }
		if (progressFormat == null) {
			if (this.message != null) this.message.setText(message);
		} else {
			super.setMessage(message);
		}
	}

	public void setIndeterminate(boolean indeterminate) {
		if (standardContent != null) { values.indeterminate = indeterminate; updateStandard(); return; }
		progressBar.setIndeterminate(indeterminate);
	}

	public void setValue(int value) {
		if (standardContent != null) { values.value = value; updateStandard(); return; }
		progressBar.setProgress(value);
		updateProgress();
	}

	public void setMax(int max) {
		if (standardContent != null) { values.max = max; updateStandard(); return; }
		progressBar.setMax(max);
		updateProgress();
	}

	private void updateProgress() {
		if (progressFormat != null) {
			int value = progressBar.getProgress();
			int max = progressBar.getMax();
			percent.setText(numberFormat.format((double) value / (double) max));
			progress.setText(String.format(progressFormat, value, max));
		}
	}
	private void updateStandard() { standardContent.state(values.value, values.max, values.indeterminate); }
	@Override protected void onStart() {
		super.onStart();
		if (standardContent != null) standardContent.refreshAppearance();
	}
}
