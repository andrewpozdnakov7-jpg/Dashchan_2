package com.mishiranu.dashchan.widget;

import android.animation.Animator;
import android.view.View;
import android.widget.ImageView;
import java.util.function.BooleanSupplier;

/** Distribution contract. Legacy effects remain owned by common code. */
public final class ElementMotion {
	private ElementMotion() {}
	public static boolean busy(View view) { return false; }
	public static void finish(View view) {}
	public static void finishTree(View view) {}
	public static boolean blocksInput(View view) { return false; }
	public static void visibility(View view, boolean visible, float distanceDp, boolean scale, BooleanSupplier valid) {
		if (view != null) view.setVisibility(visible ? View.VISIBLE : View.GONE);
	}
	public static Animator height(View view, int from, int to, int finalHeight) { return null; }
	public static void icon(ImageView view, int resource) { if (view != null) view.setImageResource(resource); }
}
