package com.mishiranu.dashchan.widget;

import android.app.AlertDialog;
import android.content.Context;
import android.view.View;
import android.widget.Button;
import android.widget.CompoundButton;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.SeekBar;
import android.widget.TextView;

/** The experimental appearance remains exclusive to the GitHub distribution. */
public final class InterfaceAppearance {
	private InterfaceAppearance() {}
	public static ImageView createShortcutPreview(Context context) { return new ImageView(context); }
	public static boolean isEnabled(Context context) { return false; }
	public static void row(View view, TextView title, TextView summary, boolean header) {}
	public static void settingsRow(View view, TextView title, TextView summary, View widgets, boolean header) {}
	public static void serviceRow(View view, TextView title, TextView metadata, TextView end) {}
	public static void serviceHeader(TextView text) {}
	public static void serviceTableCell(TextView text, boolean header) {}
	public static void serviceText(TextView text, boolean nested, boolean medium) {}
	public static void serviceCategory(View root, TextView title, ImageView arrow) {}
	public static void servicePreview(View root, TextView title, TextView metadata, TextView date,
			boolean muted, boolean emphasized, float legacyAlpha) {}
	public static void serviceAction(TextView text, boolean empty) {}
	public static void serviceTab(View root, TextView text, View indicator, boolean selected) {}
	public static void themeChoice(View root, TextView title, ImageView preview, ImageView check, boolean selected, boolean dialog) {}
	public static void serviceDocument(TextView text) {}
	public static com.mishiranu.dashchan.graphics.ColorScheme serviceDocumentColors(Context context) {
		return ThemeEngine.getColorScheme(context);
	}
	public static int serviceWarningColor(Context context) {
		return com.mishiranu.dashchan.util.ResourceUtils.getColor(context, com.mishiranu.dashchan.R.attr.colorTextError);
	}
	public static void menuRow(View view, TextView title) {}
	public static void serviceState(View view, TextView message) {}
	public static void serviceErrorState(View view, ImageView icon, TextView message) {}
	public static void standardPage(View view) {}
	public static Integer standardStatusBarSurface(Context context) { return null; }
	public static void standardLoading(android.widget.ProgressBar view) {}
	public static void standardCompactLoading(android.widget.ProgressBar view) {}
	public static void standardDialogLoading(android.widget.ProgressBar view) {}
	public static ProgressDialog.Content standardProgressContent(Context context, String format) { return null; }
	public static void standardProgressText(TextView text, boolean caption) {}
	public static void standardCustomContent(View view, View... previews) {}
	public static void standardCustomCaption(TextView view) {}
	public static void standardCustomInsets(View view) {}
	public static void standardUntitledInsets(View view) {}
	public static void standardCustomStack(android.view.ViewGroup view) {}
	public static void standardCustomRows(android.view.ViewGroup view) {}
	public static void standardCustomDialogContent(AlertDialog dialog, View content) {}
	public static void standardChoiceText(TextView view) {}
	public static void standardCustomAction(Button view, boolean primary) {}
	public static void standardChoiceRow(View row, TextView text) {}
	public static int[] standardSummaryColors(Context context) { return null; }
	public static void standardToolbar(android.widget.Toolbar toolbar, TextView title, TextView subtitle) {}
	public static void standardSearch(View root, android.widget.SearchView search) {}
	public static Context standardDialogContext(Context context) { return context; }
	public static void configureStandardDialog(AlertDialog dialog) {}
	public static void configureStandardPageDialog(AlertDialog dialog) {}
	public static void standardLinearProgress(android.widget.ProgressBar view) {}
	public static void standardNativePanel(android.view.ViewGroup root, TextView title, TextView status, android.view.ViewGroup actions) {}
	public static void prepareStandardMenuDialog(AlertDialog dialog, View menu) {}
	public static void standardDialogTitle(TextView title) {}
	public static void standardFieldLabel(TextView label) {}
	public static void updateStandardDialogTitle(AlertDialog dialog, CharSequence title) {}
	public static void popupView(View view) {}
	public static boolean standardPopup(View decor, View background) { return false; }
	public static void action(Button view, boolean primary) {}
	public static void field(EditText view) {}
	public static void postingForm(View view) {}
	public static void postingField(EditText view) {}
	public static void check(CompoundButton view) {}
	public static void slider(SeekBar view) {}
	public static void icon(ImageView view) {}
	public static void secondary(TextView view) {}
	public static void audioTitle(TextView view) {}
	public static void fieldError(EditText view, boolean error) {}
	public static void refreshTree(View view) {}
	public static void configureDialog(AlertDialog dialog) {}
}
