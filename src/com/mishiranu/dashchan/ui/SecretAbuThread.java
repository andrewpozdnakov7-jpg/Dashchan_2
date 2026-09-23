package com.mishiranu.dashchan.ui;

import android.content.Context;
import android.content.ContextWrapper;
import android.os.Bundle;
import android.text.TextUtils;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.PopupMenu;
import android.widget.TextView;
import androidx.annotation.NonNull;
import androidx.fragment.app.FragmentManager;
import androidx.recyclerview.widget.RecyclerView;
import chan.content.Chan;
import com.mishiranu.dashchan.R;
import com.mishiranu.dashchan.content.model.Post;
import com.mishiranu.dashchan.content.model.PostItem;
import com.mishiranu.dashchan.content.model.PostNumber;
import com.mishiranu.dashchan.ui.navigator.PageFragment;
import com.mishiranu.dashchan.ui.navigator.manager.UiManager;
import com.mishiranu.dashchan.ui.navigator.manager.ViewUnit;
import com.mishiranu.dashchan.ui.preference.BaseListFragment;
import com.mishiranu.dashchan.util.ResourceUtils;
import com.mishiranu.dashchan.widget.ClickableToast;
import com.mishiranu.dashchan.widget.DividerItemDecoration;
import java.util.Calendar;
import java.util.Collections;

// A presentation-only post: never inserted into the real thread list or database.
public final class SecretAbuThread {
	private SecretAbuThread() {}

	public static final class State {
		private int taps;
		private boolean triedToHide;
		private long timestamp;

		private long getTimestamp() {
			if (timestamp == 0) {
				Calendar tomorrow = Calendar.getInstance();
				tomorrow.add(Calendar.DAY_OF_MONTH, 1);
				timestamp = tomorrow.getTimeInMillis();
			}
			return timestamp;
		}
	}

	public static RecyclerView.ViewHolder createViewHolder(ViewGroup parent, State state,
			UiManager uiManager, FragmentManager fragmentManager) {
		return createViewHolder(parent, state, uiManager, fragmentManager, ViewUnit.ViewType.POST, "abu");
	}

	public static RecyclerView.ViewHolder createCatalogViewHolder(ViewGroup parent, State state,
			UiManager uiManager, FragmentManager fragmentManager, boolean cardsMode, String boardName) {
		return createViewHolder(parent, state, uiManager, fragmentManager,
				cardsMode ? ViewUnit.ViewType.THREAD_CARD : ViewUnit.ViewType.THREAD, boardName);
	}

