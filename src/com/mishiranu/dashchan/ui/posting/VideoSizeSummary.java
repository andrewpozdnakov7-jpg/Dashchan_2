package com.mishiranu.dashchan.ui.posting;

/** Read-only presentation snapshot; never exposes the mutable conversion job to a dialog. */
public final class VideoSizeSummary {
	public final long currentBytes, beforeBytes, afterBytes;
	public final boolean converted, busy, convertible;
	public VideoSizeSummary(long currentBytes, long beforeBytes, long afterBytes,
			boolean converted, boolean busy, boolean convertible) {
		this.currentBytes = currentBytes; this.beforeBytes = beforeBytes; this.afterBytes = afterBytes;
		this.converted = converted; this.busy = busy; this.convertible = convertible;
	}
}
