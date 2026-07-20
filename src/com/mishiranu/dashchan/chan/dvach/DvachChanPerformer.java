package com.mishiranu.dashchan.chan.dvach;

import android.annotation.SuppressLint;
import android.graphics.Bitmap;
import android.net.Uri;

import chan.content.ApiException;
import chan.content.ChanPerformer;
import chan.content.InvalidResponseException;
import chan.content.RedirectException;
import chan.content.model.Board;
import chan.content.model.BoardCategory;
import chan.content.model.Post;
import chan.content.model.Posts;
import chan.content.model.ThreadSummary;
import chan.http.CookieBuilder;
import chan.http.HttpException;
import chan.http.HttpRequest;
import chan.http.HttpResponse;
import chan.http.MultipartEntity;
import chan.http.UrlEncodedEntity;
import chan.text.JsonSerial;
import chan.text.ParseException;
import chan.util.CommonUtils;
import chan.util.StringUtils;
import com.mishiranu.dashchan.R;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.text.DateFormatSymbols;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Objects;
import java.util.TimeZone;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

public class DvachChanPerformer extends ChanPerformer {
	private static final String COOKIE_USERCODE_AUTH = "usercode_auth";
	private static final String COOKIE_PASSCODE_AUTH = "passcode_auth";
	private static final int PASSCODUBOYAR_MAX_FILES = 8;

	private static final String[] PREFERRED_BOARDS_ORDER = {"Разное", "Тематика", "Творчество", "Политика",
			"Техника и софт", "Игры", "Японская культура", "Взрослым", "Пробное"};

	public DvachChanPerformer() {
		try {
			registerFirewallResolver(new DvachFirewallResolver());
		} catch (LinkageError e) {
			e.printStackTrace();
		}
	}

	private CookieBuilder buildCookies(String captchaPassCookie) {
		DvachChanConfiguration configuration = DvachChanConfiguration.get(this);
		CookieBuilder builder = new CookieBuilder();
		builder.append(COOKIE_USERCODE_AUTH, configuration.getCookie(COOKIE_USERCODE_AUTH));
		builder.append(COOKIE_PASSCODE_AUTH, captchaPassCookie);
		return builder;
	}

	private CookieBuilder buildCookiesWithCaptchaPass() {
		return buildCookies(DvachChanConfiguration.get(this).getCookie(COOKIE_PASSCODE_AUTH));
	}

	private static final int[] MOBILE_API_DELAYS = {0, 250, 500, 1000};
	private final Object mobileApiLock = new Object();

	private HttpResponse readMobileApi(HttpRequest request) throws HttpException {
		synchronized (mobileApiLock) {
			HttpException lastException = null;
			for (int delay : MOBILE_API_DELAYS) {
				if (delay > 0) {
					request.setDelay(delay);
				}
				try {
					return request.perform();
				} catch (HttpException e) {
					if (e.isHttpException() && e.getResponseCode() == HttpURLConnection.HTTP_UNAVAILABLE) {
						lastException = e;
						// Retry in loop
					} else {
						throw e;
					}
				}
			}
			throw lastException;
		}
	}

	@Override
	public ReadThreadsResult onReadThreads(ReadThreadsData data) throws HttpException, InvalidResponseException {
		DvachChanLocator locator = DvachChanLocator.get(this);
		Uri uri = locator.buildPath(data.boardName, (data.isCatalog() ? "catalog" : data.pageNumber == 0
				? "index" : Integer.toString(data.pageNumber)) + ".json");
		HttpResponse response = new HttpRequest(uri, data).addCookie(buildCookiesWithCaptchaPass())
				.setValidator(data.validator).perform();
		DvachChanConfiguration configuration = DvachChanConfiguration.get(this);
		DvachModelMapper.BoardConfiguration boardConfiguration = new DvachModelMapper.BoardConfiguration();
		ArrayList<Posts> threads = new ArrayList<>();
		int boardSpeed = 0;
		try (InputStream input = response.open();
				JsonSerial.Reader reader = JsonSerial.reader(input)) {
			reader.startObject();
			while (!reader.endStruct()) {
				String name = reader.nextName();
				if (!boardConfiguration.handle(reader, name)) {
					switch (name) {
						case "threads": {
							boolean sageEnabled = boardConfiguration.sageEnabled != null
									? boardConfiguration.sageEnabled : configuration.isSageEnabled(data.boardName);
							reader.startArray();
							while (!reader.endStruct()) {
								threads.add(DvachModelMapper.createThread(reader,
										locator, data.boardName, sageEnabled));
							}
							break;
						}
						case "board_speed": {
							boardSpeed = reader.nextInt();
							break;
						}
						case "board": {
							reader.startObject();
							while (!reader.endStruct()) {
								String boardObjectName = reader.nextName();
								if(!boardConfiguration.handle(reader, boardObjectName)) {
									reader.skip();
								}
							}
							break;
						}
						default: {
							reader.skip();
							break;
						}
					}
				}
			}
			configuration.updateFromThreadsPostsJson(data.boardName, boardConfiguration);
			return new ReadThreadsResult(threads).setBoardSpeed(boardSpeed);
		} catch (ParseException e) {
			throw new InvalidResponseException(e);
		} catch (IOException e) {
			throw response.fail(e);
		}
	}

	@Override
	public ReadPostsResult onReadPosts(ReadPostsData data) throws HttpException, InvalidResponseException,
			RedirectException {
		boolean usePartialApi = data.partialThreadLoading && data.lastPostNumber != null;
		boolean tryReadStatic = false;
		try {
			return new ReadPostsResult(onReadPosts(data, usePartialApi, false));
		} catch (HttpException e) {
			int responseCode = e.getResponseCode();
			if (responseCode >= 500 && responseCode < 600 && usePartialApi) {
				tryReadStatic = true;
			} else if (responseCode != HttpURLConnection.HTTP_NOT_FOUND) {
				throw e;
			}
		}
		if (tryReadStatic) {
			try {
				return new ReadPostsResult(onReadPosts(data, false, false));
			} catch (HttpException e) {
				if (e.getResponseCode() != HttpURLConnection.HTTP_NOT_FOUND) {
					throw e;
				}
			}
		}
		return new ReadPostsResult(onReadPosts(data, false, true)).setFullThread(true);
	}

