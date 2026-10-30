package com.mishiranu.dashchan.ui;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ValueAnimator;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.drawable.Drawable;
import android.view.View;
import android.view.ViewParent;
import android.view.animation.LinearInterpolator;
import android.view.animation.PathInterpolator;
import androidx.core.view.OneShotPreDrawListener;
import androidx.fragment.app.FragmentTransaction;
import com.mishiranu.dashchan.R;
import com.mishiranu.dashchan.graphics.BaseDrawable;
import com.mishiranu.dashchan.ui.navigator.Page;
import com.mishiranu.dashchan.ui.navigator.PageFragment;
import com.mishiranu.dashchan.util.InterfaceMotion;
import com.mishiranu.dashchan.util.PerformanceDiagnostics;
import com.mishiranu.dashchan.util.ResourceUtils;
import com.mishiranu.dashchan.util.ThreadMotionKey;
import com.mishiranu.dashchan.widget.ThemeEngine;
import com.mishiranu.dashchan.widget.ThreadMotionLayout;
import java.lang.ref.WeakReference;
import java.util.LinkedHashMap;
import java.util.function.Consumer;

/**
 * A container transition for the app's saved-page navigation. No FragmentTransactions, requests,
 * stack mutation or retained page Views live here. One transient scene; four identity-only anchors.
 * This is an app implementation of the Material container-transform pattern, not MDC internals.
 */
public final class ThreadMotionController {
	private static final int ENTER_MS = 340, RETURN_MS = 300, PREPARE_TIMEOUT_MS = 600;
	private static final int SCENE_PIXELS = 1_000_000, CARD_PIXELS = 200_000, MAX_ANCHORS = 4;
	private static final PathInterpolator EMPHASIZED;
	static {
		Path path = new Path();
		path.moveTo(0f, 0f);
		path.cubicTo(.05f, 0f, .133333f, .06f, .166666f, .4f);
		path.cubicTo(.208333f, .82f, .25f, 1f, 1f, 1f);
		EMPHASIZED = new PathInterpolator(path);
	}

	/** Metadata can survive a deferred request, but never retains or snapshots its source View. */
	public static final class Opening {
		final String sourceRetainId;
		final ThreadMotionKey key;
		final WeakReference<View> card;
		public Opening(String sourceRetainId, ThreadMotionKey key, View card) {
			this.sourceRetainId = sourceRetainId;
			this.key = key;
			this.card = new WeakReference<>(card);
		}
	}

	private static final class Anchor {
		final String sourceRetainId;
		final ThreadMotionKey key;
		Anchor(Opening opening) { sourceRetainId = opening.sourceRetainId; key = opening.key; }
	}

	private final ThreadMotionLayout host;
	private final LinkedHashMap<String, Anchor> anchors = new LinkedHashMap<>();
	private Session active;

	public ThreadMotionController(ThreadMotionLayout host) {
		this.host = host;
		host.addOnAttachStateChangeListener(new View.OnAttachStateChangeListener() {
			@Override public void onViewAttachedToWindow(View view) {}
			@Override public void onViewDetachedFromWindow(View view) { finish(); }
		});
		host.addOnLayoutChangeListener((view, l, t, r, b, oldL, oldT, oldR, oldB) -> {
			if (active != null && (r - l != oldR - oldL || b - t != oldB - oldT)) finish();
		});
	}

	public void finish() { if (active != null) active.finish(); }
	public void clear() { finish(); anchors.clear(); }

