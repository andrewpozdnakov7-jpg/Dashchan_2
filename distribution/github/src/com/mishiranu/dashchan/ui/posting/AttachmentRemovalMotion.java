package com.mishiranu.dashchan.ui.posting;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ValueAnimator;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.RectF;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewTreeObserver;
import androidx.core.view.OneShotPreDrawListener;
import com.mishiranu.dashchan.util.InterfaceMotion;
import com.mishiranu.dashchan.widget.ElementMotion;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;

/** Model/draft deletion is immediate. Only a bounded, window-local outgoing image and live poses animate. */
final class AttachmentRemovalMotion {
	private static final int EXIT_MS = 160, MOVE_MS = 260, MAX_PIXELS = 2 * 1024 * 1024;
	private final PostingAttachmentsAppearance ui;
	private Session session;
	private boolean closed;
	AttachmentRemovalMotion(PostingAttachmentsAppearance ui) { this.ui = ui; }
	private boolean allowed() {
		return !closed && ui.grid.isAttachedToWindow() && ui.grid.isShown() && ui.grid.hasWindowFocus()
				&& InterfaceMotion.isEnabled() && InterfaceMotion.duration(1) > 0 && !ElementMotion.busy(ui.container);
	}
	private static int[] at(View view) { int[] at = new int[2]; view.getLocationOnScreen(at); return at; }
	private static boolean same(List<AttachmentHolder> first, List<AttachmentHolder> second) {
		if (first.size() != second.size()) return false;
		for (int i = 0; i < first.size(); i++) if (first.get(i) != second.get(i)) return false;
		return true;
	}
	private static final class Pose {
		final View view;
		final float x, y, tx, ty;
		Pose(View view) {
			this.view = view; int[] at = at(view); x = at[0]; y = at[1];
			tx = view.getTranslationX(); ty = view.getTranslationY();
		}
		void restore() { view.setTranslationX(tx); view.setTranslationY(ty); }
	}
	private final class Session {
		final ViewGroup root;
		final List<AttachmentHolder> expected;
		final Map<AttachmentHolder, Pose> before = new IdentityHashMap<>();
		final Map<AttachmentHolder, float[]> offsets = new IdentityHashMap<>();
		final Map<AttachmentHolder, Rect> targets = new IdentityHashMap<>();
		final RectF outgoingBounds;
		final Rect clip;
		final Overlay overlay;
		final int width, height, gridWidth;
		final View.OnLayoutChangeListener resize;
		final View.OnAttachStateChangeListener attachment;
		final ViewTreeObserver.OnScrollChangedListener scroll;
		final ViewTreeObserver.OnWindowFocusChangeListener focus;
		Bitmap image;
		OneShotPreDrawListener preDraw;
		ValueAnimator animator;
		float exit, moveStart;
		boolean committed, reflow;
		Session(ViewGroup root, AttachmentHolder removed, Rect clip) {
			this.root = root; this.clip = clip;
			expected = new ArrayList<>(ui.host.attachments()); expected.remove(removed);
			for (AttachmentHolder holder : expected) before.put(holder, new Pose(holder.view));
			int[] cardAt = at(removed.view), gridAt = at(ui.grid);
			// Store grid-local bounds, not a frozen window position. If automatic
			// editor sizing moves the grid, the outgoing paint must not cover the editor.
			outgoingBounds = new RectF(cardAt[0] - gridAt[0], cardAt[1] - gridAt[1],
					cardAt[0] - gridAt[0] + removed.view.getWidth(), cardAt[1] - gridAt[1] + removed.view.getHeight());
			width = root.getWidth(); height = root.getHeight(); gridWidth = ui.grid.getWidth();
			overlay = new Overlay(this);
			resize = (v, l, t, r, b, ol, ot, or, ob) -> {
				if (session == this && (r - l != width || b - t != height)) finish();
			};
			attachment = new View.OnAttachStateChangeListener() {
				@Override public void onViewAttachedToWindow(View view) {}
				@Override public void onViewDetachedFromWindow(View view) { if (session == Session.this) finish(); }
			};
			scroll = () -> {
				if (session != this) return;
				// The first layout may clamp ScrollView after deletion. Before capturing
				// destinations the snapshot follows grid-local bounds, so that is safe.
				if (reflow) finish(); else overlay.invalidate();
			};
			focus = hasFocus -> { if (!hasFocus && session == this) finish(); };
		}
	}

