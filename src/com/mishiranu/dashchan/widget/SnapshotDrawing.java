package com.mishiranu.dashchan.widget;

import android.graphics.Canvas;
import android.graphics.Outline;
import android.graphics.Rect;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.DrawableContainer;
import android.graphics.drawable.DrawableWrapper;
import android.graphics.drawable.LayerDrawable;
import android.graphics.drawable.RippleDrawable;
import android.graphics.drawable.StateListDrawable;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AbsListView;
import androidx.annotation.UiThread;
import com.mishiranu.dashchan.graphics.BaseDrawable;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.function.Consumer;

/** UI-thread bitmap drawing without the platform's hardware-only touch-ink animation. */
public final class SnapshotDrawing {
	private SnapshotDrawing() {}

	@UiThread
	public static void draw(View view, Canvas canvas) {
		draw(view, canvas, view::draw);
	}

	/** The callback preserves a caller's cropping, child-only drawing and canvas transforms. */
	@UiThread
	public static void draw(View root, Canvas canvas, Consumer<Canvas> draw) {
		if (canvas.isHardwareAccelerated()) { draw.accept(canvas); return; }
		try (Scope scope = new Scope()) {
			scope.prepare(root);
			draw.accept(canvas);
		}
	}

	@UiThread
	public static void draw(Drawable drawable, Canvas canvas) {
		if (canvas.isHardwareAccelerated()) { drawable.draw(canvas); return; }
		try (Scope scope = new Scope()) {
			scope.prepare(drawable).draw(canvas);
		}
	}

	private static final class Scope implements AutoCloseable {
		final ArrayList<Runnable> restore = new ArrayList<>();
		final IdentityHashMap<Drawable, Drawable> prepared = new IdentityHashMap<>();
		final IdentityHashMap<Drawable, Boolean> hasRipple = new IdentityHashMap<>();

		void prepare(View view) {
			Drawable background = view.getBackground(), replacement = prepare(background);
			if (replacement != background) {
				boolean relative = view.isPaddingRelative();
				int start = relative ? view.getPaddingStart() : view.getPaddingLeft();
				int end = relative ? view.getPaddingEnd() : view.getPaddingRight();
				int top = view.getPaddingTop(), bottom = view.getPaddingBottom();
				Runnable padding = () -> {
					if (relative) view.setPaddingRelative(start, top, end, bottom);
					else view.setPadding(start, top, end, bottom);
				};
				restore.add(() -> { view.setBackground(background); padding.run(); });
				view.setBackground(replacement);
				// setBackground can install drawable padding over the view's custom padding.
				padding.run();
			}
			Drawable foreground = view.getForeground(); replacement = prepare(foreground);
			if (replacement != foreground) {
				restore.add(() -> view.setForeground(foreground));
				view.setForeground(replacement);
			}
			if (view instanceof AbsListView) {
				AbsListView list = (AbsListView) view;
				Drawable selector = list.getSelector(); replacement = prepare(selector);
				if (replacement != selector) {
					restore.add(() -> list.setSelector(selector));
					list.setSelector(replacement);
				}
			}
			if (view instanceof ViewGroup) {
				ViewGroup group = (ViewGroup) view;
				for (int i = 0; i < group.getChildCount(); i++) {
					View child = group.getChildAt(i);
					if (child.getVisibility() == View.VISIBLE) prepare(child);
				}
			}
		}

		Drawable prepare(Drawable drawable) {
			return prepare(drawable, false);
		}

		private boolean containsRipple(Drawable drawable) {
			if (drawable == null) return false;
			Boolean known = hasRipple.get(drawable);
			if (known != null) return known;
			hasRipple.put(drawable, false);
			boolean result = drawable instanceof RippleDrawable;
			if (!result && drawable instanceof LayerDrawable) {
				LayerDrawable layers = (LayerDrawable) drawable;
				for (int i = 0; i < layers.getNumberOfLayers() && !result; i++) result = containsRipple(layers.getDrawable(i));
			} else if (!result && drawable instanceof DrawableWrapper) {
				result = containsRipple(((DrawableWrapper) drawable).getDrawable());
			} else if (!result && drawable instanceof StateListDrawable) {
				StateListDrawable states = (StateListDrawable) drawable;
				// Include an outgoing state: DrawableContainer can still be fading its ripple out.
				for (int i = 0; i < states.getStateCount() && !result; i++) result = containsRipple(states.getStateDrawable(i));
			} else if (!result && drawable instanceof DrawableContainer) {
				Drawable current = drawable.getCurrent();
				result = current != drawable && containsRipple(current);
			}
			hasRipple.put(drawable, result);
			return result;
		}