	private static RecyclerView.ViewHolder createViewHolder(ViewGroup parent, State state,
			UiManager uiManager, FragmentManager fragmentManager, ViewUnit.ViewType viewType, String boardName) {
		boolean catalog = viewType != ViewUnit.ViewType.POST;
		Context context = parent.getContext();
		float density = ResourceUtils.obtainDensity(context);
		int padding = (int) (12f * density + 0.5f);
		LinearLayout card = new LinearLayout(context);
		card.setOrientation(LinearLayout.VERTICAL);
		card.setLayoutParams(new RecyclerView.LayoutParams(
				ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
		Post.Builder builder = new Post.Builder();
		builder.number = new PostNumber(1, 0);
		builder.name = "Abu";
		builder.timestamp = state.getTimestamp();
		builder.subject = context.getString(R.string.secret_abu_title);
		builder.comment = TextUtils.htmlEncode(context.getString(R.string.secret_abu_body));
		builder.setSticky(true);
		Post post = builder.build(false);
		PostItem postItem = catalog
				? PostItem.createThread(Collections.singletonList(post), 1, 0, 0, Chan.get("dvach"), boardName, "1")
				: PostItem.createPost(post, Chan.get("dvach"), boardName, "1", builder.number);
		postItem.setOrdinalIndex(0);
		UiManager.ConfigurationSet configuration = new UiManager.ConfigurationSet("dvach", null, null,
				UiManager.PostStateProvider.DEFAULT, null, fragmentManager, null, null, null,
				false, false, false, false, false, null);
		// Intercept local gestures before the ordinary post's reply, link or context-menu handlers.
		FrameLayout presentation = new FrameLayout(context) {
			@Override
			public boolean onInterceptTouchEvent(MotionEvent event) {
				return true;
			}
		};
		presentation.setDescendantFocusability(ViewGroup.FOCUS_BLOCK_DESCENDANTS);
		RecyclerView.ViewHolder nativeHolder = uiManager.view().createView(presentation, viewType);
		if (catalog) {
			uiManager.view().bindThreadView(nativeHolder, postItem, configuration);
		} else {
			uiManager.view().bindPostView(nativeHolder, postItem, configuration, new UiManager.DemandSet());
		}
		nativeHolder.itemView.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS);
		presentation.setContentDescription("Abu. " + context.getString(R.string.secret_abu_title) + ". "
				+ context.getString(R.string.secret_abu_body));
		presentation.addView(nativeHolder.itemView, new FrameLayout.LayoutParams(
				ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
		card.addView(presentation);
		presentation.setOnClickListener(v -> {
			if (catalog) {
				openThread(v.getContext(), state, boardName);
				return;
			}
			state.taps = Math.min(5, state.taps + 1);
			ClickableToast.show(state.taps >= 5 ? R.string.secret_abu_stop_tapping : R.string.secret_abu_busy);
		});
		presentation.setOnLongClickListener(v -> {
			if (catalog) {
				PopupMenu menu = new PopupMenu(v.getContext(), v);
				menu.getMenu().add(R.string.hide).setOnMenuItemClickListener(item -> {
					tryHide(state);
					return true;
				});
				menu.show();
				return true;
			}
			ClickableToast.show(R.string.secret_abu_stop_tapping);
			return true;
		});
		if (catalog) return new LocalHolder(card, nativeHolder, postItem, configuration, true);
		TextView footer = new TextView(context);
		footer.setText(R.string.secret_abu_footer);
		footer.setTextSize(12);
		footer.setAlpha(0.65f);
		footer.setPadding(padding, 0, padding, padding / 2);
		card.addView(footer);
		Button hide = new Button(context, null, android.R.attr.borderlessButtonStyle);
		hide.setText(R.string.hide);
		hide.setOnClickListener(v -> tryHide(state));
		card.addView(hide, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,
				ViewGroup.LayoutParams.WRAP_CONTENT));
		return new LocalHolder(card, nativeHolder, postItem, configuration, false);
	}

	private static void tryHide(State state) {
		ClickableToast.show(state.triedToHide ? R.string.secret_abu_hide_again : R.string.secret_abu_cannot_hide);
		state.triedToHide = true;
	}

	private static void openThread(Context context, State state, String boardName) {
		while (!(context instanceof FragmentHandler) && context instanceof ContextWrapper) {
			Context base = ((ContextWrapper) context).getBaseContext();
			if (base == context) break;
			context = base;
		}
		if (context instanceof FragmentHandler) {
			Bundle args = new Bundle();
			args.putString("abuBoard", boardName);
			args.putLong("abuTimestamp", state.getTimestamp());
			args.putInt("abuTaps", state.taps);
			args.putBoolean("abuTriedToHide", state.triedToHide);
			ThreadFragment fragment = new ThreadFragment();
			fragment.setArguments(args);
			((FragmentHandler) context).pushFragment(fragment);
		}
	}

	public static void bindViewHolder(RecyclerView.ViewHolder holder, UiManager uiManager) {
		LocalHolder localHolder = (LocalHolder) holder;
		if (localHolder.catalog) {
			uiManager.view().bindThreadView(localHolder.nativeHolder, localHolder.postItem, localHolder.configuration);
		} else {
			uiManager.view().bindPostView(localHolder.nativeHolder, localHolder.postItem,
					localHolder.configuration, new UiManager.DemandSet());
		}
	}

	private static final class LocalHolder extends RecyclerView.ViewHolder {
		// Native holders keep weak model references; retain this isolated model for the view's lifetime.
		private final RecyclerView.ViewHolder nativeHolder;
		private final PostItem postItem;
		private final UiManager.ConfigurationSet configuration;
		private final boolean catalog;

		private LocalHolder(View view, RecyclerView.ViewHolder nativeHolder, PostItem postItem,
				UiManager.ConfigurationSet configuration, boolean catalog) {
			super(view);
			this.nativeHolder = nativeHolder;
			this.postItem = postItem;
			this.configuration = configuration;
			this.catalog = catalog;
			// Do not transfer a local card's state to another board through the shared recycled pool.
			setIsRecyclable(false);
		}
	}

	public static class PreviewBoardFragment extends BaseListFragment {
		private final State state = new State();

		protected boolean isThreadPage() {
			return false;
		}

		@Override
		public void onViewCreated(@NonNull View view, Bundle savedInstanceState) {
			super.onViewCreated(view, savedInstanceState);
			Bundle saved = savedInstanceState != null ? savedInstanceState : getArguments();
			if (saved != null) {
				state.taps = saved.getInt("abuTaps");
				state.triedToHide = saved.getBoolean("abuTriedToHide");
				state.timestamp = saved.getLong("abuTimestamp");
			}
			String boardName = getArguments() != null ? getArguments().getString("abuBoard", "abu") : "abu";
			((FragmentHandler) requireActivity()).setTitleSubtitle(
					getString(isThreadPage() ? R.string.secret_abu_title : R.string.secret_abu_board_title),
					isThreadPage() ? "/" + boardName + "/" : getString(R.string.secret_abu_board_subtitle));
			getRecyclerView().setAdapter(new RecyclerView.Adapter<RecyclerView.ViewHolder>() {
				@NonNull
				@Override
				public RecyclerView.ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
					UiManager uiManager = ((PageFragment.Callback) requireActivity()).getUiManager();
					return isThreadPage() ? SecretAbuThread.createViewHolder(parent, state, uiManager,
							getParentFragmentManager(), ViewUnit.ViewType.POST, boardName)
							: SecretAbuThread.createCatalogViewHolder(parent, state, uiManager,
									getParentFragmentManager(), false, boardName);
				}

				@Override
				public void onBindViewHolder(@NonNull RecyclerView.ViewHolder holder, int position) {
					SecretAbuThread.bindViewHolder(holder, ((PageFragment.Callback) requireActivity()).getUiManager());
				}

				@Override
				public int getItemCount() {
					return 1;
				}
			});
		}

		@Override
		public void onSaveInstanceState(@NonNull Bundle outState) {
			super.onSaveInstanceState(outState);
			outState.putInt("abuTaps", state.taps);
			outState.putBoolean("abuTriedToHide", state.triedToHide);
			outState.putLong("abuTimestamp", state.timestamp);
		}

		@Override
		protected void setListPadding(RecyclerView recyclerView) {
			recyclerView.setPadding(0, 0, 0, 0);
		}

		@Override
		protected DividerItemDecoration.Configuration configureDivider(
				DividerItemDecoration.Configuration configuration, int position) {
			return configuration.need(false);
		}
	}

	public static class ThreadFragment extends PreviewBoardFragment {
		@Override
		protected boolean isThreadPage() {
			return true;
		}
	}
}
