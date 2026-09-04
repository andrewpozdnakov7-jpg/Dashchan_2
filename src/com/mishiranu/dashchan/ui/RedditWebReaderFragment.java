package com.mishiranu.dashchan.ui;

import android.annotation.SuppressLint;
import android.app.AlertDialog;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.net.Uri;
import android.net.http.SslError;
import android.os.Bundle;
import android.text.InputType;
import android.view.LayoutInflater;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.webkit.CookieManager;
import android.webkit.SslErrorHandler;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
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
import com.mishiranu.dashchan.util.NavigationUtils;
import com.mishiranu.dashchan.util.ViewUtils;
import com.mishiranu.dashchan.util.WebViewUtils;
import com.mishiranu.dashchan.widget.ClickableToast;
import com.mishiranu.dashchan.widget.ExpandedLayout;
import com.mishiranu.dashchan.widget.ThemeEngine;
import java.util.Locale;
import java.util.UUID;

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
 * regular browser.</p>
 */
public class RedditWebReaderFragment extends ContentFragment {
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
	private static final String READ_SIGNED_IN_STATE_SCRIPT = "(function(){var app=" +
			"document.querySelector('shreddit-app');if(!app)return null;return " +
			"app.getAttribute('user-logged-in')==='true'||!!document.querySelector('[is-user-logged-in]');})()";

	private WebView webView;
	private View progressView;
	private String navigationDrawerLocker;
	private String readerStyleScript;
	private boolean authorizationMode;
	private MenuItem finishAuthorizationMenuItem;
	private int pageLoadGeneration;
	private String lastRecordedPageUrl;

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

