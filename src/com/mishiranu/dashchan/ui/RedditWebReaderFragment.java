package com.mishiranu.dashchan.ui;

import com.mishiranu.dashchan.widget.MotionDialogBuilder;
import android.annotation.SuppressLint;
import android.app.AlertDialog;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.content.pm.PackageInfo;
import android.graphics.Bitmap;
import android.net.Uri;
import android.net.http.SslError;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.InputType;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.webkit.ConsoleMessage;
import android.webkit.CookieManager;
import android.webkit.RenderProcessGoneDetail;
import android.webkit.SslErrorHandler;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceError;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebStorage;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.EditText;
import android.widget.FrameLayout;
import androidx.annotation.NonNull;
import chan.util.StringUtils;
import com.mishiranu.dashchan.R;
import com.mishiranu.dashchan.content.Preferences;
import com.mishiranu.dashchan.content.storage.RedditPageStorage;
import com.mishiranu.dashchan.content.translation.TranslationController;
import com.mishiranu.dashchan.content.translation.TranslationDiagnostics;
import com.mishiranu.dashchan.content.translation.TranslationModel;
import com.mishiranu.dashchan.util.NavigationUtils;
import com.mishiranu.dashchan.util.ViewUtils;
import com.mishiranu.dashchan.util.WebViewUtils;
import com.mishiranu.dashchan.widget.ClickableToast;
import com.mishiranu.dashchan.widget.ExpandedLayout;
import com.mishiranu.dashchan.ui.reddit.RedditReaderScripts;
import com.mishiranu.dashchan.widget.ThemeEngine;
import com.mishiranu.dashchan.widget.ThreadQuickNavigation;
import java.util.Locale;
import java.util.UUID;
import org.json.JSONException;
import org.json.JSONObject;
import org.json.JSONTokener;

/**
 * A visible, user-driven browser for Reddit pages.
 *
 * <p>This fragment deliberately does not expose a JavaScript bridge, export page content to native code, call
 * Reddit APIs or internal endpoints, click controls, or preload discussions. It stores only canonical URLs,
 * titles and timestamps for subreddit and discussion pages so they can be reopened from Slooop's drawer. The
 * dedicated sign-in screen displays the unmodified Reddit page and keeps its session in WebView's cookie store;
 * native code receives only Reddit's boolean signed-in marker so the settings screen can report session state.
 * Outside that screen, local presentation scripts apply the reader theme and suppress Reddit's blocking
 * app-install prompt. Reddit's own scripts may load content after a user action in the same way as they do in a
 * regular browser. When the user explicitly enables translation, only the visible post title/body and comment
 * markup is passed to the translator selected in Slooop's translation settings.</p>
 */
public class RedditWebReaderFragment extends ContentFragment {
	private ThreadQuickNavigation quickNavigation;
	private static final String LOG_TAG = "SlooopRedditWeb";
	private static final String JS_LOG_PREFIX = "SLOOOP_REDDIT ";
	// Diagnostic test builds only. Disable before committing or publishing a release build.
	private static final boolean ENABLE_WEBVIEW_DIAGNOSTICS = false;

	private static void logDiagnostic(String message) {
		if (ENABLE_WEBVIEW_DIAGNOSTICS) {
			Log.d(LOG_TAG, message);
		}
	}

	private static void logDiagnosticError(String message) {
		if (ENABLE_WEBVIEW_DIAGNOSTICS) {
			Log.e(LOG_TAG, message);
		}
	}

	public static final String HOME_URL = "https://www.reddit.com/";
	public static final String LOGIN_URL = "https://www.reddit.com/login/";
	public static final String POPULAR_URL = "https://www.reddit.com/r/popular/";
	public static final String ALL_URL = "https://www.reddit.com/r/all/";
	public static final String ASK_REDDIT_URL = "https://www.reddit.com/r/AskReddit/";
	public static final String WORLD_NEWS_URL = "https://www.reddit.com/r/worldnews/";
	public static final String TECHNOLOGY_URL = "https://www.reddit.com/r/technology/";
	public static final String ANDROID_URL = "https://www.reddit.com/r/Android/";
	public static final String GAMING_URL = "https://www.reddit.com/r/gaming/";
	public static final String MILDLY_INFURIATING_URL = "https://www.reddit.com/r/mildlyinfuriating/";
	public static final String TODAY_I_LEARNED_URL = "https://www.reddit.com/r/todayilearned/";
	public static final String SCIENCE_URL = "https://www.reddit.com/r/science/";
	public static final String MOVIES_URL = "https://www.reddit.com/r/movies/";
	private static final String[] REDDIT_COOKIE_URLS = {
			"https://www.reddit.com/", "https://reddit.com/", "https://old.reddit.com/", "https://new.reddit.com/"
	};
	private static final String EXTRA_START_URL = "startUrl";
	private static final String EXTRA_AUTHORIZATION_MODE = "authorizationMode";
	private static final String STATE_CLEAR_HISTORY_URL = "clearHistoryUrl";
	private static final String STATE_TRANSLATION_ENABLED = "translationEnabled";
	private static final long TRANSLATION_RESCAN_DELAY = 2500L;
	private static final String READ_SIGNED_IN_STATE_SCRIPT = "(function(){var app=" +
			"document.querySelector('shreddit-app');if(!app)return null;return " +
			"app.getAttribute('user-logged-in')==='true'||!!document.querySelector('[is-user-logged-in]');})()";

