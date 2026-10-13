package com.mishiranu.dashchan.widget;

import android.content.Context;
import android.view.View;
import android.view.ViewGroup;
import android.widget.CompoundButton;
import android.widget.FrameLayout;
import java.util.List;
import com.mishiranu.dashchan.util.ResourceUtils;

/** Presentation-only adaptive captcha/option layout. Retains the original option widgets and row. */
final class PostingOptionsPanel extends ViewGroup {
	private final View captcha;
	private final CarryLayout options;
	private final List<CompoundButton> checks;
	private boolean stacked;
	private int captchaWidth;

	PostingOptionsPanel(Context context, View captcha, CarryLayout options, List<CompoundButton> checks) {
		super(context);
		this.captcha = captcha; this.options = options; this.checks = checks;
		addView(captcha); addView(options);
	}

	private int dp(float value) { return Math.round(ResourceUtils.obtainDensity(this) * value); }

	/** Each original CarryLayout child fills its column, so its existing wrap algorithm yields a vertical list. */
	static final class OptionRow extends FrameLayout {
		OptionRow(Context context) { super(context); }
		@Override protected void onMeasure(int widthSpec, int heightSpec) {
			if (MeasureSpec.getMode(widthSpec) != MeasureSpec.UNSPECIFIED) {
				widthSpec = MeasureSpec.makeMeasureSpec(MeasureSpec.getSize(widthSpec), MeasureSpec.EXACTLY);
			}
			super.onMeasure(widthSpec, heightSpec);
		}
	}

	@Override protected void onMeasure(int widthSpec, int heightSpec) {
		int naturalOptions = 0;
		for (CompoundButton check : checks) if (check.getVisibility() != GONE) {
			check.measure(MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED),
					MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED));
			naturalOptions = Math.max(naturalOptions, check.getMeasuredWidth());
		}
		boolean capVisible = captcha.getVisibility() != GONE, optionsVisible = options.getVisibility() != GONE;
		int gap = capVisible && optionsVisible ? dp(24) : 0;
		int width = MeasureSpec.getMode(widthSpec) == MeasureSpec.UNSPECIFIED
				? dp(168) + gap + naturalOptions : MeasureSpec.getSize(widthSpec);
		int unconstrainedHeight = MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED);
		// The panel excludes the two 16dp outer margins: W - 232 becomes width - 200.
		// Reserve the V2 option column, but let measured labels trigger stacking instead of clipping.
		int preferredCaptcha = optionsVisible ? Math.min(dp(168), width - dp(200)) : dp(168);
		int requiredCaptcha = capVisible ? Math.max(dp(104), captcha.getMinimumWidth()) : 0;
		stacked = capVisible && optionsVisible
				&& (preferredCaptcha < requiredCaptcha || preferredCaptcha + gap + naturalOptions > width);
		captchaWidth = capVisible ? Math.min(width, Math.max(requiredCaptcha, stacked ? dp(168) : preferredCaptcha)) : 0;
		if (capVisible) captcha.measure(MeasureSpec.makeMeasureSpec(captchaWidth, MeasureSpec.EXACTLY), unconstrainedHeight);
		if (optionsVisible) options.measure(MeasureSpec.makeMeasureSpec(stacked ? width : width - captchaWidth - gap,
				MeasureSpec.EXACTLY), unconstrainedHeight);
		int capHeight = capVisible ? captcha.getMeasuredHeight() : 0;
		int optionHeight = optionsVisible ? options.getMeasuredHeight() : 0;
		int height = stacked ? capHeight + dp(12) + optionHeight : Math.max(capHeight, optionHeight);
		setMeasuredDimension(resolveSize(width, widthSpec), resolveSize(height, heightSpec));
	}

	@Override protected void onLayout(boolean changed, int left, int top, int right, int bottom) {
		boolean rtl = getLayoutDirection() == LAYOUT_DIRECTION_RTL;
		int width = right - left, height = bottom - top;
		if (captcha.getVisibility() != GONE) {
			int x = rtl ? width - captchaWidth : 0;
			int y = stacked ? 0 : (height - captcha.getMeasuredHeight()) / 2;
			captcha.layout(x, y, x + captchaWidth, y + captcha.getMeasuredHeight());
		}
		if (options.getVisibility() != GONE) {
			int x = stacked || captcha.getVisibility() == GONE || rtl ? 0 : captchaWidth + dp(24);
			int y = stacked ? captcha.getMeasuredHeight() + dp(12) : 0;
			options.layout(x, y, x + options.getMeasuredWidth(), y + options.getMeasuredHeight());
		}
	}

	@Override protected LayoutParams generateDefaultLayoutParams() {
		return new LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT);
	}
}
