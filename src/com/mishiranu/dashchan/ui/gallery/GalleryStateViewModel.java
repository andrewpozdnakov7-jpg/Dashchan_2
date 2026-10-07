package com.mishiranu.dashchan.ui.gallery;

import android.os.Bundle;
import android.os.Parcelable;
import androidx.lifecycle.ViewModel;
import com.mishiranu.dashchan.content.model.GalleryItem;
import java.util.List;

// Fragment-scoped non-UI state. Windows, views and their listeners are rebuilt for each host.
public class GalleryStateViewModel extends ViewModel {
	List<GalleryItem> allItems;
	List<GalleryItem> visibleItems;
	java.util.Set<com.mishiranu.dashchan.content.model.PostNumber> refreshKnownPosts;
	Bundle dialogState;
	Parcelable gridState;
	GalleryViewportMemory.Snapshot viewport;
	String filter;
	String sort;
	String restoreToken;
	String pendingPictureInPictureToken;
	VideoUnit.LifecycleState video;
	boolean cleared;

	@Override
	protected void onCleared() {
		cleared = true;
		if (video != null) {
			video.dispose();
			video = null;
		}
		allItems = null;
		visibleItems = null;
		refreshKnownPosts = null;
		dialogState = null;
		gridState = null;
		viewport = null;
	}
}
