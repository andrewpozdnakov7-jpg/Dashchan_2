package com.mishiranu.dashchan.ui.gallery;

import com.mishiranu.dashchan.widget.MotionDialogBuilder;
import android.app.ActionBar;
import android.animation.ValueAnimator;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.content.res.Configuration;
import android.graphics.Color;
import android.graphics.PixelFormat;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.net.Uri;
import android.os.Bundle;
import android.os.SystemClock;
import android.util.Pair;
import android.view.ContextThemeWrapper;
import android.view.Gravity;
import android.view.Menu;
import android.view.MenuInflater;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewTreeObserver;
import android.view.Window;
import android.view.WindowInsets;
import android.view.WindowInsetsController;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.ScrollView;
import android.widget.TextView;
import androidx.annotation.NonNull;
import androidx.core.view.MenuProvider;
import androidx.fragment.app.DialogFragment;
import androidx.fragment.app.FragmentManager;
import androidx.lifecycle.ViewModelProvider;
import chan.content.Chan;
import chan.util.CommonUtils;
import chan.util.StringUtils;
import com.mishiranu.dashchan.C;
import com.mishiranu.dashchan.R;
import com.mishiranu.dashchan.content.MainApplication;
import com.mishiranu.dashchan.content.Preferences;
import com.mishiranu.dashchan.content.model.GalleryItem;
import com.mishiranu.dashchan.content.service.DownloadService;
import com.mishiranu.dashchan.graphics.GalleryBackgroundDrawable;
import com.mishiranu.dashchan.media.VideoDiagnostics;
import com.mishiranu.dashchan.ui.FragmentHandler;
import com.mishiranu.dashchan.ui.MainActivity;
import com.mishiranu.dashchan.util.AndroidUtils;
import com.mishiranu.dashchan.util.AnimationUtils;
import com.mishiranu.dashchan.util.ConcurrentUtils;
import com.mishiranu.dashchan.util.FlagUtils;
import com.mishiranu.dashchan.util.ResourceUtils;
import com.mishiranu.dashchan.util.InterfaceMotion;
import com.mishiranu.dashchan.util.ViewUtils;
import com.mishiranu.dashchan.widget.InsetsLayout;
import com.mishiranu.dashchan.widget.ThemeEngine;
import com.mishiranu.dashchan.widget.ViewFactory;
import com.mishiranu.dashchan.widget.ClickableToast;
import com.mishiranu.dashchan.ui.navigator.PageFragment;
import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

