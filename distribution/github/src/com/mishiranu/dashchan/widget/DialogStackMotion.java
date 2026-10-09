package com.mishiranu.dashchan.widget;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ValueAnimator;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PixelFormat;
import android.graphics.RectF;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.InsetDrawable;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import androidx.core.view.OneShotPreDrawListener;
import com.mishiranu.dashchan.R;
import com.mishiranu.dashchan.util.InterfaceMotion;
import com.mishiranu.dashchan.util.GraphicsUtils;
import com.mishiranu.dashchan.util.ResourceUtils;

/** Presentation only: factories and the logical stack are destroyed/updated synchronously. */
public final class DialogStackMotion {
	private final View content, root;
	private final Drawable originalBackground;
	private final ColorDrawable scrim = new ColorDrawable();
	private Window window;
	private float dim;
	private boolean modern;
	private Scene scene;

	public DialogStackMotion(View content, View root) {
		this.content = content;
		this.root = root;
		originalBackground = content.getBackground();
		root.addOnAttachStateChangeListener(new View.OnAttachStateChangeListener() {
			@Override public void onViewAttachedToWindow(View view) {}
			@Override public void onViewDetachedFromWindow(View view) { finish(); }
		});
		root.addOnLayoutChangeListener((v, l, t, r, b, ol, ot, or, ob) -> {
			if (scene != null && (scene.started || or > ol && ob > ot) &&
					(r - l != or - ol || b - t != ob - ot)) finish();
		});
	}

	public void configure(Window window, float dim, int legacyAnimations) {
		finish();
		this.window = window;
		this.dim = dim;
		modern = InterfaceMotion.isEnabled() && ValueAnimator.areAnimatorsEnabled();
		WindowManager.LayoutParams attributes = window.getAttributes();
		attributes.windowAnimations = modern ? 0 : legacyAnimations;
		// Keep DIM_BEHIND's window semantics, but paint the animated dim locally.
		attributes.flags |= WindowManager.LayoutParams.FLAG_DIM_BEHIND;
		attributes.dimAmount = modern ? 0f : dim;
		window.setAttributes(attributes);
		content.setBackground(modern ? scrim : originalBackground);
		setDim(1f);
	}

	private void setDim(float fraction) {
		if (modern) scrim.setColor(Math.round(255f * dim * fraction) << 24);
	}

	private boolean enabled() {
		return modern && InterfaceMotion.isEnabled() && ValueAnimator.areAnimatorsEnabled();
	}

	/** The detached source is represented by pixels only, never by its provider or View. */
	public Snapshot capture(View card) {
		finish();
		if (!enabled() || card == null || externalSurface(card) || card.getWidth() <= 0 || card.getHeight() <= 0 ||
				!root.isAttachedToWindow()) return null;
		Bitmap bitmap = null;
		try {
			float scale = Math.min(1f, (float) Math.sqrt(600000d / ((double) card.getWidth() * card.getHeight())));
			int width = Math.max(1, (int) (card.getWidth() * scale));
			int height = Math.max(1, (int) (card.getHeight() * scale));
			if ((long) width * height > 600000) {
				if (width >= height) width = Math.max(1, 600000 / height);
				else height = Math.max(1, 600000 / width);
			}
			bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
			Canvas canvas = new Canvas(bitmap);
			canvas.scale((float) bitmap.getWidth() / card.getWidth(), (float) bitmap.getHeight() / card.getHeight());
			card.draw(canvas);
			int[] position = new int[2], origin = new int[2];
			card.getLocationInWindow(position); root.getLocationInWindow(origin);
			return new Snapshot(bitmap, new RectF(position[0] - origin[0], position[1] - origin[1],
					position[0] - origin[0] + card.getWidth(), position[1] - origin[1] + card.getHeight()), card.getAlpha());
		} catch (RuntimeException | OutOfMemoryError e) {
			if (bitmap != null) bitmap.recycle();
			return null;
		}
	}

	private static boolean externalSurface(View view) {
		if (view instanceof android.view.SurfaceView || view instanceof android.view.TextureView ||
				view instanceof android.webkit.WebView) return true;
		if (view instanceof ViewGroup) {
			ViewGroup group = (ViewGroup) view;
			for (int i = 0; i < group.getChildCount(); i++) if (externalSurface(group.getChildAt(i))) return true;
		}
		return false;
	}

	/** Returns false for legacy rendering; the caller retains its original 100 ms entrance. */
	public boolean enter(Snapshot previous, View previousLive, View incoming, boolean first, boolean restoring) {
		if (!enabled()) { discard(previous); return false; }
		if (restoring) { discard(previous); setDim(1f); return true; }
		// A failed/unsupported snapshot must not crossfade two live posts over each other.
		if (!first && (previous == null || cardContent(incoming) == null)) {
			discard(previous); setDim(1f); return true;
		}
		begin(previous, incoming, first, false, false, null);
		return true;
	}

