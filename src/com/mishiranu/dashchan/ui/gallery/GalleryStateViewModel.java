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
	Bundle dialogState;
	Parcelable gridState;
	String filter;
	String sort;
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
		dialogState = null;
		gridState = null;
	}
}