		WebSettings settings = webView.getSettings();
		WebViewUtils.configureCommonSettings(settings);
		settings.setJavaScriptEnabled(true);
		settings.setDomStorageEnabled(true);
		settings.setBuiltInZoomControls(true);
		settings.setDisplayZoomControls(false);
		settings.setSupportMultipleWindows(false);
		settings.setJavaScriptCanOpenWindowsAutomatically(false);
		settings.setMediaPlaybackRequiresUserGesture(true);
		settings.setMixedContentMode(WebSettings.MIXED_CONTENT_NEVER_ALLOW);
		CookieManager cookieManager = CookieManager.getInstance();
		cookieManager.setAcceptCookie(true);
		cookieManager.setAcceptThirdPartyCookies(webView, false);
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
		});
		if (savedInstanceState != null) {
			webView.restoreState(savedInstanceState);
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
	}

	@Override
	public void onPause() {
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
	}

	@Override
	public void onDestroyView() {
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
		finishAuthorizationMenuItem = null;
		super.onDestroyView();
	}

	@Override
	public void onCreateOptionsMenu(Menu menu, boolean primary) {
		menu.add(0, R.id.menu_reload, 0, R.string.reload)
				.setIcon(((FragmentHandler) requireActivity()).getActionBarIcon(R.attr.iconActionRefresh))
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
		return false;
	}

	@Override
	public boolean onBackPressed() {
		if (webView != null && webView.canGoBack()) {
			webView.goBack();
			return true;
		}
		return false;
	}

	@Override
	public boolean canHandleBack() {
		return webView != null && webView.canGoBack();
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

	private static String buildAppPromoSuppressionScript() {
		// Reddit can inject this dialog long after the initial page load. Inspect only mutation targets and newly
		// attached shadow roots synchronously, before the browser paints them; rescanning the entire feed would stall it.
		return "(function(){if(window.__slooopRedditPromoGuard)return;" +
				"var activeMenuHost=null,activeMenuOwners=[];" +
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
				"function menuHost(n){for(var i=0;n&&i<32;i++,n=parent(n))if(n.matches&&" +
				"n.matches('shreddit-overflow-menu'))return n;return null;}" +
				"function clearMenuLayer(){if(activeMenuHost)activeMenuHost.classList.remove(" +
				"'slooop-reddit-menu-open');for(var i=0;i<activeMenuOwners.length;i++)" +
				"activeMenuOwners[i].classList.remove('slooop-reddit-menu-owner');" +
				"activeMenuHost=null;activeMenuOwners=[];}" +
				"function setMenuLayer(host){if(!host)return;if(activeMenuHost!==host)clearMenuLayer();" +
				"activeMenuHost=host;host.classList.add('slooop-reddit-menu-open');" +
				"for(var n=host;n;n=parent(n))if(n.matches&&n.matches('shreddit-comment,shreddit-post')){" +
				"n.classList.add('slooop-reddit-menu-owner');activeMenuOwners.push(n);}}" +
				"function syncMenuLayer(){if(!activeMenuHost)return;var rs=[];roots(activeMenuHost,rs),visible=false;" +
				"for(var x=0;x<rs.length&&!visible;x++){var layers=query(rs[x]," +
				"'[role=dialog],[role=menu],faceplate-bottom-sheet');for(var j=0;j<layers.length;j++){" +
				"var r=layers[j].getBoundingClientRect(),s=getComputedStyle(layers[j]);if(s.display!=='none'&&" +
				"s.visibility!=='hidden'&&r.width>0&&r.height>0&&r.bottom>0&&r.top<innerHeight){visible=true;break;}}}" +
				"if(!visible)clearMenuLayer();}" +
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
				"function roots(root,out){out.push(root);if(root!==document)try{observer.observe(root,options);}" +
				"catch(ignored){}var all=root.querySelectorAll?root.querySelectorAll('*'):[];" +
				"for(var i=0;i<all.length;i++)if(all[i].shadowRoot)roots(all[i].shadowRoot,out);}" +
				"function scan(root){var rs=[];roots(root||document,rs),hidden=false,legitimateDialog=false;" +
				"for(var x=0;x<rs.length;x++){" +
				"var headers=query(rs[x],headerKnown);" +
				"for(var h=0;h<headers.length;h++){headers[h].style.setProperty('display','none','important');" +
				"headers[h].style.setProperty('visibility','hidden','important');}" +
				"var direct=query(rs[x],promoKnown);for(var d=0;d<direct.length;d++)hidden=hide(direct[d])||hidden;" +
				"var dialogs=query(rs[x],'dialog,[role=dialog],[role=alertdialog],rpl-dialog,rpl-dialog-sheet');" +
				"for(var j=0;j<dialogs.length;j++){var dialog=dialogs[j],text=dialog.innerText||dialog.textContent||'';" +
				"if(text.length<1600&&promoText(text))hidden=hide(dialog)||hidden;else{" +
				"var rect=dialog.getBoundingClientRect(),display=getComputedStyle(dialog).display;" +
				"if(display!=='none'&&rect.width>0&&rect.height>0){legitimateDialog=true;" +
				"var host=menuHost(dialog);if(host)setMenuLayer(host);}}}" +
				"var actions=query(rs[x],'a,button,[role=button],faceplate-tracker,[tabindex]');" +
				"for(var a=0;a<actions.length;a++)if(appAction(actions[a]))" +
				"hidden=hide(dialogFor(actions[a]))||hidden;}" +
				"if(legitimateDialog)clearUnlock();else if(hidden){unlock();setTimeout(unlock,100);" +
				"setTimeout(unlock,400);}return hidden;}" +
				"document.addEventListener('pointerdown',function(event){var path=event.composedPath?" +
				"event.composedPath():[],host=null;for(var i=0;i<path.length;i++){var n=path[i];" +
				"host=menuHost(n);if(host)break;}if(host){setMenuLayer(host);clearUnlock();}" +
				"setTimeout(syncMenuLayer,120);setTimeout(syncMenuLayer,700);},true);" +
				"var options={childList:true,subtree:true},observer=new MutationObserver(function(changes){" +
				"var targets=[];for(var i=0;i<changes.length;i++)if(changes[i].addedNodes.length&&" +
				"targets.indexOf(changes[i].target)<0)targets.push(changes[i].target);" +
				"for(var i=0;i<targets.length;i++)scan(targets[i]);});" +
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
				"html.slooop-reddit-reader :is(shreddit-comment,shreddit-post).slooop-reddit-menu-owner{" +
				"contain:none!important;position:relative!important;z-index:2147483000!important}" +
				"html.slooop-reddit-reader shreddit-overflow-menu.slooop-reddit-menu-open{" +
				"position:relative!important;z-index:2147483001!important}" +
				"html.slooop-reddit-reader shreddit-comment>details{box-sizing:border-box!important;background:" +
				color(theme.card) + "!important;color:" + color(textPrimary) +
				"!important;border-inline-start:2px solid " + colorWithAlpha(theme.accent, 0x78) +
				"!important;border-radius:7px!important;margin:4px 5px!important;padding:5px 8px 6px 4px!important}" +
				"html.slooop-reddit-reader shreddit-comment [slot=\"commentMeta\"]{color:" +
				color(textSecondary) + "!important;font-size:12px!important}" +
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
				"color:" + color(textSecondary) + "!important;min-height:28px!important;opacity:.9}" +
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

	private static String color(int color) {
		return String.format(Locale.US, "#%06x", color & 0x00ffffff);
	}

	private static String colorWithAlpha(int color, int alpha) {
		return String.format(Locale.US, "rgba(%d,%d,%d,%.3f)", Color.red(color), Color.green(color),
				Color.blue(color), alpha / 255f);
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
				view.evaluateJavascript(readerStyleScript, ignored -> revealPage(view, generation));
			} else {
				revealPage(view, generation);
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
			view.setVisibility(View.INVISIBLE);
		}

		@Override
		public void onPageCommitVisible(WebView view, String url) {
			String currentUrl = view.getUrl();
			if (url != null && url.equals(currentUrl)) {
				applyPagePresentation(view, url, pageLoadGeneration);
			}
		}

		@Override
		public void onPageFinished(WebView view, String url) {
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
			handler.cancel();
			ClickableToast.show(R.string.invalid_certificate);
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
