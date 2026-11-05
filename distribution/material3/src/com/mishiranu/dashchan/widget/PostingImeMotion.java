package com.mishiranu.dashchan.widget;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ValueAnimator;
import android.graphics.Canvas;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.drawable.Drawable;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.ViewGroup;
import android.view.WindowInsets;
import android.view.WindowInsetsAnimation;
import android.widget.LinearLayout;
import com.mishiranu.dashchan.graphics.BaseDrawable;
import com.mishiranu.dashchan.ui.posting.PostingFormDiagnostics;
import com.mishiranu.dashchan.util.InterfaceMotion;
import com.mishiranu.dashchan.util.ResourceUtils;
import java.util.ArrayList;
import java.util.List;

/** One presentation owner. Final layout never depends on animation completion.
 * No draft capture, focus changes, IME requests or bitmap scaling of primary-action content. */
final class PostingImeMotion implements View.OnAttachStateChangeListener {
	private final ViewGroup root;
	private ViewGroup overlayHost;
	private final View form, editor, primary, utilitySlot, utility;
	private final ViewTreeFocus focus = new ViewTreeFocus();
	private final SystemTimeline timeline = new SystemTimeline();
	private ViewGroup actionRow;
	private View primarySlot, attachment, action;
	private Frame last, pending;
	private Pose pose;
	private ValueAnimator animator;
	private WindowInsetsAnimation systemAnimation;
	private boolean systemFrames, followingSystem, closed;
	private int imeStartBottom, imeEndBottom, imeBottom;
	private boolean imeEdges;
	private Frame displayedBeforeLayout;
	private float fraction;
	private int runningWidth, runningHeight;
	private int windowHeight;
	private View touchTarget;
	private boolean controlGesture, moved;
	private float downX, downY;
	private final Runnable systemTimeout = this::finish;