		private Drawable prepare(Drawable drawable, boolean owned) {
			if (!containsRipple(drawable)) return drawable;
			Drawable existing = prepared.get(drawable);
			if (existing != null) return existing;
			// Never replace children inside a ConstantState shared with another live control.
			prepared.put(drawable, drawable);
			Drawable.ConstantState state = !owned ? drawable.getConstantState() : null;
			if (state != null) {
				Drawable copy = state.newDrawable().mutate();
				copy.setLayoutDirection(drawable.getLayoutDirection()); copy.setLevel(drawable.getLevel());
				copy.setState(drawable.getState()); copy.setBounds(drawable.getBounds());
				copy.setAlpha(drawable.getAlpha()); copy.setAutoMirrored(drawable.isAutoMirrored());
				if (drawable.getColorFilter() != null) copy.setColorFilter(drawable.getColorFilter());
				Drawable result = prepare(copy, true);
				prepared.put(drawable, result);
				return result;
			}
			// Non-copyable trees have no reusable ConstantState. Scope restores their child
			// references and callbacks even when preparation or drawing throws.
			Drawable result = drawable;
			if (drawable instanceof RippleDrawable) {
				result = new Content(drawable, null);
			} else if (drawable instanceof DrawableContainer) {
				Drawable current = drawable.getCurrent();
				if (current != null && current != drawable) {
					// A container may still be fading out its previous, hardware-only ripple.
					// Capture its selected state without advancing or stopping that live fade.
					result = new Content(drawable, prepare(current, owned));
				}
			}
			if (result != drawable) {
				Rect bounds = new Rect(drawable.getBounds());
				Drawable.Callback callback = drawable.getCallback();
				// Bounds advance just as in an ordinary View.draw(). Rolling back only a
				// nested child's bounds would leave it stale inside an already sized wrapper.
				restore.add(() -> drawable.setCallback(callback));
				result.setBounds(bounds);
			}
			prepared.put(drawable, result);
			if (drawable instanceof LayerDrawable) {
				LayerDrawable layers = (LayerDrawable) drawable;
				for (int i = 0; i < layers.getNumberOfLayers(); i++) {
					if (drawable instanceof RippleDrawable && layers.getId(i) == android.R.id.mask) continue;
					Drawable child = layers.getDrawable(i), replacement = prepare(child, owned);
					if (replacement != child) {
						int index = i;
						Rect bounds = new Rect();
						restore.add(() -> {
							bounds.set(replacement.getBounds());
							layers.setDrawable(index, child); child.setBounds(bounds);
						});
						layers.setDrawable(index, replacement);
					}
				}
			} else if (drawable instanceof DrawableWrapper) {
				DrawableWrapper wrapper = (DrawableWrapper) drawable;
				Drawable child = wrapper.getDrawable(), replacement = prepare(child, owned);
				if (replacement != child) {
					Rect bounds = new Rect();
					restore.add(() -> {
						bounds.set(replacement.getBounds());
						wrapper.setDrawable(child); child.setBounds(bounds);
					});
					wrapper.setDrawable(replacement);
				}
			}
			return result;
		}

		@Override public void close() {
			Throwable failure = null;
			for (int i = restore.size() - 1; i >= 0; i--) {
				try { restore.get(i).run(); }
				catch (RuntimeException | Error e) {
					if (failure == null) failure = e;
					else if (failure != e) failure.addSuppressed(e);
				}
			}
			restore.clear(); prepared.clear(); hasRipple.clear();
			if (failure instanceof RuntimeException) throw (RuntimeException) failure;
			if (failure instanceof Error) throw (Error) failure;
		}
	}

	/** The native ripple keeps its state, pending animations, callbacks and content layers. */
	private static final class Content extends BaseDrawable {
		final Drawable source, current;
		Content(Drawable source, Drawable current) { this.source = source; this.current = current; }
		@Override protected void onBoundsChange(Rect bounds) {
			source.setBounds(bounds);
			if (current != null) current.setBounds(source.getCurrent().getBounds());
		}
		@Override public void draw(Canvas canvas) {
			if (current != null) { current.draw(canvas); return; }
			RippleDrawable ripple = (RippleDrawable) source;
			int save = canvas.save();
			try {
				if (ripple.getNumberOfLayers() > 0) canvas.clipRect(getBounds());
				for (int i = 0; i < ripple.getNumberOfLayers(); i++) {
					if (ripple.getId(i) != android.R.id.mask) ripple.getDrawable(i).draw(canvas);
				}
			} finally { canvas.restoreToCount(save); }
		}
		@Override public boolean getPadding(Rect padding) { return source.getPadding(padding); }
		@Override public int getMinimumWidth() { return source.getMinimumWidth(); }
		@Override public int getMinimumHeight() { return source.getMinimumHeight(); }
		@Override public int getIntrinsicWidth() { return source.getIntrinsicWidth(); }
		@Override public int getIntrinsicHeight() { return source.getIntrinsicHeight(); }
		@Override public void getOutline(Outline outline) { source.getOutline(outline); }
		@SuppressWarnings({"deprecation", "RedundantSuppression"})
		@Override public int getOpacity() { return source.getOpacity(); }
	}
}
