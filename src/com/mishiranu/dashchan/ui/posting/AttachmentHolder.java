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
	public final View previewButton;
	public final View warningButton;
	public final View ratingButton;

	public String hash;
	public String name;
	public String rating;
	public boolean optionUniqueHash = false;
	public boolean optionRemoveMetadata = false;
	public boolean optionRemoveFileName = false;
	public boolean optionSpoiler = false;
	public GraphicsUtils.Reencoding reencoding;

	public AttachmentHolder(View view, TextView fileName, TextView fileSize, ImageView imageView, View previewButton,
			View warningButton, View ratingButton) {
		this.view = view;
		this.fileName = fileName;
		this.fileSize = fileSize;
		this.imageView = imageView;
		this.previewButton = previewButton;
		this.warningButton = warningButton;
		this.ratingButton = ratingButton;
	}
}
