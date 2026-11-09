package com.mishiranu.dashchan.ui;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ValueAnimator;
import android.graphics.Canvas;
import android.graphics.Rect;
import android.graphics.RectF;
import android.view.HapticFeedbackConstants;
import android.view.View;
import androidx.annotation.NonNull;
import androidx.core.view.OneShotPreDrawListener;
import androidx.recyclerview.widget.ItemTouchHelper;
import androidx.recyclerview.widget.RecyclerView;
import com.mishiranu.dashchan.R;
import com.mishiranu.dashchan.content.database.HistoryDatabase.HistoryItem;
import com.mishiranu.dashchan.ui.navigator.adapter.HistoryAdapter;
import com.mishiranu.dashchan.util.InterfaceMotion;
import com.mishiranu.dashchan.util.ThreadMotionKey;
import com.mishiranu.dashchan.util.ThreadSwipeIdentity;
import com.mishiranu.dashchan.widget.InterfaceAppearance;
import com.mishiranu.dashchan.widget.ThreadHideSwipeAppearance;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.function.Consumer;

/** Page-local history removal. A confirmed identity is passed once to the existing database writer. */
public final class HistoryRemoveSwipeController implements AutoCloseable {
	private final RecyclerView list;
	private final HistoryAdapter adapter;
	private final Consumer<HistoryItem> remove;
	private final ThreadHideSwipeAppearance appearance;
	private final ItemTouchHelper helper;
	private final IdentityHashMap<RecyclerView.ViewHolder, Gesture> gestures = new IdentityHashMap<>();
	private RecyclerView.ViewHolder selected;
	private long generation;
	private boolean enabled, closed, resetPending, attached;
	private Reflow reflow;

	private static ThreadMotionKey key(View view) {
		Object key = view.getTag(R.id.history_item_motion_key);
		return key instanceof ThreadMotionKey ? (ThreadMotionKey) key : null;
	}
	private static ThreadMotionKey key(HistoryItem item) {
		return new ThreadMotionKey(item.chanName, item.boardName, item.threadNumber);
	}
	private static boolean foreignMotion(View view) {
		for (View current = view; current != null; current = current.getParent() instanceof View ? (View) current.getParent() : null) {
			if (InterfaceMotion.isAnimating(current)) return true;
		}
		return false;
	}

	private final class Gesture {
		final RecyclerView.ViewHolder holder;
		final ThreadSwipeIdentity identity;
		boolean valid = true, haptic;
		Gesture(RecyclerView.ViewHolder holder, ThreadMotionKey key) {
			this.holder = holder; identity = new ThreadSwipeIdentity(key, generation);
		}
		void cancel() { valid = false; identity.cancel(); }
		void clear() {
			cancel(); View view = holder.itemView;
			if (view.getTag(R.id.history_remove_swipe_state) == this) {
				view.setTag(R.id.history_remove_swipe_state, null);
				if (!InterfaceMotion.isAnimating(view)) {
					ItemTouchHelper.Callback.getDefaultUIUtil().clearView(view); view.setAlpha(1f);
				}
			}
			if (gestures.get(holder) == this) gestures.remove(holder);
		}
	}

	/** Rebind/recycle is a boundary: release only this feature's properties, before replacing content. */
	public static void onBinding(View view) {
		Object motion = view.getTag(R.id.history_remove_motion_owner);
		if (motion instanceof OwnedView) ((OwnedView) motion).release();
		Object gesture = view.getTag(R.id.history_remove_swipe_state);
		if (gesture instanceof HistoryRemoveSwipeController.Gesture) ((HistoryRemoveSwipeController.Gesture) gesture).clear();
	}