	private WebView webView;
	private View progressView;
	private String navigationDrawerLocker;
	private String boardStyleScript;
	private String readerStyleScript;
	private String hybridReaderScript;
	private boolean authorizationMode;
	private MenuItem finishAuthorizationMenuItem;
	private int pageLoadGeneration;
	private String lastRecordedPageUrl;
	private String clearHistoryUrl;
	private boolean translationEnabled;
	private boolean translationRequestPending;
	private int translationRequestGeneration = -1;
	private int translationFailureGeneration = -1;
	private final Handler handler = new Handler(Looper.getMainLooper());
	private final Runnable translationRescanRunnable = this::requestRedditTranslation;

	public static RedditWebReaderFragment newInstance(String url) {
		RedditWebReaderFragment fragment = new RedditWebReaderFragment();
		Bundle arguments = new Bundle();
		arguments.putString(EXTRA_START_URL, url);
		fragment.setArguments(arguments);
		return fragment;
	}

	public static RedditWebReaderFragment newAuthorizationInstance() {
		RedditWebReaderFragment fragment = new RedditWebReaderFragment();
		Bundle arguments = new Bundle();
		arguments.putString(EXTRA_START_URL, LOGIN_URL);
		arguments.putBoolean(EXTRA_AUTHORIZATION_MODE, true);
		fragment.setArguments(arguments);
		return fragment;
	}

	public boolean isAuthorizationMode() {
		Bundle arguments = getArguments();
		return arguments != null && arguments.getBoolean(EXTRA_AUTHORIZATION_MODE);
	}

	public static void clearRedditSession() {
		CookieManager cookieManager = CookieManager.getInstance();
		for (String url : REDDIT_COOKIE_URLS) {
			String cookies = cookieManager.getCookie(url);
			if (StringUtils.isEmpty(cookies)) {
				continue;
			}
			for (String cookie : cookies.split(";")) {
				int index = cookie.indexOf('=');
				String name = (index >= 0 ? cookie.substring(0, index) : cookie).trim();
				if (!name.matches("[!#$%&'*+.^_`|~0-9A-Za-z-]+")) {
					continue;
				}
				String expired = name + "=; Path=/; Max-Age=0; " +
						"Expires=Thu, 01 Jan 1970 00:00:00 GMT; Secure";
				cookieManager.setCookie(url, expired);
				cookieManager.setCookie(url, expired + "; Domain=.reddit.com");
			}
		}
		cookieManager.flush();
		WebStorage webStorage = WebStorage.getInstance();
		webStorage.deleteOrigin("https://www.reddit.com");
		webStorage.deleteOrigin("https://reddit.com");
		webStorage.deleteOrigin("https://old.reddit.com");
		webStorage.deleteOrigin("https://new.reddit.com");
		Preferences.setRedditSignedIn(false);
	}

	@Override
	public View onCreateView(@NonNull LayoutInflater inflater, ViewGroup container, Bundle savedInstanceState) {
		ExpandedLayout layout = new ExpandedLayout(container.getContext(), true);
		if (ENABLE_WEBVIEW_DIAGNOSTICS) {
			WebView.setWebContentsDebuggingEnabled(true);
			logDiagnostic("event=devtools_enabled");
		}
		// Android Autofill needs the WebView to retain its Activity context while a password provider briefly
		// opens an authentication activity and returns the selected dataset. An application-context WebView can
		// expose the HTML fields to Autofill but fail to receive their values after that round trip.
		webView = new WebView(layout.getContext());
		webView.setImportantForAutofill(View.IMPORTANT_FOR_AUTOFILL_YES);
		webView.setFocusable(true);
		webView.setFocusableInTouchMode(true);
		webView.setOnTouchListener((view, event) -> {
			if (!view.hasFocus()) {
				view.requestFocus();
			}
			return false;
		});
		webView.setVisibility(View.INVISIBLE);
		layout.addView(webView, FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT);
		progressView = new View(layout.getContext());
		progressView.setBackgroundColor(0xff808080);
		layout.addView(progressView, FrameLayout.LayoutParams.MATCH_PARENT,
				Math.max(1, (int) (3f * getResources().getDisplayMetrics().density + 0.5f)));
		quickNavigation = new ThreadQuickNavigation(webView,
				() -> !isAuthorizationMode() && webView != null && webView.isShown()
						&& webView.getContentHeight() > 0, bottom -> {
			if (webView == null) return;
			// Snapshot the loaded document height once: do not chase an infinite feed.
			webView.evaluateJavascript("(function(){var e=document.scrollingElement||document.documentElement;"
					+ "var y=" + (bottom ? "Math.max(0,e.scrollHeight-window.innerHeight)" : "0") + ";"
					+ "window.scrollTo({top:y,behavior:window.matchMedia('(prefers-reduced-motion: reduce)').matches"
					+ "?'auto':'smooth'});})()", null);
		});
		layout.addView(quickNavigation, FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT);
		return layout;
	}

