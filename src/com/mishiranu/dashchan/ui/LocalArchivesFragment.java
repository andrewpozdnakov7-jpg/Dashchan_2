package com.mishiranu.dashchan.ui;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.UriPermission;
import android.net.Uri;
import android.os.Bundle;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ProgressBar;
import android.widget.TextView;
import androidx.annotation.NonNull;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import com.mishiranu.dashchan.C;
import com.mishiranu.dashchan.R;
import com.mishiranu.dashchan.content.LocalArchiveManager;
import com.mishiranu.dashchan.content.Preferences;
import com.mishiranu.dashchan.util.ConcurrentUtils;
import com.mishiranu.dashchan.util.PostDateFormatter;
import com.mishiranu.dashchan.widget.DividerItemDecoration;
import com.mishiranu.dashchan.widget.PaddedRecyclerView;
import com.mishiranu.dashchan.widget.ClickableToast;
import com.mishiranu.dashchan.widget.ThemeEngine;
import com.mishiranu.dashchan.widget.ViewFactory;
import java.util.ArrayList;
import java.util.List;

public class LocalArchivesFragment extends ContentFragment {
	private static final int MENU_ADD_FOLDER = 0x6201;
	private static final int MENU_MANAGE_FOLDERS = 0x6202;

	private PaddedRecyclerView recyclerView;
	private TextView emptyView;
	private ProgressBar progressBar;
	private ArchiveAdapter adapter;
	private int loadGeneration;

	@Override
	public View onCreateView(@NonNull LayoutInflater inflater, ViewGroup container, Bundle savedInstanceState) {
		FrameLayout root = new FrameLayout(container.getContext());
		recyclerView = new PaddedRecyclerView(root.getContext());
		recyclerView.setLayoutManager(new LinearLayoutManager(root.getContext()));
		adapter = new ArchiveAdapter(new ArchiveAdapter.Callback() {
			@Override
			public void onClick(LocalArchiveManager.Item item) {
				openArchive(item);
			}

			@Override
			public void onLongClick(LocalArchiveManager.Item item) {
				confirmDeleteArchive(item);
			}
		});
		recyclerView.setAdapter(adapter);
		recyclerView.addItemDecoration(new DividerItemDecoration(recyclerView.getContext(),
				(configuration, position) -> configuration.need(true)));
		recyclerView.setItemAnimator(null);
		root.addView(recyclerView, FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT);

		emptyView = new TextView(root.getContext());
		ThemeEngine.applyStyle(emptyView);
		emptyView.setText(R.string.local_archives_empty);
		emptyView.setGravity(Gravity.CENTER);
		emptyView.setVisibility(View.GONE);
		root.addView(emptyView, FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT);

		progressBar = new ProgressBar(root.getContext());
		FrameLayout.LayoutParams progressLayoutParams = new FrameLayout.LayoutParams(
				FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.CENTER);
		root.addView(progressBar, progressLayoutParams);
		return root;
	}

	@Override
	public void onActivityCreated(Bundle savedInstanceState) {
		super.onActivityCreated(savedInstanceState);
		((FragmentHandler) requireActivity()).setTitleSubtitle(getString(R.string.local_archives), null);
		loadArchives();
	}

	@Override
	public void onDestroyView() {
		loadGeneration++;
		recyclerView = null;
		emptyView = null;
		progressBar = null;
		adapter = null;
		super.onDestroyView();
	}

	@Override
	public void onCreateOptionsMenu(Menu menu, boolean primary) {
		menu.add(0, R.id.menu_reload, 0, R.string.reload)
				.setIcon(((FragmentHandler) requireActivity()).getActionBarIcon(R.attr.iconActionRefresh))
				.setShowAsAction(MenuItem.SHOW_AS_ACTION_IF_ROOM);
		menu.add(0, MENU_ADD_FOLDER, 1, R.string.local_archive_add_folder);
		menu.add(0, MENU_MANAGE_FOLDERS, 2, R.string.local_archive_manage_folders);
	}

