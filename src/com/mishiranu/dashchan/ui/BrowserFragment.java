package com.mishiranu.dashchan.ui;

import com.mishiranu.dashchan.widget.MotionDialogBuilder;
import android.annotation.SuppressLint;
import android.app.AlertDialog;
import android.app.Dialog;
import android.content.ActivityNotFoundException;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ProviderInfo;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.net.Uri;
import android.net.http.SslError;
import android.os.Bundle;
import android.os.SystemClock;
import android.view.LayoutInflater;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.webkit.DownloadListener;
import android.webkit.SslErrorHandler;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceError;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.FrameLayout;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.fragment.app.DialogFragment;
import androidx.lifecycle.ViewModelProvider;
import chan.content.Chan;
import chan.content.ChanLocator;
import chan.util.StringUtils;
import com.mishiranu.dashchan.R;
import com.mishiranu.dashchan.chan.arhivach.ArhivachBrowserSession;
import com.mishiranu.dashchan.chan.soyjak.SoyjakBrowserSession;
import com.mishiranu.dashchan.chan.soyjak.SoyjakChanLocator;
import com.mishiranu.dashchan.chan.soyjak.SoyjakDiagnostics;
import com.mishiranu.dashchan.chan.soyjak.SoyjakReadRecovery;
import com.mishiranu.dashchan.chan.soyjak.SoyjakReaderPage;
import com.mishiranu.dashchan.media.VideoDiagnostics;
import com.mishiranu.dashchan.util.ConcurrentUtils;
import com.mishiranu.dashchan.content.AdvancedPreferences;
import com.mishiranu.dashchan.content.Preferences;
import com.mishiranu.dashchan.content.security.ProtectedActivityResults;
import com.mishiranu.dashchan.util.AndroidUtils;
import com.mishiranu.dashchan.util.AnimationUtils;
import com.mishiranu.dashchan.util.NavigationUtils;
import com.mishiranu.dashchan.util.ResourceUtils;
import com.mishiranu.dashchan.util.ViewUtils;
import com.mishiranu.dashchan.util.WebViewUtils;
import com.mishiranu.dashchan.widget.ClickableToast;
import com.mishiranu.dashchan.widget.ExpandedLayout;
import com.mishiranu.dashchan.widget.ThemeEngine;
import java.util.UUID;
import org.json.JSONException;
import org.json.JSONTokener;

public class BrowserFragment extends ContentFragment implements DownloadListener {
	private static final String EXTRA_URI = "uri";
	private static final String EXTRA_SOYJAK_SITE = "soyjak.site";
	private static final String EXTRA_SOYJAK_REPLY = "soyjak.reply_form";
	private static final String EXTRA_RECOVERY_TOKEN = "soyjak.recovery";
	private static final String READ_VISIBLE_DOCUMENT = """
			(() => {
				if (!document.body || (location.origin !== 'https://soyjak.st'
						&& location.origin !== 'https://www.soyjak.st')) return null;
				const text = document.contentType === 'application/json' || document.body.firstElementChild?.tagName === 'PRE'
						? document.body.innerText : document.body.outerHTML;
				return text.length <= 4000000 ? text : null;
			})()
			""";

	public static BrowserFragment forSoyjakPage(Uri uri) {
		BrowserFragment fragment = new BrowserFragment(uri);
		fragment.requireArguments().putBoolean(EXTRA_SOYJAK_SITE, true);
		return fragment;
	}

	public static BrowserFragment forSoyjakReply(Uri uri) {
		BrowserFragment fragment = new BrowserFragment(uri);
		fragment.requireArguments().putBoolean(EXTRA_SOYJAK_REPLY, true);
		return fragment;
	}

	public static BrowserFragment forSoyjakRecovery(SoyjakReadRecovery.Request request) {
		BrowserFragment fragment = forSoyjakPage(request.uri());
		fragment.requireArguments().putString(EXTRA_RECOVERY_TOKEN, request.token());
		return fragment;
	}

	public BrowserFragment() {}

