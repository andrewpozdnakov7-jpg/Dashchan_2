package com.mishiranu.dashchan.content;

import static org.junit.Assert.*;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import org.junit.Test;

/** Integration source guards, not proof of system picker/permission behavior on a device. */
public class DownloadSafContractTest {
	private static File sourceFile(String path) throws Exception {
		File root = new File(".").getCanonicalFile();
		while (root != null) {
			File file = new File(root, path);
			if (file.isFile()) return file;
			root = root.getParentFile();
		}
		throw new AssertionError("Cannot find source: " + path);
	}

	private static String source(String path) throws Exception {
		return Files.readString(sourceFile(path).toPath(), StandardCharsets.UTF_8).replace("\r\n", "\n");
	}

	private static String appSource(String path) throws Exception {
		return source("src/com/mishiranu/dashchan/" + path);
	}

	private static String method(String path, String name) throws Exception {
		String text = appSource(path);
		java.util.regex.Matcher matcher = java.util.regex.Pattern.compile(
				"(?:private|public|protected) (?:static )?[\\w<>.]+ " + name + "\\(").matcher(text);
		assertTrue("Missing method: " + name, matcher.find());
		int opening = text.indexOf('{', matcher.start()), depth = 1, end = opening + 1;
		while (end < text.length() && depth > 0) {
			char c = text.charAt(end++);
			if (c == '{') depth++;
			if (c == '}') depth--;
		}
		assertEquals(0, depth);
		return text.substring(opening + 1, end - 1);
	}

	@Test public void getterChecksRealGrantsBeforeSavingTreeAndRearmsStaleSetup() throws Exception {
		String getter = method("content/Preferences.java", "getDownloadUriTree");
		assertTrue(getter.contains("getPersistedUriPermissions()"));
		assertTrue(getter.contains("uriPermission.isReadPermission()"));
		assertTrue(getter.contains("uriPermission.isWritePermission()"));
		assertTrue(getter.contains("isUriTreeAvailable("));
		assertTrue(getter.contains("DownloadDirectoryAccess.select("));
		assertTrue(getter.indexOf("if (selection.uri != null)") < getter.indexOf("PREFERENCES.edit().put(KEY_DOWNLOAD_URI_TREE"));
		assertTrue(getter.contains("if (selection.clearConfiguredUri)"));
		assertTrue(getter.contains(".remove(KEY_DOWNLOAD_URI_TREE).put(KEY_DOWNLOAD_DIRECTORY_SETUP_PROMPTED, false)"));
	}

	@Test public void setterRecordsOnlyPersistedReadableWritableAvailableTrees() throws Exception {
		String setter = method("content/Preferences.java", "setDownloadUriTree");
		assertTrue(setter.contains("(uriFlags & requiredFlags) != requiredFlags"));
		assertTrue(setter.contains("takePersistableUriPermission(uri, requiredFlags)"));
		assertTrue(setter.contains("permission.isReadPermission() && permission.isWritePermission()"));
		assertTrue(setter.contains("if (!persisted || !isUriTreeAvailable(contentResolver, uri))"));
		assertTrue(setter.indexOf("if (!persisted ||") < setter.indexOf("PREFERENCES.edit().put(KEY_DOWNLOAD_URI_TREE"));
		assertTrue(setter.contains("localArchiveUriTrees.contains("));
	}

	@Test public void setupRequiresRealPermissionAndAvailableTree() throws Exception {
		String setup = method("content/Preferences.java", "shouldRequestDownloadDirectorySetup");
		assertTrue(setup.contains("getDownloadUriTree(context)"));
		assertFalse(setup.contains("KEY_DOWNLOAD_STORAGE_MIGRATION_VERSION"));
		String availability = method("content/Preferences.java", "isUriTreeAvailable");
		assertTrue(availability.contains("DocumentsContract.isTreeUri(treeUri)"));
		assertTrue(availability.contains("contentResolver.query("));
	}

	@Test public void safSelectionCannotCreateMoveOrDeleteFilesystemDownloads() throws Exception {
		for (String name : new String[] {"getDownloadUriTree", "setDownloadUriTree", "shouldRequestDownloadDirectorySetup"}) {
			String body = method("content/Preferences.java", name);
			for (String forbidden : new String[] {"mkdirs(", ".delete(", ".renameTo(", "Uri.fromFile(", "new File("}) {
				assertFalse(name + ": " + forbidden, body.contains(forbidden));
			}
		}
	}

	@Test public void providerRetainsOnlyUpdatesAndSharePathsAndIsReadOnly() throws Exception {
		String preferences = appSource("content/Preferences.java");
		assertFalse(preferences.contains("KEY_DOWNLOAD_PATH"));
		String provider = appSource("content/FileProvider.java");
		assertFalse(provider.contains("PATH_DOWNLOADS"));
		assertFalse(provider.contains("convertDownloadsLegacyFile("));
		assertFalse(provider.contains("Preferences.getDownloadDirectoryLegacy()"));
		assertTrue(provider.contains("case PATH_UPDATES:"));
		assertTrue(provider.contains("case PATH_SHARE:"));
		assertTrue(provider.contains("default: throw new IllegalArgumentException(\"Unknown provider path\")"));
		String open = method("content/FileProvider.java", "openFile");
		assertTrue(open.contains("if (!\"r\".equals(mode))"));
		assertTrue(open.contains("ParcelFileDescriptor.MODE_READ_ONLY"));
		assertFalse(preferences.contains("KEY_DOWNLOAD_STORAGE_MIGRATION_VERSION"));
	}

