package com.mishiranu.dashchan.ui.posting;

/** An index alone is not an attachment identity after draft restoration/reordering. */
final class AttachmentResultGuard {
	private AttachmentResultGuard() {}

	static boolean matches(String sourceHash, String sourceName, String currentHash, String currentName) {
		return sourceHash != null && !sourceHash.isEmpty() && sourceName != null
				&& sourceHash.equals(currentHash) && sourceName.equals(currentName);
	}
}
