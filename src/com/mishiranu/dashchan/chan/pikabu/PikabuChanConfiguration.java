package com.mishiranu.dashchan.chan.pikabu;

import chan.content.ChanConfiguration;

public class PikabuChanConfiguration extends ChanConfiguration {
	public PikabuChanConfiguration() {
		setBoardTitle(PikabuChanLocator.BOARD_HOT, "Горячее");
		setBoardDescription(PikabuChanLocator.BOARD_HOT, "Популярные истории");
		setBoardTitle(PikabuChanLocator.BOARD_BEST, "Лучшее");
		setBoardDescription(PikabuChanLocator.BOARD_BEST, "Лучшие истории");
		setBoardTitle(PikabuChanLocator.BOARD_NEW, "Свежее");
		setBoardDescription(PikabuChanLocator.BOARD_NEW, "Новые истории");
		setDefaultName("Пикабушник");
	}

	@Override
	public Board obtainBoardConfiguration(String boardName) {
		Board board = new Board();
		board.allowSearch = false;
		board.allowCatalog = false;
		board.allowThreadsSorting = false;
		board.allowArchive = false;
		board.allowPosting = false;
		board.allowEditing = false;
		board.allowDeleting = false;
		board.allowReporting = false;
		board.allowVotes = false;
		return board;
	}

	@Override
	public Statistics obtainStatisticsConfiguration() {
		Statistics statistics = new Statistics();
		statistics.postsSent = false;
		statistics.threadsCreated = false;
		statistics.threadsViewed = true;
		return statistics;
	}
}
