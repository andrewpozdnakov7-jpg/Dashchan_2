package com.mishiranu.dashchan.ui.posting;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ValueAnimator;
import android.content.ClipData;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.Point;
import android.graphics.PointF;
import android.graphics.RectF;
import android.view.DragEvent;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import androidx.core.view.OneShotPreDrawListener;
import com.mishiranu.dashchan.util.InterfaceMotion;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;

/** Native drag transports coordinates only. A window-local overlay owns the sole drag image.
 * Lift/hover/drop/cancel timings match the accepted player editor; ordering does NOT use its swap model. */
final class AttachmentGridMotion {
	private final PostingAttachmentsAppearance ui;
	private final Map<AttachmentHolder, PointF> grabs = new IdentityHashMap<>();
	private Session session;
	private boolean closed;
	private static boolean animated() { return InterfaceMotion.isEnabled() && InterfaceMotion.duration(1) > 0; }

	private final class Session {
		final AttachmentHolder source;
		final ViewGroup root;
		final List<AttachmentHolder> before = new ArrayList<>(ui.host.attachments());
		final Map<AttachmentHolder, Bitmap> images = new IdentityHashMap<>();
		final Map<AttachmentHolder, RectF> old = new IdentityHashMap<>(), end = new IdentityHashMap<>();
		final PointF grab;
		final Overlay overlay;
		final View.OnLayoutChangeListener resize;
		List<AttachmentHolder> expected;
		AttachmentHolder target;
		float screenX, screenY, x, y, lift, scale = 1, progress, highlight;
		boolean active = true, inside, committed, committing, settling, ended, layoutApplied;
		ValueAnimator liftAnimator, dimAnimator, hoverAnimator, settleAnimator;
		OneShotPreDrawListener preDraw;
		Runnable finish;
		Session(AttachmentHolder source, ViewGroup root) {
			this.source = source; this.root = root;
			grab = grabs.containsKey(source) ? grabs.get(source) : new PointF(source.view.getWidth() / 2f, source.view.getHeight() / 2f);
			overlay = new Overlay(this);
			resize = (v, l, t, r, b, ol, ot, or, ob) -> {
				if (session == this && (r - l != or - ol || b - t != ob - ot)) cancel();
			};
		}
	}
	AttachmentGridMotion(PostingAttachmentsAppearance ui) {
		this.ui = ui;
		ui.container.addOnAttachStateChangeListener(new View.OnAttachStateChangeListener() {
			@Override public void onViewAttachedToWindow(View v) {}
			@Override public void onViewDetachedFromWindow(View v) { cancel(); }
		});
	}
	void grab(AttachmentHolder holder, View handle, MotionEvent event) {
		if (event.getActionMasked() != MotionEvent.ACTION_DOWN || session != null) return;
		int[] at = location(handle), card = location(holder.view);
		grabs.put(holder, new PointF(at[0] - card[0] + event.getX(), at[1] - card[1] + event.getY()));
	}
	boolean start(AttachmentHolder source) {
		if (closed || session != null || !ui.grid.isLaidOut() || !ui.grid.isAttachedToWindow()
				|| ui.host.attachments().size() < 2 || !ui.host.attachments().contains(source)) return false;
		View rootView = ui.container.getRootView();
		if (!(rootView instanceof ViewGroup)) return false;
		Session s = new Session(source, (ViewGroup) rootView);
		try {
			for (AttachmentHolder holder : s.before) {
				View view = holder.view;
				if (view.getWidth() <= 0 || view.getHeight() <= 0) { release(s); return false; }
				Bitmap image = Bitmap.createBitmap(view.getWidth(), view.getHeight(), Bitmap.Config.ARGB_8888);
				s.images.put(holder, image); view.draw(new Canvas(image));
			}
		} catch (RuntimeException | OutOfMemoryError e) { release(s); return false; }
		capture(s, s.old); RectF sourceBounds = s.old.get(source);
		s.x = sourceBounds.left; s.y = sourceBounds.top;
		int[] root = location(s.root); s.screenX = root[0] + s.x + s.grab.x; s.screenY = root[1] + s.y + s.grab.y;
		session = s;
		s.overlay.layout(0, 0, s.root.getWidth(), s.root.getHeight());
		s.root.getOverlay().add(s.overlay); s.root.addOnLayoutChangeListener(s.resize);
		boolean started = source.view.startDragAndDrop(ClipData.newPlainText("attachment", ""), new View.DragShadowBuilder() {
			@Override public void onProvideShadowMetrics(Point size, Point touch) { size.set(1, 1); touch.set(0, 0); }
			@Override public void onDrawShadow(Canvas canvas) {}
		}, s, 0);
		if (!started) { cancel(); return false; }
		if (session != s) return true;
		if (animated()) {
			s.liftAnimator = animator(120, f -> {
				if (session == s) { s.lift = ui.dp(8) * f; s.scale = 1 + .06f * f; s.overlay.invalidate(); }
			});
			s.dimAnimator = animator(100, f -> { if (session == s) source.view.setAlpha(1 - .78f * f); });
			s.liftAnimator.start(); s.dimAnimator.start();
		} else source.view.setAlpha(.22f);
		return true;
	}
	boolean dispatch(DragEvent event) {
		Session s = session; if (s == null || event.getLocalState() != s) return false;
		switch (event.getAction()) {
			case DragEvent.ACTION_DRAG_STARTED: return s.active;
			case DragEvent.ACTION_DRAG_ENTERED: s.inside = true; return true;
			case DragEvent.ACTION_DRAG_LOCATION:
				if (s.active && !s.committed) { locate(s, event.getX(), event.getY()); ui.container.removeCallbacks(autoScroll); ui.container.postOnAnimation(autoScroll); }
				return true;
			case DragEvent.ACTION_DRAG_EXITED:
				s.inside = false; highlight(s, null); ui.container.removeCallbacks(autoScroll); return true;
			case DragEvent.ACTION_DROP:
				if (!s.active || s.committed || !sameOrder(s.before, ui.host.attachments())) return false;
				locate(s, event.getX(), event.getY());
				int index = s.target != null ? ui.host.attachments().indexOf(s.target) : -1;
				if (index < 0 || s.target == s.source) return false;
				capture(s, s.old); stop(s.liftAnimator); stop(s.dimAnimator); stop(s.hoverAnimator);
				s.committing = true;
				try { ui.host.move(s.source, index); } finally { s.committing = false; }
				if (session != s) return true;
				s.committed = true; s.expected = new ArrayList<>(ui.host.attachments());
				ui.container.removeCallbacks(autoScroll);
				s.settling = true; hideReal(s); s.overlay.invalidate();
				return true;
			case DragEvent.ACTION_DRAG_ENDED:
				if (s.ended) return true;
				s.ended = true; s.active = false; ui.container.removeCallbacks(autoScroll);
				stop(s.liftAnimator); stop(s.dimAnimator); stop(s.hoverAnimator);
				s.finish = () -> finishNative(s); s.root.post(s.finish); return true;
			default: return true;
		}
	}
	private void locate(Session s, float x, float y) {
		int[] at = location(ui.container); s.screenX = at[0] + x; s.screenY = at[1] + y;
		int[] root = location(s.root); s.x = s.screenX - root[0] - s.grab.x; s.y = s.screenY - root[1] - s.grab.y;
		s.inside = x >= 0 && x < ui.container.getWidth() && y >= 0 && y < ui.container.getHeight();
		AttachmentHolder target = null; float best = Float.MAX_VALUE;
		RectF visible = bounds(ui.scroll != null ? ui.scroll : ui.container, s.root);
		if (s.inside && visible.contains(s.screenX - root[0], s.screenY - root[1])) {
			for (AttachmentHolder holder : ui.host.attachments()) {
				RectF rect = bounds(holder.view, s.root);
				if (rect.contains(s.screenX - root[0], s.screenY - root[1])) { target = holder; break; }
				float dx = s.screenX - root[0] - rect.centerX(), dy = s.screenY - root[1] - rect.centerY();
				float distance = dx * dx + dy * dy;
				if (distance < best) { best = distance; target = holder; }
			}
		}
		highlight(s, target); s.overlay.invalidate();
	}
	private void highlight(Session s, AttachmentHolder target) {
		if (s.target == target) return;
		stop(s.hoverAnimator); s.target = target; s.highlight = 0;
		if (animated()) { s.hoverAnimator = animator(100, f -> { if (session == s) { s.highlight = f; s.overlay.invalidate(); } }); s.hoverAnimator.start(); }
		else s.highlight = 1;
	}
	private final Runnable autoScroll = new Runnable() {
		@Override public void run() {
			Session s = session; if (s == null || !s.active || s.committed || s.ended || !s.inside || ui.scroll == null) return;
			int[] at = location(ui.scroll); int direction = s.screenY < at[1] + ui.dp(48) ? -1
					: s.screenY > at[1] + ui.scroll.getHeight() - ui.dp(48) ? 1 : 0;
			if (direction != 0 && ui.scroll.canScrollVertically(direction)) {
				ui.scroll.scrollBy(0, direction * ui.dp(12));
				int[] container = location(ui.container); locate(s, s.screenX - container[0], s.screenY - container[1]);
				ui.container.postOnAnimation(this);
			}
		}
	};
	private void finishNative(Session s) {
		if (session != s) return;
		if (!sameOrder(s.committed ? s.expected : s.before, ui.host.attachments()) || !ui.grid.isAttachedToWindow()) { cancel(); return; }
		if (!animated()) { cancel(); return; }
		if (!s.committed) capture(s, s.old);
		s.settling = true; hideReal(s); s.overlay.invalidate();
		// Reparent only after native drag has ended; never detach its source inside DROP.
		if (s.committed) {
			s.committing = true;
			try { ui.host.rebuild(); s.layoutApplied = true; } finally { s.committing = false; }
		}
		s.preDraw = OneShotPreDrawListener.add(ui.grid, () -> {
			s.preDraw = null; if (session != s) return;
			capture(s, s.end);
			s.settleAnimator = animator(s.committed ? 260 : 220, f -> {
				if (session == s) { s.progress = f; s.overlay.invalidate(); }
			});
			s.settleAnimator.addListener(new AnimatorListenerAdapter() {
				@Override public void onAnimationEnd(Animator animator) { if (session == s) cancel(); }
			});
			s.settleAnimator.start();
		});
		ui.grid.invalidate();
	}
	private void hideReal(Session s) { for (AttachmentHolder holder : s.before) holder.view.setAlpha(0); }
	void beforeRebuild() { if (session != null && !session.committing) cancel(); }
	boolean isCommitting() { return session != null && session.committing; }
	void cancel() {
		Session s = session; if (s == null) return;
		session = null;
		ui.container.removeCallbacks(autoScroll);
		if (s.finish != null) s.root.removeCallbacks(s.finish);
		if (s.preDraw != null) s.preDraw.removeListener();
		s.root.removeOnLayoutChangeListener(s.resize);
		stop(s.liftAnimator); stop(s.dimAnimator); stop(s.hoverAnimator); stop(s.settleAnimator);
		if (s.active && !s.ended) s.source.view.cancelDragAndDrop();
		s.root.getOverlay().remove(s.overlay);
		for (AttachmentHolder holder : s.before) { holder.view.setAlpha(1); holder.view.setScaleX(1); holder.view.setScaleY(1); holder.view.setTranslationX(0); holder.view.setTranslationY(0); holder.view.setElevation(0); }
		if (s.committed && !s.layoutApplied && ui.grid.isAttachedToWindow()) ui.host.rebuild();
		release(s);
	}
	void close() { cancel(); closed = true; grabs.clear(); }
	void prune() { grabs.keySet().retainAll(ui.host.attachments()); }
	private static boolean sameOrder(List<AttachmentHolder> first, List<AttachmentHolder> second) {
		if (first == null || first.size() != second.size()) return false;
		for (int i = 0; i < first.size(); i++) if (first.get(i) != second.get(i)) return false;
		return true;
	}
	private void capture(Session s, Map<AttachmentHolder, RectF> target) {
		target.clear(); for (AttachmentHolder holder : ui.host.attachments()) target.put(holder, bounds(holder.view, s.root));
	}
	private static int[] location(View view) { int[] at = new int[2]; view.getLocationOnScreen(at); return at; }
	private static RectF bounds(View view, View root) {
		int[] at = location(view), base = location(root);
		return new RectF(at[0] - base[0], at[1] - base[1], at[0] - base[0] + view.getWidth(), at[1] - base[1] + view.getHeight());
	}
	private static void release(Session s) { for (Bitmap image : s.images.values()) image.recycle(); s.images.clear(); }
	private interface Frame { void draw(float progress); }
	private static ValueAnimator animator(int duration, Frame frame) {
		ValueAnimator animator = ValueAnimator.ofFloat(0, 1); animator.setDuration(InterfaceMotion.duration(duration));
		animator.setInterpolator(InterfaceMotion.STANDARD); animator.addUpdateListener(a -> frame.draw((float) a.getAnimatedValue())); return animator;
	}
	private static void stop(ValueAnimator animator) { if (animator != null) animator.cancel(); }
	private final class Overlay extends View {
		final Session owner;
		final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
		final Path outline = new Path();
		Overlay(Session owner) { super(ui.container.getContext()); this.owner = owner; setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO); }
		@Override protected void onDraw(Canvas canvas) {
			Session s = owner; if (session != s) return;
			if (!s.settling && s.target != null && s.target != s.source) {
				RectF target = bounds(s.target.view, s.root);
				paint.setStyle(Paint.Style.FILL); paint.setColor(ui.primary); paint.setAlpha((int) (28 * s.highlight));
				canvas.drawRoundRect(target, ui.dp(12), ui.dp(12), paint);
				paint.setStyle(Paint.Style.STROKE); paint.setStrokeWidth(ui.dp(2)); paint.setAlpha((int) (255 * s.highlight));
				canvas.drawRoundRect(target, ui.dp(12), ui.dp(12), paint); paint.setStyle(Paint.Style.FILL);
			}
			if (s.settling) for (AttachmentHolder holder : s.before) if (holder != s.source) drawCard(canvas, s, holder);
			drawCard(canvas, s, s.source);
		}
		private void drawCard(Canvas canvas, Session s, AttachmentHolder holder) {
			Bitmap image = s.images.get(holder); RectF from = s.old.get(holder); if (image == null || from == null) return;
			from = new RectF(from);
			if (holder == s.source) {
				float width = from.width() * s.scale, height = from.height() * s.scale;
				from.offsetTo(s.x - s.grab.x * (s.scale - 1), s.y - s.lift - s.grab.y * (s.scale - 1));
				from.right = from.left + width; from.bottom = from.top + height;
			}
			RectF end = s.end.get(holder);
			if (s.settling && end != null) {
				float p = s.progress;
				from.set(from.left + (end.left - from.left) * p, from.top + (end.top - from.top) * p,
						from.right + (end.right - from.right) * p, from.bottom + (end.bottom - from.bottom) * p);
			}
			paint.setAlpha(255); paint.setStyle(Paint.Style.FILL);
			if (holder == s.source && animated()) {
				paint.setColor(0xff000000); paint.setAlpha((int) (30 * (s.settling ? 1 - s.progress : 1)));
				RectF shadow = new RectF(from); shadow.offset(0, ui.dp(4)); canvas.drawRoundRect(shadow, ui.dp(12), ui.dp(12), paint); paint.setAlpha(255);
			}
			int save = canvas.save();
			outline.reset(); outline.addRoundRect(from, ui.dp(12), ui.dp(12), Path.Direction.CW);
			canvas.clipPath(outline); canvas.drawBitmap(image, null, from, paint); canvas.restoreToCount(save);
		}
	}
}
