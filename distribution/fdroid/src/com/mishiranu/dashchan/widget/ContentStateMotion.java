package com.mishiranu.dashchan.widget;

import android.view.View;
import android.view.ViewGroup;
import androidx.core.view.OneShotPreDrawListener;
import java.util.function.BooleanSupplier;

public final class ContentStateMotion {
	private ContentStateMotion() {}
	public static boolean isActive(View view) { return false; }
	public static void finish(View view) {}
	public static Session prepare(ViewGroup host, View outgoing, View incoming) { return null; }
	public static OneShotPreDrawListener revealBeforeDraw(View view, View root, BooleanSupplier valid) { return null; }
	public static void reveal(View view) {}
	public static final class Session { public void commit() {} public void finish() {} }
}
