package com.mishiranu.dashchan.content.security;

import static org.junit.Assert.*;
import android.app.AlertDialog;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.res.Configuration;
import android.text.method.PasswordTransformationMethod;
import android.view.ContextThemeWrapper;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import androidx.test.core.app.ActivityScenario;
import androidx.test.platform.app.InstrumentationRegistry;
import com.mishiranu.dashchan.R;
import com.mishiranu.dashchan.widget.StandardContentTestActivity;
import org.junit.Test;

/** Presentation only: never launches PrivacyReset/AppLock or invokes cleanup/SecurityStore. ADDED / NOT RUN. */
public class SecurityPresentationTest {
    @Test public void neutralResourceOnlyRecoveryFixtureWrapsControlsAndPreservesMaskedInputWithoutCallback() {
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
            Context target = InstrumentationRegistry.getInstrumentation().getTargetContext();
            Configuration config = new Configuration(target.getResources().getConfiguration()); config.fontScale = 2;
            Context context = new ContextThemeWrapper(target.createConfigurationContext(config), android.R.style.Theme_DeviceDefault_NoActionBar) {
                @Override public SharedPreferences getSharedPreferences(String name, int mode) { throw new AssertionError("No ordinary preferences"); }
                @Override public java.io.File getDatabasePath(String name) { throw new AssertionError("No database"); }
                @Override public java.io.File getFilesDir() { throw new AssertionError("No profile files"); }
                @Override public java.io.File getCacheDir() { throw new AssertionError("No cache"); }
            };
            LinearLayout body = new LinearLayout(context); body.setOrientation(LinearLayout.VERTICAL);
            ScrollView root = new ScrollView(context); root.addView(body); SecurityPresentation.page(root);
            TextView message = new TextView(context); message.setText(R.string.security_downloads_failed);
            SecurityPresentation.message(message); body.addView(message);
            EditText pin = new EditText(context); AppLockActivity.configurePinInput(pin); pin.setText("123456");
            SecurityPresentation.field(pin); SecurityPresentation.addControl(body, pin);
            Button action = new Button(context); action.setText(R.string.security_skip_downloads);
            int[] callbacks = {0}; action.setOnClickListener(v -> callbacks[0]++);
            SecurityPresentation.action(action, false); SecurityPresentation.addControl(body, action);
            SecurityPresentation.action(action, false); SecurityPresentation.field(pin);
            int width = Math.round(320 * context.getResources().getDisplayMetrics().density);
            int height = Math.round(180 * context.getResources().getDisplayMetrics().density);
            root.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY));
            root.layout(0, 0, width, height);
            assertEquals(0, callbacks[0]); assertEquals("123456", pin.getText().toString());
            assertTrue(pin.getTransformationMethod() instanceof PasswordTransformationMethod);
            assertFalse(pin.isSaveEnabled()); assertTrue(pin.getFilterTouchesWhenObscured());
            root.scrollTo(0, Math.max(0, body.getHeight() - root.getHeight()));
            assertTrue(action.getBottom() - root.getScrollY() <= root.getHeight());
            assertTrue(action.getTop() - root.getScrollY() >= 0);
            assertTrue(action.getHeight() >= Math.round(48 * context.getResources().getDisplayMetrics().density));
        });
    }
    @Test public void nativeChoiceDialogPresentationAndCancellationNeverConfirmOrChangeTheSelectedMode() {
        try (ActivityScenario<StandardContentTestActivity> host = ActivityScenario.launch(StandardContentTestActivity.class)) {
            int[] choices = {0}, confirms = {0}, cancellations = {0}; AlertDialog[] dialog = {null};
            host.onActivity(activity -> {
                Context context = new ContextThemeWrapper(activity, R.style.Theme_Security_Presentation_Dialog);
                dialog[0] = new AlertDialog.Builder(context).setCustomTitle(SecurityPresentation.title(context, "Fixture"))
                        .setSingleChoiceItems(new String[]{"First long mode description", "Selected mode", "Third mode"}, 1,
                                (d, index) -> choices[0]++)
                        .setPositiveButton(android.R.string.ok, (d, which) -> confirms[0]++)
                        .setNegativeButton(android.R.string.cancel, null)
                        .setOnCancelListener(d -> cancellations[0]++).create();
                SecurityPresentation.dialog(dialog[0]); dialog[0].show();
            });
            InstrumentationRegistry.getInstrumentation().waitForIdleSync();
            host.onActivity(activity -> {
                assertEquals(1, dialog[0].getListView().getCheckedItemPosition());
                assertEquals(0, choices[0]); assertEquals(0, confirms[0]); assertEquals(0, cancellations[0]);
                dialog[0].getListView().getAdapter().getView(0, null, dialog[0].getListView());
                assertEquals(0, choices[0]); assertEquals(0, confirms[0]);
                dialog[0].cancel(); assertEquals(1, cancellations[0]); assertEquals(0, confirms[0]);
            });
        }
    }
}
