package com.mishiranu.dashchan.widget;

import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;
import android.os.SystemClock;
import android.util.AttributeSet;
import android.util.Xml;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.ViewGroup;
import android.view.ViewParent;
import androidx.annotation.NonNull;
import androidx.recyclerview.widget.GridLayoutManager;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import com.mishiranu.dashchan.R;
import com.mishiranu.dashchan.util.ListViewUtils;
import com.mishiranu.dashchan.util.ResourceUtils;
import com.mishiranu.dashchan.util.ViewUtils;
import org.xmlpull.v1.XmlPullParser;

public class PaddedRecyclerView extends RecyclerView implements EdgeEffectHandler.Shift, PullableWrapper.Wrapped {
	private static final long FAST_SCROLLER_TRANSITION_IN = 100;
	private static final long FAST_SCROLLER_TRANSITION_OUT = 200;
	private static final long FAST_SCROLLER_TRANSITION_OUT_DELAY = 1000;

	private final EdgeEffectHandler edgeEffectHandler = EdgeEffectHandler.bind(this, this);
	private EdgeEffectHandler.Shift shift;
	private PullableWrapper pullableWrapper;

	private final Drawable thumbDrawable;
	private final Drawable trackDrawable;
	private final int touchSlop;
	private final int minTrackSize;
	private final Drawable fastScrollerOverlayDrawable;
	private ViewGroup fastScrollerOverlayHost;
	private final int[] fastScrollerLocation = new int[2];
	private final int[] fastScrollerOverlayHostLocation = new int[2];
	private ImportantPostsMarksFastScrollBarDecoration importantPostsMarksFastScrollBarDecoration;

	private boolean verticalScrollBarRequested;
	private boolean fastScrollerEnabled;
	private boolean fastScrollerAllowed;
	private boolean regularScrolling;
	private boolean fastScrolling;

	private boolean fastScrollingDown;
	private Float fastScrollingStartOffset;
	private float fastScrollingStartY;
	private float fastScrollingCurrentY;
	private int pendingFastScrollPosition = NO_POSITION;
	private double pendingFastScrollFraction;
	private boolean pendingFastScrollAlignEnd;
	private int pendingFastScrollItemCount;

	private long showFastScrollingStart;
	private boolean showFastScrolling;

	private static AttributeSet createDefaultAttributeSet(Context context) {
		try {
			XmlPullParser parser = context.getResources().getXml(R.xml.scrollbars);
			parser.next();
			parser.nextTag();
			return Xml.asAttributeSet(parser);
		} catch (RuntimeException e) {
			throw e;
		} catch (Exception e) {
			throw new RuntimeException(e);
		}
	}

	public PaddedRecyclerView(@NonNull Context context) {
		this(context, createDefaultAttributeSet(context));
		setVerticalScrollBarEnabled(false);
		setHorizontalScrollBarEnabled(false);
	}

	public PaddedRecyclerView(@NonNull Context context, AttributeSet attrs) {
		super(context, attrs);
		verticalScrollBarRequested = isVerticalScrollBarEnabled();
	}

	public PaddedRecyclerView(@NonNull Context context, AttributeSet attrs, int defStyleAttr) {
		super(context, attrs, defStyleAttr);
		verticalScrollBarRequested = isVerticalScrollBarEnabled();
	}