	PostingImeMotion(ViewGroup root, View form, View editor, View primary, View utilitySlot, View utility) {
		this.root = root; this.form = form; this.editor = editor; this.primary = primary;
		this.overlayHost = root.getRootView() instanceof ViewGroup ? (ViewGroup) root.getRootView() : root;
		this.utilitySlot = utilitySlot; this.utility = utility;
		root.addOnAttachStateChangeListener(this);
		root.getViewTreeObserver().addOnWindowFocusChangeListener(focus);
		// This posting root is stable when the action row moves between footer and form.
		// The legacy ImePanelMotion is not attached when PostingFormAppearance owns it.
		root.setWindowInsetsAnimationCallback(timeline);
		if (root.isAttachedToWindow()) refreshWindowHeight();
	}
	void bindActions(ViewGroup row, View slot, View attachment, View action) {
		this.actionRow = row; this.primarySlot = slot; this.attachment = attachment; this.action = action;
	}
	private final class ViewTreeFocus implements android.view.ViewTreeObserver.OnWindowFocusChangeListener {
		@Override public void onWindowFocusChanged(boolean hasFocus) { if (!hasFocus) finish(); }
	}
	private boolean allowed() {
		return !closed && root.isAttachedToWindow() && root.hasWindowFocus() && root.isShown()
				&& InterfaceMotion.isEnabled() && InterfaceMotion.duration(1) > 0 && !ElementMotion.busy(root);
	}
	private RectF bounds(View view) {
		int[] at = new int[2], base = new int[2];
		view.getLocationInWindow(at); root.getLocationInWindow(base);
		return new RectF(at[0] - base[0], at[1] - base[1],
				at[0] - base[0] + view.getWidth(), at[1] - base[1] + view.getHeight());
	}
	private boolean visible(View view, RectF bounds) {
		return view != null && view.getVisibility() == View.VISIBLE && view.isShown()
				&& !bounds.isEmpty() && view.getGlobalVisibleRect(new Rect());
	}
	private RectF visibleClip(View view) {
		Rect clip = new Rect();
		if (!view.getGlobalVisibleRect(clip)) return new RectF();
		int[] at = new int[2]; root.getLocationOnScreen(at); clip.offset(-at[0], -at[1]);
		return new RectF(clip);
	}
	private void refreshWindowHeight() {
		android.view.WindowManager manager = root.getContext().getSystemService(android.view.WindowManager.class);
		windowHeight = manager != null ? manager.getCurrentWindowMetrics().getBounds().height() : root.getRootView().getHeight();
	}
	private float keyboardEdge(int bottom) {
		int[] at = new int[2]; root.getLocationInWindow(at);
		WindowInsets insets = root.getRootWindowInsets();
		int navigation = insets != null ? insets.getInsets(WindowInsets.Type.navigationBars()).bottom : 0;
		return (windowHeight > 0 ? windowHeight : root.getRootView().getHeight()) - Math.max(bottom, navigation) - at[1];
	}
	private float keyboardEdge() {
		WindowInsets insets = root.getRootWindowInsets();
		return keyboardEdge(insets != null ? insets.getInsets(WindowInsets.Type.ime()).bottom : 0);
	}
	private final class Item {
		final View view; final RectF bounds; final float alpha;
		Item(View view) {
			this.view = view; bounds = bounds(view);
			alpha = view.getAlpha() * (view == utility ? 1f : utility.getAlpha());
		}
	}
	private final class Frame {
		final int width = root.getWidth();
		final float editorTop = bounds(editor).top, primaryTop = bounds(primary).top;
		final int editorHeight = editor.getHeight();
		// Native layout has consumed the final insets, even if the first progress
		// preceded this capture. Keep that edge separate from the displayed IME edge.
		final float layoutImeEdge = keyboardEdge();
		final float imeEdge = followingSystem && systemFrames ? keyboardEdge(imeBottom) : layoutImeEdge;
		final RectF editorClip = visibleClip(editor);
		final RectF capsule = primarySlot != null ? bounds(primarySlot) : new RectF();
		final RectF attach = attachment != null ? bounds(attachment) : new RectF();
		final boolean utilityVisible = utilitySlot.getVisibility() == View.VISIBLE && utility.isShown();
		final List<Item> options = new ArrayList<>();
		final Rect utilityClip = new Rect();
		boolean attachVisible;
		boolean primaryVisible = visible(primarySlot != null ? primarySlot : primary, capsule);
		Frame() {
			if (pose != null) editorClip.set(pose.shownEditorClip);
			if (pose != null && pose.capsuleLayer != null) {
				capsule.set(pose.shownCapsule); primaryVisible = primary.getAlpha() > .05f;
			}
			attachVisible = visible(attachment, attach);
			if (pose != null && pose.flight != null) { attach.set(pose.attach); attachVisible = true; }
			if (utilityVisible && utility.getGlobalVisibleRect(utilityClip)) {
				int[] base = new int[2]; root.getLocationOnScreen(base); utilityClip.offset(-base[0], -base[1]);
				if (utility instanceof ViewGroup) {
					ViewGroup group = (ViewGroup) utility;
					for (int i = 0; i < group.getChildCount(); i++) {
						View child = group.getChildAt(i);
						if (child != attachment && child.getVisibility() == View.VISIBLE) options.add(new Item(child));
					}
				} else options.add(new Item(utility));
			}
		}
	}

