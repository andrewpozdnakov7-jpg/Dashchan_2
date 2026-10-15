package com.mishiranu.dashchan.content.security;

import static org.junit.Assert.*;
import android.app.Activity;
import android.content.Intent;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import org.junit.Test;
import org.junit.runner.RunWith;

/** Exercises the real component substitutions, without altering production state or launching cleanup. */
@RunWith(AndroidJUnit4.class)
public class SecurityComponentFactoryTest {
	public static class OrdinaryActivity extends Activity {}
	@Test public void installedManifestNamesTheActualFactoryClass() throws Exception {
		android.content.Context context = androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().getTargetContext();
		String factory = context.getPackageManager().getApplicationInfo(context.getPackageName(), 0).appComponentFactory;
		assertEquals(SecurityComponentFactory.class.getName(), factory);
		assertSame(SecurityComponentFactory.class, context.getClassLoader().loadClass(factory));
	}
	@Test public void pendingRecoveryNeverInstantiatesOldActivityProviderServiceOrReceiver() throws Exception {
		androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
			try {
				SecurityComponentFactory factory = new SecurityComponentFactory(() -> true);
				ClassLoader loader = getClass().getClassLoader();
				String missing = "old.profile.ComponentThatMustNotBeLoaded";
				assertTrue(factory.instantiateActivity(loader, missing, new Intent()) instanceof SecurityComponentFactory.RecoveryRedirectActivity);
				assertTrue(factory.instantiateProvider(loader, missing) instanceof SecurityComponentFactory.RecoveryProvider);
				assertTrue(factory.instantiateService(loader, missing, new Intent()) instanceof SecurityComponentFactory.RecoveryService);
				assertTrue(factory.instantiateReceiver(loader, missing, new Intent()) instanceof SecurityComponentFactory.RecoveryReceiver);
			} catch (Exception e) { throw new AssertionError(e); }
		});
	}
	@Test public void normalProfileRetainsThePlatformInstantiationPath() throws Exception {
		androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
			try {
				SecurityComponentFactory factory = new SecurityComponentFactory(() -> false);
				assertTrue(factory.instantiateActivity(getClass().getClassLoader(), OrdinaryActivity.class.getName(), new Intent()) instanceof OrdinaryActivity);
			} catch (Exception e) { throw new AssertionError(e); }
		});
	}
}
