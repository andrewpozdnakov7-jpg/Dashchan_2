package com.mishiranu.dashchan.content.service.webview;

import android.annotation.SuppressLint;
import android.app.Service;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.net.Uri;
import android.net.http.SslError;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.RemoteException;
import android.view.View;
import android.view.ViewGroup;
import android.webkit.ConsoleMessage;
import android.webkit.CookieManager;
import android.webkit.JavascriptInterface;
import android.webkit.SslErrorHandler;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceError;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import androidx.webkit.WebViewFeature;
import chan.http.HttpClient;
import com.mishiranu.dashchan.R;
import com.mishiranu.dashchan.util.IOUtils;
import com.mishiranu.dashchan.util.WebViewUtils;
import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.HashMap;
import java.util.LinkedList;

public class WebViewService extends Service {
	private static class CookieRequest {
		public final String uriString;
		public final String userAgent;
		public final HttpClient.ProxyData proxyData;
		public final boolean verifyCertificate;
		public final long timeout;
		public final WebViewExtra extra;
		public final IRequestCallback requestCallback;

		public volatile boolean ready;
		public volatile boolean finished;

		public String recaptchaV2ApiKey;
		public String recaptchaV2Result;
		public boolean recaptchaIsHcaptcha;

		private CookieRequest(String uriString, String userAgent, HttpClient.ProxyData proxyData,
				boolean verifyCertificate, long timeout, WebViewExtra extra, IRequestCallback requestCallback) {
			this.uriString = uriString;
			this.userAgent = userAgent;
			this.proxyData = proxyData;
			this.verifyCertificate = verifyCertificate;
			this.timeout = timeout;
			this.extra = extra;
			this.requestCallback = requestCallback;
		}
	}

	private final LinkedList<CookieRequest> cookieRequests = new LinkedList<>();

	private volatile WebView webView;
	private volatile CookieRequest cookieRequest;
	private Thread captchaThread;
	private volatile boolean destroyed;

	private static boolean captureImageFileInit;
	private static File captureImageFile;

	private static final int DRAW_TO_FILE_INTERVAL = 2000;

	private static final int MESSAGE_HANDLE_NEXT = 1;
	private static final int MESSAGE_HANDLE_FINISH = 2;
	private static final int MESSAGE_HANDLE_AFTER_INTERRUPT = 3;
	private static final int MESSAGE_HANDLE_CAPTCHA = 4;
	private static final int MESSAGE_DRAW_TO_FILE = 5;

	private final Handler handler = new Handler(Looper.getMainLooper(), message -> {
		if (destroyed) {
			return false;
		}
		switch (message.what) {
			case MESSAGE_HANDLE_NEXT: {
				handleNextCookieRequest();
				return true;
			}
			case MESSAGE_HANDLE_FINISH: {
				CookieRequest cookieRequest = this.cookieRequest;
				if (cookieRequest != null) {
					synchronized (cookieRequest) {
						if (!cookieRequest.ready) {
							cookieRequest.ready = true;
							cookieRequest.notifyAll();
						}
					}
					this.cookieRequest = null;
				}
				handleNextCookieRequest();
				return true;
			}
			case MESSAGE_HANDLE_AFTER_INTERRUPT: {
				CookieRequest cookieRequest = (CookieRequest) message.obj;
				if (cookieRequest == this.cookieRequest) {
					this.cookieRequest = null;
					handleNextCookieRequest();
				}
				return true;
			}
			case MESSAGE_HANDLE_CAPTCHA: {
				CookieRequest cookieRequest = (CookieRequest) message.obj;
				if (cookieRequest == this.cookieRequest) {
					message.getTarget().removeMessages(MESSAGE_HANDLE_FINISH);
					if (cookieRequest.recaptchaV2Result != null) {
						webView.loadUrl("javascript:handleResult('" + cookieRequest.recaptchaV2Result + "')");
						message.getTarget().sendEmptyMessageDelayed(MESSAGE_HANDLE_FINISH, cookieRequest.timeout);
					} else {
						message.getTarget().sendEmptyMessage(MESSAGE_HANDLE_FINISH);
					}
				}
				return true;
			}
			case MESSAGE_DRAW_TO_FILE: {
				if (captureImageFile != null && webView != null) {
					Bitmap bitmap = Bitmap.createBitmap(webView.getLayoutParams().width,
							webView.getLayoutParams().height, Bitmap.Config.ARGB_8888);
					webView.draw(new Canvas(bitmap));
					try (FileOutputStream output = new FileOutputStream(captureImageFile)) {
						bitmap.compress(Bitmap.CompressFormat.PNG, 100, output);
					} catch (IOException e) {
						// Ignore exception
					} finally {
						bitmap.recycle();
					}
					message.getTarget().sendEmptyMessageDelayed(MESSAGE_DRAW_TO_FILE, DRAW_TO_FILE_INTERVAL);
				}
				return true;
			}
		}
		return false;
	});

