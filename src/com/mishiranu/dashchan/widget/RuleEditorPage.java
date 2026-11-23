package com.mishiranu.dashchan.widget;

import android.app.Dialog;
import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.graphics.drawable.StateListDrawable;
import android.text.InputType;
import android.view.ContextThemeWrapper;
import android.view.Gravity;
import android.view.View;
import android.view.Window;
import android.view.WindowInsetsController;
import android.view.WindowManager;
import android.view.inputmethod.EditorInfo;
import android.widget.*;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowCompat;
import androidx.core.view.WindowInsetsCompat;
import com.google.android.material.chip.Chip;
import com.google.android.material.chip.ChipGroup;
import com.mishiranu.dashchan.R;
import com.mishiranu.dashchan.content.security.SecureDialog;
import com.mishiranu.dashchan.util.ViewUtils;

/** A full-window presentation of the existing regex rule; storage stays in AutohideFragment. */
public final class RuleEditorPage {
    private final Context host, material;
    private final boolean dark;
    private final AppearanceColors colors;
    private final LinearLayout root, body, scopeBox, testBox;
    private final TextView sections, hideModeValue;
    private final LinearLayout hideModeField;
    private final ImageView scopeButton, testButton;
    public final ScrollView scroll;
    public final Button save;
    public Runnable onTestVisibilityChanged;
    private static final int MATCH = -1, WRAP = -2;

