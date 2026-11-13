package com.mishiranu.dashchan.media.compression;

import org.junit.Test;
import static org.junit.Assert.*;

public class CompressionBudgetTest {
	@Test public void fittingAttachmentsAreNotChanged() {
		assertArrayEquals(new long[]{10, 20}, CompressionBudget.allocate(new long[]{10, 20}, new boolean[]{true, false}, 30, 40));
	}
	@Test public void fileLimitAppliesBeforeCombinedLimit() {
		assertArrayEquals(new long[]{40, 10}, CompressionBudget.allocate(new long[]{100, 10}, new boolean[]{true, false}, 40, 100));
	}
	@Test public void unchangedFilesReserveTheirSpace() {
		assertArrayEquals(new long[]{30, 15, 5}, CompressionBudget.allocate(new long[]{80, 40, 5}, new boolean[]{true, true, false}, 100, 50));
	}
	@Test public void roundingCannotExceedTotal() {
		long[] targets = CompressionBudget.allocate(new long[]{10, 10, 10}, new boolean[]{true, true, true}, 10, 20);
		assertEquals(18, targets[0] + targets[1] + targets[2]);
	}
	@Test public void imagesCannotSilentlyBeCompressedOrDiscarded() {
		assertNull(CompressionBudget.allocate(new long[]{60, 10}, new boolean[]{false, true}, 40, 100));
		assertNull(CompressionBudget.allocate(new long[]{40, 10}, new boolean[]{false, true}, 40, 40));
	}
	@Test public void weightedProgressUsesDurationAndReservesCompletionForCommit() {
		assertEquals(25, CompressionBudget.progress(0, 100, 200, 50));
		assertEquals(75, CompressionBudget.progress(100, 100, 200, 50));
		assertEquals(99, CompressionBudget.progress(100, 100, 200, 100));
		assertEquals(-1, CompressionBudget.progress(0, 100, 200, -1));
	}
	@Test public void forcedCodecChangeCanUseSpareSpaceWithoutReducingAlreadyFittingInputs() {
		assertArrayEquals(new long[]{5, 15}, CompressionBudget.allocateForConversion(new long[]{2, 8},
				new boolean[]{true, true}, new boolean[]{true, true}, 15, 20));
	}
	@Test public void manuallyConvertingOneVideoReservesAllOtherAttachments() {
		assertArrayEquals(new long[]{12, 8}, CompressionBudget.allocateForConversion(new long[]{2, 8},
				new boolean[]{true, true}, new boolean[]{true, false}, 15, 20));
		assertArrayEquals(new long[]{3, 7}, CompressionBudget.allocateForConversion(new long[]{3, 2},
				new boolean[]{false, true}, new boolean[]{false, true}, 8, 10));
	}
	@Test public void forcedConversionStillRespectsBindingCombinedAndPerFileLimits() {
		assertArrayEquals(new long[]{5, 5}, CompressionBudget.allocateForConversion(new long[]{8, 8},
				new boolean[]{true, true}, new boolean[]{true, true}, 10, 10));
		assertArrayEquals(new long[]{6, 2}, CompressionBudget.allocateForConversion(new long[]{1, 2},
				new boolean[]{true, false}, new boolean[]{true, false}, 6, 50));
	}
	@Test public void ordinaryModeDoesNotSpendUnusedBudget() {
		assertArrayEquals(new long[]{2, 8}, CompressionBudget.allocateForConversion(new long[]{2, 8},
				new boolean[]{true, true}, new boolean[]{false, false}, 15, 20));
	}
}
