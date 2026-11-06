package com.mishiranu.dashchan.ui;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.drawable.Drawable;
import android.util.TypedValue;
import android.view.HapticFeedbackConstants;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewTreeObserver;
import androidx.annotation.NonNull;
import androidx.recyclerview.widget.ItemTouchHelper;
import androidx.recyclerview.widget.RecyclerView;
import com.mishiranu.dashchan.R;
import com.mishiranu.dashchan.content.Preferences;
import com.mishiranu.dashchan.content.model.PostNumber;
import com.mishiranu.dashchan.ui.posting.PostingFragment;
import com.mishiranu.dashchan.util.GraphicsUtils;
import com.mishiranu.dashchan.util.InterfaceMotion;
import com.mishiranu.dashchan.util.ReplySwipeOverlapSpec;
import com.mishiranu.dashchan.util.ReplySwipeRecovery;
import com.mishiranu.dashchan.util.ResourceUtils;
import com.mishiranu.dashchan.widget.PaddedRecyclerView;
import java.util.Objects;
import java.util.function.Consumer;

/** Page-local reply presentation. ItemTouchHelper still owns gesture recognition. */
public final class ReplySwipeController implements AutoCloseable, View.OnAttachStateChangeListener {
	public interface Host {
		PostNumber post(RecyclerView.ViewHolder holder);
		boolean canReply();
		void reply(PostNumber post, ReplySwipeOverlap.Request request);
	}
	private final PaddedRecyclerView list;
	private final RecyclerView.Adapter<?> adapter;
	private final Host host;
	private final float trigger, maxReveal, circle, textWidth, iconGap;
	private final int iconSize;
	private final String replyText;
	private final Paint actionPaint = new Paint(Paint.ANTI_ALIAS_FLAG), textPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
	private final Drawable icon;
	private ItemTouchHelper helper;
	private Gesture selected;
	private Return returning;
	private long revision;
	private boolean capturingReturn;
	private boolean enabled, closed, attached, resetPending, clearingNative, released, canceled;
	private final Consumer<MotionEvent> touchObserver = this::touch;
	private final Runnable reset = this::resetHelper;
	private final ViewTreeObserver.OnWindowFocusChangeListener focus = hasFocus -> { if (!hasFocus) invalidate(); };

	private final class Gesture {
		final RecyclerView.ViewHolder holder;
		final PostNumber post;
		final long revision = ReplySwipeController.this.revision;
		final boolean right = Preferences.isVideoRightHandControls();
		boolean haptic;
		Gesture(RecyclerView.ViewHolder holder, PostNumber post) { this.holder = holder; this.post = post; }
	}
	private final class Return {
		final Gesture gesture;
		final ReplySwipeRecovery recovery;
		final boolean accepted;
		final long releasedAt = android.os.SystemClock.uptimeMillis();
		final float releaseX;
		boolean posted, openingPosted, transferred;
		ReplySwipeOverlap.Clock clock;
		Request request;
		final Runnable complete = () -> complete(this);
		final Runnable open = () -> requestOverlap(this);
		Return(Gesture gesture, float from, float to, float visible, boolean accepted) {
			this.gesture = gesture; this.accepted = accepted; releaseX = visible;
			recovery = new ReplySwipeRecovery(host, gesture.post, gesture.revision, from, to, visible, accepted);
		}
		void stop() {
			list.removeCallbacks(complete); list.removeCallbacks(open);
			if (clock != null) { clock.stop(); clock = null; }
		}
	}

	private final class Request implements ReplySwipeOverlap.Request {
		final Return motion;
		boolean committed;

		Request(Return motion) { this.motion = motion; }
		@Override public long releasedAtUptime() { return motion.releasedAt; }
		@Override public boolean getAsBoolean() {
			return !committed && motion.request == this && valid(motion.gesture);
		}
		@Override public boolean prepare(ReplySwipeOverlap overlap, View source, PostingFragment destination) {
			if (!getAsBoolean()) return false;
			if (returning == motion && motion.clock != null && motion.clock.time() < ReplySwipeOverlapSpec.RETURN_MS) {
				capturingReturn = true;
				boolean captured;
				try {
					captured = overlap != null && !list.isComputingLayout()
							&& overlap.prepare(source, list, motion.gesture.holder.itemView,
									destination, motion.clock, motion.releaseX);
				} finally {
					capturingReturn = false;
				}
				if (!captured) {
					motion.request = null; // The existing completion can still open the form.
					return false;
				}
				motion.clock = null; motion.transferred = true;
				// A subsequent predictive-Back snapshot must see the thread in its neutral state.
				clear(motion.gesture);
			}
			committed = true;
			return true;
		}
	}