	private class ServiceClient extends WebViewClient {
		@Override
		public boolean onRenderProcessGone(WebView view, android.webkit.RenderProcessGoneDetail detail) {
			boolean current = webView == view;
			if (current) {
				webView = null;
				handler.removeCallbacksAndMessages(null);
				synchronized (WebViewService.this) {
					if (captchaThread != null) captchaThread.interrupt();
					captchaThread = null;
				}
				completeFailedRequest(cookieRequest);
				cookieRequest = null;
			}
			WebViewUtils.destroyAfterRendererGone(view, detail, "cookie_service");
			// The failed request is NOT replayed. A new view is created only for
			// subsequent requests, after this renderer's callbacks have unwound.
			if (current && !destroyed) handler.sendEmptyMessage(MESSAGE_HANDLE_NEXT);
			return true;
		}
		@Override
		public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
			return false;
		}

		@Override
		public void onPageFinished(WebView view, String url) {
			if (view != webView || destroyed) return;
			super.onPageFinished(view, url);

			CookieRequest cookieRequest = WebViewService.this.cookieRequest;
			boolean finished = true;
			if (cookieRequest != null) {
				String cookie = CookieManager.getInstance().getCookie(url);
				try {
					finished = cookieRequest.requestCallback.onPageFinished(url, cookie, view.getTitle());
					cookieRequest.finished = finished;
				} catch (RemoteException e) {
					e.printStackTrace();
				}
				if (!finished) {
					if (cookieRequest.extra != null) {
						String injectJavascript = cookieRequest.extra.getInjectJavascript();
						if (injectJavascript != null) {
							String sanitized = injectJavascript
									.replace("\\", "\\\\").replace("\n", "\\n").replace("\"", "\\\"");
							view.loadUrl("javascript:eval(\"" + sanitized + "\")");
						}
					}
				}
			}
			if (finished) {
				view.stopLoading();
				handler.removeMessages(MESSAGE_HANDLE_FINISH);
				handler.sendEmptyMessage(MESSAGE_HANDLE_FINISH);
			}
		}

		@Override
		public void onReceivedSslError(WebView view, SslErrorHandler sslHandler, SslError error) {
			if (view != webView || destroyed) { sslHandler.cancel(); return; }
			CookieRequest cookieRequest = WebViewService.this.cookieRequest;
			if (cookieRequest != null) {
				if (cookieRequest.verifyCertificate) {
					sslHandler.cancel();
					handler.removeMessages(MESSAGE_HANDLE_FINISH);
					handler.sendEmptyMessage(MESSAGE_HANDLE_FINISH);
				} else {
					sslHandler.proceed();
				}
			} else {
				super.onReceivedSslError(view, sslHandler, error);
			}
		}

		@Override
		public void onReceivedError(WebView view, WebResourceRequest request, WebResourceError error) {
			if (view != webView || destroyed) return;
			super.onReceivedError(view, request, error);

			if (request == null || request.isForMainFrame()) {
				handleReceivedError();
			}
		}

		private void handleReceivedError() {
			handler.removeMessages(MESSAGE_HANDLE_FINISH);
			handler.sendEmptyMessage(MESSAGE_HANDLE_FINISH);
		}

		private WebResourceResponse getCaptchaApi(String onLoad) {
			String stub = IOUtils.readRawResourceString(getResources(), R.raw.web_captcha_api)
					.replace("__REPLACE_ON_LOAD__", onLoad != null ? "'" + onLoad + "'" : "null");
			return new WebResourceResponse("application/javascript", "UTF-8",
					new ByteArrayInputStream(stub.getBytes()));
		}

		@Override
		public WebResourceResponse shouldInterceptRequest(WebView view, WebResourceRequest request) {
			if (view != webView || destroyed) return new WebResourceResponse("text/plain", "UTF-8", null);
			return handleInterceptRequest(request.getUrl().toString());
		}