	/** Called before outgoing.onTerminate(): a destroyed adapter is too late to capture. */
	public Session prepare(ContentFragment outgoing, ContentFragment incoming, int transition, Opening opening) {
		finish();
		if (outgoing != null) com.mishiranu.dashchan.widget.ElementMotion.finishTree(outgoing.getView());
		if (!InterfaceMotion.isEnabled() || InterfaceMotion.duration(1) == 0 || !host.isAttachedToWindow() ||
				!(outgoing instanceof PageFragment) || !(incoming instanceof PageFragment)) return null;
		PageFragment from = (PageFragment) outgoing, to = (PageFragment) incoming;
		Page fromPage = from.getPage(), toPage = to.getPage();
		View oldRoot = from.getView();
		if (oldRoot == null || !oldRoot.isAttachedToWindow() || !descendsFrom(oldRoot, host)) return null;
		boolean returning = transition == FragmentTransaction.TRANSIT_FRAGMENT_CLOSE;
		Anchor anchor;
		View card = null;
		if (returning) {
			anchor = anchors.get(from.getRetainId());
			if (anchor == null || fromPage.content != Page.Content.POSTS || !catalog(toPage) ||
					!anchor.sourceRetainId.equals(to.getRetainId()) || !anchor.key.equals(key(fromPage))) return null;
		} else {
			if (transition != FragmentTransaction.TRANSIT_FRAGMENT_OPEN || opening == null ||
					!catalog(fromPage) || toPage.content != Page.Content.POSTS ||
					!opening.sourceRetainId.equals(from.getRetainId()) || !opening.key.equals(key(toPage))) return null;
			card = opening.card.get();
			if (card == null || !descendsFrom(card, oldRoot) ||
					!opening.key.equals(card.getTag(R.id.thread_motion_key))) return null;
			anchor = new Anchor(opening);
		}
		RectF rootBounds = bounds(oldRoot);
		RectF cardBounds = card != null ? visibleBounds(card) : null;
		// A partially clipped card gets a normal transition instead of a misleading cropped origin.
		if (rootBounds == null || !returning && cardBounds == null) return null;
		Bitmap scene;
		if (returning) {
			Rect viewport = from.getThreadMotionContentBounds();
			if (viewport == null) return null;
			// A page root includes toolbar padding and floating controls. Only the message viewport
			// belongs to the contracting container; keep the opening snapshot path unchanged.
			rootBounds = new RectF(rootBounds.left + viewport.left, rootBounds.top + viewport.top,
					rootBounds.left + viewport.right, rootBounds.top + viewport.bottom);
			scene = captureContent(oldRoot, viewport, SCENE_PIXELS, from::drawThreadMotionContent);
		} else {
			scene = capture(oldRoot, SCENE_PIXELS);
		}
		if (scene == null) return null;
		Bitmap cardImage = card != null ? capture(card, CARD_PIXELS) : null;
		if (!returning && cardImage == null) { scene.recycle(); return null; }
		install(new Session(to, anchor, returning, rootBounds, cardBounds, scene, cardImage));
		outgoing.suppressThreadMotionExit();
		return active;
	}

	/** Single installation/cleanup path, also exercised by lifecycle tests without navigating pages. */
	void install(Session session) {
		finish();
		active = session;
		host.getOverlay().add(session.layer);
		host.setMotionBlocking(true);
		host.postDelayed(session.timeout, PREPARE_TIMEOUT_MS);
	}

	private static boolean catalog(Page page) {
		return page.content == Page.Content.THREADS || page.content == Page.Content.COMBINED_THREADS;
	}
	private static ThreadMotionKey key(Page page) {
		return new ThreadMotionKey(page.chanName, page.boardName, page.threadNumber);
	}
	private static boolean descendsFrom(View view, View root) {
		while (view != null && view != root) {
			ViewParent parent = view.getParent();
			view = parent instanceof View ? (View) parent : null;
		}
		return view == root;
	}

