package com.mishiranu.dashchan.widget;

import android.app.Dialog;
import android.content.Context;
import android.content.res.ColorStateList;
import android.view.ContextThemeWrapper;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import androidx.appcompat.app.AlertDialog;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.mishiranu.dashchan.R;
import com.mishiranu.dashchan.content.security.AppSecurity;

/** Read-only PIN help. Does not enter the PIN flow or ask for destructive approval. */
public final class PinProtectionHelp {
    private PinProtectionHelp() {}
    public static Dialog create(Context host) {
        boolean dark = ThemeEngine.getTheme(host).base == ThemeEngine.Theme.Base.DARK;
        Context ctx = new ContextThemeWrapper(host, dark ? R.style.Theme_Slooop_RuleEditor_Dark : R.style.Theme_Slooop_RuleEditor_Light);
        AppearanceColors c = StandardAppearance.colors(host);
        int ink = InterfaceAppearance.contrasting(c.onSurface, c.surfaceContainer, 4.5);
        MaterialAlertDialogBuilder builder = new MaterialAlertDialogBuilder(ctx);
        if (builder.getBackground() instanceof com.google.android.material.shape.MaterialShapeDrawable)
            ((com.google.android.material.shape.MaterialShapeDrawable) builder.getBackground()).setFillColor(ColorStateList.valueOf(c.surfaceContainer));
        LinearLayout body = new LinearLayout(host); body.setOrientation(LinearLayout.VERTICAL);
        body.setPaddingRelative(dp(host,24), 0, dp(host,24), dp(host,8));
        int[][] blocks = {{R.string.pin_access, R.string.pin_access_body},
                {R.string.pin_forgot,R.string.pin_forgot_body}, {R.string.pin_erase,R.string.pin_erase_body}};
        for (int i=0; i<blocks.length; i++) {
            TextView label = line(host,blocks[i][0],AppearanceTypography.Role.LABEL_LARGE,ink);
            label.setAccessibilityHeading(true);
            label.setPaddingRelative(0,i==0 ? 0 : dp(host,16),0,dp(host,4));
            body.addView(label); body.addView(line(host,blocks[i][1],AppearanceTypography.Role.BODY_MEDIUM,ink));
        }
        ScrollView scroll = new ScrollView(host); scroll.addView(body);
        TextView title = line(host,R.string.pin_help_title,AppearanceTypography.Role.TITLE_LARGE,ink);
        title.setAccessibilityHeading(true); title.setPaddingRelative(dp(host,24),dp(host,24),dp(host,24),dp(host,16));
        AlertDialog dialog = builder.setCustomTitle(title).setView(scroll)
                .setPositiveButton(R.string.close,null).create();
        AppSecurity.protectDialog(dialog);
        dialog.setOnShowListener(d -> dialog.getButton(AlertDialog.BUTTON_POSITIVE).setTextColor(
                InterfaceAppearance.contrasting(c.primary,c.surfaceContainer,4.5)));
        return dialog;
    }
    private static TextView line(Context c,int res,AppearanceTypography.Role role,int color) {
        TextView v = new TextView(c); v.setText(res); AppearanceTypography.apply(v,role); v.setTextColor(color); return v;
    }
    private static int dp(Context c,int n) { return Math.round(n*c.getResources().getDisplayMetrics().density); }
}
