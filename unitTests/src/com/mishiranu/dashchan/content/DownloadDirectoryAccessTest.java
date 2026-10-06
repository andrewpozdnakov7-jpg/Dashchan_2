package com.mishiranu.dashchan.content;

import static org.junit.Assert.*;
import java.util.Arrays;
import java.util.Collections;
import org.junit.Test;

/** Actual SAF selection logic on grant snapshots; obtaining/validating grants still needs Android. */
public class DownloadDirectoryAccessTest {
	private static final String DOWNLOAD = "content://example.documents/tree/download";
	private static final String ARCHIVE = "content://example.documents/tree/archive";
	private static final String OTHER = "content://example.documents/tree/other";

	private static DownloadDirectoryAccess.Grant grant(String uri) {
		return new DownloadDirectoryAccess.Grant(uri, true, true, true);
	}

	@Test public void configuredVerifiedTreeIsKeptEvenWithOtherGrants() {
		DownloadDirectoryAccess.Selection result = DownloadDirectoryAccess.select(DOWNLOAD,
				Arrays.asList(grant(DOWNLOAD), grant(OTHER)), Collections.emptySet());
		assertEquals(DOWNLOAD, result.uri);
		assertFalse(result.clearConfiguredUri);
	}

	@Test public void restoredOrRevokedUriWithoutGrantIsCleared() {
		DownloadDirectoryAccess.Selection result = DownloadDirectoryAccess.select(DOWNLOAD,
				Collections.emptyList(), Collections.emptySet());
		assertNull(result.uri);
		assertTrue(result.clearConfiguredUri);
	}

	@Test public void staleConfiguredTreeDoesNotSelectUnrelatedGrant() {
		DownloadDirectoryAccess.Selection result = DownloadDirectoryAccess.select(DOWNLOAD,
				Collections.singletonList(grant(OTHER)), Collections.emptySet());
		assertNull(result.uri);
		assertTrue(result.clearConfiguredUri);
	}

	@Test public void readOnlyWriteOnlyAndUnavailableTreesAreNotAccepted() {
		for (DownloadDirectoryAccess.Grant grant : Arrays.asList(
				new DownloadDirectoryAccess.Grant(DOWNLOAD, true, false, true),
				new DownloadDirectoryAccess.Grant(DOWNLOAD, false, true, true),
				new DownloadDirectoryAccess.Grant(DOWNLOAD, true, true, false))) {
			DownloadDirectoryAccess.Selection result = DownloadDirectoryAccess.select(DOWNLOAD,
					Collections.singletonList(grant), Collections.emptySet());
			assertNull(result.uri);
			assertTrue(result.clearConfiguredUri);
		}
	}

	@Test public void solePersistedTreeIsRecoveredExcludingArchiveTrees() {
		DownloadDirectoryAccess.Selection result = DownloadDirectoryAccess.select(null,
				Arrays.asList(grant(ARCHIVE), grant(DOWNLOAD)), Collections.singleton(ARCHIVE));
		assertEquals(DOWNLOAD, result.uri);
		assertFalse(result.clearConfiguredUri);
	}

	@Test public void ambiguousGrantsRequireExplicitSelection() {
		DownloadDirectoryAccess.Selection result = DownloadDirectoryAccess.select(null,
				Arrays.asList(grant(DOWNLOAD), grant(OTHER)), Collections.emptySet());
		assertNull(result.uri);
		assertFalse(result.clearConfiguredUri);
	}

	@Test public void invalidAndArchiveOnlyGrantsAreNotRecovered() {
		DownloadDirectoryAccess.Selection result = DownloadDirectoryAccess.select(null, Arrays.asList(
				grant(ARCHIVE), new DownloadDirectoryAccess.Grant(OTHER, true, false, true)),
				Collections.singleton(ARCHIVE));
		assertNull(result.uri);
	}

	@Test public void freshInstallRequiresFolderSelectionWithoutClearingAnything() {
		DownloadDirectoryAccess.Selection result = DownloadDirectoryAccess.select(null,
				Collections.emptyList(), Collections.emptySet());
		assertNull(result.uri);
		assertFalse(result.clearConfiguredUri);
	}

	@Test public void explicitDownloadsTreeCanAlsoBeAnArchiveTree() {
		assertEquals(ARCHIVE, DownloadDirectoryAccess.select(ARCHIVE,
				Collections.singletonList(grant(ARCHIVE)), Collections.singleton(ARCHIVE)).uri);
	}

	@Test public void duplicateSnapshotsDoNotInventAmbiguity() {
		assertEquals(DOWNLOAD, DownloadDirectoryAccess.select("",
				Arrays.asList(grant(DOWNLOAD), grant(DOWNLOAD)), Collections.emptySet()).uri);
	}

	@Test public void nullAndEmptyGrantUrisAreIgnored() {
		DownloadDirectoryAccess.Selection result = DownloadDirectoryAccess.select(null,
				Arrays.asList(grant(null), grant("")), Collections.emptySet());
		assertNull(result.uri);
		assertFalse(result.clearConfiguredUri);
	}

	@Test public void invalidCandidatesDoNotPreventRecoveringSoleUsableTree() {
		assertEquals(DOWNLOAD, DownloadDirectoryAccess.select(null,
				Arrays.asList(new DownloadDirectoryAccess.Grant(OTHER, true, true, false), grant(DOWNLOAD)),
				Collections.emptySet()).uri);
	}
}
