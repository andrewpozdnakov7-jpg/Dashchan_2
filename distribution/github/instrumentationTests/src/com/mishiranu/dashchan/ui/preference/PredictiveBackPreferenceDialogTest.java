package com.mishiranu.dashchan.ui.preference;

import static org.junit.Assert.*;
import androidx.fragment.app.FragmentManager;
import androidx.test.core.app.ActivityScenario;
import com.mishiranu.dashchan.content.Preferences;
import com.mishiranu.dashchan.widget.StandardContentTestActivity;
import java.util.Map;
import org.junit.Test;

/** Prepared source-only fixtures: real dialog clicks/results and recreation; not executed. */
public class PredictiveBackPreferenceDialogTest {
	private static PredictiveBackPreferenceDialog find(FragmentManager manager, int mode) {
		return (PredictiveBackPreferenceDialog) manager.findFragmentByTag(
				PredictiveBackPreferenceDialog.class.getName() + "/" + mode);
	}

	@Test public void cancelAndLegacyChoiceDoNotApplySettingsBeforeFinalConfirmation() {
		try (ActivityScenario<StandardContentTestActivity> scenario = ActivityScenario.launch(StandardContentTestActivity.class)) {
			scenario.onActivity(activity -> {
				FragmentManager manager = activity.getSupportFragmentManager();
				Map<String, ?> before = Preferences.PREFERENCES.getAll();
				int[] choices = {0, 0};
				manager.setFragmentResultListener(PredictiveBackPreferenceDialog.RESULT, activity, (key, result) -> {
					choices[0]++; choices[1] = result.getInt(PredictiveBackPreferenceDialog.CHOICE);
				});
				PredictiveBackPreferenceDialog.show(manager, PredictiveBackPreferenceDialog.ENABLE_NEW_INTERFACE);
				manager.executePendingTransactions();
				find(manager, PredictiveBackPreferenceDialog.ENABLE_NEW_INTERFACE).requireDialog().cancel();
				manager.executePendingTransactions();
				assertEquals(0, choices[0]); assertEquals(before, Preferences.PREFERENCES.getAll());
				PredictiveBackPreferenceDialog.show(manager, PredictiveBackPreferenceDialog.ENABLE_NEW_INTERFACE);
				manager.executePendingTransactions();
				find(manager, PredictiveBackPreferenceDialog.ENABLE_NEW_INTERFACE).requireDialog()
						.findViewById(android.R.id.button3).performClick();
				manager.executePendingTransactions();
				assertEquals(0, choices[0]); assertEquals(before, Preferences.PREFERENCES.getAll());
				find(manager, PredictiveBackPreferenceDialog.ENABLE_OLD_INTERFACE).requireDialog()
						.findViewById(android.R.id.button2).performClick();
				manager.executePendingTransactions();
				assertEquals(0, choices[0]); assertEquals(before, Preferences.PREFERENCES.getAll());
				PredictiveBackPreferenceDialog.show(manager, PredictiveBackPreferenceDialog.ENABLE_NEW_INTERFACE);
				manager.executePendingTransactions();
				find(manager, PredictiveBackPreferenceDialog.ENABLE_NEW_INTERFACE).requireDialog()
						.findViewById(android.R.id.button3).performClick();
				manager.executePendingTransactions();
				find(manager, PredictiveBackPreferenceDialog.ENABLE_OLD_INTERFACE).requireDialog()
						.findViewById(android.R.id.button1).performClick();
				manager.executePendingTransactions();
				assertEquals(1, choices[0]);
				assertEquals(PredictiveBackPreferenceDialog.ENABLE_OLD_INTERFACE, choices[1]);
				// Dialogs send a choice; only their current parent is allowed to apply it.
				assertEquals(before, Preferences.PREFERENCES.getAll());
			});
		}
	}

	@Test public void legacyWarningSurvivesRecreationWithoutAcceptingTheChoice() {
		try (ActivityScenario<StandardContentTestActivity> scenario = ActivityScenario.launch(StandardContentTestActivity.class)) {
			Map<String, ?> before = Preferences.PREFERENCES.getAll();
			scenario.onActivity(activity -> {
				FragmentManager manager = activity.getSupportFragmentManager();
				PredictiveBackPreferenceDialog.show(manager, PredictiveBackPreferenceDialog.ENABLE_OLD_INTERFACE);
				manager.executePendingTransactions();
			});
			scenario.recreate();
			scenario.onActivity(activity -> {
				FragmentManager manager = activity.getSupportFragmentManager();
				assertTrue(find(manager, PredictiveBackPreferenceDialog.ENABLE_OLD_INTERFACE).requireDialog().isShowing());
				assertEquals(before, Preferences.PREFERENCES.getAll());
				int[] accepted = {0};
				manager.setFragmentResultListener(PredictiveBackPreferenceDialog.RESULT, activity,
						(key, result) -> accepted[0]++);
				assertEquals(0, accepted[0]);
				find(manager, PredictiveBackPreferenceDialog.ENABLE_OLD_INTERFACE).requireDialog()
						.findViewById(android.R.id.button1).performClick();
				manager.executePendingTransactions();
				assertEquals(1, accepted[0]);
			});
		}
	}

	@Test public void confirmationRemovesThePromptBeforeItsResultCanRecreateTheActivity() {
		try (ActivityScenario<StandardContentTestActivity> scenario = ActivityScenario.launch(StandardContentTestActivity.class)) {
			int[] accepted = {0};
			scenario.onActivity(activity -> {
				FragmentManager manager = activity.getSupportFragmentManager();
				manager.setFragmentResultListener(PredictiveBackPreferenceDialog.RESULT, activity, (key, result) -> {
					assertNull("A settings change must not save the old prompt", find(manager,
							PredictiveBackPreferenceDialog.ENABLE_NEW_INTERFACE));
					assertEquals(PredictiveBackPreferenceDialog.ENABLE_NEW_INTERFACE,
							result.getInt(PredictiveBackPreferenceDialog.CHOICE));
					accepted[0]++;
					activity.recreate();
				});
				PredictiveBackPreferenceDialog.show(manager, PredictiveBackPreferenceDialog.ENABLE_NEW_INTERFACE);
				manager.executePendingTransactions();
				find(manager, PredictiveBackPreferenceDialog.ENABLE_NEW_INTERFACE).requireDialog()
						.findViewById(android.R.id.button1).performClick();
				assertEquals(1, accepted[0]);
			});
			scenario.onActivity(activity -> assertNull(find(activity.getSupportFragmentManager(),
					PredictiveBackPreferenceDialog.ENABLE_NEW_INTERFACE)));
			assertEquals(1, accepted[0]);
		}
	}
}
