package com.mishiranu.dashchan.ui.posting;

/** An index alone is not an attachment identity after draft restoration/reordering. */
final class AttachmentResultGuard {
	private AttachmentResultGuard() {}

	static boolean matches(String sourceHash, String sourceName, String currentHash, String currentName) {
		return sourceHash != null && !sourceHash.isEmpty() && sourceName != null
				&& sourceHash.equals(currentHash) && sourceName.equals(currentName);
	}
	static boolean matches(String sourceHash, String sourceName, String sourceId, String request,
			String currentHash, String currentName, String currentId, String currentRequest) {
		return matches(sourceHash,sourceName,currentHash,currentName) && sourceId != null && sourceId.equals(currentId)
				&& request != null && request.equals(currentRequest);
	}
}
