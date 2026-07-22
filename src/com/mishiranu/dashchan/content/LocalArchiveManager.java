package com.mishiranu.dashchan.content;

import android.content.ContentResolver;
import android.database.Cursor;
import android.net.Uri;
import android.provider.DocumentsContract;
import chan.util.DataFile;
import chan.util.StringUtils;
import com.mishiranu.dashchan.util.IOUtils;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.zip.Deflater;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;
import org.json.JSONException;
import org.json.JSONObject;

public class LocalArchiveManager {
	public static final String DIRECTORY_ARCHIVE = "Archive";
	public static final String DIRECTORY_FILES = "src";
	public static final String DIRECTORY_THUMBNAILS = "thumb";
	public static final String MANIFEST_SCHEMA = "slooop-local-archive";
	public static final int MANIFEST_FORMAT = 2;

	private LocalArchiveManager() {}

	public static class Item {
		public final String id;
		public final String name;
		public final String sourceName;
		public final long lastModified;
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
			this.id = sourceId + ':' + name.toLowerCase(Locale.ROOT);
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
				putFile(output, archiveName + "/" + DIRECTORY_THUMBNAILS + "/" + name,
						archiveDirectory.getChild(archiveName + "/" + DIRECTORY_THUMBNAILS + "/" + name));
			}
			for (String name : files) {
				putFile(output, archiveName + "/" + DIRECTORY_FILES + "/" + name,
						archiveDirectory.getChild(archiveName + "/" + DIRECTORY_FILES + "/" + name));
			}
			success = true;
			return true;
		} catch (IOException e) {
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
