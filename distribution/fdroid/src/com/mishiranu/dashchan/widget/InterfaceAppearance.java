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
	public static boolean isEnabled(Context context) { return false; }
	public static void row(View view, TextView title, TextView summary, boolean header) {}
	public static void action(Button view, boolean primary) {}
	public static void field(EditText view) {}
	public static void check(CompoundButton view) {}
	public static void slider(SeekBar view) {}
	public static void icon(ImageView view) {}
	public static void secondary(TextView view) {}
	public static void audioTitle(TextView view) {}
	public static void fieldError(EditText view, boolean error) {}
	public static void refreshTree(View view) {}
	public static void configureDialog(AlertDialog dialog) {}
}
