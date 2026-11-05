package com.mishiranu.dashchan.widget;

import static org.junit.Assert.*;
import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Rect;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.InsetDrawable;
import android.graphics.drawable.LayerDrawable;
import android.graphics.drawable.RippleDrawable;
import android.graphics.drawable.StateListDrawable;
import android.view.View;
import android.widget.ListView;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import org.junit.Test;
import org.junit.runner.RunWith;

/** Detached fixtures: no Activity, preference writes, files, network or device settings. */
@RunWith(AndroidJUnit4.class)
public class SnapshotDrawingTest {
	private static class HardwareOnlyRipple extends RippleDrawable {
		final Drawable.ConstantState contentState;
		HardwareOnlyRipple(Drawable content) {
			super(ColorStateList.valueOf(Color.BLUE), content, new ColorDrawable(Color.WHITE));
			contentState = content != null ? content.getConstantState() : null;
		}
		@Override public ConstantState getConstantState() {
			return new ConstantState() {
				@Override public Drawable newDrawable() {
					return new HardwareOnlyRipple(contentState != null ? contentState.newDrawable().mutate() : null);
				}
				@Override public int getChangingConfigurations() { return 0; }
			};
		}
		@Override public void draw(Canvas canvas) {
			if (!canvas.isHardwareAccelerated()) throw new AssertionError("Native ripple reached bitmap Canvas");
			super.draw(canvas);
		}
	}
	private Context context() {
		return PostingTestContext.create(InstrumentationRegistry.getInstrumentation().getTargetContext());
	}

