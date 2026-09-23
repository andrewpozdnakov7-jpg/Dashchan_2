package com.mishiranu.dashchan.widget;

import android.app.Dialog;
import android.content.Context;
import android.content.DialogInterface;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import com.mishiranu.dashchan.util.ResourceUtils;
import com.mishiranu.dashchan.util.ViewUtils;

/** Text-only dialogs: the message scrolls, the title and actions never do.
 * Forms, captcha and choice lists intentionally keep their own dialog implementations. */
public final class MessageDialog extends Dialog {
	public static final class Builder {
		private final Context context;
		private CharSequence title;
		private CharSequence message;
		private CharSequence positive;
		private CharSequence negative;
		private DialogInterface.OnClickListener positiveListener;
		private DialogInterface.OnClickListener negativeListener;

		public Builder(Context context) { this.context = context; }
		public Builder setTitle(int resId) { return setTitle(context.getText(resId)); }
		public Builder setTitle(CharSequence title) { this.title = title; return this; }
		public Builder setMessage(int resId) { return setMessage(context.getText(resId)); }
		public Builder setMessage(CharSequence message) { this.message = message; return this; }
		public Builder setPositiveButton(int resId, DialogInterface.OnClickListener listener) {
			positive = context.getText(resId);
			positiveListener = listener;
			return this;
		}
		public Builder setNegativeButton(int resId, DialogInterface.OnClickListener listener) {
			negative = context.getText(resId);
			negativeListener = listener;
			return this;
		}
		public MessageDialog create() { return new MessageDialog(this); }
		public MessageDialog show() {
			MessageDialog dialog = create();
			dialog.show();
			return dialog;
		}
	}

