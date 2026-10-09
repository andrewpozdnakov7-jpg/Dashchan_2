package com.mishiranu.dashchan.ui.preference;

import static org.junit.Assert.*;
import android.content.Context;
import android.os.Bundle;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import com.mishiranu.dashchan.BuildConfig;
import com.mishiranu.dashchan.R;
import com.mishiranu.dashchan.content.Preferences;
import com.mishiranu.dashchan.ui.ContentFragment;
import com.mishiranu.dashchan.ui.posting.PhotoEditorBridge;
import java.util.List;
import org.junit.Test;
import org.junit.runner.RunWith;

/** Real resource/search/navigation checks; run the common test in both distribution variants. */
@RunWith(AndroidJUnit4.class)
public class ExperimentalPreferenceDescriptorsTest {
	@Test public void photoEditorDescriptionRespectsDistributionAndDefault() {
		ExperimentalPreferenceDescriptors.Toggle toggle = ExperimentalPreferenceDescriptors.photoEditor();
		assertEquals(Preferences.KEY_NEW_PHOTO_EDITOR, toggle.key);
		assertTrue(toggle.defaultValue);
		assertEquals(PhotoEditorBridge.isAvailable(), toggle.available);
		assertEquals(PhotoEditorBridge.getTitleResId(), toggle.titleResId);
		assertEquals(PhotoEditorBridge.getSummaryResId(), toggle.summaryResId);
	}

	@Test public void motionDescriptionUsesTheExistingDistributionGate() {
		ExperimentalPreferenceDescriptors.Toggle toggle = ExperimentalPreferenceDescriptors.interfaceMotion();
		assertEquals(Preferences.KEY_NEW_INTERFACE_MOTION, toggle.key);
		assertFalse(toggle.defaultValue);
		assertEquals(BuildConfig.ENABLE_EXPERIMENTAL_INTERFACE_MOTION, toggle.available);
	}

	@Test public void contextAndOutboxKeepTheirExistingDefaults() {
		ExperimentalPreferenceDescriptors.Toggle context = ExperimentalPreferenceDescriptors.discussionContext();
		ExperimentalPreferenceDescriptors.Toggle outbox = ExperimentalPreferenceDescriptors.outboxJournal();
		assertTrue(context.available);
		assertTrue(outbox.available);
		assertFalse(context.defaultValue);
		assertFalse(outbox.defaultValue);
	}

	private static boolean hasTargetKey(SettingsSearchIndex.Entry entry, String breadcrumb, String key) {
		if (!breadcrumb.equals(entry.getBreadcrumb())) return false;
		ContentFragment fragment = entry.createFragment();
		if (!(fragment instanceof ExperimentalFragment)) return false;
		Bundle arguments = fragment.getArguments();
		if (arguments == null) return false;
		for (String argument : arguments.keySet()) {
			if (key.equals(arguments.getString(argument))) return true;
		}
		return false;
	}

	@Test public void searchContainsExactlyTheAvailableExperimentalTargets() {
		InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
			Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
			String breadcrumb = context.getString(R.string.experimental_features);
			List<SettingsSearchIndex.Entry> entries = SettingsSearchIndex.create(context);
			for (ExperimentalPreferenceDescriptors.Toggle toggle : new ExperimentalPreferenceDescriptors.Toggle[] {
					ExperimentalPreferenceDescriptors.interfaceMotion(),
					ExperimentalPreferenceDescriptors.photoEditor(),
					ExperimentalPreferenceDescriptors.discussionContext(),
					ExperimentalPreferenceDescriptors.outboxJournal()}) {
				int count = 0;
				for (SettingsSearchIndex.Entry entry : entries) {
					if (hasTargetKey(entry, breadcrumb, toggle.key)) {
						count++;
						assertEquals(context.getString(toggle.titleResId), entry.getTitle());
						assertTrue(SettingsSearchIndex.search(entries, entry.getTitle()).contains(entry));
					}
				}
				assertEquals(toggle.key, toggle.available ? 1 : 0, count);
			}
		});
	}
}
