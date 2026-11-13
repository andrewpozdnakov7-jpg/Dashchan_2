package com.mishiranu.dashchan.ui.preference;

import static org.junit.Assert.*;
import android.app.AlertDialog;
import androidx.fragment.app.FragmentManager;
import androidx.test.core.app.ActivityScenario;
import androidx.test.platform.app.InstrumentationRegistry;
import com.mishiranu.dashchan.content.Preferences;
import com.mishiranu.dashchan.widget.StandardContentTestActivity;
import java.util.Map;
import org.junit.Test;

/** Real platform buttons dispatch asynchronously. Prepared, not executed in source-only mode. */
public class PredictiveBackPreferenceDialogTest {
	private static PredictiveBackPreferenceDialog find(FragmentManager manager, int mode) {
		return (PredictiveBackPreferenceDialog) manager.findFragmentByTag(PredictiveBackPreferenceDialog.class.getName() + "/" + mode);
	}
	private static void idle() { InstrumentationRegistry.getInstrumentation().waitForIdleSync(); }
	private static void show(ActivityScenario<StandardContentTestActivity> scenario, int mode) {
		scenario.onActivity(activity -> {
			FragmentManager manager = activity.getSupportFragmentManager(); PredictiveBackPreferenceDialog.show(manager, mode);
			manager.executePendingTransactions(); assertTrue(find(manager, mode).requireDialog() instanceof AlertDialog);
		}); idle();
	}
	private static void click(ActivityScenario<StandardContentTestActivity> scenario, int mode, int button) {
		scenario.onActivity(activity -> find(activity.getSupportFragmentManager(), mode).requireDialog().findViewById(button).performClick()); idle();
	}
	@Test public void cancelAndLegacyChoiceDoNotApplySettingsBeforeFinalConfirmation() {
		Map<String, ?> before = Preferences.PREFERENCES.getAll(); int[] choices = {0, 0};
		try (ActivityScenario<StandardContentTestActivity> scenario = ActivityScenario.launch(StandardContentTestActivity.class)) {
			scenario.onActivity(activity -> activity.getSupportFragmentManager().setFragmentResultListener(
					PredictiveBackPreferenceDialog.RESULT, activity, (key, result) -> {
						choices[0]++; choices[1] = result.getInt(PredictiveBackPreferenceDialog.CHOICE);
					}));
			show(scenario, PredictiveBackPreferenceDialog.ENABLE_NEW_INTERFACE);
			click(scenario, PredictiveBackPreferenceDialog.ENABLE_NEW_INTERFACE, android.R.id.button2);
			assertEquals(0, choices[0]); assertEquals(before, Preferences.PREFERENCES.getAll());
			show(scenario, PredictiveBackPreferenceDialog.ENABLE_NEW_INTERFACE);
			click(scenario, PredictiveBackPreferenceDialog.ENABLE_NEW_INTERFACE, android.R.id.button3);
			assertEquals(0, choices[0]); assertEquals(before, Preferences.PREFERENCES.getAll());
			click(scenario, PredictiveBackPreferenceDialog.ENABLE_OLD_INTERFACE, android.R.id.button2);
			assertEquals(0, choices[0]);
			show(scenario, PredictiveBackPreferenceDialog.ENABLE_NEW_INTERFACE);
			click(scenario, PredictiveBackPreferenceDialog.ENABLE_NEW_INTERFACE, android.R.id.button3);
			click(scenario, PredictiveBackPreferenceDialog.ENABLE_OLD_INTERFACE, android.R.id.button1);
			assertEquals(1, choices[0]); assertEquals(PredictiveBackPreferenceDialog.ENABLE_OLD_INTERFACE, choices[1]);
			assertEquals(before, Preferences.PREFERENCES.getAll());
		}
	}
	@Test public void pendingWarningSurvivesRecreationWithoutApplyingPreferences() {
		Map<String, ?> before = Preferences.PREFERENCES.getAll();
		try (ActivityScenario<StandardContentTestActivity> scenario = ActivityScenario.launch(StandardContentTestActivity.class)) {
			show(scenario, PredictiveBackPreferenceDialog.ENABLE_NEW_INTERFACE);
			click(scenario, PredictiveBackPreferenceDialog.ENABLE_NEW_INTERFACE, android.R.id.button3);
			scenario.recreate(); idle(); int[] accepted = {0};
			scenario.onActivity(activity -> {
				FragmentManager manager = activity.getSupportFragmentManager();
				assertNull(find(manager, PredictiveBackPreferenceDialog.ENABLE_NEW_INTERFACE));
				assertTrue(find(manager, PredictiveBackPreferenceDialog.ENABLE_OLD_INTERFACE).requireDialog().isShowing());
				manager.setFragmentResultListener(PredictiveBackPreferenceDialog.RESULT, activity, (key, result) -> accepted[0]++);
			});
			assertEquals(0, accepted[0]);
			click(scenario, PredictiveBackPreferenceDialog.ENABLE_OLD_INTERFACE, android.R.id.button1);
			assertEquals(1, accepted[0]); assertEquals(before, Preferences.PREFERENCES.getAll());
		}
	}
	@Test public void confirmationRemovesPromptBeforeResultCanRecreateActivity() {
		int[] accepted = {0};
		try (ActivityScenario<StandardContentTestActivity> scenario = ActivityScenario.launch(StandardContentTestActivity.class)) {
			scenario.onActivity(activity -> {
				FragmentManager manager = activity.getSupportFragmentManager();
				manager.setFragmentResultListener(PredictiveBackPreferenceDialog.RESULT, activity, (key, result) -> {
					assertNull(find(manager, PredictiveBackPreferenceDialog.ENABLE_NEW_INTERFACE));
					assertEquals(PredictiveBackPreferenceDialog.ENABLE_NEW_INTERFACE, result.getInt(PredictiveBackPreferenceDialog.CHOICE));
					accepted[0]++; activity.recreate();
				});
			});
			show(scenario, PredictiveBackPreferenceDialog.ENABLE_NEW_INTERFACE);
			click(scenario, PredictiveBackPreferenceDialog.ENABLE_NEW_INTERFACE, android.R.id.button1);
			assertEquals(1, accepted[0]);
			scenario.onActivity(activity -> assertNull(find(activity.getSupportFragmentManager(), PredictiveBackPreferenceDialog.ENABLE_NEW_INTERFACE)));
		}
	}
}
