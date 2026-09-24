package com.mishiranu.dashchan.ui;

import android.annotation.SuppressLint;
import android.app.Dialog;
import android.content.DialogInterface;
import android.net.Uri;
import android.net.http.SslError;
import android.os.Bundle;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.webkit.CookieManager;
import android.webkit.RenderProcessGoneDetail;
import android.webkit.SslErrorHandler;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.LinearLayout;
import android.widget.Button;
import android.widget.TextView;
import androidx.annotation.NonNull;
import androidx.fragment.app.DialogFragment;
import chan.content.Chan;
import chan.content.InvalidResponseException;
import chan.http.HttpException;
import chan.http.HttpHolder;
import com.mishiranu.dashchan.R;
import com.mishiranu.dashchan.chan.kohlchan.KohlchanAccess;
import com.mishiranu.dashchan.content.async.HttpHolderTask;
import com.mishiranu.dashchan.util.ConcurrentUtils;
import com.mishiranu.dashchan.util.WebViewUtils;

/** Official site UI, without CAPTCHA recognition, injected form submission or JavaScript bridges. */
public class KohlchanAccessDialog extends DialogFragment {
	private WebView webView;
	private TextView statusView;
	private Button checkButton;
	private HttpHolderTask<Void, Integer> checkTask;
	private boolean published;

	public KohlchanAccessDialog() {}

	protected void publishResult(boolean success) {}

	private void finish(boolean success) {
		if (!published) {
			published = true;
			publishResult(success);
		}
		dismissAllowingStateLoss();
	}

	private static boolean allowed(Uri uri) {
		if (uri == null || !"https".equalsIgnoreCase(uri.getScheme())
				|| !"kohlchan.net".equalsIgnoreCase(uri.getHost()) || uri.getUserInfo() != null
				|| uri.getPort() != -1 && uri.getPort() != 443) return false;
		String path = uri.getPath();
		return "/".equals(path) || "/index.html".equals(path) || "/blockBypass.js".equals(path)
				|| "/renewBypass.js".equals(path) || "/validateBypass.js".equals(path)
				|| "/unlocked.html".equals(path) || "/addon.js/hashcash".equals(path)
				|| path != null && (path.startsWith("/addon.js/hashcash/") || path.startsWith("/cdn-cgi/"));
	}

