package com.mishiranu.dashchan.content.security;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ValueAnimator;
import android.app.Activity;
import android.app.ActivityOptions;
import android.content.Context;
import android.content.Intent;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.text.Editable;
import android.os.Build;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import androidx.core.view.OneShotPreDrawListener;
import com.mishiranu.dashchan.R;
import com.mishiranu.dashchan.util.InterfaceMotion;
import java.util.ArrayList;
import java.util.List;

/** Fixed local V2 palette, not an authentication state machine. No PIN copy is retained here. */
final class LockScreenDesign extends LockScreenForm {
	private final Context context;
	private final LinearLayout body, field, keypad;
	private final TextView description, label;
	private final LockCatView cat;
	private final List<Button> keys = new ArrayList<>();
	private final int background, ink, secondary, panel, accent, onAccent, outline, error, disabledFill, disabledInk;
	private boolean enabled, transition, rendered, welcome, localEdit, compact;
	private int layoutVersion;
	private ValueAnimator transitionAnimator;
	private OneShotPreDrawListener pendingLayout;
	private final Runnable settle = this::settleTransition;

	LockScreenDesign(Context activity) {
		super(activity);
		context = activity;
		// V2 deliberately fixes only this screen's palette; never apply a theme to the Activity.
		background = color(R.color.slooop_lock_background); ink = color(R.color.slooop_lock_on_background);
		panel = color(R.color.slooop_lock_surface); secondary = color(R.color.slooop_lock_secondary_text);
		accent = color(R.color.slooop_lock_primary); onAccent = color(R.color.slooop_lock_on_primary);
		outline = color(R.color.slooop_lock_outline); error = color(R.color.slooop_lock_error);
		disabledFill = color(R.color.slooop_lock_disabled_container); disabledInk = color(R.color.slooop_lock_disabled_content);
		ScrollView scroll = new ScrollView(activity) {
			@Override protected void onMeasure(int widthSpec, int heightSpec) {
				if (rendered) applyGeometry(View.MeasureSpec.getSize(widthSpec) - getPaddingLeft() - getPaddingRight());
				super.onMeasure(widthSpec, heightSpec);
				// Decide from measured content and the inset viewport, not total screen dp.
				// Compact once per presentation, before drawing; never oscillate as it fits.
				int viewport = getMeasuredHeight() - getPaddingTop() - getPaddingBottom();
				if (rendered && !compact && viewport > 0 && getChildCount() > 0
						&& getChildAt(0).getMeasuredHeight() > viewport) {
					compact = true;
					applyGeometry(getMeasuredWidth() - getPaddingLeft() - getPaddingRight());
					super.onMeasure(widthSpec, heightSpec);
				}
			}
		};
		root = scroll;
		scroll.setFillViewport(true); scroll.setBackgroundColor(background); scroll.setForceDarkAllowed(false);
		LinearLayout column = new LinearLayout(activity); column.setOrientation(LinearLayout.VERTICAL);
		column.setPadding(dp(24), dp(16), dp(24), dp(16));
		scroll.addView(column, new ScrollView.LayoutParams(-1, -2));
		TextView brand = text(20, ink, true); brand.setText("Slooop");
		column.addView(brand, new LinearLayout.LayoutParams(-1, -2));
		FrameLayout area = new FrameLayout(activity) {
			@Override protected void onMeasure(int widthSpec, int heightSpec) {
				if (getChildCount() > 0) {
					int available = View.MeasureSpec.getMode(widthSpec) == View.MeasureSpec.UNSPECIFIED
							? dp(420) : View.MeasureSpec.getSize(widthSpec);
					getChildAt(0).getLayoutParams().width = Math.min(dp(420), available);
				}
				super.onMeasure(widthSpec, heightSpec);
			}
		};
		column.addView(area, new LinearLayout.LayoutParams(-1, -2, 1));
		body = new LinearLayout(activity); body.setOrientation(LinearLayout.VERTICAL);
		area.addView(body, new FrameLayout.LayoutParams(-1, -2, Gravity.CENTER));
		cat = new LockCatView(activity); add(cat, dp(96), dp(96), 16);
		style(title, 28, ink, true); add(title, -1, -2, 0);
		description = text(16, secondary, false); add(description, -1, -2, 8);
		field = new LinearLayout(activity); field.setOrientation(LinearLayout.VERTICAL);
		field.setPadding(dp(16), dp(8), dp(16), dp(8)); field.setMinimumHeight(dp(64));
		label = text(14, secondary, false); label.setText(R.string.lock_pin_label);
		label.setLabelFor(input.getId()); field.addView(label);
		style(input, 20, ink, false); input.setHintTextColor(secondary);
		input.setShowSoftInputOnFocus(false); input.setBackground(null); input.setPadding(0, 0, 0, 0);
		input.setTextCursorDrawable(R.drawable.lock_cursor);
		input.setHighlightColor(0x40c1dfa1);
		input.setHint((CharSequence) null); field.addView(input, new LinearLayout.LayoutParams(-1, -2));
		input.setOnFocusChangeListener((v, focused) -> fieldOutline(false));
		add(field, -1, -2, 24);
		style(status, 12, secondary, false); status.setMinLines(2); add(status, -1, -2, 8);
		styleButton(submit, true); add(submit, -1, -2, 16);
		styleButton(biometric, false); add(biometric, -1, -2, 12);
		styleButton(usePin, false); usePin.setText(R.string.lock_use_pin); add(usePin, -1, -2, 12);
		styleButton(additional, false); add(additional, -1, -2, 8);
		keypad = new LinearLayout(activity); keypad.setOrientation(LinearLayout.VERTICAL);
		keypad.setPadding(0, dp(8), 0, dp(8)); add(keypad, -1, -2, 16);
		for (int row = 0; row < 4; row++) {
			LinearLayout line = new LinearLayout(activity);
			LinearLayout.LayoutParams rowParams = new LinearLayout.LayoutParams(-1, -2);
			if (row > 0) rowParams.topMargin = dp(8); keypad.addView(line, rowParams);
			for (int col = 0; col < 3; col++) {
				int number = row * 3 + col + 1;
				Button key = new Button(activity); styleButton(key, false); style(key, 24, ink, false);
				key.setMinWidth(0); key.setMinimumWidth(dp(48)); key.setMinHeight(dp(56));
				key.setMinimumHeight(dp(56)); key.setBackground(ripple(panel, 16));
				key.setTextColor(states(ink, disabledInk));
				LinearLayout.LayoutParams keyParams = new LinearLayout.LayoutParams(0, -2, 1);
				if (col > 0) keyParams.leftMargin = dp(8); line.addView(key, keyParams);
				if (number == 10) { key.setVisibility(View.INVISIBLE); key.setFocusable(false); key.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO); }
				else if (number == 12) {
					key.setCompoundDrawablesWithIntrinsicBounds(R.drawable.lock_backspace, 0, 0, 0);
					key.setCompoundDrawableTintList(states(ink, disabledInk));
					key.setGravity(Gravity.CENTER); key.setContentDescription(activity.getString(R.string.lock_delete_digit));
					key.addOnLayoutChangeListener((v, l, t, r, b, ol, ot, or, ob) -> {
						int side = Math.max(dp(8), (r - l - dp(24)) / 2);
						if (key.getPaddingLeft() != side) key.setPadding(side, dp(8), side, dp(8));
					});
					key.setOnClickListener(v -> edit(null)); keys.add(key);
				} else {
					char digit = number == 11 ? '0' : (char) ('0' + number);
					key.setText(Character.toString(digit)); key.setOnClickListener(v -> edit(key)); keys.add(key);
				}
			}
		}
		input.addTextChangedListener(new android.text.TextWatcher() {
			@Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
			@Override public void onTextChanged(CharSequence s, int start, int before, int count) {
				if (!localEdit && count > 0) cat.neutral(180);
			}
			@Override public void afterTextChanged(Editable value) { fieldOutline(false); }
		});
		fieldOutline(false);
	}
	@Override protected Button createButton(Context context) { return new LockActionButton(context); }
	private void edit(Button key) {
		if (!enabled || transition || !input.isEnabled() || input.getVisibility() != View.VISIBLE) return;
		Editable value = input.getText();
		boolean accepted;
		localEdit = true;
		try {
			accepted = LockPinEditing.edit(value, input.getSelectionStart(), input.getSelectionEnd(), key != null ? key.getText() : null);
			if (key == null) cat.neutral(180);
		} finally { localEdit = false; }
		input.requestFocus();
		if (accepted && keypad.getHeight() > 0 && body.getWidth() > 0) {
			int[] keyXY = new int[2], catXY = new int[2], padXY = new int[2];
			key.getLocationInWindow(keyXY); cat.getLocationInWindow(catXY); keypad.getLocationInWindow(padXY);
			cat.follow((keyXY[0] + key.getWidth() / 2f - catXY[0] - cat.getWidth() / 2f) / (body.getWidth() * .65f),
					(keyXY[1] + key.getHeight() / 2f - padXY[1]) / keypad.getHeight());
		}
	}
	@Override void render(int heading, int desc, int button, boolean pin, boolean bio,
			boolean extra, boolean showWelcome, boolean pinFallback) {
		boolean change = rendered && welcome != showWelcome; rendered = true;
		int[] old = new int[2]; cat.getLocationInWindow(old); int oldSize = cat.getWidth();
		cancelTransition(); cat.reset(); welcome = showWelcome;
		root.setAlpha(1); compact = false;
		int safeWidth = root.getWidth() - root.getPaddingLeft() - root.getPaddingRight();
		if (safeWidth <= 0) safeWidth = Math.round(context.getResources().getConfiguration().screenWidthDp
				* context.getResources().getDisplayMetrics().density);
		applyGeometry(safeWidth);
		title.setGravity(showWelcome ? Gravity.CENTER : Gravity.START);
		description.setGravity(showWelcome ? Gravity.CENTER : Gravity.START);
		title.setText(showWelcome ? R.string.lock_welcome : shortHeading(heading));
		description.setText(showWelcome ? R.string.lock_sign_in
				: desc == R.string.security_unlock ? R.string.lock_unlock_description
				: desc == R.string.security_current_pin ? R.string.lock_authorize_description : desc);
		status.setText(pin ? context.getString(R.string.security_pin_hint) : "");
		status.setTextColor(secondary); fieldOutline(false);
		input.setVisibility(pin ? View.VISIBLE : View.GONE); field.setVisibility(pin ? View.VISIBLE : View.GONE);
		keypad.setVisibility(pin ? View.VISIBLE : View.GONE); submit.setVisibility(pin ? View.VISIBLE : View.GONE);
		submit.setText(button == R.string.security_unlock ? R.string.lock_unlock
				: heading == R.string.security_confirm_pin ? R.string.lock_save : R.string.lock_continue);
		biometric.setVisibility(bio ? View.VISIBLE : View.GONE);
		biometric.setText(showWelcome ? R.string.lock_unlock : R.string.lock_use_biometrics);
		styleButton(biometric, showWelcome);
		biometric.setCompoundDrawablesRelativeWithIntrinsicBounds(showWelcome ? R.drawable.lock_fingerprint : 0, 0, 0, 0);
		biometric.setCompoundDrawableTintList(states(showWelcome ? onAccent : accent, disabledInk));
		biometric.setCompoundDrawablePadding(dp(12));
		usePin.setVisibility(showWelcome && pinFallback ? View.VISIBLE : View.GONE);
		additional.setVisibility(extra ? View.VISIBLE : View.GONE);
		body.removeView(status);
		body.addView(status, showWelcome ? body.indexOfChild(keypad) : body.indexOfChild(submit));
		status.setTextSize(showWelcome ? 14 : 12);
		status.setGravity(showWelcome ? Gravity.CENTER : Gravity.START);
		applyGeometry(safeWidth);
		if (!pin) input.clearFocus();
		if (change && LockCatView.motionEnabled() && oldSize > 0) {
			transition = true; available(enabled);
			final int version = layoutVersion;
			pendingLayout = OneShotPreDrawListener.add(root, () -> {
				pendingLayout = null;
				if (version != layoutVersion || !transition) return;
				int[] next = new int[2]; cat.getLocationInWindow(next);
				float dx = old[0] - next[0], dy = old[1] - next[1], scale = oldSize / (float) cat.getWidth();
				// Convert top-left bounds to the rig's authored pivot.
				dx += (scale - 1) * cat.getPivotX(); dy += (scale - 1) * cat.getPivotY();
				final float tx = dx, ty = dy;
				ValueAnimator motion = ValueAnimator.ofFloat(0, 1); transitionAnimator = motion;
				motion.setDuration(220); motion.setInterpolator(InterfaceMotion.STANDARD);
				motion.addUpdateListener(a -> {
					float f = (float) a.getAnimatedValue();
					cat.setTranslationX(tx * (1 - f)); cat.setTranslationY(ty * (1 - f));
					cat.setScaleX(scale + (1 - scale) * f); cat.setScaleY(scale + (1 - scale) * f);
					field.setAlpha(Math.min(1, f * 220 / 160)); keypad.setAlpha(Math.min(1, f * 220 / 160));
				});
				motion.addListener(new AnimatorListenerAdapter() {
					@Override public void onAnimationEnd(Animator a) { if (transitionAnimator == a) settleTransition(); }
				});
				cat.setTranslationX(tx); cat.setTranslationY(ty); cat.setScaleX(scale); cat.setScaleY(scale);
				field.setAlpha(0); keypad.setAlpha(0); motion.start();
			});
			root.postDelayed(settle, LockCatView.scaledDeadline(context, 220));
		} else available(enabled);
	}
	private void applyGeometry(int safeWidth) {
		int size = welcome ? LockScreenMetrics.biometricCat(safeWidth,
				context.getResources().getDisplayMetrics().density, compact) : dp(compact ? 64 : 96);
		LinearLayout.LayoutParams params = (LinearLayout.LayoutParams) cat.getLayoutParams();
		int gravity = welcome ? Gravity.CENTER_HORIZONTAL : Gravity.START;
		int top = dp(compact ? 8 : 24), bottom = dp(compact ? 12 : welcome ? 24 : 16);
		if (params.width != size || params.height != size || params.gravity != gravity
				|| params.topMargin != top || params.bottomMargin != bottom) {
			params.width = params.height = size; params.gravity = gravity;
			params.topMargin = top; params.bottomMargin = bottom; cat.setLayoutParams(params);
		}
		margin(field, compact ? 16 : 24);
		margin(submit, compact ? 8 : 16);
		margin(biometric, welcome ? compact ? 16 : 28 : compact ? 8 : 12);
		margin(usePin, compact ? 8 : 12);
		margin(keypad, compact ? 12 : 16);
	}
	private void margin(View view, int top) {
		LinearLayout.LayoutParams params = (LinearLayout.LayoutParams) view.getLayoutParams();
		int value = dp(top);
		if (params.topMargin != value) { params.topMargin = value; view.setLayoutParams(params); }
	}
	private int shortHeading(int heading) {
		if (heading == R.string.security_enter_pin) return R.string.lock_enter_pin;
		if (heading == R.string.security_current_pin) return R.string.lock_confirm_current;
		if (heading == R.string.security_new_pin) return R.string.lock_create_pin;
		if (heading == R.string.security_additional_pin) return R.string.lock_create_additional;
		if (heading == R.string.security_confirm_pin) return R.string.lock_repeat_pin;
		return heading;
	}
	@Override void available(boolean value) {
		enabled = value; super.available(value && !transition);
		for (Button key : keys) key.setEnabled(value && !transition);
	}
	@Override boolean transitioning() { return transition; }
	@Override void error() { root.setAlpha(1); cat.neutral(180); fieldOutline(true); status.setTextColor(error); }
	@Override void verifying() { cat.neutral(100); }
	@Override void biometricStarted() { cat.requestStarted(); }
	@Override int success(boolean bio, boolean leaving, Runnable complete) {
		cancelTransition(); return cat.success(bio, leaving ? root : null, complete);
	}
	@Override boolean customExit() { return true; }
	@Override void finish(Activity activity, Intent resume, boolean decorated) {
		if (!decorated) { super.finish(activity, resume, false); return; }
		// The content already faded in the same timeline. Do not add a second Activity slide/fade.
		if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
			activity.overrideActivityTransition(Activity.OVERRIDE_TRANSITION_OPEN, 0, 0);
			activity.overrideActivityTransition(Activity.OVERRIDE_TRANSITION_CLOSE, 0, 0);
		}
		if (resume != null) activity.startActivity(new Intent(resume).addFlags(Intent.FLAG_ACTIVITY_NO_ANIMATION),
				ActivityOptions.makeCustomAnimation(activity, 0, 0).toBundle());
		activity.finish();
		if (Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE) suppressLegacyTransition(activity);
	}
	@SuppressWarnings("deprecation")
	private static void suppressLegacyTransition(Activity activity) {
		// Android 11-13 have no overrideActivityTransition; keep this compatibility call isolated.
		activity.overridePendingTransition(0, 0);
	}
	private void settleTransition() {
		cancelTransition(); cat.reset(); available(enabled);
		if (input.getVisibility() == View.VISIBLE) input.requestFocus();
	}
	private void cancelTransition() {
		layoutVersion++; root.removeCallbacks(settle);
		if (pendingLayout != null) { pendingLayout.removeListener(); pendingLayout = null; }
		ValueAnimator old = transitionAnimator; transitionAnimator = null;
		if (old != null) { old.removeAllListeners(); old.cancel(); }
		transition = false; field.setAlpha(1); keypad.setAlpha(1);
	}
	@Override void dispose() {
		cancelTransition();
		// onPause can precede the final displayed frame. Reset only on next render/detach,
		// not while lowered glasses or the faded content can still be visible.
		cat.cancel();
	}
	private void fieldOutline(boolean invalid) {
		GradientDrawable shape = shape(panel, 20);
		shape.setStroke(dp(input.hasFocus() ? 2 : 1), invalid ? error : input.hasFocus() ? accent : outline);
		field.setBackground(shape);
	}
	private void add(View view, int width, int height, int margin) {
		LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(width, height);
		params.topMargin = dp(margin); body.addView(view, params);
	}
	private TextView text(int size, int color, boolean medium) { TextView v = new TextView(context); style(v, size, color, medium); return v; }
	private void style(TextView view, int size, int color, boolean medium) {
		view.setTextSize(size); view.setTextColor(color);
		view.setTypeface(Typeface.create(medium ? "sans-serif-medium" : "sans-serif", Typeface.NORMAL));
	}
	private void styleButton(Button button, boolean primary) {
		style(button, 16, primary ? onAccent : accent, true); button.setAllCaps(false);
		button.setGravity(Gravity.CENTER);
		button.setTextColor(states(primary ? onAccent : accent, disabledInk));
		button.setMinHeight(dp(primary ? 56 : 48)); button.setMinimumHeight(dp(primary ? 56 : 48));
		button.setMinWidth(0); button.setPadding(dp(16), dp(8), dp(16), dp(8));
		button.setFilterTouchesWhenObscured(true);
		button.setBackgroundTintList(null); button.setBackground(ripple(primary ? accent : Color.TRANSPARENT, primary ? 1000 : 28, primary));
		button.setStateListAnimator(null);
	}
	private GradientDrawable shape(int color, int radius) {
		GradientDrawable shape = new GradientDrawable(); shape.setColor(color); shape.setCornerRadius(dp(radius)); return shape;
	}
	private RippleDrawable ripple(int color, int radius) {
		return ripple(color, radius, false);
	}
	private RippleDrawable ripple(int color, int radius, boolean primary) {
		GradientDrawable fill = shape(color, radius);
		if (primary) fill.setColor(states(color, disabledFill));
		return new RippleDrawable(ColorStateList.valueOf(color(primary ? R.color.slooop_lock_primary_ripple
				: R.color.slooop_lock_secondary_ripple)), fill, shape(Color.WHITE, radius));
	}
	private ColorStateList states(int active, int disabled) {
		return new ColorStateList(new int[][]{{-android.R.attr.state_enabled}, {}}, new int[]{disabled, active});
	}
	private int color(int resource) { return context.getColor(resource); }
	private int dp(float value) { return Math.round(value * context.getResources().getDisplayMetrics().density); }
}
