package com.mishiranu.dashchan.ui;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.app.PictureInPictureParams;
import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.res.Configuration;
import android.graphics.Color;
import android.net.Uri;
import android.net.http.SslError;
import android.os.Build;
import android.os.Bundle;
import android.util.Rational;
import android.view.View;
import android.view.ViewGroup;
import android.webkit.CookieManager;
import android.webkit.RenderProcessGoneDetail;
import android.webkit.SslErrorHandler;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceError;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.FrameLayout;
import com.mishiranu.dashchan.R;
import com.mishiranu.dashchan.util.WebViewUtils;
import com.mishiranu.dashchan.widget.ClickableToast;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/** Displays an official YouTube embed without resolving or downloading YouTube media streams. */
public class YouTubePlayerActivity extends Activity {
	private static final String EXTRA_VIDEO_ID = "videoId";
	private static final String EXTRA_ORIGINAL_URI = "originalUri";

	private FrameLayout rootView;
	private WebView webView;
	private View customView;
	private WebChromeClient.CustomViewCallback customViewCallback;
	private Uri originalUri;
	private String videoId;
	private boolean externalFallbackOpened;

	public static boolean isYouTubeUri(Uri uri) {
		return extractVideoId(uri) != null;
	}

	public static Intent createIntent(Context context, Uri uri) {
		String videoId = extractVideoId(uri);
		if (videoId == null) {
			throw new IllegalArgumentException("Unsupported YouTube URI");
		}
		return new Intent(context, YouTubePlayerActivity.class)
				.putExtra(EXTRA_VIDEO_ID, videoId)
				.putExtra(EXTRA_ORIGINAL_URI, uri.toString());
	}

	public static Intent createExternalPlayerIntent(Uri uri) {
		String videoId = extractVideoId(uri);
		if (videoId == null) {
			throw new IllegalArgumentException("Unsupported YouTube URI");
		}
		return new Intent(Intent.ACTION_VIEW, Uri.parse("vnd.youtube:" + videoId));
	}

	private static String extractVideoId(Uri uri) {
		if (uri == null || !"https".equalsIgnoreCase(uri.getScheme())) {
			return null;
		}
		String host = uri.getHost();
		if (host == null) {
			return null;
		}
		host = host.toLowerCase(Locale.US);
		String candidate = null;
		if (host.equals("youtu.be")) {
			List<String> segments = uri.getPathSegments();
			candidate = !segments.isEmpty() ? segments.get(0) : null;
		} else if (host.equals("youtube.com") || host.endsWith(".youtube.com") ||
				host.equals("youtube-nocookie.com") || host.endsWith(".youtube-nocookie.com")) {
			List<String> segments = uri.getPathSegments();
			if (!segments.isEmpty() && "watch".equals(segments.get(0))) {
				candidate = uri.getQueryParameter("v");
			} else if (segments.size() >= 2 && ("shorts".equals(segments.get(0)) ||
					"embed".equals(segments.get(0)) || "live".equals(segments.get(0)))) {
				candidate = segments.get(1);
			}
		}
		return candidate != null && candidate.matches("[0-9A-Za-z_-]{11}") ? candidate : null;
	}

	@SuppressLint("SetJavaScriptEnabled")
	@Override
	protected void onCreate(Bundle savedInstanceState) {
		super.onCreate(savedInstanceState);
		videoId = getIntent().getStringExtra(EXTRA_VIDEO_ID);
		String original = getIntent().getStringExtra(EXTRA_ORIGINAL_URI);
		originalUri = original != null ? Uri.parse(original) : null;
		if (videoId == null || !videoId.equals(extractVideoId(originalUri))) {
			finish();
			return;
		}

		getWindow().setStatusBarColor(Color.BLACK);
		getWindow().setNavigationBarColor(Color.BLACK);
		rootView = new FrameLayout(this);
		rootView.setBackgroundColor(Color.BLACK);
		setContentView(rootView);

		webView = new WebView(this);
		webView.setBackgroundColor(Color.BLACK);
		webView.setKeepScreenOn(true);
		rootView.addView(webView, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
				ViewGroup.LayoutParams.MATCH_PARENT));

		WebSettings settings = webView.getSettings();
		WebViewUtils.configureCommonSettings(settings);
		settings.setJavaScriptEnabled(true);
		settings.setDomStorageEnabled(true);
		settings.setMediaPlaybackRequiresUserGesture(false);
		settings.setAllowFileAccess(false);
		settings.setAllowContentAccess(false);
		settings.setSupportMultipleWindows(false);
		settings.setJavaScriptCanOpenWindowsAutomatically(false);
		settings.setMixedContentMode(WebSettings.MIXED_CONTENT_NEVER_ALLOW);
		CookieManager cookieManager = CookieManager.getInstance();
		cookieManager.setAcceptCookie(true);
		cookieManager.setAcceptThirdPartyCookies(webView, false);
		webView.setWebViewClient(new PlayerWebViewClient());
		webView.setWebChromeClient(new PlayerWebChromeClient());

