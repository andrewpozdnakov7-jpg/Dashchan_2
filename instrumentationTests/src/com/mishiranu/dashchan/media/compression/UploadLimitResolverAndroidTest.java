package com.mishiranu.dashchan.media.compression;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import org.junit.Test;
import org.junit.runner.RunWith;
import static org.junit.Assert.*;

/** Exercise Android ICU: desktop java.util.regex accepts some patterns rejected on a phone. */
@RunWith(AndroidJUnit4.class)
public class UploadLimitResolverAndroidTest {
	@Test public void androidIcuParsesRealBoardObjectSyntax() {
		assertEquals(41_943_040L, UploadLimitResolver.parseBoardBytes(
				"<script>\n_CFG.BOARD = {\nNAME:'mobi',\nMAXFILESSIZE:parseInt(\"40960\"),\nMAXCOMMENT:15000\n};\n</script>"));
		assertEquals(41_943_040L, UploadLimitResolver.parseBoardBytes(
				"<script>_CFG.BOARD={MAXFILESSIZE:40960};</script>"));
	}
	@Test public void unknownPageAndPostTextDoNotBecomeLimits() {
		assertEquals(0, UploadLimitResolver.parseBoardBytes("<html>challenge</html>"));
		assertEquals(0, UploadLimitResolver.parseBoardBytes("<p>MAXFILESSIZE:999999,</p>"));
		assertEquals(0, UploadLimitResolver.parseBoardBytes("<script>_CFG.BOARD={MAXFILESSIZE:1e9};</script>"));
	}
}