	void prepare(AttachmentHolder removed) {
		finish();
		if (!allowed() || !ui.host.attachments().contains(removed) || removed.view.getWidth() <= 0
				|| removed.view.getHeight() <= 0 || !(ui.container.getRootView() instanceof ViewGroup)) return;
		ViewGroup root = (ViewGroup) ui.container.getRootView();
		Rect clip = new Rect();
		if (!(ui.scroll != null && ui.scroll.isAttachedToWindow() ? ui.scroll : ui.container).getGlobalVisibleRect(clip)) return;
		int[] rootAt = at(root); clip.offset(-rootAt[0], -rootAt[1]);
		Session s = new Session(root, removed, clip);
		try {
			// One deleted card only, bounded independently of density/font size. Never cache on disk.
			int w = removed.view.getWidth(), h = removed.view.getHeight();
			double scale = Math.min(1d, Math.sqrt((double) MAX_PIXELS / ((double) w * h)));
			int imageWidth = Math.max(1, (int) (w * scale)), imageHeight = Math.max(1, (int) (h * scale));
			s.image = Bitmap.createBitmap(imageWidth, imageHeight, Bitmap.Config.ARGB_8888);
			Canvas canvas = new Canvas(s.image); canvas.scale((float) imageWidth / w, (float) imageHeight / h);
			removed.view.draw(canvas);
		} catch (RuntimeException | OutOfMemoryError e) { if (s.image != null) s.image.recycle(); return; }
		session = s;
		root.addOnLayoutChangeListener(s.resize); ui.container.addOnAttachStateChangeListener(s.attachment);
		root.getViewTreeObserver().addOnScrollChangedListener(s.scroll);
		root.getViewTreeObserver().addOnWindowFocusChangeListener(s.focus);
		trace("removal_prepare", "remaining=" + s.expected.size());
	}
	/** Called at the end of the controller's existing rebuild, after model removal. */
	void committed() {
		Session s = session; if (s == null) return;
		if (s.committed || !same(s.expected, ui.host.attachments())) { finish(); return; }
		if (!allowed() || ui.grid.getWidth() != s.gridWidth
				|| s.root.getWidth() != s.width || s.root.getHeight() != s.height) { finish(); return; }
		s.committed = true;
		s.overlay.layout(0, 0, s.width, s.height); s.root.getOverlay().add(s.overlay);
		// Start the bounded exit clock now, independently of pre-draw/layout readiness.
		// No visible snapshot exists before this clock owns its eventual cleanup.
		ValueAnimator animator = ValueAnimator.ofFloat(0f, 1f); s.animator = animator;
		animator.setDuration(InterfaceMotion.duration(s.before.isEmpty() ? EXIT_MS : MOVE_MS));
		animator.setInterpolator(InterfaceMotion.STANDARD);
		animator.addUpdateListener(value -> {
			if (session != s || s.animator != value) return;
			if (!allowed() || !same(s.expected, ui.host.attachments()) || ui.grid.getWidth() != s.gridWidth
					|| s.reflow && !targetsUnchanged(s)) { finish(); return; }
			apply(s, (float) value.getAnimatedValue());
		});
		animator.addListener(new AnimatorListenerAdapter() {
			@Override public void onAnimationEnd(Animator animation) { if (session == s) finish(); }
		});
		awaitReflow(s);
		trace("removal_commit", "durationMs=" + animator.getDuration() + " remaining=" + s.expected.size());
		animator.start();
	}
	private void awaitReflow(Session s) {
		s.preDraw = OneShotPreDrawListener.add(ui.grid, () -> { s.preDraw = null; startReflow(s); });
	}
	private void startReflow(Session s) {
		if (session != s) return;
		if (!allowed() || !same(s.expected, ui.host.attachments())) { finish(); return; }
		// Check only the geometry we animate, not DecorView.isLayoutRequested().
		// A later sizing pass may defer reflow, but cannot defer exit/cleanup forever.
		boolean pending = ui.grid.isLayoutRequested() || ui.grid.getWidth() != ui.grid.getMeasuredWidth();
		for (Pose pose : s.before.values()) pending |= pose.view.isLayoutRequested()
				|| pose.view.getWidth() != pose.view.getMeasuredWidth() || pose.view.getHeight() != pose.view.getMeasuredHeight();
		if (pending) {
			trace("removal_wait", "gridRequested=" + ui.grid.isLayoutRequested());
			awaitReflow(s); return;
		}
		for (Map.Entry<AttachmentHolder, Pose> entry : s.before.entrySet()) {
			Pose before = entry.getValue();
			if (before.view.getParent() != ui.grid) { finish(); return; }
			int[] now = at(before.view);
			s.offsets.put(entry.getKey(), new float[] {before.x - now[0], before.y - now[1]});
			s.targets.put(entry.getKey(), new Rect(before.view.getLeft(), before.view.getTop(),
					before.view.getRight(), before.view.getBottom()));
		}
		s.moveStart = (float) s.animator.getAnimatedValue(); s.reflow = true;
		apply(s, s.moveStart);
		trace("removal_reflow", "remaining=" + s.before.size());
	}
	private boolean targetsUnchanged(Session s) {
		for (Map.Entry<AttachmentHolder, Rect> entry : s.targets.entrySet()) {
			View view = entry.getKey().view; Rect target = entry.getValue();
			if (view.getParent() != ui.grid || view.getLeft() != target.left || view.getTop() != target.top
					|| view.getRight() != target.right || view.getBottom() != target.bottom) return false;
		}
		return true;
	}
	private void apply(Session s, float fraction) {
		float remaining = s.moveStart < 1f ? Math.min(1f, (1f - fraction) / (1f - s.moveStart)) : 0f;
		for (Map.Entry<AttachmentHolder, float[]> entry : s.offsets.entrySet()) {
			Pose before = s.before.get(entry.getKey()); float[] offset = entry.getValue();
			before.view.setTranslationX(before.tx + offset[0] * remaining);
			before.view.setTranslationY(before.ty + offset[1] * remaining);
		}
		s.exit = InterfaceMotion.STANDARD.getInterpolation(Math.min(1f, s.animator.getCurrentPlayTime() / (float) EXIT_MS));
		s.overlay.invalidate();
	}
	private void trace(String event, String values) { PostingFormDiagnostics.trace(ui.container, event, values); }
	void beforeRebuild() { if (session != null && session.committed) finish(); }
	// Package-local view-test probes; never expose files, names or user content.
	boolean isActive() { return session != null; }
	void endAnimation() { if (session != null && session.animator != null) session.animator.end(); }
	void finish() {
		Session s = session; if (s == null) return; session = null;
		if (s.preDraw != null) s.preDraw.removeListener();
		if (s.animator != null) { s.animator.removeAllUpdateListeners(); s.animator.removeAllListeners(); s.animator.cancel(); }
		for (Pose pose : s.before.values()) pose.restore();
		s.root.removeOnLayoutChangeListener(s.resize); ui.container.removeOnAttachStateChangeListener(s.attachment);
		if (s.root.getViewTreeObserver().isAlive()) {
			s.root.getViewTreeObserver().removeOnScrollChangedListener(s.scroll);
			s.root.getViewTreeObserver().removeOnWindowFocusChangeListener(s.focus);
		}
		s.root.getOverlay().remove(s.overlay);
		if (s.image != null) { s.image.recycle(); s.image = null; }
		trace("removal_finish", "committed=" + s.committed + " reflow=" + s.reflow);
	}
	void close() { finish(); closed = true; }

	private final class Overlay extends View {
		final Session owner;
		final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
		Overlay(Session owner) { super(ui.container.getContext()); this.owner = owner; setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO); }
		@Override protected void onDraw(Canvas canvas) {
			Session s = owner; if (session != s || s.image == null || s.image.isRecycled()) return;
			int save = canvas.save(); canvas.clipRect(s.clip);
			RectF bounds = new RectF(s.outgoingBounds);
			int[] gridAt = at(ui.grid), rootAt = at(s.root);
			bounds.offset(gridAt[0] - rootAt[0], gridAt[1] - rootAt[1]);
			float insetX = bounds.width() * .05f * s.exit, insetY = bounds.height() * .05f * s.exit;
			bounds.inset(insetX, insetY); paint.setAlpha(Math.round(255 * (1f - s.exit)));
			canvas.drawBitmap(s.image, null, bounds, paint); canvas.restoreToCount(save);
		}
	}
}
