package com.mishiranu.dashchan.chan.soyjak;

import android.net.Uri;
import chan.content.Chan;
import chan.content.ChanPerformer;
import chan.content.InvalidResponseException;
import chan.content.model.Board;
import chan.content.model.BoardCategory;
import chan.content.model.Post;
import chan.content.model.Posts;
import chan.http.HttpException;
import chan.http.HttpRequest;
import chan.http.HttpResponse;
import chan.util.StringUtils;
import com.mishiranu.dashchan.util.NetworkDiagnostics;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import org.json.JSONTokener;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Element;
import org.jsoup.select.Elements;

public class SoyjakChanPerformer extends ChanPerformer {
	@Override
	public ReadBoardsResult onReadBoards(ReadBoardsData data) throws HttpException, InvalidResponseException {
		SoyjakChanLocator locator = SoyjakChanLocator.get(this);
		Uri home = locator.buildPath();
		HttpResponse response = new HttpRequest(home, data).perform();
		List<Board> boards = parseBoards(response.readString(), home, locator);
		recordParse(response, "boards", !boards.isEmpty(), boards.size());
		if (boards.isEmpty()) throw new InvalidResponseException();
		return new ReadBoardsResult(new BoardCategory("soyjak.st", boards));
	}

	static List<Board> parseBoards(String html, Uri home, SoyjakChanLocator locator) {
		LinkedHashMap<String, Board> boards = new LinkedHashMap<>();
		for (Element table : Jsoup.parse(html, home.toString()).select("table")) {
			boolean boardTable = false;
			for (Element row : table.select("tr")) {
				Elements cells = row.children();
				if (cells.size() < 2) continue;
				if (!boardTable) {
					boardTable = "Board".equalsIgnoreCase(cells.get(0).text())
							&& "Subtitle".equalsIgnoreCase(cells.get(1).text());
					continue;
				}
				Element cell = cells.get(0);
				Element link = cell.selectFirst("a[href]");
				if (link == null) continue;
				Uri uri = Uri.parse(link.absUrl("href"));
				if (!locator.isBoardUri(uri)) continue;
				String boardName = locator.getBoardName(uri);
				String prefix = "/" + boardName + "/";
				String label = cell.text();
				if (!label.startsWith(prefix)) continue;
				String title = label.substring(prefix.length()).trim().replaceFirst("^[-–—]\\s*", "");
				boards.putIfAbsent(boardName, new Board(boardName,
						StringUtils.isEmpty(title) ? boardName : title));
			}
		}
		return new ArrayList<>(boards.values());
	}

	@Override
	public ReadThreadsResult onReadThreads(ReadThreadsData data) throws HttpException, InvalidResponseException {
		SoyjakChanLocator locator = SoyjakChanLocator.get(this);
		HttpResponse response = new HttpRequest(locator.createThreadsApiUri(data.boardName, data.pageNumber), data)
				.setValidator(data.validator).perform();
		String body = response.readString();
		String resource = data.isCatalog() ? "catalog" : "board_page";
		try {
			List<Posts> threads;
			if (data.isCatalog()) {
				JSONArray pages = new JSONArray(body);
				threads = parseCatalog(pages, locator, data.boardName);
				if (pages.length() > 0) {
					SoyjakChanConfiguration.get(this).storePagesCount(data.boardName, pages.length());
				}
			} else {
				threads = parseBoardPage(new JSONObject(body), locator, data.boardName);
			}
			recordParse(response, resource, true, threads.size());
			return new ReadThreadsResult(threads).setValidator(response.getValidator());
		} catch (JSONException e) {
			recordParse(response, resource, false, 0);
			throw new InvalidResponseException(e);
		}
	}

	static List<Posts> parseCatalog(JSONArray pages, SoyjakChanLocator locator, String boardName) throws JSONException {
		ArrayList<Posts> threads = new ArrayList<>();
		for (int i = 0; i < pages.length(); i++) {
			JSONArray entries = pages.getJSONObject(i).getJSONArray("threads");
			for (int j = 0; j < entries.length(); j++) {
				threads.add(SoyjakModelMapper.readCatalogEntry(entries.getJSONObject(j), locator, boardName));
			}
		}
		return threads;
	}

	static List<Posts> parseCatalogDocument(String boardName, String document) throws JSONException {
		JSONTokener tokener = new JSONTokener(document);
		Object value = tokener.nextValue();
		if (!(value instanceof JSONArray) || tokener.nextClean() != '\0') {
			throw new JSONException("Expected a catalog array");
		}
		if (!(Chan.get("soyjak").locator instanceof SoyjakChanLocator locator)) {
			throw new JSONException("Soyjak is unavailable");
		}
		return parseCatalog((JSONArray) value, locator, boardName);
	}

	static List<Posts> parseBoardPage(JSONObject page, SoyjakChanLocator locator, String boardName) throws JSONException {
		ArrayList<Posts> threads = new ArrayList<>();
		JSONArray entries = page.getJSONArray("threads");
		for (int i = 0; i < entries.length(); i++) {
			threads.add(SoyjakModelMapper.readPreview(entries.getJSONObject(i).getJSONArray("posts"), locator, boardName));
		}
		return threads;
	}

	@Override
	public ReadPostsResult onReadPosts(ReadPostsData data) throws HttpException, InvalidResponseException {
		SoyjakChanLocator locator = SoyjakChanLocator.get(this);
		HttpResponse response = new HttpRequest(locator.createThreadApiUri(data.boardName, data.threadNumber), data)
				.setValidator(data.validator).perform();
		try {
			List<Post> parsed = parseThread(new JSONObject(response.readString()), locator, data.boardName, data.threadNumber);
			recordParse(response, "thread", true, parsed.size());
			return new ReadPostsResult(parsed)
					.setFullThread(true).setValidator(response.getValidator());
		} catch (JSONException e) {
			recordParse(response, "thread", false, 0);
			throw new InvalidResponseException(e);
		}
	}

	static List<Post> parseThread(JSONObject thread, SoyjakChanLocator locator, String boardName,
			String threadNumber) throws JSONException {
		return SoyjakModelMapper.readPosts(thread.getJSONArray("posts"), locator, boardName, threadNumber);
	}

	private static void recordParse(HttpResponse response, String resource, boolean success, int count) {
		NetworkDiagnostics diagnostics = response.getNetworkDiagnostics();
		if (diagnostics != null) diagnostics.event("parse_result resource=" + resource + " success=" + success + " items=" + count);
		SoyjakDiagnostics.event("parse_result", "resource=" + resource + " success=" + success + " items=" + count);
	}
}