	public HistoryRemoveSwipeController(RecyclerView list, HistoryAdapter adapter, Consumer<HistoryItem> remove) {
		this.list = list; this.adapter = adapter; this.remove = remove;
		appearance = new ThreadHideSwipeAppearance(list.getContext(), R.string.delete, R.drawable.ic_delete);
		helper = new ItemTouchHelper(new Callback());
		adapter.registerAdapterDataObserver(observer); list.addItemDecoration(decoration);
		list.addOnScrollListener(scroll); list.addOnChildAttachStateChangeListener(children);
	}
	public void resume() {
		if (closed || enabled) return;
		enabled = true; helper.attachToRecyclerView(list); attached = true;
	}
	public void cancelPresentation() {
		if (closed) return;
		boolean active = enabled; pause(); if (active) resume();
	}
	public void pause() {
		enabled = false; generation++; list.removeCallbacks(resetHelper); resetPending = false;
		finishReflow(); clearGestures();
		if (attached) { helper.attachToRecyclerView(null); attached = false; }
	}
	@Override public void close() {
		if (closed) return;
		pause(); closed = true;
		adapter.unregisterAdapterDataObserver(observer); list.removeItemDecoration(decoration);
		list.removeOnScrollListener(scroll); list.removeOnChildAttachStateChangeListener(children);
	}
	private void clearGestures() {
		selected = null;
		for (Gesture gesture : new ArrayList<>(gestures.values())) gesture.clear();
	}
	private final Runnable resetHelper = this::resetAfterDataChange;
	private void resetAfterDataChange() {
		resetPending = false;
		if (attached) { helper.attachToRecyclerView(null); attached = false; }
		if (enabled && !closed) { helper.attachToRecyclerView(list); attached = true; }
	}
	private void changed() {
		generation++;
		Reflow waiting = reflow != null && reflow.waitingForData ? reflow : null;
		if (waiting == null) finishReflow();
		clearGestures();
		if (waiting != null) waiting.prepare();
		if (enabled && !resetPending) {
			resetPending = true;
			// Never remove an ItemDecoration from inside RecyclerView layout/bind.
			list.post(resetHelper);
		}
	}
	private final RecyclerView.AdapterDataObserver observer = new RecyclerView.AdapterDataObserver() {
		@Override public void onChanged() { changed(); }
		@Override public void onItemRangeChanged(int start, int count, Object payload) { changed(); }
		@Override public void onItemRangeChanged(int start, int count) { changed(); }
		@Override public void onItemRangeInserted(int start, int count) { changed(); }
		@Override public void onItemRangeRemoved(int start, int count) { changed(); }
		@Override public void onItemRangeMoved(int from, int to, int count) { changed(); }
	};
	private final RecyclerView.OnScrollListener scroll = new RecyclerView.OnScrollListener() {
		@Override public void onScrolled(@NonNull RecyclerView view, int dx, int dy) {
			if (dx != 0 || dy != 0) finishReflow();
		}
		@Override public void onScrollStateChanged(@NonNull RecyclerView view, int state) {
			if (state != RecyclerView.SCROLL_STATE_IDLE) finishReflow();
		}
	};
	private final RecyclerView.OnChildAttachStateChangeListener children = new RecyclerView.OnChildAttachStateChangeListener() {
		@Override public void onChildViewAttachedToWindow(@NonNull View view) {}
		@Override public void onChildViewDetachedFromWindow(@NonNull View view) { onBinding(view); }
	};
	private final RecyclerView.ItemDecoration decoration = new RecyclerView.ItemDecoration() {
		@Override public void onDraw(@NonNull Canvas canvas, @NonNull RecyclerView parent, @NonNull RecyclerView.State state) {
			if (reflow != null) reflow.draw(canvas);
		}
	};

