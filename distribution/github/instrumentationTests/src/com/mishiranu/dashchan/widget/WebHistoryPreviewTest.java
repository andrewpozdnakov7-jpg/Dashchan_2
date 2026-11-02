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
		boolean allowReady = true;
		final java.util.concurrent.atomic.AtomicInteger responses = new java.util.concurrent.atomic.AtomicInteger();
		Object old;
		Fixture() { this(false); }
		Fixture(boolean patterned) {
			Assume.assumeTrue(android.os.Build.VERSION.SDK_INT >= 33);
			activity = ActivityScenario.launch(StandardContentTestActivity.class);
			activity.onActivity(a -> {
				old = Preferences.PREFERENCES.getAll().get(Preferences.KEY_PREDICTIVE_BACK);
				Preferences.PREFERENCES.edit().put(Preferences.KEY_PREDICTIVE_BACK, true).close();
				web = new Browser(a); a.setContentView(web); preview = new WebHistoryPreview(web, () -> true);
				web.setWebViewClient(new WebViewClient() {
					@Override public WebResourceResponse shouldInterceptRequest(WebView view, WebResourceRequest request) {
						String color = request.isForMainFrame() && responses.incrementAndGet() == 1 ? "#cc3333" : "#3333cc";
						String background = patterned ? "linear-gradient(to bottom," + color + ",#33cc33)" : color;
						String html = "<html><body style='margin:0;background:" + background + ";height:8000px'>local fixture</body></html>";
						return new WebResourceResponse("text/html", "UTF-8", new ByteArrayInputStream(html.getBytes(StandardCharsets.UTF_8)));
					}
					@Override public void onPageStarted(WebView view, String url, android.graphics.Bitmap icon) {
						if (view == web) { starts++; preview.pageStarted(); }
					}
					@Override public void doUpdateVisitedHistory(WebView view, String url, boolean reload) {
						if (view == web) preview.historyUpdated(reload);
					}
					@Override public void onPageFinished(WebView view, String url) {
						if (view == web && allowReady && url.equals(view.getUrl())) preview.pageReady();
					}
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
		void scrollAndPrepare(int y) {
			main(() -> web.scrollTo(0, y));
			await(() -> {
				try {
					java.lang.reflect.Method method = WebHistoryPreview.class.getDeclaredMethod("current"); method.setAccessible(true);
					Object entry = method.invoke(preview);
					return prepared() && (int) field(entry, "scrollY") == web.getScrollY();
				} catch (ReflectiveOperationException e) { throw new AssertionError(e); }
			});
		}
		@Override public void close() {
			main(() -> {
				preview.close(); web.stopLoading(); web.destroy();
				if (old instanceof Boolean) Preferences.PREFERENCES.edit().put(Preferences.KEY_PREDICTIVE_BACK, (Boolean) old).close();
				else Preferences.PREFERENCES.edit().remove(Preferences.KEY_PREDICTIVE_BACK).close();
			}); activity.close();
		}
	}
	private static Object field(Object owner, String name) {
		try { java.lang.reflect.Field field = owner.getClass().getDeclaredField(name); field.setAccessible(true); return field.get(owner); }
		catch (ReflectiveOperationException e) { throw new AssertionError(e); }
	}
	private static HistoryBackPreview acceptAndPause(Fixture f, Runnable action) {
		assertTrue(f.preview.commit(action));
		HistoryBackPreview session = (HistoryBackPreview) field(f.preview, "preview");
		android.animation.ValueAnimator animator = (android.animation.ValueAnimator) field(field(session, "transition"), "animator");
		assertNotNull(animator); animator.pause();
		return session;
	}
	private static android.graphics.Bitmap pixels(WebView web) {
		android.graphics.Bitmap pixels = android.graphics.Bitmap.createBitmap(128, 256, android.graphics.Bitmap.Config.ARGB_8888);
		android.graphics.Canvas canvas = new android.graphics.Canvas(pixels);
		canvas.scale(128f / web.getWidth(), 256f / web.getHeight());
		canvas.translate(-web.getScrollX(), -web.getScrollY()); web.draw(canvas);
		return pixels;
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
			f.main(() -> { f.preview.progress(.6f); assertTrue(f.preview.commit(f.web::goBack)); assertTrue(f.preview.commit(f.web::goBack)); });
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
	@Test public void acceptedBackCannotNavigateANewDocumentAndItsCompletionIsConsumed() {
		Assume.assumeTrue(android.animation.ValueAnimator.areAnimatorsEnabled());
		try (Fixture f = new Fixture()) {
			f.load("A"); f.load("B"); f.begin(); HistoryBackPreview[] old = {null};
			f.main(() -> old[0] = acceptAndPause(f, f.web::goBack));
			f.load("C");
			f.main(() -> {
				old[0].close(); old[0].close();
				assertTrue(f.preview.commit(f.web::goBack)); // consumed old gesture: caller must not fall back
				assertEquals(0, f.web.backs); assertEquals(2, f.web.copyBackForwardList().getCurrentIndex());
				assertFalse(f.preview.isBusy());
			});
			f.begin(); f.main(() -> { assertTrue(f.preview.commit(f.web::goBack)); f.preview.finish(); });
			f.await(() -> f.web.backs == 1 && f.web.getUrl().endsWith("/B"));
		}
	}
	@Test public void reloadingTheSameUrlInvalidatesAnAcceptedOldDocument() {
		Assume.assumeTrue(android.animation.ValueAnimator.areAnimatorsEnabled());
		try (Fixture f = new Fixture()) {
			f.load("A"); f.load("B"); f.begin(); int[] starts = {0};
			f.main(() -> { acceptAndPause(f, f.web::goBack); starts[0] = f.starts; f.web.reload(); });
			f.await(() -> f.starts > starts[0] && f.web.getProgress() == 100 && f.prepared());
			f.main(() -> {
				f.preview.finish(); assertEquals(0, f.web.backs);
				assertEquals(1, f.web.copyBackForwardList().getCurrentIndex()); assertFalse(f.preview.isBusy());
			});
		}
	}
	@Test public void clearHistoryAndDestroyedSessionCannotExecuteAnAcceptedBack() {
		Assume.assumeTrue(android.animation.ValueAnimator.areAnimatorsEnabled());
		for (boolean replace : new boolean[]{false, true}) try (Fixture f = new Fixture()) {
			f.load("A"); f.load("B"); f.begin();
			f.main(() -> {
				Browser oldWeb = f.web; WebHistoryPreview oldPreview = f.preview;
				HistoryBackPreview session = acceptAndPause(f, oldWeb::goBack);
				if (replace) {
					oldPreview.close(); oldWeb.stopLoading(); oldWeb.destroy();
					android.app.Activity activity = (android.app.Activity) oldWeb.getContext();
					f.web = new Browser(activity); activity.setContentView(f.web);
					f.preview = new WebHistoryPreview(f.web, () -> true);
				} else { oldWeb.clearHistory(); oldPreview.clear(); }
				session.close(); oldPreview.finish();
				assertTrue(oldPreview.commit(oldWeb::goBack));
				assertEquals(0, oldWeb.backs); assertEquals(0, f.web.backs); assertFalse(oldPreview.isBusy());
			});
		}
	}
	@Test public void ordinaryFinishAndPauseExecuteAStillValidAcceptedBackOnce() {
		Assume.assumeTrue(android.animation.ValueAnimator.areAnimatorsEnabled());
		for (boolean pause : new boolean[]{false, true}) try (Fixture f = new Fixture()) {
			f.load("A"); f.load("B"); f.begin();
			f.main(() -> {
				acceptAndPause(f, f.web::goBack);
				if (pause) f.preview.pause(); else f.preview.finish();
				f.preview.finish(); assertTrue(f.preview.commit(f.web::goBack));
				assertEquals(1, f.web.backs); assertFalse(f.preview.isBusy());
			});
			f.await(() -> f.web.getUrl().endsWith("/A"));
		}
	}
	@Test public void freshGestureWithoutARealPreviousEntryStillAllowsTheOwnersNormalBack() {
		try (Fixture f = new Fixture()) {
			f.load("A"); f.load("B"); f.begin();
			f.main(() -> { assertTrue(f.preview.commit(f.web::goBack)); f.preview.finish(); });
			f.await(() -> f.web.getUrl().endsWith("/A") && !f.web.canGoBack() && !f.preview.isBusy());
			f.main(() -> {
				assertTrue(f.preview.commit(f.web::goBack)); // duplicate completion of the accepted gesture
				assertFalse(f.preview.start(true, () -> true)); // new gesture, no real history preview
				assertFalse(f.preview.commit(f.web::goBack)); // owner may now use parentUrl / normal Back
				assertEquals(1, f.web.backs);
			});
		}
	}
	@Test public void holdingPixelsStayInViewportAcrossDifferentRestoredScrollUntilReadiness() {
		Assume.assumeTrue(android.animation.ValueAnimator.areAnimatorsEnabled());
		try (Fixture f = new Fixture(true)) {
			f.load("A"); f.scrollAndPrepare(120); f.load("B"); f.scrollAndPrepare(620); f.begin();
			android.graphics.Bitmap[] before = {null}; HistoryBackPreview[] session = {null}; int[] oldScroll = {0};
			try {
				f.main(() -> {
					f.allowReady = false; oldScroll[0] = f.web.getScrollY();
					assertTrue(f.preview.commit(() -> { before[0] = pixels(f.web); f.web.goBack(); }));
					session[0] = (HistoryBackPreview) field(f.preview, "preview");
					((PredictiveTransition) field(session[0], "transition")).finish();
				});
				f.await(() -> f.web.getUrl().endsWith("/A") && f.web.getProgress() == 100
						&& f.web.copyBackForwardList().getCurrentIndex() == 0);
				f.main(() -> {
					assertTrue(session[0].isBusy()); assertTrue((boolean) field(session[0], "holding"));
					assertNotEquals(oldScroll[0], f.web.getScrollY());
					android.graphics.drawable.Drawable layer = (android.graphics.drawable.Drawable) field(session[0], "layer");
					assertEquals(f.web.getScrollY(), layer.getBounds().top);
					android.graphics.Bitmap after = pixels(f.web);
					try {
						for (int y : new int[]{16, 64, 128, 192, 240}) {
							int a = before[0].getPixel(64, y), b = after.getPixel(64, y);
							assertTrue(Math.abs(android.graphics.Color.red(a) - android.graphics.Color.red(b)) <= 2);
							assertTrue(Math.abs(android.graphics.Color.green(a) - android.graphics.Color.green(b)) <= 2);
							assertTrue(Math.abs(android.graphics.Color.blue(a) - android.graphics.Color.blue(b)) <= 2);
						}
					} finally { after.recycle(); }
					assertEquals(1, f.web.backs); f.allowReady = true; f.preview.pageReady();
				});
				f.await(() -> !f.preview.isBusy());
			} finally { if (before[0] != null) before[0].recycle(); }
		}
	}
}
