package com.mishiranu.dashchan.chan.arhivach;

import android.net.Uri;
import chan.content.ChanPerformer;
import chan.content.InvalidResponseException;
import chan.http.HttpException;
import chan.http.HttpRequest;
import chan.http.HttpResponse;
import chan.http.HttpValidator;
import chan.text.ParseException;
import com.mishiranu.dashchan.content.model.ErrorItem;
import java.io.IOException;
import java.io.InputStream;
import java.util.List;
import java.util.Map;

public class ArhivachChanPerformer extends ChanPerformer {
	public static final int PAGE_SIZE = 25;

	@Override
	public ReadThreadsResult onReadThreads(ReadThreadsData data) throws HttpException, InvalidResponseException {
		ArhivachChanLocator locator = ArhivachChanLocator.get(this);
		Uri uri = locator.createBoardUri(null, data.pageNumber);
		HttpResponse response = perform(uri, data, data.validator);
		try (InputStream input = response.open()) {
			return new ReadThreadsResult(new ArhivachThreadsParser(this, true).convertThreads(input))
					.setValidator(response.getValidator());
		} catch (ParseException e) {
			throw new InvalidResponseException(e);
		} catch (IOException e) {
			throw response.fail(e);
		}
	}

	@Override
	public ReadPostsResult onReadPosts(ReadPostsData data) throws HttpException, InvalidResponseException {
		ArhivachChanLocator locator = ArhivachChanLocator.get(this);
		Uri uri = locator.createThreadUri(null, data.threadNumber);
		HttpResponse response = perform(uri, data, data.validator);
		try (InputStream input = response.open()) {
			return new ReadPostsResult(new ArhivachPostsParser(this, data.threadNumber).convert(input))
					.setValidator(response.getValidator()).setFullThread(true);
		} catch (ParseException e) {
			throw new InvalidResponseException(e);
		} catch (IOException e) {
			throw response.fail(e);
		}
	}

	/**
	 * Performs a page request and writes one logcat line before and after it. The log
	 * shows whether the clearance cookie is present, which user agent is used and what
	 * Cloudflare answered, so one device run is enough to diagnose the bridge.
	 */
	private static HttpResponse perform(Uri uri, HttpRequest.Preset preset, HttpValidator validator)
			throws HttpException {
		ArhivachBrowserSession.log("native_request path=" + uri.getPath()
				+ " " + ArhivachBrowserSession.cookieStatus()
				+ " " + ArhivachBrowserSession.userAgentStatus());
		try {
			HttpResponse response = new HttpRequest(uri, preset).setValidator(validator).perform();
			ArhivachBrowserSession.log("native_response path=" + uri.getPath()
					+ " status=" + response.getResponseCode()
					+ " cfMitigated=" + hasHeader(response, "cf-mitigated")
					+ " type=" + header(response, "content-type"));
			return response;
		} catch (HttpException e) {
			if (e.getResponseCode() == java.net.HttpURLConnection.HTTP_FORBIDDEN) {
				// The site no longer accepts the copy: drop it and let the user pass again.
				ArhivachBrowserSession.clearStoredClearance("http_403");
			}
			ArhivachBrowserSession.log("native_error path=" + uri.getPath()
					+ " code=" + e.getResponseCode() + " type=" + errorName(e));
			throw e;
		}
	}

	private static boolean hasHeader(HttpResponse response, String name) {
		for (String key : response.getHeaderFields().keySet()) {
			if (key != null && key.equalsIgnoreCase(name)) return true;
		}
		return false;
	}

	private static String header(HttpResponse response, String name) {
		for (Map.Entry<String, List<String>> entry : response.getHeaderFields().entrySet()) {
			if (entry.getKey() != null && entry.getKey().equalsIgnoreCase(name)) {
				List<String> values = entry.getValue();
				return values != null && !values.isEmpty() ? values.get(0) : "none";
			}
		}
		return "none";
	}

	private static String errorName(HttpException exception) {
		ErrorItem item = exception.getErrorItemAndHandle();
		return item != null && item.type != null ? item.type.name() : "UNKNOWN";
	}
}
