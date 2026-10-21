package com.mishiranu.dashchan.widget;

import android.content.Context;
import android.graphics.Typeface;
import android.text.method.TransformationMethod;
import android.util.TypedValue;
import android.widget.TextView;
import com.mishiranu.dashchan.content.FontManager;
import com.mishiranu.dashchan.util.ViewUtils;
import java.util.Objects;

/** Explicit roles only: no traversal, global font replacement or automatic M3 resizing. */
public final class AppearanceTypography {
	public enum Role {
		TITLE_LARGE(22, 28, false), TITLE_MEDIUM(16, 24, true), BODY_LARGE(16, 24, false),
		BODY_MEDIUM(14, 20, false), BODY_SMALL(12, 16, false), LABEL_LARGE(14, 20, true), LABEL_MEDIUM(12, 16, true),
		DRAWER_COMPACT(14, 0, true), DRAWER_BODY(14, 0, false), DRAWER_COUNTER(12, 0, true),
		COMPACT_TOOLBAR(20, 24, true),
		COMPAT_ACTION(14, -1, false), COMPAT_AUDIO_TITLE(18, -1, false);
		final int size, line; final boolean medium;
		Role(int size, int line, boolean medium) { this.size = size; this.line = line; this.medium = medium; }
	}
	private AppearanceTypography() {}
	public static void apply(TextView view, Role role) {
		if (role.line < 0) {
			// Preserve existing button/audio typeface and native line metrics, including fractional SP.
			view.setTextSize(TypedValue.COMPLEX_UNIT_SP, role.size); return;
		}
		view.setTypeface(FontManager.resolveTypeface(view.getContext(), role.medium));
		ViewUtils.setTextSizeScaled(view, role.size);
		view.setLetterSpacing(0f);
		// Input transformation is not typography: changing it can reset query selection/IME state.
		if (!(view instanceof android.widget.EditText)) view.setAllCaps(false);
		if (role.line == 0) {
			view.setLineSpacing(0f, 1f); view.setLetterSpacing(0f); view.setAllCaps(false);
		} else {
			int target = Math.round(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, role.line,
					view.getResources().getDisplayMetrics()));
			android.graphics.Paint.FontMetricsInt metrics = view.getPaint().getFontMetricsInt();
			view.setLineHeight(Math.max(target, metrics.bottom - metrics.top));
		}
	}
	/** Value key, not a Context cache. Captures values that can change without a new Theme object. */
	public static final class Configuration {
		private final float density, scaledDensity, fontScale;
		private final Typeface regular, medium;
		public Configuration(Context context) {
			density = context.getResources().getDisplayMetrics().density;
			scaledDensity = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, 1, context.getResources().getDisplayMetrics());
			fontScale = context.getResources().getConfiguration().fontScale;
			regular = FontManager.resolveTypeface(context, false); medium = FontManager.resolveTypeface(context, true);
		}
		@Override public boolean equals(Object other) {
			if (!(other instanceof Configuration)) return false;
			Configuration c = (Configuration) other;
			return density == c.density && scaledDensity == c.scaledDensity && fontScale == c.fontScale
					&& Objects.equals(regular, c.regular) && Objects.equals(medium, c.medium);
		}
		@Override public int hashCode() { return Objects.hash(density, scaledDensity, fontScale, regular, medium); }
	}
	/** Properties this resolver can change; does not capture text, selection, focus or listeners. */
	public static final class Snapshot {
		private final Typeface typeface;
		private final float size, extra, multiplier, letterSpacing;
		private final TransformationMethod transformation;
		public Snapshot(TextView view) {
			typeface = view.getTypeface(); size = view.getTextSize(); extra = view.getLineSpacingExtra();
			multiplier = view.getLineSpacingMultiplier(); letterSpacing = view.getLetterSpacing();
			transformation = view.getTransformationMethod();
		}
		public void restoreSizeAndTransformation(TextView view) {
			view.setTextSize(TypedValue.COMPLEX_UNIT_PX, size); view.setTransformationMethod(transformation);
		}
		public void restore(TextView view) {
			restoreTextMetrics(view); view.setTransformationMethod(transformation);
		}
		public void restoreTextMetrics(TextView view) {
			view.setTypeface(typeface); view.setTextSize(TypedValue.COMPLEX_UNIT_PX, size);
			view.setLineSpacing(extra, multiplier); view.setLetterSpacing(letterSpacing);
		}
	}
}