	private HistoryItem current(RecyclerView.ViewHolder holder) {
		int position = holder.getBindingAdapterPosition();
		if (list.getAdapter() != adapter || holder.getBindingAdapter() != adapter || position == RecyclerView.NO_POSITION || position >= adapter.getItemCount()
				|| holder.itemView.getParent() != list) return null;
		return adapter.getItem(position);
	}
	private final class Callback extends ItemTouchHelper.SimpleCallback {
		Callback() { super(0, ItemTouchHelper.LEFT); }
		@Override public int getSwipeDirs(@NonNull RecyclerView parent, @NonNull RecyclerView.ViewHolder holder) {
			HistoryItem item = current(holder);
			return !enabled || resetPending || (reflow != null && reflow.waitingForData) || item == null
					|| foreignMotion(holder.itemView) ? 0 : super.getSwipeDirs(parent, holder);
		}
		@Override public boolean onMove(@NonNull RecyclerView parent, @NonNull RecyclerView.ViewHolder holder,
				@NonNull RecyclerView.ViewHolder target) { return false; }
		@Override public void onSelectedChanged(RecyclerView.ViewHolder holder, int state) {
			selected = null;
			if (holder != null && state == ItemTouchHelper.ACTION_STATE_SWIPE && enabled && !resetPending) {
				finishReflow(); HistoryItem item = current(holder); ThreadMotionKey key = key(holder.itemView);
				if (item != null && key != null && key.equals(key(item))) {
					Gesture old = gestures.get(holder); if (old != null) old.clear();
					Gesture gesture = new Gesture(holder, key); gestures.put(holder, gesture);
					holder.itemView.setTag(R.id.history_remove_swipe_state, gesture); selected = holder;
				}
			}
			super.onSelectedChanged(holder, state);
		}
		@Override public long getAnimationDuration(@NonNull RecyclerView parent, int type, float dx, float dy) {
			return InterfaceMotion.isEnabled() ? InterfaceMotion.duration(
					type == ItemTouchHelper.ANIMATION_TYPE_SWIPE_SUCCESS ? 220 : 180) : 0;
		}
		@Override public void onChildDraw(@NonNull Canvas canvas, @NonNull RecyclerView parent,
				@NonNull RecyclerView.ViewHolder holder, float dx, float dy, int state, boolean active) {
			Gesture gesture = gestures.get(holder);
			if (gesture == null || !gesture.valid || !gesture.identity.key.equals(key(holder.itemView))) return;
			if (!enabled || foreignMotion(holder.itemView)) { gesture.clear(); return; }
			if (state == ItemTouchHelper.ACTION_STATE_SWIPE) {
				float threshold = Math.max(1, parent.getWidth() * getSwipeThreshold(holder));
				float p = Math.min(1, Math.abs(dx) / threshold);
				float alpha = 1 - .1f * p;
				if (!active && Math.abs(dx) > threshold) alpha *= 1 - Math.min(1,
						(Math.abs(dx) - threshold) / Math.max(1, parent.getWidth() - threshold));
				holder.itemView.setAlpha(alpha);
				ThreadHideSwipeAppearance.Shape shape = appearance.shape(holder.itemView); shape.bounds.offset(0, dy);
				if (InterfaceAppearance.isEnabled(parent.getContext())) appearance.draw(canvas, parent, shape, dx, p);
				if (active && p >= 1 && !gesture.haptic) {
					gesture.haptic = true; holder.itemView.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK);
				}
			}
			super.onChildDraw(canvas, parent, holder, dx, dy, state, active);
		}
		@Override public void clearView(@NonNull RecyclerView parent, @NonNull RecyclerView.ViewHolder holder) {
			Gesture gesture = gestures.get(holder);
			if (gesture != null) {
				if (!InterfaceMotion.isAnimating(holder.itemView)) super.clearView(parent, holder);
				gesture.clear();
			}
		}
		@Override public void onSwiped(@NonNull RecyclerView.ViewHolder holder, int direction) {
			Gesture gesture = gestures.get(holder); HistoryItem item = current(holder);
			if (!enabled || resetPending || direction != ItemTouchHelper.LEFT
					|| selected == holder || gesture == null || !gesture.valid || item == null
					|| !gesture.identity.key.equals(key(holder.itemView))
					|| !gesture.identity.consume(key(item), generation)) return;
			finishReflow();
			// History is refreshed asynchronously from a cursor. Capture now, animate only
			// after that cursor is replaced; never animate the old positions as new data.
			if (InterfaceAppearance.isEnabled(list.getContext()) && InterfaceMotion.isEnabled()
					&& ValueAnimator.areAnimatorsEnabled()) reflow = new Reflow(gesture.identity.key, holder.itemView);
			remove.accept(item);
		}
	}

	private void finishReflow() { if (reflow != null) reflow.finish(); }
	private final class Reflow {
		final HashMap<ThreadMotionKey, Rect> before = new HashMap<>();
		final ArrayList<OwnedView> owned = new ArrayList<>();
		final ThreadHideSwipeAppearance.Shape from;
		final ThreadHideSwipeAppearance.Shape to;
		OneShotPreDrawListener pending;
		ValueAnimator animator;
		float progress;
		long expected;
		boolean done, waitingForData = true;
		Reflow(ThreadMotionKey removed, View outgoing) {
			from = appearance.shape(outgoing);
			to = new ThreadHideSwipeAppearance.Shape(new RectF(from.bounds.left, from.bounds.top, from.bounds.right, from.bounds.top), from.radius);
			for (int i = 0; i < list.getChildCount(); i++) {
				View view = list.getChildAt(i); ThreadMotionKey key = key(view);
				if (key != null && !removed.equals(key)) {
					before.put(key, new Rect(view.getLeft(), view.getTop(), view.getRight(), view.getBottom()));
				}
			}
		}
		void prepare() {
			waitingForData = false;
			expected = generation;
			pending = OneShotPreDrawListener.add(list, () -> { pending = null; start(); }); list.invalidate();
		}
		void start() {
			if (done || reflow != this || !enabled || expected != generation || !list.isAttachedToWindow()
					|| list.hasPendingAdapterUpdates() || list.isLayoutRequested() || foreignMotion(list)
					|| !InterfaceMotion.isEnabled() || !ValueAnimator.areAnimatorsEnabled()) { finish(); return; }
			for (int i = 0; i < list.getChildCount(); i++) {
				View view = list.getChildAt(i); ThreadMotionKey key = key(view);
				if (key == null) continue;
				if (view.getTag(R.id.history_remove_swipe_state) != null || foreignMotion(view)
						|| view.getTranslationX() != 0 || view.getTranslationY() != 0) continue;
				Rect old = before.get(key);
				float dx = old != null ? old.left - view.getLeft() : 0, dy = old != null ? old.top - view.getTop() : 0;
				boolean fade = old == null;
				if (dx != 0 || dy != 0 || fade) {
					OwnedView state = new OwnedView(view, key, dx, dy, fade); owned.add(state); state.frame(0);
				}
			}
			before.clear(); animator = ValueAnimator.ofFloat(0, 1); animator.setDuration(InterfaceMotion.duration(180)); animator.setInterpolator(InterfaceMotion.STANDARD);
			animator.addUpdateListener(value -> {
				if (!enabled || expected != generation || foreignMotion(list)
						|| !InterfaceMotion.isEnabled() || !ValueAnimator.areAnimatorsEnabled()) { finish(); return; }
				progress = (float) value.getAnimatedValue();
				for (OwnedView state : owned) state.frame(progress); list.invalidate();
			});
			animator.addListener(new AnimatorListenerAdapter() {
				@Override public void onAnimationEnd(Animator animation) { finish(); }
			}); animator.start();
		}
		void draw(Canvas canvas) { if (!done && !waitingForData) appearance.drawReflow(canvas, list, from, to, progress); }
		void finish() {
			if (done) return;
			done = true; if (pending != null) { pending.removeListener(); pending = null; }
			if (animator != null) { ValueAnimator running = animator; animator = null; running.cancel(); running.removeAllUpdateListeners(); running.removeAllListeners(); }
			for (OwnedView state : owned) state.release(); owned.clear(); before.clear();
			if (reflow == this) reflow = null; list.invalidate();
		}
	}
	static final class OwnedView {
		final View view;
		final ThreadMotionKey key;
		final float dx, dy, alpha;
		final boolean fade;
		boolean released;
		OwnedView(View view, ThreadMotionKey key, float dx, float dy, boolean fade) {
			this.view = view; this.key = key; this.dx = dx; this.dy = dy; this.fade = fade; alpha = view.getAlpha();
			view.setTag(R.id.history_remove_motion_owner, this);
		}
		void frame(float progress) {
			if (released) return;
			if (view.getTag(R.id.history_remove_motion_owner) != this || !key.equals(key(view)) || foreignMotion(view)) { release(); return; }
			view.setTranslationX(dx * (1 - progress)); view.setTranslationY(dy * (1 - progress));
			if (fade) view.setAlpha(alpha * Math.min(1, progress * 1.5f));
		}
		void release() {
			if (released) return;
			released = true;
			if (view.getTag(R.id.history_remove_motion_owner) == this) {
				view.setTag(R.id.history_remove_motion_owner, null);
				// A screen transition may own an ancestor; these row-local values still belong to us.
				if (!InterfaceMotion.isAnimating(view)) { view.setTranslationX(0); view.setTranslationY(0); if (fade) view.setAlpha(alpha); }
			}
		}
	}
}
