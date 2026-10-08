package com.mishiranu.dashchan.ui;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.AnimatorSet;
import android.animation.ValueAnimator;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.drawable.Drawable;
import android.view.SurfaceView;
import android.view.TextureView;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewParent;
import android.view.animation.LinearInterpolator;
import android.webkit.WebView;
import androidx.core.view.OneShotPreDrawListener;
import androidx.fragment.app.FragmentTransaction;
import com.mishiranu.dashchan.R;
import com.mishiranu.dashchan.graphics.BaseDrawable;
import com.mishiranu.dashchan.ui.posting.PostingFragment;
import com.mishiranu.dashchan.util.InterfaceMotion;
import com.mishiranu.dashchan.util.PerformanceDiagnostics;
import com.mishiranu.dashchan.util.ResourceUtils;
import com.mishiranu.dashchan.widget.ThemeEngine;
import com.mishiranu.dashchan.widget.ThreadMotionLayout;

/**
 * One temporary outgoing image plus the live destination; no navigation or content ownership.
 * Stage 1 keeps its container geometry. This controller then owns only toolbar presentation.
 * Snapshot failures / external surfaces use the existing root animation, never a fake black frame.
 */
public final class ScreenMotionController {
	private static final int SCENE_PIXELS = 1_000_000, CHROME_PIXELS = 180_000, PREPARE_TIMEOUT_MS = 600;
	private final ThreadMotionLayout host;
	private final ViewGroup chromeHost;
	private final View toolbar, toolbarExtra;
	private Session active;

	public ScreenMotionController(ThreadMotionLayout host, ViewGroup chromeHost, View toolbar, View toolbarExtra) {
		this.host = host; this.chromeHost = chromeHost; this.toolbar = toolbar; this.toolbarExtra = toolbarExtra;
		host.addOnAttachStateChangeListener(new View.OnAttachStateChangeListener() {
			@Override public void onViewAttachedToWindow(View view) {}
			@Override public void onViewDetachedFromWindow(View view) { finish(); }
		});
		host.addOnLayoutChangeListener((view, l, t, r, b, oldL, oldT, oldR, oldB) -> {
			if (active != null && (r - l != oldR - oldL || b - t != oldB - oldT)) finish();
		});
	}

	public void finish() { if (active != null) active.finish(); }

	/** Capture before onTerminate removes the old toolbar action views / formatting row. */
	public Session prepare(ContentFragment outgoing, ContentFragment incoming, int transition, boolean container) {
		finish();
		if (!InterfaceMotion.isEnabled() || InterfaceMotion.duration(1) == 0 || outgoing == null ||
				!host.isAttachedToWindow() || !chromeHost.isAttachedToWindow() ||
				(transition != FragmentTransaction.TRANSIT_FRAGMENT_OPEN &&
				transition != FragmentTransaction.TRANSIT_FRAGMENT_CLOSE &&
				transition != FragmentTransaction.TRANSIT_FRAGMENT_FADE)) return null;
		View oldRoot = outgoing.getView();
		if (oldRoot == null || !oldRoot.isAttachedToWindow() || !descendsFrom(oldRoot, host)) return null;
		boolean back = transition == FragmentTransaction.TRANSIT_FRAGMENT_CLOSE;
		ScreenMotionSpec spec = ScreenMotionSpec.select(transition == FragmentTransaction.TRANSIT_FRAGMENT_FADE,
				back, outgoing instanceof PostingFragment, incoming instanceof PostingFragment);
		RectF bounds = bounds(oldRoot, host);
		Bitmap scene = !container && bounds != null && !externalSurface(oldRoot)
				? capture(oldRoot, SCENE_PIXELS, ThemeEngine.getTheme(host.getContext()).window) : null;
		int duration = container ? (back ? 300 : 340) : spec.durationMs;
		Session session = new Session(spec, transition, duration, scene, bounds);
		install(session);
		if (scene != null) outgoing.suppressScreenMotionExit();
		return session;
	}