	@SuppressLint("SetJavaScriptEnabled")
	@Override
	public void onViewCreated(@NonNull View view, Bundle savedInstanceState) {
		super.onViewCreated(view, savedInstanceState);

		navigationDrawerLocker = "reddit-web-reader-" + UUID.randomUUID();
		((FragmentHandler) requireActivity()).setNavigationAreaLocked(navigationDrawerLocker, true);
		authorizationMode = isAuthorizationMode();
		((FragmentHandler) requireActivity()).setCompactToolbarTitle(!authorizationMode);
		boardStyleScript = !authorizationMode && Preferences.isRedditBoardStyleEnabled()
				? buildBoardStyleScript() : null;
		readerStyleScript = !authorizationMode && Preferences.isRedditWebReaderStyleEnabled()
				? buildReaderStyleScript() : null;
		hybridReaderScript = readerStyleScript != null ? buildHybridReaderScript() : null;

		WebSettings settings = webView.getSettings();
		WebViewUtils.configureCommonSettings(settings);
		settings.setJavaScriptEnabled(true);
		settings.setDomStorageEnabled(true);
		settings.setSupportZoom(false);
		settings.setBuiltInZoomControls(false);
		settings.setDisplayZoomControls(false);
		settings.setSupportMultipleWindows(false);
		settings.setJavaScriptCanOpenWindowsAutomatically(false);
		settings.setMediaPlaybackRequiresUserGesture(true);
		settings.setMixedContentMode(WebSettings.MIXED_CONTENT_NEVER_ALLOW);
		CookieManager cookieManager = CookieManager.getInstance();
		cookieManager.setAcceptCookie(true);
		cookieManager.setAcceptThirdPartyCookies(webView, false);
		PackageInfo webViewPackage = WebView.getCurrentWebViewPackage();
		logDiagnostic("event=webview_created provider=" + (webViewPackage != null
				? webViewPackage.packageName + "/" + webViewPackage.versionName : "unknown")
				+ " auth=" + authorizationMode + " board=" + (boardStyleScript != null)
				+ " reader=" + (readerStyleScript != null));
		webView.setWebViewClient(new RedditWebViewClient());
		webView.setWebChromeClient(new WebChromeClient() {
			@Override
			public void onProgressChanged(WebView view, int newProgress) {
				if (progressView != null) {
					progressView.setScaleX(newProgress / 100f);
					progressView.setPivotX(0f);
					progressView.setVisibility(newProgress < 100 ? View.VISIBLE : View.INVISIBLE);
				}
			}

			@Override
			public void onReceivedTitle(WebView view, String title) {
				recordVisitedPage(view, view.getUrl(), true);
				updateTitle();
			}

			@Override
			public boolean onConsoleMessage(ConsoleMessage consoleMessage) {
				String message = consoleMessage.message();
				if (ENABLE_WEBVIEW_DIAGNOSTICS && message != null && message.startsWith(JS_LOG_PREFIX)) {
					message = message.substring(JS_LOG_PREFIX.length());
					logDiagnostic(message.length() <= 512 ? message : message.substring(0, 512));
					return true;
				}
				return super.onConsoleMessage(consoleMessage);
			}
		});
		if (savedInstanceState != null) {
			webView.restoreState(savedInstanceState);
			clearHistoryUrl = savedInstanceState.getString(STATE_CLEAR_HISTORY_URL);
			translationEnabled = savedInstanceState.getBoolean(STATE_TRANSLATION_ENABLED);
		}

		Bundle arguments = getArguments();
		String startUrl = arguments != null ? arguments.getString(EXTRA_START_URL) : null;
		if (StringUtils.isEmptyOrWhitespace(startUrl) || !isAllowedRedditPage(Uri.parse(startUrl))) {
			startUrl = HOME_URL;
		}
		updateTitle();
		if (savedInstanceState == null) {
			webView.loadUrl(startUrl);
		}
	}

	@Override
	public void onResume() {
		super.onResume();
		webView.onResume();
		if (quickNavigation != null) quickNavigation.refreshPreferences();
		scheduleTranslationScan(0L);
	}

	@Override
	public void onPause() {
		handler.removeCallbacks(translationRescanRunnable);
		CookieManager.getInstance().flush();
		webView.onPause();
		super.onPause();
	}

