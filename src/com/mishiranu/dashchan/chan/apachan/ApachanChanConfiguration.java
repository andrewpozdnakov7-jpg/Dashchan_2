package com.mishiranu.dashchan.chan.apachan;

import chan.content.ChanConfiguration;

public class ApachanChanConfiguration extends ChanConfiguration {
	public ApachanChanConfiguration() {
		request(OPTION_READ_POSTS_COUNT);
		setDefaultName("Аноним");
	}

	@Override
	public Board obtainBoardConfiguration(String boardName) {
		Board board = new Board();
		board.allowPosting = true;
		return board;
	}

	@Override
	public Posting obtainPostingConfiguration(String boardName, boolean newThread) {
		Posting posting = new Posting();
		posting.allowSubject = true;
		posting.attachmentCount = 1;
		posting.attachmentMimeTypes.add("image/*");
		return posting;
	}
}
