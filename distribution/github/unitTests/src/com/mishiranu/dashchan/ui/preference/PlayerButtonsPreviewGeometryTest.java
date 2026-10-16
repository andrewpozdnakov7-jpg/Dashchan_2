package com.mishiranu.dashchan.ui.preference;

import static org.junit.Assert.*;
import org.junit.Test;

public class PlayerButtonsPreviewGeometryTest {
	@Test public void fourFullSizeCellsFitBesideTwoRightCellsAtExactBoundary() {
		PlayerButtonsPreviewGeometry geometry = new PlayerButtonsPreviewGeometry(312, 4, 48, 104, 8, 8);
		assertFalse(geometry.stacked);
		assertEquals(4, geometry.columns);
		assertEquals(200, geometry.leftWidth);
		assertEquals(104, geometry.rightWidth);
	}
	@Test public void onePixelLessStacksPanelsInsteadOfDroppingToThreeColumns() {
		PlayerButtonsPreviewGeometry geometry = new PlayerButtonsPreviewGeometry(311, 4, 48, 104, 8, 8);
		assertTrue(geometry.stacked);
		assertEquals(4, geometry.columns);
		assertEquals(311, geometry.leftWidth);
	}
	@Test public void fiveAndSixRequestedColumnsUseFullCardWidthEvenWithAnEmptyRightPanel() {
		for (int right : new int[]{96, 104}) for (int columns : new int[]{5, 6}) {
			PlayerButtonsPreviewGeometry geometry = new PlayerButtonsPreviewGeometry(312, columns, 48, right, 8, 8);
			assertTrue(geometry.stacked);
			assertEquals(columns, geometry.columns);
		}
	}
	@Test public void availableWidthAndLargeCellsStillBoundTheActualColumns() {
		assertEquals(3, new PlayerButtonsPreviewGeometry(160, 6, 48, 104, 8, 8).columns);
		assertEquals(4, new PlayerButtonsPreviewGeometry(312, 4, 60, 128, 8, 8).columns);
		assertFalse(new PlayerButtonsPreviewGeometry(512, 6, 48, 104, 8, 8).stacked);
	}
}
