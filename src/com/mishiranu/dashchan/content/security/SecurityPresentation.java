package com.mishiranu.dashchan.content.security;

import android.app.AlertDialog;
import android.content.Context;
import android.content.res.ColorStateList;
import android.database.DataSetObserver;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.graphics.drawable.StateListDrawable;
import android.util.SparseBooleanArray;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.CheckedTextView;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ListAdapter;
import android.widget.ListView;
import android.widget.TextView;
import android.widget.WrapperListAdapter;
import androidx.core.view.OneShotPreDrawListener;
import com.mishiranu.dashchan.R;

/** Resource/system-font presentation only. No application theme, preferences, stores or authorization callbacks. */
public final class SecurityPresentation {
    private SecurityPresentation() {}
    private static int dp(View view, int value) { return Math.round(value * view.getResources().getDisplayMetrics().density); }
    private static int color(View view, int id) { return view.getContext().getColor(id); }
    private static ColorStateList states(View view, int normal) {
        return new ColorStateList(new int[][]{{-android.R.attr.state_enabled}, {}},
                new int[]{color(view, R.color.slooop_lock_disabled_content), normal});
    }
    private static GradientDrawable shape(View view, int fill, int radius) {
        GradientDrawable result = new GradientDrawable(); result.setColor(fill); result.setCornerRadius(dp(view, radius)); return result;
    }
    public static TextView title(Context context, CharSequence text) {
        TextView view = new TextView(context); view.setText(text);
        view.setTextAppearance(R.style.Text_Security_Presentation_Heading);
        view.setPaddingRelative(dp(view, 24), dp(view, 24), dp(view, 24), dp(view, 16));
        view.setAccessibilityHeading(true); return view;
    }
    public static void message(TextView view) {
        view.setTextAppearance(R.style.Text_Security_Presentation_Body);
        view.setTextColor(states(view, color(view, R.color.slooop_lock_on_background)));
    }
    public static void page(View root) {
        root.setForceDarkAllowed(false); root.setBackgroundColor(color(root, R.color.slooop_lock_background));
    }
    public static void action(Button view, boolean primary) {
        view.setTextAppearance(R.style.Text_Security_Presentation_Action); view.setAllCaps(false);
        view.setSingleLine(false); view.setMaxLines(Integer.MAX_VALUE);
        view.setMinWidth(dp(view, 48)); view.setMinimumWidth(dp(view, 48));
        view.setMinHeight(dp(view, 48)); view.setMinimumHeight(dp(view, 48));
        int normal = color(view, primary ? R.color.slooop_lock_on_primary : R.color.slooop_lock_primary);
        view.setTextColor(states(view, normal)); view.setBackgroundTintList(null);
        GradientDrawable fill = shape(view, Color.TRANSPARENT, 28);
        fill.setColor(new ColorStateList(new int[][]{{-android.R.attr.state_enabled}, {}}, new int[]{
                primary ? color(view, R.color.slooop_lock_disabled_container) : Color.TRANSPARENT,
                primary ? color(view, R.color.slooop_lock_primary) : Color.TRANSPARENT}));
        ColorStateList ripple = new ColorStateList(new int[][]{{-android.R.attr.state_enabled}, {}},
                new int[]{Color.TRANSPARENT, color(view, primary ? R.color.slooop_lock_primary_ripple : R.color.slooop_lock_secondary_ripple)});
        view.setBackground(new RippleDrawable(ripple, fill, shape(view, Color.WHITE, 28)));
        view.setPaddingRelative(dp(view, 16), dp(view, 8), dp(view, 16), dp(view, 8));
        view.setFilterTouchesWhenObscured(true);
    }
    public static void field(EditText view) {
        // TextAppearance changes metrics only; password transformation/input type remain with the owner.
        android.text.method.TransformationMethod transformation = view.getTransformationMethod();
        message(view);
        if (view.getTransformationMethod() != transformation) view.setTransformationMethod(transformation);
        view.setHintTextColor(states(view, color(view, R.color.slooop_lock_secondary_text)));
        StateListDrawable background = new StateListDrawable();
        GradientDrawable focus = shape(view, color(view, R.color.slooop_lock_surface), 12);
        focus.setStroke(dp(view, 2), color(view, R.color.slooop_lock_focus));
        GradientDrawable normal = shape(view, color(view, R.color.slooop_lock_surface), 12);
        normal.setStroke(dp(view, 1), color(view, R.color.slooop_lock_outline));
        background.addState(new int[]{android.R.attr.state_focused}, focus); background.addState(new int[]{}, normal);
        view.setBackgroundTintList(null); view.setBackground(background); view.setMinimumHeight(dp(view, 56));
        view.setPaddingRelative(dp(view, 12), dp(view, 10), dp(view, 12), dp(view, 10));
    }
    public static void addControl(LinearLayout parent, View child) {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-1, -2); params.topMargin = dp(parent, 8);
        parent.addView(child, params);
    }
    static void dialog(AlertDialog dialog) {
        View decor = dialog.getWindow().getDecorView();
        decor.addOnAttachStateChangeListener(new View.OnAttachStateChangeListener() {
            OneShotPreDrawListener pending;
            @Override public void onViewAttachedToWindow(View view) {
                pending = OneShotPreDrawListener.add(view, () -> {
                    pending = null; if (!dialog.isShowing()) return;
                    dialog.getWindow().setBackgroundDrawable(shape(view, color(view, R.color.slooop_lock_surface), 28));
                    TextView body = dialog.findViewById(android.R.id.message); if (body != null) message(body);
                    for (int which : new int[]{AlertDialog.BUTTON_POSITIVE, AlertDialog.BUTTON_NEGATIVE, AlertDialog.BUTTON_NEUTRAL}) {
                        Button button = dialog.getButton(which); if (button != null) action(button, false);
                    }
                    ListView list = dialog.getListView();
                    if (list != null && list.getAdapter() != null && !(list.getAdapter() instanceof ChoiceRows)) {
                        SparseBooleanArray positions = list.getCheckedItemPositions();
                        SparseBooleanArray checked = positions != null ? positions.clone() : new SparseBooleanArray();
                        int choiceMode = list.getChoiceMode();
                        list.setAdapter(new ChoiceRows(list.getAdapter())); list.setChoiceMode(choiceMode);
                        for (int i = 0; i < checked.size(); i++) list.setItemChecked(checked.keyAt(i), checked.valueAt(i));
                    }
                    android.view.WindowMetrics metrics = view.getContext().getSystemService(android.view.WindowManager.class).getCurrentWindowMetrics();
                    android.graphics.Insets safe = metrics.getWindowInsets().getInsetsIgnoringVisibility(
                            android.view.WindowInsets.Type.systemBars() | android.view.WindowInsets.Type.displayCutout());
                    int width = Math.max(1, metrics.getBounds().width() - safe.left - safe.right - dp(view, 48));
                    dialog.getWindow().setLayout(Math.min(dp(view, 560), width), -2);
                    fitActions(dialog, Math.min(dp(view, 560), width));
                });
            }
            @Override public void onViewDetachedFromWindow(View view) { if (pending != null) { pending.removeListener(); pending = null; } }
        });
    }
    private static void fitActions(AlertDialog dialog, int width) {
        Button first = dialog.getButton(AlertDialog.BUTTON_POSITIVE);
        if (first == null || !(first.getParent() instanceof LinearLayout)) return;
        LinearLayout row = (LinearLayout) first.getParent(); float needed = row.getPaddingLeft() + row.getPaddingRight();
        for (int i = 0; i < row.getChildCount(); i++) {
            View child = row.getChildAt(i);
            if (child instanceof Button && child.getVisibility() == View.VISIBLE) {
                Button action = (Button) child;
                needed += action.getPaint().measureText(action.getText().toString()) + action.getPaddingLeft() + action.getPaddingRight() + dp(action, 8);
            }
        }
        if (needed <= width) return;
        row.setOrientation(LinearLayout.VERTICAL);
        for (int i = 0; i < row.getChildCount(); i++) {
            View child = row.getChildAt(i);
            if (child instanceof Button) { LinearLayout.LayoutParams p = (LinearLayout.LayoutParams) child.getLayoutParams(); p.width = -1; p.height = -2; p.weight = 0; }
            else child.setVisibility(View.GONE);
        }
    }
    private static final class ChoiceRows implements WrapperListAdapter {
        final ListAdapter wrapped;
        ChoiceRows(ListAdapter wrapped) { this.wrapped = wrapped; }
        @Override public ListAdapter getWrappedAdapter() { return wrapped; }
        @Override public int getCount() { return wrapped.getCount(); }
        @Override public Object getItem(int p) { return wrapped.getItem(p); }
        @Override public long getItemId(int p) { return wrapped.getItemId(p); }
        @Override public boolean hasStableIds() { return wrapped.hasStableIds(); }
        @Override public int getItemViewType(int p) { return wrapped.getItemViewType(p); }
        @Override public int getViewTypeCount() { return wrapped.getViewTypeCount(); }
        @Override public boolean isEmpty() { return wrapped.isEmpty(); }
        @Override public boolean areAllItemsEnabled() { return wrapped.areAllItemsEnabled(); }
        @Override public boolean isEnabled(int p) { return wrapped.isEnabled(p); }
        @Override public void registerDataSetObserver(DataSetObserver observer) { wrapped.registerDataSetObserver(observer); }
        @Override public void unregisterDataSetObserver(DataSetObserver observer) { wrapped.unregisterDataSetObserver(observer); }
        @Override public View getView(int p, View convert, ViewGroup parent) {
            View view = wrapped.getView(p, convert, parent);
            if (view instanceof TextView) {
                TextView text = (TextView) view; message(text); text.setSingleLine(false); text.setMaxLines(Integer.MAX_VALUE);
                text.setMinHeight(dp(text, 48)); text.setMinimumHeight(dp(text, 48));
                text.setPaddingRelative(dp(text, 24), dp(text, 12), dp(text, 24), dp(text, 12));
                if (text.getLayoutParams() != null) text.getLayoutParams().height = -2;
                if (text instanceof CheckedTextView) ((CheckedTextView) text).setCheckMarkTintList(states(text, color(text, R.color.slooop_lock_primary)));
            }
            return view;
        }
    }
}
