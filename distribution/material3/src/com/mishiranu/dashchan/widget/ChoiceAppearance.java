package com.mishiranu.dashchan.widget;

import android.content.Context;
import android.content.res.ColorStateList;
import android.content.res.TypedArray;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.DrawableWrapper;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.InsetDrawable;
import android.graphics.drawable.LayerDrawable;
import android.view.View;
import android.widget.CheckedTextView;
import android.widget.CheckBox;
import android.widget.CompoundButton;
import android.widget.ListView;
import android.widget.RadioButton;
import android.widget.TextView;
import androidx.core.graphics.ColorUtils;
import com.mishiranu.dashchan.R;
import com.mishiranu.dashchan.util.OpaqueColors;

/** One owner for selection glyphs; labels, geometry, clicks and checked state remain native. */
final class ChoiceAppearance {
	private ChoiceAppearance() {}
	static int[] colors(AppearanceColors c, int surface) {
		surface = OpaqueColors.over(surface, c.window);
		int selected = InterfaceAppearance.contrasting(c.primary, surface, 3);
		return new int[] {InterfaceAppearance.contrasting(c.outline, surface, 3), selected,
				InterfaceAppearance.contrasting(c.onPrimary, selected, 3), surface, c.ripple};
	}
	static ColorStateList tint(int[] colors) {
		return new ColorStateList(new int[][] {{-android.R.attr.state_enabled}, {android.R.attr.state_checked}, {}},
				new int[] {OpaqueColors.blend(colors[3], colors[0], .38f), colors[1], colors[0]});
	}
	private static int surface(Context context, AppearanceColors colors) {
		TypedArray attrs = context.obtainStyledAttributes(new int[] {android.R.attr.windowIsFloating, R.attr.isPopup});
		try {
			if (attrs.getBoolean(0, false) || attrs.getBoolean(1, false)) {
				return InterfaceAppearance.isEnabled(context) ? colors.surfaceContainer
						: OpaqueColors.over(ThemeEngine.getTheme(context).card, colors.window);
			}
			return colors.surface;
		}
		finally { attrs.recycle(); }
	}
	private static Integer solid(Drawable background, int[] state) {
		if (background == null) return null;
		if (background instanceof ColorDrawable) {
			return ((ColorDrawable) background).getColor();
		}
		if (background instanceof GradientDrawable) {
			ColorStateList fill = ((GradientDrawable) background).getColor();
			if (fill == null) return null;
			int color = fill.getColorForState(state, fill.getDefaultColor());
			return ColorUtils.setAlphaComponent(color, Color.alpha(color) * background.getAlpha() / 255);
		}
		if (background instanceof LayerDrawable) {
			LayerDrawable layers = (LayerDrawable) background;
			Integer result = null;
			for (int i = 0; i < layers.getNumberOfLayers(); i++) {
				if (layers.getId(i) == android.R.id.mask) continue;
				Integer color = solid(layers.getDrawable(i), state);
				if (color != null) result = result != null ? ColorUtils.compositeColors(color, result) : color;
			}
			return result;
		} else if (background instanceof DrawableWrapper) return solid(((DrawableWrapper) background).getDrawable(), state);
		else if (background.getCurrent() != background) return solid(background.getCurrent(), state);
		return null;
	}
	static int[] colors(View view, AppearanceColors palette, Integer override) {
		if (override != null) return colors(palette, override);
		int foreground = Color.TRANSPARENT;
		for (View current = view; current != null; current = current.getParent() instanceof View ? (View) current.getParent() : null) {
			Integer background = null;
			ColorStateList tint = current.getBackgroundTintList();
			if (current.getBackground() != null && tint != null) {
				background = tint.getColorForState(current.getDrawableState(), tint.getDefaultColor());
			} else if (current instanceof androidx.cardview.widget.CardView) {
				ColorStateList card = ((androidx.cardview.widget.CardView) current).getCardBackgroundColor();
				background = card.getColorForState(current.getDrawableState(), card.getDefaultColor());
			} else background = solid(current.getBackground(), current.getDrawableState());
			if (background != null) foreground = ColorUtils.compositeColors(foreground, background);
			if (Color.alpha(foreground) == 255) return colors(palette, foreground);
		}
		return colors(palette, OpaqueColors.over(foreground, surface(view.getContext(), palette)));
	}
	private static boolean eligible(View view) {
		return !(view instanceof ScopeCheckBox) && (view instanceof CheckBox || view instanceof RadioButton || view instanceof CheckedTextView);
	}
	static void apply(View view) {
		if (!eligible(view)) return;
		if (ThemeEngine.peekTheme(view.getContext()) == null) return;
		AppearanceColors palette = StandardAppearance.colors(view.getContext());
		Binding binding = binding((TextView) view);
		binding.apply(colors(view, palette, binding.surface));
	}
	static void apply(View view, Boolean exclusive, int[] colors) {
		if (!eligible(view)) return;
		Binding binding = binding((TextView) view);
		if (exclusive != null) binding.exclusive = exclusive;
		binding.surface = colors[3];
		binding.apply(colors);
	}
	/** Layout owners may retain their existing text gap without drawing a different glyph. */
	static void endInset(CompoundButton view, int inset) {
		Binding binding = binding(view); binding.endInset = Math.max(0, inset); apply(view);
	}
	private static Binding binding(TextView view) {
		Object tag = view.getTag(R.id.choice_indicator_appearance);
		return tag instanceof Binding ? (Binding) tag : new Binding(view);
	}
	private static Boolean listExclusive(CheckedTextView view) {
		for (android.view.ViewParent parent = view.getParent(); parent != null; parent = parent.getParent()) {
			if (parent instanceof ListView) {
				int mode = ((ListView) parent).getChoiceMode();
				if (mode == ListView.CHOICE_MODE_SINGLE) return true;
				if (mode == ListView.CHOICE_MODE_MULTIPLE || mode == ListView.CHOICE_MODE_MULTIPLE_MODAL) return false;
			}
		}
		return null;
	}
	private static final class Binding implements View.OnAttachStateChangeListener, View.OnLayoutChangeListener {
		final TextView view;
		final boolean checkMark;
		final int compoundSlot;
		Boolean exclusive;
		Integer surface;
		ChoiceIndicatorDrawable drawable;
		Drawable presentation;
		int endInset, installedInset = -1, direction = -1;
		Binding(TextView view) {
			this.view = view;
			CheckedTextView checked = view instanceof CheckedTextView ? (CheckedTextView) view : null;
			checkMark = checked != null && checked.getCheckMarkDrawable() != null;
			Drawable[] compound = view.getCompoundDrawablesRelative();
			// Native checked rows put one indicator on start OR end. Preserve that placement,
			// and never mistake arbitrary multi-icon content for a selection control.
			compoundSlot = !checkMark && (compound[0] == null ^ compound[2] == null) ? compound[0] != null ? 0 : 2 : -1;
			view.setTag(R.id.choice_indicator_appearance, this); view.addOnAttachStateChangeListener(this); view.addOnLayoutChangeListener(this);
		}
		void apply(int[] colors) {
			Boolean radio = view instanceof RadioButton ? Boolean.TRUE : view instanceof CheckBox ? Boolean.FALSE
					: exclusive != null ? exclusive : listExclusive((CheckedTextView) view);
			if (radio == null || view instanceof CheckedTextView && !checkMark && compoundSlot < 0) return;
			if (drawable == null || drawable.isRadio() != radio) {
				drawable = new ChoiceIndicatorDrawable(view, radio);
				drawable.setStateColors(() -> ChoiceAppearance.colors(view, StandardAppearance.colors(view.getContext()), surface));
				presentation = null;
			}
			drawable.setColors(colors); ColorStateList tint = tint(colors);
			if (view instanceof CompoundButton) {
				CompoundButton control = (CompoundButton) view;
				if (presentation == null || installedInset != endInset || direction != view.getLayoutDirection()) {
					direction = view.getLayoutDirection(); installedInset = endInset;
					presentation = endInset == 0 ? drawable : new InsetDrawable(drawable,
							direction == View.LAYOUT_DIRECTION_RTL ? endInset : 0, 0,
							direction == View.LAYOUT_DIRECTION_RTL ? 0 : endInset, 0);
				}
				if (control.getButtonDrawable() != presentation) control.setButtonDrawable(presentation);
				control.setButtonTintList(tint);
			} else {
				CheckedTextView checked = (CheckedTextView) view;
				if (checkMark) {
					if (checked.getCheckMarkDrawable() != drawable) checked.setCheckMarkDrawable(drawable);
				} else {
					Drawable[] current = checked.getCompoundDrawablesRelative();
					if (current[compoundSlot] != drawable) {
						current[compoundSlot] = drawable;
						drawable.setBounds(0, 0, drawable.getIntrinsicWidth(), drawable.getIntrinsicHeight());
						checked.setCompoundDrawablesRelative(current[0], current[1], current[2], current[3]);
					}
				}
				checked.setCheckMarkTintList(tint);
			}
		}
		@Override public void onViewAttachedToWindow(View view) { ChoiceAppearance.apply(view); }
		@Override public void onViewDetachedFromWindow(View view) {}
		@Override public void onLayoutChange(View view, int left, int top, int right, int bottom,
				int oldLeft, int oldTop, int oldRight, int oldBottom) { ChoiceAppearance.apply(view); }
	}
}
