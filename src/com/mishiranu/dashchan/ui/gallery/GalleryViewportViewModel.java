package com.mishiranu.dashchan.ui.gallery;

import androidx.lifecycle.ViewModel;

/** Activity scope survives closing/reopening a gallery and host rotation, not a new app session. */
public class GalleryViewportViewModel extends ViewModel {
	final GalleryViewportMemory memory = new GalleryViewportMemory();
	@Override protected void onCleared() { memory.clear(); }
}
