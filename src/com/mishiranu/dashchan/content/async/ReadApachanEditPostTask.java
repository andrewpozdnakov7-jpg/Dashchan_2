package com.mishiranu.dashchan.content.async;

import chan.content.ApiException;
import chan.content.Chan;
import chan.content.InvalidResponseException;
import chan.http.HttpException;
import chan.http.HttpHolder;
import com.mishiranu.dashchan.chan.apachan.ApachanChanPerformer;
import com.mishiranu.dashchan.content.model.ErrorItem;

public class ReadApachanEditPostTask extends HttpHolderTask<Void, ReadApachanEditPostTask.Result> {
	public static final class Result {
		public final ApachanChanPerformer.EditPostResult editPostResult;
		public final ErrorItem errorItem;

		private Result(ApachanChanPerformer.EditPostResult editPostResult, ErrorItem errorItem) {
			this.editPostResult = editPostResult;
			this.errorItem = errorItem;
		}
	}

	private final TaskViewModel<ReadApachanEditPostTask, Result> viewModel;
	private final Chan chan;
	private final String postNumber;

	public ReadApachanEditPostTask(TaskViewModel<ReadApachanEditPostTask, Result> viewModel,
			Chan chan, String postNumber) {
		super(chan);
		this.viewModel = viewModel;
		this.chan = chan;
		this.postNumber = postNumber;
	}

	@Override
	protected Result run(HttpHolder holder) {
		try {
			ApachanChanPerformer performer = (ApachanChanPerformer) chan.performer;
			return new Result(performer.readEditPost(postNumber, holder), null);
		} catch (ApiException e) {
			return new Result(null, e.getErrorItem());
		} catch (HttpException | InvalidResponseException e) {
			return new Result(null, e.getErrorItemAndHandle());
		} finally {
			chan.configuration.commit();
		}
	}

	@Override
	protected void onComplete(Result result) {
		viewModel.handleResult(result);
	}
}
