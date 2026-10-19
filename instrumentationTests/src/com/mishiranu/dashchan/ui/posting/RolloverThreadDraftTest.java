package com.mishiranu.dashchan.ui.posting;

import static org.junit.Assert.*;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import com.mishiranu.dashchan.content.storage.DraftsStorage;
import java.util.ArrayList;
import org.junit.Test;
import org.junit.runner.RunWith;

/** Detached payloads only: never reads/writes the installation's drafts or sends anything. */
@RunWith(AndroidJUnit4.class)
public class RolloverThreadDraftTest {
	@Test public void seedCreatesNewThreadAndPreservesMarkupWithoutAuthorIdentity() {
		ArrayList<DraftsStorage.AttachmentDraft> files = new ArrayList<>();
		DraftsStorage.PostDraft draft = RolloverThreadDialog.createDraft("test", "board", "Subject #12",
				"[b]Header[/b]\n>>123\nhttps://example.org/", files);
		assertEquals("test", draft.chanName); assertEquals("board", draft.boardName);
		assertNull(draft.threadNumber);
		assertEquals("Subject #12", draft.subject);
		assertEquals("[b]Header[/b]\n>>123\nhttps://example.org/", draft.comment);
		assertNull(draft.name); assertNull(draft.email); assertNull(draft.password); assertNull(draft.userIcon);
		assertFalse(draft.optionSage); assertFalse(draft.optionSpoiler); assertFalse(draft.optionOriginalPoster);
		assertEquals(0, draft.commentCarriage);
	}

	@Test public void attachmentsRemainOrderedAndTheListIsDetached() {
		ArrayList<DraftsStorage.AttachmentDraft> files = new ArrayList<>();
		files.add(new DraftsStorage.AttachmentDraft("a", "one.png", null, false, false, false, false, null));
		files.add(new DraftsStorage.AttachmentDraft("b", "two.jpg", null, false, false, false, false, null));
		DraftsStorage.PostDraft draft = RolloverThreadDialog.createDraft("test", "board", null, "text", files);
		files.clear();
		assertEquals(2, draft.attachmentDrafts.size());
		assertEquals("one.png", draft.attachmentDrafts.get(0).name);
		assertEquals("two.jpg", draft.attachmentDrafts.get(1).name);
	}

	@Test public void textOnlyPostDoesNotRequireAttachmentsOrAnInventedSubject() {
		DraftsStorage.PostDraft draft = RolloverThreadDialog.createDraft("test", "board", null,
				"text", new ArrayList<>());
		assertNull(draft.subject); assertTrue(draft.attachmentDrafts.isEmpty());
		assertEquals("text", draft.comment);
	}
}