	/* init */ {
		ThemeEngine.Theme theme = ThemeEngine.getTheme(getContext());
		edgeEffectHandler.setColor(theme.accent);
		edgeEffectHandler.setShiftColor(theme.window);

		float density = ResourceUtils.obtainDensity(this);
		Drawable thumbDrawable = ResourceUtils.getDrawable(getContext(), android.R.attr.fastScrollThumbDrawable, 0);
		this.thumbDrawable = thumbDrawable;
		int[][] states = {{android.R.attr.state_enabled, android.R.attr.state_pressed},
				{android.R.attr.state_enabled}};
		int[] colors = {theme.accent, theme.controlNormal21};
		thumbDrawable.setTintList(new ColorStateList(states, colors));
		trackDrawable = ResourceUtils.getDrawable(getContext(), android.R.attr.fastScrollTrackDrawable, 0);
		touchSlop = ViewConfiguration.get(getContext()).getScaledTouchSlop();
		minTrackSize = (int) (16f * density);
		fastScrollerOverlayDrawable = new Drawable() {
			@Override
			public void draw(@NonNull Canvas canvas) {
				ViewGroup overlayHost = fastScrollerOverlayHost;
				if (overlayHost == null || PaddedRecyclerView.this.getVisibility() != View.VISIBLE) {
					return;
				}
				PaddedRecyclerView.this.getLocationInWindow(fastScrollerLocation);
				overlayHost.getLocationInWindow(fastScrollerOverlayHostLocation);
				int saveCount = canvas.save();
				canvas.translate(fastScrollerLocation[0] - fastScrollerOverlayHostLocation[0],
						fastScrollerLocation[1] - fastScrollerOverlayHostLocation[1]);
				canvas.clipRect(0, 0, PaddedRecyclerView.this.getWidth(), PaddedRecyclerView.this.getHeight());
				onDrawFastScroller(canvas);
				canvas.restoreToCount(saveCount);
			}

			@Override
			public void setAlpha(int alpha) {}

			@Override
			public void setColorFilter(ColorFilter colorFilter) {}

			@SuppressWarnings("deprecation")
			@Override
			public int getOpacity() {
				return PixelFormat.TRANSLUCENT;
			}
		};

		setRecycledViewPool(new ListViewUtils.UnlimitedRecycledViewPool());
		addOnScrollListener(new OnScrollListener() {
			@Override
			public void onScrollStateChanged(@NonNull RecyclerView recyclerView, int newState) {
				boolean regularScrolling = newState != RecyclerView.SCROLL_STATE_IDLE;
				updateFastScroller(false, fastScrollerEnabled, fastScrollerAllowed, regularScrolling, fastScrolling);
			}

			@Override
			public void onScrolled(@NonNull RecyclerView recyclerView, int dx, int dy) {
				if (dy != 0) {
					invalidateFastScroller();
				}
			}
		});
		addOnItemTouchListener(new OnItemTouchListener() {
			@Override
			public boolean onInterceptTouchEvent(@NonNull RecyclerView rv, @NonNull MotionEvent e) {
				return handleTouchEvent(e);
			}

			@Override
			public void onTouchEvent(@NonNull RecyclerView rv, @NonNull MotionEvent e) {
				handleTouchEvent(e);
			}

			@Override
			public void onRequestDisallowInterceptTouchEvent(boolean disallowIntercept) {
				if (disallowIntercept) {
					pendingFastScrollPosition = NO_POSITION;
					fastScrollingDown = false;
					if (fastScrolling) {
						updateFastScroller(true, fastScrollerEnabled, fastScrollerAllowed, regularScrolling, false);
					}
				}
			}
		});
	}

	@Override
	protected void onAttachedToWindow() {
		super.onAttachedToWindow();
		updatePullIndicatorActivity();
		ViewParent parent = getParent();
		if (parent instanceof ViewGroup) {
			fastScrollerOverlayHost = (ViewGroup) parent;
			updateFastScrollerOverlayBounds();
			fastScrollerOverlayHost.getOverlay().add(fastScrollerOverlayDrawable);
		}
	}

	@Override
	protected void onDetachedFromWindow() {
		pendingFastScrollPosition = NO_POSITION;
		if (pullableWrapper != null) pullableWrapper.setHostActive(false);
		if (fastScrollerOverlayHost != null) {
			fastScrollerOverlayHost.getOverlay().remove(fastScrollerOverlayDrawable);
			fastScrollerOverlayHost = null;
		}
		super.onDetachedFromWindow();
	}

	private void updatePullIndicatorActivity() {
		if (pullableWrapper != null) pullableWrapper.setHostActive(isAttachedToWindow()
				&& isShown() && getWindowVisibility() == VISIBLE && hasWindowFocus());
	}

	public void refreshPullIndicator() {
		if (pullableWrapper != null) { updatePullIndicatorActivity(); invalidate(); }
	}

