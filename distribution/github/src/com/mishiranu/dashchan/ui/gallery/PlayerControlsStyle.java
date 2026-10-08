package com.mishiranu.dashchan.ui.gallery;

import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.LayerDrawable;
import android.graphics.drawable.RippleDrawable;
import android.graphics.drawable.StateListDrawable;
import android.view.Gravity;
import android.view.View;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.SeekBar;
import android.widget.TextView;
import androidx.annotation.NonNull;
import com.mishiranu.dashchan.R;
import com.mishiranu.dashchan.graphics.BaseDrawable;
import com.mishiranu.dashchan.util.ViewUtils;

/** GitHub-only experimental video styling; playback and native controls stay in the shared player. */
final class PlayerControlsStyle {
	private PlayerControlsStyle() {}

	static View createBar(Context context, ImageButton play, LinearLayout time, SeekBar seek,
			ImageButton mute, ImageButton fullscreen) {
		return new PlayerControlBar(context, play, time, seek, mute, fullscreen);
	}

	static int dp(float density, float value) {
		return Math.round(density * value);
	}

	static GradientDrawable panel(float density, float radius) {
		return surface(density, radius, 0x40000000, 0x26ffffff);
	}

	private static GradientDrawable surface(float density, float radius, int fill, int stroke) {
		GradientDrawable background = rounded(fill, dp(density, radius));
		background.setStroke(Math.max(1, dp(density, 1)), stroke);
		return background;
	}

	static void text(TextView view, int size) {
		view.setTextColor(Color.WHITE);
		ViewUtils.setTextSizeScaled(view, size);
		view.setTypeface(view.getResources().getFont(R.font.player_montserrat_semibold));
		view.setFontFeatureSettings("tnum");
		view.setIncludeFontPadding(false);
		view.setShadowLayer(view.getResources().getDisplayMetrics().density, 0f, 0f, 0x99000000);
		view.setSingleLine(true);
		view.setGravity(Gravity.CENTER);
	}

	static void button(ImageButton view, float density, boolean center) {
		view.setImageTintList(new ColorStateList(new int[][] {
				new int[] {-android.R.attr.state_enabled}, new int[] {}},
				new int[] {0x73ffffff, Color.WHITE}));
		// Match the reference: 18 dp artwork inside 32 dp buttons; the 72 dp center is unchanged.
		int padding = dp(density, center ? 21 : 7);
		view.setPadding(padding, padding, padding, padding);
		if (center) {
			view.setBackground(new RippleDrawable(ColorStateList.valueOf(0x33ffffff),
					surface(density, 36, 0x33000000, 0x26ffffff),
					rounded(Color.WHITE, dp(density, 36))));
		}
	}

	static void slider(SeekBar view, float density) {
		// Keep the standard background/buffer/progress IDs and level invalidation used by SeekBar.
		LayerDrawable layers = new LayerDrawable(new Drawable[] {
				new SliderTrack(view, density, 0),
				new SliderTrack(view, density, 1),
				new SliderTrack(view, density, 2)});
		int[] ids = {android.R.id.background, android.R.id.secondaryProgress, android.R.id.progress};
		for (int i = 0; i < ids.length; i++) {
			layers.setId(i, ids[i]);
			layers.setLayerHeight(i, dp(density, 16));
			layers.setLayerGravity(i, Gravity.CENTER_VERTICAL | Gravity.FILL_HORIZONTAL);
		}
		view.setProgressTintList(null);
		view.setSecondaryProgressTintList(null);
		view.setProgressBackgroundTintList(null);
		view.setProgressDrawable(layers);
		StateListDrawable thumb = new StateListDrawable();
		thumb.addState(new int[] {-android.R.attr.state_enabled}, thumb(density, 0x66ffffff, 4, 24));
		thumb.addState(new int[] {android.R.attr.state_pressed}, thumb(density, Color.WHITE, 4, 28));
		thumb.addState(new int[] {}, thumb(density, Color.WHITE, 4, 24));
		view.setThumb(thumb);
		view.setThumbTintList(null);
		// Track segments leave their own gap around the thin handle.
		view.setSplitTrack(false);
		view.setPadding(dp(density, 6), 0, dp(density, 6), 0);
	}