	public BrowserFragment(Uri uri) {
		if (!isSupportedUri(uri)) {
			throw new IllegalArgumentException("Unsupported browser URI");
		}
		Bundle args = new Bundle();
		args.putParcelable(EXTRA_URI, uri);
		setArguments(args);
	}

	public static boolean isSupportedUri(Uri uri) {
		if (uri == null) {
			return false;
		}
		String scheme = uri.getScheme();
		return "http".equalsIgnoreCase(scheme) || "https".equalsIgnoreCase(scheme);
	}

	private WebView webView;
	private ProgressView progressView;
	private SoyjakReadRecovery recovery;
	private String recoveryToken;
	private String readyDocument;
	private String loadingDocument;
	private boolean mainFrameFailed;
	private long documentRevision;
	private long readRevision = -1;
	private ValueCallback<Uri[]> replyFileCallback;
	private final ActivityResultLauncher<Intent> replyFilePicker = ProtectedActivityResults.register(
			this, "browser-reply-attachments", new ActivityResultContracts.StartActivityForResult(), result -> {
				if (replyFileCallback == null) return;
				Uri[] uris = new ActivityResultContracts.OpenMultipleDocuments()
						.parseResult(result.getResultCode(), result.getData()).stream()
						.filter(this::isExternalDocumentUri).toArray(Uri[]::new);
				finishReplyFileSelection(uris.length > 0 ? uris : null);
			});

	private boolean isSoyjakSite() {
		Bundle arguments = getArguments();
		return arguments != null && arguments.getBoolean(EXTRA_SOYJAK_SITE);
	}

	private boolean isSoyjakReply() {
		Bundle arguments = getArguments();
		return arguments != null && arguments.getBoolean(EXTRA_SOYJAK_REPLY);
	}

	private Uri getInitialUri() {
		Bundle arguments = getArguments();
		return arguments != null ? AndroidUtils.getParcelable(arguments, EXTRA_URI, Uri.class) : null;
	}

	private boolean isExternalDocumentUri(Uri uri) {
		if (!ContentResolver.SCHEME_CONTENT.equals(uri.getScheme()) || uri.getAuthority() == null) return false;
		Context context = requireContext();
		ProviderInfo provider = context.getPackageManager().resolveContentProvider(uri.getAuthority(), 0);
		return provider != null && !context.getPackageName().equals(provider.packageName);
	}

	private void finishReplyFileSelection(Uri[] uris) {
		ValueCallback<Uri[]> callback = replyFileCallback;
		replyFileCallback = null;
		if (callback != null) callback.onReceiveValue(uris);
	}

	private SoyjakReadRecovery.Request pendingRecovery() {
		return recovery != null ? recovery.find(recoveryToken) : null;
	}

	void allowRecoveryRetry() {
		readRevision = -1;
	}

	private void cancelRecovery() {
		if (recovery != null) recovery.cancel(recoveryToken);
	}

	private void readRecoveryDocument() {
		SoyjakReadRecovery.Request request = pendingRecovery();
		WebView source = webView;
		String url = readyDocument;
		long revision = documentRevision;
		if (request == null || source == null || !isResumed() || !source.isShown() || mainFrameFailed
				|| readRevision == revision || url == null || !url.equals(source.getUrl())
				|| !SoyjakReaderPage.sameReaderTarget(request.uri().toString(), url)
				|| !(Chan.get("soyjak").locator instanceof SoyjakChanLocator locator)) return;
		readRevision = revision;
		source.evaluateJavascript(READ_VISIBLE_DOCUMENT, value -> {
			if (source != webView || revision != documentRevision || pendingRecovery() != request || !isResumed()) return;
			ConcurrentUtils.PARALLEL_EXECUTOR.execute(() -> {
				SoyjakReadRecovery.Result result = null;
				try {
					if (value != null && new JSONTokener(value).nextValue() instanceof String document) {
						result = request.parse(document, Uri.parse(url), locator);
					}
				} catch (JSONException e) {
					// A verification document can have the same URL. Wait for a subsequent document.
				}
				SoyjakReadRecovery.Result parsed = result;
				source.post(() -> {
					if (source != webView || revision != documentRevision || pendingRecovery() != request) return;
					if (!isResumed() || !source.isShown() || getParentFragmentManager().isStateSaved()) {
						readRevision = -1;
						return;
					}
					if (!url.equals(source.getUrl()) || mainFrameFailed) return;
					if (((FragmentHandler) requireActivity()).isContentObscured()) {
						readRevision = -1;
						return;
					}
					VideoDiagnostics.recordUi("soyjak_webview stage=native_recovery content=" + request.content()
							+ " parsed=" + (parsed != null));
					SoyjakDiagnostics.event("recovery_result", "parsed=" + (parsed != null)
							+ " content=" + request.content());
					if (parsed != null && requireActivity() instanceof MainActivity activity) {
						activity.returnFromSoyjakVerification(this, () -> {
							if (source == webView && revision == documentRevision && isResumed()
									&& url.equals(source.getUrl()) && recovery.complete(parsed)) {
								activity.removeFragment();
							}
						});
					}
				});
			});
		});
	}