	@Override
	protected void onWindowVisibilityChanged(int visibility) {
		super.onWindowVisibilityChanged(visibility);
		updatePullIndicatorActivity();
	}

	@Override
	protected void onVisibilityChanged(View changedView, int visibility) {
		super.onVisibilityChanged(changedView, visibility);
		updatePullIndicatorActivity();
	}

	private void updateFastScrollerOverlayBounds() {
		if (fastScrollerOverlayHost != null) {
			fastScrollerOverlayDrawable.setBounds(0, 0,
					fastScrollerOverlayHost.getWidth(), fastScrollerOverlayHost.getHeight());
		}
	}

	private void invalidateFastScroller() {
		if (fastScrollerOverlayHost != null) {
			fastScrollerOverlayDrawable.invalidateSelf();
		} else {
			invalidate();
		}
	}

	@Override
	public void setVerticalScrollBarEnabled(boolean enabled) {
		verticalScrollBarRequested = enabled;
		updateVerticalScrollBar();
	}

	private void updateVerticalScrollBar() {
		// Preserve the caller's fallback choice, but give the available fast scroller sole ownership.
		// Use the public View API: the hidden drawing callback is not part of the SDK/R8 contract.
		super.setVerticalScrollBarEnabled(verticalScrollBarRequested && !isFastScrollerAvailable());
	}

	public void setFastScrollerEnabled(boolean fastScrollerEnabled) {
		if (this.fastScrollerEnabled != fastScrollerEnabled) {
			pendingFastScrollPosition = NO_POSITION;
			fastScrollingDown = false;
			updateFastScroller(true, fastScrollerEnabled, fastScrollerAllowed, regularScrolling, false);
		}
	}

	public void setImportantPostsMarksFastScrollBarDecoration
			(ImportantPostsMarksFastScrollBarDecoration importantPostsMarksFastScrollBarDecoration) {
		this.importantPostsMarksFastScrollBarDecoration = importantPostsMarksFastScrollBarDecoration;
		invalidateFastScroller();
	}

	private boolean isFastScrollerAvailable() {
		return fastScrollerEnabled && fastScrollerAllowed;
	}

	// Legacy geometry hook only; scrollbar exclusivity must not depend on this hidden callback.
	@SuppressWarnings("unused") // Overrides hidden Android API protected method
	protected void onDrawVerticalScrollBar(Canvas canvas, Drawable scrollBar, int l, int t, int r, int b) {
		if (!isFastScrollerAvailable()) {
			if (b - t == getHeight()) {
				t += getEdgeEffectShift(EdgeEffectHandler.Side.TOP);
				b -= getEdgeEffectShift(EdgeEffectHandler.Side.BOTTOM);
			}
			scrollBar.setBounds(l, t, r, b);
			scrollBar.draw(canvas);
		}
	}

	public void setEdgeEffectShift(EdgeEffectHandler.Shift shift) {
		this.shift = shift;
	}

	public EdgeEffectHandler getEdgeEffectHandler() {
		return edgeEffectHandler;
	}

	@Override
	public int getEdgeEffectShift(EdgeEffectHandler.Side side) {
		return shift != null ? shift.getEdgeEffectShift(side) : obtainEdgeEffectShift(side);
	}

	public final int obtainEdgeEffectShift(EdgeEffectHandler.Side side) {
		return getClipToPadding() ? 0 : side == EdgeEffectHandler.Side.TOP ? getPaddingTop() : getPaddingBottom();
	}

	@Override
	protected void onLayout(boolean changed, int l, int t, int r, int b) {
		super.onLayout(changed, l, t, r, b);
		applyPendingFastScrollFraction();
		updateFastScrollerOverlayBounds();

		int range = computeVerticalScrollRange();
		int extent = computeVerticalScrollExtent();
		boolean allowFastScrolling = extent > 0 && range >= 2 * extent;
		updateFastScroller(false, fastScrollerEnabled, allowFastScrolling, regularScrolling, fastScrolling);
		// OVER_SCROLL_IF_CONTENT_SCROLLS it not supported, see https://issuetracker.google.com/issues/37076456
		setOverScrollMode(range > extent ? OVER_SCROLL_ALWAYS : OVER_SCROLL_NEVER);
	}