	@Override
	public void onSaveInstanceState(@NonNull Bundle outState) {
		super.onSaveInstanceState(outState);
		if (webView != null) {
			webView.saveState(outState);
		}
		outState.putString(STATE_CLEAR_HISTORY_URL, clearHistoryUrl);
		outState.putBoolean(STATE_TRANSLATION_ENABLED, translationEnabled);
	}

	@Override
	public void onDestroyView() {
		quickNavigation = null;
		handler.removeCallbacks(translationRescanRunnable);
		pageLoadGeneration++;
		translationRequestPending = false;
		translationRequestGeneration = -1;
		((FragmentHandler) requireActivity()).setNavigationAreaLocked(navigationDrawerLocker, false);
		((FragmentHandler) requireActivity()).setCompactToolbarTitle(false);
		if (webView != null) {
			webView.stopLoading();
			webView.setOnTouchListener(null);
			webView.setWebChromeClient(null);
			webView.setWebViewClient(null);
			ViewUtils.removeFromParent(webView);
			webView.destroy();
			webView = null;
		}
		progressView = null;
		boardStyleScript = null;
		readerStyleScript = null;
		hybridReaderScript = null;
		finishAuthorizationMenuItem = null;
		super.onDestroyView();
	}

	@Override
	public void onCreateOptionsMenu(Menu menu, boolean primary) {
		menu.add(0, R.id.menu_translate, 0, R.string.translate_posts)
				.setIcon(((FragmentHandler) requireActivity()).getActionBarIcon(R.attr.iconActionTranslate))
				.setShowAsAction(MenuItem.SHOW_AS_ACTION_ALWAYS);
		menu.add(0, R.id.menu_reload, 1, R.string.reload)
				.setIcon(((FragmentHandler) requireActivity()).getActionBarIcon(R.attr.iconActionRefresh))
				.setShowAsAction(MenuItem.SHOW_AS_ACTION_ALWAYS);
		if (authorizationMode) {
			finishAuthorizationMenuItem = menu.add(R.string.reddit_sign_in_done);
			finishAuthorizationMenuItem.setShowAsAction(MenuItem.SHOW_AS_ACTION_ALWAYS);
		}
		menu.add(0, R.id.menu_open_reddit_link, 0, R.string.open_reddit_link);
		menu.add(0, R.id.menu_copy_link, 0, R.string.copy_link);
		menu.add(0, R.id.menu_share_link, 0, R.string.share_link);
	}

	@Override
	public void onPrepareOptionsMenu(Menu menu, boolean primary) {
		MenuItem translate = menu.findItem(R.id.menu_translate);
		boolean available = !authorizationMode && TranslationController.isEnabledForDirection(
				TranslationModel.Direction.EN_RU);
		translate.setVisible(available);
		if (available) {
			translate.setTitle(translationEnabled ? R.string.show_original_posts : R.string.translate_posts);
		} else if (translationEnabled) {
			setTranslationEnabled(false);
		}
	}

	@Override
	public boolean onMenuItemSelected(MenuItem item) {
		if (webView == null) {
			return false;
		}
		if (item == finishAuthorizationMenuItem) {
			updateSignedInState(webView, () -> {
				CookieManager.getInstance().flush();
				if (isAdded()) {
					((FragmentHandler) requireActivity()).removeFragment();
				}
			});
			return true;
		}
		if (item.getItemId() == R.id.menu_open_reddit_link) {
			showOpenLinkDialog();
			return true;
		} else if (item.getItemId() == R.id.menu_reload) {
			webView.reload();
			return true;
		} else if (item.getItemId() == R.id.menu_translate) {
			if (!translationEnabled && !TranslationController.isReadyForDirection(
					TranslationModel.Direction.EN_RU)) {
				ClickableToast.show(R.string.translation_package_unavailable);
				return true;
			}
			setTranslationEnabled(!translationEnabled);
			if (translationEnabled) {
				ClickableToast.show(R.string.translation_initializing);
			}
			invalidateOptionsMenu();
			return true;
		} else if (item.getItemId() == R.id.menu_copy_link) {
			StringUtils.copyToClipboard(requireContext(), webView.getUrl());
			return true;
		} else if (item.getItemId() == R.id.menu_share_link) {
			String url = webView.getUrl();
			if (!StringUtils.isEmpty(url)) {
				NavigationUtils.shareLink(requireContext(), null, Uri.parse(url));
			}
			return true;
		}
		return false;
	}

	private void showOpenLinkDialog() {
		EditText editText = new EditText(requireContext());
		editText.setSingleLine(true);
		editText.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
		String currentUrl = webView != null ? webView.getUrl() : null;
		if (!StringUtils.isEmpty(currentUrl)) {
			editText.setText(currentUrl);
			editText.selectAll();
		}
		new MotionDialogBuilder(requireContext())
				.setTitle(R.string.open_reddit_link)
				.setView(editText)
				.setNegativeButton(android.R.string.cancel, null)
				.setPositiveButton(android.R.string.ok, (dialog, which) -> {
					String value = editText.getText().toString().trim();
					if (!value.contains("://")) {
						value = "https://" + value;
					}
					Uri uri = Uri.parse(value);
					if (isAllowedRedditPage(uri) && webView != null) {
						webView.loadUrl(uri.toString());
					} else {
						ClickableToast.show(R.string.unknown_address);
					}
				})
				.show();
	}

