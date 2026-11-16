package com.mishiranu.dashchan.ui.preference;

import static org.junit.Assert.*;
import android.app.AlertDialog;
import android.widget.EditText;
import androidx.fragment.app.DialogFragment;
import androidx.test.core.app.ActivityScenario;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import com.mishiranu.dashchan.R;
import com.mishiranu.dashchan.content.storage.CombinedFeedStorage;
import com.mishiranu.dashchan.ui.InstanceDialog;
import com.mishiranu.dashchan.widget.StandardContentTestActivity;
import java.util.ArrayList;
import org.junit.Test;
import org.junit.runner.RunWith;

/** Values-only factory survives configuration recreation, without saving a feed. NOT RUN. */
@RunWith(AndroidJUnit4.class)
public class CombinedFeedDialogStateTest {
	private Boolean previousIme;
	@org.junit.Before public void enableNewIme() {
		com.mishiranu.dashchan.util.SharedPreferences p = com.mishiranu.dashchan.content.Preferences.PREFERENCES;
		previousIme = p.contains(com.mishiranu.dashchan.content.Preferences.KEY_DIALOG_FORM_IME)
				? p.getBoolean(com.mishiranu.dashchan.content.Preferences.KEY_DIALOG_FORM_IME, false) : null;
		try (com.mishiranu.dashchan.util.SharedPreferences.Editor e = p.edit()) { e.put(com.mishiranu.dashchan.content.Preferences.KEY_DIALOG_FORM_IME, true); }
	}
	@org.junit.After public void restoreIme() {
		try (com.mishiranu.dashchan.util.SharedPreferences.Editor e = com.mishiranu.dashchan.content.Preferences.PREFERENCES.edit()) {
			if (previousIme == null) e.remove(com.mishiranu.dashchan.content.Preferences.KEY_DIALOG_FORM_IME);
			else e.put(com.mishiranu.dashchan.content.Preferences.KEY_DIALOG_FORM_IME, previousIme);
		}
	}

	@Test public void rotationKeepsEditedValuesAndIdentityAndCancelDoesNotWriteStorage() {
		int count = CombinedFeedStorage.getInstance().getFeeds().size();
		String tag = CombinedFeedDialog.class.getName();
		CombinedFeedStorage.Feed original = new CombinedFeedStorage.Feed(); original.id = "fixture-feed"; original.title = "original";
		original.sources.add(new CombinedFeedStorage.Source("fixture", "a"));
		original.sources.add(new CombinedFeedStorage.Source("fixture", "b"));
		ArrayList<CombinedFeedsFragment.Board> boards = new ArrayList<>();
		boards.add(new CombinedFeedsFragment.Board("fixture", "Fixture", "a", "Synthetic alpha", false));
		boards.add(new CombinedFeedsFragment.Board("fixture", "Fixture", "b", "Synthetic beta", false));
		CombinedFeedDialog.State draft = new CombinedFeedDialog.State(original, boards, true);
		try (ActivityScenario<StandardContentTestActivity> scenario = ActivityScenario.launch(StandardContentTestActivity.class)) {
			scenario.onActivity(activity -> {
				new InstanceDialog(activity.getSupportFragmentManager(), tag, provider -> CombinedFeedDialog.create(provider, draft));
				activity.getSupportFragmentManager().executePendingTransactions();
				AlertDialog dialog = (AlertDialog) ((DialogFragment) activity.getSupportFragmentManager().findFragmentByTag(tag)).getDialog();
				EditText name = dialog.findViewById(R.id.combined_feed_dialog_name), search = dialog.findViewById(R.id.combined_feed_dialog_search);
				name.setText("unsaved title"); search.setText("no matching sections"); search.requestFocus(); search.setSelection(3, 8);
				draft.showSticky = false; draft.showBoard = false; draft.expandedGroups.clear();
			});
			scenario.recreate();
			scenario.onActivity(activity -> {
				AlertDialog dialog = (AlertDialog) ((DialogFragment) activity.getSupportFragmentManager().findFragmentByTag(tag)).getDialog();
				EditText name = dialog.findViewById(R.id.combined_feed_dialog_name), search = dialog.findViewById(R.id.combined_feed_dialog_search);
				assertEquals("unsaved title", name.getText().toString()); assertEquals("no matching sections", search.getText().toString());
				assertEquals("fixture-feed", draft.existing.id); assertEquals(2, draft.selected.size()); assertTrue(draft.expandedGroups.isEmpty());
				assertFalse(draft.showSticky); assertFalse(draft.showBoard); assertEquals("original", original.title);
				dialog.getButton(AlertDialog.BUTTON_NEGATIVE).performClick(); activity.getSupportFragmentManager().executePendingTransactions();
			});
		}
		assertEquals(count, CombinedFeedStorage.getInstance().getFeeds().size());
	}
}
