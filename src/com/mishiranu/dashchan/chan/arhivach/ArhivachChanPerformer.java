package com.mishiranu.dashchan.chan.arhivach;

import android.net.Uri;
import chan.content.ChanPerformer;
import chan.content.InvalidResponseException;
import chan.http.HttpException;
import chan.http.HttpRequest;
import chan.http.HttpResponse;
import chan.text.ParseException;
import java.io.IOException;
import java.io.InputStream;

public class ArhivachChanPerformer extends ChanPerformer {
	public static final int PAGE_SIZE = 25;

	@Override
	public ReadThreadsResult onReadThreads(ReadThreadsData data) throws HttpException, InvalidResponseException {
		ArhivachChanLocator locator = ArhivachChanLocator.get(this);
		Uri uri = locator.createBoardUri(null, data.pageNumber);
		HttpResponse response = new HttpRequest(uri, data).setValidator(data.validator).perform();
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
		HttpResponse response = new HttpRequest(locator.createThreadUri(null, data.threadNumber), data)
				.setValidator(data.validator).perform();
		try (InputStream input = response.open()) {
			return new ReadPostsResult(new ArhivachPostsParser(this, data.threadNumber).convert(input))
					.setValidator(response.getValidator()).setFullThread(true);
		} catch (ParseException e) {
			throw new InvalidResponseException(e);
		} catch (IOException e) {
			throw response.fail(e);
		}
	}
}