	@Override
	public boolean onOptionsItemSelected(MenuItem item) {
		if (item.getItemId() == R.id.menu_reload) {
			loadArchives();
			return true;
		} else if (item.getItemId() == MENU_ADD_FOLDER) {
			Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE)
					.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION
							| Intent.FLAG_GRANT_WRITE_URI_PERMISSION
							| Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)
					.putExtra("android.content.extra.SHOW_ADVANCED", true);
			startActivityForResult(intent, C.REQUEST_CODE_LOCAL_ARCHIVE_TREE);
			return true;
		} else if (item.getItemId() == MENU_MANAGE_FOLDERS) {
			showFolderManager();
			return true;
		}
		return false;
	}

	@Override
	public void onActivityResult(int requestCode, int resultCode, Intent data) {
		super.onActivityResult(requestCode, resultCode, data);
		if (requestCode == C.REQUEST_CODE_LOCAL_ARCHIVE_TREE && resultCode == Activity.RESULT_OK
				&& data != null && data.getData() != null) {
			Uri uri = data.getData();
			try {
				int flags = data.getFlags() & (Intent.FLAG_GRANT_READ_URI_PERMISSION
						| Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
				requireContext().getContentResolver().takePersistableUriPermission(uri, flags);
				Preferences.addLocalArchiveUriTree(uri);
				ClickableToast.show(R.string.local_archive_folder_added);
				loadArchives();
			} catch (SecurityException e) {
				ClickableToast.show(R.string.no_access_to_memory);
			}
		}
	}

	private void showFolderManager() {
		List<String> uriStrings = Preferences.getLocalArchiveUriTrees();
		if (uriStrings.isEmpty()) {
			ClickableToast.show(R.string.local_archive_no_folders);
			return;
		}
		String[] names = new String[uriStrings.size()];
		boolean[] checked = new boolean[uriStrings.size()];
		for (int i = 0; i < names.length; i++) {
			names[i] = LocalArchiveManager.getTreeName(uriStrings.get(i));
		}
		new AlertDialog.Builder(requireContext()).setTitle(R.string.local_archive_manage_folders)
				.setMultiChoiceItems(names, checked, (dialog, which, value) -> checked[which] = value)
				.setNegativeButton(android.R.string.cancel, null)
				.setPositiveButton(R.string.local_archive_remove_folders, (dialog, which) -> {
					ArrayList<String> removed = new ArrayList<>();
					for (int i = 0; i < checked.length; i++) {
						if (checked[i]) {
							String uriString = uriStrings.get(i);
							removed.add(uriString);
							Uri uri = Uri.parse(uriString);
							try {
								for (UriPermission permission : requireContext().getContentResolver()
										.getPersistedUriPermissions()) {
									if (uri.equals(permission.getUri())) {
										int flags = (permission.isReadPermission()
												? Intent.FLAG_GRANT_READ_URI_PERMISSION : 0)
												| (permission.isWritePermission()
												? Intent.FLAG_GRANT_WRITE_URI_PERMISSION : 0);
										requireContext().getContentResolver().releasePersistableUriPermission(uri,
												flags);
										break;
									}
								}
							} catch (SecurityException e) {
								// The provider may already have revoked this permission.
							}
						}
					}
					if (!removed.isEmpty()) {
						Preferences.removeLocalArchiveUriTrees(removed);
						loadArchives();
					}
				}).show();
	}

	private void loadArchives() {
		int generation = ++loadGeneration;
		progressBar.setVisibility(View.VISIBLE);
		emptyView.setVisibility(View.GONE);
		ConcurrentUtils.PARALLEL_EXECUTOR.execute(() -> {
			List<LocalArchiveManager.Item> items = LocalArchiveManager.collect();
			PaddedRecyclerView recyclerView = this.recyclerView;
			if (recyclerView != null) {
				recyclerView.post(() -> {
					if (generation == loadGeneration && adapter != null) {
						adapter.setItems(items);
						progressBar.setVisibility(View.GONE);
						emptyView.setVisibility(items.isEmpty() ? View.VISIBLE : View.GONE);
					}
				});
			}
		});
	}

	private void openArchive(LocalArchiveManager.Item item) {
		((FragmentHandler) requireActivity()).pushFragment(new LocalArchiveViewerFragment(item.id));
	}

	private void confirmDeleteArchive(LocalArchiveManager.Item item) {
		new AlertDialog.Builder(requireContext())
				.setTitle(R.string.delete_local_archive)
				.setMessage(getString(R.string.delete_local_archive_confirmation__format, item.name))
				.setNegativeButton(android.R.string.cancel, null)
				.setPositiveButton(R.string.delete, (dialog, which) -> deleteArchive(item))
				.show();
	}

	private void deleteArchive(LocalArchiveManager.Item item) {
		int generation = ++loadGeneration;
		progressBar.setVisibility(View.VISIBLE);
		ConcurrentUtils.PARALLEL_EXECUTOR.execute(() -> {
			boolean success = LocalArchiveManager.delete(item);
			PaddedRecyclerView recyclerView = this.recyclerView;
			if (recyclerView != null) {
				recyclerView.post(() -> {
					if (generation == loadGeneration && isAdded()) {
						ClickableToast.show(success ? R.string.local_archive_deleted
								: R.string.local_archive_delete_failed);
						loadArchives();
					}
				});
			}
		});
	}

	private static class ArchiveAdapter extends RecyclerView.Adapter<RecyclerView.ViewHolder> {
		private interface Callback {
			void onClick(LocalArchiveManager.Item item);
			void onLongClick(LocalArchiveManager.Item item);
		}

		private final Callback callback;
		private final ArrayList<LocalArchiveManager.Item> items = new ArrayList<>();
		private PostDateFormatter postDateFormatter;

		private ArchiveAdapter(Callback callback) {
			this.callback = callback;
		}

		public void setItems(List<LocalArchiveManager.Item> items) {
			this.items.clear();
			this.items.addAll(items);
			notifyDataSetChanged();
		}

		@NonNull
		@Override
		public RecyclerView.ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
			if (postDateFormatter == null) {
				postDateFormatter = new PostDateFormatter(parent.getContext());
			}
			ViewFactory.TwoLinesViewHolder holder = ViewFactory.makeTwoLinesListItem(parent,
					ViewFactory.FEATURE_TEXT2_END);
			RecyclerView.ViewHolder viewHolder = new RecyclerView.ViewHolder(holder.view) {};
			viewHolder.itemView.setOnClickListener(view -> {
				int position = viewHolder.getAdapterPosition();
				if (position != RecyclerView.NO_POSITION) {
					callback.onClick(items.get(position));
				}
			});
			viewHolder.itemView.setOnLongClickListener(view -> {
				int position = viewHolder.getAdapterPosition();
				if (position != RecyclerView.NO_POSITION) {
					callback.onLongClick(items.get(position));
					return true;
				}
				return false;
			});
			return viewHolder;
		}

		@Override
		public void onBindViewHolder(@NonNull RecyclerView.ViewHolder holder, int position) {
			LocalArchiveManager.Item item = items.get(position);
			ViewFactory.TwoLinesViewHolder viewHolder = (ViewFactory.TwoLinesViewHolder) holder.itemView.getTag();
			viewHolder.text1.setText(item.name);
			int typeResId = item.hasHtml() && item.hasZip() ? R.string.local_archive_html_zip
					: item.hasHtml() ? R.string.local_archive_html : R.string.local_archive_zip;
			CharSequence type = holder.itemView.getContext().getText(typeResId);
			viewHolder.text2.setText(item.isExternal()
					? holder.itemView.getContext().getString(R.string.local_archive_type_source, type, item.sourceName)
					: type);
			viewHolder.text2End.setText(postDateFormatter.formatDate(item.lastModified));
		}

		@Override
		public int getItemCount() {
			return items.size();
		}
	}
}
