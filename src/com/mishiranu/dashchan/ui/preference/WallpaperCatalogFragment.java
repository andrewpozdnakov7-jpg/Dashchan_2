package com.mishiranu.dashchan.ui.preference;

import android.app.AlertDialog;
import android.content.Context;
import android.net.Uri;
import android.os.Bundle;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import androidx.annotation.NonNull;
import androidx.lifecycle.ViewModelProvider;
import androidx.recyclerview.widget.GridLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import chan.content.Chan;
import chan.http.HttpException;
import chan.http.HttpHolder;
import chan.http.HttpRequest;
import chan.http.HttpResponse;
import chan.util.StringUtils;
import com.mishiranu.dashchan.BuildConfig;
import com.mishiranu.dashchan.R;
import com.mishiranu.dashchan.content.ImageLoader;
import com.mishiranu.dashchan.content.MainApplication;
import com.mishiranu.dashchan.content.WallpaperManager;
import com.mishiranu.dashchan.content.async.HttpHolderTask;
import com.mishiranu.dashchan.content.async.TaskViewModel;
import com.mishiranu.dashchan.content.model.ErrorItem;
import com.mishiranu.dashchan.ui.FragmentHandler;
import com.mishiranu.dashchan.util.ConcurrentUtils;
import com.mishiranu.dashchan.util.ResourceUtils;
import com.mishiranu.dashchan.widget.CardView;
import com.mishiranu.dashchan.widget.ClickableToast;
import com.mishiranu.dashchan.widget.DividerItemDecoration;
import com.mishiranu.dashchan.widget.ThemeEngine;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

public class WallpaperCatalogFragment extends BaseListFragment {
	private static final int MAX_CATALOG_ITEMS = 100;