	/** BEFORE reparent/GONE. Reversals sample the displayed attachment and live capsule bounds. */
	void begin() {
		Frame before = pending != null ? pending : pose != null
				? pose.layoutPrepared && displayedBeforeLayout != null ? displayedBeforeLayout : new Frame() : last;
		WindowInsetsAnimation candidate = systemAnimation; boolean frames = systemFrames;
		finish(); systemAnimation = candidate; systemFrames = frames;
		if (candidate != null) armSystemTimeout();
		if (before != null && allowed()) pending = before;
	}
	/** Restore only presentation bounds before the existing sizing/alignment owner reads them. */
	void prepareLayout() {
		if (pose != null && !pose.layoutPrepared) {
			displayedBeforeLayout = new Frame();
			pose.editorShape.restore();
			pose.layoutPrepared = true;
		}
	}
	void onPreDraw() {
		if (closed) return;
		if (pose != null && (!allowed() || root.getWidth() != runningWidth
				|| !followingSystem && root.getHeight() != runningHeight)) finish();
		if (pending != null && hasMeasuredBounds(root, form, editor, primary)) {
			Frame before = pending; pending = null;
			if (allowed() && before.width == root.getWidth()) start(before);
		}
		if (pose != null) pose.apply(fraction);
		if (pending == null && pose == null && editor.getHeight() > 0 && root.isShown()) last = new Frame();
	}
	static boolean hasMeasuredBounds(View... views) {
		for (View view : views) {
			if (view == null || view.getWidth() <= 0 || view.getHeight() <= 0
					|| view.getWidth() != view.getMeasuredWidth() || view.getHeight() != view.getMeasuredHeight()) return false;
		}
		return true;
	}
	private void start(Frame before) {
		refreshWindowHeight();
		overlayHost = root.getRootView() instanceof ViewGroup ? (ViewGroup) root.getRootView() : root;
		// onPrepare -> final layout/pre-draw can precede the first onProgress.
		// Keep the prepared system owner instead of starting an unrelated Y timer.
		followingSystem = systemAnimation != null;
		if (!followingSystem) { systemAnimation = null; root.removeCallbacks(systemTimeout); fraction = 0; }
		runningWidth = root.getWidth(); runningHeight = root.getHeight();
		pose = new Pose(before); pose.apply(fraction);
		if (followingSystem) {
			PostingFormDiagnostics.trace(root, "ime_form_motion", "source=system visibleBefore=" + before.primaryVisible
					+ " fromY=" + before.capsule.top + " toY=" + pose.endTop + " viewport=" + root.getHeight()
					+ " startEdge=" + before.imeEdge + " endLayoutEdge=" + pose.endImeEdge);
			return;
		}
		ValueAnimator running = ValueAnimator.ofFloat(0f, 1f); animator = running;
		running.setDuration(InterfaceMotion.duration(260)); running.setInterpolator(InterfaceMotion.STANDARD);
		running.addUpdateListener(value -> {
			if (animator != value) return;
			if (!allowed() || root.getWidth() != runningWidth || root.getHeight() != runningHeight) { finish(); return; }
			fraction = (float) value.getAnimatedValue(); if (pose != null) pose.apply(fraction);
		});
		running.addListener(new AnimatorListenerAdapter() {
			@Override public void onAnimationEnd(Animator animation) { if (animator == animation) finish(); }
		});
		PostingFormDiagnostics.trace(root, "ime_form_motion", "source=fallback durationMs=260");
		running.start();
	}
	/** One timeline per transition, on the posting root which survives action-row reparenting. */
	private final class SystemTimeline extends WindowInsetsAnimation.Callback {
		SystemTimeline() { super(DISPATCH_MODE_CONTINUE_ON_SUBTREE); }
		@Override public void onPrepare(WindowInsetsAnimation animation) {
			if ((animation.getTypeMask() & WindowInsets.Type.ime()) == 0) return;
			refreshWindowHeight();
			WindowInsets insets = root.getRootWindowInsets();
			int startingBottom = systemAnimation != null && systemFrames ? imeBottom
					: insets != null ? insets.getInsets(WindowInsets.Type.ime()).bottom : 0;
			Frame before = pose != null && pose.layoutPrepared && displayedBeforeLayout != null
					? displayedBeforeLayout : editor.getHeight() > 0 ? new Frame() : last;
			finish();
			if (allowed()) {
				systemAnimation = animation; systemFrames = false; fraction = 0; pending = before;
				imeStartBottom = startingBottom;
				imeEndBottom = imeBottom = imeStartBottom; imeEdges = false;
				PostingFormDiagnostics.trace(root, "ime_form_prepare", "bottom=" + imeStartBottom);
				armSystemTimeout();
			}
		}
		@Override public WindowInsetsAnimation.Bounds onStart(WindowInsetsAnimation animation, WindowInsetsAnimation.Bounds bounds) {
			if (systemAnimation == animation) {
				WindowInsets insets = root.getRootWindowInsets();
				imeEndBottom = insets != null ? insets.getInsets(WindowInsets.Type.ime()).bottom : imeStartBottom;
				imeEdges = imeEndBottom != imeStartBottom;
				if (animation.getDurationMillis() == 0 || !allowed()) finish();
			}
			return bounds;
		}
		@Override public WindowInsets onProgress(WindowInsets insets, List<WindowInsetsAnimation> running) {
			if (systemAnimation != null && running.contains(systemAnimation)) {
				if (!allowed()) finish();
				else {
					boolean first = !systemFrames;
					systemFrames = true; imeBottom = insets.getInsets(WindowInsets.Type.ime()).bottom;
					// Position follows the measured IME edge. Layout has already consumed
					// final insets; interpolate between its captured poses, do not subtract IME twice.
					fraction = imeEdges ? Math.max(0f, Math.min(1f,
							(float) (imeBottom - imeStartBottom) / (imeEndBottom - imeStartBottom)))
							: systemAnimation.getInterpolatedFraction();
					if (first) PostingFormDiagnostics.trace(root, "ime_form_first_progress",
							"bottom=" + imeBottom + " end=" + imeEndBottom + " measuredEdge=" + imeEdges + " fraction=" + fraction);
					if (followingSystem && pose != null) pose.apply(fraction);
				}
			}
			return insets;
		}
		@Override public void onEnd(WindowInsetsAnimation animation) {
			if (systemAnimation == animation) {
				PostingFormDiagnostics.trace(root, "ime_form_end", "frames=" + systemFrames + " fraction=" + fraction);
				finish(); // Never start a second animation after the IME stops.
			}
		}
	}
	private void armSystemTimeout() {
		root.removeCallbacks(systemTimeout);
		long duration = systemAnimation != null ? systemAnimation.getDurationMillis() : 0;
		root.postDelayed(systemTimeout, Math.max(600L, Math.min(3000L, duration + 400L)));
	}

