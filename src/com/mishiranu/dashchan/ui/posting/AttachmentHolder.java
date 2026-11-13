package com.mishiranu.dashchan.ui.posting;

import android.view.View;
import android.widget.ImageView;
import android.widget.TextView;
import com.mishiranu.dashchan.util.GraphicsUtils;

public class AttachmentHolder {
	public final View view;
	public final TextView fileName;
	public final TextView fileSize;
	public final ImageView imageView;
	public final View videoMarker;
	public final View previewSurface, removeButton, dragButton;
	public boolean video, videoPreviewAllowed;
	public final View warningButton;
	public final View ratingButton;
	public final View editButton;

	public String hash;
	public String name;
	public String rating;
	// Original draft size; conversion results are overlaid by the attachment controller.
	public long sourceSize = -1;
	public boolean optionUniqueHash = false;
	public boolean optionRemoveMetadata = false;
	public boolean optionRemoveFileName = false;
	public boolean optionSpoiler = false;
	public GraphicsUtils.Reencoding reencoding;
	public String attachmentId = java.util.UUID.randomUUID().toString();
	public String photoEditorRequest;
	public com.mishiranu.dashchan.ui.posting.photo.PhotoAttachmentOptions photoOptions;
	// Restored detached state; a live compression model becomes authoritative after binding.
	public boolean forceVideoConversion;
	public com.mishiranu.dashchan.content.storage.AttachmentConversion conversionDraft;

	public AttachmentHolder(View view, TextView fileName, TextView fileSize, ImageView imageView, View videoMarker,
			View warningButton, View ratingButton, View editButton) {
		this(view, fileName, fileSize, imageView, videoMarker, warningButton, ratingButton, editButton,
				imageView, null, null);
	}
	public AttachmentHolder(View view, TextView fileName, TextView fileSize, ImageView imageView, View videoMarker,
			View warningButton, View ratingButton, View editButton, View previewSurface, View removeButton, View dragButton) {
		this.view = view;
		this.fileName = fileName;
		this.fileSize = fileSize;
		this.imageView = imageView;
		this.videoMarker = videoMarker;
		this.previewSurface = previewSurface; this.removeButton = removeButton; this.dragButton = dragButton;
		this.warningButton = warningButton;
		this.ratingButton = ratingButton;
		this.editButton = editButton;
	}
}
