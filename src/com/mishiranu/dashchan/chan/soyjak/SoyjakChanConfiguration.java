package com.mishiranu.dashchan.chan.soyjak;

import chan.content.ChanConfiguration;

public class SoyjakChanConfiguration extends ChanConfiguration {
	public SoyjakChanConfiguration() {
		setDefaultName("Anonymous");
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
