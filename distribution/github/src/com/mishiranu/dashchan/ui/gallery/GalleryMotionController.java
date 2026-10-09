package com.mishiranu.dashchan.ui.gallery;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ValueAnimator;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.drawable.Drawable;
import android.view.View;
import androidx.core.view.OneShotPreDrawListener;
import com.mishiranu.dashchan.R;
import com.mishiranu.dashchan.util.InterfaceMotion;
import com.mishiranu.dashchan.widget.AttachmentView;
import com.mishiranu.dashchan.widget.PhotoView;
import java.lang.ref.WeakReference;

/** Media presentation only. Owns transient rendering, never item selection, zoom state or a player. */
public final class GalleryMotionController {
	private final View root;
	private WeakReference<AttachmentView> source;
	private long sourceBinding;
	private Object sourceItem;
	private Scene scene;
	private View[] mediaChrome = new View[0];

	public GalleryMotionController(View root) {
		this.root = root;
		root.addOnAttachStateChangeListener(new View.OnAttachStateChangeListener() {
			@Override public void onViewAttachedToWindow(View view) {}
			@Override public void onViewDetachedFromWindow(View view) { finish(); }
		});
		root.addOnLayoutChangeListener((v, l, t, r, b, ol, ot, or, ob) -> {
			if (scene != null && or > ol && ob > ot && (r - l != or - ol || b - t != ob - ot)) finish();
		});
	}

	public void setSource(View view) {
		source = view instanceof AttachmentView ? new WeakReference<>((AttachmentView) view) : null;
		sourceBinding = view instanceof AttachmentView ? ((AttachmentView) view).getImageBindingGeneration() : -1;
		sourceItem = null;
	}
	public void setMediaChrome(View[] views) { mediaChrome = views != null ? views.clone() : new View[0]; }

	public void enter(PhotoView photo, Object item, View chrome, boolean restoring) {
		enter(photo, item, chrome, restoring, null);
	}
	public void enter(PhotoView photo, Object item, View chrome, boolean restoring, View surface) {
		finish();
		sourceItem = item;
		if (restoring || !enabled() || photo == null) return;
		start(photo, chrome, false, null, item, surface);
	}

	/** A caller must consume Back once true is returned. Repeated Back cannot dismiss twice. */
	public boolean close(PhotoView photo, Object item, View chrome, Runnable dismiss) {
		return close(photo, item, chrome, dismiss, null);
	}
	public boolean close(PhotoView photo, Object item, View chrome, Runnable dismiss, View surface) {
		if (scene != null && scene.exit) return true;
		finish();
		if (!enabled() || photo == null || !photo.hasImage() || !root.isAttachedToWindow()) return false;
		start(photo, chrome, true, dismiss, item, surface);
		return true;
	}

	private boolean enabled() { return InterfaceMotion.isEnabled() && ValueAnimator.areAnimatorsEnabled(); }
	private void start(PhotoView photo, View chrome, boolean exit, Runnable dismiss, Object item, View surface) {
		photo.finishGalleryPresentation();
		photo.clearInitialScaleAnimationData();
		install(new Scene(photo, chrome, exit, dismiss, item, surface));
	}
	void install(Scene next) { finish(); scene = next; next.prepare(); }

	private RectF target(PhotoView photo, Object item) {
		AttachmentView view = source != null ? source.get() : null;
		if (view == null || item != sourceItem || view.getImageBindingGeneration() != sourceBinding ||
				!view.isAttachedToWindow() || !view.isShown() || view.getWidth() == 0 || view.getHeight() == 0) return null;
		Rect visible = new Rect();
		if (!view.getGlobalVisibleRect(visible) || visible.width() < view.getWidth() - 1 ||
				visible.height() < view.getHeight() - 1) return null;
		int[] from = new int[2], to = new int[2];
		view.getLocationOnScreen(from); photo.getLocationOnScreen(to);
		return new RectF(from[0] - to[0], from[1] - to[1],
				from[0] - to[0] + view.getWidth(), from[1] - to[1] + view.getHeight());
	}

