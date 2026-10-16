package com.mishiranu.dashchan.content.security;

import static org.junit.Assert.*;
import android.app.Dialog;
import android.app.Notification;
import android.content.Context;
import android.content.ContextWrapper;
import android.graphics.Bitmap;
import android.os.Bundle;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;
import androidx.core.app.NotificationCompat;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import java.io.File;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

/** Synthetic records/notifications only; never enables protection or deletes data in the real profile. */
@RunWith(AndroidJUnit4.class)
public class SecurityWindowPrivacyTest {
	private File sandbox;
	private Context context;
	@Before public void prepare() throws Exception {
		Context base = InstrumentationRegistry.getInstrumentation().getTargetContext();
		sandbox = new File(base.getCacheDir(), "window-security-test-" + java.util.UUID.randomUUID());
		assertTrue(sandbox.mkdirs());
		context = new ContextWrapper(base) { @Override public File getNoBackupFilesDir() { return sandbox; } };
		SecurityStore.update(context, s -> { s.put("primary", new org.json.JSONObject()); return null; });
	}
	@After public void cleanup() throws Exception {
		if (context != null) SecuritySessionProvider.revoke(context);
		PrivacyResetFiles.delete(sandbox);
	}
	private void checkPopupDelegation(boolean protectedProfile) {
		checkPopupDelegation(protectedProfile, protectedProfile);
	}
	private void checkPopupDelegation(boolean protectedProfile, boolean captureBlocked) {
		InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
			java.util.concurrent.atomic.AtomicInteger adds = new java.util.concurrent.atomic.AtomicInteger();
			WindowManager delegate = (WindowManager) java.lang.reflect.Proxy.newProxyInstance(getClass().getClassLoader(),
					new Class<?>[]{WindowManager.class}, (proxy, method, args) -> {
				if ("addView".equals(method.getName()) || "updateViewLayout".equals(method.getName())) {
					WindowManager.LayoutParams params = (WindowManager.LayoutParams) args[1];
					assertEquals(captureBlocked, (params.flags & WindowManager.LayoutParams.FLAG_SECURE) != 0);
					adds.incrementAndGet();
				}
				return null;
			});
			View popup = new View(context);
			WindowManager manager = SecurePopupContext.protect(context, delegate);
			try {
				manager.addView(popup, new WindowManager.LayoutParams());
				manager.updateViewLayout(popup, new WindowManager.LayoutParams());
				assertEquals(2, adds.get());
				assertEquals(protectedProfile ? View.INVISIBLE : View.VISIBLE, popup.getVisibility());
			} finally { manager.removeViewImmediate(popup); }
		});
	}
	@Test public void popupSubwindowIsSecureBeforeAddAndCannotLoseTheFlagOnUpdate() { checkPopupDelegation(true); }
	@Test public void designCaptureOptInRemovesCaptureFlagButDoesNotExposeLockedPopup() throws Exception {
		SecurityStore.update(context, s -> { s.put(SecurityStore.ALLOW_SCREENSHOTS, true); return null; });
		checkPopupDelegation(true, !SecurityStore.designCaptureAllowed(context));
	}
	@Test public void unlockedOptInMakesOrdinaryDialogCapturableButKeepsItsSecurityGate() throws Exception {
		SecurityStore.foreground(context, true);
		SecurityStore.unlock(context);
		SecurityStore.setScreenshotsAllowed(context, true, () -> true);
		InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
			Context themed = new android.view.ContextThemeWrapper(context, android.R.style.Theme_Material_Dialog_Alert);
			Dialog dialog = new SecureDialogBuilder(themed).create();
			assertEquals(0, dialog.getWindow().getAttributes().flags & WindowManager.LayoutParams.FLAG_SECURE);
			assertTrue(dialog.getWindow().getCallback() instanceof SecurityWindowCallback);
		});
		assertTrue(AppSecurity.isProtected(context));
	}
	@Test public void popupUpdateRemovesOnlyTheAppCaptureFlagAndKeepsExplicitSecureWindows() throws Exception {
		SecurityStore.foreground(context, true);
		SecurityStore.unlock(context);
		InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
			for (boolean originallySecure : new boolean[]{false, true}) {
				java.util.concurrent.atomic.AtomicBoolean expectSecure = new java.util.concurrent.atomic.AtomicBoolean(true);
				WindowManager delegate = (WindowManager) java.lang.reflect.Proxy.newProxyInstance(getClass().getClassLoader(),
						new Class<?>[]{WindowManager.class}, (proxy, method, args) -> {
					if ("addView".equals(method.getName()) || "updateViewLayout".equals(method.getName())) {
						WindowManager.LayoutParams params = (WindowManager.LayoutParams) args[1];
						assertEquals(expectSecure.get(), (params.flags & WindowManager.LayoutParams.FLAG_SECURE) != 0);
					}
					return null;
				});
				View view = new View(context);
				WindowManager manager = SecurePopupContext.protect(context, delegate);
				WindowManager.LayoutParams params = new WindowManager.LayoutParams();
				if (originallySecure) params.flags |= WindowManager.LayoutParams.FLAG_SECURE;
				try {
					SecurityStore.setScreenshotsAllowed(context, false, () -> true);
					manager.addView(view, params);
					SecurityStore.setScreenshotsAllowed(context, true, () -> true);
					expectSecure.set(originallySecure && !SecurityStore.designCaptureAllowed(context));
					manager.updateViewLayout(view, params);
					assertEquals(View.VISIBLE, view.getVisibility());
					SecurityStore.setScreenshotsAllowed(context, false, () -> true);
					expectSecure.set(true);
					manager.updateViewLayout(view, params);
				} catch (java.io.IOException e) { throw new AssertionError(e); }
				finally { manager.removeViewImmediate(view); }
			}
		});
	}
	@Test public void popupWithoutAppLockKeepsItsOrdinaryWindowAndVisibility() throws Exception {
		SecurityStore.update(context, s -> { s.remove("primary"); return null; });
		checkPopupDelegation(false);
	}
	@Test public void sharedPinInputMasksDigitsAndDisablesPersistenceAndAutofill() {
		InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
			android.widget.EditText input = new android.widget.EditText(context);
			AppLockActivity.configurePinInput(input);
			input.setText("123456");
			assertTrue(input.getTransformationMethod() instanceof android.text.method.PasswordTransformationMethod);
			assertTrue(input.createAccessibilityNodeInfo().isPassword());
			assertFalse(input.isSaveEnabled());
			input.setId(123);
			android.util.SparseArray<android.os.Parcelable> saved = new android.util.SparseArray<>();
			input.saveHierarchyState(saved);
			assertEquals(0, saved.size()); // EditText.getFreezesText() is always true; test the actual save barrier.
			assertFalse(input.isLongClickable()); assertTrue(input.getFilterTouchesWhenObscured());
			assertEquals(View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS, input.getImportantForAutofill());
		});
	}
	@Test public void bothPlatformDialogTypesAreSecureBeforeAnyContentIsShown() {
		InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
			Context themed = new android.view.ContextThemeWrapper(context, android.R.style.Theme_Material_Dialog_Alert);
			Dialog dialog = new SecureDialog(themed);
			assertTrue((dialog.getWindow().getAttributes().flags & WindowManager.LayoutParams.FLAG_SECURE) != 0);
			Dialog alert = new SecureDialogBuilder(themed).create();
			assertTrue((alert.getWindow().getAttributes().flags & WindowManager.LayoutParams.FLAG_SECURE) != 0);
			assertTrue(alert.getWindow().getCallback() instanceof SecurityWindowCallback);
		});
	}
	@Test public void allWindowDesignCaptureAlsoAppliesToDialogsWithoutUnlockingTheProfile() throws Exception {
		SecurityStore.update(context, s -> { s.put(SecurityStore.ALLOW_SCREENSHOTS, true); return null; });
		boolean captureBlocked = !SecurityStore.designCaptureAllowed(context);
		InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
			Context themed = new android.view.ContextThemeWrapper(context, android.R.style.Theme_Material_Dialog_Alert);
			Dialog dialog = new SecureDialogBuilder(themed).create();
			assertEquals(captureBlocked, (dialog.getWindow().getAttributes().flags & WindowManager.LayoutParams.FLAG_SECURE) != 0);
			assertTrue(dialog.getWindow().getCallback() instanceof SecurityWindowCallback);
		});
		assertTrue(SecurityStore.locked(context));
		assertFalse(SecuritySessionProvider.isUnlocked(context));
	}
	@Test public void redactionRemovesTextThumbnailExtrasAndPublicCopy() {
		Bitmap image = Bitmap.createBitmap(2, 2, Bitmap.Config.ARGB_8888);
		try {
			Bundle extras = new Bundle(); extras.putString("fixture_secret", "fixture-private-value");
			Notification original = new NotificationCompat.Builder(context, "privacy-fixture")
					.setSmallIcon(android.R.drawable.stat_sys_download).setContentTitle("fixture-private-file")
					.setContentText("fixture-private-message").setLargeIcon(image).addExtras(extras)
					.setStyle(new NotificationCompat.BigPictureStyle().bigPicture(image))
					.setPublicVersion(new NotificationCompat.Builder(context, "privacy-fixture")
							.setSmallIcon(android.R.drawable.stat_sys_download).setContentText("fixture-private-public-copy").build()).build();
			Notification hidden = AppSecurity.redactNotification(context, original);
			assertEquals("Slooop", hidden.extras.getCharSequence(Notification.EXTRA_TITLE));
			assertEquals(context.getString(com.mishiranu.dashchan.R.string.security_notification_hidden),
					hidden.extras.getCharSequence(Notification.EXTRA_TEXT));
			assertFalse(hidden.extras.containsKey("fixture_secret"));
			assertNull(hidden.getLargeIcon()); assertNull(hidden.publicVersion);
		} finally { image.recycle(); }
	}
	@Test public void disabledWindowGateDelegatesWithoutTouchingAnyContextOrStorage() {
		AtomicInteger calls = new AtomicInteger();
		Window.Callback callback = (Window.Callback) java.lang.reflect.Proxy.newProxyInstance(getClass().getClassLoader(),
				new Class<?>[]{Window.Callback.class}, (proxy, method, args) -> {
			calls.incrementAndGet(); return method.getReturnType() == boolean.class ? true : null;
		});
		SecurityWindowCallback gate = new SecurityWindowCallback(null, callback, false);
		assertTrue(gate.dispatchTouchEvent(null)); assertTrue(gate.dispatchKeyEvent(null)); assertEquals(2, calls.get());
	}
}
