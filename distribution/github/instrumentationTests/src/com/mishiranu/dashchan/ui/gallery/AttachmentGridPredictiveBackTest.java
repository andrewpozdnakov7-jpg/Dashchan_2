package com.mishiranu.dashchan.ui.gallery;

import static org.junit.Assert.*;
import android.animation.ValueAnimator;
import android.view.View;
import android.window.BackEvent;
import android.window.OnBackAnimationCallback;
import android.widget.FrameLayout;
import androidx.annotation.RequiresApi;
import androidx.test.core.app.ActivityScenario;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.filters.SdkSuppress;
import androidx.test.platform.app.InstrumentationRegistry;
import com.mishiranu.dashchan.R;
import com.mishiranu.dashchan.content.Preferences;
import com.mishiranu.dashchan.widget.AttachmentView;
import com.mishiranu.dashchan.widget.StandardContentTestActivity;
import java.lang.reflect.Field;
import java.util.function.Consumer;
import org.junit.Assume;
import org.junit.Test;
import org.junit.runner.RunWith;

/** Real dialog/callback lifecycle fixtures. ADDED / NOT RUN; no media decoder or network. */
@RunWith(AndroidJUnit4.class)
@SdkSuppress(minSdkVersion = 34)
@RequiresApi(34)
public class AttachmentGridPredictiveBackTest {
	private static Object callback(AttachmentGridDialog dialog) {
		try {
			Field field = AttachmentGridDialog.class.getDeclaredField("predictiveBackCallback"); field.setAccessible(true);
			Object value = field.get(dialog); assertNotNull(value); return value;
		} catch (ReflectiveOperationException e) { throw new AssertionError(e); }
	}
	private static final class Fixture {
		final AttachmentGridDialog dialog;
		final FrameLayout content;
		int cancellations;
		Fixture(StandardContentTestActivity activity) {
			FrameLayout background = new FrameLayout(activity); AttachmentView source = new AttachmentView(activity, null);
			background.addView(source, new FrameLayout.LayoutParams(80, 80)); activity.setContentView(background);
			dialog = new AttachmentGridDialog(activity, R.style.Theme_Gallery);
			content = new FrameLayout(dialog.getContext()); content.setAlpha(.7f);
			content.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_YES);
			dialog.setContentView(content, new FrameLayout.LayoutParams(300, 400)); dialog.setMotionContent(content, source);
			dialog.setOnCancelListener(d -> cancellations++); dialog.show(); dialog.getWindow().setDimAmount(.6f);
		}
		void assertRestored() {
			assertEquals(.7f, content.getAlpha(), .001f); assertEquals(1f, content.getScaleX(), .001f);
			assertEquals(1f, content.getScaleY(), .001f); assertEquals(0f, content.getTranslationX(), .001f);
			assertEquals(0f, content.getTranslationY(), .001f); assertNull(content.getTag(R.id.gallery_motion_owner));
			assertEquals(.6f, dialog.getWindow().getAttributes().dimAmount, .001f);
			assertEquals(View.IMPORTANT_FOR_ACCESSIBILITY_YES, content.getImportantForAccessibility());
		}
	}
	private static final class Harness implements AutoCloseable {
		final Object predictive = Preferences.PREFERENCES.getAll().get(Preferences.KEY_PREDICTIVE_BACK);
		final Object material = Preferences.PREFERENCES.getAll().get(Preferences.KEY_NEW_INTERFACE_MOTION);
		final ActivityScenario<StandardContentTestActivity> scenario;
		Fixture fixture;
		Harness() {
			Assume.assumeTrue(ValueAnimator.areAnimatorsEnabled());
			ActivityScenario<StandardContentTestActivity> created = null;
			try {
				InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
					Preferences.PREFERENCES.edit().put(Preferences.KEY_PREDICTIVE_BACK, true)
							.put(Preferences.KEY_NEW_INTERFACE_MOTION, true).close();
				});
				created = ActivityScenario.launch(StandardContentTestActivity.class); scenario = created;
				scenario.onActivity(a -> fixture = new Fixture(a)); idle();
			} catch (RuntimeException | Error failure) {
				try { if (created != null) created.close(); } finally { restorePreferences(); }
				throw failure;
			}
		}
		void ui(Consumer<Fixture> check) { scenario.onActivity(a -> check.accept(fixture)); }
		void idle() { InstrumentationRegistry.getInstrumentation().waitForIdleSync(); }
		@Override public void close() {
			try { ui(f -> f.dialog.dismiss()); scenario.close(); }
			finally { restorePreferences(); }
		}
		private void restorePreferences() {
			InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
					if (predictive instanceof Boolean) Preferences.PREFERENCES.edit().put(Preferences.KEY_PREDICTIVE_BACK, (Boolean) predictive).close();
					else Preferences.PREFERENCES.edit().remove(Preferences.KEY_PREDICTIVE_BACK).close();
					if (material instanceof Boolean) Preferences.PREFERENCES.edit().put(Preferences.KEY_NEW_INTERFACE_MOTION, (Boolean) material).close();
					else Preferences.PREFERENCES.edit().remove(Preferences.KEY_NEW_INTERFACE_MOTION).close();
			});
		}
	}

	@Test public void cancellationAndFocusLossRestoreWithoutClosingAndOldCallbacksStayInvalid() {
		try (Harness h = new Harness()) {
			h.ui(f -> {
				OnBackAnimationCallback old = (OnBackAnimationCallback) callback(f.dialog);
				old.onBackStarted(new BackEvent(0, 100, 0, BackEvent.EDGE_LEFT));
				old.onBackProgressed(new BackEvent(80, 100, .6f, BackEvent.EDGE_LEFT));
				assertTrue(f.dialog.isShowing()); assertTrue(f.dialog.getWindow().getAttributes().dimAmount < .6f);
				old.onBackCancelled(); f.dialog.onWindowFocusChanged(false); f.assertRestored();
				f.dialog.onWindowFocusChanged(true); assertNotSame(old, callback(f.dialog));
				old.onBackInvoked(); assertTrue(f.dialog.isShowing()); assertEquals(0, f.cancellations);
			});
		}
	}

	@Test public void acceptedCloseCompletesExactlyOnceEvenOnFocusLossAndRepeatedBack() {
		try (Harness h = new Harness()) {
			h.ui(f -> {
				OnBackAnimationCallback back = (OnBackAnimationCallback) callback(f.dialog);
				back.onBackStarted(new BackEvent(0, 100, 0, BackEvent.EDGE_RIGHT));
				back.onBackProgressed(new BackEvent(80, 100, .5f, BackEvent.EDGE_RIGHT));
				back.onBackInvoked(); back.onBackInvoked(); f.dialog.onWindowFocusChanged(false); back.onBackInvoked();
			});
			h.idle(); h.ui(f -> { assertFalse(f.dialog.isShowing()); assertEquals(1, f.cancellations); f.assertRestored(); });
		}
	}

	@Test public void forcedDismissSuppressesPendingUserCancelAndDisabledPredictiveKeepsOrdinaryBack() {
		try (Harness h = new Harness()) {
			h.ui(f -> {
				OnBackAnimationCallback back = (OnBackAnimationCallback) callback(f.dialog);
				back.onBackStarted(new BackEvent(0, 100, 0, BackEvent.EDGE_LEFT)); back.onBackInvoked();
				f.dialog.dismiss(); back.onBackInvoked();
			});
			h.idle(); h.ui(f -> { assertFalse(f.dialog.isShowing()); assertEquals(0, f.cancellations); f.assertRestored(); });
		}
		try (Harness h = new Harness()) {
			h.ui(f -> {
				Preferences.PREFERENCES.edit().put(Preferences.KEY_PREDICTIVE_BACK, false).close();
				Object back = callback(f.dialog); assertFalse(back instanceof OnBackAnimationCallback);
				((android.window.OnBackInvokedCallback) back).onBackInvoked(); f.dialog.onWindowFocusChanged(false);
			});
			h.idle(); h.ui(f -> { assertFalse(f.dialog.isShowing()); assertEquals(1, f.cancellations); });
		}
	}
}