	@Override
	public boolean onHomePressed() {
		return navigateBack();
	}

	@Override
	public boolean onBackPressed() {
		return navigateBack();
	}

	private boolean navigateBack() {
		if (webView == null) {
			return false;
		}
		if (webView.canGoBack()) {
			webView.goBack();
			return true;
		}
		String parentUrl = getThreadParentUrl(webView.getUrl());
		if (parentUrl != null) {
			// Reddit may replace its browser history while navigating from a subreddit feed to a discussion.
			// Restore the logical parent explicitly, then clear the synthetic thread -> parent history entry so
			// the following Back returns to Slooop's Reddit sections instead of reopening the discussion.
			clearHistoryUrl = parentUrl;
			webView.loadUrl(parentUrl);
			return true;
		}
		return false;
	}

	@Override
	public boolean canHandleBack() {
		return webView != null && (webView.canGoBack() || getThreadParentUrl(webView.getUrl()) != null);
	}

	@Override
	public boolean isPrimaryNavigationContent() {
		return !authorizationMode;
	}

	public String getCurrentPageUrl() {
		if (webView != null) {
			return webView.getUrl();
		}
		Bundle arguments = getArguments();
		return arguments != null ? arguments.getString(EXTRA_START_URL) : null;
	}

	public boolean openStoredPage(String url) {
		String normalized = RedditPageStorage.normalizeUrl(url);
		if (authorizationMode || webView == null || normalized == null) {
			return false;
		}
		String current = RedditPageStorage.normalizeUrl(webView.getUrl());
		if (!normalized.equals(current)) {
			webView.loadUrl(normalized);
		}
		return true;
	}

	private boolean openExternal(Uri uri) {
		if (NavigationUtils.handleYouTubeUri(requireContext(), uri)) {
			return true;
		}
		ClickableToast.show(R.string.reddit_public_web_reader_external_link);
		Intent intent = new Intent(Intent.ACTION_VIEW, uri).addCategory(Intent.CATEGORY_BROWSABLE);
		try {
			requireContext().startActivity(intent);
		} catch (ActivityNotFoundException | SecurityException e) {
			ClickableToast.show(R.string.unknown_address);
		}
		return true;
	}

	private static boolean isAllowedRedditPage(Uri uri) {
		if (!"https".equalsIgnoreCase(uri.getScheme())) {
			return false;
		}
		String host = uri.getHost();
		if (host == null) {
			return false;
		}
		host = host.toLowerCase(Locale.US);
		return host.equals("reddit.com") || host.endsWith(".reddit.com");
	}

	private static String getLogHost(String url) {
		if (url == null) {
			return "none";
		}
		try {
			String host = Uri.parse(url).getHost();
			return host != null ? host.toLowerCase(Locale.US) : "none";
		} catch (RuntimeException e) {
			return "invalid";
		}
	}

	private static String getThreadParentUrl(String url) {
		RedditPageStorage.Entry entry = RedditPageStorage.parse(url, null);
		return entry != null && entry.type == RedditPageStorage.Type.THREAD
				? "https://www.reddit.com/r/" + entry.subreddit + "/" : null;
	}

	private String buildAppPromoSuppressionScript() {
		return RedditReaderScripts.appPromo(requireContext().getAssets());
	}

	private String buildBoardStyleScript() {
		return RedditReaderScripts.board(requireContext().getAssets(), ThemeEngine.getTheme(requireContext()));
	}

	private String buildReaderStyleScript() {
		return RedditReaderScripts.reader(requireContext().getAssets(), ThemeEngine.getTheme(requireContext()));
	}

	private String buildHybridReaderScript() {
		boolean russian = "ru".equals(requireContext().getResources().getConfiguration()
				.getLocales().get(0).getLanguage());
		return RedditReaderScripts.hybrid(requireContext().getAssets(), ThemeEngine.getTheme(requireContext()),
				russian, JS_LOG_PREFIX);
	}

	private void setTranslationEnabled(boolean enabled) {
		translationEnabled = enabled;
		handler.removeCallbacks(translationRescanRunnable);
		TranslationDiagnostics.log("reddit", "toggle", "enabled", enabled,
				"generation", pageLoadGeneration, "has_view", webView != null);
		if (webView == null) {
			return;
		}
		String property = enabled ? "e.__slooopTranslatedHtml" : "e.__slooopOriginalHtml";
		String script = "(function(){window.__slooopRedditTranslationEnabled=" + enabled + ";" +
				"var n=document.querySelectorAll('[data-slooop-translation-id]');" +
				"for(var i=0;i<n.length;i++){var e=n[i],h=" + property + ";" +
				(enabled ? "e.__slooopTranslationFailed=false;" : "") +
				"if(h!=null)e.innerHTML=h;}})();";
		webView.evaluateJavascript(script, ignored -> {
			if (translationEnabled) {
				scheduleTranslationScan(0L);
			}
		});
	}