	@Override
	public void onWindowFocusChanged(boolean hasWindowFocus) {
		super.onWindowFocusChanged(hasWindowFocus);
		updatePullIndicatorActivity();

		if (!hasWindowFocus) {
			pendingFastScrollPosition = NO_POSITION;
			fastScrollingDown = false;
			if (fastScrolling) {
				updateFastScroller(true, fastScrollerEnabled, fastScrollerAllowed, regularScrolling, false);
			}
		}
	}

	private final Runnable invalidateRunnable = this::invalidateFastScroller;

	private void updateFastScroller(boolean immediately, boolean fastScrollerEnabled, boolean fastScrollerAllowed,
			boolean regularScrolling, boolean fastScrolling) {
		boolean wasAvailable = isFastScrollerAvailable();
		boolean oldShow = this.fastScrollerAllowed && this.fastScrollerEnabled &&
				(this.regularScrolling || this.fastScrolling);
		boolean newShow = fastScrollerAllowed && fastScrollerEnabled &&
				(regularScrolling || fastScrolling);
		this.fastScrollerEnabled = fastScrollerEnabled;
		this.fastScrollerAllowed = fastScrollerAllowed;
		this.regularScrolling = regularScrolling;
		this.fastScrolling = fastScrolling;
		if (!isFastScrollerAvailable()) pendingFastScrollPosition = NO_POSITION;
		updateVerticalScrollBar();
		if (wasAvailable != isFastScrollerAvailable()) {
			invalidateFastScroller();
		}
		long time = SystemClock.elapsedRealtime();
		long passed = time - showFastScrollingStart;
		if (oldShow != newShow) {
			removeCallbacks(invalidateRunnable);
			long start;
			if (newShow && passed < FAST_SCROLLER_TRANSITION_OUT + FAST_SCROLLER_TRANSITION_OUT_DELAY) {
				start = passed <= FAST_SCROLLER_TRANSITION_OUT_DELAY ? 0L :
						time - (long) ((float) (FAST_SCROLLER_TRANSITION_OUT_DELAY +
								FAST_SCROLLER_TRANSITION_OUT - passed) /
								FAST_SCROLLER_TRANSITION_OUT * FAST_SCROLLER_TRANSITION_IN);
			} else if (!newShow && passed < FAST_SCROLLER_TRANSITION_IN) {
				if (immediately) {
					start = time - (long) ((float) (FAST_SCROLLER_TRANSITION_IN - passed) /
							FAST_SCROLLER_TRANSITION_IN * FAST_SCROLLER_TRANSITION_OUT) -
							FAST_SCROLLER_TRANSITION_IN - FAST_SCROLLER_TRANSITION_OUT_DELAY;
				} else {
					start = time - passed;
					postDelayed(invalidateRunnable, FAST_SCROLLER_TRANSITION_IN - passed +
							FAST_SCROLLER_TRANSITION_OUT_DELAY);
				}
			} else {
				if (!newShow) {
					postDelayed(invalidateRunnable, FAST_SCROLLER_TRANSITION_OUT_DELAY);
				}
				start = newShow ? time : time - FAST_SCROLLER_TRANSITION_IN;
			}
			showFastScrollingStart = start;
			showFastScrolling = newShow;
			invalidateFastScroller();
		} else if (!isFastScrollerAvailable() && passed < FAST_SCROLLER_TRANSITION_OUT_DELAY) {
			removeCallbacks(invalidateRunnable);
			showFastScrollingStart = time - FAST_SCROLLER_TRANSITION_IN - FAST_SCROLLER_TRANSITION_OUT_DELAY;
			invalidateFastScroller();
		}
	}