	public ReplySwipeController(PaddedRecyclerView list, RecyclerView.Adapter<?> adapter, Host host) {
		this.list = list; this.adapter = adapter; this.host = host; attached = list.isAttachedToWindow();
		Context context = list.getContext(); float density = ResourceUtils.obtainDensity(context);
		trigger = 45 * density; maxReveal = 56 * density; circle = 32 * density; iconGap = 3 * density;
		iconSize = Math.round(20 * density);
		int color = ResourceUtils.getColor(context, R.attr.colorAccentSupport);
		actionPaint.setColor(color); textPaint.setColor(color);
		textPaint.setTextSize(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, 10, context.getResources().getDisplayMetrics()));
		replyText = context.getString(R.string.reply); textWidth = textPaint.measureText(replyText);
		icon = ResourceUtils.getDrawable(context, R.drawable.ic_swipe_reply).mutate();
		icon.setTint(GraphicsUtils.isLight(color) ? Color.BLACK : Color.WHITE);
		adapter.registerAdapterDataObserver(data); list.addOnScrollListener(scroll);
		list.addOnChildAttachStateChangeListener(children); list.addOnAttachStateChangeListener(this);
		list.getViewTreeObserver().addOnWindowFocusChangeListener(focus);
	}
	public void resume() {
		if (closed || enabled) return;
		enabled = true; list.setReplySwipeTouchObserver(touchObserver); resetWhenSafe();
	}
	public void pause() {
		enabled = false; list.clearReplySwipeTouchObserver(touchObserver);
		cancelState(); resetWhenSafe();
	}
	@Override public void close() {
		if (closed) return;
		closed = true; pause();
		adapter.unregisterAdapterDataObserver(data); list.removeOnScrollListener(scroll);
		list.removeOnChildAttachStateChangeListener(children); list.removeOnAttachStateChangeListener(this);
		if (list.getViewTreeObserver().isAlive()) list.getViewTreeObserver().removeOnWindowFocusChangeListener(focus);
	}
	private boolean available() {
		return enabled && !closed && attached && !resetPending && list.isAttachedToWindow()
				&& list.hasWindowFocus() && list.getAdapter() == adapter && Preferences.isSwipeReplyEnabled() && host.canReply();
	}
	private PostNumber current(RecyclerView.ViewHolder holder) {
		return holder != null && holder.getBindingAdapter() == adapter
				&& holder.getBindingAdapterPosition() != RecyclerView.NO_POSITION && holder.itemView.getParent() == list
				? host.post(holder) : null;
	}
	private boolean valid(Gesture gesture) {
		return available() && gesture != null && gesture.revision == revision
				&& gesture.right == Preferences.isVideoRightHandControls() && Objects.equals(gesture.post, current(gesture.holder));
	}
	private void touch(MotionEvent event) {
		int action = event.getActionMasked();
		if (action == MotionEvent.ACTION_DOWN) {
			if (selected != null || returning != null) invalidate();
			else revision++; // A new touch also invalidates any deferred reply from a completed return.
			released = canceled = false;
		} else if (action == MotionEvent.ACTION_UP) released = !canceled;
		else if (action == MotionEvent.ACTION_CANCEL || action == MotionEvent.ACTION_POINTER_DOWN) {
			canceled = true; released = false;
		}
	}
	private void clear(Gesture gesture) {
		if (gesture != null) ItemTouchHelper.Callback.getDefaultUIUtil().clearView(gesture.holder.itemView);
	}
	private void cancelState() {
		revision++;
		if (returning != null) {
			returning.stop(); returning.recovery.cancel(); clear(returning.gesture);
		}
		clear(selected); selected = null; returning = null; released = false; canceled = true;
		list.invalidate();
	}
	private void invalidate() {
		if (closed) return;
		cancelState();
		if (!resetPending) { resetPending = true; list.post(reset); }
	}
	private void resetWhenSafe() {
		list.removeCallbacks(reset); resetPending = true;
		if (list.isComputingLayout()) list.post(reset); else resetHelper();
	}
	private void resetHelper() {
		if (list.isComputingLayout()) { resetPending = true; list.post(reset); return; }
		resetPending = false; clearingNative = true;
		try {
			if (helper != null) { helper.attachToRecyclerView(null); helper = null; }
			if (enabled && !closed && attached) {
				// A fresh helper also discards successful-swipe pending cleanup and late dispatches.
				helper = new ItemTouchHelper(new Callback()); helper.attachToRecyclerView(list);
			}
		} finally { clearingNative = false; }
	}
	private void frame(Return motion) {
		if (returning != motion || motion.clock == null) return;
		float time = motion.clock.time();
		if (time >= ReplySwipeOverlapSpec.RETURN_MS) postComplete(motion);
		else if (time >= ReplySwipeOverlapSpec.OPEN_MS && !motion.openingPosted) {
			motion.openingPosted = true;
			list.post(motion.open);
		}
		list.invalidate();
	}
	private void requestOverlap(Return motion) {
		if (returning != motion || motion.clock == null || motion.request != null || !valid(motion.gesture)) return;
		if (motion.clock.time() >= ReplySwipeOverlapSpec.RETURN_MS) { postComplete(motion); return; }
		dispatch(motion, motion.gesture.post);
	}
	private void dispatch(Return motion, PostNumber post) {
		Request request = new Request(motion);
		motion.request = request;
		host.reply(post, request);
	}
	private void postComplete(Return motion) {
		if (motion == returning && !motion.posted) { motion.posted = true; list.post(motion.complete); }
	}
	private void complete(Return motion) {
		if (returning != motion) return;
		if (list.isComputingLayout()) { list.post(motion.complete); return; }
		PostNumber post = valid(motion.gesture) ? current(motion.gesture.holder) : null;
		boolean confirmed = motion.recovery.consume(host, post, revision);
		motion.stop(); clear(motion.gesture); selected = null; returning = null;
		// Detach through the public API after drawing, clearing native elevation/translation.
		// No adapter mutation or change animation is needed to clean up a non-dismissed row.
		resetWhenSafe();
		if (confirmed && motion.request == null && valid(motion.gesture)) dispatch(motion, post);
	}
	private final RecyclerView.AdapterDataObserver data = new RecyclerView.AdapterDataObserver() {
		@Override public void onChanged() { invalidate(); }
		@Override public void onItemRangeChanged(int start, int count) { invalidate(); }
		@Override public void onItemRangeChanged(int start, int count, Object payload) { invalidate(); }
		@Override public void onItemRangeInserted(int start, int count) { invalidate(); }
		@Override public void onItemRangeRemoved(int start, int count) { invalidate(); }
		@Override public void onItemRangeMoved(int from, int to, int count) { invalidate(); }
	};
	private final RecyclerView.OnScrollListener scroll = new RecyclerView.OnScrollListener() {
		@Override public void onScrolled(@NonNull RecyclerView view, int dx, int dy) {
			if ((selected != null || returning != null) && (dx != 0 || dy != 0)) invalidate();
		}
		@Override public void onScrollStateChanged(@NonNull RecyclerView view, int state) {
			if ((selected != null || returning != null) && state != RecyclerView.SCROLL_STATE_IDLE) invalidate();
		}
	};
	private final RecyclerView.OnChildAttachStateChangeListener children = new RecyclerView.OnChildAttachStateChangeListener() {
		@Override public void onChildViewAttachedToWindow(@NonNull View view) {}
		@Override public void onChildViewDetachedFromWindow(@NonNull View view) {
			if (selected != null && selected.holder.itemView == view
					|| returning != null && returning.gesture.holder.itemView == view) invalidate();
		}
	};
	@Override public void onViewAttachedToWindow(View view) { attached = true; if (enabled) resetWhenSafe(); }
	@Override public void onViewDetachedFromWindow(View view) { attached = false; cancelState(); resetWhenSafe(); }

	private final class Callback extends ItemTouchHelper.SimpleCallback {
		Callback() { super(0, ItemTouchHelper.LEFT | ItemTouchHelper.RIGHT); }
		@Override public int getSwipeDirs(@NonNull RecyclerView parent, @NonNull RecyclerView.ViewHolder holder) {
			if (!available() || canceled || returning != null || current(holder) == null) return 0;
			return Preferences.isVideoRightHandControls() ? ItemTouchHelper.RIGHT : ItemTouchHelper.LEFT;
		}
		@Override public float getSwipeThreshold(@NonNull RecyclerView.ViewHolder holder) {
			return Math.min(.95f, trigger / Math.max(1f, holder.itemView.getWidth()));
		}
		@Override public float getSwipeEscapeVelocity(float normal) { return Float.MAX_VALUE; }
		@Override public boolean onMove(@NonNull RecyclerView parent, @NonNull RecyclerView.ViewHolder holder,
				@NonNull RecyclerView.ViewHolder target) { return false; }
		@Override public void onSelectedChanged(RecyclerView.ViewHolder holder, int state) {
			if (holder != null && state == ItemTouchHelper.ACTION_STATE_SWIPE && available()) {
				PostNumber post = current(holder); selected = post != null ? new Gesture(holder, post) : null;
			} else selected = null;
			super.onSelectedChanged(holder, state);
		}
		@Override public long getAnimationDuration(@NonNull RecyclerView parent, int type, float dx, float dy) {
			if (selected == null || type == ItemTouchHelper.ANIMATION_TYPE_DRAG) return 0;
			boolean success = type == ItemTouchHelper.ANIMATION_TYPE_SWIPE_SUCCESS;
			// Public callback distances: end is +/- RecyclerView width for success, zero for cancel.
			float end = success ? (selected.right ? 1 : -1) * parent.getWidth() : 0;
			Return motion = new Return(selected, end - dx, end, selected.holder.itemView.getTranslationX(),
					success && released && !canceled && valid(selected));
			returning = motion;
			long duration = InterfaceMotion.duration(ReplySwipeOverlapSpec.RETURN_MS);
			if (duration == 0) postComplete(motion);
			else if (motion.accepted && Float.isFinite(motion.releaseX) && motion.releaseX != 0f
					&& InterfaceMotion.isEnabled()) {
				motion.clock = new ReplySwipeOverlap.Clock(() -> frame(motion));
				motion.clock.start();
			}
			return duration;
		}
		@Override public void onSwiped(@NonNull RecyclerView.ViewHolder holder, int direction) {
			if (returning != null && returning.gesture.holder == holder && returning.clock == null
					&& !returning.transferred) postComplete(returning);
		}
		@Override public void clearView(@NonNull RecyclerView parent, @NonNull RecyclerView.ViewHolder holder) {
			super.clearView(parent, holder);
			if (clearingNative) return;
			if (returning != null && returning.gesture.holder == holder) {
				if (!returning.accepted) postComplete(returning); else if (!returning.transferred) invalidate();
			}
		}
		@Override public void onChildDraw(@NonNull Canvas canvas, @NonNull RecyclerView parent,
				@NonNull RecyclerView.ViewHolder holder, float dx, float dy, int state, boolean active) {
			Return motion = returning;
			if (capturingReturn) {
				// The still scene omits the row; the existing action is behind the independently moving card.
				if (motion != null && motion.clock != null && motion.gesture.holder == holder) {
					float offset = ReplySwipeOverlapSpec.offset(motion.releaseX, motion.clock.time());
					if (offset != 0f) drawAction(canvas, holder.itemView, offset, motion.gesture.right);
				}
				return;
			}
			if (!active && motion != null && motion.transferred) return;
			Gesture gesture = active ? selected : motion != null ? motion.gesture : null;
			float visible = 0;
			if (gesture != null && gesture.holder == holder && !valid(gesture)) invalidate();
			if (gesture != null && gesture.holder == holder && valid(gesture)) {
				if (active) {
					visible = gesture.right ? Math.max(0, Math.min(maxReveal, dx)) : Math.min(0, Math.max(-maxReveal, dx));
					boolean reached = Math.abs(visible) >= trigger;
					if (reached && !gesture.haptic) holder.itemView.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK);
					gesture.haptic = reached;
				} else if (motion.clock != null) {
					visible = ReplySwipeOverlapSpec.offset(motion.releaseX, motion.clock.time());
				} else {
					visible = motion.recovery.offset(dx);
					if (motion.recovery.progress(dx) >= 1f) postComplete(motion);
				}
				if (Math.abs(visible) > 0) drawAction(canvas, holder.itemView, visible, gesture.right);
			}
			super.onChildDraw(canvas, parent, holder, visible, dy, state, active);
		}
	}
	private void drawAction(Canvas canvas, View view, float dx, boolean rightHand) {
		float reveal = Math.abs(dx), left = rightHand ? view.getLeft() : view.getRight() - reveal;
		float right = rightHand ? view.getLeft() + reveal : view.getRight();
		int save = canvas.save();
		try {
			canvas.clipRect(left, view.getTop(), right, view.getBottom());
			int alpha = Math.round(255 * Math.min(1f, reveal / trigger));
			actionPaint.setAlpha(alpha); textPaint.setAlpha(alpha); icon.setAlpha(alpha);
			float x = rightHand ? view.getLeft() + maxReveal / 2 : view.getRight() - maxReveal / 2;
			Paint.FontMetrics metrics = textPaint.getFontMetrics();
			float contentHeight = circle + iconGap + metrics.descent - metrics.ascent;
			float y = (view.getTop() + view.getBottom()) / 2f - contentHeight / 2 + circle / 2;
			float scale = .82f + .18f * Math.min(1f, reveal / trigger);
			canvas.drawCircle(x, y, circle * scale / 2, actionPaint);
			int l = Math.round(x - iconSize / 2f), t = Math.round(y - iconSize / 2f);
			icon.setBounds(l, t, l + iconSize, t + iconSize);
			if (rightHand) { int flipped = canvas.save(); canvas.scale(-1, 1, x, y); icon.draw(canvas); canvas.restoreToCount(flipped); }
			else icon.draw(canvas);
			canvas.drawText(replyText, x - textWidth / 2, y + circle / 2 + iconGap - metrics.ascent, textPaint);
		} finally { canvas.restoreToCount(save); }
	}
}
