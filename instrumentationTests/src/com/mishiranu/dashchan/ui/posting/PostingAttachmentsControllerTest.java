package com.mishiranu.dashchan.ui.posting;

import static org.junit.Assert.*;
import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.util.Pair;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import androidx.fragment.app.FragmentManager;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import chan.content.ChanConfiguration;
import chan.content.ChanPerformer;
import com.mishiranu.dashchan.C;
import com.mishiranu.dashchan.content.model.FileHolder;
import com.mishiranu.dashchan.content.storage.DraftsStorage;
import com.mishiranu.dashchan.util.GraphicsUtils;
import com.mishiranu.dashchan.widget.ThemeEngine;
import java.io.File;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Locale;
import java.util.function.Function;
import org.junit.Test;
import org.junit.runner.RunWith;

/** Real controller/View contracts on the main thread. No network, sending or draft writes. */
@RunWith(AndroidJUnit4.class)
public class PostingAttachmentsControllerTest {
	private static DraftsStorage.AttachmentDraft draft(int id, String rating, int flags,
			GraphicsUtils.Reencoding reencoding) {
		return new DraftsStorage.AttachmentDraft(String.format(Locale.US, "%064x", id), "fixture-" + id + ".txt",
				rating, (flags & 1) != 0, (flags & 2) != 0, (flags & 4) != 0, (flags & 8) != 0, reencoding);
	}

	private static final class Fixture implements PostingAttachmentsController.Host {
		final Context context;
		final PostingAttachmentsController controller;
		final ChanConfiguration.Posting posting = new ChanConfiguration.Posting();
		final LinearLayout container;
		int saves;
		int staleResults;

		Fixture(int columns, Function<String, FileHolder> resolver) {
			context = ThemeEngine.attach(InstrumentationRegistry.getInstrumentation().getTargetContext());
			ThemeEngine.applyTheme(context);
			controller = new PostingAttachmentsController(resolver);
			posting.attachmentCount = 20;
			posting.attachmentSpoiler = true;
			controller.updateConfiguration(posting, false, false);
			container = new LinearLayout(context);
			container.setOrientation(LinearLayout.VERTICAL);
			controller.attachView(this, container, new ScrollView(context), new Button(context), columns);
		}

		void restore(DraftsStorage.AttachmentDraft... drafts) {
			controller.restoreAttachmentDrafts(new ArrayList<>(Arrays.asList(drafts)));
		}

		@Override public Context requireContext() { return context; }
		@Override public FragmentManager getChildFragmentManager() { throw new AssertionError("Unexpected dialog"); }
		@Override public FragmentManager getParentFragmentManager() { throw new AssertionError("Unexpected preview"); }
		@Override public void requestStorage() { throw new AssertionError("Unexpected storage picker"); }
		@Override public void invalidateOptionsMenu() {}
		@Override public void resizeCommentAfterAttachmentChange() {}
		@Override public void saveDraftAfterAttachmentChange() { saves++; }
		@Override public void updateSendButtonState() {}
		@Override public void launchAttachmentPicker(Intent intent) { throw new AssertionError("Unexpected picker"); }
		@Override public void launchImageEditor(Intent intent) { throw new AssertionError("Unexpected editor"); }
		@Override public void onAttachmentDiagnostic(String event) {
			assertEquals("editor_result_stale", event);
			staleResults++;
		}
	}

	private static void sameDraft(DraftsStorage.AttachmentDraft expected, DraftsStorage.AttachmentDraft actual) {
		assertEquals(expected.hash, actual.hash);
		assertEquals(expected.name, actual.name);
		assertEquals(expected.rating, actual.rating);
		assertEquals(expected.optionUniqueHash, actual.optionUniqueHash);
		assertEquals(expected.optionRemoveMetadata, actual.optionRemoveMetadata);
		assertEquals(expected.optionRemoveFileName, actual.optionRemoveFileName);
		assertEquals(expected.optionSpoiler, actual.optionSpoiler);
		assertSame(expected.reencoding, actual.reencoding);
	}

	private static void assertContainerOrder(Fixture f) {
		ArrayList<View> actual = new ArrayList<>();
		for (int i = 0; i < f.container.getChildCount(); i++) {
			View child = f.container.getChildAt(i);
			if (child instanceof LinearLayout) {
				LinearLayout row = (LinearLayout) child;
				// The last child is the original weighted placeholder, not an attachment.
				for (int j = 0; j < row.getChildCount() - 1; j++) actual.add(row.getChildAt(j));
			} else actual.add(child);
		}
		ArrayList<DraftsStorage.AttachmentDraft> drafts = f.controller.createAttachmentDrafts();
		assertEquals(drafts == null ? 0 : drafts.size(), actual.size());
		for (int i = 0; i < actual.size(); i++) assertSame(f.controller.getAttachmentHolder(i).view, actual.get(i));
	}

	private static View lastTaggedClickable(View view, AttachmentHolder holder) {
		if (view instanceof ViewGroup) {
			ViewGroup group = (ViewGroup) view;
			for (int i = group.getChildCount() - 1; i >= 0; i--) {
				View found = lastTaggedClickable(group.getChildAt(i), holder);
				if (found != null) return found;
			}
		}
		return view.getTag() == holder && view.hasOnClickListeners() ? view : null;
	}

