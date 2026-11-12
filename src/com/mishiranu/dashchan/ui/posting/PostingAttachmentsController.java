package com.mishiranu.dashchan.ui.posting;

import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.ClipData;
import android.content.Context;
import android.content.Intent;
import android.content.res.ColorStateList;
import android.graphics.Bitmap;
import android.graphics.Rect;
import android.net.Uri;
import android.provider.DocumentsContract;
import android.text.TextUtils;
import android.util.DisplayMetrics;
import android.util.Pair;
import android.view.DragEvent;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import androidx.core.widget.TextViewCompat;
import androidx.lifecycle.ViewModelProvider;
import chan.content.Chan;
import chan.content.ChanConfiguration;
import chan.content.ChanPerformer;
import chan.util.CommonUtils;
import chan.util.StringUtils;
import com.mishiranu.dashchan.media.compression.VideoCompressionJob;
import com.mishiranu.dashchan.media.compression.VideoCompressionService;
import com.mishiranu.dashchan.media.compression.CompressionSettings;
import com.mishiranu.dashchan.content.security.ProfileGeneration;
import com.mishiranu.dashchan.widget.MotionDialogBuilder;
import com.mishiranu.dashchan.C;
import com.mishiranu.dashchan.R;
import com.mishiranu.dashchan.content.MainApplication;
import com.mishiranu.dashchan.content.Preferences;
import com.mishiranu.dashchan.content.async.ExecutorTask;
import com.mishiranu.dashchan.content.async.TaskViewModel;
import com.mishiranu.dashchan.content.model.FileHolder;
import com.mishiranu.dashchan.content.storage.DraftsStorage;
import com.mishiranu.dashchan.graphics.RoundedCornersDrawable;
import com.mishiranu.dashchan.graphics.TransparentTileDrawable;
import com.mishiranu.dashchan.media.JpegData;
import com.mishiranu.dashchan.media.PngData;
import com.mishiranu.dashchan.media.VideoPlayer;
import com.mishiranu.dashchan.ui.gallery.GalleryOverlay;
import com.mishiranu.dashchan.ui.posting.dialog.AttachmentOptionsDialog;
import com.mishiranu.dashchan.ui.posting.dialog.AttachmentRatingDialog;
import com.mishiranu.dashchan.ui.posting.dialog.AttachmentWarningDialog;
import com.mishiranu.dashchan.util.ConcurrentUtils;
import com.mishiranu.dashchan.util.GraphicsUtils;
import com.mishiranu.dashchan.util.ResourceUtils;
import com.mishiranu.dashchan.util.ViewUtils;
import com.mishiranu.dashchan.widget.ClickableToast;
import com.mishiranu.dashchan.widget.ThemeEngine;
import com.mishiranu.dashchan.widget.PostingAttachmentSummaryView;
import com.mishiranu.dashchan.widget.UriPasteEditText;
import java.io.File;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.concurrent.Executor;

/**
 * Attachment state owner with a view-lifetime UI adapter. detachView releases all attachment
 * Views, thumbnail tasks and Host references; retained import work belongs to the ViewModel.
 * No send/captcha orchestration.
 */
public final class PostingAttachmentsController {
	interface Host {
		Context requireContext();
		androidx.fragment.app.FragmentManager getChildFragmentManager();
		androidx.fragment.app.FragmentManager getParentFragmentManager();
		void requestStorage();
		void invalidateOptionsMenu();
		void resizeCommentAfterAttachmentChange();
		void saveDraftAfterAttachmentChange();
		void updateSendButtonState();
		void launchAttachmentPicker(Intent intent);
		void launchImageEditor(Intent intent);
		void onAttachmentDiagnostic(String event);
	}

	private Host host;
	private LinearLayout attachmentContainer;
	private ScrollView scrollView;
	private Button sendButton;
	private int attachmentColumnCount;
	private PostingAttachmentsAppearance attachmentAppearance;
	private ChanConfiguration.Posting postingConfiguration;
	private List<Pair<String, String>> attachmentRatingItems;
	private final ArrayList<AttachmentHolder> attachments = new ArrayList<>();
	private static final Executor VIDEO_THUMBNAIL_EXECUTOR = ConcurrentUtils
			.newSingleThreadPool(3000, "PostingThumbnail", null);
	private final HashMap<AttachmentHolder, VideoThumbnailTask> videoThumbnailTasks = new HashMap<>();
	private AttachmentHolder draggedAttachment;
	private AttachmentHolder attachmentDragTarget;
	private boolean attachmentImportInProgress;
	private AttachmentImportViewModel attachmentImportViewModel;
	private Runnable processingNotice;
	private PostingCompressionModel compressionModel;
	private androidx.fragment.app.Fragment compressionOwner;
	private TextView compressionStatus;
	private PostingAttachmentSummaryView attachmentSummary;
	private PostingLimitsModel limitsModel;
	private final java.util.IdentityHashMap<AttachmentHolder, VideoConversionBadge> conversionBadges = new java.util.IdentityHashMap<>();
	private android.app.AlertDialog compressionDialog;
	private String compressionChan, compressionBoard;
	private boolean compressionRendering;
	private boolean compressionWasBusy;
	private String boundConversionState = "";
	private final Runnable compressionRefresh = this::scheduleCompression;
	private final java.util.function.Function<String, FileHolder> attachmentFileResolver;

	public PostingAttachmentsController() {
		this(hash -> DraftsStorage.getInstance().getAttachmentDraftFileHolder(hash));
	}

	// Allows controller contracts to supply files without changing the app's draft storage.
	PostingAttachmentsController(java.util.function.Function<String, FileHolder> attachmentFileResolver) {
		this.attachmentFileResolver = attachmentFileResolver;
	}

	public void attachView(Host host, LinearLayout container, ScrollView scrollView, Button sendButton,
			int columnCount) {
		if (this.host != null) throw new IllegalStateException("Attachment view already attached");
		this.host = host;
		attachmentContainer = container;
		this.scrollView = scrollView;
		this.sendButton = sendButton;
		attachmentColumnCount = columnCount;
		attachmentSummary = container.getRootView().findViewById(R.id.posting_attachment_summary);
		if (attachmentSummary != null) ViewUtils.setTextSizeScaled(attachmentSummary, 12);
		compressionStatus = container.getRootView().findViewById(R.id.compression_status);
		if (compressionStatus != null) ThemeEngine.applyStyle(compressionStatus);
		attachments.clear();
		attachmentAppearance = PostingAttachmentsAppearance.create(container, scrollView, new PostingAttachmentsAppearance.Host() {
			@Override public List<AttachmentHolder> attachments() { return attachments; }
			@Override public void move(AttachmentHolder holder, int index) { moveAttachment(holder, index); }
			@Override public void rebuild() { invalidateAttachments(true); }
		});
		container.setOnDragListener(attachmentContainerDragListener);
	}

	public void detachView() {
		for (VideoConversionBadge badge : conversionBadges.values()) badge.release();
		conversionBadges.clear();
		boundConversionState = "";
		if (attachmentContainer != null) attachmentContainer.removeCallbacks(compressionRefresh);
		if (compressionDialog != null) compressionDialog.dismiss();
		compressionDialog = null; compressionStatus = null; compressionOwner = null; compressionModel = null;
		attachmentSummary = null; limitsModel = null;
		clearAttachmentDragState();
		if (attachmentAppearance != null) { attachmentAppearance.close(); attachmentAppearance = null; }
		for (VideoThumbnailTask task : videoThumbnailTasks.values()) task.cancel();
		videoThumbnailTasks.clear();
		if (sendButton != null && processingNotice != null) sendButton.removeCallbacks(processingNotice);
		processingNotice = null;
		if (attachmentContainer != null) attachmentContainer.setOnDragListener(null);
		attachmentContainer = null;
		scrollView = null;
		sendButton = null;
		attachmentImportViewModel = null;
		attachments.clear();
		host = null;
		// Import work remains owned by the Fragment's retained ViewModel, not this view.
	}

	public void bindImportState(androidx.fragment.app.Fragment owner) {
		attachmentImportViewModel = new ViewModelProvider(owner).get(AttachmentImportViewModel.class);
		setAttachmentImportInProgress(attachmentImportViewModel.hasTaskOrValue());
		attachmentImportViewModel.observe(owner.getViewLifecycleOwner(), this::onAttachmentImportComplete);
	}

	public boolean isImportInProgress() {
		return attachmentImportInProgress || isCompressionInProgress();
	}

	public boolean isAttachmentImportInProgress() {
		return attachmentImportInProgress;
	}