	private void scheduleTranslationScan(long delay) {
		handler.removeCallbacks(translationRescanRunnable);
		if (translationEnabled && !authorizationMode && webView != null && isResumed()) {
			TranslationDiagnostics.log("reddit", "scan_scheduled", "delay_ms", delay,
					"generation", pageLoadGeneration, "pending", translationRequestPending);
			handler.postDelayed(translationRescanRunnable, delay);
		}
	}

	private void requestRedditTranslation() {
		WebView view = webView;
		if (!translationEnabled) {
			TranslationDiagnostics.log("reddit", "scan_skipped", "reason", "disabled");
			return;
		}
		if (translationRequestPending) {
			TranslationDiagnostics.log("reddit", "scan_skipped", "reason", "pending",
					"generation", translationRequestGeneration);
			return;
		}
		if (view == null) {
			TranslationDiagnostics.log("reddit", "scan_skipped", "reason", "missing_view");
			return;
		}
		if (!isResumed()) {
			TranslationDiagnostics.log("reddit", "scan_skipped", "reason", "not_resumed");
			return;
		}
		if (!TranslationController.isReadyForDirection(TranslationModel.Direction.EN_RU)) {
			TranslationDiagnostics.log("reddit", "scan_skipped", "reason", "engine_not_ready");
			return;
		}
		int generation = pageLoadGeneration;
		TranslationDiagnostics.log("reddit", "dom_scan_start", "generation", generation);
		String script = "(function(){window.__slooopRedditTranslationEnabled=true;" +
				"var q='shreddit-post [slot=title],shreddit-post [slot=text-body]," +
				"shreddit-comment [slot=comment]',n=document.querySelectorAll(q);" +
				"var r={total:n.length,cached:0,pending:0,failed:0,empty:0};" +
				"window.__slooopRedditTranslationCounter=window.__slooopRedditTranslationCounter||0;" +
				"for(var i=0;i<n.length;i++){var e=n[i];if(e.__slooopTranslatedHtml!=null){" +
				"r.cached++;e.innerHTML=e.__slooopTranslatedHtml;continue;}if(e.__slooopTranslationPending){" +
				"r.pending++;continue;}if(e.__slooopTranslationFailed){r.failed++;continue;}" +
				"var text=(e.innerText||e.textContent||'').trim();if(!text){r.empty++;continue;}" +
				"if(e.__slooopOriginalHtml==null)e.__slooopOriginalHtml=e.innerHTML;" +
				"var id=e.getAttribute('data-slooop-translation-id');if(!id){id='t'+" +
				"(++window.__slooopRedditTranslationCounter);e.setAttribute('data-slooop-translation-id',id);}" +
				"e.__slooopTranslationPending=true;r.id=id;r.html=e.__slooopOriginalHtml;" +
				"return JSON.stringify(r);}return JSON.stringify(r);})()";
		translationRequestPending = true;
		translationRequestGeneration = generation;
		view.evaluateJavascript(script, value -> {
			if (!isCurrentPageLoad(view, generation) || !translationEnabled) {
				TranslationDiagnostics.log("reddit", "dom_scan_stale", "generation", generation,
						"current_generation", pageLoadGeneration, "enabled", translationEnabled);
				finishTranslationRequest(generation);
				return;
			}
			JSONObject item = decodeJavascriptObject(value);
			if (item == null) {
				TranslationDiagnostics.error("reddit", "dom_scan_invalid", "generation", generation,
						"result_chars", TranslationDiagnostics.length(value));
				finishTranslationRequest(generation);
				scheduleTranslationScan(TRANSLATION_RESCAN_DELAY);
				return;
			}
			TranslationDiagnostics.log("reddit", "dom_scan_result", "generation", generation,
					"total", item.optInt("total", -1), "cached", item.optInt("cached", -1),
					"pending", item.optInt("pending", -1), "failed", item.optInt("failed", -1),
					"empty", item.optInt("empty", -1));
			String id = item.optString("id", null);
			String html = item.optString("html", null);
			if (StringUtils.isEmpty(id)) {
				TranslationDiagnostics.log("reddit", "dom_queue_empty", "generation", generation);
				finishTranslationRequest(generation);
				scheduleTranslationScan(TRANSLATION_RESCAN_DELAY);
				return;
			}
			if (html == null) {
				TranslationDiagnostics.error("reddit", "dom_item_invalid", "generation", generation,
						"has_id", true, "html_chars", TranslationDiagnostics.length(html));
				finishTranslationRequest(generation);
				scheduleTranslationScan(TRANSLATION_RESCAN_DELAY);
				return;
			}
			TranslationDiagnostics.log("reddit", "request_start", "generation", generation,
					"dom_id", id, "html_chars", html.length());
			TranslationController.getInstance().requestTranslation(TranslationModel.Direction.EN_RU, "", html,
					(translatedSubject, translatedHtml, error) -> applyRedditTranslation(view, generation,
							id, translatedHtml, error));
		});
	}