	@SuppressWarnings("SwitchStatementWithTooFewBranches")
	private Posts onReadPosts(ReadPostsData data, boolean usePartialApi, boolean archive) throws HttpException,
			InvalidResponseException, RedirectException {
		DvachChanLocator locator = DvachChanLocator.get(this);
		DvachChanConfiguration configuration = DvachChanConfiguration.get(this);
		Uri uri;
		HttpRequest.RedirectHandler handler = HttpRequest.RedirectHandler.BROWSER;
		Uri[] archiveThreadUri = {null};
		if (usePartialApi) {
			uri = locator.createMobileApiV2Uri("after", data.boardName, data.threadNumber, data.lastPostNumber == null
					? data.threadNumber : Integer.toString(Integer.parseInt(data.lastPostNumber) + 1));
		} else if (archive) {
			uri = locator.buildPath(data.boardName, "arch", "res", data.threadNumber + ".json");
			handler = response -> {
				archiveThreadUri[0] = response.getRedirectedUri();
				return HttpRequest.RedirectHandler.BROWSER.onRedirect(response);
			};
		} else {
			uri = locator.buildPath(data.boardName, "res", data.threadNumber + ".json");
		}
		HttpRequest request = new HttpRequest(uri, data).addCookie(buildCookiesWithCaptchaPass())
				.setValidator(data.validator).setRedirectHandler(handler);
		HttpResponse response = (usePartialApi ? readMobileApi(request) : request.perform());
		String archiveDate = null;
		if (archive) {
			if (archiveThreadUri[0] == null) {
				throw HttpException.createNotFoundException();
			}
			archiveDate = archiveThreadUri[0].getPath();
			int index1 = archiveDate.indexOf("arch/");
			int index2 = archiveDate.indexOf("/res");
			if (index1 >= 0 && index2 - index1 > 5) {
				archiveDate = archiveDate.substring(index1 + 5, index2);
			} else {
				throw HttpException.createNotFoundException();
			}
		}
		String archiveDateFinal = archiveDate;
		try (InputStream input = response.open();
				JsonSerial.Reader reader = JsonSerial.reader(input)) {
			try {
				if (usePartialApi) {
					List<Post> posts = Collections.emptyList();
					int uniquePosters = 0;
					int result = 0;
					reader.startObject();
					while (!reader.endStruct()) {
						switch (reader.nextName()) {
							case "posts": {
								posts = DvachModelMapper.createPosts(reader,
										locator, data.boardName, null,
										configuration.isSageEnabled(data.boardName), null);
								if (!posts.isEmpty()) {
									Post post = posts.get(0);
									String parentPostNumber = post.getParentPostNumber();
									if (parentPostNumber != null && !parentPostNumber.equals(data.threadNumber)) {
										throw RedirectException.toThread(data.boardName,
												parentPostNumber, post.getPostNumber());
									}
								}
								break;
							}
							case "unique_posters": {
								uniquePosters = reader.nextInt();
								break;
							}
							case "error": {
								throw handleMobileApiV2Error(reader);
							}
							case "result": {
								result = reader.nextInt();
								break;
							}
							default: {
								reader.skip();
								break;
							}
						}
					}
					if (result == 0) {
						throw new InvalidResponseException();
					}
					return new Posts(posts).setUniquePosters(uniquePosters);
				} else {
					if (archiveDateFinal != null && archiveDateFinal.equals("wakaba")) {
						ArrayList<Post> posts = new ArrayList<>();
						reader.startObject();
						while (!reader.endStruct()) {
							switch (reader.nextName()) {
								case "thread": {
									reader.startArray();
									while (!reader.endStruct()) {
										reader.startArray();
										// Array of arrays with a single post object (weird)
										posts.add(DvachModelMapper.createWakabaArchivePost(reader,
												this, data.boardName));
										while (!reader.endStruct()) {
											// Skip the rest items
											reader.skip();
										}
									}
									break;
								}
								default: {
									reader.skip();
									break;
								}
							}
						}
						return new Posts(posts);
					} else {
						DvachModelMapper.BoardConfiguration boardConfiguration =
								new DvachModelMapper.BoardConfiguration();
						ArrayList<Post> posts = null;
						int uniquePosters = 0;
						reader.startObject();
						while (!reader.endStruct()) {
							String name = reader.nextName();
							if (!boardConfiguration.handle(reader, name)) {
								switch (name) {
									case "threads": {
										boolean sageEnabled = boardConfiguration.sageEnabled != null
												? boardConfiguration.sageEnabled
												: configuration.isSageEnabled(data.boardName);
										reader.startArray();
										reader.startObject();
										while (!reader.endStruct()) {
											switch (reader.nextName()) {
												case "posts": {
													posts = DvachModelMapper.createPosts(reader,
															locator, data.boardName, archiveDateFinal,
															sageEnabled, null);
													break;
												}
												default: {
													reader.skip();
													break;
												}
											}
										}
										while (!reader.endStruct()) {
											reader.skip();
										}
										break;
									}
									case "board": {
										reader.startObject();
										while (!reader.endStruct()) {
											String boardObjectName = reader.nextName();
											if (!boardConfiguration.handle(reader, boardObjectName)) {
												reader.skip();
											}
										}
										break;
									}
									case "unique_posters": {
										uniquePosters = reader.nextInt();
										break;
									}
									default: {
										reader.skip();
										break;
									}
								}
							}
						}
						configuration.updateFromThreadsPostsJson(data.boardName, boardConfiguration);
						return new Posts(posts).setUniquePosters(uniquePosters);
					}
				}
			} catch (ParseException e) {
				if (archive && response.readString().contains("Доска не существует")) {
					throw HttpException.createNotFoundException();
				} else {
					throw e;
				}
			}
		} catch (ParseException e) {
			throw new InvalidResponseException(e);
		} catch (IOException e) {
			throw response.fail(e);
		}
	}