	public void finish() { if (scene != null) scene.run(); }
	public boolean isClosing() { return scene != null && scene.exit; }
	public static void finishHost(View host) {
		Object owner = host.getTag(R.id.gallery_motion_owner);
		if (owner instanceof Runnable) ((Runnable) owner).run();
	}
	public static boolean blocks(View host) { return host.getTag(R.id.gallery_motion_owner) != null; }

	/** Paired grid/photo mode changes use the same wall-clock phases and settle to logical visibility. */
	public static boolean mode(View view, boolean enter, int duration, boolean scaleContent) {
		Object old = view.getTag(R.id.gallery_motion_owner);
		if (old instanceof Mode) ((Mode) old).run();
		if (!InterfaceMotion.isEnabled()) return false;
		view.animate().setListener(null).cancel();
		view.setAlpha(1f); view.setScaleX(1f); view.setScaleY(1f);
		Mode mode = new Mode(view, enter, scaleContent);
		mode.start(duration);
		return true;
	}
	private static final class Mode implements Runnable {
		final View view;
		final boolean enter;
		final boolean scaleContent;
		final float alpha, scaleX, scaleY;
		final int accessibility;
		ValueAnimator animator;
		boolean released;
		Mode(View view, boolean enter, boolean scaleContent) {
			this.view = view; this.enter = enter; this.scaleContent = scaleContent;
			alpha = view.getAlpha(); scaleX = view.getScaleX(); scaleY = view.getScaleY();
			accessibility = view.getImportantForAccessibility();
		}
		void start(int duration) {
			view.setTag(R.id.gallery_motion_owner, this); view.setVisibility(View.VISIBLE);
			view.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS);
			if (duration <= 0 || !ValueAnimator.areAnimatorsEnabled()) { run(); return; }
			apply(0f);
			animator = ValueAnimator.ofFloat(0f, 1f); animator.setDuration(duration); animator.setInterpolator(input -> input);
			animator.addUpdateListener(value -> {
				if (!InterfaceMotion.isEnabled() || !view.isAttachedToWindow() || view.getTag(R.id.gallery_motion_owner) != this) { run(); return; }
				apply((float) value.getAnimatedValue());
			});
			animator.addListener(new AnimatorListenerAdapter() { @Override public void onAnimationEnd(Animator animation) { run(); } });
			animator.start();
		}
		void apply(float clock) {
			float eased = InterfaceMotion.STANDARD.getInterpolation(clock);
			view.setAlpha(alpha * (enter ? GalleryMotionSpec.incomingAlpha(clock) : GalleryMotionSpec.outgoingAlpha(clock)));
			float scale = scaleContent ? (enter ? .96f + .04f * eased : 1f - .02f * eased) : 1f;
			view.setScaleX(scaleX * scale); view.setScaleY(scaleY * scale);
		}
		@Override public void run() {
			if (released) return; released = true;
			if (animator != null) { animator.cancel(); animator = null; }
			if (view.getTag(R.id.gallery_motion_owner) == this) {
				view.setTag(R.id.gallery_motion_owner, null); view.setAlpha(alpha); view.setScaleX(scaleX); view.setScaleY(scaleY);
				view.setVisibility(enter ? View.VISIBLE : View.GONE);
				view.setImportantForAccessibility(accessibility);
			}
		}
	}

	final class Scene implements Runnable {
		final PhotoView photo;
		final PresentationFrame surface;
		final PresentationFrame[] controls;
		final View chrome;
		final Object item;
		final boolean exit;
		final Runnable dismiss;
		final Drawable background;
		final float alpha, chromeAlpha;
		final int backgroundAlpha, accessibility, chromeAccessibility;
		final Runnable watchdog = this;
		RectF image, thumbnail;
		boolean crop;
		boolean released;
		OneShotPreDrawListener preDraw;
		ValueAnimator animator;

		Scene(PhotoView photo, View chrome, boolean exit, Runnable dismiss, Object item) {
			this(photo, chrome, exit, dismiss, item, null);
		}
		Scene(PhotoView photo, View chrome, boolean exit, Runnable dismiss, Object item, View surface) {
			this.photo = photo; this.chrome = chrome; this.exit = exit; this.dismiss = dismiss; this.item = item;
			this.surface = surface != null ? new PresentationFrame(surface) : null;
			java.util.ArrayList<PresentationFrame> controls = new java.util.ArrayList<>();
			for (View view : mediaChrome) {
				if (view != null && view != surface && view != photo && view != chrome && view != root
						&& controls.stream().noneMatch(frame -> frame.view == view)) controls.add(new PresentationFrame(view));
			}
			this.controls = controls.toArray(new PresentationFrame[0]);
			alpha = photo.getAlpha(); chromeAlpha = chrome != null ? chrome.getAlpha() : 1f;
			background = root.getBackground(); backgroundAlpha = background != null ? background.getAlpha() : 255;
			accessibility = root.getImportantForAccessibility();
			chromeAccessibility = chrome != null ? chrome.getImportantForAccessibility() : View.IMPORTANT_FOR_ACCESSIBILITY_AUTO;
		}
		void prepare() {
			root.setTag(R.id.gallery_motion_owner, this); photo.setTag(R.id.gallery_motion_owner, this);
			if (surface != null) surface.own(this);
			for (PresentationFrame control : controls) control.own(this);
			root.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS);
			if (chrome != null) {
				chrome.setTag(R.id.gallery_motion_owner, this);
				chrome.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS);
			}
			if (!exit) {
				photo.setAlpha(0f);
				if (surface != null) surface.fade(this, 0f);
				for (PresentationFrame control : controls) control.fade(this, 0f);
				if (background != null) background.setAlpha(0);
				if (chrome != null) chrome.setAlpha(0f);
			}
			root.postDelayed(watchdog, 600);
			preDraw = OneShotPreDrawListener.add(root, () -> {
				preDraw = null;
				if (released) return;
				root.removeCallbacks(watchdog);
				if (!enabled() || !root.isAttachedToWindow() || photo.getWidth() == 0 || photo.getHeight() == 0) { run(); return; }
				image = photo.getGalleryImageBounds();
				thumbnail = photo.isGalleryImageAtRest() ? target(photo, item) : null;
				AttachmentView from = source != null ? source.get() : null;
				crop = from != null && from.isCropEnabled();
				if (image == null) thumbnail = null;
				apply(0f);
				animator = ValueAnimator.ofFloat(0f, 1f);
				animator.setDuration(exit ? GalleryMotionSpec.EXIT : GalleryMotionSpec.ENTER);
				animator.setInterpolator(InterfaceMotion.STANDARD);
				animator.addUpdateListener(value -> {
					if (!enabled() || photo.getTag(R.id.gallery_motion_owner) != this ||
							root.getTag(R.id.gallery_motion_owner) != this || !photo.isAttachedToWindow()) { run(); return; }
					if (thumbnail != null && target(photo, item) == null) { run(); return; }
					apply((float) value.getAnimatedValue());
				});
				animator.addListener(new AnimatorListenerAdapter() {
					@Override public void onAnimationEnd(Animator animation) { run(); }
				});
				animator.start();
			});
			root.invalidate();
		}
		void apply(float progress) {
			float amount = exit ? 1f - progress : progress;
			for (PresentationFrame control : controls) control.fade(this, amount);
			if (background != null && root.getBackground() == background) background.setAlpha(Math.round(backgroundAlpha * amount));
			if (chrome != null && chrome.getTag(R.id.gallery_motion_owner) == this) chrome.setAlpha(chromeAlpha * amount);
			if (thumbnail != null) {
				photo.setAlpha(alpha);
				RectF clip = new RectF(GalleryMotionSpec.lerp(thumbnail.left, image.left, amount),
						GalleryMotionSpec.lerp(thumbnail.top, image.top, amount),
						GalleryMotionSpec.lerp(thumbnail.right, image.right, amount),
						GalleryMotionSpec.lerp(thumbnail.bottom, image.bottom, amount));
				photo.setGalleryFrame(this, clip, crop);
				if (surface != null) {
					RectF surfaceImage = new RectF(image), surfaceClip = new RectF(clip);
					float dx = photo.getLeft() - surface.view.getLeft(), dy = photo.getTop() - surface.view.getTop();
					surfaceImage.offset(dx, dy); surfaceClip.offset(dx, dy);
					surface.frame(this, surfaceImage, surfaceClip, crop);
					surface.fade(this, 1f);
				}
			} else {
				photo.setAlpha(alpha * amount);
				if (surface != null) surface.fade(this, amount);
			}
		}
		@Override public void run() {
			if (released) return;
			released = true;
			if (scene == this) scene = null;
			root.removeCallbacks(watchdog);
			if (preDraw != null) { preDraw.removeListener(); preDraw = null; }
			if (animator != null) { animator.cancel(); animator = null; }
			photo.clearGalleryFrame(this);
			if (surface != null) surface.release(this);
			for (PresentationFrame control : controls) control.release(this);
			if (photo.getTag(R.id.gallery_motion_owner) == this) { photo.setTag(R.id.gallery_motion_owner, null); photo.setAlpha(alpha); }
			if (chrome != null && chrome.getTag(R.id.gallery_motion_owner) == this) {
				chrome.setTag(R.id.gallery_motion_owner, null); chrome.setAlpha(chromeAlpha);
				chrome.setImportantForAccessibility(chromeAccessibility);
			}
			if (root.getTag(R.id.gallery_motion_owner) == this) {
				root.setTag(R.id.gallery_motion_owner, null); root.setImportantForAccessibility(accessibility);
				if (background != null && root.getBackground() == background) background.setAlpha(backgroundAlpha);
			}
			if (dismiss != null) dismiss.run();
		}
	}

	/** Transform a container, not its TextureView/decoder or the user's gesture matrix. */
	static final class PresentationFrame {
		final View view;
		final float alpha, x, y, scaleX, scaleY, pivotX, pivotY;
		final Rect clip;
		PresentationFrame(View view) {
			this.view = view;
			alpha = view.getAlpha(); x = view.getTranslationX(); y = view.getTranslationY();
			scaleX = view.getScaleX(); scaleY = view.getScaleY(); pivotX = view.getPivotX(); pivotY = view.getPivotY();
			clip = view.getClipBounds();
		}
		void own(Object owner) { view.setTag(R.id.gallery_motion_owner, owner); }
		void fade(Object owner, float amount) {
			if (view.getTag(R.id.gallery_motion_owner) == owner) view.setAlpha(alpha * amount);
		}
		void frame(Object owner, RectF image, RectF target, boolean crop) {
			if (view.getTag(R.id.gallery_motion_owner) != owner || image.width() <= 0f || image.height() <= 0f) return;
			float sx = target.width() / image.width(), sy = target.height() / image.height();
			float scale = crop ? Math.max(sx, sy) : Math.min(sx, sy);
			if (scale <= 0f) return;
			view.setPivotX(0f); view.setPivotY(0f);
			float tx = target.centerX() - image.centerX() * scale, ty = target.centerY() - image.centerY() * scale;
			view.setScaleX(scale); view.setScaleY(scale);
			view.setTranslationX(tx); view.setTranslationY(ty);
			Rect bounds = new Rect((int) Math.floor((target.left - tx) / scale),
					(int) Math.floor((target.top - ty) / scale), (int) Math.ceil((target.right - tx) / scale),
					(int) Math.ceil((target.bottom - ty) / scale));
			if (clip != null && !bounds.intersect(clip)) bounds.setEmpty();
			view.setClipBounds(bounds);
		}
		void release(Object owner) {
			if (view.getTag(R.id.gallery_motion_owner) != owner) return;
			view.setTag(R.id.gallery_motion_owner, null); view.setAlpha(alpha);
			view.setPivotX(pivotX); view.setPivotY(pivotY); view.setScaleX(scaleX); view.setScaleY(scaleY);
			view.setTranslationX(x); view.setTranslationY(y); view.setClipBounds(clip);
		}
	}

	/** A small preview only, never a copy of a decoded full-size image. */
	public static Preview capturePreview(PhotoView photo) {
		if (!InterfaceMotion.isEnabled() || !ValueAnimator.areAnimatorsEnabled() || !photo.hasImage() ||
				photo.getTag(R.id.gallery_motion_owner) != null || photo.isZoomed() || photo.getWidth() <= 0 || photo.getHeight() <= 0) return null;
		Bitmap bitmap = null;
		try {
			float scale = Math.min(1f, (float) Math.sqrt(300000d / ((double) photo.getWidth() * photo.getHeight())));
			int width = Math.max(1, (int) (photo.getWidth() * scale)), height = Math.max(1, (int) (photo.getHeight() * scale));
			if ((long) width * height > 300000) {
				if (width >= height) width = Math.max(1, 300000 / height); else height = Math.max(1, 300000 / width);
			}
			bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
			Canvas canvas = new Canvas(bitmap); canvas.scale((float) width / photo.getWidth(), (float) height / photo.getHeight());
			photo.draw(canvas);
			return new Preview(bitmap);
		} catch (RuntimeException | OutOfMemoryError e) { if (bitmap != null) bitmap.recycle(); return null; }
	}
	public static final class Preview {
		final Bitmap bitmap;
		Preview(Bitmap bitmap) { this.bitmap = bitmap; }
		public void discard() { if (!bitmap.isRecycled()) bitmap.recycle(); }
	}
	public static void revealPhoto(PhotoView photo, Preview preview, boolean hadImage) {
		photo.finishGalleryPresentation();
		if (!InterfaceMotion.isEnabled() || !ValueAnimator.areAnimatorsEnabled() || !photo.isAttachedToWindow() ||
				(preview == null && hadImage)) { if (preview != null) preview.discard(); return; }
		Reveal reveal = new Reveal(photo, preview); reveal.start();
	}
	static final class Reveal extends Drawable implements Runnable {
		final PhotoView photo;
		final Preview preview;
		final float alpha;
		final Paint paint = new Paint(Paint.FILTER_BITMAP_FLAG);
		ValueAnimator animator;
		float progress;
		boolean released;
		Reveal(PhotoView photo, Preview preview) { this.photo = photo; this.preview = preview; alpha = photo.getAlpha(); }
		void start() {
			photo.setTag(R.id.gallery_motion_owner, this);
			if (preview != null) { setBounds(0, 0, photo.getWidth(), photo.getHeight()); photo.setGalleryPreview(this, this); }
			else photo.setAlpha(0f);
			animator = ValueAnimator.ofFloat(0f, 1f); animator.setDuration(GalleryMotionSpec.REVEAL); animator.setInterpolator(InterfaceMotion.STANDARD);
			animator.addUpdateListener(value -> {
				if (!InterfaceMotion.isEnabled() || photo.getTag(R.id.gallery_motion_owner) != this) { run(); return; }
				progress = (float) value.getAnimatedValue();
				if (preview == null) photo.setAlpha(alpha * progress); else invalidateSelf();
			});
			animator.addListener(new AnimatorListenerAdapter() { @Override public void onAnimationEnd(Animator animation) { run(); } });
			animator.start();
		}
		@Override public void draw(Canvas canvas) {
			if (preview != null && !preview.bitmap.isRecycled()) {
				paint.setAlpha(Math.round(255f * (1f - progress))); canvas.drawBitmap(preview.bitmap, null, getBounds(), paint);
			}
		}
		@Override public void run() {
			if (released) return; released = true;
			if (animator != null) { animator.cancel(); animator = null; }
			photo.clearGalleryPreview(this);
			if (photo.getTag(R.id.gallery_motion_owner) == this) { photo.setTag(R.id.gallery_motion_owner, null); photo.setAlpha(alpha); }
			if (preview != null) preview.discard();
		}
		@Override public void setAlpha(int alpha) {}
		@Override public void setColorFilter(ColorFilter filter) {}
		@SuppressWarnings("deprecation") @Override public int getOpacity() { return PixelFormat.TRANSLUCENT; }
	}
}