	private float calculateOffset() {
		float result;
		int height = getHeight() - getEdgeEffectShift(EdgeEffectHandler.Side.TOP) -
				getEdgeEffectShift(EdgeEffectHandler.Side.BOTTOM);
		if (fastScrollingStartOffset != null) {
			result = fastScrollingStartOffset + (fastScrollingCurrentY - fastScrollingStartY) /
					Math.max(1, height - thumbDrawable.getIntrinsicHeight());
		} else {
			// A post mark denotes the top item on screen, so use the top edge of the thumb as the
			// common anchor for track taps, dragging and marks. Centering the thumb on a tap used a
			// different coordinate system and made the selected post miss its mark.
			result = fastScrollingCurrentY /
					Math.max(1, height - thumbDrawable.getIntrinsicHeight());
		}
		return Math.max(0, Math.min(result, 1));
	}

	private int getFastScrollerPositionRange(LinearLayoutManager layoutManager) {
		// Legacy navigation fallback for layouts that do not have linear viewport geometry.
		return Math.max(0, layoutManager.getItemCount() - 1);
	}

	FastScrollGeometry getFastScrollGeometry() {
		LayoutManager layoutManager = getLayoutManager();
		if (layoutManager instanceof LinearLayoutManager && !(layoutManager instanceof GridLayoutManager)) {
			LinearLayoutManager linearLayoutManager = (LinearLayoutManager) layoutManager;
			if (linearLayoutManager.getOrientation() != VERTICAL || linearLayoutManager.getReverseLayout()) return null;
			int count = linearLayoutManager.getItemCount();
			int first = linearLayoutManager.findFirstVisibleItemPosition();
			int last = linearLayoutManager.findLastVisibleItemPosition();
			if (count <= 0 || first < 0 || last < first || last >= count) return null;
			View firstView = linearLayoutManager.findViewByPosition(first);
			View lastView = linearLayoutManager.findViewByPosition(last);
			if (firstView == null || lastView == null) return null;
			Rect bounds = this.bounds;
			getDecoratedBoundsWithMargins(firstView, bounds);
			if (bounds.height() <= 0) return null;
			double start = first + Math.max(0, Math.min(1,
					(linearLayoutManager.getPaddingTop() - bounds.top) / (double) bounds.height()));
			getDecoratedBoundsWithMargins(lastView, bounds);
			if (bounds.height() <= 0) return null;
			double end = last + Math.max(0, Math.min(1,
					(getHeight() - linearLayoutManager.getPaddingBottom() - bounds.top) / (double) bounds.height()));
			return new FastScrollGeometry(count, start, end);
		}
		return null;
	}

	private float getCurrentOffset() {
		FastScrollGeometry geometry = getFastScrollGeometry();
		if (geometry != null) return geometry.offset();
		int offset = computeVerticalScrollOffset();
		int range = computeVerticalScrollRange() - computeVerticalScrollExtent();
		return Math.max(0, Math.min(range > 0 ? (float) offset / range : 0f, 1));
	}

	private void scroll(float offset) {
		if (!(getLayoutManager() instanceof LinearLayoutManager)) return;
		LinearLayoutManager layoutManager = (LinearLayoutManager) getLayoutManager();
		int count = layoutManager.getItemCount();
		pendingFastScrollPosition = NO_POSITION;
		pendingFastScrollAlignEnd = false;
		if (count > 0) {
			if (offset <= 0f) {
				layoutManager.scrollToPositionWithOffset(0, 0);
			} else if (offset < 1f) {
				FastScrollGeometry geometry = getFastScrollGeometry();
				if (geometry != null) {
					double coordinate = geometry.targetCoordinate(offset);
					int position = (int) coordinate;
					double fraction = coordinate - position;
					View target = layoutManager.findViewByPosition(position);
					int itemHeight = 0;
					if (target != null) {
						getDecoratedBoundsWithMargins(target, bounds);
						itemHeight = bounds.height();
					}
					if (fraction > 0 && itemHeight <= 0) {
						// Resolve only this target after layout, never bind/measure the whole adapter.
						pendingFastScrollPosition = position;
						pendingFastScrollFraction = fraction;
						pendingFastScrollItemCount = count;
					}
					layoutManager.scrollToPositionWithOffset(position, -(int) Math.round(fraction * itemHeight));
					return;
				}
				int position = (int) (getFastScrollerPositionRange(layoutManager) * offset + 0.5f);
				layoutManager.scrollToPositionWithOffset(position, 0);
			} else {
				if (getFastScrollGeometry() != null) {
					int position = count - 1;
					View target = layoutManager.findViewByPosition(position);
					if (target != null) {
						getDecoratedBoundsWithMargins(target, bounds);
						layoutManager.scrollToPositionWithOffset(position,
								getFastScrollViewportHeight(layoutManager) - bounds.height());
					} else {
						// Measuring only the last item also allows reaching the end of an oversized post.
						pendingFastScrollPosition = position;
						pendingFastScrollAlignEnd = true;
						pendingFastScrollItemCount = count;
						layoutManager.scrollToPositionWithOffset(position, 0);
					}
				} else {
					scrollToPosition(count - 1);
				}
			}
		}
	}

