package org.brotli.dec;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import org.junit.Test;

public class BrotliDecoderTest {
	// Test vectors from Google's Brotli v1.2.0 DecodeTest.java.
	private static final String COMPRESSED_FOX =
			"\u001B*\u0000\u0000\u0004\u0004\u00BAF:\u0085\u0003\u00E9\u00FA\f\u0091\u0002H\u0011,"
			+ "\u00F3\u008A:\u00A3V\u007F\u001A\u00AE\u00BF\u00A4\u00AB\u008EM\u00BF\u00ED\u00E2\u0004K"
			+ "\u0091\u00FF\u0087\u00E9\u001E";

	private static byte[] compressedBytes(String encoded) {
		byte[] result = new byte[encoded.length()];
		for (int i = 0; i < encoded.length(); i++) {
			result[i] = (byte) encoded.charAt(i);
		}
		return result;
	}

	private static byte[] decode(String encoded, boolean singleByteReads) throws IOException {
		ByteArrayOutputStream output = new ByteArrayOutputStream();
		try (BrotliInputStream input = new BrotliInputStream(
				new ByteArrayInputStream(compressedBytes(encoded)))) {
			if (singleByteReads) {
				int next;
				while ((next = input.read()) != -1) {
					output.write(next);
				}
			} else {
				byte[] buffer = new byte[7];
				int count;
				while ((count = input.read(buffer)) != -1) {
					output.write(buffer, 0, count);
				}
			}
		}
		return output.toByteArray();
	}

	@Test
	public void decodesEmptyResponse() throws IOException {
		assertEquals(0, decode("\u0006", false).length);
	}

	@Test
	public void decodesRepeatedContentAcrossSmallReadBuffers() throws IOException {
		byte[] expected = "XXXXXXXXXXYYYYYYYYYY".getBytes(StandardCharsets.UTF_8);
		String compressed = "\u001B\u0013\u0000\u0000\u00A4\u00B0\u00B2\u00EA\u0081G\u0002\u008A";
		assertArrayEquals(expected, decode(compressed, false));
	}

	@Test
	public void decodesHttpBodyWithBufferedAndSingleByteReads() throws IOException {
		byte[] expected = "The quick brown fox jumps over the lazy dog"
				.getBytes(StandardCharsets.UTF_8);
		assertArrayEquals(expected, decode(COMPRESSED_FOX, false));
		assertArrayEquals(expected, decode(COMPRESSED_FOX, true));
	}
}
