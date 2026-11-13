package com.mishiranu.dashchan.ui.posting;

import android.util.Pair;
import chan.content.ChanConfiguration;
import java.util.List;

public interface PostingDialogCallback {
	AttachmentHolder getAttachmentHolder(int index);
	List<Pair<String, String>> getAttachmentRatingItems();
	ChanConfiguration.Posting getPostingConfiguration();
	default boolean canConvertVideoAttachment(int index) { return false; }
	default void convertVideoAttachment(int index, String sourceHash, String sourceName) {}
	default boolean hasVideoConversionDetails(int index) { return false; }
	default void showVideoConversionDetails(int index, String sourceHash) {}
	default int[] getVideoConversionPosition(int index) { return new int[] {0, 0}; }
	default VideoSizeSummary getVideoSizeSummary(int index, String hash, String name) { return null; }
	default Runnable observeVideoConversion(androidx.lifecycle.LifecycleOwner owner, Runnable changed) { return () -> {}; }
}