	private final class Pose {
		final Frame before;
		final float formY = form.getTranslationY(), primaryY = primary.getTranslationY();
		final float utilityY = utility.getTranslationY(), utilityAlpha = utility.getAlpha(), primaryAlpha = primary.getAlpha();
		final float attachmentAlpha = attachment != null ? attachment.getAlpha() : 1f;
		final float rowAlpha = actionRow != null ? actionRow.getAlpha() : 1f;
		final float editorAlpha = editor.getAlpha();
		final boolean showUtility, inForm, docked;
		final EditorShape editorShape = new EditorShape();
		final UtilityExit outgoing;
		final Drawable flight;
		final Drawable capsuleLayer;
		final Drawable editorLayer;
		final RectF shownCapsule = new RectF();
		final RectF shownEditorClip = new RectF(), endEditorClip;
		final float endTop;
		final float endImeEdge;
		final int endEditorHeight;
		boolean layoutPrepared;
		final RectF attach = new RectF();
		Pose(Frame before) {
			this.before = before;
			Frame after = new Frame(); showUtility = !before.utilityVisible && after.utilityVisible;
			endTop = !after.capsule.isEmpty() ? after.capsule.top : after.primaryTop;
			endImeEdge = after.layoutImeEdge;
			endEditorHeight = after.editorHeight;
			endEditorClip = new RectF(after.editorClip);
			editorLayer = new BaseDrawable() {
				@Override public void draw(Canvas canvas) {
					if (shownEditorClip.isEmpty()) return;
					int save = canvas.save();
					try {
						translateOverlay(canvas); RectF at = bounds(editor);
						canvas.clipRect(shownEditorClip);
						// Keep the real toolbar above the transient editor, including expanded/large-text bars.
						View toolbar = overlayHost.findViewById(com.mishiranu.dashchan.R.id.toolbar);
						if (toolbar != null) {
							Rect bar = new Rect(); int[] origin = new int[2]; root.getLocationOnScreen(origin);
							if (toolbar.getGlobalVisibleRect(bar)) canvas.clipRect(0, bar.bottom - origin[1], root.getWidth(), overlayHost.getHeight());
						}
						canvas.translate(at.left, at.top);
						int layer = canvas.saveLayerAlpha(0, 0, editor.getWidth(), editor.getHeight(), Math.round(255 * editorAlpha));
						SnapshotDrawing.draw(editor, canvas); canvas.restoreToCount(layer);
					} finally { canvas.restoreToCount(save); }
				}
			};
			editor.setAlpha(0f); editorLayer.setBounds(0, 0, overlayHost.getWidth(), overlayHost.getHeight());
			overlayHost.getOverlay().add(editorLayer);
			boolean childOfForm = false;
			for (View view = primary; view != null; view = view.getParent() instanceof View ? (View) view.getParent() : null) {
				if (view == form) { childOfForm = true; break; }
			}
			inForm = childOfForm; docked = attachment != null && attachment.getParent() == actionRow;
			outgoing = before.utilityVisible && !after.utilityVisible && !before.utilityClip.isEmpty() ? new UtilityExit(before) : null;
			if (outgoing != null) root.getOverlay().add(outgoing);
			flight = before.attachVisible && after.attachVisible && attachment != null ? new BaseDrawable() {
				@Override public void draw(Canvas canvas) { drawAttachment(canvas); }
			} : null;
			if (flight != null) {
				flight.setBounds(0, 0, overlayHost.getWidth(), overlayHost.getHeight());
				attachment.setAlpha(0f); overlayHost.getOverlay().add(flight);
			}
			capsuleLayer = actionRow != null && primarySlot != null ? new BaseDrawable() {
				@Override public void draw(Canvas canvas) {
					int save = canvas.save();
					try {
						translateOverlay(canvas);
						canvas.translate(shownCapsule.left, shownCapsule.top);
						int layer = canvas.saveLayerAlpha(0, 0, primarySlot.getWidth(), primarySlot.getHeight(),
								Math.round(255 * primary.getAlpha() * rowAlpha));
						// The existing live slot (including captcha-state motion), drawn once.
						SnapshotDrawing.draw(primarySlot, canvas); canvas.restoreToCount(layer);
					} finally { canvas.restoreToCount(save); }
				}
			} : null;
			if (capsuleLayer != null) {
				actionRow.setAlpha(0f);
				capsuleLayer.setBounds(0, 0, overlayHost.getWidth(), overlayHost.getHeight());
				overlayHost.getOverlay().add(capsuleLayer);
			}
		}
		void apply(float value) {
			layoutPrepared = false;
			float progress = Math.max(0f, Math.min(1f, value));
			// Read natural bounds again after native remeasurement, including OEM IME resize frames.
			form.setTranslationY(formY); primary.setTranslationY(primaryY);
			editorShape.restore();
			float contentDelta = before.editorTop - bounds(editor).top;
			float shownTop = !before.capsule.isEmpty() ? before.capsule.top : before.primaryTop;
			float targetTop = primarySlot != null ? bounds(primarySlot).top : bounds(primary).top;
			boolean reveal = !before.primaryVisible;
			float desiredTop = followingSystem && imeEdges
					? keyboardEdge(imeBottom) - lerp(before.imeEdge - shownTop, endImeEdge - endTop, progress)
					: lerp(shownTop, endTop, progress);
			float primaryDelta = reveal ? 24 * ResourceUtils.obtainDensity(root) * (1f - progress) : desiredTop - targetTop;
			RectF destination = attachment != null ? bounds(attachment) : new RectF();
			float fade = Math.min(1f, progress * (260f / 160f));
			form.setTranslationY(formY + contentDelta * (1f - progress));
			primary.setTranslationY(primaryY + primaryDelta - (inForm ? contentDelta * (1f - progress) : 0));
			// Presentation bounds only: min/max/height policy and LayoutParams stay untouched.
			// Formatting remains at the visible editor bottom instead of snapping ahead of the CTA.
			editorShape.apply(Math.round(lerp(before.editorHeight, endEditorHeight, progress)));
			RectF startClip = before.editorClip.isEmpty() ? endEditorClip : before.editorClip;
			RectF endClip = endEditorClip.isEmpty() ? startClip : endEditorClip;
			shownEditorClip.set(lerp(startClip.left, endClip.left, progress), lerp(startClip.top, endClip.top, progress),
					lerp(startClip.right, endClip.right, progress), lerp(startClip.bottom, endClip.bottom, progress));
			editorLayer.invalidateSelf();
			if (reveal) primary.setAlpha(primaryAlpha * fade);
			if (showUtility) {
				utility.setAlpha(utilityAlpha * fade);
				utility.setTranslationY(utilityY + 8 * ResourceUtils.obtainDensity(root) * (1f - progress));
			}
			if (flight != null) {
				flight.setBounds(0, 0, overlayHost.getWidth(), overlayHost.getHeight());
				attach.set(lerp(before.attach.left, destination.left, progress), lerp(before.attach.top, destination.top, progress),
						lerp(before.attach.right, destination.right, progress), lerp(before.attach.bottom, destination.bottom, progress));
				flight.invalidateSelf();
			}
			if (actionRow != null && primarySlot != null && actionRow.getWidth() > 0) {
				float start = Math.max(0, actionRow.getWidth() - before.capsule.width());
				float end = finalInset();
				float phase = docked ? smooth(progress / .35f) : smooth((progress - .65f) / .35f);
				float inset = lerp(start, end, phase);
				RectF row = bounds(actionRow);
				if (flight != null && attach.bottom > row.top && attach.top < row.bottom) {
					float gap = 12 * ResourceUtils.obtainDensity(root);
					float safe = actionRow.getLayoutDirection() == View.LAYOUT_DIRECTION_RTL
							? row.right - attach.left + gap : attach.right - row.left + gap;
					inset = Math.max(inset, safe);
				}
				layoutPrimary(Math.round(inset));
			}
			if (capsuleLayer != null) {
				shownCapsule.set(bounds(primarySlot)); capsuleLayer.invalidateSelf();
			}
			if (outgoing != null) {
				outgoing.setBounds(0, 0, root.getWidth(), root.getHeight());
				outgoing.fraction = fade; outgoing.invalidateSelf();
			}
		}
		private void drawAttachment(Canvas canvas) {
			int save = canvas.save();
			try {
				translateOverlay(canvas); canvas.translate(attach.left, attach.top);
				Drawable background = attachment.getBackground();
				if (background != null) drawAt(background, canvas, 0, 0, Math.round(attach.width()), Math.round(attach.height()));
				if (attachment instanceof android.widget.ImageView) {
					Drawable icon = ((android.widget.ImageView) attachment).getDrawable();
					int size = Math.round(24 * ResourceUtils.obtainDensity(root));
					int x = Math.round((attach.width() - size) / 2), y = Math.round((attach.height() - size) / 2);
					if (icon != null) drawAt(icon, canvas, x, y, x + size, y + size);
				}
			} finally { canvas.restoreToCount(save); }
		}
		void restore() {
			editorShape.restore();
			overlayHost.getOverlay().remove(editorLayer); editor.setAlpha(editorAlpha);
			form.setTranslationY(formY); primary.setTranslationY(primaryY); primary.setAlpha(primaryAlpha);
			utility.setTranslationY(utilityY); utility.setAlpha(utilityAlpha);
			if (flight != null) { overlayHost.getOverlay().remove(flight); attachment.setAlpha(attachmentAlpha); }
			if (capsuleLayer != null) { overlayHost.getOverlay().remove(capsuleLayer); actionRow.setAlpha(rowAlpha); }
			if (outgoing != null) root.getOverlay().remove(outgoing);
			if (actionRow != null && primarySlot != null) layoutPrimary(finalInset());
		}
	}
	private void translateOverlay(Canvas canvas) {
		int[] origin = new int[2], host = new int[2];
		root.getLocationInWindow(origin); overlayHost.getLocationInWindow(host);
		canvas.translate(origin[0] - host[0], origin[1] - host[1]);
	}
	/** Transient bounds of a vertical editor; restored before any policy/scroll measurement. */
	private final class EditorShape {
		final List<View> views = new ArrayList<>();
		final List<Rect> natural = new ArrayList<>(), presented = new ArrayList<>();
		void remember(View view) {
			views.add(view); natural.add(new Rect(view.getLeft(), view.getTop(), view.getRight(), view.getBottom()));
		}
		void apply(int height) {
			if (!(editor instanceof android.widget.LinearLayout)) return;
			ViewGroup group = (ViewGroup) editor;
			if (((android.widget.LinearLayout) group).getOrientation() != android.widget.LinearLayout.VERTICAL
					|| group.getChildCount() == 0 || !(group.getChildAt(0) instanceof android.widget.EditText)) return;
			int delta = height - editor.getHeight(); if (delta == 0) return;
			// Never compress the native input below one line plus its current padding.
			android.widget.EditText input = (android.widget.EditText) group.getChildAt(0);
			delta = Math.max(delta, input.getLineHeight() + input.getCompoundPaddingTop()
					+ input.getCompoundPaddingBottom() - input.getHeight());
			remember(editor); editor.setBottom(editor.getBottom() + delta);
			for (int i = 0; i < group.getChildCount(); i++) {
				View child = group.getChildAt(i); remember(child);
				if (i == 0) child.setBottom(child.getBottom() + delta);
				else { child.setTop(child.getTop() + delta); child.setBottom(child.getBottom() + delta); }
			}
			for (View view : views) presented.add(new Rect(view.getLeft(), view.getTop(), view.getRight(), view.getBottom()));
		}
		void restore() {
			for (int i = 0; i < views.size(); i++) {
				View view = views.get(i); Rect shown = presented.get(i), saved = natural.get(i);
				// A new native layout wins over the preceding presentation frame.
				if (view.getLeft() == shown.left && view.getTop() == shown.top
						&& view.getRight() == shown.right && view.getBottom() == shown.bottom) {
					view.setTop(saved.top); view.setBottom(saved.bottom);
				}
			}
			views.clear(); natural.clear(); presented.clear();
		}
	}
	private static void drawAt(Drawable drawable, Canvas canvas, int l, int t, int r, int b) {
		Rect bounds = new Rect(drawable.getBounds());
		try { drawable.setBounds(l, t, r, b); SnapshotDrawing.draw(drawable, canvas); }
		finally { drawable.setBounds(bounds); }
	}
	private static float lerp(float a, float b, float f) { return a + (b - a) * f; }
	boolean isTransitionRunning() { return pending != null || pose != null || systemAnimation != null; }
	private static float smooth(float f) { f = Math.max(0, Math.min(1, f)); return f * f * (3 - 2 * f); }
	private int finalInset() {
		if (attachment == null || attachment.getParent() != actionRow || attachment.getVisibility() == View.GONE) return 0;
		LinearLayout.LayoutParams params = (LinearLayout.LayoutParams) attachment.getLayoutParams();
		return params.width + params.getMarginStart() + params.getMarginEnd();
	}
	private void layoutPrimary(int inset) {
		int rowWidth = actionRow.getWidth(); if (rowWidth <= 0) return;
		inset = Math.max(0, Math.min(rowWidth - 1, inset));
		int width = rowWidth - inset, height = primarySlot.getLayoutParams().height;
		if (height <= 0) height = primarySlot.getHeight();
		boolean rtl = actionRow.getLayoutDirection() == View.LAYOUT_DIRECTION_RTL;
		int left = rtl ? 0 : inset;
		if (primarySlot.getWidth() != width || primarySlot.getMeasuredWidth() != width || primarySlot.isLayoutRequested()) {
			primarySlot.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
					View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY));
		}
		if (primarySlot.getLeft() != left || primarySlot.getRight() != left + width || primarySlot.isLayoutRequested()) {
			primarySlot.layout(left, primarySlot.getTop(), left + width, primarySlot.getTop() + height);
		}
	}

	/** Settle before input and map a moving control only to itself, never its new neighbour. */
	boolean onTouchEvent(MotionEvent event) {
		int type = event.getActionMasked();
		if (type == MotionEvent.ACTION_DOWN) {
			View target = null;
			if (pose != null) {
				int[] origin = new int[2]; root.getLocationOnScreen(origin);
				float x = event.getRawX() - origin[0], y = event.getRawY() - origin[1];
				if (pose.flight != null && pose.attach.contains(x, y)) target = attachment;
				else if (primarySlot != null && primary.getAlpha() > .05f && bounds(primarySlot).contains(x, y)) target = action;
			}
			finish();
			if (target == null) return false;
			touchTarget = target; controlGesture = true; moved = false; downX = event.getRawX(); downY = event.getRawY();
		}
		if (!controlGesture) return false;
		int slop = ViewConfiguration.get(root.getContext()).getScaledTouchSlop();
		if (event.getPointerCount() > 1 || Math.abs(event.getRawX() - downX) > slop || Math.abs(event.getRawY() - downY) > slop) moved = true;
		if (type == MotionEvent.ACTION_UP || type == MotionEvent.ACTION_CANCEL) {
			View target = touchTarget; boolean click = type == MotionEvent.ACTION_UP && !moved;
			controlGesture = false; touchTarget = null;
			if (click && !closed && target != null && target.isEnabled() && target.isShown()) target.performClick();
		}
		return true;
	}
	void finish() {
		root.removeCallbacks(systemTimeout);
		pending = null; displayedBeforeLayout = null; systemAnimation = null; systemFrames = false; followingSystem = false;
		controlGesture = false; touchTarget = null;
		ValueAnimator old = animator; animator = null;
		if (old != null) { old.removeAllUpdateListeners(); old.removeAllListeners(); old.cancel(); }
		Pose previous = pose; pose = null; if (previous != null) previous.restore();
		if (root.isAttachedToWindow() && editor.getHeight() > 0) last = new Frame();
	}
	void close() {
		finish(); closed = true; last = null;
		root.setWindowInsetsAnimationCallback(null);
		root.removeOnAttachStateChangeListener(this);
		if (root.getViewTreeObserver().isAlive()) root.getViewTreeObserver().removeOnWindowFocusChangeListener(focus);
	}
	@Override public void onViewAttachedToWindow(View view) { refreshWindowHeight(); }
	@Override public void onViewDetachedFromWindow(View view) { finish(); last = null; }

	/** Option children only, at captured positions. The attachment never enters UtilityExit. */
	private final class UtilityExit extends BaseDrawable {
		final Frame frame; float fraction;
		UtilityExit(Frame frame) { this.frame = frame; setBounds(0, 0, root.getWidth(), root.getHeight()); }
		@Override public void draw(Canvas canvas) {
			int save = canvas.save();
			try {
				canvas.clipRect(frame.utilityClip);
				for (Item item : frame.options) {
					int layer = canvas.saveLayerAlpha(item.bounds.left, item.bounds.top, item.bounds.right,
							item.bounds.bottom, Math.round(255 * item.alpha * (1f - fraction)));
					canvas.clipRect(item.bounds); canvas.translate(item.bounds.left, item.bounds.top);
					SnapshotDrawing.draw(item.view, canvas); canvas.restoreToCount(layer);
				}
			} finally { canvas.restoreToCount(save); }
		}
	}
}
