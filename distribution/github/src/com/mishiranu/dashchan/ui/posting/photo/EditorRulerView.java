package com.mishiranu.dashchan.ui.posting.photo;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Shader;
import android.os.Bundle;
import android.util.TypedValue;
import android.view.HapticFeedbackConstants;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.view.accessibility.AccessibilityNodeInfo;
import java.util.Locale;

/** A scrolling scale under a fixed pointer; gestures produce one history operation. */
public final class EditorRulerView extends View {
    public interface Listener { void onBegin(); void onValue(float value); void onEnd(boolean commit); }
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final float minimum, maximum, step, tick, spacing;
    private final Listener listener;
    private float value, downX, initial;
    private int feedback;
    private boolean tracking;

    public EditorRulerView(Context context, float minimum, float maximum, float step, float value, Listener listener) {
        super(context); this.minimum = minimum; this.maximum = maximum; this.step = step;
        this.value = value; this.listener = listener;
        tick = maximum - minimum > 100 ? 5 : 1; spacing = dp(tick == 5 ? 16 : 10);
        setFocusable(true); setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_YES);
    }
    private float dp(float value) { return value * getResources().getDisplayMetrics().density; }
    public float value() { return value; }
    /** Model -> control binding. Never feeds a programmatic change back into history. */
    public void syncValue(float next) {
        next = Math.max(minimum, Math.min(maximum, Math.round(next / step) * step));
        if (value != next) {
            value = next; initial = next; tracking = false;
            if (getParent() != null) getParent().requestDisallowInterceptTouchEvent(false);
            invalidate();
        }
    }
    private void setValue(float next) {
        next = Math.max(minimum, Math.min(maximum, Math.round(next / step) * step));
        if (value != next) { value = next; listener.onValue(value); invalidate(); }
    }

    @Override protected void onDraw(Canvas canvas) {
        super.onDraw(canvas); float center = getWidth() / 2f, top = dp(12);
        paint.setStrokeWidth(dp(1));
        paint.setTextSize(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, 12, getResources().getDisplayMetrics()));
        paint.setTextAlign(Paint.Align.CENTER);
        for (float number = minimum; number <= maximum + .01f; number += tick) {
            float x = center + (number - value) / tick * spacing;
            if (x < -spacing || x > getWidth() + spacing) continue;
            boolean major = Math.round(number / tick) % 5 == 0;
            paint.setColor(Math.abs(number) < .001f ? 0xffa8c7fa : major ? 0xffb4b6bd : 0xff5d6066);
            canvas.drawLine(x, top, x, top + dp(major ? 18 : 10), paint);
            if (major) canvas.drawText(Integer.toString(Math.round(number)), x, top + dp(34), paint);
        }
        paint.setShader(new LinearGradient(0, 0, dp(48), 0, 0xff1b1b1f, 0x001b1b1f, Shader.TileMode.CLAMP));
        canvas.drawRect(0, 0, dp(48), getHeight(), paint);
        paint.setShader(new LinearGradient(getWidth() - dp(48), 0, getWidth(), 0, 0x001b1b1f, 0xff1b1b1f, Shader.TileMode.CLAMP));
        canvas.drawRect(getWidth() - dp(48), 0, getWidth(), getHeight(), paint); paint.setShader(null);
        paint.setColor(0xffa8c7fa); paint.setStrokeWidth(dp(2.5f));
        canvas.drawLine(center, top - dp(5), center, top + dp(23), paint);
        paint.setColor(Color.WHITE); canvas.drawCircle(center, top - dp(6), dp(2), paint);
    }

    @Override public boolean onTouchEvent(MotionEvent event) {
        if (!isEnabled()) return false;
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                tracking = true; downX = event.getX(); initial = value; feedback = Math.round(value / tick);
                getParent().requestDisallowInterceptTouchEvent(true); listener.onBegin(); return true;
            case MotionEvent.ACTION_MOVE:
                if (!tracking) return true;
                setValue(initial + (downX - event.getX()) / spacing * tick);
                int interval = Math.round(value / tick);
                if (feedback != interval) { feedback = interval; performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK); }
                return true;
            case MotionEvent.ACTION_UP:
                if (tracking) { tracking = false; listener.onEnd(true); performClick(); }
                getParent().requestDisallowInterceptTouchEvent(false); return true;
            case MotionEvent.ACTION_CANCEL:
                if (tracking) { tracking = false; value = initial; listener.onEnd(false); invalidate(); }
                getParent().requestDisallowInterceptTouchEvent(false); return true;
            default: return true;
        }
    }
    @Override public boolean performClick() { super.performClick(); return true; }
    @Override public boolean onKeyDown(int key, KeyEvent event) {
        if (isEnabled() && (key == KeyEvent.KEYCODE_DPAD_LEFT || key == KeyEvent.KEYCODE_DPAD_RIGHT)) {
            listener.onBegin(); setValue(value + (key == KeyEvent.KEYCODE_DPAD_RIGHT ? step : -step)); listener.onEnd(true); return true;
        }
        return super.onKeyDown(key, event);
    }
    @Override public void onInitializeAccessibilityNodeInfo(AccessibilityNodeInfo info) {
        super.onInitializeAccessibilityNodeInfo(info); info.setClassName("android.widget.SeekBar");
        info.setRangeInfo(new AccessibilityNodeInfo.RangeInfo(AccessibilityNodeInfo.RangeInfo.RANGE_TYPE_FLOAT, minimum, maximum, value));
        info.setStateDescription(String.format(Locale.getDefault(), step < 1 ? "%.1f" : "%.0f", value));
        info.addAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SET_PROGRESS);
        info.addAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_FORWARD);
        info.addAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_BACKWARD);
    }
    @Override public boolean performAccessibilityAction(int action, Bundle args) {
        if (!isEnabled()) return false;
        float next;
        if (action == AccessibilityNodeInfo.AccessibilityAction.ACTION_SET_PROGRESS.getId() && args != null) {
            next = args.getFloat(AccessibilityNodeInfo.ACTION_ARGUMENT_PROGRESS_VALUE, value);
            if (!Float.isFinite(next)) return false;
        } else if (action == AccessibilityNodeInfo.ACTION_SCROLL_FORWARD) next = value + step;
        else if (action == AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD) next = value - step;
        else return super.performAccessibilityAction(action, args);
        listener.onBegin(); setValue(next); listener.onEnd(true); return true;
    }
}