	@Override
	public void onActivityCreated(Bundle savedInstanceState) {
		super.onActivityCreated(savedInstanceState);
		((FragmentHandler) requireActivity()).setTitleSubtitle(getString(R.string.wallpaper_catalog), null);
		RecyclerView recyclerView = getRecyclerView();
		recyclerView.setLayoutManager(new GridLayoutManager(recyclerView.getContext(), 2));
		Adapter adapter = new Adapter(recyclerView.getContext(), this::confirmDownload);
		recyclerView.setAdapter(adapter);
		setErrorText(getString(R.string.wallpaper_catalog_loading));

		CatalogViewModel catalogViewModel = new ViewModelProvider(this).get(CatalogViewModel.class);
		catalogViewModel.observe(getViewLifecycleOwner(), result -> {
			List<Wallpaper> wallpapers = result.wallpapers != null ? result.wallpapers : Collections.emptyList();
			adapter.setItems(wallpapers);
			setErrorText(result.error != null ? result.error.toString() : wallpapers.isEmpty()
					? getString(R.string.wallpaper_catalog_empty) : null);
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
				ClickableToast.show(R.string.wallpaper_installed);
				requireActivity().recreate();
			}
		});
	}

	@Override
	protected void setListPadding(RecyclerView recyclerView) {
		int padding = Math.round(6f * ResourceUtils.obtainDensity(recyclerView));
		recyclerView.setPadding(padding, padding, padding, padding);
	}

	@Override
	protected DividerItemDecoration.Configuration configureDivider
			(DividerItemDecoration.Configuration configuration, int position) {
		return configuration.need(false);
	}

	private void confirmDownload(Wallpaper wallpaper) {
		new AlertDialog.Builder(requireContext())
				.setTitle(wallpaper.title)
				.setMessage(getString(R.string.wallpaper_download_confirmation__format,
						wallpaper.author, wallpaper.license, formatSize(wallpaper.fileSize)))
				.setPositiveButton(R.string.download_file, (dialog, which) -> {
					DownloadViewModel viewModel = new ViewModelProvider(this).get(DownloadViewModel.class);
					if (!viewModel.hasTaskOrValue()) {
						DownloadTask task = new DownloadTask(viewModel, wallpaper);
						viewModel.attach(task);
						task.execute(ConcurrentUtils.PARALLEL_EXECUTOR);
						ClickableToast.show(R.string.wallpaper_downloading);
					}
				})
				.setNegativeButton(android.R.string.cancel, null)
				.show();
	}

	private static String formatSize(long bytes) {
		return String.format(Locale.getDefault(), "%.1f MB", bytes / 1024f / 1024f);
	}

	private static boolean isHttps(Uri uri) {
		return uri != null && "https".equalsIgnoreCase(uri.getScheme()) && !StringUtils.isEmpty(uri.getHost());
	}

	private interface ClickCallback {
		void onClick(Wallpaper wallpaper);
	}

	private static class Adapter extends RecyclerView.Adapter<Adapter.ViewHolder> {
		private final Context context;
		private final ClickCallback callback;
		private List<Wallpaper> items = Collections.emptyList();

		private Adapter(Context context, ClickCallback callback) {
			this.context = context;
			this.callback = callback;
		}

		public void setItems(List<Wallpaper> items) {
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
			int margin = Math.round(6f * density);
			int padding = Math.round(10f * density);
			CardView card = new CardView(parent.getContext());
			card.setBackgroundColor(ThemeEngine.getTheme(parent.getContext()).card);
			RecyclerView.LayoutParams cardParams = new RecyclerView.LayoutParams(
					ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
			cardParams.setMargins(margin, margin, margin, margin);
			card.setLayoutParams(cardParams);

			LinearLayout content = new LinearLayout(parent.getContext());
			content.setOrientation(LinearLayout.VERTICAL);
			card.addView(content, ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
			ImageView preview = new ImageView(parent.getContext());
			preview.setScaleType(ImageView.ScaleType.CENTER_CROP);
			preview.setBackgroundColor(ThemeEngine.getTheme(parent.getContext()).window);
			content.addView(preview, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
					Math.round(180f * density)));

			TextView title = new TextView(parent.getContext());
			title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f);
			title.setTextColor(ThemeEngine.getTheme(parent.getContext()).post);
			title.setSingleLine(true);
			title.setEllipsize(TextUtils.TruncateAt.END);
			title.setGravity(Gravity.START);
			title.setPadding(padding, padding, padding, 0);
			content.addView(title, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
					ViewGroup.LayoutParams.WRAP_CONTENT));

			TextView summary = new TextView(parent.getContext());
			summary.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f);
			summary.setTextColor(ThemeEngine.getTheme(parent.getContext()).meta);
			summary.setMaxLines(2);
			summary.setEllipsize(TextUtils.TruncateAt.END);
			summary.setPadding(padding, Math.round(2f * density), padding, padding);
			content.addView(summary, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
					ViewGroup.LayoutParams.WRAP_CONTENT));
			return new ViewHolder(card, preview, title, summary);
		}

		@Override
		public void onBindViewHolder(@NonNull ViewHolder holder, int position) {
			Wallpaper wallpaper = items.get(position);
			holder.title.setText(wallpaper.title);
			holder.preview.setContentDescription(wallpaper.title);
			holder.summary.setText(context.getString(R.string.wallpaper_catalog_item__format,
					wallpaper.author, wallpaper.license, formatSize(wallpaper.fileSize)));
			ImageLoader.getInstance().cancel(holder.preview);
			holder.preview.setImageDrawable(null);
			ImageLoader.getInstance().loadImage(Chan.getFallback(), wallpaper.previewUri, false, holder.preview);
			holder.itemView.setOnClickListener(v -> callback.onClick(wallpaper));
		}

		@Override
		public void onViewRecycled(@NonNull ViewHolder holder) {
			ImageLoader.getInstance().cancel(holder.preview);
			holder.preview.setImageDrawable(null);
			super.onViewRecycled(holder);
		}

		private static class ViewHolder extends RecyclerView.ViewHolder {
			public final ImageView preview;
			public final TextView title;
			public final TextView summary;

			private ViewHolder(View itemView, ImageView preview, TextView title, TextView summary) {
				super(itemView);
				this.preview = preview;
				this.title = title;
				this.summary = summary;
			}
		}
	}

	private static final class Wallpaper {
		public final String id;
		public final String title;
		public final String author;
		public final String license;
		public final Uri imageUri;
		public final Uri previewUri;
		public final String sha256;
		public final long fileSize;

		private Wallpaper(String id, String title, String author, String license, Uri imageUri,
				Uri previewUri, String sha256, long fileSize) {
			this.id = id;
			this.title = title;
			this.author = author;
			this.license = license;
			this.imageUri = imageUri;
			this.previewUri = previewUri;
			this.sha256 = sha256;
			this.fileSize = fileSize;
		}
	}

	private static final class Result {
		public final ErrorItem error;
		public final List<Wallpaper> wallpapers;

		private Result(ErrorItem error, List<Wallpaper> wallpapers) {
			this.error = error;
			this.wallpapers = wallpapers;
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
				Uri uri = Chan.getFallback().locator.setSchemeIfEmpty(Uri.parse(BuildConfig.URI_WALLPAPERS), null);
				JSONObject root = new JSONObject(new HttpRequest(uri, holder).perform().readString());
				if (root.optInt("schemaVersion") != 1) {
					throw new JSONException("Unsupported schema version");
				}
				JSONArray array = root.getJSONArray("wallpapers");
				if (array.length() > MAX_CATALOG_ITEMS) {
					throw new JSONException("Catalog is too large");
				}
				ArrayList<Wallpaper> wallpapers = new ArrayList<>();
				for (int i = 0; i < array.length(); i++) {
					JSONObject object = array.getJSONObject(i);
					String id = object.getString("id");
					String title = object.getString("title");
					String author = object.getString("author");
					String license = object.getString("license");
					Uri imageUri = Uri.parse(object.getString("imageUrl"));
					Uri previewUri = Uri.parse(object.getString("previewUrl"));
					String sha256 = object.getString("sha256").toLowerCase(Locale.US);
					long fileSize = object.getLong("fileSizeBytes");
					if (StringUtils.isEmpty(id) || StringUtils.isEmpty(title) || StringUtils.isEmpty(author)
							|| StringUtils.isEmpty(license) || !isHttps(imageUri) || !isHttps(previewUri)
							|| !sha256.matches("[a-f0-9]{64}") || fileSize <= 0L
							|| fileSize > WallpaperManager.MAX_FILE_SIZE) {
						throw new JSONException("Invalid wallpaper entry");
					}
					wallpapers.add(new Wallpaper(id, title, author, license, imageUri, previewUri,
							sha256, fileSize));
				}
				return new Result(null, wallpapers);
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
		private final Wallpaper wallpaper;

		private DownloadTask(DownloadViewModel viewModel, Wallpaper wallpaper) {
			super(Chan.getFallback());
			this.viewModel = viewModel;
			this.wallpaper = wallpaper;
		}

		@Override
		protected Result run(HttpHolder holder) {
			HttpResponse response = null;
			try {
				response = new HttpRequest(wallpaper.imageUri, holder).perform();
				try (InputStream input = response.open()) {
					WallpaperManager.install(MainApplication.getInstance(), input, wallpaper.fileSize,
							wallpaper.sha256, wallpaper.id, wallpaper.title);
				}
				return new Result(null, Collections.emptyList());
			} catch (HttpException e) {
				return new Result(e.getErrorItemAndHandle(), null);
			} catch (IOException | RuntimeException e) {
				return new Result(new ErrorItem(ErrorItem.Type.DOWNLOAD), null);
			} finally {
				if (response != null) {
					response.cleanupAndDisconnect();
				}
			}
		}

		@Override
		protected void onComplete(Result result) {
			viewModel.handleResult(result);
		}
	}
}
