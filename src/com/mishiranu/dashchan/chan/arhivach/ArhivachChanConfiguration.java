package com.mishiranu.dashchan.chan.arhivach;

import chan.content.ChanConfiguration;

public class ArhivachChanConfiguration extends ChanConfiguration {
	public ArhivachChanConfiguration() {
		request(OPTION_SINGLE_BOARD_MODE);
		setSingleBoardName(null);
		setBoardTitle(null, "Архивач");
		setDefaultName("Аноним");
	}

	@Override
	public Board obtainBoardConfiguration(String boardName) {
		Board board = new Board();
		board.allowSearch = false;
		board.allowCatalog = false;
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
