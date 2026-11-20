package com.mishiranu.dashchan.media.compression;

import static org.junit.Assert.*;
import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.InterruptedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Arrays;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

/** Container-boundary/ownership contracts; no codec, Android UI, sending or network. */
public class VideoInputNormalizerTest {
	@Rule public final TemporaryFolder temp = new TemporaryFolder();
	private static byte[] box(String type, byte[] payload) throws Exception {
		ByteArrayOutputStream bytes = new ByteArrayOutputStream();
		DataOutputStream output = new DataOutputStream(bytes);
		output.writeInt(8 + payload.length); output.writeBytes(type); output.write(payload); return bytes.toByteArray();
	}
	private static byte[] concat(byte[]... parts) throws Exception {
		ByteArrayOutputStream output = new ByteArrayOutputStream(); for (byte[] part : parts) output.write(part); return output.toByteArray();
	}
	private static byte[] movie() throws Exception {
		return concat(box("ftyp", "isom0000".getBytes(StandardCharsets.US_ASCII)), box("moov", new byte[0]),
				box("mdat", new byte[] {0, 1, 2, 3, 4}));
	}
	private File file(byte[] data) throws Exception {
		File file = temp.newFile(); Files.write(file.toPath(), data); return file;
	}
	@Test public void recognizesDifferentTextTailLengthsAfterCompleteBoxes() throws Exception {
		byte[] movie = movie();
		for (int length : new int[] {1, 6, 17, 4096, 65536}) {
			byte[] suffix = new byte[length]; Arrays.fill(suffix, (byte) 'x');
			File original = file(concat(movie, suffix));
			VideoInputNormalizer.Inspection result = VideoInputNormalizer.inspect(original, () -> false);
			assertTrue(result.needsCopy()); assertEquals(movie.length, result.readableBytes);
			assertEquals(movie.length + length, result.originalBytes);
			assertArrayEquals(concat(movie, suffix), Files.readAllBytes(original.toPath()));
		}
	}
	@Test public void validUnknownExtendedAndToEndBoxesArePreserved() throws Exception {
		ByteArrayOutputStream bytes = new ByteArrayOutputStream(); DataOutputStream output = new DataOutputStream(bytes);
		output.writeInt(1); output.writeBytes("uuid"); output.writeLong(35); output.write(new byte[19]);
		byte[] valid = concat(movie(), box("abcd", "valid metadata".getBytes(StandardCharsets.US_ASCII)), bytes.toByteArray());
		assertFalse(VideoInputNormalizer.inspect(file(valid), () -> false).needsCopy());
		bytes.reset(); output.writeInt(0); output.writeBytes("mdat"); output.writeBytes("valid media through EOF");
		assertFalse(VideoInputNormalizer.inspect(file(concat(movie(), bytes.toByteArray())), () -> false).needsCopy());
	}
	@Test public void neverRepairsMissingMediaBoxesOrBinaryOrUnprovenTail() throws Exception {
		ByteArrayOutputStream bytes = new ByteArrayOutputStream(); DataOutputStream output = new DataOutputStream(bytes);
		output.writeInt(200); output.writeBytes("mdat"); output.writeBytes("cut off");
		for (byte[] data : new byte[][] {
				concat(movie(), bytes.toByteArray()), concat(movie(), new byte[] {1, 2, 3}),
				concat(box("ftyp", "isom0000".getBytes(StandardCharsets.US_ASCII)), "suffix".getBytes(StandardCharsets.US_ASCII)),
				"not an ISO BMFF video".getBytes(StandardCharsets.US_ASCII)}) {
			assertFalse(VideoInputNormalizer.inspect(file(data), () -> false).needsCopy());
		}
	}
	@Test public void normalizedInputsOwnOnlyTheirCopiesAndKeepOtherActiveInputs() throws Exception {
		byte[] valid = movie(), originalBytes = concat(valid, "tail".getBytes(StandardCharsets.US_ASCII));
		File original = file(originalBytes), directory = new File(temp.getRoot(), "inputs");
		VideoInputNormalizer.Input first = VideoInputNormalizer.prepare(original, directory, () -> false);
		VideoInputNormalizer.Input second = VideoInputNormalizer.prepare(original, directory, () -> false);
		try {
			assertTrue(first.temporary); assertTrue(second.temporary); assertTrue(first.file.isFile());
			assertNotEquals(first.file, second.file);
			File stale = new File(directory, "normalize-00000000-0000-0000-0000-000000000000.mp4");
			assertTrue(stale.createNewFile());
			VideoInputNormalizer.cleanup(directory);
			assertFalse(stale.exists()); assertTrue(first.file.exists()); assertTrue(second.file.exists());
			assertArrayEquals(valid, Files.readAllBytes(first.file.toPath()));
			assertArrayEquals(originalBytes, Files.readAllBytes(original.toPath()));
			first.close(); assertFalse(first.file.exists()); assertTrue(second.file.isFile());
		} finally { first.close(); second.close(); }
		assertTrue(original.isFile()); assertEquals(0, directory.list().length);
	}
	@Test public void cleanInputsNeedNoCopyAndCancellationRemovesPartialCopies() throws Exception {
		File clean = file(movie()), directory = new File(temp.getRoot(), "inputs");
		VideoInputNormalizer.Input input = VideoInputNormalizer.prepare(clean, directory, () -> false);
		assertFalse(input.temporary); assertEquals(clean, input.file); input.close();
		assertTrue(clean.isFile()); assertFalse(directory.exists());
		File original = file(concat(movie(), "suffix".getBytes(StandardCharsets.US_ASCII)));
		try {
			VideoInputNormalizer.prepare(original, directory, () -> {
				String[] files = directory.list(); return files != null && files.length > 0;
			});
			fail("Cancellation must stop copying");
		} catch (InterruptedIOException expected) {}
		assertTrue(original.isFile()); assertEquals(0, directory.list().length);
	}
}
