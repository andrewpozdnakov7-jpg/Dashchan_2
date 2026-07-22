package com.mishiranu.dashchan.ui;

import android.annotation.SuppressLint;
import android.app.AlertDialog;
import android.net.Uri;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.FrameLayout;
import androidx.annotation.NonNull;
import androidx.recyclerview.widget.LinearLayoutManager;
import com.mishiranu.dashchan.R;
import chan.content.Chan;
import chan.util.StringUtils;
import com.mishiranu.dashchan.content.LocalArchiveManager;
import com.mishiranu.dashchan.content.model.Post;
import com.mishiranu.dashchan.content.model.PostItem;
import com.mishiranu.dashchan.content.model.PostNumber;
import com.mishiranu.dashchan.ui.navigator.adapter.PostsAdapter;
import com.mishiranu.dashchan.ui.navigator.manager.UiManager;
import com.mishiranu.dashchan.util.ResourceUtils;
import com.mishiranu.dashchan.util.ConcurrentUtils;
import com.mishiranu.dashchan.util.MimeTypes;
import com.mishiranu.dashchan.util.NavigationUtils;
import com.mishiranu.dashchan.util.ViewUtils;
import com.mishiranu.dashchan.util.WebViewUtils;
import com.mishiranu.dashchan.widget.ClickableToast;
import com.mishiranu.dashchan.widget.DividerItemDecoration;
import com.mishiranu.dashchan.widget.PaddedRecyclerView;
import com.mishiranu.dashchan.widget.ThemeEngine;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.UUID;
import org.json.JSONObject;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;

public class LocalArchiveViewerFragment extends ContentFragment implements PostsAdapter.Callback {
	private static final String EXTRA_ID = "id";
	private static final String EXTRA_VIEW_MODE = "viewMode";
	private static final String EXTRA_ADAPTIVE_VIEW = "adaptiveView";
	private static final String LOCAL_HOST = "local.archive";
	private static final String LOCAL_BASE_URL = "https://" + LOCAL_HOST + "/";
	private static final int MENU_VIEW_MODE = 0x6100;
	private static final int VIEW_ORIGINAL = 0;
	private static final int VIEW_ADAPTIVE = 1;
	private static final int VIEW_NATIVE = 2;

	private WebView webView;
	private PaddedRecyclerView recyclerView;
	private PostsAdapter postsAdapter;
	private LocalArchiveManager.Item archiveItem;
	private String rawHtml;
	private String adaptiveHtml;
	private int viewMode = VIEW_ADAPTIVE;
	private boolean viewModeInitialized;
	private int loadGeneration;
	private String navigationDrawerLocker;

	public LocalArchiveViewerFragment() {}

	public LocalArchiveViewerFragment(String id) {
		Bundle args = new Bundle();
		args.putString(EXTRA_ID, id);
		setArguments(args);
	}

