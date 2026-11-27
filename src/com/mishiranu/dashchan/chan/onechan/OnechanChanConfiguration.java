package com.mishiranu.dashchan.chan.onechan;

import chan.content.ChanConfiguration;
import com.mishiranu.dashchan.R;

public class OnechanChanConfiguration extends ChanConfiguration {
	static final String BOARD = "news";
	static final String CAPTCHA_TYPE = "onechan_image";

	public OnechanChanConfiguration() {
		request(OPTION_SINGLE_BOARD_MODE);
		setSingleBoardName(BOARD);
		addCaptchaType(CAPTCHA_TYPE);
	}

	@Override
	public Board obtainBoardConfiguration(String boardName) {
		Board board = new Board();
		board.allowPosting = BOARD.equals(boardName);
		return board;
	}

	@Override
	public Posting obtainPostingConfiguration(String boardName, boolean newThread) {
		if (!BOARD.equals(boardName)) return null;
		Posting posting = new Posting();
		posting.allowSubject = newThread;
		posting.maxCommentLength = newThread ? 0 : 2048;
		return posting;
	}

	@Override
	public Captcha obtainCustomCaptchaConfiguration(String captchaType) {
		if (!CAPTCHA_TYPE.equals(captchaType)) return null;
		Captcha captcha = new Captcha();
		captcha.title = getResources().getString(R.string.forum_image_captcha);
		captcha.validity = Captcha.Validity.SHORT_LIFETIME;
		return captcha;
	}
}
