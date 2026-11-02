package com.mishiranu.dashchan.widget;

import android.content.Context;
import android.graphics.Insets;
import android.view.Gravity;
import android.view.View;
import android.view.WindowInsets;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import com.mishiranu.dashchan.R;
import com.mishiranu.dashchan.content.Preferences;
import com.mishiranu.dashchan.util.ResourceUtils;
import com.mishiranu.dashchan.util.SharedPreferences;

/** Presentation fixture as well as the real WindowManager content. Never reads/writes showcase state. */
public final class GalleryShowcaseView extends FrameLayout {
    private final ScrollView scroll;
    private final LinearLayout column;
    private final Boolean visualOverride;
    private Insets safe = Insets.NONE;
    private final SharedPreferences.Listener preferences = key -> {
        if (key == null || Preferences.KEY_NEW_INTERFACE_MOTION.equals(key) || key.contains("theme") || key.contains("font")) {
            refreshAppearance(); requestLayout();
        }
    };
    public GalleryShowcaseView(Context context, Runnable confirm) {
        this(context, confirm, null);
    }
    /** Detached presentation fixture; does not alter the user's showcase or appearance preference. */
    GalleryShowcaseView(Context context, Runnable confirm, boolean modern) { this(context, confirm, Boolean.valueOf(modern)); }
    private GalleryShowcaseView(Context context, Runnable confirm, Boolean visualOverride) {
        super(context);
        this.visualOverride = visualOverride;
        setBackgroundColor(context.getColor(R.color.standard_media_showcase_scrim));
        scroll = new ScrollView(context); scroll.setFillViewport(false);
        addView(scroll, new LayoutParams(-2, -2, Gravity.CENTER));
        column = new LinearLayout(context); column.setOrientation(LinearLayout.VERTICAL);
        scroll.addView(column, new ScrollView.LayoutParams(-1, -2));
        Button button = new Button(context, null, android.R.attr.borderlessButtonStyle);
        ThemeEngine.applyStyle(button); button.setText(R.string.got_it);
        button.setMinimumWidth(0); button.setMinWidth(0);
        float density = getResources().getDisplayMetrics().density;
        int left = button.getPaddingLeft(), right = button.getPaddingRight(), top = button.getPaddingTop();
        int bottom = Math.max(0, (int) (24f * density) - top);
        int[] titles = {R.string.context_menu, R.string.gallery};
        int[] messages = {R.string.context_menu_description__sentence, R.string.gallery_description__sentence};
        for (int i = 0; i < titles.length; i++) {
            TextView title = new TextView(context, null, android.R.attr.textAppearanceLarge);
            ThemeEngine.applyStyle(title); title.setText(titles[i]); title.setTypeface(ResourceUtils.TYPEFACE_LIGHT);
            title.setPadding(left, top, right, (int) (4 * density)); title.setAccessibilityHeading(true);
            TextView body = new TextView(context, null, android.R.attr.textAppearanceSmall);
            ThemeEngine.applyStyle(body); body.setText(messages[i]); body.setPadding(left, 0, right, bottom);
            column.addView(title, -1, -2); column.addView(body, -1, -2);
            InterfaceAppearance.mediaHeading(title); InterfaceAppearance.mediaBody(body);
        }
        column.addView(button, -2, -2); InterfaceAppearance.mediaAction(button);
        button.setOnClickListener(v -> confirm.run());
        setOnApplyWindowInsetsListener((v, insets) -> {
            safe = insets.getInsetsIgnoringVisibility(WindowInsets.Type.systemBars() | WindowInsets.Type.displayCutout());
            requestLayout(); return insets;
        });
        refreshAppearance();
    }
    private void refreshAppearance() {
        if (visualOverride == null) InterfaceAppearance.refreshTree(this);
        else resolve(this, visualOverride);
        boolean modern = visualOverride != null ? visualOverride : InterfaceAppearance.isEnabled(getContext());
        int inset = modern ? StandardAppearance.dp(this, AppearanceShapes.SPACE_24) : 0;
        column.setPadding(inset, modern ? StandardAppearance.dp(this, 16) : 0, inset, modern ? StandardAppearance.dp(this, 16) : 0);
        for (int i = 0; i < column.getChildCount() - 1; i++) if (modern) {
            column.getChildAt(i).setPaddingRelative(0, i > 0 && i % 2 == 0 ? StandardAppearance.dp(this, 16) : 0,
                    0, StandardAppearance.dp(this, i % 2 == 0 ? 8 : 16));
        }
        LinearLayout.LayoutParams action = (LinearLayout.LayoutParams) column.getChildAt(column.getChildCount() - 1).getLayoutParams();
        action.gravity = modern ? Gravity.END : Gravity.NO_GRAVITY;
    }
    private static void resolve(View view, boolean modern) {
        MediaContentAppearance.resolve(view, modern);
        if (view instanceof android.view.ViewGroup) {
            android.view.ViewGroup group = (android.view.ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) resolve(group.getChildAt(i), modern);
        }
    }
    @Override protected void onMeasure(int widthSpec, int heightSpec) {
        boolean modern = visualOverride != null ? visualOverride : InterfaceAppearance.isEnabled(getContext());
        int width = MeasureSpec.getSize(widthSpec), height = MeasureSpec.getSize(heightSpec);
        int gap = modern ? StandardAppearance.dp(this, 24) : 0;
        setPadding(modern ? safe.left : 0, modern ? safe.top : 0, modern ? safe.right : 0, modern ? safe.bottom : 0);
        int availableWidth = Math.max(1, width - getPaddingLeft() - getPaddingRight() - 2 * gap);
        int availableHeight = Math.max(1, height - getPaddingTop() - getPaddingBottom() - 2 * gap);
        scroll.getLayoutParams().width = Math.min(availableWidth, StandardAppearance.dp(this, modern ? 560 : 304));
        // A bounded ScrollView keeps the action reachable at large fontScale and in landscape.
        scroll.measure(MeasureSpec.makeMeasureSpec(scroll.getLayoutParams().width, MeasureSpec.EXACTLY),
                MeasureSpec.makeMeasureSpec(availableHeight, MeasureSpec.AT_MOST));
        setMeasuredDimension(resolveSize(width, widthSpec), resolveSize(height, heightSpec));
    }
    @Override protected void onAttachedToWindow() {
        super.onAttachedToWindow(); if (visualOverride == null) Preferences.PREFERENCES.register(preferences); requestApplyInsets();
        refreshAppearance();
    }
    @Override protected void onDetachedFromWindow() {
        if (visualOverride == null) Preferences.PREFERENCES.unregister(preferences); super.onDetachedFromWindow();
    }
}
