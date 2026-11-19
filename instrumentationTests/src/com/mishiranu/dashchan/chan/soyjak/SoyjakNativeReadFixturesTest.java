package com.mishiranu.dashchan.chan.soyjak;

import android.net.Uri;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import chan.content.Chan;
import chan.content.ChanPerformer;
import chan.content.model.Board;
import chan.content.model.FileAttachment;
import chan.content.model.Post;
import chan.content.model.Posts;
import com.mishiranu.dashchan.content.model.GalleryItem;
import com.mishiranu.dashchan.content.model.PostItem;
import java.util.List;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

@RunWith(AndroidJUnit4.class)
public class SoyjakNativeReadFixturesTest {
	private static final String BOARDS = """
			<nav><a href="/tech/" title="Navigation label">tech</a><a href="/overboard/">overboard</a>
			<a href="/rules.html">rules</a><a href="/date/">date</a></nav>
			<table><thead><tr><th>Board</th><th>Subtitle</th><th>PPH</th><th>Posters</th><th>Posts</th></tr></thead>
			<tbody>
			<tr><td><a href="/tech/">/tech/ - Soyence and Technology</a></td><td>Technology</td><td>0</td></tr>
			<tr><td><a href="/date/">/date/ - dating board</a></td><td>Dating</td><td>0</td></tr>
			<tr><td><a href="/tech/">/tech/ - Duplicate</a></td><td>Duplicate</td></tr>
			<tr><td><a href="/rules.html">Rules</a></td><td>Service</td></tr>
			<tr><td><a href="https://example.invalid/other/">/other/ - External</a></td><td>External</td></tr>
			</tbody></table>
			""";
	// A minimal public-response shape with synthetic text and IDs.
	private static final String OP = """
			{"no":101,"resto":0,"sub":"Example","com":"Original post","replies":1,"images":0,
			"tim":"1700000000000a","ext":".png","thumb":"1700000000000a.png","filename":"example",
			"fsize":1234,"w":320,"h":200}
			""";
	private static final String REPLY = """
			{"no":102,"resto":101,"com":"Reply"}
			""";

	private static SoyjakChanLocator locator() {
		return (SoyjakChanLocator) Chan.get("soyjak").locator;
	}

	@Test
	public void boardsComeFromTheBoardTable() {
		SoyjakChanLocator locator = locator();
		List<Board> boards = SoyjakChanPerformer.parseBoards(BOARDS, locator.buildPath(), locator);
		assertEquals(2, boards.size());
		assertEquals("tech", boards.get(0).getBoardName());
		assertEquals("Soyence and Technology", boards.get(0).getTitle());
		assertEquals("date", boards.get(1).getBoardName());
		assertEquals("dating board", boards.get(1).getTitle());
		assertEquals(0, SoyjakChanPerformer.parseBoards("<h1>Verification</h1>", locator.buildPath(), locator).size());
	}

	@Test
	public void catalogAndBoardPageUseTheSameOp() throws JSONException {
		SoyjakChanLocator locator = locator();
		List<Posts> catalog = SoyjakChanPerformer.parseCatalog(new JSONArray("[{\"page\":0,\"threads\":[" + OP + "]}]"),
				locator, "tech");
		List<Posts> page = SoyjakChanPerformer.parseBoardPage(
				new JSONObject("{\"threads\":[{\"posts\":[" + OP + "," + REPLY + "]}]}"), locator, "tech");
		assertEquals(1, catalog.size());
		assertEquals(1, page.size());
		assertEquals("101", catalog.get(0).getThreadNumber());
		assertEquals("Example", catalog.get(0).getPosts()[0].getSubject());
		assertEquals(2, page.get(0).getPosts().length);
		assertEquals(catalog.get(0).getThreadNumber(), page.get(0).getThreadNumber());
	}

	@Test
	public void renderedCatalogKeepsSubjectAndOrder() throws JSONException {
		String later = OP.replace("101", "103").replace("Example", "Another subject");
		String document = "[{\"page\":0,\"threads\":[" + OP + "," + later + "]}]";
		List<Posts> threads = SoyjakChanPerformer.parseCatalogDocument("soy", "\n" + document + "\n");
		assertEquals(2, threads.size());
		assertEquals("Example", threads.get(0).getPosts()[0].getSubject());
		assertEquals("Another subject", threads.get(1).getPosts()[0].getSubject());
		assertEquals(0, SoyjakChanPerformer.parseCatalogDocument("soy", "[]").size());
		assertThrows(JSONException.class,
				() -> SoyjakChanPerformer.parseCatalogDocument("soy", "<html>Verification</html>"));
		assertThrows(JSONException.class,
				() -> SoyjakChanPerformer.parseCatalogDocument("soy", document + "<html>Verification</html>"));
	}

