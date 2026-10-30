package com.mishiranu.dashchan.widget;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.view.Gravity;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;
import java.text.NumberFormat;

/** Local progress presentation. No operation, timer, dialog or dismissal ownership. */
final class StandardProgressContent extends ProgressDialog.Content {
	final ProgressValues values = new ProgressValues();
	final ProgressBar waiting;
	final TextView message, percent, count;
	final LinearLayout horizontal, vertical;
	final Line line;
	private final String format;
	private final NumberFormat number = NumberFormat.getPercentInstance();
	StandardProgressContent(Context context, String format) {
		super(context); this.format = format; number.setMaximumFractionDigits(0);
		setPadding(dp(24), dp(20), dp(24), dp(20));
		ScrollView scroll = new ScrollView(context); scroll.setFillViewport(false);
		addView(scroll, new LayoutParams(-1, -2));
		vertical = new LinearLayout(context); vertical.setOrientation(LinearLayout.VERTICAL); vertical.setGravity(Gravity.CENTER_VERTICAL);
		scroll.addView(vertical, new ScrollView.LayoutParams(-1, -2));
		horizontal = new LinearLayout(context); horizontal.setGravity(Gravity.CENTER_VERTICAL);
		vertical.addView(horizontal, new LinearLayout.LayoutParams(-1, -2));
		waiting = new ProgressBar(context); waiting.setIndeterminate(true);
		waiting.setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
		horizontal.addView(waiting, new LinearLayout.LayoutParams(dp(48), dp(48)));
		message = new TextView(context); message.setMaxLines(Integer.MAX_VALUE);
		LinearLayout.LayoutParams msg = new LinearLayout.LayoutParams(0, -2, 1);
		msg.setMarginStart(dp(16)); horizontal.addView(message, msg);
		line = new Line(context); LinearLayout.LayoutParams track = new LinearLayout.LayoutParams(-1, dp(6));
		track.topMargin = dp(16); vertical.addView(line, track);
		ProgressLabels labels = new ProgressLabels(context);
		LinearLayout.LayoutParams captions = new LinearLayout.LayoutParams(-1, -2); captions.topMargin = dp(8);
		vertical.addView(labels, captions);
		percent = new TextView(context); count = new TextView(context);
		labels.addView(percent, new LinearLayout.LayoutParams(0, -2, 1));
		labels.addView(count, new LinearLayout.LayoutParams(-2, -2));
		count.setGravity(Gravity.END);
		InterfaceAppearance.standardLoading(waiting);
		refreshAppearance();
	}
	private int dp(int value) { return StandardAppearance.dp(this, value); }
	@Override public void message(CharSequence value) { message.setText(value); }
	@Override public void state(int value, int max, boolean indeterminate) {
		values.value = value; values.max = max; values.indeterminate = indeterminate;
		boolean known = values.hasTotal();
		waiting.setVisibility(known ? GONE : VISIBLE);
		message.getLayoutParams().width = known ? LayoutParams.MATCH_PARENT : 0;
		((LinearLayout.LayoutParams) message.getLayoutParams()).weight = known ? 0 : 1;
		((LinearLayout.LayoutParams) message.getLayoutParams()).setMarginStart(known ? 0 : dp(16));
		message.requestLayout();
		line.setVisibility(known ? VISIBLE : GONE);
		((View) percent.getParent()).setVisibility(known ? VISIBLE : GONE);
		percent.setText(known ? number.format(values.fraction()) : "");
		count.setText(known && format != null ? String.format(format, value, max) : "");
		count.setVisibility(format != null ? VISIBLE : GONE);
		line.fraction = (float) values.fraction(); line.invalidate();
	}
	@Override public void refreshAppearance() {
		AppearanceColors c = StandardAppearance.colors(getContext());
		int background = c.surfaceContainer;
		// The rounded DialogState window owns the backing; do not paint square child corners.
		InterfaceAppearance.standardProgressText(message, false);
		InterfaceAppearance.standardProgressText(percent, true); InterfaceAppearance.standardProgressText(count, true);
		message.setTextColor(InterfaceAppearance.contrasting(c.onSurface, background, 4.5));
		int secondary = InterfaceAppearance.contrasting(c.onSurfaceVariant, background, 4.5);
		percent.setTextColor(secondary); count.setTextColor(secondary);
		percent.setAlpha(1f); count.setAlpha(1f);
		vertical.setMinimumHeight(Math.max(dp(48), message.getLineHeight() + dp(30)
				+ Math.max(percent.getLineHeight(), count.getLineHeight())));
		line.track = c.surface;
		line.fill = InterfaceAppearance.contrasting(c.primary, line.track, 3);
		line.invalidate();
	}
	@Override protected void onAttachedToWindow() {
		super.onAttachedToWindow(); refreshAppearance();
	}
	/** No tween: actual progress and captions update immediately, including reset/finish. */
	static final class Line extends View {
		float fraction; int track, fill; final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
		Line(Context context) { super(context); setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO); }
		@Override protected void onDraw(Canvas canvas) {
			super.onDraw(canvas); float radius = getHeight() / 2f;
			paint.setColor(track); canvas.drawRoundRect(0, 0, getWidth(), getHeight(), radius, radius, paint);
			float width = getWidth() * fraction;
			if (width > 0) {
				paint.setColor(fill); float start = getLayoutDirection() == LAYOUT_DIRECTION_RTL ? getWidth() - width : 0;
				float endRadius = Math.min(radius, width / 2f);
				canvas.drawRoundRect(new RectF(start, 0, start + width, getHeight()), endRadius, endRadius, paint);
			}
		}
	}
	static final class ProgressLabels extends LinearLayout {
		ProgressLabels(Context context) { super(context); }
		@Override protected void onMeasure(int widthSpec, int heightSpec) {
			TextView a = (TextView) getChildAt(0), b = (TextView) getChildAt(1);
			int available = MeasureSpec.getSize(widthSpec);
			boolean stacked = MeasureSpec.getMode(widthSpec) != MeasureSpec.UNSPECIFIED && b.getVisibility() != GONE
					&& android.text.Layout.getDesiredWidth(a.getText(), a.getPaint()) + android.text.Layout.getDesiredWidth(b.getText(), b.getPaint())
					+ StandardAppearance.dp(this, 12) > available;
			setOrientation(stacked ? VERTICAL : HORIZONTAL);
			LinearLayout.LayoutParams p = (LinearLayout.LayoutParams) a.getLayoutParams();
			p.width = stacked ? -1 : 0; p.weight = stacked ? 0 : 1;
			LinearLayout.LayoutParams q = (LinearLayout.LayoutParams) b.getLayoutParams();
			q.width = stacked ? -1 : -2; q.topMargin = stacked ? StandardAppearance.dp(this, 4) : 0;
			q.setMarginStart(stacked ? 0 : StandardAppearance.dp(this, 12));
			super.onMeasure(widthSpec, heightSpec);
		}
	}
}