	@Override
	public ReadSinglePostResult onReadSinglePost(ReadSinglePostData data) throws HttpException,
			InvalidResponseException {
		DvachChanLocator locator = DvachChanLocator.get(this);
		DvachChanConfiguration configuration = DvachChanConfiguration.get(this);
		Uri uri = locator.createMobileApiV2Uri("post", data.boardName, data.postNumber);
		HttpResponse response = readMobileApi(new HttpRequest(uri, data).addCookie(buildCookiesWithCaptchaPass()));
		try (InputStream input = response.open();
				JsonSerial.Reader reader = JsonSerial.reader(input)) {
			Post post = null;
			int result = 0;
			reader.startObject();
			while (!reader.endStruct()) {
				switch (reader.nextName()) {
					case "post": {
						post = DvachModelMapper.createPost(reader, this, data.boardName, null,
								configuration.isSageEnabled(data.boardName), null);
						break;
					}
					case "error": {
						throw handleMobileApiV2Error(reader);
					}
					case "result": {
						result = reader.nextInt();
						break;
					}
					default: {
						reader.skip();
						break;
					}
				}
			}
			if (result == 0 || post == null) {
				throw new InvalidResponseException();
			}
			return new ReadSinglePostResult(post);
		} catch (ParseException e) {
			throw new InvalidResponseException(e);
		} catch (IOException e) {
			throw response.fail(e);
		}
	}

	private HttpException handleMobileApiV2Error(JsonSerial.Reader reader) throws IOException, ParseException {
		int code = 0;
		String error = "";
		reader.startObject();
		while (!reader.endStruct()) {
			switch (reader.nextName()) {
				case "code": {
					code = Math.abs(reader.nextInt());
					break;
				}
				case "error": {
					error = reader.nextString();
					break;
				}
				default: {
					reader.skip();
					break;
				}
			}
		}
		switch (code) {
			// ErrorNotFound
			case 667:
			// ErrorNoBoard
			case 2:
			// ErrorNoParent
			case 3:
			// ErrorNoPost
			case 31: {
				return HttpException.createNotFoundException();
			}
			default: {
				return new HttpException(code, error);
			}
		}
	}

	@SuppressWarnings("SwitchStatementWithTooFewBranches")
	@Override
	public ReadSearchPostsResult onReadSearchPosts(ReadSearchPostsData data) throws HttpException,
			InvalidResponseException {
		DvachChanLocator locator = DvachChanLocator.get(this);
		DvachChanConfiguration configuration = DvachChanConfiguration.get(this);
		if (data.searchQuery.startsWith("#")) {
			Uri uri = locator.buildPath(data.boardName, "catalog.json");
			HttpResponse response = new HttpRequest(uri, data).addCookie(buildCookiesWithCaptchaPass()).perform();
			try (InputStream input = response.open();
					JsonSerial.Reader reader = JsonSerial.reader(input)) {
				String tag = data.searchQuery.substring(1);
				ArrayList<Post> posts = new ArrayList<>();
				reader.startObject();
				while (!reader.endStruct()) {
					switch (reader.nextName()) {
						case "threads": {
							reader.startArray();
							while (!reader.endStruct()) {
								DvachModelMapper.Extra extra = new DvachModelMapper.Extra();
								Post post = DvachModelMapper.createPost(reader, this, data.boardName, null,
										configuration.isSageEnabled(data.boardName), extra);
								if (tag.equals(extra.tags)) {
									posts.add(post);
								}
							}
							break;
						}
						default: {
							reader.skip();
							break;
						}
					}
				}
				return new ReadSearchPostsResult(posts);
			} catch (ParseException e) {
				throw new InvalidResponseException(e);
			} catch (IOException e) {
				throw response.fail(e);
			}
		} else {
			Uri uri = locator.buildPath("user/search");
			MultipartEntity entity = new MultipartEntity("board", data.boardName,
					"text", data.searchQuery);
			HttpResponse response = new HttpRequest(uri, data).addCookie(buildCookiesWithCaptchaPass())
					.setPostMethod(entity).setRedirectHandler(HttpRequest.RedirectHandler.STRICT).perform();

			List<String> postsNumbers = DvachModelMapper.createPostsFromHtml(response.readString());
			List<Post> posts = new ArrayList<>();

			for (String number : postsNumbers) {

				uri = locator.createMobileApiV2Uri("post", data.boardName, number);
				response = new HttpRequest(uri, data).addCookie(buildCookiesWithCaptchaPass())
						.setGetMethod().setRedirectHandler(HttpRequest.RedirectHandler.STRICT).perform();

				try(InputStream input = response.open(); JsonSerial.Reader reader = JsonSerial.reader(input)) {

					reader.startObject();
					while (!reader.endStruct()) {
						switch (reader.nextName()) {
							case "post": {
								Post post = DvachModelMapper.createPost(reader, this, data.boardName, null,
										configuration.isSageEnabled(data.boardName), null);
								posts.add(post);
								break;
							}
							default: {
								reader.skip();
								break;
							}
						}
					}

				} catch (ParseException e) {
					throw new InvalidResponseException();
				} catch (IOException e) {
					throw response.fail(e);
				}

			}

			return new ReadSearchPostsResult(posts);
		}
	}