	@Test
	public void threadMapsReplyAndAttachmentPaths() throws JSONException {
		SoyjakChanLocator locator = locator();
		List<Post> posts = SoyjakChanPerformer.parseThread(new JSONObject("{\"posts\":[" + OP + "," + REPLY + "]}"),
				locator, "tech", "101");
		assertEquals(2, posts.size());
		assertNull(posts.get(0).getParentPostNumberOrNull());
		assertEquals("102", posts.get(1).getPostNumber());
		assertEquals("101", posts.get(1).getParentPostNumberOrNull());
		assertEquals(1, posts.get(0).getAttachmentsCount());
		FileAttachment file = (FileAttachment) posts.get(0).getAttachmentAt(0);
		assertEquals("/tech/src/1700000000000a.png", file.getFileUri(locator).getPath());
		assertEquals("/tech/thumb/1700000000000a.png", file.getThumbnailUri(locator).getPath());
		assertThrows(JSONException.class,
				() -> SoyjakChanPerformer.parseThread(new JSONObject("{\"posts\":[" + OP + "]}"), locator, "tech", "103"));
	}

	@Test
	public void readerRecognizesHomeBoardAndThread() {
		assertNull(SoyjakReaderPage.from(Uri.parse("https://soyjak.st/")).board);
		assertTrue(SoyjakReaderPage.sameReaderTarget("https://soyjak.st", "https://www.soyjak.st/"));
		assertFalse(SoyjakReaderPage.sameReaderTarget("https://soyjak.st/soy/catalog.html",
				"https://soyjak.st/tech/catalog.html"));
		assertTrue(SoyjakReaderPage.sameReaderTarget("https://soyjak.st/soy/", "https://soyjak.st/soy/index.html"));
		assertFalse(SoyjakReaderPage.sameReaderTarget("https://soyjak.st/soy/", "https://soyjak.st/soy/1.html"));
		assertFalse(SoyjakReaderPage.sameReaderTarget("https://soyjak.st/soy/", "https://soyjak.st/soy/catalog.html"));
		assertEquals("soy", SoyjakReaderPage.from(Uri.parse("https://soyjak.st/soy/catalog.html")).board);
		assertEquals("date", SoyjakReaderPage.from(Uri.parse("https://www.soyjak.st/date/catalog.json")).board);
		assertEquals("42", SoyjakReaderPage.from(Uri.parse("https://soyjak.st/soy/res/42.html#p45")).thread);
		for (String url : new String[]{"http://soyjak.st/soy/catalog.html",
				"https://soyjak.st.example/soy/catalog.html", "https://fixture-user@soyjak.st/soy/catalog.html",
				"https://soyjak.st/soy%2fother/catalog.html", "https://soyjak.st/cdn-cgi/test",
				"https://soyjak.st/soy/catalog.html?redirect=1", "https://soyjak.st/soy/thread/9876543210987654.html"}) {
			assertNull(url, SoyjakReaderPage.from(Uri.parse(url)));
		}
	}

	@Test
	public void renderedDirectoryUsesTheSameBoardTable() throws JSONException {
		Uri home = Uri.parse("https://soyjak.st/");
		SoyjakReaderPage page = SoyjakReaderPage.from(home);
		assertEquals("date", page.readBoards(BOARDS, home, locator()).get(1).getBoardName());
		assertThrows(JSONException.class, () -> page.readBoards("<p>Verify</p>", home, locator()));
	}

	@Test
	public void visibleCatalogUsesCurrentBoardAndKeepsThumbSeparate() throws JSONException {
		Uri url = Uri.parse("https://soyjak.st/soy/catalog.html");
		String html = """
				<div class="thread"><a href="res/41.html"><img src="thumb/41.jpg"></a>
				<span class="subject">Cat</span><div class="replies">R: 4 / I: 2</div><div class="teaser">Sleeping cat</div>
				<a href="thread/41.html">open</a></div>
				<div class="thread"><a href="/date/thread/80.html">Wrong board</a></div>
				<div class="thread"><a href="thread/42.html">Text only</a></div>
				""";
		SoyjakReaderPage page = SoyjakReaderPage.from(url);
		List<Posts> rows = page.read(html, url, locator());
		assertEquals(2, rows.size());
		Post op = rows.get(0).getPosts()[0];
		assertEquals("Cat", op.getSubject());
		assertEquals("Sleeping cat", op.getComment());
		assertEquals(5, rows.get(0).getPostsCount());
		FileAttachment file = (FileAttachment) op.getAttachmentAt(0);
		assertEquals("/soy/thumb/41.jpg", file.getThumbnailUri(locator()).getPath());
		assertNull(file.getFileUri(locator()));
		assertEquals("42", rows.get(1).getThreadNumber());
		assertThrows(JSONException.class, () -> SoyjakReaderPage.from(url).read("<p>Verify</p>", url, locator()));
	}

