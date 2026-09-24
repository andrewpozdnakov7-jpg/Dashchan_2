package com.mishiranu.dashchan.content;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.fail;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import org.junit.Assume;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

public class ProviderFileResolverTest {
	@Rule
	public final TemporaryFolder temporary = new TemporaryFolder();

	@Test
	public void independentFilesResolveWithoutLastFileState() throws Exception {
		File root = temporary.newFolder("downloads");
		File first = new File(root, "first.jpg");
		File second = new File(root, "second.webm");
		Files.write(first.toPath(), new byte[] {1});
		Files.write(second.toPath(), new byte[] {2, 3});
		String firstPath = ProviderFileResolver.relativePath(root, first);
		String secondPath = ProviderFileResolver.relativePath(root, second);
		assertEquals(second.getCanonicalFile(), ProviderFileResolver.resolve(root, secondPath));
		assertEquals(first.getCanonicalFile(), ProviderFileResolver.resolve(new File(root.getPath()), firstPath));
	}

	@Test
	public void nestedAndSpecialNamesRoundTrip() throws Exception {
		File root = temporary.newFolder("downloads");
		File nested = new File(root, "board");
		Files.createDirectory(nested.toPath());
		File file = new File(nested, "\u0444\u043e\u0442\u043e #50%.jpg");
		Files.write(file.toPath(), new byte[] {1});
		String path = ProviderFileResolver.relativePath(root, file);
		assertEquals("board/\u0444\u043e\u0442\u043e #50%.jpg", path);
		assertEquals(file.getCanonicalFile(), ProviderFileResolver.resolve(root, path));
	}

	@Test
	public void rootAndSiblingWithSamePrefixAreNotChildren() throws Exception {
		File root = temporary.newFolder("downloads");
		File sibling = temporary.newFolder("downloads-other");
		for (File file : new File[] {root, new File(sibling, "private.jpg")}) {
			try {
				ProviderFileResolver.relativePath(root, file);
				fail("Non-child path must be rejected");
			} catch (IOException expected) {}
		}
	}

	@Test
	public void traversalAbsoluteAndMissingPathsAreRejected() throws Exception {
		File root = temporary.newFolder("downloads");
		for (String path : new String[] {"", "/file", "../file", "a/../file", "./file", "a//file",
				"a\\file", "missing.jpg", "a\0b"}) {
			assertRejected(root, path);
		}
		assertRejected(null, "file");
		assertRejected(root, null);
	}

	@Test
	public void escapingSymlinkIsRejectedWhenSupported() throws Exception {
		File root = temporary.newFolder("downloads");
		File outside = temporary.newFile("outside.jpg");
		File link = new File(root, "link.jpg");
		try {
			Files.createSymbolicLink(link.toPath(), outside.toPath());
		} catch (IOException | UnsupportedOperationException | SecurityException e) {
			Assume.assumeNoException(e);
		}
		assertRejected(root, "link.jpg");
	}

	private static void assertRejected(File root, String path) throws Exception {
		try {
			ProviderFileResolver.resolve(root, path);
			fail("Invalid or unavailable path must be rejected");
		} catch (IOException expected) {}
	}
}
