package com.mishiranu.dashchan.media.compression;

import org.junit.Test;
import static org.junit.Assert.*;

public class UploadLimitResolverTest {
	@Test public void boardFormUsesKibibytesAndCombinedSize() {
		assertEquals(40L * 1024 * 1024, UploadLimitResolver.parseBoardBytes("<script>_CFG.BOARD = { MAXFILESSIZE : parseInt(\"40960\"), MAXCOMMENT: 15000 };</script>"));
	}
	@Test public void unknownOrInvalidFormCannotInventAnUploadLimit() {
		assertEquals(0, UploadLimitResolver.parseBoardBytes("<html>challenge</html>"));
		assertEquals(0, UploadLimitResolver.parseBoardBytes("<script>_CFG.BOARD = {MAXFILESSIZE: -40};</script>"));
		assertEquals(0, UploadLimitResolver.parseBoardBytes("<script>_CFG.BOARD = {MAXFILESSIZE: 1e9};</script>"));
		assertEquals(0, UploadLimitResolver.parseBoardBytes("<p>MAXFILESSIZE: 999999,</p>"));
	}
	@Test public void compactAndMultilineBoardObjectsHaveLiteralClosingBraces() {
		assertEquals(41_943_040L, UploadLimitResolver.parseBoardBytes("<script>_CFG.BOARD={MAXFILESSIZE:40960};</script>"));
		assertEquals(41_943_040L, UploadLimitResolver.parseBoardBytes("<script>\n_CFG.BOARD = {\nMAXFILESSIZE: parseInt('40960'),\nNAME: 'mobi'\n};\n</script>"));
	}
	@Test public void manualMegabytesAcceptDecimalCommaWithoutRoundingPastLimit() {
		assertEquals(1_500_000, CompressionSettings.bytes("1,5"));
		assertEquals(0, CompressionSettings.bytes(""));
		assertEquals(0, CompressionSettings.bytes("NaN"));
		assertEquals(0, CompressionSettings.bytes("999999999999999999999999"));
	}
	@Test(timeout = 5_000) public void repeatedUnterminatedScriptTagsCannotCauseRegexBacktracking() {
		StringBuilder html = new StringBuilder();
		for (int i = 0; i < 25_000; i++) html.append("<script ");
		assertEquals(0, UploadLimitResolver.parseBoardBytes(html.toString()));
		html.setLength(0);
		for (int i = 0; i < 25_000; i++) html.append("<script>a");
		assertEquals(0, UploadLimitResolver.parseBoardBytes(html.toString()));
	}
	@Test public void onlyRealScriptElementsSupplyBoardLimits() {
		assertEquals(0, UploadLimitResolver.parseBoardBytes(
				"<!-- <script>_CFG.BOARD={MAXFILESSIZE:999999};</script> -->"));
		assertEquals(41_943_040L, UploadLimitResolver.parseBoardBytes(
				"<SCRIPT type='text/javascript'>_CFG.BOARD={MAXFILESSIZE:40960};</SCRIPT>"));
	}
}
