package com.mishiranu.dashchan.content;

import static org.junit.Assert.*;
import java.io.File;
import java.nio.file.Files;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

public class ShareFileCopyTest {
	@Rule public TemporaryFolder folder = new TemporaryFolder();

	@Test public void normalCopyPreservesBytes() throws Exception {
		File source = folder.newFile("source #%.bin");
		byte[] bytes = {1, 2, 3, 4};
		Files.write(source.toPath(), bytes);
		File destination = new File(folder.getRoot(), "share.bin");
		assertTrue(ShareFileCopy.copy(source, destination));
		assertArrayEquals(bytes, Files.readAllBytes(destination.toPath()));
		assertArrayEquals(bytes, Files.readAllBytes(source.toPath()));
	}

	@Test public void missingSourceRemovesStagingFile() throws Exception {
		File destination = folder.newFile();
		Files.write(destination.toPath(), new byte[] {1});
		assertFalse(ShareFileCopy.copy(new File(folder.getRoot(), "missing"), destination));
		assertFalse(destination.exists());
	}

	@Test public void unavailableDestinationDoesNotProduceSuccess() throws Exception {
		File source = folder.newFile();
		File destination = new File(folder.getRoot(), "missing-parent/share.bin");
		assertFalse(ShareFileCopy.copy(source, destination));
		assertFalse(destination.exists());
		assertTrue(source.exists());
	}
}
