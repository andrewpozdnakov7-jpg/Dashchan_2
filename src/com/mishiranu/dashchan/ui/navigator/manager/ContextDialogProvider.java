package com.mishiranu.dashchan.ui.navigator.manager;

import android.os.CancellationSignal;
import android.view.View;
import android.widget.TextView;
import androidx.recyclerview.widget.RecyclerView;
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
		ContextGraph.Key target;
		final ArrayDeque<ContextGraph.Key> history = new ArrayDeque<>();
		long cacheEpoch = PagesDatabase.getInstance().getContextEpoch();
		boolean cacheErased;
		PostNumber anchor;
		int anchorOffset;
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
	private boolean closed, loading, failed, cacheErased, updatesAvailable, selectedAnchor = true;
	private ContextGraph.Result displayedResult;
	private final Runnable invalidateCache;

	private void onCacheInvalidated() {
		if (closed) return;
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
		selectedAnchor = factory.anchor == null;
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
					() -> { revealed.add(key(post)); rebuild(); }).create().show();
		}
	}
	@Override public boolean onBackPressed() {
		if (closed || factory.history.isEmpty()) return false;
		factory.target = factory.history.removeLast(); resetSelection(); load(); return true;
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
			selectedAnchor = factory.anchor == null;
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
	}
	private void appendStatus(int message) {
		if (status == null) return;
		if (status.length() > 0) status.append("\n");
		status.append(uiManager.getContext().getText(message));
		status.setVisibility(View.VISIBLE);
	}
	private void cancelWork() {
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
		ListPosition anchor = list != null ? ListPosition.obtain(list, null) : null;
		PostNumber anchorNumber = factory.anchor != null ? factory.anchor : anchor != null && anchor.position < visible.size()
				? visible.get(anchor.position).getPostNumber() : null;
		int anchorOffset = factory.anchor != null ? factory.anchorOffset : anchor != null ? anchor.offset : 0;
		factory.anchor = null;
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
		switchState(DialogUnit.State.LIST, () -> {
			if (list == null) return;
			PostNumber wanted = selectedAnchor ? ContextSource.number(factory.target) : anchorNumber;
			for (int i = 0; wanted != null && i < visible.size(); i++) if (visible.get(i).getPostNumber().equals(wanted)) {
				new ListPosition(i, selectedAnchor ? 0 : anchorOffset).apply(list); break;
			}
			selectedAnchor = false;
		});
		android.util.Log.d("DiscussionContext", "display cards=" + visible.size() + " bindMs="
				+ (android.os.SystemClock.elapsedRealtime() - started) + " totalMs="
				+ (android.os.SystemClock.elapsedRealtime() - loadStarted));
	}
	private void resetSelection() {
		factory.anchor = null; retained.clear(); revealed.clear(); selectedAnchor = true;
		displayedResult = null; visible.clear(); placeholders.clear();
		switchState(DialogUnit.State.LIST, null);
	}
	private void select(PostItem post) {
		if (closed || !matches(post) || placeholders.containsKey(post.getPostNumber())) return;
		if (factory.history.size() == 5) factory.history.removeFirst();
		factory.history.addLast(factory.target); factory.target = key(post); resetSelection(); load();
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
		PagesDatabase.getInstance().unregisterContextInvalidation(invalidateCache);
		ListPosition position = list != null ? ListPosition.obtain(list, null) : null;
		if (position != null && position.position < visible.size()) {
			factory.anchor = visible.get(position.position).getPostNumber(); factory.anchorOffset = position.offset;
		}
		closed = true; cancelWork(); worker.shutdownNow(); snapshot = null; displayedResult = null;
		stateListener = null;
		visible.clear(); placeholders.clear(); list = null; status = null;
	}
}
