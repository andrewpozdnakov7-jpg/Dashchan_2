package com.mishiranu.dashchan.ui.posting;

import static org.junit.Assert.assertEquals;
import org.junit.Test;

public class AttachmentDisplayNameTest {
	@Test public void onlyTheFinalExtensionIsRemovedWithoutPreTruncatingTheCaption() {
		for (String[] pair : new String[][] {
				{"(16).jpg", "(16)"}, {"0074.jpg", "0074"},
				{"1626769219392_big.jpg", "1626769219392_big"},
				{"16598733197630.jpg", "16598733197630"}, {"holiday.final.PNG", "holiday.final"},
				{"screenshot.webp", "screenshot"}, {"video.webm", "video"},
				{"фото.отпуск.jpeg", "фото.отпуск"}, {"filename", "filename"}}) {
			assertEquals(pair[1], AttachmentDisplayName.withoutExtension(pair[0]));
		}
	}
	@Test public void emptyDotfilesTrailingDotsAndUnexpectedPathsAreNotDestroyed() {
		for (String value : new String[] {"", ".", "..", ".hidden", "filename.", "folder/file.jpg",
				"folder\\file.jpg", "content://provider/file.jpg"}) {
			assertEquals(value, AttachmentDisplayName.withoutExtension(value));
		}
		assertEquals("", AttachmentDisplayName.withoutExtension(null));
		assertEquals(".hidden", AttachmentDisplayName.withoutExtension(".hidden.png"));
	}
}
