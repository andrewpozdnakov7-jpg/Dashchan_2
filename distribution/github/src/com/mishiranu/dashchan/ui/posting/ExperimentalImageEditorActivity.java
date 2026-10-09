package com.mishiranu.dashchan.ui.posting;

import android.app.AlertDialog;
import android.content.Context;
import android.content.Intent;
import android.content.res.ColorStateList;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.Rect;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.RippleDrawable;
import android.os.Bundle;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.text.InputType;
import android.util.AtomicFile;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowInsets;
import android.view.WindowInsetsController;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.SeekBar;
import android.widget.ScrollView;
import android.widget.TextView;
import androidx.activity.ComponentActivity;
import androidx.activity.OnBackPressedCallback;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import com.mishiranu.dashchan.R;
import com.mishiranu.dashchan.content.LocaleManager;
import com.mishiranu.dashchan.content.model.FileHolder;
import com.mishiranu.dashchan.content.storage.DraftsStorage;
import com.mishiranu.dashchan.ui.posting.photo.EditorCanvasView;
import com.mishiranu.dashchan.ui.posting.photo.EditorColorPickerView;
import com.mishiranu.dashchan.ui.posting.photo.EditorPalette;
import com.mishiranu.dashchan.ui.posting.photo.EditorStickers;
import com.mishiranu.dashchan.ui.posting.photo.EditorDocument;
import com.mishiranu.dashchan.ui.posting.photo.EditorGeometry;
import com.mishiranu.dashchan.ui.posting.photo.EditorExportRequest;
import com.mishiranu.dashchan.ui.posting.photo.EditorSessionSnapshot;
import com.mishiranu.dashchan.ui.posting.photo.EditorSessionSnapshot.Section;
import com.mishiranu.dashchan.ui.posting.photo.EditorSessionCodec;
import com.mishiranu.dashchan.ui.posting.photo.EditorMotion;
import com.mishiranu.dashchan.ui.posting.photo.EditorPanelMotion;
import com.mishiranu.dashchan.ui.posting.photo.EditorRenderer;
import com.mishiranu.dashchan.ui.posting.photo.EditorRulerView;
import com.mishiranu.dashchan.ui.posting.photo.PhotoEditorSessionFiles;
import com.mishiranu.dashchan.util.ViewUtils;
import com.mishiranu.dashchan.widget.ThemeEngine;
import org.json.JSONException;
import org.json.JSONObject;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Local attachment editor. The existing posting result contract is deliberately unchanged. */
public class ExperimentalImageEditorActivity extends ComponentActivity {
    private static final String EXTRA_SOURCE_HASH = "sourceHash", EXTRA_SOURCE_NAME = "sourceName";
    private static final String EXTRA_ATTACHMENT_INDEX = "attachmentIndex";
    public static final String EXTRA_RESULT_HASH = "resultHash", EXTRA_RESULT_NAME = "resultName";
    public static final String EXTRA_RESULT_ATTACHMENT_INDEX = "resultAttachmentIndex";
    public static final String EXTRA_RESULT_SOURCE_HASH = "resultSourceHash", EXTRA_RESULT_SOURCE_NAME = "resultSourceName";
    private static final String SESSION = "editorSession";
    private static final int ACCENT = EditorPalette.ACCENT, SURFACE = EditorPalette.SURFACE, INK = EditorPalette.TEXT;
    private final ExecutorService worker = Executors.newSingleThreadExecutor(r -> new Thread(r, "PhotoEditorWorker"));
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final HashMap<String, Bitmap> assets = new HashMap<>();
    private final java.util.ArrayList<Bitmap> thumbnails = new java.util.ArrayList<>();
    private final java.util.ArrayList<Runnable> rulerBindings = new java.util.ArrayList<>();
    private final java.util.ArrayList<Runnable> filterBindings = new java.util.ArrayList<>();
    private final EditorPanelMotion panelMotion = new EditorPanelMotion();
    private LinearLayout editorRoot;
    private ToolScroll toolScroll;
    private EditorDocument document = new EditorDocument();
    private Bitmap source;
    private EditorCanvasView canvas;
    private File sessionDirectory;
    private String sourceHash, sourceName, checkpoint;
    private volatile String encodedKey;
    private int attachmentIndex, selectedColor = Color.WHITE, exportSide = 0, exportQuality = 94, adjustmentIndex;
    private float brushSize = .025f, mosaicSize = .025f;
    private int blurPreset = 1;
    private boolean drawAll = true, objectAboveEffects;
    private Runnable toolBinding;
    private boolean exportPng, busy, completed, restoredWithLoss, maskAll = true;
    private int originalWidth, originalHeight, stickerGroup;
    private volatile boolean destroyed;
    private Section section = Section.HOME;
    private EditorDocument.Kind drawTool = EditorDocument.Kind.PEN, hideTool = EditorDocument.Kind.COVER;
    private FrameLayout content;
    private LinearLayout panel, categories;
    private LinearLayout historyRow;
    private LinearLayout headerRow;
    private View cropToolbar, cropScale;
    private Button ratioButton;
    private TextView status, subtitle;
    private Button save, undo, redo;
    private EditorColorPickerView.Swatch brushSwatch;
    private volatile int panelGeneration;
    private volatile int sessionGeneration;
    private AlertDialog exportDialog;
    private LinearLayout exportControls;
    private final java.util.EnumMap<Section, LinearLayout> navigation = new java.util.EnumMap<>(Section.class);
    private final ActivityResultLauncher<String> imagePicker = registerForActivityResult(
            new ActivityResultContracts.GetContent(), uri -> {
                if (uri != null && canvas != null && !busy) importImage(FileHolder.obtain(uri));
            });

    /** Leaves room for the photo on short screens and in landscape. */
    private static final class ToolScroll extends ScrollView {
        ToolScroll(Context context) { super(context); setFillViewport(false); }
        @Override protected void onSizeChanged(int width, int height, int oldWidth, int oldHeight) {
            super.onSizeChanged(width, height, oldWidth, oldHeight);
            // The editor root allows the photo to cross layout bounds during transitions.
            // Tool contents must still stay inside their viewport, including while scrolling.
            // Clip this view itself: clipChildren only limits its much taller content child.
            setClipBounds(new Rect(0, 0, width, height));
        }
        @Override protected void onMeasure(int width, int height) {
            int screen = getRootView().getHeight();
            if (screen <= 0) screen = getResources().getDisplayMetrics().heightPixels;
            int cap = Math.max(1, Math.round(screen * .35f));
            if (MeasureSpec.getMode(height) != MeasureSpec.UNSPECIFIED) cap = Math.min(cap, MeasureSpec.getSize(height));
            super.onMeasure(width, MeasureSpec.makeMeasureSpec(cap, MeasureSpec.AT_MOST));
        }
    }

    public static Intent createIntent(Context context, String sourceHash, String sourceName, int attachmentIndex) {
        return new Intent(context, ExperimentalImageEditorActivity.class).putExtra(EXTRA_SOURCE_HASH, sourceHash)
                .putExtra(EXTRA_SOURCE_NAME, sourceName).putExtra(EXTRA_ATTACHMENT_INDEX, attachmentIndex);
    }

    @Override protected void attachBaseContext(Context context) {
        super.attachBaseContext(ThemeEngine.attach(LocaleManager.getInstance().apply(context)));
    }

    @Override protected void onCreate(Bundle saved) {
        requestWindowFeature(Window.FEATURE_NO_TITLE); ThemeEngine.applyTheme(this); super.onCreate(saved);
        sourceHash = getIntent().getStringExtra(EXTRA_SOURCE_HASH); sourceName = getIntent().getStringExtra(EXTRA_SOURCE_NAME);
        attachmentIndex = getIntent().getIntExtra(EXTRA_ATTACHMENT_INDEX, -1);
        if (sourceHash == null || sourceName == null || attachmentIndex < 0) { finish(); return; }
        String id = saved == null ? null : saved.getString(SESSION);
        if (id == null || !id.matches("[a-f0-9]{32}")) id = UUID.randomUUID().toString().replace("-", "");
        sessionDirectory = new File(new File(getCacheDir(), "photo-editor"), id);
        if (!sessionDirectory.isDirectory() && !sessionDirectory.mkdirs()) { finish(); return; }
        PhotoEditorSessionFiles.register(sessionDirectory);
        createLayout();
        getOnBackPressedDispatcher().addCallback(this, new OnBackPressedCallback(true) {
            @Override public void handleOnBackPressed() {
                if (busy) return;
                if (section != Section.HOME) closeSection(false); else requestClose();
            }
        });
        load();
    }