	private String navigationDrawerLocker;

	@Override
	public View onCreateView(@NonNull LayoutInflater inflater, ViewGroup container, Bundle savedInstanceState) {
		ExpandedLayout layout = new ExpandedLayout(container.getContext(), true);
		webView = new WebView(layout.getContext().getApplicationContext());
		layout.addView(webView, FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT);
		progressView = new ProgressView(layout.getContext());
		float density = ResourceUtils.obtainDensity(this);
		layout.addView(progressView, FrameLayout.LayoutParams.MATCH_PARENT, (int) (3f * density + 0.5f));
		return layout;
	}

	@SuppressLint("SetJavaScriptEnabled")
	@Override
	public void onViewCreated(@NonNull View view, Bundle savedInstanceState) {
		super.onViewCreated(view, savedInstanceState);

		Bundle arguments = getArguments();
		recoveryToken = arguments != null ? arguments.getString(EXTRA_RECOVERY_TOKEN) : null;
		if (recoveryToken != null) recovery = new ViewModelProvider(requireActivity()).get(SoyjakReadRecovery.class);
		navigationDrawerLocker = "browser-" + UUID.randomUUID();
		((FragmentHandler) requireActivity()).setNavigationAreaLocked(navigationDrawerLocker, true);

		WebSettings settings = webView.getSettings();
		WebViewUtils.configureCommonSettings(settings);
		if (isSoyjakReply()) settings.setUserAgentString(null);
		if (arguments != null && arguments.getBoolean(EXTRA_SOYJAK_SITE)) {
			// The clearance cookie issued by CloudFlare is bound to the exact user agent.
			// The native stack uses the same advanced setting, so both sides must agree.
			String userAgent = AdvancedPreferences.getUserAgent(SoyjakDiagnostics.CHAN_NAME);
			if (!StringUtils.isEmpty(userAgent)) settings.setUserAgentString(userAgent);
			SoyjakDiagnostics.event("webview_ua", "fp=" + SoyjakDiagnostics.fingerprint(settings.getUserAgentString())
					+ " sameAsNative=" + settings.getUserAgentString().equals(
					AdvancedPreferences.getUserAgent(SoyjakDiagnostics.CHAN_NAME)));
		}
		Uri initialUri = getInitialUri();
		if (initialUri != null && ArhivachBrowserSession.isSite(initialUri)) {
			String userAgent = AdvancedPreferences.getUserAgent(ArhivachBrowserSession.CHAN_NAME);
			if (!StringUtils.isEmpty(userAgent)) settings.setUserAgentString(userAgent);
			ArhivachBrowserSession.log("webview_ua fp="
					+ ArhivachBrowserSession.fingerprint(settings.getUserAgentString())
					+ " " + ArhivachBrowserSession.userAgentStatus());
		}
		settings.setBuiltInZoomControls(true);
		settings.setDisplayZoomControls(false);
		settings.setUseWideViewPort(true);
		settings.setLoadWithOverviewMode(true);
		settings.setJavaScriptEnabled(true);
		settings.setDomStorageEnabled(true);
		webView.setWebViewClient(new CustomWebViewClient());
		webView.setWebChromeClient(new CustomWebChromeClient());
		webView.setDownloadListener(this);
		webView.setOnLongClickListener(v -> {
			WebView.HitTestResult hitTestResult = webView.getHitTestResult();
			switch (hitTestResult.getType()) {
				case WebView.HitTestResult.IMAGE_TYPE:
				case WebView.HitTestResult.SRC_IMAGE_ANCHOR_TYPE: {
					Chan chan = Chan.getFallback();
					Uri uri = Uri.parse(hitTestResult.getExtra());
					if (chan.locator.isWebScheme(uri) && chan.locator.isImageExtension(uri.getPath())) {
						NavigationUtils.openImageVideo(requireContext(), uri);
					}
					return true;
				}
			}
			return false;
		});

		boolean restored = savedInstanceState != null && webView.restoreState(savedInstanceState) != null;
		loadInitialPage(restored);
	}

