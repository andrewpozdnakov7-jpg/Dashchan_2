package com.mishiranu.dashchan.ui.navigator.page;

import android.content.Context;
import android.net.Uri;
import android.util.Pair;
import android.view.Menu;
import android.view.MenuItem;
import android.view.SubMenu;
import androidx.annotation.NonNull;
import androidx.fragment.app.FragmentManager;
import androidx.lifecycle.LifecycleOwner;
import androidx.lifecycle.MutableLiveData;
import androidx.lifecycle.Observer;
import androidx.lifecycle.ViewModel;
import androidx.recyclerview.widget.GridLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import chan.content.Chan;
import chan.content.RedirectException;
import chan.http.HttpValidator;
import chan.util.StringUtils;
import com.mishiranu.dashchan.R;
import com.mishiranu.dashchan.content.HidePerformer;
import com.mishiranu.dashchan.content.Preferences;
import com.mishiranu.dashchan.content.async.ReadThreadsTask;
import com.mishiranu.dashchan.content.database.CommonDatabase;
import com.mishiranu.dashchan.content.model.AttachmentItem;
import com.mishiranu.dashchan.content.model.ErrorItem;
import com.mishiranu.dashchan.content.model.PostItem;
import com.mishiranu.dashchan.content.storage.CombinedFeedStorage;
import com.mishiranu.dashchan.content.translation.TranslationController;
import com.mishiranu.dashchan.ui.DialogMenu;
import com.mishiranu.dashchan.ui.FragmentHandler;
import com.mishiranu.dashchan.ui.InstanceDialog;
import com.mishiranu.dashchan.ui.navigator.adapter.ThreadsAdapter;
import com.mishiranu.dashchan.ui.navigator.manager.DialogUnit;
import com.mishiranu.dashchan.ui.navigator.manager.UiManager;
import com.mishiranu.dashchan.ui.preference.CombinedFeedsFragment;
import com.mishiranu.dashchan.util.ConcurrentUtils;
import com.mishiranu.dashchan.util.NavigationUtils;
import com.mishiranu.dashchan.widget.ClickableToast;
import com.mishiranu.dashchan.widget.DividerItemDecoration;
import com.mishiranu.dashchan.widget.ListPosition;
import com.mishiranu.dashchan.widget.PaddedRecyclerView;
import com.mishiranu.dashchan.widget.PullableWrapper;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;