	@SuppressWarnings("SwitchStatementWithTooFewBranches")
	@Override
	public ReadBoardsResult onReadBoards(ReadBoardsData data) throws HttpException, InvalidResponseException {

		DvachChanConfiguration configuration = DvachChanConfiguration.get(this);
		DvachChanLocator locator = DvachChanLocator.get(this);
		Uri uri = locator.buildPath("/api/mobile/v2/boards");

		JSONArray jsonArray = null;
		try {
			String value = new HttpRequest(uri, data)
					.setGetMethod()
					.addCookie(buildCookiesWithCaptchaPass()).perform().readString();
			jsonArray = new JSONArray(value);
		} catch (HttpException e) {
			throw new HttpException(e.getResponseCode(), e.getMessage());
		} catch (JSONException e) {
			throw new InvalidResponseException();
		}

		HashMap<String, ArrayList<Board>> boardsMap = new HashMap<>();

		try {
			for (int i = 0; i < jsonArray.length(); i++) {

				JSONObject jsonObject = jsonArray.getJSONObject(i);

				String category = null;
				String boardName = null;
				String title = null;
				String description = null;
				String defaultName = null;
				Integer bumpLimit = null;

				Iterator<String> keys = jsonObject.keys();

				while (keys.hasNext()) {
					switch (keys.next()) {
						case "category": {
							category = jsonObject.getString("category");
							break;
						}
						case "id": {
							boardName = jsonObject.getString("id");;
							break;
						}
						case "name": {
							title = jsonObject.getString("name");;
							break;
						}
						case "info": {
							description = jsonObject.getString("info");;
							break;
						}
						case "default_name": {
							defaultName = jsonObject.getString("default_name");;
							break;
						}
						case "bump_limit": {
							bumpLimit = Integer.getInteger(jsonObject.getString("bump_limit"));;
							break;
						}
						default: {
							break;
						}
					}
				}

				if (!StringUtils.isEmpty(category) && !StringUtils.isEmpty(boardName) &&
						!StringUtils.isEmpty(title)) {
					ArrayList<Board> boards = boardsMap.get(category);
					if (boards == null) {
						boards = new ArrayList<>();
						boardsMap.put(category, boards);
					}
					description = configuration.transformBoardDescription(description);
					boards.add(new Board(boardName, title, description));
					configuration.updateFromBoardsJson(boardName, defaultName, bumpLimit);
				}

			}
		} catch (JSONException e) {
			throw new InvalidResponseException();
		}

		ArrayList<BoardCategory> boardCategories = new ArrayList<>();
		for (String title : PREFERRED_BOARDS_ORDER) {
			for (HashMap.Entry<String, ArrayList<Board>> entry : boardsMap.entrySet()) {
				if (title.equals(entry.getKey())) {
					ArrayList<Board> boards = entry.getValue();
					Collections.sort(boards);
					boardCategories.add(new BoardCategory(title, boards));
					break;
				}
			}
		}

		return new ReadBoardsResult(boardCategories);

	}

	@SuppressWarnings("SwitchStatementWithTooFewBranches")
	@Override
	public ReadUserBoardsResult onReadUserBoards(ReadUserBoardsData data) throws HttpException,
			InvalidResponseException {

		DvachChanConfiguration configuration = DvachChanConfiguration.get(this);
		DvachChanLocator locator = DvachChanLocator.get(this);
		Uri uri = locator.buildPath("/api/mobile/v2/boards");

		JSONArray jsonArray = null;
		try {
			String value = new HttpRequest(uri, data)
					.setGetMethod()
					.addCookie(buildCookiesWithCaptchaPass()).perform().readString();
			jsonArray = new JSONArray(value);
		} catch (HttpException e) {
			throw new HttpException(e.getResponseCode(), e.getMessage());
		} catch (JSONException e) {
			throw new InvalidResponseException();
		}

		ArrayList<Board> boards = new ArrayList<>();

		try {
			for (int i = 0; i < jsonArray.length(); i++) {

				JSONObject jsonObject = jsonArray.getJSONObject(i);

				String boardName = null;
				String title = null;
				String description = null;

				Iterator<String> keys = jsonObject.keys();

				while (keys.hasNext()) {
					switch (keys.next()) {
						case "id": {
							boardName = jsonObject.getString("id");
							;
							break;
						}
						case "name": {
							title = jsonObject.getString("name");
							;
							break;
						}
						case "info": {
							description = jsonObject.getString("info");
							;
							break;
						}
						default: {
							break;
						}
					}
					if (jsonObject.getString("category").equals("Пользовательские")) {
						if (!StringUtils.isEmpty(boardName) && !StringUtils.isEmpty(title)) {
							description = configuration.transformBoardDescription(description);
							boards.add(new Board(boardName, title, description));
						}
					}
				}
			}
		} catch (JSONException e) {
			throw new InvalidResponseException();
		}

		return new ReadUserBoardsResult(boards);

	}

