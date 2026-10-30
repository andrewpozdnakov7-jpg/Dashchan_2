package com.mishiranu.dashchan.ui.posting;

import static org.junit.Assert.*;
import android.app.Dialog;
import android.os.Bundle;
import androidx.annotation.NonNull;
import androidx.fragment.app.DialogFragment;
import androidx.fragment.app.Fragment;
import androidx.fragment.app.FragmentManager;
import androidx.test.core.app.ActivityScenario;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import org.junit.Test;
import org.junit.runner.RunWith;

/** Uses only detached fixture fragments, never real drafts or user pages. Prepared, not run. */
@RunWith(AndroidJUnit4.class)
public class RolloverHandoffTest {
	public static final class FixtureDialog extends DialogFragment {
		@NonNull @Override public Dialog onCreateDialog(Bundle savedInstanceState) {
			return new android.app.AlertDialog.Builder(requireContext()).setMessage("Fixture").create();
		}
	}

	@Test public void dialogIsRemovedBeforeNavigationSavesTheSourcePage() {
		try (ActivityScenario<RolloverHandoffTestActivity> scenario =
				ActivityScenario.launch(RolloverHandoffTestActivity.class)) {
			scenario.onActivity(activity -> {
				FragmentManager manager = activity.getSupportFragmentManager();
				Fragment page = new Fragment();
				manager.beginTransaction().add(page, "source_page").commitNow();
				FragmentManager children = page.getChildFragmentManager();
				FixtureDialog preparation = new FixtureDialog();
				preparation.showNow(children, "preparation");
				assertTrue(preparation.isAdded());
				int[] opened = {0};
				RolloverThreadDialog.dismissBeforeNavigation(preparation, () -> {
					assertFalse(preparation.isAdded());
					assertNull(children.findFragmentByTag("preparation"));
					// This is the same operation performed when the real page goes to the stack.
					Fragment.SavedState saved = manager.saveFragmentInstanceState(page);
					assertNotNull(saved);
					assertTrue(children.isStateSaved());
					Fragment restored = new Fragment(); restored.setInitialSavedState(saved);
					manager.beginTransaction().add(restored, "restored_page").commitNow();
					assertNull(restored.getChildFragmentManager().findFragmentByTag("preparation"));
					opened[0]++;
				});
				assertEquals(1, opened[0]);
			});
		}
	}
}
