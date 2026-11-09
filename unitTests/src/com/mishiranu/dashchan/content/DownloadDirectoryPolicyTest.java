package com.mishiranu.dashchan.content;

import static org.junit.Assert.*;
import java.util.EnumMap;
import org.junit.Test;

public class DownloadDirectoryPolicyTest {
	@Test public void disabledRoutingPreservesEveryExistingDestination() {
		DownloadDirectoryPolicy policy = new DownloadDirectoryPolicy(false, new EnumMap<>(DownloadDirectoryPolicy.Type.class));
		for (DownloadDirectoryPolicy.Type type : DownloadDirectoryPolicy.Type.values()) {
			assertNull(policy.resolve(type, null));
			assertEquals("", policy.resolve(type, ""));
			assertEquals("site/board/thread", policy.resolve(type, "site/board/thread"));
		}
	}

	@Test public void typeFolderWrapsRatherThanReplacesThreadSubdirectory() {
		DownloadDirectoryPolicy policy = new DownloadDirectoryPolicy(true, new EnumMap<>(DownloadDirectoryPolicy.Type.class));
		assertEquals("Images/site/board/thread", policy.resolve(DownloadDirectoryPolicy.Type.IMAGES, "site/board/thread"));
		assertEquals("Videos", policy.resolve(DownloadDirectoryPolicy.Type.VIDEOS, null));
		assertEquals("Backups", policy.resolve(DownloadDirectoryPolicy.Type.BACKUPS, ""));
	}

	@Test public void queuedSettingsSnapshotCannotBeChangedByEditingTheOriginalMap() {
		EnumMap<DownloadDirectoryPolicy.Type, String> names = new EnumMap<>(DownloadDirectoryPolicy.Type.class);
		names.put(DownloadDirectoryPolicy.Type.IMAGES, " Картинки ");
		DownloadDirectoryPolicy queued = new DownloadDirectoryPolicy(true, names);
		names.put(DownloadDirectoryPolicy.Type.IMAGES, "New images");
		assertEquals("Картинки/thread", queued.resolve(DownloadDirectoryPolicy.Type.IMAGES, "thread"));
		assertEquals("New images/thread", new DownloadDirectoryPolicy(true, names)
				.resolve(DownloadDirectoryPolicy.Type.IMAGES, "thread"));
	}

	@Test public void extensionsAreCaseInsensitiveAndMediaListsDoNotDependOnPlayerSupport() {
		for (String name : new String[]{"a.JPG", "a.jpeg", "a.AVIF", "a.HEIC", "a.webp"}) {
			assertEquals(DownloadDirectoryPolicy.Type.IMAGES, DownloadDirectoryPolicy.classify(name, null));
		}
		for (String name : new String[]{"a.MP4", "a.mkv", "a.webm", "a.MOV"}) {
			assertEquals(DownloadDirectoryPolicy.Type.VIDEOS, DownloadDirectoryPolicy.classify(name, null));
		}
		assertEquals(DownloadDirectoryPolicy.Type.AUDIO, DownloadDirectoryPolicy.classify("a.OGG", "application/ogg"));
		assertEquals(DownloadDirectoryPolicy.Type.IMAGES, DownloadDirectoryPolicy.classify("a.unknown", "image/example"));
		assertEquals(DownloadDirectoryPolicy.Type.OTHER, DownloadDirectoryPolicy.classify("no-extension", null));
	}

	@Test public void aDownloadedZipNeverBecomesAnApplicationBackup() {
		assertEquals(DownloadDirectoryPolicy.Type.OTHER,
				DownloadDirectoryPolicy.classify("backup-123456789.zip", "application/zip"));
		assertEquals(DownloadDirectoryPolicy.Type.OTHER, DownloadDirectoryPolicy.classify("archive.ZIP", null));
	}

	@Test public void folderNamesCannotEscapeOrIntroduceNestedPaths() {
		for (String name : new String[]{"", "   ", ".", "..", "../Images", "a/b", "a\\b", "/root", "C:folder", "a\u0000b"}) {
			assertNull(name, DownloadDirectoryPolicy.normalizeName(name));
		}
		EnumMap<DownloadDirectoryPolicy.Type, String> names = new EnumMap<>(DownloadDirectoryPolicy.Type.class);
		names.put(DownloadDirectoryPolicy.Type.BACKUPS, "../backup");
		assertEquals("Backups", new DownloadDirectoryPolicy(true, names).resolve(DownloadDirectoryPolicy.Type.BACKUPS, null));
		assertEquals("Мои копии", DownloadDirectoryPolicy.normalizeName(" Мои копии "));
	}
}
