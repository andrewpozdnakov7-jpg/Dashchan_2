package chan.util;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class StringUtilsTest {
	@Test
	public void emptyHelpersHandleNullAndEmptyValues() {
		assertTrue(StringUtils.isEmpty(null));
		assertTrue(StringUtils.isEmpty(""));
		assertFalse(StringUtils.isEmpty("text"));
		assertTrue(StringUtils.isEmptyOrWhitespace(" \n\t"));
		assertEquals("", StringUtils.emptyIfNull(null));
		assertNull(StringUtils.nullIfEmpty(""));
		assertEquals("text", StringUtils.nullIfEmpty("text"));
	}

	@Test
	public void nearestIndexFindsEarliestStringOrCharacter() {
		assertEquals(2, StringUtils.nearestIndexOf("abcdef", 0, "de", "cd"));
		assertEquals(4, StringUtils.nearestIndexOf("abcdef", 3, 'e', 'f'));
		assertEquals(-1, StringUtils.nearestIndexOf("abcdef", 4, "bc", "cd"));
	}

	@Test
	public void indexOfFindsSubsequence() {
		assertEquals(1, StringUtils.indexOf("abcabc", 0, "bc"));
		assertEquals(3, StringUtils.indexOf("abcabc", 2, "abc"));
		assertEquals(-1, StringUtils.indexOf("abcabc", 0, "ac"));
		assertEquals(2, StringUtils.indexOf("abc", 2, ""));
	}

	@Test
	public void indexOfFindsOverlappingPrefixes() {
		assertEquals(1, StringUtils.indexOf(new StringBuilder("aab"), 0, "ab"));
		assertEquals(1, StringUtils.indexOf("aaab", 0, new StringBuilder("aab")));
		assertEquals(2, StringUtils.indexOf(new StringBuilder("ababac"), 0, new StringBuilder("abac")));
	}

	@Test
	public void indexOfMatchesStringForShortInputsAndStartBoundaries() {
		String[] strings = {"", "a", "b", "aa", "ab", "aab", "aaab", "ababac", "aaaaab", "\u0430\u0430\u0431"};
		for (String string : strings) {
			for (String target : strings) {
				for (int start = -2; start <= string.length() + 2; start++) {
					int expected = string.indexOf(target, start);
					assertEquals(expected, StringUtils.indexOf(string, start, target));
					assertEquals(expected, StringUtils.indexOf(new StringBuilder(string), start, target));
					assertEquals(expected, StringUtils.indexOf(string, start, new StringBuilder(target)));
				}
			}
		}
	}

	@Test
	public void formatHexUsesLowercaseAndLeadingZeroes() {
		assertEquals("000fff", StringUtils.formatHex(new byte[] {0x00, 0x0f, (byte) 0xff}));
		assertNull(StringUtils.formatHex(null));
	}

	@Test
	public void stripTrailingZerosKeepsMeaningfulPart() {
		assertEquals("10.5", StringUtils.stripTrailingZeros("10.5000"));
		assertEquals("10", StringUtils.stripTrailingZeros("10.000"));
		assertEquals("12", StringUtils.stripTrailingZeros("12"));
	}

	@Test
	public void escapeFileKeepsPathSeparatorsOnlyInPathMode() {
		assertEquals("a_b_c_d", StringUtils.escapeFile("a/b:c?d", false));
		assertEquals("a/b_c_d", StringUtils.escapeFile("a/b:c?d", true));
	}

	@Test
	public void boardNamesAndExtensionsAreNormalized() {
		assertEquals("b", StringUtils.validateBoardName("/b/"));
		assertEquals("test_board", StringUtils.validateBoardName("test_board"));
		assertNull(StringUtils.validateBoardName("bad/name"));
		assertEquals("gz", StringUtils.getFileExtension("/tmp/archive.tar.gz"));
		assertNull(StringUtils.getFileExtension("/tmp/noextension"));
		assertNull(StringUtils.getFileExtension("/tmp.name/file"));
	}

	@Test
	public void linkifyWrapsPlainLinksAndLeavesExistingLinksAlone() {
		assertEquals("see <a href=\"http://example.com/test\">http://example.com/test</a>.",
				StringUtils.linkify("see http://example.com/test."));
		assertEquals("<a href=\"https://example.com\">https://example.com</a>",
				StringUtils.linkify("<a href=\"https://example.com\">https://example.com</a>"));
	}
}
