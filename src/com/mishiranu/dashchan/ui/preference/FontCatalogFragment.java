package com.mishiranu.dashchan.ui.preference;

import android.app.AlertDialog;
import android.content.Context;
import android.os.Bundle;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.TextView;
import androidx.annotation.NonNull;
import androidx.lifecycle.ViewModelProvider;
import androidx.recyclerview.widget.RecyclerView;
import chan.content.Chan;
import chan.http.HttpException;
import chan.http.HttpHolder;
import com.mishiranu.dashchan.R;
import com.mishiranu.dashchan.content.FontCatalogManager;
import com.mishiranu.dashchan.content.FontManager;
import com.mishiranu.dashchan.content.MainApplication;
import com.mishiranu.dashchan.content.async.HttpHolderTask;
import com.mishiranu.dashchan.content.async.TaskViewModel;
import com.mishiranu.dashchan.content.model.ErrorItem;
import com.mishiranu.dashchan.ui.FragmentHandler;
import com.mishiranu.dashchan.util.ConcurrentUtils;
import com.mishiranu.dashchan.util.ResourceUtils;
import com.mishiranu.dashchan.widget.ClickableToast;
import com.mishiranu.dashchan.widget.ThemeEngine;
import java.io.IOException;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import org.json.JSONException;

public class FontCatalogFragment extends BaseListFragment {
	@Override
	public void onActivityCreated(Bundle savedInstanceState) {
		super.onActivityCreated(savedInstanceState);
		((FragmentHandler) requireActivity()).setTitleSubtitle(getString(R.string.font_catalog), null);
		RecyclerView recyclerView = getRecyclerView();
		Adapter adapter = new Adapter(recyclerView.getContext(), this::handleFontClick);
		recyclerView.setAdapter(adapter);
		setErrorText(getString(R.string.font_catalog_loading));

		CatalogViewModel catalogViewModel = new ViewModelProvider(this).get(CatalogViewModel.class);
		catalogViewModel.observe(getViewLifecycleOwner(), result -> {
			List<FontCatalogManager.CatalogFont> fonts = result.fonts != null
					? result.fonts : Collections.emptyList();
			adapter.setItems(fonts);
			setErrorText(result.error != null ? result.error.toString() : fonts.isEmpty()
					? getString(R.string.font_catalog_empty) : null);
		});
		if (!catalogViewModel.hasTaskOrValue()) {
			ReadCatalogTask task = new ReadCatalogTask(catalogViewModel);
			catalogViewModel.attach(task);
			task.execute(ConcurrentUtils.PARALLEL_EXECUTOR);
		}

		DownloadViewModel downloadViewModel = new ViewModelProvider(this).get(DownloadViewModel.class);
		downloadViewModel.observe(getViewLifecycleOwner(), result -> {
			if (result.error != null) {
				ClickableToast.show(result.error);
			} else {
				ClickableToast.show(R.string.font_catalog_installed);
				requireActivity().recreate();
			}
		});
	}

	private void handleFontClick(FontCatalogManager.CatalogFont font) {
		if (FontManager.isCatalogFontInstalled(requireContext(), font.id)) {
			FontManager.selectCatalogFont(requireContext(), font.id);
			requireActivity().recreate();
			return;
		}
		new AlertDialog.Builder(requireContext())
				.setTitle(font.title)
				.setMessage(getString(R.string.font_download_confirmation__format,
						font.license, formatSize(font.fileSize)))
				.setPositiveButton(R.string.download_file, (dialog, which) -> {
					DownloadViewModel viewModel = new ViewModelProvider(this).get(DownloadViewModel.class);
					if (!viewModel.hasTaskOrValue()) {
						DownloadTask task = new DownloadTask(viewModel, font);
						viewModel.attach(task);
						task.execute(ConcurrentUtils.PARALLEL_EXECUTOR);
						ClickableToast.show(R.string.font_catalog_downloading);
					}
				})
				.setNegativeButton(android.R.string.cancel, null)
				.show();
	}

	private static String formatSize(long bytes) {
		return String.format(Locale.getDefault(), "%.1f MB", bytes / 1024f / 1024f);
	}

	private interface ClickCallback {
		void onClick(FontCatalogManager.CatalogFont font);
	}

	private static class Adapter extends RecyclerView.Adapter<Adapter.ViewHolder> {
		private final Context context;
		private final ClickCallback callback;
		private List<FontCatalogManager.CatalogFont> items = Collections.emptyList();

		private Adapter(Context context, ClickCallback callback) {
			this.context = context;
			this.callback = callback;
		}

		public void setItems(List<FontCatalogManager.CatalogFont> items) {
			this.items = items;
			notifyDataSetChanged();
		}