	private MessageDialog(Builder builder) {
		super(builder.context, ResourceUtils.getResourceId(builder.context, android.R.attr.alertDialogTheme, 0));
		requestWindowFeature(Window.FEATURE_NO_TITLE);
		setCanceledOnTouchOutside(true);
		Context context = getContext();
		Content content = new Content(context);
		content.title.setText(builder.title);
		content.title.setVisibility(builder.title == null || builder.title.length() == 0 ? View.GONE : View.VISIBLE);
		content.message.setText(builder.message);
		if (builder.title != null) getWindow().setTitle(builder.title);
		addButton(content.actions, builder.negative, BUTTON_NEGATIVE, builder.negativeListener);
		addButton(content.actions, builder.positive, BUTTON_POSITIVE, builder.positiveListener);
		setContentView(content, new ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
				ViewGroup.LayoutParams.WRAP_CONTENT));
	}

	private void addButton(ActionRow actions, CharSequence text, int which,
			DialogInterface.OnClickListener listener) {
		if (text == null) return;
		Button button = new Button(getContext(), null, android.R.attr.buttonBarButtonStyle);
		ThemeEngine.applyStyle(button);
		button.setText(text);
		button.setAllCaps(false);
		button.setSingleLine(false);
		button.setEllipsize(null);
		button.setMinHeight(dp(getContext(), 48));
		button.setOnClickListener(view -> {
			if (listener != null) listener.onClick(this, which);
			dismiss();
		});
		actions.addView(button, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,
				ViewGroup.LayoutParams.WRAP_CONTENT));
	}

	private static int dp(Context context, int value) {
		return Math.round(value * ResourceUtils.obtainDensity(context));
	}

	private static final class ActionRow extends LinearLayout {
		ActionRow(Context context) {
			super(context);
			setGravity(Gravity.END | Gravity.CENTER_VERTICAL);
			setPadding(dp(context, 8), dp(context, 4), dp(context, 8), dp(context, 4));
		}

		@Override
		protected void onMeasure(int widthSpec, int heightSpec) {
			int naturalWidth = getPaddingLeft() + getPaddingRight();
			for (int i = 0; i < getChildCount(); i++) {
				View child = getChildAt(i);
				child.measure(MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED),
						MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED));
				naturalWidth += child.getMeasuredWidth();
			}
			// Large accessibility fonts / long translations may need two fixed rows, never clipping.
			boolean stacked = MeasureSpec.getMode(widthSpec) != MeasureSpec.UNSPECIFIED
					&& naturalWidth > MeasureSpec.getSize(widthSpec);
			setOrientation(stacked ? VERTICAL : HORIZONTAL);
			for (int i = 0; i < getChildCount(); i++) {
				getChildAt(i).getLayoutParams().width = stacked ? LayoutParams.MATCH_PARENT : LayoutParams.WRAP_CONTENT;
			}
			super.onMeasure(widthSpec, heightSpec);
		}
	}

	private static final class Content extends ViewGroup {
		final TextView title;
		final TextView message;
		final ScrollView scroll;
		final ActionRow actions;

		Content(Context context) {
			super(context);
			title = new TextView(context);
			ThemeEngine.applyStyle(title);
			title.setTextColor(ResourceUtils.getColorStateList(context, android.R.attr.textColorPrimary));
			title.setTextSize(20);
			title.setTypeface(ResourceUtils.TYPEFACE_MEDIUM);
			title.setAccessibilityHeading(true);
			title.setPadding(dp(context, 24), dp(context, 20), dp(context, 24), dp(context, 12));
			addView(title);
			message = new TextView(context);
			ThemeEngine.applyStyle(message);
			message.setTextColor(ResourceUtils.getColorStateList(context, android.R.attr.textColorPrimary));
			message.setTextSize(16);
			message.setPadding(dp(context, 24), dp(context, 8), dp(context, 24), dp(context, 16));
			scroll = new ScrollView(context);
			ThemeEngine.applyStyle(scroll);
			scroll.setOverScrollMode(OVER_SCROLL_IF_CONTENT_SCROLLS);
			scroll.addView(message, new ScrollView.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT));
			addView(scroll);
			actions = new ActionRow(context);
			addView(actions);
		}

		@Override
		protected void onMeasure(int widthSpec, int heightSpec) {
			int availableHeight = Math.max(1, (int) (ViewUtils.getWindowContentSize(getContext()).y * 0.85f));
			if (MeasureSpec.getMode(heightSpec) != MeasureSpec.UNSPECIFIED) {
				availableHeight = Math.min(availableHeight, MeasureSpec.getSize(heightSpec));
			}
			int width = resolveSize(dp(getContext(), 420), widthSpec);
			int childWidth = MeasureSpec.makeMeasureSpec(width, MeasureSpec.EXACTLY);
			// Reserve action space first. Only the message is allowed to consume the remaining height.
			actions.measure(childWidth, MeasureSpec.makeMeasureSpec(availableHeight, MeasureSpec.AT_MOST));
			int remaining = Math.max(0, availableHeight - actions.getMeasuredHeight());
			int titleHeight = 0;
			if (title.getVisibility() != GONE) {
				title.measure(childWidth, MeasureSpec.makeMeasureSpec(remaining / 2, MeasureSpec.AT_MOST));
				titleHeight = title.getMeasuredHeight();
			}
			scroll.measure(childWidth, MeasureSpec.makeMeasureSpec(Math.max(0, remaining - titleHeight), MeasureSpec.AT_MOST));
			setMeasuredDimension(width, resolveSize(titleHeight + scroll.getMeasuredHeight()
					+ actions.getMeasuredHeight(), heightSpec));
		}

		@Override
		protected void onLayout(boolean changed, int left, int top, int right, int bottom) {
			int titleHeight = title.getVisibility() != GONE ? title.getMeasuredHeight() : 0;
			int width = right - left;
			if (titleHeight > 0) title.layout(0, 0, width, titleHeight);
			scroll.layout(0, titleHeight, width, titleHeight + scroll.getMeasuredHeight());
			actions.layout(0, titleHeight + scroll.getMeasuredHeight(), width,
					titleHeight + scroll.getMeasuredHeight() + actions.getMeasuredHeight());
		}
	}
}
