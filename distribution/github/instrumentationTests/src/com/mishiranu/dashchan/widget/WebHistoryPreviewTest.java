package com.mishiranu.dashchan.widget;

import static org.junit.Assert.*;
import android.os.SystemClock;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import androidx.test.core.app.ActivityScenario;
import com.mishiranu.dashchan.content.Preferences;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.function.BooleanSupplier;
import org.junit.Assume;
import org.junit.Test;

/** Real WebView history + the production controller, with entirely intercepted local HTML responses. NOT RUN. */
public class WebHistoryPreviewTest {
	private static class Browser extends WebView {
		int backs, loads;
		Browser(android.content.Context context) { super(context); }
		@Override public void goBack() { backs++; super.goBack(); }
		@Override public void loadUrl(String url) { loads++; super.loadUrl(url); }
	}
	private static final class Fixture implements AutoCloseable {
		final ActivityScenario<StandardContentTestActivity> activity;
		Browser web;
		WebHistoryPreview preview;
		int starts;
		final java.util.concurrent.atomic.AtomicInteger responses = new java.util.concurrent.atomic.AtomicInteger();
		Object old;
		Fixture() {
			Assume.assumeTrue(android.os.Build.VERSION.SDK_INT >= 33);
			activity = ActivityScenario.launch(StandardContentTestActivity.class);
			activity.onActivity(a -> {
				old = Preferences.PREFERENCES.getAll().get(Preferences.KEY_PREDICTIVE_BACK);
				Preferences.PREFERENCES.edit().put(Preferences.KEY_PREDICTIVE_BACK, true).close();
				web = new Browser(a); a.setContentView(web); preview = new WebHistoryPreview(web, () -> true);
				web.setWebViewClient(new WebViewClient() {
					@Override public WebResourceResponse shouldInterceptRequest(WebView view, WebResourceRequest request) {
						String color = request.isForMainFrame() && responses.incrementAndGet() == 1 ? "#cc3333" : "#3333cc";
						String html = "<html><body style='margin:0;background:" + color + ";height:4000px'>local fixture</body></html>";
						return new WebResourceResponse("text/html", "UTF-8", new ByteArrayInputStream(html.getBytes(StandardCharsets.UTF_8)));
					}
					@Override public void onPageStarted(WebView view, String url, android.graphics.Bitmap icon) { starts++; preview.pageStarted(); }
					@Override public void doUpdateVisitedHistory(WebView view, String url, boolean reload) { preview.historyUpdated(reload); }
					@Override public void onPageFinished(WebView view, String url) { if (url.equals(view.getUrl())) preview.pageReady(); }
				});
			});
		}
		void main(Runnable action) { activity.onActivity(a -> action.run()); }
		void await(BooleanSupplier condition) {
			boolean[] done = {false};
			for (int i = 0; i < 160; i++) { main(() -> done[0] = condition.getAsBoolean()); if (done[0]) return; SystemClock.sleep(50); }
			fail("WebView fixture readiness timed out");
		}
		void load(String name) {
			int[] before = {0}; main(() -> { before[0] = starts; web.loadUrl("https://preview.invalid/" + name); });
			await(() -> starts > before[0] && web.getProgress() == 100 && web.getUrl().endsWith("/" + name) && prepared());
		}
		boolean prepared() {
			try {
				java.lang.reflect.Method current = WebHistoryPreview.class.getDeclaredMethod("current"); current.setAccessible(true);
				Object entry = current.invoke(preview); if (entry == null) return false;
				java.lang.reflect.Field frame = entry.getClass().getDeclaredField("frame"); frame.setAccessible(true); return frame.get(entry) != null;
			} catch (ReflectiveOperationException e) { throw new AssertionError(e); }
		}
		void begin() { await(() -> preview.start(true, () -> true)); }
		@Override public void close() {
			main(() -> {
				preview.close(); web.stopLoading(); web.destroy();
				if (old instanceof Boolean) Preferences.PREFERENCES.edit().put(Preferences.KEY_PREDICTIVE_BACK, (Boolean) old).close();
				else Preferences.PREFERENCES.edit().remove(Preferences.KEY_PREDICTIVE_BACK).close();
			}); activity.close();
		}
	}
	@Test public void cancelPreservesRealDocumentHistoryAndScrollWithoutNavigationRequests() {
		try (Fixture f = new Fixture()) {
			f.load("A"); f.load("B"); f.main(() -> f.web.scrollTo(0, 300)); f.begin();
			f.main(() -> {
				int index = f.web.copyBackForwardList().getCurrentIndex(), scroll = f.web.getScrollY(), loads = f.web.loads;
				f.preview.progress(.8f); f.preview.progress(.2f); f.preview.cancel(); f.preview.finish();
				assertEquals(index, f.web.copyBackForwardList().getCurrentIndex()); assertTrue(f.web.getUrl().endsWith("/B"));
				assertEquals(scroll, f.web.getScrollY()); assertEquals(0, f.web.backs); assertEquals(loads, f.web.loads);
			});
		}
	}
	@Test public void commitUsesOneGoBackAndHandsOffAfterDestinationVisualReadiness() {
		try (Fixture f = new Fixture()) {
			f.load("A"); f.load("B"); f.begin();
			f.main(() -> { f.preview.progress(.6f); assertTrue(f.preview.commit(f.web::goBack)); assertFalse(f.preview.commit(f.web::goBack)); });
			f.await(() -> f.web.backs == 1 && f.web.getUrl().endsWith("/A") && !f.preview.isBusy());
			f.main(() -> { f.preview.finish(); f.preview.finish(); assertEquals(1, f.web.backs); assertEquals(0, f.web.copyBackForwardList().getCurrentIndex()); });
		}
	}
	@Test public void replacingForwardBranchAndReloadStillReturnToTheCorrectEntry() {
		try (Fixture f = new Fixture()) {
			f.load("A"); f.load("B"); f.begin(); f.main(() -> assertTrue(f.preview.commit(f.web::goBack)));
			f.await(() -> f.web.getUrl().endsWith("/A") && !f.preview.isBusy());
			SystemClock.sleep(350); f.load("C"); f.main(() -> f.web.reload()); SystemClock.sleep(500); f.begin();
			f.main(() -> assertTrue(f.preview.commit(f.web::goBack)));
			f.await(() -> f.web.backs == 2 && f.web.getUrl().endsWith("/A") && !f.preview.isBusy());
			f.main(() -> assertEquals(2, f.web.copyBackForwardList().getSize()));
		}
	}
	@Test public void errorCleanupAndLateVisualCallbacksCannotResurrectOldPixels() {
		try (Fixture f = new Fixture()) {
			f.load("A"); f.load("B"); f.begin();
			f.main(() -> { f.preview.progress(.8f); f.preview.pageReady(); f.preview.clear(); });
			SystemClock.sleep(350);
			f.main(() -> { assertFalse(f.preview.isBusy()); assertFalse(f.preview.start(false, () -> true)); assertEquals(0, f.web.backs); });
		}
	}
	@Test public void repeatedUrlEntriesKeepDistinctPixelsAndBackDestinations() {
		try (Fixture f = new Fixture()) {
			f.load("A"); f.load("B"); f.load("A"); f.begin();
			f.main(() -> { assertEquals(2, f.web.copyBackForwardList().getCurrentIndex()); assertTrue(f.preview.commit(f.web::goBack)); });
			f.await(() -> f.web.getUrl().endsWith("/B") && !f.preview.isBusy()); f.begin();
			f.main(() -> {
				f.preview.progress(1f);
				android.graphics.Bitmap pixels = android.graphics.Bitmap.createBitmap(128, 128, android.graphics.Bitmap.Config.ARGB_8888);
				try {
					android.graphics.Canvas canvas = new android.graphics.Canvas(pixels);
					canvas.scale(128f / f.web.getWidth(), 128f / f.web.getHeight()); f.web.draw(canvas);
					int color = pixels.getPixel(64, 100); assertTrue(android.graphics.Color.red(color) > android.graphics.Color.blue(color));
				} finally { pixels.recycle(); }
				assertTrue(f.preview.commit(f.web::goBack));
			});
			f.await(() -> f.web.copyBackForwardList().getCurrentIndex() == 0 && !f.preview.isBusy());
			f.main(() -> assertEquals(2, f.web.backs));
		}
	}
}
