package com.mishiranu.dashchan.widget;

import android.content.Context;
import android.graphics.Rect;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewTreeObserver;
import android.widget.Checkable;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import androidx.lifecycle.Lifecycle;
import androidx.lifecycle.LifecycleEventObserver;
import androidx.lifecycle.LifecycleOwner;
import androidx.core.view.OneShotPreDrawListener;
import com.mishiranu.dashchan.util.ResourceUtils;

/** Scrolls the form body while the native dialog keeps its title and actions visible. */
public final class DialogFormScrollView extends ScrollView {
	private final boolean bounded;
	private LinearLayout legacyBody;
	private ScrollView legacyList;
	private int legacyHeaderHeight, laidOutHeaderHeight;
	private boolean legacyWhole, laidOutWhole, hadLayout;
	private final Runnable reveal = this::revealFocusedField;
	public DialogFormScrollView(Context context) { this(context, true); }
	private DialogFormScrollView(Context context, boolean bounded) { super(context); this.bounded = bounded; }

	/** Stable hierarchy: in small legacy windows the outer viewport takes over from the list. */
	public static DialogFormScrollView create(Context context, LinearLayout fields, View choices, boolean modern) {
		DialogFormScrollView form = new DialogFormScrollView(context, modern);
		if (modern) fields.addView(choices, ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
		else {
			form.legacyBody = fields;
			form.legacyList = new ScrollView(context);
			form.legacyList.addView(choices, ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
			fields.addView(form.legacyList, ViewGroup.LayoutParams.MATCH_PARENT, form.limit());
		}
		form.addView(fields, ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
		return form;
	}

	/** The native custom slot can be EXACTLY; its inner WRAP_CONTENT viewport still gets a cap. */
	public View dialogContent() {
		FrameLayout slot = new FrameLayout(getContext());
		slot.addView(this, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
		return slot;
	}
	private int limit() { return Math.round(AppearanceShapes.DIALOG_FORM_MAX_HEIGHT * ResourceUtils.obtainDensity(this)); }
	// All containers in create() span their parent's content width.
	private int minimumListHeight(int widthSpec, int horizontalInsets) {
		View content = legacyList.getChildAt(0);
		horizontalInsets += legacyList.getPaddingLeft() + legacyList.getPaddingRight();
		int verticalInsets = legacyList.getPaddingTop() + legacyList.getPaddingBottom();
		if (!(content instanceof LinearLayout)) {
			return verticalInsets + measureRowHeight(content, widthSpec, horizontalInsets);
		}
		LinearLayout rows = (LinearLayout) content;
		horizontalInsets += rows.getPaddingLeft() + rows.getPaddingRight();
		verticalInsets += rows.getPaddingTop() + rows.getPaddingBottom();
		int firstHeight = 0;
		for (int i = 0; i < rows.getChildCount(); i++) {
			View row = rows.getChildAt(i);
			if (row.getVisibility() != VISIBLE) continue;
			// Group headings can be shorter than the first selectable board at large font sizes.
			if (row instanceof Checkable) {
				return verticalInsets + Math.max(firstHeight, measureRowHeight(row, widthSpec, horizontalInsets));
			}
			if (firstHeight == 0) firstHeight = measureRowHeight(row, widthSpec, horizontalInsets);
		}
		return verticalInsets + firstHeight;
	}

	private static int measureRowHeight(View row, int widthSpec, int horizontalInsets) {
		ViewGroup.MarginLayoutParams params = (ViewGroup.MarginLayoutParams) row.getLayoutParams();
		int rowWidth = getChildMeasureSpec(widthSpec, horizontalInsets + params.leftMargin + params.rightMargin, params.width);
		int rowHeight = params.height >= 0 ? MeasureSpec.makeMeasureSpec(params.height, MeasureSpec.EXACTLY)
				: MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED);
		row.measure(rowWidth, rowHeight);
		return row.getMeasuredHeight() + params.topMargin + params.bottomMargin;
	}

	@Override protected void onMeasure(int widthSpec, int heightSpec) {
		int mode = MeasureSpec.getMode(heightSpec), size = MeasureSpec.getSize(heightSpec);
		if (legacyList != null) {
			int headers = legacyBody.getPaddingTop() + legacyBody.getPaddingBottom();
			int horizontalInsets = getPaddingLeft() + getPaddingRight()
					+ legacyBody.getPaddingLeft() + legacyBody.getPaddingRight();
			for (int i = 0; i < legacyBody.getChildCount(); i++) {
				View child = legacyBody.getChildAt(i); if (child == legacyList || child.getVisibility() == GONE) continue;
				headers += measureRowHeight(child, widthSpec, horizontalInsets);
			}
			int available = mode == MeasureSpec.UNSPECIFIED ? Integer.MAX_VALUE : Math.max(0, size - getPaddingTop() - getPaddingBottom());
			int listHeight = Math.min(limit(), Math.max(0, available - headers));
			boolean wholeForm = headers >= available || listHeight < minimumListHeight(widthSpec, horizontalInsets);
			legacyHeaderHeight = headers; legacyWhole = wholeForm;
			// No reparenting, selection/focus reset, or second active scrolling range.
			int targetHeight = wholeForm ? ViewGroup.LayoutParams.WRAP_CONTENT : listHeight;
			ViewGroup.LayoutParams params = legacyList.getLayoutParams();
			if (params.height != targetHeight) {
				params.height = targetHeight;
				// Remeasure in the current pass even if the body's width and measure specs are cached.
				legacyList.forceLayout();
				legacyBody.forceLayout();
			}
			legacyList.setVerticalScrollBarEnabled(!wholeForm);
			setVerticalScrollBarEnabled(wholeForm);
		}
		if (bounded && mode != MeasureSpec.EXACTLY) {
			int height = mode == MeasureSpec.UNSPECIFIED ? limit() : Math.min(limit(), size);
			heightSpec = MeasureSpec.makeMeasureSpec(Math.max(0, height), MeasureSpec.AT_MOST);
		}
		super.onMeasure(widthSpec, heightSpec);
	}
	@Override protected void onLayout(boolean changed, int l, int t, int r, int b) {
		int oldOuter = getScrollY(), oldList = legacyList != null ? legacyList.getScrollY() : 0;
		int listOffset = laidOutWhole ? Math.max(0, oldOuter - laidOutHeaderHeight) : oldList;
		super.onLayout(changed, l, t, r, b);
		if (legacyList != null && hadLayout && legacyWhole != laidOutWhole) {
			if (legacyWhole) scrollTo(0, listOffset > 0 ? legacyHeaderHeight + listOffset : 0);
			else { scrollTo(0, 0); legacyList.scrollTo(0, listOffset); }
		}
		hadLayout = true; laidOutWhole = legacyWhole; laidOutHeaderHeight = legacyHeaderHeight;
	}
	@Override protected void onSizeChanged(int w, int h, int oldw, int oldh) {
		super.onSizeChanged(w, h, oldw, oldh);
		if (w != oldw || h != oldh) {
			removeCallbacks(reveal);
			post(reveal);
		}
	}
	private void revealFocusedField() {
		View field = findFocus(); if (!(field instanceof EditText) || getHeight() <= 0) return;
		Rect rect = new Rect(); field.getDrawingRect(rect); offsetDescendantRectToMyCoords(field, rect);
		if (rect.top < getScrollY() + getPaddingTop() || rect.bottom > getScrollY() + getHeight() - getPaddingBottom()) {
			field.getDrawingRect(rect); field.requestRectangleOnScreen(rect, true);
		}
	}
	@Override protected void onDetachedFromWindow() { removeCallbacks(reveal); super.onDetachedFromWindow(); }

	/** Values only. Safe to retain in an InstanceDialog factory across configuration changes. */
	public static final class State {
		int focused = -1, start = -1, end = -1, scroll, listScroll;
		boolean whole;
	}
	public void retain(State state, LifecycleOwner owner, EditText... fields) {
		new RetainedInput(this, state, owner, fields);
	}
	private static final class RetainedInput implements View.OnAttachStateChangeListener,
			ViewTreeObserver.OnGlobalFocusChangeListener, LifecycleEventObserver {
		final DialogFormScrollView form;
		final State state;
		final LifecycleOwner owner;
		final EditText[] fields;
		ViewTreeObserver tree;
		boolean restoring, savedAtStop;
		OneShotPreDrawListener restoreDraw;
		RetainedInput(DialogFormScrollView form, State state, LifecycleOwner owner, EditText[] fields) {
			this.form = form; this.state = state; this.owner = owner; this.fields = fields;
			form.addOnAttachStateChangeListener(this);
		}
		@Override public void onViewAttachedToWindow(View view) {
			restoring = true;
			savedAtStop = false;
			owner.getLifecycle().addObserver(this);
			tree = form.getViewTreeObserver(); tree.addOnGlobalFocusChangeListener(this);
			restoreDraw = OneShotPreDrawListener.add(form, () -> { restoreDraw = null; restore(); });
		}
		void save() {
			if (restoring) return;
			state.focused = -1;
			for (int i = 0; i < fields.length; i++) if (fields[i].hasFocus()) state.focused = i;
			if (state.focused >= 0 && state.focused < fields.length) {
				state.start = fields[state.focused].getSelectionStart(); state.end = fields[state.focused].getSelectionEnd();
			}
			state.scroll = form.getScrollY();
			state.whole = form.legacyWhole;
			state.listScroll = 0;
			if (form.legacyList != null) state.listScroll = form.legacyWhole
					? Math.max(0, state.scroll - form.legacyHeaderHeight) : form.legacyList.getScrollY();
		}
		void restore() {
			if (!form.isAttachedToWindow()) return;
			if (state.focused >= 0 && state.focused < fields.length) {
				EditText field = fields[state.focused]; field.requestFocus();
				if (state.start >= 0 && state.end >= 0) field.setSelection(Math.min(state.start, field.length()), Math.min(state.end, field.length()));
			}
			if (form.legacyList != null) {
				int outer = 0;
				if (form.legacyWhole) {
					if (state.whole) outer = state.scroll;
					else if (state.listScroll > 0) outer = form.legacyHeaderHeight + state.listScroll;
				}
				form.scrollTo(0, outer);
				if (!form.legacyWhole) form.legacyList.scrollTo(0, state.listScroll);
			} else form.scrollTo(0, state.scroll);
			restoring = false; form.revealFocusedField();
		}
		@Override public void onGlobalFocusChanged(View old, View next) {
			if (!restoring) for (int i = 0; i < fields.length; i++) if (fields[i] == next) state.focused = i;
		}
		@Override public void onStateChanged(LifecycleOwner source, Lifecycle.Event event) {
			if (event == Lifecycle.Event.ON_STOP) { save(); savedAtStop = true; }
			else if (event == Lifecycle.Event.ON_START) savedAtStop = false;
		}
		@Override public void onViewDetachedFromWindow(View view) {
			if (!savedAtStop) save();
			if (restoreDraw != null) { restoreDraw.removeListener(); restoreDraw = null; }
			owner.getLifecycle().removeObserver(this);
			if (tree != null && tree.isAlive()) tree.removeOnGlobalFocusChangeListener(this);
			tree = null;
		}
	}
}
