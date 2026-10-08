package com.mishiranu.dashchan.ui;

import static org.junit.Assert.*;
import android.content.Context;
import android.os.Bundle;
import android.os.Parcel;
import com.mishiranu.dashchan.ui.navigator.Page;
import com.mishiranu.dashchan.ui.navigator.PageItem;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import com.mishiranu.dashchan.util.AndroidUtils;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

/** Uses an isolated cache directory, never the user's actual saved session. */
@RunWith(AndroidJUnit4.class)
public class PageSessionStoreTest {
	private File directory;
	private PageSessionStore store;
	@Before public void setUp() throws IOException {
		Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
		directory = java.nio.file.Files.createTempDirectory(context.getCacheDir().toPath(), "session-store-test-").toFile();
		store = new PageSessionStore(() -> directory, () -> new File(directory, "saved-pages"), getClass().getClassLoader());
	}
	@After public void tearDown() {
		if (store != null) {
			PageSessionStore.deletePagesState(store.getSavedPagesFile());
			PageSessionStore.deletePagesState(store.getPagesSessionFile());
			PageSessionStore.deletePagesState(store.getPagesInstanceStateFile());
		}
		if (directory != null) assertTrue(directory.delete());
	}
	private Bundle state() {
		Bundle state = new Bundle();
		state.putInt(PageSessionStore.EXTRA_PAGES_STATE_VERSION, PageSessionStore.PAGES_STATE_VERSION);
		Bundle args = new Bundle(); args.putString("chanName", "reddit-web-reader");
		state.putParcelable("currentFragment", new StackItem(
				"com.mishiranu.dashchan.ui.preference.ChanFragment", args, null));
		state.putString("marker", "retained");
		return state;
	}
	@Test public void sessionRoundTripPreservesParcelableClassLoaderAndFile() {
		File file = store.getPagesSessionFile();
		assertEquals("pages-session", file.getName());
		assertEquals("pages-instance-state", store.getPagesInstanceStateFile().getName());
		assertTrue(store.writePagesState(file, state()));
		Bundle restored = store.readPagesState(file, false, true);
		assertNotNull(restored); assertTrue(file.isFile());
		assertEquals("retained", restored.getString("marker"));
		StackItem stack = AndroidUtils.getParcelable(restored, "currentFragment", StackItem.class);
		assertNotNull(stack);
		assertEquals("reddit-web-reader", stack.arguments.getString("chanName"));
	}
	@Test public void legacyOneShotStateIsReadWithoutVersionThenDeleted() {
		File file = store.getSavedPagesFile();
		Bundle legacy = state(); legacy.remove(PageSessionStore.EXTRA_PAGES_STATE_VERSION);
		assertTrue(store.writePagesState(file, legacy));
		assertNotNull(store.readPagesState(file, true, false));
		assertFalse(file.exists());
	}
	@Test public void wrongVersionAndCorruptStateReturnNullAndRemoveBadFile() throws IOException {
		File file = store.getPagesSessionFile();
		Bundle invalid = state(); invalid.putInt(PageSessionStore.EXTRA_PAGES_STATE_VERSION, 999);
		assertTrue(store.writePagesState(file, invalid));
		assertNull(store.readPagesState(file, false, true)); assertFalse(file.exists());
		try (FileOutputStream output = new FileOutputStream(file)) { output.write(new byte[] {1, 2, 3}); }
		assertNull(store.readPagesState(file, false, true)); assertFalse(file.exists());
		assertNull(store.readPagesState(null, false, true));
		assertNull(store.readPagesState(file, false, true));
	}
	/** Minimal framework fallback retains the active page even when the external navigation stack is gone. */
	private Bundle currentPageFallback() {
		Bundle fallback = new Bundle();
		PageItem item = new PageItem();
		item.createdRealtime = 42L; item.threadTitle = "current title"; item.allowReturn = true;
		fallback.putParcelable("currentPageItem", item);
		fallback.putParcelable("page", new Page(Page.Content.POSTS, "dvach", "test", "123", null));
		fallback.putString("drawerChan", "dvach");
		// Model process recreation: assert against actual Parcelable deserialization, not live object references.
		Parcel parcel = Parcel.obtain();
		try {
			fallback.writeToParcel(parcel, 0); parcel.setDataPosition(0);
			Bundle restored = new Bundle();
			restored.setClassLoader(getClass().getClassLoader()); restored.readFromParcel(parcel);
			return restored;
		} finally { parcel.recycle(); }
	}

	private void assertCurrentPageFallback(Bundle expected, Bundle restored) {
		assertSame(expected, restored);
		PageItem item = AndroidUtils.getParcelable(restored, "currentPageItem", PageItem.class);
		assertNotNull(item); assertEquals(42L, item.createdRealtime);
		assertEquals("current title", item.threadTitle); assertTrue(item.allowReturn);
		Page page = AndroidUtils.getParcelable(restored, "page", Page.class);
		assertNotNull(page); assertEquals(Page.Content.POSTS, page.content);
		assertEquals("dvach", page.chanName); assertEquals("test", page.boardName);
		assertEquals("123", page.threadNumber); assertEquals("dvach", restored.getString("drawerChan"));
	}

	@Test public void corruptExternalStateFallsBackToCurrentPageWithoutLosingMetadata() throws IOException {
		File file = store.getPagesInstanceStateFile();
		try (FileOutputStream output = new FileOutputStream(file)) { output.write(new byte[]{1, 2, 3}); }
		Bundle fallback = currentPageFallback();
		assertCurrentPageFallback(fallback, store.readPagesInstanceStateOrFallback(fallback));
		assertFalse(file.exists());
	}

	@Test public void missingOrWrongVersionExternalStateAlsoPreservesCurrentPage() {
		Bundle fallback = currentPageFallback();
		assertCurrentPageFallback(fallback, store.readPagesInstanceStateOrFallback(fallback));
		Bundle invalid = state(); invalid.putInt(PageSessionStore.EXTRA_PAGES_STATE_VERSION, 999);
		assertTrue(store.writePagesState(store.getPagesInstanceStateFile(), invalid));
		assertCurrentPageFallback(fallback, store.readPagesInstanceStateOrFallback(fallback));
		assertFalse(store.getPagesInstanceStateFile().exists());
	}

	@Test public void validExternalStateTakesPrecedenceOverCurrentPageFallbackAndIsOneShot() {
		assertTrue(store.writePagesState(store.getPagesInstanceStateFile(), state()));
		Bundle fallback = currentPageFallback();
		Bundle restored = store.readPagesInstanceStateOrFallback(fallback);
		assertNotNull(restored); assertNotSame(fallback, restored);
		assertEquals("retained", restored.getString("marker"));
		assertNotNull(AndroidUtils.getParcelable(restored, "currentFragment", StackItem.class));
		assertFalse(store.getPagesInstanceStateFile().exists());
		assertCurrentPageFallback(fallback, store.readPagesInstanceStateOrFallback(fallback));
	}

	@Test public void externalInstanceStateRemainsOneShot() {
		File file = store.getPagesInstanceStateFile();
		assertTrue(store.writePagesState(file, state()));
		assertNotNull(store.readPagesState(file, true, true));
		assertFalse(file.exists());
	}
}
