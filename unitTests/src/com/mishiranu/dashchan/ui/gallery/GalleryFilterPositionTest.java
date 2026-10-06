package com.mishiranu.dashchan.ui.gallery;

import static org.junit.Assert.*;
import java.util.List;
import org.junit.Test;

public class GalleryFilterPositionTest {
	private final Object photo1 = new Object();
	private final Object video1 = new Object();
	private final Object photo2 = new Object();
	private final Object video2 = new Object();
	private final Object photo3 = new Object();
	private final List<Object> original = List.of(photo1, video1, photo2, video2, photo3);
	private final List<Object> videos = List.of(video1, video2);

	@Test public void bottomRemainsAtBottomEvenWhenAnchorIsStillPresent() {
		assertEquals(1, GalleryFilterPosition.resolve(original, videos, video1, false, true, true));
	}

	@Test public void topRemainsAtTopWhenPhotoIsFilteredOut() {
		assertEquals(0, GalleryFilterPosition.resolve(original, videos, photo1, true, false, true));
	}

	@Test public void nonScrollableListPrefersStartRatherThanLastItem() {
		assertEquals(0, GalleryFilterPosition.resolve(original, videos, photo3, true, true, true));
	}

	@Test public void middleKeepsSurvivingVisibleFile() {
		assertEquals(1, GalleryFilterPosition.resolve(original, videos, video2, false, false, true));
	}

	@Test public void excludedMiddlePhotoUsesNearestFollowingAttachmentOnTie() {
		assertEquals(1, GalleryFilterPosition.resolve(original, videos, photo2, false, false, true));
	}

	@Test public void excludedLastPhotoUsesPreviousVideoInsteadOfFirst() {
		assertEquals(1, GalleryFilterPosition.resolve(original, videos, photo3, false, false, true));
	}

	@Test public void changedSortKeepsFileRatherThanOldEdge() {
		List<Object> reversed = List.of(video2, video1);
		assertEquals(0, GalleryFilterPosition.resolve(original, reversed, video2, false, true, false));
		assertEquals(1, GalleryFilterPosition.resolve(original, reversed, video1, true, false, false));
	}

	@Test public void expandingFilterPreservesBottomAndMiddleAnchor() {
		assertEquals(4, GalleryFilterPosition.resolve(original, original, video2, false, true, true));
		assertEquals(3, GalleryFilterPosition.resolve(original, original, video2, false, false, true));
	}

	@Test public void emptyMissingAndUnknownAnchorsAreSafe() {
		assertEquals(-1, GalleryFilterPosition.resolve(original, List.of(), photo3, false, true, true));
		assertEquals(-1, GalleryFilterPosition.resolve(original, videos, null, false, false, true));
		assertEquals(-1, GalleryFilterPosition.resolve(original, videos, new Object(), false, false, true));
		assertEquals(1, GalleryFilterPosition.resolve(original, videos, null, false, true, true));
	}

	@Test public void distinctAttachmentsAreNotMergedByEqualityOrSharedUrl() {
		String first = new String("same-file");
		String second = new String("same-file");
		List<String> source = List.of(first, second);
		assertEquals(1, GalleryFilterPosition.resolve(source, source, second, false, false, true));
		assertEquals(0, GalleryFilterPosition.resolve(source, List.of(second), first, false, false, true));
	}
}