	public void leave(Snapshot outgoing, View incoming, boolean last, Runnable close) {
		if (!enabled() || outgoing == null || !last && cardContent(incoming) == null) {
			discard(outgoing);
			if (close != null) close.run();
			return;
		}
		begin(outgoing, incoming, false, last, true, close);
	}

	private static View cardContent(View card) {
		return card instanceof ViewGroup && ((ViewGroup) card).getChildCount() == 1
				? ((ViewGroup) card).getChildAt(0) : null;
	}

	private void begin(Snapshot outgoing, View incoming, boolean first,
			boolean last, boolean returning, Runnable close) {
		finish();
		Scene next = new Scene(outgoing, incoming, first, last, returning, close);
		scene = next;
		next.prepare();
	}

	public void finish() {
		Scene current = scene;
		if (current != null) current.release();
	}

	public void closed(Window closed) {
		if (window == closed) {
			finish();
			window = null;
			content.setBackground(originalBackground);
			modern = false;
		}
	}

	public static void discard(Snapshot snapshot) {
		if (snapshot != null && !snapshot.bitmap.isRecycled()) snapshot.bitmap.recycle();
	}

	public static boolean blocks(View host, MotionEvent event) {
		return host.getTag(R.id.dialog_motion_owner) != null;
	}

	public static void lostFocus(View host) {
		Object owner = host.getTag(R.id.dialog_motion_owner);
		if (owner instanceof Scene) ((Scene) owner).release();
	}

	public static final class Snapshot {
		final Bitmap bitmap;
		final RectF bounds;
		final float alpha;
		Snapshot(Bitmap bitmap, RectF bounds) { this(bitmap, bounds, 1f); }
		Snapshot(Bitmap bitmap, RectF bounds, float alpha) { this.bitmap = bitmap; this.bounds = bounds; this.alpha = alpha; }
	}

	private final class Scene extends Drawable {
		final Snapshot outgoing;
		final View incoming, animated;
		final float incomingAlpha, scaleX, scaleY, translationY, pivotX, pivotY;
		final int accessibility;
		final boolean first, last, returning;
		final Runnable close;
		final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
		final RectF contentClip = new RectF();
		final Path surfaceClip = new Path();
		final Runnable watchdog = this::release;
		OneShotPreDrawListener preDraw;
		ValueAnimator animator;
		float clock;
		boolean started, released;

		Scene(Snapshot outgoing, View incoming, boolean first, boolean last, boolean returning, Runnable close) {
			this.outgoing = outgoing; this.incoming = incoming;
			this.first = first; this.last = last; this.returning = returning; this.close = close;
			// Nested transitions keep the real card surface opaque and stationary.
			// Only first entrance/final exit may fade the whole card and screen dim.
			animated = first ? incoming : cardContent(incoming);
			incomingAlpha = animated != null ? animated.getAlpha() : 1f;
			scaleX = animated != null ? animated.getScaleX() : 1f;
			scaleY = animated != null ? animated.getScaleY() : 1f;
			translationY = animated != null ? animated.getTranslationY() : 0f;
			pivotX = animated != null ? animated.getPivotX() : 0f;
			pivotY = animated != null ? animated.getPivotY() : 0f;
			accessibility = root.getImportantForAccessibility();
		}