	@Override
	public void onDestroy() {
		if (isRemoving() && getActivity() != null && !requireActivity().isChangingConfigurations()) cancelRecovery();
		super.onDestroy();
	}

	@Override
	public void onDestroyView() {
		super.onDestroyView();
		finishReplyFileSelection(null);
		documentRevision++;
		readyDocument = null;

		((FragmentHandler) requireActivity()).setNavigationAreaLocked(navigationDrawerLocker, false);
		if (webView != null) {
			webView.stopLoading();
			// Remove references to fragment and parent view since WebView bugs may cause memory leaks
			webView.setOnLongClickListener(null);
			ViewUtils.removeFromParent(webView);
			webView.destroy();
		}
		webView = null;
		progressView = null;
	}

	private void loadInitialPage(boolean restored) {
		Uri uri = getInitialUri();
		boolean reply = isSoyjakReply();
		if (reply ? SoyjakReaderPage.isSite(uri) : isSupportedUri(uri)) {
			((FragmentHandler) requireActivity()).setTitleSubtitle(
					getString(reply ? R.string.new_post : R.string.web_browser), reply ? uri.getHost() : null);
			if (!restored) {
				webView.loadUrl(uri.toString());
			}
		} else {
			ClickableToast.show(R.string.unknown_address);
			((FragmentHandler) requireActivity()).removeFragment();
		}
	}

	@Override
	public void onPause() {
		super.onPause();
		if (webView != null) webView.onPause();
		readRevision = -1;
	}

	@Override
	public void onResume() {
		super.onResume();
		if (webView != null) {
			webView.onResume();
			webView.post(this::readRecoveryDocument);
		}
	}

	@Override
	public void onSaveInstanceState(@NonNull Bundle outState) {
		super.onSaveInstanceState(outState);

		if (webView != null) {
			webView.saveState(outState);
		}
	}

	@Override
	public void onCreateOptionsMenu(Menu menu, boolean primary) {
		if (isSoyjakReply()) {
			menu.add(0, R.id.menu_soyjak_external_browser, 0, R.string.soyjak_external_browser);
			return;
		}
		menu.add(0, R.id.menu_reload, 0, R.string.reload)
				.setIcon(((FragmentHandler) requireActivity()).getActionBarIcon(R.attr.iconActionRefresh))
				.setShowAsAction(MenuItem.SHOW_AS_ACTION_IF_ROOM);
		menu.add(0, R.id.menu_copy_link, 0, R.string.copy_link);
		menu.add(0, R.id.menu_share_link, 0, R.string.share_link);
		if (isSoyjakSite()) {
			menu.add(0, R.id.menu_soyjak_diagnostics, 0, R.string.soyjak_probe);
		}
		if (pendingRecovery() != null) {
			menu.add(0, R.id.menu_soyjak_read_catalog, 0, R.string.soyjak_read_catalog);
			menu.add(0, R.id.menu_soyjak_show_site, 0, R.string.soyjak_show_site);
		}
	}

