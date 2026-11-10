package com.mishiranu.dashchan.widget;

import android.content.res.TypedArray;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import androidx.core.graphics.ColorUtils;
import com.mishiranu.dashchan.R;
import com.mishiranu.dashchan.util.OpaqueColors;

/** Shared caret/selection palette. Does not change text, focus, IME, callbacks or geometry. */
public final class TextInputAppearance {

	private TextInputAppearance() {}

	private static final class Mark {
		final Drawable original;
		Drawable owned;
		int color;
		Mark(Drawable original) { this.original = original; }
		Drawable tint(EditText view, Drawable current, int color) {
			// Null and later replacements belong to the caller. Never resurrect a hidden
			// cursor or overwrite a dedicated screen's replacement on a later refresh.
			if (current == null || current != original && current != owned) return current;
			if (owned == null) {
				Drawable.ConstantState constant = current.getConstantState();
				// A state-less custom drawable cannot be copied without touching its owner.
				if (constant == null) return current;
				Drawable copy = constant.newDrawable(view.getResources(), view.getContext().getTheme()).mutate();
				// Programmatic drawables can carry a density different from Resources.
				// Recoloring must keep their authored intrinsic size on every density.
				if (copy.getIntrinsicWidth() != current.getIntrinsicWidth() || copy.getIntrinsicHeight() != current.getIntrinsicHeight()) {
					copy = constant.newDrawable().mutate();
				}
				if (copy.getIntrinsicWidth() != current.getIntrinsicWidth() || copy.getIntrinsicHeight() != current.getIntrinsicHeight()) return current;
				owned = copy;
				owned.setBounds(current.getBounds()); owned.setLevel(current.getLevel());
				owned.setState(current.getState()); owned.setLayoutDirection(current.getLayoutDirection());
				owned.setTint(color); this.color = color;
			} else if (this.color != color) {
				owned.setTint(color); this.color = color;
			}
			return owned;
		}
	}

	private static final class State {
		final Mark cursor, left, right, middle;
		final int originalHighlight;
		final boolean preserved, floating;
		boolean explicit;
		int highlight;
		State(EditText view) {
			TypedArray attributes = view.getContext().obtainStyledAttributes(new int[] {
					R.attr.themeEnginePreserveColors, android.R.attr.windowIsFloating});
			preserved = attributes.getBoolean(0, false); floating = attributes.getBoolean(1, false);
			attributes.recycle();
			cursor = new Mark(view.getTextCursorDrawable()); left = new Mark(view.getTextSelectHandleLeft());
			right = new Mark(view.getTextSelectHandleRight()); middle = new Mark(view.getTextSelectHandle());
			originalHighlight = highlight = view.getHighlightColor();
		}
	}

	private static State state(EditText view) {
		Object tag = view.getTag(R.id.text_input_appearance);
		if (Boolean.FALSE.equals(tag)) return null;
		if (tag instanceof State) return (State) tag;
		State state = new State(view); view.setTag(R.id.text_input_appearance, state); return state;
	}

	/** Explicit palettes can opt out before the generic theme/inflation pass. */
	public static void preserve(EditText view) {
		view.setTag(R.id.text_input_appearance, Boolean.FALSE);
	}

	/** Native/common fields, including the classic interface. No fallback theme creation. */
	public static void applyTheme(EditText view) {
		ThemeEngine.Theme theme = ThemeEngine.peekTheme(view.getContext());
		if (theme == null) return;
		State state = state(view); if (state == null || state.preserved) return;
		if (state.explicit) return; // A generic inflation/rebind pass must not undo a field owner's palette.
		int surface = state.floating ? theme.card : theme.window;
		// Programmatic fields may already be in a container with an explicit opaque fill.
		// Complex/stateful field fills are resolved by their appearance adapter below.
		for (View ancestor = view; ancestor != null;) {
			Drawable background = ancestor.getBackground();
			if (background instanceof ColorDrawable && Color.alpha(((ColorDrawable) background).getColor()) == 255) {
				surface = ((ColorDrawable) background).getColor(); break;
			}
			ancestor = ancestor.getParent() instanceof View ? (View) ancestor.getParent() : null;
		}
		applyPalette(view, state, theme.accent, surface);
	}

	/** Leaving a local Material role returns to the shared classic theme, not an old white overlay. */
	public static void restoreTheme(EditText view) {
		State state = state(view); if (state == null || state.preserved) return;
		state.explicit = false; applyTheme(view);
	}

	/** A field owner supplies its real surface instead of an inherited toolbar/dialog accent. */
	public static void apply(EditText view, int accent, int surface) {
		State state = state(view); if (state == null || state.preserved) return;
		state.explicit = true; applyPalette(view, state, accent, surface);
	}

	private static void applyPalette(EditText view, State state, int accent, int surface) {
		ThemeEngine.Theme theme = ThemeEngine.peekTheme(view.getContext());
		int window = theme != null ? OpaqueColors.over(theme.window,
				theme.base == ThemeEngine.Theme.Base.DARK ? Color.BLACK : Color.WHITE) : Color.WHITE;
		int backing = OpaqueColors.over(surface, window);
		int color = OpaqueColors.readable(accent, backing, 3, OpaqueColors.Policy.INTERFACE_COMPATIBILITY);
		Drawable current = view.getTextCursorDrawable(), next = state.cursor.tint(view, current, color);
		if (current != next) view.setTextCursorDrawable(next);
		current = view.getTextSelectHandleLeft(); next = state.left.tint(view, current, color);
		if (current != next) view.setTextSelectHandleLeft(next);
		current = view.getTextSelectHandleRight(); next = state.right.tint(view, current, color);
		if (current != next) view.setTextSelectHandleRight(next);
		current = view.getTextSelectHandle(); next = state.middle.tint(view, current, color);
		if (current != next) view.setTextSelectHandle(next);
		int currentHighlight = view.getHighlightColor();
		if (currentHighlight == state.originalHighlight || currentHighlight == state.highlight) {
			state.highlight = ColorUtils.setAlphaComponent(color, 64);
			if (currentHighlight != state.highlight) view.setHighlightColor(state.highlight);
		}
	}

	/** One inflation-time pass also reaches fully-qualified/custom widgets and constructor children. */
	static void applyTree(View view) {
		if (view instanceof EditText) ThemeEngine.applyTextInputStyle((EditText) view);
		if (view instanceof ViewGroup) {
			ViewGroup group = (ViewGroup) view;
			for (int i = 0; i < group.getChildCount(); i++) applyTree(group.getChildAt(i));
		}
	}
}
