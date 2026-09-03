package com.mishiranu.dashchan.ui;

import android.annotation.SuppressLint;
import android.app.AlertDialog;
import android.content.ActivityNotFoundException;
import android.content.Intent;
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
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.EditText;
import android.widget.FrameLayout;
import androidx.annotation.NonNull;
import chan.util.StringUtils;
import com.mishiranu.dashchan.R;
import com.mishiranu.dashchan.content.Preferences;
import com.mishiranu.dashchan.util.NavigationUtils;
import com.mishiranu.dashchan.util.ViewUtils;
import com.mishiranu.dashchan.util.WebViewUtils;
import com.mishiranu.dashchan.widget.ClickableToast;
import com.mishiranu.dashchan.widget.ExpandedLayout;
import com.mishiranu.dashchan.widget.ThemeEngine;
import java.util.Locale;
import java.util.UUID;

/**
 * A visible, user-driven browser for public Reddit pages.
 *
 * <p>This fragment deliberately does not expose a JavaScript bridge, export DOM content to native code, call
 * Reddit APIs or internal endpoints, click controls, preload discussions, or copy Reddit content into Slooop
 * storage. A local stylesheet only changes presentation inside the visible page. Reddit's own scripts may load
 * content after a user action in the same way as they do in a regular browser.</p>
 */
public class RedditWebReaderFragment extends ContentFragment {
	public static final String HOME_URL = "https://www.reddit.com/";
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
	private static final String EXTRA_START_URL = "startUrl";

	private WebView webView;
	private View progressView;
	private String navigationDrawerLocker;
	private String readerStyleScript;

	public static RedditWebReaderFragment newInstance(String url) {
		RedditWebReaderFragment fragment = new RedditWebReaderFragment();
		Bundle arguments = new Bundle();
		arguments.putString(EXTRA_START_URL, url);
		fragment.setArguments(arguments);
		return fragment;
	}

	@Override
	public View onCreateView(@NonNull LayoutInflater inflater, ViewGroup container, Bundle savedInstanceState) {
		ExpandedLayout layout = new ExpandedLayout(container.getContext(), true);
		webView = new WebView(layout.getContext().getApplicationContext());
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
		readerStyleScript = Preferences.isRedditWebReaderStyleEnabled() ? buildReaderStyleScript() : null;

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
		CookieManager.getInstance().setAcceptThirdPartyCookies(webView, false);
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
		((FragmentHandler) requireActivity()).setTitleSubtitle(getString(R.string.forum_reddit), null);
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
			webView.setWebChromeClient(null);
			webView.setWebViewClient(null);
			ViewUtils.removeFromParent(webView);
			webView.destroy();
			webView = null;
		}
		progressView = null;
		readerStyleScript = null;
		super.onDestroyView();
	}

	@Override
	public void onCreateOptionsMenu(Menu menu, boolean primary) {
		menu.add(0, R.id.menu_reload, 0, R.string.reload)
				.setIcon(((FragmentHandler) requireActivity()).getActionBarIcon(R.attr.iconActionRefresh))
				.setShowAsAction(MenuItem.SHOW_AS_ACTION_IF_ROOM);
		menu.add(0, R.id.menu_open_reddit_link, 0, R.string.open_reddit_link);
		menu.add(0, R.id.menu_copy_link, 0, R.string.copy_link);
		menu.add(0, R.id.menu_share_link, 0, R.string.share_link);
	}

	@Override
	public boolean onOptionsItemSelected(MenuItem item) {
		if (webView == null) {
			return false;
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
				"html.slooop-reddit-reader shreddit-post [slot=\"text-body\"]," +
				"html.slooop-reddit-reader shreddit-post [slot=\"text-body\"] p{color:" +
				color(textPrimary) + "!important;line-height:1.45!important}" +
				"html.slooop-reddit-reader shreddit-comment{color:" + color(textPrimary) + "!important}" +
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
		public void onPageFinished(WebView view, String url) {
			if (readerStyleScript != null && isAllowedRedditPage(Uri.parse(url))) {
				view.evaluateJavascript(readerStyleScript, null);
			}
			((FragmentHandler) requireActivity()).setTitleSubtitle(getString(R.string.forum_reddit), null);
			notifyBackNavigationChanged();
		}

		@Override
		public void onReceivedSslError(WebView view, SslErrorHandler handler, SslError error) {
			handler.cancel();
			ClickableToast.show(R.string.invalid_certificate);
		}
	}
}