		private WebResourceResponse handleInterceptRequest(String url) {
			boolean allowed = false;
			CookieRequest cookieRequest = WebViewService.this.cookieRequest;
			boolean recaptcha = url.contains("recaptcha");
			boolean hcaptcha = url.contains("hcaptcha");
			if (cookieRequest != null && (recaptcha || hcaptcha)) {
				Uri uri = Uri.parse(url);
				String key = uri.getQueryParameter("render");
				String onLoad = uri.getQueryParameter("onload");
				if (key != null || onLoad != null) {
					cookieRequest.recaptchaV2ApiKey = key;
					cookieRequest.recaptchaIsHcaptcha = hcaptcha;
					return getCaptchaApi(onLoad);
				}
			}
			if (cookieRequest != null) {
				try {
					allowed = cookieRequest.requestCallback.onLoad(url);
				} catch (RemoteException e) {
					e.printStackTrace();
				}
			}
			if (allowed) {
				return null;
			} else {
				return new WebResourceResponse("text/html", "UTF-8", null);
			}
		}
	}

	private void startCaptchaThread(CookieRequest cookieRequest) {
		synchronized (this) {
			if (captchaThread != null) {
				captchaThread.interrupt();
			}
			captchaThread = new Thread(() -> {
				try {
					if (cookieRequest.recaptchaIsHcaptcha) {
						cookieRequest.recaptchaV2Result = cookieRequest.requestCallback
								.onHcaptcha(cookieRequest.recaptchaV2ApiKey, cookieRequest.uriString);
					} else {
						cookieRequest.recaptchaV2Result = cookieRequest.requestCallback
								.onRecaptchaV2(cookieRequest.recaptchaV2ApiKey, true, cookieRequest.uriString);
					}
				} catch (RemoteException e) {
					// Ignore
				}
				handler.obtainMessage(MESSAGE_HANDLE_CAPTCHA, cookieRequest).sendToTarget();
			});
			captchaThread.start();
		}
	}

	private void handleNextCookieRequest() {
		if (destroyed) return;
		if (cookieRequest == null) {
			synchronized (cookieRequests) {
				while (!cookieRequests.isEmpty()) {
					cookieRequest = cookieRequests.removeFirst();
					// Ignore interrupted requests
					if (!cookieRequest.ready) {
						break;
					}
					cookieRequest = null;
				}
			}
			if (webView == null) {
				if (cookieRequest == null) return;
				try {
					createWebView();
				} catch (RuntimeException e) {
					android.util.Log.w("WebViewRecovery", "service_create_failed type=" + e.getClass().getSimpleName());
					if (webView != null) webView.destroy();
					webView = null;
					completeFailedRequest(cookieRequest);
					cookieRequest = null;
					failQueuedRequests();
					return;
				}
			}
			handler.removeMessages(MESSAGE_DRAW_TO_FILE);
			webView.stopLoading();
			CookieRequest cookieRequest = this.cookieRequest;
			WebView clearedView = webView;
			if (cookieRequest != null) {
				handler.removeMessages(MESSAGE_HANDLE_FINISH);
				handler.sendEmptyMessageDelayed(MESSAGE_HANDLE_FINISH, cookieRequest.timeout);
				WebViewUtils.clearAll(clearedView, () -> {
					if (destroyed || this.cookieRequest != cookieRequest || webView != clearedView
							|| cookieRequest.ready) return;
					clearedView.getSettings().setUserAgentString(cookieRequest.userAgent);
					WebViewUtils.setProxy(this, cookieRequest.proxyData, () -> {
						if (!destroyed && this.cookieRequest == cookieRequest && webView == clearedView
								&& !cookieRequest.ready) {
							clearedView.loadUrl(cookieRequest.uriString);
						}
					});
					if (captureImageFile != null) {
						handler.sendEmptyMessageDelayed(MESSAGE_DRAW_TO_FILE, DRAW_TO_FILE_INTERVAL);
					}
				});
			} else {
				WebViewUtils.clearAll(clearedView, () -> {
					if (!destroyed && this.cookieRequest == null && webView == clearedView) {
						clearedView.loadUrl("about:blank");
					}
				});
			}
		}
	}

	@Override
	public IBinder onBind(Intent intent) {
		return new IWebViewService.Stub() {
			private final HashMap<String, CookieRequest> interruptedRequests = new HashMap<>();

			@Override
			public boolean loadWithCookieResult(String requestId, String uriString, String userAgent,
					boolean proxySocks, String proxyHost, int proxyPort, boolean verifyCertificate, long timeout,
					WebViewExtra extra, IRequestCallback requestCallback) throws RemoteException {
			if (proxySocks && !WebViewFeature.isFeatureSupported(WebViewFeature.PROXY_OVERRIDE)) {
				// The fallback proxy path handles HTTP only. Never silently load a SOCKS forum directly.
				return false;
			}
				HttpClient.ProxyData proxyData = proxyHost != null
						? new HttpClient.ProxyData(proxySocks, proxyHost, proxyPort) : null;
				CookieRequest cookieRequest = new CookieRequest(uriString, userAgent, proxyData,
						verifyCertificate, timeout, extra, requestCallback);
				synchronized (interruptedRequests) {
					if (interruptedRequests.containsKey(requestId)) {
						interruptedRequests.remove(requestId);
						return false;
					} else {
						interruptedRequests.put(requestId, cookieRequest);
					}
				}
				try {
					synchronized (cookieRequests) {
						if (destroyed) return false;
						cookieRequests.add(cookieRequest);
					}
					synchronized (cookieRequest) {
						handler.sendEmptyMessage(MESSAGE_HANDLE_NEXT);
						while (!cookieRequest.ready) {
							try {
								cookieRequest.wait();
							} catch (InterruptedException e) {
								Thread.currentThread().interrupt();
								throw new RemoteException("interrupted");
							}
						}
					}
					return cookieRequest.finished;
				} finally {
					synchronized (interruptedRequests) {
						interruptedRequests.remove(requestId);
					}
				}
			}

			@Override
			public void interrupt(String requestId) {
				synchronized (interruptedRequests) {
					CookieRequest cookieRequest = interruptedRequests.get(requestId);
					if (cookieRequest != null) {
						synchronized (cookieRequest) {
							cookieRequest.ready = true;
							cookieRequest.notifyAll();
						}
						handler.obtainMessage(MESSAGE_HANDLE_AFTER_INTERRUPT, cookieRequest).sendToTarget();
					} else {
						interruptedRequests.put(requestId, null);
					}
				}
			}
		};
	}

	@SuppressLint({"SetJavaScriptEnabled", "AddJavascriptInterface"})
	@Override
	public void onCreate() {
		super.onCreate();

		if (!captureImageFileInit) {
			captureImageFileInit = true;
			File file = new File(getExternalCacheDir().getParentFile(), "files/webview.png");
			if (file.exists()) {
				captureImageFile = file;
				WebView.enableSlowWholeDocumentDraw();
			}
		}

		createWebView();
	}

	@SuppressLint({"SetJavaScriptEnabled", "AddJavascriptInterface"})
	private void createWebView() {
		webView = new WebView(this);
		WebView createdView = webView;
		WebSettings settings = webView.getSettings();
		WebViewUtils.configureCommonSettings(settings);
		settings.setJavaScriptEnabled(true);
		settings.setCacheMode(WebSettings.LOAD_NO_CACHE);
		webView.addJavascriptInterface(new Object() {
			@SuppressWarnings("unused")
			@JavascriptInterface
			public void onRequestRecaptcha(String apiKey) {
				CookieRequest request = cookieRequest;
				handler.post(() -> {
					if (webView != createdView || destroyed || request == null || request != cookieRequest) return;
					if (apiKey != null) request.recaptchaV2ApiKey = apiKey;
					handler.removeMessages(MESSAGE_HANDLE_FINISH);
					startCaptchaThread(request);
				});
			}
		}, "jsi");
		webView.setWebViewClient(new ServiceClient());
		webView.setWebChromeClient(new WebChromeClient() {
			@Override
			public boolean onConsoleMessage(ConsoleMessage consoleMessage) {
				if (webView != createdView || destroyed) return false;
				String text = consoleMessage.message();
				if (text != null && text.contains("SyntaxError")) {
					handler.removeMessages(MESSAGE_HANDLE_FINISH);
					handler.sendEmptyMessage(MESSAGE_HANDLE_FINISH);
					return true;
				}
				return false;
			}
		});
		webView.setLayoutParams(new ViewGroup.LayoutParams(480, 270));
		int initialScale = 25;
		if (captureImageFile != null) {
			int factor = 4;
			webView.getLayoutParams().width *= factor;
			webView.getLayoutParams().height *= factor;
			initialScale *= factor;
		}
		int measureSpec = View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED);
		webView.measure(measureSpec, measureSpec);
		webView.layout(0, 0, webView.getLayoutParams().width, webView.getLayoutParams().height);
		webView.setInitialScale(initialScale);
	}

	@Override
	public void onDestroy() {
		super.onDestroy();
		synchronized (cookieRequests) { destroyed = true; }
		handler.removeCallbacksAndMessages(null);
		synchronized (this) {
			if (captchaThread != null) captchaThread.interrupt();
			captchaThread = null;
		}
		completeFailedRequest(cookieRequest);
		cookieRequest = null;
		failQueuedRequests();
		if (webView != null) webView.destroy();
		webView = null;
	}

	private static void completeFailedRequest(CookieRequest request) {
		if (request != null) synchronized (request) {
			request.finished = false;
			request.ready = true;
			request.notifyAll();
		}
	}

	private void failQueuedRequests() {
		synchronized (cookieRequests) {
			for (CookieRequest request : cookieRequests) completeFailedRequest(request);
			cookieRequests.clear();
		}
	}
}
