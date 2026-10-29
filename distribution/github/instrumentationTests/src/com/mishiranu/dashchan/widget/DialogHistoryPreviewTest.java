package com.mishiranu.dashchan.widget;

import static org.junit.Assert.*;
import android.view.View;
import android.widget.TextView;
import androidx.test.core.app.ActivityScenario;
import androidx.test.platform.app.InstrumentationRegistry;
import com.mishiranu.dashchan.content.Preferences;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import org.junit.Test;

/** Real DialogStack routing with a bounded provider-history adapter; Context DB/hide cases remain manual. */
public class DialogHistoryPreviewTest {
	@org.junit.Before public void supportedApi() { org.junit.Assume.assumeTrue(android.os.Build.VERSION.SDK_INT >= 33); }
	private static void call(Object owner, String name, Class<?>[] types, Object... args) {
		try { Method method = owner.getClass().getDeclaredMethod(name, types); method.setAccessible(true); method.invoke(owner, args); }
		catch (ReflectiveOperationException e) { throw new AssertionError(e); }
	}
	private static Object field(Object owner, String name) {
		try { Field field = owner.getClass().getDeclaredField(name); field.setAccessible(true); return field.get(owner); }
		catch (ReflectiveOperationException e) { throw new AssertionError(e); }
	}
	private static int count(DialogStack<?> stack) { int count = 0; for (Object ignored : stack) count++; return count; }
	private static final class Provider implements DialogStack.ViewFactory<Provider> {
		TextView view;
		BackPreviewFrame previous, current;
		HistoryBackPreview active;
		boolean history;
		int revision, backs;
		@Override public View createView(DialogStack<Provider> stack) {
			view = new TextView(ThemeEngine.attach(InstrumentationRegistry.getInstrumentation().getTargetContext()));
			view.setText("A\nPrevious post"); view.setMinHeight(240); return view;
		}
		void advance() {
			previous = BackPreviewFrame.capture(view, false); assertNotNull(previous);
			view.setText("B\nCurrent post"); history = true; revision++;
		}
		void prepare() { current = BackPreviewFrame.capture(view, false); assertNotNull(current); }
		@Override public boolean canHandleBack() { return history; }
		@Override public long backActionRevision() { return revision; }
		@Override public HistoryBackPreview startBackPreview(boolean fromLeft) {
			int frozen = revision;
			active = HistoryBackPreview.start(view, previous, current, fromLeft, () -> history && frozen == revision); return active;
		}
		@Override public boolean onBackPressed() {
			if (!history) return false;
			backs++; history = false; revision++; view.setText("A\nPrevious post");
			if (active != null) active.destinationReady(); return true;
		}
		@Override public void destroyView(View view, boolean remove) {
			if (active != null) active.close();
			if (previous != null) { previous.release(); previous = null; }
			if (current != null) { current.release(); current = null; }
		}
	}
	private static void fixture(boolean commit) {
		Object old = Preferences.PREFERENCES.getAll().get(Preferences.KEY_PREDICTIVE_BACK);
		DialogStack<Provider>[] stack = new DialogStack[1]; Provider provider = new Provider();
		try (ActivityScenario<StandardContentTestActivity> scenario = ActivityScenario.launch(StandardContentTestActivity.class)) {
			scenario.onActivity(a -> { Preferences.PREFERENCES.edit().put(Preferences.KEY_PREDICTIVE_BACK, true).close();
				stack[0] = new DialogStack<>(a); stack[0].push(provider); });
			InstrumentationRegistry.getInstrumentation().waitForIdleSync();
			scenario.onActivity(a -> provider.advance());
			InstrumentationRegistry.getInstrumentation().waitForIdleSync();
			scenario.onActivity(a -> {
				provider.prepare(); call(stack[0], "startPredictiveBack", new Class<?>[]{boolean.class}, true);
				assertNotNull(provider.active); provider.active.progress(.7f);
				assertEquals("B\nCurrent post", provider.view.getText().toString()); assertEquals(0, provider.backs);
				if (commit) {
					call(stack[0], "handleBackInvocation", new Class<?>[0]);
					call(stack[0], "handleBackInvocation", new Class<?>[0]);
					provider.active.close(); provider.active.close();
					assertEquals(1, provider.backs); assertFalse(provider.history);
					assertEquals("A\nPrevious post", provider.view.getText().toString());
				} else {
					call(stack[0], "cancelPredictiveBack", new Class<?>[0]); provider.active.close();
					assertEquals(0, provider.backs); assertTrue(provider.history);
					assertEquals("B\nCurrent post", provider.view.getText().toString());
				}
				assertEquals(1, count(stack[0])); stack[0].clear();
			});
		} finally {
			InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
				if (stack[0] != null) stack[0].clear();
				if (old instanceof Boolean) Preferences.PREFERENCES.edit().put(Preferences.KEY_PREDICTIVE_BACK, (Boolean) old).close();
				else Preferences.PREFERENCES.edit().remove(Preferences.KEY_PREDICTIVE_BACK).close();
			});
		}
	}
	@Test public void providerCancelKeepsCurrentPostAndOuterWindow() { fixture(false); }
	@Test public void providerCommitStepsOnceWithoutPoppingOuterWindow() { fixture(true); }
	@Test public void ordinaryQuoteBackPopsOnlyOneOuterEntry() {
		try (ActivityScenario<StandardContentTestActivity> scenario = ActivityScenario.launch(StandardContentTestActivity.class)) {
			DialogStack<Provider>[] stack = new DialogStack[1];
			scenario.onActivity(a -> { stack[0] = new DialogStack<>(a); stack[0].push(new Provider()); stack[0].push(new Provider()); });
			InstrumentationRegistry.getInstrumentation().waitForIdleSync();
			scenario.onActivity(a -> {
				call(stack[0], "startPredictiveBack", new Class<?>[]{boolean.class}, false);
				call(stack[0], "handleBackInvocation", new Class<?>[0]);
				((PredictiveBackMotion) field(stack[0], "predictiveMotion")).finish();
				assertEquals(1, count(stack[0])); stack[0].clear();
			});
		}
	}
}
