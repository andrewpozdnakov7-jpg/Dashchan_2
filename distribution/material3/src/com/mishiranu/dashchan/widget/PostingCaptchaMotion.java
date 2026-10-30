package com.mishiranu.dashchan.widget;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ValueAnimator;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.ColorFilter;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PathMeasure;
import android.graphics.PixelFormat;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.content.res.ColorStateList;
import android.view.Gravity;
import android.view.View;
import android.view.animation.LinearInterpolator;
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
import com.mishiranu.dashchan.widget.PostingCaptchaMotionState.State;

/** One view-owned, cancel-safe captcha animation. Business state always takes effect immediately. */
final class PostingCaptchaMotion implements View.OnAttachStateChangeListener {
	private final Button entry;
	private final FrameLayout slot;
	private final LoadingIndicator indicator;
	private final LinearLayout content;
	private final TextView label, outgoing;
	private final ImageView check;
	private final CheckDrawable stroke = new CheckDrawable();
	private final GradientDrawable fill = new GradientDrawable();
	private final PostingCaptchaMotionState revisions = new PostingCaptchaMotionState();
	private State state = State.READY;
	private ValueAnimator animator;
	private int surface, tonal, color, fillColor;
	private boolean closed;

	PostingCaptchaMotion(Button entry, FrameLayout slot, LoadingIndicator indicator) {
		this.entry = entry; this.slot = slot; this.indicator = indicator;
		outgoing = new TextView(entry.getContext()); outgoing.setGravity(Gravity.CENTER);
		outgoing.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
		ViewUtils.setTextSizeScaled(outgoing, 16);
		slot.addView(outgoing, new FrameLayout.LayoutParams(-1, -1));
		content = new LinearLayout(entry.getContext()); content.setGravity(Gravity.CENTER);
		content.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS);
		check = new ImageView(entry.getContext()); check.setId(R.id.posting_captcha_ready);
		check.setImageDrawable(stroke); check.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
		LinearLayout.LayoutParams iconParams = new LinearLayout.LayoutParams(dp(22), dp(22));
		iconParams.setMarginEnd(dp(8)); content.addView(check, iconParams);
		label = new TextView(entry.getContext()); label.setGravity(Gravity.CENTER);
		label.setId(R.id.posting_captcha_status); ViewUtils.setTextSizeScaled(label, 16);
		content.addView(label, new LinearLayout.LayoutParams(-2, -2));
		slot.addView(content, new FrameLayout.LayoutParams(-2, -2, Gravity.CENTER));
		slot.addOnAttachStateChangeListener(this);
	}

	private int dp(float value) { return Math.round(ResourceUtils.obtainDensity(entry) * value); }
	private CharSequence text(State state) {
		return entry.getContext().getText(state == State.DONE ? R.string.posting_captcha_done
				: state == State.ERROR ? R.string.posting_captcha_retry : R.string.posting_captcha_label);
	}

	void colors(int surface, int tonal, int color, int accent) {
		finish(); this.surface = surface; this.tonal = tonal; this.color = color;
		fill.setCornerRadius(dp(16)); fill.setStroke(dp(1), accent);
		GradientDrawable mask = new GradientDrawable(); mask.setCornerRadius(dp(16)); mask.setColor(Color.WHITE);
		entry.setBackgroundTintList(null);
		entry.setBackground(new RippleDrawable(ColorStateList.valueOf(ColorUtils.setAlphaComponent(accent, 32)), fill, mask));
		entry.setTextColor(Color.TRANSPARENT); // The original button retains measurement, focus and callbacks.
		label.setTextColor(color); outgoing.setTextColor(color); stroke.color(color);
		indicator.setIndicatorColor(accent);
		int minimum = dp(104);
		for (State value : new State[] {State.READY, State.ERROR, State.DONE}) {
			int width = (int) Math.ceil(android.text.Layout.getDesiredWidth(text(value), label.getPaint()));
			minimum = Math.max(minimum, width + dp(value == State.DONE ? 42 : 12));
		}
		// Reserve all states, not only the currently painted label, to avoid geometry jumps.
		slot.setMinimumWidth(minimum);
		slot.setMinimumHeight(Math.max(dp(56), (int) Math.ceil(label.getPaint().getFontSpacing()) + dp(16)));
		settle();
	}

	void bind(State next, boolean restoring) {
		if (closed) return;
		boolean allowed = InterfaceMotion.isEnabled() && InterfaceMotion.duration(1) > 0 && slot.isAttachedToWindow() && slot.isShown();
		PostingCaptchaMotionState.Change change = revisions.bind(next, restoring, allowed);
		if (!change.changed && !restoring && allowed) return;
		State previous = state; CharSequence oldText = text(previous); int startFill = fillColor;
		finish(); state = next; settle();
		if (!change.animate || next == State.LEGACY) return;
		boolean oldLoading = previous == State.LOADING, newLoading = next == State.LOADING;
		outgoing.setText(oldText); outgoing.setVisibility(oldLoading || previous == State.LEGACY ? View.GONE : View.VISIBLE);
		outgoing.setAlpha(1f);
		indicator.setVisibility(oldLoading || newLoading ? View.VISIBLE : View.GONE);
		indicator.setAlpha(oldLoading ? 1f : 0f);
		content.setVisibility(newLoading ? View.GONE : View.VISIBLE);
		label.setAlpha(0f); label.setScaleX(.94f); label.setScaleY(.94f);
		fillColor = startFill; fill.setColor(fillColor);
		if (change.success) { stroke.progress(0f); check.setScaleX(.90f); check.setScaleY(.90f); }
		int duration = change.success ? 360 : 180;
		ValueAnimator running = ValueAnimator.ofFloat(0f, duration); animator = running;
		running.setDuration(InterfaceMotion.duration(duration)); running.setInterpolator(new LinearInterpolator());
		running.addUpdateListener(value -> {
			if (closed || animator != value || !revisions.owns(change.revision)) return;
			float time = (float) value.getAnimatedValue();
			float incoming = ease(time / 180f);
			outgoing.setAlpha(1f - Math.min(1f, time / 180f));
			indicator.setAlpha(newLoading ? incoming : oldLoading ? 1f - Math.min(1f, time / 180f) : 0f);
			label.setAlpha(incoming); label.setScaleX(.94f + .06f * incoming); label.setScaleY(.94f + .06f * incoming);
			fillColor = ColorUtils.blendARGB(startFill, next == State.DONE ? tonal : surface, ease(time / (change.success ? 220f : 180f)));
			fill.setColor(fillColor);
			if (change.success) {
				stroke.progress(ease(time / 240f));
				float fraction = time / 360f;
				float scale = fraction < .65f ? .90f + .15f * ease(fraction / .65f)
						: 1.05f - .05f * ease((fraction - .65f) / .35f);
				check.setScaleX(scale); check.setScaleY(scale);
			}
		});
		running.addListener(new AnimatorListenerAdapter() {
			@Override public void onAnimationEnd(Animator animation) {
				if (animator == animation && revisions.owns(change.revision)) { animator = null; settle(); }
			}
		});
		running.start();
	}
	private static float ease(float fraction) { return InterfaceMotion.STANDARD.getInterpolation(Math.max(0f, Math.min(1f, fraction))); }
	private void settle() {
		label.setText(text(state)); label.setAlpha(1f); label.setScaleX(1f); label.setScaleY(1f);
		outgoing.setVisibility(View.GONE); outgoing.setAlpha(1f);
		content.setVisibility(state == State.LOADING || state == State.LEGACY ? View.GONE : View.VISIBLE);
		check.setVisibility(state == State.DONE ? View.VISIBLE : View.GONE);
		check.setScaleX(1f); check.setScaleY(1f); stroke.progress(1f);
		indicator.setVisibility(state == State.LOADING ? View.VISIBLE : View.GONE); indicator.setAlpha(1f);
		fillColor = state == State.DONE ? tonal : surface; fill.setColor(fillColor);
	}
	void finish() {
		ValueAnimator old = animator; animator = null;
		if (old != null) { old.removeAllUpdateListeners(); old.removeAllListeners(); old.cancel(); }
		settle();
	}
	void close() { finish(); closed = true; revisions.close(); slot.removeOnAttachStateChangeListener(this); }
	@Override public void onViewAttachedToWindow(View view) {}
	@Override public void onViewDetachedFromWindow(View view) { finish(); }

	static final class CheckDrawable extends Drawable {
		private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
		private final Path complete = new Path(), segment = new Path();
		private final PathMeasure measure = new PathMeasure();
		private float progress = 1f;
		CheckDrawable() { paint.setStyle(Paint.Style.STROKE); paint.setStrokeCap(Paint.Cap.ROUND); paint.setStrokeJoin(Paint.Join.ROUND); }
		void color(int color) { paint.setColor(color); invalidateSelf(); }
		void progress(float value) { progress = value; invalidateSelf(); }
		@Override public void draw(Canvas canvas) {
			float width = getBounds().width(), height = getBounds().height();
			complete.rewind(); complete.moveTo(width * .18f, height * .52f);
			complete.lineTo(width * .42f, height * .75f); complete.lineTo(width * .84f, height * .26f);
			measure.setPath(complete, false); segment.rewind();
			measure.getSegment(0, measure.getLength() * progress, segment, true);
			paint.setStrokeWidth(Math.max(1f, width * .10f));
			int save = canvas.save(); canvas.translate(getBounds().left, getBounds().top); canvas.drawPath(segment, paint); canvas.restoreToCount(save);
		}
		@Override public void setAlpha(int alpha) { paint.setAlpha(alpha); invalidateSelf(); }
		@Override public void setColorFilter(ColorFilter filter) { paint.setColorFilter(filter); invalidateSelf(); }
		@SuppressWarnings("deprecation") @Override public int getOpacity() { return PixelFormat.TRANSLUCENT; }
	}
}