	@Test public void downloadsTargetHasOnlySafAndCannotConstructRegularFile() throws Exception {
		String model = source("src/chan/util/DataFile.java");
		assertTrue(model.contains("DOWNLOADS(SafFile.SafTarget.DOWNLOADS)"));
		assertFalse(model.contains("getDownloadDirectoryLegacy"));
		assertTrue(model.contains("CACHE(CacheManager.getInstance()::getMediaDirectory)"));
		assertTrue(model.contains("UPDATES(FileProvider::getUpdatesDirectory)"));
		assertTrue(model.contains("Target(SafFile.SafTarget safTarget) {\n\t\t\tthis.safTarget = safTarget;\n\t\t\tthis.directory = null;"));
		assertTrue(model.contains("if (target.safTarget != null) {"));
		assertTrue(model.contains("return new SafFile(target, validatePath(path));"));
		assertTrue(model.contains("if (target.directory == null) {"));
		String saf = model.substring(model.indexOf("private static class SafFile"));
		assertTrue(saf.contains("return new Pair<>(null, exists(resolution) ? resolution.documentUri : null)"));
		assertFalse(saf.contains("new RegularFile("));
		assertFalse(saf.contains("Uri.fromFile("));
	}

	@Test public void productionHasNoFilesystemDownloadBackendOrMigrationState() throws Exception {
		File sourceRoot = sourceFile("src/chan/util/DataFile.java").getParentFile().getParentFile().getParentFile();
		try (java.util.stream.Stream<java.nio.file.Path> files = Files.walk(sourceRoot.toPath())) {
			for (java.nio.file.Path file : (Iterable<java.nio.file.Path>) files.filter(p -> p.toString().endsWith(".java"))::iterator) {
				String text = Files.readString(file, StandardCharsets.UTF_8);
				for (String forbidden : new String[] {"convertDownloadsLegacyFile", "getDownloadDirectoryLegacy",
						"getDownloadPathLegacy", "KEY_DOWNLOAD_PATH", "PATH_DOWNLOADS", "DEFAULT_DOWNLOAD_PATH",
						"download_storage_migration_version", "DownloadStorageMigration", "Environment.getExternalStorageDirectory"}) {
					assertFalse(file + ": " + forbidden, text.contains(forbidden));
				}
			}
		}
	}

	@Test public void conflictPreviewAndNotificationOpenerUseDocumentUriWithoutFileFallback() throws Exception {
		String dialog = method("ui/DownloadDialog.java", "createReplace");
		assertTrue(dialog.contains("singleFile.getFileOrUri().second"));
		assertTrue(dialog.contains("Intent.FLAG_GRANT_READ_URI_PERMISSION"));
		assertFalse(dialog.contains("fileOrUri.first"));
		String opener = appSource("content/DownloadedFileOpener.java");
		assertTrue(opener.contains("file.getFileOrUri().second"));
		assertTrue(opener.contains("Intent.FLAG_GRANT_READ_URI_PERMISSION"));
		assertTrue(opener.contains("Intent.FLAG_GRANT_WRITE_URI_PERMISSION"));
		assertFalse(opener.contains("MediaScannerConnection"));
		assertFalse(opener.contains(".first"));
		assertFalse(opener.contains("Uri.fromFile("));
	}

	@Test public void notificationReconstructsSameTargetAndRelativePathThroughDataFile() throws Exception {
		String activity = appSource("content/OpenFileActivity.java");
		assertTrue(activity.contains("file.getTarget().name()"));
		assertTrue(activity.contains("file.getRelativePath()"));
		assertTrue(activity.contains("DataFile.obtain(DataFile.Target.valueOf(target), path)"));
		assertTrue(activity.contains("DownloadedFileOpener.open("));
		assertFalse(activity.contains("Uri.fromFile("));
		String service = appSource("content/service/DownloadService.java");
		assertTrue(service.contains("if (taskData.target.isExternal())"));
		assertTrue(service.contains("OpenFileActivity.createIntent(this, notificationData.lastSuccessFile,"));
	}

	@Test public void scanningRegularFilesDoesNotIncludeSafDownloads() throws Exception {
		String finish = method("content/service/DownloadService.java", "onFinishDownloadingInternal");
		assertTrue(finish.contains("if (success && !taskData.target.isExternal())"));
		assertTrue(finish.contains("scanRegularFile(file)"));
		assertTrue(finish.indexOf("!taskData.target.isExternal()") < finish.indexOf("getFileOrUri().first"));
		String scan = method("content/service/DownloadService.java", "scanRegularFile");
		assertTrue(scan.contains("MediaScannerConnection.scanFile("));
	}

	@Test public void pickerUsesNormalRootAndCancelKeepsExistingFilesAndSettings() throws Exception {
		String picker = method("ui/MainActivity.java", "startStorageDirectoryPicker");
		assertTrue(picker.contains("Intent.ACTION_OPEN_DOCUMENT_TREE"));
		assertTrue(picker.contains("DocumentsContract.EXTRA_INITIAL_URI"));
		assertTrue(picker.contains("DocumentsContract.buildRootUri(\"com.android.externalstorage.documents\", \"primary\")"));
		assertFalse(picker.contains("takePersistableUriPermission"));
		String cancel = method("ui/MainActivity.java", "finishInitialStorageSetup");
		assertFalse(cancel.contains("Preferences.PREFERENCES.edit"));
		assertFalse(cancel.contains(".delete("));
		assertFalse(cancel.contains(".renameTo("));
	}
}
