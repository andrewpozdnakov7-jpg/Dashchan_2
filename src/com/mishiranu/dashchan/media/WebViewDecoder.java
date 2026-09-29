package com.mishiranu.dashchan.media;

import android.annotation.SuppressLint;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.os.Handler;
import android.os.Looper;
import android.os.Message;
import android.webkit.JavascriptInterface;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import com.mishiranu.dashchan.content.MainApplication;
import com.mishiranu.dashchan.content.model.FileHolder;
import com.mishiranu.dashchan.util.IOUtils;
import com.mishiranu.dashchan.util.WebViewUtils;
import java.io.IOException;
import java.io.InputStream;
import java.io.InterruptedIOException;
import java.util.concurrent.CountDownLatch;

public class WebViewDecoder extends WebViewClient {
	private static final int MESSAGE_INIT_WEB_VIEW = 1;
	private static final int MESSAGE_MEASURE_PICTURE = 2;
	private static final int MESSAGE_CHECK_PICTURE_SIZE = 3;

	private static final Handler HANDLER = new Handler(Looper.getMainLooper(), new Callback());

	private final FileHolder fileHolder;
	private final int sampleSize;
	private final CountDownLatch latch = new CountDownLatch(1);

	private volatile Bitmap bitmap;

	private WebView webView;
	private boolean finished;
	private final Runnable timeout = () -> {
		android.util.Log.w("WebViewRecovery", "decoder_timeout");
		countDownAndDestroy(webView);
	};

	private WebViewDecoder(FileHolder fileHolder, BitmapFactory.Options options) throws IOException {
		this.fileHolder = fileHolder;
		sampleSize = options != null ? Math.max(options.inSampleSize, 1) : 1;
		if (!fileHolder.isImage()) {
			throw new IOException();
		}
		HANDLER.obtainMessage(MESSAGE_INIT_WEB_VIEW, this).sendToTarget();
		try {
			latch.await();
		} catch (InterruptedException e) {
			HANDLER.post(() -> countDownAndDestroy(webView));
			Thread.currentThread().interrupt();
			throw new InterruptedIOException();
		}
	}

	@Override
	public WebResourceResponse shouldInterceptRequest(WebView view, WebResourceRequest request) {
		return handleInterceptRequest(request.getUrl().toString());
	}

	@SuppressWarnings("deprecation")
	@Override
	public WebResourceResponse shouldInterceptRequest(WebView view, String url) {
		return handleInterceptRequest(url);
	}

	private WebResourceResponse handleInterceptRequest(String url) {
		if (url.startsWith("http://") || url.startsWith("https://")) {
			if (url.endsWith("//127.0.0.1/image.jpeg")) {
				InputStream inputStream = null;
				try {
					inputStream = fileHolder.openInputStream();
					return new WebResourceResponse(fileHolder.getImageType() == FileHolder.ImageType.IMAGE_SVG
							? "image/svg+xml" : "image/jpeg", null, inputStream);
				} catch (IOException e) {
					IOUtils.close(inputStream);
				}
			}
			return new WebResourceResponse("text/html", "UTF-8", null);
		} else {
			return null;
		}
	}

	private boolean pageFinished = false;

	@Override
	public void onPageFinished(WebView view, String url) {
		if (finished || view != webView) return;
		super.onPageFinished(view, url);
		pageFinished = true;
		notifyExtract(view);
	}

	@SuppressWarnings("deprecation")
	private final WebView.PictureListener pictureListener = (view, picture) -> {
		if (pageFinished) {
			notifyExtract(view);
		}
	};

	private void notifyExtract(WebView view) {
		if (finished || view != webView) return;
		HANDLER.removeMessages(MESSAGE_MEASURE_PICTURE, this);
		HANDLER.sendMessageDelayed(HANDLER.obtainMessage(MESSAGE_MEASURE_PICTURE, this), 1000);
	}

	private boolean measured = false;

	private void measurePicture(WebView view) {
		if (!finished && view != null && !measured) {
			measured = true;
			webView = view;
			view.loadUrl("javascript:calculateSize();");
		}
	}

	private void countDownAndDestroy(WebView view) {
		if (finished) return;
		finished = true;
		webView = null;
		HANDLER.removeCallbacks(timeout);
		HANDLER.removeMessages(MESSAGE_MEASURE_PICTURE, this);
		try {
			if (view != null) view.destroy();
		} finally {
			latch.countDown();
		}
	}

