package com.mishiranu.dashchan.ui.posting;

import android.view.DragEvent;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import java.util.List;

/** No experimental attachment UI, motion, or resources in this distribution. */
public final class PostingAttachmentsAppearance {
	public interface Host { List<AttachmentHolder> attachments(); void move(AttachmentHolder holder, int index); void rebuild(); }
	private PostingAttachmentsAppearance() {}
	public static PostingAttachmentsAppearance create(LinearLayout container, ScrollView scroll, Host host) { return null; }
	public AttachmentHolder createCard(View.OnClickListener options, View.OnClickListener warning,
			View.OnClickListener rating, View.OnClickListener edit, View.OnClickListener remove,
			View.OnClickListener preview, View.OnLongClickListener drag) { throw new IllegalStateException(); }
	public void addCard(View view) {}
	public void clearCards() {}
	public void prepareRemoval(AttachmentHolder holder) {}
	public boolean deferRebuild() { return false; }
	public void prune() {}
	public int thumbnailSize() { return 0; }
	public void bindFile(AttachmentHolder holder, String details, boolean available, boolean image, boolean video, boolean editable) {}
	public void thumbnailFinished(AttachmentHolder holder, boolean success) {}
	public boolean startDrag(AttachmentHolder holder) { return false; }
	public boolean dispatchDrag(DragEvent event) { return false; }
	public void cancelMotion() {}
	public void close() {}
}
