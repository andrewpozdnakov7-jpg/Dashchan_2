package com.mishiranu.dashchan.content.model;

import java.util.List;

public interface PendingUserPost {
	PostNumber findUserPost(List<Post> posts, PostNumber lastExistingPostNumber, boolean firstPostIsOriginal);

	class NewThread implements PendingUserPost {
		public static final NewThread INSTANCE = new NewThread();

		private NewThread() {}

		@Override
		public PostNumber findUserPost(List<Post> posts,
				PostNumber lastExistingPostNumber, boolean firstPostIsOriginal) {
			return firstPostIsOriginal && !posts.isEmpty() ? posts.get(0).number : null;
		}

		@Override
		public boolean equals(Object o) {
			return o instanceof NewThread;
		}

		@Override
		public int hashCode() {
			return 1;
		}
	}
}
