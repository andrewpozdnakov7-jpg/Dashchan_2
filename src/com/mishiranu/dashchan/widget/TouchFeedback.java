package com.mishiranu.dashchan.widget;

import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.DrawableWrapper;
import android.graphics.drawable.LayerDrawable;
import android.graphics.drawable.RippleDrawable;
import android.graphics.drawable.StateListDrawable;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AbsListView;
import com.mishiranu.dashchan.content.Preferences;
import com.mishiranu.dashchan.util.ResourceUtils;
import java.util.WeakHashMap;

/** Adjusts only touch ink, never the opacity of a view or its content/background layers. */
public final class TouchFeedback {
	private TouchFeedback() {}

	// UI-thread only. Weak keys neither retain views nor accumulate with recycled rows.
	private static final WeakHashMap<RippleDrawable, Integer> APPLIED = new WeakHashMap<>();

	public static void apply(View view) {
		int percent = Preferences.getTouchFeedbackIntensity();
		if (percent == 100) return; // Preserve the platform/OEM drawable completely in normal mode.
		int color = ResourceUtils.getColor(view.getContext(), android.R.attr.colorControlHighlight);
		apply(view.getBackground(), color, percent);
		apply(view.getForeground(), color, percent);
		if (view instanceof AbsListView) {
			apply(((AbsListView) view).getSelector(), color, percent);
		}
	}

	public static void applyTree(View view) {
		if (Preferences.getTouchFeedbackIntensity() == 100) return;
		apply(view);
		if (view instanceof ViewGroup) {
			ViewGroup group = (ViewGroup) view;
			for (int i = 0; i < group.getChildCount(); i++) applyTree(group.getChildAt(i));
		}
	}

	private static void apply(Drawable drawable, int normalColor, int percent) {
		if (drawable instanceof RippleDrawable) {
			RippleDrawable ripple = (RippleDrawable) drawable;
			int touchColor = (normalColor & 0x00ffffff)
					| (Math.round(Color.alpha(normalColor) * percent / 100f) << 24);
			Integer previous = APPLIED.get(ripple);
			if (previous != null && previous == touchColor) return;
			ripple.mutate();
			// Keep keyboard/mouse focus visible. The default also uses touchColor so the
			// release animation cannot briefly flash at full intensity after ACTION_UP.
			ripple.setColor(new ColorStateList(new int[][] {
					{android.R.attr.state_pressed}, {android.R.attr.state_focused},
					{android.R.attr.state_hovered}, {}},
					new int[] {touchColor, normalColor, normalColor, touchColor}));
			APPLIED.put(ripple, touchColor);
		} else if (drawable instanceof LayerDrawable) {
			drawable.mutate();
			LayerDrawable layers = (LayerDrawable) drawable;
			for (int i = 0; i < layers.getNumberOfLayers(); i++) apply(layers.getDrawable(i), normalColor, percent);
		} else if (drawable instanceof DrawableWrapper) {
			drawable.mutate();
			apply(((DrawableWrapper) drawable).getDrawable(), normalColor, percent);
		} else if (drawable instanceof StateListDrawable) {
			drawable.mutate();
			StateListDrawable states = (StateListDrawable) drawable;
			for (int i = 0; i < states.getStateCount(); i++) apply(states.getStateDrawable(i), normalColor, percent);
		}
	}
}