	@SuppressLint("SetJavaScriptEnabled")
	@NonNull
	@Override
	public Dialog onCreateDialog(Bundle state) {
		float density = getResources().getDisplayMetrics().density;
		LinearLayout layout = new LinearLayout(requireContext());
		layout.setOrientation(LinearLayout.VERTICAL);
		TextView title = new TextView(requireContext());
		title.setText(R.string.kohlchan_access_title);
		title.setTextSize(20);
		int titlePadding = (int) (12 * density + 0.5f);
		title.setPadding(titlePadding, titlePadding, titlePadding, 0);
		layout.addView(title, new LinearLayout.LayoutParams(-1, -2));
		statusView = new TextView(requireContext());
		statusView.setText(R.string.kohlchan_access_instructions);
		int padding = (int) (12 * density + 0.5f);
		statusView.setPadding(padding, padding, padding, padding);
		layout.addView(statusView, new LinearLayout.LayoutParams(-1, -2));
		webView = new WebView(requireContext());
		WebViewUtils.configureCommonSettings(webView.getSettings());
		webView.getSettings().setJavaScriptEnabled(true);
		webView.getSettings().setDomStorageEnabled(true);
		webView.getSettings().setMixedContentMode(WebSettings.MIXED_CONTENT_NEVER_ALLOW);
		webView.getSettings().setSupportMultipleWindows(false);
		webView.getSettings().setJavaScriptCanOpenWindowsAutomatically(false);
		webView.setWebChromeClient(new WebChromeClient());
		webView.setWebViewClient(new WebViewClient() {
			@Override
			public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
				return request.isForMainFrame() && !allowed(request.getUrl());
			}

			@SuppressWarnings("deprecation")
			@Override
			public boolean shouldOverrideUrlLoading(WebView view, String url) {
				return !allowed(Uri.parse(url));
			}

			@Override
			public void onReceivedSslError(WebView view, SslErrorHandler handler, SslError error) {
				handler.cancel();
				if (statusView != null) statusView.setText(R.string.kohlchan_access_network_error);
			}

			@Override
			public boolean onRenderProcessGone(WebView view, RenderProcessGoneDetail detail) {
				finish(false);
				return true;
			}
		});
		CookieManager manager = CookieManager.getInstance();
		manager.setAcceptCookie(true);
		manager.setAcceptThirdPartyCookies(webView, false);
		layout.addView(webView, new LinearLayout.LayoutParams(-1, 0, 1));
		boolean restored = state != null && webView.restoreState(state) != null;
		if (!restored) {
			// Do not overwrite a newer browser permission with an older saved value.
			String saved = KohlchanAccess.getCookie(Chan.get("kohlchan"));
			if (readCookie() == null && saved != null) {
				manager.setCookie(KohlchanAccess.ORIGIN, "bypass=" + saved + "; Path=/; Secure", ignored -> {
					if (webView != null) webView.loadUrl(KohlchanAccess.PAGE);
				});
			} else webView.loadUrl(KohlchanAccess.PAGE);
		}
		LinearLayout buttons = new LinearLayout(requireContext());
		buttons.setPadding(padding, 0, padding, padding);
		Button cancel = new Button(requireContext(), null, android.R.attr.borderlessButtonStyle);
		cancel.setText(android.R.string.cancel);
		cancel.setOnClickListener(view -> finish(false));
		buttons.addView(cancel, new LinearLayout.LayoutParams(0, -2, 1));
		checkButton = new Button(requireContext(), null, android.R.attr.borderlessButtonStyle);
		checkButton.setText(R.string.kohlchan_access_check);
		checkButton.setOnClickListener(view -> checkPermission());
		buttons.addView(checkButton, new LinearLayout.LayoutParams(0, -2, 1));
		layout.addView(buttons, new LinearLayout.LayoutParams(-1, -2));
		Dialog dialog = new Dialog(requireContext());
		dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);
		dialog.setContentView(layout, new ViewGroup.LayoutParams(-1, -1));
		return dialog;
	}

	@SuppressWarnings("deprecation") // Dialog must resize for the site's manual CAPTCHA input.
	@Override
	public void onStart() {
		super.onStart();
		Dialog dialog = getDialog();
		if (dialog == null) return;
		Window window = dialog.getWindow();
		if (window != null) {
			window.clearFlags(WindowManager.LayoutParams.FLAG_ALT_FOCUSABLE_IM);
			window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);
			window.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT);
		}
	}

	private static String readCookie() {
		String cookies = CookieManager.getInstance().getCookie(KohlchanAccess.ORIGIN + "/");
		if (cookies != null) {
			for (String pair : cookies.split(";")) {
				pair = pair.trim();
				if (pair.startsWith("bypass=")) return KohlchanAccess.checkedCookie(pair.substring(7));
			}
		}
		return null;
	}

	private void checkPermission() {
		if (checkTask != null || webView == null) return;
		String cookie = readCookie();
		if (cookie == null) {
			statusView.setText(R.string.kohlchan_access_not_ready);
			return;
		}
		Chan chan = Chan.get("kohlchan");
		checkButton.setEnabled(false);
		checkTask = new HttpHolderTask<Void, Integer>(chan) {
			@Override
			protected Integer run(HttpHolder holder) {
				try {
					return KohlchanAccess.check(chan, holder, cookie).valid ? 0 : R.string.kohlchan_access_not_ready;
				} catch (HttpException | InvalidResponseException e) {
					return R.string.kohlchan_access_network_error;
				}
			}

			@Override
			protected void onComplete(Integer error) {
				checkTask = null;
				if (!isAdded() || webView == null || published) return;
				if (error == 0) {
					KohlchanAccess.storeCookie(chan, cookie);
					CookieManager.getInstance().flush();
					finish(true);
				} else {
					statusView.setText(error);
					checkButton.setEnabled(true);
				}
			}
		};
		checkTask.execute(ConcurrentUtils.PARALLEL_EXECUTOR);
	}

	@Override
	public void onCancel(@NonNull DialogInterface dialog) {
		super.onCancel(dialog);
		if (!published) { published = true; publishResult(false); }
	}

	@Override
	public void onSaveInstanceState(@NonNull Bundle outState) {
		super.onSaveInstanceState(outState);
		if (webView != null) webView.saveState(outState);
	}

	@Override
	public void onDestroyView() {
		if (checkTask != null) { checkTask.cancel(); checkTask = null; }
		if (!published && (isRemoving() || getActivity() != null && getActivity().isFinishing())) {
			published = true;
			publishResult(false);
		}
		if (webView != null) {
			webView.stopLoading();
			webView.setWebChromeClient(null);
			webView.setWebViewClient(null);
			if (webView.getParent() instanceof ViewGroup) ((ViewGroup) webView.getParent()).removeView(webView);
			webView.destroy();
			webView = null;
		}
		statusView = null;
		checkButton = null;
		super.onDestroyView();
	}
}
