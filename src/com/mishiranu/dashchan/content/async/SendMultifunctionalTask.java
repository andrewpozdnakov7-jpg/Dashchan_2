package com.mishiranu.dashchan.content.async;

import android.net.Uri;
import android.os.SystemClock;
import android.util.Log;
import android.util.Pair;
import chan.content.ApiException;
import chan.content.Chan;
import chan.content.ChanPerformer;
import chan.content.ExtensionException;
import chan.content.InvalidResponseException;
import chan.http.HttpException;
import chan.http.HttpHolder;
import com.mishiranu.dashchan.R;
import com.mishiranu.dashchan.content.model.ErrorItem;
import com.mishiranu.dashchan.content.model.PostItem;
import com.mishiranu.dashchan.content.model.PostNumber;
import java.net.HttpURLConnection;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public class SendMultifunctionalTask extends HttpHolderTask<Void, Boolean> {
	private static final int REPORT_MAX_CONNECTION_ATTEMPTS = 5;

	private final State state;
	private final String type;
	private final String text;
	private final List<String> options;
	private final Callback callback;

	private final Chan chan;
	private final Chan archiveChan;

	private String archiveBoardName;
	private String archiveThreadNumber;
	private ErrorItem errorItem;

	public enum Operation {DELETE, REPORT, VOTE, ARCHIVE}

	public interface Callback {
		void onSendSuccess(String archiveBoardName, String archiveThreadNumber);
		void onSendFail(ErrorItem errorItem);
	}

	public static final String OPTION_FILES_ONLY = "filesOnly";

	public static class State {
		public final Operation operation;
		public final String chanName;
		public final String boardName;
		public final String threadNumber;

		public List<Pair<String, String>> types;
		public List<Pair<String, String>> options;

		public boolean commentField;
		public boolean like;
		public int vote;
		public boolean singleScoreVote;
		public PostItem votePostItem;
		public PostItem deletedPostItem;

		public List<PostNumber> postNumbers;
		public String archiveThreadTitle;
		public String archiveChanName;
		public boolean archiveQueryOnly;

		public State(Operation operation, String chanName, String boardName, String threadNumber,
				List<Pair<String, String>> types, List<Pair<String, String>> options, boolean commentField) {
			this.operation = operation;
			this.chanName = chanName;
			this.boardName = boardName;
			this.threadNumber = threadNumber;
			this.types = types;
			this.options = options;
			this.commentField = commentField;
		}

		public boolean isArchiveSimpleQueryOnly() {
			return archiveQueryOnly && (options == null || options.isEmpty());
		}

		private String getWorkChanName() {
			return archiveChanName != null ? archiveChanName : chanName;
		}
	}

	public SendMultifunctionalTask(Callback callback, State state, String type, String text, List<String> options) {
		super(Chan.get(state.getWorkChanName()));
		this.state = state;
		this.type = type;
		this.text = text;
		this.options = options != null ? Collections.unmodifiableList(options) : null;
		this.callback = callback;
		chan = Chan.get(state.chanName);
		archiveChan = state.archiveChanName != null ? Chan.get(state.archiveChanName) : null;
	}

	private static List<String> createPostNumberList(List<PostNumber> numbers) {
		ArrayList<String> postNumbers = new ArrayList<>(numbers.size());
		for (PostNumber number : numbers) {
			postNumbers.add(number.toString());
		}
		return postNumbers;
	}

	private boolean sendReportWithSafeRetries(HttpHolder holder) throws ExtensionException, HttpException,
			ApiException, InvalidResponseException {
		String diagnostic = "request=" + Integer.toHexString(System.identityHashCode(holder));
		Log.i("ReportDiag", diagnostic + " event=start provider=" + ("dvach".equals(state.chanName) ? "dvach" : "other")
				+ " posts=" + state.postNumbers.size() + " comment_present=" + !android.text.TextUtils.isEmpty(text));
		for (int attempt = 1; attempt <= REPORT_MAX_CONNECTION_ATTEMPTS; attempt++) {
			holder.resetRequestBodyStarted();
			long started = SystemClock.elapsedRealtime();
			String diagnosticAttempt = diagnostic + " attempt=" + attempt;
			Log.i("ReportDiag", diagnosticAttempt + " event=attempt_start");
			try {
				chan.performer.safe().onSendReportPosts(new ChanPerformer.SendReportPostsData(state.boardName,
						state.threadNumber, createPostNumberList(state.postNumbers), type, options, text, holder));
				Log.i("ReportDiag", diagnosticAttempt + " event=success elapsed_ms=" + (SystemClock.elapsedRealtime() - started));
				return true;
			} catch (HttpException e) {
				boolean requestBodyStarted = holder.hasRequestBodyStarted();
				// Never log exception messages: they can contain URLs or response data.
				StringBuilder causes = new StringBuilder();
				Throwable cause = e;
				for (int depth = 0; cause != null && depth < 4; depth++, cause = cause.getCause()) {
					if (depth > 0) causes.append('/');
					causes.append(cause.getClass().getSimpleName());
				}
				Log.w("ReportDiag", diagnosticAttempt + " event=http_failure status=" + e.getResponseCode()
						+ " body_started=" + requestBodyStarted + " retryable=" + e.isRetryableReadException()
						+ " causes=" + causes + " elapsed_ms=" + (SystemClock.elapsedRealtime() - started));
				if (!requestBodyStarted && e.isRetryableReadException()) {
					if (attempt < REPORT_MAX_CONNECTION_ATTEMPTS) {
						Log.i("ReportDiag", diagnosticAttempt + " event=retry_before_body");
						SystemClock.sleep(Math.min(2000L, attempt * 500L));
						continue;
					}
					errorItem = new ErrorItem(R.string.report_send_failed_connection);
					Log.w("ReportDiag", diagnosticAttempt + " event=stop reason=connection_attempts_exhausted");
					return false;
				}
				if (requestBodyStarted && e.getResponseCode() == 0) {
					Log.w("ReportDiag", diagnosticAttempt + " event=stop reason=delivery_unknown retry=false");
					errorItem = new ErrorItem(R.string.report_send_status_unknown);
					return false;
				}
				throw e;
			} catch (InvalidResponseException e) {
				Log.w("ReportDiag", diagnosticAttempt + " event=invalid_response body_started=" + holder.hasRequestBodyStarted()
						+ " retry=false elapsed_ms=" + (SystemClock.elapsedRealtime() - started));
				if (holder.hasRequestBodyStarted()) {
					errorItem = new ErrorItem(R.string.report_send_status_unknown);
					return false;
				}
				throw e;
			} catch (ApiException e) {
				Log.w("ReportDiag", diagnosticAttempt + " event=api_rejected error_type=" + e.getErrorType()
						+ " retry=false elapsed_ms=" + (SystemClock.elapsedRealtime() - started));
				throw e;
			} catch (ExtensionException e) {
				Log.w("ReportDiag", diagnosticAttempt + " event=extension_failure retry=false elapsed_ms="
						+ (SystemClock.elapsedRealtime() - started));
				throw e;
			}
		}
		throw new AssertionError();
	}

	@Override
	protected Boolean run(HttpHolder holder) {
		Chan chan = this.chan;
		try {
			switch (state.operation) {
				case DELETE: {
					chan.performer.safe().onSendDeletePosts(new ChanPerformer
							.SendDeletePostsData(state.boardName, state.threadNumber,
							createPostNumberList(state.postNumbers), text,
							options != null && options.contains(OPTION_FILES_ONLY), holder));
					break;
				}
				case REPORT: {
					if (!sendReportWithSafeRetries(holder)) return false;
					break;
				}
				case VOTE: {
					chan.performer.safe().onSendVotePost(new ChanPerformer.SendVotePostData(state.boardName,
							state.threadNumber, state.postNumbers.get(0).toString(), state.vote, holder));
					break;
				}
				case ARCHIVE: {
					Uri uri = chan.locator.safe(false)
							.createThreadUri(state.boardName, state.threadNumber);
					if (uri == null) {
						errorItem = new ErrorItem(ErrorItem.Type.UNKNOWN);
						return false;
					}
					Chan archiveChan = this.archiveChan;
					if (archiveChan == null) {
						errorItem = new ErrorItem(ErrorItem.Type.UNKNOWN);
						return false;
					}
					chan = archiveChan;
					ChanPerformer.SendAddToArchiveResult result;
					try {
						result = chan.performer.safe().onSendAddToArchive(new ChanPerformer
								.SendAddToArchiveData(uri, state.boardName, state.threadNumber, options, holder));
					} catch (HttpException e) {
						if (state.archiveQueryOnly) {
							int responseCode = e.getResponseCode();
							if (responseCode == HttpURLConnection.HTTP_NOT_FOUND) {
								errorItem = new ErrorItem(ErrorItem.Type.THREAD_NOT_EXISTS);
								return false;
							}
						}
						throw e;
					}
					if (result != null && result.threadNumber != null) {
						archiveBoardName = result.boardName;
						archiveThreadNumber = result.threadNumber;
					}
					break;
				}
			}
			return true;
		} catch (ExtensionException | HttpException | InvalidResponseException e) {
			errorItem = e.getErrorItemAndHandle();
			return false;
		} catch (ApiException e) {
			errorItem = e.getErrorItem();
			return false;
		} finally {
			chan.configuration.commit();
		}
	}

	@Override
	protected void onComplete(Boolean success) {
		if (success) {
			callback.onSendSuccess(archiveBoardName, archiveThreadNumber);
		} else {
			callback.onSendFail(errorItem);
		}
	}
}