public class CombinedThreadsPage extends ListPage implements ThreadsAdapter.Callback, UiManager.Observer,
		CombinedFeedStorage.Observer {
	private static class RetainableExtra implements Retainable {
		public static final ExtraFactory<RetainableExtra> FACTORY = RetainableExtra::new;

		public final ArrayList<PostItem> cachedPostItems = new ArrayList<>();
		public final PostItem.HideState.Map<String> hiddenThreads = new PostItem.HideState.Map<>();
		public int failedSources;
		public String sourceSignature;
		public Boolean translationEnabled;
		public DialogUnit.StackInstance.State dialogsState;

		@Override
		public void clear() {
			if (dialogsState != null) {
				dialogsState.dropState();
				dialogsState = null;
			}
		}
	}

	private static class SourceResult {
		public final CombinedFeedStorage.Source source;
		public final List<PostItem> postItems;
		public final PostItem.HideState.Map<String> hiddenThreads;
		public final ErrorItem errorItem;

		private SourceResult(CombinedFeedStorage.Source source, List<PostItem> postItems,
				PostItem.HideState.Map<String> hiddenThreads, ErrorItem errorItem) {
			this.source = source;
			this.postItems = postItems;
			this.hiddenThreads = hiddenThreads;
			this.errorItem = errorItem;
		}
	}

	private static class LoadResult {
		public final ArrayList<SourceResult> results;

		private LoadResult(ArrayList<SourceResult> results) {
			this.results = results;
		}
	}

	public static class ReadViewModel extends ViewModel {
		private final HashMap<String, ReadThreadsTask> tasks = new HashMap<>();
		private final ArrayList<SourceResult> results = new ArrayList<>();
		private final MutableLiveData<LoadResult> result = new MutableLiveData<>();
		private int generation;

		public boolean isLoading() {
			return !tasks.isEmpty();
		}

		public void cancel() {
			generation++;
			cancelTasks();
			result.setValue(null);
		}

		public void load(CombinedFeedStorage.Feed feed) {
			cancelTasks();
			result.setValue(null);
			results.clear();
			int generation = ++this.generation;
			for (CombinedFeedStorage.Source sourceValue : feed.sources) {
				CombinedFeedStorage.Source source = new CombinedFeedStorage.Source(sourceValue);
				String key = source.chanName + '\n' + source.boardName;
				Chan chan = Chan.get(source.chanName);
				if (chan.name == null) {
					results.add(new SourceResult(source, null, null,
							new ErrorItem(ErrorItem.Type.UNSUPPORTED_SERVICE)));
					continue;
				}
				ReadThreadsTask.Callback callback = new ReadThreadsTask.Callback() {
					private void finish(List<PostItem> postItems,
							PostItem.HideState.Map<String> hiddenThreads, ErrorItem errorItem) {
						finishSource(generation, key,
								new SourceResult(source, postItems, hiddenThreads, errorItem));
					}

					@Override
					public void onReadThreadsSuccess(List<PostItem> postItems, int pageNumber, int boardSpeed,
							boolean append, boolean checkModified, HttpValidator validator,
							PostItem.HideState.Map<String> hiddenThreads) {
						finish(postItems, hiddenThreads, null);
					}

					@Override
					public void onReadThreadsRedirect(RedirectException.Target target) {
						finish(null, null, new ErrorItem(ErrorItem.Type.INVALID_RESPONSE));
					}

					@Override
					public void onReadThreadsFail(ErrorItem errorItem, int pageNumber) {
						finish(null, null, errorItem);
					}
				};
				ReadThreadsTask task = new ReadThreadsTask(callback, chan, source.boardName, 0, null, false);
				tasks.put(key, task);
				task.execute(ConcurrentUtils.PARALLEL_EXECUTOR);
			}
			if (tasks.isEmpty()) {
				result.setValue(new LoadResult(new ArrayList<>(results)));
			}
		}

		private void finishSource(int generation, String key, SourceResult sourceResult) {
			if (generation != this.generation || tasks.remove(key) == null) {
				return;
			}
			results.add(sourceResult);
			if (tasks.isEmpty()) {
				result.setValue(new LoadResult(new ArrayList<>(results)));
			}
		}

		public void observe(LifecycleOwner owner, Observer<LoadResult> observer) {
			result.observe(owner, value -> {
				if (value != null) {
					result.setValue(null);
					observer.onChanged(value);
				}
			});
		}

		private void cancelTasks() {
			for (ReadThreadsTask task : tasks.values()) {
				task.cancel();
			}
			tasks.clear();
		}

		@Override
		protected void onCleared() {
			generation++;
			cancelTasks();
		}
	}

	private CombinedFeedStorage.Feed feed;
	private HidePerformer hidePerformer;

	private final UiManager.PostStateProvider postStateProvider = new UiManager.PostStateProvider() {
		@Override
		public boolean isHiddenResolve(PostItem postItem) {
			if (postItem.getHideState() == PostItem.HideState.UNDEFINED) {
				PostItem.HideState hideState = getRetainableExtra(RetainableExtra.FACTORY).hiddenThreads
						.get(makeThreadKey(postItem));
				if (hideState != PostItem.HideState.UNDEFINED) {
					postItem.setHidden(hideState, null);
				} else {
					String reason = hidePerformer.checkHidden(Chan.get(postItem.getChanName()), postItem);
					postItem.setHidden(reason != null ? PostItem.HideState.HIDDEN : PostItem.HideState.SHOWN, reason);
				}
			}
			return postItem.getHideState().hidden;
		}
	};

	private ThreadsAdapter getAdapter() {
		return (ThreadsAdapter) getRecyclerView().getAdapter();
	}

	@Override
	protected void onCreate() {
		feed = CombinedFeedStorage.getInstance().getFeed(getPage().boardName);
		if (feed == null || feed.sources.size() < 2) {
			switchError(R.string.combined_feed_not_found);
			return;
		}
		Context context = getContext();
		PaddedRecyclerView recyclerView = getRecyclerView();
		GridLayoutManager layoutManager = new GridLayoutManager(context, 1);
		recyclerView.setLayoutManager(layoutManager);
		hidePerformer = new HidePerformer(context);
		UiManager uiManager = getUiManager();
		uiManager.view().bindThreadsPostRecyclerView(recyclerView);
		ThreadsAdapter adapter = new ThreadsAdapter(context, this, getPage().chanName, uiManager,
				postStateProvider, getFragmentManager(), this::formatSourceLabel);
		RetainableExtra retainableExtra = getRetainableExtra(RetainableExtra.FACTORY);
		String sourceSignature = makeSourceSignature(feed);
		if (!sourceSignature.equals(retainableExtra.sourceSignature)) {
			retainableExtra.sourceSignature = sourceSignature;
			retainableExtra.cachedPostItems.clear();
			retainableExtra.hiddenThreads.clear();
			retainableExtra.failedSources = 0;
		}
		if (retainableExtra.translationEnabled == null) {
			retainableExtra.translationEnabled = TranslationController.isReadyForChan(getPage().chanName)
					&& Preferences.isTranslationAutoEnabled();
		}
		adapter.setTranslationEnabled(Boolean.TRUE.equals(retainableExtra.translationEnabled));
		recyclerView.setAdapter(adapter);
		recyclerView.addItemDecoration(new RecyclerView.ItemDecoration() {
			@Override
			public void getItemOffsets(@NonNull android.graphics.Rect outRect, @NonNull android.view.View view,
					@NonNull RecyclerView parent, @NonNull RecyclerView.State state) {
				int column = ((GridLayoutManager.LayoutParams) view.getLayoutParams()).getSpanIndex();
				adapter.applyItemPadding(view, parent.getChildAdapterPosition(view), column, outRect);
			}
		});
		recyclerView.addItemDecoration(new DividerItemDecoration(context, adapter::configureDivider));
		recyclerView.getPullable().setPullSides(PullableWrapper.Side.TOP);
		layoutManager.setSpanCount(adapter.setThreadsView(Preferences.getThreadsView()));
		adapter.applyFilter(getInitSearch().currentQuery);
		uiManager.observable().register(this);
		CombinedFeedStorage.getInstance().getObservable().register(this);

		ListPosition listPosition = takeListPosition();
		if (!retainableExtra.cachedPostItems.isEmpty()) {
			adapter.setItems(Collections.singleton(retainableExtra.cachedPostItems), false);
			if (listPosition != null) {
				listPosition.apply(recyclerView);
			}
			if (retainableExtra.dialogsState != null) {
				uiManager.dialog().restoreState(adapter.getConfigurationSet(), retainableExtra.dialogsState);
				retainableExtra.dialogsState.dropState();
				retainableExtra.dialogsState = null;
			}
		}
		ReadViewModel readViewModel = getViewModel(ReadViewModel.class);
		if (readViewModel.isLoading()) {
			recyclerView.getPullable().startBusyState(PullableWrapper.Side.TOP);
			if (adapter.isRealEmpty()) {
				switchProgress();
			}
		} else if (adapter.isRealEmpty()) {
			refresh(false);
		}
		readViewModel.observe(this, this::onLoadResult);
	}

	@Override
	protected void onDestroy() {
		if (getRecyclerView().getAdapter() != null) {
			getUiManager().dialog().closeDialogs(getAdapter().getConfigurationSet().stackInstance);
		}
		getUiManager().observable().unregister(this);
		CombinedFeedStorage.getInstance().getObservable().unregister(this);
	}

	@Override
	protected void onNotifyAllAdaptersChanged() {
		if (getRecyclerView().getAdapter() != null) {
			getUiManager().dialog().notifyDataSetChangedToAll(getAdapter().getConfigurationSet().stackInstance);
		}
	}

	@Override
	protected void onRequestStoreExtra(boolean saveToStack) {
		if (getRecyclerView().getAdapter() == null) {
			return;
		}
		RetainableExtra extra = getRetainableExtra(RetainableExtra.FACTORY);
		if (extra.dialogsState != null) {
			extra.dialogsState.dropState();
		}
		extra.dialogsState = getAdapter().getConfigurationSet().stackInstance.collectState();
	}

	@Override
	public Pair<String, String> obtainTitleSubtitle() {
		String title = feed != null ? feed.title : getString(R.string.combined_feeds);
		int count = feed != null ? feed.sources.size() : 0;
		int failed = getRetainableExtra(RetainableExtra.FACTORY).failedSources;
		String subtitle = getResources().getQuantityString(R.plurals.number_boards__format, count, count);
		if (failed > 0) {
			subtitle += " - " + getString(R.string.combined_feed_failed__format, failed);
		}
		return new Pair<>(title, subtitle);
	}

	private String formatSourceLabel(PostItem postItem) {
		Chan chan = Chan.get(postItem.getChanName());
		String title;
		if ("dvach".equals(postItem.getChanName())) {
			title = "2ch";
		} else {
			title = chan.name != null ? chan.configuration.getTitle() : postItem.getChanName();
		}
		if (StringUtils.isEmpty(title)) {
			title = postItem.getChanName();
		}
		if (title.indexOf(' ') < 0) {
			int dotIndex = title.indexOf('.');
			if (dotIndex > 0) {
				title = title.substring(0, dotIndex);
			}
		}
		return title + " /" + postItem.getBoardName() + "/";
	}

	private static String makeThreadKey(PostItem postItem) {
		return postItem.getChanName() + '\n' + postItem.getBoardName() + '\n' + postItem.getThreadNumber();
	}

	private static String makeSourceSignature(CombinedFeedStorage.Feed feed) {
		StringBuilder builder = new StringBuilder().append(feed.showSticky).append('\n');
		for (CombinedFeedStorage.Source source : feed.sources) {
			builder.append(source.chanName).append('\n').append(source.boardName).append('\n');
		}
		return builder.toString();
	}

	private void refresh(boolean showPull) {
		if (feed == null) {
			return;
		}
		ReadViewModel viewModel = getViewModel(ReadViewModel.class);
		viewModel.load(feed);
		PaddedRecyclerView recyclerView = getRecyclerView();
		recyclerView.getPullable().startBusyState(showPull ? PullableWrapper.Side.TOP : PullableWrapper.Side.BOTH);
		if (showPull) {
			switchList();
		} else {
			switchProgress();
		}
	}

	private void onLoadResult(LoadResult loadResult) {
		if (feed == null) {
			return;
		}
		PaddedRecyclerView recyclerView = getRecyclerView();
		recyclerView.getPullable().cancelBusyState();
		ArrayList<PostItem> postItems = new ArrayList<>();
		RetainableExtra extra = getRetainableExtra(RetainableExtra.FACTORY);
		PostItem.HideState.Map<String> hiddenThreads = new PostItem.HideState.Map<>();
		int failed = 0;
		int succeeded = 0;
		ErrorItem firstError = null;
		for (SourceResult result : loadResult.results) {
			if (result.errorItem != null) {
				failed++;
				if (firstError == null) {
					firstError = result.errorItem;
				}
			} else if (result.postItems != null) {
				succeeded++;
				for (PostItem postItem : result.postItems) {
					if (!feed.showSticky && postItem.isSticky()) {
						continue;
					}
					postItems.add(postItem);
					if (result.hiddenThreads != null) {
						PostItem.HideState state = result.hiddenThreads.get(postItem.getThreadNumber());
						if (state != PostItem.HideState.UNDEFINED) {
							hiddenThreads.set(makeThreadKey(postItem), state);
						}
					}
				}
			}
		}
		postItems.sort((first, second) -> Long.compare(second.getThreadLatestTimestamp(),
				first.getThreadLatestTimestamp()));
		extra.failedSources = failed;
		if (!postItems.isEmpty()) {
			extra.hiddenThreads.clear();
			extra.hiddenThreads.addAll(hiddenThreads);
			extra.cachedPostItems.clear();
			extra.cachedPostItems.addAll(postItems);
			switchList();
			int oldCount = getAdapter().getItemCount();
			getAdapter().setItems(Collections.singleton(postItems), false);
			recyclerView.scrollToPosition(0);
			if (failed > 0) {
				ClickableToast.show(getString(R.string.combined_feed_partial_failure__format,
						failed, loadResult.results.size()));
			}
			if (oldCount == 0) {
				showScaleAnimation();
			}
		} else if (firstError != null) {
			if (succeeded > 0 || getAdapter().isRealEmpty()) {
				extra.hiddenThreads.clear();
				extra.cachedPostItems.clear();
				switchError(firstError);
			} else {
				switchList();
				ClickableToast.show(firstError);
			}
		} else {
			extra.hiddenThreads.clear();
			extra.cachedPostItems.clear();
			switchError(R.string.empty_response);
		}
		notifyTitleChanged();
	}

	@Override
	public void onItemClick(PostItem postItem) {
		if (postItem == null) {
			return;
		}
		if (postItem.getHideState().hidden) {
			setThreadHideState(postItem, PostItem.HideState.SHOWN);
			getAdapter().notifyDataSetChanged();
		} else {
			getUiManager().navigator().navigatePosts(postItem.getChanName(), postItem.getBoardName(),
					postItem.getThreadNumber(), null, postItem.getSubjectOrComment());
		}
	}

	@Override
	public boolean onItemLongClick(PostItem postItem) {
		if (postItem != null) {
			showItemPopupMenu(getFragmentManager(), postItem);
			return true;
		}
		return false;
	}

	private static void showItemPopupMenu(FragmentManager fragmentManager, PostItem postItem) {
		new InstanceDialog(fragmentManager, null, provider -> {
			CombinedThreadsPage page = extract(provider);
			Chan chan = Chan.get(postItem.getChanName());
			Uri uri = chan.locator.safe(true).createThreadUri(postItem.getBoardName(), postItem.getThreadNumber());
			DialogMenu menu = new DialogMenu(provider.getContext());
			if (uri != null) {
				menu.add(R.string.copy_link,
						() -> StringUtils.copyToClipboard(provider.getContext(), uri.toString()));
				menu.add(R.string.share_link, () -> {
					String subject = postItem.getSubjectOrComment();
					NavigationUtils.shareLink(provider.getContext(), StringUtils.isEmptyOrWhitespace(subject)
							? uri.toString() : subject, uri);
				});
			}
			if (!postItem.getHideState().hidden) {
				menu.add(R.string.hide, () -> {
					page.setThreadHideState(postItem, PostItem.HideState.HIDDEN);
					page.getAdapter().notifyThreadHidden(postItem);
				});
			}
			return menu.create();
		});
	}

	private void setThreadHideState(PostItem postItem, PostItem.HideState hideState) {
		getRetainableExtra(RetainableExtra.FACTORY).hiddenThreads.set(makeThreadKey(postItem), hideState);
		CommonDatabase.getInstance().getThreads().setFlagsAsync(postItem.getChanName(), postItem.getBoardName(),
				postItem.getThreadNumber(), hideState);
		postItem.setHidden(hideState, null);
	}

	@Override
	public void onCreateOptionsMenu(Menu menu) {
		if (feed == null) {
			return;
		}
		menu.add(0, R.id.menu_refresh, 0, R.string.refresh)
				.setIcon(getActionBarIcon(R.attr.iconActionRefresh))
				.setShowAsAction(MenuItem.SHOW_AS_ACTION_IF_ROOM);
		menu.add(0, R.id.menu_translate, 0, R.string.translate_posts)
				.setIcon(getActionBarIcon(R.attr.iconActionTranslate))
				.setShowAsAction(MenuItem.SHOW_AS_ACTION_ALWAYS);
		menu.add(0, R.id.menu_search, 0, R.string.filter)
				.setShowAsAction(MenuItem.SHOW_AS_ACTION_COLLAPSE_ACTION_VIEW);
		menu.add(0, R.id.menu_configure_feed, 0, R.string.configure_combined_feed);
		menu.addSubMenu(0, R.id.menu_appearance, 0, R.string.appearance);
		SubMenu viewOptions = menu.addSubMenu(0, R.id.menu_threads_view, 0, R.string.threads_view);
		for (Preferences.ThreadsView threadsView : Preferences.ThreadsView.values()) {
			viewOptions.add(R.id.menu_threads_view, threadsView.menuItemId, 0, threadsView.titleResId);
		}
		viewOptions.setGroupCheckable(R.id.menu_threads_view, true, true);
	}

	@Override
	public void onPrepareOptionsMenu(Menu menu) {
		if (feed == null || getRecyclerView().getAdapter() == null) {
			return;
		}
		boolean enabled = TranslationController.isEnabledForChan(getPage().chanName);
		MenuItem translate = menu.findItem(R.id.menu_translate);
		translate.setVisible(enabled);
		if (enabled) {
			translate.setTitle(getAdapter().isTranslationEnabled()
					? R.string.show_original_posts : R.string.translate_posts);
		}
		menu.findItem(Preferences.getThreadsView().menuItemId).setChecked(true);
	}

	@Override
	public boolean onOptionsItemSelected(MenuItem item) {
		if (feed == null || getRecyclerView().getAdapter() == null) {
			return false;
		}
		if (item.getItemId() == R.id.menu_refresh) {
			refresh(true);
			return true;
		}
		if (item.getItemId() == R.id.menu_translate) {
			if (!TranslationController.isReadyForChan(getPage().chanName)) {
				ClickableToast.show(R.string.translation_package_unavailable);
				return true;
			}
			boolean enabled = !getAdapter().isTranslationEnabled();
			getRetainableExtra(RetainableExtra.FACTORY).translationEnabled = enabled;
			getAdapter().setTranslationEnabled(enabled);
			updateOptionsMenu();
			return true;
		}
		if (item.getItemId() == R.id.menu_configure_feed) {
			Context context = getContext();
			if (context instanceof FragmentHandler) {
				((FragmentHandler) context).pushFragment(CombinedFeedsFragment.forFeed(feed.id));
				return true;
			}
		}
		for (Preferences.ThreadsView threadsView : Preferences.ThreadsView.values()) {
			if (item.getItemId() == threadsView.menuItemId) {
				Preferences.setThreadsView(threadsView);
				GridLayoutManager manager = (GridLayoutManager) getRecyclerView().getLayoutManager();
				manager.setSpanCount(getAdapter().setThreadsView(threadsView));
				getAdapter().notifyDataSetChanged();
				return true;
			}
		}
		return false;
	}

	@Override
	public void onSearchQueryChange(String query) {
		if (getRecyclerView().getAdapter() != null) {
			getAdapter().applyFilter(query);
		}
	}

	@Override
	public void onListPulled(PullableWrapper wrapper, PullableWrapper.Side side) {
		refresh(true);
	}

	@Override
	public void onAppearanceOptionChanged(int what) {
		if (getRecyclerView().getAdapter() != null &&
				(what == R.id.menu_spoilers || what == R.id.menu_sfw_mode)) {
			notifyAllAdaptersChanged();
		}
	}

	@Override
	public void onReloadAttachmentItem(AttachmentItem attachmentItem) {
		getAdapter().reloadAttachment(attachmentItem);
	}

	@Override
	public void onCombinedFeedsChanged() {
		CombinedFeedStorage.Feed newFeed = CombinedFeedStorage.getInstance().getFeed(getPage().boardName);
		if (newFeed == null) {
			feed = null;
			getViewModel(ReadViewModel.class).cancel();
			getRecyclerView().getPullable().cancelBusyState();
			switchError(R.string.combined_feed_not_found);
			notifyTitleChanged();
		} else {
			feed = newFeed;
			getRetainableExtra(RetainableExtra.FACTORY).sourceSignature = makeSourceSignature(newFeed);
			refresh(!getAdapter().isRealEmpty());
			notifyTitleChanged();
		}
	}
}
