package com.mishiranu.dashchan.content.security;

import static org.junit.Assert.*;
import android.content.Context;
import android.content.ContextWrapper;
import android.graphics.Rect;
import android.graphics.drawable.ColorDrawable;
import android.view.ContextThemeWrapper;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.ScrollView;
import androidx.test.platform.app.InstrumentationRegistry;
import com.mishiranu.dashchan.R;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.Test;

/** Presentation-only tests. Never changes credentials, grants a session or launches cleanup. */
public class LockScreenDesignTest {
	@Test public void fixedPaletteDoesNotApplyAThemeToItsBaseActivityOrApplication() {
		InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
			Context base = InstrumentationRegistry.getInstrumentation().getTargetContext();
			AtomicInteger forwarded = new AtomicInteger();
			Context sentinel = new ContextWrapper(base) {
				@Override public void setTheme(int resource) { forwarded.incrementAndGet(); }
			};
			LockScreenDesign form = new LockScreenDesign(sentinel);
			try {
				assertEquals(0xff151d11, ((ColorDrawable) form.root.getBackground()).getColor());
				assertEquals(0, forwarded.get());
			} finally { form.dispose(); }
		});
	}
	@Test public void biometricIconAndTextShareACenteredAccessibleButton() {
		InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
			Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
			LockScreenDesign form = new LockScreenDesign(context);
			try {
				form.render(R.string.security_enter_pin, R.string.security_unlock, R.string.security_unlock,
						false, true, false, true, true);
				int width = Math.round(312 * context.getResources().getDisplayMetrics().density);
				form.biometric.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
						View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED));
				assertTrue(form.biometric instanceof LockActionButton);
				assertEquals(form.biometric.getPaddingStart(), form.biometric.getPaddingEnd());
				assertTrue(form.biometric.getPaddingStart() > Math.round(16 * context.getResources().getDisplayMetrics().density));
				assertEquals(0, ((ViewGroup) form.root).getChildAt(0).getTranslationX(), .01f);
			} finally { form.dispose(); }
		});
	}
	@Test public void shortViewportKeepsAllTwelveKeypadCellsAndCanReachTheLastRow() {
		InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
			Context base = InstrumentationRegistry.getInstrumentation().getTargetContext();
			Context context = new ContextThemeWrapper(base, android.R.style.Theme_DeviceDefault_NoActionBar);
			LockScreenDesign form = new LockScreenDesign(context);
			try {
				form.render(R.string.security_enter_pin, R.string.security_unlock, R.string.security_unlock,
						true, true, false, false, true);
				float density = context.getResources().getDisplayMetrics().density;
				int width = Math.round(360 * density), height = Math.round(600 * density);
				form.root.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
						View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY));
				form.root.layout(0, 0, width, height);
				List<Button> buttons = new ArrayList<>(); collectButtons(form.root, buttons);
				Button last = null;
				for (Button button : buttons) {
					if (context.getString(R.string.lock_delete_digit).contentEquals(
							button.getContentDescription() != null ? button.getContentDescription() : "")) last = button;
				}
				assertNotNull(last); assertEquals(View.VISIBLE, last.getVisibility());
				assertTrue(last.getMeasuredHeight() >= Math.round(48 * density));
				ViewGroup row = (ViewGroup) last.getParent();
				ViewGroup keypad = (ViewGroup) row.getParent();
				assertEquals(4, keypad.getChildCount());
				for (int i = 0; i < 4; i++) assertEquals(3, ((ViewGroup) keypad.getChildAt(i)).getChildCount());
				assertEquals(View.INVISIBLE, ((ViewGroup) keypad.getChildAt(3)).getChildAt(0).getVisibility());
				ScrollView scroll = (ScrollView) form.root;
				scroll.scrollTo(0, scroll.getChildAt(0).getHeight());
				Rect bounds = new Rect(); last.getDrawingRect(bounds);
				scroll.offsetDescendantRectToMyCoords(last, bounds);
				bounds.offset(-scroll.getScrollX(), -scroll.getScrollY());
				assertTrue(bounds.bottom <= scroll.getHeight() + 1);
			} finally { form.dispose(); }
		});
	}
	private static void collectButtons(View view, List<Button> buttons) {
		if (view instanceof Button) buttons.add((Button) view);
		if (view instanceof ViewGroup) {
			ViewGroup parent = (ViewGroup) view;
			for (int i = 0; i < parent.getChildCount(); i++) collectButtons(parent.getChildAt(i), buttons);
		}
	}
}
