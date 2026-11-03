package com.mishiranu.dashchan.widget;

import android.app.AlertDialog;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.ColorFilter;
import android.graphics.drawable.RippleDrawable;
import android.graphics.drawable.GradientDrawable;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.SeekBar;
import android.widget.TextView;
import androidx.core.view.OneShotPreDrawListener;
import com.mishiranu.dashchan.R;

/** Opt-in native media chrome only. Does not traverse images, editor canvas or HTML. */
final class MediaContentAppearance {
    enum Role {CAPTION, HEADING, BODY, ACTION, PANEL, WINDOW, TOOLBAR, ICON, QUICK, SLIDER, FOOTER, COLOR_CHIP}
    private MediaContentAppearance() {}
    static AppearanceColors colors(View view) {
        android.content.Context c = view.getContext();
        return AppearanceColors.standardInterface(true, c.getColor(R.color.standard_media_window),
                ThemeEngine.getTheme(c).accent, c.getColor(R.color.standard_media_on_surface),
                c.getColor(R.color.standard_media_error));
    }
    static void apply(View view, Role role) {
        Object tag = view.getTag(R.id.standard_media_appearance);
        State state = tag instanceof State ? (State) tag : new State(view);
        state.role = role; view.setTag(R.id.standard_media_appearance, state); state.apply();
    }
    static void colorChip(View view, int color, boolean selected) {
        Object tag = view.getTag(R.id.standard_media_appearance);
        State state = tag instanceof State ? (State) tag : new State(view);
        state.role = Role.COLOR_CHIP; state.chipColor = color; state.chipSelected = selected;
        view.setTag(R.id.standard_media_appearance, state); state.apply();
    }
    private static GradientDrawable chip(View view, int color, boolean selected, int outline) {
        float density = com.mishiranu.dashchan.util.ResourceUtils.obtainDensity(view);
        GradientDrawable drawable = new GradientDrawable(); drawable.setColor(color);
        drawable.setCornerRadius(8f * density);
        drawable.setStroke((int) ((selected ? 3f : 1f) * density), outline);
        return drawable;
    }
    static void refresh(View view) {
        Object tag = view.getTag(R.id.standard_media_appearance);
        if (tag instanceof State) ((State) tag).apply();
    }
    /** Same resolved profile seam as the existing standard appearance fixtures; no preference writes. */
    static void resolve(View view, boolean enabled) {
        Object tag = view.getTag(R.id.standard_media_appearance);
        if (tag instanceof State) ((State) tag).apply(enabled);
    }
    private static final class State {
        final View view;
        final StandardAppearance.Saved saved;
        final AppearanceTypography.Snapshot type;
        final ColorStateList text, image;
        final StandardSlider.Snapshot slider;
        final ColorFilter filter;
        final float alpha;
        final int width;
        Role role;
        int chipColor;
        boolean chipSelected;
        State(View view) {
            this.view = view; saved = new StandardAppearance.Saved(view);
            type = view instanceof TextView ? new AppearanceTypography.Snapshot((TextView) view) : null;
            text = view instanceof TextView ? ((TextView) view).getTextColors() : null;
            image = view instanceof ImageView ? ((ImageView) view).getImageTintList() : null;
            filter = view instanceof ImageView ? ((ImageView) view).getColorFilter() : null;
            slider = view instanceof SeekBar ? new StandardSlider.Snapshot((SeekBar) view) : null;
            alpha = view.getAlpha(); width = view.getLayoutParams() != null ? view.getLayoutParams().width : 0;
        }
        void apply() {
            apply(InterfaceAppearance.isEnabled(view.getContext()));
        }
        void apply(boolean enabled) {
            saved.restore(); saved.restoreHeight(); saved.restoreTextBounds();
            if (type != null) { type.restore((TextView) view); ((TextView) view).setTextColor(text); }
            if (view instanceof ImageView) { ((ImageView) view).setColorFilter(filter); ((ImageView) view).setImageTintList(image); }
            if (slider != null) slider.restore((SeekBar) view);
            if (role == Role.FOOTER) view.setAlpha(alpha);
            if (role == Role.ICON && view.getLayoutParams() != null) view.getLayoutParams().width = width;
            if (!enabled) {
                if (role == Role.COLOR_CHIP) view.setBackground(chip(view, chipColor, chipSelected,
                        chipSelected ? 0xff64b5f6 : 0xff808080));
                view.requestLayout(); return;
            }
            AppearanceColors c = role == Role.QUICK || role == Role.FOOTER ? StandardAppearance.colors(view.getContext()) : colors(view);
            int surface = role == Role.QUICK ? c.surfaceContainer : view.getContext().getColor(R.color.standard_media_surface);
            switch (role) {
                case PANEL: case TOOLBAR: case WINDOW:
                    view.setBackgroundTintList(null);
                    view.setBackgroundColor(role == Role.WINDOW ? c.window : surface);
                    if (role == Role.TOOLBAR) {
                        view.setMinimumHeight(StandardAppearance.dp(view, 56));
                        if (view.getLayoutParams() != null) view.getLayoutParams().height = ViewGroup.LayoutParams.WRAP_CONTENT;
                    }
                    break;
                case ICON: case QUICK: case ACTION:
                    view.setBackgroundTintList(null);
                    view.setBackground(new RippleDrawable(new ColorStateList(new int[][]{{-android.R.attr.state_enabled}, {}}, new int[]{Color.TRANSPARENT, c.ripple}),
                            role == Role.QUICK ? StandardAppearance.shape(view, surface, AppearanceShapes.MEDIUM) : null,
                            StandardAppearance.shape(view, Color.WHITE, AppearanceShapes.MEDIUM)));
                    if (view instanceof ImageView) {
                        ((ImageView) view).clearColorFilter();
                        ((ImageView) view).setImageTintList(StandardAppearance.textColors(c.onSurface, surface));
                    }
                    if (role != Role.QUICK) {
                        int min = StandardAppearance.dp(view, AppearanceShapes.STANDARD_TARGET);
                        view.setMinimumWidth(min); view.setMinimumHeight(min);
                        if (role == Role.ICON && width > 0 && view.getLayoutParams() != null) view.getLayoutParams().width = Math.max(width, min);
                        if (role == Role.ICON && view instanceof ImageView && width > 0 && width < min) {
                            // Enlarge the target, retaining the original compact drawable viewport.
                            int inset = Math.max(0, (min - width + saved.start + saved.end) / 2);
                            view.setPaddingRelative(Math.max(saved.start, inset), Math.max(saved.top, inset),
                                    Math.max(saved.end, inset), Math.max(saved.bottom, inset));
                        }
                    }
                    if (role == Role.ACTION && view instanceof TextView) {
                        TextView label = (TextView) view;
                        AppearanceTypography.apply(label, AppearanceTypography.Role.LABEL_LARGE);
                        label.setTextColor(StandardAppearance.textColors(InterfaceAppearance.contrasting(c.primary, surface, 4.5), surface));
                        label.setSingleLine(false); label.setMaxLines(Integer.MAX_VALUE);
                        label.setPaddingRelative(StandardAppearance.dp(view, 12), StandardAppearance.dp(view, 8),
                                StandardAppearance.dp(view, 12), StandardAppearance.dp(view, 8));
                    }
                    break;
                case SLIDER:
                    StandardSlider.apply((SeekBar) view,
                            StandardAppearance.textColors(com.mishiranu.dashchan.util.OpaqueColors.blend(surface, c.onSurface, .16f), surface),
                            StandardAppearance.textColors(c.primary, surface),
                            StandardAppearance.textColors(InterfaceAppearance.contrasting(c.primary, surface, 3), surface), saved.minHeight);
                    break;
                case COLOR_CHIP:
                    view.setBackground(chip(view, chipColor, chipSelected, chipSelected ? c.primary : c.outline));
                    break;
                default:
                    TextView label = (TextView) view;
                    AppearanceTypography.apply(label, role == Role.HEADING ? AppearanceTypography.Role.TITLE_LARGE
                            : role == Role.CAPTION || role == Role.FOOTER ? AppearanceTypography.Role.BODY_SMALL : AppearanceTypography.Role.BODY_MEDIUM);
                    label.setTextColor(StandardAppearance.textColors(role == Role.FOOTER ? c.onSurfaceVariant : c.onSurface, surface));
                    if (role == Role.CAPTION) label.setBackgroundColor(view.getContext().getColor(R.color.standard_media_caption_scrim));
                    if (role == Role.FOOTER) view.setAlpha(1f);
                    break;
            }
            view.requestLayout();
        }
    }
    static void dialog(AlertDialog dialog) {
        View decor = dialog.getWindow().getDecorView();
        decor.addOnAttachStateChangeListener(new View.OnAttachStateChangeListener() {
            OneShotPreDrawListener pending;
            @Override public void onViewAttachedToWindow(View v) {
                pending = OneShotPreDrawListener.add(v, () -> {
                    pending = null;
                    if (!dialog.isShowing()) return;
                    dialog.getWindow().setBackgroundDrawable(StandardAppearance.shape(v,
                            v.getContext().getColor(R.color.standard_media_surface), AppearanceShapes.EXTRA_LARGE));
                    TextView message = dialog.findViewById(android.R.id.message);
                    if (message != null) apply(message, Role.BODY);
                    for (int which : new int[]{AlertDialog.BUTTON_POSITIVE, AlertDialog.BUTTON_NEGATIVE, AlertDialog.BUTTON_NEUTRAL}) {
                        if (dialog.getButton(which) != null) apply(dialog.getButton(which), Role.ACTION);
                    }
                    int available = com.mishiranu.dashchan.util.ViewUtils.getWindowContentSize(v.getContext()).x;
                    dialog.getWindow().setLayout(Math.min(StandardAppearance.dp(v, 560), Math.max(1, available - StandardAppearance.dp(v, 48))), -2);
                });
            }
            @Override public void onViewDetachedFromWindow(View v) { if (pending != null) { pending.removeListener(); pending = null; } }
        });
    }
}