	@Override
	public View onCreateView(@NonNull LayoutInflater inflater, ViewGroup container, Bundle savedInstanceState) {
		FrameLayout root = new FrameLayout(container.getContext());
		webView = new WebView(container.getContext().getApplicationContext());
		recyclerView = new PaddedRecyclerView(container.getContext());
		root.addView(webView, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
				ViewGroup.LayoutParams.MATCH_PARENT));
		root.addView(recyclerView, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
				ViewGroup.LayoutParams.MATCH_PARENT));
		recyclerView.setVisibility(View.GONE);
		return root;
	}

	@SuppressLint("SetJavaScriptEnabled")
	@Override
	public void onViewCreated(@NonNull View view, Bundle savedInstanceState) {
		super.onViewCreated(view, savedInstanceState);
		navigationDrawerLocker = "local-archive-" + UUID.randomUUID();
		((FragmentHandler) requireActivity()).setNavigationAreaLocked(navigationDrawerLocker, true);
		WebSettings settings = webView.getSettings();
		WebViewUtils.configureCommonSettings(settings);
		settings.setBuiltInZoomControls(true);
		settings.setDisplayZoomControls(false);
		settings.setUseWideViewPort(true);
		settings.setLoadWithOverviewMode(true);
		settings.setJavaScriptEnabled(false);
		webView.setWebViewClient(new ArchiveWebViewClient());
		recyclerView.setLayoutManager(new LinearLayoutManager(recyclerView.getContext()));
	}

	@Override
	public void onActivityCreated(Bundle savedInstanceState) {
		super.onActivityCreated(savedInstanceState);
		((FragmentHandler) requireActivity()).setTitleSubtitle(getString(R.string.local_archives), null);
		Integer restoredMode = null;
		if (savedInstanceState != null) {
			if (savedInstanceState.containsKey(EXTRA_VIEW_MODE)) {
				restoredMode = savedInstanceState.getInt(EXTRA_VIEW_MODE);
			} else if (savedInstanceState.containsKey(EXTRA_ADAPTIVE_VIEW)) {
				restoredMode = savedInstanceState.getBoolean(EXTRA_ADAPTIVE_VIEW)
						? VIEW_ADAPTIVE : VIEW_ORIGINAL;
			}
		}
		loadArchive(requireArguments().getString(EXTRA_ID), restoredMode);
	}

	@Override
	public void onSaveInstanceState(@NonNull Bundle outState) {
		super.onSaveInstanceState(outState);
		if (viewModeInitialized) {
			outState.putInt(EXTRA_VIEW_MODE, viewMode);
		}
	}

	@Override
	public void onCreateOptionsMenu(Menu menu, boolean primary) {
		menu.add(0, MENU_VIEW_MODE, 0, R.string.local_archive_view_mode);
		updateViewModeMenu(menu);
	}

	@Override
	public void onPrepareOptionsMenu(Menu menu, boolean primary) {
		updateViewModeMenu(menu);
	}

	@Override
	public boolean onOptionsItemSelected(MenuItem item) {
		if (item.getItemId() == MENU_VIEW_MODE) {
			showViewModeDialog();
			return true;
		}
		return false;
	}

	private void updateViewModeMenu(Menu menu) {
		MenuItem item = menu.findItem(MENU_VIEW_MODE);
		if (item != null) {
			item.setTitle(getString(R.string.local_archive_view_mode) + ": " + getViewModeTitle(viewMode));
			item.setEnabled(viewModeInitialized);
		}
	}

	private CharSequence getViewModeTitle(int mode) {
		return getString(mode == VIEW_ORIGINAL ? R.string.local_archive_view_original
				: mode == VIEW_NATIVE ? R.string.local_archive_view_native : R.string.local_archive_view_adaptive);
	}

	private void showViewModeDialog() {
		if (!viewModeInitialized) {
			return;
		}
		int count = postsAdapter != null ? 3 : 2;
		CharSequence[] items = new CharSequence[count];
		for (int i = 0; i < count; i++) {
			items[i] = getViewModeTitle(i);
		}
		new InstanceDialog(getParentFragmentManager(), "local-archive-view-mode", provider ->
				new AlertDialog.Builder(provider.getContext()).setTitle(R.string.local_archive_view_mode)
						.setSingleChoiceItems(items, Math.min(viewMode, count - 1), (dialog, which) -> {
							applyViewMode(which);
							dialog.dismiss();
						}).setNegativeButton(android.R.string.cancel, null).create());
	}

	private void applyViewMode(int mode) {
		if (mode < VIEW_ORIGINAL || mode > VIEW_NATIVE || (mode == VIEW_NATIVE && postsAdapter == null)) {
			return;
		}
		if (viewMode != mode) {
			viewMode = mode;
			displayArchive();
		}
		invalidateOptionsMenu();
	}

	private void loadArchive(String id, Integer restoredMode) {
		ThemeEngine.Theme theme = ThemeEngine.getTheme(requireContext());
		int generation = ++loadGeneration;
		ConcurrentUtils.PARALLEL_EXECUTOR.execute(() -> {
			LocalArchiveManager.Item found = LocalArchiveManager.findById(id);
			String sourceHtml = null;
			String adaptiveHtml = null;
			int preferredViewMode = VIEW_ADAPTIVE;
			NativeArchive nativeArchive = null;
			if (found != null) {
				try {
					sourceHtml = new String(LocalArchiveManager.readHtml(found), StandardCharsets.UTF_8);
					String htmlText = sourceHtml.toLowerCase(Locale.ROOT);
					JSONObject manifest = LocalArchiveManager.readManifest(found);
					boolean slooopArchive = (manifest != null && LocalArchiveManager.MANIFEST_SCHEMA.equals(
							manifest.optString("schema"))) || htmlText.contains("name=\"slooop-local-archive\"");
					String preferredView = manifest != null ? manifest.optString("view") : null;
					boolean prefersAdaptive = manifest != null && LocalArchiveManager.MANIFEST_SCHEMA.equals(
							manifest.optString("schema")) && manifest.optInt("format", 0) >=
							LocalArchiveManager.MANIFEST_FORMAT && "adaptive".equals(preferredView);
					preferredViewMode = "native".equals(preferredView) ? VIEW_NATIVE
							: prefersAdaptive || slooopArchive ? VIEW_ADAPTIVE : VIEW_ORIGINAL;
					int initialViewMode = restoredMode != null ? restoredMode : preferredViewMode;
					if (initialViewMode == VIEW_ADAPTIVE) {
						adaptiveHtml = makeAdaptiveHtml(sourceHtml, theme);
					}
					nativeArchive = parseNativeArchive(found, sourceHtml, manifest);
					if (preferredViewMode == VIEW_NATIVE && nativeArchive == null) {
						preferredViewMode = VIEW_ADAPTIVE;
					}
				} catch (IOException e) {
					// Handled below.
				}
			}
			LocalArchiveManager.Item finalFound = found;
			String finalSourceHtml = sourceHtml;
			String finalAdaptiveHtml = adaptiveHtml;
			int finalPreferredViewMode = preferredViewMode;
			NativeArchive finalNativeArchive = nativeArchive;
			ConcurrentUtils.HANDLER.post(() -> {
				if (loadGeneration != generation || webView == null || !isAdded()) {
					return;
				}
				if (finalFound != null && finalSourceHtml != null) {
					archiveItem = finalFound;
					rawHtml = finalSourceHtml;
					this.adaptiveHtml = finalAdaptiveHtml;
					viewMode = restoredMode != null ? restoredMode : finalPreferredViewMode;
					if (viewMode == VIEW_NATIVE && finalNativeArchive == null) {
						viewMode = VIEW_ADAPTIVE;
					}
					installNativeArchive(finalNativeArchive);
					viewModeInitialized = true;
					((FragmentHandler) requireActivity()).setTitleSubtitle(finalFound.name,
							getString(R.string.local_archives));
					displayArchive();
					invalidateOptionsMenu();
					if (finalAdaptiveHtml == null) {
						prepareAdaptiveHtml(finalSourceHtml, theme, generation);
					}
				} else {
					ClickableToast.show(R.string.local_archive_open_failed);
				}
			});
		});
	}

	private void prepareAdaptiveHtml(String sourceHtml, ThemeEngine.Theme theme, int generation) {
		ConcurrentUtils.PARALLEL_EXECUTOR.execute(() -> {
			String preparedHtml = makeAdaptiveHtml(sourceHtml, theme);
			ConcurrentUtils.HANDLER.post(() -> {
				if (loadGeneration == generation && webView != null && isAdded()) {
					adaptiveHtml = preparedHtml;
					if (viewMode == VIEW_ADAPTIVE) {
						displayArchive();
					}
				}
			});
		});
	}

	private void displayArchive() {
		if (webView == null || recyclerView == null || rawHtml == null) {
			return;
		}
		boolean nativeView = viewMode == VIEW_NATIVE && postsAdapter != null;
		webView.setVisibility(nativeView ? View.GONE : View.VISIBLE);
		recyclerView.setVisibility(nativeView ? View.VISIBLE : View.GONE);
		if (nativeView) {
			return;
		}
		WebSettings settings = webView.getSettings();
		boolean adaptive = viewMode == VIEW_ADAPTIVE;
		settings.setUseWideViewPort(!adaptive);
		settings.setLoadWithOverviewMode(!adaptive);
		String html = adaptive && adaptiveHtml != null ? adaptiveHtml : rawHtml;
		webView.loadDataWithBaseURL(LOCAL_BASE_URL, html, "text/html", "UTF-8", null);
	}

	private static class NativeArchive {
		public final String chanName;
		public final LinkedHashMap<PostNumber, PostItem> postItems;

		public NativeArchive(String chanName, LinkedHashMap<PostNumber, PostItem> postItems) {
			this.chanName = chanName;
			this.postItems = postItems;
		}
	}

	private static class ArchivePostSource {
		public final String number;
		public final Element header;
		public final ArrayList<Element> files;
		public final Element comment;

		public ArchivePostSource(String number, Element header, ArrayList<Element> files, Element comment) {
			this.number = number;
			this.header = header;
			this.files = files;
			this.comment = comment;
		}
	}

	private static NativeArchive parseNativeArchive(LocalArchiveManager.Item item, String html, JSONObject manifest) {
		try {
			Document document = Jsoup.parse(html, LOCAL_BASE_URL);
			Element postsRoot = document.getElementById("delform");
			ArrayList<ArchivePostSource> sources = collectPostSources(postsRoot);
			if (sources.isEmpty()) {
				return null;
			}
			String chanName = manifest != null ? manifest.optString("chan", null) : null;
			String boardName = manifest != null ? manifest.optString("board", null) : null;
			String threadNumber = manifest != null ? manifest.optString("thread", null) : null;
			Uri threadUri = postsRoot != null ? Uri.parse(postsRoot.attr("data-thread-uri")) : null;
			Chan chan = Chan.getPreferred(chanName, threadUri);
			if (chan.name == null) {
				return null;
			}
			chanName = chan.name;
			if (StringUtils.isEmpty(boardName) && threadUri != null) {
				boardName = chan.locator.safe(false).getBoardName(threadUri);
			}
			if (StringUtils.isEmpty(threadNumber) && threadUri != null) {
				threadNumber = chan.locator.safe(false).getThreadNumber(threadUri);
			}
			if (StringUtils.isEmpty(boardName) || StringUtils.isEmpty(threadNumber)) {
				return null;
			}
			PostNumber originalPostNumber = PostNumber.parseNullable(sources.get(0).number);
			if (originalPostNumber == null) {
				return null;
			}
			LinkedHashMap<PostNumber, PostItem> postItems = new LinkedHashMap<>();
			int ordinalIndex = 0;
			for (ArchivePostSource source : sources) {
				PostNumber number = PostNumber.parseNullable(source.number);
				if (number == null) {
					continue;
				}
				Post.Builder builder = new Post.Builder();
				builder.number = number;
				Element subject = source.header.selectFirst("[data-subject]");
				Element poster = source.header.selectFirst(".postername");
				Element trip = source.header.selectFirst(".postertrip");
				Element timestamp = source.header.selectFirst("[data-timestamp]");
				builder.subject = subject != null ? subject.text() : null;
				if (poster != null) {
					builder.name = poster.attr("data-name");
					builder.identifier = poster.attr("data-identifier");
					builder.email = poster.attr("data-email");
					builder.setDefaultName(poster.hasAttr("data-default-name"));
				}
				if (trip != null) {
					builder.tripcode = trip.attr("data-tripcode");
					builder.capcode = trip.attr("data-capcode");
					builder.setOriginalPoster(trip.hasAttr("data-op"));
				}
				builder.setSage(source.header.selectFirst("[data-sage]") != null);
				builder.timestamp = parseLong(timestamp != null ? timestamp.attr("data-timestamp") : null);
				builder.comment = source.comment.html();
				ArrayList<Post.Attachment> attachments = new ArrayList<>();
				for (Element file : source.files) {
					String filePath = file.attr("data-file");
					String thumbnailPath = file.attr("data-thumbnail");
					if (StringUtils.isEmpty(filePath) && StringUtils.isEmpty(thumbnailPath)) {
						continue;
					}
					Uri fileUri = !StringUtils.isEmpty(filePath) ? createArchiveUri(filePath) : null;
					Uri thumbnailUri = !StringUtils.isEmpty(thumbnailPath)
							? LocalArchiveManager.createResourceUri(item, thumbnailPath) : null;
					Post.Attachment.File attachment = Post.Attachment.File.createExternal(fileUri, thumbnailUri,
							file.attr("data-original-name"), parseInt(file.attr("data-size")),
							parseInt(file.attr("data-width")), parseInt(file.attr("data-height")), false);
					if (attachment != null) {
						attachments.add(attachment);
					}
				}
				builder.attachments = attachments;
				boolean deleted = source.header.selectFirst(".reflink") != null
						&& source.header.selectFirst(".reflink").text().contains("DELETED");
				PostItem postItem = PostItem.createPost(builder.build(deleted), chan,
						boardName, threadNumber, originalPostNumber);
				postItem.setOrdinalIndex(deleted ? PostItem.ORDINAL_INDEX_DELETED : ordinalIndex++);
				postItems.put(number, postItem);
			}
			for (PostItem postItem : postItems.values()) {
				for (PostNumber reference : postItem.getReferencesTo()) {
					PostItem referenced = postItems.get(reference);
					if (referenced != null) {
						referenced.addReferenceFrom(postItem.getPostNumber());
					}
				}
			}
			return postItems.isEmpty() ? null : new NativeArchive(chanName, postItems);
		} catch (RuntimeException e) {
			return null;
		}
	}

	private static ArrayList<ArchivePostSource> collectPostSources(Element postsRoot) {
		ArrayList<ArchivePostSource> result = new ArrayList<>();
		if (postsRoot == null) {
			return result;
		}
		ArrayList<Element> markers = new ArrayList<>();
		for (Element child : postsRoot.children()) {
			if (child.hasAttr("data-number")) {
				markers.add(child);
			}
		}
		for (int i = 0; i < markers.size(); i++) {
			Element marker = markers.get(i);
			Element boundary = i + 1 < markers.size() ? markers.get(i + 1) : null;
			Element next = marker.nextElementSibling();
			Element scope = next != null && "table".equals(next.normalName())
					? next.selectFirst("td.reply") : null;
			Element header = null;
			Element comment = null;
			ArrayList<Element> files = new ArrayList<>();
			if (scope != null) {
				header = scope.selectFirst("div.replyheader");
				comment = scope.selectFirst("[data-comment]");
				files.addAll(scope.select("span[data-file]"));
			} else {
				for (Element sibling = next; sibling != null && sibling != boundary;
						sibling = sibling.nextElementSibling()) {
					if (header == null && "div".equals(sibling.normalName())
							&& sibling.selectFirst("a[name]") != null) {
						header = sibling;
					}
					if (comment == null && sibling.hasAttr("data-comment")) {
						comment = sibling;
					}
					if (sibling.hasAttr("data-file")) {
						files.add(sibling);
					}
					files.addAll(sibling.select("span[data-file]"));
				}
			}
			if (header != null && comment != null) {
				result.add(new ArchivePostSource(marker.attr("data-number"), header, files, comment));
			}
		}
		return result;
	}

	private static Uri createArchiveUri(String path) {
		return Uri.parse(LOCAL_BASE_URL + Uri.encode(path, "/"));
	}

	private static int parseInt(String value) {
		try {
			return Integer.parseInt(value);
		} catch (NumberFormatException e) {
			return 0;
		}
	}

	private static long parseLong(String value) {
		try {
			return Long.parseLong(value);
		} catch (NumberFormatException e) {
			return 0L;
		}
	}

	private void installNativeArchive(NativeArchive archive) {
		if (archive == null || recyclerView == null) {
			return;
		}
		UiManager uiManager = ((MainActivity) requireActivity()).getUiManager();
		uiManager.view().bindThreadsPostRecyclerView(recyclerView);
		postsAdapter = new PostsAdapter(this, archive.chanName, uiManager, (click, data) -> false,
				UiManager.PostStateProvider.DEFAULT, getParentFragmentManager(), recyclerView, archive.postItems);
		recyclerView.setAdapter(postsAdapter);
		int dividerPadding = (int) (12f * ResourceUtils.obtainDensity(requireContext()));
		recyclerView.addItemDecoration(new DividerItemDecoration(requireContext(),
				(configuration, position) -> postsAdapter.configureDivider(configuration, position)
						.horizontal(dividerPadding, dividerPadding)));
		recyclerView.addItemDecoration(postsAdapter.createPostItemDecoration(requireContext(), dividerPadding));
		recyclerView.setFastScrollerEnabled(true);
	}

	@Override
	public void onItemClick(View view, PostItem postItem) {
		if (postsAdapter != null) {
			((MainActivity) requireActivity()).getUiManager().interaction()
					.handlePostClick(view, UiManager.PostStateProvider.DEFAULT, postItem, postsAdapter);
		}
	}

	@Override
	public boolean onItemLongClick(PostItem postItem) {
		if (postsAdapter != null) {
			((MainActivity) requireActivity()).getUiManager().interaction()
					.handlePostContextMenu(postsAdapter.getConfigurationSet(), postItem);
			return true;
		}
		return false;
	}

	private static String makeAdaptiveHtml(String html, ThemeEngine.Theme theme) {
		try {
			Document source = Jsoup.parse(html, LOCAL_BASE_URL);
			ArrayList<ArchivePostSource> posts = collectPostSources(source.getElementById("delform"));
			if (!posts.isEmpty()) {
				Document output = Document.createShell(LOCAL_BASE_URL);
				output.outputSettings().prettyPrint(false);
				output.title(source.title());
				output.head().appendElement("meta").attr("name", "viewport")
						.attr("content", "width=device-width,initial-scale=1,maximum-scale=5");
				output.head().appendElement("style").appendText(makePostFeedStyle(theme));
				Element feed = output.body().appendElement("main").addClass("archive-feed");
				for (int i = 0; i < posts.size(); i++) {
					ArchivePostSource post = posts.get(i);
					appendPost(feed, post.number, post.header, post.files, post.comment, i == 0);
				}
				return output.outerHtml();
			}
		} catch (RuntimeException e) {
			// Third-party and very old archives fall back to the original document with adaptive CSS.
		}
		return makeLegacyAdaptiveHtml(html, theme);
	}

	private static void appendPost(Element feed, String number, Element sourceHeader,
			ArrayList<Element> sourceFiles, Element sourceComment, boolean originalPost) {
		Element article = feed.appendElement("article").addClass("archive-post");
		if (originalPost) {
			article.addClass("original-post");
		}
		Element header = sourceHeader.clone();
		header.removeAttr("class").addClass("post-header");
		header.select("input[type=checkbox]").remove();
		article.appendChild(header);
		if (!sourceFiles.isEmpty()) {
			Element attachments = article.appendElement("section").addClass("attachments");
			for (Element sourceFile : sourceFiles) {
				String file = sourceFile.attr("data-file");
				if (file.isEmpty()) {
					continue;
				}
				Element attachment = attachments.appendElement("div").addClass("attachment");
				String thumbnail = sourceFile.attr("data-thumbnail");
				Element preview = attachment.appendElement("a").addClass("attachment-preview")
						.attr("href", file).attr("target", "_blank");
				if (!thumbnail.isEmpty()) {
					preview.appendElement("img").attr("src", thumbnail).attr("loading", "lazy");
				} else {
					preview.appendElement("span").text("FILE");
				}
				Element info = attachment.appendElement("div").addClass("attachment-info");
				Element sourceInfo = sourceFile.clone();
				sourceInfo.removeAttr("class");
				info.html(sourceInfo.html());
			}
		}
		Element comment = sourceComment.clone();
		comment.removeAttr("class").addClass("post-comment");
		article.appendChild(comment);
		article.attr("id", number);
	}

	private static String makePostFeedStyle(ThemeEngine.Theme theme) {
		return String.format(Locale.US, "*{box-sizing:border-box}html,body{margin:0;padding:0;min-width:0;"
				+ "background:%s;color:%s;font-family:sans-serif;font-size:16px;line-height:1.38;overflow-wrap:anywhere}"
				+ ".archive-feed{width:100%%}.archive-post{padding:12px 14px 14px;border-bottom:1px solid %s;"
				+ "background:%s}.post-header{color:%s;font-size:.9rem;line-height:1.55;margin-bottom:6px}"
				+ ".post-header>a[name],.post-header input{display:none}.replytitle{display:block;color:%s;"
				+ "font-weight:bold;font-size:1rem}.postername{color:%s;font-weight:500}.postertrip{color:%s}"
				+ ".postericon{max-height:1.1em;vertical-align:middle;margin:0 3px}.reflink{white-space:nowrap}"
				+ ".attachments{display:flex;gap:10px;overflow-x:auto;padding:2px 0 8px;scrollbar-width:thin}"
				+ ".attachment{flex:0 0 auto;width:min(46vw,190px);background:%s;border-radius:4px;overflow:hidden}"
				+ ".attachment-preview{display:flex;width:100%%;height:150px;align-items:center;justify-content:center;"
				+ "background:%s;text-decoration:none}.attachment-preview img{display:block;max-width:100%%;max-height:100%%;"
				+ "object-fit:contain}.attachment-preview span{color:%s;font-weight:bold}.attachment-info{padding:6px 8px;"
				+ "color:%s;font-size:.78rem;line-height:1.3}.attachment-info a{color:%s}"
				+ ".post-comment{margin:4px 0 0;padding:0;color:%s;white-space:normal}.post-comment br{line-height:1.5}"
				+ "a{color:%s}.unkfunc{color:%s}.quote{color:%s}span.spoiler{background:%s;color:transparent}"
				+ "span.spoiler:active{color:%s}.underline{text-decoration:underline}.overline{text-decoration:overline}"
				+ ".strike{text-decoration:line-through}.code{font-family:monospace;white-space:pre-wrap}"
				+ ".aa{font-family:monospace;white-space:pre;overflow-x:auto}.heading{font-weight:bold;font-size:1.1rem}",
				color(theme.window), color(theme.post), color(theme.meta), color(theme.window), color(theme.meta),
				color(theme.accent), color(theme.post), color(theme.tripcode), color(theme.card), color(theme.card),
				color(theme.meta), color(theme.meta), color(theme.link), color(theme.post), color(theme.link),
				color(theme.quote), color(theme.quote), color(theme.spoiler), color(theme.post));
	}

	private static String makeLegacyAdaptiveHtml(String html, ThemeEngine.Theme theme) {
		String style = String.format(Locale.US, "<meta name=\"viewport\" "
				+ "content=\"width=device-width,initial-scale=1,maximum-scale=5\" />\n"
				+ "<style id=\"slooop-adaptive-archive\">\n"
				+ "html,body{margin:0!important;padding:0!important;min-width:0!important;"
				+ "background:%s!important;color:%s!important;font-family:sans-serif!important;"
				+ "font-size:16px!important;line-height:1.35!important;overflow-wrap:anywhere;}\n"
				+ "body{padding:8px 12px!important;} .logo,body>hr,.footer,.doubledash{display:none!important;}\n"
				+ "#delform{width:100%%!important;} table,tbody,tr,td.reply{display:block!important;"
				+ "box-sizing:border-box!important;width:100%%!important;min-width:0!important;}\n"
				+ "table{margin:0!important;border-top:1px solid %s!important;} td.reply{margin:0!important;"
				+ "padding:10px 0!important;background:transparent!important;color:%s!important;}\n"
				+ ".withimage{min-width:0!important;} blockquote{margin:8px 0 12px!important;"
				+ "padding:0!important;line-height:1.45!important;white-space:normal!important;}\n"
				+ ".filesize{display:block!important;padding:4px 0!important;color:%s!important;}"
				+ ".thumb,.nothumb{float:none!important;margin:4px 10px 8px 0!important;max-width:46vw!important;"
				+ "max-height:240px!important;width:auto!important;height:auto!important;}\n"
				+ "a{color:%s!important;} .unkfunc{color:%s!important;} .postername,.replyheader{color:%s!important;}"
				+ "span.spoiler{background:%s!important;color:transparent!important;}"
				+ "span.spoiler:hover{color:%s!important;} input[type=checkbox]{display:none!important;}\n"
				+ "</style>\n", color(theme.window), color(theme.post), color(theme.meta), color(theme.post),
				color(theme.meta), color(theme.link), color(theme.quote), color(theme.meta), color(theme.spoiler),
				color(theme.post));
		String lower = html.toLowerCase(Locale.ROOT);
		int index = lower.lastIndexOf("</head>");
		return index >= 0 ? html.substring(0, index) + style + html.substring(index) : style + html;
	}

	private static String color(int color) {
		return String.format(Locale.US, "#%06X", color & 0x00ffffff);
	}

	@Override
	public void onDestroyView() {
		loadGeneration++;
		((FragmentHandler) requireActivity()).setNavigationAreaLocked(navigationDrawerLocker, false);
		if (postsAdapter != null) {
			postsAdapter.cancelPreloading();
		}
		if (recyclerView != null) {
			recyclerView.setAdapter(null);
		}
		if (webView != null) {
			webView.stopLoading();
			webView.destroy();
			ViewUtils.removeFromParent(webView);
		}
		webView = null;
		recyclerView = null;
		postsAdapter = null;
		archiveItem = null;
		rawHtml = null;
		adaptiveHtml = null;
		viewModeInitialized = false;
		super.onDestroyView();
	}

	@Override
	public void onPause() {
		super.onPause();
		if (webView != null) {
			webView.onPause();
		}
	}

	@Override
	public void onResume() {
		super.onResume();
		if (webView != null) {
			webView.onResume();
		}
	}

	@Override
	public boolean onBackPressed() {
		if (viewMode != VIEW_NATIVE && webView != null && webView.canGoBack()) {
			webView.goBack();
			return true;
		}
		return false;
	}

	@Override
	public boolean canHandleBack() {
		return viewMode != VIEW_NATIVE && webView != null && webView.canGoBack();
	}

	private class ArchiveWebViewClient extends WebViewClient {
		@Override
		public WebResourceResponse shouldInterceptRequest(WebView view, WebResourceRequest request) {
			Uri uri = request.getUrl();
			if (!LOCAL_HOST.equals(uri.getHost()) || archiveItem == null) {
				return null;
			}
			String path = uri.getPath();
			if (path == null || path.length() <= 1) {
				return null;
			}
			path = path.substring(1);
			try {
				InputStream input = LocalArchiveManager.openResource(archiveItem, path);
				if (input != null) {
					String extension = chan.util.StringUtils.getFileExtension(path);
					String mimeType = MimeTypes.forExtension(extension, "application/octet-stream");
					return new WebResourceResponse(mimeType, null, input);
				}
			} catch (IOException e) {
				// Return an empty response below.
			}
			return new WebResourceResponse("text/plain", "UTF-8", new ByteArrayInputStream(new byte[0]));
		}

		@Override
		public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
			Uri uri = request.getUrl();
			if (LOCAL_HOST.equals(uri.getHost())) {
				return false;
			}
			NavigationUtils.handleUri(requireContext(), null, uri, NavigationUtils.BrowserType.EXTERNAL);
			return true;
		}
	}
}