	@Test
	public void threadReaderKeepsRepliesAndDistinctOriginalFiles() throws JSONException {
		Uri url = Uri.parse("https://soyjak.st/soy/thread/41.html");
		String html = """
				<div class="thread" id="thread_41">
				<div class="files"><a href="/soy/src/41.png"><img src="/soy/thumb/41.jpg"></a></div>
				<div class="post op" id="op_41"><span class="subject">Cat</span><div class="body">OP</div></div>
				<div class="post reply" id="reply_42"><a href="/soy/src/42.webm">Original</a>
				<a href="/soy/src/42.webm"><img src="/soy/thumb/42.jpg"></a><a href="/soy/src/43.png">Second image</a>
				<div class="body">Reply <a href="/soy/src/99.jpg">quoted link</a></div></div></div>
				""";
		List<Posts> rows = SoyjakReaderPage.from(url).read(html, url, locator());
		assertEquals(2, rows.size());
		assertEquals(1, rows.get(0).getPosts()[0].getAttachmentsCount());
		Post reply = rows.get(1).getPosts()[0];
		assertEquals("42", reply.getPostNumber());
		assertEquals("41", reply.getParentPostNumberOrNull());
		assertEquals(2, reply.getAttachmentsCount());
		FileAttachment video = (FileAttachment) reply.getAttachmentAt(0);
		assertEquals("/soy/src/42.webm", video.getFileUri(locator()).getPath());
		assertEquals("/soy/thumb/42.jpg", video.getThumbnailUri(locator()).getPath());
		assertThrows(JSONException.class, () -> SoyjakReaderPage.from(url).read("<p>No posts</p>", url, locator()));
	}

	@Test
	public void catalogEntryBuildsTheStandardThreadCardModel() throws JSONException {
		Chan chan = Chan.get("soyjak");
		JSONArray pages = new JSONArray().put(new JSONObject().put("threads",
				new JSONArray().put(new JSONObject(OP))));
		ChanPerformer.ReadThreadsResult result =
				new ChanPerformer.ReadThreadsResult(SoyjakChanPerformer.parseCatalog(pages, locator(), "soy"));
		assertEquals(1, result.threads.size());
		ChanPerformer.ReadThreadsResult.Thread thread = result.threads.get(0);
		PostItem card = PostItem.createThread(thread.posts, thread.postsCount, thread.filesCount,
				thread.postsWithFilesCount, chan, "soy", thread.threadNumber);
		assertEquals("soy", card.getBoardName());
		assertEquals("101", card.getThreadNumber());
		assertEquals("Example", card.getSubject());
		assertEquals(2, thread.postsCount);
		assertTrue(card.hasAttachments());
		assertEquals("/soy/thumb/1700000000000a.png",
				card.getAttachmentItems().get(0).getThumbnailUri(chan).getPath());
	}

	@Test
	public void standardPostModelsKeepQuoteTargetsAndGalleryOrder() throws JSONException {
		Chan chan = Chan.get("soyjak");
		JSONObject op = new JSONObject(OP).put("extra_files", new JSONArray().put(
				new JSONObject().put("tim", "clip").put("ext", ".mp4").put("thumb", "clip.jpg")));
		JSONObject reply = new JSONObject(REPLY).put("com", "<a href='#p101'>&gt;&gt;101</a>")
				.put("tim", "cat").put("ext", ".jpg").put("thumb", "cat-small.jpg");
		JSONObject document = new JSONObject().put("posts", new JSONArray().put(op).put(reply));
		ChanPerformer.ReadPostsResult rows =
				new ChanPerformer.ReadPostsResult(SoyjakChanPerformer.parseThread(document, locator(), "soy", "101"));
		assertEquals(2, rows.posts.size());
		PostItem original = PostItem.createPost(rows.posts.get(0), chan, "soy", "101", rows.posts.get(0).number);
		PostItem response = PostItem.createPost(rows.posts.get(1), chan, "soy", "101", original.getPostNumber());
		assertTrue(response.getReferencesTo().contains(original.getPostNumber()));

		GalleryItem.Set gallery = new GalleryItem.Set(true);
		gallery.put(original.getPostNumber(), original.getAttachmentItems());
		gallery.put(response.getPostNumber(), response.getAttachmentItems());
		List<GalleryItem> items = gallery.createList();
		assertEquals(3, items.size());
		assertEquals(0, gallery.findIndex(original));
		assertEquals(2, gallery.findIndex(response));
		assertEquals("/soy/src/1700000000000a.png", items.get(0).getFileUri(chan).getPath());
		assertEquals("/soy/src/clip.mp4", items.get(1).getFileUri(chan).getPath());
		assertEquals("/soy/src/cat.jpg", items.get(2).getFileUri(chan).getPath());
		assertEquals(response.getPostNumber(), items.get(2).postNumber);
	}
}