	private int getFastScrollViewportHeight(LinearLayoutManager layoutManager) {
		return Math.max(0, getHeight() - layoutManager.getPaddingTop() - layoutManager.getPaddingBottom());
	}

	private void applyPendingFastScrollFraction() {
		int position = pendingFastScrollPosition;
		pendingFastScrollPosition = NO_POSITION;
		if (position < 0 || !isFastScrollerAvailable() || !(getLayoutManager() instanceof LinearLayoutManager)) return;
		LinearLayoutManager layoutManager = (LinearLayoutManager) getLayoutManager();
		if (pendingFastScrollItemCount != layoutManager.getItemCount() || position >= layoutManager.getItemCount()) return;
		View target = layoutManager.findViewByPosition(position);
		if (target != null) {
			getDecoratedBoundsWithMargins(target, bounds);
			if (bounds.height() > 0) layoutManager.scrollToPositionWithOffset(position, pendingFastScrollAlignEnd
					? getFastScrollViewportHeight(layoutManager) - bounds.height()
					: -(int) Math.round(pendingFastScrollFraction * bounds.height()));
		}
	}

	private boolean handleTouchEvent(MotionEvent event) {
		int action = event.getActionMasked();
		int top = getEdgeEffectShift(EdgeEffectHandler.Side.TOP);
		float currentY = event.getY() - top;
		boolean fastScrollerAvailable = isFastScrollerAvailable();
		if (action == MotionEvent.ACTION_DOWN) {
			pendingFastScrollPosition = NO_POSITION;
			fastScrollingDown = false;
			if (!fastScrollerAvailable) {
				return false;
			}
			boolean rtl = getLayoutDirection() == View.LAYOUT_DIRECTION_RTL;
			int trackWidth = Math.max(minTrackSize, Math.max(thumbDrawable.getIntrinsicWidth(),
					trackDrawable.getIntrinsicWidth()));
			boolean atThumbVertical = rtl ? event.getX() <= trackWidth : event.getX() >= getWidth() - trackWidth;
			if (atThumbVertical) {
				int height = getHeight() - top - getEdgeEffectShift(EdgeEffectHandler.Side.BOTTOM);
				float offset = getCurrentOffset();
				int thumbHeight = thumbDrawable.getIntrinsicHeight();
				int thumbY = (int) ((height - thumbHeight) * offset);
				boolean atThumb = event.getY() >= top + thumbY && event.getY() <= top + thumbY + thumbHeight;
				fastScrollingDown = true;
				fastScrollingStartOffset = atThumb ? offset : null;
				fastScrollingStartY = currentY;
				if (!ViewUtils.isGestureNavigationOverlap(this, rtl, !rtl)) {
					fastScrollingCurrentY = currentY;
					getParent().requestDisallowInterceptTouchEvent(true);
					updateFastScroller(false, fastScrollerEnabled, fastScrollerAllowed, regularScrolling, true);
					if (!atThumb) {
						scroll(calculateOffset());
					}
					return true;
				}
			}
		} else if (fastScrollingDown) {
			boolean finish = action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL;
			if (!fastScrolling && fastScrollerAvailable) {
				boolean fastScrolling = false;
				if (finish) {
					fastScrolling = action != MotionEvent.ACTION_CANCEL;
				} else if (Math.abs(currentY - fastScrollingStartY) > touchSlop) {
					getParent().requestDisallowInterceptTouchEvent(true);
					fastScrolling = true;
				}
				if (fastScrolling) {
					updateFastScroller(false, fastScrollerEnabled, fastScrollerAllowed, regularScrolling, true);
					if (pullableWrapper != null) {
						pullableWrapper.onTouchEventOrNull(null);
					}
				}
			}
			if (fastScrolling) {
				boolean cancel = !fastScrollerAvailable || finish;
				if (fastScrollerAvailable && action != MotionEvent.ACTION_CANCEL) {
					fastScrollingCurrentY = currentY;
					scroll(calculateOffset());
				}
				if (action == MotionEvent.ACTION_CANCEL) pendingFastScrollPosition = NO_POSITION;
				if (cancel) {
					updateFastScroller(false, fastScrollerEnabled, fastScrollerAllowed, regularScrolling, false);
				}
				return true;
			}
		}
		return false;
	}