public class GalleryOverlay extends DialogFragment implements GalleryDialog.Callback, GalleryInstance.Callback,
		MenuProvider {
	private static final String EXTRA_PIP_WINDOW_RETIRED = "pipWindowRetired";
	public enum NavigatePostMode {DISABLED, MANUALLY, ENABLED}

	private static final String EXTRA_URI = "uri";
	private static final String EXTRA_FILE_NAME = "fileName";
	private static final String EXTRA_CHAN_NAME = "chanName";
	private static final String EXTRA_IMAGE_INDEX = "imageIndex";
	private static final String EXTRA_THREAD_TITLE = "threadTitle";
	private static final String EXTRA_NAVIGATE_POST_MODE = "navigatePostMode";
	private static final String EXTRA_INITIAL_GALLERY_MODE = "initialGalleryMode";
	private static final String EXTRA_PIP_RESTORE_TOKEN = "pipRestoreToken";

	private static final String EXTRA_POSITION = "position";
	private static final String EXTRA_RESTORE = "restoreSnapshot";
	private static final String EXTRA_FILTER = "restoreFilter";
	private static final String EXTRA_SORT = "restoreSort";
	private static final String EXTRA_ATTACHMENT = "restoreAttachment";
	private static final String EXTRA_GRID_POSITION = "restoreGridPosition";
	private static final String EXTRA_GRID_VIEWPORT = "restoreGridViewport";
	private static final String EXTRA_SELECTED = "selected";
	private static final String EXTRA_GALLERY_WINDOW = "galleryWindow";
	private static final String EXTRA_GALLERY_MODE = "galleryMode";
	private static final String EXTRA_SYSTEM_UI_VISIBILITY = "systemUiVisibility";
	private static final String EXTRA_VIDEO_FULLSCREEN = "videoFullscreen";
	private static final String EXTRA_VIDEO_FULLSCREEN_PREVIOUS_ORIENTATION = "videoFullscreenPreviousOrientation";
	private static final String EXTRA_VIDEO_FULLSCREEN_PREVIOUS_SYSTEM_UI_FLAGS =
			"videoFullscreenPreviousSystemUiFlags";
	private static final String EXTRA_VIDEO_FULLSCREEN_REQUESTED_ORIENTATION =
			"videoFullscreenRequestedOrientation";
	private static final String FILTER_ALL = "all";
	private static final String FILTER_PICTURES = "pictures";
	private static final String FILTER_GIF = "gif";
	private static final String FILTER_VIDEO = "video";

	private enum GallerySort {
		POST_ORDER, NEWEST_FIRST, LARGEST_FIRST, SMALLEST_FIRST, HIGHEST_RESOLUTION
	}

	private List<GalleryItem> queuedGalleryItems;
	private List<GalleryItem> allGalleryItems;
	private WeakReference<View> queuedFromView;
	private String queuedPictureInPictureRestoreToken;
	private VideoPipActivity.GalleryRestoreData queuedPictureInPictureRestoreData;

	private InsetsLayout rootView;
	private GalleryMotionController galleryMotion;
	private final GallerySwipeChrome swipeChrome = new GallerySwipeChrome();
	private boolean modernPhotoOpening;
	private boolean photoSwipeClosePending;
	private GalleryDialog photoSwipeWindow;
	private final Runnable photoSwipeOwner = this::completePhotoSwipeClose;
	private GalleryInstance instance;
	private PagerUnit pagerUnit;
	private GalleryStateViewModel galleryState;
	private GalleryViewportViewModel viewportState;
	private android.os.CancellationSignal restoreSignal;
	private Bundle restorationState;
	private boolean restoreUnavailable;
	private ListUnit listUnit;
	private Bundle pendingDialogState;
	private GalleryDialog initializedDialog;
	private ViewTreeObserver restoreDrawObserver;
	private ViewTreeObserver.OnPreDrawListener restoreDrawListener;
	private View windowFocusView;
	private View.OnFocusChangeListener windowFocusListener;
	private Runnable pendingSystemUiFlags;

	private boolean galleryWindow;
	private boolean galleryMode;
	private boolean hiddenForPictureInPicture;
	private boolean retirePictureInPictureGallery;
	private boolean predictiveBackRunning;
	private CornerAnimator cornerAnimator;
	private String galleryFilter = FILTER_ALL;
	private GallerySort gallerySort = GallerySort.POST_ORDER;
	private Runnable cancelGalleryRefresh;
	private boolean galleryRefreshing;
	private final Runnable galleryRefreshTimeout = () -> {
		if (galleryRefreshing) {
			endGalleryRefresh();
			ClickableToast.show(R.string.gallery_refresh_failed);
		}
	};

	private PageFragment getGalleryRefreshSource() {
		if (instance == null || allGalleryItems == null || allGalleryItems.isEmpty()
				|| !(getActivity() instanceof MainActivity) || getNavigatePostMode() == NavigatePostMode.DISABLED) return null;
		GalleryItem first = allGalleryItems.get(0);
		if (first.threadNumber == null) return null;
		for (GalleryItem item : allGalleryItems) {
			if (!java.util.Objects.equals(first.boardName, item.boardName)
					|| !java.util.Objects.equals(first.threadNumber, item.threadNumber)) return null;
		}
		return ((MainActivity) getActivity()).getGalleryRefreshSource(instance.chanName,
				first.boardName, first.threadNumber);
	}

	@Override
	public boolean canRefreshGallery() { return getGalleryRefreshSource() != null; }

	private void endGalleryRefresh() {
		galleryRefreshing = false;
		ConcurrentUtils.HANDLER.removeCallbacks(galleryRefreshTimeout);
		if (cancelGalleryRefresh != null) cancelGalleryRefresh.run();
		cancelGalleryRefresh = null;
		if (listUnit != null) listUnit.setRefreshBusy(false);
		invalidateOptionsMenu();
	}

	@Override
	public void refreshGallery() {
		if (galleryRefreshing) return;
		PageFragment source = getGalleryRefreshSource();
		if (source == null) {
			endGalleryRefresh();
			ClickableToast.show(R.string.gallery_refresh_unavailable);
			return;
		}
		galleryRefreshing = true;
		listUnit.setRefreshBusy(true);
		instance.logNavigation("refresh_start count=" + allGalleryItems.size());
		ConcurrentUtils.HANDLER.postDelayed(galleryRefreshTimeout, 120000);
		cancelGalleryRefresh = source.refreshGallery((items, newPosts, error) -> {
			if (!galleryRefreshing || instance == null) return;
			endGalleryRefresh();
			if (error != null) {
				instance.logNavigation("refresh_failed");
				ClickableToast.show(error);
				return;
			}
			Chan chan = Chan.get(instance.chanName);
			Map<String, GalleryItem> merged = new LinkedHashMap<>();
			for (GalleryItem item : allGalleryItems) merged.put(galleryItemKey(item, chan), item);
			int added = 0;
			for (GalleryItem item : items) {
				String key = galleryItemKey(item, chan);
				if (!merged.containsKey(key)) {
					merged.put(key, item);
					added++;
				}
			}
			// Keep existing objects, including an open attachment deleted remotely. Refresh
			// must not destroy the player's surface or turn a deletion into an index jump.
			if (added > 0) {
				allGalleryItems = new ArrayList<>(merged.values());
				allGalleryItems.sort((a, b) -> a.postNumber != null && b.postNumber != null
						? a.postNumber.compareTo(b.postNumber) : 0);
				galleryState.restoreToken = GalleryRestoreStore.save(instance.chanName, allGalleryItems);
				applyGalleryFilter(galleryFilter, gallerySort, true);
			}
			instance.logNavigation("refresh_complete media=" + added + " posts=" + newPosts);
			galleryState.refreshKnownPosts = source.getGalleryPostNumbers();
			ClickableToast.show(getString(R.string.gallery_refresh_result, added, newPosts));
		}, galleryState.refreshKnownPosts);
		if (cancelGalleryRefresh == null && galleryRefreshing) {
			endGalleryRefresh();
			ClickableToast.show(R.string.gallery_refresh_unavailable);
		}
		invalidateOptionsMenu();
	}

	private static String galleryItemKey(GalleryItem item, Chan chan) {
		return item.postNumber + "|" + item.getFileUri(chan);
	}

	private String viewportScope() {
		// Do not mix standalone files or galleries spanning different threads.
		if (allGalleryItems == null || allGalleryItems.isEmpty()
				|| AndroidUtils.getParcelable(requireArguments(), EXTRA_URI, Uri.class) != null) return null;
		GalleryItem first = allGalleryItems.get(0);
		if (first.threadNumber == null) return null;
		for (GalleryItem item : allGalleryItems) {
			if (!java.util.Objects.equals(first.boardName, item.boardName)
					|| !java.util.Objects.equals(first.threadNumber, item.threadNumber)) return null;
		}
		StringBuilder scope = new StringBuilder();
		for (String part : new String[] {getChanName(), first.boardName, first.threadNumber,
				getNavigatePostMode().name(), galleryFilter, gallerySort.name()}) {
			if (part == null) scope.append("-1:");
			else scope.append(part.length()).append(':').append(part);
		}
		return scope.toString();
	}

	private void rememberViewport() {
		if (listUnit == null) return;
		GalleryViewportMemory.Snapshot snapshot = listUnit.getViewport();
		galleryState.viewport = snapshot;
		if (snapshot != null) {
			viewportState.memory.put(viewportScope(), snapshot);
		}
	}
	private final boolean scrollThread = Preferences.isScrollThreadGallery();

	private Pair<CharSequence, CharSequence> titleSubtitle;
	private boolean screenOnFixed = false;
	private int systemUiVisibilityFlags = GalleryInstance.Flags.LOCKED_USER;
	private boolean videoFullscreen;
	private int videoFullscreenPreviousOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED;
	private int videoFullscreenPreviousSystemUiFlags = GalleryInstance.Flags.LOCKED_USER;
	private boolean videoFullscreenRequestedOrientation;

	private static final int ACTION_BAR_COLOR = 0xaa202020;
	private static final int BACKGROUND_COLOR = 0xf0101010;

	public GalleryOverlay() {}

	public GalleryOverlay(Uri uri) {
		this(uri, null);
	}

	public GalleryOverlay(Uri uri, String fileName) {
		this(uri, fileName, null, null, 0, null, null, NavigatePostMode.DISABLED, false, null);
	}

	public GalleryOverlay(String chanName, List<GalleryItem> galleryItems, int imageIndex, String threadTitle,
			View fromView, NavigatePostMode navigatePostMode, boolean initialGalleryMode) {
		this(null, null, chanName, galleryItems, imageIndex, threadTitle, fromView,
				navigatePostMode, initialGalleryMode, null);
	}

	static GalleryOverlay createForPictureInPictureRestore(VideoPipActivity.GalleryRestoreData data,
			String restoreToken) {
		GalleryOverlay overlay = new GalleryOverlay(null, null, data.chanName, data.galleryItems, data.imageIndex,
				data.threadTitle, null, data.navigatePostMode, false, restoreToken);
		overlay.queuedPictureInPictureRestoreData = data;
		return overlay;
	}

	public String getChanName() {
		return requireArguments().getString(EXTRA_CHAN_NAME);
	}

	public boolean isPictureInPictureRestore(String token) {
		return token != null && token.equals(requireArguments().getString(EXTRA_PIP_RESTORE_TOKEN));
	}

	private GalleryOverlay(Uri uri, String fileName, String chanName, List<GalleryItem> galleryItems, int imageIndex,
			String threadTitle,
			View fromView, NavigatePostMode navigatePostMode, boolean initialGalleryMode,
			String pictureInPictureRestoreToken) {
		Bundle args = new Bundle();
		args.putParcelable(EXTRA_URI, uri);
		args.putString(EXTRA_FILE_NAME, fileName);
		args.putString(EXTRA_CHAN_NAME, chanName);
		args.putInt(EXTRA_IMAGE_INDEX, imageIndex);
		args.putString(EXTRA_THREAD_TITLE, threadTitle);
		args.putString(EXTRA_NAVIGATE_POST_MODE, navigatePostMode.name());
		args.putBoolean(EXTRA_INITIAL_GALLERY_MODE, initialGalleryMode);
		args.putString(EXTRA_PIP_RESTORE_TOKEN, pictureInPictureRestoreToken);
		setArguments(args);
		this.queuedGalleryItems = galleryItems;
		this.queuedFromView = fromView != null ? new WeakReference<>(fromView) : null;
		this.queuedPictureInPictureRestoreToken = pictureInPictureRestoreToken;
	}

	private NavigatePostMode getNavigatePostMode() {
		String name = requireArguments().getString(EXTRA_NAVIGATE_POST_MODE);
		return name != null ? NavigatePostMode.valueOf(name) : NavigatePostMode.DISABLED;
	}

	private String getThreadTitle() {
		return requireArguments().getString(EXTRA_THREAD_TITLE);
	}

	@Override
	public void onCreate(Bundle savedInstanceState) {
		super.onCreate(savedInstanceState);
		galleryState = new ViewModelProvider(this).get(GalleryStateViewModel.class);
		viewportState = new ViewModelProvider(requireActivity()).get(GalleryViewportViewModel.class);
		if (savedInstanceState != null) {
			restorationState = new Bundle(savedInstanceState);
			if (galleryState.viewport == null) {
				Bundle viewport = savedInstanceState.getBundle(EXTRA_GRID_VIEWPORT);
				if (viewport != null) {
					GalleryViewportMemory.Snapshot snapshot = new GalleryViewportMemory.Snapshot(
							viewport.getString("item"), viewport.getInt("offset"), viewport.getInt("width"),
							viewport.getInt("columns"));
					if (snapshot.isValid()) galleryState.viewport = snapshot;
				}
			}
			if (galleryState.restoreToken == null) galleryState.restoreToken = savedInstanceState.getString(EXTRA_RESTORE);
			if (galleryState.filter == null) galleryState.filter = savedInstanceState.getString(EXTRA_FILTER);
			if (galleryState.sort == null) galleryState.sort = savedInstanceState.getString(EXTRA_SORT);
		}
		if (queuedPictureInPictureRestoreToken == null) {
			queuedPictureInPictureRestoreToken = requireArguments().getString(EXTRA_PIP_RESTORE_TOKEN);
		}
		if (queuedPictureInPictureRestoreData != null) {
			VideoPipActivity.GalleryRestoreData data = queuedPictureInPictureRestoreData;
			queuedPictureInPictureRestoreData = null;
			galleryState.allItems = new ArrayList<>(data.allGalleryItems);
			galleryState.visibleItems = new ArrayList<>(data.galleryItems);
			galleryState.dialogState = new Bundle(data.dialogState);
			galleryState.gridState = data.gridState;
			galleryState.filter = data.filter;
			galleryState.sort = data.sort;
		}
		VideoUnit.LifecycleState video = galleryState.video;
		VideoUnit.PictureInPictureSource pipSource = video != null ? video.pipSource : null;
		retirePictureInPictureGallery = (savedInstanceState != null
				&& savedInstanceState.getBoolean(EXTRA_PIP_WINDOW_RETIRED))
				|| (pipSource != null && (!pipSource.active
						|| pipSource.snapshotAvailable && pipSource.enteredPictureInPicture));
		if (retirePictureInPictureGallery) {
			// Decide before DialogFragment prepares/restores the dialog. Showing a new
			// window and immediately hiding it can leave rotation waiting for a surface
			// that will never draw. PiP owns the player/download and the return snapshot.
			setShowsDialog(false);
			hiddenForPictureInPicture = true;
			if (pipSource != null) pipSource.closeGallery();
			VideoDiagnostics.recordUi("gallery pip_window_recreation skipped=true snapshot="
					+ (pipSource != null && pipSource.snapshotAvailable));
		}
		if (galleryState.allItems == null && queuedGalleryItems != null) {
			galleryState.allItems = new ArrayList<>(queuedGalleryItems);
		}
		if (galleryState.allItems != null && galleryState.restoreToken == null && !retirePictureInPictureGallery) {
			galleryState.restoreToken = GalleryRestoreStore.save(getChanName(), galleryState.allItems);
		}
		if (queuedPictureInPictureRestoreToken != null) {
			galleryState.pendingPictureInPictureToken = queuedPictureInPictureRestoreToken;
			VideoDiagnostics.recordUi("gallery pip_restore tiktok=" + Preferences.isVideoTikTokMode());
		}
		if (galleryState.dialogState != null) {
			savedInstanceState = galleryState.dialogState;
		}
		if (galleryState.filter != null) galleryFilter = galleryState.filter;
		if (galleryState.sort != null) {
			try { gallerySort = GallerySort.valueOf(galleryState.sort); }
			catch (IllegalArgumentException ignored) { gallerySort = GallerySort.POST_ORDER; }
		}
		if (savedInstanceState != null) {
			videoFullscreen = savedInstanceState.getBoolean(EXTRA_VIDEO_FULLSCREEN);
			videoFullscreenPreviousOrientation = savedInstanceState.getInt(
					EXTRA_VIDEO_FULLSCREEN_PREVIOUS_ORIENTATION, ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED);
			videoFullscreenPreviousSystemUiFlags = savedInstanceState.getInt(
					EXTRA_VIDEO_FULLSCREEN_PREVIOUS_SYSTEM_UI_FLAGS, GalleryInstance.Flags.LOCKED_USER);
			videoFullscreenRequestedOrientation = savedInstanceState.getBoolean(
					EXTRA_VIDEO_FULLSCREEN_REQUESTED_ORIENTATION);
		}
	}

	@NonNull
	@Override
	public GalleryDialog onCreateDialog(Bundle savedInstanceState) {
		if (galleryState.dialogState != null) savedInstanceState = galleryState.dialogState;
		pendingDialogState = savedInstanceState != null ? new Bundle(savedInstanceState) : null;
		return new GalleryDialog(this);
	}

	@Override
	public GalleryDialog getDialog() {
		return (GalleryDialog) super.getDialog();
	}

	@Override
	public void onDestroyView() {
		finishGalleryMotion();
		swipeChrome.reset();
		if (restoreSignal != null) restoreSignal.cancel();
		restoreSignal = null;
		endGalleryRefresh();
		disableAnimationForWindowRecreation();
		if (getActivity() instanceof MainActivity) {
			((MainActivity) getActivity()).onPictureInPictureGalleryClosed(
					requireArguments().getString(EXTRA_PIP_RESTORE_TOKEN));
		}
		clearDialogCallbacks();
		if (cornerAnimator != null) {
			cornerAnimator.cancel();
			cornerAnimator = null;
		}
		if (rootView != null) rootView.removeCallbacks(returnToGalleryRunnable);
		if (instance != null) {
			instance.logNavigation("destroy_view gridMode=" + galleryMode + " index="
					+ (pagerUnit != null ? pagerUnit.getCurrentIndex() : -1));
			galleryState.dialogState = new Bundle();
			saveGalleryState(galleryState.dialogState);
			galleryState.allItems = new ArrayList<>(allGalleryItems);
			galleryState.visibleItems = new ArrayList<>(instance.galleryItems);
			galleryState.filter = galleryFilter;
			galleryState.sort = gallerySort.name();
			if (listUnit != null) {
				galleryState.gridState = listUnit.getRecyclerView().getLayoutManager().onSaveInstanceState();
			}
			if (pagerUnit != null) {
				pagerUnit.saveLifecycleState(galleryState);
				pagerUnit.onFinish();
			}
		}
		initializedDialog = null;
		pendingDialogState = null;
		resetPredictiveBackView(false);
		super.onDestroyView();
		destroyShowcase(false);
		rootView = null;
		galleryMotion = null;
		instance = null;
		pagerUnit = null;
		listUnit = null;
	}

	@Override
	public void onStart() {
		if (retirePictureInPictureGallery) {
			super.onStart(); // No dialog exists, so no window is registered with WindowManager.
			VideoDiagnostics.recordUi("gallery pip_window_retired dialog_created=" + (getDialog() != null));
			dismissAllowingStateLoss();
			return;
		}
		VideoUnit.LifecycleState video = galleryState.video;
		if (video != null && video.pipSource != null && !video.pipSource.active) {
			// PiP was closed while the host was being recreated. Do not resurrect its gallery.
			super.onStart();
			dismissAllowingStateLoss();
			return;
		}
		hiddenForPictureInPicture = video != null && video.pipSource != null
				|| hiddenForPictureInPicture;
		GalleryDialog dialog = getDialog();
		boolean initialize = dialog != null && dialog != initializedDialog;
		int[] imageViewPosition = initialize ? prepareGalleryWindow(dialog) : null;
		// DialogFragment owns showing the window and installing its lifecycle owners.
		super.onStart();
		if (initialize) {
			initializedDialog = dialog;
			if (instance == null && galleryState.allItems == null && queuedGalleryItems == null
					&& AndroidUtils.getParcelable(requireArguments(), EXTRA_URI, Uri.class) == null) {
				restoreGalleryWindow(dialog, pendingDialogState);
			} else {
				initializeGalleryWindow(dialog, pendingDialogState, imageViewPosition);
				observePictureInPictureRestoreFrame(dialog);
			}
			pendingDialogState = null;
			// Android may have restored the menu before the gallery units existed.
			// Rebuild it now that all callbacks can use the new window's state.
			dialog.invalidateOptionsMenu();
		}
		if (dialog != null && hiddenForPictureInPicture) {
			dialog.hide();
		}
	}

	private void restoreGalleryWindow(GalleryDialog dialog, Bundle state) {
		restorationState = state != null ? new Bundle(state) : new Bundle();
		Bundle saved = new Bundle(restorationState);
		dialog.getActionBar().setDisplayHomeAsUpEnabled(true);
		android.widget.ProgressBar progress = new android.widget.ProgressBar(rootView.getContext());
		rootView.addView(progress, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,
				ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER));
		android.os.CancellationSignal signal = new android.os.CancellationSignal();
		restoreSignal = signal;
		VideoDiagnostics.recordUi("gallery process_restore_start");
		GalleryRestoreStore.load(galleryState.restoreToken, getChanName(), signal, items -> {
			if (signal.isCanceled() || !isAdded() || getDialog() != dialog || initializedDialog != dialog
					|| rootView == null || retirePictureInPictureGallery) return;
			restoreSignal = null;
			rootView.removeView(progress);
			galleryState.allItems = items;
			List<GalleryItem> visible = filteredGalleryItems(items, Chan.get(getChanName()), galleryFilter, gallerySort);
			galleryState.visibleItems = visible;
			Bundle attachment = saved.getBundle(EXTRA_ATTACHMENT);
			if (attachment != null && !visible.isEmpty()) {
				GalleryRestoreCodec.Item selected = new GalleryRestoreCodec.Item(attachment.getString("uri"),
						null, null, attachment.getString("board"), attachment.getString("thread"),
						attachment.getString("post"), null, 0, 0, 0);
				List<GalleryRestoreCodec.Item> descriptors = new ArrayList<>(visible.size());
				Chan chan = Chan.get(getChanName());
				for (GalleryItem item : visible) descriptors.add(GalleryRestoreStore.describe(item, chan));
				saved.putInt(EXTRA_POSITION, GalleryRestoreCodec.find(descriptors, selected, saved.getInt(EXTRA_POSITION)));
			}
			// Old multi-selection indices are unsafe after recovery/filter fallback.
			saved.remove(EXTRA_SELECTED);
			restoreUnavailable = items.isEmpty();
			if (!getLifecycle().getCurrentState().isAtLeast(androidx.lifecycle.Lifecycle.State.STARTED)) {
				pendingDialogState = saved;
				initializedDialog = null;
				return;
			}
			initializeGalleryWindow(dialog, saved, null);
			if (pagerUnit != null && isResumed()) pagerUnit.onResume();
			dialog.invalidateOptionsMenu();
			VideoDiagnostics.recordUi("gallery process_restore_complete count=" + items.size()
					+ " unavailable=" + restoreUnavailable);
		});
	}

	private int[] prepareGalleryWindow(GalleryDialog dialog) {
		View queuedFromView = this.queuedFromView != null ? this.queuedFromView.get() : null;
		this.queuedFromView = null;
		int[] imageViewPosition = null;
		if (queuedFromView != null) {
			int[] location = new int[2];
			queuedFromView.getLocationOnScreen(location);
			imageViewPosition = new int[] {location[0], location[1],
					queuedFromView.getWidth(), queuedFromView.getHeight()};
		}
		modernPhotoOpening = false;
		if (InterfaceMotion.isEnabled() && queuedGalleryItems != null &&
				!requireArguments().getBoolean(EXTRA_INITIAL_GALLERY_MODE)) {
			int index = requireArguments().getInt(EXTRA_IMAGE_INDEX);
			if (index >= 0 && index < queuedGalleryItems.size()) {
				GalleryItem item = queuedGalleryItems.get(index);
				Chan chan = Chan.get(getChanName());
				modernPhotoOpening = item.isImage(chan) || item.isVideo(chan);
			}
		}
		boolean restoring = pendingDialogState != null || galleryState.video != null
				|| requireArguments().getString(EXTRA_PIP_RESTORE_TOKEN) != null;
		if (restoring) { imageViewPosition = null; modernPhotoOpening = false; }
		WindowManager.LayoutParams attributes = dialog.getWindow().getAttributes();
		attributes.windowAnimations = restoring ? R.style.Animation_Gallery_Restore : imageViewPosition == null
				? R.style.Animation_Gallery_Full : R.style.Animation_Gallery_Partial;
		VideoDiagnostics.recordUi("gallery window_prepare restoring=" + restoring
				+ " enter_animation=" + !restoring);
		if (modernPhotoOpening) attributes.windowAnimations = 0;
		attributes.layoutInDisplayCutoutMode = WindowManager.LayoutParams
				.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES;

		if (rootView == null) {
			Context context = ThemeEngine.attach(new ContextThemeWrapper
					(MainApplication.getInstance().getLocalizedContext(), R.style.Theme_Gallery));
			rootView = new InsetsLayout(context);
			rootView.addOnAttachStateChangeListener(new View.OnAttachStateChangeListener() {
				@Override
				public void onViewAttachedToWindow(View v) {
					if (!galleryMode) {
						displayShowcase();
					}
				}

				@Override
				public void onViewDetachedFromWindow(View v) {}
			});
			rootView.setOnApplyInsetsListener(apply -> {
				InsetsLayout.Insets insets = apply.get();
				if (listUnit != null) {
					boolean invalidate = listUnit.onApplyWindowInsets(insets);
					if (invalidate) {
						postInvalidateSystemUIVisibility();
					}
				}
				if (pagerUnit != null) {
					pagerUnit.onApplyWindowInsets(insets);
				}
			});
			rootView.setLayoutParams(new ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
					ViewGroup.LayoutParams.MATCH_PARENT));
			rootView.setBackground(new GalleryBackgroundDrawable(rootView,
					modernPhotoOpening ? null : imageViewPosition, BACKGROUND_COLOR));
			galleryMotion = new GalleryMotionController(rootView);
		}
		if (galleryMotion != null) galleryMotion.setSource(restoring ? null : queuedFromView);
		ViewUtils.removeFromParent(rootView);
		dialog.setContentView(rootView);
		return imageViewPosition;
	}

	private void initializeGalleryWindow(GalleryDialog dialog, Bundle savedInstanceState, int[] imageViewPosition) {
		if (savedInstanceState != null) {
			galleryMode = savedInstanceState.getBoolean(EXTRA_GALLERY_MODE);
			galleryWindow = savedInstanceState.getBoolean(EXTRA_GALLERY_WINDOW);
		}
		dialog.getActionBar().setDisplayHomeAsUpEnabled(true);
		clearDialogCallbacks();
		Runnable invalidateSystemUiFlags = () -> {
			if (getDialog() == dialog && dialog.isShowing()) {
				invalidateSystemUiFlags();
			}
		};
		pendingSystemUiFlags = invalidateSystemUiFlags;
		windowFocusView = dialog.getWindow().getDecorView();
		windowFocusListener = (v, hasFocus) -> {
			if (!hasFocus) finishGalleryMotion();
			if (pagerUnit != null) {
				// Block touch events when dialogs are opened
				pagerUnit.setHasFocus(hasFocus);
			}
			ConcurrentUtils.HANDLER.removeCallbacks(invalidateSystemUiFlags);
			if (hasFocus) {
				// Re-apply visibility flags after dialogs closed
				ConcurrentUtils.HANDLER.postDelayed(invalidateSystemUiFlags, 100);
			}
		};
		ViewUtils.addWindowFocusListener(windowFocusView, windowFocusListener);

		Integer newImagePosition = null;
		if (instance == null) {
			Uri uri = AndroidUtils.getParcelable(requireArguments(), EXTRA_URI, Uri.class);
			String chanNameFromArguments = requireArguments().getString(EXTRA_CHAN_NAME);
			Chan chan = chanNameFromArguments == null && uri != null
					? Chan.getPreferred(null, uri) : Chan.get(chanNameFromArguments);
			boolean defaultLocator = chan.name == null;

			List<GalleryItem> galleryItems;
			int imagePosition;
			if (uri != null) {
				String boardName = null;
				String threadNumber = null;
				if (!defaultLocator) {
					boardName = chan.locator.safe(true).getBoardName(uri);
					threadNumber = chan.locator.safe(true).getThreadNumber(uri);
				}
				String fileName = requireArguments().getString(EXTRA_FILE_NAME);
				galleryItems = Collections.singletonList(new GalleryItem(uri, fileName, boardName, threadNumber));
				imagePosition = 0;
			} else {
				galleryItems = galleryState.allItems != null ? galleryState.allItems : queuedGalleryItems;
				queuedGalleryItems = null;
				imagePosition = savedInstanceState != null ? savedInstanceState.getInt(EXTRA_POSITION)
						: requireArguments().getInt(EXTRA_IMAGE_INDEX);
			}
			allGalleryItems = new ArrayList<>(galleryItems != null ? galleryItems : Collections.emptyList());
			if (queuedPictureInPictureRestoreToken != null) {
				galleryState.pendingPictureInPictureToken = queuedPictureInPictureRestoreToken;
			}
			instance = new GalleryInstance(rootView.getContext(), this, ACTION_BAR_COLOR, chan.name,
					new ArrayList<>(galleryState.visibleItems != null ? galleryState.visibleItems : allGalleryItems),
					galleryState.pendingPictureInPictureToken);
			instance.logNavigation("open argumentIndex=" + requireArguments().getInt(EXTRA_IMAGE_INDEX)
					+ " effectiveIndex=" + imagePosition + " savedState=" + (savedInstanceState != null)
					+ " gridState=" + (galleryState.gridState != null) + " count=" + instance.galleryItems.size()
					+ " initialGrid=" + requireArguments().getBoolean(EXTRA_INITIAL_GALLERY_MODE));
			queuedPictureInPictureRestoreToken = null;
			if (!instance.galleryItems.isEmpty()) {
				PageFragment refreshSource = getGalleryRefreshSource();
				if (galleryState.refreshKnownPosts == null && refreshSource != null) {
					galleryState.refreshKnownPosts = refreshSource.getGalleryPostNumbers();
				}
				listUnit = new ListUnit(instance);
				GalleryViewportMemory.Snapshot viewport = galleryState.viewport != null ? galleryState.viewport
						: viewportState.memory.get(viewportScope());
				if (listUnit.setViewport(viewport)) instance.logNavigation("grid_session_restore available=true");
				// Gallery has a separate dark overlay theme; refresh belongs to the underlying thread.
				listUnit.setRefreshColor(ThemeEngine.getTheme(refreshSource != null && refreshSource.getView() != null
						? refreshSource.getView().getContext() : requireActivity()).accent);
				pagerUnit = new PagerUnit(instance);
				pagerUnit.restoreLifecycleState(galleryState, savedInstanceState);
				rootView.addView(listUnit.getRecyclerView(), InsetsLayout.LayoutParams.MATCH_PARENT,
						InsetsLayout.LayoutParams.MATCH_PARENT);
				rootView.addView(pagerUnit.getView(), InsetsLayout.LayoutParams.MATCH_PARENT,
						InsetsLayout.LayoutParams.MATCH_PARENT);
				pagerUnit.addAndInitViews(rootView, imagePosition);
			}
			newImagePosition = imagePosition;
		}

		if (instance.galleryItems.isEmpty()) {
			ViewFactory.ErrorHolder errorHolder = ViewFactory.createErrorLayout(rootView);
			errorHolder.text.setText(restoreUnavailable ? R.string.gallery_restore_unavailable : R.string.gallery_is_empty);
			rootView.addView(errorHolder.layout);
		} else {
			if (savedInstanceState != null && savedInstanceState.containsKey(EXTRA_GALLERY_MODE)) {
				galleryMode = savedInstanceState.getBoolean(EXTRA_GALLERY_MODE);
				galleryWindow = savedInstanceState.getBoolean(EXTRA_GALLERY_WINDOW);
				switchMode(galleryMode, false);
				modifySystemUiVisibility(GalleryInstance.Flags.LOCKED_USER,
						savedInstanceState.getBoolean(EXTRA_SYSTEM_UI_VISIBILITY));
			} else if (newImagePosition != null) {
				int imagePosition = newImagePosition;
				galleryWindow = imagePosition < 0 || requireArguments().getBoolean(EXTRA_INITIAL_GALLERY_MODE);
				if (galleryWindow) {
					listUnit.initializeGridPosition(imagePosition);
				}
				switchMode(galleryWindow, false);
			}
			if (newImagePosition != null) {
				pagerUnit.onViewsCreated(imageViewPosition);
			}
			if (!galleryMode) {
				displayShowcase();
			}
		}
		int[] selected = savedInstanceState != null ? savedInstanceState.getIntArray(EXTRA_SELECTED) : null;
		if (selected != null && galleryMode && listUnit != null && listUnit.areItemsSelectable()) {
			listUnit.startSelectionMode(selected);
		}
		if (listUnit != null && galleryState.gridState != null) {
			listUnit.logPosition("restore_grid_state_before");
			listUnit.getRecyclerView().getLayoutManager().onRestoreInstanceState(galleryState.gridState);
			listUnit.logPosition("restore_grid_state_submitted");
			galleryState.gridState = null;
		} else if (listUnit != null && galleryMode && savedInstanceState != null && listUnit.getViewport() == null) {
			int position = savedInstanceState.getInt(EXTRA_GRID_POSITION, savedInstanceState.getInt(EXTRA_POSITION));
			listUnit.scrollListToPosition(Math.max(0, Math.min(position, instance.galleryItems.size() - 1)), false);
		}

		if (newImagePosition == null) {
			Configuration configuration = getResources().getConfiguration();
			if (listUnit != null) {
				listUnit.onConfigurationChanged(configuration);
			}
			if (pagerUnit != null) {
				pagerUnit.onConfigurationChanged(configuration);
			}
		}
		if (titleSubtitle != null) {
			dialog.setTitleSubtitle(titleSubtitle.first, titleSubtitle.second);
		}
		Window window = getWindow();
		if (window != null) {
			int color = ACTION_BAR_COLOR;
			ViewUtils.setStatusBarColor(window, color);
			ViewUtils.setNavigationBarColor(window, color);
			ViewUtils.setWindowLayoutFullscreen(window);
		}
		setScreenOnFixed(screenOnFixed);
		invalidateSystemUiVisibility();
		if (galleryMotion != null && pagerUnit != null) {
			galleryMotion.setMediaChrome(pagerUnit.getMotionVideoControls());
			galleryMotion.enter(pagerUnit.getMotionMediaPhotoView(), pagerUnit.getCurrentGalleryItem(),
					dialog.getActionBarView(), !modernPhotoOpening || savedInstanceState != null,
					pagerUnit.getMotionVideoSurface());
		}
		// The window has already been shown. Retain its native exit for grid/forced dismiss.
		if (modernPhotoOpening && getWindow() != null) getWindow().setWindowAnimations(R.style.Animation_Gallery_Partial);
	}

	private void clearDialogCallbacks() {
		clearRestoreDrawListener();
		if (windowFocusView != null && windowFocusListener != null) {
			ViewUtils.removeWindowFocusListener(windowFocusView, windowFocusListener);
		}
		windowFocusView = null;
		windowFocusListener = null;
		if (pendingSystemUiFlags != null) {
			ConcurrentUtils.HANDLER.removeCallbacks(pendingSystemUiFlags);
			pendingSystemUiFlags = null;
		}
	}

	private void clearRestoreDrawListener() {
		if (restoreDrawObserver != null && restoreDrawObserver.isAlive() && restoreDrawListener != null) {
			restoreDrawObserver.removeOnPreDrawListener(restoreDrawListener);
		}
		restoreDrawObserver = null;
		restoreDrawListener = null;
	}

	private void observePictureInPictureRestoreFrame(GalleryDialog dialog) {
		String token = requireArguments().getString(EXTRA_PIP_RESTORE_TOKEN);
		if (token == null || rootView == null) return;
		View root = rootView;
		restoreDrawObserver = root.getViewTreeObserver();
		restoreDrawListener = () -> {
			if (!root.isShown() || root.getWidth() == 0 || root.getHeight() == 0) return true;
			clearRestoreDrawListener();
			boolean hardware = root.isHardwareAccelerated();
			Runnable drawn = () -> ConcurrentUtils.HANDLER.post(() -> {
				if (rootView != root || initializedDialog != dialog || !dialog.isShowing()) return;
				VideoDiagnostics.recordUi("pip_return gallery_window_frame hardware=" + hardware);
				Activity activity = getActivity();
				if (activity instanceof MainActivity) {
					((MainActivity) activity).onPictureInPictureGalleryDrawn(token);
				}
			});
			if (hardware) {
				root.getViewTreeObserver().registerFrameCommitCallback(drawn);
			} else {
				// Frame commit callbacks are not delivered by software rendering.
				root.post(drawn);
			}
			return true;
		};
		restoreDrawObserver.addOnPreDrawListener(restoreDrawListener);
	}

	@Override
	public void onResume() {
		super.onResume();

		if (hiddenForPictureInPicture) {
			GalleryDialog dialog = getDialog();
			if (dialog != null) {
				dialog.hide();
			}
		}
		if (pagerUnit != null) {
			pagerUnit.onResume();
		}
	}

	@Override
	public void onPause() {
		finishGalleryMotion();
		swipeChrome.reset();
		disableAnimationForWindowRecreation();
		super.onPause();

		if (pagerUnit != null) {
			pagerUnit.onPause(getActivity() != null && requireActivity().isChangingConfigurations());
		}
	}

	private void disableAnimationForWindowRecreation() {
		if (getActivity() != null && requireActivity().isChangingConfigurations() && getDialog() != null) {
			// Recreating an existing gallery is not a user close/open animation.
			getDialog().getWindow().setWindowAnimations(0);
		}
	}

	@Override
	public void onDestroy() {
		if (isRemoving() && (getActivity() == null || !requireActivity().isChangingConfigurations())) {
			VideoPipActivity.cancelPendingGalleryReturn(
					requireArguments().getString(EXTRA_PIP_RESTORE_TOKEN), "gallery_removed");
		}
		if (videoFullscreen && getActivity() != null && !requireActivity().isChangingConfigurations()) {
			if (videoFullscreenRequestedOrientation) {
				requireActivity().setRequestedOrientation(videoFullscreenPreviousOrientation);
			}
			videoFullscreen = false;
		}
		super.onDestroy();

		if (cornerAnimator != null) {
			cornerAnimator.cancel();
		}
		if (pagerUnit != null) {
			pagerUnit.onFinish();
		}
	}

	private void invalidateListPosition() {
		instance.logNavigation("sync_grid_to_pager index=" + pagerUnit.getCurrentIndex());
		listUnit.requestReturnToGrid(pagerUnit.getCurrentIndex());
	}

	private final Runnable returnToGalleryRunnable = () -> {
		switchMode(true, true);
		invalidateListPosition();
	};

	private boolean returnToGallery() {
		if (pagerUnit == null) return false;
		if (instance != null) instance.logNavigation("return_to_grid window=" + galleryWindow
				+ " gridMode=" + galleryMode + " index=" + (pagerUnit != null ? pagerUnit.getCurrentIndex() : -1));
		if (galleryWindow && !galleryMode) {
			pagerUnit.onBackToGallery();
			rootView.post(returnToGalleryRunnable);
			return true;
		}
		return false;
	}

	@Override
	public boolean onBackPressed() {
		if (destroyShowcase(true)) {
			return true;
		}
		if (videoFullscreen) {
			return requestPhotoClose();
		}
		if (photoSwipeClosePending) return true;
		return returnToGallery() || requestPhotoClose();
	}

	boolean isGalleryMotionBlocking() {
		return rootView != null && GalleryMotionController.blocks(rootView) ||
				pagerUnit != null && pagerUnit.isModeMotionRunning() ||
				listUnit != null && GalleryMotionController.blocks(listUnit.getRecyclerView());
	}

	private void finishGalleryMotion() {
		if (galleryMotion != null) galleryMotion.finish();
		if (rootView != null) GalleryMotionController.finishHost(rootView);
		if (listUnit != null) GalleryMotionController.finishHost(listUnit.getRecyclerView());
		if (pagerUnit != null) pagerUnit.finishPhotoMotion();
	}

	private boolean requestPhotoClose() {
		if (photoSwipeClosePending) return true;
		if (galleryMotion == null || pagerUnit == null || hiddenForPictureInPicture || galleryMode) return false;
		if (pagerUnit.getMotionMediaPhotoView() == null || !InterfaceMotion.isEnabled()) return false;
		resetPredictiveBackView(false);
		GalleryDialog closing = getDialog();
		if (closing == null) return false;
		swipeChrome.reset();
		galleryMotion.setMediaChrome(pagerUnit.getMotionVideoControls());
		boolean animated = galleryMotion.close(pagerUnit.getMotionMediaPhotoView(), pagerUnit.getCurrentGalleryItem(),
				closing.getActionBarView(), () -> dismissPhotoAfterMotion(closing), pagerUnit.getMotionVideoSurface());
		if (animated && getWindow() != null) getWindow().setWindowAnimations(0);
		return animated;
	}

	private void dismissPhotoAfterMotion(GalleryDialog expected) {
		if (!isAdded() || isRemoving() || getDialog() != expected || expected == null) return;
		if (getParentFragmentManager().isDestroyed()) return;
		if (getParentFragmentManager().isStateSaved()) dismissAllowingStateLoss(); else dismiss();
	}

	@Override public boolean deferPhotoSwipeClose(boolean down) {
		if (!InterfaceMotion.isEnabled() || down || galleryWindow || pagerUnit == null ||
				pagerUnit.getMotionPhotoView() == null || rootView == null || getDialog() == null) return false;
		finishGalleryMotion();
		photoSwipeClosePending = true;
		photoSwipeWindow = getDialog();
		rootView.setTag(R.id.gallery_motion_owner, photoSwipeOwner);
		if (getWindow() != null) getWindow().setWindowAnimations(0);
		return true;
	}

	@Override public void completePhotoSwipeClose() {
		if (!photoSwipeClosePending) return;
		photoSwipeClosePending = false;
		GalleryDialog closing = photoSwipeWindow;
		photoSwipeWindow = null;
		if (rootView != null && rootView.getTag(R.id.gallery_motion_owner) == photoSwipeOwner) rootView.setTag(R.id.gallery_motion_owner, null);
		dismissPhotoAfterMotion(closing);
	}

	@Override
	public void onPredictiveBackStarted(boolean fromLeft) {
		finishGalleryMotion();
		if (InterfaceMotion.isEnabled() && InterfaceMotion.duration(1) == 0) return;
		if (rootView != null) {
			predictiveBackRunning = true;
			rootView.animate().cancel();
		}
	}

	@Override
	public void onPredictiveBackProgressed(float progress, boolean fromLeft) {
		if (predictiveBackRunning && rootView != null) {
			float easedProgress = 1f - (float) Math.pow(1f - progress, 3f);
			float direction = fromLeft ? 1f : -1f;
			rootView.setTranslationX(direction * 24f * ResourceUtils.obtainDensity(rootView.getContext()) * easedProgress);
			rootView.setScaleX(1f - 0.04f * easedProgress);
			rootView.setScaleY(1f - 0.04f * easedProgress);
			rootView.setAlpha(1f - 0.18f * easedProgress);
		}
	}

	@Override
	public void onPredictiveBackCancelled() {
		resetPredictiveBackView(true);
	}

	@Override
	public void onPredictiveBackCommitted() {
		if (photoSwipeClosePending || galleryMotion != null && galleryMotion.isClosing()) return;
		resetPredictiveBackView(true);
	}

	private void resetPredictiveBackView(boolean animate) {
		predictiveBackRunning = false;
		if (rootView != null) {
			rootView.animate().cancel();
			if (animate && rootView.isAttachedToWindow()) {
				if (InterfaceMotion.isEnabled()) {
					rootView.animate().translationX(0f).scaleX(1f).scaleY(1f).alpha(1f)
							.setDuration(InterfaceMotion.duration(InterfaceMotion.RECOVERY_DURATION))
							.setInterpolator(InterfaceMotion.STANDARD).start();
				} else {
					rootView.animate().translationX(0f).scaleX(1f).scaleY(1f).alpha(1f).setDuration(150)
							.setInterpolator(new android.view.animation.AccelerateDecelerateInterpolator()).start();
				}
			} else {
				rootView.setTranslationX(0f);
				rootView.setScaleX(1f);
				rootView.setScaleY(1f);
				rootView.setAlpha(1f);
			}
		}
	}

	@Override
	public void onCreateMenu(@NonNull Menu menu, @NonNull MenuInflater inflater) {
		// Dialog.onRestoreInstanceState can create the menu before Fragment.onStart.
		// Its static structure depends on the dialog theme, not on GalleryInstance.
		GalleryDialog dialog = getDialog();
		Context context = dialog != null ? dialog.getContext()
				: new ContextThemeWrapper(requireContext(), R.style.Theme_Gallery);
		menu.add(0, R.id.menu_save, 0, R.string.save)
				.setIcon(ResourceUtils.getActionBarIcon(context, R.attr.iconActionSave))
				.setShowAsAction(MenuItem.SHOW_AS_ACTION_ALWAYS);
		menu.add(0, R.id.menu_refresh, 0, R.string.refresh)
				.setIcon(ResourceUtils.getActionBarIcon(context, R.attr.iconActionRefresh))
				.setShowAsAction(MenuItem.SHOW_AS_ACTION_ALWAYS);
		menu.add(0, R.id.menu_filter, 0, R.string.filter)
				.setIcon(R.drawable.ic_action_filter)
				.setShowAsAction(MenuItem.SHOW_AS_ACTION_ALWAYS);
		menu.add(0, R.id.menu_select, 0, R.string.select)
				.setIcon(ResourceUtils.getActionBarIcon(context, R.attr.iconActionSelect))
				.setShowAsAction(MenuItem.SHOW_AS_ACTION_ALWAYS);
		menu.add(0, R.id.menu_tiktok_filter, 0, R.string.tiktok_filter)
				.setShowAsAction(MenuItem.SHOW_AS_ACTION_NEVER);
	}

	private boolean isGalleryMenuReady() {
		return initializedDialog != null && initializedDialog == getDialog()
				&& instance != null && pagerUnit != null && listUnit != null;
	}

	@Override
	public void onPrepareMenu(@NonNull Menu menu) {
		for (int i = 0; i < menu.size(); i++) {
			menu.getItem(i).setVisible(false);
		}
		if (!isGalleryMenuReady()) {
			return;
		}
		if (!galleryMode) {
			menu.findItem(R.id.menu_tiktok_filter).setVisible(pagerUnit.getCurrentGalleryItem() != null);
			menu.findItem(R.id.menu_refresh).setEnabled(true).setTitle(R.string.refresh);
			PagerUnit.OptionsMenuCapabilities capabilities = pagerUnit != null
					? pagerUnit.obtainOptionsMenuCapabilities() : null;
			if (capabilities != null && capabilities.available) {
				menu.findItem(R.id.menu_save).setVisible(capabilities.save);
				menu.findItem(R.id.menu_refresh).setVisible(capabilities.refresh);
			}
			if (pagerUnit != null) {
				pagerUnit.invalidatePopupMenu();
			}
		} else {
			MenuItem filterItem = menu.findItem(R.id.menu_filter);
			menu.findItem(R.id.menu_refresh).setVisible(canRefreshGallery()).setEnabled(!galleryRefreshing)
					.setTitle(R.string.gallery_refresh);
			filterItem.setVisible(listUnit.areItemsSelectable());
			Drawable filterIcon = instance.context.getDrawable(R.drawable.ic_action_filter).mutate();
			filterIcon.setTint(isGalleryFilterActive()
					? ThemeEngine.getTheme(instance.context).accent : Color.WHITE);
			filterItem.setIcon(filterIcon);
			menu.findItem(R.id.menu_select).setVisible(listUnit.areItemsSelectable());
		}
	}

	@Override
	public boolean onMenuItemSelected(@NonNull MenuItem item) {
		if (item.getItemId() == android.R.id.home) {
			if (!requestPhotoClose()) dismiss();
			return true;
		}
		// Ignore stale menu actions during creation/teardown or in an empty gallery.
		if (!isGalleryMenuReady()) {
			return false;
		}
		PagerInstance.ViewHolder holder = pagerUnit.getCurrentHolder();
		if (item.getItemId() == R.id.menu_save) {
			if (holder == null || holder.galleryItem == null) return false;
			downloadGalleryItem(holder.galleryItem);
		} else if (item.getItemId() == R.id.menu_refresh) {
			if (galleryMode) {
				refreshGallery();
				return true;
			}
			if (holder == null || holder.galleryItem == null) return false;
			pagerUnit.refreshCurrent();
		} else if (item.getItemId() == R.id.menu_filter) {
			showGalleryFilterDialog();
		} else if (item.getItemId() == R.id.menu_tiktok_filter) {
			pagerUnit.showTikTokFilterDialog();
		} else if (item.getItemId() == R.id.menu_select) {
			listUnit.startSelectionMode(null);
		}
		return true;
	}

	@Override
	public Window getWindow() {
		GalleryDialog dialog = getDialog();
		return dialog != null ? dialog.getWindow() : null;
	}

	@Override
	public void downloadGalleryItem(GalleryItem galleryItem) {
		DownloadService.Binder binder = ((FragmentHandler) requireActivity()).getDownloadBinder();
		if (binder != null) {
			Chan chan = Chan.get(instance.chanName);
			Window window = getWindow();
			View feedbackView = galleryItem.isImage(chan) && window != null ? window.getDecorView() : null;
			binder.downloadStorage(new DownloadService.RequestItem(galleryItem.getFileUri(chan),
					galleryItem.getFileName(chan), galleryItem.originalName, feedbackView),
					chan.name, galleryItem.boardName, galleryItem.threadNumber, getThreadTitle());
		}
	}

	@Override
	public void downloadGalleryItems(List<GalleryItem> galleryItems) {
		String boardName = null;
		String threadNumber = null;
		Chan chan = Chan.get(instance.chanName);
		ArrayList<DownloadService.RequestItem> requestItems = new ArrayList<>();
		for (GalleryItem galleryItem : galleryItems) {
			if (requestItems.size() == 0) {
				boardName = galleryItem.boardName;
				threadNumber = galleryItem.threadNumber;
			} else if (boardName != null || threadNumber != null) {
				if (!CommonUtils.equals(boardName, galleryItem.boardName) ||
						!CommonUtils.equals(threadNumber, galleryItem.threadNumber)) {
					// Images from different threads, so don't use them to mark files and folders
					boardName = null;
					threadNumber = null;
				}
			}
			requestItems.add(new DownloadService.RequestItem(galleryItem.getFileUri(chan),
					galleryItem.getFileName(chan), galleryItem.originalName));
		}
		if (requestItems.size() > 0) {
			DownloadService.Binder binder = ((FragmentHandler) requireActivity()).getDownloadBinder();
			if (binder != null) {
				binder.downloadStorage(requestItems, true, instance.chanName,
						boardName, threadNumber, getThreadTitle());
			}
		}
	}

	@Override
	public void onSaveInstanceState(@NonNull Bundle outState) {
		super.onSaveInstanceState(outState);
		saveGalleryState(outState);
	}

	private void saveGalleryState(Bundle outState) {
		rememberViewport();
		if (instance == null && restorationState != null) outState.putAll(restorationState);
		GalleryRestoreDiagnostics.observe("Audit/Gallery/tokenInSavedState", galleryState.restoreToken);
		outState.putString(EXTRA_RESTORE, galleryState.restoreToken);
		outState.putString(EXTRA_FILTER, galleryFilter);
		outState.putString(EXTRA_SORT, gallerySort.name());
		GalleryViewportMemory.Snapshot viewport = galleryState.viewport;
		if (viewport != null) {
			Bundle snapshot = new Bundle();
			snapshot.putString("item", viewport.itemKey);
			snapshot.putInt("offset", viewport.offset);
			snapshot.putInt("width", viewport.width);
			snapshot.putInt("columns", viewport.columns);
			outState.putBundle(EXTRA_GRID_VIEWPORT, snapshot);
		}
		outState.putBoolean(EXTRA_PIP_WINDOW_RETIRED, retirePictureInPictureGallery || hiddenForPictureInPicture);
		if (instance == null) return;
		if (pagerUnit != null) {
			outState.putInt(EXTRA_POSITION, pagerUnit.getCurrentIndex());
			pagerUnit.savePagingState(outState);
			GalleryItem selected = pagerUnit.getCurrentGalleryItem();
			if (selected != null && selected.getFileUri(Chan.get(instance.chanName)) != null
					&& selected.getFileUri(Chan.get(instance.chanName)).toString().length() <= 8192) {
				GalleryRestoreCodec.Item item = GalleryRestoreStore.describe(selected, Chan.get(instance.chanName));
				Bundle attachment = new Bundle();
				attachment.putString("uri", item.uri());
				attachment.putString("board", item.board()); attachment.putString("thread", item.thread());
				attachment.putString("post", item.post());
				outState.putBundle(EXTRA_ATTACHMENT, attachment);
			}
		}
		if (listUnit != null) {
			outState.putIntArray(EXTRA_SELECTED, listUnit.getSelectedPositions());
			androidx.recyclerview.widget.RecyclerView.LayoutManager manager = listUnit.getRecyclerView().getLayoutManager();
			if (manager instanceof androidx.recyclerview.widget.LinearLayoutManager) {
				outState.putInt(EXTRA_GRID_POSITION, ((androidx.recyclerview.widget.LinearLayoutManager) manager)
						.findFirstVisibleItemPosition());
			}
		}
		outState.putBoolean(EXTRA_GALLERY_WINDOW, galleryWindow);
		outState.putBoolean(EXTRA_GALLERY_MODE, galleryMode);
		outState.putBoolean(EXTRA_SYSTEM_UI_VISIBILITY,
				FlagUtils.get(systemUiVisibilityFlags, GalleryInstance.Flags.LOCKED_USER));
		outState.putBoolean(EXTRA_VIDEO_FULLSCREEN, videoFullscreen);
		outState.putInt(EXTRA_VIDEO_FULLSCREEN_PREVIOUS_ORIENTATION, videoFullscreenPreviousOrientation);
		outState.putInt(EXTRA_VIDEO_FULLSCREEN_PREVIOUS_SYSTEM_UI_FLAGS,
				videoFullscreenPreviousSystemUiFlags);
		outState.putBoolean(EXTRA_VIDEO_FULLSCREEN_REQUESTED_ORIENTATION,
				videoFullscreenRequestedOrientation);
	}

	private static final int GALLERY_TRANSITION_DURATION = 150;

	private void switchMode(boolean galleryMode, boolean animated) {
		instance.logNavigation("mode_change fromGrid=" + this.galleryMode + " toGrid=" + galleryMode
				+ " index=" + pagerUnit.getCurrentIndex() + " animated=" + animated);
		finishGalleryMotion();
		int duration = animated ? (InterfaceMotion.isEnabled() ? 300 : GALLERY_TRANSITION_DURATION) : 0;
		pagerUnit.switchMode(galleryMode, duration);
		listUnit.switchMode(galleryMode, duration);
		if (galleryMode) {
			updateGalleryModeTitle();
		}
		modifySystemUiVisibility(GalleryInstance.Flags.LOCKED_GRID, galleryMode);
		this.galleryMode = galleryMode;
		if (galleryMode) {
			new CornerAnimator(0xa0, 0xc0);
		} else {
			int alpha = Color.alpha(ACTION_BAR_COLOR);
			new CornerAnimator(alpha, alpha);
		}
		invalidateOptionsMenu();
		if (!galleryMode) {
			displayShowcase();
		}
	}

	public boolean enterPictureInPictureIfPlaying() {
		return pagerUnit != null && !hiddenForPictureInPicture
				&& pagerUnit.enterPictureInPictureIfPlaying();
	}

	private static class GalleryFilterOption {
		public final String value;
		public final String title;
		public final int count;

		public GalleryFilterOption(String value, String title, int count) {
			this.value = value;
			this.title = title;
			this.count = count;
		}
	}

	private static String getGalleryExtension(GalleryItem galleryItem, Chan chan) {
		String extension = StringUtils.getFileExtension(galleryItem.getFileName(chan));
		if (StringUtils.isEmpty(extension)) {
			return "";
		}
		extension = extension.toLowerCase(Locale.US);
		return "jpeg".equals(extension) ? "jpg" : extension;
	}

	private List<GalleryFilterOption> createGalleryFilterOptions() {
		Chan chan = Chan.get(instance.chanName);
		int pictures = 0;
		int gifs = 0;
		int videos = 0;
		for (GalleryItem galleryItem : allGalleryItems) {
			String extension = getGalleryExtension(galleryItem, chan);
			if ("gif".equals(extension)) {
				gifs++;
			} else if (galleryItem.isVideo(chan)) {
				videos++;
			} else if (galleryItem.isImage(chan)) {
				pictures++;
			}
		}

		ArrayList<GalleryFilterOption> options = new ArrayList<>();
		options.add(new GalleryFilterOption(FILTER_ALL, getString(R.string.gallery_filter_all_files),
				allGalleryItems.size()));
		if (pictures > 0) {
			options.add(new GalleryFilterOption(FILTER_PICTURES,
					getString(R.string.gallery_filter_pictures), pictures));
		}
		if (gifs > 0) {
			options.add(new GalleryFilterOption(FILTER_GIF, getString(R.string.gallery_filter_gif), gifs));
		}
		if (videos > 0) {
			options.add(new GalleryFilterOption(FILTER_VIDEO, getString(R.string.gallery_filter_video), videos));
		}
		return options;
	}

	private boolean matchesGalleryFilter(GalleryItem galleryItem, Chan chan, String filter) {
		String extension = getGalleryExtension(galleryItem, chan);
		switch (filter) {
			case FILTER_PICTURES: {
				return galleryItem.isImage(chan) && !"gif".equals(extension);
			}
			case FILTER_GIF: {
				return "gif".equals(extension);
			}
			case FILTER_VIDEO: {
				return galleryItem.isVideo(chan);
			}
			case FILTER_ALL: {
				return true;
			}
			default: {
				return false;
			}
		}
	}

	private static long getGalleryResolution(GalleryItem galleryItem) {
		return (long) galleryItem.width * galleryItem.height;
	}

	private static int compareKnownValues(long left, long right, boolean descending) {
		if (left <= 0 || right <= 0) {
			if (left <= 0 && right <= 0) {
				return 0;
			}
			return left <= 0 ? 1 : -1;
		}
		return descending ? Long.compare(right, left) : Long.compare(left, right);
	}

	private void applyGalleryFilter(String filter, GallerySort sort) {
		applyGalleryFilter(filter, sort, false);
	}

	private void applyGalleryFilter(String filter, GallerySort sort, boolean refresh) {
		List<GalleryItem> oldItems = refresh ? new ArrayList<>(instance.galleryItems) : null;
		// Capture the actual grid BEFORE replacing its backing list. The pager may
		// still point to the image used to enter the gallery, far from this viewport.
		ListUnit.FilterViewport viewport = !refresh && galleryMode ? listUnit.captureFilterViewport() : null;
		boolean preserveEdges = sort == gallerySort;
		GalleryItem currentGalleryItem = pagerUnit.getCurrentGalleryItem();
		if (refresh && currentGalleryItem == null && !instance.galleryItems.isEmpty()) {
			currentGalleryItem = instance.galleryItems.get(Math.max(0,
					Math.min(pagerUnit.getCurrentIndex(), instance.galleryItems.size() - 1)));
		}
		Chan chan = Chan.get(instance.chanName);
		List<GalleryItem> galleryItems = filteredGalleryItems(allGalleryItems, chan, filter, sort);
		instance.galleryItems.clear();
		instance.galleryItems.addAll(galleryItems);
		int position = currentGalleryItem != null ? instance.galleryItems.indexOf(currentGalleryItem) : -1;
		if (refresh) {
			if (position < 0) position = 0;
			listUnit.onGalleryItemsRefreshed(oldItems);
			pagerUnit.onGalleryItemsRefreshed(position);
			updateTitle();
			if (galleryMode) updateGalleryModeTitle();
			invalidateOptionsMenu();
			return;
		}
		int gridPosition = viewport != null ? GalleryFilterPosition.resolve(allGalleryItems,
				instance.galleryItems, viewport.item, viewport.atStart, viewport.atEnd, preserveEdges) : -1;
		if (position < 0) {
			position = gridPosition >= 0 ? gridPosition : GalleryFilterPosition.resolve(allGalleryItems,
					instance.galleryItems, currentGalleryItem, false, false, false);
			if (position < 0) position = 0;
		}
		listUnit.onGalleryItemsChanged();
		pagerUnit.onGalleryItemsChanged(position);
		if (galleryMode) {
			if (gridPosition < 0) gridPosition = position;
			int offset = viewport != null && !(preserveEdges && (viewport.atStart || viewport.atEnd))
					? viewport.offset : 0;
			listUnit.restoreFilterPosition(gridPosition, offset);
			instance.logNavigation("filter_position schema=1 index=" + gridPosition + " offset=" + offset
					+ " anchor=" + (viewport != null) + " preserveEdges=" + preserveEdges
					+ " start=" + (viewport != null && viewport.atStart)
					+ " end=" + (viewport != null && viewport.atEnd));
			updateGalleryModeTitle();
		} else {
			updateTitle();
		}
		invalidateOptionsMenu();
	}

	private List<GalleryItem> filteredGalleryItems(List<GalleryItem> source, Chan chan, String filter, GallerySort sort) {
		ArrayList<GalleryItem> galleryItems = new ArrayList<>();
		for (GalleryItem galleryItem : source) {
			if (matchesGalleryFilter(galleryItem, chan, filter)) {
				galleryItems.add(galleryItem);
			}
		}
		if (galleryItems.isEmpty()) {
			filter = FILTER_ALL;
			galleryItems.addAll(source);
		}
		switch (sort) {
			case NEWEST_FIRST: {
				Collections.reverse(galleryItems);
				break;
			}
			case LARGEST_FIRST: {
				galleryItems.sort((left, right) -> compareKnownValues(left.size, right.size, true));
				break;
			}
			case SMALLEST_FIRST: {
				galleryItems.sort((left, right) -> compareKnownValues(left.size, right.size, false));
				break;
			}
			case HIGHEST_RESOLUTION: {
				galleryItems.sort((left, right) -> compareKnownValues(getGalleryResolution(left),
						getGalleryResolution(right), true));
				break;
			}
			case POST_ORDER: {
				break;
			}
		}

		galleryFilter = filter;
		gallerySort = sort;
		return galleryItems;
	}

	private void updateGalleryModeTitle() {
		int count = instance.galleryItems.size();
		int total = allGalleryItems.size();
		CharSequence subtitle = count == total ? getResources()
				.getQuantityString(R.plurals.number_files__format, count, count)
				: getString(R.string.gallery_filtered_count__format, count, total);
		getDialog().setTitleSubtitle(getString(R.string.gallery), subtitle);
		titleSubtitle = null;
	}

	private boolean isGalleryFilterActive() {
		return !FILTER_ALL.equals(galleryFilter) || gallerySort != GallerySort.POST_ORDER;
	}

	private TextView createGalleryFilterSection(Context context, int textResId, int topPadding) {
		TextView textView = new TextView(context);
		ThemeEngine.applyStyle(textView);
		textView.setText(textResId);
		textView.setTextColor(ThemeEngine.getTheme(context).accent);
		textView.setTextSize(14f);
		textView.setPadding(0, topPadding, 0, topPadding / 3);
		return textView;
	}

	private static RadioButton createGalleryFilterRadio(Context context, CharSequence text) {
		RadioButton radioButton = new RadioButton(context);
		ThemeEngine.applyStyle(radioButton);
		radioButton.setId(View.generateViewId());
		radioButton.setText(text);
		return radioButton;
	}

	private void showGalleryFilterDialog() {
		Context context = getDialog().getContext();
		float density = ResourceUtils.obtainDensity(context);
		int horizontalPadding = (int) (24f * density);
		int verticalPadding = (int) (12f * density);
		LinearLayout container = new LinearLayout(context);
		container.setOrientation(LinearLayout.VERTICAL);
		container.setPadding(horizontalPadding, 0, horizontalPadding, verticalPadding);

		RadioGroup filterGroup = new RadioGroup(context);
		Map<Integer, String> filterValues = new LinkedHashMap<>();
		List<GalleryFilterOption> filterOptions = createGalleryFilterOptions();
		container.addView(createGalleryFilterSection(context, R.string.gallery_filter_show, verticalPadding));
		for (GalleryFilterOption option : filterOptions) {
			RadioButton radioButton = createGalleryFilterRadio(context,
					getString(R.string.gallery_filter_option_with_count__format, option.title, option.count));
			filterValues.put(radioButton.getId(), option.value);
			filterGroup.addView(radioButton);
			if (option.value.equals(galleryFilter)) {
				radioButton.setChecked(true);
			}
		}
		container.addView(filterGroup);

		container.addView(createGalleryFilterSection(context, R.string.sorting, verticalPadding));
		RadioGroup sortGroup = new RadioGroup(context);
		Map<Integer, GallerySort> sortValues = new LinkedHashMap<>();
		int[] sortTitles = {R.string.gallery_sort_post_order, R.string.gallery_sort_newest_first,
				R.string.gallery_sort_largest_first, R.string.gallery_sort_smallest_first,
				R.string.gallery_sort_highest_resolution};
		GallerySort[] sorts = GallerySort.values();
		for (int i = 0; i < sorts.length; i++) {
			RadioButton radioButton = createGalleryFilterRadio(context, getString(sortTitles[i]));
			sortValues.put(radioButton.getId(), sorts[i]);
			sortGroup.addView(radioButton);
			if (sorts[i] == gallerySort) {
				radioButton.setChecked(true);
			}
		}
		container.addView(sortGroup);

		ScrollView scrollView = new ScrollView(context);
		scrollView.addView(container);
		new MotionDialogBuilder(context).setTitle(R.string.gallery_filter).setView(scrollView)
				.setNegativeButton(android.R.string.cancel, null)
				.setNeutralButton(R.string.gallery_filter_reset, (dialog, which) ->
						applyGalleryFilter(FILTER_ALL, GallerySort.POST_ORDER))
				.setPositiveButton(android.R.string.ok, (dialog, which) -> {
					String filter = filterValues.get(filterGroup.getCheckedRadioButtonId());
					GallerySort sort = sortValues.get(sortGroup.getCheckedRadioButtonId());
					applyGalleryFilter(filter != null ? filter : FILTER_ALL,
							sort != null ? sort : GallerySort.POST_ORDER);
				}).show();
	}

	private class CornerAnimator implements Runnable {
		private final long startTime = SystemClock.elapsedRealtime();
		private final int fromActionBarAlpha, toActionBarAlpha, fromStatusBarAlpha, toStatusBarAlpha;
		private ValueAnimator modernAnimator;
		private static final int INTERVAL = 200;

		public CornerAnimator(int actionBarAlpha, int statusBarAlpha) {
			if (cornerAnimator != null) cornerAnimator.cancel();
			Drawable drawable = getDialog().getActionBarView().getBackground();
			fromActionBarAlpha = Color.alpha(drawable instanceof ColorDrawable ? ((ColorDrawable) drawable).getColor() : statusBarAlpha);
			toActionBarAlpha = actionBarAlpha;
			fromStatusBarAlpha = Color.alpha(ViewUtils.getStatusBarColor(getWindow()));
			toStatusBarAlpha = statusBarAlpha;
			if (fromActionBarAlpha != toActionBarAlpha || fromStatusBarAlpha != toStatusBarAlpha) {
				cornerAnimator = this;
				if (InterfaceMotion.isEnabled()) {
					modernAnimator = ValueAnimator.ofFloat(0f, 1f);
					modernAnimator.setDuration(InterfaceMotion.duration(INTERVAL));
					modernAnimator.setInterpolator(InterfaceMotion.STANDARD);
					modernAnimator.addUpdateListener(value -> {
						if (cornerAnimator != this || !apply((float) value.getAnimatedValue())) cancel();
					});
					modernAnimator.addListener(new android.animation.AnimatorListenerAdapter() {
						@Override public void onAnimationEnd(android.animation.Animator animation) {
							if (cornerAnimator == CornerAnimator.this) cornerAnimator = null;
						}
					});
					modernAnimator.start();
				} else run();
			}
		}
		private boolean apply(float progress) {
			GalleryDialog dialog = getDialog();
			if (dialog == null || rootView == null || getWindow() == null) return false;
			int actionBarColor = ((int) AnimationUtils.lerp(fromActionBarAlpha, toActionBarAlpha, progress) << 24)
					| (0x00ffffff & ACTION_BAR_COLOR);
			dialog.getActionBarView().setBackgroundColor(actionBarColor);
			View actionContextBar = dialog.getActionContextBarView();
			if (actionContextBar != null) actionContextBar.setBackgroundColor(actionBarColor);
			int color = ((int) AnimationUtils.lerp(fromStatusBarAlpha, toStatusBarAlpha, progress) << 24)
					| (0x00ffffff & ACTION_BAR_COLOR);
			ViewUtils.setStatusBarColor(getWindow(), color); ViewUtils.setNavigationBarColor(getWindow(), color);
			return true;
		}
		@Override public void run() {
			float progress = Math.min((float) (SystemClock.elapsedRealtime() - startTime) / INTERVAL, 1f);
			if (!apply(progress)) { cancel(); return; }
			if (progress < 1f) rootView.postOnAnimation(this);
			else if (cornerAnimator == this) cornerAnimator = null;
		}
		public void cancel() {
			if (rootView != null) rootView.removeCallbacks(this);
			if (modernAnimator != null) { modernAnimator.cancel(); modernAnimator = null; }
			if (cornerAnimator == this) cornerAnimator = null;
		}
	}

	@Override
	public void onCreateActionContextBarView() {
		GalleryDialog dialog = getDialog();
		Drawable drawable = dialog.getActionBarView().getBackground();
		if (drawable instanceof ColorDrawable) {
			dialog.getActionContextBarView().setBackgroundColor(((ColorDrawable) drawable).getColor());
		}
	}

	@Override
	public void modifyVerticalSwipeState(boolean ignoreIfGallery, float value) {
		if (ignoreIfGallery || galleryWindow) {
			value = 0f;
		}
		if (rootView == null) return;
		float amount = Math.max(0f, Math.min(1f, 1f - value));
		rootView.getBackground().setAlpha((int) (0xff * amount));
		GalleryDialog dialog = getDialog();
		if (InterfaceMotion.isEnabled() && dialog != null && amount < 1f) {
			swipeChrome.fade(amount, dialog.getActionBarView(), dialog.getActionContextBarView());
		} else swipeChrome.reset();
	}

	@Override
	public void updateTitle() {
		PagerInstance.ViewHolder holder = pagerUnit.getCurrentHolder();
		if (holder != null && holder.galleryItem != null) {
			setTitle(holder.galleryItem, holder.mediaSummary, pagerUnit.getCurrentIndex());
		}
	}

	private void setTitle(GalleryItem galleryItem, PagerInstance.MediaSummary mediaSummary, int position) {
		String fileName = galleryItem.getFileName(Chan.get(instance.chanName));
		if (!StringUtils.isEmpty(galleryItem.originalName)) {
			fileName = galleryItem.originalName;
		}
		int count = instance.galleryItems.size();
		StringBuilder builder = new StringBuilder().append(position + 1).append('/').append(count);
		if (mediaSummary.width > 0 && mediaSummary.height > 0) {
			builder.append(", ").append(mediaSummary.width).append('×').append(mediaSummary.height);
		}
		if (mediaSummary.size > 0) {
			builder.append(", ").append(StringUtils.formatFileSize(mediaSummary.size, false));
		}
		titleSubtitle = new Pair<>(fileName, builder);
		GalleryDialog dialog = getDialog();
		if (dialog != null) {
			dialog.setTitleSubtitle(fileName, builder);
		}
	}

	@Override
	public void navigateGalleryOrFinish(boolean enableGalleryMode) {
		if (enableGalleryMode && !galleryWindow) {
			galleryWindow = true;
		}
		if (!returnToGallery()) {
			getWindow().getDecorView().post(this::dismiss);
		}
	}

	@Override
	public VideoPipActivity.GalleryRestoreData createPictureInPictureGalleryRestoreData() {
		if (instance == null || pagerUnit == null || instance.galleryItems.isEmpty()) {
			return null;
		}
		int imageIndex = Math.max(0, Math.min(pagerUnit.getCurrentIndex(), instance.galleryItems.size() - 1));
		// Keep navigation state, but not the old window/player lifecycle. The return
		// always opens the selected video in a freshly prepared destination window.
		Bundle dialogState = new Bundle();
		dialogState.putInt(EXTRA_POSITION, imageIndex);
		pagerUnit.savePagingState(dialogState);
		dialogState.putBoolean(EXTRA_GALLERY_WINDOW, galleryWindow);
		dialogState.putBoolean(EXTRA_GALLERY_MODE, false);
		dialogState.putBoolean(EXTRA_SYSTEM_UI_VISIBILITY,
				FlagUtils.get(systemUiVisibilityFlags, GalleryInstance.Flags.LOCKED_USER));
		android.os.Parcelable gridState = listUnit != null
				&& listUnit.getRecyclerView().getLayoutManager() != null
				? listUnit.getRecyclerView().getLayoutManager().onSaveInstanceState() : null;
		return new VideoPipActivity.GalleryRestoreData(instance.chanName,
				new ArrayList<>(instance.galleryItems), imageIndex, getThreadTitle(), getNavigatePostMode(),
				new ArrayList<>(allGalleryItems != null ? allGalleryItems : instance.galleryItems),
				dialogState, gridState, galleryFilter, gallerySort.name());
	}

	@Override
	public void setGalleryVisibleForPictureInPicture(boolean visible) {
		hiddenForPictureInPicture = !visible;
		GalleryDialog dialog = getDialog();
		if (dialog != null) {
			if (visible) {
				dialog.show();
				invalidateSystemUiVisibility();
			} else {
				dialog.hide();
			}
		}
	}

	@Override
	public void bringGalleryToForeground(Context context) {
		Activity activity = getActivity();
		if (activity != null) {
			Intent intent = new Intent(context, activity.getClass())
					.setAction(C.ACTION_RETURN_FROM_PICTURE_IN_PICTURE)
					.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP
							| Intent.FLAG_ACTIVITY_SINGLE_TOP);
			context.startActivity(intent);
		}
	}

	@Override
	public void closeGallery() {
		boolean wasHiddenForPictureInPicture = hiddenForPictureInPicture;
		hiddenForPictureInPicture = false;
		if (wasHiddenForPictureInPicture) {
			dismissAfterPlayerClose();
			return;
		}
		Window window = getWindow();
		if (window == null || !window.getDecorView().post(this::dismissAfterPlayerClose)) {
			dismissAfterPlayerClose();
		}
	}

	private void dismissAfterPlayerClose() {
		// A PiP player can finish after its source activity saved state or was destroyed.
		// Check at execution time as this method may also run from a posted callback.
		if (!isAdded() || isRemoving()) {
			VideoDiagnostics.recordUi("gallery player_close skipped=detached_or_removing");
			return;
		}
		FragmentManager fragmentManager = getParentFragmentManager();
		if (fragmentManager.isDestroyed()) {
			VideoDiagnostics.recordUi("gallery player_close skipped=manager_destroyed");
			return;
		}
		boolean stateSaved = fragmentManager.isStateSaved();
		VideoDiagnostics.recordUi("gallery player_close state_saved=" + stateSaved);
		if (stateSaved) {
			// Only a transient gallery is removed; do not interrupt player teardown to
			// preserve a fragment transaction after the host's state was already saved.
			dismissAllowingStateLoss();
		} else {
			dismiss();
		}
	}

	@Override
	public void navigatePageFromList(int position) {
		listUnit.onPageOpenedFromGrid(position);
		switchMode(false, true);
		pagerUnit.navigatePageFromList(position, GALLERY_TRANSITION_DURATION);
	}

	private boolean checkAllowNavigatePost(boolean manually) {
		NavigatePostMode navigatePostMode = getNavigatePostMode();
		return navigatePostMode == NavigatePostMode.ENABLED ||
				navigatePostMode == NavigatePostMode.MANUALLY && manually;
	}

	@Override
	public void navigatePost(GalleryItem galleryItem, boolean manually, boolean force) {
		if (checkAllowNavigatePost(manually) && (scrollThread || force)) {
			if (force) rememberViewport();
			((FragmentHandler) requireActivity()).scrollToPost(instance.chanName, galleryItem.boardName,
					galleryItem.threadNumber, galleryItem.postNumber);
			if (force) {
				dismiss();
			}
		}
	}

	@Override
	public boolean isAllowNavigatePostManually(boolean fromPager) {
		// Don't allow navigate to post from pager if thread is scrolling automatically with pager
		return checkAllowNavigatePost(true) && (!(scrollThread && checkAllowNavigatePost(false)) || !fromPager);
	}

	@Override
	public void invalidateOptionsMenu() {
		GalleryDialog dialog = getDialog();
		if (dialog != null) {
			dialog.invalidateOptionsMenu();
		}
	}

	@Override
	public void setScreenOnFixed(boolean fixed) {
		screenOnFixed = fixed;
		GalleryDialog dialog = getDialog();
		if (dialog != null) {
			Window window = getWindow();
			if (fixed) {
				window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
			} else {
				window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
			}
		}
	}

	@Override
	public boolean isGalleryWindow() {
		return galleryWindow;
	}

	@Override
	public boolean isGalleryMode() {
		return galleryMode;
	}

	private void postInvalidateSystemUIVisibility() {
		rootView.post(this::invalidateSystemUiVisibility);
	}

	private void invalidateSystemUiVisibility() {
		GalleryDialog dialog = getDialog();
		if (dialog != null) {
			ActionBar actionBar = dialog.getActionBar();
			boolean visible = isSystemUiVisible();
			boolean changed = visible != actionBar.isShowing();
			if (visible) {
				actionBar.show();
			} else {
				actionBar.hide();
			}
			invalidateSystemUiFlags();
			if (pagerUnit != null) {
				pagerUnit.invalidateControlsVisibility();
				if (changed) {
					pagerUnit.invalidatePopupMenu();
				}
			}
		}
	}

	private void invalidateSystemUiFlags() {
		boolean visible = isSystemUiVisible();
		Window window = getWindow();
		WindowInsetsController controller = window.getInsetsController();
		controller.setSystemBarsBehavior(WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
		if (visible) {
			controller.show(WindowInsets.Type.systemBars());
		} else {
			controller.hide(WindowInsets.Type.systemBars());
		}
	}

	@Override
	public boolean isSystemUiVisible() {
		return systemUiVisibilityFlags != 0;
	}

	@Override
	public void modifySystemUiVisibility(int flag, boolean value) {
		systemUiVisibilityFlags = FlagUtils.set(systemUiVisibilityFlags, flag, value);
		invalidateSystemUiVisibility();
	}

	@Override
	public void toggleSystemUIVisibility(int flag) {
		modifySystemUiVisibility(flag, !FlagUtils.get(systemUiVisibilityFlags, flag));
	}

	@Override
	public boolean isVideoFullscreen() {
		return videoFullscreen;
	}

	@Override
	public void setVideoFullscreen(boolean fullscreen, boolean landscape) {
		if (videoFullscreen == fullscreen) {
			return;
		}
		if (fullscreen) {
			videoFullscreenPreviousOrientation = requireActivity().getRequestedOrientation();
			videoFullscreenPreviousSystemUiFlags = systemUiVisibilityFlags;
			videoFullscreen = true;
			systemUiVisibilityFlags = 0;
			boolean currentLandscape = getResources().getConfiguration().orientation
					== Configuration.ORIENTATION_LANDSCAPE;
			videoFullscreenRequestedOrientation = landscape != currentLandscape;
			if (videoFullscreenRequestedOrientation) {
				requireActivity().setRequestedOrientation(landscape
						? ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
						: ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT);
			}
		} else {
			videoFullscreen = false;
			systemUiVisibilityFlags = videoFullscreenPreviousSystemUiFlags;
			if (videoFullscreenRequestedOrientation) {
				requireActivity().setRequestedOrientation(videoFullscreenPreviousOrientation);
			}
			videoFullscreenRequestedOrientation = false;
		}
		invalidateSystemUiVisibility();
	}

	private Runnable showcaseDestroy;

	private boolean destroyShowcase(boolean consume) {
		if (showcaseDestroy != null) {
			if (consume) {
				Preferences.consumeShowcaseGallery();
			}
			showcaseDestroy.run();
			showcaseDestroy = null;
			return true;
		}
		return false;
	}

	private void displayShowcase() {
		if (pagerUnit == null || showcaseDestroy != null || !Preferences.isShowcaseGalleryEnabled() ||
				!rootView.isAttachedToWindow()) {
			return;
		}

		Context context = getWindow().getContext();
		float density = ResourceUtils.obtainDensity(context);
		FrameLayout frameLayout = new FrameLayout(context);
		frameLayout.setBackgroundColor(0xf0222222);
		LinearLayout linearLayout = new LinearLayout(context);
		linearLayout.setOrientation(LinearLayout.VERTICAL);
		frameLayout.addView(linearLayout, new FrameLayout.LayoutParams((int) (304f * density),
				FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.CENTER));

		Button button = new Button(context, null, android.R.attr.borderlessButtonStyle);
		ThemeEngine.applyStyle(button);
		button.setText(R.string.got_it);
		button.setMinimumWidth(0);
		button.setMinWidth(0);

		int paddingLeft = button.getPaddingLeft();
		int paddingRight = button.getPaddingRight();
		int paddingTop = button.getPaddingTop();
		int paddingBottom = Math.max(0, (int) (24f * density) - paddingTop);

		int[] titles = {R.string.context_menu, R.string.gallery};
		int[] messages = {R.string.context_menu_description__sentence, R.string.gallery_description__sentence};

		for (int i = 0; i < titles.length; i++) {
			TextView textView1 = new TextView(context, null, android.R.attr.textAppearanceLarge);
			ThemeEngine.applyStyle(textView1);
			textView1.setText(titles[i]);
			textView1.setTypeface(ResourceUtils.TYPEFACE_LIGHT);
			textView1.setPadding(paddingLeft, paddingTop, paddingRight, (int) (4f * density));
			TextView textView2 = new TextView(context, null, android.R.attr.textAppearanceSmall);
			ThemeEngine.applyStyle(textView2);
			textView2.setText(messages[i]);
			textView2.setPadding(paddingLeft, 0, paddingRight, paddingBottom);
			linearLayout.addView(textView1, LinearLayout.LayoutParams.MATCH_PARENT,
					LinearLayout.LayoutParams.WRAP_CONTENT);
			linearLayout.addView(textView2, LinearLayout.LayoutParams.MATCH_PARENT,
					LinearLayout.LayoutParams.WRAP_CONTENT);
		}

		linearLayout.addView(button, LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);

		WindowManager windowManager = (WindowManager) context.getSystemService(Context.WINDOW_SERVICE);
		WindowManager.LayoutParams layoutParams = new WindowManager.LayoutParams();
		layoutParams.format = PixelFormat.TRANSLUCENT;
		layoutParams.width = WindowManager.LayoutParams.MATCH_PARENT;
		layoutParams.height = WindowManager.LayoutParams.MATCH_PARENT;
		layoutParams.flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE |
				WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL;
		layoutParams.windowAnimations = R.style.Animation_Gallery_Full;
		layoutParams.flags |= WindowManager.LayoutParams.FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS;
		windowManager.addView(frameLayout, layoutParams);

		showcaseDestroy = () -> windowManager.removeViewImmediate(frameLayout);
		button.setOnClickListener(v -> destroyShowcase(true));
	}
}
