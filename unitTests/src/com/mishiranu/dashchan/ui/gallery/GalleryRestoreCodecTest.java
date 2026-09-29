package com.mishiranu.dashchan.ui.gallery;

import org.junit.Test;
import static org.junit.Assert.*;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.Arrays;
import java.util.List;

public class GalleryRestoreCodecTest {
	private static GalleryRestoreCodec.Item item(String uri, String post) {
		return new GalleryRestoreCodec.Item(uri, null, "фото.jpg", "b", "100", post, null, 640, 480, 512);
	}
	private static byte[] encode(GalleryRestoreCodec.Snapshot value) throws IOException {
		ByteArrayOutputStream out = new ByteArrayOutputStream();
		GalleryRestoreCodec.write(out, value);
		return out.toByteArray();
	}
	@Test public void descriptorsRoundTripWithoutAndroidOrMedia() throws Exception {
		GalleryRestoreCodec.Snapshot value = new GalleryRestoreCodec.Snapshot("test", List.of(
				item("https://example.invalid/a.jpg", "101"), item("content://test/image/2", null)));
		assertEquals(value, GalleryRestoreCodec.read(new ByteArrayInputStream(encode(value))));
	}
	@Test public void selectedAttachmentSurvivesReorderAndDuplicateUrl() {
		GalleryRestoreCodec.Item first = item("https://example.invalid/a.jpg", "101");
		GalleryRestoreCodec.Item second = item("https://example.invalid/a.jpg", "102");
		assertEquals(1, GalleryRestoreCodec.find(List.of(second, first), first, 0));
		assertEquals(1, GalleryRestoreCodec.find(List.of(first, second), null, 100));
		assertEquals(-1, GalleryRestoreCodec.find(List.of(), first, 100));
	}
	@Test public void tokenNeverAcceptsPaths() {
		assertTrue(GalleryRestoreCodec.validToken("00000000-0000-0000-0000-000000000000"));
		assertFalse(GalleryRestoreCodec.validToken("../snapshot"));
		assertFalse(GalleryRestoreCodec.validToken("C:\\snapshot"));
		assertFalse(GalleryRestoreCodec.validToken(null));
	}
	@Test public void truncatedAndUnknownFilesAreRejected() throws Exception {
		byte[] data = encode(new GalleryRestoreCodec.Snapshot("test", List.of(item("test", "1"))));
		assertThrows(IOException.class, () -> GalleryRestoreCodec.read(new ByteArrayInputStream(Arrays.copyOf(data, data.length - 1))));
		data[0] = 0;
		assertThrows(IOException.class, () -> GalleryRestoreCodec.read(new ByteArrayInputStream(data)));
	}
	@Test public void emptyOrOversizedDescriptorsAreRejected() {
		assertThrows(IOException.class, () -> encode(new GalleryRestoreCodec.Snapshot("test", List.of())));
		assertThrows(IOException.class, () -> encode(new GalleryRestoreCodec.Snapshot("test", List.of(item("x".repeat(8193), "1")))));
	}
	@Test public void closedWindowCancelsReading() throws Exception {
		byte[] data = encode(new GalleryRestoreCodec.Snapshot("test", List.of(item("test", "1"))));
		assertThrows(java.util.concurrent.CancellationException.class,
				() -> GalleryRestoreCodec.read(new ByteArrayInputStream(data), () -> true));
	}
}
