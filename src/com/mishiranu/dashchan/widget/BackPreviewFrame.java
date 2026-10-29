package com.mishiranu.dashchan.widget;

import android.content.res.Configuration;
import android.graphics.Bitmap;
import android.view.View;

/** A bounded, memory-only image. Cache eviction cannot recycle an image leased by a gesture. */
public final class BackPreviewFrame {
	final Bitmap image;
	final int width, height, background;
	private final Configuration configuration;
	private final Object theme, insets;
	private final int rootWidth, rootHeight, direction;
	private int references = 1;
	private BackPreviewFrame(View view, Bitmap image, int background) {
		this.background = background;
		this.image = image; width = view.getWidth(); height = view.getHeight();
		configuration = new Configuration(view.getResources().getConfiguration());
		theme = ThemeEngine.getTheme(view.getContext()); insets = view.getRootWindowInsets();
		rootWidth = view.getRootView().getWidth(); rootHeight = view.getRootView().getHeight();
		direction = view.getLayoutDirection();
	}
	public static BackPreviewFrame capture(View view, boolean web) {
		if (!com.mishiranu.dashchan.util.InterfaceMotion.isPredictiveBackEnabled()
				|| !view.isAttachedToWindow() || !view.isShown() || view.isLayoutRequested()) return null;
		long cap = BackPreviewBudget.limit(view.getContext()) / 8;
		int background = web ? 0xffFFFFFF : ThemeEngine.getTheme(view.getContext()).card;
		Bitmap bitmap = web ? BackPreviewBudget.captureWeb((android.webkit.WebView) view, cap, background)
				: BackPreviewBudget.capture(view, cap, background);
		if (bitmap == null) return null;
		try { return new BackPreviewFrame(view, bitmap, background); }
		catch (RuntimeException | OutOfMemoryError error) { BackPreviewBudget.release(bitmap); return null; }
	}
	public boolean valid(View view) {
		return references > 0 && !image.isRecycled() && view.isAttachedToWindow() && width == view.getWidth()
				&& rootWidth == view.getRootView().getWidth() && rootHeight == view.getRootView().getHeight()
				&& direction == view.getLayoutDirection() && configuration.equals(view.getResources().getConfiguration())
				&& theme == ThemeEngine.getTheme(view.getContext()) && java.util.Objects.equals(insets, view.getRootWindowInsets());
	}
	public BackPreviewFrame retain() { if (references <= 0) throw new IllegalStateException(); references++; return this; }
	public void release() { if (references > 0 && --references == 0) BackPreviewBudget.release(image); }
}
