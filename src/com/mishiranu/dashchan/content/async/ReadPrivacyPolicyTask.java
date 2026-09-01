package com.mishiranu.dashchan.content.async;

import android.net.Uri;
import android.os.SystemClock;
import chan.content.Chan;
import chan.http.HttpException;
import chan.http.HttpHolder;
import chan.http.HttpRequest;
import chan.http.HttpResponse;
import com.mishiranu.dashchan.BuildConfig;
import com.mishiranu.dashchan.content.MainApplication;
import com.mishiranu.dashchan.content.model.ErrorItem;
import com.mishiranu.dashchan.util.IOUtils;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;

public class ReadPrivacyPolicyTask extends ExecutorTask<Void, ReadPrivacyPolicyTask.Result> {
	private static final long NETWORK_FALLBACK_TIMEOUT_MS = 20 * 1000L;
	private static final int MIN_POLICY_LENGTH = 1024;
	private static final int MAX_POLICY_LENGTH = 256 * 1024;
	private static final String LOCAL_ASSET = "privacy_policy.md";

	public interface Callback {
		void onReadPrivacyPolicyComplete(String text, ErrorItem errorItem, boolean localFallback);
	}

	public static class Result {
		public final String text;
		public final ErrorItem errorItem;
		public final boolean localFallback;

		public Result(String text, ErrorItem errorItem, boolean localFallback) {
			this.text = text;
			this.errorItem = errorItem;
			this.localFallback = localFallback;
		}
	}

	private static class NetworkResult {
		public boolean done;
		public String text;
		public HttpException exception;
	}

	private final Callback callback;

	public ReadPrivacyPolicyTask(Callback callback) {
		this.callback = callback;
	}

	private static String validate(String text) {
		if (text == null || text.length() < MIN_POLICY_LENGTH || text.length() > MAX_POLICY_LENGTH ||
				!text.contains("# Политика конфиденциальности Slooop / Slooop Privacy Policy") ||
				!text.contains("## English version") || !text.contains("## Русская версия")) {
			return null;
		}
		return text;
	}

	private static String readLocal() {
		try (InputStream input = MainApplication.getInstance().getAssets().open(LOCAL_ASSET);
				ByteArrayOutputStream output = new ByteArrayOutputStream()) {
			IOUtils.copyStream(input, output);
			return validate(output.toString("UTF-8"));
		} catch (IOException e) {
			return null;
		}
	}

	private static String readNetwork() throws InterruptedException, HttpException {
		HttpHolder holder = new HttpHolder(Chan.getFallback());
		NetworkResult result = new NetworkResult();
		Thread thread = new Thread(() -> {
			try (HttpHolder.Use ignored = holder.use()) {
				HttpResponse response = new HttpRequest(Uri.parse(BuildConfig.PRIVACY_POLICY_URL), holder).perform();
				response.setEncoding("UTF-8");
				result.text = validate(response.readString());
			} catch (HttpException e) {
				result.exception = e;
			} finally {
				synchronized (result) {
					result.done = true;
					result.notifyAll();
				}
			}
		}, "ReadPrivacyPolicyNetwork");
		thread.setDaemon(true);
		thread.start();

		long end = SystemClock.elapsedRealtime() + NETWORK_FALLBACK_TIMEOUT_MS;
		synchronized (result) {
			while (!result.done) {
				long timeout = end - SystemClock.elapsedRealtime();
				if (timeout <= 0) {
					break;
				}
				result.wait(timeout);
			}
		}
		if (!result.done) {
			holder.interrupt();
			return null;
		}
		if (result.exception != null) {
			throw result.exception;
		}
		return result.text;
	}

	@Override
	protected Result run() throws InterruptedException {
		ErrorItem networkError = null;
		String text = null;
		try {
			text = readNetwork();
		} catch (HttpException e) {
			networkError = e.getErrorItemAndHandle();
		}
		if (text != null) {
			return new Result(text, null, false);
		}
		text = readLocal();
		if (text != null) {
			return new Result(text, null, true);
		}
		return new Result(null, networkError != null ? networkError :
				new ErrorItem(ErrorItem.Type.INVALID_RESPONSE), false);
	}

	@Override
	protected void onComplete(Result result) {
		callback.onReadPrivacyPolicyComplete(result.text, result.errorItem, result.localFallback);
	}
}
