package com.mishiranu.dashchan.content.async;

import chan.content.ApiException;
import chan.content.Chan;
import chan.content.InvalidResponseException;
import chan.http.HttpException;
import chan.http.HttpHolder;
import com.mishiranu.dashchan.chan.apachan.ApachanChanPerformer;
import com.mishiranu.dashchan.content.model.ErrorItem;

public class SendApachanEditPostTask extends HttpHolderTask<Void, SendApachanEditPostTask.Result> {
	public static final class Result {
		public final boolean success;
		public final ErrorItem errorItem;

		private Result(boolean success, ErrorItem errorItem) {
			this.success = success;
			this.errorItem = errorItem;
		}
	}

	private final TaskViewModel<SendApachanEditPostTask, Result> viewModel;
	private final Chan chan;
	private final String threadNumber;
	private final String postNumber;
	private final String subject;
	private final String comment;
	private final boolean originalPost;
	private final boolean deleteAttachment;
	private final boolean showOriginalPoster;
	private final int commentLimit;

	public SendApachanEditPostTask(TaskViewModel<SendApachanEditPostTask, Result> viewModel, Chan chan,
			String threadNumber, String postNumber, String subject, String comment, boolean originalPost,
			boolean deleteAttachment, boolean showOriginalPoster, int commentLimit) {
		super(chan);
		this.viewModel = viewModel;
		this.chan = chan;
		this.threadNumber = threadNumber;
		this.postNumber = postNumber;
		this.subject = subject;
		this.comment = comment;
		this.originalPost = originalPost;
		this.deleteAttachment = deleteAttachment;
		this.showOriginalPoster = showOriginalPoster;
		this.commentLimit = commentLimit;
	}

	@Override
	protected Result run(HttpHolder holder) {
		try {
			ApachanChanPerformer performer = (ApachanChanPerformer) chan.performer;
			performer.sendEditPost(threadNumber, postNumber, subject, comment, originalPost,
					deleteAttachment, showOriginalPoster, commentLimit, holder);
			return new Result(true, null);
		} catch (ApiException e) {
			return new Result(false, e.getErrorItem());
		} catch (HttpException | InvalidResponseException e) {
			return new Result(false, e.getErrorItemAndHandle());
		} finally {
			chan.configuration.commit();
		}
	}

	@Override
	protected void onComplete(Result result) {
		viewModel.handleResult(result);
	}
}