	public void resumeCompression() {
		if (limitsModel != null && postingConfiguration != null && postingConfiguration.attachmentCount > 0) {
			limitsModel.load(Chan.get(compressionChan), compressionBoard);
		}
		scheduleCompression();
		updateAttachmentSummary();
	}
	public boolean isCompressionInProgress() {
		return compressionModel != null && compressionModel.job != null && compressionModel.job.busy();
	}
	public void bindCompression(androidx.fragment.app.Fragment owner, String chan, String board) {
		compressionOwner = owner; compressionChan = chan; compressionBoard = board;
		compressionModel = new ViewModelProvider(owner).get(PostingCompressionModel.class);
		compressionModel.state.observe(owner.getViewLifecycleOwner(), job -> renderCompression());
		limitsModel = new ViewModelProvider(owner).get(PostingLimitsModel.class);
		limitsModel.totalBytes.observe(owner.getViewLifecycleOwner(), bytes -> updateAttachmentSummary());
		if (postingConfiguration != null && postingConfiguration.attachmentCount > 0) {
			limitsModel.load(Chan.get(chan), board);
		}
		scheduleCompression();
	}
	private String compressionSignature() {
		StringBuilder signature = new StringBuilder(compressionChan).append('/').append(compressionBoard)
				.append(':').append(CompressionSettings.signature());
		for (AttachmentHolder holder : attachments) signature.append('\n').append(holder.hash).append(':').append(holder.name);
		if (compressionModel != null) signature.append("\nforced:").append(new java.util.TreeSet<>(compressionModel.forcedVideos));
		return signature.toString();
	}
	public boolean canConvertVideoAttachment(int index) {
		AttachmentHolder holder = getAttachmentHolder(index);
		return CompressionSettings.enabled() && compressionModel != null && !isImportInProgress()
				&& holder != null && Chan.getFallback().locator.isVideoExtension(holder.name);
	}
	public boolean hasVideoConversionDetails(int index) {
		AttachmentHolder holder = getAttachmentHolder(index);
		if (holder == null || compressionModel == null || compressionModel.job == null || !CompressionSettings.enabled()) return false;
		for (VideoCompressionJob.Source source : compressionModel.job.sources) {
			if (source.hash.equals(holder.hash) && Chan.getFallback().locator.isVideoExtension(source.name)) return true;
		}
		return false;
	}
	public void showVideoConversionDetails(int index, String hash) {
		AttachmentHolder holder = getAttachmentHolder(index);
		if (holder != null && hash.equals(holder.hash) && hasVideoConversionDetails(index)) showCompressionActions();
	}
	public int[] getVideoConversionPosition(int index) {
		AttachmentHolder holder = getAttachmentHolder(index);
		if (holder != null && compressionModel != null && compressionModel.job != null && compressionModel.job.busy()) {
			VideoCompressionJob job = compressionModel.job;
			int position = job.queue.indexOf(conversionSource(job, holder));
			if (position >= 0) return new int[] {position + 1, job.queue.size()};
		}
		return new int[] {0, 0};
	}
	private VideoCompressionJob.Source conversionSource(VideoCompressionJob job, AttachmentHolder holder) {
		// A retained job owns Source identities, not the grid's current position. Distinguish duplicate inputs too.
		int occurrence = 0;
		for (AttachmentHolder other : attachments) {
			if (other == holder) break;
			if (TextUtils.equals(other.hash, holder.hash) && TextUtils.equals(other.name, holder.name)) occurrence++;
		}
		for (VideoCompressionJob.Source source : job.sources) {
			if (TextUtils.equals(source.hash, holder.hash) && TextUtils.equals(source.name, holder.name) && occurrence-- == 0) return source;
		}
		return null;
	}
	public void convertVideoAttachment(int index, String sourceHash, String sourceName) {
		AttachmentHolder holder = getAttachmentHolder(index);
		if (!canConvertVideoAttachment(index) || !AttachmentResultGuard.matches(sourceHash, sourceName,
				holder != null ? holder.hash : null, holder != null ? holder.name : null)) {
			ClickableToast.show(R.string.compression_attachment_changed); return;
		}
		attachmentsChanged();
		compressionModel.forcedVideos.add(holder.hash);
		compressionModel.signature = null;
		scheduleCompression();
	}
	private void attachmentsChanged() {
		updateAttachmentSummary();
		pruneConversionBadges();
		host.saveDraftAfterAttachmentChange();
		if (attachmentContainer != null) { attachmentContainer.removeCallbacks(compressionRefresh); attachmentContainer.post(compressionRefresh); }
	}
	private void scheduleCompression() {
		if (host == null || compressionModel == null || attachmentImportInProgress) return;
		if (!CompressionSettings.enabled()) {
			compressionModel.forcedVideos.clear();
			compressionModel.clear(); if (compressionStatus != null) compressionStatus.setVisibility(View.GONE); return;
		}
		java.util.Set<String> current = new java.util.HashSet<>();
		for (AttachmentHolder holder : attachments) current.add(holder.hash);
		compressionModel.forcedVideos.retainAll(current);
		String signature = compressionSignature();
		if (signature.equals(compressionModel.signature)) { renderCompression(); return; }
		ArrayList<VideoCompressionJob.Source> sources = new ArrayList<>();
		boolean hasVideo = false;
		for (AttachmentHolder holder : attachments) {
			File file = DraftsStorage.getInstance().getAttachmentDraftFile(holder.hash);
			if (file != null) {
				sources.add(new VideoCompressionJob.Source(holder.hash, holder.name, file));
				hasVideo |= Chan.getFallback().locator.isVideoExtension(holder.name);
			}
		}
		if (!hasVideo) { compressionModel.clear(); if (compressionStatus != null) compressionStatus.setVisibility(View.GONE); return; }
		try {
			long profile = ProfileGeneration.current(requireContext());
			Collection<String> types = postingConfiguration.attachmentMimeTypes;
			boolean mp4 = types.contains("video/mp4") || types.contains("video/*") || types.contains("*/*");
			compressionModel.replace(new VideoCompressionJob(requireContext(), sources, profile,
					compressionChan, compressionBoard, mp4, new java.util.HashSet<>(compressionModel.forcedVideos)), signature);
		} catch (java.io.IOException e) { ClickableToast.show(R.string.compression_start_failed); }
	}
	private VideoCompressionJob.Result compressed(AttachmentHolder holder) {
		if (compressionModel == null || compressionModel.job == null
				|| !CompressionSettings.enabled()
				|| !compressionSignature().equals(compressionModel.signature)) return null;
		VideoCompressionJob job = compressionModel.job;
		return job.phase == VideoCompressionJob.Phase.DONE ? job.results.get(holder.hash) : null;
	}
	private void renderCompression() {
		if (host == null || compressionModel == null || compressionStatus == null || compressionRendering) return;
		compressionRendering = true;
		try {
			VideoCompressionJob job = compressionModel.job;
			String fileState = job != null && job.phase == VideoCompressionJob.Phase.DONE && CompressionSettings.enabled() ? job.id : "";
			if (!fileState.equals(boundConversionState)) {
				boundConversionState = fileState;
				for (AttachmentHolder holder : attachments) bindAttachmentFile(holder, resolveAttachmentFile(holder.hash));
			}
			renderConversionBadges(job);
			com.mishiranu.dashchan.widget.PostingFormAppearance.setConversionStatus(attachmentContainer,
					job != null && (job.busy() || job.phase == VideoCompressionJob.Phase.ERROR) ? compressionDescription(job) : "");
		} finally {
			compressionRendering = false;
			updateAttachmentSummary();
			boolean busy = isCompressionInProgress();
			if (busy != compressionWasBusy) { compressionWasBusy = busy; host.invalidateOptionsMenu(); }
			host.updateSendButtonState();
		}
	}
	private void showCompressionActions() {
		if (host == null || compressionModel == null || compressionModel.job == null || compressionDialog != null
				|| compressionOwner == null || !compressionOwner.isResumed()) return;
		VideoCompressionJob job = compressionModel.job;
		MotionDialogBuilder builder = MotionDialogBuilder.standard(requireContext());
		builder.setTitle(R.string.compression_title);
		if (job.busy()) {
			builder.setMessage(compressionDescription(job)).setPositiveButton(android.R.string.cancel, (dialog, which) ->
					VideoCompressionService.cancel(job))
					.setNegativeButton(android.R.string.ok, null);
		} else {
			StringBuilder details = new StringBuilder(compressionDescription(job));
			for (VideoCompressionJob.Result result : job.results.values()) {
				details.append('\n').append(requireContext().getString(R.string.compression_result,
						StringUtils.formatFileSize(result.before, false), StringUtils.formatFileSize(result.after, false), result.codec));
			}
			builder.setMessage(details).setNegativeButton(android.R.string.ok, null);
			if (!job.results.isEmpty()) builder.setPositiveButton(R.string.slooop_converter_ui_restore_many, (dialog, which) ->
					VideoCompressionService.cancel(job));
			else builder.setPositiveButton(R.string.compression_restart, (dialog, which) -> { compressionModel.signature = null; scheduleCompression(); });
		}
		compressionDialog = builder.create();
		compressionDialog.setOnDismissListener(dialog -> compressionDialog = null); compressionDialog.show();
	}

