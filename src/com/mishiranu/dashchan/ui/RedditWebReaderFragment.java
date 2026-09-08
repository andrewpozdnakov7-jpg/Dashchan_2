package com.mishiranu.dashchan.ui;

import android.annotation.SuppressLint;
import android.app.AlertDialog;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.content.pm.PackageInfo;
import android.graphics.Bitmap;
import android.graphics.Color;
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
import com.mishiranu.dashchan.content.translation.TranslationModel;
import com.mishiranu.dashchan.util.NavigationUtils;
import com.mishiranu.dashchan.util.ViewUtils;
import com.mishiranu.dashchan.util.WebViewUtils;
import com.mishiranu.dashchan.widget.ClickableToast;
import com.mishiranu.dashchan.widget.ExpandedLayout;
import com.mishiranu.dashchan.widget.ThemeEngine;
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
		return layout;
	}

	@SuppressLint("SetJavaScriptEnabled")
	@Override
	public void onViewCreated(@NonNull View view, Bundle savedInstanceState) {
		super.onViewCreated(view, savedInstanceState);

		navigationDrawerLocker = "reddit-web-reader-" + UUID.randomUUID();
		((FragmentHandler) requireActivity()).setNavigationAreaLocked(navigationDrawerLocker, true);
		Bundle arguments = getArguments();
		authorizationMode = arguments != null && arguments.getBoolean(EXTRA_AUTHORIZATION_MODE);
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
				+ " auth=" + authorizationMode + " reader=" + (readerStyleScript != null));
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
	}

	@Override
	public void onActivityCreated(Bundle savedInstanceState) {
		super.onActivityCreated(savedInstanceState);
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
		handler.removeCallbacks(translationRescanRunnable);
		pageLoadGeneration++;
		translationRequestPending = false;
		translationRequestGeneration = -1;
		((FragmentHandler) requireActivity()).setNavigationAreaLocked(navigationDrawerLocker, false);
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
		readerStyleScript = null;
		hybridReaderScript = null;
		finishAuthorizationMenuItem = null;
		super.onDestroyView();
	}

	@Override
	public void onCreateOptionsMenu(Menu menu, boolean primary) {
		menu.add(0, R.id.menu_reload, 0, R.string.reload)
				.setIcon(((FragmentHandler) requireActivity()).getActionBarIcon(R.attr.iconActionRefresh))
				.setShowAsAction(MenuItem.SHOW_AS_ACTION_IF_ROOM);
		menu.add(0, R.id.menu_translate, 0, R.string.translate_posts)
				.setIcon(((FragmentHandler) requireActivity()).getActionBarIcon(R.attr.iconActionTranslate))
				.setShowAsAction(MenuItem.SHOW_AS_ACTION_IF_ROOM);
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
	public boolean onOptionsItemSelected(MenuItem item) {
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
		switch (item.getItemId()) {
			case R.id.menu_open_reddit_link: {
				showOpenLinkDialog();
				return true;
			}
			case R.id.menu_reload: {
				webView.reload();
				return true;
			}
			case R.id.menu_translate: {
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
			}
			case R.id.menu_copy_link: {
				StringUtils.copyToClipboard(requireContext(), webView.getUrl());
				return true;
			}
			case R.id.menu_share_link: {
				String url = webView.getUrl();
				if (!StringUtils.isEmpty(url)) {
					NavigationUtils.shareLink(requireContext(), null, Uri.parse(url));
				}
				return true;
			}
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
		new AlertDialog.Builder(requireContext())
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

	private static String buildAppPromoSuppressionScript() {
		// Reddit can inject this dialog long after the initial page load. Inspect only mutation targets and newly
		// attached shadow roots synchronously, before the browser paints them; rescanning the entire feed would stall it.
		return "(function(){if(window.__slooopRedditPromoGuard)return;" +
				"var promoKnown='shreddit-app-selector,shreddit-app-selector-banner,shreddit-app-selector-modal," +
				"xpromo-app-selector,shreddit-async-loader[bundlename*=\"app-selector\"]," +
				"[data-testid*=\"app-selector\"],[data-testid*=\"app-promo\"],.XPromoPopupRpl.m-active," +
				"[paint-group=\"xpromo\"],#xpromo-bottom-sheet';" +
				"var headerKnown='reddit-header-large,reddit-header-small';" +
				"var style=document.createElement('style');style.textContent=promoKnown+'{display:none!important}'+" +
				"'.slooop-reddit-app-promo{display:none!important}'+" +
				"headerKnown+'{display:none!important;visibility:hidden!important;height:0!important;min-height:0!important}'+" +
				"'shreddit-app{--shreddit-header-height:0px!important;" +
				"--shreddit-header-large-height:0px!important;}';" +
				"(document.head||document.documentElement).appendChild(style);" +
				"function parent(n){if(n.parentElement)return n.parentElement;var r=n.getRootNode&&n.getRootNode();" +
				"return r&&r.host?r.host:null;}" +
				"function query(root,selector){var out=[];if(root&&root.nodeType===1&&root.matches&&" +
				"root.matches(selector))out.push(root);var found=root&&root.querySelectorAll?root.querySelectorAll(selector):[];" +
				"for(var i=0;i<found.length;i++)out.push(found[i]);return out;}" +
				"function compactEdited(root){var spans=query(root,'span');for(var i=0;i<spans.length;i++){" +
				"var span=spans[i];if(span.hasAttribute('data-slooop-edited-compact')||" +
				"!span.querySelector('faceplate-timeago'))continue;var text=span.textContent||'';" +
				"if(text.indexOf('\u041e\u0442\u0440\u0435\u0434\u0430\u043a\u0442')<0)continue;" +
				"for(var node=span.firstChild;node;node=node.nextSibling)if(node.nodeType===3&&" +
				"node.nodeValue.indexOf('\u041e\u0442\u0440\u0435\u0434\u0430\u043a\u0442')>=0){" +
				"node.nodeValue=', \u0440\u0435\u0434. ';span.setAttribute('data-slooop-edited-compact','');break;}}}" +
				"function promoText(t){t=(t||'').toLowerCase();return (t.indexOf('reddit')>=0||" +
				"t.indexOf('прилож')>=0)&&/(open|download|get|install|откры|скач|загруз|установ)/.test(t);}" +
				"function appAction(n){var h=((n.href||n.getAttribute&&n.getAttribute('href')||'')+'').toLowerCase();" +
				"if(h.indexOf('com.reddit.frontpage')>=0||h.indexOf('id1064216828')>=0||" +
				"h.indexOf('reddit.app.link')>=0||h.indexOf('/mobile/download')>=0)return true;" +
				"var t=((n.innerText||'')+' '+(n.getAttribute&&n.getAttribute('aria-label')||'')+' '+" +
				"(n.getAttribute&&n.getAttribute('title')||'')).toLowerCase();" +
				"return promoText(t);}" +
				"function dialogFor(n){var found=null;for(var i=0;n&&i<24;i++,n=parent(n)){" +
				"var name=((n.localName||'')+' '+(n.id||'')+' '+(typeof n.className==='string'?n.className:'')+' '+" +
				"(n.getAttribute&&n.getAttribute('data-testid')||'')).toLowerCase();" +
				"var role=n.getAttribute&&n.getAttribute('role'),s=getComputedStyle(n),r=n.getBoundingClientRect();" +
				"if(role==='dialog'||role==='alertdialog'||n.localName==='dialog')return n;" +
				"if(!found&&(/(app-selector|app-promo|xpromo)/.test(name)||" +
				"(/^(fixed|absolute|sticky)$/.test(s.position)&&r.width>=innerWidth*.7&&" +
				"r.height>=innerHeight*.18&&r.height<=innerHeight*1.1&&r.bottom>=innerHeight*.8)))found=n;}" +
				"return found;}" +
				"function hide(n){if(!n||n.classList&&n.classList.contains('slooop-reddit-app-promo'))" +
				"return false;if(n.classList)n.classList.add('slooop-reddit-app-promo');" +
				"if(n.style)n.style.setProperty('display','none','important');return true;}" +
				"function clearUnlock(){var b=document.body,e=document.documentElement;[e,b].forEach(function(n){" +
				"if(!n||!n.hasAttribute('data-slooop-promo-unlocked'))return;" +
				"if(n.style.getPropertyValue('overflow-y')==='auto')n.style.removeProperty('overflow-y');" +
				"if(n.style.getPropertyValue('touch-action')==='auto')n.style.removeProperty('touch-action');" +
				"n.removeAttribute('data-slooop-promo-unlocked');});}" +
				"function unlock(){var top=0,b=document.body,e=document.documentElement;if(b){" +
				"top=parseFloat(b.style.top)||0;}[e,b].forEach(function(n){if(!n)return;" +
				"var s=getComputedStyle(n);if(s.overflow==='hidden'||s.overflow==='clip'||s.overflowY==='hidden'||" +
				"s.overflowY==='clip'){n.style.setProperty('overflow-y','auto','important');" +
				"n.setAttribute('data-slooop-promo-unlocked','');}" +
				"n.style.setProperty('touch-action','auto','important');n.setAttribute('data-slooop-promo-unlocked','');" +
				"if(s.position==='fixed'){n.style.removeProperty('position');n.style.removeProperty('top');}});" +
				"if(top<0)setTimeout(function(){scrollTo(0,-top);},0);}" +
				"function roots(root,out){out.push(root);if(root!==document){try{observer.observe(root,options);}" +
				"catch(ignored){}}" +
				"var all=root.querySelectorAll?root.querySelectorAll('*'):[];" +
				"for(var i=0;i<all.length;i++)if(all[i].shadowRoot)roots(all[i].shadowRoot,out);}" +
				"function scan(root){var rs=[];roots(root||document,rs),hidden=false,legitimateDialog=false;" +
				"for(var x=0;x<rs.length;x++){" +
				"compactEdited(rs[x]);" +
				"var headers=query(rs[x],headerKnown);" +
				"for(var h=0;h<headers.length;h++){headers[h].style.setProperty('display','none','important');" +
				"headers[h].style.setProperty('visibility','hidden','important');}" +
				"var direct=query(rs[x],promoKnown);for(var d=0;d<direct.length;d++)hidden=hide(direct[d])||hidden;" +
				"var dialogs=query(rs[x],'dialog,[role=dialog],[role=alertdialog],rpl-dialog,rpl-dialog-sheet');" +
				"for(var j=0;j<dialogs.length;j++){var dialog=dialogs[j],text=dialog.innerText||dialog.textContent||'';" +
				"if(text.length<1600&&promoText(text))hidden=hide(dialog)||hidden;else{" +
				"var rect=dialog.getBoundingClientRect(),display=getComputedStyle(dialog).display;" +
				"if(display!=='none'&&rect.width>0&&rect.height>0)legitimateDialog=true;}}" +
				"var actions=query(rs[x],'a,button,[role=button],faceplate-tracker,[tabindex]');" +
				"for(var a=0;a<actions.length;a++)if(appAction(actions[a]))" +
				"hidden=hide(dialogFor(actions[a]))||hidden;}" +
				"if(legitimateDialog)clearUnlock();else if(hidden){unlock();setTimeout(unlock,100);" +
				"setTimeout(unlock,400);}return hidden;}" +
				"var options={childList:true,subtree:true},observer=new MutationObserver(function(changes){" +
				"var targets=[];" +
				"for(var i=0;i<changes.length;i++)if(targets.indexOf(changes[i].target)<0)" +
				"targets.push(changes[i].target);for(var i=0;i<targets.length;i++)scan(targets[i]);});" +
				"observer.observe(document.documentElement,options);window.__slooopRedditPromoGuard=observer;" +
				"scan(document);})();";
	}

	private String buildReaderStyleScript() {
		ThemeEngine.Theme theme = ThemeEngine.getTheme(requireContext());
		int textPrimary = theme.post;
		int textSecondary = theme.meta;
		String css = "html.slooop-reddit-reader{" +
				"--shreddit-content-background:" + color(theme.card) + "!important;" +
				"--color-neutral-background:" + color(theme.window) + "!important;" +
				"--color-neutral-background-weak:" + color(theme.card) + "!important;" +
				"--color-neutral-background-medium:" + color(theme.card) + "!important;" +
				"--color-neutral-background-strong:" + color(theme.card) + "!important;" +
				"--color-neutral-background-container:" + color(theme.card) + "!important;" +
				"--color-neutral-content:" + color(textPrimary) + "!important;" +
				"--color-neutral-content-weak:" + color(textSecondary) + "!important;" +
				"--color-neutral-content-strong:" + color(textPrimary) + "!important;" +
				"--color-neutral-border-weak:" + colorWithAlpha(theme.meta, 0x45) + "!important;" +
				"--color-primary:" + color(theme.link) + "!important;" +
				"color-scheme:" + (theme.base == ThemeEngine.Theme.Base.DARK ? "dark" : "light") + "}" +
				"html.slooop-reddit-reader reddit-header-large{display:none!important}" +
				"html.slooop-reddit-reader body{background:" + color(theme.window) +
				"!important;color:" + color(textPrimary) + "!important;overflow-wrap:anywhere}" +
				"html.slooop-reddit-reader .grid-container.theme-rpl," +
				"html.slooop-reddit-reader #main-content," +
				"html.slooop-reddit-reader #comment-tree{background:" + color(theme.window) +
				"!important;color:" + color(textPrimary) + "!important}" +
				"html.slooop-reddit-reader #comment-tree>section{padding-inline:6px!important}" +
				"html.slooop-reddit-reader shreddit-post{" +
				"display:block!important;box-sizing:border-box!important;background:" + color(theme.card) +
				"!important;color:" + color(textPrimary) + "!important;border:0!important;" +
				"border-inline-start:3px solid " + color(theme.accent) + "!important;border-radius:8px!important;" +
				"margin:8px 6px 12px!important;padding:0 10px 10px!important;box-shadow:none!important}" +
				"html.slooop-reddit-reader shreddit-post [slot=\"title\"]{color:" + color(textPrimary) +
				"!important;font-size:17px!important;line-height:1.35!important;margin:4px 0 8px!important;" +
				"padding:0!important}" +
				"html.slooop-reddit-reader shreddit-post [slot=\"credit-bar\"]{color:" +
				color(textSecondary) + "!important;padding:8px 0 4px!important}" +
				"html.slooop-reddit-reader :is(shreddit-ad-post,shreddit-post[is-promoted]," +
				"shreddit-post[promoted],shreddit-post[is-sponsored],shreddit-post[sponsored]) " +
				"[slot=\"title\"]{display:-webkit-box!important;-webkit-box-orient:vertical!important;" +
				"-webkit-line-clamp:2!important;overflow:hidden!important;font-size:9px!important;" +
				"line-height:1.2!important;margin:2px 0!important}" +
				"html.slooop-reddit-reader :is(shreddit-ad-post,shreddit-post[is-promoted]," +
				"shreddit-post[promoted],shreddit-post[is-sponsored],shreddit-post[sponsored]) " +
				":is([slot=\"credit-bar\"],[slot=\"call-to-action\"],[data-testid=\"cta\"]){" +
				"font-size:9px!important;line-height:1.15!important;padding-top:2px!important;" +
				"padding-bottom:2px!important}" +
				"html.slooop-reddit-reader :is(shreddit-ad-post,shreddit-post[is-promoted]," +
				"shreddit-post[promoted],shreddit-post[is-sponsored],shreddit-post[sponsored]) " +
				":is([slot=\"post-media-container\"],[data-post-click-location=\"media\"]," +
				"[data-testid=\"post-media\"],reddit-video,shreddit-player,shreddit-gallery," +
				"gallery-carousel,picture,figure){box-sizing:border-box!important;height:56px!important;" +
				"max-height:56px!important;min-height:0!important;overflow:hidden!important;" +
				"margin:3px 0!important;border-radius:8px!important}" +
				"html.slooop-reddit-reader :is(shreddit-ad-post,shreddit-post[is-promoted]," +
				"shreddit-post[promoted],shreddit-post[is-sponsored],shreddit-post[sponsored]) " +
				":is([slot=\"post-media-container\"],[data-post-click-location=\"media\"]," +
				"[data-testid=\"post-media\"],reddit-video,shreddit-player,shreddit-gallery," +
				"gallery-carousel,picture,figure) :is(img,video){width:100%!important;height:56px!important;" +
				"max-height:56px!important;object-fit:cover!important}" +
				"html.slooop-reddit-reader shreddit-post [slot=\"text-body\"]," +
				"html.slooop-reddit-reader shreddit-post [slot=\"text-body\"] p{color:" +
				color(textPrimary) + "!important;line-height:1.45!important}" +
				"html.slooop-reddit-reader shreddit-comment{color:" + color(textPrimary) + "!important}" +
				"html.slooop-reddit-reader shreddit-comment>details{box-sizing:border-box!important;background:" +
				color(theme.card) + "!important;color:" + color(textPrimary) +
				"!important;border:0!important;border-radius:7px!important;position:relative!important;" +
				"margin:4px 0!important;padding:5px 4px 6px 2px!important}" +
				"html.slooop-reddit-reader shreddit-comment[slot]>details{background:transparent!important}" +
				"html.slooop-reddit-reader shreddit-comment>details::before{content:''!important;" +
				"position:absolute!important;inset-block:0!important;inset-inline-start:0!important;width:2px!important;" +
				"background:" + colorWithAlpha(theme.accent, 0x78) +
				"!important;z-index:3!important;pointer-events:none!important}" +
				"html.slooop-reddit-reader shreddit-comment .comment-main-grid{" +
				"grid-template-columns:8px minmax(0,1fr)!important}" +
				"html.slooop-reddit-reader shreddit-comment .comment-main-grid>.ssr-thread-line{" +
				"width:8px!important}" +
				"html.slooop-reddit-reader shreddit-comment .comment-main-grid>.ssr-thread-line::before{" +
				"display:none!important}" +
				"html.slooop-reddit-reader shreddit-comment .comment-main-grid>div.col-span-2.grid{" +
				"grid-template-columns:8px minmax(0,1fr)!important}" +
				"html.slooop-reddit-reader shreddit-comment .branchline{background:transparent!important}" +
				"html.slooop-reddit-reader shreddit-comment [slot=\"commentMeta\"]{color:" +
				color(textSecondary) + "!important;font-size:12px!important;white-space:nowrap!important;" +
				"overflow-wrap:normal!important;word-break:normal!important;min-width:0!important}" +
				"html.slooop-reddit-reader shreddit-comment [slot=\"commentMeta\"] *{" +
				"white-space:nowrap!important;overflow-wrap:normal!important;word-break:normal!important}" +
				"html.slooop-reddit-reader shreddit-comment [slot=\"commentMeta\"] .author-name-meta{" +
				"flex:1 1 auto!important;min-width:0!important;overflow:hidden!important}" +
				"html.slooop-reddit-reader shreddit-comment [slot=\"commentMeta\"] " +
				"faceplate-tracker[noun=\"comment_time\"] a," +
				"html.slooop-reddit-reader shreddit-comment [slot=\"commentMeta\"] " +
				"span[data-slooop-edited-compact]{flex:0 0 auto!important}" +
				"html.slooop-reddit-reader shreddit-comment [slot=\"commentMeta\"] a{" +
				"color:" + color(textPrimary) + "!important}" +
				"html.slooop-reddit-reader shreddit-comment [slot=\"commentMeta\"] time{" +
				"color:" + color(textSecondary) + "!important}" +
				"html.slooop-reddit-reader shreddit-comment [slot=\"comment\"]{color:" + color(textPrimary) +
				"!important;font-size:14px!important;line-height:1.45!important;padding-top:2px!important;" +
				"padding-bottom:2px!important}" +
				"html.slooop-reddit-reader shreddit-comment [slot=\"comment\"] p{" +
				"color:" + color(textPrimary) + "!important;margin-top:4px!important;margin-bottom:6px!important}" +
				"html.slooop-reddit-reader shreddit-comment [slot=\"actionRow\"]{" +
				"color:" + color(textSecondary) + "!important;min-height:28px!important}" +
				"html.slooop-reddit-reader shreddit-comment [slot=\"actionRow\"] button{" +
				"color:" + color(textSecondary) + "!important}" +
				"html.slooop-reddit-reader faceplate-partial[slot=\"children\"]," +
				"html.slooop-reddit-reader a[slot=\"more-comments-permalink\"]{display:inline-flex!important;" +
				"color:" + color(theme.link) + "!important;background:" + color(theme.card) +
				"!important;border:1px solid " + colorWithAlpha(theme.accent, 0x6b) +
				"!important;border-radius:16px!important;margin:5px 8px!important;padding:6px 12px!important;" +
				"min-height:0!important}" +
				"html.slooop-reddit-reader faceplate-partial[slot=\"children\"] button," +
				"html.slooop-reddit-reader a[slot=\"more-comments-permalink\"] button{" +
				"color:" + color(theme.link) + "!important}";
		String escapedCss = css.replace("\\", "\\\\").replace("'", "\\'");
		return "(function(){var i='slooop-reddit-reader-style',s=document.getElementById(i);" +
				"if(!s){s=document.createElement('style');s.id=i;document.head.appendChild(s);}" +
				"s.textContent='" + escapedCss + "';" +
				"document.documentElement.classList.add('slooop-reddit-reader');})();";
	}

	private String buildHybridReaderScript() {
		ThemeEngine.Theme theme = ThemeEngine.getTheme(requireContext());
		boolean russian = "ru".equals(requireContext().getResources().getConfiguration()
				.getLocales().get(0).getLanguage());
		String css = ":host{display:block;box-sizing:border-box;min-height:100vh;background:" +
				color(theme.window) + ";color:" + color(theme.post) +
				";font:14px/1.45 sans-serif;-webkit-text-size-adjust:100%;overflow-wrap:anywhere}" +
				":host([data-mode=original]){position:fixed;right:12px;bottom:12px;z-index:2147483646;" +
				"min-height:0;background:transparent}" +
				":host([data-mode=original]) .reader{display:none}" +
				":host(:not([data-mode=original])) .reader-return{display:none}" +
				"*{box-sizing:border-box}button{font:inherit}a{color:" + color(theme.link) +
				";text-decoration:none}a:active{text-decoration:underline}" +
				".reader{min-height:100vh;padding:8px 6px 24px}" +
				".toolbar{position:sticky;top:0;z-index:20;display:flex;align-items:center;gap:8px;" +
				"margin:-8px -6px 8px;padding:8px;background:" + colorWithAlpha(theme.window, 0xf2) +
				";border-bottom:1px solid " + colorWithAlpha(theme.meta, 0x45) + ";backdrop-filter:blur(8px)}" +
				".toolbar-title{flex:1;min-width:0;font-weight:700;white-space:nowrap;overflow:hidden;" +
				"text-overflow:ellipsis}.button{border:1px solid " + colorWithAlpha(theme.accent, 0x80) +
				";border-radius:18px;padding:6px 11px;background:" + color(theme.card) + ";color:" +
				color(theme.link) + ";min-height:32px}.reader-return{box-shadow:0 2px 8px " +
				colorWithAlpha(Color.BLACK, 0x55) + ";background:" + color(theme.card) + "}" +
				".notice{margin:6px 2px 10px;color:" + color(theme.meta) + ";font-size:12px}" +
				".post{margin:0 0 12px;padding:10px;border-radius:8px;background:" + color(theme.card) +
				";border-left:3px solid " + color(theme.accent) + "}" +
				".post-meta,.comment-meta{color:" + color(theme.meta) + ";font-size:12px}" +
				".post-title{margin:5px 0 8px;font-size:17px;line-height:1.35;font-weight:700}" +
				".post-link{display:block;margin:7px 0;padding:7px;border-radius:6px;background:" +
				colorWithAlpha(theme.link, 0x16) + ";white-space:nowrap;overflow:hidden;text-overflow:ellipsis}" +
				".content p{margin:4px 0 7px}.content blockquote{margin:7px 0;padding-left:9px;" +
				"border-left:3px solid " + colorWithAlpha(theme.meta, 0x70) + ";color:" + color(theme.meta) + "}" +
				".content pre{overflow:auto;padding:7px;border-radius:5px;background:" + color(theme.window) + "}" +
				".content code{font-family:monospace}.content img{display:block;max-width:100%;height:auto;" +
				"margin:6px 0;border-radius:6px}" +
				".comments-title{margin:10px 3px 5px;font-weight:700}" +
				".comment{position:relative;margin-top:4px;padding:6px 6px 6px 8px;border-radius:0 7px 7px 0;" +
				"border-left:2px solid " + colorWithAlpha(theme.accent, 0x92) + ";background:" + color(theme.card) + "}" +
				".comment-meta{display:flex;gap:5px;align-items:baseline;white-space:nowrap;overflow:hidden}" +
				".author{min-width:0;overflow:hidden;text-overflow:ellipsis;color:" + color(theme.post) +
				";font-weight:700}.time,.score{flex:0 0 auto}.comment-body{margin-top:3px}" +
				".comment-actions{display:flex;justify-content:flex-end;margin-top:3px}" +
				".comment-actions button{border:0;padding:3px 6px;background:transparent;color:" +
				color(theme.link) + ";font-size:12px}.empty{padding:16px;color:" + color(theme.meta) +
				";text-align:center}.more{display:block;margin:10px auto}" +
				"[hidden]{display:none!important}.action-overlay{position:fixed;inset:0;z-index:40;display:flex;" +
				"align-items:flex-end;background:" + colorWithAlpha(Color.BLACK, 0x72) + "}" +
				".action-sheet{width:100%;padding:10px 10px max(10px,env(safe-area-inset-bottom));" +
				"border-radius:14px 14px 0 0;background:" + color(theme.card) + ";box-shadow:0 -3px 14px " +
				colorWithAlpha(Color.BLACK, 0x55) + "}.action-title{padding:4px 6px 9px;font-weight:700}" +
				".action-sheet button{display:block;width:100%;min-height:42px;padding:9px 7px;border:0;" +
				"border-top:1px solid " + colorWithAlpha(theme.meta, 0x35) + ";background:transparent;" +
				"color:" + color(theme.post) + ";text-align:left}.action-sheet button:last-child{color:" +
				color(theme.meta) + "}";
		String quotedCss = JSONObject.quote(css);
		return "(function(){" +
				"var VERSION=4,existing=window.__slooopRedditHybridReader;if(existing&&existing.version===VERSION){" +
				"existing.scan();return;}if(existing){try{existing.observer.disconnect();}catch(e){}" +
				"try{if(existing.moreObserver)existing.moreObserver.disconnect();}catch(e){}" +
				"try{document.removeEventListener('click',existing.navigationIntent,true);}catch(e){}" +
				"try{window.removeEventListener('popstate',existing.historyNavigation);}catch(e){}" +
				"document.documentElement.classList.remove('slooop-hybrid-reader-active');" +
				"var staleHost=document.getElementById('slooop-hybrid-reader-host');if(staleHost)staleHost.remove();" +
				"var staleStyle=document.getElementById('slooop-hybrid-reader-style');if(staleStyle)staleStyle.remove();" +
				"delete window.__slooopRedditHybridReader;}" +
				"var HOST='slooop-hybrid-reader-host',ACTIVE='slooop-hybrid-reader-active';" +
				"var state={active:false,initialized:false,userOriginal:false,timer:0,key:'',path:''," +
				"readerY:0,originalY:0,action:null,pendingSince:0,pendingReason:'',moreLoading:false,moreTarget:null};" +
				"function log(name,detail){console.log('" + JS_LOG_PREFIX +
				"event=hybrid_'+name+(detail?' '+detail:''));}" +
				"function text(value){return(value||'').replace(/\\s+/g,' ').trim();}" +
				"function safeUrl(value){try{var u=new URL(value,location.href);" +
				"return u.protocol==='https:'?u.href:null;}catch(e){return null;}}" +
				"function digest(value){var hash=2166136261;for(var i=0;i<value.length;i++){hash^=value.charCodeAt(i);" +
				"hash=Math.imul(hash,16777619);}return(hash>>>0).toString(36);}" +
				"function element(name,className,value){var e=document.createElement(name);" +
				"if(className)e.className=className;if(value)e.textContent=value;return e;}" +
				"var allowed={P:1,BR:1,A:1,STRONG:1,B:1,EM:1,I:1,U:1,S:1,CODE:1,PRE:1," +
				"BLOCKQUOTE:1,UL:1,OL:1,LI:1,H1:1,H2:1,H3:1,H4:1,H5:1,H6:1,SUP:1,SUB:1,SPAN:1,IMG:1};" +
				"function copyClean(source,target){for(var child=source.firstChild;child;child=child.nextSibling){" +
				"if(child.nodeType===3){target.appendChild(document.createTextNode(child.nodeValue));continue;}" +
				"if(child.nodeType!==1)continue;var tag=child.tagName;if(tag==='SCRIPT'||tag==='STYLE'||tag==='IFRAME'||" +
				"tag==='FORM'||tag==='INPUT'||tag==='BUTTON'||tag==='VIDEO'||tag==='AUDIO')continue;" +
				"var out=allowed[tag]?document.createElement(tag.toLowerCase()):document.createDocumentFragment();" +
				"if(tag==='A'){var href=safeUrl(child.getAttribute('href'));if(href){out.setAttribute('href',href);" +
				"out.setAttribute('rel','noopener noreferrer');}}else if(tag==='IMG'){var src=safeUrl(child.getAttribute('src'));" +
				"if(!src)continue;out.setAttribute('src',src);out.setAttribute('loading','lazy');" +
				"out.setAttribute('alt',child.getAttribute('alt')||'');}" +
				"copyClean(child,out);target.appendChild(out);}}" +
				"function cleanContent(source){var out=element('div','content');if(source)copyClean(source,out);return out;}" +
				"function own(comment,selector){var all=comment.querySelectorAll(selector);for(var i=0;i<all.length;i++)" +
				"if(all[i].closest('shreddit-comment')===comment)return all[i];return null;}" +
				"var host=document.getElementById(HOST);if(!host){host=document.createElement('div');host.id=HOST;" +
				"host.hidden=true;(document.body||document.documentElement).appendChild(host);}" +
				"var shadow=host.shadowRoot||host.attachShadow({mode:'open'});shadow.innerHTML='<style>'+" +
				quotedCss + "+'</style><button class=\"button reader-return\" type=\"button\"></button>' +" +
				"'<main class=\"reader\"><header class=\"toolbar\"><div class=\"toolbar-title\"></div>' +" +
				"'<button class=\"button original\" type=\"button\"></button></header><div class=\"notice\"></div>' +" +
				"'<article class=\"post\"></article><div class=\"comments-title\"></div>' +" +
				"'<section class=\"comments\"></section><button class=\"button more\" type=\"button\"></button></main>' +" +
				"'<div class=\"action-overlay\" hidden><section class=\"action-sheet\"><div class=\"action-title\"></div>' +" +
				"'<button type=\"button\" data-command=\"copy-text\"></button><button type=\"button\" data-command=\"copy-link\"></button>' +" +
				"'<button type=\"button\" data-command=\"collapse\"></button><button type=\"button\" data-command=\"original-actions\"></button>' +" +
				"'<button type=\"button\" data-command=\"cancel\"></button></section></div>';" +
				"var documentStyle=document.getElementById('slooop-hybrid-reader-style');if(!documentStyle){" +
				"documentStyle=document.createElement('style');documentStyle.id='slooop-hybrid-reader-style';" +
				"documentStyle.textContent='html.'+ACTIVE+' body>*:not(#'+HOST+'){display:none!important}';" +
				"document.head.appendChild(documentStyle);}" +
				"var ru=" + russian + ";" +
				"var labels=ru?{title:'Режим чтения',original:'Оригинал',reader:'Режим чтения'," +
				"notice:'Стабильная разметка Slooop. Ответы, голосование и другие действия доступны на оригинальной странице Reddit.'," +
				"comments:'Комментарии',empty:'Комментарии пока не загружены.',loading:'Загрузка обсуждения…',actions:'Действия'," +
				"more:'Ещё ответы',copyText:'Копировать текст',copyLink:'Копировать ссылку',copied:'Скопировано'," +
				"collapse:'Свернуть ветку',expand:'Развернуть ветку',originalActions:'Действия на оригинальной странице',cancel:'Отмена'}:" +
				"{title:'Reading mode',original:'Original',reader:'Reading mode'," +
				"notice:'Stable Slooop layout. Replies, voting, and other actions remain available on the original Reddit page.'," +
				"comments:'Comments',empty:'Comments have not loaded yet.',loading:'Loading discussion…',actions:'Actions'," +
				"more:'More replies',copyText:'Copy text',copyLink:'Copy link',copied:'Copied'," +
				"collapse:'Collapse thread',expand:'Expand thread',originalActions:'Actions on original page',cancel:'Cancel'};" +
				"shadow.querySelector('.toolbar-title').textContent=labels.title;shadow.querySelector('.original').textContent=labels.original;" +
				"shadow.querySelector('.reader-return').textContent=labels.reader;shadow.querySelector('.notice').textContent=labels.notice;" +
				"shadow.querySelector('.comments-title').textContent=labels.comments;shadow.querySelector('.more').textContent=labels.more;" +
				"shadow.querySelector('[data-command=copy-text]').textContent=labels.copyText;" +
				"shadow.querySelector('[data-command=copy-link]').textContent=labels.copyLink;" +
				"shadow.querySelector('[data-command=original-actions]').textContent=labels.originalActions;" +
				"shadow.querySelector('[data-command=cancel]').textContent=labels.cancel;" +
				"var actionOverlay=shadow.querySelector('.action-overlay');" +
				"function closeActions(){actionOverlay.hidden=true;state.action=null;}" +
				"function showReader(){state.originalY=scrollY;state.active=true;state.userOriginal=false;host.hidden=false;" +
				"closeActions();host.removeAttribute('data-mode');" +
				"document.documentElement.classList.add(ACTIVE);requestAnimationFrame(function(){scrollTo(0,state.readerY||0);});" +
				"log('mode','value=reader');}" +
				"function showOriginal(target){state.readerY=scrollY;state.active=false;state.userOriginal=true;closeActions();" +
				"document.documentElement.classList.remove(ACTIVE);" +
				"host.setAttribute('data-mode','original');requestAnimationFrame(function(){if(target&&target.isConnected)" +
				"target.scrollIntoView({block:'center'});else scrollTo(0,state.originalY||0);});log('mode','value=original');}" +
				"function copyValue(value,button){function done(ok){if(ok){var old=button.textContent;button.textContent=labels.copied;" +
				"setTimeout(function(){button.textContent=old;},900);}log('copy','success='+!!ok);}" +
				"if(navigator.clipboard&&navigator.clipboard.writeText){navigator.clipboard.writeText(value).then(function(){done(true);}," +
				"function(){fallback();});}else fallback();function fallback(){var area=document.createElement('textarea');" +
				"area.value=value;area.setAttribute('readonly','');area.style.position='fixed';area.style.opacity='0';" +
				"shadow.appendChild(area);area.select();var ok=false;try{ok=document.execCommand('copy');}catch(e){}area.remove();done(ok);}}" +
				"function setCollapsed(card,collapsed){var depth=parseInt(card.dataset.depth||'0',10),next=card.nextElementSibling;" +
				"while(next){var nextDepth=parseInt(next.dataset.depth||'0',10);if(nextDepth<=depth)break;next.hidden=collapsed;next=next.nextElementSibling;}" +
				"card.dataset.collapsed=collapsed?'true':'false';}" +
				"function openActions(comment,card,bodyText,permalink){state.action={comment:comment,card:card,body:bodyText,link:permalink};" +
				"shadow.querySelector('.action-title').textContent='u/'+text(comment.getAttribute('author'));" +
				"var collapse=shadow.querySelector('[data-command=collapse]'),next=card.nextElementSibling;" +
				"collapse.hidden=!next||parseInt(next.dataset.depth||'0',10)<=parseInt(card.dataset.depth||'0',10);" +
				"collapse.textContent=card.dataset.collapsed==='true'?labels.expand:labels.collapse;actionOverlay.hidden=false;" +
				"log('actions_open','comment='+text(comment.getAttribute('thingid')));}" +
				"shadow.querySelector('.original').onclick=function(){showOriginal(null);};" +
				"shadow.querySelector('.reader-return').onclick=showReader;" +
				"actionOverlay.onclick=function(event){if(event.target===actionOverlay)closeActions();};" +
				"shadow.querySelector('[data-command=cancel]').onclick=closeActions;" +
				"shadow.querySelector('[data-command=copy-text]').onclick=function(){if(state.action)copyValue(state.action.body,this);};" +
				"shadow.querySelector('[data-command=copy-link]').onclick=function(){if(state.action)copyValue(state.action.link,this);};" +
				"shadow.querySelector('[data-command=collapse]').onclick=function(){if(!state.action)return;" +
				"var collapsed=state.action.card.dataset.collapsed!=='true';setCollapsed(state.action.card,collapsed);closeActions();" +
				"log('branch_toggle','collapsed='+collapsed);};" +
				"shadow.querySelector('[data-command=original-actions]').onclick=function(){if(state.action)showOriginal(state.action.comment);};" +
				"function findMoreButton(target){var buttons=target?target.querySelectorAll('button'):[];" +
				"for(var i=0;i<buttons.length;i++){var candidate=buttons[i];if(candidate.getAttribute('aria-hidden')!=='true'&&" +
				"candidate.getAttribute('aria-label')!=='Loading'&&!candidate.disabled)return candidate;}return null;}" +
				"function findMoreTarget(tree){var targets=tree.querySelectorAll('faceplate-partial[slot=children]');" +
				"for(var i=0;i<targets.length;i++)if(findMoreButton(targets[i]))return targets[i];return null;}" +
				"function loadMore(target,button){if(!target||!target.isConnected||button.disabled||state.moreLoading)return;" +
				"var clickable=findMoreButton(target);" +
				"if(!clickable||typeof clickable.click!=='function'){log('more_failed','reason=no_action');return;}" +
				"state.moreLoading=true;button.disabled=true;button.textContent=labels.loading;" +
				"state.active=true;state.userOriginal=false;host.hidden=false;host.removeAttribute('data-mode');" +
				"document.documentElement.classList.add(ACTIVE);try{clickable.click();}catch(e){state.moreLoading=false;" +
				"button.disabled=false;button.textContent=labels.more;log('more_failed','reason=click_exception');return;}" +
				"log('more_requested','tag='+target.tagName);setTimeout(function(){state.moreLoading=false;" +
				"button.disabled=false;button.textContent=labels.more;scan(0);},3000);}" +
				"var moreButton=shadow.querySelector('.more'),moreObserver=null;" +
				"if(typeof IntersectionObserver==='function'){moreObserver=new IntersectionObserver(function(entries){" +
				"for(var i=0;i<entries.length;i++)if(entries[i].isIntersecting&&state.active&&!state.userOriginal&&" +
				"state.moreTarget&&!state.moreLoading){log('more_auto','margin=900px');loadMore(state.moreTarget,moreButton);break;}" +
				"},{root:null,rootMargin:'900px 0px',threshold:0});moreObserver.observe(moreButton);}" +
				"function showPending(reason){if(state.userOriginal)return;var postBox=shadow.querySelector('.post')," +
				"titleBox=shadow.querySelector('.comments-title'),list=shadow.querySelector('.comments');postBox.hidden=true;" +
				"titleBox.hidden=true;shadow.querySelector('.more').hidden=true;list.replaceChildren(element('div','empty',labels.loading));" +
				"host.hidden=false;state.initialized=true;if(!state.active)showReader();if(state.pendingReason!==reason){" +
				"state.pendingReason=reason;log('pending','reason='+reason);}}" +
				"function render(){try{var path=location.pathname,isThread=/\\/comments\\//.test(path);" +
				"if(state.path!==path){state.path=path;state.key='';state.pendingSince=Date.now();state.userOriginal=false;" +
				"state.initialized=false;state.pendingReason='';state.moreLoading=false;state.moreTarget=null;}" +
				"var post=isThread?document.querySelector('shreddit-post'):null,tree=document.querySelector('#comment-tree');" +
				"if(!tree&&document.querySelector('shreddit-comment'))tree=document;" +
				"if(!isThread){if(state.initialized||state.active){document.documentElement.classList.remove(ACTIVE);host.hidden=true;" +
				"state.initialized=false;state.active=false;state.key='';log('fallback','reason=not_thread');}return;}" +
				"if(!post||!tree){if(document.readyState==='complete'&&Date.now()-state.pendingSince>12000){" +
				"document.documentElement.classList.remove(ACTIVE);host.hidden=true;state.active=false;state.userOriginal=true;" +
				"log('fallback','reason=missing_structure');}else{showPending(!post?'missing_post':'missing_tree');scan(300);}return;}" +
				"var titleSource=post.querySelector('[slot=title]');" +
				"var title=text(titleSource?titleSource.textContent:post.getAttribute('post-title'));" +
				"if(!title){showPending('missing_title');scan(300);return;}" +
				"var comments=Array.prototype.slice.call(tree.querySelectorAll('shreddit-comment'));" +
				"var expected=parseInt(post.getAttribute('comment-count')||'0',10)||0;" +
				"if(expected>0&&comments.length===0){showPending('missing_comments');scan(300);return;}" +
				"var contentKey=title;for(var n=0;n<comments.length;n++){var body=own(comments[n],'[slot=comment]');" +
				"contentKey+='|'+comments[n].getAttribute('thingid')+'|'+comments[n].getAttribute('score')+'|'+" +
				"(body?text(body.textContent):'');}" +
				"var moreTarget=findMoreTarget(tree);state.moreTarget=moreTarget;" +
				"var key=location.pathname+'|'+post.getAttribute('id')+'|'+comments.length+'|'+digest(contentKey)+'|'+!!moreTarget+" +
				"'|'+!!window.__slooopRedditTranslationEnabled;if(key===state.key)return;state.key=key;" +
				"var postBox=shadow.querySelector('.post');postBox.hidden=false;postBox.replaceChildren();" +
				"shadow.querySelector('.comments-title').hidden=false;" +
				"var postMeta=element('div','post-meta',text(post.getAttribute('subreddit-prefixed-name'))+' · u/'+" +
				"text(post.getAttribute('author'))+' · '+text((post.querySelector('[slot=credit-bar] time')||{}).textContent)+" +
				"' · '+text(post.getAttribute('score')));postBox.appendChild(postMeta);" +
				"postBox.appendChild(element('h1','post-title',title));var bodySource=post.querySelector('[slot=text-body]');" +
				"if(bodySource)postBox.appendChild(cleanContent(bodySource));var contentHref=safeUrl(post.getAttribute('content-href'));" +
				"if(contentHref){var link=element('a','post-link',contentHref);link.href=contentHref;link.rel='noopener noreferrer';postBox.appendChild(link);}" +
				"var list=shadow.querySelector('.comments');list.replaceChildren();var rendered=0;" +
				"for(var i=0;i<comments.length&&i<500;i++){var comment=comments[i],source=own(comment,'[slot=comment]');" +
				"var author=text(comment.getAttribute('author')),bodyText=source?text(source.textContent):'';if(!author&&!bodyText)continue;" +
				"var card=element('article','comment');var depth=Math.max(0,Math.min(8,parseInt(comment.getAttribute('depth')||'0',10)||0));" +
				"card.dataset.depth=depth;card.style.marginInlineStart=(depth*8)+'px';var meta=element('div','comment-meta');" +
				"meta.appendChild(element('span','author',author?'u/'+author:'[deleted]'));var metaSource=own(comment,'[slot=commentMeta]');" +
				"var time=metaSource?metaSource.querySelector('time'):null;if(time)meta.appendChild(element('span','time','· '+text(time.textContent)));" +
				"var score=text(comment.getAttribute('score'));if(score)meta.appendChild(element('span','score','· '+score));card.appendChild(meta);" +
				"if(source){var clean=cleanContent(source);clean.className='content comment-body';card.appendChild(clean);}" +
				"var actions=element('div','comment-actions'),button=element('button','',labels.actions);button.type='button';" +
				"var permalink=safeUrl(comment.getAttribute('permalink'))||location.href;" +
				"(function(target,targetCard,targetBody,targetLink){button.onclick=function(){" +
				"openActions(target,targetCard,targetBody,targetLink);};})(comment,card,bodyText,permalink);" +
				"actions.appendChild(button);card.appendChild(actions);" +
				"list.appendChild(card);rendered++;}" +
				"if(!rendered)list.appendChild(element('div','empty',labels.empty));var more=shadow.querySelector('.more');" +
				"more.hidden=!moreTarget;more.disabled=state.moreLoading;" +
				"more.textContent=state.moreLoading?labels.loading:labels.more;" +
				"more.onclick=function(){loadMore(moreTarget,more);};" +
				"host.hidden=false;state.pendingSince=0;state.pendingReason='';" +
				"if(!state.initialized){state.initialized=true;if(!state.userOriginal)showReader();}" +
				"else if(state.active){" +
				"document.documentElement.classList.add(ACTIVE);host.removeAttribute('data-mode');}" +
				"log('render','comments='+rendered+' expected='+expected+' more='+!!moreTarget);" +
				"}catch(e){document.documentElement.classList.remove(ACTIVE);host.hidden=true;state.active=false;" +
				"log('fallback','reason=exception name='+(e&&e.name?e.name:'unknown'));}}" +
				"function scan(delay){clearTimeout(state.timer);state.timer=setTimeout(render,delay==null?120:delay);}" +
				"var observer=new MutationObserver(function(changes){for(var i=0;i<changes.length;i++){" +
				"var target=changes[i].target;if(target!==host&&!host.contains(target)){scan();return;}}});" +
				"observer.observe(document.documentElement,{childList:true,subtree:true,characterData:true,attributes:true," +
				"attributeFilter:['score','comment-count','aria-hidden']});" +
				"function navigationIntent(event){var path=event.composedPath?event.composedPath():[],anchor=null;" +
				"for(var i=0;i<path.length;i++)if(path[i]&&path[i].matches&&path[i].matches('a[href]')){" +
				"anchor=path[i];break;}if(!anchor&&event.target&&event.target.closest)anchor=event.target.closest('a[href]');" +
				"if(!anchor)return;try{var url=new URL(anchor.href,location.href),hostName=url.hostname.toLowerCase();" +
				"if((hostName==='reddit.com'||hostName.endsWith('.reddit.com'))&&/\\/comments\\//.test(url.pathname)){" +
				"state.path=url.pathname;state.key='';state.pendingSince=Date.now();state.userOriginal=false;" +
				"state.initialized=false;showPending('navigation');}}catch(e){}}" +
				"function historyNavigation(){state.path='';state.key='';state.pendingSince=Date.now();" +
				"setTimeout(render,0);}" +
				"document.addEventListener('click',navigationIntent,true);window.addEventListener('popstate',historyNavigation);" +
				"window.__slooopRedditHybridReader={version:VERSION,scan:scan,showReader:showReader,showOriginal:showOriginal,observer:observer," +
				"moreObserver:moreObserver," +
				"navigationIntent:navigationIntent,historyNavigation:historyNavigation};" +
				"log('installed','version='+VERSION);if(/\\/comments\\//.test(location.pathname)){state.path=location.pathname;" +
				"state.pendingSince=Date.now();showPending('initial');}render();})();";
	}

	private static String color(int color) {
		return String.format(Locale.US, "#%06x", color & 0x00ffffff);
	}

	private static String colorWithAlpha(int color, int alpha) {
		return String.format(Locale.US, "rgba(%d,%d,%d,%.3f)", Color.red(color), Color.green(color),
				Color.blue(color), alpha / 255f);
	}

	private void setTranslationEnabled(boolean enabled) {
		translationEnabled = enabled;
		handler.removeCallbacks(translationRescanRunnable);
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
			handler.postDelayed(translationRescanRunnable, delay);
		}
	}

	private void requestRedditTranslation() {
		WebView view = webView;
		if (!translationEnabled || translationRequestPending || view == null || !isResumed() ||
				!TranslationController.isReadyForDirection(TranslationModel.Direction.EN_RU)) {
			return;
		}
		int generation = pageLoadGeneration;
		String script = "(function(){window.__slooopRedditTranslationEnabled=true;" +
				"var q='shreddit-post [slot=title],shreddit-post [slot=text-body]," +
				"shreddit-comment [slot=comment]',n=document.querySelectorAll(q);" +
				"window.__slooopRedditTranslationCounter=window.__slooopRedditTranslationCounter||0;" +
				"for(var i=0;i<n.length;i++){var e=n[i];if(e.__slooopTranslatedHtml!=null){" +
				"e.innerHTML=e.__slooopTranslatedHtml;continue;}if(e.__slooopTranslationPending||" +
				"e.__slooopTranslationFailed)continue;" +
				"var text=(e.innerText||e.textContent||'').trim();if(!text)continue;" +
				"if(e.__slooopOriginalHtml==null)e.__slooopOriginalHtml=e.innerHTML;" +
				"var id=e.getAttribute('data-slooop-translation-id');if(!id){id='t'+" +
				"(++window.__slooopRedditTranslationCounter);e.setAttribute('data-slooop-translation-id',id);}" +
				"e.__slooopTranslationPending=true;return JSON.stringify({id:id,html:e.__slooopOriginalHtml});}" +
				"return null;})()";
		translationRequestPending = true;
		translationRequestGeneration = generation;
		view.evaluateJavascript(script, value -> {
			if (!isCurrentPageLoad(view, generation) || !translationEnabled) {
				finishTranslationRequest(generation);
				return;
			}
			JSONObject item = decodeJavascriptObject(value);
			if (item == null) {
				finishTranslationRequest(generation);
				scheduleTranslationScan(TRANSLATION_RESCAN_DELAY);
				return;
			}
			String id = item.optString("id", null);
			String html = item.optString("html", null);
			if (StringUtils.isEmpty(id) || html == null) {
				finishTranslationRequest(generation);
				scheduleTranslationScan(TRANSLATION_RESCAN_DELAY);
				return;
			}
			TranslationController.getInstance().requestTranslation(TranslationModel.Direction.EN_RU, "", html,
					(translatedSubject, translatedHtml, error) -> applyRedditTranslation(view, generation,
							id, translatedHtml));
		});
	}

	private void applyRedditTranslation(WebView view, int generation, String id, String translatedHtml) {
		if (!isCurrentPageLoad(view, generation)) {
			finishTranslationRequest(generation);
			return;
		}
		String selector = "[data-slooop-translation-id=" + JSONObject.quote(id) + "]";
		String script;
		if (translatedHtml != null) {
			script = "(function(){var e=document.querySelector(" + JSONObject.quote(selector) + ");" +
					"if(!e)return;e.__slooopTranslationPending=false;e.__slooopTranslatedHtml=" +
					JSONObject.quote(translatedHtml) + ";if(window.__slooopRedditTranslationEnabled)" +
					"e.innerHTML=e.__slooopTranslatedHtml;})();";
		} else {
			script = "(function(){var e=document.querySelector(" + JSONObject.quote(selector) + ");" +
					"if(e){e.__slooopTranslationPending=false;e.__slooopTranslationFailed=true;}})();";
		}
		view.evaluateJavascript(script, ignored -> {
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
			if (readerStyleScript != null) {
				view.evaluateJavascript(readerStyleScript, ignored -> {
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
		});
	}

	private class RedditWebViewClient extends WebViewClient {
		@Override
		public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
			return request.isForMainFrame() && !isAllowedRedditPage(request.getUrl()) &&
					openExternal(request.getUrl());
		}

		@SuppressWarnings("deprecation")
		@Override
		public boolean shouldOverrideUrlLoading(WebView view, String url) {
			Uri uri = Uri.parse(url);
			return !isAllowedRedditPage(uri) && openExternal(uri);
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
