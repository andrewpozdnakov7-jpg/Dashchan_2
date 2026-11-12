package com.mishiranu.dashchan.content.security;

import static org.junit.Assert.*;
import android.content.Context;
import android.content.ContextWrapper;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import androidx.activity.result.ActivityResult;
import androidx.lifecycle.Lifecycle;
import androidx.lifecycle.LifecycleRegistry;
import androidx.savedstate.SavedStateRegistry;
import androidx.savedstate.SavedStateRegistryController;
import androidx.savedstate.SavedStateRegistryOwner;
import androidx.test.platform.app.InstrumentationRegistry;
import java.io.File;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

/** Isolated records and synthetic picker payloads only. ADDED / NOT RUN. */
public class ProtectedActivityResultsTest {
	private Context context;
	private File sandbox;
	private static final class Owner implements SavedStateRegistryOwner {
		final LifecycleRegistry lifecycle = new LifecycleRegistry(this);
		final SavedStateRegistryController state = SavedStateRegistryController.create(this);
		Owner(Bundle restored) { state.performAttach(); state.performRestore(restored); }
		@Override public Lifecycle getLifecycle() { return lifecycle; }
		@Override public SavedStateRegistry getSavedStateRegistry() { return state.getSavedStateRegistry(); }
		void event(Lifecycle.Event event) { lifecycle.handleLifecycleEvent(event); }
		void resume() { event(Lifecycle.Event.ON_CREATE); event(Lifecycle.Event.ON_START); event(Lifecycle.Event.ON_RESUME); }
		void destroy() { event(Lifecycle.Event.ON_PAUSE); event(Lifecycle.Event.ON_STOP); event(Lifecycle.Event.ON_DESTROY); }
	}
	private static ActivityResult result(String name) {
		return new ActivityResult(android.app.Activity.RESULT_OK, new Intent().setData(Uri.parse("content://picker-fixture/" + name)));
	}
	private static void main(Runnable work) { InstrumentationRegistry.getInstrumentation().runOnMainSync(work); }
	private static void idle() { InstrumentationRegistry.getInstrumentation().waitForIdleSync(); }
	@Before public void prepare() throws Exception {
		org.junit.Assume.assumeTrue(com.mishiranu.dashchan.BuildConfig.ENABLE_APP_SECURITY_SETTINGS);
		Context base = InstrumentationRegistry.getInstrumentation().getTargetContext();
		sandbox = new File(base.getCacheDir(), "protected-picker-fixture-" + java.util.UUID.randomUUID());
		assertTrue(sandbox.mkdirs());
		context = new ContextWrapper(base) { @Override public File getNoBackupFilesDir() { return sandbox; } };
		SecurityStore.foreground(context, true);
	}
	@After public void cleanup() throws Exception {
		if (context == null) return;
		RememberedAuthentication.revoke(context); SecuritySessionProvider.revoke(context);
		String alias = SecurityStore.pinAlias(context); assertTrue(alias.contains(".test."));
		java.security.KeyStore keys = java.security.KeyStore.getInstance("AndroidKeyStore"); keys.load(null); keys.deleteEntry(alias);
		PrivacyResetFiles.delete(sandbox);
	}
	@Test public void lockedResultSurvivesRecreationAndIsDeliveredOnceToTheNewOwner() throws Exception {
		SecurityStore.setPin(context, "123456".toCharArray(), false);
		AtomicInteger oldCalls = new AtomicInteger(), newCalls = new AtomicInteger(); Owner[] current = {null};
		main(() -> {
			Owner old = new Owner(null);
			ProtectedActivityResults.Delivery<ActivityResult> first = new ProtectedActivityResults.Delivery<>(old, old, () -> context, "fixture", r -> oldCalls.incrementAndGet());
			old.resume(); first.receive(result("kept")); assertEquals(0, oldCalls.get());
			Bundle saved = new Bundle(); old.state.performSave(saved); old.destroy();
			current[0] = new Owner(saved);
			new ProtectedActivityResults.Delivery<ActivityResult>(current[0], current[0], () -> context, "fixture", r -> {
				assertEquals("content://picker-fixture/kept", r.getData().getData().toString()); newCalls.incrementAndGet();
			});
			current[0].resume();
		});
		idle(); assertEquals(0, newCalls.get()); SecurityStore.unlock(context);
		main(() -> { current[0].event(Lifecycle.Event.ON_PAUSE); current[0].event(Lifecycle.Event.ON_RESUME); });
		idle(); assertEquals(0, oldCalls.get()); assertEquals(1, newCalls.get());
		main(() -> { current[0].event(Lifecycle.Event.ON_PAUSE); current[0].event(Lifecycle.Event.ON_RESUME); });
		idle(); assertEquals(1, newCalls.get()); main(current[0]::destroy);
	}
	@Test public void queuedPayloadCannotCrossAProfileGenerationChange() throws Exception {
		SecurityStore.setPin(context, "123456".toCharArray(), false);
		AtomicInteger calls = new AtomicInteger(); Owner[] owner = {null};
		main(() -> {
			owner[0] = new Owner(null);
			ProtectedActivityResults.Delivery<ActivityResult> delivery = new ProtectedActivityResults.Delivery<>(owner[0], owner[0], () -> context, "fixture", r -> calls.incrementAndGet());
			owner[0].resume(); delivery.receive(result("old-profile"));
		});
		SecurityStore.update(context, s -> { s.put("profile_generation", s.optLong("profile_generation") + 1); return null; });
		SecurityStore.unlock(context);
		main(() -> { owner[0].event(Lifecycle.Event.ON_PAUSE); owner[0].event(Lifecycle.Event.ON_RESUME); });
		idle(); assertEquals(0, calls.get()); main(owner[0]::destroy);
	}
	@Test public void unprotectedPickerKeepsItsImmediateDeliveryBeforeResume() {
		AtomicInteger calls = new AtomicInteger();
		main(() -> {
			Owner owner = new Owner(null);
			ProtectedActivityResults.Delivery<ActivityResult> delivery = new ProtectedActivityResults.Delivery<>(owner, owner, () -> context, "fixture", r -> calls.incrementAndGet());
			owner.event(Lifecycle.Event.ON_CREATE); owner.event(Lifecycle.Event.ON_START);
			delivery.receive(result("ordinary")); assertEquals(1, calls.get());
			owner.event(Lifecycle.Event.ON_STOP); owner.event(Lifecycle.Event.ON_DESTROY);
		});
	}
}
