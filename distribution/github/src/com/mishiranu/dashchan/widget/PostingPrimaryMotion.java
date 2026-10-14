package com.mishiranu.dashchan.widget;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ValueAnimator;
import android.content.res.ColorStateList;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.view.Gravity;
import android.view.View;
import android.view.animation.LinearInterpolator;
import android.view.animation.PathInterpolator;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import androidx.core.graphics.ColorUtils;
import com.google.android.material.loadingindicator.LoadingIndicator;
import com.mishiranu.dashchan.R;
import com.mishiranu.dashchan.util.InterfaceMotion;
import com.mishiranu.dashchan.util.ResourceUtils;
import com.mishiranu.dashchan.util.ViewUtils;

/** Fixed-bounds feedback. Never enables the button, opens a dialog or sends a post. */
final class PostingPrimaryMotion implements View.OnAttachStateChangeListener {
	private static final int CONTENT_DURATION = 180;
	private static final int SUCCESS_CHECK_DURATION = 500;
	private static final int SUCCESS_HOLD_DURATION = 500;
	private static final int SUCCESS_TO_SEND_DURATION = 500;
	private final Button button;
	private final FrameLayout slot;
	private final FrameLayout decorations;
	private final LinearLayout content;
	private final TextView label;
	private final ImageView check, outgoing;
	private final LoadingIndicator indicator;
	private final PostingCaptchaMotion.CheckDrawable stroke = new PostingCaptchaMotion.CheckDrawable();
	private final GradientDrawable fill = new GradientDrawable();
	private final PostingPrimaryFeedback feedback = new PostingPrimaryFeedback();
	private PostingPrimaryState.Mode mode = PostingPrimaryState.Mode.SOLVE;
	private ValueAnimator animation, colorAnimation, shapeAnimation;
	private int accent, solveFill, loadingFill, onAccent, disabled, onDisabled, currentFill, currentText;
	private float radius;
	private boolean closed, enabled, success, shapePressed, resettingShape, haveColors, immediateAvailability;
	private Runnable availabilityChanged;
	private boolean captchaModalVisible;
	private PostingPrimaryState.Change deferredCaptchaChange;

	void setCaptchaDialogVisible(boolean visible) {
		if (closed || captchaModalVisible == visible) return;
		captchaModalVisible = visible;
		finish();
		if (!visible && deferredCaptchaChange != null) {
			PostingPrimaryState.Change deferred = deferredCaptchaChange; deferredCaptchaChange = null;
			bind(new PostingPrimaryState.Change(deferred.mode, true, deferred.success, deferred.restoring, deferred.initial));
		}
		notifyAvailability();
	}

