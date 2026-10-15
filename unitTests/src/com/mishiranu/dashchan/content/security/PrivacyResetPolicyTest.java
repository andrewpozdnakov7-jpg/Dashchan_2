package com.mishiranu.dashchan.content.security;

import static org.junit.Assert.*;
import org.junit.Test;

public class PrivacyResetPolicyTest {
	@Test public void localTreeCannotBeAVolumeRootOrContainTraversalSegments() {
		for (String id : new String[]{"primary:", "primary:.", "primary:..", "primary:Download/..",
				"primary:/Download", "primary:Download//nested", "primary:Download\\.."}) assertFalse(id, PrivacyResetPolicy.isSafeLocalTreeId(id));
		assertTrue(PrivacyResetPolicy.isSafeLocalTreeId("primary:Download/Slooop"));
		assertTrue(PrivacyResetPolicy.isSafeLocalTreeId("ABCD-1234:Pictures/.hidden"));
	}
	@Test public void downloadCleanupIsOptInAndNeverRetargetsNewOrUnavailableFolder() {
		assertNull(PrivacyResetPolicy.approvedDownloadTarget(null, "tree-A"));
		assertEquals("tree-A", PrivacyResetPolicy.approvedDownloadTarget("tree-A", "tree-B"));
		assertEquals("tree-A", PrivacyResetPolicy.approvedDownloadTarget("tree-A", null));
		assertEquals("tree-A", PrivacyResetPolicy.approvedDownloadTarget("tree-A", "tree-A"));
	}
	@Test public void appearanceSurvivesButPersonalValuesNeverDo() {
		for (String key : new String[]{"theme", "application_font", "text_scale", "experimental_new_posting_form",
				"posting_hide_email", "video_volume_gesture", "rounded_dialogs_radius"}) assertTrue(key, PrivacyResetPolicy.keepPreference(key));
		for (String key : new String[]{"dvach_proxy", "dvach_password", "dvach_domain", "dvach_user_authorization",
				"saved_last_video_position", "reply_push_installation_id", "captcha_solving", "reddit_communities",
				"my_boards", "settings_search_history", "some_future_account_key"}) assertFalse(key, PrivacyResetPolicy.keepPreference(key));
	}
	@Test public void onlyThemesSurviveStorageCleanupIncludingRecoveryCopies() {
		for (String key : new String[]{"themes.json", "themes.backup.json", "themes.restore.json"}) assertTrue(PrivacyResetPolicy.keepStorageFile(key));
		for (String key : new String[]{"my-posts.json", "my-posts.backup.json", "drafts.restore.json", "favorites.json", "future.json"}) {
			assertFalse(PrivacyResetPolicy.keepStorageFile(key));
		}
	}
}