	@Test public void reorderAndRemoveKeepModelAndContainerInSyncForEveryColumnCount() {
		InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
			for (int columns : new int[] {1, 2, 4}) {
				Fixture f = new Fixture(columns, hash -> null);
				try {
					f.restore(draft(1, null, 0, null), draft(2, null, 0, null), draft(3, null, 15, null));
					AttachmentHolder c = f.controller.getAttachmentHolder(2);
					f.controller.moveAttachment(c, 1);
					assertSame(c, f.controller.getAttachmentHolder(1));
					assertEquals(draft(2, null, 0, null).hash, f.controller.getAttachmentHolder(2).hash);
					assertEquals(1, f.saves);
					assertContainerOrder(f);
					View remove = lastTaggedClickable(c.view, c);
					assertNotNull(remove);
					assertTrue(remove.performClick());
					assertEquals(2, f.saves);
					assertEquals(draft(2, null, 0, null).hash, f.controller.getAttachmentHolder(1).hash);
					assertContainerOrder(f);
				} finally { f.controller.detachView(); }
			}
		});
	}

	@Test public void holderDraftRestoreKeepsAllOptionCombinationsAndNullableValues() {
		InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
			Fixture first = new Fixture(1, hash -> null), second = new Fixture(2, hash -> null);
			try {
				for (int flags = 0; flags < 16; flags++) {
					GraphicsUtils.Reencoding encoding = flags % 2 == 0 ? null : new GraphicsUtils.Reencoding("png", 87, 3);
					first.restore(draft(flags + 1, flags % 2 == 0 ? null : "r", flags, encoding));
				}
				ArrayList<DraftsStorage.AttachmentDraft> expected = first.controller.createAttachmentDrafts();
				second.controller.restoreAttachmentDrafts(expected);
				ArrayList<DraftsStorage.AttachmentDraft> actual = second.controller.createAttachmentDrafts();
				for (int i = 0; i < expected.size(); i++) sameDraft(expected.get(i), actual.get(i));
				assertContainerOrder(second);
			} finally { first.controller.detachView(); second.controller.detachView(); }
		});
	}

	@Test public void sendSnapshotKeepsOrderHashesRatingFallbackMissingFilesAndOptions() throws Exception {
		Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
		File file = File.createTempFile("posting-controller-", ".txt", context.getCacheDir());
		try {
			Files.write(file.toPath(), new byte[] {1, 2, 3});
			FileHolder supplied = FileHolder.obtain(file);
			assertNotNull(supplied);
			InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
				Fixture f = new Fixture(1, hash -> draft(2, null, 0, null).hash.equals(hash) ? null : supplied);
				try {
					f.posting.attachmentRatings.add(new Pair<>("safe", "Safe"));
					f.posting.attachmentRatings.add(new Pair<>("other", "Other"));
					f.controller.updateConfiguration(f.posting, true, false);
					GraphicsUtils.Reencoding encoding = new GraphicsUtils.Reencoding("png", 81, 2);
					f.restore(draft(1, "obsolete", 15, encoding), draft(2, "safe", 0, null), draft(3, "other", 0, null));
					f.controller.moveAttachment(f.controller.getAttachmentHolder(2), 0);
					PostingAttachmentsController.SendAttachments result = f.controller.createSendAttachments();
					assertEquals(Arrays.asList(draft(3, null, 0, null).hash, draft(1, null, 0, null).hash), result.hashes);
					assertEquals(2, result.attachments.length);
					assertEquals("other", result.attachments[0].rating);
					ChanPerformer.SendPostData.Attachment converted = result.attachments[1];
					assertEquals("safe", converted.rating);
					assertEquals("fixture-1.txt", converted.fileName);
					assertSame(supplied, converted.fileHolder);
					assertTrue(converted.optionUniqueHash && converted.optionRemoveMetadata && converted.optionRemoveFileName && converted.optionSpoiler);
					assertSame(encoding, converted.reencoding);
					f.posting.attachmentSpoiler = false;
					assertFalse(f.controller.createSendAttachments().attachments[1].optionSpoiler);
					f.posting.attachmentRatings.clear();
					f.controller.updateConfiguration(f.posting, true, false);
					assertNull(f.controller.createSendAttachments().attachments[0].rating);
				} finally { f.controller.detachView(); }
			});
		} finally { Files.deleteIfExists(file.toPath()); }
	}

	@Test public void missingFilesProduceNullPayloadAndNoHashes() {
		InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
			Fixture f = new Fixture(1, hash -> null);
			try {
				f.restore(draft(1, null, 0, null));
				assertNull(f.controller.createSendAttachments().attachments);
				assertTrue(f.controller.createSendAttachments().hashes.isEmpty());
			} finally { f.controller.detachView(); }
		});
	}

	@Test public void mimeFiltersKeepGroupOrderWildcardsAndOtherTypes() {
		assertEquals(Arrays.asList("image/*", "image/png", "video/mp4", "audio/ogg", "application/pdf", "text/plain"),
				PostingAttachmentsController.buildMimeTypeList(Arrays.asList("application/pdf", "video/mp4",
						"text/plain", "image/png", "audio/ogg", "image/*")));
	}

	@Test public void partialImportClearsBusyStateHonoursLimitAndSavesOnlyIfAttached() {
		InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
			Fixture f = new Fixture(1, hash -> null);
			try {
				f.posting.attachmentCount = 2;
				f.restore(draft(1, null, 0, null));
				f.controller.setAttachmentImportInProgress(true);
				assertTrue(f.controller.isImportInProgress());
				assertFalse(f.controller.canAttach());
				ArrayList<Pair<String, String>> imported = new ArrayList<>();
				imported.add(new Pair<>(draft(2, null, 0, null).hash, "fixture-2.txt"));
				imported.add(new Pair<>(draft(3, null, 0, null).hash, "fixture-3.txt"));
				PostingAttachmentsController.AttachmentImportResult result =
						new PostingAttachmentsController.AttachmentImportResult(imported, 3);
				assertEquals(3, result.requestedCount);
				f.controller.onAttachmentImportComplete(result);
				assertFalse(f.controller.isImportInProgress());
				assertFalse(f.controller.canAttach());
				assertNull(f.controller.getAttachmentHolder(2));
				assertEquals(1, f.saves);
				f.controller.onAttachmentImportComplete(new PostingAttachmentsController.AttachmentImportResult(new ArrayList<>(), 1));
				assertEquals(1, f.saves);
				assertContainerOrder(f);
			} finally { f.controller.detachView(); }
		});
	}

	@Test public void reorderedEditorResultCannotChangeAnotherAttachment() {
		InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
			Fixture f = new Fixture(1, hash -> null);
			try {
				f.restore(draft(1, null, 0, null), draft(2, null, 0, null));
				AttachmentHolder original = f.controller.getAttachmentHolder(0);
				Intent result = new Intent().putExtra(ImageEditorActivity.EXTRA_RESULT_ATTACHMENT_INDEX, 0)
						.putExtra(ImageEditorActivity.EXTRA_RESULT_SOURCE_HASH, original.hash)
						.putExtra(ImageEditorActivity.EXTRA_RESULT_SOURCE_NAME, original.name)
						.putExtra(ImageEditorActivity.EXTRA_RESULT_HASH, draft(3, null, 0, null).hash)
						.putExtra(ImageEditorActivity.EXTRA_RESULT_NAME, "edited.txt");
				f.controller.moveAttachment(original, 1);
				f.controller.handleActivityResult(C.REQUEST_CODE_IMAGE_EDITOR, Activity.RESULT_OK, result);
				assertEquals(1, f.staleResults);
				assertEquals(draft(2, null, 0, null).hash, f.controller.getAttachmentHolder(0).hash);
				assertEquals(draft(1, null, 0, null).hash, original.hash);
				AttachmentHolder current = f.controller.getAttachmentHolder(0);
				result.putExtra(ImageEditorActivity.EXTRA_RESULT_SOURCE_HASH, current.hash)
						.putExtra(ImageEditorActivity.EXTRA_RESULT_SOURCE_NAME, current.name);
				f.controller.handleActivityResult(C.REQUEST_CODE_IMAGE_EDITOR, Activity.RESULT_OK, result);
				assertEquals(draft(3, null, 0, null).hash, current.hash);
				assertEquals("edited.txt", current.name);
				assertEquals(2, f.saves);
				assertEquals(1, f.staleResults);
				assertContainerOrder(f);
			} finally { f.controller.detachView(); }
		});
	}

	@Test public void detachDropsEveryViewAndHostReferenceAndAllowsFreshRestore() {
		InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
			Fixture f = new Fixture(1, hash -> null);
			f.restore(draft(1, null, 15, null));
			ArrayList<DraftsStorage.AttachmentDraft> saved = f.controller.createAttachmentDrafts();
			f.controller.setAttachmentImportInProgress(true);
			f.controller.detachView();
			try {
				for (String name : new String[] {"host", "attachmentContainer", "scrollView", "sendButton",
						"attachmentImportViewModel", "processingNotice", "draggedAttachment", "attachmentDragTarget"}) {
					java.lang.reflect.Field field = PostingAttachmentsController.class.getDeclaredField(name);
					field.setAccessible(true);
					assertNull(name, field.get(f.controller));
				}
				assertNull(f.controller.getAttachmentHolder(0));
				assertNull(f.controller.createAttachmentDrafts());
				f.controller.attachView(f, new LinearLayout(f.context), new ScrollView(f.context), new Button(f.context), 1);
				f.controller.setAttachmentImportInProgress(false);
				f.controller.restoreAttachmentDrafts(saved);
				sameDraft(saved.get(0), f.controller.createAttachmentDrafts().get(0));
			} catch (ReflectiveOperationException e) { throw new AssertionError(e); }
			finally { f.controller.detachView(); }
		});
	}
}
