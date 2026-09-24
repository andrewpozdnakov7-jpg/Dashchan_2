package com.mishiranu.dashchan.widget;

import android.content.Context;
import android.content.res.Configuration;
import android.content.res.TypedArray;
import android.text.Layout;
import android.text.StaticLayout;
import android.text.TextPaint;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.SeekBar;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toolbar;
import androidx.core.widget.TextViewCompat;
import chan.util.StringUtils;
import com.mishiranu.dashchan.R;
import com.mishiranu.dashchan.content.Preferences;
import com.mishiranu.dashchan.util.SharedPreferences;
import com.mishiranu.dashchan.util.FlagUtils;
import com.mishiranu.dashchan.util.ResourceUtils;
import com.mishiranu.dashchan.util.ViewUtils;

public class ViewFactory {
	public static final int FEATURE_WIDGET = 0x00000001;
	public static final int FEATURE_SINGLE_LINE = 0x00000002;
	public static final int FEATURE_TEXT2_END = 0x00000004;
	public static final int FEATURE_MULTILINE_TITLE = 0x00000008;

	public static TextView makeListTextHeader(ViewGroup parent) {
		TextView textView = new TextView(parent.getContext());
		float density = ResourceUtils.obtainDensity(parent);
		textView.setLayoutParams(new ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
				ViewGroup.LayoutParams.WRAP_CONTENT));
		textView.setMinHeight((int) (48f * density));
		textView.setGravity(Gravity.CENTER_VERTICAL);
		textView.setTextColor(ThemeEngine.getTheme(textView.getContext()).accent);
		textView.setTypeface(ResourceUtils.TYPEFACE_MEDIUM);
		ThemeEngine.applyStyle(textView);
		ViewUtils.setTextSizeScaled(textView, 14);
		textView.setPadding((int) (16f * density), (int) (16f * density), (int) (16f * density),
				(int) (8f * density));
		return textView;
	}

	public static View makeSingleLineListItem(ViewGroup parent) {
		float density = ResourceUtils.obtainDensity(parent);
		TextView textView = new TextView(parent.getContext());
		textView.setPadding((int) (16f * density), 0, (int) (16f * density), 0);
		TypedArray typedArray = textView.getContext().obtainStyledAttributes(new int[] {
				android.R.attr.textAppearanceListItem,
				android.R.attr.listPreferredItemHeightSmall});
		TextViewCompat.setTextAppearance(textView, typedArray.getResourceId(0, 0));
		ThemeEngine.applyStyle(textView);
		textView.setMinimumHeight(typedArray.getDimensionPixelSize(1, 0));
		typedArray.recycle();
		ViewUtils.setSelectableItemBackground(textView);
		textView.setGravity(Gravity.CENTER_VERTICAL);
		textView.setSingleLine(true);
		textView.setEllipsize(TextUtils.TruncateAt.END);
		textView.setLayoutParams(new ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
				ViewGroup.LayoutParams.WRAP_CONTENT));
		return textView;
	}

	public static class TwoLinesViewHolder {
		public final View view;
		public final TextView text1;
		public final TextView text2;
		public final TextView text2End;
		public final LinearLayout widgetFrame;

		public TwoLinesViewHolder(View view, TextView text1, TextView text2,
				TextView text2End, LinearLayout widgetFrame) {
			this.view = view;
			this.text1 = text1;
			this.text2 = text2;
			this.text2End = text2End;
			this.widgetFrame = widgetFrame;
		}
	}

	private static final int[] ATTRS_TWO_LINES = {
			android.R.attr.listPreferredItemHeightSmall,
			android.R.attr.textAppearanceListItem,
			android.R.attr.textAppearanceListItemSecondary,
			android.R.attr.textColorSecondary
	};

	public static TwoLinesViewHolder makeTwoLinesListItem(ViewGroup parent, int features) {
		float density = ResourceUtils.obtainDensity(parent);
		LinearLayout outerLayout = new LinearLayout(parent.getContext());
		outerLayout.setLayoutParams(new ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
				ViewGroup.LayoutParams.WRAP_CONTENT));
		int outerPaddingHorizontal = (int) (16f * density + 0.5f);
		int innerPaddingVertical = (int) (16f * density + 0.5f);
		boolean featureWidgetFrame = FlagUtils.get(features, FEATURE_WIDGET);
		LinearLayout innerLayout;
		if (featureWidgetFrame) {
			outerLayout.setOrientation(LinearLayout.HORIZONTAL);
			outerLayout.setBaselineAligned(false);
			innerLayout = new LinearLayout(outerLayout.getContext());
			innerLayout.setOrientation(LinearLayout.VERTICAL);
			outerLayout.addView(innerLayout, 0, LinearLayout.LayoutParams.WRAP_CONTENT);
			((LinearLayout.LayoutParams) innerLayout.getLayoutParams()).weight = 1;
			outerLayout.setPaddingRelative(outerPaddingHorizontal, 0, outerPaddingHorizontal, 0);
			innerLayout.setPaddingRelative(0, innerPaddingVertical, 0, innerPaddingVertical);
		} else {
			outerLayout.setOrientation(LinearLayout.VERTICAL);
			outerLayout.setPaddingRelative(outerPaddingHorizontal, innerPaddingVertical,
					outerPaddingHorizontal, innerPaddingVertical);
			innerLayout = outerLayout;
		}
		outerLayout.setGravity(Gravity.CENTER_VERTICAL);
		TypedArray typedArray = parent.getContext().obtainStyledAttributes(ATTRS_TWO_LINES);
		try {
			outerLayout.setMinimumHeight(typedArray.getDimensionPixelSize(0, 0));
			TextView text1 = new TextView(parent.getContext());
			TextViewCompat.setTextAppearance(text1, typedArray.getResourceId(1, 0));
			ThemeEngine.applyStyle(text1);
			if (FlagUtils.get(features, FEATURE_MULTILINE_TITLE)) {
				text1.setMaxLines(2);
			} else {
				text1.setSingleLine(true);
			}
			text1.setEllipsize(TextUtils.TruncateAt.END);
			TextView text2 = new TextView(parent.getContext());
			TextViewCompat.setTextAppearance(text2, typedArray.getResourceId(2, 0));
			ThemeEngine.applyStyle(text2);
			text2.setTextColor(typedArray.getColorStateList(3));
			boolean featureText2End = FlagUtils.get(features, FEATURE_TEXT2_END);
			if (FlagUtils.get(features, FEATURE_SINGLE_LINE) || featureText2End) {
				text2.setSingleLine(true);
				text2.setEllipsize(TextUtils.TruncateAt.END);
			} else {
				text2.setMaxLines(10);
			}
			TextView text2End;
			if (featureText2End) {
				text2End = new TextView(parent.getContext());
				TextViewCompat.setTextAppearance(text2End, typedArray.getResourceId(2, 0));
				ThemeEngine.applyStyle(text2End);
				text2End.setTextColor(typedArray.getColorStateList(3));
				text2.setSingleLine(true);
				text2.setEllipsize(TextUtils.TruncateAt.END);
			} else {
				text2End = null;
			}
			innerLayout.addView(text1, LinearLayout.LayoutParams.WRAP_CONTENT,
					LinearLayout.LayoutParams.WRAP_CONTENT);
			if (featureText2End) {
				LinearLayout text2Layout = new LinearLayout(parent.getContext());
				text2Layout.setOrientation(LinearLayout.HORIZONTAL);
				innerLayout.addView(text2Layout, LinearLayout.LayoutParams.MATCH_PARENT,
						LinearLayout.LayoutParams.WRAP_CONTENT);
				text2Layout.addView(text2, 0, LinearLayout.LayoutParams.WRAP_CONTENT);
				((LinearLayout.LayoutParams) text2.getLayoutParams()).weight = 1;
				text2Layout.addView(text2End, LinearLayout.LayoutParams.WRAP_CONTENT,
						LinearLayout.LayoutParams.WRAP_CONTENT);
				ViewUtils.setNewMarginRelative(text2End, (int) (8f * density + 0.5f), null, null, null);
			} else {
				innerLayout.addView(text2, LinearLayout.LayoutParams.WRAP_CONTENT,
						LinearLayout.LayoutParams.WRAP_CONTENT);
			}
			LinearLayout widgetFrame;
			if (featureWidgetFrame) {
				widgetFrame = new LinearLayout(parent.getContext());
				widgetFrame.setOrientation(LinearLayout.VERTICAL);
				widgetFrame.setGravity(Gravity.CENTER);
				widgetFrame.setVisibility(View.GONE);
				outerLayout.addView(widgetFrame, LinearLayout.LayoutParams.WRAP_CONTENT,
						LinearLayout.LayoutParams.MATCH_PARENT);
				ViewUtils.setNewMarginRelative(widgetFrame, outerPaddingHorizontal, null, null, null);
			} else {
				widgetFrame = null;
			}
			ViewUtils.setSelectableItemBackground(outerLayout);
			TwoLinesViewHolder holder = new TwoLinesViewHolder(outerLayout, text1, text2, text2End, widgetFrame);
			outerLayout.setTag(holder);
			return holder;
		} finally {
			typedArray.recycle();
		}
	}

	/** Chooses wrapping during measurement, before a frame is drawn. */
	// Native widgets are intentional: ThemeEngine supplies the platform theme, not AppCompat.
	@android.annotation.SuppressLint("AppCompatCustomView")
	private static class ToolbarTitleView extends TextView {
		private static final int MAX_TITLE_LINES = 3;
		private final TextPaint normalPaint = new TextPaint();
		private float defaultTextSize;
		private int defaultBreakStrategy;
		private int defaultHyphenationFrequency;
		private int availableHeight = Integer.MAX_VALUE;
		private final SharedPreferences.Listener sizeListener = key -> {
			if (Preferences.KEY_TOOLBAR_TITLE_CUSTOMIZATION.equals(key)
					|| Preferences.KEY_TOOLBAR_TITLE_SIZE.equals(key)
					|| Preferences.KEY_TOOLBAR_COMPACT_TITLE_SIZE.equals(key)
					|| Preferences.KEY_TOOLBAR_ADAPTIVE_TITLE.equals(key)
					|| Preferences.KEY_TOOLBAR_TITLE_HYPHENATION.equals(key)
					|| Preferences.KEY_TOOLBAR_MIN_TITLE_SIZE.equals(key)
					|| Preferences.KEY_TOOLBAR_TITLE_SIZE_STEP.equals(key)) {
				requestLayout();
			}
		};

		public ToolbarTitleView(Context context) {
			super(context);
		}

		public void initializeSize() {
			defaultTextSize = getTextSize();
			defaultBreakStrategy = getBreakStrategy();
			defaultHyphenationFrequency = getHyphenationFrequency();
			normalPaint.set(getPaint());
		}

		@Override
		protected void onAttachedToWindow() {
			super.onAttachedToWindow();
			Preferences.PREFERENCES.register(sizeListener);
			requestLayout();
		}

		@Override
		protected void onDetachedFromWindow() {
			Preferences.PREFERENCES.unregister(sizeListener);
			super.onDetachedFromWindow();
		}

		@Override
		// Values come exclusively from platform getters and Layout constants.
		@android.annotation.SuppressLint("WrongConstant")
		protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
			boolean hyphenate = Preferences.isToolbarTitleCustomizationEnabled()
					&& Preferences.isToolbarTitleHyphenationEnabled();
			int breakStrategy = hyphenate ? Layout.BREAK_STRATEGY_HIGH_QUALITY : defaultBreakStrategy;
			int hyphenation = hyphenate ? Layout.HYPHENATION_FREQUENCY_FULL : defaultHyphenationFrequency;
			// Apply before probing, so adaptive sizing and the displayed text use identical wrapping rules.
			if (getBreakStrategy() != breakStrategy) setBreakStrategy(breakStrategy);
			if (getHyphenationFrequency() != hyphenation) setHyphenationFrequency(hyphenation);
			if (!Preferences.isToolbarTitleCustomizationEnabled()) {
				setTextSize(TypedValue.COMPLEX_UNIT_PX, defaultTextSize);
				if (getMaxLines() != 1) {
					setSingleLine(true);
					setMaxLines(1);
				}
				super.onMeasure(widthMeasureSpec, heightMeasureSpec);
				return;
			}
			int width = MeasureSpec.getSize(widthMeasureSpec) - getCompoundPaddingLeft()
					- getCompoundPaddingRight();
			// Always compare with the ORIGINAL size, not the result of the previous measurement.
			// This also restores short titles after navigation, rotation or menu changes.
			float configuredNormal = Preferences.getToolbarTitleSize(false);
			float selectedSize = configuredNormal;
			normalPaint.setTextSize(toPixels(selectedSize));
			boolean overflow = MeasureSpec.getMode(widthMeasureSpec) != MeasureSpec.UNSPECIFIED
					&& width > 0 && (Layout.getDesiredWidth(getText(), normalPaint) > width
							|| TextUtils.indexOf(getText(), '\n') >= 0);
			int visibleLines = 1;
			if (overflow) {
				StaticLayout probe;
				if (Preferences.isToolbarTitleAdaptive()) {
					float minimum = Preferences.getToolbarMinTitleSize();
					float step = Preferences.getToolbarTitleSizeStep();
					// First try up to three lines WITHOUT shrinking. Stop at the largest size that fits.
					while (true) {
						probe = measureTitle(width, selectedSize);
						if (fitsTitle(probe) || selectedSize <= minimum) break;
						selectedSize = Math.max(minimum, selectedSize - step);
					}
				} else {
					selectedSize = Math.min(configuredNormal, Preferences.getToolbarTitleSize(true));
					probe = measureTitle(width, selectedSize);
				}
				// At the minimum, use an ellipsis, never shrink below the user's limit.
				visibleLines = Math.min(MAX_TITLE_LINES, probe.getLineCount());
				while (visibleLines > 1) {
					int height = probe.getLineBottom(visibleLines - 1) + getCompoundPaddingTop()
							+ getCompoundPaddingBottom();
					if (probe.getLineCount() > visibleLines) height += Math.max(0, probe.getBottomPadding());
					if (height <= availableHeight) break;
					visibleLines--;
				}
			}
			setTextSize(TypedValue.COMPLEX_UNIT_PX, toPixels(selectedSize));
			if (getMaxLines() != visibleLines) {
				setSingleLine(visibleLines == 1);
				setMaxLines(visibleLines);
			}
			super.onMeasure(widthMeasureSpec, heightMeasureSpec);
		}

		private float toPixels(float size) {
			return TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, size,
					getResources().getDisplayMetrics());
		}

		private StaticLayout measureTitle(int width, float size) {
			normalPaint.setTextSize(toPixels(size));
			CharSequence text = getText();
			return StaticLayout.Builder.obtain(text, 0, text.length(), normalPaint, width)
					.setMaxLines(MAX_TITLE_LINES + 1).setIncludePad(getIncludeFontPadding())
					.setLineSpacing(getLineSpacingExtra(), getLineSpacingMultiplier())
					.setBreakStrategy(getBreakStrategy()).setHyphenationFrequency(getHyphenationFrequency())
					.setTextDirection(getTextDirectionHeuristic()).build();
		}

		private boolean fitsTitle(StaticLayout probe) {
			return probe.getLineCount() <= MAX_TITLE_LINES && probe.getLineEnd(probe.getLineCount() - 1) == length()
					&& probe.getHeight() + getCompoundPaddingTop() + getCompoundPaddingBottom() <= availableHeight;
		}
	}

	private static class ToolbarTitleLayout extends LinearLayout {
		public ToolbarTitleLayout(Context context) {
			super(context);
			setOrientation(VERTICAL);
		}

		@Override
		protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
			ToolbarTitleView title = (ToolbarTitleView) getChildAt(0);
			View subtitle = getChildAt(1);
			int available = Integer.MAX_VALUE;
			if (MeasureSpec.getMode(heightMeasureSpec) != MeasureSpec.UNSPECIFIED) {
				available = MeasureSpec.getSize(heightMeasureSpec) - getPaddingTop() - getPaddingBottom();
				if (subtitle.getVisibility() != GONE) {
					measureChildWithMargins(subtitle, widthMeasureSpec, 0, heightMeasureSpec, 0);
					LayoutParams params = (LayoutParams) subtitle.getLayoutParams();
					available -= subtitle.getMeasuredHeight() + params.topMargin + params.bottomMargin;
				}
			}
			title.availableHeight = Math.max(0, available);
			super.onMeasure(widthMeasureSpec, heightMeasureSpec);
		}
	}

	public static class ToolbarHolder {
		public final ViewGroup toolbar;
		public final View layout;
		private final TextView title;
		private final TextView subtitle;
		private final int contentInsetStartWithNavigation;

		private ToolbarHolder(Toolbar toolbar, View layout, TextView title, TextView subtitle) {
			this.toolbar = toolbar;
			this.layout = layout;
			this.title = title;
			this.subtitle = subtitle;
			contentInsetStartWithNavigation = toolbar.getContentInsetStartWithNavigation();
		}

		public void update(CharSequence title, CharSequence subtitle) {
			this.title.setText(title);
			this.subtitle.setText(subtitle);
			this.subtitle.setVisibility(StringUtils.isEmpty(subtitle) ? View.GONE : View.VISIBLE);
		}

		public Toolbar getToolbar() {
			return (Toolbar) toolbar;
		}

		public void setCompactTitle(boolean compact) {
			getToolbar().setContentInsetStartWithNavigation(compact ? 0 : contentInsetStartWithNavigation);
			// All forums now share measured title fitting; Reddit only needs its inset override.
		}
	}

	public static ToolbarHolder addToolbarTitle(Toolbar toolbar) {
		LinearLayout layout = new ToolbarTitleLayout(toolbar.getContext());
		toolbar.addView(layout, Toolbar.LayoutParams.WRAP_CONTENT, Toolbar.LayoutParams.WRAP_CONTENT);
		ToolbarTitleView title = new ToolbarTitleView(layout.getContext());
		layout.addView(title, LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
		TextViewCompat.setTextAppearance(title, android.R.style.TextAppearance_Material_Widget_Toolbar_Title);
		ThemeEngine.applyStyle(title);
		title.initializeSize();
		title.setSingleLine(true);
		title.setEllipsize(TextUtils.TruncateAt.END);
		TextView subtitle = new TextView(layout.getContext());
		layout.addView(subtitle, LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
		TextViewCompat.setTextAppearance(subtitle, android.R.style.TextAppearance_Material_Widget_Toolbar_Subtitle);
		ThemeEngine.applyStyle(subtitle);
		subtitle.setSingleLine(true);
		subtitle.setEllipsize(TextUtils.TruncateAt.END);
		Configuration configuration = toolbar.getResources().getConfiguration();
		if (configuration.orientation == Configuration.ORIENTATION_PORTRAIT && !ResourceUtils.isTablet(configuration)) {
			float density = ResourceUtils.obtainDensity(toolbar);
			ViewUtils.setNewMargin(subtitle, null, (int) (-2f * density), null, null);
			subtitle.setTextSize(TypedValue.COMPLEX_UNIT_PX, (int) (subtitle.getTextSize() * 0.85f + 0.5f));
		}
		return new ToolbarHolder(toolbar, layout, title, subtitle);
	}

	public static View createProgressLayout(ViewGroup parent) {
		FrameLayout progress = new FrameLayout(parent.getContext());
		parent.addView(progress, ExpandedLayout.LayoutParams.MATCH_PARENT, ExpandedLayout.LayoutParams.MATCH_PARENT);
		progress.setVisibility(View.GONE);
		ProgressBar progressBar = new ProgressBar(progress.getContext());
		progress.addView(progressBar, FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT);
		((FrameLayout.LayoutParams) progressBar.getLayoutParams()).gravity = Gravity.CENTER;
		ThemeEngine.applyStyle(progressBar);
		return progress;
	}

	public static class ErrorHolder {
		public final View layout;
		public final TextView text;

		public ErrorHolder(View layout, TextView text) {
			this.layout = layout;
			this.text = text;
		}
	}

	public static ErrorHolder createErrorLayout(ViewGroup parent) {
		LinearLayout layout = new LinearLayout(parent.getContext());
		layout.setOrientation(LinearLayout.VERTICAL);
		layout.setGravity(Gravity.CENTER);
		ImageView image = new ImageView(layout.getContext());
		image.setImageDrawable(ResourceUtils.getDrawable(image.getContext(), R.attr.iconButtonWarning, 0));
		image.setImageTintList(ResourceUtils.getColorStateList(image.getContext(),
				android.R.attr.textColorSecondary));
		layout.addView(image, LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
		TextView text = new TextView(layout.getContext());
		TextViewCompat.setTextAppearance(text, ResourceUtils.getResourceId(text.getContext(),
				android.R.attr.textAppearanceMedium, 0));
		ThemeEngine.applyStyle(text);
		text.setGravity(Gravity.CENTER);
		float density = ResourceUtils.obtainDensity(parent);
		text.setPadding((int) (16f * density), 0, (int) (16f * density), 0);
		layout.addView(text, LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
		layout.setLayoutParams(new ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
				ViewGroup.LayoutParams.MATCH_PARENT));
		return new ErrorHolder(layout, text);
	}

	public static class SeekLayoutHolder {
		public final View layout;
		private final TextView valueText;
		private final Switch switchView;
		private final SeekBar seekBar;

		private final int minValue;
		private final int step;
		private final String valueFormat;

		public SeekLayoutHolder(View layout, TextView valueText, Switch switchView, SeekBar seekBar,
				int minValue, int step, String valueFormat) {
			this.layout = layout;
			this.valueText = valueText;
			this.switchView = switchView;
			this.seekBar = seekBar;
			this.minValue = minValue;
			this.step = step;
			this.valueFormat = valueFormat;
		}

		public void setEnabled(boolean enabled) {
			if (switchView.isChecked() != enabled) {
				switchView.setChecked(enabled);
			}
			seekBar.setEnabled(enabled);
		}

		public boolean isEnabled() {
			return seekBar.isEnabled();
		}

		public void setValue(int value) {
			int progress = (value - minValue) / step;
			if (seekBar.getProgress() != progress) {
				seekBar.setProgress(progress);
			}
			String text = valueFormat != null ? String.format(valueFormat, value) : Integer.toString(value);
			valueText.setText(text);
		}

		public int getValue() {
			return seekBar.getProgress() * step + minValue;
		}
	}

	public static SeekLayoutHolder createSeekLayout(Context context, boolean showSwitch,
			int minValue, int maxValue, int step, String valueFormat) {
		LayoutInflater inflater = LayoutInflater.from(context);
		View layout = inflater.inflate(R.layout.dialog_seek_bar, null);
		layout.<TextView>findViewById(R.id.min_value).setText(Integer.toString(minValue));
		layout.<TextView>findViewById(R.id.max_value).setText(Integer.toString(maxValue));
		TextView valueText = layout.findViewById(R.id.current_value);
		Switch switchView = layout.findViewById(R.id.switch_view);
		SeekBar seekBar = layout.findViewById(R.id.seek_bar);
		SeekLayoutHolder holder = new SeekLayoutHolder(layout,
				valueText, switchView, seekBar, minValue, step, valueFormat);
		layout.setTag(holder);
		seekBar.setSaveEnabled(false);
		seekBar.setMax((maxValue - minValue) / step);
		seekBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
			@Override
			public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
				holder.setValue(progress * step + minValue);
			}

			@Override
			public void onStartTrackingTouch(SeekBar seekBar) {}

			@Override
			public void onStopTrackingTouch(SeekBar seekBar) {}
		});
		switchView.setSaveEnabled(false);
		if (!showSwitch) {
			switchView.setVisibility(View.GONE);
		} else {
			switchView.setOnCheckedChangeListener((v, isChecked) -> holder.setEnabled(isChecked));
			ViewUtils.setNewMarginRelative(switchView, null, null, 0, null);
		}
		return holder;
	}
}
