package com.mishiranu.dashchan.ui.navigator.page;

import android.os.Parcel;
import android.util.Pair;
import chan.text.JsonSerial;
import chan.text.ParseException;
import com.mishiranu.dashchan.content.HidePerformer;
import com.mishiranu.dashchan.content.model.PostNumber;
import java.io.IOException;
import java.util.HashSet;
import java.util.Set;

/** Thread wire formats only; ParcelableExtra retains its existing class name for saved sessions. */
final class PostsStateCodec {
	private PostsStateCodec() {}
	static class ThreadState {
		public final HashSet<PostNumber> expandedPosts = new HashSet<>();
		public final HashSet<PostNumber> unreadPosts = new HashSet<>();
		public boolean isAddedToHistory = false;
		public String threadTitle;
		public PostNumber scrollToPostNumber;
		public Set<PostNumber> selectedPosts;
		public Boolean translationEnabled;
	}
	static void writeToParcel(ThreadState state, Parcel dest, int flags) {
		dest.writeInt(state.expandedPosts.size());
		for (PostNumber number : state.expandedPosts) {
			number.writeToParcel(dest, flags);
		}
		dest.writeInt(state.unreadPosts.size());
		for (PostNumber number : state.unreadPosts) {
			number.writeToParcel(dest, flags);
		}
		dest.writeByte((byte) (state.isAddedToHistory ? 1 : 0));
		dest.writeString(state.threadTitle);
		dest.writeByte((byte) (state.scrollToPostNumber != null ? 1 : 0));
		if (state.scrollToPostNumber != null) {
			state.scrollToPostNumber.writeToParcel(dest, flags);
		}
		dest.writeInt(state.selectedPosts != null ? state.selectedPosts.size() : -1);
		if (state.selectedPosts != null) {
			for (PostNumber number : state.selectedPosts) {
				number.writeToParcel(dest, flags);
			}
		}
		dest.writeByte((byte) (state.translationEnabled == null ? -1 : state.translationEnabled ? 1 : 0));
	}
	static void readFromParcel(Parcel source, ThreadState parcelableExtra) {
		int expandedPostsCount = source.readInt();
		for (int i = 0; i < expandedPostsCount; i++) {
			parcelableExtra.expandedPosts.add(PostNumber.CREATOR.createFromParcel(source));
		}
		int unreadPostsCount = source.readInt();
		for (int i = 0; i < unreadPostsCount; i++) {
			parcelableExtra.unreadPosts.add(PostNumber.CREATOR.createFromParcel(source));
		}
		parcelableExtra.isAddedToHistory = source.readByte() != 0;
		parcelableExtra.threadTitle = source.readString();
		if (source.readByte() != 0) {
			parcelableExtra.scrollToPostNumber = PostNumber.CREATOR.createFromParcel(source);
		}
		int selectedPostsCount = source.readInt();
		if (selectedPostsCount >= 0) {
			HashSet<PostNumber> selectedPosts = new HashSet<>(selectedPostsCount);
			for (int i = 0; i < selectedPostsCount; i++) {
				selectedPosts.add(PostNumber.CREATOR.createFromParcel(source));
			}
			parcelableExtra.selectedPosts = selectedPosts;
		}
		if (source.dataAvail() > 0) {
			byte translationEnabled = source.readByte();
			parcelableExtra.translationEnabled = translationEnabled < 0 ? null : translationEnabled != 0;
		}
	}
	static byte[] decodeThreadExtra(byte[] threadExtra, HidePerformer hidePerformer) {
		boolean localFiltersDecoded = false;
		if (threadExtra != null) {
			try (JsonSerial.Reader reader = JsonSerial.reader(threadExtra)) {
				reader.startObject();
				while (!reader.endStruct()) {
					switch (reader.nextName()) {
						case "filters": {
							hidePerformer.decodeLocalFilters(reader);
							localFiltersDecoded = true;
							break;
						}
						default: {
							reader.skip();
							break;
						}
					}
				}
			} catch (ParseException e) {
				e.printStackTrace();
				threadExtra = null;
			} catch (IOException e) {
				throw new RuntimeException(e);
			}
		}
		if (!localFiltersDecoded) {
			try {
				hidePerformer.decodeLocalFilters(null);
			} catch (ParseException e) {
				e.printStackTrace();
			} catch (IOException e) {
				throw new RuntimeException(e);
			}
		}
		return threadExtra;
	}
	static byte[] encodeThreadExtra(HidePerformer hidePerformer) {
		byte[] extra = null;
		if (hidePerformer.hasLocalFilters()) {
			try (JsonSerial.Writer writer = JsonSerial.writer()) {
				writer.startObject();
				writer.name("filters");
				hidePerformer.encodeLocalFilters(writer);
				writer.endObject();
				extra = writer.build();
			} catch (IOException e) {
				throw new RuntimeException(e);
			}
		}
		return extra;
	}
	static Pair<PostNumber, Integer> decodeThreadState(byte[] state) {
		PostNumber positionPostNumber = null;
		int positionOffset = 0;
		if (state != null) {
			try (JsonSerial.Reader reader = JsonSerial.reader(state)) {
				reader.startObject();
				while (!reader.endStruct()) {
					switch (reader.nextName()) {
						case "position": {
							reader.startObject();
							while (!reader.endStruct()) {
								switch (reader.nextName()) {
									case "number": {
										positionPostNumber = PostNumber.parseNullable(reader.nextString());
										break;
									}
									case "offset": {
										positionOffset = reader.nextInt();
										break;
									}
									default: {
										reader.skip();
										break;
									}
								}
							}
							break;
						}
						default: {
							reader.skip();
							break;
						}
					}
				}
			} catch (ParseException e) {
				e.printStackTrace();
			} catch (IOException e) {
				throw new RuntimeException(e);
			}
		}
		return positionPostNumber != null ? new Pair<>(positionPostNumber, positionOffset) : null;
	}
	static byte[] encodePosition(PostNumber number, int offset) {
		byte[] state;
		try (JsonSerial.Writer writer = JsonSerial.writer()) {
			writer.startObject();
			writer.name("position");
			writer.startObject();
			writer.name("number");
			writer.value(number.toString());
			writer.name("offset");
			writer.value(offset);
			writer.endObject();
			writer.endObject();
			state = writer.build();
		} catch (IOException e) {
			throw new RuntimeException(e);
		}
		return state;
	}
}
