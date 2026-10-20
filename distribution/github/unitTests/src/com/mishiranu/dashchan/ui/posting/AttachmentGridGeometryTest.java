package com.mishiranu.dashchan.ui.posting;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import org.junit.Test;

public class AttachmentGridGeometryTest {
	@Test public void normalPhoneHasTwoColumnsWithinActualFormWidth() {
		assertEquals(2, AttachmentGridGeometry.columns(360 - 32, 8, 144));
		assertEquals(160, AttachmentGridGeometry.cardWidth(360 - 32, 8, 144));
	}
	@Test public void narrowPhoneRetainsTheAcceptedMinimumCardWidth() {
		assertEquals(1, AttachmentGridGeometry.columns(320 - 32, 8, 144));
		assertEquals(288, AttachmentGridGeometry.cardWidth(320 - 32, 8, 144));
	}
	@Test public void boundaryKeepsTheAccepted144DpCardMinimumNotScreenClass() {
		assertEquals(1, AttachmentGridGeometry.columns(295, 8, 144));
		assertEquals(2, AttachmentGridGeometry.columns(296, 8, 144));
		assertEquals(144, AttachmentGridGeometry.cardWidth(296, 8, 144));
	}
	@Test public void densityAndInsetsUseTheSamePhysicalUnit() {
		for (int density : new int[] {1, 2, 3, 4}) {
			assertEquals(2, AttachmentGridGeometry.columns(328 * density, 8 * density, 144 * density));
			assertEquals(160 * density, AttachmentGridGeometry.cardWidth(328 * density, 8 * density, 144 * density));
			assertEquals(1, AttachmentGridGeometry.columns(288 * density, 8 * density, 144 * density));
		}
	}
	@Test public void wideWindowsStillUseTwoColumnsAndNeverOverflowWidth() {
		for (int width : new int[] {296, 328, 361, 380, 600, 1200}) {
			assertEquals(2, AttachmentGridGeometry.columns(width, 8, 144));
			int card = AttachmentGridGeometry.cardWidth(width, 8, 144);
			assertTrue(card >= 144);
			assertTrue(card * 2 + 8 <= width);
		}
	}
	@Test public void emptyOrTransientMeasurementCannotProduceNegativeWidth() {
		assertEquals(1, AttachmentGridGeometry.columns(0, 8, 144));
		assertEquals(0, AttachmentGridGeometry.cardWidth(0, 8, 144));
		assertEquals(0, AttachmentGridGeometry.cardWidth(-1, 8, 144));
	}
}