	/** Centralized installation also permits ownership/lifecycle tests without changing preferences. */
	void install(Session session) {
		finish(); active = session;
		if (session.scene != null) {
			host.getOverlay().add(session.layer);
			host.setTag(R.id.screen_motion_owner, session);
			host.setMotionBlocking(true);
		}
		chromeHost.getOverlay().add(session.chromeLayer);
		session.header = session.new Chrome(toolbar);
		session.extra = session.new Chrome(toolbarExtra);
		host.postDelayed(session.timeout, PREPARE_TIMEOUT_MS);
	}

	private static boolean descendsFrom(View view, View ancestor) {
		while (view != null && view != ancestor) {
			ViewParent parent = view.getParent(); view = parent instanceof View ? (View) parent : null;
		}
		return view == ancestor;
	}

	private static RectF bounds(View view, View parent) {
		if (view.getWidth() <= 0 || view.getHeight() <= 0 || parent.getWidth() <= 0 || parent.getHeight() <= 0) return null;
		int[] a = new int[2], b = new int[2];
		parent.getLocationInWindow(a); view.getLocationInWindow(b);
		return new RectF(b[0] - a[0], b[1] - a[1], b[0] - a[0] + view.getWidth(), b[1] - a[1] + view.getHeight());
	}

	static boolean externalSurface(View view) {
		if (view instanceof SurfaceView || view instanceof TextureView || view instanceof WebView) return true;
		if (view instanceof ViewGroup) {
			ViewGroup group = (ViewGroup) view;
			for (int i = 0; i < group.getChildCount(); i++) if (externalSurface(group.getChildAt(i))) return true;
		}
		return false;
	}

	static Bitmap capture(View view, int maxPixels, int background) {
		Bitmap bitmap = null;
		long started = PerformanceDiagnostics.now();
		boolean success = false;
		try {
			int w = view.getWidth(), h = view.getHeight();
			if (w <= 0 || h <= 0 || maxPixels <= 0) return null;
			double scale = Math.min(1d, Math.sqrt(maxPixels / ((double) w * h)));
			bitmap = Bitmap.createBitmap(Math.max(1, (int) (w * scale)), Math.max(1, (int) (h * scale)),
					Bitmap.Config.ARGB_8888);
			Canvas canvas = new Canvas(bitmap); canvas.drawColor(background);
			canvas.scale(bitmap.getWidth() / (float) w, bitmap.getHeight() / (float) h);
			view.draw(canvas); success = true; return bitmap;
		} catch (RuntimeException | OutOfMemoryError e) {
			if (bitmap != null) bitmap.recycle(); return null;
		} finally {
			PerformanceDiagnostics.finish("Motion/screenSnapshot", started, success, true);
		}
	}

	public final class Session {
		private final ScreenMotionSpec spec;
		private final int transition, durationMs;
		private Bitmap scene;
		private final RectF outgoingBounds, frame = new RectF();
		private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
		private View root, destinationView;
		private float originalAlpha, originalX, originalY, originalScaleX, originalScaleY, progress;
		private int originalAccessibility;
		private boolean stopped, ready;
		private Chrome header, extra;
		private Animator animation;
		private OneShotPreDrawListener preDraw;
		private final Runnable timeout = this::finish;
		private final Drawable layer = new BaseDrawable() {
			@Override public void draw(Canvas canvas) { drawScene(canvas); }
		};
		private final Drawable chromeLayer = new BaseDrawable() {
			@Override public void draw(Canvas canvas) {
				if (!stopped) { if (header != null) header.draw(canvas); if (extra != null) extra.draw(canvas); }
			}
		};

		Session(ScreenMotionSpec spec, int transition, int durationMs, Bitmap scene, RectF outgoingBounds) {
			this.spec = spec; this.transition = transition; this.durationMs = durationMs;
			this.scene = scene; this.outgoingBounds = outgoingBounds;
			layer.setBounds(0, 0, host.getWidth(), host.getHeight());
			chromeLayer.setBounds(0, 0, chromeHost.getWidth(), chromeHost.getHeight());
		}