	private void applyRedditTranslation(WebView view, int generation, String id, String translatedHtml,
			String error) {
		if (!isCurrentPageLoad(view, generation)) {
			TranslationDiagnostics.log("reddit", "result_stale", "generation", generation,
					"current_generation", pageLoadGeneration, "dom_id", id);
			finishTranslationRequest(generation);
			return;
		}
		TranslationDiagnostics.log("reddit", "result_received", "generation", generation, "dom_id", id,
				"success", translatedHtml != null, "html_chars", TranslationDiagnostics.length(translatedHtml),
				"error", TranslationDiagnostics.safeError(error));
		String selector = "[data-slooop-translation-id=" + JSONObject.quote(id) + "]";
		String script;
		if (translatedHtml != null) {
			script = "(function(){var e=document.querySelector(" + JSONObject.quote(selector) + ");" +
					"if(!e)return 'missing';e.__slooopTranslationPending=false;e.__slooopTranslatedHtml=" +
					JSONObject.quote(translatedHtml) + ";if(window.__slooopRedditTranslationEnabled)" +
					"e.innerHTML=e.__slooopTranslatedHtml;return window.__slooopRedditTranslationEnabled?" +
					"'applied':'cached';})();";
		} else {
			script = "(function(){var e=document.querySelector(" + JSONObject.quote(selector) + ");" +
					"if(!e)return 'missing';e.__slooopTranslationPending=false;" +
					"e.__slooopTranslationFailed=true;return 'failed';})();";
		}
		view.evaluateJavascript(script, applyResult -> {
			TranslationDiagnostics.log("reddit", "dom_result", "generation", generation, "dom_id", id,
					"status", TranslationDiagnostics.safeError(applyResult));
			finishTranslationRequest(generation);
			if (translatedHtml == null) {
				if (translationEnabled && translationFailureGeneration != generation) {
					translationFailureGeneration = generation;
					ClickableToast.show(R.string.translation_failed);
				}
				scheduleTranslationScan(TRANSLATION_RESCAN_DELAY);
			} else {
				scheduleTranslationScan(0L);
			}
		});
	}

	private void finishTranslationRequest(int generation) {
		if (translationRequestGeneration == generation) {
			translationRequestPending = false;
			translationRequestGeneration = -1;
			TranslationDiagnostics.log("reddit", "request_finished", "generation", generation);
		} else {
			TranslationDiagnostics.log("reddit", "finish_ignored", "generation", generation,
					"pending_generation", translationRequestGeneration);
		}
	}

	private static JSONObject decodeJavascriptObject(String value) {
		try {
			Object decoded = new JSONTokener(value).nextValue();
			return decoded instanceof String ? new JSONObject((String) decoded) : null;
		} catch (JSONException | RuntimeException e) {
			return null;
		}
	}

	private boolean isCurrentPageLoad(WebView view, int generation) {
		return webView == view && pageLoadGeneration == generation;
	}

	private void revealPage(WebView view, int generation) {
		if (isCurrentPageLoad(view, generation)) {
			view.setVisibility(View.VISIBLE);
			if (quickNavigation != null) quickNavigation.setContentVisible(true);
		}
	}

	private void applyPagePresentation(WebView view, String url, int generation) {
		if (!isAllowedRedditPage(Uri.parse(url))) {
			revealPage(view, generation);
			return;
		}
		if (authorizationMode) {
			revealPage(view, generation);
			return;
		}
		view.evaluateJavascript(buildAppPromoSuppressionScript(), result -> {
			if (!isCurrentPageLoad(view, generation)) {
				return;
			}
			if (boardStyleScript != null) {
				view.evaluateJavascript(boardStyleScript,
						ignored -> applyDiscussionPresentation(view, generation));
			} else {
				applyDiscussionPresentation(view, generation);
			}
		});
	}

	private void applyDiscussionPresentation(WebView view, int generation) {
		if (!isCurrentPageLoad(view, generation)) {
			return;
		}
		if (readerStyleScript != null) {
			view.evaluateJavascript(readerStyleScript, ignored -> {
				if (!isCurrentPageLoad(view, generation)) {
					return;
				}
				if (hybridReaderScript != null) {
					view.evaluateJavascript(hybridReaderScript, hybridIgnored -> {
						revealPage(view, generation);
						scheduleTranslationScan(0L);
					});
				} else {
					revealPage(view, generation);
					scheduleTranslationScan(0L);
				}
			});
		} else {
			revealPage(view, generation);
			scheduleTranslationScan(0L);
		}
	}

