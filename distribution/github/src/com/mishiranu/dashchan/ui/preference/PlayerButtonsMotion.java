package com.mishiranu.dashchan.ui.preference;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ValueAnimator;
import android.content.ClipData;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Point;
import android.graphics.PointF;
import android.graphics.RectF;
import android.view.DragEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.accessibility.AccessibilityNodeInfo;
import androidx.annotation.NonNull;
import androidx.core.view.OneShotPreDrawListener;
import androidx.recyclerview.widget.RecyclerView;
import com.mishiranu.dashchan.R;
import com.mishiranu.dashchan.content.Preferences;
import com.mishiranu.dashchan.media.PlayerButtonsLayout;
import com.mishiranu.dashchan.media.PlayerButtonsLayout.Button;
import com.mishiranu.dashchan.util.InterfaceMotion;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.Map;

/** One owner/session, one visible drag image, and no persistence from animation callbacks. */
final class PlayerButtonsMotion {
	static final class Target {
		final Button button;
		final boolean right;
		Target(Button button, boolean right) { this.button = button; this.right = right; }
		boolean same(Target other) { return other != null && button == other.button && right == other.right; }
	}
	static PlayerButtonsLayout dropped(PlayerButtonsLayout current, Button source, Target target) {
		return target == null ? current : target.button != null ? current.swap(source, target.button)
				: current.moveToEnd(source, target.right);
	}
	private final PlayerButtonsEditorView editor;
	private final EnumMap<Button, PointF> grabs = new EnumMap<>(Button.class);
	private Session session;
	private long generation;
	private static boolean animate() { return InterfaceMotion.isEnabled() && InterfaceMotion.duration(1) > 0; }

	private final class Session {
		final long token = ++generation;
		final Button source;
		final ViewGroup root;
		final PlayerButtonsLayout before = Preferences.getVideoButtonsLayout();
		final EnumMap<Button, Bitmap> snapshots = new EnumMap<>(Button.class);
		final EnumMap<Button, RectF> old = new EnumMap<>(Button.class), end = new EnumMap<>(Button.class);
		final RectF[] oldPanels = new RectF[2], endPanels = new RectF[2];
		final Map<View, RectF> oldFurniture = new HashMap<>(), endFurniture = new HashMap<>();
		RectF oldCard, endCard;
		final Overlay overlay;
		final PointF grab;
		final View.OnLayoutChangeListener rootLayout;
		final RecyclerView recycler;
		final RecyclerView.OnScrollListener scrollListener;
		ValueAnimator liftAnimator, dimAnimator, hoverAnimator, settleAnimator;
		OneShotPreDrawListener preDraw;
		Runnable endedPost;
		PlayerButtonsLayout committed;
		Target target;
		Button focus;
		boolean restoreAccessibility, restoreKeyboard, ended, nativeActive, settling, inside;
		float screenX, screenY, imageX, imageY, lift, scale = 1, progress;
		Session(Button source, ViewGroup root) {
			this.source = source; this.root = root;
			grab = source != null && grabs.containsKey(source) ? grabs.get(source)
					: new PointF(editor.board.cellWidth / 2f, editor.board.cellHeight / 2f);
			overlay = new Overlay(this);
			rootLayout = (v, l, t, r, b, ol, ot, or, ob) -> {
				if (current(this) && (r - l != or - ol || b - t != ob - ot)) cleanup(true);
			};
			recycler = editor.recycler();
			scrollListener = new RecyclerView.OnScrollListener() {
				@Override public void onScrolled(@NonNull RecyclerView view, int dx, int dy) {
					if (current(Session.this) && !nativeActive && (dx != 0 || dy != 0)) cleanup(false);
				}
			};
		}
	}
	PlayerButtonsMotion(PlayerButtonsEditorView editor) { this.editor = editor; }
	void setGrab(Button button, float x, float y) { if (session == null) grabs.put(button, new PointF(x, y)); }
	boolean isDragging() { return session != null && session.nativeActive; }
	boolean owns(Object state) { return session != null && state == session; }
	private boolean current(Session s) { return session == s && s.token == generation; }

