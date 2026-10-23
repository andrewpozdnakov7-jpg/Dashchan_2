package com.mishiranu.dashchan.widget;

import android.app.AlertDialog;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.drawable.Drawable;
import android.os.SystemClock;
import android.text.Layout;
import android.text.Spannable;
import android.text.SpannableStringBuilder;
import android.text.TextPaint;
import android.text.style.ForegroundColorSpan;
import android.text.style.MetricAffectingSpan;
import android.text.style.RelativeSizeSpan;
import android.text.style.ReplacementSpan;
import android.view.ContextThemeWrapper;
import android.view.View;
import android.view.ViewConfiguration;
import android.widget.FrameLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import androidx.annotation.NonNull;
import com.mishiranu.dashchan.R;
import com.mishiranu.dashchan.util.GraphicsUtils;
import com.mishiranu.dashchan.util.ResourceUtils;
import com.mishiranu.dashchan.util.ViewUtils;
import java.lang.ref.WeakReference;

public class SummaryLayout implements CommentTextView.PrepareToCopyListener, View.OnClickListener {
	private static final int TITLE_PADDING_DP = 4;
	private static final int BLOCK_PADDING_DP = 16;
	private static final int TITLE_SIZE_SP = 12;
	private static final int TEXT_SIZE_SP = 16;

	private final CommentTextView textView;
	private final SpannableStringBuilder builder = new SpannableStringBuilder();
	private final Drawable dividerDrawable;
	private final int titleSizeSp;
	private final int textSizeSp;
	private final int titleColor;
	private final boolean standard;

	private final float titlePaddingTextSize;
	private final float blockPaddingTextSize;
	private final float dividerPaddingTextSize;
	private final int dividerExtraTop;
	private final int dividerExtraBottom;

	public SummaryLayout(AlertDialog dialog) {
		this(dialog, false);
	}

	/** Explicit profile; legacy callers keep their original metrics and selection implementation. */
	public static SummaryLayout standard(AlertDialog dialog) { return new SummaryLayout(dialog, true); }

	private SummaryLayout(AlertDialog dialog, boolean requestStandard) {
		Context context = dialog.getContext();
		int[] colors = requestStandard ? InterfaceAppearance.standardSummaryColors(context) : null;
		standard = colors != null;
		titleSizeSp = standard ? 12 : TITLE_SIZE_SP;
		textSizeSp = standard ? 14 : TEXT_SIZE_SP;
		titleColor = standard ? colors[1] : ResourceUtils.getColor(context, android.R.attr.textColorSecondary);
		ScrollView scrollView = new ScrollView(context) {
			@Override
			public boolean requestChildRectangleOnScreen(View child, Rect rectangle, boolean immediate) {
				// Don't scroll on select
				return true;
			}
		};
		ThemeEngine.applyStyle(scrollView);
		dialog.setView(scrollView);
		FrameLayout frameLayout = new FrameLayout(context);
		scrollView.addView(frameLayout, ScrollView.LayoutParams.MATCH_PARENT, ScrollView.LayoutParams.WRAP_CONTENT);
		frameLayout.setOnClickListener(this);
		textView = new CommentTextView(context, null, android.R.attr.textViewStyle);
		frameLayout.addView(textView, FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT);
		textView.setTextColor(ResourceUtils.getColorStateList(context, android.R.attr.textColorPrimary));
		if (standard) {
			textView.setTextColor(colors[0]);
			textView.setTypeface(com.mishiranu.dashchan.content.FontManager.resolveTypeface(context, false));
		}
		textView.setPrepareToCopyListener(this);

		// Dialogs have disabled dividers.
		boolean light = GraphicsUtils.isLight(ResourceUtils.getDialogBackground(context));
		Context dividerContext = new ContextThemeWrapper(context,
				light ? R.style.Theme_Main_Light : R.style.Theme_Main_Dark);
		if (standard) {
			android.graphics.drawable.GradientDrawable divider = new android.graphics.drawable.GradientDrawable();
			divider.setColor(colors[2]); divider.setSize(1, Math.max(1, Math.round(ResourceUtils.obtainDensity(context))));
			dividerDrawable = divider;
		} else dividerDrawable = ResourceUtils.getDrawable(dividerContext, android.R.attr.dividerHorizontal, 0);

		int unspecifiedSpec = View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED);
		Paint.FontMetrics fontMetrics = new Paint.FontMetrics();
		textView.setText(measureText(false));
		ViewUtils.setTextSizeScaled(textView, textSizeSp);
		textView.measure(unspecifiedSpec, unspecifiedSpec);
		textView.getPaint().getFontMetrics(fontMetrics);
		dividerExtraTop = textView.getLayout().getLineBottom(0) - textView.getLayout().getLineBaseline(0);
		ViewUtils.setTextSizeScaled(textView, titleSizeSp);
		textView.setText(measureText(true));
		textView.measure(unspecifiedSpec, unspecifiedSpec);
		textView.getPaint().getFontMetrics(fontMetrics);
		dividerExtraBottom = textView.getLayout().getLineBaseline(0) - textView.getLayout().getLineTop(0);

