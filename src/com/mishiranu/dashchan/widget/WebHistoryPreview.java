package com.mishiranu.dashchan.widget;

import android.view.View;
import android.webkit.WebBackForwardList;
import android.webkit.WebHistoryItem;
import android.webkit.WebView;
import com.mishiranu.dashchan.content.Preferences;
import com.mishiranu.dashchan.util.InterfaceMotion;
import com.mishiranu.dashchan.util.SharedPreferences;
import java.util.ArrayList;
import java.util.Objects;
import java.util.function.BooleanSupplier;

/** In-memory last-known web pixels, not DOM or a second browsing session. UI thread only. */
public final class WebHistoryPreview implements AutoCloseable {
	private static final int MAX_ENTRIES = 128, MAX_IMAGES = 4;
	private static final class Entry {
		final String url, original;
		BackPreviewFrame frame;
		int scrollX, scrollY;
		Entry(WebHistoryItem item) { url = item.getUrl(); original = item.getOriginalUrl(); }
		boolean matches(WebHistoryItem item) {
			return Objects.equals(url, item.getUrl()) && Objects.equals(original, item.getOriginalUrl());
		}
		void release() { if (frame != null) { frame.release(); frame = null; } }
	}
	private final WebView web;
	private final BooleanSupplier permitted;
	private final PreviewMemoryPolicy memory;
	private final ArrayList<Entry> entries = new ArrayList<>();
	private int index = -1;
	private long generation, revision, visualRequest;
	private boolean loading, visuallyReady, closed, paused;
	private HistoryBackPreview preview;
	private Entry destination;
	private Entry loadingSource;
	private final Runnable capture = this::requestVisualFrame;
	private final android.view.ViewTreeObserver.OnScrollChangedListener scroll = () -> {
		if (visuallyReady && !isBusy()) scheduleCapture();
	};
	private final SharedPreferences.Listener preferences = this::preferencesChanged;
	private void preferencesChanged(String key) {
		if (key != null && !Preferences.KEY_PREDICTIVE_BACK.equals(key) && !Preferences.KEY_NEW_INTERFACE_MOTION.equals(key)
				&& !key.contains("theme") && !Preferences.KEY_REDDIT_WEB_READER_STYLE.equals(key)
				&& !Preferences.KEY_REDDIT_BOARD_STYLE.equals(key) && !Preferences.KEY_REDDIT_WEB_READER_ENABLED.equals(key)) return;
		clear(); if (enabled() && web.getProgress() == 100 && web.isShown()) pageReady();
	}
	private final View.OnAttachStateChangeListener attachment = new View.OnAttachStateChangeListener() {
		@Override public void onViewAttachedToWindow(View view) {}
		@Override public void onViewDetachedFromWindow(View view) { clear(); }
	};
	private final View.OnLayoutChangeListener layout = this::layoutChanged;
	private void layoutChanged(View v, int l, int t, int r, int b, int ol, int ot, int or, int ob) {
		if (r - l != or - ol || b - t != ob - ot) { clear(); if (web.getProgress() == 100) pageReady(); }
	}
	public WebHistoryPreview(WebView web, BooleanSupplier permitted) {
		this.web = web; this.permitted = permitted;
		memory = new PreviewMemoryPolicy(web.getContext(), this::clear);
		web.getViewTreeObserver().addOnScrollChangedListener(scroll);
		web.addOnAttachStateChangeListener(attachment); web.addOnLayoutChangeListener(layout);
		Preferences.PREFERENCES.register(preferences);
	}
	private boolean enabled() { return !closed && !paused && permitted.getAsBoolean() && InterfaceMotion.isPredictiveBackEnabled(); }
	public boolean isBusy() { return preview != null && preview.isBusy(); }
	public boolean isCommitting() { return preview != null && preview.isCommitting(); }
	public boolean isTracking() { return preview != null && preview.isTracking(); }
	public void pageStarted() {
		loadingSource = current();
		generation++; visualRequest++; loading = true; visuallyReady = false; web.removeCallbacks(capture);
		if (preview != null && !preview.isCommitting()) { preview.close(); preview = null; }
	}
	/** Only a full document navigation with a matching prefix carries entry identities forward. */
	public void historyUpdated(boolean reload) {
		if (!enabled()) { clear(); return; }
		WebBackForwardList history = web.copyBackForwardList();
		if (history.getSize() == 0 || history.getSize() > MAX_ENTRIES) { clear(); return; }
		int next = history.getCurrentIndex();
		boolean same = matches(history);
		if (same && next == index && !reload) return;
		boolean expectedBack = destination != null && next == index - 1 && same
				&& entries.get(next) == destination;
		if (expectedBack) {
			index = next;
		} else if (loading && next == index + 1 && prefixMatches(history, index + 1)) {
			truncate(index + 1); entries.add(new Entry(history.getItemAtIndex(next))); index = next;
			// Some providers include forward entries in the callback; do not infer their identity.
			for (int i = next + 1; i < history.getSize(); i++) entries.add(new Entry(history.getItemAtIndex(i)));
		} else if (loading && next == index && prefixMatches(history, index)) {
			// Reload / redirect replace current identity, never reuse its old pixels.
			truncate(index); for (int i = next; i < history.getSize(); i++) entries.add(new Entry(history.getItemAtIndex(i)));
		} else {
			// SPA, restoreState, clearHistory, unsolicited traversal or ambiguous replacement.
			truncate(0); for (int i = 0; i < history.getSize(); i++) entries.add(new Entry(history.getItemAtIndex(i)));
			index = next;
		}
		revision++; visualRequest++;
		if (preview != null && preview.isCommitting() && destination != current()) endPreview();
		if (!loading) { visuallyReady = false; if (!isCommitting()) destination = null; }
	}
	private boolean prefixMatches(WebBackForwardList history, int count) {
		if (count < 0 || count > entries.size() || count > history.getSize()) return false;
		for (int i = 0; i < count; i++) if (!entries.get(i).matches(history.getItemAtIndex(i))) return false;
		return true;
	}
	private boolean matches(WebBackForwardList history) {
		return history.getSize() == entries.size() && prefixMatches(history, entries.size());
	}
	private boolean currentMatches() {
		WebBackForwardList history = web.copyBackForwardList();
		return history.getCurrentIndex() == index && matches(history);
	}
	private Entry current() { return index >= 0 && index < entries.size() ? entries.get(index) : null; }
	private void truncate(int size) { while (entries.size() > size) entries.remove(entries.size() - 1).release(); }
	/** Private: after finish; Reddit: after its complete asynchronous styling/reveal chain. */
	public void pageReady() {
		if (!enabled() || !web.isShown()) return;
		historyUpdated(false);
		if (loading && current() != null && current() == loadingSource && current() != destination) current().release();
		if (current() != null) requestVisualFrame();
	}
	private void scheduleCapture() { web.removeCallbacks(capture); web.postDelayed(capture, 150); }
	private void requestVisualFrame() {
		if (!enabled() || !web.isAttachedToWindow() || !web.isShown() || current() == null || !currentMatches()
				|| preview != null && preview.isTracking()) return;
		long document = generation, historyRevision = revision, request = ++visualRequest;
		Entry entry = current(); int x = web.getScrollX(), y = web.getScrollY();
		web.postVisualStateCallback(request, new WebView.VisualStateCallback() {
			@Override public void onComplete(long id) {
				if (!enabled() || document != generation || historyRevision != revision || request != visualRequest
						|| current() != entry || !currentMatches() || !web.isShown()) return;
				loading = false; visuallyReady = true;
				if (preview != null && preview.isCommitting()) {
					if (entry == destination) preview.destinationReady(); else endPreview();
					scheduleCapture(); return;
				}
				web.postOnAnimation(() -> {
					if (!enabled() || document != generation || historyRevision != revision || request != visualRequest
							|| current() != entry || !currentMatches() || x != web.getScrollX() || y != web.getScrollY() || isBusy()) return;
					entry.release(); // Return its reservation before replacing it; active sessions hold their own lease.
					entry.frame = BackPreviewFrame.capture(web, true); entry.scrollX = x; entry.scrollY = y;
					evictImages(entry);
				});
			}
		});
	}
	private void evictImages(Entry keep) {
		int count = 0; for (Entry entry : entries) if (entry.frame != null) count++;
		for (Entry entry : entries) if (count > MAX_IMAGES && entry != keep && entry.frame != null) { entry.release(); count--; }
	}
	public boolean start(boolean fromLeft, BooleanSupplier routeValid) {
		if (isCommitting()) return false;
		endPreview(); destination = null;
		if (!enabled() || loading || !visuallyReady || index <= 0 || !currentMatches()) return false;
		Entry source = current(), target = entries.get(index - 1);
		if (target.frame != null && target.frame.height != web.getHeight()) return false;
		if (source.scrollX != web.getScrollX() || source.scrollY != web.getScrollY()) return false;
		long document = generation, routeRevision = revision;
		preview = HistoryBackPreview.start(web, target.frame, source.frame, fromLeft, () -> enabled()
				&& generation == document && revision == routeRevision && current() == source && routeValid.getAsBoolean()
				&& source.scrollX == web.getScrollX() && source.scrollY == web.getScrollY());
		if (preview != null) destination = target;
		return preview != null;
	}
	public void progress(float fraction) { if (preview != null) preview.progress(fraction); }
	public void cancel() { if (preview != null) preview.cancel(); destination = null; }
	public void finish() { endPreview(); destination = null; }
	public boolean commit(Runnable goBack) {
		if (!isTracking() || !currentMatches()) { endPreview(); return false; }
		return preview.commit(goBack);
	}
	private void endPreview() { HistoryBackPreview old = preview; preview = null; if (old != null) old.close(); }
	public void clear() {
		endPreview(); destination = loadingSource = null; generation++; revision++; visualRequest++;
		visuallyReady = loading = false; web.removeCallbacks(capture); truncate(0); index = -1;
	}
	public void pause() { paused = true; clear(); }
	public void resume() { paused = false; if (web.getProgress() == 100) pageReady(); }
	@Override public void close() {
		if (closed) return;
		clear(); closed = true; memory.close(); Preferences.PREFERENCES.unregister(preferences);
		web.getViewTreeObserver().removeOnScrollChangedListener(scroll);
		web.removeOnAttachStateChangeListener(attachment); web.removeOnLayoutChangeListener(layout);
	}
}
