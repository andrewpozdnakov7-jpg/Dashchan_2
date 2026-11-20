package com.mishiranu.dashchan.widget;

import android.annotation.SuppressLint;
import android.content.Context;
import android.util.AttributeSet;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewParent;
import android.view.ViewConfiguration;
import androidx.core.view.GravityCompat;
import androidx.drawerlayout.widget.DrawerLayout;
import com.mishiranu.dashchan.util.ResourceUtils;
import com.mishiranu.dashchan.util.ViewUtils;
import com.mishiranu.dashchan.util.InputGestureDiagnostics;

public class CustomDrawerLayout extends DrawerLayout {
	// Match the default edge width of the pinned AndroidX ViewDragHelper.
	private static final int DRAWER_EDGE_SIZE_DP = 20;
	private final int touchSlop;
	private final int edgeSize;

	private boolean expandableFromAnyPoint = true;

	private float startX;
	private float startY;
	private boolean handle;
	private boolean ignoreTouch;
	private boolean editorGestureOwned;
	private boolean closedAtDown;
	private long gestureDownTime;
	private final InputGestureDiagnostics.Trace gestureTrace = new InputGestureDiagnostics.Trace();
	boolean isEditorGestureOwned() { return editorGestureOwned; }

	/** Keep gestures that start in an editor available for cursor movement and selection. */
	static void protectEditorGesture(View editor, MotionEvent event) {
		if (!editor.isEnabled() || event.getActionMasked() != MotionEvent.ACTION_DOWN) return;
		ViewParent parent = editor.getParent();
		while (parent != null) {
			if (parent instanceof CustomDrawerLayout) {
				CustomDrawerLayout drawer = (CustomDrawerLayout) parent;
				if (drawer.closedAtDown && drawer.gestureDownTime == event.getDownTime()) {
					drawer.editorGestureOwned = true;
					drawer.handle = false;
					drawer.ignoreTouch = false;
					// Native DrawerLayout also cancels pending edge-peek callbacks here.
					drawer.requestDisallowInterceptTouchEvent(true);
					if (InputGestureDiagnostics.isRecording()) InputGestureDiagnostics.event(drawer, "drawer_editor_claim",
							"gesture=" + event.getDownTime() + " owner="
							+ com.mishiranu.dashchan.ui.posting.PostingFormDiagnostics.viewLabel(editor));
				}
				return;
			}
			parent = parent.getParent();
		}
	}

	private String gestureState() {
		return "editorOwned=" + editorGestureOwned + " anywhere=" + expandableFromAnyPoint
				+ " handle=" + handle + " ignore=" + ignoreTouch
				+ " visible=" + isDrawerVisible(GravityCompat.START) + "," + isDrawerVisible(GravityCompat.END)
				+ " locks=" + getDrawerLockMode(GravityCompat.START) + "," + getDrawerLockMode(GravityCompat.END);
	}

	private void releaseEditorGesture(String reason) {
		if (editorGestureOwned) {
			if (InputGestureDiagnostics.isRecording()) InputGestureDiagnostics.event(this, "drawer_editor_release",
					"gesture=" + gestureDownTime + " reason=" + reason);
			editorGestureOwned = false;
			super.requestDisallowInterceptTouchEvent(false);
		}
	}