	@SuppressWarnings("SwitchStatementWithTooFewBranches")
	@Override
	public ReadThreadSummariesResult onReadThreadSummaries(ReadThreadSummariesData data) throws HttpException,
			InvalidResponseException {
		if (data.type == ReadThreadSummariesData.TYPE_ARCHIVED_THREADS) {
			DvachChanLocator locator = DvachChanLocator.get(this);
			Uri uri = locator.buildPath(data.boardName, "arch", "index.json");
			HttpResponse response = new HttpRequest(uri, data).perform();
			ArrayList<Integer> pages = new ArrayList<>();
			List<ThreadSummary> threadSummaries = Collections.emptyList();
			try (InputStream input = response.open();
					JsonSerial.Reader reader = JsonSerial.reader(input)) {
				reader.startObject();
				while (!reader.endStruct()) {
					switch (reader.nextName()) {
						case "pages": {
							reader.startArray();
							while (!reader.endStruct()) {
								pages.add(reader.nextInt());
							}
							break;
						}
						case "threads": {
							if (data.pageNumber > 0 && !pages.isEmpty()) {
								reader.skip();
							} else {
								threadSummaries = DvachModelMapper.createArchive(reader, data.boardName);
							}
							break;
						}
						default: {
							reader.skip();
							break;
						}
					}
				}
			} catch (ParseException e) {
				throw new InvalidResponseException(e);
			} catch (IOException e) {
				throw response.fail(e);
			}
			if (data.pageNumber > 0) {
				if (data.pageNumber + 1 > pages.size()) {
					return new ReadThreadSummariesResult();
				}
				int pageNumber = pages.get(pages.size() - data.pageNumber - 1);
				uri = locator.buildPath(data.boardName, "arch", pageNumber + ".json");
				response = new HttpRequest(uri, data).perform();
				threadSummaries = Collections.emptyList();
				try (InputStream input = response.open();
						JsonSerial.Reader reader = JsonSerial.reader(input)) {
					reader.startObject();
					while (!reader.endStruct()) {
						switch (reader.nextName()) {
							case "threads": {
								threadSummaries = DvachModelMapper.createArchive(reader, data.boardName);
								break;
							}
							default: {
								reader.skip();
								break;
							}
						}
					}
				} catch (ParseException e) {
					throw new InvalidResponseException(e);
				} catch (IOException e) {
					throw response.fail(e);
				}
			}
			return new ReadThreadSummariesResult(threadSummaries);
		} else {
			return super.onReadThreadSummaries(data);
		}
	}

	@SuppressWarnings("SwitchStatementWithTooFewBranches")
	@Override
	public ReadPostsCountResult onReadPostsCount(ReadPostsCountData data) throws HttpException,
			InvalidResponseException {
		DvachChanLocator locator = DvachChanLocator.get(this);
		Uri uri = locator.createMobileApiV2Uri("info", data.boardName, data.threadNumber);
		HttpResponse response = readMobileApi(new HttpRequest(uri, data).addCookie(buildCookiesWithCaptchaPass()));
		try (InputStream input = response.open();
				JsonSerial.Reader reader = JsonSerial.reader(input)) {
			int count = 0;
			int result = 0;
			reader.startObject();
			while (!reader.endStruct()) {
				switch (reader.nextName()) {
					case "thread": {
						reader.startObject();
						while (!reader.endStruct()) {
							switch (reader.nextName()) {
								case "posts": {
									count = reader.nextInt() + 1;
									break;
								}
								default: {
									reader.skip();
									break;
								}
							}
						}
						break;
					}
					case "error": {
						throw handleMobileApiV2Error(reader);
					}
					case "result": {
						result = reader.nextInt();
						break;
					}
					default: {
						reader.skip();
						break;
					}
				}
			}
			if (result == 0) {
				throw new InvalidResponseException();
			}
			return new ReadPostsCountResult(count);
		} catch (ParseException e) {
			throw new InvalidResponseException(e);
		} catch (IOException e) {
			throw response.fail(e);
		}
	}

	@Override
	public ReadContentResult onReadContent(ReadContentData data) throws HttpException {
		return new ReadContentResult(new HttpRequest(data.uri, data.direct)
				.addCookie(buildCookiesWithCaptchaPass()).perform());
	}

	@Override
	public CheckAuthorizationResult onCheckAuthorization(CheckAuthorizationData data) throws HttpException,
			InvalidResponseException {
		return new CheckAuthorizationResult(readCaptchaPass(data, data.authorizationData[0]) != null);
	}

	private String lastCaptchaPassData;
	private String lastCaptchaPassCookie;

	private String readCaptchaPass(HttpRequest.Preset preset, String captchaPassData) throws HttpException,
			InvalidResponseException {
		lastCaptchaPassData = null;
		lastCaptchaPassCookie = null;
		DvachChanConfiguration configuration = DvachChanConfiguration.get(this);
		configuration.revokeMaxFilesCount();
		configuration.storeCookie(COOKIE_PASSCODE_AUTH, null, null);
		DvachChanLocator locator = DvachChanLocator.get(this);
		Uri uri = locator.buildPath("/user/passlogin");
		UrlEncodedEntity entity = new UrlEncodedEntity("passcode", captchaPassData);

		HttpResponse response;
		try {
			response = new HttpRequest(uri, preset).addCookie(buildCookies(null))
					.setPostMethod(entity).setRedirectHandler(HttpRequest.RedirectHandler.NONE).perform();
		} catch (HttpException e) {
			throw new InvalidResponseException();
		}

		String captchaPassCookie = "";
		if (response != null) {
			captchaPassCookie = response.getCookieValue(COOKIE_PASSCODE_AUTH);
		}
		if (StringUtils.isEmpty(captchaPassCookie)) {
			throw new InvalidResponseException();
		}
		lastCaptchaPassData = captchaPassData;
		lastCaptchaPassCookie = captchaPassCookie;
		if (captchaPassCookie != null) {
			configuration.setMaxFilesCount(PASSCODUBOYAR_MAX_FILES);
			configuration.storeCookie(COOKIE_PASSCODE_AUTH, captchaPassCookie, "Passcode Auth");
		}
		return captchaPassCookie;
	}

	private static final String CAPTCHA_PASS_COOKIE = "captchaPassCookie";

	@Override
	public ReadCaptchaResult onReadCaptcha(ReadCaptchaData data) throws HttpException, InvalidResponseException {
		return onReadCaptcha(data, data.captchaPass != null ? data.captchaPass[0] : null, true);
	}

	private static ReadCaptchaResult makeCaptchaPassResult(String captchaPassCookie) {
		CaptchaData captchaData = new CaptchaData();
		captchaData.put(CAPTCHA_PASS_COOKIE, captchaPassCookie);
		return new ReadCaptchaResult(CaptchaState.PASS, captchaData)
				.setValidity(DvachChanConfiguration.Captcha.Validity.LONG_LIFETIME);
	}