	@Test public void captureKeepsContentAndLivePressStateAndNeverPaintsTheMask() {
		InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
			View view = new View(context());
			RippleDrawable background = new HardwareOnlyRipple(new ColorDrawable(Color.RED));
			RippleDrawable foreground = new HardwareOnlyRipple(null);
			view.setBackground(background); view.setForeground(foreground);
			view.setPaddingRelative(3, 4, 5, 6); view.setMinimumWidth(7); view.setMinimumHeight(8);
			view.layout(0, 0, 40, 40); view.setPressed(true);
			int[] state = background.getState().clone(); Drawable.Callback callback = background.getCallback();
			Bitmap image = Bitmap.createBitmap(40, 40, Bitmap.Config.ARGB_8888);
			try {
				for (int i = 0; i < 3; i++) {
					image.eraseColor(Color.TRANSPARENT); SnapshotDrawing.draw(view, new Canvas(image));
					assertEquals(Color.RED, image.getPixel(20, 20));
					assertSame(background, view.getBackground()); assertSame(foreground, view.getForeground());
					assertSame(callback, background.getCallback()); assertArrayEquals(state, background.getState());
					assertTrue(view.isPressed()); assertTrue(view.isEnabled());
					assertTrue(view.isPaddingRelative()); assertEquals(3, view.getPaddingStart());
					assertEquals(4, view.getPaddingTop()); assertEquals(5, view.getPaddingEnd());
					assertEquals(6, view.getPaddingBottom()); assertEquals(7, view.getMinimumWidth());
					assertEquals(8, view.getMinimumHeight()); assertEquals(40, view.getWidth());
				}
			} finally { image.recycle(); }
		});
	}

	@Test public void wrappedAndStateSelectedRippleKeepsInsetsAndRestoresItsOwners() {
		InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
			RippleDrawable ripple = new HardwareOnlyRipple(new ColorDrawable(Color.RED));
			InsetDrawable inset = new InsetDrawable(ripple, 5);
			StateListDrawable selected = new StateListDrawable(); selected.addState(new int[]{}, inset);
			LayerDrawable background = new LayerDrawable(new Drawable[]{new ColorDrawable(Color.GREEN), selected});
			View view = new View(context()); view.setBackground(background); view.layout(0, 0, 40, 40);
			Drawable.Callback callback = ripple.getCallback();
			Rect originalBounds = new Rect(ripple.getBounds());
			Bitmap image = Bitmap.createBitmap(40, 40, Bitmap.Config.ARGB_8888);
			try {
				for (int i = 0; i < 3; i++) {
					SnapshotDrawing.draw(view, new Canvas(image));
					assertEquals(Color.GREEN, image.getPixel(1, 1)); assertEquals(Color.RED, image.getPixel(20, 20));
					assertSame(background, view.getBackground()); assertSame(inset, selected.getCurrent());
					assertSame(ripple, inset.getDrawable()); assertSame(callback, ripple.getCallback());
					assertEquals(originalBounds, ripple.getBounds());
				}
			} finally { image.recycle(); }
		});
	}

	@Test public void exceptionRestoresBackgroundForegroundAndNestedDrawableCallbacks() {
		InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
			View view = new View(context());
			RippleDrawable background = new HardwareOnlyRipple(new ColorDrawable(Color.RED)) {
				@Override public ConstantState getConstantState() { return null; }
			};
			RippleDrawable foreground = new HardwareOnlyRipple(null);
			InsetDrawable wrapper = new InsetDrawable(background, 2);
			view.setBackground(wrapper); view.setForeground(foreground); view.setPadding(3, 4, 5, 6);
			view.layout(0, 0, 40, 40); view.setPressed(true);
			Drawable.Callback callback = background.getCallback();
			RuntimeException failure = new IllegalStateException("Synthetic draw failure");
			Bitmap image = Bitmap.createBitmap(40, 40, Bitmap.Config.ARGB_8888);
			try {
				try { SnapshotDrawing.draw(view, new Canvas(image), canvas -> { throw failure; }); fail(); }
				catch (RuntimeException e) { assertSame(failure, e); }
				assertSame(wrapper, view.getBackground()); assertSame(background, wrapper.getDrawable());
				assertSame(foreground, view.getForeground()); assertSame(callback, background.getCallback());
				assertTrue(view.isPressed()); assertEquals(3, view.getPaddingLeft()); assertEquals(6, view.getPaddingBottom());
			} finally { image.recycle(); }
		});
	}

	@Test public void listSelectorAndStandaloneDrawableUseTheSameSnapshotPolicy() {
		InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
			ListView list = new ListView(context());
			RippleDrawable selector = new HardwareOnlyRipple(new ColorDrawable(Color.RED));
			list.setSelector(selector); list.layout(0, 0, 40, 40);
			Drawable.Callback callback = selector.getCallback();
			Bitmap image = Bitmap.createBitmap(40, 40, Bitmap.Config.ARGB_8888);
			try {
				SnapshotDrawing.draw(list, new Canvas(image), canvas -> {
					list.getSelector().setBounds(0, 0, 40, 40); list.getSelector().draw(canvas);
				});
				assertEquals(Color.RED, image.getPixel(20, 20));
				assertSame(selector, list.getSelector()); assertSame(callback, selector.getCallback());
				image.eraseColor(Color.TRANSPARENT); selector.setBounds(0, 0, 40, 40);
				SnapshotDrawing.draw(selector, new Canvas(image));
				assertEquals(Color.RED, image.getPixel(20, 20)); assertSame(callback, selector.getCallback());
			} finally { image.recycle(); }
		});
	}

	@Test public void captureDoesNotRewriteTheOriginalOrSharedConstantStateWhileDrawing() {
		InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
			RippleDrawable ripple = new HardwareOnlyRipple(new ColorDrawable(Color.RED));
			InsetDrawable background = new InsetDrawable(ripple, 5);
			InsetDrawable sibling = (InsetDrawable) background.getConstantState().newDrawable();
			Drawable siblingRipple = sibling.getDrawable();
			Drawable.Callback callback = ripple.getCallback();
			View view = new View(context()); view.setBackground(background); view.layout(0, 0, 40, 40);
			Bitmap image = Bitmap.createBitmap(40, 40, Bitmap.Config.ARGB_8888);
			try {
				SnapshotDrawing.draw(view, new Canvas(image), canvas -> {
					assertSame(ripple, background.getDrawable()); assertSame(callback, ripple.getCallback());
					assertSame(siblingRipple, sibling.getDrawable()); assertNotSame(background, view.getBackground());
					view.draw(canvas);
				});
				assertEquals(Color.RED, image.getPixel(20, 20));
				assertSame(background, view.getBackground()); assertSame(ripple, background.getDrawable());
				assertSame(siblingRipple, sibling.getDrawable());
			} finally { image.recycle(); }
		});
	}
}