	PostingPrimaryMotion(Button button, FrameLayout slot) {
		this.button = button; this.slot = slot; radius = dp(28);
		decorations = new FrameLayout(button.getContext());
		decorations.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS);
		slot.addView(decorations, new FrameLayout.LayoutParams(-1, -1));
		outgoing = new ImageView(button.getContext());
		outgoing.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
		outgoing.setScaleType(ImageView.ScaleType.FIT_CENTER);
		decorations.addView(outgoing, new FrameLayout.LayoutParams(-1, -1));
		content = new LinearLayout(button.getContext()); content.setGravity(Gravity.CENTER);
		content.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS);
		indicator = PullLoadingIndicator.createMaterial(button.getContext());
		indicator.setContainerWidth(dp(32)); indicator.setContainerHeight(dp(32)); indicator.setIndicatorSize(dp(26));
		indicator.setContainerColor(Color.TRANSPARENT); indicator.setId(R.id.posting_captcha_loading);
		content.addView(indicator, new LinearLayout.LayoutParams(dp(32), dp(32)));
		check = new ImageView(button.getContext()); check.setImageDrawable(stroke); check.setId(R.id.posting_captcha_ready);
		LinearLayout.LayoutParams checkParams = new LinearLayout.LayoutParams(dp(22), dp(22));
		checkParams.setMarginEnd(dp(8)); content.addView(check, checkParams);
		label = new TextView(button.getContext()); label.setGravity(Gravity.CENTER); label.setId(R.id.posting_captcha_status);
		ViewUtils.setTextSizeScaled(label, 16); content.addView(label, new LinearLayout.LayoutParams(-2, -2));
		decorations.addView(content, new FrameLayout.LayoutParams(-2, -2, Gravity.CENTER));
		slot.setClipToOutline(true); slot.addOnAttachStateChangeListener(this);
	}
	private int dp(float value) { return Math.round(value * ResourceUtils.obtainDensity(button)); }
	private CharSequence caption() {
		if (captchaModalVisible) return button.getContext().getText(R.string.posting_solve_captcha);
		return button.getContext().getText(mode == PostingPrimaryState.Mode.SEND ? R.string.send
				: mode == PostingPrimaryState.Mode.LOADING ? R.string.posting_primary_loading : R.string.posting_solve_captcha);
	}
	private boolean motionAllowed() {
		return slot.isAttachedToWindow() && slot.isShown() && InterfaceMotion.isEnabled() && InterfaceMotion.duration(1) > 0;
	}
	void setAvailabilityChanged(Runnable callback) { availabilityChanged = callback; }
	boolean canActivate() { return !closed && !captchaModalVisible && !feedback.blocksAction(); }
	private void notifyAvailability() { if (!closed && availabilityChanged != null) availabilityChanged.run(); }

	/** Retain theme hue, soften saturation for the earlier active stages, and keep white readable. */
	static int stageFill(int primary, float saturation) {
		float[] hsl = new float[3]; ColorUtils.colorToHSL(primary, hsl); hsl[1] *= saturation;
		return PostingFormAppearance.primaryBackground(ColorUtils.HSLToColor(hsl));
	}
	void colors(int accent, int onAccent, int disabled, int onDisabled) {
		if (haveColors && this.accent == accent && this.onAccent == onAccent
				&& this.disabled == disabled && this.onDisabled == onDisabled) return;
		finish(); this.accent = accent; this.onAccent = onAccent; this.disabled = disabled; this.onDisabled = onDisabled;
		solveFill = stageFill(accent, .65f); loadingFill = stageFill(accent, .85f); haveColors = true;
		slot.setBackground(fill);
		GradientDrawable mask = new GradientDrawable(); mask.setColor(Color.WHITE); mask.setCornerRadius(radius);
		// Observe actual native drawable state, without intercepting touch, click or keyboard actions.
		button.setBackground(new PressRipple(mask)); applyRadius(dp(28)); settle();
	}
	private final class PressRipple extends RippleDrawable {
		PressRipple(GradientDrawable mask) {
			super(ColorStateList.valueOf(ColorUtils.setAlphaComponent(PostingPrimaryMotion.this.onAccent, 32)), null, mask);
		}
		@Override protected boolean onStateChange(int[] states) {
			boolean changed = super.onStateChange(states), pressed = false, available = false;
			for (int state : states) {
				if (state == android.R.attr.state_pressed) pressed = true;
				if (state == android.R.attr.state_enabled) available = true;
			}
			if (!resettingShape && !closed) pressedChanged(pressed && available, available);
			return changed;
		}
	}
	private void applyRadius(float value) {
		radius = value; fill.setCornerRadius(value);
		Drawable background = button.getBackground();
		if (background instanceof RippleDrawable) {
			// Resolve the installed mask after tint/mutate, not a potentially copied child reference.
			Drawable mask = ((RippleDrawable) background).findDrawableByLayerId(android.R.id.mask);
			if (mask instanceof GradientDrawable) ((GradientDrawable) mask).setCornerRadius(value);
		}
		slot.invalidateOutline(); button.invalidateOutline();
	}
	private void pressedChanged(boolean pressed, boolean available) {
		if (shapePressed == pressed && available) return;
		shapePressed = pressed; cancelShape(); float target = dp(pressed ? 16 : 28);
		if (!available || !motionAllowed()) { applyRadius(target); return; }
		ValueAnimator running = ValueAnimator.ofFloat(radius, target); shapeAnimation = running;
		running.setDuration(InterfaceMotion.duration(pressed ? 150 : 340));
		running.setInterpolator(pressed ? InterfaceMotion.STANDARD : new PathInterpolator(.18f, .85f, .25f, 1.12f));
		running.addUpdateListener(value -> { if (shapeAnimation == value && !closed) applyRadius((float) value.getAnimatedValue()); });
		running.addListener(new AnimatorListenerAdapter() {
			@Override public void onAnimationEnd(Animator ignored) {
				if (shapeAnimation == running && !closed) { shapeAnimation = null; applyRadius(target); }
			}
		}); running.start();
	}
	private void cancelShape() {
		ValueAnimator old = shapeAnimation; shapeAnimation = null;
		if (old != null) { old.removeAllUpdateListeners(); old.removeAllListeners(); old.cancel(); }
	}

	void bind(PostingPrimaryState.Change change) {
		if (closed || !change.changed && !change.success && !change.restoring) return;
		if (captchaModalVisible) {
			boolean successEdge = change.success || deferredCaptchaChange != null && deferredCaptchaChange.success
					&& change.mode == PostingPrimaryState.Mode.SEND && !change.restoring;
			deferredCaptchaChange = new PostingPrimaryState.Change(change.mode, true, successEdge, change.restoring, change.initial);
			mode = change.mode; finish(); return;
		}
		boolean motion = !change.initial && !change.restoring && motionAllowed();
		Bitmap previous = motion ? snapshot() : null; int startFill = currentFill, startText = currentText;
		cancelContent(); feedback.cancel(); success = false; mode = change.mode;
		immediateAvailability = !motion;
		button.setText(caption()); button.setContentDescription(caption()); button.setTooltipText(caption());
		long generation = 0;
		if (motion && change.success) { success = true; generation = feedback.begin(); }
		settle();
		if (motion) {
			animateColor(startFill, startText, targetFill(), targetText(), 240);
			if (success) stroke.progress(0f);
			animateContent(previous, success ? SUCCESS_CHECK_DURATION + SUCCESS_HOLD_DURATION : CONTENT_DURATION,
					CONTENT_DURATION, generation);
		}
	}
	private static float ease(float value) { return InterfaceMotion.STANDARD.getInterpolation(Math.max(0f, Math.min(1f, value))); }
	private Bitmap snapshot() {
		if (decorations.getWidth() <= 0 || decorations.getHeight() <= 0 || !slot.isAttachedToWindow()) return null;
		// Only decorative CTA pixels, including the real loader/check; never the form or captcha.
		// Capture both current layers, with their actual alpha/scale, when a fast event interrupts a fade.
		Bitmap bitmap = Bitmap.createBitmap(decorations.getWidth(), decorations.getHeight(), Bitmap.Config.ARGB_8888);
		decorations.draw(new Canvas(bitmap)); return bitmap;
	}
	private void animateContent(Bitmap previous, int duration, int fadeDuration, long generation) {
		outgoing.setImageBitmap(previous);
		if (previous != null) {
			outgoing.setAlpha(1f); outgoing.setVisibility(View.VISIBLE);
		} else outgoing.setVisibility(View.GONE);
		content.setAlpha(previous != null ? 0f : 1f); content.setScaleX(.94f); content.setScaleY(.94f);
		ValueAnimator running = ValueAnimator.ofFloat(0f, duration); animation = running;
		running.setInterpolator(new LinearInterpolator()); running.setDuration(InterfaceMotion.duration(duration));
		running.addUpdateListener(value -> {
			if (closed || animation != value) return;
			if (!motionAllowed()) { finish(); return; }
			float time = (float) value.getAnimatedValue(), alpha = Math.min(1f, time / fadeDuration), scale = ease(time / fadeDuration);
			outgoing.setAlpha(1f - alpha); content.setAlpha(previous != null ? alpha : 1f);
			content.setScaleX(.94f + .06f * scale); content.setScaleY(.94f + .06f * scale);
			if (success) stroke.progress(ease(time / SUCCESS_CHECK_DURATION));
		});
		running.addListener(new AnimatorListenerAdapter() {
			@Override public void onAnimationEnd(Animator ignored) {
				if (animation != running || closed) return;
				animation = null;
				if (success) {
					Bitmap done = snapshot(); success = false; settle();
					animateContent(done, SUCCESS_TO_SEND_DURATION, SUCCESS_TO_SEND_DURATION, generation);
				} else {
					boolean released = feedback.complete(generation);
					// Recompute the genuine validator before painting: no disabled flash between Success and Send.
					if (released) { immediateAvailability = true; notifyAvailability(); }
					settle();
				}
			}
		}); running.start();
	}
	private boolean filled() { return captchaModalVisible || success || feedback.blocksAction() || mode == PostingPrimaryState.Mode.LOADING || button.isEnabled(); }
	private int targetFill() {
		if (!filled()) return disabled;
		if (captchaModalVisible) return solveFill;
		return mode == PostingPrimaryState.Mode.SOLVE ? solveFill : mode == PostingPrimaryState.Mode.LOADING ? loadingFill : accent;
	}
	private int targetText() { return filled() ? onAccent : onDisabled; }
	private void paint(int background, int text) {
		background = ColorUtils.setAlphaComponent(background, 255);
		if (haveColors && filled()) {
			// Keep the agreed light content readable throughout color transitions too.
			background = PostingFormAppearance.primaryBackground(background); text = onAccent;
		}
		currentFill = background; currentText = text; fill.setColor(background);
		button.setTextColor(Color.TRANSPARENT); label.setTextColor(text); stroke.color(text); indicator.setIndicatorColor(text);
	}
	private void settle() {
		enabled = button.isEnabled(); button.setText(caption()); button.setContentDescription(caption()); button.setTooltipText(caption());
		label.setText(success ? button.getContext().getText(R.string.posting_captcha_done) : caption());
		outgoing.setVisibility(View.GONE); outgoing.setImageDrawable(null);
		content.setAlpha(1f); content.setScaleX(1f); content.setScaleY(1f);
		indicator.setVisibility(!captchaModalVisible && mode == PostingPrimaryState.Mode.LOADING ? View.VISIBLE : View.GONE);
		label.setVisibility(!captchaModalVisible && mode == PostingPrimaryState.Mode.LOADING ? View.GONE : View.VISIBLE);
		check.setVisibility(success ? View.VISIBLE : View.GONE); check.setScaleX(1f); check.setScaleY(1f); stroke.progress(1f);
		paint(targetFill(), targetText());
	}
	void enabledChanged() {
		boolean immediate = immediateAvailability; immediateAvailability = false;
		if (closed || enabled == button.isEnabled()) return;
		boolean becameEnabled = !enabled && button.isEnabled(); enabled = button.isEnabled();
		// Real click availability does not compete with success/loading stage paint.
		if (feedback.blocksAction() || mode == PostingPrimaryState.Mode.LOADING) return;
		int startFill = currentFill, startText = currentText;
		if (immediate || !becameEnabled || !motionAllowed()) { cancelColor(); paint(targetFill(), targetText()); return; }
		animateColor(startFill, startText, targetFill(), targetText(), 250);
	}
	private void animateColor(int startFill, int startText, int endFill, int endText, int duration) {
		cancelColor(); paint(startFill, startText);
		ValueAnimator running = ValueAnimator.ofFloat(0f, 1f); colorAnimation = running;
		running.setInterpolator(InterfaceMotion.STANDARD); running.setDuration(InterfaceMotion.duration(duration));
		running.addUpdateListener(value -> {
			if (colorAnimation != value || closed) return;
			float fraction = (float) value.getAnimatedValue();
			paint(PostingFormAppearance.blendPrimaryBackground(startFill, endFill, fraction),
					ColorUtils.blendARGB(startText, endText, fraction));
		});
		running.addListener(new AnimatorListenerAdapter() {
			@Override public void onAnimationEnd(Animator ignored) {
				if (colorAnimation == running && !closed) { colorAnimation = null; paint(endFill, endText); }
			}
		}); running.start();
	}
	private void cancelColor() {
		ValueAnimator old = colorAnimation; colorAnimation = null;
		if (old != null) { old.removeAllUpdateListeners(); old.removeAllListeners(); old.cancel(); }
	}
	private void cancelContent() {
		ValueAnimator old = animation; animation = null; cancelColor();
		if (old != null) { old.removeAllUpdateListeners(); old.removeAllListeners(); old.cancel(); }
	}
	void finishContentTransition() {
		cancelContent(); boolean released = feedback.cancel(); success = false;
		if (released) { immediateAvailability = true; notifyAvailability(); }
		settle();
	}
	void finish() {
		finishContentTransition(); cancelShape();
		resettingShape = true; button.setPressed(false); resettingShape = false; shapePressed = false; applyRadius(dp(28));
	}
	void close() { availabilityChanged = null; deferredCaptchaChange = null; finish(); closed = true; slot.removeOnAttachStateChangeListener(this); }
	@Override public void onViewAttachedToWindow(View view) {}
	@Override public void onViewDetachedFromWindow(View view) { deferredCaptchaChange = null; finish(); }
}
