package com.mishiranu.dashchan.ui.posting.photo;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ArgbEvaluator;
import android.animation.ValueAnimator;
import android.content.res.ColorStateList;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewTreeObserver;
import android.widget.Button;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;

/** Bounded outgoing panel layer, one incoming transition, and interruptible button colors. */
public final class EditorPanelMotion {
    private ViewGroup root;
    private Bitmap snapshot;
    private BitmapDrawable overlay;
    private ValueAnimator animator;
    private ViewTreeObserver.OnPreDrawListener preDraw;
    private long generation;
    private final ArrayList<View> incoming = new ArrayList<>();
    private final IdentityHashMap<View, Boolean> enabled = new IdentityHashMap<>();
    private final IdentityHashMap<Button, ValueAnimator> buttons = new IdentityHashMap<>();
    private Runnable complete;

    public boolean isTransitioning() { return snapshot != null; }

    public void capture(ViewGroup root, List<View> panels) {
        Bitmap next = null;
        if (EditorMotion.enabled() && root.getWidth() > 0 && root.getHeight() > 0) {
            try {
                float scale = Math.min(1, (float) Math.sqrt(EditorMotion.PANEL_PIXELS / ((double) root.getWidth() * root.getHeight())));
                next = Bitmap.createBitmap(Math.max(1, Math.round(root.getWidth() * scale)),
                        Math.max(1, Math.round(root.getHeight() * scale)), Bitmap.Config.ARGB_8888);
                Canvas canvas = new Canvas(next); canvas.scale(scale, scale);
                int[] origin = new int[2], position = new int[2]; root.getLocationInWindow(origin);
                for (View panel : panels) if (panel != null && panel.getVisibility() == View.VISIBLE && panel.getWidth() > 0) {
                    panel.getLocationInWindow(position); int save = canvas.save();
                    canvas.translate(position[0] - origin[0], position[1] - origin[1]);
                    int alpha = canvas.saveLayerAlpha(0, 0, panel.getWidth(), panel.getHeight(), Math.round(255 * panel.getAlpha()));
                    // Direct draw() bypasses the parent's viewport clipping and scroll offset.
                    // Capture only what is visible, not the entire scrolled tool catalog.
                    canvas.clipRect(0, 0, panel.getWidth(), panel.getHeight());
                    canvas.translate(-panel.getScrollX(), -panel.getScrollY());
                    panel.draw(canvas); canvas.restoreToCount(alpha); canvas.restoreToCount(save);
                }
                if (overlay != null) overlay.draw(canvas);
            } catch (RuntimeException | OutOfMemoryError e) { if (next != null) next.recycle(); next = null; }
        }
        finish(); this.root = root; snapshot = next;
    }
    public void enter(List<View> panels, Runnable complete) {
        if (snapshot == null || !EditorMotion.enabled()) { finish(); complete.run(); return; }
        this.complete = complete;
        for (View panel : panels) if (panel != null && panel.getVisibility() == View.VISIBLE) {
            incoming.add(panel); panel.setAlpha(0); panel.setTranslationY(dp(16)); lock(panel);
        }
        overlay = new BitmapDrawable(root.getResources(), snapshot); overlay.setBounds(0, 0, root.getWidth(), root.getHeight());
        root.getOverlay().add(overlay);
        long token = ++generation;
        preDraw = () -> {
            removePreDraw();
            if (token != generation) return true;
            animator = ValueAnimator.ofFloat(0, 1); animator.setDuration(EditorMotion.PANELS); animator.setInterpolator(EditorMotion.GEOMETRY);
            animator.addUpdateListener(a -> {
                if (token != generation) return;
                float fraction = (float) a.getAnimatedValue(); overlay.setAlpha(Math.round(255 * (1 - fraction)));
                overlay.setBounds(0, Math.round(dp(16) * fraction), root.getWidth(), root.getHeight() + Math.round(dp(16) * fraction));
                for (View panel : incoming) { panel.setAlpha(fraction); panel.setTranslationY(dp(16) * (1 - fraction)); }
            });
            animator.addListener(new AnimatorListenerAdapter() {
                @Override public void onAnimationEnd(Animator animation) { if (token == generation) finish(); }
            }); animator.start(); return true;
        }; root.getViewTreeObserver().addOnPreDrawListener(preDraw); root.invalidate();
    }
    private float dp(float value) { return value * root.getResources().getDisplayMetrics().density; }
    private void lock(View view) {
        enabled.put(view, view.isEnabled()); view.setEnabled(false);
        if (view instanceof ViewGroup) for (int i = 0; i < ((ViewGroup) view).getChildCount(); i++) lock(((ViewGroup) view).getChildAt(i));
    }
    private void removePreDraw() {
        if (preDraw != null) { if (root.getViewTreeObserver().isAlive()) root.getViewTreeObserver().removeOnPreDrawListener(preDraw); preDraw = null; }
    }
    public void finish() {
        ++generation; removePreDraw();
        if (animator != null) { ValueAnimator old = animator; animator = null; old.cancel(); old.removeAllListeners(); old.removeAllUpdateListeners(); }
        if (overlay != null) { root.getOverlay().remove(overlay); overlay = null; }
        if (snapshot != null) { snapshot.recycle(); snapshot = null; }
        for (View view : incoming) { view.setAlpha(1); view.setTranslationY(0); }
        incoming.clear();
        for (Map.Entry<View, Boolean> entry : enabled.entrySet()) entry.getKey().setEnabled(entry.getValue());
        enabled.clear(); Runnable callback = complete; complete = null; if (callback != null) callback.run();
    }
    public void highlight(Button button, int background, int foreground) {
        // The editor owns both colors; inherited widget tints must not recolor either layer.
        button.setBackgroundTintList(null);
        button.setCompoundDrawableTintList(null);
        ValueAnimator previous = buttons.remove(button);
        if (previous != null) { previous.cancel(); previous.removeAllListeners(); previous.removeAllUpdateListeners(); }
        int fromText = button.getCurrentTextColor(), fromBackground = EditorPalette.SECONDARY;
        Drawable existing = button.getBackground();
        if (existing instanceof RippleDrawable && ((RippleDrawable) existing).getDrawable(0) instanceof GradientDrawable) {
            ColorStateList colors = ((GradientDrawable) ((RippleDrawable) existing).getDrawable(0)).getColor();
            if (colors != null) fromBackground = colors.getDefaultColor();
        }
        GradientDrawable surface = new GradientDrawable(); surface.setCornerRadius(22 * button.getResources().getDisplayMetrics().density);
        button.setBackground(new RippleDrawable(ColorStateList.valueOf(0x30ffffff), surface, null));
        ArgbEvaluator colors = new ArgbEvaluator(); final int startBackground = fromBackground;
        java.util.function.Consumer<Float> update = fraction -> {
            surface.setColor((int) colors.evaluate(fraction, startBackground, background));
            int text = (int) colors.evaluate(fraction, fromText, foreground);
            ColorStateList ink = new ColorStateList(new int[][] {{-android.R.attr.state_enabled}, {}},
                    new int[] {(text & 0x00ffffff) | 0x66000000, text});
            button.setTextColor(ink);
            for (Drawable icon : button.getCompoundDrawables()) if (icon != null) icon.setTintList(ink);
        };
        if (!button.isLaidOut() || !EditorMotion.enabled()) { update.accept(1f); return; }
        ValueAnimator animation = ValueAnimator.ofFloat(0, 1); buttons.put(button, animation);
        animation.setDuration(EditorMotion.SELECTION); animation.setInterpolator(EditorMotion.FADE);
        animation.addUpdateListener(a -> update.accept((float) a.getAnimatedValue()));
        animation.addListener(new AnimatorListenerAdapter() {
            @Override public void onAnimationEnd(Animator animator) { if (buttons.get(button) == animator) { update.accept(1f); buttons.remove(button); } }
        }); animation.start();
    }
    public void release() {
        finish();
        for (ValueAnimator animation : new ArrayList<>(buttons.values())) animation.end();
        buttons.clear(); root = null;
    }
}
