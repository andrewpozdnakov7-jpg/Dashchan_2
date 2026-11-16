package com.mishiranu.dashchan.content;

import static org.junit.Assert.*;
import android.content.Context;
import android.graphics.Bitmap;
import android.net.Uri;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import chan.content.Chan;
import com.mishiranu.dashchan.content.model.GalleryItem;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.UUID;
import org.junit.Test;
import org.junit.runner.RunWith;

/** Read-only browser snapshot and local URI contracts. Source fixtures, NOT RUN. */
@RunWith(AndroidJUnit4.class)
public class CachedMediaTest {
	private static File png(File directory, String name, long modified) throws IOException {
		File file = new File(directory, name);
		Bitmap image = Bitmap.createBitmap(8, 8, Bitmap.Config.ARGB_8888);
		try (FileOutputStream output = new FileOutputStream(file)) { assertTrue(image.compress(Bitmap.CompressFormat.PNG, 100, output)); }
		finally { image.recycle(); }
		assertTrue(file.setLastModified(modified)); return file;
	}
	@Test public void listsCompletedMediaNewestFirstWithoutTouchingOrCopyingFiles() throws Exception {
		Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
		File directory = new File(context.getCacheDir(), "cache-browser-fixture-" + UUID.randomUUID());
		assertTrue(directory.mkdir());
		try {
			String older = new String(new char[64]).replace('\0', 'a'), newer = new String(new char[64]).replace('\0', 'b');
			File old = png(directory, older, 1000000L), next = png(directory, newer, 2000000L);
			png(directory, older + ".part", 3000000L); png(directory, "unmanaged.png", 4000000L);
			new File(directory, new String(new char[64]).replace('\0', 'c')).mkdir();
			ArrayList<GalleryItem> items = CachedMedia.scan(directory, () -> false);
			assertEquals(2, items.size()); Chan chan = Chan.get(null);
			// A writer using the final hashed name is also excluded until the completed index accepts it.
			assertEquals(1, CachedMedia.scan(directory, () -> false, file -> !file.equals(next)).size());
			assertEquals(Uri.fromFile(next), items.get(0).getFileUri(chan));
			assertEquals(Uri.fromFile(old), items.get(1).getFileUri(chan));
			assertTrue(items.get(0).isImage(chan));
			assertEquals(next, CacheManager.getInstance().getMediaFile(items.get(0).getFileUri(chan), true));
			assertEquals(1000000L, old.lastModified()); assertEquals(2000000L, next.lastModified());
			assertEquals(5, directory.listFiles().length);
			assertTrue(next.delete()); // A vanished cached source remains local; it is not a URL to retry.
			assertEquals(next, CacheManager.getInstance().getMediaFile(items.get(0).getFileUri(chan), false));
			assertEquals(1, CachedMedia.scan(directory, () -> false).size());
			try { CachedMedia.scan(directory, () -> true); fail("Cancelled scan returned a partial gallery"); }
			catch (InterruptedException expected) {}
		} finally {
			File[] files = directory.listFiles(); if (files != null) for (File file : files) file.delete();
			directory.delete();
		}
	}
	@Test public void mediaHeadersDistinguishImagesVideosAndNonMedia() {
		assertEquals("gif", CachedMedia.extension(new byte[]{'G','I','F','8','9','a'}, 6));
		assertNull(CachedMedia.extension(new byte[]{'G','I','F','8','9','a'}, 3));
		assertEquals("mkv", CachedMedia.extension(new byte[]{0x1a,0x45,(byte)0xdf,(byte)0xa3}, 4));
		for (String brand : new String[]{"isom", "mp42", "qt  ", "avif", "heic", "M4A "}) {
			byte[] header = ("0000ftyp" + brand).getBytes(java.nio.charset.StandardCharsets.US_ASCII);
			String extension = CachedMedia.extension(header, header.length);
			if (brand.equals("qt  ")) assertEquals("mov", extension);
			else if (brand.equals("isom") || brand.equals("mp42")) assertEquals("mp4", extension);
			else assertNull(extension);
		}
		byte[] html = "<html>not cached media</html>".getBytes(java.nio.charset.StandardCharsets.UTF_8);
		assertNull(CachedMedia.extension(html, html.length));
	}
}
