package com.mishiranu.dashchan.content;

import android.content.ContentResolver;
import android.content.UriPermission;
import android.database.Cursor;
import android.net.Uri;
import android.provider.DocumentsContract;
import chan.util.DataFile;
import chan.util.StringUtils;
import com.mishiranu.dashchan.util.IOUtils;
import com.mishiranu.dashchan.util.Hasher;
import java.io.ByteArrayOutputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ConcurrentHashMap;
import java.util.zip.Deflater;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;
import org.json.JSONException;
import org.json.JSONObject;
import org.jsoup.Jsoup;

public class LocalArchiveManager {
	public static final String DIRECTORY_ARCHIVE = "Archive";
	public static final String DIRECTORY_FILES = "src";
	public static final String DIRECTORY_THUMBNAILS = "thumb";
	public static final String MANIFEST_SCHEMA = "slooop-local-archive";
	public static final int MANIFEST_FORMAT = 2;
	public static final String RESOURCE_SCHEME = "slooop-archive";

	private static final ConcurrentHashMap<String, Item> RESOURCE_ITEMS = new ConcurrentHashMap<>();

	private LocalArchiveManager() {}

	public static class Item {
		public final String id;
		public final String name;
		public final String sourceName;
		public final long lastModified;
		private String title;
		private final DataFile htmlFile;
		private final DataFile zipFile;
		private final DataFile manifestFile;
		private final Uri treeUri;
		private final Uri htmlUri;
		private final Uri zipUri;
		private final Uri manifestUri;

		private Item(String sourceId, String name, String sourceName, DataFile htmlFile, DataFile zipFile,
				DataFile manifestFile, Uri treeUri, Uri htmlUri, Uri zipUri, Uri manifestUri,
				long htmlModified, long zipModified) {
			this.id = createId(sourceId, name);
			this.name = name;
			this.sourceName = sourceName;
			this.htmlFile = htmlFile;
			this.zipFile = zipFile;
			this.manifestFile = manifestFile;
			this.treeUri = treeUri;
			this.htmlUri = htmlUri;
			this.zipUri = zipUri;
			this.manifestUri = manifestUri;
			lastModified = Math.max(htmlModified, zipModified);
		}

		public boolean hasHtml() {
			return htmlFile != null || htmlUri != null;
		}

		public boolean hasZip() {
			return zipFile != null || zipUri != null;
		}

		public boolean isExternal() {
			return treeUri != null;
		}

		public String getTitle() {
			return title;
		}

		public String getDisplayName() {
			return !StringUtils.isEmpty(title) ? title : name;
		}
	}

	private static String createId(String sourceId, String name) {
		return sourceId + ':' + name.toLowerCase(Locale.ROOT);
	}

	public static String createDefaultId(String name) {
		return createId("default", name);
	}

	private static class MutableItem {
		public final String name;
		public DataFile htmlFile;
		public DataFile zipFile;
		public DataFile manifestFile;
		public Uri htmlUri;
		public Uri zipUri;
		public Uri manifestUri;
		public long htmlModified;
		public long zipModified;

		private MutableItem(String name) {
			this.name = name;
		}
	}

	public static List<Item> collect() {
		ArrayList<Item> items = new ArrayList<>();
		collectDefault(items);
		for (String uriString : Preferences.getLocalArchiveUriTrees()) {
			try {
				collectTree(items, Uri.parse(uriString));
			} catch (RuntimeException e) {
				// A provider may disappear or revoke access. Keep other sources available.
			}
		}
		for (Item item : items) {
			item.title = readManifestTitle(item);
		}
		items.sort((first, second) -> Long.compare(second.lastModified, first.lastModified));
		return items;
	}

	public static Item findById(String id) {
		for (Item item : collect()) {
			if (item.id.equals(id)) {
				return item;
			}
		}
		return null;
	}

	public static Item findDefaultThreadArchive(String chanName, String boardName, String threadNumber) {
		for (Item item : collect()) {
			if (item.isExternal()) {
				continue;
			}
			JSONObject manifest = readManifest(item);
			if (manifest != null && MANIFEST_SCHEMA.equals(manifest.optString("schema"))
					&& chanName.equals(manifest.optString("chan"))
					&& boardName.equals(manifest.optString("board"))
					&& threadNumber.equals(manifest.optString("thread"))) {
				return item;
			}
		}
		return null;
	}

