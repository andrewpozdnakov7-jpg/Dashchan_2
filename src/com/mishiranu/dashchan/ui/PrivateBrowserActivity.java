package com.mishiranu.dashchan.ui;

import android.annotation.SuppressLint;
import android.app.ActionBar;
import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.net.http.SslError;
import android.os.Bundle;
import android.os.Process;
import android.view.Gravity;
import android.view.Menu;
import android.view.MenuItem;
import android.view.ViewGroup;
import android.view.Window;
import android.webkit.CookieManager;
import android.webkit.DownloadListener;
import android.webkit.SslErrorHandler;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebStorage;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.webkit.WebViewDatabase;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.Toast;
import android.widget.Toolbar;
import androidx.annotation.NonNull;
import chan.util.StringUtils;
import com.mishiranu.dashchan.R;
import com.mishiranu.dashchan.content.LocaleManager;
import com.mishiranu.dashchan.content.Preferences;
import com.mishiranu.dashchan.util.AndroidUtils;
import com.mishiranu.dashchan.util.NavigationUtils;
import com.mishiranu.dashchan.util.ResourceUtils;
import com.mishiranu.dashchan.util.WebViewUtils;
import com.mishiranu.dashchan.widget.ClickableToast;
import com.mishiranu.dashchan.widget.ThemeEngine;
import com.mishiranu.dashchan.widget.ViewFactory;

/**
 * An ordinary-looking browser backed by a dedicated WebView process and data directory. Its browsing data is
 * cleared before the first page is loaded and again when the activity is closed, so it never touches forum login
 * sessions stored by WebViews in the main process.
 */
public class PrivateBrowserActivity extends StateActivity implements DownloadListener {
	private static final String EXTRA_URI = "uri";

	public static Intent createIntent(Context context, Uri uri) {
		Intent intent = new Intent(context, PrivateBrowserActivity.class).putExtra(EXTRA_URI, uri);
		if (!(context instanceof Activity)) {
			intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
		}
		return intent;
	}

	private WebView webView;
	private ProgressBar progressBar;
	private ViewFactory.ToolbarHolder toolbarHolder;
	private boolean sessionReady;
	private int sessionGeneration;

	@Override
	protected void attachBaseContext(Context newBase) {
		super.attachBaseContext(ThemeEngine.attach(LocaleManager.getInstance().apply(newBase)));
	}

	@SuppressLint("SetJavaScriptEnabled")
	@Override
	protected void onCreate(Bundle savedInstanceState) {
		requestWindowFeature(Window.FEATURE_NO_TITLE);
		ThemeEngine.applyTheme(this);
		super.onCreate(savedInstanceState);

		createLayout();
		ClickableToast.register(this);
		WebSettings settings = webView.getSettings();
		WebViewUtils.configureCommonSettings(settings);
		settings.setBuiltInZoomControls(true);
		settings.setDisplayZoomControls(false);
		settings.setUseWideViewPort(true);
		settings.setLoadWithOverviewMode(true);
		settings.setJavaScriptEnabled(true);
		settings.setJavaScriptCanOpenWindowsAutomatically(false);
		settings.setSupportMultipleWindows(false);
		settings.setDomStorageEnabled(true);
		// On supported Android versions, form filling is controlled by the Autofill framework.
		webView.setImportantForAutofill(WebView.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS);
		webView.setWebViewClient(new PrivateWebViewClient());
		webView.setWebChromeClient(new PrivateWebChromeClient());
		webView.setDownloadListener(this);

		CookieManager cookieManager = CookieManager.getInstance();
		cookieManager.setAcceptCookie(false);
		cookieManager.setAcceptThirdPartyCookies(webView, false);

		boolean restored = false;
		if (savedInstanceState != null) {
			// Keep first-party session cookies available while Android recreates the same private window.
			cookieManager.setAcceptCookie(true);
			restored = webView.restoreState(savedInstanceState) != null;
		}
		if (restored) {
			sessionReady = true;
		} else {
			startFreshSession(getIntent());
		}
	}

	@Override
	protected void onNewIntent(Intent intent) {
		super.onNewIntent(intent);
		setIntent(intent);
		startFreshSession(intent);
	}

	private void startFreshSession(Intent intent) {
		CookieManager.getInstance().setAcceptCookie(false);
		Uri uri = AndroidUtils.getParcelableExtra(intent, EXTRA_URI, Uri.class);
		if (!BrowserFragment.isSupportedUri(uri)) {
			Toast.makeText(this, R.string.unknown_address, Toast.LENGTH_SHORT).show();
			finish();
			return;
		}
		sessionReady = false;
		int generation = ++sessionGeneration;
		clearBrowsingData(() -> {
			if (webView != null && !isFinishing() && generation == sessionGeneration) {
				sessionReady = true;
				CookieManager manager = CookieManager.getInstance();
				manager.setAcceptCookie(true);
				manager.setAcceptThirdPartyCookies(webView, false);
				webView.loadUrl(uri.toString());
			}
		});
	}