    public RuleEditorPage(Context host, boolean editing, Runnable back, Runnable commit, Runnable remove) {
        this.host = host;
        dark = ThemeEngine.getTheme(host).base == ThemeEngine.Theme.Base.DARK;
        material = new ContextThemeWrapper(host, dark ? R.style.Theme_Slooop_RuleEditor_Dark : R.style.Theme_Slooop_RuleEditor_Light);
        colors = StandardAppearance.colors(host);
        root = column(); root.setFocusableInTouchMode(true); root.setBackgroundColor(colors.surface);
        LinearLayout bar = new LinearLayout(host); bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setBackgroundColor(colors.surfaceContainer); bar.setMinimumHeight(dp(64));
        root.addView(bar, MATCH, WRAP);
        ImageButton up = new ImageButton(host, null, android.R.attr.borderlessButtonStyle);
        up.setImageResource(R.drawable.ic_arrow_back); up.setImageTintList(ColorStateList.valueOf(colors.onSurface));
        up.setContentDescription(host.getString(R.string.close));
        if (root.getResources().getConfiguration().getLayoutDirection() == View.LAYOUT_DIRECTION_RTL) up.setScaleX(-1);
        up.setOnClickListener(v -> back.run()); bar.addView(up, dp(48), dp(48));
        TextView title = text(editing ? R.string.rule_edit_title : R.string.new_rule, AppearanceTypography.Role.TITLE_LARGE);
        title.setAccessibilityHeading(true);
        bar.addView(title, new LinearLayout.LayoutParams(0, WRAP, 1));
        save = action(R.string.save, commit); bar.addView(save, WRAP, WRAP);
        scroll = new ScrollView(host); scroll.setFillViewport(false);
        root.addView(scroll, new LinearLayout.LayoutParams(MATCH, 0, 1));
        body = column(); body.setPaddingRelative(dp(20), dp(16), dp(20), dp(24));
        scroll.addView(body, MATCH, WRAP);
        field(body, R.id.value, R.string.regular_expression, false, false);
        TextView error = text(0, AppearanceTypography.Role.BODY_SMALL); error.setId(R.id.error_text);
        error.setTextColor(colors.error); error.setVisibility(View.GONE); error.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
        body.addView(error, MATCH, WRAP);
        heading(R.string.rule_where);
        ChipGroup fields = group(); chip(fields, R.id.autohide_subject, R.string.subject);
        chip(fields, R.id.autohide_comment, R.string.comment); chip(fields, R.id.autohide_name, R.string.name);
        chip(fields, R.id.autohide_file_name, R.string.rule_filename);
        heading(R.string.rule_which);
        ChipGroup posts = group(); chip(posts, R.id.autohide_original_post, R.string.rule_op);
        chip(posts, R.id.autohide_replies, R.string.rule_replies);
        CheckBox sage = new CheckBox(host); sage.setId(R.id.autohide_sage); sage.setText(R.string.rule_sage);
        AppearanceTypography.apply(sage, AppearanceTypography.Role.BODY_LARGE);
        sage.setButtonTintList(states(InterfaceAppearance.contrasting(colors.primary, colors.surface, 3), colors.outline));
        sage.setMinHeight(dp(48)); sage.setTextColor(colors.onSurface); body.addView(sage, MATCH, WRAP);
        hideModeValue = text(0, AppearanceTypography.Role.TITLE_MEDIUM);
        hideModeField = addHideModeField(hideModeValue);
        LinearLayout scopeHead = column(); scopeHead.setMinimumHeight(dp(64)); scopeHead.setGravity(Gravity.CENTER_VERTICAL);
        ViewUtils.setSelectableItemBackground(scopeHead); scopeHead.setFocusable(true);
        scopeButton = disclosureLabel(scopeHead, R.string.rule_scope);
        sections = text(0, AppearanceTypography.Role.BODY_MEDIUM); sections.setTextColor(colors.onSurfaceVariant);
        scopeHead.addView(sections, MATCH, WRAP); body.addView(scopeHead, MATCH, WRAP);
        scopeBox = column(); body.addView(scopeBox, MATCH, WRAP);
        TextView forums = text(R.string.all_forums, AppearanceTypography.Role.LABEL_LARGE); forums.setId(R.id.chan_name);
        forums.setMinimumHeight(dp(48)); forums.setGravity(Gravity.CENTER_VERTICAL); forums.setFocusable(true);
        ViewUtils.setSelectableItemBackground(forums); scopeBox.addView(forums, MATCH, WRAP);
        field(scopeBox, R.id.board_name, R.string.board, false, true);
        field(scopeBox, R.id.thread_number, R.string.rule_thread_number, true, true);
        scopeHead.setOnClickListener(v -> expandScope(!scopeOpen()));
        divider();
        LinearLayout testHead = column(); testHead.setGravity(Gravity.CENTER_VERTICAL); testHead.setMinimumHeight(dp(56));
        ViewUtils.setSelectableItemBackground(testHead); testHead.setFocusable(true);
        testButton = disclosureLabel(testHead, R.string.rule_try); body.addView(testHead, MATCH, WRAP);
        testBox = column(); body.addView(testBox, MATCH, WRAP);
        field(testBox, R.id.test_string, R.string.test_string, false, false);
        TextView result = text(0, AppearanceTypography.Role.BODY_MEDIUM); result.setId(R.id.matcher_result);
        result.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE); testBox.addView(result, MATCH, WRAP);
        testHead.setOnClickListener(v -> expandTest(!testOpen()));
        divider();
        if (editing) {
            Button delete = action(R.string.delete, remove); delete.setTextColor(colors.error);
            body.addView(delete, WRAP, WRAP);
        }
        expandScope(false); expandTest(false); root.setFocusable(true); root.requestFocus();
    }
    public Dialog dialog() {
        Dialog dialog = new SecureDialog(host, dark ? android.R.style.Theme_Material_NoActionBar : android.R.style.Theme_Material_Light_NoActionBar);
        Window window = dialog.getWindow();
        window.setBackgroundDrawable(new ColorDrawable(colors.surface));
        window.setDimAmount(0);
        window.getDecorView();
        boolean enforceNavigationContrast = window.isNavigationBarContrastEnforced();
        WindowCompat.enableEdgeToEdge(window);
        // Keep the theme's contrast policy for three-button navigation.
        window.setNavigationBarContrastEnforced(enforceNavigationContrast);
        root.addOnAttachStateChangeListener(new View.OnAttachStateChangeListener() {
            @Override
            public void onViewAttachedToWindow(View view) {
                view.removeOnAttachStateChangeListener(this);
                WindowInsetsController controller = view.getWindowInsetsController();
                if (controller != null) {
                    int mask = WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS
                            | WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS;
                    controller.setSystemBarsAppearance(dark ? 0 : mask, mask);
                }
            }

            @Override
            public void onViewDetachedFromWindow(View view) {}
        });
        ViewCompat.setOnApplyWindowInsetsListener(root, (view, insets) -> {
            Insets bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() | WindowInsetsCompat.Type.displayCutout());
            int bottom = Math.max(bars.bottom, insets.getInsets(WindowInsetsCompat.Type.ime()).bottom);
            view.setPadding(bars.left, bars.top, bars.right, bottom);
            return insets;
        });
        dialog.setContentView(root);
        dialog.setCanceledOnTouchOutside(false);
        window.setLayout(MATCH, MATCH);
        // The root's insets listener owns the space for the IME.
        window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_NOTHING
                | WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN);
        return dialog;
    }
    public boolean scopeOpen() { return scopeBox.getVisibility() == View.VISIBLE; }
    public boolean testOpen() { return testBox.getVisibility() == View.VISIBLE; }
    public void expandScope(boolean open) { disclosure(scopeBox, scopeButton, open); }
    public void expandTest(boolean open) { disclosure(testBox, testButton, open); if (onTestVisibilityChanged != null) onTestVisibilityChanged.run(); }
    private void disclosure(LinearLayout box, ImageView arrow, boolean open) {
        box.setVisibility(open ? View.VISIBLE : View.GONE); arrow.setRotation(open ? 180 : 0);
        ((View) ((View) arrow.getParent()).getParent()).setStateDescription(host.getString(open ? R.string.rule_expanded : R.string.rule_collapsed));
    }
    public void summary(CharSequence forum, CharSequence board, CharSequence thread) {
        String value = forum.toString();
        if (board.length() != 0) value += " · /" + board + "/";
        if (thread.length() != 0) value += " · №" + thread;
        sections.setText(value);
    }
    private int dp(float v) { return Math.round(host.getResources().getDisplayMetrics().density * v); }
    private LinearLayout column() { LinearLayout v = new LinearLayout(host); v.setOrientation(LinearLayout.VERTICAL); return v; }
    private TextView text(int label, AppearanceTypography.Role role) {
        TextView v = new TextView(host); if (label != 0) v.setText(label); AppearanceTypography.apply(v, role);
        v.setTextColor(colors.onSurface); return v;
    }
    private Button action(int label, Runnable run) {
        Button v = new Button(host, null, android.R.attr.borderlessButtonStyle);
        AppearanceTypography.apply(v, AppearanceTypography.Role.LABEL_LARGE); v.setText(label);
        v.setTextColor(new ColorStateList(new int[][]{{-android.R.attr.state_enabled},{}},
                new int[]{colors.disabledContent, InterfaceAppearance.contrasting(colors.primary, colors.surfaceContainer, 4.5)}));
        v.setMinHeight(dp(48)); v.setOnClickListener(w -> run.run()); return v;
    }
    private LinearLayout addHideModeField(TextView value) {
        LinearLayout field = new LinearLayout(host) {
            @Override public CharSequence getAccessibilityClassName() { return Button.class.getName(); }
        };
        field.setId(R.id.autohide_mode);
        field.setGravity(Gravity.CENTER_VERTICAL);
        field.setMinimumHeight(dp(AppearanceShapes.SETTING_MIN_HEIGHT));
        field.setFocusable(true);
        field.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_YES);
        field.setContentDescription(host.getText(R.string.autohide_mode));
        int surface = colors.surfaceContainer;
        field.setBackground(new RippleDrawable(ColorStateList.valueOf(colors.ripple),
                StandardAppearance.shape(field, surface, AppearanceShapes.MEDIUM), null));
        field.setPaddingRelative(dp(16), dp(8), dp(12), dp(8));

        LinearLayout labels = column();
        labels.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS);
        TextView caption = text(R.string.autohide_mode, AppearanceTypography.Role.BODY_SMALL);
        caption.setIncludeFontPadding(false);
        caption.setTextColor(InterfaceAppearance.contrasting(colors.onSurfaceVariant, surface, 4.5));
        value.setIncludeFontPadding(false);
        value.setTextColor(InterfaceAppearance.contrasting(colors.onSurface, surface, 4.5));
        labels.addView(caption, MATCH, WRAP);
        labels.addView(value, MATCH, WRAP);
        LinearLayout.LayoutParams labelParams = new LinearLayout.LayoutParams(0, WRAP, 1f);
        labelParams.setMarginEnd(dp(8));
        field.addView(labels, labelParams);

        ImageView caret = new ImageView(host);
        caret.setImageResource(R.drawable.ic_arrow_drop_down);
        caret.setImageTintList(ColorStateList.valueOf(InterfaceAppearance.contrasting(colors.primary, surface, 3)));
        caret.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        field.addView(caret, dp(24), dp(24));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(MATCH, WRAP);
        lp.topMargin = dp(4); lp.bottomMargin = dp(8);
        body.addView(field, lp);
        return field;
    }
    public void setHideModeLabel(int label) {
        hideModeValue.setText(label);
        hideModeField.setStateDescription(hideModeValue.getText());
    }
    private void heading(int label) {
        TextView v = text(label, AppearanceTypography.Role.TITLE_MEDIUM); v.setAccessibilityHeading(true);
        v.setPaddingRelative(0, dp(16), 0, dp(4)); body.addView(v, MATCH, WRAP);
    }
    private ChipGroup group() {
        ChipGroup g = new ChipGroup(material); g.setSingleSelection(false); g.setSelectionRequired(false);
        g.setSingleLine(false); g.setChipSpacingHorizontal(dp(6)); g.setChipSpacingVertical(0);
        body.addView(g, MATCH, WRAP); return g;
    }
    private void chip(ChipGroup g, int id, int label) {
        Chip v = new Chip(material); v.setId(id); v.setText(label); v.setCheckable(true);
        v.setCheckedIconResource(R.drawable.ic_editor_done); v.setCheckedIconVisible(true); v.setEnsureMinTouchTargetSize(true); v.ensureAccessibleTouchTarget(dp(48));
        v.setChipBackgroundColor(states(colors.primaryContainer, colors.surface));
        v.setChipStrokeColor(states(Color.TRANSPARENT, colors.outline)); v.setChipStrokeWidth(dp(1));
        v.setTextColor(states(colors.onPrimaryContainer, colors.onSurface)); v.setCheckedIconTint(ColorStateList.valueOf(colors.onPrimaryContainer));
        AppearanceTypography.apply(v, AppearanceTypography.Role.LABEL_LARGE); g.addView(v);
    }
    private ColorStateList states(int yes, int no) {
        return new ColorStateList(new int[][]{{android.R.attr.state_checked},{}},new int[]{yes,no});
    }
    private void field(LinearLayout box, int id, int hint, boolean numeric, boolean caption) {
        if (caption) { TextView title = text(hint, AppearanceTypography.Role.BODY_SMALL); title.setLabelFor(id);
            title.setPaddingRelative(0, dp(8), 0, dp(4)); box.addView(title, MATCH, WRAP); }
        SafePasteEditText edit = new SafePasteEditText(host); edit.setId(id); edit.setHint(hint);
        edit.setInputType(numeric ? InputType.TYPE_CLASS_NUMBER : InputType.TYPE_CLASS_TEXT);
        edit.setSingleLine(true); edit.setImeOptions(EditorInfo.IME_FLAG_NO_EXTRACT_UI | EditorInfo.IME_FLAG_NO_FULLSCREEN | EditorInfo.IME_ACTION_DONE);
        edit.setMinHeight(dp(56)); edit.setPaddingRelative(dp(12), dp(8), dp(12), dp(8));
        GradientDrawable normal = outline(colors.outline, 1), focus = outline(InterfaceAppearance.contrasting(colors.primary, colors.surface, 3), 2);
        StateListDrawable bg = new StateListDrawable(); bg.addState(new int[]{android.R.attr.state_focused}, focus); bg.addState(new int[]{},normal);
        edit.setBackgroundTintList(null); edit.setBackground(bg);
        edit.setTextColor(colors.onSurface); edit.setHintTextColor(colors.onSurfaceVariant);
        box.addView(edit, MATCH, WRAP);
    }
    private GradientDrawable outline(int stroke, int width) {
        GradientDrawable shape = new GradientDrawable(); shape.setColor(colors.surface); shape.setCornerRadius(dp(5));
        shape.setStroke(dp(width),stroke); return shape;
    }
    private ImageView disclosureLabel(LinearLayout parent, int label) {
        LinearLayout row = new LinearLayout(host); row.setGravity(Gravity.CENTER_VERTICAL); parent.addView(row, MATCH, WRAP);
        row.addView(text(label, AppearanceTypography.Role.TITLE_MEDIUM), new LinearLayout.LayoutParams(0, WRAP, 1));
        ImageView arrow = new ImageView(host); arrow.setImageResource(R.drawable.ic_rule_chevron); arrow.setImageTintList(ColorStateList.valueOf(colors.onSurfaceVariant));
        arrow.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO); row.addView(arrow, dp(24), dp(24));
        return arrow;
    }
    private void divider() {
        View line = new View(host); line.setBackgroundColor(colors.outlineVariant); line.setAlpha(.3f); body.addView(line, MATCH, dp(1));
    }
}