	private static String normalizeCaptchaType(String captchaType) {
		return DvachChanConfiguration.CAPTCHA_TYPES.containsKey(captchaType) ? captchaType
				: DvachChanConfiguration.CAPTCHA_TYPE_2CH_EMOJI_CAPTCHA;
	}

	private ReadCaptchaResult onReadCaptcha(ReadCaptchaData data, String captchaPassData,
			boolean mayUseLastCaptchaPassCookie) throws HttpException, InvalidResponseException {
		DvachChanLocator locator = DvachChanLocator.get(this);
		DvachChanConfiguration configuration = DvachChanConfiguration.get(this);
		String captchaPassCookie = null;
		boolean mayRelogin = false;
		if (captchaPassData != null) {
			if (mayUseLastCaptchaPassCookie && captchaPassData.equals(lastCaptchaPassData)) {
				captchaPassCookie = lastCaptchaPassCookie;
				mayRelogin = true;
			} else {
				captchaPassCookie = readCaptchaPass(data, captchaPassData);
			}
		}

		String captchaType = normalizeCaptchaType(data.captchaType);
		String remoteCaptchaType = DvachChanConfiguration.CAPTCHA_TYPES.get(captchaType);
		if (remoteCaptchaType == null) {
			throw new RuntimeException();
		}

		Uri.Builder uriBuilder = locator.buildPath("api", "captcha", remoteCaptchaType, "id").buildUpon();
		uriBuilder.appendQueryParameter("board", data.boardName);
		if (data.threadNumber != null) {
			uriBuilder.appendQueryParameter("thread", data.threadNumber);
		}
		Uri uri = uriBuilder.build();
		JSONObject jsonObject = null;
		HttpException exception = null;
		try {
			jsonObject = new JSONObject(readMobileApi(new HttpRequest(uri, data)
					.addCookie(buildCookies(captchaPassCookie))).readString());
		} catch (JSONException e) {
			// Ignore exception
		} catch (HttpException e) {
			if (!e.isHttpException()) {
				throw e;
			}
			exception = e;
			boolean tooManyRequestsException = exception.getResponseCode() == 429;
			if(tooManyRequestsException){
				mayRelogin = false;
			}
		}

		String apiResult = jsonObject != null ? CommonUtils.optJsonString(jsonObject, "result") : null;
		String apiCaptchaType = jsonObject != null ? CommonUtils.optJsonString(jsonObject, "type") : null;
		if ("3".equals(apiResult) || "nocaptcha".equals(apiCaptchaType)) {
			configuration.setMaxFilesCountEnabled(false);
			return new ReadCaptchaResult(CaptchaState.SKIP, null);
		} else if ("2".equals(apiResult) || "passcode".equals(apiCaptchaType)) {
			configuration.setMaxFilesCountEnabled(true);
			return makeCaptchaPassResult(captchaPassCookie);
		} else {
			if (mayRelogin) {
				return onReadCaptcha(data, captchaPassData, false);
			}
			configuration.setMaxFilesCountEnabled(false);
			String id = jsonObject != null ? CommonUtils.optJsonString(jsonObject, "id") : null;
			if (id != null) {
				if (data.mayShowLoadButton) {
					return new ReadCaptchaResult(CaptchaState.NEED_LOAD, null);
				}
				DvachEmojiCaptchaProvider.DvachEmojiCaptchaAnswerRetriever retriever =
						(Bitmap task, Bitmap[] keyboardImages) -> {
							try {
								return requireUserImageSingleChoice(-1, keyboardImages,
										configuration.getResources().getString(R.string.emoji_captcha_input),
										task);
							} catch (HttpException e) {
								return -1;
							}
						};
				return new DvachEmojiCaptchaProvider(data, locator, id, retriever).loadEmojiCaptcha();
			} else {
				if (exception != null) {
					// If wakaba is swaying, but passcode is verified, let's try to use it
					if (captchaPassCookie != null) {
						configuration.setMaxFilesCountEnabled(true);
						return makeCaptchaPassResult(captchaPassCookie);
					}
					throw exception;
				}
				throw new InvalidResponseException();
			}
		}
	}

	private static final Pattern PATTERN_TAG = Pattern.compile("(.*) /([^/]*)/");
	private static final Pattern PATTERN_BAN = Pattern.compile("[^ ]*?: (\\d+)\\. .*: (.*(?=//![a-z]+\\.)|.*(?=[А-Я][а-я]{2} [А-Я][а-я]{2} \\d{2} (?:\\d{2}:?){3} \\d{4}$)|.*$)(?:.*?[а-я] )?([А-Я].*|)");

	private static final ThreadLocal<SimpleDateFormat> DATE_FORMAT_BAN =
			ThreadLocal.withInitial(DvachChanPerformer::createBanDateFormat);

	@SuppressLint("SimpleDateFormat")
	private static SimpleDateFormat createBanDateFormat() {
		DateFormatSymbols symbols = new DateFormatSymbols();
		symbols.setShortMonths(new String[] {"Янв", "Фев", "Мар", "Апр", "Май", "Июн", "Июл", "Авг",
				"Сен", "Окт", "Ноя", "Дек"});
		SimpleDateFormat dateFormatBan = new SimpleDateFormat("MMM dd HH:mm:ss yyyy", symbols);
		dateFormatBan.setTimeZone(TimeZone.getTimeZone("GMT+3"));
		return dateFormatBan;
	}

