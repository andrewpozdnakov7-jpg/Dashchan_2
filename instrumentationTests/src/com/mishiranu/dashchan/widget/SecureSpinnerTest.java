package com.mishiranu.dashchan.widget;

import static org.junit.Assert.*;
import android.app.AlertDialog;
import android.content.Context;
import android.content.ContextWrapper;
import android.view.WindowManager;
import android.widget.ArrayAdapter;
import androidx.test.platform.app.InstrumentationRegistry;
import com.mishiranu.dashchan.BuildConfig;
import com.mishiranu.dashchan.content.security.SecurityStore;
import java.io.File;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

/** Actual selection-dialog creation path, without showing a live window or using personal categories. */
public class SecureSpinnerTest {
	private Context context;
	private File sandbox;
	@Before public void prepare() throws Exception {
		Context base = InstrumentationRegistry.getInstrumentation().getTargetContext();
		sandbox = new File(base.getCacheDir(), "secure-spinner-test-" + java.util.UUID.randomUUID()); assertTrue(sandbox.mkdir());
		Context isolated = new ContextWrapper(base) { @Override public File getNoBackupFilesDir() { return sandbox; } };
		context = new android.view.ContextThemeWrapper(isolated, android.R.style.Theme_Material_Light);
		if (BuildConfig.ENABLE_APP_SECURITY_SETTINGS) {
			SecurityStore.update(context, s -> { s.put("primary", new org.json.JSONObject()); return null; });
		}
	}
	@After public void cleanup() throws Exception {
		if (context != null && BuildConfig.ENABLE_APP_SECURITY_SETTINGS) SecurityStore.lock(context);
		com.mishiranu.dashchan.content.security.PrivacyResetFiles.delete(sandbox);
	}
	private void checkSelection(boolean secure) {
		InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
			SecureSpinner spinner = new SecureSpinner(context);
			ArrayAdapter<String> choices = new ArrayAdapter<>(context, android.R.layout.simple_spinner_item,
					new String[]{"fixture-category-A", "fixture-category-B"});
			choices.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
			spinner.setAdapter(choices); spinner.setSelection(1);
			AlertDialog dialog = spinner.createSelectionDialog(); assertNotNull(dialog);
			assertEquals(secure && BuildConfig.ENABLE_APP_SECURITY_SETTINGS,
					(dialog.getWindow().getAttributes().flags & WindowManager.LayoutParams.FLAG_SECURE) != 0);
			if (BuildConfig.ENABLE_APP_SECURITY_SETTINGS) {
				assertEquals("com.mishiranu.dashchan.content.security.SecurityWindowCallback", dialog.getWindow().getCallback().getClass().getName());
			} else {
				assertSame(dialog, dialog.getWindow().getCallback());
				assertFalse(new File(sandbox, "app-security-v1").exists());
			}
			assertEquals(1, spinner.getSelectedItemPosition());
			dialog.dismiss();
		});
	}
	@Test public void categorySelectionHasItsOwnSecureFlagBeforeShowing() { checkSelection(true); }
	@Test public void messageDialogKeepsTheRegisteredProtectedWindowBeforeShowing() {
		InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
			MessageDialog dialog = new MessageDialog.Builder(context).setMessage("Fixture message").create();
			assertEquals(BuildConfig.ENABLE_APP_SECURITY_SETTINGS,
					(dialog.getWindow().getAttributes().flags & WindowManager.LayoutParams.FLAG_SECURE) != 0);
			if (BuildConfig.ENABLE_APP_SECURITY_SETTINGS) {
				assertEquals("com.mishiranu.dashchan.content.security.SecurityWindowCallback", dialog.getWindow().getCallback().getClass().getName());
			}
			dialog.dismiss();
		});
	}
	@Test public void ordinaryUnlockedUnprotectedSelectionIsNotForcedSecure() throws Exception {
		if (BuildConfig.ENABLE_APP_SECURITY_SETTINGS) {
			SecurityStore.update(context, s -> { s.remove("primary"); return null; });
		}
		checkSelection(false);
	}
}
