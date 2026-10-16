package com.mishiranu.dashchan.widget;

import static org.junit.Assert.*;
import android.app.AlertDialog;
import android.content.Context;
import android.content.ContextWrapper;
import android.view.WindowManager;
import android.widget.ArrayAdapter;
import androidx.test.platform.app.InstrumentationRegistry;
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
		SecurityStore.update(context, s -> { s.put("primary", new org.json.JSONObject()); return null; });
	}
	@After public void cleanup() throws Exception {
		if (context != null) SecurityStore.lock(context);
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
			assertEquals(secure, (dialog.getWindow().getAttributes().flags & WindowManager.LayoutParams.FLAG_SECURE) != 0);
			assertEquals("com.mishiranu.dashchan.content.security.SecurityWindowCallback", dialog.getWindow().getCallback().getClass().getName());
			assertEquals(1, spinner.getSelectedItemPosition());
			dialog.dismiss();
		});
	}
	@Test public void categorySelectionHasItsOwnSecureFlagBeforeShowing() { checkSelection(true); }
	@Test public void ordinaryUnlockedUnprotectedSelectionIsNotForcedSecure() throws Exception {
		SecurityStore.update(context, s -> { s.remove("primary"); return null; }); checkSelection(false);
	}
}