	@Override
	public SendPostResult onSendPost(SendPostData data) throws HttpException, ApiException, InvalidResponseException {
		DvachChanLocator locator = DvachChanLocator.get(this);
		String subject = data.subject;
		String tag = null;
		if (data.threadNumber == null && data.subject != null) {
			Matcher matcher = PATTERN_TAG.matcher(subject);
			if (matcher.matches()) {
				subject = matcher.group(1);
				tag = matcher.group(2);
			}
		}
		MultipartEntity entity = new MultipartEntity();
		entity.add("task", "post");
		entity.add("board", data.boardName);
		entity.add("thread", data.threadNumber != null ? data.threadNumber : "0");
		entity.add("subject", subject);
		entity.add("tags", tag);
		entity.add("comment", data.comment);
		entity.add("name", data.name);
		entity.add("email", data.optionSage ? "sage" : data.email);
		if (data.optionOriginalPoster) {
			entity.add("op_mark", "1");
		}
		if (data.attachments != null) {
			for (int i = 0; i < data.attachments.length; i++) {
				data.attachments[i].addToEntity(entity, "file[]");
			}
		}
		entity.add("icon", data.userIcon);

		String captchaPassCookie = null;
		if (data.captchaData != null) {
			captchaPassCookie = data.captchaData.get(CAPTCHA_PASS_COOKIE);
			String challenge = data.captchaData.get(CaptchaData.CHALLENGE);
			if (!StringUtils.isEmpty(challenge)) {
				entity.add("captcha_type", DvachChanConfiguration.CAPTCHA_TYPE_2CH_EMOJI_CAPTCHA);
				entity.add("emoji_captcha_id", challenge);
			}
		}

		String originalPosterCookieName = null;
		String originalPosterCookie = null;
		if (data.threadNumber != null && data.optionOriginalPoster) {
			DvachChanConfiguration configuration = DvachChanConfiguration.get(this);
			originalPosterCookieName = "op_" + data.boardName + "_" + data.threadNumber;
			originalPosterCookie = configuration.getCookie(originalPosterCookieName);
		}

		Uri uri = locator.buildPath("user/posting");
		HttpResponse response = new HttpRequest(uri, data).setPostMethod(entity)
				.addCookie(buildCookies(captchaPassCookie)).addCookie(originalPosterCookieName, originalPosterCookie)
				.setRedirectHandler(HttpRequest.RedirectHandler.STRICT).perform();
		JSONObject jsonObject;
		try {
			jsonObject = new JSONObject(response.readString());
		} catch (JSONException e) {
			throw new InvalidResponseException(e);
		}
		String auth = response.getCookieValue(COOKIE_USERCODE_AUTH);
		if (!StringUtils.isEmpty(auth)) {
			DvachChanConfiguration configuration = DvachChanConfiguration.get(this);
			configuration.storeCookie(COOKIE_USERCODE_AUTH, auth, "Usercode Auth");
		}
		String postNumber = CommonUtils.optJsonString(jsonObject, "num");
		if (!StringUtils.isEmpty(postNumber)) {
			return new SendPostResult(data.threadNumber, postNumber);
		}
		String threadNumber = CommonUtils.optJsonString(jsonObject, "thread");
		if (!StringUtils.isEmpty(threadNumber)) {
			originalPosterCookieName = "op_" + data.boardName + "_" + threadNumber;
			originalPosterCookie = response.getCookieValue(originalPosterCookieName);
			if (!StringUtils.isEmpty(originalPosterCookie)) {
				DvachChanConfiguration configuration = DvachChanConfiguration.get(this);
				configuration.storeCookie(originalPosterCookieName, originalPosterCookie,
						"OP /" + data.boardName + "/" + threadNumber);
			}
			return new SendPostResult(threadNumber, null);
		}

		int error;
		String reason;
		try {
			JSONObject jsonError = new JSONObject(CommonUtils.getJsonString(jsonObject, "error"));
			error = Math.abs(jsonError.optInt("code", Integer.MAX_VALUE));
			reason = CommonUtils.optJsonString(jsonError, "message");
		} catch (JSONException e) {
			throw new InvalidResponseException();
		}

		int errorType = 0;
		Object extra = null;
		switch (error) {
			case 2: {
				errorType = ApiException.SEND_ERROR_NO_BOARD;
				break;
			}
			case 3: {
				errorType = ApiException.SEND_ERROR_NO_THREAD;
				break;
			}
			case 4: {
				errorType = ApiException.SEND_ERROR_NO_ACCESS;
				break;
			}
			case 7: {
				errorType = ApiException.SEND_ERROR_CLOSED;
				break;
			}
			case 8: {
				errorType = ApiException.SEND_ERROR_TOO_FAST;
				break;
			}
			case 9: {
				errorType = ApiException.SEND_ERROR_FIELD_TOO_LONG;
				break;
			}
			case 10: {
				errorType = ApiException.SEND_ERROR_FILE_EXISTS;
				break;
			}
			case 11: {
				errorType = ApiException.SEND_ERROR_FILE_NOT_SUPPORTED;
				reason = DvachChanConfiguration.get(this).getResources()
						.getString(R.string.posting_file_not_supported);
				break;
			}
			case 12: {
				errorType = ApiException.SEND_ERROR_FILE_TOO_BIG;
				break;
			}
			case 13: {
				errorType = ApiException.SEND_ERROR_FILES_TOO_MANY;
				break;
			}
			case 16:
			case 18: {
				errorType = ApiException.SEND_ERROR_SPAM_LIST;
				break;
			}
			case 19: {
				errorType = ApiException.SEND_ERROR_EMPTY_FILE;
				break;
			}
			case 20: {
				errorType = ApiException.SEND_ERROR_EMPTY_COMMENT;
				break;
			}
			case 6:
			case 14:
			case 15: {
				errorType = ApiException.SEND_ERROR_BANNED;
				break;
			}
			case 5:
			case 21:
			case 22: {
				errorType = ApiException.SEND_ERROR_CAPTCHA;
				break;
			}
			default: {
				if (reasonIsBanMessage(reason)){
					errorType = ApiException.SEND_ERROR_BANNED;
				}
			}
		}
		if (errorType == ApiException.SEND_ERROR_BANNED) {
			ApiException.BanExtra banExtra = new ApiException.BanExtra();
			Matcher matcher = PATTERN_BAN.matcher(reason);
			if(matcher.find()) {
				String banId = StringUtils.emptyIfNull(matcher.group(1));
				banExtra.setId(banId);
				String banMessage = StringUtils.emptyIfNull(matcher.group(2));
				banExtra.setMessage(banMessage);
				String banExpireDate = StringUtils.emptyIfNull(matcher.group(3));
				if (!StringUtils.isEmpty(banExpireDate)) {
					try {
						long date = Objects.requireNonNull(DATE_FORMAT_BAN.get().parse(banExpireDate)).getTime();
						banExtra.setExpireDate(date);
					} catch (java.text.ParseException e) {
						// Ignore exception
					}
				}
			}
			else {
				banExtra.setMessage(reason);
			}
			extra = banExtra;
		}
		if (errorType == ApiException.SEND_ERROR_CAPTCHA) {
			lastCaptchaPassData = null;
			lastCaptchaPassCookie = null;
		}
		if (extra != null) {
			throw new ApiException(errorType, extra);
		}
		if (errorType != 0) {
			throw new ApiException(errorType);
		}
		if (!StringUtils.isEmpty(reason)) {
			throw new ApiException(reason);
		}
		throw new InvalidResponseException();
	}

