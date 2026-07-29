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
import android.os.Bundle;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowInsets;
import android.view.WindowInsetsController;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
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
			editorView.setMode(EditorView.Mode.DRAW);
		});
		addTool(R.drawable.ic_editor_eraser, R.string.image_editor_eraser, v -> {
			leaveCropMode();
			editorView.setMode(EditorView.Mode.ERASE);
		});
		addTool(R.drawable.ic_editor_sticker, R.string.image_editor_sticker, v -> {
			leaveCropMode();
			new AlertDialog.Builder(this).setTitle(R.string.image_editor_sticker_hint)
					.setItems(STICKERS, (dialog, which) -> editorView.setSticker(STICKERS[which])).show();
		});
		updateHistoryButtons();
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
		private final Runnable historyChanged;
		private Mode mode = Mode.NONE;
		private String selectedSticker;
		private StickerPlacement activeSticker;
		private int cropTouch;
		private float touchStartX;
		private float touchStartY;
		private float lastBrushX;
		private float lastBrushY;
		private float stickerDragOffsetX;
		private float stickerDragOffsetY;
		private float stickerScaleStartDistance;
		private float stickerScaleStartSize;

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

		boolean isCropping() { return mode == Mode.CROP; }
		boolean canUndo() { return !undo.isEmpty(); }
		boolean canRedo() { return !redo.isEmpty(); }

		@Override
		protected void onSizeChanged(int w, int h, int oldw, int oldh) {
			updateMatrices();
		}

		private void updateMatrices() {
			if (baseBitmap == null || getWidth() == 0 || getHeight() == 0) return;
			float scale = Math.min((float) getWidth() / baseBitmap.getWidth(),
					(float) getHeight() / baseBitmap.getHeight());
			float dx = (getWidth() - baseBitmap.getWidth() * scale) / 2f;
			float dy = (getHeight() - baseBitmap.getHeight() * scale) / 2f;
			bitmapToView.reset();
			bitmapToView.postScale(scale, scale);
			bitmapToView.postTranslate(dx, dy);
			bitmapToView.invert(viewToBitmap);
		}

		@Override
		protected void onDraw(Canvas canvas) {
			super.onDraw(canvas);
			if (baseBitmap == null) return;
			canvas.drawBitmap(baseBitmap, bitmapToView, bitmapPaint);
			canvas.drawBitmap(overlayBitmap, bitmapToView, bitmapPaint);
			if (activeSticker != null) drawActiveSticker(canvas);
			if (mode == Mode.CROP) drawCrop(canvas);
		}

		private void drawActiveSticker(Canvas canvas) {
			int save = canvas.save();
			canvas.concat(bitmapToView);
			drawSticker(canvas, activeSticker);
			Paint border = new Paint(Paint.ANTI_ALIAS_FLAG);
			border.setColor(Color.WHITE);
			border.setStyle(Paint.Style.STROKE);
			border.setStrokeWidth(2f / Math.max(0.01f, getMatrixScale()));
			float half = activeSticker.size * 0.55f;
			canvas.drawRect(activeSticker.x - half, activeSticker.y - half,
					activeSticker.x + half, activeSticker.y + half, border);
			border.setStyle(Paint.Style.FILL);
			float handle = 5f / Math.max(0.01f, getMatrixScale());
			canvas.drawCircle(activeSticker.x + half, activeSticker.y + half, handle, border);
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
			float[] point = {event.getX(), event.getY()};
			viewToBitmap.mapPoints(point);
			float x = point[0], y = point[1];
			if (x < 0 || y < 0 || x > baseBitmap.getWidth() || y > baseBitmap.getHeight()) return true;
			switch (mode) {
				case CROP: return handleCropTouch(event, x, y);
				case DRAW:
				case ERASE: return handleBrushTouch(event, x, y);
				case STICKER: return handleStickerTouch(event, x, y);
				default: return true;
			}
		}

		private boolean handleStickerTouch(MotionEvent event, float x, float y) {
			switch (event.getActionMasked()) {
				case MotionEvent.ACTION_DOWN: {
					if (activeSticker == null) {
						if (selectedSticker == null) return true;
						pushHistory();
						float size = Math.max(48f,
								Math.min(baseBitmap.getWidth(), baseBitmap.getHeight()) * 0.16f);
						activeSticker = new StickerPlacement(selectedSticker, x, y, size);
						clampActiveSticker();
						historyChanged.run();
					} else {
						float half = activeSticker.size * 0.65f;
						if (Math.abs(x - activeSticker.x) > half || Math.abs(y - activeSticker.y) > half) {
							activeSticker.x = x;
							activeSticker.y = y;
							clampActiveSticker();
						}
					}
					stickerDragOffsetX = x - activeSticker.x;
					stickerDragOffsetY = y - activeSticker.y;
					invalidate();
					return true;
				}
				case MotionEvent.ACTION_POINTER_DOWN: {
					if (activeSticker != null && event.getPointerCount() >= 2) {
						stickerScaleStartDistance = getPointerDistance(event);
						stickerScaleStartSize = activeSticker.size;
					}
					return true;
				}
				case MotionEvent.ACTION_MOVE: {
					if (activeSticker == null) return true;
					if (event.getPointerCount() >= 2 && stickerScaleStartDistance > 0f) {
						float distance = getPointerDistance(event);
						float minimum = Math.min(baseBitmap.getWidth(), baseBitmap.getHeight()) * 0.06f;
						float maximum = Math.min(baseBitmap.getWidth(), baseBitmap.getHeight()) * 0.75f;
						activeSticker.size = Math.max(minimum, Math.min(maximum,
								stickerScaleStartSize * distance / stickerScaleStartDistance));
						float[] center = getPointerCenter(event);
						activeSticker.x = center[0];
						activeSticker.y = center[1];
					} else {
						activeSticker.x = x - stickerDragOffsetX;
						activeSticker.y = y - stickerDragOffsetY;
					}
					clampActiveSticker();
					invalidate();
					return true;
				}
				case MotionEvent.ACTION_POINTER_UP: {
					stickerScaleStartDistance = 0f;
					return true;
				}
				case MotionEvent.ACTION_UP:
				case MotionEvent.ACTION_CANCEL: {
					stickerScaleStartDistance = 0f;
					invalidate();
					return true;
				}
			}
			return true;
		}

		private float getPointerDistance(MotionEvent event) {
			float[] points = {event.getX(0), event.getY(0), event.getX(1), event.getY(1)};
			viewToBitmap.mapPoints(points);
			return (float) Math.hypot(points[2] - points[0], points[3] - points[1]);
		}

		private float[] getPointerCenter(MotionEvent event) {
			float[] points = {event.getX(0), event.getY(0), event.getX(1), event.getY(1)};
			viewToBitmap.mapPoints(points);
			return new float[] {(points[0] + points[2]) / 2f, (points[1] + points[3]) / 2f};
		}

		private void clampActiveSticker() {
			if (activeSticker == null) return;
			float margin = activeSticker.size * 0.5f;
			activeSticker.x = Math.max(margin, Math.min(baseBitmap.getWidth() - margin, activeSticker.x));
			activeSticker.y = Math.max(margin, Math.min(baseBitmap.getHeight() - margin, activeSticker.y));
		}

		private void commitActiveSticker() {
			if (activeSticker != null) {
				drawSticker(overlayCanvas, activeSticker);
				activeSticker = null;
				stickerScaleStartDistance = 0f;
				invalidate();
			}
		}

		private boolean handleBrushTouch(MotionEvent event, float x, float y) {
			switch (event.getActionMasked()) {
				case MotionEvent.ACTION_DOWN: {
					pushHistory();
					configureBrush();
					brushPath.reset();
					brushPath.moveTo(x, y);
					lastBrushX = x;
					lastBrushY = y;
					overlayCanvas.drawPoint(x, y, brushPaint);
					invalidate();
					return true;
				}
				case MotionEvent.ACTION_MOVE: {
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
					brushPath.lineTo(x, y);
					overlayCanvas.drawPath(brushPath, brushPaint);
					invalidate();
					historyChanged.run();
					return true;
				}
			}
			return true;
		}

		private void configureBrush() {
			brushPaint.setStrokeWidth(Math.max(18f,
					Math.min(baseBitmap.getWidth(), baseBitmap.getHeight()) * 0.035f));
			if (mode == Mode.ERASE) {
				brushPaint.setColor(Color.TRANSPARENT);
				brushPaint.setXfermode(new PorterDuffXfermode(PorterDuff.Mode.CLEAR));
			} else {
				brushPaint.setColor(Color.BLACK);
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
				historyChanged.run();
			}
			mode = Mode.NONE;
			invalidate();
		}

		void rotate() {
			commitActiveSticker();
			pushHistory();
			Matrix matrix = new Matrix();
			matrix.postRotate(90f);
			replaceBitmaps(Bitmap.createBitmap(baseBitmap, 0, 0, baseBitmap.getWidth(), baseBitmap.getHeight(),
					matrix, true), Bitmap.createBitmap(overlayBitmap, 0, 0, overlayBitmap.getWidth(),
					overlayBitmap.getHeight(), matrix, true));
			historyChanged.run();
		}

		void flip() {
			commitActiveSticker();
			pushHistory();
			Matrix matrix = new Matrix();
			matrix.postScale(-1f, 1f);
			replaceBitmaps(Bitmap.createBitmap(baseBitmap, 0, 0, baseBitmap.getWidth(), baseBitmap.getHeight(),
					matrix, true), Bitmap.createBitmap(overlayBitmap, 0, 0, overlayBitmap.getWidth(),
					overlayBitmap.getHeight(), matrix, true));
			historyChanged.run();
		}

		private void replaceBitmaps(Bitmap base, Bitmap overlay) {
			baseBitmap.recycle();
			overlayBitmap.recycle();
			baseBitmap = ensureArgbBitmap(base);
			overlayBitmap = ensureArgbBitmap(overlay);
			overlayCanvas = new Canvas(overlayBitmap);
			updateMatrices();
			invalidate();
		}

		private void pushHistory() {
			try {
				undo.addLast(new Snapshot(baseBitmap, overlayBitmap));
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
			redo.addLast(new Snapshot(baseBitmap, overlayBitmap));
			while (redo.size() > MAX_HISTORY) redo.removeFirst().recycle();
			restore(undo.removeLast());
		}

		void redo() {
			commitActiveSticker();
			if (redo.isEmpty()) return;
			undo.addLast(new Snapshot(baseBitmap, overlayBitmap));
			while (undo.size() > MAX_HISTORY) undo.removeFirst().recycle();
			restore(redo.removeLast());
		}

		private void restore(Snapshot snapshot) {
			baseBitmap.recycle();
			overlayBitmap.recycle();
			baseBitmap = snapshot.base;
			overlayBitmap = snapshot.overlay;
			overlayCanvas = new Canvas(overlayBitmap);
			mode = Mode.NONE;
			selectedSticker = null;
			activeSticker = null;
			updateMatrices();
			invalidate();
			historyChanged.run();
		}

		Bitmap createResultBitmap() {
			commitActiveSticker();
			try {
				Bitmap result = baseBitmap.copy(Bitmap.Config.ARGB_8888, true);
				new Canvas(result).drawBitmap(overlayBitmap, 0f, 0f, bitmapPaint);
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

			Snapshot(Bitmap base, Bitmap overlay) {
				this.base = base.copy(Bitmap.Config.ARGB_8888, true);
				this.overlay = overlay.copy(Bitmap.Config.ARGB_8888, true);
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
		}
	}
}
