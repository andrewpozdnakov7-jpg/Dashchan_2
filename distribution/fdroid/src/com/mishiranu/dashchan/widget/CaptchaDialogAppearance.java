package com.mishiranu.dashchan.widget;

import android.app.AlertDialog;
import android.content.Context;
import android.content.DialogInterface;
import android.graphics.Bitmap;
import android.view.View;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.LinearLayout;

/** The legacy foreground dialog remains the only F-Droid presentation. */
public final class CaptchaDialogAppearance {
	public static boolean isEnabled() { return false; }
	public CaptchaDialogAppearance(Context context, LinearLayout content, String instruction) {}
	public ImageView taskImage(Bitmap image) { throw new IllegalStateException("Unsupported presentation"); }
	public Button button(int which) { throw new IllegalStateException("Unsupported presentation"); }
	public AlertDialog create(View content, DialogInterface.OnClickListener actions, Runnable dismiss) { throw new IllegalStateException("Unsupported presentation"); }
	public void start(AlertDialog dialog) {}
	public void bindSelection(Bitmap[] images, boolean animate) {}
	public void waiting(boolean waiting, boolean refreshing, boolean error) {}
	public void beforeContentChange(View grid) {}
	public void afterContentChange(View grid) {}
	public void close() {}
}
