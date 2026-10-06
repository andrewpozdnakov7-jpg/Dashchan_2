package com.mishiranu.dashchan.content;

import static org.junit.Assert.*;
import org.junit.Test;

public class ImageMemoryKeyTest {
	@Test public void resourceGenerationSeparatesBitmapsAndNegativeCache() {
		assertNotEquals(ImageMemoryKey.create("dvach", 100, "old-uri-key", true, 1L),
				ImageMemoryKey.create("dvach", 100, "old-uri-key", true, 2L));
	}

	@Test public void resourceOwnerAndTargetSizeRemainPartOfKey() {
		String key = ImageMemoryKey.create("dvach", 100, "icon", true, 1L);
		assertNotEquals(key, ImageMemoryKey.create("fourchan", 100, "icon", true, 1L));
		assertNotEquals(key, ImageMemoryKey.create("dvach", 200, "icon", true, 1L));
		assertNotEquals(key, ImageMemoryKey.create("dvach", 100, "other", true, 1L));
	}

	@Test public void networkAndArchiveKeysAreUnchangedAndIgnoreResourceGeneration() {
		assertEquals("dvach\n100\nattachment", ImageMemoryKey.create("dvach", 100, "attachment", false, 1L));
		assertEquals(ImageMemoryKey.create("dvach", 100, "attachment", false, 1L),
				ImageMemoryKey.create("dvach", 100, "attachment", false, 2L));
	}

	@Test public void resourceKeysCannotAliasOrdinaryImageKeys() {
		assertNotEquals(ImageMemoryKey.create("dvach", 100, "attachment", false, 0L),
				ImageMemoryKey.create("dvach", 100, "attachment", true, 0L));
	}
}