	private static final int[] STATE_PRESSED = {android.R.attr.state_enabled, android.R.attr.state_pressed};
	private static final int[] STATE_NORMAL = {android.R.attr.state_enabled};

	private void onDrawFastScroller(Canvas canvas) {
		// A disabled/unavailable scroller must not fade over the restored native scrollbar.
		if (!isFastScrollerAvailable()) {
			return;
		}
		long time = SystemClock.elapsedRealtime();
		long passed = time - showFastScrollingStart;
		boolean shouldInvalidate;
		float stateValue;
		if (!showFastScrolling && passed >= FAST_SCROLLER_TRANSITION_IN) {
			passed -= FAST_SCROLLER_TRANSITION_IN;
			shouldInvalidate = passed >= FAST_SCROLLER_TRANSITION_OUT_DELAY &&
					passed < FAST_SCROLLER_TRANSITION_OUT_DELAY + FAST_SCROLLER_TRANSITION_OUT;
			stateValue = 1f - (float) (passed - FAST_SCROLLER_TRANSITION_OUT_DELAY)
					/ FAST_SCROLLER_TRANSITION_OUT;
		} else {
			shouldInvalidate = true;
			stateValue = (float) passed / FAST_SCROLLER_TRANSITION_IN;
		}
		stateValue = Math.max(0, Math.min(stateValue, 1));

		if (stateValue > 0f) {
			boolean rtl = getLayoutDirection() == View.LAYOUT_DIRECTION_RTL;
			int maxWidth = Math.max(thumbDrawable.getIntrinsicWidth(), trackDrawable.getIntrinsicHeight());
			int translateX = (int) (maxWidth * (1f - stateValue) + 0.5f);
			int top = getEdgeEffectShift(EdgeEffectHandler.Side.TOP);
			int height = getHeight() - top - getEdgeEffectShift(EdgeEffectHandler.Side.BOTTOM);
			FastScrollGeometry geometry = getFastScrollGeometry();
			// Draw the laid-out position, not an optimistic finger position that snaps on release.
			float offset = geometry != null ? geometry.offset() : getCurrentOffset();
			int thumbHeight = thumbDrawable.getIntrinsicHeight();
			int thumbY = (int) ((height - thumbHeight) * offset);

			boolean thumbBitmap = thumbDrawable.getCurrent() instanceof BitmapDrawable;
			int trackTop = top + (thumbBitmap ? thumbHeight / 2 : 0);
			int trackBottom = top + height - (thumbBitmap ? thumbHeight / 2 : 0);
			trackDrawable.setState(fastScrolling ? STATE_PRESSED : STATE_NORMAL);
			int trackExtra = (maxWidth - trackDrawable.getIntrinsicWidth()) / 2;
			int trackLeft;
			int trackRight;
			if (rtl) {
				trackLeft = trackExtra - translateX;
				trackRight = trackExtra + trackDrawable.getIntrinsicWidth() - translateX;
			} else {
				trackLeft = getWidth() - trackExtra - trackDrawable.getIntrinsicWidth() + translateX;
				trackRight = getWidth() - trackExtra + translateX;
			}
			trackDrawable.setBounds(trackLeft, trackTop, trackRight, trackBottom);
			trackDrawable.draw(canvas);
			if (importantPostsMarksFastScrollBarDecoration != null &&
					importantPostsMarksFastScrollBarDecoration.hasMarks()) {
				importantPostsMarksFastScrollBarDecoration.draw(trackLeft, top, trackRight,
						top + height - thumbHeight, canvas, geometry);
			}
			int thumbExtra = (maxWidth - thumbDrawable.getIntrinsicWidth()) / 2;
			thumbDrawable.setState(fastScrolling ? STATE_PRESSED : STATE_NORMAL);
			if (rtl) {
				thumbDrawable.setBounds(thumbExtra - translateX, top + thumbY,
						thumbExtra + thumbDrawable.getIntrinsicWidth() - translateX, top + thumbY + thumbHeight);
			} else {
				thumbDrawable.setBounds(getWidth() - thumbExtra - thumbDrawable.getIntrinsicWidth() + translateX,
						top + thumbY, getWidth() - thumbExtra + translateX, top + thumbY + thumbHeight);
			}
			thumbDrawable.draw(canvas);
		}

		if (shouldInvalidate) {
			invalidateFastScroller();
		}
	}