	@Override public boolean dispatchTouchEvent(MotionEvent event) {
		int action = event.getActionMasked();
		if (action == MotionEvent.ACTION_DOWN) {
			gestureTrace.begin(event);
			releaseEditorGesture("next_down");
			gestureDownTime = event.getDownTime();
			closedAtDown = !isDrawerVisible(GravityCompat.START) && !isDrawerVisible(GravityCompat.END);
		}
		try {
			boolean handled = super.dispatchTouchEvent(event);
			if (InputGestureDiagnostics.isRecording()) gestureTrace.motion(this, "drawer_dispatch", event,
					"handled=" + handled + " " + gestureState());
			return handled;
		} finally {
			if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) {
				releaseEditorGesture(action == MotionEvent.ACTION_UP ? "up" : "cancel");
				closedAtDown = false;
			}
		}
	}

	@Override public void requestDisallowInterceptTouchEvent(boolean disallow) {
		boolean effective = disallow || editorGestureOwned;
		if (gestureTrace != null && InputGestureDiagnostics.isRecording()) gestureTrace.state(this, "drawer_intercept_request",
				"gesture=" + gestureDownTime + " requested=" + disallow + " effective=" + effective + " " + gestureState());
		super.requestDisallowInterceptTouchEvent(effective);
	}

	@Override public void onWindowFocusChanged(boolean focused) {
		super.onWindowFocusChanged(focused);
		if (!focused) {
			releaseEditorGesture("window_focus_lost"); closedAtDown = false; handle = false;
		}
	}
	@Override protected void onDetachedFromWindow() {
		releaseEditorGesture("detach"); closedAtDown = false; handle = false;
		super.onDetachedFromWindow();
	}

	public CustomDrawerLayout(Context context, AttributeSet attrs) {
		super(context, attrs);

		float density = ResourceUtils.obtainDensity(context);
		touchSlop = ViewConfiguration.get(context).getScaledTouchSlop();
		edgeSize = (int) (DRAWER_EDGE_SIZE_DP * density);
	}

	public void setExpandableFromAnyPoint(boolean expandableFromAnyPoint) {
		this.expandableFromAnyPoint = expandableFromAnyPoint;
		if (InputGestureDiagnostics.isRecording()) gestureTrace.state(this, "drawer_anywhere_policy", gestureState());
	}

	/** Keep predictive pixels in window coordinates while the native content avoids the IME. */
	public boolean setImeViewportInset(int bottom) {
		if (getChildCount() == 0 || !(getChildAt(0) instanceof PredictiveBackLayout)) return false;
		PredictiveBackLayout host = (PredictiveBackLayout) getChildAt(0);
		bottom = Math.max(0, bottom);
		// Both the page/wide-drawer row and toolbar/interlayer retain their former viewport.
		for (int i = 0; i < host.getChildCount(); i++) {
			ViewUtils.setNewMargin(host.getChildAt(i), null, null, null, bottom);
		}
		// The sliding drawer's search field must also stay above the keyboard.
		for (int i = 1; i < getChildCount(); i++) {
			ViewUtils.setNewMargin(getChildAt(i), null, null, null, bottom);
		}
		return true;
	}

	@Override
	public boolean onInterceptTouchEvent(MotionEvent ev) {
		int action = ev.getActionMasked();
		if (action == MotionEvent.ACTION_DOWN) {
			startX = ev.getX();
			startY = ev.getY();
			handle = !isDrawerOpen(GravityCompat.START) && !isDrawerOpen(GravityCompat.END);
			ignoreTouch = false;
		} else if (action == MotionEvent.ACTION_MOVE && handle) {
			float dx = ev.getX() - startX;
			float dy = ev.getY() - startY;
			if (Math.abs(dx) > touchSlop) {
				handle = false;
				if (expandableFromAnyPoint && Math.abs(dx) > Math.abs(dy) &&
						getDrawerLockMode(GravityCompat.START) == LOCK_MODE_UNLOCKED) {
					boolean rtl = getLayoutDirection() == View.LAYOUT_DIRECTION_RTL;
					boolean left = !rtl && dx > 0 && startX >= edgeSize;
					boolean right = rtl && dx < 0 && startX <= getWidth() - edgeSize;
					if (left || right) {
						if (InputGestureDiagnostics.isRecording()) InputGestureDiagnostics.event(this, "drawer_anywhere_start",
								"gesture=" + ev.getDownTime() + " dx=" + dx + " dy=" + dy + " " + gestureState());
						startX = ev.getX();
						startY = ev.getY();
						ignoreTouch = true;
						// ViewDragHelper tracks pointer IDs
						MotionEvent.PointerProperties[] properties = {new MotionEvent.PointerProperties()};
						ev.getPointerProperties(0, properties[0]);
						MotionEvent.PointerCoords[] coordinates = {new MotionEvent.PointerCoords()};
						coordinates[0].x = right ? getWidth() : 0;
						coordinates[0].y = ev.getY();
						// Zero event time to disallow initial high velocity
						MotionEvent fakeDown = MotionEvent.obtain(0, 0, MotionEvent.ACTION_DOWN,
								1, properties, coordinates, 0, 0, 0, 0, 0, 0, 0, 0);
						try {
							super.onInterceptTouchEvent(fakeDown);
						} finally {
							fakeDown.recycle();
						}
					}
				}
			}
		}
		boolean result = super.onInterceptTouchEvent(ev);
		if (InputGestureDiagnostics.isRecording()) gestureTrace.state(this, "drawer_intercept_result",
				"gesture=" + ev.getDownTime() + " action=" + ev.getActionMasked() + " result=" + result + " " + gestureState());
		if (result && handle) {
			handle = false;
		}
		return result;
	}

	@SuppressLint("ClickableViewAccessibility")
	@Override
	public boolean onTouchEvent(MotionEvent ev) {
		int action = ev.getActionMasked();
		if (ignoreTouch && Math.abs(ev.getX() - startX) > touchSlop) {
			ignoreTouch = false;
		}
		if (action == MotionEvent.ACTION_UP && ignoreTouch) {
			MotionEvent fakeCancel = MotionEvent.obtain(ev.getDownTime(), ev.getEventTime(),
					MotionEvent.ACTION_CANCEL, ev.getX(), ev.getY(), ev.getMetaState());
			try {
				return super.onTouchEvent(fakeCancel);
			} finally {
				fakeCancel.recycle();
			}
		}
		return super.onTouchEvent(ev);
	}
}
