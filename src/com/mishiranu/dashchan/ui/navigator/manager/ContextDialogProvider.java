package com.mishiranu.dashchan.ui.navigator.manager;

import android.os.CancellationSignal;
import android.content.res.Configuration;
import android.view.View;
import android.widget.TextView;
import androidx.recyclerview.widget.RecyclerView;
import androidx.core.view.OneShotPreDrawListener;
import chan.content.Chan;
import com.mishiranu.dashchan.R;
import com.mishiranu.dashchan.content.Preferences;
import com.mishiranu.dashchan.content.context.ContextGraph;
import com.mishiranu.dashchan.content.context.ContextSource;
import com.mishiranu.dashchan.content.database.PagesDatabase;
import com.mishiranu.dashchan.content.model.GalleryItem;
import com.mishiranu.dashchan.content.model.Post;
import com.mishiranu.dashchan.content.model.PostItem;
import com.mishiranu.dashchan.content.model.PostNumber;
import com.mishiranu.dashchan.util.ConcurrentUtils;
import com.mishiranu.dashchan.util.ResourceUtils;
import com.mishiranu.dashchan.ui.DialogMenu;
import com.mishiranu.dashchan.widget.ListPosition;
import com.mishiranu.dashchan.widget.InterfaceAppearance;
import com.mishiranu.dashchan.widget.BackPreviewFrame;
import com.mishiranu.dashchan.widget.HistoryBackPreview;
import com.mishiranu.dashchan.util.InterfaceMotion;
import com.mishiranu.dashchan.media.VideoDiagnostics;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;

