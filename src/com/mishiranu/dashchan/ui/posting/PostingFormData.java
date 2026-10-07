package com.mishiranu.dashchan.ui.posting;

import chan.content.ChanPerformer;
import com.mishiranu.dashchan.content.async.ReadCaptchaTask;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.function.Supplier;

/** Detached form snapshot. No Views; mutable request containers are created only at service handoff. */
final class PostingFormData {
	private final String subject, comment, name, email, password, userIcon;
	private final boolean sage, spoiler, originalPoster;
	private final List<ChanPerformer.SendPostData.Attachment> attachments;
	private final List<String> attachmentHashes;
	private final PostingCaptchaController.CaptchaSnapshot captcha;

	PostingFormData(String subject, String comment, String name, String email, String password,
			boolean sage, boolean spoiler, boolean originalPoster, String userIcon,
			PostingAttachmentsController.SendAttachments attachments, PostingCaptchaController.CaptchaSnapshot captcha) {
		this.subject = subject;
		this.comment = comment;
		this.name = name;
		this.email = email;
		this.password = password;
		this.sage = sage;
		this.spoiler = spoiler;
		this.originalPoster = originalPoster;
		this.userIcon = userIcon;
		this.attachments = attachments.attachments != null
				? Collections.unmodifiableList(new ArrayList<>(Arrays.asList(attachments.attachments))) : null;
		attachmentHashes = Collections.unmodifiableList(new ArrayList<>(attachments.hashes));
		this.captcha = new PostingCaptchaController.CaptchaSnapshot(captcha.type, captcha.state,
				captcha.data != null ? captcha.data.copy() : null, captcha.input, captcha.validity);
	}

	ChanPerformer.SendPostData createRequest(String boardName, String threadNumber) {
		return new ChanPerformer.SendPostData(boardName, threadNumber, subject, comment, name, email, password,
				attachments != null ? attachments.toArray(new ChanPerformer.SendPostData.Attachment[0]) : null,
				sage, spoiler, originalPoster, userIcon, captcha.type,
				captcha.data != null ? captcha.data.copy() : null, captcha.needLoad, 15000, 45000);
	}
	ArrayList<String> createAttachmentHashes() { return new ArrayList<>(attachmentHashes); }
	boolean allowFloodRetry() { return captcha.state == ReadCaptchaTask.CaptchaState.PASS; }
	static String resolvePassword(String password, Supplier<String> fallback) {
		return password != null ? password : fallback.get();
	}
}
