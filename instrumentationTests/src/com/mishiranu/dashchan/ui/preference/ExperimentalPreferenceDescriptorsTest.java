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
	@Test public void videoThumbnailsAreAvailableAndDefaultOn() {
		ExperimentalPreferenceDescriptors.Toggle toggle = ExperimentalPreferenceDescriptors.videoThumbnails();
		assertTrue(toggle.available); assertTrue(toggle.defaultValue);
		assertEquals(Preferences.KEY_UPDATE_VIDEO_THUMBNAILS, toggle.key);
		assertEquals(R.string.update_video_thumbnails, toggle.titleResId);
		assertEquals(R.string.update_video_thumbnails_summary, toggle.summaryResId);
	}

	@Test public void motionDescriptionUsesTheExistingDistributionGate() {
		ExperimentalPreferenceDescriptors.Toggle toggle = ExperimentalPreferenceDescriptors.interfaceMotion();
		assertEquals(Preferences.KEY_NEW_INTERFACE_MOTION, toggle.key);
		assertTrue(toggle.defaultValue);
		assertEquals(BuildConfig.ENABLE_EXPERIMENTAL_INTERFACE_MOTION, toggle.available);
	}

	@Test public void retiredExperimentsAreDisabled() {
		assertFalse(Preferences.isDiscussionContextEnabled());
		assertFalse(Preferences.isOutboxJournalEnabled());
		assertFalse(Preferences.isWindowedThreadLoadingEnabled());
		assertEquals("normal", Preferences.getPipDiagnosticMode());
	}

	private static boolean hasTargetKey(SettingsSearchIndex.Entry entry, String breadcrumb, String key) {
		if (!breadcrumb.equals(entry.getBreadcrumb())) return false;
		ContentFragment fragment = entry.createFragment();
		if (Preferences.KEY_NEW_INTERFACE_MOTION.equals(key) && !(fragment instanceof Material3DesignFragment)) return false;
		if (Preferences.KEY_NEW_PHOTO_EDITOR.equals(key) && !(fragment instanceof ImageAttachmentsFragment)) return false;
		if (Preferences.KEY_UPDATE_VIDEO_THUMBNAILS.equals(key) && !(fragment instanceof GallerySettingsFragment)) return false;
		Bundle arguments = fragment.getArguments();
		if (arguments == null) return false;
		for (String argument : arguments.keySet()) {
			if (key.equals(arguments.getString(argument))) return true;
		}
		return false;
	}

	@Test public void searchTargetsMovedMediaSettingsAndKeepsDistributionAvailability() {
		InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
			Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
			String breadcrumb = context.getString(R.string.experimental_features);
			List<SettingsSearchIndex.Entry> entries = SettingsSearchIndex.create(context);
			for (ExperimentalPreferenceDescriptors.Toggle toggle : new ExperimentalPreferenceDescriptors.Toggle[] {
					ExperimentalPreferenceDescriptors.interfaceMotion(),
					ExperimentalPreferenceDescriptors.photoEditor(),
					ExperimentalPreferenceDescriptors.videoThumbnails()}) {
				int count = 0;
				boolean design = Preferences.KEY_NEW_INTERFACE_MOTION.equals(toggle.key);
				String targetBreadcrumb = design ? breadcrumb + " \u203a " + context.getString(R.string.material3_design_settings)
						: context.getString(R.string.media) + " \u203a " + context.getString(
								Preferences.KEY_NEW_PHOTO_EDITOR.equals(toggle.key) ? R.string.media_images_attachments : R.string.gallery);
				for (SettingsSearchIndex.Entry entry : entries) {
					if (hasTargetKey(entry, targetBreadcrumb, toggle.key)) {
						count++;
						assertEquals(context.getString(design ? R.string.material3_enable_design : toggle.titleResId), entry.getTitle());
						assertTrue(SettingsSearchIndex.search(entries, entry.getTitle()).contains(entry));
					}
				}
				assertEquals(toggle.key, toggle.available ? 1 : 0, count);
			}
		});
	}
	@Test public void searchOmitsRetiredExperimentsAndRoutesDiagnosticsUnderAbout() {
		InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
			Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
			List<SettingsSearchIndex.Entry> entries = SettingsSearchIndex.create(context);
			int diagnostics = 0, reports = 0;
			String path = context.getString(R.string.about) + " \u203a " + context.getString(R.string.settings_diagnostics);
			for (SettingsSearchIndex.Entry entry : entries) {
				for (int retired : new int[] {R.string.discussion_context, R.string.outbox_title,
						R.string.pip_diagnostic_mode, R.string.windowed_thread_loading}) {
					assertNotEquals(context.getString(retired), entry.getTitle());
				}
				if (entry.getTitle().equals(context.getString(R.string.crash_report_prompt_enabled))) {
					assertEquals(path, entry.getBreadcrumb()); assertTrue(entry.createFragment() instanceof DiagnosticsFragment);
					diagnostics++;
				}
				if (entry.getTitle().equals(context.getString(R.string.crash_reports_saved))) {
					assertTrue(entry.getBreadcrumb().startsWith(path)); assertTrue(entry.createFragment() instanceof CrashReportsFragment);
					reports++;
				}
			}
			assertEquals(1, diagnostics); assertEquals(1, reports);
		});
	}
}
