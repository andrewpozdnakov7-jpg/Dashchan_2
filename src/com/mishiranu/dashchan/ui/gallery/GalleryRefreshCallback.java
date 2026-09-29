package com.mishiranu.dashchan.ui.gallery;

import com.mishiranu.dashchan.content.model.ErrorItem;
import com.mishiranu.dashchan.content.model.GalleryItem;
import java.util.List;

/** Completion of the source thread's normal refresh; never transfers post text. */
public interface GalleryRefreshCallback {
	void onComplete(List<GalleryItem> items, int newPosts, ErrorItem error);
}