	public static boolean delete(Item item) {
		if (!safeDirectory(item.name)) return false;
		boolean success;
		if (item.treeUri == null) {
			success = deleteDefault(item.name);
		} else {
			String resourceDirectory = resourceDirectory(item);
			ContentResolver resolver = MainApplication.getInstance().getContentResolver();
			if (!hasWritePermission(resolver, item.treeUri)) {
				return false;
			}
			try {
				success = delete(resolver, item.htmlUri) & delete(resolver, item.zipUri)
						& delete(resolver, item.manifestUri);
				if (success && !hasResourceFamily(item.treeUri, resourceDirectory)) {
					success = delete(resolver, findDocument(item.treeUri, resourceDirectory));
				}
			} catch (RuntimeException e) {
				success = false;
			}
		}
		if (success) {
			for (String token : RESOURCE_ITEMS.keySet()) {
				RESOURCE_ITEMS.remove(token, item);
			}
		}
		return success;
	}

	public static boolean deleteDefault(String name) {
		try { return deleteDefaultInternal(name); }
		catch (RuntimeException e) { return false; }
	}

	private static boolean deleteDefaultInternal(String name) {
		if (!safeDirectory(name)) {
			return false;
		}
		DataFile directory = DataFile.obtain(DataFile.Target.DOWNLOADS, DIRECTORY_ARCHIVE);
		String resources = resourceDirectory(defaultItem(name));
		boolean success = delete(directory.getChild(name + ".html"))
				& delete(directory.getChild(name + ".htm"))
				& delete(directory.getChild(name + ".zip"))
				& delete(directory.getChild(name + ".json"));
		return success && (hasResourceFamily(null, resources) || deleteRecursively(directory.getChild(resources)));
	}

	private static Item defaultItem(String name) {
		DataFile root = DataFile.obtain(DataFile.Target.DOWNLOADS, DIRECTORY_ARCHIVE);
		DataFile html = root.getChild(name + ".html"), zip = root.getChild(name + ".zip"), manifest = root.getChild(name + ".json");
		if (!html.exists()) html = root.getChild(name + ".htm");
		return new Item("default", name, null, html.exists() ? html : null, zip.exists() ? zip : null,
				manifest.exists() ? manifest : null, null, null, null, null, 0, 0);
	}

	private static boolean safeDirectory(String name) {
		return DataFile.isValidSegment(name) && name.indexOf('/') < 0 && name.indexOf('\\') < 0;
	}

	private static boolean familyName(String name, String resources) {
		if (name.equals(resources)) return true;
		if (!name.startsWith(resources + "_")) return false;
		String suffix = name.substring(resources.length() + 1);
		return !suffix.isEmpty() && suffix.chars().allMatch(value -> value >= '0' && value <= '9');
	}

	public static String resourceDirectory(Item item) {
		return resourceDirectory(item, readManifest(item));
	}

	private static String resourceDirectory(Item item, JSONObject manifest) {
		String resources = manifest != null ? manifest.optString("resources", "") : "";
		if (resources.isEmpty() && item.hasHtml()) {
			try {
				org.jsoup.nodes.Element meta = Jsoup.parse(new String(readHtml(item), StandardCharsets.UTF_8))
						.selectFirst("meta[name=slooop-local-archive-resources]");
				if (meta != null) resources = meta.attr("content");
			} catch (IOException | RuntimeException ignored) {}
		}
		return safeDirectory(resources) && familyName(item.name, resources) ? resources : item.name;
	}

