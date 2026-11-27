package com.mishiranu.dashchan.chan.aoba;

import chan.content.ChanConfiguration;
import com.mishiranu.dashchan.R;
import java.util.Collections;

public class AobaChanConfiguration extends ChanConfiguration {
	static final String CAPTCHA_TYPE = "aoba_image";

	public AobaChanConfiguration() {
		addCaptchaType(CAPTCHA_TYPE);
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
		posting.optionSage = !newThread;
		posting.maxCommentLength = 30 * 1024;
		posting.maxCommentLengthEncoding = "UTF-8";
		posting.attachmentCount = 1;
		Collections.addAll(posting.attachmentMimeTypes, "image/jpeg", "image/png", "image/gif", "image/webp",
				"audio/mp4", "audio/mpeg", "audio/ogg", "video/mp4", "video/webm");
		posting.attachmentSpoiler = true;
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