		ViewUtils.setTextSizeScaled(textView, textSizeSp);
		float density = ResourceUtils.obtainDensity(context);
		int blockPadding = (int) (BLOCK_PADDING_DP * density + 0.5f);
		int titlePadding = (int) ((standard ? 8 : TITLE_PADDING_DP) * density + 0.5f);
		titlePaddingTextSize = calculateFontSize(textView, titlePadding, true, false);
		blockPaddingTextSize = calculateFontSize(textView, blockPadding, false, true);
		dividerPaddingTextSize = calculateFontSize(textView, 2 * blockPadding +
				(dividerDrawable != null ? dividerDrawable.getIntrinsicHeight() : 0), false, true);

		textView.setText(null);
		textView.setPadding((int) (24f * density), (int) (20f * density),
				(int) (24f * density), (int) (8f * density));
	}
	private CharSequence measureText(boolean title) {
		SpannableStringBuilder text = new SpannableStringBuilder("Measure");
		lineHeight(text, 0, text.length(), title); return text;
	}
	private void lineHeight(SpannableStringBuilder text, int start, int end, boolean title) {
		if (standard && end > start) text.setSpan(new TextLineHeight(Math.round(android.util.TypedValue.applyDimension(
				android.util.TypedValue.COMPLEX_UNIT_SP, title ? 16 : 20, textView.getResources().getDisplayMetrics()))),
				start, end, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
	}

	private float calculateFontSize(TextView textView, int padding, boolean titleFirst, boolean titleSecond) {
		int unspecifiedSpec = View.MeasureSpec.makeMeasureSpec(Integer.MAX_VALUE / 2, View.MeasureSpec.AT_MOST);
		SpannableStringBuilder builder = new SpannableStringBuilder();
		addTestLines(builder, "Line", titleFirst, titleSecond);
		textView.setText(builder);
		textView.measure(unspecifiedSpec, unspecifiedSpec);
		int singleHeight = textView.getMeasuredHeight();
		builder.replace(builder.length() - 1, builder.length(), "\n");
		builder.append('\n');
		PaddingSpan paddingSpan = new PaddingSpan(padding);
		builder.setSpan(paddingSpan, builder.length() - 1, builder.length(),
				SpannableStringBuilder.SPAN_EXCLUSIVE_EXCLUSIVE);
		addTestLines(builder, "Line", titleFirst, titleSecond);
		int target = 2 * singleHeight + padding;
		float lastTextSize = 0f;
		while (true) {
			textView.setText(builder);
			textView.measure(unspecifiedSpec, unspecifiedSpec);
			int height = textView.getMeasuredHeight();
			float newTextSize = paddingSpan.textSize + (target - height) / 2f;
			if (newTextSize <= 0 || newTextSize == lastTextSize) {
				break;
			}
			lastTextSize = paddingSpan.textSize;
			paddingSpan.textSize = newTextSize;
		}
		return paddingSpan.textSize;
	}

	private void addTestLines(SpannableStringBuilder builder, String lineText,
			boolean titleFirst, boolean titleSecond) {
		builder.append(lineText).append('\n').append(lineText).append(' ');
		lineHeight(builder, builder.length() - 2 * lineText.length() - 2, builder.length() - lineText.length() - 1, titleSecond);
		lineHeight(builder, builder.length() - lineText.length() - 1, builder.length(), titleFirst);
		float titleSize = (float) titleSizeSp / textSizeSp;
		if (titleFirst) {
			builder.setSpan(new RelativeSizeSpan(titleSize),
					builder.length() - lineText.length() - 1, builder.length(),
					SpannableStringBuilder.SPAN_EXCLUSIVE_EXCLUSIVE);
		}
		if (titleSecond) {
			builder.setSpan(new RelativeSizeSpan(titleSize),
					builder.length() - 2 * lineText.length() - 2, builder.length() - lineText.length() - 1,
					SpannableStringBuilder.SPAN_EXCLUSIVE_EXCLUSIVE);
		}
	}

	private boolean addDivider;

	public void add(CharSequence title, CharSequence text) {
		if (builder.length() > 0) {
			builder.append('\n');
			if (addDivider && dividerDrawable != null) {
				builder.append("  \n");
				builder.setSpan(new PaddingSpan(dividerPaddingTextSize),
						builder.length() - 2, builder.length(), SpannableStringBuilder.SPAN_EXCLUSIVE_EXCLUSIVE);
				builder.setSpan(new DividerSpan(textView, dividerDrawable, dividerExtraTop, dividerExtraBottom),
						builder.length() - 3, builder.length() - 2, SpannableStringBuilder.SPAN_EXCLUSIVE_EXCLUSIVE);
			} else {
				builder.append('\n');
				builder.setSpan(new PaddingSpan(blockPaddingTextSize),
						builder.length() - 1, builder.length(), SpannableStringBuilder.SPAN_EXCLUSIVE_EXCLUSIVE);
				builder.setSpan(new PaddingSpan(blockPaddingTextSize),
						builder.length() - 1, builder.length(), SpannableStringBuilder.SPAN_EXCLUSIVE_EXCLUSIVE);
			}
		}
		addDivider = false;
		builder.append(title).append('\n');
		lineHeight(builder, builder.length() - title.length() - 1, builder.length(), true);
		builder.setSpan(new RelativeSizeSpan((float) titleSizeSp / textSizeSp),
				builder.length() - title.length() - 1, builder.length(),
				SpannableStringBuilder.SPAN_EXCLUSIVE_EXCLUSIVE);
		builder.setSpan(new ForegroundColorSpan(titleColor), builder.length() - title.length() - 1, builder.length(),
				SpannableStringBuilder.SPAN_EXCLUSIVE_EXCLUSIVE);
		builder.append('\n');
		builder.setSpan(new PaddingSpan(titlePaddingTextSize),
				builder.length() - 1, builder.length(), SpannableStringBuilder.SPAN_EXCLUSIVE_EXCLUSIVE);
		int textStart = builder.length();
		builder.append(text);
		lineHeight(builder, textStart, builder.length(), false);
		textView.setText(builder);
	}

	public void addDivider() {
		addDivider = true;
	}

	@Override
	public String onPrepareToCopy(CommentTextView view, Spannable text, int start, int end) {
		return text.toString().substring(start, end).trim().replace("\n\n", "\n").replace("\n  \n", "\n\n");
	}

	private long lastClickTime;

	@Override
	public void onClick(View v) {
		long time = SystemClock.elapsedRealtime();
		if (time - lastClickTime < ViewConfiguration.getDoubleTapTimeout()) {
			lastClickTime = 0L;
			textView.startSelection();
		} else {
			lastClickTime = time;
		}
	}

	private static class PaddingSpan extends MetricAffectingSpan {
		private float textSize;

		public PaddingSpan(float textSize) {
			this.textSize = textSize;
		}

		@Override
		public void updateMeasureState(@NonNull TextPaint textPaint) {
			textPaint.setTextSize(textSize);
		}

		@Override
		public void updateDrawState(TextPaint textPaint) {
			updateMeasureState(textPaint);
		}
	}
	/** Only real title/body lines; spacer and divider lines retain their independently measured metrics. */
	private static final class TextLineHeight implements android.text.style.LineHeightSpan {
		private final int height;
		TextLineHeight(int height) { this.height = height; }
		@Override public void chooseHeight(CharSequence text, int start, int end, int spanStartV, int v, Paint.FontMetricsInt fm) {
			int extra = Math.max(0, height - (fm.descent - fm.ascent));
			fm.descent += extra; fm.bottom = Math.max(fm.bottom, fm.descent);
		}
	}

	private static class DividerSpan extends ReplacementSpan {
		private final WeakReference<TextView> parent;
		private final WeakReference<Drawable> dividerDrawable;
		private final int extraTop;
		private final int extraBottom;

		public DividerSpan(TextView parent, Drawable dividerDrawable, int extraTop, int extraBottom) {
			this.parent = new WeakReference<>(parent);
			this.dividerDrawable = new WeakReference<>(dividerDrawable);
			this.extraTop = extraTop;
			this.extraBottom = extraBottom;
		}

		@Override
		public int getSize(@NonNull Paint paint, CharSequence text, int start, int end, Paint.FontMetricsInt fm) {
			return 1;
		}

		@Override
		public void draw(@NonNull Canvas canvas, CharSequence text, int start, int end,
				float x, int top, int y, int bottom, @NonNull Paint paint) {
			TextView parent = this.parent.get();
			Drawable dividerDrawable = this.dividerDrawable.get();
			if (parent != null && dividerDrawable != null) {
				Layout layout = parent.getLayout();
				int line = layout.getLineForVertical(y);
				if (line >= 1) {
					int height = dividerDrawable.getIntrinsicHeight();
					int topBaseline = layout.getLineBaseline(line - 1) + extraTop;
					int bottomBaseline = layout.getLineBaseline(line + 1) - extraBottom;
					int drawTop = (int) ((topBaseline + bottomBaseline - height) / 2f + 0.5f);
					dividerDrawable.setBounds(0, drawTop, parent.getWidth(), drawTop + height);
					dividerDrawable.draw(canvas);
				}
			}
		}
	}
}
