package com.mishiranu.dashchan.content.security;

import android.content.ContentResolver;
import android.content.Context;
import android.content.UriPermission;
import android.database.Cursor;
import android.net.Uri;
import android.provider.DocumentsContract;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashSet;

/** Only a separately approved local SAF tree. Never delete the tree root or follow another tree. */
public final class DownloadTreeCleanup {
	private DownloadTreeCleanup() {}
	public static String describe(Context context, Uri tree) throws IOException {
		validate(context, tree);
		return DocumentsContract.getTreeDocumentId(tree) + "\n" + tree;
	}
	private static void validate(Context context, Uri tree) throws IOException {
		if (tree == null || !"content".equals(tree.getScheme()) || !DocumentsContract.isTreeUri(tree)
				|| !"com.android.externalstorage.documents".equals(tree.getAuthority())) {
			throw new IOException("A local storage directory is required");
		}
		String id = DocumentsContract.getTreeDocumentId(tree);
		if (!PrivacyResetPolicy.isSafeLocalTreeId(id)) throw new IOException("A storage volume root or noncanonical tree cannot be cleared");
		boolean permission = false;
		for (UriPermission grant : context.getContentResolver().getPersistedUriPermissions()) {
			if (tree.equals(grant.getUri()) && grant.isReadPermission() && grant.isWritePermission()) permission = true;
		}
		if (!permission) throw new IOException("Directory permission is unavailable");
		Uri root = DocumentsContract.buildDocumentUriUsingTree(tree, id);
		try (Cursor cursor = context.getContentResolver().query(root,
				new String[]{DocumentsContract.Document.COLUMN_MIME_TYPE}, null, null, null)) {
			if (cursor == null || !cursor.moveToFirst()
					|| !DocumentsContract.Document.MIME_TYPE_DIR.equals(cursor.getString(0))) throw new IOException("Directory unavailable");
		} catch (RuntimeException e) { throw new IOException("Directory unavailable", e); }
	}

	public static void clear(Context context, String approvedTree) throws IOException {
		Uri tree = Uri.parse(approvedTree);
		validate(context, tree);
		try {
			ContentResolver resolver = context.getContentResolver();
			Uri root = DocumentsContract.buildDocumentUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree));
			clearDirectory(resolver, tree, root, root, new HashSet<>(), 0);
		} catch (RuntimeException e) { throw new IOException("Directory cleanup failed", e); }
	}

	private static void clearDirectory(ContentResolver resolver, Uri tree, Uri root, Uri directory,
			HashSet<String> visited, int depth) throws IOException {
		String directoryId = DocumentsContract.getDocumentId(directory);
		if (depth > 64 || !visited.add(directoryId)) throw new IOException("Invalid directory hierarchy");
		Uri children = DocumentsContract.buildChildDocumentsUriUsingTree(tree, directoryId);
		ArrayList<String[]> entries = new ArrayList<>();
		try (Cursor cursor = resolver.query(children, new String[]{DocumentsContract.Document.COLUMN_DOCUMENT_ID,
				DocumentsContract.Document.COLUMN_MIME_TYPE}, null, null, null)) {
			if (cursor == null) throw new IOException("Cannot enumerate download directory");
			if (cursor.getExtras().getBoolean(DocumentsContract.EXTRA_LOADING)
					|| cursor.getExtras().containsKey(DocumentsContract.EXTRA_ERROR)) throw new IOException("Incomplete directory listing");
			while (cursor.moveToNext()) entries.add(new String[]{cursor.getString(0), cursor.getString(1)});
		}
		for (String[] entry : entries) {
			Uri child = DocumentsContract.buildDocumentUriUsingTree(tree, entry[0]);
			if (child.equals(root) || !DocumentsContract.isChildDocument(resolver, root, child)) throw new IOException("Unexpected document outside the approved directory");
			if (DocumentsContract.Document.MIME_TYPE_DIR.equals(entry[1])) clearDirectory(resolver, tree, root, child, visited, depth + 1);
			if (!DocumentsContract.deleteDocument(resolver, child)) throw new IOException("Cannot delete download document");
		}
		// Check the provider's actual result, not just the return value of deleteDocument().
		try (Cursor remaining = resolver.query(children, new String[]{DocumentsContract.Document.COLUMN_DOCUMENT_ID}, null, null, null)) {
			if (remaining == null || remaining.moveToFirst() || remaining.getExtras().getBoolean(DocumentsContract.EXTRA_LOADING)
					|| remaining.getExtras().containsKey(DocumentsContract.EXTRA_ERROR)) throw new IOException("Download directory is not empty");
		}
	}
}
