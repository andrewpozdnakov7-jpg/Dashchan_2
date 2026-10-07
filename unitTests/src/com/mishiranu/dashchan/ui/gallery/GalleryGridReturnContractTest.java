package com.mishiranu.dashchan.ui.gallery;

import static org.junit.Assert.*;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import org.junit.Test;

/** Source wiring checks; real layout, animations and pixel offsets require device testing. */
public class GalleryGridReturnContractTest {
	private static String source(String name) throws Exception {
		File root = new File(".").getCanonicalFile();
		while (root != null) {
			File file = new File(root, "src/com/mishiranu/dashchan/ui/gallery/" + name + ".java");
			if (file.isFile()) return Files.readString(file.toPath(), StandardCharsets.UTF_8);
			root = root.getParentFile();
		}
		throw new AssertionError("Missing source: " + name);
	}

	private static String method(String name, String signature) throws Exception {
		String text = source(name);
		int start = text.indexOf(signature);
		assertTrue(signature, start >= 0);
		int opening = text.indexOf('{', start);
		int depth = 1;
		int end = opening + 1;
		while (end < text.length() && depth > 0) {
			char c = text.charAt(end++);
			if (c == '{') depth++;
			else if (c == '}') depth--;
		}
		assertEquals(signature, 0, depth);
		return text.substring(opening + 1, end - 1);
	}

	@Test public void remembersOriginBeforeHidingGrid() throws Exception {
		String body = method("GalleryOverlay", "public void navigatePageFromList(");
		assertTrue(body.indexOf("listUnit.onPageOpenedFromGrid(position)") >= 0);
		assertTrue(body.indexOf("listUnit.onPageOpenedFromGrid(position)")
				< body.indexOf("switchMode(false, true)"));
	}

	@Test public void returnQueuesIdentityRatherThanImmediatelyForcingOffset() throws Exception {
		String body = method("GalleryOverlay", "private void invalidateListPosition(");
		assertTrue(body.contains("listUnit.requestReturnToGrid(pagerUnit.getCurrentIndex())"));
		assertFalse(body.contains("scrollListToPosition"));
		String request = method("ListUnit", "void requestReturnToGrid(");
		assertTrue(request.contains("instance.galleryItems.get(position)"));
		assertTrue(request.contains("recyclerView.invalidate()"));
		assertFalse(request.contains("new ListPosition"));
		assertTrue(request.contains("pendingViewport = viewport"));
	}

	@Test public void visibilityDecisionIsFencedByCompletedLayout() throws Exception {
		String text = source("ListUnit");
		assertTrue(text.contains("pendingReturnItem != null && isGridLayoutSettled()"));
		assertTrue(text.contains("GalleryGridReturnPolicy.shouldReveal(position, manager.findFirstVisibleItemPosition()"));
		String settled = method("ListUnit", "private boolean isGridLayoutSettled(");
		for (String guard : new String[] {"recyclerView.isShown()", "gridMetricsWidth == getGridWidth()",
				"!recyclerView.isLayoutRequested()", "!recyclerView.hasPendingAdapterUpdates()",
				"!recyclerView.isComputingLayout()"}) assertTrue(guard, settled.contains(guard));
		assertTrue(text.contains("openedGridWidth == recyclerView.getWidth()"));
		assertTrue(text.contains("openedGridHeight == recyclerView.getHeight()"));
	}

	@Test public void explicitFilterRestoreStillUsesItsPixelOffset() throws Exception {
		assertTrue(method("ListUnit", "void restoreFilterPosition(")
				.contains("scrollListToPosition(position, offset)"));
		String explicit = method("ListUnit", "private void scrollListToPosition(int position, int offset)");
		assertTrue(explicit.contains("pendingReturnItem = null"));
		assertTrue(explicit.contains("new ListPosition(position, offset)"));
		assertTrue(explicit.contains("pendingListPosition.apply(recyclerView)"));
		assertTrue(method("ListUnit", "public void onGalleryItemsChanged(")
				.contains("openedFromGridItem = null"));
	}

