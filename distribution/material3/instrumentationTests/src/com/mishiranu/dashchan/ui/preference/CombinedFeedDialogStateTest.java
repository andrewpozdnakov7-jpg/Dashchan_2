package com.mishiranu.dashchan.ui.preference;

import static org.junit.Assert.*;
import android.app.AlertDialog;
import android.view.View;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.TextView;
import androidx.core.view.OneShotPreDrawListener;
import androidx.fragment.app.DialogFragment;
import androidx.test.core.app.ActivityScenario;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import com.mishiranu.dashchan.R;
import com.mishiranu.dashchan.content.Preferences;
import com.mishiranu.dashchan.content.storage.CombinedFeedStorage;
import com.mishiranu.dashchan.ui.InstanceDialog;
import com.mishiranu.dashchan.util.SharedPreferences;
import com.mishiranu.dashchan.widget.StandardContentTestActivity;
import java.util.ArrayList;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.Test;
import org.junit.runner.RunWith;

/** Values-only factory survives configuration recreation, without saving a feed. NOT RUN. */
@RunWith(AndroidJUnit4.class)
public class CombinedFeedDialogStateTest {
	private Boolean previousIme;
	@org.junit.Before public void useDefaultImePreference() {
		SharedPreferences preferences = Preferences.PREFERENCES;
		previousIme = preferences.contains(Preferences.KEY_DIALOG_FORM_IME)
				? preferences.getBoolean(Preferences.KEY_DIALOG_FORM_IME, false) : null;
		preferences.edit().remove(Preferences.KEY_DIALOG_FORM_IME).close();
	}
	@org.junit.After public void restoreIme() {
		try (SharedPreferences.Editor editor = Preferences.PREFERENCES.edit()) {
			if (previousIme == null) editor.remove(Preferences.KEY_DIALOG_FORM_IME);
			else editor.put(Preferences.KEY_DIALOG_FORM_IME, previousIme);
		}
	}

	private static AlertDialog editor(StandardContentTestActivity activity) {
		DialogFragment fragment = (DialogFragment) activity.getSupportFragmentManager()
				.findFragmentByTag(CombinedFeedDialog.class.getName());
		assertNotNull(fragment);
		return (AlertDialog) fragment.requireDialog();
	}

	private static void awaitEditorDraw(ActivityScenario<StandardContentTestActivity> scenario) throws InterruptedException {
		CountDownLatch drawn = new CountDownLatch(1);
		scenario.onActivity(activity -> {
			View decor = editor(activity).getWindow().getDecorView();
			OneShotPreDrawListener.add(decor, () -> decor.post(drawn::countDown));
			decor.invalidate();
		});
		assertTrue("Dialog did not complete its first layout", drawn.await(5, TimeUnit.SECONDS));
	}

	private static TextView label(AlertDialog dialog, String text) {
		ArrayList<View> found = new ArrayList<>();
		dialog.getWindow().getDecorView().findViewsWithText(found, text, View.FIND_VIEWS_WITH_TEXT);
		for (View view : found) {
			if (view instanceof TextView && text.contentEquals(((TextView) view).getText())) return (TextView) view;
		}
		throw new AssertionError("Missing dialog row: " + text);
	}

	@Test public void recreateKeepsEditedValuesAndIdentityAndCancelDoesNotWriteStorage() throws InterruptedException {
		assertTrue(Preferences.isDialogFormImeEnabled());
		CombinedFeedStorage storage = CombinedFeedStorage.getInstance();
		int count = storage.getFeeds().size();
		CombinedFeedStorage.Feed original = new CombinedFeedStorage.Feed();
		original.id = UUID.randomUUID().toString();
		original.title = "original";
		original.sources.add(new CombinedFeedStorage.Source("fixture", "a"));
		original.sources.add(new CombinedFeedStorage.Source("fixture", "b"));
		ArrayList<CombinedFeedsFragment.Board> boards = new ArrayList<>();
		boards.add(new CombinedFeedsFragment.Board("fixture", "Fixture", "a", "Synthetic alpha", false));
		boards.add(new CombinedFeedsFragment.Board("fixture", "Fixture", "b", "Synthetic beta", false));
		boards.add(new CombinedFeedsFragment.Board("fixture", "Fixture", "c", "Synthetic gamma", false));
		CombinedFeedDialog.State draft = new CombinedFeedDialog.State(original, boards, true);
		try (ActivityScenario<StandardContentTestActivity> scenario = ActivityScenario.launch(StandardContentTestActivity.class)) {
			scenario.onActivity(activity -> {
				new InstanceDialog(activity.getSupportFragmentManager(), CombinedFeedDialog.class.getName(),
						provider -> CombinedFeedDialog.create(provider, draft));
				activity.getSupportFragmentManager().executePendingTransactions();
			});
			awaitEditorDraw(scenario);
			scenario.onActivity(activity -> {
				AlertDialog dialog = editor(activity);
				EditText name = dialog.findViewById(R.id.combined_feed_dialog_name);
				EditText search = dialog.findViewById(R.id.combined_feed_dialog_search);
				name.setText("unsaved title");
				label(dialog, dialog.getContext().getString(R.string.show_sticky_threads)).performClick();
				label(dialog, dialog.getContext().getString(R.string.combined_feed_show_board)).performClick();
				label(dialog, "Synthetic gamma").performClick();
				label(dialog, "\u25bc Fixture").performClick();
				search.setText("no matching sections");
				assertTrue(search.requestFocus());
				search.setSelection(3, 8);
			});
			scenario.recreate();
			awaitEditorDraw(scenario);
			scenario.onActivity(activity -> {
				AlertDialog dialog = editor(activity);
				EditText name = dialog.findViewById(R.id.combined_feed_dialog_name);
				EditText search = dialog.findViewById(R.id.combined_feed_dialog_search);
				assertEquals("unsaved title", name.getText().toString());
				assertEquals("no matching sections", search.getText().toString());
				assertTrue(search.hasFocus());
				assertEquals(3, search.getSelectionStart());
				assertEquals(8, search.getSelectionEnd());
				assertFalse(((CheckBox) label(dialog, dialog.getContext().getString(R.string.show_sticky_threads))).isChecked());
				assertFalse(((CheckBox) label(dialog, dialog.getContext().getString(R.string.combined_feed_show_board))).isChecked());
				assertEquals(original.id, draft.existing.id);
				search.setText("");
				label(dialog, "\u25b6 Fixture").performClick();
				assertTrue(((CheckBox) label(dialog, "Synthetic gamma")).isChecked());
				assertEquals(3, draft.selected.size());
				dialog.getButton(AlertDialog.BUTTON_NEGATIVE).performClick();
			});
		}
		assertEquals("original", original.title);
		assertNull(storage.getFeed(original.id));
		assertEquals(count, storage.getFeeds().size());
	}
}
