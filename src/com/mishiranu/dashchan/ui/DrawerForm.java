package com.mishiranu.dashchan.ui;

import android.annotation.SuppressLint;
import android.app.AlertDialog;
import android.content.Context;
import android.content.res.ColorStateList;
import android.content.res.TypedArray;
import android.graphics.Rect;
import android.net.Uri;
import android.os.Looper;
import android.os.SystemClock;
import android.os.Trace;
import android.text.InputType;
import android.text.TextUtils;
import android.view.ContextThemeWrapper;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.ActionMode;
import android.view.Menu;
import android.view.MenuItem;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.widget.EditText;
import android.widget.CheckBox;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.PopupMenu;
import android.widget.TextView;
import androidx.annotation.NonNull;
import androidx.drawerlayout.widget.DrawerLayout;
import androidx.fragment.app.FragmentManager;
import androidx.recyclerview.widget.DiffUtil;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import chan.content.Chan;
import chan.content.ChanConfiguration;
import chan.content.ChanManager;
import chan.content.ChanMarkup;
import chan.util.CommonUtils;
import chan.util.StringUtils;
import com.mishiranu.dashchan.R;
import com.mishiranu.dashchan.content.Preferences;
import com.mishiranu.dashchan.content.model.PostNumber;
import com.mishiranu.dashchan.content.service.WatcherService;
import com.mishiranu.dashchan.content.storage.CombinedFeedStorage;
import com.mishiranu.dashchan.content.storage.FavoritesStorage;
import com.mishiranu.dashchan.content.storage.MyPostsStorage;
import com.mishiranu.dashchan.graphics.ChanIconDrawable;
import com.mishiranu.dashchan.util.FlagUtils;
import com.mishiranu.dashchan.util.GraphicsUtils;
import com.mishiranu.dashchan.util.IOUtils;
import com.mishiranu.dashchan.util.ListViewUtils;
import com.mishiranu.dashchan.util.NavigationUtils;
import com.mishiranu.dashchan.util.ResourceUtils;
import com.mishiranu.dashchan.util.ViewUtils;
import com.mishiranu.dashchan.widget.ClickableToast;
import com.mishiranu.dashchan.widget.DividerItemDecoration;
import com.mishiranu.dashchan.widget.EdgeEffectHandler;
import com.mishiranu.dashchan.widget.PaddedRecyclerView;
import com.mishiranu.dashchan.widget.SafePasteEditText;
import com.mishiranu.dashchan.widget.SortableHelper;
import com.mishiranu.dashchan.widget.ThemeEngine;
import com.mishiranu.dashchan.widget.WatcherView;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class DrawerForm extends RecyclerView.Adapter<DrawerForm.ViewHolder> implements EdgeEffectHandler.Shift,
		DrawerLayout.DrawerListener, EditText.OnEditorActionListener, SortableHelper.Callback<DrawerForm.ViewHolder> {
	private final Context context;
	private final Callback callback;
	private final FragmentManager fragmentManager;
	private final WatcherView.ColorSet watcherViewColorSet;
	private final SortableHelper<ViewHolder> sortableHelper;
	private final int drawerIconColor;

	private final WatcherService.Client watcherServiceClient;
	private final InputMethodManager inputMethodManager;

	private final PaddedRecyclerView recyclerView;
	private final EditText searchEdit;
	private final View selectorContainer;
	private final View headerView;
	private final View restartView;
	private final TextView chanNameView;
	private final ImageView chanSelectorIcon;

	private final HashMap<String, ChanIconDrawable> chanIcons = new HashMap<>();
	private final HashSet<String> watcherSupportSet = new HashSet<>();

	private final ArrayList<ListItem> chans = new ArrayList<>();
	private final ArrayList<ListItem> pages = new ArrayList<>();
	private final ArrayList<ListItem> favorites = new ArrayList<>();
	private final ArrayList<ListItem> menu = new ArrayList<>();
	private final HashMap<WatcherUpdateKey, WatcherService.Counter> pendingWatcherUpdates = new HashMap<>();
	private static final int PREWARM_ITEM_COUNT = 12;
	private static final int COLLAPSED_OPEN_THREAD_LIMIT = 12;
	private boolean drawerPrewarmScheduled;
	private boolean drawerPrewarmed;
	private int[] drawerPrewarmViewTypes;
	private int drawerPrewarmViewTypeIndex;

	private boolean mergeChans = false;
	private boolean showHistory = false;
	private boolean combinedFeedsEnabled = false;
	private boolean trackMyPostsEnabled;
	private boolean collapseLongOpenThreadsEnabled;
	private boolean pagesExpanded;
	private Preferences.PagesListMode pagesListMode = null;
	private boolean chanSelectMode = false;
	private boolean showRestartButton = false;
	private CategoriesOrder categoriesOrder;
	private String chanName;
	private int drawerState = DrawerLayout.STATE_IDLE;
	private boolean drawerOpened;
	private boolean drawerAlwaysVisible;
	private ActionMode favoriteSelectionActionMode;
	private final HashSet<Long> selectedFavoriteIds = new HashSet<>();

	public static final int RESULT_REMOVE_ERROR_MESSAGE = 0x00000001;
	public static final int RESULT_SUCCESS = 0x00000002;

	public static final int MENU_ITEM_BOARDS = 1;
	public static final int MENU_ITEM_USER_BOARDS = 2;
	public static final int MENU_ITEM_HISTORY = 3;
	public static final int MENU_ITEM_LOCAL_ARCHIVES = 4;
	public static final int MENU_ITEM_PREFERENCES = 5;
	public static final int MENU_ITEM_COMBINED_FEEDS = 6;
	public static final int MENU_ITEM_MY_POSTS = 7;

	private final Runnable myPostsObserver = () -> updateConfigurationInternal(chanName, true);

	private enum CategoriesOrder {PAGES_FIRST, FAVORITES_FIRST, HIDE_PAGES}

	public static class Page implements Comparable<Page> {
		public final String chanName;
		public final String boardName;
		public final String threadNumber;
		public final String threadTitle;
		public final long createRealtime;
		public final boolean current;

		public Page(String chanName, String boardName, String threadNumber,
				String threadTitle, long createRealtime, boolean current) {
			this.chanName = chanName;
			this.boardName = boardName;
			this.threadNumber = threadNumber;
			this.threadTitle = threadTitle;
			this.createRealtime = createRealtime;
			this.current = current;
		}

		@Override
		public int compareTo(Page page) {
			return Long.compare(page.createRealtime, createRealtime);
		}
	}

	public interface Callback {
		void onSelectChan(String chanName);
		void onSelectBoard(String chanName, String boardName, boolean fromCache);
		void onSelectCombinedFeed(String feedId);
		boolean onSelectThread(String chanName, String boardName, String threadNumber, PostNumber postNumber,
				String threadTitle, boolean fromCache);
		void onClosePage(String chanName, String boardName, String threadNumber);
		void onCloseAllPages();
		int onEnterNumber(int number);
		void onSelectDrawerMenuItem(int item);
		void onDraggingStateChanged(boolean dragging);
		Collection<Page> obtainDrawerPages();
		void restartApplication();
	}

	public DrawerForm(Context context, Callback callback, FragmentManager fragmentManager,
			WatcherService.Client watcherServiceClient) {
		this.context = context;
		this.callback = callback;
		this.fragmentManager = fragmentManager;
		this.watcherServiceClient = watcherServiceClient;

		ThemeEngine.Theme theme = ThemeEngine.getTheme(context);
		int enabledColor = theme.accent;
		int disabledColor = 0xff666666;
		int unavailableColor = GraphicsUtils.mixColors(disabledColor, enabledColor & 0x7fffffff);
		watcherViewColorSet = new WatcherView.ColorSet(enabledColor, unavailableColor, disabledColor);

		recyclerView = new PaddedRecyclerView(context);
		recyclerView.setId(R.id.drawer_recycler_view);
		recyclerView.setMotionEventSplittingEnabled(false);
		recyclerView.setClipToPadding(false);
		recyclerView.setHasFixedSize(true);
		recyclerView.setEdgeEffectShift(this);
		// Drawer overscroll is not a refresh affordance, keep it from flashing accent under system bars.
		recyclerView.getEdgeEffectHandler().setColor(theme.card);
		recyclerView.setLayoutManager(new LinearLayoutManager(recyclerView.getContext()) {
			@Override
			public boolean requestChildRectangleOnScreen(@NonNull RecyclerView parent, @NonNull View child,
					@NonNull Rect rect, boolean immediate, boolean focusedChildVisible) {
				if (child == headerView) {
					// Keep EditText on top and don't allow LinearLayoutManager weird scrolls
					int dy = child.getTop() - parent.getPaddingTop();
					if (dy != 0) {
						if (immediate) {
							parent.scrollBy(0, dy);
						} else {
							parent.smoothScrollBy(0, dy);
						}
						return true;
					}
				}
				return false;
			}
		});
		recyclerView.addOnScrollListener(new RecyclerView.OnScrollListener() {
			@Override
			public void onScrollStateChanged(@NonNull RecyclerView recyclerView, int newState) {
				// Hide keyboard when list is scrolled
				if (newState == RecyclerView.SCROLL_STATE_DRAGGING) {
					View focusView = recyclerView.getFocusedChild();
					if (focusView != null) {
						focusView.clearFocus();
						hideKeyboard();
					}
				}
			}
		});
		setHasStableIds(true);
		recyclerView.setAdapter(this);
		DividerItemDecoration dividerItemDecoration = new DividerItemDecoration
				(recyclerView.getContext(), (c, position) -> configureDivider(c, position).translate(false));
		recyclerView.addItemDecoration(dividerItemDecoration);
		dividerItemDecoration.setAboveCallback(position -> {
			ListItem listItem = getItem(position);
			// Attach dividers to the top of sections and menus to fix decorations on dragging
			return listItem.type == ListItem.Type.SECTION || listItem.type == ListItem.Type.MENU;
		});
		recyclerView.setItemAnimator(null);
		sortableHelper = new SortableHelper<>(recyclerView, this);
		drawerIconColor = ResourceUtils.getColor(context, android.R.attr.textColorSecondary);

		float density = ResourceUtils.obtainDensity(context);

		LinearLayout headerView = new LinearLayout(context);
		headerView.setOrientation(LinearLayout.VERTICAL);
		headerView.setLayoutParams(new RecyclerView.LayoutParams(RecyclerView.LayoutParams.MATCH_PARENT,
				RecyclerView.LayoutParams.WRAP_CONTENT));
		this.headerView = headerView;

		LinearLayout editTextContainer = new LinearLayout(context);
		editTextContainer.setGravity(Gravity.CENTER_VERTICAL);
		// Reset focus to parent view
		editTextContainer.setFocusableInTouchMode(true);
		headerView.addView(editTextContainer);

		searchEdit = new SafePasteEditText(context);
		searchEdit.setOnKeyListener((v, keyCode, event) -> {
			if (event.getAction() == KeyEvent.ACTION_UP && keyCode == KeyEvent.KEYCODE_BACK) {
				v.clearFocus();
			}
			return false;
		});
		searchEdit.setHint(context.getString(R.string.code_number_address));
		searchEdit.setOnEditorActionListener(this);
		searchEdit.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
		searchEdit.setImeOptions(EditorInfo.IME_ACTION_GO | EditorInfo.IME_FLAG_NO_EXTRACT_UI);

		ImageView searchIcon = new ImageView(context, null, android.R.attr.buttonBarButtonStyle);
		searchIcon.setImageResource(ResourceUtils.getResourceId(context, R.attr.iconButtonForward, 0));
		searchIcon.setImageTintList(ResourceUtils.getColorStateList(searchIcon.getContext(),
				android.R.attr.textColorPrimary));
		searchIcon.setScaleType(ImageView.ScaleType.CENTER);
		searchIcon.setOnClickListener(v -> onSearchClick());
		editTextContainer.addView(searchEdit, new LinearLayout.LayoutParams(0,
				LinearLayout.LayoutParams.WRAP_CONTENT, 1));
		editTextContainer.addView(searchIcon, (int) (40f * density), (int) (40f * density));
		editTextContainer.setPadding((int) (12f * density), (int) (8f * density), (int) (8f * density), 0);

		LinearLayout selectorContainer = new LinearLayout(context);
		this.selectorContainer = selectorContainer;
		selectorContainer.setBackgroundResource(ResourceUtils.getResourceId(context,
				android.R.attr.selectableItemBackground, 0));
		selectorContainer.setOrientation(LinearLayout.HORIZONTAL);
		selectorContainer.setGravity(Gravity.CENTER_VERTICAL);
		selectorContainer.setOnClickListener(v -> {
			hideKeyboard();
			setChanSelectMode(!chanSelectMode);
		});
		headerView.addView(selectorContainer);
		selectorContainer.setMinimumHeight((int) (40f * density));
		selectorContainer.setPadding((int) (16f * density), 0, (int) (16f * density), 0);
		((LinearLayout.LayoutParams) selectorContainer.getLayoutParams()).topMargin = (int) (4f * density);

		chanNameView = new TextView(context, null, android.R.attr.textAppearanceListItem);
		ThemeEngine.applyStyle(chanNameView);
		ViewUtils.setTextSizeScaled(chanNameView, 14);
		chanNameView.setTypeface(ResourceUtils.TYPEFACE_MEDIUM);
		selectorContainer.addView(chanNameView, new LinearLayout.LayoutParams(0,
				LinearLayout.LayoutParams.WRAP_CONTENT, 1));

		chanSelectorIcon = new ImageView(context);
		chanSelectorIcon.setImageResource(ResourceUtils.getResourceId(context, R.attr.iconButtonDropDown, 0));
		chanSelectorIcon.setImageTintList(ResourceUtils.getColorStateList(context,
				android.R.attr.textColorPrimary));
		selectorContainer.addView(chanSelectorIcon, (int) (24f * density), (int) (24f * density));
		((LinearLayout.LayoutParams) chanSelectorIcon.getLayoutParams()).gravity = Gravity.CENTER_VERTICAL
				| Gravity.END;

		LinearLayout restartView = new LinearLayout(context);
		restartView.setOrientation(LinearLayout.VERTICAL);
		this.restartView = restartView;

		TextView restartTextView = new TextView(context, null, android.R.attr.textAppearanceSmall);
		ThemeEngine.applyStyle(restartTextView);
		restartTextView.setText(R.string.new_extensions_installed__sentence);
		restartTextView.setTextColor(ResourceUtils.getColor(context, android.R.attr.textColorPrimary));
		restartTextView.setPadding((int) (16f * density), (int) (8f * density),
				(int) (16f * density), (int) (8f * density));
		restartView.addView(restartTextView, LinearLayout.LayoutParams.MATCH_PARENT,
				LinearLayout.LayoutParams.WRAP_CONTENT);

		ViewHolder restartButtonViewHolder = createItem(ViewType.ITEM, density);
		restartButtonViewHolder.text.setText(R.string.restart);
		restartButtonViewHolder.itemView.setOnClickListener(v -> callback.restartApplication());
		restartView.addView(restartButtonViewHolder.itemView, LinearLayout.LayoutParams.MATCH_PARENT,
				LinearLayout.LayoutParams.WRAP_CONTENT);

		inputMethodManager = (InputMethodManager) context.getSystemService(Context.INPUT_METHOD_SERVICE);
		MyPostsStorage.getInstance().getObservable().register(myPostsObserver);
		updatePreferencesWithoutConfiguration();
		updateChansWithoutConfiguration();
	}

	private void updateConfigurationInternal(String chanName, boolean force) {
		if (!CommonUtils.equals(chanName, this.chanName) || force || menu.isEmpty()) {
			updateConfigurationInternal(chanName, force, createAdapterSnapshot());
		}
	}

	private void updateConfigurationInternal(String chanName, boolean force,
			ArrayList<AdapterItem> previousItems) {
		if (!CommonUtils.equals(chanName, this.chanName) || force || menu.isEmpty()) {
			this.chanName = chanName;
			Chan chan = Chan.get(chanName);
			chanNameView.setText(chan.configuration.getTitle());
			menu.clear();
			Context context = this.context;
			TypedArray typedArray = context.obtainStyledAttributes(new int[] {R.attr.iconDrawerMenuBoards,
					R.attr.iconDrawerMenuUserBoards, R.attr.iconDrawerMenuHistory,
					R.attr.iconDrawerMenuLocalArchives, R.attr.iconDrawerMenuPreferences});
			boolean hasUserBoards = chan.configuration.getOption(ChanConfiguration.OPTION_READ_USER_BOARDS);
			if (chanName != null && !chan.configuration.getOption(ChanConfiguration.OPTION_SINGLE_BOARD_MODE)) {
				menu.add(new ListItem(ListItem.Type.MENU, MENU_ITEM_BOARDS, typedArray.getResourceId(0, 0),
						context.getString(hasUserBoards ? R.string.general_boards : R.string.boards)));
			}
			if (chanName != null && hasUserBoards) {
				menu.add(new ListItem(ListItem.Type.MENU, MENU_ITEM_USER_BOARDS, typedArray.getResourceId(1, 0),
						context.getString(R.string.user_boards)));
			}
			if (chanName != null && Preferences.isRememberHistory()) {
				menu.add(new ListItem(ListItem.Type.MENU, MENU_ITEM_HISTORY, typedArray.getResourceId(2, 0),
						context.getString(R.string.history)));
			}
			if (trackMyPostsEnabled) {
				int unreadCount = MyPostsStorage.getInstance().getUnreadCount();
				menu.add(new ListItem(ListItem.Type.MENU, MENU_ITEM_MY_POSTS, R.drawable.ic_reply,
						context.getString(R.string.replies), unreadCount));
			}
			menu.add(new ListItem(ListItem.Type.MENU, MENU_ITEM_LOCAL_ARCHIVES, typedArray.getResourceId(3, 0),
					context.getString(R.string.local_archives)));
			menu.add(new ListItem(ListItem.Type.MENU, MENU_ITEM_PREFERENCES, typedArray.getResourceId(4, 0),
					context.getString(R.string.preferences)));
			typedArray.recycle();
			updateItems(true, true, previousItems);
		}
	}

	public void updateConfiguration(String chanName) {
		updateConfigurationInternal(chanName, false);
	}

	public View getContentView() {
		return recyclerView;
	}

	public View getHeaderView() {
		return headerView;
	}

	public void setChanSelectMode(boolean enabled) {
		if (chans.size() >= 2 && chanSelectMode != enabled) {
			ArrayList<AdapterItem> previousItems = createAdapterSnapshot();
			chanSelectMode = enabled;
			chanSelectorIcon.setRotation(enabled ? 180f : 0f);
			dispatchAdapterDiff(previousItems);
			recyclerView.scrollToPosition(0);
			updateRestartViewVisibility();
		}
	}

	public boolean isChanSelectMode() {
		return chanSelectMode;
	}

	public void updateRestartViewVisibility() {
		boolean showRestartButton = !chanSelectMode && ChanManager.getInstance().isRestartRequired();
		if (this.showRestartButton != showRestartButton) {
			ArrayList<AdapterItem> previousItems = createAdapterSnapshot();
			this.showRestartButton = showRestartButton;
			dispatchAdapterDiff(previousItems);
		}
	}

	public void updateChans() {
		ArrayList<AdapterItem> previousItems = createAdapterSnapshot();
		updateChansWithoutConfiguration();
		updateConfigurationInternal(chanName, true, previousItems);
	}

	private void updateChansWithoutConfiguration() {
		ChanManager manager = ChanManager.getInstance();
		Iterable<Chan> availableChans = manager.getAvailableChans();
		int availableChansCount = 0;
		chans.clear();
		watcherSupportSet.clear();
		for (Chan chan : availableChans) {
			availableChansCount++;
			if (watcherServiceClient.isWatcherSupported(chan)) {
				watcherSupportSet.add(chan.name);
			}
			chans.add(new ListItem(ListItem.Type.CHAN, 0, chan.name, null, null, chan.configuration.getTitle()));
		}
		selectorContainer.setVisibility(availableChansCount >= 2 ? View.VISIBLE : View.GONE);
		if (chanSelectMode && availableChansCount <= 1) {
			chanSelectMode = false;
			chanSelectorIcon.setRotation(0f);
		}
	}

	public void updatePreferences() {
		ArrayList<AdapterItem> previousItems = createAdapterSnapshot();
		if (updatePreferencesWithoutConfiguration()) {
			updateConfigurationInternal(chanName, true, previousItems);
		}
	}

	private boolean updatePreferencesWithoutConfiguration() {
		boolean mergeChans = Preferences.isMergeChans();
		boolean showHistory = Preferences.isRememberHistory();
		boolean combinedFeedsEnabled = Preferences.isCombinedFeedsEnabled();
		boolean trackMyPostsEnabled = Preferences.isTrackMyPostsEnabled();
		boolean collapseLongOpenThreadsEnabled = Preferences.isCollapseLongOpenThreadsEnabled();
		Preferences.PagesListMode pagesListMode = Preferences.getPagesListMode();
		if (this.mergeChans != mergeChans || this.showHistory != showHistory ||
				this.combinedFeedsEnabled != combinedFeedsEnabled ||
				this.trackMyPostsEnabled != trackMyPostsEnabled ||
				this.collapseLongOpenThreadsEnabled != collapseLongOpenThreadsEnabled ||
				this.pagesListMode != pagesListMode) {
			this.mergeChans = mergeChans;
			this.showHistory = showHistory;
			this.combinedFeedsEnabled = combinedFeedsEnabled;
			this.trackMyPostsEnabled = trackMyPostsEnabled;
			this.collapseLongOpenThreadsEnabled = collapseLongOpenThreadsEnabled;
			this.pagesExpanded = false;
			this.pagesListMode = pagesListMode;
			return true;
		}
		return false;
	}

	@Override
	public int getEdgeEffectShift(EdgeEffectHandler.Side side) {
		int shift = recyclerView.obtainEdgeEffectShift(side);
		return side == EdgeEffectHandler.Side.TOP ? shift + headerView.getPaddingTop() : shift;
	}

	private void onItemClick(int position) {
		ListItem listItem = getItem(position);
		if (favoriteSelectionActionMode != null) {
			if (listItem.type == ListItem.Type.FAVORITE && listItem.isThreadItem()) {
				toggleFavoriteSelection(listItem);
			}
			return;
		}
		switch (listItem.type) {
			case COMBINED_FEED: {
				callback.onSelectCombinedFeed(listItem.boardName);
				break;
			}
			case PAGE:
			case FAVORITE: {
				boolean fromCache = listItem.type == ListItem.Type.PAGE;
				if (!listItem.isThreadItem()) {
					callback.onSelectBoard(listItem.chanName, listItem.boardName, fromCache);
				} else {
					callback.onSelectThread(listItem.chanName, listItem.boardName, listItem.threadNumber, null,
							listItem.title, fromCache);
				}
				break;
			}
			case PAGES_TOGGLE: {
				pagesExpanded = !pagesExpanded;
				updateItems(true, false);
				break;
			}
			case MENU: {
				callback.onSelectDrawerMenuItem(listItem.data);
				break;
			}
			case CHAN: {
				callback.onSelectChan(listItem.chanName);
				if (drawerAlwaysVisible) {
					setChanSelectMode(false);
				}
				break;
			}
		}
	}

	private boolean onItemLongClick(ViewHolder holder) {
		if (favoriteSelectionActionMode != null) {
			ListItem listItem = getItem(holder.getAdapterPosition());
			if (listItem.type == ListItem.Type.FAVORITE && listItem.isThreadItem()) {
				toggleFavoriteSelection(listItem);
				return true;
			}
		}
		if (chanSelectMode) {
			sortableHelper.start(holder);
			return true;
		}
		ListItem listItem = getItem(holder.getAdapterPosition());
		if (listItem.type == ListItem.Type.FAVORITE && listItem.threadNumber != null &&
				FavoritesStorage.getInstance().canSortManually() && holder.isMultipleFingers()) {
			sortableHelper.start(holder);
			return true;
		}
		switch (listItem.type) {
			case PAGE:
			case FAVORITE: {
				showPageFavoriteMenu(fragmentManager, listItem.type == ListItem.Type.FAVORITE, listItem.isThreadItem(),
						listItem.chanName, listItem.boardName, listItem.threadNumber, listItem.title);
				return true;
			}
		}
		return false;
	}

	private void startFavoriteSelection() {
		if (favoriteSelectionActionMode == null && getVisibleFavoriteThreadCount() > 0) {
			ArrayList<AdapterItem> previousItems = createAdapterSnapshot();
			ActionMode actionMode = recyclerView.startActionMode(favoriteSelectionCallback);
			favoriteSelectionActionMode = actionMode;
			if (actionMode != null) {
				dispatchAdapterDiff(previousItems);
				updateFavoriteSelectionActionMode();
			}
		}
	}

	private int getVisibleFavoriteThreadCount() {
		int count = 0;
		for (ListItem listItem : favorites) {
			if (listItem.type == ListItem.Type.FAVORITE && listItem.isThreadItem()) count++;
		}
		return count;
	}

	private void toggleFavoriteSelection(ListItem listItem) {
		if (!selectedFavoriteIds.add(listItem.id)) selectedFavoriteIds.remove(listItem.id);
		int position = findAdapterPosition(listItem.id);
		if (position != RecyclerView.NO_POSITION) notifyItemChanged(position);
		updateFavoriteSelectionActionMode();
	}

	private int findAdapterPosition(long id) {
		for (int position = 0; position < getItemCount(); position++) {
			if (getItem(position).id == id) return position;
		}
		return RecyclerView.NO_POSITION;
	}

	private void updateFavoriteSelectionActionMode() {
		if (favoriteSelectionActionMode != null) {
			favoriteSelectionActionMode.setTitle(context.getString(R.string.selected) + ": "
					+ selectedFavoriteIds.size());
			MenuItem delete = favoriteSelectionActionMode.getMenu().findItem(FAVORITE_SELECTION_DELETE);
			if (delete != null) delete.setEnabled(!selectedFavoriteIds.isEmpty());
		}
	}

	private ArrayList<FavoritesStorage.FavoriteItem> collectSelectedFavoriteThreads() {
		ArrayList<FavoritesStorage.FavoriteItem> selected = new ArrayList<>();
		FavoritesStorage storage = FavoritesStorage.getInstance();
		for (ListItem listItem : favorites) {
			if (listItem.type == ListItem.Type.FAVORITE && listItem.isThreadItem()
					&& selectedFavoriteIds.contains(listItem.id)) {
				FavoritesStorage.FavoriteItem favoriteItem = storage.getFavorite(listItem.chanName,
						listItem.boardName, listItem.threadNumber);
				if (favoriteItem != null) selected.add(favoriteItem);
			}
		}
		return selected;
	}

	private static void showPageFavoriteMenu(FragmentManager fragmentManager, boolean isFavorite, boolean isThread,
			String chanName, String boardName, String threadNumber, String title) {
		new InstanceDialog(fragmentManager, null, provider -> {
			Context context = provider.getContext();
			DialogMenu dialogMenu = new DialogMenu(provider.getContext());
			dialogMenu.add(R.string.copy_link, () -> onCopyShareLink(context, isThread, false,
					chanName, boardName, threadNumber, title));
			if (isThread) {
				dialogMenu.add(R.string.share_link, () -> onCopyShareLink(context, isThread, true,
						chanName, boardName, threadNumber, title));
			}
			if (isFavorite) {
				dialogMenu.add(R.string.remove_from_favorites, () -> FavoritesStorage.getInstance()
						.remove(chanName, boardName, threadNumber));
				if (threadNumber != null) {
					dialogMenu.add(R.string.rename, () -> showRenameFragment(provider.getFragmentManager(),
							chanName, boardName, threadNumber, title));
				}
			} else if (!FavoritesStorage.getInstance().hasFavorite(chanName, boardName, threadNumber)) {
				dialogMenu.add(R.string.add_to_favorites, () -> {
					if (isThread) {
						FavoritesStorage.getInstance().add(chanName, boardName, threadNumber, title, true);
					} else {
						FavoritesStorage.getInstance().add(chanName, boardName);
					}
				});
			}
			return dialogMenu.create();
		});
	}

	private static void onCopyShareLink(Context context, boolean isThread, boolean share,
			String chanName, String boardName, String threadNumber, String title) {
		Chan chan = Chan.get(chanName);
		Uri uri = isThread ? chan.locator.safe(true).createThreadUri(boardName, threadNumber)
				: chan.locator.safe(true).createBoardUri(boardName, 0);
		if (uri != null) {
			if (share) {
				NavigationUtils.shareLink(context, StringUtils.isEmptyOrWhitespace(title)
						? uri.toString() : title, uri);
			} else {
				StringUtils.copyToClipboard(context, uri.toString());
			}
		}
	}

	private static void showRenameFragment(FragmentManager fragmentManager,
			String chanName, String boardName, String threadNumber, String title) {
		new InstanceDialog(fragmentManager, null, provider -> {
			Context context = provider.getContext();
			EditText editText = new SafePasteEditText(context);
			editText.setId(android.R.id.edit);
			editText.setSingleLine(true);
			editText.setText(title);
			editText.setSelection(editText.length());
			LinearLayout linearLayout = new LinearLayout(context);
			linearLayout.setOrientation(LinearLayout.HORIZONTAL);
			linearLayout.addView(editText, LinearLayout.LayoutParams.MATCH_PARENT,
					LinearLayout.LayoutParams.WRAP_CONTENT);
			int padding = context.getResources().getDimensionPixelSize(R.dimen
					.dialog_padding_view);
			linearLayout.setPadding(padding, padding, padding, padding);
			AlertDialog dialog = new AlertDialog.Builder(context)
					.setView(linearLayout).setTitle(R.string.rename)
					.setNegativeButton(android.R.string.cancel, null)
					.setPositiveButton(android.R.string.ok, (d, which) -> {
						String newTitle = editText.getText().toString();
						FavoritesStorage.getInstance().updateTitle(chanName, boardName, threadNumber, newTitle, true);
					}).create();
			dialog.getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE);
			return dialog;
		});
	}

	@Override
	public void onDrawerSlide(@NonNull View drawerView, float slideOffset) {}

	@Override
	public void onDrawerOpened(@NonNull View drawerView) {
		drawerOpened = true;
		drawerPrewarmed = true;
		applyDrawerWatcherState();
	}

	@Override
	public void onDrawerClosed(@NonNull View drawerView) {
		drawerOpened = false;
		if (favoriteSelectionActionMode != null) favoriteSelectionActionMode.finish();
		setWatcherProgressAnimationsEnabled(false);
		hideKeyboard();
		setChanSelectMode(false);
		if (pagesExpanded && collapseLongOpenThreadsEnabled) {
			pagesExpanded = false;
			updateItems(true, false);
		}
	}

	@Override
	public void onDrawerStateChanged(int newState) {
		drawerState = newState;
		applyDrawerWatcherState();
	}

	public void setDrawerAlwaysVisible(boolean alwaysVisible) {
		if (drawerAlwaysVisible != alwaysVisible) {
			drawerAlwaysVisible = alwaysVisible;
			if (alwaysVisible) {
				drawerPrewarmed = true;
			}
			applyDrawerWatcherState();
		}
	}

	private boolean canApplyWatcherUpdates() {
		return drawerState == DrawerLayout.STATE_IDLE && (drawerOpened || drawerAlwaysVisible);
	}

	private void applyDrawerWatcherState() {
		boolean active = canApplyWatcherUpdates();
		if (active) {
			flushPendingWatcherUpdates();
		}
		setWatcherProgressAnimationsEnabled(active);
	}

	private void setWatcherProgressAnimationsEnabled(boolean enabled) {
		int childCount = recyclerView.getChildCount();
		for (int i = 0; i < childCount; i++) {
			RecyclerView.ViewHolder holder = recyclerView.getChildViewHolder(recyclerView.getChildAt(i));
			if (holder instanceof ViewHolder) {
				WatcherView watcher = ((ViewHolder) holder).watcher;
				if (watcher != null) {
					watcher.setProgressAnimationEnabled(enabled);
				}
			}
		}
	}

	private void clearTextAndHideKeyboard() {
		searchEdit.setText(null);
		hideKeyboard();
	}

	private void hideKeyboard() {
		searchEdit.clearFocus();
		if (inputMethodManager != null) {
			inputMethodManager.hideSoftInputFromWindow(searchEdit.getWindowToken(), 0);
		}
	}

	private static final Pattern PATTERN_NAVIGATION_BOARD_THREAD = Pattern.compile("([\\w_-]+) (\\d+)");
	private static final Pattern PATTERN_NAVIGATION_BOARD = Pattern.compile("/?([\\w_-]+)");
	private static final Pattern PATTERN_NAVIGATION_THREAD = Pattern.compile("#(\\d+)");

	private void onSearchClick() {
		String text = searchEdit.getText().toString().trim();
		int number = -1;
		try {
			number = Integer.parseInt(text);
		} catch (NumberFormatException e) {
			// Not a number, ignore exception
		}
		if (number >= 0) {
			int result = callback.onEnterNumber(number);
			if (FlagUtils.get(result, RESULT_SUCCESS)) {
				clearTextAndHideKeyboard();
				return;
			}
			if (FlagUtils.get(result, RESULT_REMOVE_ERROR_MESSAGE)) {
				return;
			}
		} else {
			{
				String boardName = null;
				String threadNumber = null;
				Matcher matcher = PATTERN_NAVIGATION_BOARD_THREAD.matcher(text);
				if (matcher.matches()) {
					boardName = matcher.group(1);
					threadNumber = matcher.group(2);
				} else {
					matcher = PATTERN_NAVIGATION_BOARD.matcher(text);
					if (matcher.matches()) {
						boardName = matcher.group(1);
					} else {
						matcher = PATTERN_NAVIGATION_THREAD.matcher(text);
						if (matcher.matches()) {
							threadNumber = matcher.group(1);
						}
					}
				}
				if (boardName != null || threadNumber != null) {
					boolean success;
					if (threadNumber == null) {
						callback.onSelectBoard(chanName, boardName, false);
						success = true;
					} else {
						success = callback.onSelectThread(chanName, boardName, threadNumber, null, null, false);
					}
					if (success) {
						clearTextAndHideKeyboard();
						return;
					}
				}
			}
			Uri uri = Uri.parse(text);
			Chan chan = Chan.getPreferred(null, uri);
			if (chan.name != null) {
				boolean success = false;
				String boardName = null;
				String threadNumber = null;
				PostNumber postNumber = null;
				if (chan.locator.safe(false).isThreadUri(uri)) {
					boardName = chan.locator.safe(false).getBoardName(uri);
					threadNumber = chan.locator.safe(false).getThreadNumber(uri);
					postNumber = chan.locator.safe(false).getPostNumber(uri);
					success = true;
				} else if (chan.locator.safe(false).isBoardUri(uri)) {
					boardName = chan.locator.safe(false).getBoardName(uri);
					threadNumber = null;
					postNumber = null;
					success = true;
				}
				if (success) {
					if (threadNumber == null) {
						callback.onSelectBoard(chan.name, boardName, false);
					} else {
						callback.onSelectThread(chan.name, boardName, threadNumber, postNumber, null, false);
					}
					clearTextAndHideKeyboard();
					return;
				}
			}
		}
		if (text.isEmpty()) {
			SearchHelpFormat searchHelpFormat = null;
			if (chanName != null) {
				Chan chan = Chan.get(chanName);
				if (chan.name != null && !chan.configuration.getOption(ChanConfiguration.OPTION_SINGLE_BOARD_MODE)) {
					searchHelpFormat = SearchHelpFormat.obtain(chan, false);
				}
			}
			if (searchHelpFormat == null) {
				for (Chan chan : ChanManager.getInstance().getAvailableChans()) {
					if (!chan.configuration.getOption(ChanConfiguration.OPTION_SINGLE_BOARD_MODE)) {
						searchHelpFormat = SearchHelpFormat.obtain(chan, false);
						if (searchHelpFormat != null) {
							break;
						}
					}
				}
			}
			if (searchHelpFormat == null) {
				for (Chan chan : ChanManager.getInstance().getAvailableChans()) {
					if (chan.configuration.getOption(ChanConfiguration.OPTION_SINGLE_BOARD_MODE)) {
						searchHelpFormat = SearchHelpFormat.obtain(chan, true);
						if (searchHelpFormat != null) {
							break;
						}
					}
				}
			}
			if (searchHelpFormat == null) {
				searchHelpFormat = new SearchHelpFormat("mobi", "307707", "https://2ch.hk/mobi/res/307707.html");
			}
			showSearchHelp(fragmentManager, searchHelpFormat);
			return;
		}
		ClickableToast.show(R.string.enter_valid_data);
	}

	@Override
	public boolean onEditorAction(TextView v, int actionId, KeyEvent event) {
		onSearchClick();
		return true;
	}

	private static class SearchHelpFormat {
		public final String boardName;
		public final String threadNumber;
		public final String threadUrl;

		public SearchHelpFormat(String boardName, String threadNumber, String threadUrl) {
			this.boardName = boardName;
			this.threadNumber = threadNumber;
			this.threadUrl = threadUrl;
		}

		public static SearchHelpFormat obtain(Chan chan, boolean allowEmptyBoardName) {
			String boardName = Preferences.getDefaultBoardName(chan);
			if (boardName == null) {
				ArrayList<FavoritesStorage.FavoriteItem> favoriteItems = FavoritesStorage
						.getInstance().getBoards(chan.name);
				if (!favoriteItems.isEmpty()) {
					boardName = favoriteItems.get(0).boardName;
				}
			}
			if (boardName == null) {
				if (allowEmptyBoardName) {
					boardName = "b";
				} else {
					return null;
				}
			}
			String threadNumber = null;
			ArrayList<FavoritesStorage.FavoriteItem> favoriteItems = FavoritesStorage
					.getInstance().getThreads(chan.name);
			if (!favoriteItems.isEmpty()) {
				threadNumber = favoriteItems.get(0).threadNumber;
			}
			if (threadNumber == null) {
				return null;
			}
			Uri uri = chan.locator.safe(false).createThreadUri(boardName, threadNumber);
			if (uri == null) {
				return null;
			}
			return new SearchHelpFormat(boardName, threadNumber, uri.toString());
		}
	}

	private static void showSearchHelp(FragmentManager fragmentManager, SearchHelpFormat searchHelpFormat) {
		new InstanceDialog(fragmentManager, null, provider -> {
			Context context = provider.getContext();
			String html = IOUtils.readRawResourceString(context.getResources(), R.raw.markup_drawer_search)
					.replace("__REPLACE_BOARD_NAME__", searchHelpFormat.boardName)
					.replace("__REPLACE_THREAD_NUMBER__", searchHelpFormat.threadNumber)
					.replace("__REPLACE_THREAD_URL__", searchHelpFormat.threadUrl);
			return new AlertDialog.Builder(context)
					.setTitle(R.string.code_number_address)
					.setMessage(BUILDER_SEARCH_HELP.fromHtmlReduced(html))
					.setPositiveButton(android.R.string.ok, null)
					.create();
		});
	}

	private static final ChanMarkup.MarkupBuilder BUILDER_SEARCH_HELP = new ChanMarkup.MarkupBuilder(markup -> {
		markup.addTag("h1", ChanMarkup.TAG_BOLD);
		markup.addTag("u", ChanMarkup.TAG_UNDERLINE);
	});

	public void updateItems(boolean pages, boolean favorites) {
		ArrayList<AdapterItem> previousItems = createAdapterSnapshot();
		updateItems(pages, favorites, previousItems);
	}

	private void updateItems(boolean pages, boolean favorites, ArrayList<AdapterItem> previousItems) {
		if (pages && pagesListMode != Preferences.PagesListMode.HIDE_PAGES) {
			updateListPages();
		}
		if (favorites) {
			updateListFavorites();
		}
		if (pagesListMode == null) {
			categoriesOrder = null;
		} else {
			switch (pagesListMode) {
				case PAGES_FIRST: {
					categoriesOrder = CategoriesOrder.PAGES_FIRST;
					break;
				}
				case FAVORITES_FIRST: {
					categoriesOrder = CategoriesOrder.FAVORITES_FIRST;
					break;
				}
				case HIDE_PAGES: {
					categoriesOrder = CategoriesOrder.HIDE_PAGES;
					break;
				}
				default: {
					throw new IllegalStateException();
				}
			}
		}
		dispatchAdapterDiff(previousItems);
	}

	private void updateListPages() {
		ArrayList<ListItem> newPages = new ArrayList<>();
		boolean mergeChans = this.mergeChans;
		ArrayList<CombinedFeedStorage.Feed> combinedFeeds = new ArrayList<>();
		if (combinedFeedsEnabled) {
			for (CombinedFeedStorage.Feed feed : CombinedFeedStorage.getInstance().getFeeds()) {
				combinedFeeds.add(feed);
			}
		}
		if (combinedFeedsEnabled) {
			newPages.add(new ListItem(ListItem.Type.SECTION, SECTION_ACTION_COMBINED_FEEDS_SETTINGS,
					ResourceUtils.getResourceId(context, R.attr.iconDrawerMenuPreferences, 0),
					context.getString(R.string.combined_feeds)));
			for (CombinedFeedStorage.Feed feed : combinedFeeds) {
				newPages.add(new ListItem(ListItem.Type.COMBINED_FEED, feed.getPrimaryChanName(),
						feed.id, null, feed.title));
			}
		}
		Collection<Page> allPages = callback.obtainDrawerPages();
		ArrayList<Page> pages = new ArrayList<>();
		for (Page page : allPages) {
			if (mergeChans || page.chanName.equals(chanName)) {
				if (page.threadNumber != null || !Chan.get(page.chanName).configuration
						.getOption(ChanConfiguration.OPTION_SINGLE_BOARD_MODE)) {
					pages.add(page);
				}
			}
		}
		if (pages.size() > 0) {
			Collections.sort(pages);
			newPages.add(new ListItem(ListItem.Type.SECTION, SECTION_ACTION_CLOSE_ALL,
					ResourceUtils.getResourceId(context, R.attr.iconButtonCancel, 0),
					context.getString(R.string.open_pages__noun)));
			int threadCount = 0;
			Page currentThread = null;
			for (Page page : pages) {
				if (page.threadNumber != null) {
					threadCount++;
					if (page.current) {
						currentThread = page;
					}
				}
			}
			boolean collapsible = collapseLongOpenThreadsEnabled
					&& threadCount > COLLAPSED_OPEN_THREAD_LIMIT;
			HashSet<Page> visibleThreads = null;
			if (collapsible && !pagesExpanded) {
				visibleThreads = new HashSet<>(COLLAPSED_OPEN_THREAD_LIMIT);
				if (currentThread != null) {
					visibleThreads.add(currentThread);
				}
				for (Page page : pages) {
					if (page.threadNumber != null && visibleThreads.size() < COLLAPSED_OPEN_THREAD_LIMIT) {
						visibleThreads.add(page);
					}
				}
			}
			for (Page page : pages) {
				if (page.threadNumber != null && visibleThreads != null && !visibleThreads.contains(page)) {
					continue;
				}
				if (page.threadNumber != null) {
					newPages.add(new ListItem(ListItem.Type.PAGE, 0, page.chanName, page.boardName,
							page.threadNumber, page.threadTitle));
				} else {
					newPages.add(new ListItem(ListItem.Type.PAGE, 0, page.chanName, page.boardName,
							null, Chan.get(page.chanName).configuration.getBoardTitle(page.boardName)));
				}
			}
			if (collapsible) {
				int hiddenCount = threadCount - COLLAPSED_OPEN_THREAD_LIMIT;
				newPages.add(new ListItem(ListItem.Type.PAGES_TOGGLE, pagesExpanded ? 1 : 0,
						R.drawable.ic_arrow_drop_down, pagesExpanded
								? context.getString(R.string.collapse_open_threads)
								: context.getString(R.string.show_remaining_open_threads__format, hiddenCount)));
			} else {
				pagesExpanded = false;
			}
		}
		// Build the category off to the side and publish it in one step. Calls reached while page metadata is
		// being resolved must never interleave writes into the adapter's live list and duplicate a section.
		this.pages.clear();
		this.pages.addAll(newPages);
	}

	private void updateListFavorites() {
		this.favorites.clear();
		boolean mergeChans = this.mergeChans;
		FavoritesStorage favoritesStorage = FavoritesStorage.getInstance();
		ArrayList<FavoritesStorage.FavoriteItem> favoriteBoards = favoritesStorage.getBoards(mergeChans
				? null : chanName);
		ArrayList<FavoritesStorage.FavoriteItem> favoriteThreads = favoritesStorage.getThreads(mergeChans
				? null : chanName);
		boolean addSection = true;
		for (int i = 0; i < favoriteThreads.size(); i++) {
			FavoritesStorage.FavoriteItem favoriteItem = favoriteThreads.get(i);
			Chan chan = Chan.get(favoriteItem.chanName);
			if (chan.name == null) {
				continue;
			}
			if (mergeChans || favoriteItem.chanName.equals(chanName)) {
				if (addSection) {
					favorites.add(new ListItem(ListItem.Type.SECTION, SECTION_ACTION_FAVORITES_MENU,
							ResourceUtils.getResourceId(context, R.attr.iconButtonMore, 0),
							context.getString(R.string.favorite_threads)));
					addSection = false;
				}
				if (!isFavoriteThreadHidden(favoriteItem)) {
					ListItem listItem = new ListItem(ListItem.Type.FAVORITE, 0, favoriteItem.chanName,
							favoriteItem.boardName, favoriteItem.threadNumber, favoriteItem.title);
					favorites.add(listItem);
				}
			}
		}
		addSection = true;
		for (int i = 0; i < favoriteBoards.size(); i++) {
			FavoritesStorage.FavoriteItem favoriteItem = favoriteBoards.get(i);
			Chan chan = Chan.get(favoriteItem.chanName);
			if (chan.name == null) {
				continue;
			}
			if (mergeChans || favoriteItem.chanName.equals(chanName)) {
				if (addSection) {
					favorites.add(new ListItem(ListItem.Type.SECTION, null, null, null,
							context.getString(R.string.favorite_boards)));
					addSection = false;
				}
				favorites.add(new ListItem(ListItem.Type.FAVORITE, 0, favoriteItem.chanName, favoriteItem.boardName,
						null, chan.configuration.getBoardTitle(favoriteItem.boardName)));
			}
		}
		if (favoriteSelectionActionMode != null) {
			HashSet<Long> visibleIds = new HashSet<>();
			for (ListItem listItem : this.favorites) {
				if (listItem.type == ListItem.Type.FAVORITE && listItem.isThreadItem()) {
					visibleIds.add(listItem.id);
				}
			}
			selectedFavoriteIds.retainAll(visibleIds);
			updateFavoriteSelectionActionMode();
		}
	}

	private boolean isFavoriteThreadHidden(FavoritesStorage.FavoriteItem favoriteItem) {
		if (Preferences.isFavoritesHidedAll()) {
			return true;
		}
		return Preferences.isFavoritesHidedDeleted() &&
				watcherServiceClient.getCounter(favoriteItem.chanName, favoriteItem.boardName,
						favoriteItem.threadNumber).deleted;
	}

	private String formatBoardThreadTitle(boolean threadItem, String boardName, String threadNumber, String title) {
		if (threadItem) {
			if (!StringUtils.isEmptyOrWhitespace(title)) {
				return title;
			} else {
				return StringUtils.formatThreadTitle(chanName, boardName, threadNumber);
			}
		} else {
			return StringUtils.formatBoardTitle(chanName, boardName, title);
		}
	}

	private static class ListItem {
		public enum Type {HEADER, RESTART, SECTION, COMBINED_FEED, PAGE, PAGES_TOGGLE, FAVORITE, MENU, CHAN}

		public static final ListItem HEADER = new ListItem(Type.HEADER, null, null, null, null);
		public static final ListItem RESTART = new ListItem(Type.RESTART, null, null, null, null);

		public final long id;
		public final Type type;
		public final int data;
		public final boolean iconChan;
		public final int iconResId;
		public final String chanName;
		public final String boardName;
		public final String threadNumber;
		public final String title;
		public final int badgeCount;

		private static final long ID_HASH_OFFSET = 0xcbf29ce484222325L;
		private static final long ID_HASH_PRIME = 0x100000001b3L;

		private static long appendIdHash(long hash, int value) {
			for (int i = 0; i < Integer.BYTES; i++) {
				hash = (hash ^ value & 0xffL) * ID_HASH_PRIME;
				value >>>= 8;
			}
			return hash;
		}

		private static long appendIdHash(long hash, String value) {
			if (value != null) {
				for (int i = 0; i < value.length(); i++) {
					hash = (hash ^ value.charAt(i)) * ID_HASH_PRIME;
				}
			}
			return (hash ^ 0xffL) * ID_HASH_PRIME;
		}

		private static long calculateId(Type type, int data,
				String chanName, String boardName, String threadNumber, String title) {
			long hash = appendIdHash(ID_HASH_OFFSET, type.ordinal());
			switch (type) {
				case COMBINED_FEED:
				case PAGE:
				case FAVORITE: {
					hash = appendIdHash(hash, chanName);
					hash = appendIdHash(hash, boardName);
					return appendIdHash(hash, threadNumber);
				}
				case CHAN: {
					return appendIdHash(hash, chanName);
				}
				case PAGES_TOGGLE: {
					return hash;
				}
				case SECTION: {
					hash = appendIdHash(hash, data);
					return appendIdHash(hash, title);
				}
				case MENU: {
					return appendIdHash(hash, data);
				}
				case HEADER:
				case RESTART: {
					return hash;
				}
				default: {
					throw new IllegalStateException();
				}
			}
		}

		private ListItem(Type type, int data, boolean iconChan, int iconResId,
				String chanName, String boardName, String threadNumber, String title, int badgeCount) {
			id = calculateId(type, data, chanName, boardName, threadNumber, title);
			this.type = type;
			this.data = data;
			this.iconChan = iconChan;
			this.iconResId = iconResId;
			this.chanName = chanName;
			this.boardName = boardName;
			this.threadNumber = threadNumber;
			this.title = title;
			this.badgeCount = badgeCount;
		}

		public ListItem(Type type, int data, int iconResId,
				String chanName, String boardName, String threadNumber, String title) {
			this(type, data, false, iconResId, chanName, boardName, threadNumber, title, 0);
		}

		public ListItem(Type type, int data, String chanName, String boardName, String threadNumber, String title) {
			this(type, data, true, 0, chanName, boardName, threadNumber, title, 0);
		}

		public ListItem(Type type, String chanName, String boardName, String threadNumber, String title) {
			this(type, 0, 0, chanName, boardName, threadNumber, title);
		}

		public ListItem(Type type, int data, int iconResId, String title) {
			this(type, data, false, iconResId, null, null, null, title, 0);
		}

		public ListItem(Type type, int data, int iconResId, String title, int badgeCount) {
			this(type, data, false, iconResId, null, null, null, title, badgeCount);
		}

		public boolean isThreadItem() {
			return threadNumber != null;
		}

		public boolean contentEquals(ListItem other) {
			return other != null && type == other.type && data == other.data && iconChan == other.iconChan &&
					iconResId == other.iconResId && CommonUtils.equals(chanName, other.chanName) &&
					CommonUtils.equals(boardName, other.boardName) &&
					CommonUtils.equals(threadNumber, other.threadNumber) && CommonUtils.equals(title, other.title)
					&& badgeCount == other.badgeCount;
		}

		public boolean compare(String chanName, String boardName, String threadNumber) {
			return CommonUtils.equals(this.chanName, chanName) && CommonUtils.equals(this.boardName, boardName)
					&& CommonUtils.equals(this.threadNumber, threadNumber);
		}
	}

	private final View.OnClickListener closeButtonListener = new View.OnClickListener() {
		@Override
		public void onClick(View v) {
			ListItem listItem = getItemFromChild(v);
			if (listItem != null && listItem.type == ListItem.Type.PAGE) {
				callback.onClosePage(listItem.chanName, listItem.boardName, listItem.threadNumber);
			}
		}
	};

	private static final int SECTION_ACTION_CLOSE_ALL = 0;
	private static final int SECTION_ACTION_FAVORITES_MENU = 1;
	private static final int SECTION_ACTION_COMBINED_FEEDS_SETTINGS = 2;

	private static final int FAVORITES_MENU_REFRESH = 1;
	private static final int FAVORITES_MENU_CLEAR_DELETED = 2;
	private static final int FAVORITES_MENU_HIDE_DELETED = 3;
	private static final int FAVORITES_MENU_HIDE_ALL = 4;
	private static final int FAVORITES_MENU_SELECT = 5;
	private static final int FAVORITE_SELECTION_SELECT_ALL = 1;
	private static final int FAVORITE_SELECTION_DELETE = 2;

	private final View.OnClickListener sectionButtonListener = new View.OnClickListener() {
		@SuppressLint("NewApi")
		@Override
		public void onClick(View v) {
			ListItem listItem = getItemFromChild(v);
			if (listItem != null && listItem.type == ListItem.Type.SECTION) {
					switch (listItem.data) {
					case SECTION_ACTION_COMBINED_FEEDS_SETTINGS: {
						callback.onSelectDrawerMenuItem(MENU_ITEM_COMBINED_FEEDS);
						break;
					}
					case SECTION_ACTION_CLOSE_ALL: {
						callback.onCloseAllPages();
						break;
					}
					case SECTION_ACTION_FAVORITES_MENU: {
						boolean hasEnabled = false;
						ArrayList<FavoritesStorage.FavoriteItem> deleteFavoriteItems = new ArrayList<>();
						FavoritesStorage favoritesStorage = FavoritesStorage.getInstance();
						for (ListItem itListItem : favorites) {
							if (itListItem.isThreadItem()) {
								FavoritesStorage.FavoriteItem favoriteItem = favoritesStorage.getFavorite
										(itListItem.chanName, itListItem.boardName, itListItem.threadNumber);
								if (favoriteItem != null) {
									hasEnabled |= favoriteItem.watcherEnabled;
									if (getCounter(itListItem).deleted) {
										deleteFavoriteItems.add(favoriteItem);
									}
								}
							}
						}
						int resId = ResourceUtils.getResourceId(context, android.R.attr.popupTheme, 0);
						Context context = v.getContext();
						Context popupContext = resId != 0 ? new ContextThemeWrapper(context, resId) : context;
						PopupMenu popupMenu = new PopupMenu(popupContext, v, Gravity.END, 0,
								R.style.Widget_OverlapPopupMenu);
						popupMenu.getMenu().add(0, FAVORITES_MENU_REFRESH, 0, R.string.refresh)
								.setEnabled(hasEnabled);
						popupMenu.getMenu().add(0, FAVORITES_MENU_CLEAR_DELETED, 0, R.string.clear_deleted)
								.setEnabled(!deleteFavoriteItems.isEmpty());
						popupMenu.getMenu().add(0, FAVORITES_MENU_HIDE_DELETED, 0,
								Preferences.isFavoritesHidedDeleted()
										? R.string.favorites_show_deleted : R.string.favorites_hide_deleted)
								.setEnabled(!Preferences.isFavoritesHidedAll());
						popupMenu.getMenu().add(0, FAVORITES_MENU_HIDE_ALL, 0,
								Preferences.isFavoritesHidedAll()
										? R.string.favorites_show_all : R.string.favorites_hide_all);
						popupMenu.getMenu().add(0, FAVORITES_MENU_SELECT, 0, R.string.select_threads)
								.setEnabled(getVisibleFavoriteThreadCount() > 0);
						popupMenu.setOnMenuItemClickListener(item -> {
							switch (item.getItemId()) {
								case FAVORITES_MENU_REFRESH: {
									if (mergeChans) {
										watcherServiceClient.refreshAll(null);
									} else if (chanName != null) {
										watcherServiceClient.refreshAll(chanName);
									}
									return true;
								}
								case FAVORITES_MENU_CLEAR_DELETED: {
									StringBuilder builder = new StringBuilder(context
											.getString(R.string.threads_will_be_deleted__sentence));
									builder.append("\n");
									for (FavoritesStorage.FavoriteItem favoriteItem : deleteFavoriteItems) {
										builder.append("\n\u2022 ").append(formatBoardThreadTitle(true,
												favoriteItem.boardName, favoriteItem.threadNumber, favoriteItem.title));
									}
									showDeleteFavoritesDialog(fragmentManager, builder, deleteFavoriteItems);
									return true;
								}
								case FAVORITES_MENU_HIDE_DELETED: {
									Preferences.setFavoritesHideDeleted(!Preferences.isFavoritesHidedDeleted());
									updateItems(false, true);
									return true;
								}
								case FAVORITES_MENU_HIDE_ALL: {
									boolean hideAll = !Preferences.isFavoritesHidedAll();
									if (!hideAll) {
										Preferences.setFavoritesHideDeleted(false);
									}
									Preferences.setFavoritesHideAll(hideAll);
									updateItems(false, true);
									return true;
								}
								case FAVORITES_MENU_SELECT: {
									startFavoriteSelection();
									return true;
								}
							}
							return false;
						});
						popupMenu.show();
						break;
					}
				}
			}
		}
	};

	private final ActionMode.Callback favoriteSelectionCallback = new ActionMode.Callback() {
		@Override
		public boolean onCreateActionMode(ActionMode mode, Menu menu) {
			mode.setTitle(context.getString(R.string.selected) + ": 0");
			menu.add(0, FAVORITE_SELECTION_SELECT_ALL, 0, R.string.select_all)
					.setShowAsAction(MenuItem.SHOW_AS_ACTION_IF_ROOM);
			menu.add(0, FAVORITE_SELECTION_DELETE, 1, R.string.delete)
					.setShowAsAction(MenuItem.SHOW_AS_ACTION_IF_ROOM);
			menu.findItem(FAVORITE_SELECTION_DELETE).setEnabled(false);
			return true;
		}

		@Override
		public boolean onPrepareActionMode(ActionMode mode, Menu menu) {
			MenuItem delete = menu.findItem(FAVORITE_SELECTION_DELETE);
			if (delete != null) delete.setEnabled(!selectedFavoriteIds.isEmpty());
			return true;
		}

		@Override
		public boolean onActionItemClicked(ActionMode mode, MenuItem item) {
			switch (item.getItemId()) {
				case FAVORITE_SELECTION_SELECT_ALL: {
					ArrayList<AdapterItem> previousItems = createAdapterSnapshot();
					selectedFavoriteIds.clear();
					for (ListItem listItem : favorites) {
						if (listItem.type == ListItem.Type.FAVORITE && listItem.isThreadItem()) {
							selectedFavoriteIds.add(listItem.id);
						}
					}
					dispatchAdapterDiff(previousItems);
					updateFavoriteSelectionActionMode();
					return true;
				}
				case FAVORITE_SELECTION_DELETE: {
					ArrayList<FavoritesStorage.FavoriteItem> selected = collectSelectedFavoriteThreads();
					if (!selected.isEmpty()) showDeleteSelectedFavoritesDialog(mode, selected);
					return true;
				}
			}
			return false;
		}

		@Override
		public void onDestroyActionMode(ActionMode mode) {
			ArrayList<AdapterItem> previousItems = createAdapterSnapshot();
			favoriteSelectionActionMode = null;
			selectedFavoriteIds.clear();
			dispatchAdapterDiff(previousItems);
		}
	};

	private void showDeleteSelectedFavoritesDialog(ActionMode actionMode,
			ArrayList<FavoritesStorage.FavoriteItem> selected) {
		new InstanceDialog(fragmentManager, null, provider -> new AlertDialog.Builder(provider.getContext())
				.setMessage(provider.getContext().getResources().getQuantityString(
						R.plurals.favorites_remove_selected_confirmation__format,
						selected.size(), selected.size()))
				.setNegativeButton(android.R.string.cancel, null)
				.setPositiveButton(R.string.delete, (dialog, which) -> {
					FavoritesStorage.getInstance().remove(selected);
					actionMode.finish();
				})
				.create());
	}

	private static void showDeleteFavoritesDialog(FragmentManager fragmentManager,
			CharSequence message, List<FavoritesStorage.FavoriteItem> deleteFavoriteItems) {
		new InstanceDialog(fragmentManager, null, provider -> new AlertDialog
				.Builder(provider.getContext())
				.setMessage(message)
				.setNegativeButton(android.R.string.cancel, null)
				.setPositiveButton(android.R.string.ok, (d, which) -> {
					FavoritesStorage.getInstance().remove(deleteFavoriteItems);
				})
				.create());
	}

	private enum ViewType {
		HEADER(false, false, false),
		RESTART(false, false, false),
		SECTION(false, false, false),
		SECTION_BUTTON(true, false, false),
		ITEM(false, false, false),
		ITEM_ICON(true, false, false),
		WATCHER(false, true, false),
		WATCHER_ICON(true, true, false),
		CLOSEABLE(false, false, true),
		CLOSEABLE_ICON(true, false, true);

		public final boolean icon;
		public final boolean watcher;
		public final boolean closeable;

		ViewType(boolean icon, boolean watcher, boolean closeable) {
			this.icon = icon;
			this.watcher = watcher;
			this.closeable = closeable;
		}
	}

	@Override
	public int getItemViewType(int position) {
		ListItem listItem = getItem(position);
		ViewType viewType;
		switch (listItem.type) {
			case HEADER: {
				viewType = ViewType.HEADER;
				break;
			}
			case RESTART: {
				viewType = ViewType.RESTART;
				break;
			}
			case SECTION: {
				viewType = listItem.iconChan || listItem.iconResId != 0
						? ViewType.SECTION_BUTTON : ViewType.SECTION;
				break;
			}
			case COMBINED_FEED: {
				viewType = ViewType.ITEM;
				break;
			}
			case PAGE: {
				viewType = mergeChans ? ViewType.CLOSEABLE_ICON : ViewType.CLOSEABLE;
				break;
			}
			case PAGES_TOGGLE: {
				viewType = ViewType.ITEM_ICON;
				break;
			}
			case FAVORITE: {
				if (listItem.threadNumber != null) {
					boolean watcherSupported = watcherSupportSet.contains(listItem.chanName);
					if (mergeChans) {
						viewType = watcherSupported ? ViewType.WATCHER_ICON : ViewType.ITEM_ICON;
					} else {
						viewType = watcherSupported ? ViewType.WATCHER : ViewType.ITEM;
					}
				} else {
					viewType = mergeChans ? ViewType.ITEM_ICON : ViewType.ITEM;
				}
				break;
			}
			case MENU: {
				viewType = ViewType.ITEM_ICON;
				break;
			}
			case CHAN: {
				viewType = ViewType.ITEM_ICON;
				break;
			}
			default: {
				throw new IllegalStateException();
			}
		}
		return viewType.ordinal();
	}

	@SuppressWarnings("unchecked")
	private final List<ListItem>[] categoriesArray = new List[2];

	private static class AdapterItem {
		public final ListItem listItem;
		public final int viewType;
		public final boolean selected;

		public AdapterItem(ListItem listItem, int viewType, boolean selected) {
			this.listItem = listItem;
			this.viewType = viewType;
			this.selected = selected;
		}

		public boolean contentEquals(AdapterItem other) {
			return other != null && viewType == other.viewType && selected == other.selected &&
					listItem.contentEquals(other.listItem);
		}
	}

	private ArrayList<AdapterItem> createAdapterSnapshot() {
		if (categoriesOrder == null) {
			return null;
		}
		int count = getItemCount();
		ArrayList<AdapterItem> items = new ArrayList<>(count);
		for (int position = 0; position < count; position++) {
			ListItem listItem = getItem(position);
			boolean selected = favoriteSelectionActionMode != null && selectedFavoriteIds.contains(listItem.id);
			items.add(new AdapterItem(listItem, getItemViewType(position), selected));
		}
		return items;
	}

	private void dispatchAdapterDiff(ArrayList<AdapterItem> previousItems) {
		if (previousItems == null) {
			notifyDataSetChanged();
			scheduleDrawerPrewarm();
			return;
		}
		ArrayList<AdapterItem> currentItems = createAdapterSnapshot();
		if (currentItems == null) {
			notifyDataSetChanged();
			scheduleDrawerPrewarm();
			return;
		}
		Trace.beginSection("DrawerForm#calculateDiff");
		try {
			DiffUtil.calculateDiff(new DiffUtil.Callback() {
				@Override
				public int getOldListSize() {
					return previousItems.size();
				}

				@Override
				public int getNewListSize() {
					return currentItems.size();
				}

				@Override
				public boolean areItemsTheSame(int oldItemPosition, int newItemPosition) {
					return previousItems.get(oldItemPosition).listItem.id ==
							currentItems.get(newItemPosition).listItem.id;
				}

				@Override
				public boolean areContentsTheSame(int oldItemPosition, int newItemPosition) {
					return previousItems.get(oldItemPosition).contentEquals(currentItems.get(newItemPosition));
				}
			}, false).dispatchUpdatesTo(this);
		} finally {
			Trace.endSection();
		}
		scheduleDrawerPrewarm();
	}

	private void scheduleDrawerPrewarm() {
		if (drawerPrewarmed || drawerPrewarmScheduled || categoriesOrder == null) {
			return;
		}
		drawerPrewarmScheduled = true;
		drawerPrewarmViewTypes = null;
		drawerPrewarmViewTypeIndex = 0;
		// RecyclerView otherwise creates every visible drawer row during the first opening animation. Create
		// only one holder per idle opportunity so prewarming itself cannot become a long UI-thread stall.
		Looper.myQueue().addIdleHandler(this::prewarmNextDrawerHolder);
	}

	private boolean prewarmNextDrawerHolder() {
		if (drawerOpened || drawerAlwaysVisible || drawerState != DrawerLayout.STATE_IDLE || drawerPrewarmed) {
			drawerPrewarmScheduled = false;
			drawerPrewarmViewTypes = null;
			return false;
		}
		if (drawerPrewarmViewTypes == null) {
			int count = Math.min(getItemCount(), PREWARM_ITEM_COUNT);
			int[] viewTypes = new int[count];
			int viewTypesCount = 0;
			int[] typeCounts = new int[ViewType.values().length];
			for (int position = 0; position < count; position++) {
				int viewType = getItemViewType(position);
				ViewType type = ViewType.values()[viewType];
				if (type != ViewType.HEADER && type != ViewType.RESTART) {
					viewTypes[viewTypesCount++] = viewType;
					typeCounts[viewType]++;
				}
			}
			drawerPrewarmViewTypes = Arrays.copyOf(viewTypes, viewTypesCount);
			RecyclerView.RecycledViewPool pool = recyclerView.getRecycledViewPool();
			for (int viewType = 0; viewType < typeCounts.length; viewType++) {
				if (typeCounts[viewType] > 0) {
					pool.setMaxRecycledViews(viewType, Math.max(5, typeCounts[viewType]));
				}
			}
		}
		if (drawerPrewarmViewTypeIndex >= drawerPrewarmViewTypes.length) {
			drawerPrewarmed = true;
			drawerPrewarmScheduled = false;
			drawerPrewarmViewTypes = null;
			return false;
		}
		Trace.beginSection("DrawerForm#prewarmHolder");
		try {
			RecyclerView.RecycledViewPool pool = recyclerView.getRecycledViewPool();
			int viewType = drawerPrewarmViewTypes[drawerPrewarmViewTypeIndex++];
			pool.putRecycledView(createViewHolder(recyclerView, viewType));
		} finally {
			Trace.endSection();
		}
		return true;
	}

	private int prepareCategoriesArray() {
		switch (categoriesOrder) {
			case PAGES_FIRST: {
				categoriesArray[0] = pages;
				categoriesArray[1] = favorites;
				return 2;
			}
			case FAVORITES_FIRST: {
				categoriesArray[0] = favorites;
				categoriesArray[1] = pages;
				return 2;
			}
			case HIDE_PAGES: {
				categoriesArray[0] = favorites;
				return 1;
			}
			default: {
				return 0;
			}
		}
	}

	@Override
	public int getItemCount() {
		int count = showRestartButton ? 2 : 1;
		if (chanSelectMode) {
			count += chans.size();
		} else {
			int arraySize = prepareCategoriesArray();
			List<ListItem>[] categoriesArray = this.categoriesArray;
			for (int i = 0; i < arraySize; i++) {
				count += categoriesArray[i].size();
			}
			count += menu.size();
		}
		return count;
	}

	private ListItem getItem(int position) {
		if (position == 0) {
			return ListItem.HEADER;
		}
		position--;
		if (showRestartButton) {
			if (position == 0) {
				return ListItem.RESTART;
			}
			position--;
		}
		if (position >= 0) {
			if (chanSelectMode) {
				if (position < chans.size()) {
					return chans.get(position);
				}
			} else {
				int arraySize = prepareCategoriesArray();
				List<ListItem>[] categoriesArray = this.categoriesArray;
				for (int i = 0; i < arraySize; i++) {
					List<ListItem> listItems = categoriesArray[i];
					if (position < listItems.size()) {
						return listItems.get(position);
					}
					position -= listItems.size();
					if (position < 0) {
						throw new IndexOutOfBoundsException();
					}
				}
				if (position < menu.size()) {
					return menu.get(position);
				}
			}
		}
		throw new IndexOutOfBoundsException();
	}

	@Override
	public long getItemId(int position) {
		return getItem(position).id;
	}

	private TextView makeCommonTextView(boolean section) {
		TextView textView = new TextView(context, null, android.R.attr.textAppearanceListItem);
		ThemeEngine.applyStyle(textView);
		ViewUtils.setTextSizeScaled(textView, 14);
		textView.setGravity(Gravity.CENTER_VERTICAL);
		textView.setEllipsize(TextUtils.TruncateAt.END);
		textView.setSingleLine(true);
		textView.setTypeface(ResourceUtils.TYPEFACE_MEDIUM);
		int color = textView.getTextColors().getDefaultColor();
		if (section) {
			color &= 0x5effffff;
		} else {
			color &= 0xddffffff;
		}
		textView.setTextColor(color);
		return textView;
	}

	private ViewHolder createItem(ViewType viewType, float density) {
		int size = (int) (48f * density);
		LinearLayout linearLayout = new LinearLayout(context);
		linearLayout.setOrientation(LinearLayout.HORIZONTAL);
		linearLayout.setGravity(Gravity.CENTER_VERTICAL);
		CheckBox selectionView = new CheckBox(context);
		selectionView.setClickable(false);
		selectionView.setFocusable(false);
		selectionView.setVisibility(View.GONE);
		linearLayout.addView(selectionView, size, size);
		ImageView iconView = null;
		if (viewType.icon) {
			iconView = new ImageView(context);
			iconView.setScaleType(ImageView.ScaleType.CENTER_INSIDE);
			linearLayout.addView(iconView, (int) (24f * density), size);
			iconView.setImageTintList(ColorStateList.valueOf(drawerIconColor));
		}
		TextView textView = makeCommonTextView(false);
		linearLayout.addView(textView, new LinearLayout.LayoutParams(0, size, 1));
		TextView badgeView = makeCommonTextView(false);
		badgeView.setGravity(Gravity.CENTER);
		badgeView.setVisibility(View.GONE);
		badgeView.setPadding((int) (8f * density), 0, (int) (16f * density), 0);
		linearLayout.addView(badgeView, LinearLayout.LayoutParams.WRAP_CONTENT, size);
		WatcherView watcherView = null;
		if (viewType.watcher) {
			watcherView = new WatcherView(context, watcherViewColorSet);
			watcherView.setOnClickListener(watcherClickListener);
			linearLayout.addView(watcherView, size, size);
		}
		if (!viewType.watcher && viewType.closeable) {
			ImageView closeView = new ImageView(context);
			closeView.setScaleType(ImageView.ScaleType.CENTER);
			closeView.setImageResource(ResourceUtils.getResourceId(context, R.attr.iconButtonCancel, 0));
			closeView.setImageTintList(ResourceUtils.getColorStateList(closeView.getContext(),
					android.R.attr.textColorPrimary));
			closeView.setBackgroundResource(ResourceUtils.getResourceId(context,
					android.R.attr.borderlessButtonStyle, android.R.attr.background, 0));
			linearLayout.addView(closeView, size, size);
			closeView.setOnClickListener(closeButtonListener);
		}
		int layoutLeftDp = 0;
		int layoutRightDp = 0;
		int textLeftDp;
		int textRightDp;
		textLeftDp = 16;
		textRightDp = 16;
		if (viewType.icon) {
			layoutLeftDp = 16;
			textLeftDp = 32;
		}
		if (viewType.watcher || viewType.closeable) {
			layoutRightDp = 4;
			textRightDp = 8;
		}
		linearLayout.setPadding((int) (layoutLeftDp * density), 0, (int) (layoutRightDp * density), 0);
		textView.setPadding((int) (textLeftDp * density), 0, (int) (textRightDp * density), 0);
		ViewUtils.setSelectableItemBackground(linearLayout);
		linearLayout.setLayoutParams(new RecyclerView.LayoutParams(RecyclerView.LayoutParams.MATCH_PARENT,
				RecyclerView.LayoutParams.WRAP_CONTENT));
		return new ViewHolder(linearLayout, iconView, textView, badgeView, watcherView, selectionView);
	}

	private ViewHolder createSection(ViewGroup parent, boolean button, float density) {
		LinearLayout linearLayout = new LinearLayout(context);
		linearLayout.setOrientation(LinearLayout.VERTICAL);
		LinearLayout linearLayout2 = new LinearLayout(context);
		linearLayout2.setOrientation(LinearLayout.HORIZONTAL);
		linearLayout.addView(linearLayout2, LinearLayout.LayoutParams.MATCH_PARENT,
				LinearLayout.LayoutParams.WRAP_CONTENT);
		TextView textView = makeCommonTextView(true);
		LinearLayout.LayoutParams layoutParams = new LinearLayout.LayoutParams(0, (int) (32f * density), 1);
		layoutParams.setMargins((int) (16f * density), (int) (8f * density),
				(int) (16f * density), (int) (8f * density));
		linearLayout2.addView(textView, layoutParams);
		ImageView imageView = null;
		if (button) {
			imageView = new ImageView(context);
			imageView.setScaleType(ImageView.ScaleType.CENTER);
			imageView.setBackgroundResource(ResourceUtils.getResourceId(context,
					android.R.attr.borderlessButtonStyle, android.R.attr.background, 0));
			imageView.setOnClickListener(sectionButtonListener);
			imageView.setImageTintList(textView.getTextColors());
			int size = (int) (48f * density);
			layoutParams = new LinearLayout.LayoutParams(size, size);
			layoutParams.rightMargin = (int) (4f * density);
			linearLayout2.addView(imageView, layoutParams);
		}
		linearLayout.setLayoutParams(new RecyclerView.LayoutParams(RecyclerView.LayoutParams.MATCH_PARENT,
				RecyclerView.LayoutParams.WRAP_CONTENT));
		return new ViewHolder(linearLayout, imageView, textView, null, null, null);
	}

	private final ListViewUtils.ClickCallback<Void, ViewHolder> clickCallback = (holder, position, item, longClick) -> {
		if (longClick) {
			return onItemLongClick(holder);
		} else {
			onItemClick(position);
			return true;
		}
	};

	@NonNull
	@Override
	public ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
		float density = ResourceUtils.obtainDensity(context);
		ViewType enumViewType = ViewType.values()[viewType];
		switch (enumViewType) {
			case HEADER: {
				return new ViewHolder(headerView, null, null, null, null, null);
			}
			case RESTART: {
				return new ViewHolder(restartView, null, null, null, null, null);
			}
			case SECTION:
			case SECTION_BUTTON: {
				return createSection(parent, enumViewType.icon, density);
			}
			case ITEM:
			case ITEM_ICON:
			case WATCHER:
			case WATCHER_ICON:
			case CLOSEABLE:
			case CLOSEABLE_ICON: {
				return ListViewUtils.bind(createItem(enumViewType, density), true, null, clickCallback);
			}
			default: {
				throw new IllegalStateException();
			}
		}
	}

	@Override
	public void onBindViewHolder(@NonNull ViewHolder holder, int position) {
		ListItem listItem = getItem(position);
		if (holder.badge != null) {
			holder.badge.setText(listItem.badgeCount > 0 ? Integer.toString(listItem.badgeCount) : null);
			holder.badge.setVisibility(listItem.badgeCount > 0 ? View.VISIBLE : View.GONE);
		}
		boolean selectableFavorite = favoriteSelectionActionMode != null
				&& listItem.type == ListItem.Type.FAVORITE && listItem.isThreadItem();
		if (holder.selection != null) {
			holder.selection.setVisibility(selectableFavorite ? View.VISIBLE : View.GONE);
			holder.selection.setChecked(selectableFavorite && selectedFavoriteIds.contains(listItem.id));
		}
		if (holder.watcher != null) {
			holder.watcher.setEnabled(favoriteSelectionActionMode == null);
			holder.watcher.setAlpha(favoriteSelectionActionMode == null ? 1f : 0.45f);
		}
		if (listItem.type == ListItem.Type.SECTION) {
			holder.itemView.setOnClickListener(listItem.data == SECTION_ACTION_COMBINED_FEEDS_SETTINGS
					? sectionButtonListener : null);
		}
		switch (listItem.type) {
			case HEADER:
			case RESTART: {
				// Do nothing
				break;
			}
			case COMBINED_FEED:
			case PAGE:
			case FAVORITE: {
				holder.text.setText(listItem.type == ListItem.Type.COMBINED_FEED ? listItem.title
						: formatBoardThreadTitle(listItem.isThreadItem(),
								listItem.boardName, listItem.threadNumber, listItem.title));
				if (listItem.type == ListItem.Type.FAVORITE && listItem.isThreadItem() &&
						watcherSupportSet.contains(listItem.chanName)) {
					holder.watcher.setProgressAnimationEnabled(canApplyWatcherUpdates());
					holder.watcher.update(getCounter(listItem));
				}
				break;
			}
			case SECTION:
			case PAGES_TOGGLE:
			case MENU:
			case CHAN: {
				holder.text.setText(listItem.title);
				break;
			}
		}
		if (holder != null && holder.icon != null) {
			holder.icon.setRotation(listItem.type == ListItem.Type.PAGES_TOGGLE && listItem.data != 0
					? 180f : 0f);
			if (listItem.iconChan) {
				if (!chanIcons.containsKey(listItem.chanName)) {
					ChanIconDrawable drawable = ChanManager.getInstance().getIcon(Chan.get(listItem.chanName));
					chanIcons.put(listItem.chanName, drawable);
				}
				ChanIconDrawable chanIcon = chanIcons.get(listItem.chanName);
				holder.icon.setImageDrawable(chanIcon != null ? chanIcon.newInstance() : null);
			} else if (listItem.iconResId != 0) {
				holder.icon.setImageResource(listItem.iconResId);
			} else {
				throw new IllegalStateException();
			}
		}
	}

	public static class ViewHolder extends RecyclerView.ViewHolder implements View.OnTouchListener {
		public final ImageView icon;
		public final TextView text;
		public final TextView badge;
		public final WatcherView watcher;
		public final CheckBox selection;

		public ViewHolder(View itemView, ImageView icon, TextView text, TextView badge,
				WatcherView watcher, CheckBox selection) {
			super(itemView);

			this.text = text;
			this.icon = icon;
			this.badge = badge;
			this.watcher = watcher;
			this.selection = selection;

			itemView.setOnTouchListener(this);
		}

		private ColorStateList originalTextColors;
		private ColorStateList originalTintColors;

		public void setDragging(boolean dragging, int activeColor) {
			if (dragging) {
				if (originalTextColors == null) {
					originalTextColors = text.getTextColors();
				}
				text.setTextColor(activeColor);
				if (icon != null) {
					if (originalTintColors == null) {
						originalTintColors = icon.getImageTintList();
					}
					icon.setImageTintList(ColorStateList.valueOf(activeColor));
				}
			} else {
				if (originalTextColors != null) {
					text.setTextColor(originalTextColors);
				}
				if (icon != null && originalTintColors != null) {
					icon.setImageTintList(originalTintColors);
				}
			}
		}

		private boolean multipleFingersCountingTime = false;
		private long multipleFingersTime;
		private long multipleFingersStartTime;

		@SuppressLint("ClickableViewAccessibility")
		@Override
		public boolean onTouch(View v, MotionEvent event) {
			switch (event.getActionMasked()) {
				case MotionEvent.ACTION_DOWN: {
					multipleFingersCountingTime = false;
					multipleFingersStartTime = 0L;
					multipleFingersTime = 0L;
					break;
				}
				case MotionEvent.ACTION_POINTER_DOWN: {
					if (!multipleFingersCountingTime) {
						multipleFingersCountingTime = true;
						multipleFingersStartTime = SystemClock.elapsedRealtime();
					}
					break;
				}
				case MotionEvent.ACTION_POINTER_UP: {
					if (event.getPointerCount() <= 2) {
						if (multipleFingersCountingTime) {
							multipleFingersCountingTime = false;
							multipleFingersTime += SystemClock.elapsedRealtime() - multipleFingersStartTime;
						}
					}
					break;
				}
			}
			return false;
		}

		public boolean isMultipleFingers() {
			long time = multipleFingersTime;
			if (multipleFingersCountingTime) {
				time += SystemClock.elapsedRealtime() - multipleFingersStartTime;
			}
			return time >= ViewConfiguration.getLongPressTimeout() / 10;
		}
	}

	private ListItem getItemFromChild(View child) {
		View view = ListViewUtils.getRootViewInList(child);
		ViewHolder holder = ListViewUtils.getViewHolder(view, ViewHolder.class);
		int position = holder.getAdapterPosition();
		return position >= 0 ? getItem(position) : null;
	}

	private boolean needDivider(ListItem current, ListItem next) {
		return current.type == ListItem.Type.HEADER || current.type == ListItem.Type.RESTART ||
				current.type != ListItem.Type.CHAN && next.type == ListItem.Type.CHAN ||
				current.type != ListItem.Type.MENU && next.type == ListItem.Type.MENU ||
				current.type == ListItem.Type.MENU && current.data == MENU_ITEM_BOARDS &&
						(next.type != ListItem.Type.MENU || next.data != MENU_ITEM_USER_BOARDS) ||
				current.type == ListItem.Type.MENU && current.data == MENU_ITEM_USER_BOARDS;
	}

	private DividerItemDecoration.Configuration configureDivider
			(DividerItemDecoration.Configuration configuration, int position) {
		float density = ResourceUtils.obtainDensity(context);
		int padding = (int) (8f * density);
		ListItem current = getItem(position);
		ListItem next = position + 1 < getItemCount() ? getItem(position + 1) : null;
		if (next == null) {
			return configuration.need(false).vertical(0, 0);
		} else if (next.type == ListItem.Type.SECTION) {
			return configuration.need(true).vertical(padding, 0);
		} else if (needDivider(current, next)) {
			return configuration.need(true).vertical(padding, padding);
		} else {
			return configuration.need(false).vertical(0, 0);
		}
	}

	private WatcherService.Counter getCounter(ListItem listItem) {
		return watcherServiceClient.getCounter(listItem.chanName, listItem.boardName, listItem.threadNumber);
	}

	private boolean removeFavoriteThreadFromList(String chanName, String boardName, String threadNumber) {
		boolean removed = false;
		for (int i = favorites.size() - 1; i >= 0; i--) {
			ListItem favorite = favorites.get(i);
			if (favorite.type == ListItem.Type.FAVORITE && favorite.isThreadItem() &&
					favorite.compare(chanName, boardName, threadNumber)) {
				favorites.remove(i);
				removed = true;
			}
		}
		return removed;
	}

	private final View.OnClickListener watcherClickListener = v -> {
		DrawerForm.ListItem listItem = getItemFromChild(v);
		if (listItem != null) {
			FavoritesStorage.getInstance().setWatcherEnabled(listItem.chanName,
					listItem.boardName, listItem.threadNumber, null);
		}
	};

	private static class WatcherUpdateKey {
		public final String chanName;
		public final String boardName;
		public final String threadNumber;

		public WatcherUpdateKey(String chanName, String boardName, String threadNumber) {
			this.chanName = chanName;
			this.boardName = boardName;
			this.threadNumber = threadNumber;
		}

		@Override
		public boolean equals(Object o) {
			if (o == this) {
				return true;
			}
			if (o instanceof WatcherUpdateKey) {
				WatcherUpdateKey key = (WatcherUpdateKey) o;
				return CommonUtils.equals(chanName, key.chanName) && CommonUtils.equals(boardName, key.boardName) &&
						CommonUtils.equals(threadNumber, key.threadNumber);
			}
			return false;
		}

		@Override
		public int hashCode() {
			int result = chanName != null ? chanName.hashCode() : 0;
			result = 31 * result + (boardName != null ? boardName.hashCode() : 0);
			return 31 * result + (threadNumber != null ? threadNumber.hashCode() : 0);
		}
	}

	private boolean shouldRemoveWatcherItem(WatcherService.Counter counter) {
		return counter.deleted && Preferences.isFavoritesHidedDeleted();
	}

	private void updateVisibleWatcher(WatcherUpdateKey key, WatcherService.Counter counter) {
		if (!Preferences.isFavoritesHidedAll() && (mergeChans || key.chanName.equals(chanName))) {
			long id = ListItem.calculateId(ListItem.Type.FAVORITE, 0,
					key.chanName, key.boardName, key.threadNumber, null);
			RecyclerView.ViewHolder holder = recyclerView.findViewHolderForItemId(id);
			if (holder instanceof ViewHolder && ((ViewHolder) holder).watcher != null) {
				((ViewHolder) holder).watcher.update(counter);
			}
		}
	}

	private void flushPendingWatcherUpdates() {
		if (!pendingWatcherUpdates.isEmpty()) {
			Trace.beginSection("DrawerForm#flushWatcherUpdates");
			ArrayList<AdapterItem> previousItems = null;
			boolean itemsRemoved = false;
			try {
				for (Map.Entry<WatcherUpdateKey, WatcherService.Counter> entry
						: pendingWatcherUpdates.entrySet()) {
					WatcherService.Counter counter = entry.getValue();
					if (shouldRemoveWatcherItem(counter)) {
						if (previousItems == null) {
							previousItems = createAdapterSnapshot();
						}
						WatcherUpdateKey key = entry.getKey();
						itemsRemoved |= removeFavoriteThreadFromList(key.chanName, key.boardName,
								key.threadNumber);
					} else {
						updateVisibleWatcher(entry.getKey(), counter);
					}
				}
				pendingWatcherUpdates.clear();
				if (itemsRemoved) {
					dispatchAdapterDiff(previousItems);
				}
			} finally {
				Trace.endSection();
			}
		}
	}

	public void onWatcherUpdate(String chanName, String boardName, String threadNumber,
			WatcherService.Counter counter) {
		WatcherUpdateKey key = new WatcherUpdateKey(chanName, boardName, threadNumber);
		if (!canApplyWatcherUpdates()) {
			pendingWatcherUpdates.put(key, counter);
		} else if (shouldRemoveWatcherItem(counter)) {
			// Only structural watcher changes need a list snapshot and DiffUtil pass.
			ArrayList<AdapterItem> previousItems = createAdapterSnapshot();
			if (removeFavoriteThreadFromList(key.chanName, key.boardName, key.threadNumber)) {
				dispatchAdapterDiff(previousItems);
			}
		} else {
			updateVisibleWatcher(key, counter);
		}
	}

	private final SortableHelper.DragState chanDragState = new SortableHelper.DragState();
	private final SortableHelper.DragState favoriteDragState = new SortableHelper.DragState();

	@Override
	public void onDragStart(ViewHolder holder) {
		chanDragState.reset();
		favoriteDragState.reset();
		holder.setDragging(true, watcherViewColorSet.enabledColor);
		callback.onDraggingStateChanged(true);
	}

	@Override
	public void onDragFinish(ViewHolder holder, boolean cancelled) {
		if (!cancelled) {
			int chanMovedTo = chanDragState.getMovedTo();
			int favoriteMovedTo = favoriteDragState.getMovedTo();
			if (chanMovedTo >= 0) {
				ArrayList<String> chanNames = new ArrayList<>();
				for (DrawerForm.ListItem listItem : chans) {
					chanNames.add(listItem.chanName);
				}
				Preferences.setChansOrder(chanNames);
				// Regroup favorite threads
				if (mergeChans) {
					updateItems(false, true);
				}
			} else if (favoriteMovedTo >= 0) {
				// "to" is always > 0 since favorites list contains header
				DrawerForm.ListItem listItem = favorites.get(favoriteMovedTo);
				DrawerForm.ListItem afterListItem = favorites.get(favoriteMovedTo - 1);
				FavoritesStorage favoritesStorage = FavoritesStorage.getInstance();
				FavoritesStorage.FavoriteItem favoriteItem = favoritesStorage.getFavorite(listItem.chanName,
						listItem.boardName, listItem.threadNumber);
				FavoritesStorage.FavoriteItem afterFavoriteItem = afterListItem.type ==
						DrawerForm.ListItem.Type.FAVORITE && afterListItem.chanName.equals(favoriteItem.chanName)
						? favoritesStorage.getFavorite(afterListItem.chanName, afterListItem.boardName,
						afterListItem.threadNumber) : null;
				favoritesStorage.moveAfter(favoriteItem, afterFavoriteItem);
			}
		}
		holder.setDragging(false, 0);
		callback.onDraggingStateChanged(false);
	}

	@Override
	public boolean onDragCanMove(ViewHolder fromHolder, ViewHolder toHolder) {
		DrawerForm.ListItem from = getItem(fromHolder.getAdapterPosition());
		DrawerForm.ListItem to = getItem(toHolder.getAdapterPosition());
		return from.type == to.type && (from.type == DrawerForm.ListItem.Type.CHAN ||
				from.type == DrawerForm.ListItem.Type.FAVORITE && CommonUtils.equals(from.chanName, to.chanName) &&
						(from.threadNumber == null) == (to.threadNumber == null));
	}

	@Override
	public boolean onDragMove(ViewHolder fromHolder, ViewHolder toHolder) {
		int fromIndex = fromHolder.getAdapterPosition();
		int toIndex = toHolder.getAdapterPosition();
		DrawerForm.ListItem from = getItem(fromIndex);
		DrawerForm.ListItem to = getItem(toIndex);
		int chansFrom = chans.indexOf(from);
		int chansTo = chans.indexOf(to);
		int favoritesFrom = favorites.indexOf(from);
		int favoritesTo = favorites.indexOf(to);
		ArrayList<DrawerForm.ListItem> workList = null;
		SortableHelper.DragState dragState = null;
		int workFrom = -1;
		int workTo = -1;
		if (chansFrom >= 0 && chansTo >= 0) {
			workList = chans;
			dragState = chanDragState;
			workFrom = chansFrom;
			workTo = chansTo;
		} else if (favoritesFrom >= 0 && favoritesTo >= 0) {
			workList = favorites;
			dragState = favoriteDragState;
			workFrom = favoritesFrom;
			workTo = favoritesTo;
		}
		if (workList != null && dragState != null) {
			workList.add(workTo, workList.remove(workFrom));
			notifyItemMoved(fromIndex, toIndex);
			dragState.set(workFrom, workTo);
			return true;
		}
		return false;
	}
}