	/** A failed directory listing retains data. Metadata-only interrupted snapshots count as references too. */
	private static boolean hasResourceFamily(Uri tree, String resources) {
		try {
			java.util.HashSet<String> seen = new java.util.HashSet<>();
			if (tree == null) {
				List<DataFile> children = DataFile.obtain(DataFile.Target.DOWNLOADS, DIRECTORY_ARCHIVE).getChildren();
				if (children == null) return true;
				for (DataFile child : children) if (!child.isDirectory() && familyFile(child.getName(), resources)) {
					String name = snapshotName(child.getName());
					if (seen.add(name) && referencesResources(defaultItem(name), resources)) return true;
				}
			} else {
				ArrayList<Item> items = new ArrayList<>();
				collectTree(items, tree);
				HashMap<String, Item> byName = new HashMap<>();
				for (Item item : items) byName.put(item.name, item);
				ContentResolver resolver = MainApplication.getInstance().getContentResolver();
				Uri children = DocumentsContract.buildChildDocumentsUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree));
				try (Cursor cursor = resolver.query(children, new String[] {DocumentsContract.Document.COLUMN_DISPLAY_NAME}, null, null, null)) {
					if (cursor == null) return true;
					while (cursor.moveToNext()) if (familyFile(cursor.getString(0), resources)) {
						String name = snapshotName(cursor.getString(0));
						if (seen.add(name)) {
							Item item = byName.get(name);
							if (item == null || referencesResources(item, resources)) return true;
						}
					}
				}
			}
			return false;
		} catch (RuntimeException e) { return true; }
	}

	private static boolean referencesResources(Item item, String resources) {
		JSONObject manifest = readManifest(item);
		if (manifest == null) {
			// Unknown metadata is not evidence that this snapshot no longer needs its media.
			if (!item.hasHtml()) return true;
			try {
				org.jsoup.nodes.Element meta = Jsoup.parse(new String(readHtml(item), StandardCharsets.UTF_8))
						.selectFirst("meta[name=slooop-local-archive-resources]");
				if (meta == null) return resources.equals(item.name); // Legacy independent resource folder.
				String declared = meta.attr("content");
				return !safeDirectory(declared) || !familyName(item.name, declared) || resources.equals(declared);
			} catch (IOException | RuntimeException e) { return true; }
		}
		return resources.equals(resourceDirectory(item, manifest));
	}

	private static String snapshotName(String fileName) {
		String extension = StringUtils.getFileExtension(fileName);
		return fileName.substring(0, fileName.length() - extension.length() - 1);
	}

	private static boolean familyFile(String name, String resources) {
		String extension = StringUtils.getFileExtension(name);
		return ("html".equalsIgnoreCase(extension) || "htm".equalsIgnoreCase(extension)
				|| "json".equalsIgnoreCase(extension) || "zip".equalsIgnoreCase(extension))
				&& familyName(snapshotName(name), resources);
	}

	/** Called only after the new snapshot and every requested resource are complete. */
	public static boolean deleteSuperseded(String currentName) {
		Item current = defaultItem(currentName);
		JSONObject identity = readManifest(current);
		if (identity == null) return false;
		String resources = resourceDirectory(current);
		List<DataFile> children = DataFile.obtain(DataFile.Target.DOWNLOADS, DIRECTORY_ARCHIVE).getChildren();
		if (children == null) return false;
		java.util.HashSet<String> names = new java.util.HashSet<>();
		for (DataFile child : children) {
			String fileName = child.getName(), extension = StringUtils.getFileExtension(fileName);
			if (familyFile(fileName, resources)) names.add(fileName.substring(0, fileName.length() - extension.length() - 1));
		}
		boolean success = true;
		for (String name : names) {
			if (name.equals(currentName)) continue;
			Item previous = defaultItem(name);
			JSONObject manifest = readManifest(previous);
			if (manifest != null && MANIFEST_SCHEMA.equals(manifest.optString("schema"))
					&& resources.equals(resourceDirectory(previous))
					&& identity.optString("chan").equals(manifest.optString("chan"))
					&& identity.optString("board").equals(manifest.optString("board"))
					&& identity.optString("thread").equals(manifest.optString("thread"))) success &= delete(previous);
		}
		return success;
	}

	private static boolean hasWritePermission(ContentResolver resolver, Uri treeUri) {
		for (UriPermission permission : resolver.getPersistedUriPermissions()) {
			if (treeUri.equals(permission.getUri()) && permission.isWritePermission()) {
				return true;
			}
		}
		return false;
	}

	private static boolean delete(DataFile file) {
		return file == null || !file.exists() || file.delete();
	}

	private static boolean deleteRecursively(DataFile file) {
		if (!file.exists()) {
			return true;
		}
		boolean success = true;
		if (file.isDirectory()) {
			List<DataFile> children = file.getChildren();
			if (children != null) {
				for (DataFile child : children) {
					success &= deleteRecursively(child);
				}
			}
		}
		return file.delete() && success;
	}

	private static boolean delete(ContentResolver resolver, Uri uri) {
		if (uri == null) {
			return true;
		}
		try {
			return DocumentsContract.deleteDocument(resolver, uri);
		} catch (FileNotFoundException | SecurityException | IllegalArgumentException e) {
			return false;
		}
	}

	public static String getTreeName(String uriString) {
		try {
			Uri treeUri = Uri.parse(uriString);
			ContentResolver resolver = MainApplication.getInstance().getContentResolver();
			String name = queryDisplayName(resolver, DocumentsContract.buildDocumentUriUsingTree(treeUri,
					DocumentsContract.getTreeDocumentId(treeUri)));
			return !StringUtils.isEmpty(name) ? name : treeUri.getLastPathSegment();
		} catch (RuntimeException e) {
			return uriString;
		}
	}

	private static void collectDefault(List<Item> items) {
		DataFile directory = DataFile.obtain(DataFile.Target.DOWNLOADS, DIRECTORY_ARCHIVE);
		List<DataFile> children = directory.getChildren();
		HashMap<String, MutableItem> mutableItems = new HashMap<>();
		if (children != null) {
			for (DataFile child : children) {
				if (child.isDirectory()) {
					continue;
				}
				String fileName = child.getName();
				String extension = StringUtils.getFileExtension(fileName);
				if (!"html".equalsIgnoreCase(extension) && !"htm".equalsIgnoreCase(extension)
						&& !"zip".equalsIgnoreCase(extension)
						&& !"json".equalsIgnoreCase(extension)) {
					continue;
				}
				String name = fileName.substring(0, fileName.length() - extension.length() - 1);
				MutableItem mutableItem = obtainMutable(mutableItems, name);
				if ("html".equalsIgnoreCase(extension) || "htm".equalsIgnoreCase(extension)) {
					mutableItem.htmlFile = child;
					mutableItem.htmlModified = child.getLastModified();
				} else if ("zip".equalsIgnoreCase(extension)) {
					mutableItem.zipFile = child;
					mutableItem.zipModified = child.getLastModified();
				} else {
					mutableItem.manifestFile = child;
				}
			}
		}
		for (MutableItem mutableItem : mutableItems.values()) {
			if (mutableItem.htmlFile != null || mutableItem.zipFile != null) {
				items.add(new Item("default", mutableItem.name, null, mutableItem.htmlFile,
						mutableItem.zipFile, mutableItem.manifestFile, null, null, null, null,
						mutableItem.htmlModified, mutableItem.zipModified));
			}
		}
	}

	private static void collectTree(List<Item> items, Uri treeUri) {
		ContentResolver resolver = MainApplication.getInstance().getContentResolver();
		String sourceName = queryDisplayName(resolver, DocumentsContract.buildDocumentUriUsingTree(treeUri,
				DocumentsContract.getTreeDocumentId(treeUri)));
		if (StringUtils.isEmpty(sourceName)) {
			sourceName = treeUri.getLastPathSegment();
		}
		HashMap<String, MutableItem> mutableItems = new HashMap<>();
		Uri childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri,
				DocumentsContract.getTreeDocumentId(treeUri));
		String[] projection = {DocumentsContract.Document.COLUMN_DOCUMENT_ID,
				DocumentsContract.Document.COLUMN_DISPLAY_NAME, DocumentsContract.Document.COLUMN_MIME_TYPE,
				DocumentsContract.Document.COLUMN_LAST_MODIFIED};
		try (Cursor cursor = resolver.query(childrenUri, projection, null, null, null)) {
			if (cursor == null) {
				return;
			}
			while (cursor.moveToNext()) {
				String mimeType = cursor.getString(2);
				if (DocumentsContract.Document.MIME_TYPE_DIR.equals(mimeType)) {
					continue;
				}
				String fileName = cursor.getString(1);
				String extension = StringUtils.getFileExtension(fileName);
				if (!"html".equalsIgnoreCase(extension) && !"htm".equalsIgnoreCase(extension)
						&& !"zip".equalsIgnoreCase(extension)
						&& !"json".equalsIgnoreCase(extension)) {
					continue;
				}
				String name = fileName.substring(0, fileName.length() - extension.length() - 1);
				MutableItem mutableItem = obtainMutable(mutableItems, name);
				Uri uri = DocumentsContract.buildDocumentUriUsingTree(treeUri, cursor.getString(0));
				long modified = cursor.isNull(3) ? 0L : cursor.getLong(3);
				if ("html".equalsIgnoreCase(extension) || "htm".equalsIgnoreCase(extension)) {
					mutableItem.htmlUri = uri;
					mutableItem.htmlModified = modified;
				} else if ("zip".equalsIgnoreCase(extension)) {
					mutableItem.zipUri = uri;
					mutableItem.zipModified = modified;
				} else {
					mutableItem.manifestUri = uri;
				}
			}
		} catch (SecurityException e) {
			return;
		}
		String sourceId = "tree:" + treeUri;
		for (MutableItem mutableItem : mutableItems.values()) {
			if (mutableItem.htmlUri != null || mutableItem.zipUri != null) {
				items.add(new Item(sourceId, mutableItem.name, sourceName, null, null, null, treeUri,
						mutableItem.htmlUri, mutableItem.zipUri, mutableItem.manifestUri,
						mutableItem.htmlModified, mutableItem.zipModified));
			}
		}
	}

	private static MutableItem obtainMutable(HashMap<String, MutableItem> mutableItems, String name) {
		String key = name.toLowerCase(Locale.ROOT);
		MutableItem mutableItem = mutableItems.get(key);
		if (mutableItem == null) {
			mutableItem = new MutableItem(name);
			mutableItems.put(key, mutableItem);
		}
		return mutableItem;
	}

	public static JSONObject createManifest(String archiveName, String chanName, String boardName,
			String threadNumber, String title, int postsCount, int attachmentsCount,
			boolean savedFiles, boolean savedThumbnails) throws JSONException {
		JSONObject manifest = new JSONObject();
		manifest.put("schema", MANIFEST_SCHEMA);
		manifest.put("format", MANIFEST_FORMAT);
		manifest.put("generator", "Slooop");
		manifest.put("created", System.currentTimeMillis());
		manifest.put("name", archiveName);
		manifest.put("html", archiveName + ".html");
		manifest.put("resources", archiveName);
		manifest.put("view", "native");
		manifest.put("chan", chanName);
		manifest.put("board", boardName);
		manifest.put("thread", threadNumber);
		manifest.put("posts", postsCount);
		manifest.put("attachments", attachmentsCount);
		manifest.put("savedFiles", savedFiles);
		manifest.put("savedThumbnails", savedThumbnails);
		if (!StringUtils.isEmpty(title)) {
			manifest.put("title", title);
		}
		return manifest;
	}

	public static boolean createZip(String archiveName, List<String> files, List<String> thumbnails) {
		return createZip(archiveName, archiveName, files, thumbnails);
	}

	/** Rewrite only the newly staged snapshot. A failure never modifies its previous snapshot. */
	public static void writeDefaultManifest(String archiveName, JSONObject manifest) throws IOException {
		if (!safeDirectory(archiveName)) throw new IOException("Invalid archive name");
		DataFile file = DataFile.obtain(DataFile.Target.DOWNLOADS, DIRECTORY_ARCHIVE).getChild(archiveName + ".json");
		try (java.io.OutputStream output = file.openOutputStream()) {
			output.write(manifest.toString().getBytes(StandardCharsets.UTF_8));
		}
	}

	public static boolean createZip(String archiveName, String resources, List<String> files, List<String> thumbnails) {
		if (!safeDirectory(archiveName) || !safeDirectory(resources) || !familyName(archiveName, resources)) return false;
		DataFile archiveDirectory = DataFile.obtain(DataFile.Target.DOWNLOADS, DIRECTORY_ARCHIVE);
		DataFile zipFile = archiveDirectory.getChild(archiveName + ".zip");
		boolean success = false;
		try (ZipOutputStream output = new ZipOutputStream(zipFile.openOutputStream())) {
			output.setLevel(Deflater.NO_COMPRESSION);
			DataFile manifestFile = archiveDirectory.getChild(archiveName + ".json");
			putFile(output, "manifest.json", manifestFile);
			DataFile htmlFile = archiveDirectory.getChild(archiveName + ".html");
			putFile(output, archiveName + ".html", htmlFile);
			for (String name : thumbnails) {
				putFile(output, resources + "/" + DIRECTORY_THUMBNAILS + "/" + name,
						archiveDirectory.getChild(resources + "/" + DIRECTORY_THUMBNAILS + "/" + name));
			}
			for (String name : files) {
				putFile(output, resources + "/" + DIRECTORY_FILES + "/" + name,
						archiveDirectory.getChild(resources + "/" + DIRECTORY_FILES + "/" + name));
			}
			success = true;
			return true;
		} catch (IOException e) {
			com.mishiranu.dashchan.media.VideoDiagnostics.recordUi("archive_update stage=pack_io_failed type=" + e.getClass().getSimpleName());
			return false;
		} finally {
			if (!success) {
				zipFile.delete();
			}
		}
	}

	private static void putFile(ZipOutputStream output, String name, DataFile file) throws IOException {
		if (!file.exists() || file.isDirectory()) {
			throw new IOException("Archive file is missing: " + file.getRelativePath());
		}
		try (InputStream input = file.openInputStream()) {
			putStream(output, name, input);
		}
	}

	private static void putStream(ZipOutputStream output, String name, InputStream input) throws IOException {
		output.putNextEntry(new ZipEntry(name));
		IOUtils.copyStream(input, output);
		output.closeEntry();
	}

	public static byte[] readHtml(Item item) throws IOException {
		InputStream htmlInput = open(item.htmlFile, item.htmlUri);
		if (htmlInput != null) {
			return readBytes(htmlInput);
		}
		InputStream zipInput = open(item.zipFile, item.zipUri);
		if (zipInput != null) {
			try (InputStream input = openZipHtml(zipInput, item.name)) {
				if (input != null) {
					return readBytes(input);
				}
			}
		}
		throw new IOException("Archive HTML is missing");
	}

	public static JSONObject readManifest(Item item) {
		try {
			InputStream manifestInput = open(item.manifestFile, item.manifestUri);
			if (manifestInput != null) {
				return new JSONObject(new String(readBytes(manifestInput), StandardCharsets.UTF_8));
			}
			InputStream zipInput = open(item.zipFile, item.zipUri);
			if (zipInput != null) {
				try (InputStream input = openZipEntry(zipInput, "manifest.json")) {
					if (input != null) {
						return new JSONObject(new String(readBytes(input), StandardCharsets.UTF_8));
					}
				}
			}
		} catch (IOException | JSONException | SecurityException e) {
			// Old and third-party archives may not have a manifest.
		}
		return null;
	}

	private static String readManifestTitle(Item item) {
		JSONObject manifest = readManifest(item);
		if (manifest == null) {
			return null;
		}
		String title = manifest.optString("title", null);
		if (StringUtils.isEmpty(title)) {
			return null;
		}
		title = StringUtils.cutIfLongerToLine(title.replace('\r', ' ').replace('\n', ' '), 100, true);
		return StringUtils.nullIfEmpty(title);
	}

	public static boolean prefersAdaptiveView(Item item) {
		JSONObject manifest = readManifest(item);
		return manifest != null && MANIFEST_SCHEMA.equals(manifest.optString("schema"))
				&& manifest.optInt("format", 0) >= MANIFEST_FORMAT
				&& "adaptive".equals(manifest.optString("view"));
	}

	public static InputStream openResource(Item item, String path) throws IOException {
		if (!isSafePath(path)) {
			return null;
		}
		if (item.htmlFile != null) {
			DataFile file = DataFile.obtain(DataFile.Target.DOWNLOADS, DIRECTORY_ARCHIVE).getChild(path);
			if (file.exists() && !file.isDirectory()) {
				return file.openInputStream();
			}
		} else if (item.htmlUri != null && item.treeUri != null) {
			Uri uri = findDocument(item.treeUri, path);
			if (uri != null) {
				return MainApplication.getInstance().getContentResolver().openInputStream(uri);
			}
		}
		InputStream zipInput = open(item.zipFile, item.zipUri);
		return zipInput != null ? openZipEntry(zipInput, path) : null;
	}

	public static Uri createResourceUri(Item item, String path) {
		if (item == null || !isSafePath(path)) {
			return null;
		}
		String token = StringUtils.formatHex(Hasher.getInstanceSha256().calculate(item.id));
		RESOURCE_ITEMS.put(token, item);
		return new Uri.Builder().scheme(RESOURCE_SCHEME).authority(token)
				.encodedPath('/' + Uri.encode(path, "/")).build();
	}

	public static InputStream openResource(Uri uri) throws IOException {
		if (uri == null || !RESOURCE_SCHEME.equals(uri.getScheme())) {
			return null;
		}
		Item item = RESOURCE_ITEMS.get(uri.getHost());
		String path = uri.getPath();
		if (item == null || path == null || path.length() <= 1) {
			return null;
		}
		return uri.getBooleanQueryParameter("zipOnly", false) ? openZipResource(item, path.substring(1))
				: openResource(item, path.substring(1));
	}

	public static InputStream openZipResource(Item item, String path) throws IOException {
		if (item == null || !isSafePath(path)) return null;
		InputStream input = open(item.zipFile, item.zipUri);
		return input != null ? openZipEntry(input, path) : null;
	}

	private static InputStream open(DataFile file, Uri uri) throws IOException {
		if (file != null) {
			return file.openInputStream();
		}
		if (uri != null) {
			return MainApplication.getInstance().getContentResolver().openInputStream(uri);
		}
		return null;
	}

	private static boolean isSafePath(String path) {
		if (StringUtils.isEmpty(path) || path.startsWith("/") || path.contains("\\")) {
			return false;
		}
		for (String segment : path.split("/")) {
			if (!DataFile.isValidSegment(segment)) {
				return false;
			}
		}
		return true;
	}

	private static Uri findDocument(Uri treeUri, String path) {
		ContentResolver resolver = MainApplication.getInstance().getContentResolver();
		String parentId = DocumentsContract.getTreeDocumentId(treeUri);
		for (String segment : path.split("/")) {
			Uri childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, parentId);
			String[] projection = {DocumentsContract.Document.COLUMN_DOCUMENT_ID,
					DocumentsContract.Document.COLUMN_DISPLAY_NAME};
			String nextId = null;
			try (Cursor cursor = resolver.query(childrenUri, projection, null, null, null)) {
				if (cursor == null) {
					return null;
				}
				while (cursor.moveToNext()) {
					if (segment.equals(cursor.getString(1))) {
						nextId = cursor.getString(0);
						break;
					}
				}
			} catch (SecurityException e) {
				return null;
			}
			if (nextId == null) {
				return null;
			}
			parentId = nextId;
		}
		return DocumentsContract.buildDocumentUriUsingTree(treeUri, parentId);
	}

	private static String queryDisplayName(ContentResolver resolver, Uri uri) {
		String[] projection = {DocumentsContract.Document.COLUMN_DISPLAY_NAME};
		try (Cursor cursor = resolver.query(uri, projection, null, null, null)) {
			return cursor != null && cursor.moveToFirst() ? cursor.getString(0) : null;
		} catch (SecurityException e) {
			return null;
		}
	}

	private static InputStream openZipEntry(InputStream source, String name) throws IOException {
		ZipInputStream input = new ZipInputStream(source);
		try {
			ZipEntry entry;
			while ((entry = input.getNextEntry()) != null) {
				if (!entry.isDirectory() && name.equals(entry.getName())) {
					return input;
				}
			}
		} catch (IOException e) {
			input.close();
			throw e;
		}
		input.close();
		return null;
	}

	private static InputStream openZipHtml(InputStream source, String archiveName) throws IOException {
		ZipInputStream input = new ZipInputStream(source);
		try {
			ZipEntry entry;
			while ((entry = input.getNextEntry()) != null) {
				if (!entry.isDirectory()) {
					String name = entry.getName();
					String extension = StringUtils.getFileExtension(name);
					if ((archiveName + ".html").equals(name) || (archiveName + ".htm").equals(name)
							|| "html".equalsIgnoreCase(extension) || "htm".equalsIgnoreCase(extension)) {
						return input;
					}
				}
			}
		} catch (IOException e) {
			input.close();
			throw e;
		}
		input.close();
		return null;
	}

	private static byte[] readBytes(InputStream input) throws IOException {
		try (InputStream closeable = input; ByteArrayOutputStream output = new ByteArrayOutputStream()) {
			IOUtils.copyStream(closeable, output);
			return output.toByteArray();
		}
	}
}