		public void bind(View view) {
			if (stopped || active != this) return;
			destinationView = view;
			if (scene != null) {
				if (externalSurface(view)) discardScene();
				else {
					root = view; InterfaceMotion.cancel(root);
					originalAlpha = root.getAlpha(); originalX = root.getTranslationX(); originalY = root.getTranslationY();
					originalScaleX = root.getScaleX(); originalScaleY = root.getScaleY();
					originalAccessibility = root.getImportantForAccessibility();
					root.setTag(R.id.screen_motion_owner, this);
					root.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS);
					root.setAlpha(0f);
				}
			}
			preDraw = OneShotPreDrawListener.add(view, () -> {
				preDraw = null;
				if (stopped || active != this) return;
				if (!InterfaceMotion.isEnabled() || InterfaceMotion.duration(1) == 0 || !view.isAttachedToWindow() ||
						view.getWidth() <= 0 || view.getHeight() <= 0) { finish(); return; }
				ready = true; applyFrame();
				if (animation != null && animation.isStarted()) host.removeCallbacks(timeout);
				android.util.Log.d("SlooopMotion", "profile=" + spec.name().toLowerCase(java.util.Locale.US)
						+ " root=" + (root != null ? "scene" : "fallback_or_container") + " toolbar=true");
			});
		}

		/** FragmentManager owns start/end, including the stage 1 animator when it is supplied. */
		public Animator createAnimator(Animator primary) {
			if (stopped) return primary != null ? primary : ValueAnimator.ofFloat(0f, 1f).setDuration(0);
			if (animation != null) return animation;
			ValueAnimator clock = ValueAnimator.ofFloat(0f, 1f);
			clock.setDuration(InterfaceMotion.duration(durationMs)); clock.setInterpolator(new LinearInterpolator());
			clock.addUpdateListener(value -> {
				if (!stopped) { progress = (float) value.getAnimatedValue(); applyFrame(); }
			});
			if (primary == null && root == null) primary = InterfaceMotion.content(destinationView, true, transition);
			if (primary != null) {
				AnimatorSet set = new AnimatorSet(); set.playTogether(primary, clock); animation = set;
			} else animation = clock;
			animation.addListener(new AnimatorListenerAdapter() {
				@Override public void onAnimationStart(Animator animator) {
					if (stopped) { animator.cancel(); return; }
					host.removeCallbacks(timeout);
					if (!ready) host.postDelayed(timeout, PREPARE_TIMEOUT_MS);
				}
				@Override public void onAnimationCancel(Animator animator) { release(); }
				@Override public void onAnimationEnd(Animator animator) { release(); }
			});
			return animation;
		}

		private void applyFrame() {
			if (!ready) return;
			float alpha = ScreenMotionSpec.incomingAlpha(progress);
			if (root != null && root.getTag(R.id.screen_motion_owner) == this) {
				float t = InterfaceMotion.STANDARD.getInterpolation(alpha);
				float density = ResourceUtils.obtainDensity(root);
				boolean rtl = root.getLayoutDirection() == View.LAYOUT_DIRECTION_RTL;
				root.setAlpha(originalAlpha * alpha);
				root.setTranslationX(originalX + spec.incomingX(density, rtl) * (1f - t));
				root.setTranslationY(originalY + spec.incomingY(density) * (1f - t));
				float scale = spec.initialScale() + (1f - spec.initialScale()) * t;
				root.setScaleX(originalScaleX * scale); root.setScaleY(originalScaleY * scale);
			}
			if (header != null) header.apply(alpha);
			if (extra != null) extra.apply(alpha);
			layer.invalidateSelf(); chromeLayer.invalidateSelf();
		}

		public void finish() {
			if (stopped) return;
			release(); // Restore before cancellation invokes FragmentManager listeners.
			if (animation != null) animation.cancel();
		}

