package com.mishiranu.dashchan.ui.navigator.page;

import static org.junit.Assert.*;
import android.os.Parcel;
import android.util.Pair;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import com.mishiranu.dashchan.content.model.PostNumber;
import java.util.HashSet;
import org.junit.Test;
import org.junit.runner.RunWith;

@RunWith(AndroidJUnit4.class)
public class PostsStateCodecTest {
	@Test public void threadParcelRoundTripPreservesAllFields() {
		PostsStateCodec.ThreadState original = new PostsStateCodec.ThreadState();
		original.expandedPosts.add(new PostNumber(12, 0));
		original.unreadPosts.add(new PostNumber(13, 1));
		original.isAddedToHistory = true;
		original.threadTitle = "title";
		original.scrollToPostNumber = new PostNumber(14, 0);
		original.selectedPosts = new HashSet<>(original.expandedPosts);
		original.translationEnabled = true;
		Parcel parcel = Parcel.obtain();
		try {
			PostsStateCodec.writeToParcel(original, parcel, 0);
			parcel.setDataPosition(0);
			PostsStateCodec.ThreadState restored = new PostsStateCodec.ThreadState();
			PostsStateCodec.readFromParcel(parcel, restored);
			assertEquals(original.expandedPosts, restored.expandedPosts);
			assertEquals(original.unreadPosts, restored.unreadPosts);
			assertTrue(restored.isAddedToHistory);
			assertEquals(original.threadTitle, restored.threadTitle);
			assertEquals(original.scrollToPostNumber, restored.scrollToPostNumber);
			assertEquals(original.selectedPosts, restored.selectedPosts);
			assertEquals(Boolean.TRUE, restored.translationEnabled);
		} finally { parcel.recycle(); }
	}
	@Test public void legacyParcelWithoutTranslationStillRestores() {
		Parcel parcel = Parcel.obtain();
		try {
			parcel.writeInt(0); parcel.writeInt(0); parcel.writeByte((byte) 0);
			parcel.writeString(null); parcel.writeByte((byte) 0); parcel.writeInt(-1);
			parcel.setDataPosition(0);
			PostsStateCodec.ThreadState state = new PostsStateCodec.ThreadState();
			PostsStateCodec.readFromParcel(parcel, state);
			assertNull(state.translationEnabled); assertNull(state.selectedPosts);
		} finally { parcel.recycle(); }
	}
	@Test public void positionWireFormatPreservesOffsetAndNullFallback() {
		PostNumber number = new PostNumber(123, 4);
		Pair<PostNumber, Integer> restored = PostsStateCodec.decodeThreadState(PostsStateCodec.encodePosition(number, -37));
		assertEquals(number, restored.first); assertEquals(Integer.valueOf(-37), restored.second);
		assertNull(PostsStateCodec.decodeThreadState(null));
		assertNull(PostsStateCodec.decodeThreadState("{}".getBytes(java.nio.charset.StandardCharsets.UTF_8)));
	}
}