	private class RedditWebViewClient extends WebViewClient {
		@Override
		public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
			return request.isForMainFrame() && !isAllowedRedditPage(request.getUrl()) &&
					openExternal(request.getUrl());
		}

		@Override
		public void doUpdateVisitedHistory(WebView view, String url, boolean isReload) {
			recordVisitedPage(view, url, false);
			notifyBackNavigationChanged();
		}

		@Override
		public void onPageStarted(WebView view, String url, Bitmap favicon) {
			pageLoadGeneration++;
			logDiagnostic("event=page_started generation=" + pageLoadGeneration + " host=" + getLogHost(url));
			translationRequestPending = false;
			translationRequestGeneration = -1;
			translationFailureGeneration = -1;
			handler.removeCallbacks(translationRescanRunnable);
			view.setVisibility(View.INVISIBLE);
		}

		@Override
		public void onPageCommitVisible(WebView view, String url) {
			logDiagnostic("event=page_commit generation=" + pageLoadGeneration + " host=" + getLogHost(url));
			String currentUrl = view.getUrl();
			if (url != null && url.equals(currentUrl)) {
				applyPagePresentation(view, url, pageLoadGeneration);
			}
		}

		@Override
		public void onPageFinished(WebView view, String url) {
			logDiagnostic("event=page_finished generation=" + pageLoadGeneration + " host=" + getLogHost(url));
			String clearHistoryUrl = RedditWebReaderFragment.this.clearHistoryUrl;
			if (clearHistoryUrl != null && clearHistoryUrl.equals(RedditPageStorage.normalizeUrl(url))) {
				view.clearHistory();
				RedditWebReaderFragment.this.clearHistoryUrl = null;
			}
			String currentUrl = view.getUrl();
			if (view.getVisibility() != View.VISIBLE && url != null && url.equals(currentUrl)) {
				applyPagePresentation(view, url, pageLoadGeneration);
			}
			CookieManager.getInstance().flush();
			updateSignedInState(view, null);
			recordVisitedPage(view, view.getUrl(), true);
			updateTitle();
			notifyBackNavigationChanged();
		}

		@Override
		public void onReceivedSslError(WebView view, SslErrorHandler handler, SslError error) {
			logDiagnosticError("event=ssl_error primary=" + error.getPrimaryError());
			handler.cancel();
			ClickableToast.show(R.string.invalid_certificate);
		}

		@Override
		public void onReceivedError(WebView view, WebResourceRequest request, WebResourceError error) {
			if (request.isForMainFrame()) {
				logDiagnosticError("event=main_frame_error code=" + error.getErrorCode());
			}
		}

		@Override
		public void onReceivedHttpError(WebView view, WebResourceRequest request, WebResourceResponse errorResponse) {
			if (request.isForMainFrame()) {
				logDiagnosticError("event=main_frame_http_error status=" + errorResponse.getStatusCode());
			}
		}

		@Override
		public void onScaleChanged(WebView view, float oldScale, float newScale) {
			logDiagnostic("event=scale_changed old=" + oldScale + " new=" + newScale);
		}

		@Override
		public boolean onRenderProcessGone(WebView view, RenderProcessGoneDetail detail) {
			logDiagnosticError("event=renderer_gone crashed=" + detail.didCrash()
					+ " priority=" + detail.rendererPriorityAtExit());
			return super.onRenderProcessGone(view, detail);
		}
	}

	private void updateSignedInState(WebView view, Runnable completion) {
		if (view == null || webView != view) {
			if (completion != null) {
				completion.run();
			}
			return;
		}
		view.evaluateJavascript(READ_SIGNED_IN_STATE_SCRIPT, value -> {
			if (webView == view) {
				if ("true".equals(value)) {
					Preferences.setRedditSignedIn(true);
				} else if ("false".equals(value)) {
					Preferences.setRedditSignedIn(false);
				}
			}
			if (completion != null) {
				completion.run();
			}
		});
	}

	private void recordVisitedPage(WebView view, String url, boolean loaded) {
		if (authorizationMode) {
			return;
		}
		RedditPageStorage.Entry entry = RedditPageStorage.parse(url, loaded ? view.getTitle() : null);
		if (entry == null) {
			return;
		}
		RedditPageStorage storage = RedditPageStorage.getInstance();
		if (!entry.url.equals(lastRecordedPageUrl)) {
			lastRecordedPageUrl = entry.url;
			storage.record(entry.url, entry.title);
		} else {
			storage.updateTitle(entry.url, entry.title);
		}
	}

	private void updateTitle() {
		CharSequence title = getString(authorizationMode ? R.string.reddit_sign_in : R.string.forum_reddit);
		if (!authorizationMode && webView != null) {
			RedditPageStorage.Entry entry = RedditPageStorage.parse(webView.getUrl(), webView.getTitle());
			if (entry != null) {
				title = entry.title;
			}
		}
		((FragmentHandler) requireActivity()).setTitleSubtitle(title, null);
	}
}
