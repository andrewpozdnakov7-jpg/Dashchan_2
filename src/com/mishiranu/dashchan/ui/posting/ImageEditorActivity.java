package com.mishiranu.dashchan.ui.posting;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffXfermode;
import android.graphics.RectF;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowInsets;
import android.view.WindowInsetsController;
import android.widget.FrameLayout;
import android.widget.GridLayout;
import android.widget.HorizontalScrollView;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.SeekBar;
import android.widget.TextView;
import com.mishiranu.dashchan.R;
import com.mishiranu.dashchan.content.LocaleManager;
import com.mishiranu.dashchan.content.model.FileHolder;
import com.mishiranu.dashchan.content.storage.DraftsStorage;
import com.mishiranu.dashchan.util.ViewUtils;
import com.mishiranu.dashchan.widget.ThemeEngine;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.ArrayDeque;
import java.util.ArrayList;

/**
 * A deliberately self-contained editor for posting attachments. It never overwrites the source draft:
 * the rendered result is encoded without metadata and stored as a new attachment draft.
 */
public class ImageEditorActivity extends Activity {
	private static final String EXTRA_SOURCE_HASH = "sourceHash";
	private static final String EXTRA_SOURCE_NAME = "sourceName";
	private static final String EXTRA_ATTACHMENT_INDEX = "attachmentIndex";
	public static final String EXTRA_RESULT_HASH = "resultHash";
	public static final String EXTRA_RESULT_NAME = "resultName";
	public static final String EXTRA_RESULT_ATTACHMENT_INDEX = "resultAttachmentIndex";

	private static final int MAX_IMAGE_SIZE = 2048;
	private static final String[] STICKERS = {"😀", "😂", "❤️", "👍", "🔥", "💩", "🤡", "🚫"};

	public static Intent createIntent(Context context, String sourceHash, String sourceName, int attachmentIndex) {
		return new Intent(context, ImageEditorActivity.class)
				.putExtra(EXTRA_SOURCE_HASH, sourceHash)
				.putExtra(EXTRA_SOURCE_NAME, sourceName)
				.putExtra(EXTRA_ATTACHMENT_INDEX, attachmentIndex);
	}

	private FrameLayout content;
	private LinearLayout tools;
	private TextView statusView;
	private ProgressBar progressBar;
	private ImageButton saveButton;
	private ImageButton cropButton;
	private ImageButton undoButton;
	private ImageButton redoButton;
	private EditorView editorView;
	private FileHolder.ImageType sourceType;
	private String sourceHash;
	private String sourceName;
	private int attachmentIndex;
	private volatile boolean stopped;
	private float brushWidthRatio = 0.035f;
	private int brushColor = Color.BLACK;

	@Override
	protected void attachBaseContext(Context newBase) {
		super.attachBaseContext(ThemeEngine.attach(LocaleManager.getInstance().apply(newBase)));
	}

	@Override
	protected void onCreate(Bundle savedInstanceState) {
		requestWindowFeature(Window.FEATURE_NO_TITLE);
		ThemeEngine.applyTheme(this);
		super.onCreate(savedInstanceState);
		sourceHash = getIntent().getStringExtra(EXTRA_SOURCE_HASH);
		sourceName = getIntent().getStringExtra(EXTRA_SOURCE_NAME);
		attachmentIndex = getIntent().getIntExtra(EXTRA_ATTACHMENT_INDEX, -1);
		if (sourceHash == null || sourceName == null || attachmentIndex < 0) {
			finish();
			return;
		}
		ViewUtils.setWindowLayoutFullscreen(getWindow());
		createLayout();
		hideSystemBars();
		loadImage();
	}