	private RectF bounds(View view) {
		if (view.getWidth() <= 0 || view.getHeight() <= 0 || host.getWidth() <= 0 || host.getHeight() <= 0) return null;
		int[] a = new int[2], b = new int[2];
		host.getLocationInWindow(a); view.getLocationInWindow(b);
		return new RectF(b[0] - a[0], b[1] - a[1], b[0] - a[0] + view.getWidth(), b[1] - a[1] + view.getHeight());
	}
	private RectF visibleBounds(View card) {
		if (!card.isShown() || card.getAlpha() <= 0f) return null;
		RectF result = bounds(card);
		Rect visible = new Rect();
		if (result == null || !card.getGlobalVisibleRect(visible) || visible.width() < card.getWidth() - 1 ||
				visible.height() < card.getHeight() - 1 || result.left < 0 || result.top < 0 ||
				result.right > host.getWidth() || result.bottom > host.getHeight()) return null;
		return result;
	}
	private static Bitmap capture(View view, int maxPixels) {
		Bitmap bitmap = null;
		long started = PerformanceDiagnostics.now();
		boolean success = false;
		try {
			int width = view.getWidth(), height = view.getHeight();
			if (width <= 0 || height <= 0) return null;
			double scale = Math.min(1d, Math.sqrt(maxPixels / ((double) width * height)));
			bitmap = Bitmap.createBitmap(Math.max(1, (int) (width * scale)),
					Math.max(1, (int) (height * scale)), Bitmap.Config.ARGB_8888);
			Canvas canvas = new Canvas(bitmap);
			canvas.scale(bitmap.getWidth() / (float) width, bitmap.getHeight() / (float) height);
			view.draw(canvas);
			success = true;
			return bitmap;
		} catch (RuntimeException | OutOfMemoryError e) {
			if (bitmap != null) bitmap.recycle();
			return null;
		} finally {
			// Fixed local label only; never log thread identifiers, text or screenshot bytes.
			PerformanceDiagnostics.finish("Motion/threadSnapshot", started, success, true);
		}
	}
	private static float phase(float value, float start, float end) {
		return Math.max(0f, Math.min(1f, (value - start) / (end - start)));
	}

	/** Capture in cropped root coordinates without changing the live hierarchy or allocating a second scene. */
	static Bitmap captureContent(View root, Rect viewport, int maxPixels, Consumer<Canvas> draw) {
		Bitmap bitmap = null;
		long started = PerformanceDiagnostics.now();
		boolean success = false;
		try {
			int width = viewport.width(), height = viewport.height();
			if (width <= 0 || height <= 0) return null;
			double scale = Math.min(1d, Math.sqrt(maxPixels / ((double) width * height)));
			bitmap = Bitmap.createBitmap(Math.max(1, (int) (width * scale)),
					Math.max(1, (int) (height * scale)), Bitmap.Config.ARGB_8888);
			Canvas canvas = new Canvas(bitmap);
			canvas.drawColor(ThemeEngine.getTheme(root.getContext()).window);
			canvas.scale(bitmap.getWidth() / (float) width, bitmap.getHeight() / (float) height);
			canvas.translate(-viewport.left, -viewport.top);
			draw.accept(canvas);
			success = true;
			return bitmap;
		} catch (RuntimeException | OutOfMemoryError e) {
			if (bitmap != null) bitmap.recycle();
			return null;
		} finally {
			PerformanceDiagnostics.finish("Motion/threadSnapshot", started, success, true);
		}
	}
	private static float lerp(float a, float b, float progress) { return a + (b - a) * progress; }

	public final class Session {
		private final PageFragment destination;
		private final Anchor anchor;
		private final boolean returning;
		private final RectF outgoingBounds, cardBounds, incomingBounds = new RectF(), frame = new RectF();
		private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
		private final Path clip = new Path();
		private Bitmap scene, cardImage;
		private View root;
		private float originalAlpha, progress;
		private int originalAccessibility;
		private boolean ready, stopped, anchored;
		private OneShotPreDrawListener preDraw;
		private ValueAnimator animator;
		private final Runnable timeout = this::finish;
		private final Drawable layer = new BaseDrawable() {
			@Override public void draw(Canvas canvas) { drawScene(canvas); }
		};

		Session(PageFragment destination, Anchor anchor, boolean returning, RectF outgoingBounds,
				RectF cardBounds, Bitmap scene, Bitmap cardImage) {
			this.destination = destination; this.anchor = anchor; this.returning = returning;
			this.outgoingBounds = outgoingBounds;
			this.cardBounds = cardBounds != null ? cardBounds : new RectF();
			this.scene = scene; this.cardImage = cardImage;
			layer.setBounds(0, 0, host.getWidth(), host.getHeight());
		}

		/** Runs from ContentFragment.onViewCreated, before its ready list can schedule a reveal. */
		public void bind(View view) {
			if (stopped || active != this) return;
			root = view;
			InterfaceMotion.cancel(root);
			originalAlpha = root.getAlpha();
			originalAccessibility = root.getImportantForAccessibility();
			root.setTag(R.id.thread_motion_owner, this);
			root.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS);
			root.setAlpha(0f);
			preDraw = OneShotPreDrawListener.add(root, this::prepareFrame);
		}

