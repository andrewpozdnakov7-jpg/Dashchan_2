package com.mishiranu.dashchan.chan.soyjak;

import chan.content.ChanConfiguration;
import com.mishiranu.dashchan.R;

public class SoyjakChanConfiguration extends ChanConfiguration {
	private static final String KEY_WEBSITE_REPLIES = "website_replies";

	public SoyjakChanConfiguration() {
		setDefaultName("Anonymous");
		addCustomPreference(KEY_WEBSITE_REPLIES, false);
	}

	@Override
	protected CustomPreference obtainCustomPreferenceConfiguration(String key) {
		if (!KEY_WEBSITE_REPLIES.equals(key)) return null;
		CustomPreference preference = new CustomPreference();
		preference.title = getResources().getString(R.string.soyjak_allow_posts);
		preference.summary = getResources().getString(R.string.soyjak_allow_posts_summary);
		return preference;
	}

	public boolean isWebsiteReplyEnabled() {
		return get(null, KEY_WEBSITE_REPLIES, false);
	}

	@Override
	public Board obtainBoardConfiguration(String boardName) {
		Board board = new Board();
		board.allowCatalog = true;
		board.allowSearch = false;
		board.allowPosting = false;
		board.allowDeleting = false;
		board.allowReporting = false;
		return board;
	}

	@Override
	public Statistics obtainStatisticsConfiguration() {
		Statistics statistics = new Statistics();
		statistics.postsSent = false;
		statistics.threadsCreated = false;
		return statistics;
	}
}
