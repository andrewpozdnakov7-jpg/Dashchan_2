package com.mishiranu.dashchan.chan.d3ru;

import chan.content.ChanConfiguration;

public class D3ChanConfiguration extends ChanConfiguration {
	@SuppressWarnings("unchecked")
	public static D3ChanConfiguration get(Object object) {
		return ChanConfiguration.get(object);
	}

	public D3ChanConfiguration() {
		request(OPTION_BOARD_TITLE_ONLY);
		setBoardTitle(D3ChanLocator.BOARD_ALL, "Все публикации");
		setBoardDescription(D3ChanLocator.BOARD_ALL, "Общая публичная лента d3.ru");
		setPagesCount(D3ChanLocator.BOARD_ALL, 2);
		setDefaultName("Пользователь d3.ru");
	}

	@Override
	protected String obtainBoardTitle(String boardName) {
		return boardName;
	}

	@Override
	public Board obtainBoardConfiguration(String boardName) {
		Board board = new Board();
		board.allowSearch = false;
		board.allowCatalog = false;
		board.allowThreadsSorting = false;
		board.allowRatingSorting = false;
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