	@Override
	public boolean onRenderProcessGone(WebView view, android.webkit.RenderProcessGoneDetail detail) {
		if (view == webView) {
			webView = null;
			countDownAndDestroy(null);
		}
		WebViewUtils.destroyAfterRendererGone(view, detail, "image_decoder");
		return true;
	}

	private void checkPictureSize(int width, int height) {
		if (finished || webView == null) return;
		if (width > 0 && height > 0) {
			if (webView.getWidth() <= 0 || webView.getHeight() <= 0) {
				measured = false;
				webView.layout(0, 0, width, height);
				webView.reload();
			} else {
				if (webView.getWidth() != width || webView.getHeight() != height) {
					countDownAndDestroy(webView);
				} else {
					try {
						bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
						webView.draw(new Canvas(bitmap));
					} catch (OutOfMemoryError e) {
						bitmap = null;
					} finally {
						countDownAndDestroy(webView);
					}
				}
			}
		} else {
			countDownAndDestroy(webView);
		}
	}

	private static class Callback implements Handler.Callback {
		@SuppressWarnings("deprecation")
		@SuppressLint({"SetJavaScriptEnabled", "AddJavascriptInterface"})
		@Override
		public boolean handleMessage(Message msg) {
			switch (msg.what) {
				case MESSAGE_INIT_WEB_VIEW: {
					WebViewDecoder decoder = (WebViewDecoder) msg.obj;
					if (decoder.finished) return true;
					int width = decoder.fileHolder.getImageWidth();
					int height = decoder.fileHolder.getImageHeight();
					int rotation = decoder.fileHolder.getImageRotation();
					if (rotation == 90 || rotation == 270) width = height ^ width ^ (height = width); // Swap
					width /= decoder.sampleSize;
					height /= decoder.sampleSize;
					WebView webView = new WebView(MainApplication.getInstance());
					decoder.webView = webView;
					HANDLER.postDelayed(decoder.timeout, 20000);
					WebSettings settings = webView.getSettings();
					WebViewUtils.configureCommonSettings(settings);
					settings.setCacheMode(WebSettings.LOAD_NO_CACHE);
					settings.setJavaScriptEnabled(true);
					webView.setInitialScale(100 / decoder.sampleSize);
					webView.setWebViewClient(decoder);
					webView.setBackgroundColor(Color.TRANSPARENT);
					webView.setPictureListener(decoder.pictureListener);
					webView.addJavascriptInterface(decoder, "jsi");
					if (width > 0 && height > 0) {
						webView.layout(0, 0, width, height);
					}
					webView.loadData("<!DOCTYPE html><html><head><script type=\"text/javascript\">"
							+ "function calculateSize() {jsi.onCalculateSize(document.body.children[0].naturalWidth, "
							+ "document.body.children[0].naturalHeight);}</script></head>"
							+ "<body style=\"margin: 0\"><img src=\"http://127.0.0.1/image.jpeg\" /></body></html>",
							"text/html", "UTF-8");
					return true;
				}
				case MESSAGE_MEASURE_PICTURE: {
					WebViewDecoder decoder = (WebViewDecoder) msg.obj;
					decoder.measurePicture(decoder.webView);
					return true;
				}
				case MESSAGE_CHECK_PICTURE_SIZE: {
					Object[] data = (Object[]) msg.obj;
					WebViewDecoder decoder = (WebViewDecoder) data[0];
					int width = (int) data[1];
					int height = (int) data[2];
					decoder.checkPictureSize(width, height);
					return true;
				}
			}
			return false;
		}
	}

	@SuppressWarnings("unused")
	@JavascriptInterface
	public void onCalculateSize(int width, int height) {
		width /= sampleSize;
		height /= sampleSize;
		HANDLER.obtainMessage(MESSAGE_CHECK_PICTURE_SIZE, new Object[] {this, width, height}).sendToTarget();
	}

	public static Bitmap loadBitmap(FileHolder fileHolder, BitmapFactory.Options options) {
		if (!MainApplication.getInstance().isLowRam()) {
			WebViewDecoder decoder;
			try {
				decoder = new WebViewDecoder(fileHolder, options);
			} catch (IOException e) {
				return null;
			}
			return decoder.bitmap;
		}
		return null;
	}
}