	private boolean reasonIsBanMessage(String reason){
		if (!StringUtils.isEmpty(reason)){
			String lowerCaseReason = reason.toLowerCase();
			return lowerCaseReason.contains("постинг запрещён") || lowerCaseReason.contains("бан");
		}
		return false;
	}

	@Override
	public SendReportPostsResult onSendReportPosts(SendReportPostsData data) throws HttpException, ApiException,
			InvalidResponseException {
		DvachChanLocator locator = DvachChanLocator.get(this);
		Uri uri = locator.buildPath("user/report");
		StringBuilder postsBuilder = new StringBuilder();
		for (String postNumber : data.postNumbers) {
			postsBuilder.append(postNumber).append(", ");
		}

		MultipartEntity entity = new MultipartEntity();
		entity.add("board", data.boardName);
		entity.add("thread", data.threadNumber);
		if (!data.postNumbers.isEmpty()) {
			entity.add("post", data.postNumbers.get(0));
		}
		entity.add("comment", data.comment);

		String referer = locator.createThreadUri(data.boardName, data.threadNumber).toString();
		JSONObject jsonObject;
		try {
			jsonObject = new JSONObject(new HttpRequest(uri, data).addCookie(buildCookiesWithCaptchaPass())
					.setPostMethod(entity).setRedirectHandler(HttpRequest.RedirectHandler.STRICT).perform().readString());
		} catch (JSONException e) {
			throw new InvalidResponseException(e);
		}
		try {
			String result = CommonUtils.getJsonString(jsonObject, "result");
			String message = "";
			if (!result.equals("1")) {
				message = CommonUtils.getJsonString(jsonObject, "message");
				if (StringUtils.isEmpty(message)) {
					return null;
				}
				int errorType = 0;
				if (message.contains("Вы уже отправляли жалобу")) {
					errorType = ApiException.REPORT_ERROR_TOO_OFTEN;
				} else if (message.contains("Вы ничего не написали в жалобе")) {
					errorType = ApiException.REPORT_ERROR_EMPTY_COMMENT;
				}
				if (errorType != 0) {
					throw new ApiException(errorType);
				}
			} else {
				return null;
			}
			throw new ApiException(message);
		} catch (JSONException e) {
			throw new InvalidResponseException(e);
		}
	}

	@Override
	public SendVotePostResult onSendVotePost(SendVotePostData data) throws HttpException, ApiException,
			InvalidResponseException {
		DvachChanLocator locator = DvachChanLocator.get(this);
		Uri uri = locator.buildPath("api", data.isLike ? "like" : "dislike").buildUpon()
				.appendQueryParameter("board", data.boardName)
				.appendQueryParameter("num", data.postNumber)
				.build();
		String response = new HttpRequest(uri, data).addCookie(buildCookiesWithCaptchaPass()).setGetMethod()
				.setRedirectHandler(HttpRequest.RedirectHandler.STRICT).perform().readString();
		try {
			JSONObject jsonObject = new JSONObject(response);
			Object resultValue = jsonObject.opt("result");
			if (Boolean.TRUE.equals(resultValue) || resultValue instanceof Number
					&& ((Number) resultValue).intValue() == 1 || "1".equals(String.valueOf(resultValue))
					|| "true".equalsIgnoreCase(String.valueOf(resultValue))) {
				return new SendVotePostResult();
			}
			Object errorValue = jsonObject.opt("error");
			String error = null;
			if (errorValue instanceof JSONObject) {
				JSONObject errorObject = (JSONObject) errorValue;
				error = errorObject.optString("message", errorObject.optString("error", null));
			} else if (errorValue != null && errorValue != JSONObject.NULL) {
				error = errorValue.toString();
				if (error.startsWith("{")) {
					JSONObject errorObject = new JSONObject(error);
					error = errorObject.optString("message", errorObject.optString("error", error));
				}
			}
			if (StringUtils.isEmpty(error)) {
				error = jsonObject.optString("message", null);
			}
			if (!StringUtils.isEmpty(error)) {
				String lowerError = error.toLowerCase();
				if (lowerError.contains("уже голос") || lowerError.contains("already voted")
						|| lowerError.contains("постинг запрещ") || lowerError.contains("posting prohibited")) {
					throw new ApiException(ApiException.VOTE_ERROR_ALREADY_VOTED);
				}
				throw new ApiException(error);
			}
			throw new InvalidResponseException();
		} catch (JSONException e) {
			throw new InvalidResponseException(e);
		}
	}

}
