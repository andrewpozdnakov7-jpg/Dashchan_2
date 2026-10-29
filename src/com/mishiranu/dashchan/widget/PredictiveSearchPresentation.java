package com.mishiranu.dashchan.widget;

import android.graphics.Canvas;
import android.graphics.Rect;
import android.view.View;
import android.view.ViewGroup;
import com.mishiranu.dashchan.graphics.BaseDrawable;
import java.util.function.BooleanSupplier;

/** Render the real normal title over the search; no query/focus/menu mutation during tracking. */
public final class PredictiveSearchPresentation implements PredictiveTransition.Presentation {
	private final ViewGroup toolbar;
	private final View search, normalTitle;
	private final BooleanSupplier ownerValid;
	private final float alpha, x;
	private final Rect clip;
	private final int accessibility, width, height;
	private float fraction;
	private final BaseDrawable titleLayer = new BaseDrawable() {
		@Override public void draw(Canvas canvas) {
			if (fraction == 0f) return;
			int save = canvas.saveLayerAlpha(0, 0, toolbar.getWidth(), toolbar.getHeight(), Math.round(255 * fraction));
			float inset = BackMotionSpec.TOOLBAR_TITLE_INSET_DP * toolbar.getResources().getDisplayMetrics().density;
			float left = toolbar.getLayoutDirection() == View.LAYOUT_DIRECTION_RTL
					? toolbar.getWidth() - inset - normalTitle.getWidth() : inset;
			canvas.translate(left, (toolbar.getHeight() - normalTitle.getHeight()) / 2f);
			normalTitle.draw(canvas); canvas.restoreToCount(save);
		}
	};
	public PredictiveSearchPresentation(ViewGroup toolbar, View normalTitle, View search, BooleanSupplier ownerValid) {
		this.toolbar = toolbar; this.normalTitle = normalTitle; this.search = search; this.ownerValid = ownerValid;
		alpha = search.getAlpha(); x = search.getTranslationX(); clip = search.getClipBounds();
		accessibility = search.getImportantForAccessibility(); width = toolbar.getWidth(); height = toolbar.getHeight();
		titleLayer.setBounds(0, 0, width, height); toolbar.getOverlay().add(titleLayer);
		// The Drawable overlay has no accessibility nodes. Keep the live search's focus intact.
	}
	@Override public boolean valid() {
		return ownerValid.getAsBoolean() && toolbar.isAttachedToWindow() && search.isAttachedToWindow()
				&& toolbar.getWidth() == width && toolbar.getHeight() == height;
	}
	@Override public void apply(float value) {
		fraction = value;
		search.setAlpha(alpha * (1f - value));
		float distance = BackMotionSpec.SEARCH_DISMISS_DP * toolbar.getResources().getDisplayMetrics().density;
		search.setTranslationX(x + (toolbar.getLayoutDirection() == View.LAYOUT_DIRECTION_RTL ? distance : -distance) * value);
		titleLayer.invalidateSelf();
	}
	@Override public void restore() {
		toolbar.getOverlay().remove(titleLayer); search.setAlpha(alpha); search.setTranslationX(x);
		search.setClipBounds(clip); search.setImportantForAccessibility(accessibility);
	}
}