    private int dp(float value) { return Math.round(value * getResources().getDisplayMetrics().density); }
    private AlertDialog.Builder dialogBuilder() { return new AlertDialog.Builder(this, R.style.PhotoEditorDialogTheme); }
    private GradientDrawable background(int color) {
        GradientDrawable out = new GradientDrawable(); out.setColor(color); out.setCornerRadius(dp(22)); return out;
    }
    private TextView label(int resource) {
        return label(this, resource);
    }
    private TextView label(Context context, int resource) {
        TextView view = new TextView(context); view.setText(resource); view.setTextColor(controlText()); view.setTextSize(14);
        view.setPadding(dp(12), dp(3), dp(12), dp(3)); return view;
    }
    private Button button(String text, Runnable action) {
        return button(this, text, action);
    }
    private Button button(Context context, String text, Runnable action) {
        Button view = new Button(context); view.setText(text); view.setAllCaps(false); view.setTextSize(13);
        view.setBackgroundTintList(null);
        view.setTextColor(controlText()); view.setBackground(new RippleDrawable(ColorStateList.valueOf(0x30ffffff), background(EditorPalette.SECONDARY), null)); view.setMinWidth(dp(48));
        view.setMinimumWidth(dp(48)); view.setMinHeight(dp(48)); view.setPadding(dp(12), 0, dp(12), 0);
        view.setOnClickListener(v -> { if (!busy) action.run(); }); return view;
    }
    private ColorStateList controlText() {
        return new ColorStateList(new int[][] {{-android.R.attr.state_enabled}, {}}, new int[] {0x66e3e3e3, INK});
    }
    private CheckBox checkBox(Context context, int text, boolean checked) {
        CheckBox view = new CheckBox(context); view.setText(text); view.setTextColor(controlText()); view.setChecked(checked);
        view.setButtonTintList(new ColorStateList(new int[][] {{-android.R.attr.state_enabled},
                {android.R.attr.state_checked}, {}}, new int[] {0x66b4b6bd, ACCENT, EditorPalette.MUTED}));
        return view;
    }
    private void styleInput(EditText view) {
        view.setTextColor(INK); view.setHintTextColor(EditorPalette.MUTED);
        GradientDrawable shape = background(EditorPalette.INPUT); shape.setCornerRadius(dp(12)); shape.setStroke(dp(1), EditorPalette.BORDER);
        view.setBackground(shape); view.setPadding(dp(12), dp(12), dp(12), dp(12));
        view.setBackgroundTintList(null);
    }
    @SuppressWarnings("deprecation")
    private void resizeTextDialogForIme(Window window, boolean show) {
        // Floating framework dialogs retain the platform IME resize contract on API 30+.
        // IME visibility itself is controlled below through public WindowInsetsController.
        window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE
                | (show ? WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_VISIBLE
                : WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN));
    }
    private Drawable icon(int resource, int size, int tint) {
        Drawable image = getDrawable(resource).mutate(); image.setTint(tint); image.setBounds(0, 0, dp(size), dp(size)); return image;
    }
    private int toolIcon(int title) {
        if (title == R.string.pe_crop || title == R.string.pe_ratio) return R.drawable.ic_editor_crop;
        if (title == R.string.pe_adjust) return R.drawable.ic_pe_tune;
        if (title == R.string.pe_filters) return R.drawable.ic_pe_filters;
        if (title == R.string.pe_draw || title == R.string.pe_pen || title == R.string.pe_marker) return R.drawable.ic_editor_brush;
        if (title == R.string.pe_text || title == R.string.pe_add_text) return R.drawable.ic_pe_text;
        if (title == R.string.pe_hide || title == R.string.pe_cover) return R.drawable.ic_pe_hide;
        if (title == R.string.pe_stickers) return R.drawable.ic_editor_sticker;
        if (title == R.string.pe_rotate) return R.drawable.ic_editor_rotate;
        if (title == R.string.pe_mirror) return R.drawable.ic_editor_flip;
        if (title == R.string.pe_reset) return R.drawable.ic_refresh;
        if (title == R.string.pe_color) return R.drawable.ic_color_lens;
        if (title == R.string.pe_add_image) return R.drawable.ic_photo_library;
        if (title == R.string.pe_delete_selected) return R.drawable.ic_delete;
        if (title == R.string.pe_edit_selected) return R.drawable.ic_edit;
        if (title == R.string.image_editor_eraser) return R.drawable.ic_editor_eraser;
        if (title == R.string.pe_arrow) return R.drawable.ic_pe_arrow;
        if (title == R.string.pe_line) return R.drawable.ic_pe_line;
        if (title == R.string.pe_rectangle) return R.drawable.ic_pe_rectangle;
        if (title == R.string.pe_oval) return R.drawable.ic_pe_oval;
        if (title == R.string.pe_mosaic) return R.drawable.ic_pe_mosaic;
        if (title == R.string.pe_blur) return R.drawable.ic_pe_blur;
        return 0;
    }
    private Button button(int resource, Runnable action) {
        Button view = button(getString(resource), action); int image = toolIcon(resource);
        if (image != 0) { view.setCompoundDrawables(icon(image, 20, INK), null, null, null); view.setCompoundDrawablePadding(dp(8)); }
        return view;
    }
    private Button iconButton(int image, int description, Runnable action) {
        Button view = button("", action); view.setContentDescription(getString(description));
        view.setBackground(new RippleDrawable(ColorStateList.valueOf(0x30ffffff), background(Color.TRANSPARENT), null));
        view.setPadding(dp(12), 0, dp(12), 0); view.setCompoundDrawables(icon(image, 24, INK), null, null, null); return view;
    }
    private void highlight(Button button, boolean active) {
        // Selected text remains readable on the dark editor surface throughout the transition.
        panelMotion.highlight(button, active ? EditorPalette.SELECTED : EditorPalette.SECONDARY, active ? ACCENT : INK);
    }
    private LinearLayout toolTile(int title, int image, boolean active, boolean small, Runnable action) {
        LinearLayout tile = new LinearLayout(this); tile.setOrientation(LinearLayout.VERTICAL); tile.setGravity(Gravity.CENTER);
        tile.setPadding(dp(4), dp(4), dp(4), dp(4)); tile.setContentDescription(getString(title));
        tile.setFocusable(true); tile.setClickable(true);
        tile.setBackground(new RippleDrawable(ColorStateList.valueOf(0x24ffffff), background(Color.TRANSPARENT), null));
        ImageView symbol = new ImageView(this); symbol.setImageDrawable(icon(image, 24, active ? EditorPalette.ON_ACCENT : INK));
        symbol.setPadding(dp(small ? 16 : 10), dp(small ? 4 : 10), dp(small ? 16 : 10), dp(small ? 4 : 10));
        symbol.setBackground(background(active ? ACCENT : small ? Color.TRANSPARENT : EditorPalette.INPUT));
        tile.addView(symbol, new LinearLayout.LayoutParams(dp(small ? 56 : 44), dp(small ? 32 : 44)));
        TextView caption = new TextView(this); caption.setText(title); caption.setTextSize(12); caption.setTextColor(active ? ACCENT : INK);
        caption.setGravity(Gravity.CENTER); caption.setMaxLines(2); caption.setEllipsize(android.text.TextUtils.TruncateAt.END);
        caption.setPadding(0, dp(6), 0, 0); tile.addView(caption, new LinearLayout.LayoutParams(-1, -2));
        tile.setMinimumHeight(dp(small ? 72 : 82));
        tile.setOnClickListener(v -> { if (!busy) action.run(); }); return tile;
    }
    private void updateCategories() {
        for (java.util.Map.Entry<Section, LinearLayout> entry : navigation.entrySet()) {
            boolean active = entry.getKey() == section;
            ImageView image = (ImageView) entry.getValue().getChildAt(0); image.setColorFilter(active ? EditorPalette.ON_ACCENT : INK);
            image.setBackground(background(active ? ACCENT : Color.TRANSPARENT));
            ((TextView) entry.getValue().getChildAt(1)).setTextColor(active ? ACCENT : INK);
        }
    }
    private void addButton(LinearLayout row, Button view) {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        p.setMargins(dp(3), dp(4), dp(3), dp(4)); row.addView(view, p);
    }
    private LinearLayout scrollRow(LinearLayout parent) {
        HorizontalScrollView scroll = new HorizontalScrollView(parent.getContext()); scroll.setHorizontalScrollBarEnabled(false);
        scroll.setFillViewport(true); parent.addView(scroll, new LinearLayout.LayoutParams(-1, -2));
        LinearLayout row = new LinearLayout(parent.getContext()); row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(6), 0, dp(6), 0); scroll.addView(row, new HorizontalScrollView.LayoutParams(-2, -2)); return row;
    }

    private void createLayout() {
        LinearLayout root = new LinearLayout(this); root.setOrientation(LinearLayout.VERTICAL);
        editorRoot = root;
        // During a tool reflow the retained pose can briefly straddle the old/new canvas
        // bounds. Clip at the root's system-bar padding, not at the resized content child.
        root.setClipChildren(false);
        root.setBackgroundColor(EditorPalette.ROOT); setContentView(root);
        getWindow().getDecorView().setBackgroundColor(EditorPalette.ROOT);
        ViewUtils.setStatusBarColor(getWindow(), EditorPalette.ROOT);
        ViewUtils.setNavigationBarColor(getWindow(), EditorPalette.ROOT);
        WindowInsetsController systemUi = getWindow().getInsetsController();
        if (systemUi != null) systemUi.setSystemBarsAppearance(0, WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS
                | WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS);
        root.setOnApplyWindowInsetsListener((v, insets) -> {
            android.graphics.Insets edges = insets.getInsets(WindowInsets.Type.systemBars() | WindowInsets.Type.displayCutout());
            v.setPadding(edges.left, edges.top, edges.right, edges.bottom); return insets;
        }); root.requestApplyInsets();
        LinearLayout header = new LinearLayout(this); header.setGravity(Gravity.CENTER_VERTICAL); header.setBackgroundColor(SURFACE); headerRow = header;
        header.setMinimumHeight(dp(60)); root.addView(header, new LinearLayout.LayoutParams(-1, -2));
        Button close = iconButton(R.drawable.ic_editor_close, android.R.string.cancel, this::requestClose); addButton(header, close);
        LinearLayout title = new LinearLayout(this); title.setOrientation(LinearLayout.VERTICAL);
        header.addView(title, new LinearLayout.LayoutParams(0, -2, 1));
        TextView heading = label(R.string.image_editor); heading.setTextSize(17); heading.setMaxLines(2);
        heading.setEllipsize(android.text.TextUtils.TruncateAt.END); title.addView(heading);
        subtitle = label(R.string.image_editor_loading); subtitle.setTextSize(11); subtitle.setMaxLines(1);
        subtitle.setEllipsize(android.text.TextUtils.TruncateAt.END); title.addView(subtitle);
        save = button(R.string.pe_save, this::saveDirect); save.setTextColor(Color.BLACK); save.setBackground(background(ACCENT));
        save.setEnabled(false); addButton(header, save);
        content = new FrameLayout(this); content.setClipChildren(false);
        root.addView(content, new LinearLayout.LayoutParams(-1, 0, 1));
        status = label(R.string.image_editor_loading); status.setGravity(Gravity.CENTER);
        content.addView(status, new FrameLayout.LayoutParams(-1, -1));
        LinearLayout history = new LinearLayout(this); history.setGravity(Gravity.CENTER_VERTICAL);
        historyRow = history;
        history.setMinimumHeight(dp(52)); root.addView(history, new LinearLayout.LayoutParams(-1, -2));
        undo = iconButton(R.drawable.ic_editor_undo, R.string.image_editor_undo, () -> history(false)); addButton(history, undo);
        redo = iconButton(R.drawable.ic_editor_redo, R.string.image_editor_redo, () -> history(true)); addButton(history, redo);
        View spacer = new View(this); history.addView(spacer, new LinearLayout.LayoutParams(0, 1, 1));
        addButton(history, iconButton(R.drawable.ic_settings, R.string.pe_export_settings, this::showExport));
        Button compare = iconButton(R.drawable.ic_pe_compare, R.string.pe_original, () -> {});
        compare.setOnTouchListener((v, event) -> {
            if (canvas != null && !busy) canvas.compare(event.getActionMasked() != MotionEvent.ACTION_UP
                    && event.getActionMasked() != MotionEvent.ACTION_CANCEL);
            if (event.getActionMasked() == MotionEvent.ACTION_UP) v.performClick(); return true;
        }); addButton(history, compare);
        panel = new LinearLayout(this); panel.setOrientation(LinearLayout.VERTICAL); panel.setBackgroundColor(SURFACE);
        toolScroll = new ToolScroll(this); toolScroll.addView(panel, new ScrollView.LayoutParams(-1, -2));
        root.addView(toolScroll, new LinearLayout.LayoutParams(-1, -2));
        categories = new LinearLayout(this); categories.setOrientation(LinearLayout.VERTICAL); categories.setBackgroundColor(SURFACE);
        root.addView(categories, new LinearLayout.LayoutParams(-1, -2));
        LinearLayout row = scrollRow(categories);
        int[] names = {R.string.pe_crop, R.string.pe_adjust, R.string.pe_filters, R.string.pe_draw,
                R.string.pe_text, R.string.pe_hide, R.string.pe_stickers};
        Section[] sections = {Section.CROP, Section.ADJUST, Section.FILTERS, Section.DRAW, Section.TEXT, Section.HIDE, Section.STICKERS};
        for (int i = 0; i < names.length; i++) {
            final Section target = sections[i];
            LinearLayout tile = toolTile(names[i], toolIcon(names[i]), false, true, () -> openSection(target));
            navigation.put(target, tile); row.addView(tile, new LinearLayout.LayoutParams(dp(78), -2));
        }
        categories.setVisibility(View.GONE);
    }

    private void load() {
        worker.execute(() -> {
            Bitmap loaded = null;
            try {
                PhotoEditorSessionFiles.prune(sessionDirectory.getParentFile(), System.currentTimeMillis());
                FileHolder holder = DraftsStorage.getInstance().getAttachmentDraftFileHolder(sourceHash);
                if (holder == null) throw new IOException("Missing attachment");
                originalWidth = holder.getImageWidth(); originalHeight = holder.getImageHeight();
                exportPng = holder.getImageType() == FileHolder.ImageType.IMAGE_PNG;
                AtomicFile session = new AtomicFile(new File(sessionDirectory, "session.json"));
                if (session.getBaseFile().isFile()) {
                    EditorSessionSnapshot.Builder restored = sessionBuilder(document);
                    try {
                        if (session.getBaseFile().length() > 16 * 1024 * 1024) throw new IOException("Oversized session");
                        JSONObject json = new JSONObject(new String(session.readFully(), StandardCharsets.UTF_8));
                        EditorSessionCodec.restore(json, restored);
                    } catch (IOException | JSONException | IllegalArgumentException e) {
                        restored.document = new EditorDocument();
                        restored.checkpoint = null;
                        restored.section = Section.HOME;
                        restoredWithLoss = true;
                    }
                    applySession(restored.build());
                }
                loaded = holder.readImageBitmap(1536, false, false);
                if (loaded == null) throw new IOException("Unsupported photo");
                File[] files = sessionDirectory.listFiles((dir, name) -> name.startsWith("asset-") && name.endsWith(".png"));
                if (files != null) for (File file : files) {
                    if (assets.size() >= 8) break;
                    FileHolder assetHolder = FileHolder.obtain(file);
                    Bitmap asset = assetHolder != null ? assetHolder.readImageBitmap(1024, false, false) : null;
                    if (asset != null) assets.put(file.getName(), asset);
                }
                for (EditorDocument.Item item : document.state().items) {
                    if (item.kind == EditorDocument.Kind.IMAGE && !assets.containsKey(item.asset)) restoredWithLoss = true;
                }
                Bitmap ready = loaded;
                handler.post(() -> {
                    if (destroyed) { ready.recycle(); return; }
                    source = ready;
                    canvas = new EditorCanvasView(this, source, document, assets, worker, new EditorCanvasView.Listener() {
                        @Override public void onChange() {
                            encodedKey = null; updateHistory();
                            if (!document.isInTransaction() && toolBinding != null) toolBinding.run();
                        }
                        @Override public void onRenderFailure() { failure(R.string.pe_preview_failed); }
                        @Override public void onLimit() { android.widget.Toast.makeText(ExperimentalImageEditorActivity.this, R.string.pe_limit, android.widget.Toast.LENGTH_SHORT).show(); }
                    });
                    content.removeAllViews(); content.addView(canvas, new FrameLayout.LayoutParams(-1, -1));
                    save.setEnabled(true); buildPanel(); changed();
                    if (restoredWithLoss) failure(R.string.pe_restore_failed);
                });
            } catch (IOException | RuntimeException | OutOfMemoryError e) {
                if (loaded != null) loaded.recycle();
                handler.post(() -> { if (!destroyed) { status.setText(R.string.image_editor_load_failed); failure(R.string.image_editor_load_failed); }});
            }
        });
    }

    private void openSection(Section target) {
        if (canvas == null || busy || target == section) return;
        canvas.finishGesture(true);
        final String nextCheckpoint;
        try { nextCheckpoint = document.toJson().toString(); }
        catch (JSONException e) { failure(R.string.pe_restore_failed); return; }
        panelMotion.capture(editorRoot, visiblePanels());
        canvas.beginPresentation(EditorMotion.CROP, 0, false);
        checkpoint = nextCheckpoint; section = target; buildPanel();
        canvas.endPresentation(); panelMotion.enter(visiblePanels(), this::updateHistory);
    }

    private void closeSection(boolean apply) {
        canvas.finishGesture(apply);
        EditorDocument restored = null;
        if (!apply && checkpoint != null) {
            try { restored = EditorDocument.fromJson(new JSONObject(checkpoint)); }
            catch (JSONException | IllegalArgumentException e) { failure(R.string.pe_restore_failed); return; }
        }
        panelMotion.capture(editorRoot, visiblePanels());
        canvas.beginPresentation(EditorMotion.CROP, 0, !apply);
        if (restored != null) { document = restored; canvas.setDocument(document); }
        checkpoint = null; section = Section.HOME; buildPanel(); changed(); persist();
        canvas.endPresentation(); panelMotion.enter(visiblePanels(), this::updateHistory);
    }

    private java.util.List<View> visiblePanels() {
        // Shared navigation stays laid out and interactive; only tool-specific panels crossfade.
        return java.util.Arrays.asList(headerRow, historyRow, toolScroll, cropToolbar, cropScale);
    }

    private void buildPanel() {
        panelGeneration++; toolBinding = null; panel.removeAllViews(); rulerBindings.clear(); filterBindings.clear(); brushSwatch = null;
        for (Bitmap bitmap : thumbnails) if (!bitmap.isRecycled()) bitmap.recycle();
        thumbnails.clear(); categories.setVisibility(View.VISIBLE); updateCategories();
        if (cropToolbar != null) { content.removeView(cropToolbar); cropToolbar = null; }
        if (cropScale != null) { content.removeView(cropScale); cropScale = null; }
        boolean crop = section == Section.CROP;
        headerRow.setVisibility(crop ? View.GONE : View.VISIBLE);
        historyRow.setVisibility(crop ? View.GONE : View.VISIBLE);
        // categories already occupies its own root row: do not hide it or add its height to canvas insets.
        canvas.setDrawingKind(null); canvas.setCropMode(section == Section.CROP); canvas.setBrush(selectedColor, brushSize); canvas.setMaskScope(maskAll); canvas.setDrawingScope(drawAll);
        canvas.setEditorInsets(crop ? dp(72) : 0, crop ? dp(86) : 0);
        if (section == Section.HOME) { panel.setVisibility(View.GONE); updateHistory(); return; }
        panel.setVisibility(View.VISIBLE);
        panel.setBackgroundColor(crop ? Color.BLACK : SURFACE);
        LinearLayout actions = new LinearLayout(this); actions.setGravity(Gravity.CENTER_VERTICAL); panel.addView(actions);
        addButton(actions, iconButton(R.drawable.ic_editor_close, android.R.string.cancel, () -> closeSection(false)));
        int title = section == Section.CROP ? R.string.pe_crop : section == Section.ADJUST ? R.string.pe_adjust
                : section == Section.FILTERS ? R.string.pe_filters : section == Section.DRAW ? R.string.pe_draw
                : section == Section.TEXT ? R.string.pe_text : section == Section.HIDE ? R.string.pe_hide : R.string.pe_stickers;
        TextView name = label(title); name.setGravity(Gravity.CENTER); name.setTextSize(15);
        actions.addView(name, new LinearLayout.LayoutParams(0, -2, 1));
        Button apply = iconButton(R.drawable.ic_editor_done, R.string.pe_apply, () -> closeSection(true));
        apply.getCompoundDrawables()[0].setTint(crop ? EditorPalette.ON_ACCENT : ACCENT);
        if (crop) apply.setBackground(background(ACCENT));
        addButton(actions, apply);
        switch (section) {
            case CROP: cropPanel(); break;
            case ADJUST: adjustmentPanel(); break;
            case FILTERS: filterPanel(); break;
            case DRAW: drawingPanel(false); break;
            case HIDE: drawingPanel(true); break;
            case TEXT: textPanel(); break;
            case STICKERS: stickerPanel(); break;
            default: break;
        }
        updateHistory();
    }

    private void mutate(Runnable change) {
        canvas.finishGesture(true); canvas.beginSceneChange();
        document.begin(); change.run(); document.commit(); changed(); canvas.finishSceneChange();
    }
    private void geometryChange(long duration, float rotation, boolean pixels, Runnable change, boolean rebuild) {
        canvas.finishGesture(true); canvas.beginPresentation(duration, rotation, pixels);
        document.begin(); change.run(); document.commit(); changed();
        if (rebuild) buildPanel(); else canvas.resetViewport();
        canvas.endPresentation();
    }
    private void history(boolean redo) {
        canvas.finishGesture(true); canvas.invalidatePreview(); canvas.beginPresentation(EditorMotion.HISTORY, 0, true);
        if (redo) document.redo(); else document.undo();
        changed(); buildPanel(); canvas.endPresentation();
    }
    private void changed() {
        if (canvas != null) canvas.refresh(); encodedKey = null; updateHistory();
        for (Runnable binding : rulerBindings) binding.run();
        for (Runnable binding : filterBindings) binding.run();
        if (toolBinding != null) toolBinding.run();
        updateSubtitle();
    }
    private void updateSubtitle() {
        subtitle.setText(""); subtitle.setVisibility(View.GONE);
    }
    private void updateHistory() {
        if (undo == null) return;
        undo.setEnabled(!busy && !panelMotion.isTransitioning() && !document.isInTransaction() && document.canUndo());
        redo.setEnabled(!busy && !panelMotion.isTransitioning() && !document.isInTransaction() && document.canRedo());
        undo.setAlpha(undo.isEnabled() ? 1 : .35f); redo.setAlpha(redo.isEnabled() ? 1 : .35f);
    }

    private interface ValueChange { void set(int value); }
    private void ruler(LinearLayout parent, int title, float minimum, float maximum, float step,
            java.util.function.Supplier<Float> current, java.util.function.Consumer<Float> change) {
        LinearLayout heading = new LinearLayout(this); heading.setGravity(Gravity.CENTER_VERTICAL);
        heading.setPadding(dp(12), 0, dp(12), 0); parent.addView(heading);
        TextView name = label(title); name.setTextColor(EditorPalette.MUTED); name.setMinimumHeight(dp(28));
        heading.addView(name, new LinearLayout.LayoutParams(0, -2, 1));
        TextView number = new TextView(this); number.setTextColor(ACCENT); number.setTextSize(17); number.setPadding(0, 0, dp(12), 0);
        heading.addView(number); java.util.function.Consumer<Float> show = next -> number.setText(String.format(Locale.getDefault(), step < 1 ? "%.1f°" : "%.0f", next));
        show.accept(current.get());
        EditorRulerView control = new EditorRulerView(this, minimum, maximum, step, current.get(), new EditorRulerView.Listener() {
            @Override public void onBegin() {
                canvas.finishGesture(true); canvas.beginControl(title == R.string.pe_horizon);
                document.begin(); updateHistory();
            }
            @Override public void onValue(float value) { change.accept(value); show.accept(value); changed(); }
            @Override public void onEnd(boolean commit) {
                if (commit) document.commit(); else document.cancel();
                canvas.endControl(title == R.string.pe_horizon);
                changed();
            }
        });
        rulerBindings.add(() -> { control.syncValue(current.get()); show.accept(control.value()); });
        int height = Math.max(dp(58), dp(35) + Math.round(android.util.TypedValue.applyDimension(
                android.util.TypedValue.COMPLEX_UNIT_SP, 18, getResources().getDisplayMetrics())));
        control.setContentDescription(getString(title)); parent.addView(control, new LinearLayout.LayoutParams(-1, height));
    }
    private View slider(LinearLayout parent, int title, int minimum, int maximum, int value, ValueChange change,
            boolean history, String suffix) {
        return slider(parent, title, minimum, maximum, value, change, history, suffix, null);
    }
    private View slider(LinearLayout parent, int title, int minimum, int maximum, int value, ValueChange change,
            boolean history, String suffix, java.util.function.Supplier<Integer> currentValue) {
        LinearLayout group = new LinearLayout(parent.getContext()); group.setOrientation(LinearLayout.VERTICAL); parent.addView(group);
        parent = group;
        TextView label = label(parent.getContext(), title); parent.addView(label);
        SeekBar slider = new SeekBar(new android.view.ContextThemeWrapper(parent.getContext(), R.style.PhotoEditorDialogTheme)); slider.setMax(maximum - minimum); slider.setProgress(value - minimum);
        slider.setProgressTintList(ColorStateList.valueOf(ACCENT)); slider.setThumbTintList(ColorStateList.valueOf(ACCENT));
        slider.setProgressBackgroundTintList(ColorStateList.valueOf(EditorPalette.BORDER));
        slider.setPadding(dp(18), 0, dp(18), 0); parent.addView(slider, new LinearLayout.LayoutParams(-1, dp(40)));
        Runnable updateLabel = () -> {
            int current = slider.getProgress() + minimum;
            String number = title == R.string.pe_horizon ? String.format(Locale.getDefault(), "%.1f°", current / 10f)
                    : "‰".equals(suffix) ? String.format(Locale.getDefault(), "%.1f%%", current / 10f) : current + suffix;
            label.setText(getString(title) + ": " + number);
        };
        updateLabel.run();
        slider.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onStartTrackingTouch(SeekBar bar) {
                if (history && !busy) { canvas.finishGesture(true); document.begin(); }
                if (title == R.string.pe_thickness && !busy) canvas.beginBrushPreview();
            }
            @Override public void onProgressChanged(SeekBar bar, int progress, boolean fromUser) {
                updateLabel.run();
                if (fromUser && !busy) {
                    // Accessibility changes can happen without onStartTrackingTouch.
                    boolean own = history && !document.isInTransaction(); if (own) document.begin();
                    change.set(progress + minimum); if (own) document.commit();
                    if (title == R.string.pe_thickness) canvas.showBrushPreview();
                    changed();
                }
            }
            @Override public void onStopTrackingTouch(SeekBar bar) { if (history) document.commit();
                if (title == R.string.pe_thickness) canvas.endBrushPreview(); changed(); }
        });
        if (currentValue != null) {
            Runnable previousBinding = toolBinding;
            toolBinding = () -> {
                if (previousBinding != null) previousBinding.run();
                slider.setProgress(Math.max(minimum, Math.min(maximum, currentValue.get())) - minimum);
            };
            toolBinding.run();
        }
        return group;
    }

    private void cropPanel() {
        LinearLayout row = new LinearLayout(this); row.setGravity(Gravity.CENTER_VERTICAL | Gravity.END); row.setPadding(dp(10), dp(8), dp(10), 0);
        View space = new View(this); row.addView(space, new LinearLayout.LayoutParams(0, 1, 1));
        ratioButton = button(ratioLabel(), this::chooseRatio); ratioButton.setContentDescription(getString(R.string.pe_ratio));
        ratioButton.setCompoundDrawables(icon(R.drawable.ic_editor_crop, 20, INK), null, icon(R.drawable.ic_arrow_drop_down, 18, INK), null);
        ratioButton.setCompoundDrawablePadding(dp(6)); addButton(row, ratioButton);
        Button mirror = iconButton(R.drawable.ic_editor_flip, R.string.pe_mirror, () -> {
            geometryChange(EditorMotion.MIRROR, 0, true, () -> EditorGeometry.mirror(document.state()), false);
        });
        mirror.setBackground(background(EditorPalette.SECONDARY)); addButton(row, mirror);
        Button rotate = iconButton(R.drawable.ic_editor_rotate, R.string.pe_rotate, () ->
                geometryChange(EditorMotion.ROTATE, 90, false, () -> EditorGeometry.rotateQuarter(document.state()), true));
        rotate.setBackground(background(EditorPalette.SECONDARY)); addButton(row, rotate);
        Button reset = iconButton(R.drawable.ic_refresh, R.string.pe_reset, () -> {
            geometryChange(EditorMotion.RESET, 0, true, () -> { EditorDocument.State s = document.state(); s.crop.set(0, 0, 1, 1);
                s.straighten = 0; s.quarterTurns = 0; s.mirror = false; s.aspect = 0; }, true);
        }); reset.setBackground(background(EditorPalette.SECONDARY)); addButton(row, reset);
        HorizontalScrollView toolbar = new HorizontalScrollView(this); toolbar.setFillViewport(true);
        toolbar.setHorizontalScrollBarEnabled(false); row.setMinimumHeight(dp(64));
        toolbar.addView(row, new HorizontalScrollView.LayoutParams(-2, -2));
        cropToolbar = toolbar; content.addView(toolbar, new FrameLayout.LayoutParams(-1, -2, Gravity.TOP));
        LinearLayout scale = new LinearLayout(this); scale.setOrientation(LinearLayout.VERTICAL); scale.setBackground(background(SURFACE));
        ruler(scale, R.string.pe_horizon, -45, 45, .1f, () -> document.state().straighten, value -> document.state().straighten = value);
        cropScale = scale; FrameLayout.LayoutParams p = new FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM);
        p.setMargins(dp(12), 0, dp(12), dp(4)); content.addView(scale, p);
        View.OnLayoutChangeListener insets = (v, l, t, r, b, ol, ot, or, ob) -> {
            if (section == Section.CROP && cropToolbar != null && cropScale != null) {
                canvas.setEditorInsets(cropToolbar.getHeight() + dp(8), cropScale.getHeight() + dp(8));
            }
        };
        toolbar.addOnLayoutChangeListener(insets); scale.addOnLayoutChangeListener(insets);
    }

    private String ratioLabel() {
        float value = document.state().aspect;
        if (value == 0) return getString(R.string.pe_free);
        String[] labels = {"1:1", "5:4", "4:3", "3:2", "16:9", "4:5", "3:4", "2:3", "9:16"};
        float[] values = {1, 1.25f, 4f / 3, 1.5f, 16f / 9, .8f, .75f, 2f / 3, 9f / 16};
        for (int i = 0; i < values.length; i++) if (Math.abs(value - values[i]) < .001f) return labels[i];
        return getString(R.string.pe_source_ratio);
    }

    private void chooseRatio() {
        String[] labels = {getString(R.string.pe_free), getString(R.string.pe_source_ratio), "1:1", "5:4", "4:3", "3:2", "16:9", "4:5", "3:4", "2:3", "9:16"};
        float ratio = EditorGeometry.width(document.state(), source.getWidth(), source.getHeight())
                / EditorGeometry.height(document.state(), source.getWidth(), source.getHeight());
        float[] values = {0, ratio, 1, 1.25f, 4f / 3, 1.5f, 16f / 9, .8f, .75f, 2f / 3, 9f / 16};
        LinearLayout list = new LinearLayout(this); list.setOrientation(LinearLayout.VERTICAL);
        list.setPadding(dp(4), dp(8), dp(4), dp(8)); GradientDrawable menuBackground = background(EditorPalette.SECONDARY); menuBackground.setCornerRadius(dp(8)); list.setBackground(menuBackground);
        ScrollView scroll = new ScrollView(this); scroll.addView(list);
        android.widget.PopupWindow popup = new android.widget.PopupWindow(scroll, dp(186),
                Math.min(dp(labels.length * 48 + 16), Math.round(content.getHeight() * .8f)), true);
        popup.setBackgroundDrawable(menuBackground); popup.setElevation(dp(12)); popup.setOutsideTouchable(true);
        for (int i = 0; i < labels.length; i++) {
            final int index = i; TextView option = new TextView(this); option.setText(labels[i]); option.setTextColor(INK); option.setTextSize(16);
            option.setGravity(Gravity.CENTER_VERTICAL); option.setPadding(dp(12), 0, dp(12), 0);
            if (Math.abs(document.state().aspect - values[i]) < .0001f) option.setCompoundDrawables(null, null, icon(R.drawable.ic_editor_done, 20, ACCENT), null);
            option.setBackground(new RippleDrawable(ColorStateList.valueOf(0x30ffffff), background(Color.TRANSPARENT), null));
            option.setFocusable(true); option.setOnClickListener(v -> { popup.dismiss();
                geometryChange(EditorMotion.RATIO, 0, false,
                        () -> EditorGeometry.setAspect(document.state(), values[index], source.getWidth(), source.getHeight()), true);
            }); option.setMinimumHeight(dp(48)); list.addView(option, new LinearLayout.LayoutParams(-1, -2));
        }
        popup.showAsDropDown(ratioButton, 0, dp(2), Gravity.END);
    }

    /** One UI description per named adjustment; no parallel label/icon arrays. */
    private enum AdjustmentControl {
        BRIGHTNESS(EditorDocument.Adjustment.BRIGHTNESS, R.string.pe_brightness, R.drawable.ic_pe_brightness),
        CONTRAST(EditorDocument.Adjustment.CONTRAST, R.string.pe_contrast, R.drawable.ic_pe_contrast),
        SATURATION(EditorDocument.Adjustment.SATURATION, R.string.pe_saturation, R.drawable.ic_pe_saturation),
        WARMTH(EditorDocument.Adjustment.WARMTH, R.string.pe_warmth, R.drawable.ic_pe_warmth),
        SHADOWS(EditorDocument.Adjustment.SHADOWS, R.string.pe_shadows, R.drawable.ic_pe_shadows),
        HIGHLIGHTS(EditorDocument.Adjustment.HIGHLIGHTS, R.string.pe_highlights, R.drawable.ic_pe_highlights),
        SHARPNESS(EditorDocument.Adjustment.SHARPNESS, R.string.pe_sharpness, R.drawable.ic_pe_sharpness);

        final EditorDocument.Adjustment adjustment;
        final int label, icon;

        AdjustmentControl(EditorDocument.Adjustment adjustment, int label, int icon) {
            this.adjustment = adjustment;
            this.label = label;
            this.icon = icon;
        }

        static AdjustmentControl forStorageIndex(int index) {
            for (AdjustmentControl control : values()) {
                if (control.adjustment.storageIndex == index) return control;
            }
            throw new IllegalArgumentException("Unknown adjustment: " + index);
        }
    }

    private void adjustmentPanel() {
        LinearLayout selection = scrollRow(panel);
        LinearLayout controls = new LinearLayout(this);
        controls.setOrientation(LinearLayout.VERTICAL);
        panel.addView(controls);
        for (AdjustmentControl control : AdjustmentControl.values()) {
            final int index = control.adjustment.storageIndex;
            selection.addView(toolTile(control.label, control.icon, index == adjustmentIndex, false, () -> {
                adjustmentIndex = index;
                buildPanel();
            }), new LinearLayout.LayoutParams(dp(80), -2));
        }
        AdjustmentControl control = AdjustmentControl.forStorageIndex(adjustmentIndex);
        EditorDocument.Adjustment adjustment = control.adjustment;
        final int index = adjustment.storageIndex;
        ruler(controls, control.label, adjustment.uiMinimum, adjustment.uiMaximum, 1,
                () -> (float) document.state().adjustments[index],
                value -> document.state().adjustments[index] = Math.round(value));
        LinearLayout row = scrollRow(controls);
        Button reset = button(R.string.pe_reset, () -> geometryChange(EditorMotion.RESET, 0, true,
                () -> document.state().adjustments[index] = 0, true));
        reset.setMinHeight(dp(40));
        reset.setTextSize(12);
        row.addView(reset, new LinearLayout.LayoutParams(-2, dp(40)));
    }

    private void filterPanel() {
        final int request = panelGeneration;
        LinearLayout row = scrollRow(panel);
        int[] names = {R.string.pe_filter_none, R.string.pe_filter_mono, R.string.pe_filter_sepia, R.string.pe_filter_warm,
                R.string.pe_filter_cool, R.string.pe_filter_vivid, R.string.pe_filter_fade};
        EditorDocument.Filter[] filters = EditorDocument.Filter.values();
        for (int i = 0; i < filters.length; i++) {
            final EditorDocument.Filter filter = filters[i];
            LinearLayout tile = new LinearLayout(this); tile.setOrientation(LinearLayout.VERTICAL); tile.setPadding(dp(4), 0, dp(4), 0);
            ImageView image = new ImageView(this); image.setScaleType(ImageView.ScaleType.CENTER_CROP);
            GradientDrawable outline = background(document.state().filter == filter ? ACCENT : EditorPalette.INPUT); outline.setCornerRadius(dp(12));
            image.setBackground(outline); image.setClipToOutline(true); image.setPadding(dp(2), dp(2), dp(2), dp(2)); image.setImageBitmap(source);
            tile.addView(image, new LinearLayout.LayoutParams(dp(76), dp(76)));
            TextView name = label(names[i]); name.setGravity(Gravity.CENTER); name.setTextSize(12); name.setPadding(0, dp(6), 0, 0);
            name.setMaxLines(2); name.setEllipsize(android.text.TextUtils.TruncateAt.END);
            name.setTextColor(document.state().filter == filter ? ACCENT : INK);
            name.setMinimumHeight(dp(28)); tile.addView(name, new LinearLayout.LayoutParams(-1, -2));
            tile.setContentDescription(getString(names[i])); tile.setFocusable(true);
            filterBindings.add(() -> {
                boolean active = document.state().filter == filter;
                outline.setColor(active ? ACCENT : EditorPalette.INPUT); name.setTextColor(active ? ACCENT : INK);
            });
            tile.setOnClickListener(v -> { if (!busy) mutate(() -> document.state().filter = filter); }); row.addView(tile);
            final EditorDocument.State thumbnailState = document.state().copy(); thumbnailState.items.clear(); thumbnailState.filter = filter; thumbnailState.filterStrength = 100;
            worker.execute(() -> {
                if (destroyed || request != panelGeneration) return;
                Bitmap small = null, result = null;
                try {
                    float scale = Math.min(1f, 160f / Math.max(source.getWidth(), source.getHeight()));
                    small = Bitmap.createScaledBitmap(source, Math.max(1, Math.round(source.getWidth() * scale)),
                            Math.max(1, Math.round(source.getHeight() * scale)), true);
                    result = EditorRenderer.export(small, thumbnailState, 160, false, java.util.Collections.emptyMap());
                } catch (RuntimeException | OutOfMemoryError ignored) {
                    // A missing decorative thumbnail must not fail the editor.
                } finally { if (small != null && small != source) small.recycle(); }
                Bitmap ready = result;
                handler.post(() -> {
                    if (ready == null) return;
                    if (destroyed || request != panelGeneration) { ready.recycle(); return; }
                    thumbnails.add(ready); image.setImageBitmap(ready);
                });
            });
        }
        ruler(panel, R.string.pe_intensity, 0, 100, 1, () -> (float) document.state().filterStrength,
                value -> document.state().filterStrength = Math.round(value));
    }

    private void drawingPanel(boolean hide) {
        LinearLayout row = scrollRow(panel);
        LinearLayout parameters = new LinearLayout(this); parameters.setOrientation(LinearLayout.VERTICAL); panel.addView(parameters);
        EditorDocument.Kind[] kinds = hide ? new EditorDocument.Kind[] {EditorDocument.Kind.COVER, EditorDocument.Kind.MOSAIC, EditorDocument.Kind.BLUR}
                : new EditorDocument.Kind[] {EditorDocument.Kind.PEN, EditorDocument.Kind.MARKER, EditorDocument.Kind.ERASER,
                    EditorDocument.Kind.ARROW, EditorDocument.Kind.LINE, EditorDocument.Kind.RECTANGLE, EditorDocument.Kind.OVAL};
        int[] names = hide ? new int[] {R.string.pe_cover, R.string.pe_mosaic, R.string.pe_blur}
                : new int[] {R.string.pe_pen, R.string.pe_marker, R.string.image_editor_eraser, R.string.pe_arrow, R.string.pe_line, R.string.pe_rectangle, R.string.pe_oval};
        final java.util.ArrayList<Button> choices = new java.util.ArrayList<>();
        for (int i = 0; i < kinds.length; i++) {
            final EditorDocument.Kind kind = kinds[i];
            Button choice = button(names[i], () -> {});
            choice.setOnClickListener(v -> {
                if (busy) return; canvas.setDrawingKind(kind);
                if (hide) hideTool = kind; else drawTool = kind;
                for (Button b : choices) highlight(b, b == choice);
                drawingParameters(parameters, kind);
            }); choices.add(choice); addButton(row, choice);
        }
        int active = 0;
        for (int i = 0; i < kinds.length; i++) if (kinds[i] == (hide ? hideTool : drawTool)) active = i;
        if (!choices.isEmpty()) choices.get(active).performClick();
    }

    private float toolSize(EditorDocument.Kind kind) {
        return kind == EditorDocument.Kind.BLUR
                ? com.mishiranu.dashchan.ui.posting.photo.EditorBlurPresets.size(blurPreset)
                : kind == EditorDocument.Kind.MOSAIC ? mosaicSize : brushSize;
    }

    private void drawingParameters(LinearLayout parameters, EditorDocument.Kind kind) {
        parameters.removeAllViews(); brushSwatch = null; toolBinding = null;
        canvas.setBrush(selectedColor, toolSize(kind)); canvas.setDrawingScope(drawAll);
        boolean color = kind != EditorDocument.Kind.ERASER && kind != EditorDocument.Kind.MOSAIC && kind != EditorDocument.Kind.BLUR;
        if (color) {
            LinearLayout row = scrollRow(parameters);
            brushSwatch = new EditorColorPickerView.Swatch(this, selectedColor);
            brushSwatch.setOnClickListener(v -> { if (!busy) chooseColor(); });
            row.addView(brushSwatch, new LinearLayout.LayoutParams(dp(48), dp(48)));
        }
        if (EditorRenderer.isDrawing(kind)) {
            LinearLayout scope = scrollRow(parameters);
            boolean eraser = kind == EditorDocument.Kind.ERASER;
            Button all = button(eraser ? R.string.pe_erase_all : R.string.pe_draw_above, () -> {});
            Button drawing = button(eraser ? R.string.pe_erase_drawing : R.string.pe_draw_below, () -> {});
            Runnable sync = () -> {
                all.setSelected(drawAll); drawing.setSelected(!drawAll);
                highlight(all, drawAll); highlight(drawing, !drawAll);
            };
            all.setOnClickListener(v -> { if (!busy) { drawAll = true; canvas.setDrawingScope(true); sync.run(); persist(); } });
            drawing.setOnClickListener(v -> { if (!busy) { drawAll = false; canvas.setDrawingScope(false); sync.run(); persist(); } });
            addButton(scope, all); addButton(scope, drawing); sync.run();
        } else {
            LinearLayout scope = scrollRow(parameters);
            TextView title = label(kind == EditorDocument.Kind.BLUR ? R.string.pe_mask_scope : R.string.pe_mask_scope_mosaic);
            scope.addView(title);
            Button all = button(R.string.pe_mask_all, () -> {}), photo = button(R.string.pe_mask_photo, () -> {});
            toolBinding = () -> {
                EditorDocument.Item active = canvas.activeMask(kind);
                boolean value = active != null ? active.affectAnnotations : maskAll;
                maskAll = value; canvas.setMaskScope(value);
                if (kind == EditorDocument.Kind.COVER && active != null) {
                    selectedColor = active.color; canvas.setBrush(selectedColor, toolSize(kind)); updateBrushSwatch();
                }
                all.setSelected(value); photo.setSelected(!value);
                highlight(all, value); highlight(photo, !value);
            };
            all.setOnClickListener(v -> { if (!busy) {
                maskAll = true; canvas.setMaskScope(true); canvas.updateLastMask(kind, null, true); toolBinding.run(); persist();
            } });
            photo.setOnClickListener(v -> { if (!busy) {
                maskAll = false; canvas.setMaskScope(false); canvas.updateLastMask(kind, null, false); toolBinding.run(); persist();
            } });
            addButton(scope, all); addButton(scope, photo); toolBinding.run();
            LinearLayout actions = scrollRow(parameters);
            addButton(actions, button(R.string.pe_new_region, () -> { if (!busy) canvas.requestNewMask(); }));
        }
        if (kind == EditorDocument.Kind.BLUR) {
            LinearLayout row = new LinearLayout(this); row.setGravity(Gravity.CENTER_VERTICAL);
            row.setPadding(dp(8), 0, dp(8), 0); parameters.addView(row);
            int[] names = {R.string.pe_blur_weak, R.string.pe_blur_medium, R.string.pe_blur_strong};
            java.util.ArrayList<Button> buttons = new java.util.ArrayList<>();
            Runnable scopeBinding = toolBinding;
            toolBinding = () -> {
                if (scopeBinding != null) scopeBinding.run();
                EditorDocument.Item active = canvas.activeMask(kind);
                int selected = active == null ? blurPreset
                        : com.mishiranu.dashchan.ui.posting.photo.EditorBlurPresets.nearest(active.size);
                blurPreset = selected; canvas.setBrush(selectedColor, toolSize(kind));
                for (int i = 0; i < buttons.size(); i++) {
                    buttons.get(i).setSelected(i == selected); highlight(buttons.get(i), i == selected);
                }
            };
            for (int i = 0; i < names.length; i++) {
                final int index = i;
                Button choice = button(names[i], () -> { if (!busy) {
                    blurPreset = index; canvas.setBrush(selectedColor, toolSize(kind));
                    canvas.updateLastMask(kind, toolSize(kind), null); toolBinding.run(); persist();
                } });
                choice.setPadding(dp(4), 0, dp(4), 0); choice.setMaxLines(2);
                buttons.add(choice); row.addView(choice, new LinearLayout.LayoutParams(0, dp(48), 1));
            }
            toolBinding.run();
        } else if (kind != EditorDocument.Kind.COVER) {
            slider(parameters, kind == EditorDocument.Kind.MOSAIC ? R.string.pe_block_size : R.string.pe_thickness,
                    1, 100, Math.round(toolSize(kind) * 1000), value -> {
                        if (kind == EditorDocument.Kind.MOSAIC) {
                            mosaicSize = value / 1000f;
                            EditorDocument.Item active = canvas.activeMask(kind);
                            if (active != null) active.size = mosaicSize;
                        }
                        else brushSize = value / 1000f;
                        canvas.setBrush(selectedColor, toolSize(kind));
                    }, kind == EditorDocument.Kind.MOSAIC, "‰", kind == EditorDocument.Kind.MOSAIC ? () -> {
                        EditorDocument.Item active = canvas.activeMask(kind);
                        if (active != null) mosaicSize = active.size;
                        canvas.setBrush(selectedColor, toolSize(kind));
                        return Math.round(mosaicSize * 1000);
                    } : null);
        }
    }

    private void updateBrushSwatch() {
        if (brushSwatch != null) brushSwatch.setColor(selectedColor);
    }

    private void chooseColor() {
        chooseColor(selectedColor, color -> {
            selectedColor = color; canvas.setBrush(selectedColor, toolSize(section == Section.HIDE ? hideTool : drawTool)); updateBrushSwatch();
            if (section == Section.HIDE && hideTool == EditorDocument.Kind.COVER) {
                canvas.updateMask(hideTool, null, null, color);
            }
        });
    }
    private void chooseColor(int initialColor, java.util.function.IntConsumer accept) {
        canvas.hideBrushPreview();
        AlertDialog.Builder builder = dialogBuilder(); Context context = builder.getContext();
        AlertDialog dialog = builder.create();
        EditorColorPickerView picker = new EditorColorPickerView(context, initialColor, color -> {
            if (!busy && !destroyed) accept.accept(color);
            dialog.dismiss();
        }, dialog::dismiss);
        dialog.setView(picker); dialog.show();
        if (dialog.getWindow() != null) resizeTextDialogForIme(dialog.getWindow(), false);
    }

    private EditorDocument.Item layerObject() {
        EditorDocument.Item item = canvas.selectedItem();
        if (item == null) return null;
        return section == Section.TEXT && item.kind == EditorDocument.Kind.TEXT
                || section == Section.STICKERS && (item.kind == EditorDocument.Kind.STICKER || item.kind == EditorDocument.Kind.IMAGE)
                ? item : null;
    }
    private void objectLayerPanel(LinearLayout parent) {
        LinearLayout row = new LinearLayout(this); row.setPadding(dp(8), 0, dp(8), 0); parent.addView(row);
        Button below = button(R.string.pe_object_below_effects, () -> changeObjectLayer(false));
        Button above = button(R.string.pe_object_above_effects, () -> changeObjectLayer(true));
        below.setMaxLines(2); above.setMaxLines(2);
        row.addView(below, new LinearLayout.LayoutParams(0, -2, 1));
        row.addView(above, new LinearLayout.LayoutParams(0, -2, 1));
        toolBinding = () -> {
            EditorDocument.Item item = layerObject();
            boolean value = item == null ? objectAboveEffects : item.aboveEffects;
            objectAboveEffects = value;
            above.setSelected(value); below.setSelected(!value); highlight(above, value); highlight(below, !value);
        };
        toolBinding.run();
    }
    private void changeObjectLayer(boolean above) {
        if (busy) return;
        canvas.finishGesture(true); objectAboveEffects = above;
        EditorDocument.Item item = layerObject();
        if (item != null && item.aboveEffects != above) mutate(() -> item.aboveEffects = above);
        if (toolBinding != null) toolBinding.run(); persist();
    }

    private void textPanel() {
        objectLayerPanel(panel);
        LinearLayout row = scrollRow(panel);
        addButton(row, button(R.string.pe_add_text, () -> editText(null, null)));
        addButton(row, button(R.string.pe_edit_selected, () -> {
            EditorDocument.Item item = canvas.selectedItem();
            if (item != null && item.kind == EditorDocument.Kind.TEXT) editText(item, null); else failure(R.string.pe_select_text);
        }));
        addButton(row, button(R.string.pe_delete_selected, this::deleteSelected));
    }

    private void editText(EditorDocument.Item target, String initial) {
        AlertDialog.Builder builder = dialogBuilder(); Context context = builder.getContext();
        final boolean plaque = target != null && target.kind == EditorDocument.Kind.STICKER;
        final int[] textColor = {target != null ? target.color : selectedColor};
        LinearLayout layout = new LinearLayout(context); layout.setOrientation(LinearLayout.VERTICAL); layout.setPadding(dp(16), dp(8), dp(16), dp(8));
        EditText input = new EditText(context); styleInput(input);
        input.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
        input.setSingleLine(false); input.setHorizontallyScrolling(false); input.setMinLines(3); input.setMaxLines(5);
        input.setGravity(Gravity.TOP | Gravity.START); input.setHint(R.string.pe_text_hint);
        input.setText(target != null ? target.text : initial != null ? initial : ""); layout.addView(input);
        CheckBox bg = checkBox(context, R.string.pe_text_background, target != null && target.background); layout.addView(bg);
        CheckBox outline = checkBox(context, R.string.pe_text_outline, target == null || target.outline); layout.addView(outline);
        if (plaque) { bg.setVisibility(View.GONE); outline.setVisibility(View.GONE); }
        LinearLayout colorRow = new LinearLayout(context); colorRow.setGravity(Gravity.CENTER_VERTICAL); layout.addView(colorRow);
        EditorColorPickerView.Swatch swatch = new EditorColorPickerView.Swatch(context, textColor[0]);
        colorRow.addView(swatch, new LinearLayout.LayoutParams(dp(48), dp(48)));
        ScrollView scroll = new ScrollView(context); scroll.setFillViewport(false); scroll.addView(layout);
        FrameLayout modal = new FrameLayout(context); modal.addView(scroll);
        AlertDialog dialog = builder.setTitle(R.string.pe_text).setView(modal).setNegativeButton(android.R.string.cancel, null)
                .setPositiveButton(android.R.string.ok, null).create();
        final EditorColorPickerView[] activePicker = {null};
        final int[] cursor = {0, 0};
        Runnable returnToText = () -> {
            if (activePicker[0] != null) modal.removeView(activePicker[0]);
            activePicker[0] = null; scroll.setVisibility(View.VISIBLE); dialog.setTitle(R.string.pe_text);
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setVisibility(View.VISIBLE);
            dialog.getButton(AlertDialog.BUTTON_NEGATIVE).setVisibility(View.VISIBLE);
            input.requestFocus(); input.setSelection(Math.min(cursor[0], input.length()), Math.min(cursor[1], input.length()));
            if (dialog.getWindow() != null) {
                resizeTextDialogForIme(dialog.getWindow(), true);
                WindowInsetsController ime = dialog.getWindow().getInsetsController();
                if (ime != null) ime.show(WindowInsets.Type.ime());
            }
        };
        swatch.setOnClickListener(v -> {
            if (activePicker[0] != null) return;
            cursor[0] = Math.max(0, input.getSelectionStart()); cursor[1] = Math.max(0, input.getSelectionEnd());
            input.clearFocus();
            if (dialog.getWindow() != null) {
                resizeTextDialogForIme(dialog.getWindow(), false);
                WindowInsetsController ime = dialog.getWindow().getInsetsController();
                if (ime != null) ime.hide(WindowInsets.Type.ime());
            }
            scroll.setVisibility(View.GONE); dialog.setTitle(R.string.pe_color);
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setVisibility(View.GONE);
            dialog.getButton(AlertDialog.BUTTON_NEGATIVE).setVisibility(View.GONE);
            activePicker[0] = new EditorColorPickerView(context, textColor[0], color -> {
                textColor[0] = color; swatch.setColor(color); returnToText.run();
            }, returnToText);
            modal.addView(activePicker[0]);
        });
        dialog.setOnKeyListener((ignored, key, event) -> {
            if (key != android.view.KeyEvent.KEYCODE_BACK || activePicker[0] == null) return false;
            if (event.getAction() == android.view.KeyEvent.ACTION_UP) returnToText.run();
            return true;
        });
        final Runnable[] unregisterBack = {null};
        dialog.setOnDismissListener(ignored -> {
            if (unregisterBack[0] != null) { unregisterBack[0].run(); unregisterBack[0] = null; }
        });
        dialog.setOnShowListener(ignored -> {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                unregisterBack[0] = registerDialogBack(dialog, () -> {
                    if (activePicker[0] != null) returnToText.run(); else dialog.cancel();
                });
            }
            input.requestFocus(); input.setSelection(input.length());
            Window window = dialog.getWindow();
            if (window != null) {
                resizeTextDialogForIme(window, true);
                input.post(() -> { if (dialog.isShowing() && input.isAttachedToWindow() && input.hasFocus()) {
                    WindowInsetsController controller = window.getInsetsController();
                    if (controller != null) controller.show(WindowInsets.Type.ime());
                }});
            }
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
            if (busy) return;
            String text = input.getText().toString(); if (!plaque && text.trim().isEmpty()) return;
            if (plaque && (text.length() > 120 || text.split("\\n", -1).length > 3)) { input.setError(getString(R.string.pe_plaque_limit)); return; }
            if (text.length() > 2000 || document.state().textLength() - (target == null ? 0 : target.text.length())
                    + text.length() > EditorDocument.TEXT_LIMIT
                    || (target == null && document.state().items.size() >= EditorDocument.ITEM_LIMIT)) {
                input.setError(getString(R.string.pe_limit)); return;
            }
            EditorDocument.Item item = target != null ? target : new EditorDocument.Item();
            mutate(() -> {
                if (!plaque) item.kind = EditorDocument.Kind.TEXT; item.text = text; item.color = textColor[0];
                item.background = bg.isChecked(); item.outline = outline.isChecked();
                if (target == null) { item.aboveEffects = objectAboveEffects; placeAtCropCenter(item); item.size = .07f; document.state().items.add(item); }
            }); canvas.select(item); dialog.dismiss();
        }); }); dialog.show();
    }

    @androidx.annotation.RequiresApi(Build.VERSION_CODES.TIRAMISU)
    private static Runnable registerDialogBack(AlertDialog dialog, Runnable action) {
        android.window.OnBackInvokedCallback callback = action::run;
        android.window.OnBackInvokedDispatcher dispatcher = dialog.getOnBackInvokedDispatcher();
        dispatcher.registerOnBackInvokedCallback(android.window.OnBackInvokedDispatcher.PRIORITY_DEFAULT, callback);
        return () -> dispatcher.unregisterOnBackInvokedCallback(callback);
    }

    private void placeAtCropCenter(EditorDocument.Item item) {
        android.graphics.RectF crop = EditorGeometry.cropPixels(document.state(), source.getWidth(), source.getHeight());
        android.graphics.Matrix inverse = new android.graphics.Matrix();
        EditorGeometry.sourceToFrame(document.state(), source.getWidth(), source.getHeight()).invert(inverse);
        float[] center = {crop.centerX(), crop.centerY()}; inverse.mapPoints(center);
        item.x = center[0] / source.getWidth(); item.y = center[1] / source.getHeight();
        // Newly added labels are horizontal in the current output, then stay attached to the photo.
        item.mirrored = document.state().mirror;
        item.angle = document.state().mirror ? -document.state().quarterTurns * 90f + document.state().straighten
                : -document.state().quarterTurns * 90f - document.state().straighten;
    }

    private void stickerPanel() {
        objectLayerPanel(panel);
        LinearLayout tabs = scrollRow(panel);
        LinearLayout grid = new LinearLayout(this); grid.setOrientation(LinearLayout.VERTICAL);
        int[] names = {R.string.pe_sticker_reactions, R.string.pe_sticker_symbols, R.string.pe_sticker_shapes};
        java.util.ArrayList<Button> buttons = new java.util.ArrayList<>();
        Runnable rebuild = () -> {
            grid.removeAllViews();
            for (int i = 0; i < buttons.size(); i++) highlight(buttons.get(i), i == stickerGroup);
            int columns = Math.max(2, Math.min(4, getResources().getDisplayMetrics().widthPixels / dp(76)));
            String[] ids = EditorStickers.GROUPS[stickerGroup];
            for (int i = 0; i < ids.length; i += columns) {
                LinearLayout row = new LinearLayout(this); row.setPadding(dp(8), 0, dp(8), 0); grid.addView(row);
                for (int j = 0; j < columns; j++) {
                    View tile;
                    if (i + j < ids.length) {
                        String id = ids[i + j]; tile = new EditorStickers.Tile(this, id, () -> addSticker(id));
                    } else tile = new View(this);
                    row.addView(tile, new LinearLayout.LayoutParams(0, dp(76), 1));
                }
            }
        };
        for (int i = 0; i < names.length; i++) {
            final int group = i; Button tab = button(names[i], () -> { if (!busy) { stickerGroup = group; rebuild.run(); } });
            buttons.add(tab); addButton(tabs, tab);
        }
        panel.addView(grid); rebuild.run();
        LinearLayout actions = scrollRow(panel);
        addButton(actions, button(R.string.pe_add_own_sticker, () -> imagePicker.launch("image/*")));
        addButton(actions, button(R.string.pe_plaque_text, () -> {
            EditorDocument.Item item = canvas.selectedItem();
            if (item != null && item.kind == EditorDocument.Kind.STICKER && EditorStickers.isPlaque(item.asset)) editText(item, null);
            else failure(R.string.pe_select_plaque);
        }));
        addButton(actions, button(R.string.pe_delete_selected, this::deleteSelected));
    }
    private void addSticker(String id) {
        if (busy) return;
        if (document.state().items.size() >= EditorDocument.ITEM_LIMIT) { failure(R.string.pe_limit); return; }
        EditorDocument.Item item = new EditorDocument.Item(); item.kind = EditorDocument.Kind.STICKER;
        item.aboveEffects = objectAboveEffects; item.asset = id; item.size = EditorStickers.isPlaque(id) ? .4f : .25f; item.color = 0xff252936; placeAtCropCenter(item);
        mutate(() -> document.state().items.add(item)); canvas.select(item); persist();
    }

    private void deleteSelected() {
        EditorDocument.Item item = canvas.selectedItem();
        if (item == null) { failure(R.string.pe_select_object); return; }
        mutate(() -> document.state().items.remove(item)); canvas.select(null);
    }

    private void importImage(FileHolder holder) {
        if (holder == null || assets.size() >= 8 || document.state().items.size() >= EditorDocument.ITEM_LIMIT) { failure(R.string.pe_limit); return; }
        setBusy(true, R.string.image_editor_loading);
        worker.execute(() -> {
            Bitmap bitmap = null; File file = new File(sessionDirectory, "asset-" + UUID.randomUUID() + ".png");
            try {
                bitmap = holder.readImageBitmap(1024, false, false); if (bitmap == null) throw new IOException("Unsupported asset");
                try (FileOutputStream stream = new FileOutputStream(file)) {
                    if (!bitmap.compress(Bitmap.CompressFormat.PNG, 100, stream)) throw new IOException("Asset encode failed");
                }
                Bitmap ready = bitmap;
                handler.post(() -> {
                    if (destroyed) { ready.recycle(); return; }
                    assets.put(file.getName(), ready); setBusy(false, 0);
                    EditorDocument.Item item = new EditorDocument.Item(); item.kind = EditorDocument.Kind.IMAGE;
                    item.aboveEffects = objectAboveEffects; item.asset = file.getName(); item.size = .3f; placeAtCropCenter(item);
                    mutate(() -> document.state().items.add(item)); canvas.select(item); persist();
                });
            } catch (IOException | RuntimeException | OutOfMemoryError e) {
                if (bitmap != null) bitmap.recycle(); file.delete();
                handler.post(() -> { if (!destroyed) { setBusy(false, 0); failure(R.string.image_editor_load_failed); }});
            }
        });
    }

    private void prepareSave() {
        if (section != Section.HOME) closeSection(true);
        panelMotion.release();
        canvas.finishGesture(true);
        canvas.settlePresentation();
    }

    private void saveDirect() {
        if (canvas == null || busy) return;
        prepareSave();
        encode(true, null);
    }

    private int exportSample(EditorDocument.State state, int width, int height, int side) {
        android.graphics.RectF crop = EditorGeometry.cropPixels(state, width, height);
        float wanted = side == 0 ? 1 : Math.max(1f, Math.max(crop.width(), crop.height()) / side);
        long budget = Math.min(12000000L, Math.max(1000000L, Runtime.getRuntime().maxMemory() / 40));
        int sample = 1;
        while (sample < (1 << 29) && sample * 2 <= wanted) sample *= 2;
        while ((long) ((width + (long) sample - 1) / sample) * ((height + (long) sample - 1) / sample) > budget) sample *= 2;
        return sample;
    }
    private String exportDimensions(int side) {
        int sample = exportSample(document.state(), originalWidth, originalHeight, side);
        int w = (int) ((originalWidth + (long) sample - 1) / sample), h = (int) ((originalHeight + (long) sample - 1) / sample);
        android.graphics.RectF crop = EditorGeometry.cropPixels(document.state(), w, h);
        float scale = side == 0 ? 1 : Math.min(1, side / Math.max(crop.width(), crop.height()));
        return Math.max(1, Math.round(crop.width() * scale)) + " × " + Math.max(1, Math.round(crop.height() * scale));
    }
    private void showExport() {
        if (canvas == null || busy || exportDialog != null) return;
        canvas.finishGesture(true);
        canvas.settlePresentation();
        AlertDialog.Builder builder = dialogBuilder();
        Context context = builder.getContext();
        LinearLayout layout = new LinearLayout(context);
        layout.setOrientation(LinearLayout.VERTICAL);
        layout.setPadding(dp(16), dp(8), dp(16), dp(8));
        // This is a draft: Cancel, outside tap and Back leave export settings unchanged.
        int[] side = {exportSide};
        int[] quality = {exportQuality};
        boolean[] png = {exportPng};
        TextView dimensions = label(context, R.string.pe_export_size);
        layout.addView(dimensions);
        Button size = button(context, side[0] == 0 ? getString(R.string.pe_export_no_resize) + " · " + exportDimensions(0)
                : exportDimensions(side[0]), () -> {});
        layout.addView(size);
        size.setOnClickListener(v -> {
            java.util.ArrayList<Integer> limits = new java.util.ArrayList<>();
            java.util.ArrayList<String> labels = new java.util.ArrayList<>();
            limits.add(0);
            labels.add(getString(R.string.pe_export_no_resize) + " · " + exportDimensions(0));
            android.graphics.RectF crop = EditorGeometry.cropPixels(document.state(), originalWidth, originalHeight);
            for (int limit : new int[] {4096, 3072, 2048, 1024}) {
                if (limit < Math.max(crop.width(), crop.height())) {
                    String dimensionsLabel = exportDimensions(limit);
                    if (!dimensionsLabel.equals(exportDimensions(0)) && !labels.contains(dimensionsLabel)) {
                        limits.add(limit);
                        labels.add(dimensionsLabel);
                    }
                }
            }
            int checked = limits.indexOf(side[0]);
            if (checked < 0) {
                checked = labels.indexOf(exportDimensions(side[0]));
                if (checked < 0) checked = 0;
            }
            dialogBuilder().setTitle(R.string.pe_export_size)
                    .setSingleChoiceItems(labels.toArray(new String[0]), checked, (d, which) -> {
                        side[0] = limits.get(which);
                        size.setText(side[0] == 0 ? getString(R.string.pe_export_no_resize) + " · " + exportDimensions(0)
                                : exportDimensions(side[0]));
                        d.dismiss();
                    }).setNegativeButton(android.R.string.cancel, null).show();
        });
        TextView formatTitle = label(context, R.string.pe_export_format);
        layout.addView(formatTitle);
        LinearLayout formats = scrollRow(layout);
        Button jpeg = button(context, "JPEG", () -> {});
        Button pngButton = button(context, "PNG", () -> {});
        addButton(formats, jpeg);
        addButton(formats, pngButton);
        View qualityControl = slider(layout, R.string.pe_quality, 50, 100, quality[0],
                value -> quality[0] = value, false, "%");
        Runnable sync = () -> {
            highlight(jpeg, !png[0]);
            highlight(pngButton, png[0]);
            qualityControl.setVisibility(png[0] ? View.GONE : View.VISIBLE);
        };
        jpeg.setOnClickListener(v -> {
            png[0] = false;
            sync.run();
        });
        pngButton.setOnClickListener(v -> {
            png[0] = true;
            sync.run();
        });
        sync.run();
        ScrollView scroll = new ScrollView(context);
        scroll.addView(layout);
        AlertDialog dialog = builder.setTitle(R.string.pe_export_settings).setView(scroll)
                .setNegativeButton(android.R.string.cancel, null)
                .setPositiveButton(R.string.pe_color_done, (d, w) -> {
                    exportSide = side[0];
                    exportQuality = quality[0];
                    exportPng = png[0];
                    encodedKey = null;
                    persist();
                }).create();
        exportDialog = dialog;
        exportControls = layout;
        dialog.setOnDismissListener(d -> {
            if (exportDialog == dialog) {
                exportDialog = null;
                exportControls = null;
            }
        });
        dialog.show();
    }

    private void encode(boolean attach, TextView sizeInfo) {
        if (busy || destroyed) return;
        final EditorExportRequest request;
        try {
            request = EditorExportRequest.capture(document,
                    new EditorExportRequest.Options(exportSide, exportQuality, exportPng),
                    new EditorExportRequest.Source(sourceHash, sourceName, attachmentIndex, originalWidth, originalHeight),
                    attach, assets);
        } catch (JSONException e) {
            failure(R.string.image_editor_save_failed);
            return;
        }
        setBusy(true, R.string.image_editor_saving);
        worker.execute(() -> {
            EditorDocument.State state = request.state;
            EditorExportRequest.Options options = request.options;
            File output = new File(sessionDirectory, options.png ? "export.png" : "export.jpg");
            Bitmap decoded = null;
            Bitmap result = null;
            try {
                if (!request.cacheKey.equals(encodedKey) || !output.isFile()) {
                    FileHolder holder = DraftsStorage.getInstance().getAttachmentDraftFileHolder(request.source.hash);
                    if (holder == null) throw new IOException("Source removed");
                    int w = holder.getImageWidth();
                    int h = holder.getImageHeight();
                    if (w <= 0 || h <= 0) throw new IOException("Invalid source dimensions");
                    // The settings size estimate and export share the existing bounded decode policy.
                    int sample = exportSample(state, w, h, options.side);
                    int decodeSide = (int) ((Math.max(w, h) + (long) sample - 1) / sample);
                    decoded = holder.readImageBitmap(decodeSide, false, false);
                    if (decoded == null) throw new IOException("Decode failed");
                    result = EditorRenderer.export(decoded, state, options.side, !options.png, request.assets);
                    try (FileOutputStream stream = new FileOutputStream(output)) {
                        if (!result.compress(options.png ? Bitmap.CompressFormat.PNG : Bitmap.CompressFormat.JPEG,
                                options.quality, stream)) {
                            throw new IOException("Encode failed");
                        }
                    }
                    encodedKey = request.cacheKey;
                }
                FileHolder resultHolder = FileHolder.obtain(output);
                if (resultHolder == null) throw new IOException("Invalid result");
                int outWidth = resultHolder.getImageWidth();
                int outHeight = resultHolder.getImageHeight();
                long bytes = output.length();
                String hash = request.attach ? DraftsStorage.getInstance().storeAttachmentFile(resultHolder) : null;
                if (request.attach && hash == null) throw new IOException("Draft save failed");
                handler.post(() -> {
                    if (destroyed) return;
                    setBusy(false, 0);
                    if (!request.attach) {
                        if (sizeInfo != null) sizeInfo.setText(getString(R.string.pe_export_info, outWidth, outHeight, bytes / 1024f));
                        return;
                    }
                    android.graphics.RectF wantedCrop = EditorGeometry.cropPixels(state, request.source.width, request.source.height);
                    float wantedScale = options.side == 0 ? 1 : Math.min(1, options.side / Math.max(wantedCrop.width(), wantedCrop.height()));
                    if (outWidth + 1 < Math.round(wantedCrop.width() * wantedScale)
                            || outHeight + 1 < Math.round(wantedCrop.height() * wantedScale)) {
                        android.widget.Toast.makeText(this, getString(R.string.pe_export_actual_size, outWidth, outHeight),
                                android.widget.Toast.LENGTH_LONG).show();
                    }
                    int dot = request.source.name.lastIndexOf('.');
                    String base = dot > 0 ? request.source.name.substring(0, dot) : request.source.name;
                    setResult(RESULT_OK, new Intent().putExtra(EXTRA_RESULT_HASH, hash)
                            .putExtra(EXTRA_RESULT_NAME, base + "_edited." + (options.png ? "png" : "jpg"))
                            .putExtra(EXTRA_RESULT_ATTACHMENT_INDEX, request.source.attachmentIndex)
                            .putExtra(EXTRA_RESULT_SOURCE_HASH, request.source.hash)
                            .putExtra(EXTRA_RESULT_SOURCE_NAME, request.source.name));
                    completed = true;
                    finish();
                });
            } catch (IOException | RuntimeException | OutOfMemoryError e) {
                encodedKey = null;
                output.delete();
                handler.post(() -> {
                    if (!destroyed) {
                        setBusy(false, 0);
                        failure(R.string.image_editor_save_failed);
                    }
                });
            } finally {
                if (result != null) result.recycle();
                if (decoded != null) decoded.recycle();
            }
        });
    }

    private void setBusy(boolean busy, int message) {
        panelMotion.release();
        if (canvas != null) canvas.settlePresentation();
        this.busy = busy;
        save.setEnabled(!busy && canvas != null);
        categories.setAlpha(busy ? .35f : 1);
        if (canvas != null) canvas.setInputEnabled(!busy);
        if (panel != null) setTreeEnabled(panel, !busy);
        if (cropToolbar != null) setTreeEnabled(cropToolbar, !busy);
        if (cropScale != null) setTreeEnabled(cropScale, !busy);
        setTreeEnabled(categories, !busy);
        if (exportDialog != null) {
            exportDialog.setCancelable(!busy);
            exportDialog.setCanceledOnTouchOutside(!busy);
            exportDialog.getButton(AlertDialog.BUTTON_POSITIVE).setEnabled(!busy);
            exportDialog.getButton(AlertDialog.BUTTON_NEGATIVE).setEnabled(!busy);
            setTreeEnabled(exportControls, !busy);
        }
        if (busy) {
            subtitle.setVisibility(View.VISIBLE);
            subtitle.setText(message);
        } else {
            updateSubtitle();
        }
        updateHistory();
    }

    private static void setTreeEnabled(View view, boolean enabled) {
        view.setEnabled(enabled);
        if (view instanceof ViewGroup) for (int i = 0; i < ((ViewGroup) view).getChildCount(); i++) setTreeEnabled(((ViewGroup) view).getChildAt(i), enabled);
    }

    private void requestClose() {
        if (busy) return;
        if (document.hasChanges()) dialogBuilder().setMessage(R.string.pe_discard)
                .setNegativeButton(android.R.string.cancel, null).setPositiveButton(R.string.pe_discard_button, (d, w) -> cancel()).show();
        else cancel();
    }
    private void cancel() { completed = true; setResult(RESULT_CANCELED); finish(); }
    private void failure(int message) {
        if (!destroyed && !isFinishing()) dialogBuilder().setMessage(message).setPositiveButton(android.R.string.ok, null).show();
    }

    private EditorSessionSnapshot.Builder sessionBuilder(EditorDocument capturedDocument) {
        EditorSessionSnapshot.Builder builder = new EditorSessionSnapshot.Builder(capturedDocument, sourceHash, sourceName);
        builder.checkpoint = checkpoint;
        builder.section = section;
        builder.adjustmentIndex = adjustmentIndex;
        builder.drawTool = drawTool;
        builder.hideTool = hideTool;
        builder.selectedColor = selectedColor;
        builder.brushSize = brushSize;
        builder.mosaicSize = mosaicSize;
        builder.blurPreset = blurPreset;
        builder.drawAll = drawAll;
        builder.objectAboveEffects = objectAboveEffects;
        builder.maskAll = maskAll;
        builder.exportSide = exportSide;
        builder.exportQuality = exportQuality;
        builder.exportPng = exportPng;
        return builder;
    }

    private void applySession(EditorSessionSnapshot snapshot) {
        document = snapshot.document;
        checkpoint = snapshot.checkpoint;
        section = snapshot.section;
        adjustmentIndex = snapshot.adjustmentIndex;
        drawTool = snapshot.drawTool;
        hideTool = snapshot.hideTool;
        selectedColor = snapshot.selectedColor;
        brushSize = snapshot.brushSize;
        mosaicSize = snapshot.mosaicSize;
        blurPreset = snapshot.blurPreset;
        drawAll = snapshot.drawAll;
        objectAboveEffects = snapshot.objectAboveEffects;
        maskAll = snapshot.maskAll;
        exportSide = snapshot.exportSide;
        exportQuality = snapshot.exportQuality;
        exportPng = snapshot.exportPng;
    }

    private void persist() {
        if (source == null || completed || destroyed || sessionDirectory == null) return;
        final EditorSessionSnapshot snapshot;
        try {
            snapshot = sessionBuilder(document.copy()).build();
        } catch (OutOfMemoryError e) {
            failure(R.string.pe_session_failed);
            return;
        }
        final int request = ++sessionGeneration;
        // Serialization and AtomicFile IO are ordered with other worker jobs and final cleanup.
        worker.execute(() -> {
            if (request != sessionGeneration) return;
            AtomicFile file = new AtomicFile(new File(sessionDirectory, "session.json"));
            FileOutputStream stream = null;
            try {
                byte[] bytes = EditorSessionCodec.encode(snapshot).toString().getBytes(StandardCharsets.UTF_8);
                if (request != sessionGeneration) return;
                stream = file.startWrite();
                stream.write(bytes);
                file.finishWrite(stream);
            } catch (IOException | JSONException | OutOfMemoryError e) {
                if (stream != null) file.failWrite(stream);
                handler.post(() -> {
                    if (!destroyed && request == sessionGeneration) failure(R.string.pe_session_failed);
                });
            }
        });
    }

    @Override protected void onSaveInstanceState(Bundle out) {
        if (sessionDirectory != null) out.putString(SESSION, sessionDirectory.getName());
        settleEditor();
        persist();
        super.onSaveInstanceState(out);
    }

    @Override public void onConfigurationChanged(android.content.res.Configuration configuration) {
        super.onConfigurationChanged(configuration);
        settleEditor();
        if (canvas != null) buildPanel();
        handler.post(() -> {
            if (!destroyed && content != null) content.getRootView().requestLayout();
        });
    }

    private void settleEditor() {
        panelMotion.release();
        if (canvas != null) {
            canvas.finishGesture(true);
            canvas.settlePresentation();
        }
    }

    @Override protected void onStop() {
        settleEditor();
        persist();
        super.onStop();
    }

    @Override protected void onDestroy() {
        destroyed = true;
        panelMotion.release();
        if (canvas != null) canvas.release();
        // Cleanup is ordered after every render/export job; bitmaps cannot be recycled under a running worker.
        worker.execute(() -> {
            if (source != null && !source.isRecycled()) source.recycle();
            for (Bitmap asset : assets.values()) if (!asset.isRecycled()) asset.recycle();
            for (Bitmap bitmap : thumbnails) if (!bitmap.isRecycled()) bitmap.recycle();
            if (completed && sessionDirectory != null) PhotoEditorSessionFiles.remove(sessionDirectory);
            if (sessionDirectory != null) PhotoEditorSessionFiles.unregister(sessionDirectory);
        });
        worker.shutdown();
        super.onDestroy();
    }
}