		private void restoreRoot() {
			if (root != null && root.getTag(R.id.screen_motion_owner) == this) {
				root.setTag(R.id.screen_motion_owner, null);
				root.setAlpha(originalAlpha); root.setTranslationX(originalX); root.setTranslationY(originalY);
				root.setScaleX(originalScaleX); root.setScaleY(originalScaleY);
				root.setImportantForAccessibility(originalAccessibility);
			}
			root = null;
		}
		private void discardScene() {
			restoreRoot(); host.getOverlay().remove(layer);
			if (host.getTag(R.id.screen_motion_owner) == this) {
				host.setTag(R.id.screen_motion_owner, null); host.setMotionBlocking(false);
			}
			if (scene != null) { scene.recycle(); scene = null; }
		}
		private void release() {
			if (stopped) return; stopped = true;
			host.removeCallbacks(timeout);
			if (preDraw != null) { preDraw.removeListener(); preDraw = null; }
			discardScene();
			if (header != null) { header.release(); header = null; }
			if (extra != null) { extra.release(); extra = null; }
			chromeHost.getOverlay().remove(chromeLayer);
			if (active == this) active = null;
			destinationView = null;
		}

		private void drawScene(Canvas canvas) {
			if (stopped || scene == null || outgoingBounds == null) return;
			float alpha = ready ? ScreenMotionSpec.outgoingAlpha(progress) : 1f;
			if (alpha <= 0f) return;
			frame.set(outgoingBounds);
			float t = ready ? InterfaceMotion.STANDARD.getInterpolation(progress) : 0f;
			boolean rtl = host.getLayoutDirection() == View.LAYOUT_DIRECTION_RTL;
			float density = ResourceUtils.obtainDensity(host);
			frame.offset(spec.outgoingX(density, rtl) * t, spec.outgoingY(density) * t);
			int save = canvas.save(); canvas.clipRect(0, 0, host.getWidth(), host.getHeight());
			paint.setAlpha(Math.round(alpha * 255f)); canvas.drawBitmap(scene, null, frame, paint);
			canvas.restoreToCount(save);
		}

		/** Owns child alpha, not toolbar_layout alpha/translation (ExpandedScreen owns those). */
		private final class Chrome {
			private final View view;
			private final float alpha;
			private final int accessibility;
			private final float parentX, parentY;
			private final RectF bounds;
			private Bitmap bitmap;
			Chrome(View view) {
				this.view = view; alpha = view.getAlpha(); accessibility = view.getImportantForAccessibility();
				ViewParent parent = view.getParent();
				parentX = parent instanceof View ? ((View) parent).getTranslationX() : 0f;
				parentY = parent instanceof View ? ((View) parent).getTranslationY() : 0f;
				bounds = ScreenMotionController.bounds(view, chromeHost);
				bitmap = bounds != null && view.isShown() && alpha > 0f
						? capture(view, CHROME_PIXELS, ThemeEngine.getTheme(view.getContext()).primary) : null;
				view.setTag(R.id.screen_motion_owner, Session.this);
				view.setAlpha(0f);
				view.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS);
			}
			void apply(float fraction) {
				if (view.getTag(R.id.screen_motion_owner) == Session.this) view.setAlpha(alpha * fraction);
			}
			void draw(Canvas canvas) {
				if (bitmap == null || bounds == null) return;
				ViewParent parent = view.getParent();
				float parentAlpha = parent instanceof View ? ((View) parent).getAlpha() : 1f;
				float fraction = ready ? ScreenMotionSpec.outgoingAlpha(progress) : 1f;
				paint.setAlpha(Math.round(255f * fraction * alpha * parentAlpha));
				int save = canvas.save();
				if (parent instanceof View) canvas.translate(((View) parent).getTranslationX() - parentX,
						((View) parent).getTranslationY() - parentY);
				canvas.drawBitmap(bitmap, null, bounds, paint); canvas.restoreToCount(save);
			}
			void release() {
				if (view.getTag(R.id.screen_motion_owner) == Session.this) {
					view.setTag(R.id.screen_motion_owner, null); view.setAlpha(alpha);
					view.setImportantForAccessibility(accessibility);
				}
				if (bitmap != null) { bitmap.recycle(); bitmap = null; }
			}
		}
	}
}
