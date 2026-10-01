package com.mishiranu.dashchan.content.storage;

import org.junit.Test;
import static org.junit.Assert.*;

public class RedditCommunitiesTest {
	@Test public void acceptsNamesAndPrefixes() {
		assertEquals("Android", RedditCommunities.normalize(" Android "));
		assertEquals("Android", RedditCommunities.normalize("r/Android"));
		assertEquals("Android", RedditCommunities.normalize("/r/Android/"));
		assertEquals("Android", RedditCommunities.normalize("R/Android"));
	}

	@Test public void acceptsOnlyCommunityLinks() {
		assertEquals("android", RedditCommunities.normalize("https://www.reddit.com/r/android/?share=test"));
		assertEquals("android", RedditCommunities.normalize("https://old.reddit.com/r/android/"));
		assertNull(RedditCommunities.normalize("https://www.reddit.com/r/android/comments/123/title/"));
		assertNull(RedditCommunities.normalize("https://www.reddit.com/user/example"));
	}

	@Test public void rejectsOtherHostsAndSchemes() {
		assertNull(RedditCommunities.normalize("https://reddit.com.evil.invalid/r/android/"));
		assertNull(RedditCommunities.normalize("https://reddit.com@evil.invalid/r/android/"));
		assertNull(RedditCommunities.normalize("https://user@reddit.com/r/android/"));
		assertNull(RedditCommunities.normalize("https://reddit.com:443/r/android/"));
		assertNull(RedditCommunities.normalize("javascript:alert(1)"));
		assertNull(RedditCommunities.normalize("file:///r/android/"));
	}

	@Test public void rejectsInvalidNamesAndNavigationFeeds() {
		assertNull(RedditCommunities.normalize(null));
		assertNull(RedditCommunities.normalize(""));
		assertNull(RedditCommunities.normalize("a"));
		assertNull(RedditCommunities.normalize("name with spaces"));
		assertNull(RedditCommunities.normalize("android+linux"));
		assertNull(RedditCommunities.normalize("/r/all/"));
		assertNull(RedditCommunities.normalize("popular"));
		assertNull(RedditCommunities.normalize("a".repeat(65)));
	}
}
