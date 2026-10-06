package com.mishiranu.dashchan.content;

import java.util.Collection;

/** Pure SAF selection decisions. Actual grants and tree availability are checked by Android callers. */
final class DownloadDirectoryAccess {
	private DownloadDirectoryAccess() {}

	static final class Grant {
		final String uri;
		final boolean readable;
		final boolean writable;
		final boolean available;

		Grant(String uri, boolean readable, boolean writable, boolean available) {
			this.uri = uri;
			this.readable = readable;
			this.writable = writable;
			this.available = available;
		}

		boolean isUsable() {
			return uri != null && !uri.isEmpty() && readable && writable && available;
		}
	}

	static final class Selection {
		final String uri;
		final boolean clearConfiguredUri;

		Selection(String uri, boolean clearConfiguredUri) {
			this.uri = uri;
			this.clearConfiguredUri = clearConfiguredUri;
		}
	}

	static Selection select(String configuredUri, Collection<Grant> grants, Collection<String> archiveUris) {
		if (configuredUri != null && !configuredUri.isEmpty()) {
			for (Grant grant : grants) {
				if (configuredUri.equals(grant.uri) && grant.isUsable()) return new Selection(configuredUri, false);
			}
			// A restored/revoked URI must not silently switch to some other folder.
			return new Selection(null, true);
		}
		String candidate = null;
		for (Grant grant : grants) {
			if (!grant.isUsable() || archiveUris.contains(grant.uri)) continue;
			if (candidate != null && !candidate.equals(grant.uri)) return new Selection(null, false);
			candidate = grant.uri;
		}
		return new Selection(candidate, false);
	}
}