	private String compressionDescription(VideoCompressionJob job) {
		if (job.phase == VideoCompressionJob.Phase.ERROR) return getString(job.error);
		if (job.phase == VideoCompressionJob.Phase.CANCELLED) return getString(R.string.compression_cancelled);
		if (job.phase == VideoCompressionJob.Phase.DONE) return getString(job.results.isEmpty()
				? R.string.compression_unchanged : R.string.compression_done);
		if (job.importing || job.itemPercent >= 100) return getString(R.string.slooop_converter_ui_checking_caption);
		if (job.attempt > 1) return getString(R.string.slooop_converter_ui_optimizing_caption);
		if (job.phase == VideoCompressionJob.Phase.RUNNING && job.itemPercent >= 0) {
			return job.queue.size() > 1 ? requireContext().getString(R.string.slooop_converter_ui_compressing_position_caption,
					job.index + 1, job.queue.size(), job.itemPercent) : requireContext().getString(
					R.string.slooop_converter_ui_compressing_caption, job.itemPercent);
		}
		return getString(R.string.slooop_converter_ui_preparing_caption);
	}
	private void pruneConversionBadges() {
		java.util.Iterator<java.util.Map.Entry<AttachmentHolder, VideoConversionBadge>> iterator = conversionBadges.entrySet().iterator();
		while (iterator.hasNext()) {
			java.util.Map.Entry<AttachmentHolder, VideoConversionBadge> entry = iterator.next();
			if (!attachments.contains(entry.getKey())) {
				entry.getValue().release(); ViewUtils.removeFromParent(entry.getValue()); iterator.remove();
			}
		}
	}
	private void renderConversionBadges(VideoCompressionJob job) {
		pruneConversionBadges();
		long now = android.os.SystemClock.uptimeMillis();
		for (AttachmentHolder holder : attachments) {
			VideoConversionBadge.Stage stage = VideoConversionBadge.Stage.NONE;
			int position = -1, count = job != null ? job.queue.size() : 0;
			long accepted = 0;
			boolean sourceVideo = false;
			if (job != null && CompressionSettings.enabled()) {
				VideoCompressionJob.Source source = conversionSource(job, holder);
				sourceVideo = source != null && Chan.getFallback().locator.isVideoExtension(source.name);
				position = job.queue.indexOf(source);
				VideoCompressionJob.Result result = job.results.get(holder.hash);
				boolean sourceAccepted = result != null && position >= 0 && position < job.index;
				Long at = compressionModel.acceptedAt.get(position + ":" + holder.hash);
				if (sourceVideo && job.phase == VideoCompressionJob.Phase.PREPARING) stage = VideoConversionBadge.Stage.PREPARING;
				else if (sourceVideo && job.phase == VideoCompressionJob.Phase.ERROR
						&& (position == job.index || job.queue.isEmpty())) stage = VideoConversionBadge.Stage.ERROR;
				else if (sourceAccepted && (!result.hash.equals(holder.hash) || source != null && job.isManualSource(source)) && at != null && now < at + 610) {
					stage = VideoConversionBadge.Stage.SUCCESS; accepted = at;
				} else if (!sourceAccepted && position >= 0 && job.busy()) {
					if (position != job.index) stage = VideoConversionBadge.Stage.QUEUED;
					else if (job.importing || job.itemPercent >= 100) stage = VideoConversionBadge.Stage.CHECKING;
					else if (job.attempt > 1) stage = VideoConversionBadge.Stage.OPTIMIZING;
					else if (job.phase == VideoCompressionJob.Phase.RUNNING && job.itemPercent >= 0) stage = VideoConversionBadge.Stage.PROGRESS;
					else stage = VideoConversionBadge.Stage.PREPARING;
				}
			}
			VideoConversionBadge badge = conversionBadges.get(holder);
			if (badge == null && stage != VideoConversionBadge.Stage.NONE && holder.imageView.getParent() instanceof FrameLayout) {
				FrameLayout preview = (FrameLayout) holder.imageView.getParent();
				badge = new VideoConversionBadge(requireContext(), VideoConversionSetup.materialContext(requireContext()), preview);
				int inset = Math.round(4 * ResourceUtils.obtainDensity(preview));
				FrameLayout.LayoutParams params = new FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM | Gravity.END);
				params.setMarginEnd(inset); params.bottomMargin = inset
						+ (attachmentAppearance == null ? Math.round(48 * ResourceUtils.obtainDensity(preview)) : 0);
				preview.addView(badge, params); conversionBadges.put(holder, badge);
			}
			if (badge != null) {
				int text;
				switch (stage) {
					case QUEUED: text = R.string.slooop_converter_ui_queued; break;
					case OPTIMIZING: text = R.string.slooop_converter_ui_optimizing_badge; break;
					case CHECKING: text = R.string.slooop_converter_ui_checking_badge; break;
					case ERROR: text = R.string.slooop_converter_ui_error_badge; break;
					default: text = R.string.slooop_converter_ui_preparing_badge; break;
				}
				CharSequence label = stage == VideoConversionBadge.Stage.SUCCESS ? "✓" : stage == VideoConversionBadge.Stage.PROGRESS
						? requireContext().getString(R.string.slooop_converter_ui_percent, job.itemPercent) : getString(text);
				CharSequence description = stage == VideoConversionBadge.Stage.PROGRESS
						? requireContext().getString(R.string.slooop_converter_ui_a11y_progress, position + 1, count, job.itemPercent)
						: stage == VideoConversionBadge.Stage.SUCCESS ? getString(R.string.compression_done) : label;
				badge.bind((job != null ? job.id : "") + ":" + position + ":" + holder.hash, stage, job != null ? job.itemPercent : -1, label, description, accepted);
			}
			boolean converting = stage == VideoConversionBadge.Stage.QUEUED || stage == VideoConversionBadge.Stage.PREPARING
					|| stage == VideoConversionBadge.Stage.PROGRESS || stage == VideoConversionBadge.Stage.OPTIMIZING || stage == VideoConversionBadge.Stage.CHECKING;
			holder.previewButton.setAlpha(converting ? 0f : 1f);
			if (attachmentAppearance != null) attachmentAppearance.conversionActive(holder, converting);
		}
	}

	public boolean canAttach() {
		return !isImportInProgress() && attachments.size() < postingConfiguration.attachmentCount;
	}

	public PostingAttachmentState getUiState() {
		return new PostingAttachmentState(attachments.size(),
				postingConfiguration != null ? postingConfiguration.attachmentCount : 0, isImportInProgress());
	}

	private Context requireContext() {
		if (host == null) throw new IllegalStateException("Attachment view not attached");
		return host.requireContext();
	}

	private android.content.res.Resources getResources() {
		return requireContext().getResources();
	}

	private String getString(int resId) {
		return requireContext().getString(resId);
	}

	private FileHolder resolveAttachmentFile(String hash) {
		return attachmentFileResolver.apply(hash);
	}

	public void updateConfiguration(ChanConfiguration.Posting posting, boolean attachmentOptions,
			boolean attachmentCount) {
		postingConfiguration = posting;
		if (attachmentOptions || attachmentCount) {
			if (attachmentAppearance != null) attachmentAppearance.cancelMotion();
			if (attachmentOptions) {
				attachmentRatingItems = posting.attachmentRatings.size() > 0 ? posting.attachmentRatings : null;
			}
			if (attachmentCount) {
				if (attachments.size() > posting.attachmentCount) {
					attachments.subList(posting.attachmentCount, attachments.size()).clear();
				}
			}
			invalidateAttachments(attachmentCount);
			if (attachmentCount) {
				host.invalidateOptionsMenu();
			}
		}
		updateAttachmentSummary();
	}

	private void updateAttachmentSummary() {
		if (host == null || attachmentSummary == null || postingConfiguration == null) return;
		boolean visible = postingConfiguration.attachmentCount > 0;
		if (visible) {
			int max = postingConfiguration.attachmentCount;
			String count = getResources().getQuantityString(R.plurals.posting_attachment_count,
					max, attachments.size(), max);
			long bytes = 0;
			boolean sizeKnown = true;
			for (AttachmentHolder holder : attachments) {
				VideoCompressionJob.Result converted = compressed(holder);
				long size = converted != null ? converted.after : holder.sourceSize;
				if (size < 0) { sizeKnown = false; break; }
				bytes += size;
			}
			Long limit = limitsModel != null ? limitsModel.totalBytes.getValue() : null;
			String size = null;
			if (sizeKnown) {
				size = StringUtils.formatFileSize(bytes, false);
				if (limit != null && limit > 0) size = requireContext().getString(
						R.string.posting_attachment_size_limit, size, StringUtils.formatFileSize(limit, false));
			}
			attachmentSummary.setSummary(count, size);
		}
		int visibility = visible ? View.VISIBLE : View.GONE;
		if (attachmentSummary.getVisibility() != visibility) {
			attachmentSummary.setVisibility(visibility);
			host.resizeCommentAfterAttachmentChange();
		}
	}

	public ArrayList<DraftsStorage.AttachmentDraft> createAttachmentDrafts() {
		ArrayList<DraftsStorage.AttachmentDraft> attachmentDrafts = null;
		if (attachments.size() > 0) {
			attachmentDrafts = new ArrayList<>(attachments.size());
			for (AttachmentHolder holder : attachments) {
				attachmentDrafts.add(new DraftsStorage.AttachmentDraft(holder.hash, holder.name, holder.rating,
						holder.optionUniqueHash, holder.optionRemoveMetadata, holder.optionRemoveFileName,
						holder.optionSpoiler, holder.reencoding));
			}
		}
		return attachmentDrafts;
	}

	public void restoreAttachmentDrafts(ArrayList<DraftsStorage.AttachmentDraft> attachmentDrafts) {
		if (attachmentDrafts != null && !attachmentDrafts.isEmpty()) {
			for (DraftsStorage.AttachmentDraft attachmentDraft : attachmentDrafts) {
				addAttachment(attachmentDraft.hash, attachmentDraft.name, attachmentDraft.rating,
						attachmentDraft.optionUniqueHash, attachmentDraft.optionRemoveMetadata,
						attachmentDraft.optionRemoveFileName, attachmentDraft.optionSpoiler,
						attachmentDraft.reencoding);
			}
		}
		updateAttachmentSummary();
	}

	public void addFutureAttachments(ArrayList<DraftsStorage.AttachmentDraft> futureAttachmentDrafts) {
		ArrayList<Pair<String, String>> attachmentsToAdd = new ArrayList<>(futureAttachmentDrafts.size());
		for (DraftsStorage.AttachmentDraft attachmentDraft : futureAttachmentDrafts) {
			attachmentsToAdd.add(new Pair<>(attachmentDraft.hash, attachmentDraft.name));
		}
		handleAttachmentsToAdd(attachmentsToAdd, futureAttachmentDrafts.size());
	}

	static final class SendAttachments {
		final ChanPerformer.SendPostData.Attachment[] attachments;
		final ArrayList<String> hashes;
		SendAttachments(ChanPerformer.SendPostData.Attachment[] attachments, ArrayList<String> hashes) {
			this.attachments = attachments;
			this.hashes = hashes;
		}
	}
	static final class CompressionUnavailableException extends IllegalStateException {
		private static final long serialVersionUID = 1L;
	}
	public void handleCompressionUnavailable() {
		if (compressionModel != null && compressionModel.job != null) compressionModel.job.fail(R.string.compression_failed);
		ClickableToast.show(R.string.compression_failed);
	}

	public boolean hasSendAttachments() {
		for (AttachmentHolder holder : attachments) if (resolveAttachmentFile(holder.hash) != null) return true;
		return false;
	}

	// A single snapshot keeps the payload and successful-file hashes in exactly the same order.
	public SendAttachments createSendAttachments() {
		ArrayList<ChanPerformer.SendPostData.Attachment> array = new ArrayList<>();
		ArrayList<String> attachmentHashes = new ArrayList<>();
		for (int i = 0; i < attachments.size(); i++) {
			AttachmentHolder data = attachments.get(i);
			String rating = data.rating;
			if (rating != null && attachmentRatingItems != null) {
				boolean found = false;
				for (Pair<String, String> pair : attachmentRatingItems) {
					if (rating.equals(pair.first)) {
						found = true;
						break;
					}
				}
				if (!found) {
					rating = null;
				}
			} else {
				rating = null;
			}
			if (attachmentRatingItems != null && rating == null) {
				rating = attachmentRatingItems.get(0).first;
			}
			FileHolder fileHolder = resolveAttachmentFile(data.hash);
			if (fileHolder != null) {
				VideoCompressionJob.Result converted = compressed(data);
				if (converted != null && !compressionModel.job.current()) throw new CompressionUnavailableException();
				if (converted != null) fileHolder = resolveAttachmentFile(converted.hash);
				if (fileHolder == null) throw new CompressionUnavailableException();
				attachmentHashes.add(converted != null ? converted.hash : data.hash);
				array.add(new ChanPerformer.SendPostData.Attachment(fileHolder, converted != null ? converted.name : data.name, rating,
						data.optionUniqueHash, data.optionRemoveMetadata, data.optionRemoveFileName,
						postingConfiguration.attachmentSpoiler && data.optionSpoiler, data.reencoding));
			}
		}
		ChanPerformer.SendPostData.Attachment[] attachments = null;
		if (array.size() > 0) {
			attachments = CommonUtils.toArray(array, ChanPerformer.SendPostData.Attachment.class);
		}
		return new SendAttachments(attachments, attachmentHashes);
	}

	public void openPicker() {
		if (isImportInProgress()) {
			ClickableToast.show(R.string.processing_data__ellipsis);
			return;
		}
		if (Preferences.isOpenConfiguredAttachmentFolderEnabled()) {
			openConfiguredAttachmentFolder();
		} else {
			openSystemAttachmentPicker();
		}
	}

	private static void handleMimeTypeGroup(ArrayList<String> list, Collection<String> mimeTypes, String mimeTypeGroup) {
		String allSubMimeTypes = mimeTypeGroup + "*";
		if (mimeTypes.contains(allSubMimeTypes)) {
			list.add(allSubMimeTypes);
		}
		for (String mimeType : mimeTypes) {
			if (mimeType.startsWith(mimeTypeGroup) && !allSubMimeTypes.equals(mimeType)) {
				list.add(mimeType);
			}
		}
	}

	public static ArrayList<String> buildMimeTypeList(Collection<String> mimeTypes) {
		ArrayList<String> list = new ArrayList<>();
		handleMimeTypeGroup(list, mimeTypes, "image/");
		handleMimeTypeGroup(list, mimeTypes, "video/");
		handleMimeTypeGroup(list, mimeTypes, "audio/");
		for (String mimeType : mimeTypes) {
			if (!list.contains(mimeType)) {
				list.add(mimeType);
			}
		}
		return list;
	}

	public UriPasteEditText.PasteResult onUrisWithAllowedMimeTypePasted(
			List<UriPasteEditText.UriContent> uriContents) {
		if (uriContents == null || uriContents.isEmpty()) {
			return UriPasteEditText.PasteResult.FAILED;
		}
		if (attachmentImportViewModel == null) {
			return UriPasteEditText.PasteResult.FAILED;
		}
		if (attachmentImportViewModel.hasTaskOrValue()) {
			return UriPasteEditText.PasteResult.IMPORT_IN_PROGRESS;
		}
		int availableCount = postingConfiguration.attachmentCount - attachments.size();
		if (availableCount <= 0) {
			return UriPasteEditText.PasteResult.FILES_LIMIT_REACHED;
		}
		int acceptedCount = Math.min(availableCount, uriContents.size());
		ArrayList<UriPasteEditText.UriContent> acceptedUriContents = new ArrayList<>(acceptedCount);
		for (int i = 0; i < uriContents.size(); i++) {
			UriPasteEditText.UriContent uriContent = uriContents.get(i);
			if (i < acceptedCount) {
				acceptedUriContents.add(uriContent);
			} else {
				uriContent.releasePermission();
			}
		}
		int rejectedCount = uriContents.size() - acceptedCount;
		if (rejectedCount > 0) {
			ClickableToast.show(getResources().getQuantityString(R.plurals
					.number_files_havent_been_attached__format, rejectedCount, rejectedCount));
		}
		AttachmentImportTask task = new AttachmentImportTask(attachmentImportViewModel, acceptedUriContents);
		try {
			task.execute(ConcurrentUtils.SEPARATE_EXECUTOR);
		} catch (RuntimeException e) {
			return UriPasteEditText.PasteResult.FAILED;
		}
		attachmentImportViewModel.attach(task);
		setAttachmentImportInProgress(true);
		return UriPasteEditText.PasteResult.ACCEPTED;
	}

	void setAttachmentImportInProgress(boolean inProgress) {
		if (sendButton != null && processingNotice != null) sendButton.removeCallbacks(processingNotice);
		processingNotice = null;
		attachmentImportInProgress = inProgress;
		if (sendButton != null) {
			host.updateSendButtonState();
			if (inProgress) {
				Button currentSendButton = sendButton;
				processingNotice = () -> {
					if (sendButton == currentSendButton && attachmentImportInProgress) {
						ClickableToast.show(R.string.processing_data__ellipsis);
					}
				};
				currentSendButton.postDelayed(processingNotice, 500L);
			}
		}
		host.invalidateOptionsMenu();
	}

	void onAttachmentImportComplete(AttachmentImportResult result) {
		setAttachmentImportInProgress(false);
		if (result == null) {
			ClickableToast.show(R.string.unknown_error);
			return;
		}
		int oldCount = attachments.size();
		for (Pair<String, String> attachment : result.attachments) {
			if (attachments.size() < postingConfiguration.attachmentCount) {
				addAttachment(attachment.first, attachment.second);
			}
		}
		int attachedCount = attachments.size() - oldCount;
		if (attachedCount > 0) {
			attachmentsChanged();
		}
		int errorCount = result.requestedCount - attachedCount;
		if (errorCount > 0) {
			ClickableToast.show(getResources().getQuantityString(R.plurals
					.number_files_havent_been_attached__format, errorCount, errorCount));
		}
	}

	static final class AttachmentImportResult {
		public final ArrayList<Pair<String, String>> attachments;
		public final int requestedCount;

		public AttachmentImportResult(ArrayList<Pair<String, String>> attachments, int requestedCount) {
			this.attachments = attachments;
			this.requestedCount = requestedCount;
		}
	}

	public static class AttachmentImportViewModel extends TaskViewModel<AttachmentImportTask,
			AttachmentImportResult> {}

	private static class AttachmentImportTask extends ExecutorTask<Void, AttachmentImportResult> {
		private final AttachmentImportViewModel viewModel;
		private final ArrayList<UriPasteEditText.UriContent> uriContents;

		public AttachmentImportTask(AttachmentImportViewModel viewModel,
				ArrayList<UriPasteEditText.UriContent> uriContents) {
			this.viewModel = viewModel;
			this.uriContents = uriContents;
		}

		@Override
		protected AttachmentImportResult run() {
			ArrayList<Pair<String, String>> attachments = new ArrayList<>(uriContents.size());
			try {
				for (UriPasteEditText.UriContent uriContent : uriContents) {
					if (isCancelled()) {
						return null;
					}
					try {
						FileHolder fileHolder = FileHolder.obtainForStreaming(uriContent.getUri());
						if (fileHolder != null) {
							String hash = DraftsStorage.getInstance().storeAttachmentFile(fileHolder);
							if (hash != null && !isCancelled()) {
								attachments.add(new Pair<>(hash, fileHolder.getName()));
							}
						}
					} catch (RuntimeException e) {
						// Continue importing the other items from the same receive-content payload.
					}
				}
				return isCancelled() ? null : new AttachmentImportResult(attachments, uriContents.size());
			} finally {
				releasePermissions();
			}
		}

		@Override
		protected void onCancel(AttachmentImportResult result) {
			releasePermissions();
		}

		@Override
		protected void onComplete(AttachmentImportResult result) {
			viewModel.handleResult(result != null ? result
					: new AttachmentImportResult(new ArrayList<>(), uriContents.size()));
		}

		private void releasePermissions() {
			for (UriPasteEditText.UriContent uriContent : uriContents) {
				uriContent.releasePermission();
			}
		}
	}

	private void openConfiguredAttachmentFolder() {
		Uri treeUri = Preferences.getDownloadUriTree(requireContext());
		if (treeUri == null) {
			host.requestStorage();
			return;
		}
		try {
			Uri initialUri = DocumentsContract.buildDocumentUriUsingTree(treeUri,
					DocumentsContract.getTreeDocumentId(treeUri));
			openSystemAttachmentPicker(Intent.ACTION_OPEN_DOCUMENT, initialUri);
		} catch (RuntimeException e) {
			openSystemAttachmentPicker();
		}
	}

	private void openSystemAttachmentPicker() {
		openSystemAttachmentPicker(Intent.ACTION_GET_CONTENT, null);
	}

	private void openSystemAttachmentPicker(String action, Uri initialUri) {
		// SHOW_ADVANCED is only a hint. Some photo providers still hide folder navigation.
		Intent intent = new Intent(action).addCategory(Intent.CATEGORY_OPENABLE)
				.putExtra("android.content.extra.SHOW_ADVANCED", true);
		if (initialUri != null) {
			intent.putExtra(DocumentsContract.EXTRA_INITIAL_URI, initialUri);
			intent.putExtra(Intent.EXTRA_LOCAL_ONLY, true);
		}
		ArrayList<String> mimeTypes = buildMimeTypeList(postingConfiguration.attachmentMimeTypes);
		if (mimeTypes.size() >= 2) {
			intent.setType("*/*");
			intent.putExtra(Intent.EXTRA_MIME_TYPES, CommonUtils.toArray(mimeTypes, String.class));
		} else if (mimeTypes.size() == 1) {
			intent.setType(mimeTypes.get(0));
		} else {
			intent.setType("*/*");
		}
		intent.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true);
		try {
			attachmentsChanged();
			host.launchAttachmentPicker(intent);
		} catch (ActivityNotFoundException e) {
			if (initialUri != null) {
				openSystemAttachmentPicker();
			} else {
				ClickableToast.show(R.string.unknown_address);
			}
		}
	}

	public void handleActivityResult(int requestCode, int resultCode, Intent data) {
		if (host == null) return;
		if (resultCode == Activity.RESULT_OK && data != null) {
			switch (requestCode) {
				case C.REQUEST_CODE_ATTACH: {
					if ((data.getFlags() & Intent.FLAG_GRANT_READ_URI_PERMISSION) == 0) {
						ClickableToast.show(R.string.no_access_to_memory);
						break;
					}
					LinkedHashSet<Uri> uris = new LinkedHashSet<>();
					Uri dataUri = data.getData();
					if (dataUri != null) {
						uris.add(dataUri);
					}
					ClipData clipData = data.getClipData();
					if (clipData != null) {
						for (int i = 0; i < clipData.getItemCount(); i++) {
							ClipData.Item item = clipData.getItemAt(i);
							Uri uri = item.getUri();
							if (uri != null) {
								uris.add(uri);
							}
						}
					}
					ArrayList<Pair<String, String>> attachmentsToAdd = new ArrayList<>();
					for (Uri uri : uris) {
						FileHolder fileHolder = FileHolder.obtain(uri);
						if (fileHolder != null) {
							String hash = DraftsStorage.getInstance().store(fileHolder);
							if (hash != null) {
								attachmentsToAdd.add(new Pair<>(hash, fileHolder.getName()));
							}
						}
					}
					handleAttachmentsToAdd(attachmentsToAdd, uris.size());
					break;
				}
				case C.REQUEST_CODE_IMAGE_EDITOR: {
					int index = data.getIntExtra(ImageEditorActivity.EXTRA_RESULT_ATTACHMENT_INDEX, -1);
					String hash = data.getStringExtra(ImageEditorActivity.EXTRA_RESULT_HASH);
					String name = data.getStringExtra(ImageEditorActivity.EXTRA_RESULT_NAME);
					AttachmentHolder holder = getAttachmentHolder(index);
					if (holder == null || !AttachmentResultGuard.matches(
							data.getStringExtra(ImageEditorActivity.EXTRA_RESULT_SOURCE_HASH),
							data.getStringExtra(ImageEditorActivity.EXTRA_RESULT_SOURCE_NAME), holder.hash, holder.name)) {
						host.onAttachmentDiagnostic("editor_result_stale");
						ClickableToast.show(R.string.image_editor_attachment_changed);
						break;
					}
					if (hash != null && name != null) {
						holder.hash = hash;
						holder.name = name;
						FileHolder fileHolder = resolveAttachmentFile(hash);
						if (fileHolder == null || !GraphicsUtils.canReencode(fileHolder)) {
							holder.reencoding = null;
						}
						bindAttachmentFile(holder, fileHolder);
						attachmentsChanged();
					}
					break;
				}
			}
		}
	}

	private void handleAttachmentsToAdd(ArrayList<Pair<String, String>> attachmentsToAdd, int addedCount) {
		int oldCount = attachments.size();
		for (Pair<String, String> attachmentToAdd : attachmentsToAdd) {
			if (attachments.size() < postingConfiguration.attachmentCount) {
				addAttachment(attachmentToAdd.first, attachmentToAdd.second);
			}
		}
		int newCount = attachments.size() - oldCount;
		if (newCount > 0) {
			attachmentsChanged();
		}
		int errorCount = addedCount - newCount;
		if (errorCount > 0) {
			ClickableToast.show(getResources().getQuantityString(R.plurals
					.number_files_havent_been_attached__format, errorCount, errorCount));
		}
	}

	public AttachmentHolder getAttachmentHolder(int index) {
		return index >= 0 && index < attachments.size() ? attachments.get(index) : null;
	}

	public List<Pair<String, String>> getAttachmentRatingItems() {
		return attachmentRatingItems;
	}

	private final View.OnClickListener attachmentOptionsListener = v -> {
		AttachmentHolder holder = (AttachmentHolder) v.getTag();
		int attachmentIndex = attachments.indexOf(holder);
		new AttachmentOptionsDialog(attachmentIndex).show(host.getChildFragmentManager(), AttachmentOptionsDialog.TAG);
	};

	private final View.OnClickListener attachmentWarningListener = v -> {
		AttachmentHolder holder = (AttachmentHolder) v.getTag();
		int attachmentIndex = attachments.indexOf(holder);
		new AttachmentWarningDialog(attachmentIndex).show(host.getChildFragmentManager(), AttachmentWarningDialog.TAG);
	};

	private final View.OnClickListener attachmentRatingListener = v -> {
		AttachmentHolder holder = (AttachmentHolder) v.getTag();
		int attachmentIndex = attachments.indexOf(holder);
		new AttachmentRatingDialog(attachmentIndex).show(host.getChildFragmentManager(), AttachmentRatingDialog.TAG);
	};

	private final View.OnClickListener attachmentPreviewListener = v -> {
		AttachmentHolder holder = (AttachmentHolder) v.getTag();
		if (holder == null || !attachments.contains(holder)) {
			return;
		}
		VideoCompressionJob.Result converted = compressed(holder);
		File file = DraftsStorage.getInstance().getAttachmentDraftFile(converted != null ? converted.hash : holder.hash);
		if (file == null) {
			ClickableToast.show(R.string.unknown_error);
			return;
		}
		String tag = GalleryOverlay.class.getName();
		if (host.getParentFragmentManager().findFragmentByTag(tag) == null) {
			new GalleryOverlay(Uri.fromFile(file), converted != null ? converted.name : holder.name).show(host.getParentFragmentManager(), tag);
		}
	};

	private final View.OnClickListener attachmentEditListener = v -> {
		AttachmentHolder holder = (AttachmentHolder) v.getTag();
		int attachmentIndex = attachments.indexOf(holder);
		if (attachmentIndex >= 0 && Preferences.isImageEditorEnabled()) {
			// Persist the exact source list before leaving for an external Activity.
			attachmentsChanged();
			host.launchImageEditor(ImageEditorActivity.createIntent(requireContext(), holder.hash, holder.name,
					attachmentIndex));
		}
	};

	private final View.OnLongClickListener attachmentDragStartListener = v -> {
		AttachmentHolder holder = (AttachmentHolder) v.getTag();
		if (holder == null || attachments.size() < 2 || !attachments.contains(holder)) {
			return false;
		}
		if (attachmentAppearance != null) return attachmentAppearance.startDrag(holder);
		return v.startDragAndDrop(ClipData.newPlainText("", ""),
				new View.DragShadowBuilder(holder.view), holder, 0);
	};

	private final View.OnDragListener attachmentContainerDragListener = (v, event) -> {
		if (attachmentAppearance != null) return attachmentAppearance.dispatchDrag(event);
		Object localState = event.getLocalState();
		if (!(localState instanceof AttachmentHolder)) {
			return false;
		}
		AttachmentHolder holder = (AttachmentHolder) localState;
		switch (event.getAction()) {
			case DragEvent.ACTION_DRAG_STARTED: {
				if (!attachments.contains(holder)) {
					return false;
				}
				draggedAttachment = holder;
				holder.view.setAlpha(0.45f);
				setAttachmentDragTarget(holder);
				return true;
			}
			case DragEvent.ACTION_DRAG_LOCATION: {
				scrollPostingFormDuringAttachmentDrag(event.getY());
				int index = findAttachmentDropIndex(event.getX(), event.getY());
				setAttachmentDragTarget(index >= 0 ? attachments.get(index) : null);
				return true;
			}
			case DragEvent.ACTION_DROP: {
				int index = findAttachmentDropIndex(event.getX(), event.getY());
				if (index >= 0) {
					moveAttachment(holder, index);
				}
				clearAttachmentDragState();
				return true;
			}
			case DragEvent.ACTION_DRAG_ENDED: {
				clearAttachmentDragState();
				return true;
			}
		}
		return true;
	};

	private void setAttachmentDragTarget(AttachmentHolder target) {
		if (attachmentDragTarget == target) {
			return;
		}
		if (attachmentDragTarget != null && attachmentDragTarget != draggedAttachment) {
			attachmentDragTarget.view.setScaleX(1f);
			attachmentDragTarget.view.setScaleY(1f);
		}
		attachmentDragTarget = target;
		if (target != null && target != draggedAttachment) {
			target.view.setScaleX(0.96f);
			target.view.setScaleY(0.96f);
		}
	}

	private int findAttachmentDropIndex(float x, float y) {
		int result = -1;
		float minDistance = Float.MAX_VALUE;
		Rect rect = new Rect();
		for (int i = 0; i < attachments.size(); i++) {
			View view = attachments.get(i).view;
			view.getDrawingRect(rect);
			attachmentContainer.offsetDescendantRectToMyCoords(view, rect);
			if (rect.contains((int) x, (int) y)) {
				return i;
			}
			float dx = x - rect.exactCenterX();
			float dy = y - rect.exactCenterY();
			float distance = dx * dx + dy * dy;
			if (distance < minDistance) {
				minDistance = distance;
				result = i;
			}
		}
		return result;
	}

	private void scrollPostingFormDuringAttachmentDrag(float y) {
		if (scrollView == null || attachmentContainer == null) {
			return;
		}
		int[] containerLocation = new int[2];
		int[] scrollLocation = new int[2];
		attachmentContainer.getLocationOnScreen(containerLocation);
		scrollView.getLocationOnScreen(scrollLocation);
		float screenY = containerLocation[1] + y;
		float density = ResourceUtils.obtainDensity(getResources());
		int threshold = (int) (48f * density);
		int step = (int) (12f * density);
		if (screenY < scrollLocation[1] + threshold && scrollView.canScrollVertically(-1)) {
			scrollView.scrollBy(0, -step);
		} else if (screenY > scrollLocation[1] + scrollView.getHeight() - threshold
				&& scrollView.canScrollVertically(1)) {
			scrollView.scrollBy(0, step);
		}
	}

	// Move the same holder: rating/options/reencoding and pending thumbnail identity must survive.
	public void moveAttachment(AttachmentHolder holder, int targetIndex) {
		int sourceIndex = attachments.indexOf(holder);
		if (sourceIndex < 0 || sourceIndex == targetIndex) {
			return;
		}
		attachments.remove(sourceIndex);
		attachments.add(targetIndex, holder);
		if (attachmentAppearance == null || !attachmentAppearance.deferRebuild()) invalidateAttachments(true);
		attachmentsChanged();
	}

	private void clearAttachmentDragState() {
		if (draggedAttachment != null) {
			draggedAttachment.view.setAlpha(1f);
		}
		if (attachmentDragTarget != null && attachmentDragTarget != draggedAttachment) {
			attachmentDragTarget.view.setScaleX(1f);
			attachmentDragTarget.view.setScaleY(1f);
		}
		draggedAttachment = null;
		attachmentDragTarget = null;
	}

	private final View.OnClickListener attachmentRemoveListener = new View.OnClickListener() {
		@Override
		public void onClick(View v) {
			AttachmentHolder holder = (AttachmentHolder) v.getTag();
			if (!attachments.contains(holder)) return;
			if (attachmentAppearance != null) attachmentAppearance.prepareRemoval(holder);
			if (attachments.remove(holder)) {
				VideoThumbnailTask task = videoThumbnailTasks.remove(holder);
				if (task != null) {
					task.cancel();
				}
				if (attachmentAppearance == null && attachmentColumnCount == 1) {
					attachmentContainer.removeView(holder.view);
				} else {
					invalidateAttachments(true);
				}
				host.invalidateOptionsMenu();
				host.resizeCommentAfterAttachmentChange();
				attachmentsChanged();
			}
		}
	};

	private void invalidateAttachments(boolean clearContainer) {
		if (clearContainer) {
			if (attachmentAppearance != null) attachmentAppearance.clearCards();
			else attachmentContainer.removeAllViews();
		}
		for (int i = 0; i < attachments.size(); i++) {
			AttachmentHolder holder = attachments.get(i);
			if (clearContainer) {
				ViewUtils.removeFromParent(holder.view);
				addAttachmentViewToContainer(holder.view, i);
			}
			updateAttachmentConfiguration(holder);
		}
		if (attachmentAppearance != null) attachmentAppearance.prune();
	}

	private void addAttachmentViewToContainer(View attachmentView, int position) {
		if (attachmentAppearance != null) { attachmentAppearance.addCard(attachmentView); return; }
		LinearLayout.LayoutParams layoutParams = (LinearLayout.LayoutParams) attachmentView.getLayoutParams();
		if (attachmentColumnCount == 1) {
			layoutParams.width = LinearLayout.LayoutParams.MATCH_PARENT;
			layoutParams.weight = 0;
			layoutParams.leftMargin = 0;
			attachmentContainer.addView(attachmentView);
		} else {
			float density = ResourceUtils.obtainDensity(getResources());
			float paddingDp = 4f;
			layoutParams.width = 0;
			layoutParams.weight = 1;
			layoutParams.leftMargin = (int) (paddingDp * density);
			int row = position / attachmentColumnCount, column = position % attachmentColumnCount;
			LinearLayout subcontainer;
			View placeholder;
			if (column == 0) {
				subcontainer = new LinearLayout(requireContext());
				attachmentContainer.addView(subcontainer, LinearLayout.LayoutParams.MATCH_PARENT,
						LinearLayout.LayoutParams.WRAP_CONTENT);
				subcontainer.setOrientation(LinearLayout.HORIZONTAL);
				placeholder = new View(requireContext());
				subcontainer.addView(placeholder, 0, LinearLayout.LayoutParams.MATCH_PARENT);
				subcontainer.setPadding(0, 0, (int) (paddingDp * density), 0);
				subcontainer.setGravity(Gravity.BOTTOM);
			} else {
				subcontainer = (LinearLayout) attachmentContainer.getChildAt(row);
				placeholder = subcontainer.getChildAt(subcontainer.getChildCount() - 1);
			}
			subcontainer.addView(attachmentView, column);
			layoutParams = ((LinearLayout.LayoutParams) placeholder.getLayoutParams());
			layoutParams.weight = attachmentColumnCount - column - 1;
			layoutParams.leftMargin = (int) (paddingDp * density * layoutParams.weight);
			placeholder.setVisibility(attachmentColumnCount == column + 1 ? View.GONE : View.VISIBLE);
		}
	}

	private static View addAttachmentButton(LinearLayout parent, int width,
			int attrResId, View.OnClickListener listener) {
		ImageView imageView = createAttachmentButton(parent, width, listener);
		imageView.setImageDrawable(ResourceUtils.getDrawable(imageView.getContext(), attrResId, 0));
		return imageView;
	}

	private static View addAttachmentButtonResource(LinearLayout parent, int width,
			int drawableResId, View.OnClickListener listener) {
		ImageView imageView = createAttachmentButton(parent, width, listener);
		imageView.setImageResource(drawableResId);
		return imageView;
	}

	private static ImageView createAttachmentButton(LinearLayout parent, int width,
			View.OnClickListener listener) {
		float density = ResourceUtils.obtainDensity(parent);
		ImageView imageView = new ImageView(parent.getContext(), null, android.R.attr.borderlessButtonStyle);
		parent.addView(imageView, width, LinearLayout.LayoutParams.MATCH_PARENT);
		LinearLayout.LayoutParams layoutParams = (LinearLayout.LayoutParams) imageView.getLayoutParams();
		layoutParams.gravity = Gravity.CENTER_VERTICAL;
		ViewUtils.setNewMarginRelative(imageView, (int) (-8f * density), 0, 0, 0);
		imageView.setScaleType(ImageView.ScaleType.CENTER);
		imageView.setImageTintList(ResourceUtils.getColorStateList(imageView.getContext(),
				android.R.attr.textColorPrimary));
		imageView.setOnClickListener(listener);
		return imageView;
	}

	private AttachmentHolder addNewAttachment() {
		if (attachmentAppearance != null) {
			AttachmentHolder holder = attachmentAppearance.createCard(attachmentOptionsListener, attachmentWarningListener,
					attachmentRatingListener, attachmentEditListener, attachmentRemoveListener, attachmentPreviewListener,
					attachmentDragStartListener);
			attachmentAppearance.addCard(holder.view);
			attachments.add(holder);
			host.invalidateOptionsMenu(); host.resizeCommentAfterAttachmentChange();
			return holder;
		}
		float density = ResourceUtils.obtainDensity(getResources());
		int minHeight = (int) (48f * density);
		FrameLayout view = new FrameLayout(attachmentContainer.getContext());
		view.setLayoutParams(new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, minHeight));
		ViewUtils.setNewMargin(view, 0, (int) (4f * density), 0, 0);
		view.setBackgroundColor(0xff000000);
		view.setForeground(new RoundedCornersDrawable((int) (2f * density),
				ThemeEngine.getTheme(view.getContext()).window));
		addAttachmentViewToContainer(view, attachments.size());
		ImageView imageView = new ImageView(view.getContext());
		imageView.setScaleType(ImageView.ScaleType.CENTER_CROP);
		imageView.setBackground(new TransparentTileDrawable(imageView.getContext(), true));
		imageView.setVisibility(View.GONE);
		view.addView(imageView, FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT);
		View overlay = new View(view.getContext());
		overlay.setBackgroundColor(ResourceUtils.getColor(overlay.getContext(), R.attr.colorBlockBackground));
		view.addView(overlay, FrameLayout.LayoutParams.MATCH_PARENT, minHeight);
		((FrameLayout.LayoutParams) overlay.getLayoutParams()).gravity = Gravity.BOTTOM;
		View options = new View(view.getContext());
		ViewUtils.setSelectableItemBackground(options);
		options.setOnClickListener(attachmentOptionsListener);
		view.addView(options, FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT);
		ImageView previewButton = new ImageView(view.getContext(), null, android.R.attr.borderlessButtonStyle);
		previewButton.setScaleType(ImageView.ScaleType.CENTER);
		previewButton.setImageDrawable(ResourceUtils.getDrawable(previewButton.getContext(),
				R.attr.iconAttachmentVideo, 0));
		previewButton.setVisibility(View.GONE);
		previewButton.setContentDescription(getString(R.string.preview_video_attachment));
		previewButton.setOnClickListener(attachmentPreviewListener);
		int previewButtonSize = (int) (48f * density);
		view.addView(previewButton, previewButtonSize, previewButtonSize);
		FrameLayout.LayoutParams previewLayoutParams = (FrameLayout.LayoutParams) previewButton.getLayoutParams();
		previewLayoutParams.gravity = Gravity.TOP | Gravity.CENTER_HORIZONTAL;
		previewLayoutParams.topMargin = (int) (16f * density);

		LinearLayout controls = new LinearLayout(view.getContext());
		controls.setOrientation(LinearLayout.HORIZONTAL);
		view.addView(controls, FrameLayout.LayoutParams.MATCH_PARENT, minHeight);
		((FrameLayout.LayoutParams) controls.getLayoutParams()).gravity = Gravity.BOTTOM;
		controls.setPaddingRelative((int) (8f * density), 0, 0, 0);
		LinearLayout textLayout = new LinearLayout(controls.getContext());
		textLayout.setOrientation(LinearLayout.VERTICAL);
		textLayout.setGravity(Gravity.CENTER_VERTICAL);
		controls.addView(textLayout, 0, LinearLayout.LayoutParams.MATCH_PARENT);
		((LinearLayout.LayoutParams) textLayout.getLayoutParams()).weight = 1f;
		textLayout.setPaddingRelative((int) (4f * density), 0, (int) (8f * density), 0);
		TextView fileName = new TextView(controls.getContext());
		textLayout.addView(fileName, LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
		TextViewCompat.setTextAppearance(fileName, ResourceUtils.getResourceId(fileName.getContext(),
				android.R.attr.textAppearanceListItem, 0));
		ThemeEngine.applyStyle(fileName);
		fileName.setSingleLine(true);
		fileName.setEllipsize(TextUtils.TruncateAt.END);
		ViewUtils.setTextSizeScaled(fileName, 12);
		fileName.setTypeface(ResourceUtils.TYPEFACE_MEDIUM);
		TextView fileSize = new TextView(controls.getContext());
		textLayout.addView(fileSize, LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
		TextViewCompat.setTextAppearance(fileSize, ResourceUtils.getResourceId(fileSize.getContext(),
				android.R.attr.textAppearanceListItem, 0));
		ThemeEngine.applyStyle(fileSize);
		fileSize.setSingleLine(true);
		fileSize.setEllipsize(TextUtils.TruncateAt.END);
		ViewUtils.setTextSizeScaled(fileSize, 12);
		View warningButton = addAttachmentButton(controls, minHeight,
				R.attr.iconButtonWarning, attachmentWarningListener);
		View ratingButton = addAttachmentButton(controls, minHeight,
				R.attr.iconButtonRating, attachmentRatingListener);
		View editButton = addAttachmentButtonResource(controls, minHeight,
				R.drawable.ic_edit, attachmentEditListener);
		int editIconColor = GraphicsUtils.isLight(ResourceUtils.getColor(editButton.getContext(),
				R.attr.colorBlockBackground)) ? 0xff000000 : 0xffffffff;
		((ImageView) editButton).setImageTintList(ColorStateList.valueOf(editIconColor));
		editButton.setVisibility(View.GONE);
		editButton.setContentDescription(getString(R.string.edit_image));
		View dragButton = addAttachmentButton(controls, minHeight,
				R.attr.iconButtonDragHandle, null);
		View removeButton = addAttachmentButton(controls, minHeight,
				R.attr.iconButtonCancel, attachmentRemoveListener);

		AttachmentHolder holder = new AttachmentHolder(view, fileName, fileSize, imageView, previewButton,
				warningButton, ratingButton, editButton);
		warningButton.setTag(holder);
		ratingButton.setTag(holder);
		editButton.setTag(holder);
		dragButton.setTag(holder);
		dragButton.setContentDescription(getString(R.string.reorder_attachment));
		dragButton.setOnLongClickListener(attachmentDragStartListener);
		previewButton.setTag(holder);
		removeButton.setTag(holder);
		options.setTag(holder);
		attachments.add(holder);
		host.invalidateOptionsMenu();
		host.resizeCommentAfterAttachmentChange();
		return holder;
	}

	private void addAttachment(String hash, String name) {
		FileHolder fileHolder = resolveAttachmentFile(hash);
		GraphicsUtils.Reencoding reencoding = Preferences.isDefaultAttachmentReencoding()
				&& fileHolder != null && GraphicsUtils.canReencode(fileHolder)
				? new GraphicsUtils.Reencoding(GraphicsUtils.Reencoding.FORMAT_JPEG, 90, 1) : null;
		addAttachment(hash, name, null, Preferences.isDefaultAttachmentUniqueHash(),
				Preferences.isDefaultAttachmentRemoveMetadata(), Preferences.isDefaultAttachmentRemoveFileName(),
				false, reencoding);
	}

	private void addAttachment(String hash, String name, String rating, boolean optionUniqueHash,
			boolean optionRemoveMetadata, boolean optionRemoveFileName, boolean optionSpoiler,
			GraphicsUtils.Reencoding reencoding) {
		FileHolder fileHolder = resolveAttachmentFile(hash);
		AttachmentHolder holder = addNewAttachment();
		holder.hash = hash;
		holder.name = name;
		holder.rating = rating;
		holder.optionUniqueHash = optionUniqueHash;
		holder.optionRemoveMetadata = optionRemoveMetadata;
		holder.optionRemoveFileName = optionRemoveFileName;
		holder.optionSpoiler = optionSpoiler;
		holder.reencoding = reencoding;
		bindAttachmentFile(holder, fileHolder);
		updateAttachmentConfiguration(holder);
	}

	private void bindAttachmentFile(AttachmentHolder holder, FileHolder fileHolder) {
		holder.sourceSize = fileHolder != null ? fileHolder.getSize() : -1;
		VideoCompressionJob.Result converted = compressed(holder);
		if (converted != null) fileHolder = resolveAttachmentFile(converted.hash);
		if (attachmentAppearance != null) attachmentAppearance.cancelMotion();
		VideoThumbnailTask oldTask = videoThumbnailTasks.remove(holder);
		if (oldTask != null) oldTask.cancel();
		JpegData jpegData = fileHolder != null ? fileHolder.getJpegData() : null;
		PngData pngData = fileHolder != null ? fileHolder.getPngData() : null;
		holder.fileName.setText(converted != null ? converted.name : holder.name);
		int size = fileHolder != null ? fileHolder.getSize() : 0;
		String fileSize = converted != null ? requireContext().getString(R.string.compression_result,
				StringUtils.formatFileSize(converted.before, false), StringUtils.formatFileSize(converted.after, false), converted.codec)
				: StringUtils.formatFileSize(size, false);
		Bitmap bitmap = null;
		DisplayMetrics metrics = getResources().getDisplayMetrics();
		int targetImageSize = attachmentAppearance != null ? attachmentAppearance.thumbnailSize()
				: Math.max(metrics.widthPixels, metrics.heightPixels);
		boolean video = Chan.getFallback().locator.isVideoExtension(holder.name);
		holder.imageView.setImageDrawable(null);
		holder.imageView.setVisibility(View.GONE);
		holder.previewButton.setVisibility(View.GONE);
		holder.warningButton.setVisibility(View.VISIBLE);
		holder.editButton.setVisibility(View.GONE);
		holder.view.getLayoutParams().height = (int) (48f * ResourceUtils.obtainDensity(getResources()));
		if (fileHolder != null) {
			if (fileHolder.isImage()) {
				if (attachmentAppearance == null) {
					try {
						bitmap = fileHolder.readImageBitmap(targetImageSize, false, false);
					} catch (OutOfMemoryError e) {
						// Ignore
					}
				}
				fileSize += " " + fileHolder.getImageWidth() + '×' + fileHolder.getImageHeight();
			}
		}
		if (video) {
			holder.previewButton.setVisibility(Preferences.isAttachmentVideoPreview()
					&& Preferences.isUseVideoPlayer() ? View.VISIBLE : View.GONE);
			holder.view.getLayoutParams().height = (int) (128f * ResourceUtils.obtainDensity(getResources()));
			VideoThumbnailTask task = new VideoThumbnailTask(holder, holder.hash, targetImageSize);
			videoThumbnailTasks.put(holder, task);
			task.execute(VIDEO_THUMBNAIL_EXECUTOR);
		}
		if (fileHolder != null && Preferences.isImageEditorEnabled() && isEditableImage(fileHolder, holder.name)) {
			holder.editButton.setVisibility(View.VISIBLE);
		}
		if (bitmap != null) {
			holder.imageView.setVisibility(View.VISIBLE);
			holder.imageView.setImageBitmap(bitmap);
			holder.view.getLayoutParams().height = (int) (128f * ResourceUtils.obtainDensity(getResources()));
		}
		holder.fileSize.setText(fileSize);
		if ((jpegData == null || jpegData.exifData == null) && (pngData == null || !pngData.hasMetadata)) {
			holder.warningButton.setVisibility(View.GONE);
		}
		if (attachmentAppearance != null) {
			boolean image = fileHolder != null && fileHolder.isImage();
			boolean editable = fileHolder != null && Preferences.isImageEditorEnabled() && isEditableImage(fileHolder, holder.name);
			attachmentAppearance.bindFile(holder, fileSize, fileHolder != null, image, video, editable);
			if (image && !video) {
				VideoThumbnailTask task = new VideoThumbnailTask(holder, holder.hash, targetImageSize, fileHolder);
				videoThumbnailTasks.put(holder, task); task.execute(VIDEO_THUMBNAIL_EXECUTOR);
			}
		}
	}

	private static boolean isEditableImage(FileHolder fileHolder, String name) {
		String extension = StringUtils.getFileExtension(name);
		if ("gif".equals(extension) || "apng".equals(extension) || "svg".equals(extension)) return false;
		switch (fileHolder.getImageType()) {
			case IMAGE_JPEG:
			case IMAGE_PNG:
			case IMAGE_WEBP:
			case IMAGE_BMP: return true;
			default: return false;
		}
	}

	/** Existing video worker also generates sampled, uncropped images for the compact form. */
	private class VideoThumbnailTask extends ExecutorTask<Void, Bitmap> {
		private final AttachmentHolder holder;
		private final String hash;
		private final int targetImageSize;
		private final FileHolder imageFile;

		public VideoThumbnailTask(AttachmentHolder holder, String hash, int targetImageSize) {
			this(holder, hash, targetImageSize, null);
		}

		public VideoThumbnailTask(AttachmentHolder holder, String hash, int targetImageSize, FileHolder imageFile) {
			this.holder = holder;
			this.hash = hash;
			this.targetImageSize = targetImageSize;
			this.imageFile = imageFile;
		}

		@Override
		protected Bitmap run() throws InterruptedException {
			if (imageFile != null) {
				try { return imageFile.readImageBitmap(targetImageSize, false, false); }
				catch (RuntimeException | OutOfMemoryError e) { return null; }
			}
			File file = DraftsStorage.getInstance().getAttachmentDraftFile(hash);
			if (file == null || !VideoPlayer.loadLibraries(MainApplication.getInstance()).first) {
				return null;
			}
			try {
				Bitmap bitmap = VideoPlayer.createThumbnail(file);
				return bitmap != null ? GraphicsUtils.reduceBitmapSize(bitmap, targetImageSize, true) : null;
			} catch (java.io.IOException | RuntimeException | LinkageError | OutOfMemoryError e) {
				return null;
			}
		}

		@Override
		protected void onCancel(Bitmap bitmap) {
			if (bitmap != null) {
				bitmap.recycle();
			}
		}

		@Override
		protected void onComplete(Bitmap bitmap) {
			boolean currentTask = videoThumbnailTasks.get(holder) == this;
			if (currentTask) {
				videoThumbnailTasks.remove(holder);
			}
			if (currentTask && host != null && attachments.contains(holder) && hash.equals(holder.hash)) {
				if (bitmap != null) {
					holder.imageView.setImageBitmap(bitmap);
					holder.imageView.setVisibility(View.VISIBLE);
				}
				if (attachmentAppearance != null) attachmentAppearance.thumbnailFinished(holder, bitmap != null);
			} else if (bitmap != null) {
				bitmap.recycle();
			}
		}
	}

	private void updateAttachmentConfiguration(AttachmentHolder holder) {
		if (attachmentRatingItems != null) {
			if (holder.rating == null) {
				holder.rating = attachmentRatingItems.get(0).first;
			}
			holder.ratingButton.setVisibility(View.VISIBLE);
		} else {
			holder.ratingButton.setVisibility(View.GONE);
		}
	}

}