		@Override
		public int getItemCount() {
			return items.size();
		}

		@NonNull
		@Override
		public ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
			float density = ResourceUtils.obtainDensity(parent);
			int horizontal = Math.round(16f * density);
			int vertical = Math.round(12f * density);
			LinearLayout layout = new LinearLayout(parent.getContext());
			layout.setLayoutParams(new RecyclerView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
					ViewGroup.LayoutParams.WRAP_CONTENT));
			layout.setOrientation(LinearLayout.VERTICAL);
			layout.setPadding(horizontal, vertical, horizontal, vertical);
			layout.setClickable(true);
			layout.setFocusable(true);
			layout.setBackground(ResourceUtils.getDrawable(parent.getContext(),
					android.R.attr.selectableItemBackground, 0));

			TextView title = new TextView(parent.getContext());
			title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f);
			title.setTextColor(ThemeEngine.getTheme(parent.getContext()).post);
			title.setSingleLine(true);
			title.setEllipsize(TextUtils.TruncateAt.END);
			FontManager.applyTypeface(title);
			layout.addView(title, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
					ViewGroup.LayoutParams.WRAP_CONTENT));

			TextView summary = new TextView(parent.getContext());
			summary.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f);
			summary.setTextColor(ThemeEngine.getTheme(parent.getContext()).meta);
			summary.setMaxLines(2);
			summary.setEllipsize(TextUtils.TruncateAt.END);
			FontManager.applyTypeface(summary);
			layout.addView(summary, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
					ViewGroup.LayoutParams.WRAP_CONTENT));
			return new ViewHolder(layout, title, summary);
		}

		@Override
		public void onBindViewHolder(@NonNull ViewHolder holder, int position) {
			FontCatalogManager.CatalogFont font = items.get(position);
			holder.title.setText(font.title);
			boolean installed = FontManager.isCatalogFontInstalled(context, font.id);
			holder.summary.setText(context.getString(R.string.font_catalog_item__format,
					font.license, formatSize(font.fileSize), installed
							? context.getString(R.string.font_catalog_installed_state)
							: context.getString(R.string.download_file)));
			holder.itemView.setOnClickListener(v -> callback.onClick(font));
		}

		private static class ViewHolder extends RecyclerView.ViewHolder {
			public final TextView title;
			public final TextView summary;

			private ViewHolder(View itemView, TextView title, TextView summary) {
				super(itemView);
				this.title = title;
				this.summary = summary;
			}
		}
	}

	private static final class Result {
		public final ErrorItem error;
		public final List<FontCatalogManager.CatalogFont> fonts;

		private Result(ErrorItem error, List<FontCatalogManager.CatalogFont> fonts) {
			this.error = error;
			this.fonts = fonts;
		}
	}

	public static class CatalogViewModel extends TaskViewModel<ReadCatalogTask, Result> {}
	public static class DownloadViewModel extends TaskViewModel<DownloadTask, Result> {}

	private static class ReadCatalogTask extends HttpHolderTask<Void, Result> {
		private final CatalogViewModel viewModel;

		private ReadCatalogTask(CatalogViewModel viewModel) {
			super(Chan.getFallback());
			this.viewModel = viewModel;
		}

		@Override
		protected Result run(HttpHolder holder) {
			try {
				return new Result(null, FontCatalogManager.readCatalog(holder));
			} catch (HttpException e) {
				return new Result(e.getErrorItemAndHandle(), null);
			} catch (JSONException | RuntimeException e) {
				return new Result(new ErrorItem(ErrorItem.Type.INVALID_DATA_FORMAT), null);
			}
		}

		@Override
		protected void onComplete(Result result) {
			viewModel.handleResult(result);
		}
	}

	private static class DownloadTask extends HttpHolderTask<Void, Result> {
		private final DownloadViewModel viewModel;
		private final FontCatalogManager.CatalogFont font;

		private DownloadTask(DownloadViewModel viewModel, FontCatalogManager.CatalogFont font) {
			super(Chan.getFallback());
			this.viewModel = viewModel;
			this.font = font;
		}

		@Override
		protected Result run(HttpHolder holder) {
			try {
				FontCatalogManager.downloadAndInstall(MainApplication.getInstance(), holder, font);
				return new Result(null, Collections.emptyList());
			} catch (HttpException e) {
				return new Result(e.getErrorItemAndHandle(), null);
			} catch (IOException | RuntimeException e) {
				return new Result(new ErrorItem(ErrorItem.Type.DOWNLOAD), null);
			}
		}

		@Override
		protected void onComplete(Result result) {
			viewModel.handleResult(result);
		}
	}
}