		private void prepareFrame() {
			preDraw = null;
			if (stopped || active != this || !InterfaceMotion.isEnabled() || InterfaceMotion.duration(1) == 0) {
				finish(); return;
			}
			RectF bounds = bounds(root);
			if (bounds == null || destination.getView() != root) { finish(); return; }
			incomingBounds.set(bounds);
			anchored = !returning;
			if (returning) {
				View card = destination.findThreadMotionCard(anchor.key);
				RectF target = card != null ? visibleBounds(card) : null;
				if (target != null) {
					// Composite translucent cards over their actual wallpaper, not an invented solid
					// background. The final snapshot then matches the live catalog at handoff.
					Rect targetInRoot = new Rect(Math.round(target.left - incomingBounds.left),
							Math.round(target.top - incomingBounds.top),
							Math.round(target.right - incomingBounds.left),
							Math.round(target.bottom - incomingBounds.top));
					cardImage = captureContent(root, targetInRoot, CARD_PIXELS, destination::drawThreadMotionContent);
					if (cardImage != null) { cardBounds.set(target); anchored = true; }
				}
			} else {
				anchors.remove(destination.getRetainId());
				anchors.put(destination.getRetainId(), anchor);
				while (anchors.size() > MAX_ANCHORS) anchors.remove(anchors.keySet().iterator().next());
			}
			ready = true;
			android.util.Log.d("SlooopMotion", "profile=" + (returning ? "thread_return" : "thread_expand")
					+ " anchored=" + anchored);
			if (animator != null && animator.isStarted()) host.removeCallbacks(timeout);
			layer.invalidateSelf();
		}

		/** FragmentManager starts this animator; it still owns the transaction's lifecycle. */
		public Animator createAnimator() {
			// A preparation timeout may have released this session before FragmentManager asks for it.
			// Complete normally, rather than cancelling from inside onAnimationStart().
			if (stopped) return ValueAnimator.ofFloat(0f, 1f).setDuration(0);
			if (animator != null) return animator;
			animator = ValueAnimator.ofFloat(0f, 1f);
			animator.setDuration(InterfaceMotion.duration(returning ? RETURN_MS : ENTER_MS));
			animator.setInterpolator(new LinearInterpolator());
			animator.addUpdateListener(value -> {
				if (!stopped) { progress = (float) value.getAnimatedValue(); layer.invalidateSelf(); }
			});
			animator.addListener(new AnimatorListenerAdapter() {
				@Override public void onAnimationStart(Animator animation) {
					if (stopped) { animation.cancel(); return; }
					host.removeCallbacks(timeout);
					// Bounds must be ready within a frame; never wait for a network response.
					// Do not cap a started transition: Android's 5x/10x duration scale is legitimate.
					if (!ready) host.postDelayed(timeout, PREPARE_TIMEOUT_MS);
				}
				@Override public void onAnimationEnd(Animator animation) { release(); }
				@Override public void onAnimationCancel(Animator animation) { release(); }
			});
			return animator;
		}

		public void finish() {
			if (stopped) return;
			// Unblock/restore first: cancelling can synchronously call FragmentManager listeners.
			release();
			if (animator != null) animator.cancel();
		}
		private void release() {
			if (stopped) return;
			stopped = true;
			host.removeCallbacks(timeout);
			if (preDraw != null) { preDraw.removeListener(); preDraw = null; }
			if (root != null && root.getTag(R.id.thread_motion_owner) == this) {
				root.setTag(R.id.thread_motion_owner, null);
				root.setAlpha(originalAlpha);
				root.setImportantForAccessibility(originalAccessibility);
			}
			host.getOverlay().remove(layer);
			if (active == this) { active = null; host.setMotionBlocking(false); }
			if (scene != null) { scene.recycle(); scene = null; }
			if (cardImage != null) { cardImage.recycle(); cardImage = null; }
			root = null;
		}