	private void createLayout() {
		LinearLayout root = new LinearLayout(this);
		root.setOrientation(LinearLayout.VERTICAL);
		setContentView(root);

		getLayoutInflater().inflate(R.layout.widget_toolbar, root, true);
		Toolbar toolbar = findViewById(R.id.toolbar);
		setActionBar(toolbar);
		setTitle(null);
		toolbarHolder = ViewFactory.addToolbarTitle(toolbar);
		toolbarHolder.update(getString(R.string.web_browser), null);
		ActionBar actionBar = getActionBar();
		if (actionBar != null) {
			actionBar.setDisplayHomeAsUpEnabled(true);
		}

		FrameLayout content = new FrameLayout(this);
		root.addView(content, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
		webView = new WebView(this);
		content.addView(webView, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
				ViewGroup.LayoutParams.MATCH_PARENT));
		progressBar = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
		progressBar.setMax(100);
		int height = (int) (3f * ResourceUtils.obtainDensity(this) + 0.5f);
		FrameLayout.LayoutParams progressParams = new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
				height, Gravity.TOP);
		content.addView(progressBar, progressParams);
	}

	@Override
	public boolean onCreateOptionsMenu(Menu menu) {
		menu.add(0, R.id.menu_reload, 0, R.string.reload)
				.setIcon(ResourceUtils.getActionBarIcon(this, R.attr.iconActionRefresh))
				.setShowAsAction(MenuItem.SHOW_AS_ACTION_IF_ROOM);
		menu.add(0, R.id.menu_copy_link, 0, R.string.copy_link);
		menu.add(0, R.id.menu_share_link, 0, R.string.share_link);
		return true;
	}

	@Override
	public boolean onOptionsItemSelected(@NonNull MenuItem item) {
		if (item.getItemId() == android.R.id.home) {
			finish();
			return true;
		} else if (item.getItemId() == R.id.menu_reload) {
			if (sessionReady) {
				webView.reload();
			}
			return true;
		} else if (item.getItemId() == R.id.menu_copy_link) {
			StringUtils.copyToClipboard(this, webView.getUrl());
			return true;
		} else if (item.getItemId() == R.id.menu_share_link) {
			String url = webView.getUrl();
			if (!StringUtils.isEmpty(url)) {
				NavigationUtils.shareLink(this, null, Uri.parse(url));
			}
			return true;
		}
		return super.onOptionsItemSelected(item);
	}

	@Override
	protected void onSystemBackPressed() {
		if (webView != null && webView.canGoBack()) {
			webView.goBack();
		} else {
			finish();
		}
	}

	@Override
	protected void onSaveInstanceState(@NonNull Bundle outState) {
		super.onSaveInstanceState(outState);
		if (webView != null && sessionReady) {
			webView.saveState(outState);
		}
	}

	@Override
	protected void onPause() {
		super.onPause();
		if (webView != null) {
			webView.onPause();
			CookieManager.getInstance().flush();
		}
	}

	@Override
	protected void onResume() {
		super.onResume();
		if (webView != null) {
			webView.onResume();
		}
	}

	@Override
	protected void onDestroy() {
		sessionGeneration++;
		WebView currentWebView = webView;
		webView = null;
		if (currentWebView != null) {
			currentWebView.stopLoading();
			currentWebView.setDownloadListener(null);
			currentWebView.setWebChromeClient(null);
			currentWebView.setWebViewClient(null);
			if (!isChangingConfigurations()) {
				clearBrowsingData(currentWebView, () -> Process.killProcess(Process.myPid()));
			}
			currentWebView.destroy();
		}
		progressBar = null;
		toolbarHolder = null;
		super.onDestroy();
	}

	private void clearBrowsingData(Runnable callback) {
		clearBrowsingData(webView, callback);
	}

	private void clearBrowsingData(WebView target, Runnable callback) {
		CookieManager cookieManager = CookieManager.getInstance();
		cookieManager.setAcceptCookie(false);
		if (target != null) {
			target.stopLoading();
			target.clearHistory();
			target.clearFormData();
			target.clearSslPreferences();
			target.clearCache(true);
		}
		WebStorage.getInstance().deleteAllData();
		WebViewDatabase database = WebViewDatabase.getInstance(this);
		database.clearHttpAuthUsernamePassword();
		cookieManager.removeAllCookies(removed -> {
			cookieManager.flush();
			if (callback != null) {
				callback.run();
			}
		});
	}

	@Override
	public void onDownloadStart(String url, String userAgent, String contentDisposition, String mimeType,
			long contentLength) {
		openExternal(Uri.parse(url));
	}

	private void openExternal(Uri uri) {
		Intent intent = new Intent(Intent.ACTION_VIEW, uri).addCategory(Intent.CATEGORY_BROWSABLE)
				.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
		try {
			startActivity(intent);
		} catch (ActivityNotFoundException | SecurityException e) {
			ClickableToast.show(R.string.unknown_address);
		}
	}

	private class PrivateWebViewClient extends WebViewClient {
		@Override
		public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
			return request.isForMainFrame() && handleUrlLoading(view, request.getUrl());
		}

		@SuppressWarnings("deprecation")
		@Override
		public boolean shouldOverrideUrlLoading(WebView view, String url) {
			return handleUrlLoading(view, Uri.parse(url));
		}

		private boolean handleUrlLoading(WebView view, Uri uri) {
			if (BrowserFragment.isSupportedUri(uri)) {
				view.loadUrl(uri.toString());
			} else {
				openExternal(uri);
			}
			return true;
		}

		@Override
		public void onPageFinished(WebView view, String url) {
			String title = view.getTitle();
			if (toolbarHolder != null) {
				toolbarHolder.update(StringUtils.isEmptyOrWhitespace(title)
						? getString(R.string.web_browser) : title, null);
			}
		}

		@Override
		public void onReceivedSslError(WebView view, SslErrorHandler handler, SslError error) {
			if (Preferences.isVerifyCertificate()) {
				ClickableToast.show(R.string.invalid_certificate);
				super.onReceivedSslError(view, handler, error);
			} else {
				handler.proceed();
			}
		}
	}

	private class PrivateWebChromeClient extends WebChromeClient {
		@Override
		public void onProgressChanged(WebView view, int newProgress) {
			if (progressBar != null) {
				progressBar.setProgress(newProgress);
				progressBar.setVisibility(newProgress >= 100 ? ProgressBar.GONE : ProgressBar.VISIBLE);
			}
		}
	}
}