		void prepare() {
			root.setTag(R.id.dialog_motion_owner, this);
			root.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS);
			// Lower cards remain visible with DialogStack's normal inactive dim.
			// Taking their alpha ownership would hide them until this scene ends.
			if (incoming != null) incoming.setTag(R.id.dialog_motion_owner, this);
			if (animated != null) { animated.setTag(R.id.dialog_motion_owner, this); animated.setAlpha(0f); }
			if (outgoing != null) {
				setBounds(0, 0, root.getWidth(), root.getHeight());
				root.getOverlay().add(this);
			}
			if (first) setDim(0f);
			// A bounded preparation wait; running animators still honor the system duration scale.
			root.postDelayed(watchdog, 600);
			preDraw = OneShotPreDrawListener.add(root, () -> {
				preDraw = null;
				if (released) return;
				root.removeCallbacks(watchdog);
				if (!owned() || !enabled() || !root.isAttachedToWindow() || incoming != null &&
						(incoming.getWidth() == 0 || incoming.getHeight() == 0)) { release(); return; }
				started = true;
				if (animated != null) {
					animated.setPivotX(animated.getWidth() / 2f); animated.setPivotY(animated.getHeight() / 2f);
				}
				if (!first && !last && incoming != null) {
					int[] position = new int[2], origin = new int[2];
					incoming.getLocationInWindow(position); root.getLocationInWindow(origin);
					float left = position[0] - origin[0], top = position[1] - origin[1];
					contentClip.set(left + incoming.getPaddingLeft(), top + incoming.getPaddingTop(),
							left + incoming.getWidth() - incoming.getPaddingRight(),
							top + incoming.getHeight() - incoming.getPaddingBottom());
					Drawable background = incoming.getBackground();
					while (background instanceof InsetDrawable) background = ((InsetDrawable) background).getDrawable();
					float radius = incoming.getClipToOutline() && background instanceof GradientDrawable
							? GraphicsUtils.getCornerRadius((GradientDrawable) background) : 0f;
					surfaceClip.addRoundRect(contentClip, radius, radius, Path.Direction.CW);
				}
				animator = ValueAnimator.ofFloat(0f, 1f);
				animator.setDuration(last ? 220 : 280);
				animator.setInterpolator(input -> input);
				animator.addUpdateListener(value -> {
					if (!owned() || !enabled()) { release(); return; }
					clock = (float) value.getAnimatedValue();
					float eased = InterfaceMotion.STANDARD.getInterpolation(clock);
					if (animated != null) {
						animated.setAlpha(incomingAlpha * (outgoing != null ? DialogMotionSpec.incomingAlpha(clock) : eased));
						float scale = 0.96f + 0.04f * eased;
						animated.setScaleX(scaleX * scale); animated.setScaleY(scaleY * scale);
						animated.setTranslationY(translationY + (returning ? -12f : 24f)
								* ResourceUtils.obtainDensity(root) * (1f - eased));
					}
					if (first) setDim(eased); else if (last) setDim(1f - eased);
					invalidateSelf();
				});
				animator.addListener(new AnimatorListenerAdapter() {
					@Override public void onAnimationEnd(Animator animation) { release(); }
				});
				animator.start();
			});
			root.invalidate();
		}

		@Override public void draw(Canvas canvas) {
			if (outgoing == null || outgoing.bitmap.isRecycled()) return;
			float eased = InterfaceMotion.STANDARD.getInterpolation(clock);
			float alpha = last ? 1f - eased : DialogMotionSpec.outgoingAlpha(clock);
			paint.setAlpha(Math.round(255f * alpha * outgoing.alpha));
			RectF bounds = outgoing.bounds;
			int save = canvas.save();
			// A snapshot includes the old background. Never draw its fading surface
			// outside the stable incoming card, including when their heights differ.
			if (!last) {
				if (!started) { canvas.restoreToCount(save); return; }
				canvas.clipPath(surfaceClip);
			}
			canvas.translate(0f, (returning ? 24f : -12f) * ResourceUtils.obtainDensity(root) * eased);
			float scale = 1f - 0.04f * eased;
			canvas.scale(scale, scale, bounds.centerX(), bounds.centerY());
			canvas.drawBitmap(outgoing.bitmap, null, bounds, paint);
			canvas.restoreToCount(save);
		}

		private boolean owned() {
			return root.getTag(R.id.dialog_motion_owner) == this &&
					(incoming == null || incoming.getTag(R.id.dialog_motion_owner) == this) &&
					(animated == null || animated.getTag(R.id.dialog_motion_owner) == this);
		}

		void release() {
			if (released) return;
			released = true;
			if (scene == this) scene = null;
			root.removeCallbacks(watchdog);
			if (preDraw != null) { preDraw.removeListener(); preDraw = null; }
			if (animator != null) { animator.cancel(); animator = null; }
			root.getOverlay().remove(this);
			if (animated != null && animated.getTag(R.id.dialog_motion_owner) == this) {
				animated.setTag(R.id.dialog_motion_owner, null); animated.setAlpha(incomingAlpha);
				animated.setScaleX(scaleX); animated.setScaleY(scaleY); animated.setTranslationY(translationY);
				animated.setPivotX(pivotX); animated.setPivotY(pivotY);
			}
			if (incoming != null && incoming.getTag(R.id.dialog_motion_owner) == this)
				incoming.setTag(R.id.dialog_motion_owner, null);
			if (root.getTag(R.id.dialog_motion_owner) == this) {
				root.setTag(R.id.dialog_motion_owner, null); root.setImportantForAccessibility(accessibility);
			}
			discard(outgoing);
			setDim(1f);
			if (close != null) close.run();
		}

		@Override public void setAlpha(int alpha) {}
		@Override public void setColorFilter(ColorFilter filter) {}
		@SuppressWarnings("deprecation") // Drawable's required compatibility override.
		@Override public int getOpacity() { return PixelFormat.TRANSLUCENT; }
	}
}
