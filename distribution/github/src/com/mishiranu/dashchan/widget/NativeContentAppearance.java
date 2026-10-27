package com.mishiranu.dashchan.widget;

import android.content.res.ColorStateList;
import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.LayerDrawable;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.ProgressBar;
import android.widget.TextView;
import com.mishiranu.dashchan.R;

/** Explicit native-only adapters. Never walks into a WebView or owns navigation/operation callbacks. */
final class NativeContentAppearance {
	private NativeContentAppearance() {}
	static void linear(ProgressBar view) {
		Object tag = view.getTag(R.id.standard_native_content_appearance);
		Linear state = tag instanceof Linear ? (Linear) tag : new Linear(view);
		view.setTag(R.id.standard_native_content_appearance, state); state.apply();
	}
	static void panel(ViewGroup root, TextView title, TextView status, ViewGroup actions) {
		Object tag = root.getTag(R.id.standard_native_content_appearance);
		Panel state = tag instanceof Panel ? (Panel) tag : new Panel(root, title, status, actions);
		root.setTag(R.id.standard_native_content_appearance, state); state.apply();
	}
	static void refresh(View view) {
		Object tag = view.getTag(R.id.standard_native_content_appearance);
		if (tag instanceof Linear) ((Linear) tag).apply();
		else if (tag instanceof Panel) ((Panel) tag).apply();
	}
	private static final class Linear {
		final ProgressBar view;
		final Drawable legacy;
		final ColorStateList tint, backgroundTint;
		final int height, left, top, right, bottom;
		final Line line = new Line();
		final LayerDrawable layers;
		boolean modern;
		Linear(ProgressBar view) {
			this.view = view; legacy = view.getProgressDrawable(); tint = view.getProgressTintList();
			backgroundTint = view.getProgressBackgroundTintList(); height = view.getLayoutParams().height;
			left = view.getPaddingLeft(); top = view.getPaddingTop(); right = view.getPaddingRight(); bottom = view.getPaddingBottom();
			// Secondary refresh during setProgressDrawable must not overwrite the primary fraction.
			layers = new LayerDrawable(new Drawable[]{new ColorDrawable(0), new ColorDrawable(0), line});
			layers.setId(0, android.R.id.background); layers.setId(1, android.R.id.secondaryProgress); layers.setId(2, android.R.id.progress);
		}
		void apply() {
			boolean enabled = InterfaceAppearance.isEnabled(view.getContext());
			if (!enabled && !modern) return;
			ViewGroup.LayoutParams params = view.getLayoutParams();
			if (!enabled) {
				view.setProgressDrawable(legacy); view.setProgressTintList(tint); view.setProgressBackgroundTintList(backgroundTint);
				params.height = height; view.setPadding(left, top, right, bottom); modern = false;
			} else {
				AppearanceColors c = StandardAppearance.colors(view.getContext());
				line.track = c.surface; line.fill = InterfaceAppearance.contrasting(c.primary, line.track, 3);
				view.setProgressTintList(null); view.setProgressBackgroundTintList(null);
				if (view.getProgressDrawable() != layers) view.setProgressDrawable(layers);
				// Values and visibility remain entirely with WebChromeClient. No tween/delay.
				params.height = StandardAppearance.dp(view, 6); view.setPadding(0, 0, 0, 0); modern = true;
			}
			view.setLayoutParams(params); view.invalidate();
		}
	}
	/** Actual-fraction/rounded-track recipe from StandardProgressContent.Line, adapted to ProgressBar levels. */
	static final class Line extends Drawable {
		final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
		int track, fill;
		@Override protected boolean onLevelChange(int level) { invalidateSelf(); return true; }
		@Override public void draw(Canvas canvas) {
			android.graphics.Rect b = getBounds(); float radius = b.height() / 2f;
			paint.setColor(track); canvas.drawRoundRect(b.left, b.top, b.right, b.bottom, radius, radius, paint);
			float width = b.width() * getLevel() / 10000f;
			if (width > 0) {
				// ProgressBar's existing mirrorForRtl policy owns mirroring of the canvas.
				paint.setColor(fill); float start = b.left;
				float endRadius = Math.min(radius, width / 2f);
				canvas.drawRoundRect(start, b.top, start + width, b.bottom, endRadius, endRadius, paint);
			}
		}
		@Override public void setAlpha(int alpha) { paint.setAlpha(alpha); invalidateSelf(); }
		@Override public void setColorFilter(ColorFilter filter) { paint.setColorFilter(filter); invalidateSelf(); }
		@Override public int getOpacity() { return PixelFormat.TRANSLUCENT; }
	}
	private static final class Panel {
		final ViewGroup root, actions;
		final TextView title, status;
		final int[] titlePadding, statusPadding, actionPadding;
		Panel(ViewGroup root, TextView title, TextView status, ViewGroup actions) {
			this.root = root; this.title = title; this.status = status; this.actions = actions;
			titlePadding = padding(title); statusPadding = padding(status); actionPadding = padding(actions);
		}
		static int[] padding(View view) { return view == null ? null : new int[]{view.getPaddingStart(), view.getPaddingTop(), view.getPaddingEnd(), view.getPaddingBottom()}; }
		static void restore(View view, int[] p) { if (view != null) view.setPaddingRelative(p[0], p[1], p[2], p[3]); }
		void apply() {
			InterfaceAppearance.standardPage(root);
			if (title != null) InterfaceAppearance.standardDialogTitle(title);
			if (status != null) InterfaceAppearance.standardCustomContent(status);
			if (actions != null) for (int i = 0; i < actions.getChildCount(); i++) {
				View child = actions.getChildAt(i);
				if (child instanceof Button) InterfaceAppearance.standardCustomAction((Button) child, false);
			}
			if (!InterfaceAppearance.isEnabled(root.getContext())) {
				restore(title, titlePadding); restore(status, statusPadding); restore(actions, actionPadding); return;
			}
			int inset = StandardAppearance.dp(root, 24), gap = StandardAppearance.dp(root, 8);
			if (title != null) title.setPaddingRelative(inset, inset, inset, gap);
			if (status != null) status.setPaddingRelative(inset, gap, inset, gap);
			if (actions != null) actions.setPaddingRelative(inset, gap, inset, gap);
		}
	}
}
