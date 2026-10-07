package com.mishiranu.dashchan.ui.gallery;

import static org.junit.Assert.*;
import org.junit.Test;

public class GalleryGridReturnPolicyTest {
	@Test public void sameImageKeepsViewportEvenWhenEdgeVisibilityChanges() {
		assertFalse(GalleryGridReturnPolicy.shouldReveal(758, 740, 760, true));
		assertFalse(GalleryGridReturnPolicy.shouldReveal(758, -1, -1, true));
	}

	@Test public void anotherImageInsideViewportDoesNotMoveTheGrid() {
		for (int target = 740; target <= 760; target++) {
			assertFalse(GalleryGridReturnPolicy.shouldReveal(target, 740, 760, false));
		}
	}

	@Test public void itemsInPartiallyVisibleBoundaryRowsKeepPixelOffset() {
		assertFalse(GalleryGridReturnPolicy.shouldReveal(740, 740, 760, false));
		assertFalse(GalleryGridReturnPolicy.shouldReveal(760, 740, 760, false));
	}

	@Test public void pagingOutsideViewportRevealsTheNewImage() {
		assertTrue(GalleryGridReturnPolicy.shouldReveal(739, 740, 760, false));
		assertTrue(GalleryGridReturnPolicy.shouldReveal(761, 740, 760, false));
	}

	@Test public void directEntryWithoutGridViewportRevealsCurrentImage() {
		assertTrue(GalleryGridReturnPolicy.shouldReveal(758, -1, -1, false));
		assertTrue(GalleryGridReturnPolicy.shouldReveal(758, 20, 19, false));
	}

	@Test public void removedOrInvalidTargetCannotResetTheGrid() {
		assertFalse(GalleryGridReturnPolicy.shouldReveal(-1, 740, 760, false));
		assertFalse(GalleryGridReturnPolicy.shouldReveal(-1, -1, -1, false));
	}
}
