package com.mishiranu.dashchan.ui.posting;

/** One immutable view of the controller's actual attachments, forum limit and import work. */
public final class PostingAttachmentState {
	public final int count, limit;
	public final boolean importing;

	public PostingAttachmentState(int count, int limit, boolean importing) {
		this.count = Math.max(0, count);
		this.limit = Math.max(0, limit);
		this.importing = importing;
	}

	public boolean hasRoom() { return count < limit; }
	public boolean canAdd() { return !importing && hasRoom(); }
	public boolean atLimit() { return limit > 0 && count > 0 && count >= limit; }
	public boolean visible(boolean keyboard) { return keyboard ? limit > 0 : canAdd(); }
}