	private static GradientDrawable thumb(float density, int color, int width, int height) {
		GradientDrawable result = rounded(color, dp(density, width / 2f));
		result.setSize(dp(density, width), dp(density, height));
		return result;
	}

	private static GradientDrawable rounded(int color, int radius) {
		GradientDrawable result = new GradientDrawable();
		result.setColor(color);
		result.setCornerRadius(radius);
		return result;
	}

	/** Rounded active/inactive capsules with a handle gap and the reference's end marker. */
	private static final class SliderTrack extends BaseDrawable {
		private final SeekBar view;
		private final float density;
		private final int part;
		private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
		private final RectF rect = new RectF();
		private final Path path = new Path();
		private final float[] radii = new float[8];
		private int alpha = 255;

		SliderTrack(SeekBar view, float density, int part) {
			this.view = view;
			this.density = density;
			this.part = part;
		}

		@Override
		public void draw(@NonNull Canvas canvas) {
			Rect bounds = getBounds();
			if (bounds.isEmpty()) return;
			int maximum = view.getMax();
			float fraction = maximum > 0 ? Math.max(0f, Math.min(1f,
					(float) view.getProgress() / maximum)) : 0f;
			float buffered = maximum > 0 ? Math.max(fraction, Math.min(1f,
					(float) view.getSecondaryProgress() / maximum)) : fraction;
			float handle = bounds.left + bounds.width() * fraction;
			float gap = 6f * density;
			float centerY = bounds.exactCenterY();
			float halfHeight = Math.min(8f * density, bounds.height() / 2f);
			float start = part == 2 ? bounds.left : Math.min(bounds.right, handle + gap);
			float end = part == 2 ? Math.max(bounds.left, handle - gap)
					: part == 1 ? bounds.left + bounds.width() * buffered : bounds.right;
			int color = part == 2 ? Color.WHITE : part == 1 ? 0x80ffffff : 0x4dffffff;
			paint.setColor(color);
			paint.setAlpha(Math.round(Color.alpha(color) * (alpha / 255f) * (view.isEnabled() ? 1f : 0.45f)));
			if (end > start) {
				rect.set(start, centerY - halfHeight, end, centerY + halfHeight);
				float innerRadius = Math.min(2f * density, halfHeight);
				float leftRadius = part == 2 ? halfHeight : innerRadius;
				float rightRadius = part == 2 ? innerRadius
						: end >= bounds.right ? halfHeight : innerRadius;
				radii[0] = radii[1] = radii[6] = radii[7] = leftRadius;
				radii[2] = radii[3] = radii[4] = radii[5] = rightRadius;
				path.reset();
				path.addRoundRect(rect, radii, Path.Direction.CW);
				canvas.drawPath(path, paint);
			}
			if (part == 2 && bounds.right - handle > gap + 10f * density) {
				paint.setColor(Color.WHITE);
				paint.setAlpha(Math.round(alpha * (view.isEnabled() ? 1f : 0.45f)));
				canvas.drawCircle(bounds.right - 6f * density, centerY, 2f * density, paint);
			}
		}

		@Override
		public int getIntrinsicHeight() {
			return dp(density, 16);
		}

		@Override
		protected boolean onLevelChange(int level) {
			return true;
		}

		@Override
		public boolean isStateful() {
			return true;
		}

		@Override
		protected boolean onStateChange(int[] state) {
			return true;
		}

		@Override
		public void setAlpha(int alpha) {
			this.alpha = Math.max(0, Math.min(255, alpha));
			invalidateSelf();
		}
	}
}
