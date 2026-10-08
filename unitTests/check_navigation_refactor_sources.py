"""Source contracts for both technical debt stages; no Android runtime claims."""
from pathlib import Path
import unittest

ROOT = Path(__file__).resolve().parents[1]
def source(path):
    return (ROOT / "src" / "com/mishiranu/dashchan" / path).read_text(encoding="utf-8")

class RefactorSourceContracts(unittest.TestCase):
    def test_drawer_keeps_adapter_views_and_selection(self):
        form = source("ui/DrawerForm.java")
        model = source("ui/DrawerContentController.java")
        self.assertIn("extends RecyclerView.Adapter", form)
        self.assertNotIn("import android.view.", model)
        self.assertNotIn("import androidx.recyclerview.", model)
        self.assertIn("selectedFavoriteIds.retainAll(visibleIds)", form)
        self.assertIn("content.updateItems(pages, favorites)", form)
        self.assertIn("dispatchAdapterDiff(previousItems)", form)
        self.assertIn("createAdapterSnapshot()", form)

    def test_drawer_filter_collapse_and_identity_contracts(self):
        model = source("ui/DrawerContentController.java")
        for token in (
            "COLLAPSED_OPEN_THREAD_LIMIT = 12", "if (currentThread != null)",
            "visibleThreads.add(currentThread)", "collapsedRedditPages.add(page)",
            "collapsedPages.addAll(newCollapsedPages)", "mergeChans || page.chanName.equals(chanName)",
            "showAllFavoriteThreads || favoriteItem.chanName.equals(chanName)",
            "Preferences.isFavoritesHidedDeleted()", "hiddenFavorite.test(favoriteItem)",
            "CombinedFeedStorage.getInstance().getFeeds()", "drawerSectionOrder",
            "MENU_ITEM_MY_POSTS", "MENU_ITEM_HISTORY", "MENU_ITEM_PREFERENCES"):
            self.assertIn(token, model)
        self.assertIn("content.pagesExpanded", source("ui/DrawerForm.java"))

    def test_thread_wire_format_and_result_application_order(self):
        codec = source("ui/navigator/page/PostsStateCodec.java")
        page = source("ui/navigator/page/PostsPage.java")
        self.assertIn("ParcelableExtra extends PostsStateCodec.ThreadState implements Parcelable", page)
        position = page[page.index("private final Runnable storePositionRunnable"):page.index("private final RecyclerView.OnScrollListener")]
        self.assertIn("PostsStateCodec.encodePosition(positionItem.getPostNumber(), listPosition.offset)", position)
        self.assertIn("if (listPosition == null || positionItem == null)", position)
        self.assertIn(".setStateExtra(false,", position)
        self.assertNotIn("JsonSerial.writer()", position)
        self.assertIn("source.dataAvail() > 0", codec)
        for key in ('"filters"', '"position"', '"number"', '"offset"'):
            self.assertIn(key, codec)
        callback = page[page.index("public void onExtractPostsComplete("):page.index("private static final class ExtractApplication")]
        steps = ["handleWindowedExtractTransition", "eraseExtract = false", "applyExtractedPostItems",
                 "applyExtractedHiddenAndState", "updateNewDeletedEditedNotification",
                 "updateImportantPostsFastScrollBarDecorationData", "notifyExtracted", "finishExtractApplication"]
        positions = [callback.index(step) for step in steps]
        self.assertEqual(sorted(positions), positions)
        for token in ("adapter.setContextRevision", "adapter.invalidateHidden", "adapter.insertItems",
                      "result.cacheChanged", "result.stateExtra", "result.flags", "listPosition.apply"):
            self.assertIn(token, page)

    def test_activity_is_only_commit_owner_and_startup_order_is_preserved(self):
        activity = source("ui/MainActivity.java")
        coordinator = source("ui/ContentNavigationCoordinator.java")
        self.assertEqual(1, activity.count(".runOnCommit("))
        self.assertNotIn("FragmentTransaction", coordinator.replace("FragmentTransaction commit owner", "commit owner"))
        self.assertNotIn("executePendingTransactions", activity)
        callback = activity[activity.index("public void onCreate("):activity.index("private static final class CreationLayout")]
        steps = ["super.onCreate", "createDrawerAndToolbar", "createDownloadDialog", "attachContentInfrastructure",
                 "restoreDrawerPresentation", "readCreationState", "restoreNavigationStacks", "restoreCurrentContent",
                 "initializeStartupNavigation", "finishCreation"]
        positions = [callback.index(step) for step in steps]
        self.assertEqual(sorted(positions), positions)
        self.assertIn("contentNavigation.isPendingFragment", activity)
        self.assertIn("contentNavigation.close()", activity)
        drawer = source("ui/DrawerNavigationCoordinator.java")
        self.assertLess(drawer.index("host.closeDrawer();"), drawer.index("runPending();"))
        self.assertIn("host.postAfterClose(", drawer)

    def test_session_names_version_and_sync_atomic_io_do_not_change(self):
        store = source("ui/PageSessionStore.java")
        for token in ('"pages-session"', '"pages-instance-state"', '"pagesStateVersion"',
                      "PAGES_STATE_VERSION = 1", "atomicFile.finishWrite", "atomicFile.failWrite",
                      "deleteAfterRead || !success", "bundle.setClassLoader(classLoader)"):
            self.assertIn(token, store)
        self.assertNotIn("Executor", store)
        self.assertNotIn("Handler", store)
        self.assertIn('getInternalCacheFile("saved-pages")', source("ui/MainActivity.java"))
        self.assertIn("pageSessionStore.readPagesInstanceStateOrFallback(savedInstanceState)", source("ui/MainActivity.java"))

if __name__ == "__main__":
    unittest.main()