		updatePictureInPictureParams();
		String applicationIdentity = "https://" + getPackageName();
		webView.loadUrl("https://www.youtube-nocookie.com/embed/" + videoId +
				"?autoplay=1&playsinline=1&rel=0", Collections.singletonMap("Referer", applicationIdentity));
	}

	private void updatePictureInPictureParams() {
		PictureInPictureParams.Builder builder = new PictureInPictureParams.Builder()
				.setAspectRatio(new Rational(16, 9));
		if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
			builder.setAutoEnterEnabled(true);
			builder.setSeamlessResizeEnabled(true);
		}
		setPictureInPictureParams(builder.build());
	}

	private boolean enterPictureInPicture() {
		if (isFinishing() || isInPictureInPictureMode() || !getPackageManager()
				.hasSystemFeature(PackageManager.FEATURE_PICTURE_IN_PICTURE)) {
			return false;
		}
		try {
			return enterPictureInPictureMode(new PictureInPictureParams.Builder()
					.setAspectRatio(new Rational(16, 9)).build());
		} catch (IllegalArgumentException | IllegalStateException ignored) {
			// The user may have disabled PiP for Slooop in Android settings.
			return false;
		}
	}

	@Override
	protected void onUserLeaveHint() {
		enterPictureInPicture();
		super.onUserLeaveHint();
	}

	@Override
	protected void onResume() {
		super.onResume();
		if (webView != null) {
			webView.onResume();
		}
	}

	@Override
	protected void onPause() {
		if (webView != null && !isInPictureInPictureMode()) {
			webView.onPause();
		}
		super.onPause();
	}

	@Override
	public void onPictureInPictureModeChanged(boolean inPictureInPictureMode, Configuration newConfig) {
		super.onPictureInPictureModeChanged(inPictureInPictureMode, newConfig);
		if (!inPictureInPictureMode && webView != null) {
			webView.onResume();
		}
	}

	@Override
	public void onBackPressed() {
		if (customView != null) {
			hideCustomView();
		} else if (!enterPictureInPicture()) {
			finish();
		}
	}

	private void openOriginalExternally() {
		if (externalFallbackOpened || originalUri == null) {
			return;
		}
		externalFallbackOpened = true;
		try {
			startActivity(new Intent(Intent.ACTION_VIEW, originalUri).addCategory(Intent.CATEGORY_BROWSABLE));
		} catch (ActivityNotFoundException | SecurityException e) {
			ClickableToast.show(R.string.unknown_address);
		}
		finish();
	}

	private static boolean isEmbedUri(Uri uri, String videoId) {
		if (uri == null || !"https".equalsIgnoreCase(uri.getScheme())) {
			return false;
		}
		String host = uri.getHost();
		String path = uri.getPath();
		if (host == null || path == null) {
			return false;
		}
		host = host.toLowerCase(Locale.US);
		return (host.equals("youtube-nocookie.com") || host.endsWith(".youtube-nocookie.com") ||
				host.equals("youtube.com") || host.endsWith(".youtube.com")) &&
				path.equals("/embed/" + videoId);
	}

	private void hideCustomView() {
		if (customView != null) {
			rootView.removeView(customView);
			customView = null;
			if (webView != null) {
				webView.setVisibility(View.VISIBLE);
			}
		}
		if (customViewCallback != null) {
			customViewCallback.onCustomViewHidden();
			customViewCallback = null;
		}
	}

	@Override
	protected void onDestroy() {
		hideCustomView();
		if (webView != null) {
			webView.stopLoading();
			webView.setWebChromeClient(null);
			webView.setWebViewClient(null);
			rootView.removeView(webView);
			webView.destroy();
			webView = null;
		}
		rootView = null;
		super.onDestroy();
	}

	private class PlayerWebViewClient extends WebViewClient {
		@Override
		public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
			if (!request.isForMainFrame() || isEmbedUri(request.getUrl(), videoId)) {
				return false;
			}
			openOriginalExternally();
			return true;
		}

		@SuppressWarnings("deprecation")
		@Override
		public boolean shouldOverrideUrlLoading(WebView view, String url) {
			Uri uri = Uri.parse(url);
			if (isEmbedUri(uri, videoId)) {
				return false;
			}
			openOriginalExternally();
			return true;
		}

		@Override
		public void onReceivedSslError(WebView view, SslErrorHandler handler, SslError error) {
			handler.cancel();
			ClickableToast.show(R.string.invalid_certificate);
			finish();
		}

		@Override
		public void onReceivedError(WebView view, WebResourceRequest request, WebResourceError error) {
			if (request.isForMainFrame()) {
				openOriginalExternally();
			}
		}

		@Override
		public boolean onRenderProcessGone(WebView view, RenderProcessGoneDetail detail) {
			rootView.removeView(view);
			webView = null;
			openOriginalExternally();
			return true;
		}
	}

	private class PlayerWebChromeClient extends WebChromeClient {
		@Override
		public void onShowCustomView(View view, CustomViewCallback callback) {
			if (customView != null) {
				callback.onCustomViewHidden();
				return;
			}
			customView = view;
			customViewCallback = callback;
			customView.setBackgroundColor(Color.BLACK);
			webView.setVisibility(View.INVISIBLE);
			rootView.addView(customView, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
					ViewGroup.LayoutParams.MATCH_PARENT));
		}

		@Override
		public void onHideCustomView() {
			hideCustomView();
		}
	}
}
