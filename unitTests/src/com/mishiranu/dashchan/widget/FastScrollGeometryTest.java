package com.mishiranu.dashchan.widget;

import static org.junit.Assert.*;
import org.junit.Test;

public class FastScrollGeometryTest {
	@Test public void reachingBottomAndReversingDoNotSwitchToAnotherScale() {
		FastScrollGeometry before = new FastScrollGeometry(13, 6.99, 12.99);
		FastScrollGeometry bottom = new FastScrollGeometry(13, 7, 13);
		assertEquals(6.99 / 7, before.offset(), 0.000001);
		assertEquals(1, bottom.offset(), 0);
		assertTrue(bottom.offset() - before.offset() < 0.002);
		assertEquals(0, new FastScrollGeometry(13, 0, 6).offset(), 0);
	}

	@Test public void oversizedLastItemKeepsItsFractionalNavigationCoordinate() {
		FastScrollGeometry geometry = new FastScrollGeometry(13, 12.4, 12.9);
		assertEquals(12.4, geometry.targetCoordinate(geometry.offset()), 0.00001);
		FastScrollGeometry single = new FastScrollGeometry(1, 0.25, 0.75);
		assertEquals(0.5, single.offset(), 0);
		assertEquals(0.25, single.targetCoordinate(single.offset()), 0.000001);
		assertTrue(new FastScrollGeometry(1, 1, 1).targetCoordinate(1) < 1);
	}

	@Test public void emptyAndFullyVisibleListsHaveNoScrollableRange() {
		FastScrollGeometry empty = new FastScrollGeometry(0, 0, 0);
		assertEquals(0, empty.offset(), 0);
		assertEquals(0, empty.targetCoordinate(1), 0);
		assertEquals(0, new FastScrollGeometry(13, 0, 13).offset(), 0);
		assertEquals(0, new FastScrollGeometry(-1, Double.NaN, Double.NaN).offset(), 0);
		FastScrollGeometry bounded = new FastScrollGeometry(13, -10, 20);
		assertEquals(0, bounded.start, 0);
		assertEquals(13, bounded.end, 0);
	}

	@Test public void unequalHeightsAreContinuousAtBothViewportEdges() {
		checkPixelSequence(new int[] {1100, 100, 100, 100, 100, 100, 100, 100, 100, 100, 100, 100, 100});
		checkPixelSequence(new int[] {100, 100, 100, 100, 100, 100, 100, 100, 100, 100, 100, 100, 1100});
		checkPixelSequence(new int[] {1100});
	}

	private static void checkPixelSequence(int[] heights) {
		int total = 0;
		for (int height : heights) total += height;
		int viewport = 600;
		float previous = 0;
		for (int pixel = 0; pixel <= total - viewport; pixel++) {
			FastScrollGeometry geometry = atPixel(heights, pixel, viewport);
			float current = geometry.offset();
			assertTrue(current >= previous);
			assertTrue(current - previous < 0.005);
			assertEquals(geometry.start, geometry.targetCoordinate(current), 0.00001);
			previous = current;
		}
		assertEquals(1, previous, 0);
		for (int pixel = total - viewport; pixel >= 0; pixel--) {
			float current = atPixel(heights, pixel, viewport).offset();
			assertTrue(current <= previous);
			assertTrue(previous - current < 0.005);
			previous = current;
		}
		assertEquals(0, previous, 0);
	}

	private static FastScrollGeometry atPixel(int[] heights, int pixel, int viewport) {
		return new FastScrollGeometry(heights.length, coordinateAt(heights, pixel),
				coordinateAt(heights, pixel + viewport));
	}

	private static double coordinateAt(int[] heights, double pixel) {
		for (int i = 0; i < heights.length; i++) {
			if (pixel < heights[i]) return i + pixel / heights[i];
			pixel -= heights[i];
		}
		return heights.length;
	}

	@Test public void marksAndNavigationUseTheSameScale() {
		FastScrollGeometry geometry = new FastScrollGeometry(13, 3.25, 9.25);
		for (int position = 0; position <= 7; position++) {
			float mark = FastScrollGeometry.markOffset(position, 13, geometry.viewportFraction());
			assertEquals(position, geometry.targetCoordinate(mark), 0.000001);
		}
		assertEquals(1, FastScrollGeometry.markOffset(12, 13, geometry.viewportFraction()), 0);
		assertEquals(0, FastScrollGeometry.markOffset(0, 0, 0), 0);
	}
}