	@Override
	public boolean onMenuItemSelected(MenuItem item) {
		if (isSoyjakReply() && item.getItemId() == R.id.menu_soyjak_external_browser) {
			Uri uri = getInitialUri();
			if (SoyjakReaderPage.isSite(uri)) NavigationUtils.openSystemBrowser(requireContext(), uri);
			return true;
		}
		if (webView == null) return true;
		if (item.getItemId() == R.id.menu_soyjak_read_catalog) {
			readRevision = -1;
			readRecoveryDocument();
		} else if (item.getItemId() == R.id.menu_soyjak_show_site) {
			cancelRecovery();
			invalidateOptionsMenu();
		} else if (item.getItemId() == R.id.menu_soyjak_diagnostics) {
			SoyjakDiagnostics.probeAndShow(requireContext());
		} else if (item.getItemId() == R.id.menu_reload) {
			webView.reload();
		} else if (item.getItemId() == R.id.menu_copy_link) {
			StringUtils.copyToClipboard(requireContext(), webView.getUrl());
		} else if (item.getItemId() == R.id.menu_share_link) {
			String uriString = webView.getUrl();
			if (!StringUtils.isEmpty(uriString)) {
				NavigationUtils.shareLink(requireContext(), null, Uri.parse(uriString));
			}
		}
		return true;
	}

	@Override
	public boolean onHomePressed() {
		cancelRecovery();
		return false;
	}

	@Override
	public boolean onBackPressed() {
		if (isSoyjakReply()) return false;
		if (pendingRecovery() != null) {
			cancelRecovery();
			return false;
		}
		if (webView != null && webView.canGoBack()) {
			webView.goBack();
			return true;
		}
		cancelRecovery();
		return false;
	}

	@Override
	public boolean canHandleBack() {
		return !isSoyjakReply() && pendingRecovery() == null && webView != null && webView.canGoBack();
	}

