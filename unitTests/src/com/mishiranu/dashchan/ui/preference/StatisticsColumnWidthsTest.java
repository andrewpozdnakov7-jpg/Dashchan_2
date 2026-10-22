package com.mishiranu.dashchan.ui.preference;

import static org.junit.Assert.*;
import java.util.Arrays;
import java.util.Random;
import org.junit.Test;

public class StatisticsColumnWidthsTest {
	@Test public void narrowViewportKeepsEveryColumnAndDoesNotMutateMeasurements() {
		int[] measured = {140, 95, 80, 100};
		assertArrayEquals(measured, StatisticsColumnWidths.resolve(measured, 220));
		assertArrayEquals(new int[]{140, 95, 80, 100}, measured);
	}
	@Test public void fittingTableUsesViewportWithoutAnExtraScrollablePixel() {
		int[] measured = {80, 40, 40, 40};
		for (int available = 200; available < 600; available++) {
			int[] columns = StatisticsColumnWidths.resolve(measured, available);
			assertEquals(available, Arrays.stream(columns).sum());
			for (int i = 0; i < 4; i++) assertTrue(columns[i] >= measured[i]);
		}
	}
	@Test public void repeatedWindowResizeDoesNotAccumulateColumnWidths() {
		Random random = new Random(20261022);
		for (int sample = 0; sample < 100; sample++) {
			int[] minimum = {random.nextInt(250) + 1, random.nextInt(250) + 1,
					random.nextInt(250) + 1, random.nextInt(250) + 1};
			int available = random.nextInt(1500);
			int[] first = StatisticsColumnWidths.resolve(minimum, available);
			StatisticsColumnWidths.resolve(minimum, 1);
			assertArrayEquals(first, StatisticsColumnWidths.resolve(minimum, available));
			assertEquals(Math.max(available, Arrays.stream(minimum).sum()), Arrays.stream(first).sum());
		}
	}
}
