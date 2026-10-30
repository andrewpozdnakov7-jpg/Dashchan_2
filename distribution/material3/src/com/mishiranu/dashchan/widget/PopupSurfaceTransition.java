package com.mishiranu.dashchan.widget;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.AnimatorSet;
import android.animation.ObjectAnimator;
import android.content.Context;
import android.graphics.Rect;
import android.transition.TransitionValues;
import android.transition.Visibility;
import android.util.AttributeSet;
import android.view.View;
import android.view.ViewGroup;
import com.mishiranu.dashchan.R;
import com.mishiranu.dashchan.util.InterfaceMotion;

/** The platform supplies the anchor epicenter and owns visibility/removal. */
public final class PopupSurfaceTransition extends Visibility {
	public PopupSurfaceTransition(Context context, AttributeSet attrs) { super(context, attrs); }
	@Override public Animator onAppear(ViewGroup root, View view, TransitionValues start, TransitionValues end) {
		return animate(view, true);
	}
	@Override public Animator onDisappear(ViewGroup root, View view, TransitionValues start, TransitionValues end) {
		return animate(view, false);
	}
	private Animator animate(View view, boolean enter) {
		if (!InterfaceMotion.isEnabled() || InterfaceMotion.duration(1) == 0 ||
				view.getTag(R.id.popup_motion_owner) != null) return null;
		Frame frame = new Frame(view);
		Rect epicenter = getEpicenter();
		if (epicenter != null) {
			// PopupWindow supplies a window-relative epicenter, not screen coordinates.
			int[] location = new int[2]; view.getLocationInWindow(location);
			view.setPivotX(Math.max(0f, Math.min(view.getWidth(), epicenter.exactCenterX() - location[0])));
			view.setPivotY(Math.max(0f, Math.min(view.getHeight(), epicenter.exactCenterY() - location[1])));
		}
		AnimatorSet set = new AnimatorSet();
		set.playTogether(ObjectAnimator.ofFloat(view, View.ALPHA, enter ? 0f : frame.alpha, enter ? frame.alpha : 0f),
				ObjectAnimator.ofFloat(view, View.SCALE_X, frame.x * (enter ? .94f : 1f), frame.x * (enter ? 1f : .97f)),
				ObjectAnimator.ofFloat(view, View.SCALE_Y, frame.y * (enter ? .94f : 1f), frame.y * (enter ? 1f : .97f)));
		set.setInterpolator(InterfaceMotion.STANDARD);
		set.setDuration(InterfaceMotion.duration((int) (getDuration() >= 0 ? getDuration() : enter ? 220 : 160)));
		view.setTag(R.id.popup_motion_owner, frame);
		if (enter) { view.setAlpha(0f); view.setScaleX(frame.x * .94f); view.setScaleY(frame.y * .94f); }
		set.addListener(new AnimatorListenerAdapter() {
			@Override public void onAnimationCancel(Animator animation) { frame.release(); }
			@Override public void onAnimationEnd(Animator animation) { frame.release(); }
		});
		return set;
	}
	static final class Frame {
		final View view; final float alpha, x, y, pivotX, pivotY; final boolean pivotSet;
		Frame(View view) {
			this.view = view; alpha = view.getAlpha(); x = view.getScaleX(); y = view.getScaleY();
			pivotX = view.getPivotX(); pivotY = view.getPivotY(); pivotSet = view.isPivotSet();
		}
		void release() {
			if (view.getTag(R.id.popup_motion_owner) != this) return;
			view.setTag(R.id.popup_motion_owner, null); view.setAlpha(alpha);
			view.setScaleX(x); view.setScaleY(y);
			if (pivotSet) { view.setPivotX(pivotX); view.setPivotY(pivotY); } else view.resetPivot();
		}
	}
}