	@Override
	public void onDownloadStart(String url, String userAgent, String contentDisposition, String mimetype,
			long contentLength) {
		try {
			startActivity(new Intent(Intent.ACTION_VIEW).setData(Uri.parse(url))
					.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
		} catch (ActivityNotFoundException e) {
			ClickableToast.show(R.string.unknown_address);
		}
	}

	private class CustomWebViewClient extends WebViewClient {
		@Override
		public boolean onRenderProcessGone(WebView view, android.webkit.RenderProcessGoneDetail detail) {
			boolean current = webView == view;
			ViewGroup parent = view.getParent() instanceof ViewGroup ? (ViewGroup) view.getParent() : null;
			if (current) {
				finishReplyFileSelection(null);
				webView = null;
				documentRevision++;
			}
			WebViewUtils.destroyAfterRendererGone(view, detail, "browser");
			if (current) {
				if (progressView != null) progressView.setProgress(100);
				notifyBackNavigationChanged();
				if (parent != null) {
					com.mishiranu.dashchan.widget.ViewFactory.ErrorHolder error =
							com.mishiranu.dashchan.widget.ViewFactory.createErrorLayout(parent);
					error.text.setText(R.string.webview_renderer_gone);
					parent.addView(error.layout);
				}
			}
			return true;
		}
		@Override
		public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
			if (!request.isForMainFrame()) return false;
			Uri uri = request.getUrl();
			if (isSoyjakReply()) {
				if (SoyjakReaderPage.isSite(uri)) return false;
				if (request.hasGesture() && isSupportedUri(uri)) {
					NavigationUtils.openSystemBrowser(requireContext(), uri);
				}
				return true;
			}
			return handleUrlLoading(view, uri);
		}

		private boolean handleUrlLoading(WebView view, Uri uri) {
			Bundle arguments = getArguments();
			if (arguments != null && arguments.getBoolean(EXTRA_SOYJAK_SITE) && SoyjakReaderPage.isSite(uri)) return false;
			Chan chan = Chan.getPreferred(null, uri);
			if (chan.name != null) {
				ChanLocator.NavigationData navigationData;
				if (chan.locator.safe(true).isBoardUri(uri)) {
					navigationData = new ChanLocator.NavigationData(ChanLocator.NavigationData.Target.THREADS,
							chan.locator.safe(true).getBoardName(uri), null, null, null);
				} else if (chan.locator.safe(true).isThreadUri(uri)) {
					navigationData = new ChanLocator.NavigationData(ChanLocator.NavigationData.Target.POSTS,
							chan.locator.safe(true).getBoardName(uri), chan.locator.safe(true).getThreadNumber(uri),
							chan.locator.safe(true).getPostNumber(uri), null);
				} else {
					navigationData = chan.locator.safe(true).handleUriClickSpecial(uri);
				}
				if (navigationData != null) {
					LinkDialog dialog = new LinkDialog(chan.name, navigationData);
					dialog.show(getChildFragmentManager(), LinkDialog.class.getName());
					return true;
				}
			}
			if (!chan.locator.isWebScheme(uri)) {
				Intent intent = new Intent(Intent.ACTION_VIEW).setData(uri)
						.addCategory(Intent.CATEGORY_BROWSABLE)
						.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
				try {
					requireContext().startActivity(intent);
				} catch (ActivityNotFoundException | SecurityException e) {
					ClickableToast.show(R.string.unknown_address);
				}
				return true;
			}
			view.loadUrl(uri.toString());
			return true;
		}

		@Override
		public void onPageStarted(WebView view, String url, android.graphics.Bitmap favicon) {
			if (view == webView) {
				documentRevision++;
				readyDocument = null;
				loadingDocument = url;
				mainFrameFailed = false;
			}
		}

		private void mainFrameError(WebView view, WebResourceRequest request) {
			if (view == webView && request.isForMainFrame() && request.getUrl().toString().equals(loadingDocument)) {
				mainFrameFailed = true;
				documentRevision++;
				readyDocument = null;
			}
		}

		@Override
		public void onReceivedError(WebView view, WebResourceRequest request, WebResourceError error) {
			mainFrameError(view, request);
		}

		@Override
		public void onReceivedHttpError(WebView view, WebResourceRequest request, WebResourceResponse response) {
			mainFrameError(view, request);
		}

		@Override
		public void onPageFinished(WebView view, String url) {
			if (view != webView || !isAdded() || url == null) return;
			if (!isSoyjakReply() && SoyjakBrowserSession.storeCookies(url)) {
				ClickableToast.show(R.string.soyjak_access_saved);
			}
			ArhivachBrowserSession.storeCookies(url);
			if (ArhivachBrowserSession.isSite(Uri.parse(url))) {
				String pageTitle = view.getTitle();
				ArhivachBrowserSession.log("webview_finished host=" + Uri.parse(url).getHost()
						+ " challengeTitle=" + ("Just a moment...".equals(pageTitle)
						|| "Attention Required! | Cloudflare".equals(pageTitle)));
			}
			if (isSoyjakSite()) {
				String pageTitle = view.getTitle();
				SoyjakDiagnostics.event("webview_finished", "route=" + SoyjakDiagnostics.route(Uri.parse(url))
						+ " challengeTitle=" + ("Just a moment...".equals(pageTitle)
						|| "Attention Required! | Cloudflare".equals(pageTitle)));
			}
			if (!mainFrameFailed && url.equals(view.getUrl()) && (loadingDocument == null || url.equals(loadingDocument))) {
				readyDocument = url;
				readRecoveryDocument();
			}
			if (!isSoyjakReply()) {
				String title = view.getTitle();
				((FragmentHandler) requireActivity()).setTitleSubtitle(StringUtils.isEmptyOrWhitespace(title)
						? getString(R.string.web_browser) : title, null);
			}
			notifyBackNavigationChanged();
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

	public static class LinkDialog extends DialogFragment {
		private static final String EXTRA_CHAN_NAME = "chanName";
		private static final String EXTRA_NAVIGATION_DATA = "navigationData";

		public LinkDialog() {}

		public LinkDialog(String chanName, ChanLocator.NavigationData navigationData) {
			Bundle args = new Bundle();
			args.putString(EXTRA_CHAN_NAME, chanName);
			args.putParcelable(EXTRA_NAVIGATION_DATA, navigationData);
			setArguments(args);
		}

		@NonNull
		@Override
		public Dialog onCreateDialog(Bundle savedInstanceState) {
			String chanName = requireArguments().getString(EXTRA_CHAN_NAME);
			ChanLocator.NavigationData navigationData = AndroidUtils.getParcelable(requireArguments(),
					EXTRA_NAVIGATION_DATA, ChanLocator.NavigationData.class);
			return MotionDialogBuilder.standard(requireContext())
					.setMessage(R.string.follow_the_link__sentence)
					.setNegativeButton(android.R.string.cancel, null)
					.setPositiveButton(android.R.string.ok, (dialog, which) -> ((FragmentHandler)
							requireActivity()).navigateTargetAllowReturn(chanName, navigationData))
					.create();
		}
	}

	private class CustomWebChromeClient extends WebChromeClient {
		@Override
		public boolean onShowFileChooser(WebView view, ValueCallback<Uri[]> callback, FileChooserParams params) {
			if (!isSoyjakReply() || view != webView || replyFileCallback != null) return false;
			String url = view.getUrl();
			if (url == null || !SoyjakReaderPage.isSite(Uri.parse(url))) return false;
			int mode = params.getMode();
			if (mode != FileChooserParams.MODE_OPEN && mode != FileChooserParams.MODE_OPEN_MULTIPLE) return false;
			Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE)
					.setType("*/*").putExtra(Intent.EXTRA_ALLOW_MULTIPLE, mode == FileChooserParams.MODE_OPEN_MULTIPLE);
			String[] acceptTypes = params.getAcceptTypes();
			if (acceptTypes.length > 0 && !StringUtils.isEmpty(acceptTypes[0])) {
				intent.putExtra(Intent.EXTRA_MIME_TYPES, acceptTypes);
			}
			replyFileCallback = callback;
			try {
				replyFilePicker.launch(intent);
			} catch (ActivityNotFoundException e) {
				finishReplyFileSelection(null);
			}
			return true;
		}

		@Override
		public void onProgressChanged(WebView view, int newProgress) {
			if (view == webView && progressView != null) progressView.setProgress(newProgress);
		}
	}

	private static class ProgressView extends View {
		private static final int TRANSIENT_TIME = 200;

		private final Paint paint = new Paint();

		private long progressSetTime;
		private float transientProgress = 0f;
		private int progress = 0;

		public ProgressView(Context context) {
			super(context);
			int color = ThemeEngine.getTheme(context).accent;
			paint.setColor(Color.BLACK | color);
		}

		public void setProgress(int progress) {
			transientProgress = calculateTransient();
			progressSetTime = SystemClock.elapsedRealtime();
			this.progress = progress;
			invalidate();
		}

		private float getTime() {
			return Math.min((float) (SystemClock.elapsedRealtime() - progressSetTime) / TRANSIENT_TIME, 1f);
		}

		private float calculateTransient() {
			return AnimationUtils.lerp(transientProgress, progress, getTime());
		}

		@Override
		protected void onDraw(Canvas canvas) {
			super.onDraw(canvas);
			Paint paint = this.paint;
			int progress = this.progress;
			float transientProgress = calculateTransient();
			int alpha = 0xff;
			boolean needInvalidate = transientProgress != progress;
			if (progress == 100) {
				float t = getTime();
				alpha = (int) (0xff * (1f - t));
				needInvalidate |= t < 1f;
			}
			paint.setAlpha(alpha);
			if (transientProgress > 0 && alpha > 0x00) {
				float width = getWidth() * transientProgress / 100;
				canvas.drawRect(0, 0, width, getHeight(), paint);
			}
			if (needInvalidate) {
				invalidate();
			}
		}
	}
}