		private void bitmap(Canvas canvas, Bitmap bitmap, RectF rect, float alpha) {
			paint.setAlpha(Math.round(255f * Math.max(0f, Math.min(1f, alpha))));
			canvas.drawBitmap(bitmap, null, rect, paint);
			paint.setAlpha(255);
		}
		private void liveRoot(Canvas canvas, RectF rect, float alpha) {
			if (alpha <= 0f || root == null) return;
			int save = canvas.save();
			canvas.translate(rect.left, rect.top);
			float scale = rect.width() / root.getWidth();
			canvas.scale(scale, scale); // Uniform scaling: text is never stretched independently in X/Y.
			int opacity = canvas.saveLayerAlpha(0, 0, root.getWidth(), root.getHeight(), Math.round(alpha * 255));
			// draw() bypasses parent alpha; only this overlay renders the temporarily hidden root.
			root.draw(canvas);
			canvas.restoreToCount(opacity); canvas.restoreToCount(save);
		}
		private void clippedBitmap(Canvas canvas, Bitmap bitmap, RectF rect, RectF natural, float alpha) {
			float scale = rect.width() / natural.width();
			RectF image = new RectF(rect.left, rect.top, rect.right, rect.top + natural.height() * scale);
			bitmap(canvas, bitmap, image, alpha);
		}
		private void drawScene(Canvas canvas) {
			if (stopped || scene == null) return;
			int save = canvas.save();
			canvas.clipRect(0, 0, host.getWidth(), host.getHeight());
			paint.setColor(ThemeEngine.getTheme(host.getContext()).window);
			canvas.drawRect(0, 0, host.getWidth(), host.getHeight(), paint);
			if (!ready) {
				bitmap(canvas, scene, outgoingBounds, 1f);
				canvas.restoreToCount(save); return;
			}
			float t = EMPHASIZED.getInterpolation(progress);
			if (!anchored) {
				// A missing/filtered/off-screen return card must never collapse toward stale coordinates.
				bitmap(canvas, scene, outgoingBounds, 1f - phase(progress, 0f, .3f));
				RectF reveal = new RectF(incomingBounds);
				float direction = root.getLayoutDirection() == View.LAYOUT_DIRECTION_RTL ? 1f : -1f;
				reveal.offset(direction * 24f * ResourceUtils.obtainDensity(host) * (1f - t), 0f);
				liveRoot(canvas, reveal, phase(progress, .12f, .7f));
				canvas.restoreToCount(save); return;
			}
			RectF from = returning ? outgoingBounds : cardBounds;
			RectF to = returning ? cardBounds : incomingBounds;
			frame.set(lerp(from.left, to.left, t), lerp(from.top, to.top, t),
					lerp(from.right, to.right, t), lerp(from.bottom, to.bottom, t));
			if (returning) liveRoot(canvas, incomingBounds, 1f);
			else bitmap(canvas, scene, outgoingBounds, 1f - phase(progress, .65f, 1f));
			float radius = 12f * ResourceUtils.obtainDensity(host) * (returning ? t : 1f - t);
			clip.rewind(); clip.addRoundRect(frame, radius, radius, Path.Direction.CW);
			int container = canvas.save(); canvas.clipPath(clip);
			paint.setColor(ThemeEngine.getTheme(host.getContext()).window);
			canvas.drawRect(frame, paint);
			if (returning) {
				// Geometry moves on the emphasized curve, not wall-clock progress. Finish the old
				// message layout before the container nears the card; never superimpose two texts.
				float oldAlpha = ThreadReturnPresentation.messagesAlpha(t);
				float cardAlpha = ThreadReturnPresentation.cardAlpha(t);
				if (oldAlpha > 0f) clippedBitmap(canvas, scene, frame, outgoingBounds, oldAlpha);
				if (cardAlpha > 0f) clippedBitmap(canvas, cardImage, frame, cardBounds, cardAlpha);
			} else {
				liveRoot(canvas, frame, phase(progress, .12f, .6f));
				clippedBitmap(canvas, cardImage, frame, cardBounds, 1f - phase(progress, .08f, .45f));
			}
			canvas.restoreToCount(container); canvas.restoreToCount(save);
		}
	}
}