	public PullableWrapper getPullable() {
		if (pullableWrapper == null) {
			PullableWrapper wrapper = new PullableWrapper(this);
			this.pullableWrapper = wrapper;
			updatePullIndicatorActivity();
			addOnItemTouchListener(new OnItemTouchListener() {
				private boolean intercepted = false;
				private float downY;

				@Override
				public boolean onInterceptTouchEvent(@NonNull RecyclerView rv, @NonNull MotionEvent e) {
					float y = e.getY();
					if (e.getActionMasked() == MotionEvent.ACTION_DOWN) {
						intercepted = false;
						downY = y;
					}
					if (wrapper.onTouchEventOrNull(e)) {
						if (!intercepted && Math.abs(downY - y) > touchSlop) {
							intercepted = true;
						}
						return intercepted;
					}
					return false;
				}

				@Override
				public void onTouchEvent(@NonNull RecyclerView rv, @NonNull MotionEvent e) {
					boolean result = wrapper.onTouchEventOrNull(e);
					if (intercepted && !result) {
						intercepted = false;
						// Reset intercepted state
						removeOnItemTouchListener(this);
						addOnItemTouchListener(this);
					}
				}

				@Override
				public void onRequestDisallowInterceptTouchEvent(boolean disallowIntercept) {
					if (disallowIntercept && intercepted) {
						intercepted = false;
						wrapper.onTouchEventOrNull(null);
					}
				}
			});
		}
		return pullableWrapper;
	}

	@Override
	public void draw(Canvas canvas) {
		if (pullableWrapper != null) {
			pullableWrapper.drawBefore(canvas);
			try {
				super.draw(canvas);
			} finally {
				pullableWrapper.drawAfter(canvas);
			}
		} else {
			super.draw(canvas);
		}
	}

	/** Presentation-only snapshot: do not move pull-to-refresh indicators with message content. */
	public void drawContentSnapshot(Canvas canvas) {
		super.draw(canvas);
	}

	private final Rect bounds = new Rect();

	@Override
	public boolean isScrolledToTop() {
		Rect bounds = this.bounds;
		View view = getChildCount() > 0 ? getChildAt(0) : null;
		if (view == null) {
			return true;
		} else if (getChildLayoutPosition(view) == 0) {
			getDecoratedBoundsWithMargins(view, bounds);
			return bounds.top >= getPaddingTop();
		} else {
			return false;
		}
	}

	@Override
	public boolean isScrolledToBottom() {
		Rect bounds = this.bounds;
		int childCount = getChildCount();
		View view = childCount > 0 ? getChildAt(childCount - 1) : null;
		if (view == null) {
			return true;
		} else if (getChildLayoutPosition(view) == getLayoutManager().getItemCount() - 1) {
			getDecoratedBoundsWithMargins(view, bounds);
			return bounds.bottom <= getHeight() - getPaddingBottom();
		} else {
			return false;
		}
	}
}