	boolean start(Button button) {
		ViewGroup root = editor.overlayRoot();
		if (session != null || root == null || !root.isAttachedToWindow() || !editor.isLaidOut()) return false;
		Session s = capture(button, root);
		if (s == null) return false;
		session = s;
		View source = editor.board.buttons.get(button);
		RectF rect = s.old.get(button);
		s.imageX = rect.left; s.imageY = rect.top;
		s.nativeActive = true;
		root.getOverlay().add(s.overlay);
		root.addOnLayoutChangeListener(s.rootLayout);
		if (s.recycler != null) s.recycler.addOnScrollListener(s.scrollListener);
		// Native transport only. The protected window's overlay draws the sole visible image.
		boolean started = source.startDragAndDrop(ClipData.newPlainText("player-button", ""),
				new View.DragShadowBuilder() {
					@Override public void onProvideShadowMetrics(Point size, Point touch) { size.set(1, 1); touch.set(0, 0); }
					@Override public void onDrawShadow(Canvas canvas) {}
				}, s, 0);
		if (!started) { cleanup(false); return false; }
		if (!current(s)) return true;
		editor.setDragging(true);
		editor.sayStatus(editor.getResources().getString(R.string.player_buttons_drag_start, editor.label(button)));
		if (animate()) {
			s.liftAnimator = animator(120, f -> {
				if (!current(s)) return;
				s.lift = editor.dp(8) * f; s.scale = 1 + .06f * f; s.overlay.invalidateSelf();
			});
			s.dimAnimator = animator(100, f -> { if (current(s)) source.setAlpha(1 - .78f * f); });
			s.liftAnimator.start(); s.dimAnimator.start();
		} else source.setAlpha(.22f);
		return true;
	}
	private Session capture(Button source, ViewGroup root) {
		Session s = new Session(source, root);
		try {
			for (Button button : Button.values()) {
				View view = editor.board.buttons.get(button);
				if (view.getWidth() <= 0 || view.getHeight() <= 0) { release(s); return null; }
				Bitmap bitmap = Bitmap.createBitmap(view.getWidth(), view.getHeight(), Bitmap.Config.ARGB_8888);
				s.snapshots.put(button, bitmap);
				view.draw(new Canvas(bitmap));
			}
			captureBounds(s.old, s.oldPanels, root);
			captureFurniture(s, false);
			return s;
		} catch (RuntimeException | OutOfMemoryError e) { release(s); return null; }
	}
	boolean dispatch(View receiver, DragEvent event) {
		Session s = session;
		if (s == null || event.getLocalState() != s) return false;
		switch (event.getAction()) {
			case DragEvent.ACTION_DRAG_STARTED: return s.nativeActive;
			case DragEvent.ACTION_DRAG_ENTERED: s.inside = true; return true;
			case DragEvent.ACTION_DRAG_LOCATION:
				if (s.nativeActive && !s.ended) location(s, receiver, event.getX(), event.getY());
				return true;
			case DragEvent.ACTION_DRAG_EXITED:
				s.inside = false; editor.removeCallbacks(autoScroll); highlight(s, null); return true;
			case DragEvent.ACTION_DROP:
				if (!s.nativeActive || s.ended || s.committed != null) return false;
				location(s, receiver, event.getX(), event.getY());
				PlayerButtonsLayout now = Preferences.getVideoButtonsLayout();
				if (!now.encode().equals(s.before.encode())) return false;
				PlayerButtonsLayout next = dropped(now, s.source, s.target);
				if (next == now) return false;
				// The only touch-drag write: validated DROP, independent of animator success.
				Preferences.setVideoButtonsLayout(next);
				s.committed = next;
				return true;
			case DragEvent.ACTION_DRAG_ENDED:
				if (!s.ended) {
					s.ended = true; s.nativeActive = false; editor.setDragging(false);
					editor.removeCallbacks(autoScroll);
					cancel(s.liftAnimator); cancel(s.dimAnimator); cancel(s.hoverAnimator);
					editor.board.highlight = null; editor.board.invalidate();
					s.endedPost = () -> { if (current(s)) afterNative(s); };
					s.root.post(s.endedPost);
				}
				return true;
			default: return true;
		}
	}
	private void location(Session s, View receiver, float x, float y) {
		int[] at = new int[2]; receiver.getLocationOnScreen(at);
		s.screenX = at[0] + x; s.screenY = at[1] + y; s.inside = true;
		updateLocation(s);
		editor.removeCallbacks(autoScroll); editor.postOnAnimation(autoScroll);
	}
	private void updateLocation(Session s) {
		int[] at = new int[2]; s.root.getLocationOnScreen(at);
		s.imageX = s.screenX - at[0] - s.grab.x; s.imageY = s.screenY - at[1] - s.grab.y;
		editor.board.getLocationOnScreen(at);
		highlight(s, editor.board.target(s.screenX - at[0], s.screenY - at[1]));
		s.overlay.invalidateSelf();
	}
	private final Runnable autoScroll = new Runnable() {
		@Override public void run() {
			Session s = session;
			if (s == null || !s.nativeActive || s.ended || !s.inside) return;
			RecyclerView recycler = editor.recycler();
			if (recycler == null) return;
			int[] at = new int[2]; recycler.getLocationOnScreen(at);
			float y = s.screenY - at[1];
			int edge = editor.dp(48), direction = y < edge ? -1 : y > recycler.getHeight() - edge ? 1 : 0;
			if (direction != 0 && recycler.canScrollVertically(direction)) {
				recycler.scrollBy(0, direction * editor.dp(6));
				updateLocation(s);
				editor.postOnAnimation(this);
			}
		}
	};
	private void highlight(Session s, Target target) {
		if (s.target == target || s.target != null && s.target.same(target)) return;
		s.target = target;
		cancel(s.hoverAnimator);
		PlayerButtonsEditorView.Board board = editor.board;
		board.highlight = null; board.highlightAlpha = 0; board.invalidHighlight = false;
		String text = editor.getResources().getString(R.string.player_buttons_noop);
		if (target != null && target.button != null && target.button != s.source) {
			View view = board.buttons.get(target.button);
			board.highlight = new RectF(view.getLeft(), view.getTop(), view.getRight(), view.getBottom());
			text = editor.getResources().getString(R.string.player_buttons_drag_swap,
					editor.label(s.source), editor.label(target.button));
		} else if (target != null && target.button == null
				&& !((target.right ? s.before.getRight() : s.before.getLeft()).contains(s.source))) {
			board.highlight = new RectF(target.right ? board.rightZone : board.leftZone);
			board.invalidHighlight = target.right && s.before.getRight().size() >= 2;
			text = board.invalidHighlight ? editor.getResources().getString(R.string.player_buttons_full)
					: editor.getResources().getString(target.right ? R.string.player_buttons_drag_right
					: R.string.player_buttons_drag_left, editor.label(s.source));
		}
		editor.sayStatus(text);
		if (board.highlight != null && animate()) s.hoverAnimator = animator(100, f -> {
			if (current(s)) { board.highlightAlpha = f; board.invalidate(); }
		});
		else { board.highlightAlpha = 1; board.invalidate(); }
		if (s.hoverAnimator != null && board.highlight != null && animate()) s.hoverAnimator.start();
	}
	private void afterNative(Session s) {
		String expected = (s.committed != null ? s.committed : s.before).encode();
		if (!expected.equals(Preferences.getVideoButtonsLayout().encode()) || !editor.isAttachedToWindow()) {
			cleanup(false); return;
		}
		// Scrolling during drag changes old screen bounds, not the identity/order of the buttons.
		captureBounds(s.old, s.oldPanels, s.root);
		captureFurniture(s, false);
		if (s.committed != null) {
			s.focus = s.source;
			prepareLayout(s);
		} else {
			s.end.putAll(s.old);
			s.endPanels[0] = s.oldPanels[0]; s.endPanels[1] = s.oldPanels[1];
			settle(s, 220);
		}
	}
	void command(PlayerButtonsLayout next, Button focus) {
		if (isDragging()) return;
		cleanup(false);
		PlayerButtonsLayout now = Preferences.getVideoButtonsLayout();
		if (next.encode().equals(now.encode())) return;
		ViewGroup root = editor.overlayRoot();
		Session s = animate() && root != null && editor.isLaidOut() ? capture(null, root) : null;
		if (s != null) {
			session = s; s.focus = focus; s.committed = next;
			if (focus != null) {
				View view = editor.board.buttons.get(focus);
				s.restoreAccessibility = view.isAccessibilityFocused(); s.restoreKeyboard = view.hasFocus();
			}
			root.getOverlay().add(s.overlay); root.addOnLayoutChangeListener(s.rootLayout);
			if (s.recycler != null) s.recycler.addOnScrollListener(s.scrollListener);
		}
		Preferences.setVideoButtonsLayout(next);
		if (s == null) { editor.reload(); return; }
		prepareLayout(s);
	}
	private void prepareLayout(Session s) {
		if (!animate()) { cleanup(false); return; }
		// Hold old pixels across the single new measure/layout. Never expose a new→old→new frame.
		s.settling = true;
		for (View view : editor.board.buttons.values()) view.setAlpha(0);
		editor.board.hidePanels = true;
		editor.reload();
		s.overlay.invalidateSelf();
		s.preDraw = OneShotPreDrawListener.add(editor, () -> {
			s.preDraw = null;
			if (!current(s)) return;
			if (!s.committed.encode().equals(Preferences.getVideoButtonsLayout().encode())
					|| !editor.isAttachedToWindow()) { cleanup(false); return; }
			captureBounds(s.end, s.endPanels, s.root);
			captureFurniture(s, true);
			placeFurniture(s, 0);
			settle(s, 260);
		});
	}
	private void settle(Session s, int duration) {
		if (!current(s)) return;
		s.settling = true;
		for (View view : editor.board.buttons.values()) view.setAlpha(0);
		editor.board.hidePanels = true;
		if (!animate()) { cleanup(false); return; }
		s.settleAnimator = animator(duration, f -> {
			if (current(s)) { s.progress = f; placeFurniture(s, f); s.overlay.invalidateSelf(); }
		});
		s.settleAnimator.addListener(new AnimatorListenerAdapter() {
			@Override public void onAnimationEnd(Animator animation) {
				if (current(s)) cleanup(false);
			}
		});
		s.settleAnimator.start();
	}
	/** Idempotent and presentation-only, even if cancel triggers a later animation-end callback. */
	void cleanup(boolean cancelNative) {
		Session s = session;
		if (s == null) return;
		session = null; generation++;
		editor.removeCallbacks(autoScroll);
		if (s.endedPost != null) s.root.removeCallbacks(s.endedPost);
		if (s.preDraw != null) { s.preDraw.removeListener(); s.preDraw = null; }
		s.root.removeOnLayoutChangeListener(s.rootLayout);
		if (s.recycler != null) s.recycler.removeOnScrollListener(s.scrollListener);
		cancel(s.liftAnimator); cancel(s.dimAnimator); cancel(s.hoverAnimator); cancel(s.settleAnimator);
		if (cancelNative && s.nativeActive) editor.board.buttons.get(s.source).cancelDragAndDrop();
		s.root.getOverlay().remove(s.overlay);
		for (View view : editor.board.buttons.values()) view.setAlpha(1);
		editor.board.hidePanels = false; editor.board.highlight = null; editor.board.invalidate();
		for (View view : editor.furniture) { view.setTranslationX(0); view.setTranslationY(0); }
		editor.animatedCard = null; editor.invalidate();
		editor.setDragging(false); editor.normalStatus(); editor.reload();
		if (s.focus != null) {
			View focus = editor.board.buttons.get(s.focus);
			if (s.restoreKeyboard) focus.requestFocus();
			if (s.restoreAccessibility) focus.performAccessibilityAction(
					AccessibilityNodeInfo.ACTION_ACCESSIBILITY_FOCUS, null);
			if (s.committed != null) focus.setStateDescription(editor.getResources()
					.getString(R.string.player_buttons_saved));
		}
		release(s);
	}
	private static void release(Session s) {
		for (Bitmap bitmap : s.snapshots.values()) bitmap.recycle();
		s.snapshots.clear();
	}
	private static void cancel(ValueAnimator animator) { if (animator != null) animator.cancel(); }
	private interface Frame { void update(float value); }
	private static ValueAnimator animator(int duration, Frame frame) {
		ValueAnimator animator = ValueAnimator.ofFloat(0, 1);
		animator.setDuration(InterfaceMotion.duration(duration)); animator.setInterpolator(InterfaceMotion.STANDARD);
		animator.addUpdateListener(a -> frame.update((float) a.getAnimatedValue()));
		return animator;
	}
	private void captureBounds(EnumMap<Button, RectF> map, RectF[] panels, ViewGroup root) {
		for (Button button : Button.values()) map.put(button, bounds(editor.board.buttons.get(button), root));
		RectF board = bounds(editor.board, root);
		panels[0] = new RectF(editor.board.leftPanel); panels[0].offset(board.left, board.top);
		panels[1] = new RectF(editor.board.rightPanel); panels[1].offset(board.left, board.top);
	}
	private void captureFurniture(Session s, boolean end) {
		Map<View, RectF> map = end ? s.endFurniture : s.oldFurniture;
		map.clear();
		for (View view : editor.furniture) if (view.getVisibility() == View.VISIBLE) map.put(view, bounds(view, s.root));
		if (end) s.endCard = bounds(editor.previewCard, s.root); else s.oldCard = bounds(editor.previewCard, s.root);
	}
	private void placeFurniture(Session s, float progress) {
		for (Map.Entry<View, RectF> entry : s.endFurniture.entrySet()) {
			RectF old = s.oldFurniture.get(entry.getKey()), end = entry.getValue();
			if (old != null) {
				entry.getKey().setTranslationX((old.left - end.left) * (1 - progress));
				entry.getKey().setTranslationY((old.top - end.top) * (1 - progress));
			}
		}
		if (s.oldCard != null && s.endCard != null) {
			RectF rect = lerp(s.oldCard, s.endCard, progress), origin = bounds(editor, s.root);
			rect.offset(-origin.left, -origin.top); editor.animatedCard = rect; editor.invalidate();
		}
	}
	private static RectF bounds(View view, View root) {
		int[] at = new int[2], base = new int[2]; view.getLocationOnScreen(at); root.getLocationOnScreen(base);
		return new RectF(at[0] - base[0], at[1] - base[1], at[0] - base[0] + view.getWidth(),
				at[1] - base[1] + view.getHeight());
	}
	private static RectF lerp(RectF a, RectF b, float p) {
		return new RectF(a.left + (b.left - a.left) * p, a.top + (b.top - a.top) * p,
				a.right + (b.right - a.right) * p, a.bottom + (b.bottom - a.bottom) * p);
	}
	private final class Overlay extends View {
		final Session s;
		final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
		Overlay(Session s) {
			super(editor.getContext()); this.s = s;
			setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS);
			setFocusable(false); setClickable(false);
			layout(0, 0, s.root.getWidth(), s.root.getHeight());
		}
		void invalidateSelf() { invalidate(); }
		@Override protected void onDraw(Canvas canvas) {
			if (!current(s)) return;
			if (s.settling) {
				for (int i = 0; i < 2; i++) {
					RectF a = s.oldPanels[i], b = s.endPanels[i];
					if (a != null) editor.board.drawPanel(canvas, b != null ? lerp(a, b, s.progress) : a,
							i == 1 && (s.committed != null ? s.committed : s.before).getRight().isEmpty());
				}
				for (Button button : Button.values()) if (button != s.source) {
					RectF a = s.old.get(button), b = s.end.get(button);
					if (a != null) canvas.drawBitmap(s.snapshots.get(button), null,
							b != null ? lerp(a, b, s.progress) : a, paint);
				}
			}
			if (s.source != null) {
				Bitmap bitmap = s.snapshots.get(s.source);
				RectF start = new RectF(s.imageX, s.imageY - s.lift,
						s.imageX + bitmap.getWidth(), s.imageY - s.lift + bitmap.getHeight());
				// Scale about the grab point, not the centre: the first drag location cannot jump.
				start.left = s.imageX - s.grab.x * (s.scale - 1);
				start.top = s.imageY - s.lift - s.grab.y * (s.scale - 1);
				start.right = start.left + bitmap.getWidth() * s.scale;
				start.bottom = start.top + bitmap.getHeight() * s.scale;
				RectF end = s.end.get(s.source);
				RectF image = s.settling && end != null ? lerp(start, end, s.progress) : start;
				float remaining = s.settling ? 1 - s.progress : 1;
				paint.setColor(editor.colors.active);
				paint.setShadowLayer(editor.dp(4) * remaining, 0, editor.dp(2) * remaining, 0x40000000);
				canvas.drawRoundRect(image, editor.dp(12), editor.dp(12), paint); paint.clearShadowLayer();
				canvas.drawBitmap(bitmap, null, image, paint);
			}
		}
	}
}