/** Read-only context on the existing dialog stack. Factory retains only bounded navigation state. */
final class ContextDialogProvider extends DialogUnit.DialogProvider<ContextDialogProvider>
		implements UiManager.PostsProvider {
	static final class Factory extends DialogUnit.DialogProvider.Factory<ContextDialogProvider> {
		static final class Viewport {
			final ContextGraph.Key anchor;
			final int offset, width, direction;
			final Configuration configuration;
			Viewport(ContextGraph.Key anchor, int offset, RecyclerView list) {
				this.anchor = anchor; this.offset = offset;
				width = list.getWidth(); direction = list.getLayoutDirection();
				configuration = new Configuration(list.getResources().getConfiguration());
			}
			boolean compatible(RecyclerView list) {
				return width > 0 && width == list.getWidth() && direction == list.getLayoutDirection()
						&& configuration.equals(list.getResources().getConfiguration());
			}
		}
		static final class Entry {
			final ContextGraph.Key key;
			final Viewport viewport;
			Entry(ContextGraph.Key key, Viewport viewport) { this.key = key; this.viewport = viewport; }
		}
		ContextGraph.Key target;
		final ArrayDeque<Entry> history = new ArrayDeque<>();
		long cacheEpoch = PagesDatabase.getInstance().getContextEpoch();
		boolean cacheErased;
		Viewport viewport;
		final PostNumber original;
		Factory(String chan, PostItem post) {
			target = ContextSource.key(chan, post.getBoardName(), post.getThreadNumber(), post.getPostNumber());
			original = post.getOriginalPostNumber();
		}
		@Override public ContextDialogProvider create(UiManager ui, UiManager.ConfigurationSet source) {
			return new ContextDialogProvider(ui, source, this);
		}
	}
	private final UiManager.ConfigurationSet source;
	private final Factory factory;
	private ExecutorService worker = ConcurrentUtils.newSingleThreadPool(3000, "DiscussionContext", null);
	private final List<PostItem> visible = new ArrayList<>();
	private final Map<PostNumber, CharSequence> placeholders = new HashMap<>();
	private final Set<ContextGraph.Key> revealed = new HashSet<>();
	private final List<ContextGraph.Key> retained = new ArrayList<>();
	private CancellationSignal signal;
	private ContextSource.Snapshot snapshot;
	private RecyclerView list;
	private TextView status;
	private int generation;
	private long loadStarted;
	private OneShotPreDrawListener restoreDraw;
	private boolean closed, loading, failed, cacheErased, updatesAvailable, selectedAnchor = true;
	private ContextGraph.Result displayedResult;
	private final Runnable invalidateCache;
	private View previewHost;
	private BackPreviewFrame currentFrame;
	private long scrollRevision, preparedScrollRevision;
	private HistoryBackPreview backPreview;
	private com.mishiranu.dashchan.widget.PreviewMemoryPolicy previewMemory;
	private final java.util.LinkedHashMap<Factory.Entry, SavedPreview> previews = new java.util.LinkedHashMap<>();
	private final com.mishiranu.dashchan.widget.BackPreviewBudget.Cache previewCache =
			new com.mishiranu.dashchan.widget.BackPreviewBudget.Cache() {
		@Override public boolean evictOldest() { return evictPreview(false); }
		@Override public boolean evictUnprotected(String reason) { return evictPreview(true); }
	};
	private static final class SavedPreview {
		final BackPreviewFrame frame;
		final long epoch;
		final PagesDatabase.Cache.State revision;
		SavedPreview(BackPreviewFrame frame, ContextSource.Snapshot data) {
			this.frame = frame; epoch = data.epoch(); revision = data.revision();
		}
	}
	private final Runnable prepareFrame = () -> {
		if (closed || previewHost == null || loading || failed || restoreDraw != null || displayedResult == null
				|| backPreview != null && backPreview.isBusy()) return;
		if (currentFrame != null) currentFrame.release();
		currentFrame = BackPreviewFrame.capture(previewHost, false); preparedScrollRevision = scrollRevision;
	};
	private final android.view.ViewTreeObserver.OnScrollChangedListener previewScroll = () -> {
		scrollRevision++;
		if (backPreview != null && !backPreview.isCommitting()) backPreview.close();
		scheduleFrame();
	};
	private final com.mishiranu.dashchan.util.SharedPreferences.Listener previewPreferences = key -> {
		clearPreviews(); scheduleFrame();
	};
	@Override public void onViewBound(View content) {
		previewHost = content;
		if (previewMemory != null) previewMemory.close();
		previewMemory = new com.mishiranu.dashchan.widget.PreviewMemoryPolicy(content.getContext(),
				this::releasePreviewImages, previewCache);
		content.getViewTreeObserver().addOnScrollChangedListener(previewScroll);
		Preferences.PREFERENCES.register(previewPreferences); scheduleFrame();
	}
	private void scheduleFrame() {
		if (previewHost != null) { previewHost.removeCallbacks(prepareFrame); previewHost.postDelayed(prepareFrame, 120); }
	}
	private void clearPreviews() {
		if (backPreview != null) { HistoryBackPreview old = backPreview; backPreview = null; old.close(); }
		releasePreviewImages("owner_invalidated");
	}
	/** Cache ownership only; HistoryBackPreview keeps its own leases and navigation contract. */
	private void releasePreviewImages(String reason) {
		if (previewHost != null) previewHost.removeCallbacks(prepareFrame);
		int removed = previews.size() + (currentFrame != null ? 1 : 0);
		if (currentFrame != null) { currentFrame.release(); currentFrame = null; }
		for (SavedPreview saved : previews.values()) saved.frame.release(); previews.clear();
		record("snapshots_removed reason=" + reason + " removed_images=" + removed);
	}
	private boolean evictPreview(boolean protectNearest) {
		java.util.Iterator<java.util.Map.Entry<Factory.Entry, SavedPreview>> iterator = previews.entrySet().iterator();
		while (iterator.hasNext()) {
			java.util.Map.Entry<Factory.Entry, SavedPreview> entry = iterator.next();
			if (!protectNearest || entry.getKey() != factory.history.peekLast()) {
				entry.getValue().frame.release(); iterator.remove();
				record("snapshot_removed reason=budget_pressure"); return true;
			}
		}
		if (!protectNearest && currentFrame != null) {
			currentFrame.release(); currentFrame = null;
			record("snapshot_removed reason=budget_pressure_current"); return true;
		}
		return false;
	}
	@Override public void clearBackPreviews() { clearPreviews(); }
	@Override public HistoryBackPreview startBackPreview(boolean fromLeft) {
		if (!canHandleBack() || previewHost == null || loading || failed || restoreDraw != null || preparedScrollRevision != scrollRevision
				|| !InterfaceMotion.isPredictiveBackEnabled()) return null;
		Factory.Entry entry = factory.history.peekLast(); SavedPreview saved = previews.get(entry);
		if (entry.viewport != null && (list == null || !entry.viewport.compatible(list))) {
			record("preview_rejected reason=viewport_incompatible"); return null;
		}
		if (saved == null || saved.epoch != PagesDatabase.getInstance().getContextEpoch()
				|| !saved.revision.equals(PagesDatabase.getInstance().getCacheState(new PagesDatabase.ThreadKey(
						entry.key.source(), entry.key.board(), entry.key.thread())))) return null;
		long revision = generation, scroll = scrollRevision;
		backPreview = HistoryBackPreview.start(previewHost, saved.frame, currentFrame, fromLeft,
				() -> !closed && generation == revision && factory.history.peekLast() == entry && !loading
				&& scrollRevision == scroll && InterfaceMotion.isPredictiveBackEnabled()
				&& saved.epoch == PagesDatabase.getInstance().getContextEpoch()
				&& saved.revision.equals(PagesDatabase.getInstance().getCacheState(new PagesDatabase.ThreadKey(
						entry.key.source(), entry.key.board(), entry.key.thread()))));
		return backPreview;
	}

	private void onCacheInvalidated() {
		if (closed) return;
		clearPreviews();
		cancelWork(); cacheErased = true; snapshot = null; displayedResult = null;
		factory.cacheErased = true;
		visible.clear(); placeholders.clear(); retained.clear(); revealed.clear();
		failed = true; updateStatus(); switchState(DialogUnit.State.LIST, null);
	}

	private ContextDialogProvider(UiManager ui, UiManager.ConfigurationSet source, Factory factory) {
		super(ui, p -> configuration(source, p));
		this.source = source; this.factory = factory;
		invalidateCache = this::onCacheInvalidated;
		cacheErased = factory.cacheErased || factory.cacheEpoch != PagesDatabase.getInstance().getContextEpoch();
		PagesDatabase.getInstance().registerContextInvalidation(invalidateCache);
		selectedAnchor = factory.viewport == null;
		// Do not briefly render a potentially stale UI post before the local snapshot arrives.
		load();
	}
	private static UiManager.ConfigurationSet configuration(UiManager.ConfigurationSet source, ContextDialogProvider p) {
		UiManager.PostStateProvider readOnly = new UiManager.PostStateProvider() {
			@Override public boolean isHiddenResolve(PostItem post) {
				return !p.revealed.contains(p.key(post)) && source.postStateProvider.isHiddenResolve(post);
			}
			@Override public boolean isUserPost(PostNumber number) { return source.postStateProvider.isUserPost(number); }
		};
		UiManager.ConfigurationSet result = new UiManager.ConfigurationSet(source.chanName, source.replyable,
				p, readOnly, post -> {
					GalleryItem.Set gallery = new GalleryItem.Set(true);
					if (!readOnly.isHiddenResolve(post)) gallery.put(post.getPostNumber(), post.getAttachmentItems());
					return gallery;
				}, source.fragmentManager, source.stackInstance, null, p,
				false, true, false, false, true, null).copyDisplayStateFrom(source);
		result.contextCacheOnly = true;
		// Opening local context must not schedule translations or model downloads for newly read cards.
		result.showTranslatedComments = false;
		result.contextSelect = p::select;
		return result;
	}
	private ContextGraph.Key key(PostItem post) {
		return ContextSource.key(configurationSet.chanName, post.getBoardName(), post.getThreadNumber(), post.getPostNumber());
	}
	private boolean matches(PostItem post) { return factory.target.sameThread(key(post)); }
	@Override protected ContextDialogProvider getThis() { return this; }
	@Override public Iterator<PostItem> iterator() { return visible.iterator(); }
	@Override public PostItem findPostItem(PostNumber number) {
		for (PostItem post : visible) if (number.equals(post.getPostNumber()) && !placeholders.containsKey(number)) return post;
		return null;
	}
	@Override public CharSequence placeholder(PostItem post) { return placeholders.get(post.getPostNumber()); }
	@Override public void onPlaceholderClick(PostItem post) {
		if (closed || Preferences.isRemoveHiddenPosts() || snapshot == null) return;
		if (snapshot.posts().containsKey(key(post)) && source.postStateProvider.isHiddenResolve(post)) {
			new DialogMenu(uiManager.getContext()).add(R.string.context_show_hidden,
					() -> { clearPreviews(); revealed.add(key(post)); rebuild(); }).create().show();
		}
	}
	@Override public boolean canHandleBack() { return !closed && !factory.history.isEmpty(); }
	@Override public long backActionRevision() { return generation; }
	@Override public boolean onBackPressed() {
		if (closed || factory.history.isEmpty()) return false;
		Factory.Entry entry = factory.history.removeLast();
		SavedPreview saved = previews.remove(entry); if (saved != null) saved.frame.release();
		factory.target = entry.key; resetSelection();
		factory.viewport = entry.viewport; selectedAnchor = entry.viewport == null;
		load(); return true;
	}
	@Override public void onRequestUpdateDemandSet(UiManager.DemandSet demand, int index) {
		demand.lastInList = index == visible.size() - 1;
	}
	@Override public View createControls(RecyclerView recycler) {
		// The dialog stack may temporarily recycle a lower window while opening several quotes.
		boolean resume = closed;
		if (resume) {
			closed = false;
			cacheErased |= factory.cacheEpoch != PagesDatabase.getInstance().getContextEpoch();
			worker = ConcurrentUtils.newSingleThreadPool(3000, "DiscussionContext", null);
			PagesDatabase.getInstance().registerContextInvalidation(invalidateCache);
			selectedAnchor = factory.viewport == null;
		}
		list = recycler;
		status = new TextView(uiManager.getContext());
		int pad = (int) (12 * ResourceUtils.obtainDensity(status));
		status.setPadding(pad, pad, pad, 0);
		if (resume) load();
		updateStatus(); return status;
	}
	private void updateStatus() {
		if (status == null) return;
		int message = cacheErased ? R.string.context_cache_cleared
				: failed ? R.string.context_read_error : updatesAvailable ? R.string.context_updates : 0;
		status.setText(message != 0 ? uiManager.getContext().getText(message) : "");
		status.setVisibility(message != 0 ? View.VISIBLE : View.GONE);
		InterfaceAppearance.discussionStatus(status, failed && !cacheErased);
	}
	private void appendStatus(int message) {
		if (status == null) return;
		if (status.length() > 0) status.append("\n");
		status.append(uiManager.getContext().getText(message));
		status.setVisibility(View.VISIBLE);
		InterfaceAppearance.discussionStatus(status, failed && !cacheErased);
	}
	private void cancelWork() {
		if (restoreDraw != null) { restoreDraw.removeListener(); restoreDraw = null; }
		if (currentFrame != null) { currentFrame.release(); currentFrame = null; }
		if (backPreview != null && !backPreview.isCommitting()) backPreview.close();
		generation++;
		if (signal != null) signal.cancel();
		loading = false;
	}
	private void load() {
		if (closed) return;
		loadStarted = android.os.SystemClock.elapsedRealtime();
		cancelWork(); failed = false; loading = true; updateStatus();
		int current = generation;
		signal = new CancellationSignal(); CancellationSignal token = signal;
		ContextGraph.Key target = factory.target;
		PagesDatabase db = PagesDatabase.getInstance();
		PagesDatabase.ThreadKey thread = new PagesDatabase.ThreadKey(target.source(), target.board(), target.thread());
		PagesDatabase.Cache.State seedRevision = db.getCacheState(thread);
		long seedEpoch = db.getContextEpoch();
		if (cacheErased) { loading = false; updateStatus(); return; }
		ContextSource.Snapshot cached = snapshot != null ? snapshot : source.postsProvider.getContextSnapshot();
		if (ContextSource.reusable(cached, target, seedRevision, seedEpoch)) {
			snapshot = cached;
			android.util.Log.d("DiscussionContext", "snapshot source=reused_graph posts=" + cached.posts().size());
			rebuild(); return;
		}
		snapshot = null;
		ContextSource.Seed seeds = source.postsProvider.getContextSeed(target);
		worker.execute(() -> {
			try {
				// Bounded local-cache scan runs on the worker, never a network request.
				ContextSource.Snapshot loaded = ContextSource.load(Chan.get(target.source()), target, seeds, token);
				ConcurrentUtils.HANDLER.post(() -> {
					if (closed || current != generation || token.isCanceled()) return;
					loading = false;
					if (seedEpoch != db.getContextEpoch() || loaded.epoch() != seedEpoch) { invalidateCache.run(); return; }
					if (loaded.changed() || !seedRevision.equals(loaded.revision())
							|| !loaded.revision().equals(db.getCacheState(thread))) { failed = true; updateStatus(); return; }
					updatesAvailable = false; failed = false;
					source.postsProvider.setContextSnapshot(loaded);
					snapshot = loaded; rebuild();
				});
			} catch (RuntimeException e) {
				if (token.isCanceled()) return;
				android.util.Log.w("DiscussionContext", "local_read_failed type=" + e.getClass().getSimpleName());
				ConcurrentUtils.HANDLER.post(() -> {
					if (!closed && current == generation) { loading = false; failed = true; updateStatus(); }
				});
			}
		});
	}
	private void rebuild() {
		if (closed || snapshot == null) return;
		cancelWork(); loading = true; updateStatus();
		int current = generation; signal = new CancellationSignal(); CancellationSignal token = signal;
		ContextSource.Snapshot data = snapshot;
		ContextGraph.Key target = factory.target;
		List<ContextGraph.Key> keep = new ArrayList<>(retained);
		worker.execute(() -> {
			try {
				long started = android.os.SystemClock.elapsedRealtime();
				ContextGraph.Result result = data.graph().buildContext(target, keep, token::isCanceled);
				android.util.Log.d("DiscussionContext", "traversal cards=" + result.entries().size()
						+ " elapsedMs=" + (android.os.SystemClock.elapsedRealtime() - started));
				ConcurrentUtils.HANDLER.post(() -> {
					if (!closed && current == generation && !token.isCanceled()) apply(data, result);
				});
			} catch (java.util.concurrent.CancellationException ignored) {
			} catch (RuntimeException e) {
				android.util.Log.w("DiscussionContext", "graph_failed type=" + e.getClass().getSimpleName());
				ConcurrentUtils.HANDLER.post(() -> {
					if (!closed && current == generation) { loading = false; failed = true; updateStatus(); }
				});
			}
		});
	}
	private void apply(ContextSource.Snapshot data, ContextGraph.Result result) {
		long started = android.os.SystemClock.elapsedRealtime();
		if (data.epoch() != PagesDatabase.getInstance().getContextEpoch()) { invalidateCache.run(); return; }
		if (!data.revision().equals(PagesDatabase.getInstance().getCacheState(new PagesDatabase.ThreadKey(
				factory.target.source(), factory.target.board(), factory.target.thread())))) {
			loading = false; failed = true; updateStatus(); return;
		}
		displayedResult = result;
		Factory.Viewport viewport = factory.viewport != null ? factory.viewport : !selectedAnchor ? captureViewport() : null;
		visible.clear(); placeholders.clear(); retained.clear();
		Chan chan = Chan.get(factory.target.source());
		boolean hiddenGap = false;
		for (ContextGraph.Entry entry : result.entries()) {
			ContextGraph.Key key = entry.key(); retained.add(key);
			Post raw = data.posts().get(key);
			PostItem post = null;
			try { if (raw != null) post = PostItem.createPost(raw, chan, key.board(), key.thread(), factory.original); }
			catch (RuntimeException ignored) { /* Corrupt reference markup must not crash the reader. */ }
			if (post != null && configurationSet.postStateProvider.isHiddenResolve(post)) {
				hiddenGap = true;
				if (Preferences.isRemoveHiddenPosts()) continue;
				placeholders.put(post.getPostNumber(), uiManager.getContext().getText(R.string.context_hidden));
			} else if (post == null) {
				Post.Builder builder = new Post.Builder(); builder.number = ContextSource.number(key);
				post = PostItem.createPost(builder.build(false), chan, key.board(), key.thread(), factory.original);
				placeholders.put(post.getPostNumber(), uiManager.getContext().getString(R.string.context_unavailable, key.number()));
			}
			visible.add(post);
		}
		loading = false; updateStatus();
		if (hiddenGap) appendStatus(R.string.context_hidden);
		if (result.limited()) {
			appendStatus(R.string.context_limited);
		}
		switchState(DialogUnit.State.LIST, () -> restoreViewport(viewport));
		android.util.Log.d("DiscussionContext", "display cards=" + visible.size() + " bindMs="
				+ (android.os.SystemClock.elapsedRealtime() - started) + " totalMs="
				+ (android.os.SystemClock.elapsedRealtime() - loadStarted));
	}
	private void resetSelection() {
		factory.viewport = null; retained.clear(); revealed.clear(); selectedAnchor = true;
		displayedResult = null; visible.clear(); placeholders.clear();
		switchState(DialogUnit.State.LIST, null);
	}
	private void select(PostItem post) {
		if (closed || !matches(post) || placeholders.containsKey(post.getPostNumber())) return;
		if (backPreview != null && backPreview.isBusy()) return;
		if (factory.history.size() == 5) {
			SavedPreview old = previews.remove(factory.history.removeFirst()); if (old != null) old.frame.release();
		}
		Factory.Entry entry = new Factory.Entry(factory.target, captureViewport());
		BackPreviewFrame frame = previewHost != null && !loading && !failed && revealed.isEmpty() && displayedResult != null
				? BackPreviewFrame.capture(previewHost, false) : null;
		if (frame != null && snapshot != null) {
			while (previews.size() >= 3) { Factory.Entry first = previews.keySet().iterator().next(); previews.remove(first).frame.release(); }
			previews.put(entry, new SavedPreview(frame, snapshot));
		} else if (frame != null) frame.release();
		factory.history.addLast(entry); factory.target = key(post); resetSelection(); load();
	}
	private Factory.Viewport captureViewport() {
		ListPosition position = list != null ? ListPosition.obtain(list, null) : null;
		return position != null && position.position >= 0 && position.position < visible.size()
				? new Factory.Viewport(key(visible.get(position.position)), position.offset, list) : null;
	}
	private void record(String reason) {
		if (VideoDiagnostics.isExtendedRecording()) VideoDiagnostics.recordUi("predictive_context " + reason
				+ " generation=" + generation + " visible=" + visible.size() + " history=" + factory.history.size());
	}
	private int visiblePosition(ContextGraph.Key wanted) {
		for (int i = 0; i < visible.size(); i++) {
			PostItem post = visible.get(i);
			if (key(post).equals(wanted) && !placeholders.containsKey(post.getPostNumber())
					&& !configurationSet.postStateProvider.isHiddenResolve(post)) return i;
		}
		return -1;
	}
	private void restoreViewport(Factory.Viewport viewport) {
		if (list == null || closed) return;
		int owner = generation;
		RecyclerView recycler = list;
		ContextGraph.Key target = factory.target;
		if (restoreDraw != null) { restoreDraw.removeListener(); restoreDraw = null; }
		Runnable restore = () -> {
			if (closed || generation != owner || list != recycler || !factory.target.equals(target)) return;
			int position = -1, offset = 0;
			if (viewport != null && viewport.compatible(recycler)) {
				position = visiblePosition(viewport.anchor);
				if (position >= 0) offset = viewport.offset;
				else record("restore_fallback reason=anchor_missing_or_hidden");
			} else if (viewport != null) record("restore_fallback reason=viewport_incompatible");
			if (position < 0) position = visiblePosition(target);
			if (position < 0 && !visible.isEmpty()) {
				position = 0;
				for (int i = 0; i < visible.size(); i++) if (!placeholders.containsKey(visible.get(i).getPostNumber())) {
					position = i; break;
				}
			}
			if (position >= 0) new ListPosition(position, offset).apply(recycler);
			selectedAnchor = false;
			// Scroll-to-offset is consumed by layout. Hold the old pixels until that layout's pre-draw.
			restoreDraw = OneShotPreDrawListener.add(recycler, () -> {
				if (closed || generation != owner || list != recycler || !factory.target.equals(target)) return;
				restoreDraw = null;
				if (factory.viewport == viewport) factory.viewport = null;
				if (backPreview != null) backPreview.destinationReady();
				scheduleFrame();
			});
		};
		if (recycler.getWidth() > 0) restore.run();
		else restoreDraw = OneShotPreDrawListener.add(recycler, () -> {
			if (closed || generation != owner || list != recycler || !factory.target.equals(target)) return;
			restoreDraw = null; restore.run();
		});
	}
	@Override public boolean onItemClick(RecyclerView.ViewHolder holder, int position, PostItem post, boolean longClick) {
		if (placeholders.containsKey(post.getPostNumber())) return true;
		if (longClick) uiManager.interaction().handlePostContextMenu(configurationSet, post);
		else uiManager.view().handlePostForDoubleClick(holder.itemView);
		return true;
	}
	@Override public void onPostItemMessage(PostItem post, UiManager.Message message) {
		if (!matches(post) || closed) return;
		// Never reuse stale hide decisions or transient reveal overrides after a policy update.
		if (message == UiManager.Message.POST_INVALIDATE_ALL_VIEWS || message == UiManager.Message.PERFORM_SWITCH_HIDE
				|| message == UiManager.Message.PERFORM_HIDE_REPLIES || message == UiManager.Message.PERFORM_HIDE_NAME
				|| message == UiManager.Message.PERFORM_HIDE_SIMILAR) {
			clearPreviews();
			revealed.clear();
			for (PostItem item : visible) item.setHidden(PostItem.HideState.UNDEFINED, null);
			// Reapply visibility synchronously: no stale text while an asynchronous graph task is pending.
			cancelWork();
			updatesAvailable = true;
			if (snapshot != null && displayedResult != null) apply(snapshot, displayedResult);
			else { visible.clear(); placeholders.clear(); switchState(DialogUnit.State.LIST, null); }
			updateStatus();
		}
	}
	@Override public void onCancel() {
		if (closed) return;
		if (factory.viewport == null) factory.viewport = captureViewport();
		closed = true;
		clearPreviews();
		Preferences.PREFERENCES.unregister(previewPreferences);
		if (previewMemory != null) { previewMemory.close(); previewMemory = null; }
		if (previewHost != null) previewHost.getViewTreeObserver().removeOnScrollChangedListener(previewScroll);
		previewHost = null;
		PagesDatabase.getInstance().unregisterContextInvalidation(invalidateCache);
		cancelWork(); worker.shutdownNow(); snapshot = null; displayedResult = null;
		stateListener = null;
		visible.clear(); placeholders.clear(); list = null; status = null;
	}
}
