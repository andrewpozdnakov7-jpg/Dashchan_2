package com.mishiranu.dashchan.chan.pikabu;

import chan.content.ChanConfiguration;

public class PikabuChanConfiguration extends ChanConfiguration {
	public PikabuChanConfiguration() {
		request(OPTION_BOARD_TITLE_ONLY);
		setBoardTitle(PikabuChanLocator.BOARD_HOT, "Горячее");
		setBoardDescription(PikabuChanLocator.BOARD_HOT, "Популярные истории");
		setBoardTitle(PikabuChanLocator.BOARD_BEST, "Лучшее");
		setBoardDescription(PikabuChanLocator.BOARD_BEST, "Лучшие истории");
		setBoardTitle(PikabuChanLocator.BOARD_NEW, "Свежее");
		setBoardDescription(PikabuChanLocator.BOARD_NEW, "Новые истории");
		setBoardTitle(PikabuChanLocator.createBrowseBoardName(PikabuChanLocator.BROWSE_ORIGINAL), "Авторские");
		setBoardTitle(PikabuChanLocator.createBrowseBoardName(PikabuChanLocator.BROWSE_TEXT), "Текстовые");
		setBoardTitle(PikabuChanLocator.createBrowseBoardName(PikabuChanLocator.BROWSE_IMAGES), "Изображения");
		setBoardTitle(PikabuChanLocator.createBrowseBoardName(PikabuChanLocator.BROWSE_VIDEO), "Видео");
		setBoardTitle(PikabuChanLocator.createBrowseBoardName(PikabuChanLocator.BROWSE_REPLIES),
				"Ответы на истории");
		setBoardTitle(PikabuChanLocator.createBrowseBoardName(PikabuChanLocator.BROWSE_SERIES), "Серии");
		setBoardTitle(PikabuChanLocator.createBrowseBoardName(PikabuChanLocator.BROWSE_LONG), "Длиннопосты");
		setDefaultName("Пикабушник");
	}

	@Override
	protected String obtainBoardTitle(String boardName) {
		return PikabuChanLocator.getDynamicBoardTitle(boardName);
	}

	@Override
	public Board obtainBoardConfiguration(String boardName) {
		Board board = new Board();
		board.allowSearch = true;
		board.allowCatalog = false;
		board.allowThreadsSorting = true;
		board.allowRatingSorting = true;
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
