package com.mishiranu.dashchan.chan.ejchan;

import chan.content.ChanConfiguration;
import com.mishiranu.dashchan.R;

public class EjchanChanConfiguration extends ChanConfiguration {
	public static final String CAPTCHA_TYPE_EJCHAN = "ejchan_captcha";

	public EjchanChanConfiguration() {
		setDefaultName("Аноним");
		request(OPTION_ALLOW_CAPTCHA_PASS);
		addCaptchaType(CAPTCHA_TYPE_EJCHAN);
	}

	@Override
	public Board obtainBoardConfiguration(String boardName) {
		Board board = new Board();
		board.allowCatalog = true;
		board.allowPosting = true;
		board.allowDeleting = true;
		board.allowReporting = true;
		return board;
	}

	@Override
	public Captcha obtainCustomCaptchaConfiguration(String captchaType) {
		if (CAPTCHA_TYPE_EJCHAN.equals(captchaType)) {
			Captcha captcha = new Captcha();
			captcha.title = getResources().getString(R.string.ejchan_captcha);
			captcha.input = Captcha.Input.LATIN;
			captcha.validity = Captcha.Validity.SHORT_LIFETIME;
			return captcha;
		}
		return null;
	}

	@Override
	public Posting obtainPostingConfiguration(String boardName, boolean newThread) {
		Posting posting = new Posting();
		posting.allowName = true;
		posting.allowTripcode = true;
		posting.allowEmail = true;
		posting.allowSubject = newThread;
		posting.optionSage = true;
		posting.maxCommentLength = 5000;
		posting.maxCommentLengthEncoding = "UTF-8";
		posting.attachmentCount = 4;
		posting.attachmentMimeTypes.add("image/*");
		posting.attachmentMimeTypes.add("video/webm");
		posting.attachmentMimeTypes.add("video/mp4");
		posting.attachmentSpoiler = true;
		return posting;
	}

	@Override
	public Deleting obtainDeletingConfiguration(String boardName) {
		Deleting deleting = new Deleting();
		deleting.password = true;
		deleting.multiplePosts = true;
		deleting.optionFilesOnly = true;
		return deleting;
	}

	@Override
	public Reporting obtainReportingConfiguration(String boardName) {
		Reporting reporting = new Reporting();
		reporting.comment = true;
		reporting.multiplePosts = true;
		return reporting;
	}

	@Override
	public Authorization obtainCaptchaPassConfiguration() {
		Authorization authorization = new Authorization();
		authorization.fieldsCount = 1;
		authorization.hints = new String[] {getResources().getString(R.string.ejchan_epass_code)};
		return authorization;
	}
}