	@Test public void disabledAutoScrollCancelsOnlyAutomaticReturnRequest() throws Exception {
		String body = method("ListUnit", "void requestReturnToGrid(");
		int guard = body.indexOf("if (!Preferences.isScrollGalleryToCurrentFile())");
		int enqueue = body.indexOf("pendingReturnItem = position >= 0");
		assertTrue(guard >= 0 && enqueue > guard);
		String disabled = body.substring(guard, enqueue);
		assertTrue(disabled.contains("pendingReturnItem = null"));
		assertTrue(disabled.contains("return;"));
		assertFalse(disabled.contains("new ListPosition"));
		String explicit = method("ListUnit", "private void scrollListToPosition(int position, int offset)");
		assertFalse(explicit.contains("isScrollGalleryToCurrentFile"));
		assertTrue(explicit.contains("pendingListPosition.apply(recyclerView)"));
	}

	@Test public void firstThreadToGridEntryBypassesTheAutoFollowPreference() throws Exception {
		String body = method("ListUnit", "void requestReturnToGrid(");
		assertTrue(body.indexOf("viewport == null || findViewportItem(viewport) < 0")
				< body.indexOf("if (!Preferences.isScrollGalleryToCurrentFile())"));
		assertTrue(body.contains("scrollListToPosition(position, 0)"));
	}

	@Test public void restoresExactViewportBeforeDecidingWhetherToFollowViewer() throws Exception {
		String body = source("ListUnit");
		assertTrue(body.indexOf("pendingViewport != null && isGridLayoutSettled()")
				< body.indexOf("pendingReturnItem != null && isGridLayoutSettled()"));
		assertTrue(body.contains("snapshot.offsetFor(getGridWidth(), manager.getSpanCount())"));
		assertTrue(body.contains("scrollListToPosition(anchor, offset)"));
		assertTrue(method("ListUnit", "void onPageOpenedFromGrid(").contains("captureViewport()"));
		assertTrue(method("ListUnit", "public void switchMode(").contains("if (!galleryMode) captureViewport()"));
	}

	@Test public void hiddenOrUnsettledGridCannotReplaceTheRememberedViewport() throws Exception {
		String capture = method("ListUnit", "private void captureViewport(");
		assertTrue(capture.contains("!gridVisible || !isGridLayoutSettled()"));
		assertTrue(capture.contains("pendingListPosition != null || pendingReturnItem != null"));
		assertTrue(capture.contains("ListPosition.obtain(recyclerView, null)"));
		assertTrue(capture.contains("position.offset"));
	}

	@Test public void threadNavigationRemembersViewportBeforeClosingGallery() throws Exception {
		String navigation = method("GalleryOverlay", "public void navigatePost(");
		assertTrue(navigation.indexOf("if (force) rememberViewport()") < navigation.indexOf("dismiss()"));
		assertTrue(source("GalleryOverlay").contains("new ViewModelProvider(requireActivity()).get(GalleryViewportViewModel.class)"));
		assertTrue(source("GalleryOverlay").contains("listUnit.setViewport(viewport)"));
		assertTrue(source("GalleryOverlay").contains("viewportState.memory.get(viewportScope())"));
		assertTrue(source("GalleryOverlay").contains("outState.putBundle(EXTRA_GRID_VIEWPORT, snapshot)"));
	}

	@Test public void sessionScopeSeparatesThreadsForumsFiltersAndStandaloneFiles() throws Exception {
		String scope = method("GalleryOverlay", "private String viewportScope(");
		for (String part : new String[] {"EXTRA_URI", "getChanName()", "first.boardName", "first.threadNumber",
				"getNavigatePostMode().name()", "galleryFilter", "gallerySort.name()"}) assertTrue(scope.contains(part));
		assertTrue(scope.contains("allGalleryItems"));
		assertTrue(scope.contains("part.length()"));
	}
}