	private void hideSystemBars() {
		WindowInsetsController controller = getWindow().getInsetsController();
		if (controller != null) {
			controller.setSystemBarsBehavior(WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
			controller.hide(WindowInsets.Type.systemBars());
		}
	}

	@Override
	public void onWindowFocusChanged(boolean hasFocus) {
		super.onWindowFocusChanged(hasFocus);
		if (hasFocus) hideSystemBars();
	}

	private void createLayout() {
		float density = getResources().getDisplayMetrics().density;
		LinearLayout root = new LinearLayout(this);
		root.setOrientation(LinearLayout.VERTICAL);
		root.setBackgroundColor(0xff101010);
		setContentView(root);
		root.setOnApplyWindowInsetsListener((v, insets) -> {
			android.graphics.Insets cutout = insets.getInsetsIgnoringVisibility(WindowInsets.Type.displayCutout());
			v.setPadding(cutout.left, cutout.top, cutout.right, cutout.bottom);
			return insets;
		});
		root.requestApplyInsets();

		LinearLayout header = new LinearLayout(this);
		header.setGravity(Gravity.CENTER_VERTICAL);
		header.setPadding((int) (4f * density), 0, (int) (4f * density), 0);
		header.setBackgroundColor(0xff202124);
		root.addView(header, ViewGroup.LayoutParams.MATCH_PARENT, (int) (56f * density));
		ImageButton cancelButton = createIconButton(R.drawable.ic_editor_close, android.R.string.cancel);
		cancelButton.setOnClickListener(v -> finish());
		header.addView(cancelButton, (int) (34f * density), ViewGroup.LayoutParams.MATCH_PARENT);
		TextView title = new TextView(this);
		title.setText(R.string.image_editor);
		title.setTextColor(Color.WHITE);
		title.setTextSize(18f);
		title.setGravity(Gravity.CENTER);
		header.addView(title, 0, ViewGroup.LayoutParams.MATCH_PARENT);
		((LinearLayout.LayoutParams) title.getLayoutParams()).weight = 1f;
		saveButton = createIconButton(R.drawable.ic_editor_done, android.R.string.ok);
		saveButton.setEnabled(false);
		saveButton.setOnClickListener(v -> saveImage());
		header.addView(saveButton, (int) (34f * density), ViewGroup.LayoutParams.MATCH_PARENT);

		content = new FrameLayout(this);
		content.setBackgroundColor(Color.BLACK);
		root.addView(content, ViewGroup.LayoutParams.MATCH_PARENT, 0);
		((LinearLayout.LayoutParams) content.getLayoutParams()).weight = 1f;
		progressBar = new ProgressBar(this);
		content.addView(progressBar, ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
		((FrameLayout.LayoutParams) progressBar.getLayoutParams()).gravity = Gravity.CENTER;
		statusView = new TextView(this);
		statusView.setText(R.string.image_editor_loading);
		statusView.setTextColor(Color.WHITE);
		statusView.setGravity(Gravity.CENTER);
		FrameLayout.LayoutParams statusParams = new FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,
				ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER);
		statusParams.topMargin = (int) (56f * density);
		content.addView(statusView, statusParams);

		HorizontalScrollView toolsScroll = new HorizontalScrollView(this);
		toolsScroll.setFillViewport(true);
		toolsScroll.setHorizontalScrollBarEnabled(false);
		toolsScroll.setBackgroundColor(0xff202124);
		root.addView(toolsScroll, ViewGroup.LayoutParams.MATCH_PARENT, (int) (58f * density));
		tools = new LinearLayout(this);
		tools.setGravity(Gravity.CENTER);
		tools.setPadding((int) (4f * density), 0, (int) (4f * density), 0);
		toolsScroll.addView(tools, ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.MATCH_PARENT);
		toolsScroll.setVisibility(View.GONE);
	}

	private ImageButton createIconButton(int drawableResId, int descriptionResId) {
		ImageButton button = new ImageButton(this, null, android.R.attr.borderlessButtonStyle);
		button.setImageResource(drawableResId);
		button.setColorFilter(Color.WHITE);
		button.setContentDescription(getString(descriptionResId));
		int padding = (int) (3f * getResources().getDisplayMetrics().density);
		button.setPadding(padding, padding, padding, padding);
		button.setScaleType(ImageButton.ScaleType.FIT_CENTER);
		return button;
	}

	private void addToolButtons() {
		undoButton = addTool(R.drawable.ic_editor_undo, R.string.image_editor_undo, v -> {
			leaveCropMode();
			editorView.undo();
			updateHistoryButtons();
		});
		redoButton = addTool(R.drawable.ic_editor_redo, R.string.image_editor_redo, v -> {
			leaveCropMode();
			editorView.redo();
			updateHistoryButtons();
		});
		cropButton = addTool(R.drawable.ic_editor_crop, R.string.image_editor_crop, v -> {
			if (editorView.isCropping()) {
				editorView.applyCrop();
				cropButton.setImageResource(R.drawable.ic_editor_crop);
				cropButton.setContentDescription(getString(R.string.image_editor_crop));
			} else {
				editorView.setMode(EditorView.Mode.CROP);
				cropButton.setImageResource(R.drawable.ic_editor_done);
				cropButton.setContentDescription(getString(R.string.image_editor_apply_crop));
			}
			updateHistoryButtons();
		});
		addTool(R.drawable.ic_editor_rotate, R.string.image_editor_rotate, v -> {
			leaveCropMode();
			editorView.rotate();
			updateHistoryButtons();
		});
		addTool(R.drawable.ic_editor_flip, R.string.image_editor_flip, v -> {
			leaveCropMode();
			editorView.flip();
			updateHistoryButtons();
		});
		addTool(R.drawable.ic_editor_brush, R.string.image_editor_marker, v -> {
			leaveCropMode();
			showBrushSettings();
		});
		addTool(R.drawable.ic_editor_eraser, R.string.image_editor_eraser, v -> {
			leaveCropMode();
			editorView.setMode(EditorView.Mode.ERASE);
		});
		addTool(R.drawable.ic_editor_sticker, R.string.image_editor_sticker, v -> {
			leaveCropMode();
			showStickerPicker();
		});
		updateHistoryButtons();
	}

	private void showBrushSettings() {
		float density = getResources().getDisplayMetrics().density;
		LinearLayout layout = new LinearLayout(this);
		layout.setOrientation(LinearLayout.VERTICAL);
		int padding = (int) (20f * density);
		layout.setPadding(padding, (int) (8f * density), padding, 0);

		TextView widthLabel = new TextView(this);
		layout.addView(widthLabel, ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
		SeekBar widthSeek = new SeekBar(this);
		widthSeek.setMax(95);
		widthSeek.setProgress(Math.max(0, Math.min(95, Math.round(brushWidthRatio * 1000f) - 5)));
		layout.addView(widthSeek, ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
		Runnable updateWidthLabel = () -> widthLabel.setText(getString(R.string.image_editor_brush_size__format,
				(widthSeek.getProgress() + 5) / 10f));
		widthSeek.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
			@Override
			public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
				updateWidthLabel.run();
			}

			@Override public void onStartTrackingTouch(SeekBar seekBar) {}
			@Override public void onStopTrackingTouch(SeekBar seekBar) {}
		});
		updateWidthLabel.run();

		TextView colorLabel = new TextView(this);
		colorLabel.setText(R.string.image_editor_brush_color);
		LinearLayout.LayoutParams colorLabelParams = new LinearLayout.LayoutParams(
				ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
		colorLabelParams.topMargin = (int) (8f * density);
		layout.addView(colorLabel, colorLabelParams);
		GridLayout colorsLayout = new GridLayout(this);
		colorsLayout.setColumnCount(3);
		colorsLayout.setRowCount(2);
		LinearLayout.LayoutParams colorsParams = new LinearLayout.LayoutParams(
				ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
		colorsParams.gravity = Gravity.CENTER_HORIZONTAL;
		layout.addView(colorsLayout, colorsParams);
		int[] colors = {Color.BLACK, Color.WHITE, 0xffe53935, 0xffffd600, 0xff43a047, 0xff1e88e5};
		int[] pendingColor = {brushColor};
		ArrayList<View> chips = new ArrayList<>();
		Runnable updateColors = () -> {
			for (int i = 0; i < chips.size(); i++) {
				chips.get(i).setBackground(createColorChipBackground(colors[i], colors[i] == pendingColor[0], density));
			}
		};
		for (int color : colors) {
			View chip = new View(this);
			chip.setContentDescription(getString(R.string.image_editor_brush_color));
			chip.setOnClickListener(v -> {
				pendingColor[0] = color;
				updateColors.run();
			});
			GridLayout.LayoutParams params = new GridLayout.LayoutParams();
			params.width = (int) (48f * density);
			params.height = (int) (48f * density);
			params.setMargins((int) (4f * density), (int) (6f * density),
					(int) (4f * density), (int) (6f * density));
			colorsLayout.addView(chip, params);
			chips.add(chip);
		}
		updateColors.run();

		new AlertDialog.Builder(this).setTitle(R.string.image_editor_marker).setView(layout)
				.setNegativeButton(android.R.string.cancel, null)
				.setPositiveButton(android.R.string.ok, (dialog, which) -> {
					brushWidthRatio = (widthSeek.getProgress() + 5) / 1000f;
					brushColor = pendingColor[0];
					editorView.setBrush(brushWidthRatio, brushColor);
					editorView.setMode(EditorView.Mode.DRAW);
				}).show();
	}

	private static GradientDrawable createColorChipBackground(int color, boolean selected, float density) {
		GradientDrawable drawable = new GradientDrawable();
		drawable.setColor(color);
		drawable.setCornerRadius(8f * density);
		drawable.setStroke((int) ((selected ? 3f : 1f) * density),
				selected ? 0xff64b5f6 : 0xff808080);
		return drawable;
	}

	private void showStickerPicker() {
		float density = getResources().getDisplayMetrics().density;
		GridLayout grid = new GridLayout(this);
		grid.setColumnCount(3);
		grid.setRowCount((STICKERS.length + 2) / 3);
		int padding = (int) (8f * density);
		grid.setPadding(padding, padding, padding, padding);
		FrameLayout container = new FrameLayout(this);
		FrameLayout.LayoutParams gridParams = new FrameLayout.LayoutParams(
				ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER);
		container.addView(grid, gridParams);
		AlertDialog[] dialog = new AlertDialog[1];
		for (String sticker : STICKERS) {
			TextView button = new TextView(this, null, android.R.attr.borderlessButtonStyle);
			button.setText(sticker);
			button.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 28f);
			button.setGravity(Gravity.CENTER);
			button.setContentDescription(sticker);
			button.setOnClickListener(v -> {
				editorView.setSticker(sticker);
				dialog[0].dismiss();
			});
			GridLayout.LayoutParams params = new GridLayout.LayoutParams();
			params.width = (int) (72f * density);
			params.height = (int) (64f * density);
			params.setMargins((int) (2f * density), (int) (2f * density),
					(int) (2f * density), (int) (2f * density));
			grid.addView(button, params);
		}
		dialog[0] = new AlertDialog.Builder(this).setTitle(R.string.image_editor_sticker_hint)
				.setView(container).setNegativeButton(android.R.string.cancel, null).create();
		dialog[0].show();
	}

	private ImageButton addTool(int drawableResId, int descriptionResId, View.OnClickListener listener) {
		ImageButton button = createIconButton(drawableResId, descriptionResId);
		button.setOnClickListener(listener);
		int size = (int) (34f * getResources().getDisplayMetrics().density);
		tools.addView(button, size, ViewGroup.LayoutParams.MATCH_PARENT);
		return button;
	}

	private void leaveCropMode() {
		if (editorView != null && editorView.isCropping()) {
			editorView.setMode(EditorView.Mode.NONE);
			cropButton.setImageResource(R.drawable.ic_editor_crop);
			cropButton.setContentDescription(getString(R.string.image_editor_crop));
		}
	}

	private void updateHistoryButtons() {
		if (undoButton != null) {
			undoButton.setEnabled(editorView.canUndo());
			redoButton.setEnabled(editorView.canRedo());
			undoButton.setAlpha(undoButton.isEnabled() ? 1f : 0.35f);
			redoButton.setAlpha(redoButton.isEnabled() ? 1f : 0.35f);
		}
	}

	private void loadImage() {
		new Thread(() -> {
			FileHolder fileHolder = DraftsStorage.getInstance().getAttachmentDraftFileHolder(sourceHash);
			Bitmap bitmap = null;
			if (fileHolder != null) {
				sourceType = fileHolder.getImageType();
				try {
					bitmap = fileHolder.readImageBitmap(MAX_IMAGE_SIZE, false, false);
				} catch (OutOfMemoryError e) {
					// Handled as an unsupported image below.
				}
			}
			Bitmap result = bitmap;
			runOnUiThread(() -> {
				if (stopped) {
					if (result != null) result.recycle();
					return;
				}
				if (result == null) {
					showFailure(R.string.image_editor_load_failed);
					return;
				}
				editorView = new EditorView(this, result, this::updateHistoryButtons);
				content.removeAllViews();
				content.addView(editorView, ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT);
				((View) tools.getParent()).setVisibility(View.VISIBLE);
				saveButton.setEnabled(true);
				addToolButtons();
			});
		}, "ImageEditorLoad").start();
	}

	private void saveImage() {
		if (editorView == null) return;
		leaveCropMode();
		Bitmap bitmap = editorView.createResultBitmap();
		if (bitmap == null) {
			showFailure(R.string.image_editor_save_failed);
			return;
		}
		setBusy(R.string.image_editor_saving);
		new Thread(() -> {
			boolean png = sourceType == FileHolder.ImageType.IMAGE_PNG;
			String extension = png ? ".png" : ".jpg";
			File directory = new File(getCacheDir(), "image-editor");
			File tempFile = null;
			String hash = null;
			try {
				if ((directory.isDirectory() || directory.mkdirs())) {
					tempFile = File.createTempFile("edited-", extension, directory);
					try (FileOutputStream output = new FileOutputStream(tempFile)) {
						Bitmap.CompressFormat format = png ? Bitmap.CompressFormat.PNG : Bitmap.CompressFormat.JPEG;
						if (!bitmap.compress(format, png ? 100 : 94, output)) throw new IOException();
					}
					FileHolder fileHolder = FileHolder.obtain(tempFile);
					if (fileHolder != null) hash = DraftsStorage.getInstance().storeAttachmentFile(fileHolder);
				}
			} catch (IOException | OutOfMemoryError e) {
				// Reported on the UI thread.
			} finally {
				bitmap.recycle();
				if (tempFile != null) tempFile.delete();
			}
			String resultHash = hash;
			runOnUiThread(() -> {
				if (stopped) return;
				if (resultHash == null) {
					setBusy(null);
					showFailure(R.string.image_editor_save_failed);
					return;
				}
				String resultName = createEditedName(sourceName, extension);
				setResult(RESULT_OK, new Intent()
						.putExtra(EXTRA_RESULT_HASH, resultHash)
						.putExtra(EXTRA_RESULT_NAME, resultName)
						.putExtra(EXTRA_RESULT_ATTACHMENT_INDEX, attachmentIndex));
				finish();
			});
		}, "ImageEditorSave").start();
	}

	private static String createEditedName(String name, String extension) {
		int dot = name.lastIndexOf('.');
		String base = dot > 0 ? name.substring(0, dot) : name;
		return base + "_edited" + extension;
	}

	private void setBusy(Integer messageResId) {
		boolean busy = messageResId != null;
		saveButton.setEnabled(!busy);
		((View) tools.getParent()).setVisibility(busy ? View.GONE : View.VISIBLE);
		if (busy) {
			progressBar = new ProgressBar(this);
			statusView = new TextView(this);
			statusView.setText(messageResId);
			statusView.setTextColor(Color.WHITE);
			statusView.setGravity(Gravity.CENTER);
			content.addView(progressBar, ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
			((FrameLayout.LayoutParams) progressBar.getLayoutParams()).gravity = Gravity.CENTER;
			FrameLayout.LayoutParams params = new FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,
					ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER);
			params.topMargin = (int) (56f * getResources().getDisplayMetrics().density);
			content.addView(statusView, params);
		} else {
			if (progressBar != null) content.removeView(progressBar);
			if (statusView != null) content.removeView(statusView);
		}
	}

	private void showFailure(int messageResId) {
		new AlertDialog.Builder(this).setMessage(messageResId).setPositiveButton(android.R.string.ok, null).show();
	}

	@Override
	protected void onDestroy() {
		stopped = true;
		if (editorView != null) editorView.release();
		super.onDestroy();
	}

	private static class EditorView extends View {
		enum Mode {NONE, CROP, DRAW, ERASE, STICKER}
		private static final int MAX_HISTORY = 2;
		private static final int CROP_LEFT = 1;
		private static final int CROP_TOP = 2;
		private static final int CROP_RIGHT = 4;
		private static final int CROP_BOTTOM = 8;
		private static final int CROP_MOVE = 16;
		private static final int STICKER_TOUCH_NONE = 0;
		private static final int STICKER_TOUCH_MOVE = 1;
		private static final int STICKER_TOUCH_SCALE = 2;
		private static final float MAX_VIEWPORT_SCALE = 6f;

		private Bitmap baseBitmap;
		private Bitmap overlayBitmap;
		private Canvas overlayCanvas;
		private final Matrix bitmapToView = new Matrix();
		private final Matrix viewToBitmap = new Matrix();
		private final RectF cropRect = new RectF();
		private final RectF cropStartRect = new RectF();
		private final Paint bitmapPaint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
		private final Paint cropPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
		private final Paint brushPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
		private final Path brushPath = new Path();
		private final ArrayDeque<Snapshot> undo = new ArrayDeque<>();
		private final ArrayDeque<Snapshot> redo = new ArrayDeque<>();
		private final ArrayList<StickerPlacement> stickers = new ArrayList<>();
		private final Runnable historyChanged;
		private Mode mode = Mode.NONE;
		private String selectedSticker;
		private StickerPlacement activeSticker;
		private float brushWidthRatio = 0.035f;
		private int brushColor = Color.BLACK;
		private int cropTouch;
		private float touchStartX;
		private float touchStartY;
		private float lastBrushX;
		private float lastBrushY;
		private boolean brushStarted;
		private float stickerDragOffsetX;
		private float stickerDragOffsetY;
		private int stickerTouchMode;
		private boolean stickerHistoryPushed;
		private float stickerScaleStartDistance;
		private float stickerScaleStartSize;
		private boolean stickerPendingPlacement;
		private float stickerPendingX;
		private float stickerPendingY;
		private float viewportScale = 1f;
		private float viewportOffsetX;
		private float viewportOffsetY;
		private boolean viewportGesture;
		private float viewportGestureStartDistance;
		private float viewportGestureStartScale;
		private float viewportFocusBitmapX;
		private float viewportFocusBitmapY;

		EditorView(Context context, Bitmap source, Runnable historyChanged) {
			super(context);
			this.historyChanged = historyChanged;
			baseBitmap = ensureArgbBitmap(source);
			overlayBitmap = Bitmap.createBitmap(baseBitmap.getWidth(), baseBitmap.getHeight(), Bitmap.Config.ARGB_8888);
			overlayCanvas = new Canvas(overlayBitmap);
			cropPaint.setStyle(Paint.Style.STROKE);
			cropPaint.setStrokeWidth(2f * getResources().getDisplayMetrics().density);
			cropPaint.setColor(Color.WHITE);
			brushPaint.setStyle(Paint.Style.STROKE);
			brushPaint.setStrokeCap(Paint.Cap.ROUND);
			brushPaint.setStrokeJoin(Paint.Join.ROUND);
			setBackgroundColor(Color.BLACK);
		}

		private static Bitmap ensureArgbBitmap(Bitmap source) {
			if (source.isMutable() && source.getConfig() == Bitmap.Config.ARGB_8888) return source;
			Bitmap copy = source.copy(Bitmap.Config.ARGB_8888, true);
			if (copy != source) source.recycle();
			return copy;
		}

		void setMode(Mode mode) {
			if (this.mode == Mode.STICKER && mode != Mode.STICKER) {
				commitActiveSticker();
			}
			this.mode = mode;
			if (mode != Mode.STICKER) selectedSticker = null;
			if (mode == Mode.CROP) {
				float dx = baseBitmap.getWidth() * 0.08f;
				float dy = baseBitmap.getHeight() * 0.08f;
				cropRect.set(dx, dy, baseBitmap.getWidth() - dx, baseBitmap.getHeight() - dy);
			}
			invalidate();
		}

		void setSticker(String sticker) {
			commitActiveSticker();
			selectedSticker = sticker;
			mode = Mode.STICKER;
			invalidate();
		}

		void setBrush(float widthRatio, int color) {
			brushWidthRatio = Math.max(0.005f, Math.min(0.1f, widthRatio));
			brushColor = color;
		}

		boolean isCropping() { return mode == Mode.CROP; }
		boolean canUndo() { return !undo.isEmpty(); }
		boolean canRedo() { return !redo.isEmpty(); }

		@Override
		protected void onSizeChanged(int w, int h, int oldw, int oldh) {
			updateMatrices();
		}

		private void updateMatrices() {
			if (baseBitmap == null || getWidth() == 0 || getHeight() == 0) return;
			float fitScale = Math.min((float) getWidth() / baseBitmap.getWidth(),
					(float) getHeight() / baseBitmap.getHeight());
			float scale = fitScale * viewportScale;
			clampViewportOffset(scale);
			float dx = (getWidth() - baseBitmap.getWidth() * scale) / 2f;
			float dy = (getHeight() - baseBitmap.getHeight() * scale) / 2f;
			bitmapToView.reset();
			bitmapToView.postScale(scale, scale);
			bitmapToView.postTranslate(dx + viewportOffsetX, dy + viewportOffsetY);
			bitmapToView.invert(viewToBitmap);
		}

		private void clampViewportOffset(float scale) {
			float overflowX = Math.max(0f, baseBitmap.getWidth() * scale - getWidth()) / 2f;
			float overflowY = Math.max(0f, baseBitmap.getHeight() * scale - getHeight()) / 2f;
			viewportOffsetX = Math.max(-overflowX, Math.min(overflowX, viewportOffsetX));
			viewportOffsetY = Math.max(-overflowY, Math.min(overflowY, viewportOffsetY));
		}

		private void resetViewport() {
			viewportScale = 1f;
			viewportOffsetX = 0f;
			viewportOffsetY = 0f;
			viewportGesture = false;
			updateMatrices();
		}

		@Override
		protected void onDraw(Canvas canvas) {
			super.onDraw(canvas);
			if (baseBitmap == null) return;
			canvas.drawBitmap(baseBitmap, bitmapToView, bitmapPaint);
			canvas.drawBitmap(overlayBitmap, bitmapToView, bitmapPaint);
			if (!stickers.isEmpty()) {
				int save = canvas.save();
				canvas.concat(bitmapToView);
				for (StickerPlacement sticker : stickers) drawSticker(canvas, sticker);
				canvas.restoreToCount(save);
			}
			if (activeSticker != null) drawActiveSticker(canvas);
			if (mode == Mode.CROP) drawCrop(canvas);
		}

		private void drawActiveSticker(Canvas canvas) {
			int save = canvas.save();
			canvas.concat(bitmapToView);
			Paint border = new Paint(Paint.ANTI_ALIAS_FLAG);
			border.setColor(Color.WHITE);
			border.setStyle(Paint.Style.STROKE);
			border.setStrokeWidth(2f / Math.max(0.01f, getMatrixScale()));
			float half = activeSticker.size * 0.55f;
			canvas.drawRect(activeSticker.x - half, activeSticker.y - half,
					activeSticker.x + half, activeSticker.y + half, border);
			float inverseScale = 1f / Math.max(0.01f, getMatrixScale());
			float deleteHandle = 40f * inverseScale;
			float resizeHandle = 58f * inverseScale;
			border.setStyle(Paint.Style.FILL);
			border.setColor(0xffd32f2f);
			canvas.drawCircle(activeSticker.x - half, activeSticker.y - half, deleteHandle, border);
			border.setColor(Color.WHITE);
			border.setStrokeWidth(5f * inverseScale);
			border.setStyle(Paint.Style.STROKE);
			float cross = 30f * inverseScale;
			canvas.drawLine(activeSticker.x - half - cross, activeSticker.y - half - cross,
					activeSticker.x - half + cross, activeSticker.y - half + cross, border);
			canvas.drawLine(activeSticker.x - half + cross, activeSticker.y - half - cross,
					activeSticker.x - half - cross, activeSticker.y - half + cross, border);
			border.setStyle(Paint.Style.FILL);
			canvas.drawCircle(activeSticker.x + half, activeSticker.y + half, resizeHandle, border);
			border.setColor(0xff202124);
			canvas.drawCircle(activeSticker.x + half, activeSticker.y + half, 50f * inverseScale, border);
			canvas.restoreToCount(save);
		}

		private static void drawSticker(Canvas canvas, StickerPlacement sticker) {
			Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
			paint.setTextAlign(Paint.Align.CENTER);
			paint.setTextSize(sticker.size);
			Paint.FontMetrics metrics = paint.getFontMetrics();
			canvas.drawText(sticker.text, sticker.x,
					sticker.y - (metrics.ascent + metrics.descent) / 2f, paint);
		}

		private void drawCrop(Canvas canvas) {
			RectF image = new RectF(0f, 0f, baseBitmap.getWidth(), baseBitmap.getHeight());
			RectF crop = new RectF(cropRect);
			bitmapToView.mapRect(image);
			bitmapToView.mapRect(crop);
			Paint shade = new Paint();
			shade.setColor(0x99000000);
			canvas.drawRect(image.left, image.top, image.right, crop.top, shade);
			canvas.drawRect(image.left, crop.bottom, image.right, image.bottom, shade);
			canvas.drawRect(image.left, crop.top, crop.left, crop.bottom, shade);
			canvas.drawRect(crop.right, crop.top, image.right, crop.bottom, shade);
			canvas.drawRect(crop, cropPaint);
			float radius = 5f * getResources().getDisplayMetrics().density;
			Paint handle = new Paint(Paint.ANTI_ALIAS_FLAG);
			handle.setColor(Color.WHITE);
			canvas.drawCircle(crop.left, crop.top, radius, handle);
			canvas.drawCircle(crop.right, crop.top, radius, handle);
			canvas.drawCircle(crop.left, crop.bottom, radius, handle);
			canvas.drawCircle(crop.right, crop.bottom, radius, handle);
		}

		@Override
		public boolean onTouchEvent(MotionEvent event) {
			if (handleViewportTouch(event)) return true;
			float[] point = {event.getX(), event.getY()};
			viewToBitmap.mapPoints(point);
			float x = point[0], y = point[1];
			if (x < 0 || y < 0 || x > baseBitmap.getWidth() || y > baseBitmap.getHeight()) {
				if (event.getActionMasked() == MotionEvent.ACTION_DOWN) return true;
				x = Math.max(0f, Math.min(baseBitmap.getWidth(), x));
				y = Math.max(0f, Math.min(baseBitmap.getHeight(), y));
			}
			switch (mode) {
				case CROP: return handleCropTouch(event, x, y);
				case DRAW:
				case ERASE: return handleBrushTouch(event, x, y);
				case STICKER: return handleStickerTouch(event, x, y);
				default: return true;
			}
		}

		private boolean handleViewportTouch(MotionEvent event) {
			switch (event.getActionMasked()) {
				case MotionEvent.ACTION_POINTER_DOWN: {
					if (event.getPointerCount() >= 2) {
						stickerPendingPlacement = false;
						stickerTouchMode = STICKER_TOUCH_NONE;
						brushStarted = false;
						brushPath.reset();
						viewportGesture = true;
						viewportGestureStartDistance = getPointerDistanceView(event);
						viewportGestureStartScale = viewportScale;
						float[] focus = getPointerCenterView(event);
						viewToBitmap.mapPoints(focus);
						viewportFocusBitmapX = focus[0];
						viewportFocusBitmapY = focus[1];
						return true;
					}
					break;
				}
				case MotionEvent.ACTION_MOVE: {
					if (viewportGesture) {
						if (event.getPointerCount() >= 2 && viewportGestureStartDistance > 0f) {
							float newScale = viewportGestureStartScale * getPointerDistanceView(event)
									/ viewportGestureStartDistance;
							viewportScale = Math.max(1f, Math.min(MAX_VIEWPORT_SCALE, newScale));
							float fitScale = Math.min((float) getWidth() / baseBitmap.getWidth(),
									(float) getHeight() / baseBitmap.getHeight());
							float scale = fitScale * viewportScale;
							float[] focus = getPointerCenterView(event);
							float centeredX = (getWidth() - baseBitmap.getWidth() * scale) / 2f;
							float centeredY = (getHeight() - baseBitmap.getHeight() * scale) / 2f;
							viewportOffsetX = focus[0] - centeredX - viewportFocusBitmapX * scale;
							viewportOffsetY = focus[1] - centeredY - viewportFocusBitmapY * scale;
							updateMatrices();
							invalidate();
						}
						return true;
					}
					break;
				}
				case MotionEvent.ACTION_POINTER_UP: {
					if (viewportGesture) return true;
					break;
				}
				case MotionEvent.ACTION_UP:
				case MotionEvent.ACTION_CANCEL: {
					if (viewportGesture) {
						viewportGesture = false;
						viewportGestureStartDistance = 0f;
						return true;
					}
					break;
				}
			}
			return false;
		}

		private static float getPointerDistanceView(MotionEvent event) {
			return (float) Math.hypot(event.getX(1) - event.getX(0), event.getY(1) - event.getY(0));
		}

		private static float[] getPointerCenterView(MotionEvent event) {
			return new float[] {(event.getX(0) + event.getX(1)) / 2f,
					(event.getY(0) + event.getY(1)) / 2f};
		}

		private boolean handleStickerTouch(MotionEvent event, float x, float y) {
			switch (event.getActionMasked()) {
				case MotionEvent.ACTION_DOWN: {
					stickerTouchMode = STICKER_TOUCH_NONE;
					stickerHistoryPushed = false;
					if (activeSticker != null) {
						float half = activeSticker.size * 0.55f;
						float inverseScale = 1f / Math.max(0.01f, getMatrixScale());
						float deleteTolerance = 60f * inverseScale;
						float resizeTolerance = 76f * inverseScale;
						if (Math.hypot(x - (activeSticker.x - half), y - (activeSticker.y - half))
								<= deleteTolerance) {
							pushHistory();
							stickers.remove(activeSticker);
							activeSticker = null;
							historyChanged.run();
							invalidate();
							return true;
						}
						if (Math.hypot(x - (activeSticker.x + half), y - (activeSticker.y + half))
								<= resizeTolerance) {
							stickerTouchMode = STICKER_TOUCH_SCALE;
							stickerScaleStartDistance = Math.max(1f,
									(float) Math.hypot(x - activeSticker.x, y - activeSticker.y));
							stickerScaleStartSize = activeSticker.size;
							return true;
						}
					}
					activeSticker = findSticker(x, y);
					stickerPendingPlacement = activeSticker == null && selectedSticker != null;
					stickerPendingX = x;
					stickerPendingY = y;
					if (activeSticker != null) {
						stickerTouchMode = STICKER_TOUCH_MOVE;
						stickerDragOffsetX = x - activeSticker.x;
						stickerDragOffsetY = y - activeSticker.y;
					}
					invalidate();
					return true;
				}
				case MotionEvent.ACTION_MOVE: {
					if (stickerPendingPlacement) placeSticker(stickerPendingX, stickerPendingY);
					if (activeSticker == null) return true;
					if (stickerTouchMode == STICKER_TOUCH_SCALE && stickerScaleStartDistance > 0f) {
						ensureStickerHistory();
						float distance = (float) Math.hypot(x - activeSticker.x, y - activeSticker.y);
						float minimum = Math.min(baseBitmap.getWidth(), baseBitmap.getHeight()) * 0.06f;
						float maximum = Math.min(baseBitmap.getWidth(), baseBitmap.getHeight()) * 0.75f;
						activeSticker.size = Math.max(minimum, Math.min(maximum,
								stickerScaleStartSize * distance / stickerScaleStartDistance));
					} else if (stickerTouchMode == STICKER_TOUCH_MOVE) {
						ensureStickerHistory();
						activeSticker.x = x - stickerDragOffsetX;
						activeSticker.y = y - stickerDragOffsetY;
					}
					clampActiveSticker();
					invalidate();
					return true;
				}
				case MotionEvent.ACTION_UP:
				case MotionEvent.ACTION_CANCEL: {
					if (event.getActionMasked() == MotionEvent.ACTION_UP && stickerPendingPlacement) {
						placeSticker(x, y);
					}
					stickerPendingPlacement = false;
					stickerTouchMode = STICKER_TOUCH_NONE;
					stickerScaleStartDistance = 0f;
					if (stickerHistoryPushed) historyChanged.run();
					stickerHistoryPushed = false;
					invalidate();
					return true;
				}
			}
			return true;
		}

		private void placeSticker(float x, float y) {
			if (!stickerPendingPlacement || selectedSticker == null) return;
			pushHistory();
			stickerHistoryPushed = true;
			float size = Math.max(48f,
					Math.min(baseBitmap.getWidth(), baseBitmap.getHeight()) * 0.16f);
			activeSticker = new StickerPlacement(selectedSticker, x, y, size);
			stickers.add(activeSticker);
			stickerPendingPlacement = false;
			stickerTouchMode = STICKER_TOUCH_MOVE;
			stickerDragOffsetX = 0f;
			stickerDragOffsetY = 0f;
			clampActiveSticker();
			historyChanged.run();
		}

		private void ensureStickerHistory() {
			if (!stickerHistoryPushed) {
				pushHistory();
				stickerHistoryPushed = true;
			}
		}

		private StickerPlacement findSticker(float x, float y) {
			for (int i = stickers.size() - 1; i >= 0; i--) {
				StickerPlacement sticker = stickers.get(i);
				float half = sticker.size * 0.65f;
				if (Math.abs(x - sticker.x) <= half && Math.abs(y - sticker.y) <= half) return sticker;
			}
			return null;
		}

		private void clampActiveSticker() {
			if (activeSticker != null) clampSticker(activeSticker);
		}

		private void clampSticker(StickerPlacement sticker) {
			float margin = sticker.size * 0.5f;
			sticker.x = Math.max(margin, Math.min(baseBitmap.getWidth() - margin, sticker.x));
			sticker.y = Math.max(margin, Math.min(baseBitmap.getHeight() - margin, sticker.y));
		}

		private void commitActiveSticker() {
			activeSticker = null;
			stickerTouchMode = STICKER_TOUCH_NONE;
			stickerScaleStartDistance = 0f;
			invalidate();
		}

		private boolean handleBrushTouch(MotionEvent event, float x, float y) {
			switch (event.getActionMasked()) {
				case MotionEvent.ACTION_DOWN: {
					configureBrush();
					brushPath.reset();
					brushPath.moveTo(x, y);
					lastBrushX = x;
					lastBrushY = y;
					brushStarted = false;
					return true;
				}
				case MotionEvent.ACTION_MOVE: {
					if (!brushStarted) {
						pushHistory();
						brushStarted = true;
						overlayCanvas.drawPoint(lastBrushX, lastBrushY, brushPaint);
					}
					float middleX = (lastBrushX + x) / 2f;
					float middleY = (lastBrushY + y) / 2f;
					brushPath.quadTo(lastBrushX, lastBrushY, middleX, middleY);
					overlayCanvas.drawPath(brushPath, brushPaint);
					lastBrushX = x;
					lastBrushY = y;
					invalidate();
					return true;
				}
				case MotionEvent.ACTION_UP:
				case MotionEvent.ACTION_CANCEL: {
					if (!brushStarted && event.getActionMasked() == MotionEvent.ACTION_UP) {
						pushHistory();
						brushStarted = true;
						overlayCanvas.drawPoint(x, y, brushPaint);
					} else if (brushStarted) {
						brushPath.lineTo(x, y);
						overlayCanvas.drawPath(brushPath, brushPaint);
					}
					invalidate();
					if (brushStarted) historyChanged.run();
					brushStarted = false;
					return true;
				}
			}
			return true;
		}

		private void configureBrush() {
			brushPaint.setStrokeWidth(Math.max(2f,
					Math.min(baseBitmap.getWidth(), baseBitmap.getHeight()) * brushWidthRatio));
			if (mode == Mode.ERASE) {
				brushPaint.setColor(Color.TRANSPARENT);
				brushPaint.setXfermode(new PorterDuffXfermode(PorterDuff.Mode.CLEAR));
			} else {
				brushPaint.setColor(brushColor);
				brushPaint.setXfermode(null);
			}
		}

		private boolean handleCropTouch(MotionEvent event, float x, float y) {
			float tolerance = 32f / Math.max(0.01f, getMatrixScale());
			switch (event.getActionMasked()) {
				case MotionEvent.ACTION_DOWN: {
					cropTouch = 0;
					if (Math.abs(x - cropRect.left) <= tolerance) cropTouch |= CROP_LEFT;
					if (Math.abs(x - cropRect.right) <= tolerance) cropTouch |= CROP_RIGHT;
					if (Math.abs(y - cropRect.top) <= tolerance) cropTouch |= CROP_TOP;
					if (Math.abs(y - cropRect.bottom) <= tolerance) cropTouch |= CROP_BOTTOM;
					if (cropTouch == 0 && cropRect.contains(x, y)) cropTouch = CROP_MOVE;
					if (cropTouch == 0) return true;
					touchStartX = x;
					touchStartY = y;
					cropStartRect.set(cropRect);
					return true;
				}
				case MotionEvent.ACTION_MOVE: {
					if (cropTouch == 0) return true;
					float dx = x - touchStartX, dy = y - touchStartY;
					if (cropTouch == CROP_MOVE) {
						dx = Math.max(-cropStartRect.left,
								Math.min(dx, baseBitmap.getWidth() - cropStartRect.right));
						dy = Math.max(-cropStartRect.top,
								Math.min(dy, baseBitmap.getHeight() - cropStartRect.bottom));
						cropRect.set(cropStartRect);
						cropRect.offset(dx, dy);
					} else {
						cropRect.set(cropStartRect);
						float min = Math.max(24f, Math.min(baseBitmap.getWidth(), baseBitmap.getHeight()) * 0.03f);
						if ((cropTouch & CROP_LEFT) != 0) cropRect.left = Math.max(0f,
								Math.min(cropStartRect.left + dx, cropRect.right - min));
						if ((cropTouch & CROP_RIGHT) != 0) cropRect.right = Math.min(baseBitmap.getWidth(),
								Math.max(cropStartRect.right + dx, cropRect.left + min));
						if ((cropTouch & CROP_TOP) != 0) cropRect.top = Math.max(0f,
								Math.min(cropStartRect.top + dy, cropRect.bottom - min));
						if ((cropTouch & CROP_BOTTOM) != 0) cropRect.bottom = Math.min(baseBitmap.getHeight(),
								Math.max(cropStartRect.bottom + dy, cropRect.top + min));
					}
					invalidate();
					return true;
				}
				case MotionEvent.ACTION_UP:
				case MotionEvent.ACTION_CANCEL: cropTouch = 0; return true;
			}
			return true;
		}

		private float getMatrixScale() {
			float[] values = new float[9];
			bitmapToView.getValues(values);
			return values[Matrix.MSCALE_X];
		}

		void applyCrop() {
			if (mode != Mode.CROP) return;
			commitActiveSticker();
			int left = Math.max(0, Math.round(cropRect.left));
			int top = Math.max(0, Math.round(cropRect.top));
			int right = Math.min(baseBitmap.getWidth(), Math.round(cropRect.right));
			int bottom = Math.min(baseBitmap.getHeight(), Math.round(cropRect.bottom));
			if (right > left && bottom > top && (left > 0 || top > 0 || right < baseBitmap.getWidth()
					|| bottom < baseBitmap.getHeight())) {
				pushHistory();
				replaceBitmaps(Bitmap.createBitmap(baseBitmap, left, top, right - left, bottom - top),
						Bitmap.createBitmap(overlayBitmap, left, top, right - left, bottom - top));
				for (int i = stickers.size() - 1; i >= 0; i--) {
					StickerPlacement sticker = stickers.get(i);
					sticker.x -= left;
					sticker.y -= top;
					float half = sticker.size * 0.55f;
					if (sticker.x + half < 0f || sticker.y + half < 0f
							|| sticker.x - half > baseBitmap.getWidth()
							|| sticker.y - half > baseBitmap.getHeight()) {
						stickers.remove(i);
					} else {
						clampSticker(sticker);
					}
				}
				historyChanged.run();
			}
			mode = Mode.NONE;
			invalidate();
		}

		void rotate() {
			commitActiveSticker();
			pushHistory();
			int oldHeight = baseBitmap.getHeight();
			Matrix matrix = new Matrix();
			matrix.postRotate(90f);
			replaceBitmaps(Bitmap.createBitmap(baseBitmap, 0, 0, baseBitmap.getWidth(), baseBitmap.getHeight(),
					matrix, true), Bitmap.createBitmap(overlayBitmap, 0, 0, overlayBitmap.getWidth(),
					overlayBitmap.getHeight(), matrix, true));
			for (StickerPlacement sticker : stickers) {
				float oldX = sticker.x;
				sticker.x = oldHeight - sticker.y;
				sticker.y = oldX;
				clampSticker(sticker);
			}
			historyChanged.run();
		}

		void flip() {
			commitActiveSticker();
			pushHistory();
			int oldWidth = baseBitmap.getWidth();
			Matrix matrix = new Matrix();
			matrix.postScale(-1f, 1f);
			replaceBitmaps(Bitmap.createBitmap(baseBitmap, 0, 0, baseBitmap.getWidth(), baseBitmap.getHeight(),
					matrix, true), Bitmap.createBitmap(overlayBitmap, 0, 0, overlayBitmap.getWidth(),
					overlayBitmap.getHeight(), matrix, true));
			for (StickerPlacement sticker : stickers) {
				sticker.x = oldWidth - sticker.x;
				clampSticker(sticker);
			}
			historyChanged.run();
		}

		private void replaceBitmaps(Bitmap base, Bitmap overlay) {
			baseBitmap.recycle();
			overlayBitmap.recycle();
			baseBitmap = ensureArgbBitmap(base);
			overlayBitmap = ensureArgbBitmap(overlay);
			overlayCanvas = new Canvas(overlayBitmap);
			resetViewport();
			invalidate();
		}

		private void pushHistory() {
			try {
				undo.addLast(new Snapshot(baseBitmap, overlayBitmap, stickers));
				while (undo.size() > MAX_HISTORY) undo.removeFirst().recycle();
				clearHistory(redo);
			} catch (OutOfMemoryError e) {
				clearHistory(undo);
				clearHistory(redo);
			}
		}

		void undo() {
			commitActiveSticker();
			if (undo.isEmpty()) return;
			redo.addLast(new Snapshot(baseBitmap, overlayBitmap, stickers));
			while (redo.size() > MAX_HISTORY) redo.removeFirst().recycle();
			restore(undo.removeLast());
		}

		void redo() {
			commitActiveSticker();
			if (redo.isEmpty()) return;
			undo.addLast(new Snapshot(baseBitmap, overlayBitmap, stickers));
			while (undo.size() > MAX_HISTORY) undo.removeFirst().recycle();
			restore(redo.removeLast());
		}

		private void restore(Snapshot snapshot) {
			baseBitmap.recycle();
			overlayBitmap.recycle();
			baseBitmap = snapshot.base;
			overlayBitmap = snapshot.overlay;
			overlayCanvas = new Canvas(overlayBitmap);
			stickers.clear();
			stickers.addAll(snapshot.stickers);
			mode = Mode.NONE;
			selectedSticker = null;
			activeSticker = null;
			resetViewport();
			invalidate();
			historyChanged.run();
		}

		Bitmap createResultBitmap() {
			commitActiveSticker();
			try {
				Bitmap result = baseBitmap.copy(Bitmap.Config.ARGB_8888, true);
				Canvas canvas = new Canvas(result);
				canvas.drawBitmap(overlayBitmap, 0f, 0f, bitmapPaint);
				for (StickerPlacement sticker : stickers) drawSticker(canvas, sticker);
				return result;
			} catch (OutOfMemoryError e) {
				return null;
			}
		}

		void release() {
			if (baseBitmap != null && !baseBitmap.isRecycled()) baseBitmap.recycle();
			if (overlayBitmap != null && !overlayBitmap.isRecycled()) overlayBitmap.recycle();
			clearHistory(undo);
			clearHistory(redo);
		}

		private static void clearHistory(ArrayDeque<Snapshot> history) {
			while (!history.isEmpty()) history.removeFirst().recycle();
		}

		private static class Snapshot {
			final Bitmap base;
			final Bitmap overlay;
			final ArrayList<StickerPlacement> stickers;

			Snapshot(Bitmap base, Bitmap overlay, ArrayList<StickerPlacement> stickers) {
				this.base = base.copy(Bitmap.Config.ARGB_8888, true);
				this.overlay = overlay.copy(Bitmap.Config.ARGB_8888, true);
				this.stickers = new ArrayList<>(stickers.size());
				for (StickerPlacement sticker : stickers) this.stickers.add(new StickerPlacement(sticker));
			}

			void recycle() {
				base.recycle();
				overlay.recycle();
			}
		}

		private static class StickerPlacement {
			final String text;
			float x;
			float y;
			float size;

			StickerPlacement(String text, float x, float y, float size) {
				this.text = text;
				this.x = x;
				this.y = y;
				this.size = size;
			}

			StickerPlacement(StickerPlacement other) {
				this(other.text, other.x, other.y, other.size);
			}
		}
	}
}
