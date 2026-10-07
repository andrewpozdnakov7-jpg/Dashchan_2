package com.mishiranu.dashchan.ui.posting;

import android.app.AlertDialog;
import android.content.Context;
import android.content.Intent;
import android.content.res.ColorStateList;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.RippleDrawable;
import android.os.Bundle;
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
import com.mishiranu.dashchan.ui.posting.photo.EditorDocument;
import com.mishiranu.dashchan.ui.posting.photo.EditorGeometry;
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
    private static final int ACCENT = 0xffa8c7fa, SURFACE = 0xff1b1b1f, INK = 0xffe3e3e3;
    private enum Section { HOME, CROP, ADJUST, FILTERS, DRAW, TEXT, HIDE, STICKERS }
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
    private int attachmentIndex, selectedColor = Color.WHITE, exportSide = 4096, exportQuality = 94, adjustmentIndex;
    private float brushSize = .025f;
    private boolean exportPng, busy, completed, restoredWithLoss;
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
    private ImageView brushSwatch;
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
        view.setTextColor(controlText()); view.setBackground(new RippleDrawable(ColorStateList.valueOf(0x30ffffff), background(0xff28292e), null)); view.setMinWidth(dp(48));
        view.setMinimumWidth(dp(48)); view.setMinHeight(dp(48)); view.setPadding(dp(12), 0, dp(12), 0);
        view.setOnClickListener(v -> { if (!busy) action.run(); }); return view;
    }
    private ColorStateList controlText() {
        return new ColorStateList(new int[][] {{-android.R.attr.state_enabled}, {}}, new int[] {0x66e3e3e3, INK});
    }
    private CheckBox checkBox(Context context, int text, boolean checked) {
        CheckBox view = new CheckBox(context); view.setText(text); view.setTextColor(controlText()); view.setChecked(checked);
        view.setButtonTintList(new ColorStateList(new int[][] {{-android.R.attr.state_enabled},
                {android.R.attr.state_checked}, {}}, new int[] {0x66b4b6bd, ACCENT, 0xffb4b6bd}));
        return view;
    }
    private void styleInput(EditText view) {
        view.setTextColor(INK); view.setHintTextColor(0xffb4b6bd);
        GradientDrawable shape = background(0xff303138); shape.setCornerRadius(dp(12)); shape.setStroke(dp(1), 0xff686b74);
        view.setBackground(shape); view.setPadding(dp(12), dp(12), dp(12), dp(12));
        view.setBackgroundTintList(null);
    }
    @SuppressWarnings("deprecation")
    private void resizeTextDialogForIme(Window window) {
        // Floating framework dialogs retain the platform IME resize contract on API 30+.
        // IME visibility itself is controlled below through public WindowInsetsController.
        window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE
                | WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_VISIBLE);
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
        panelMotion.highlight(button, active ? ACCENT : 0xff28292e, active ? 0xff062e60 : INK);
    }
    private LinearLayout toolTile(int title, int image, boolean active, boolean small, Runnable action) {
        LinearLayout tile = new LinearLayout(this); tile.setOrientation(LinearLayout.VERTICAL); tile.setGravity(Gravity.CENTER);
        tile.setPadding(dp(4), dp(4), dp(4), dp(4)); tile.setContentDescription(getString(title));
        tile.setFocusable(true); tile.setClickable(true);
        tile.setBackground(new RippleDrawable(ColorStateList.valueOf(0x24ffffff), background(Color.TRANSPARENT), null));
        ImageView symbol = new ImageView(this); symbol.setImageDrawable(icon(image, 24, active ? 0xff062e60 : INK));
        symbol.setPadding(dp(small ? 16 : 10), dp(small ? 4 : 10), dp(small ? 16 : 10), dp(small ? 4 : 10));
        symbol.setBackground(background(active ? ACCENT : small ? Color.TRANSPARENT : 0xff303138));
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
            ImageView image = (ImageView) entry.getValue().getChildAt(0); image.setColorFilter(active ? 0xff062e60 : INK);
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
        root.setBackgroundColor(0xff121316); setContentView(root);
        getWindow().getDecorView().setBackgroundColor(0xff121316);
        ViewUtils.setStatusBarColor(getWindow(), 0xff121316);
        ViewUtils.setNavigationBarColor(getWindow(), 0xff121316);
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
        save = button(R.string.pe_save, this::showExport); save.setTextColor(Color.BLACK); save.setBackground(background(ACCENT));
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
                exportPng = holder.getImageType() == FileHolder.ImageType.IMAGE_PNG;
                AtomicFile session = new AtomicFile(new File(sessionDirectory, "session.json"));
                if (session.getBaseFile().isFile()) {
                    try {
                        if (session.getBaseFile().length() > 16 * 1024 * 1024) throw new IOException("Oversized session");
                        JSONObject json = new JSONObject(new String(session.readFully(), StandardCharsets.UTF_8));
                        if (!sourceHash.equals(json.getString("hash")) || !sourceName.equals(json.getString("name"))) {
                            throw new JSONException("Source mismatch");
                        }
                        document = EditorDocument.fromJson(json.getJSONObject("document"));
                        checkpoint = json.isNull("checkpoint") ? null : json.getJSONObject("checkpoint").toString();
                        section = Section.valueOf(json.getString("section"));
                        selectedColor = json.optInt("color", Color.WHITE); brushSize = (float) json.optDouble("size", .025);
                        exportPng = json.optBoolean("png", exportPng); exportSide = json.optInt("side", 4096);
                        exportQuality = json.optInt("quality", 94);
                        adjustmentIndex = Math.max(0, Math.min(6, json.optInt("adjustment", 0)));
                        drawTool = EditorDocument.Kind.valueOf(json.optString("drawTool", "PEN"));
                        hideTool = EditorDocument.Kind.valueOf(json.optString("hideTool", "COVER"));
                        brushSize = Math.max(.001f, Math.min(.1f, brushSize));
                        if (exportSide != 1024 && exportSide != 2048 && exportSide != 3072 && exportSide != 4096 && exportSide != 16384) exportSide = 4096;
                        exportQuality = Math.max(50, Math.min(100, exportQuality));
                    } catch (IOException | JSONException | IllegalArgumentException e) {
                        document = new EditorDocument(); checkpoint = null; section = Section.HOME; restoredWithLoss = true;
                    }
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
                        @Override public void onChange() { updateHistory(); }
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
        return java.util.Arrays.asList(headerRow, historyRow, toolScroll, categories, cropToolbar, cropScale);
    }

    private void buildPanel() {
        panelGeneration++; panel.removeAllViews(); rulerBindings.clear(); filterBindings.clear(); brushSwatch = null;
        for (Bitmap bitmap : thumbnails) if (!bitmap.isRecycled()) bitmap.recycle();
        thumbnails.clear(); categories.setVisibility(View.VISIBLE); updateCategories();
        if (cropToolbar != null) { content.removeView(cropToolbar); cropToolbar = null; }
        if (cropScale != null) { content.removeView(cropScale); cropScale = null; }
        boolean crop = section == Section.CROP;
        headerRow.setVisibility(crop ? View.GONE : View.VISIBLE);
        historyRow.setVisibility(crop ? View.GONE : View.VISIBLE);
        categories.setVisibility(crop ? View.GONE : View.VISIBLE);
        canvas.setDrawingKind(null); canvas.setCropMode(section == Section.CROP); canvas.setBrush(selectedColor, brushSize);
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
        apply.getCompoundDrawables()[0].setTint(crop ? 0xff062e60 : ACCENT);
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
        updateSubtitle();
    }
    private void updateSubtitle() {
        if (source != null) {
            android.graphics.RectF crop = EditorGeometry.cropPixels(document.state(), source.getWidth(), source.getHeight());
            subtitle.setText(getString(R.string.pe_preview_dimensions, Math.round(crop.width()), Math.round(crop.height())));
        }
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
        TextView name = label(title); name.setTextColor(0xffb4b6bd); name.setMinimumHeight(dp(28));
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
        LinearLayout group = new LinearLayout(parent.getContext()); group.setOrientation(LinearLayout.VERTICAL); parent.addView(group);
        parent = group;
        TextView label = label(parent.getContext(), title); parent.addView(label);
        SeekBar slider = new SeekBar(new android.view.ContextThemeWrapper(parent.getContext(), R.style.PhotoEditorDialogTheme)); slider.setMax(maximum - minimum); slider.setProgress(value - minimum);
        slider.setProgressTintList(ColorStateList.valueOf(ACCENT)); slider.setThumbTintList(ColorStateList.valueOf(ACCENT));
        slider.setProgressBackgroundTintList(ColorStateList.valueOf(0xff5d6066));
        slider.setPadding(dp(18), 0, dp(18), 0); parent.addView(slider, new LinearLayout.LayoutParams(-1, dp(40)));
        Runnable updateLabel = () -> {
            int current = slider.getProgress() + minimum;
            String number = title == R.string.pe_horizon ? String.format(Locale.getDefault(), "%.1f°", current / 10f)
                    : title == R.string.pe_size ? String.format(Locale.getDefault(), "%.1f%%", current / 10f) : current + suffix;
            label.setText(getString(title) + ": " + number);
        };
        updateLabel.run();
        slider.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onStartTrackingTouch(SeekBar bar) { if (history && !busy) document.begin(); }
            @Override public void onProgressChanged(SeekBar bar, int progress, boolean fromUser) {
                updateLabel.run();
                if (fromUser && !busy) {
                    // Accessibility changes can happen without onStartTrackingTouch.
                    boolean own = history && !document.isInTransaction(); if (own) document.begin();
                    change.set(progress + minimum); if (own) document.commit(); changed();
                }
            }
            @Override public void onStopTrackingTouch(SeekBar bar) { if (history) document.commit(); changed(); }
        });
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
        mirror.setBackground(background(0xff222225)); addButton(row, mirror);
        Button rotate = iconButton(R.drawable.ic_editor_rotate, R.string.pe_rotate, () ->
                geometryChange(EditorMotion.ROTATE, 90, false, () -> EditorGeometry.rotateQuarter(document.state()), true));
        rotate.setBackground(background(0xff222225)); addButton(row, rotate);
        Button reset = iconButton(R.drawable.ic_refresh, R.string.pe_reset, () -> {
            geometryChange(EditorMotion.RESET, 0, true, () -> { EditorDocument.State s = document.state(); s.crop.set(0, 0, 1, 1);
                s.straighten = 0; s.quarterTurns = 0; s.mirror = false; s.aspect = 0; }, true);
        }); reset.setBackground(background(0xff222225)); addButton(row, reset);
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
        list.setPadding(dp(4), dp(8), dp(4), dp(8)); GradientDrawable menuBackground = background(0xff28242a); menuBackground.setCornerRadius(dp(8)); list.setBackground(menuBackground);
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

    private void adjustmentPanel() {
        int[] labels = {R.string.pe_brightness, R.string.pe_contrast, R.string.pe_saturation, R.string.pe_warmth,
                R.string.pe_shadows, R.string.pe_highlights, R.string.pe_sharpness};
        int[] icons = {R.drawable.ic_pe_brightness, R.drawable.ic_pe_contrast, R.drawable.ic_pe_saturation,
                R.drawable.ic_pe_warmth, R.drawable.ic_pe_shadows, R.drawable.ic_pe_highlights, R.drawable.ic_pe_sharpness};
        LinearLayout selection = scrollRow(panel), controls = new LinearLayout(this); controls.setOrientation(LinearLayout.VERTICAL);
        panel.addView(controls);
        for (int i = 0; i < labels.length; i++) {
            final int index = i;
            selection.addView(toolTile(labels[i], icons[i], i == adjustmentIndex, false,
                    () -> { adjustmentIndex = index; buildPanel(); }), new LinearLayout.LayoutParams(dp(80), -2));
        }
        final int index = adjustmentIndex;
        ruler(controls, labels[index], index == 6 ? 0 : -100, 100, 1, () -> (float) document.state().adjustments[index],
                value -> document.state().adjustments[index] = Math.round(value));
        LinearLayout row = scrollRow(controls);
        Button reset = button(R.string.pe_reset, () -> geometryChange(EditorMotion.RESET, 0, true,
                () -> document.state().adjustments[index] = 0, true));
        reset.setMinHeight(dp(40)); reset.setTextSize(12); row.addView(reset, new LinearLayout.LayoutParams(-2, dp(40)));
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
            GradientDrawable outline = background(document.state().filter == filter ? ACCENT : 0xff303138); outline.setCornerRadius(dp(12));
            image.setBackground(outline); image.setClipToOutline(true); image.setPadding(dp(2), dp(2), dp(2), dp(2)); image.setImageBitmap(source);
            tile.addView(image, new LinearLayout.LayoutParams(dp(76), dp(76)));
            TextView name = label(names[i]); name.setGravity(Gravity.CENTER); name.setTextSize(12); name.setPadding(0, dp(6), 0, 0);
            name.setMaxLines(2); name.setEllipsize(android.text.TextUtils.TruncateAt.END);
            name.setTextColor(document.state().filter == filter ? ACCENT : INK);
            name.setMinimumHeight(dp(28)); tile.addView(name, new LinearLayout.LayoutParams(-1, -2));
            tile.setContentDescription(getString(names[i])); tile.setFocusable(true);
            filterBindings.add(() -> {
                boolean active = document.state().filter == filter;
                outline.setColor(active ? ACCENT : 0xff303138); name.setTextColor(active ? ACCENT : INK);
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
            }); choices.add(choice); addButton(row, choice);
        }
        int active = 0;
        for (int i = 0; i < kinds.length; i++) if (kinds[i] == (hide ? hideTool : drawTool)) active = i;
        if (!choices.isEmpty()) choices.get(active).performClick();
        LinearLayout settings = scrollRow(panel);
        addButton(settings, button(R.string.pe_color, this::chooseColor));
        brushSwatch = new ImageView(this); brushSwatch.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        settings.addView(brushSwatch, new LinearLayout.LayoutParams(dp(24), dp(24))); updateBrushSwatch();
        settings.addView(label(R.string.pe_brush_hint));
        slider(panel, R.string.pe_size, 1, 100, Math.round(brushSize * 1000), value -> {
            brushSize = value / 1000f; canvas.setBrush(selectedColor, brushSize);
        }, false, "‰");
    }

    private void updateBrushSwatch() {
        if (brushSwatch != null) {
            GradientDrawable swatch = background(selectedColor); swatch.setStroke(dp(1), INK);
            brushSwatch.setBackground(swatch);
        }
    }

    private void chooseColor() {
        chooseColor(selectedColor, color -> {
            selectedColor = color; canvas.setBrush(selectedColor, brushSize); updateBrushSwatch();
        });
    }
    private void chooseColor(int initialColor, java.util.function.IntConsumer accept) {
        AlertDialog.Builder builder = dialogBuilder(); Context context = builder.getContext();
        int[] colors = {Color.WHITE, Color.BLACK, 0xfff44336, 0xffff9800, 0xffffeb3b, 0xff4caf50,
                0xff00bcd4, 0xff2196f3, 0xff9c27b0, 0xffe91e63};
        LinearLayout layout = new LinearLayout(context); layout.setOrientation(LinearLayout.VERTICAL); layout.setPadding(dp(16), dp(8), dp(16), dp(8));
        LinearLayout row = new LinearLayout(context); layout.addView(row);
        EditText hex = new EditText(context); hex.setSingleLine(); hex.setHint("#RRGGBB"); styleInput(hex);
        hex.setText(String.format(Locale.US, "#%06X", initialColor & 0xffffff)); layout.addView(hex);
        for (int i = 0; i < colors.length; i++) {
            final int color = colors[i];
            if (i == 5) { row = new LinearLayout(context); layout.addView(row, 1); }
            Button swatch = button(context, "", () -> hex.setText(String.format(Locale.US, "#%06X", color & 0xffffff)));
            swatch.setBackground(background(color)); swatch.setContentDescription(String.format(Locale.US, "#%06X", color & 0xffffff));
            row.addView(swatch, new LinearLayout.LayoutParams(0, dp(48), 1));
        }
        ScrollView scroll = new ScrollView(context); scroll.addView(layout);
        AlertDialog dialog = builder.setTitle(R.string.pe_color).setView(scroll)
                .setNegativeButton(android.R.string.cancel, null).setPositiveButton(android.R.string.ok, null).create();
        dialog.setOnShowListener(ignored -> dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
            String value = hex.getText().toString().trim();
            if (!value.matches("#[0-9a-fA-F]{6}")) { hex.setError(getString(R.string.pe_invalid_color)); return; }
            accept.accept(Color.parseColor(value)); dialog.dismiss();
        })); dialog.show();
    }

    private void textPanel() {
        LinearLayout row = scrollRow(panel);
        addButton(row, button(R.string.pe_add_text, () -> editText(null, null)));
        addButton(row, button(R.string.pe_edit_selected, () -> {
            EditorDocument.Item item = canvas.selectedItem();
            if (item != null && item.kind == EditorDocument.Kind.TEXT) editText(item, null); else failure(R.string.pe_select_text);
        }));
        addButton(row, button(R.string.pe_delete_selected, this::deleteSelected));
        panel.addView(label(R.string.pe_object_hint));
    }

    private void editText(EditorDocument.Item target, String initial) {
        AlertDialog.Builder builder = dialogBuilder(); Context context = builder.getContext();
        final int[] textColor = {target != null ? target.color : selectedColor};
        LinearLayout layout = new LinearLayout(context); layout.setOrientation(LinearLayout.VERTICAL); layout.setPadding(dp(16), dp(8), dp(16), dp(8));
        EditText input = new EditText(context); styleInput(input);
        input.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
        input.setSingleLine(false); input.setHorizontallyScrolling(false); input.setMinLines(3); input.setMaxLines(5);
        input.setGravity(Gravity.TOP | Gravity.START); input.setHint(R.string.pe_text_hint);
        input.setText(target != null ? target.text : initial != null ? initial : ""); layout.addView(input);
        CheckBox bg = checkBox(context, R.string.pe_text_background, target != null && target.background); layout.addView(bg);
        CheckBox outline = checkBox(context, R.string.pe_text_outline, target == null || target.outline); layout.addView(outline);
        LinearLayout colorRow = new LinearLayout(context); colorRow.setGravity(Gravity.CENTER_VERTICAL); layout.addView(colorRow);
        ImageView swatch = new ImageView(context); swatch.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        Runnable updateColor = () -> { GradientDrawable shape = background(textColor[0]); shape.setStroke(dp(1), INK); swatch.setBackground(shape); };
        updateColor.run();
        colorRow.addView(button(context, getString(R.string.pe_color), () -> chooseColor(textColor[0], color -> {
            textColor[0] = color; updateColor.run();
        })), new LinearLayout.LayoutParams(0, dp(48), 1));
        colorRow.addView(swatch, new LinearLayout.LayoutParams(dp(32), dp(32)));
        ScrollView scroll = new ScrollView(context); scroll.setFillViewport(false); scroll.addView(layout);
        AlertDialog dialog = builder.setTitle(R.string.pe_text).setView(scroll).setNegativeButton(android.R.string.cancel, null)
                .setPositiveButton(android.R.string.ok, null).create();
        dialog.setOnShowListener(ignored -> {
            input.requestFocus(); input.setSelection(input.length());
            Window window = dialog.getWindow();
            if (window != null) {
                resizeTextDialogForIme(window);
                input.post(() -> { if (dialog.isShowing() && input.isAttachedToWindow() && input.hasFocus()) {
                    WindowInsetsController controller = window.getInsetsController();
                    if (controller != null) controller.show(WindowInsets.Type.ime());
                }});
            }
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
            if (busy) return;
            String text = input.getText().toString(); if (text.trim().isEmpty()) return;
            if (text.length() > 2000 || document.state().textLength() - (target == null ? 0 : target.text.length())
                    + text.length() > EditorDocument.TEXT_LIMIT
                    || (target == null && document.state().items.size() >= EditorDocument.ITEM_LIMIT)) {
                input.setError(getString(R.string.pe_limit)); return;
            }
            EditorDocument.Item item = target != null ? target : new EditorDocument.Item();
            mutate(() -> {
                item.kind = EditorDocument.Kind.TEXT; item.text = text; item.color = textColor[0];
                item.background = bg.isChecked(); item.outline = outline.isChecked();
                if (target == null) { placeAtCropCenter(item); item.size = .07f; document.state().items.add(item); }
            }); canvas.select(item); dialog.dismiss();
        }); }); dialog.show();
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
        LinearLayout row = scrollRow(panel);
        String[] emoji = {"😀", "😂", "❤️", "👍", "🔥", "💩", "🤡", "🚫", "🐱", "✅", "❗", "❓"};
        for (String text : emoji) addButton(row, button(text, () -> editText(null, text)));
        LinearLayout actions = scrollRow(panel);
        addButton(actions, button(R.string.pe_add_image, () -> imagePicker.launch("image/*")));
        addButton(actions, button(R.string.pe_delete_selected, this::deleteSelected)); panel.addView(label(R.string.pe_object_hint));
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
                    item.asset = file.getName(); item.size = .3f; placeAtCropCenter(item);
                    mutate(() -> document.state().items.add(item)); canvas.select(item); persist();
                });
            } catch (IOException | RuntimeException | OutOfMemoryError e) {
                if (bitmap != null) bitmap.recycle(); file.delete();
                handler.post(() -> { if (!destroyed) { setBusy(false, 0); failure(R.string.image_editor_load_failed); }});
            }
        });
    }

    private void showExport() {
        if (canvas == null || busy) return;
        if (section != Section.HOME) closeSection(true);
        panelMotion.release(); canvas.finishGesture(true); canvas.settlePresentation();
        AlertDialog.Builder builder = dialogBuilder(); Context context = builder.getContext();
        LinearLayout layout = new LinearLayout(context); layout.setOrientation(LinearLayout.VERTICAL); layout.setPadding(dp(12), 0, dp(12), 0);
        layout.addView(label(context, R.string.pe_export_hint));
        LinearLayout sizes = scrollRow(layout);
        for (int side : new int[] {1024, 2048, 3072, 4096, 16384}) {
            Button option = button(context, side == 16384 ? getString(R.string.pe_original_size) : Integer.toString(side), () -> {});
            option.setOnClickListener(v -> { if (!busy) { exportSide = side; encodedKey = null;
                for (int i = 0; i < sizes.getChildCount(); i++) highlight((Button) sizes.getChildAt(i), sizes.getChildAt(i) == v);
            }}); highlight(option, exportSide == side); addButton(sizes, option);
        }
        CheckBox png = checkBox(context, R.string.pe_png, exportPng); layout.addView(png);
        View quality = slider(layout, R.string.pe_quality, 50, 100, exportQuality,
                value -> { exportQuality = value; encodedKey = null; }, false, "%");
        quality.setVisibility(exportPng ? View.GONE : View.VISIBLE);
        png.setOnCheckedChangeListener((v, checked) -> {
            if (!busy) { exportPng = checked; encodedKey = null; quality.setVisibility(checked ? View.GONE : View.VISIBLE); }
        });
        TextView sizeInfo = label(context, R.string.pe_size_unknown); layout.addView(sizeInfo);
        Button measure = button(context, getString(R.string.pe_measure), () -> encode(false, sizeInfo)); layout.addView(measure);
        ScrollView scroll = new ScrollView(context); scroll.addView(layout);
        AlertDialog dialog = builder.setTitle(R.string.pe_export).setView(scroll)
                .setNegativeButton(android.R.string.cancel, null).setPositiveButton(R.string.pe_save, null).create();
        exportDialog = dialog; exportControls = layout;
        dialog.setOnDismissListener(ignored -> { if (exportDialog == dialog) { exportDialog = null; exportControls = null; } });
        dialog.setOnShowListener(ignored -> dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
            if (!busy) { dialog.dismiss(); encode(true, null); }
        })); dialog.show();
    }

    private void encode(boolean attach, TextView sizeInfo) {
        if (busy || destroyed) return;
        EditorDocument.State state = document.state().copy();
        final int side = exportSide, quality = exportQuality;
        final boolean png = exportPng;
        final String key;
        try { key = document.toJson().getJSONObject("state").toString() + ":" + side + ":" + quality + ":" + png; }
        catch (JSONException e) { failure(R.string.image_editor_save_failed); return; }
        HashMap<String, Bitmap> assetSnapshot = new HashMap<>(assets);
        setBusy(true, R.string.image_editor_saving);
        worker.execute(() -> {
            File output = new File(sessionDirectory, "export." + (png ? "png" : "jpg"));
            Bitmap decoded = null, result = null;
            try {
                if (!key.equals(encodedKey) || !output.isFile()) {
                    FileHolder holder = DraftsStorage.getInstance().getAttachmentDraftFileHolder(sourceHash);
                    if (holder == null) throw new IOException("Source removed");
                    int w = holder.getImageWidth(), h = holder.getImageHeight();
                    if (w <= 0 || h <= 0) throw new IOException("Invalid source dimensions");
                    android.graphics.RectF originalCrop = EditorGeometry.cropPixels(state, w, h);
                    float cropSide = Math.max(originalCrop.width(), originalCrop.height());
                    float wantedSample = Math.max(1f, cropSide / side);
                    // Keep working source, pixel array, processed image and result within a bounded memory budget.
                    long pixels = Math.min(12000000L, Math.max(1000000L, Runtime.getRuntime().maxMemory() / 40));
                    int sample = 1;
                    while (sample < (1 << 29) && sample * 2 <= wantedSample) sample *= 2;
                    while ((long) ((w + (long) sample - 1) / sample) * ((h + (long) sample - 1) / sample) > pixels) sample *= 2;
                    int decodeSide = (int) ((Math.max(w, h) + (long) sample - 1) / sample);
                    decoded = holder.readImageBitmap(decodeSide, false, false);
                    if (decoded == null) throw new IOException("Decode failed");
                    result = EditorRenderer.export(decoded, state, side, !png, assetSnapshot);
                    try (FileOutputStream stream = new FileOutputStream(output)) {
                        if (!result.compress(png ? Bitmap.CompressFormat.PNG : Bitmap.CompressFormat.JPEG, quality, stream)) {
                            throw new IOException("Encode failed");
                        }
                    }
                    encodedKey = key;
                }
                FileHolder resultHolder = FileHolder.obtain(output); if (resultHolder == null) throw new IOException("Invalid result");
                int outWidth = resultHolder.getImageWidth(), outHeight = resultHolder.getImageHeight(); long bytes = output.length();
                String hash = attach ? DraftsStorage.getInstance().storeAttachmentFile(resultHolder) : null;
                if (attach && hash == null) throw new IOException("Draft save failed");
                handler.post(() -> {
                    if (destroyed) return;
                    setBusy(false, 0);
                    if (!attach) {
                        if (sizeInfo != null) sizeInfo.setText(getString(R.string.pe_export_info, outWidth, outHeight, bytes / 1024f));
                        return;
                    }
                    int dot = sourceName.lastIndexOf('.'); String base = dot > 0 ? sourceName.substring(0, dot) : sourceName;
                    setResult(RESULT_OK, new Intent().putExtra(EXTRA_RESULT_HASH, hash)
                            .putExtra(EXTRA_RESULT_NAME, base + "_edited." + (png ? "png" : "jpg"))
                            .putExtra(EXTRA_RESULT_ATTACHMENT_INDEX, attachmentIndex)
                            .putExtra(EXTRA_RESULT_SOURCE_HASH, sourceHash).putExtra(EXTRA_RESULT_SOURCE_NAME, sourceName));
                    completed = true; finish();
                });
            } catch (IOException | RuntimeException | OutOfMemoryError e) {
                encodedKey = null; output.delete();
                handler.post(() -> { if (!destroyed) { setBusy(false, 0); failure(R.string.image_editor_save_failed); }});
            } finally { if (result != null) result.recycle(); if (decoded != null) decoded.recycle(); }
        });
    }

    private void setBusy(boolean busy, int message) {
        panelMotion.release(); if (canvas != null) canvas.settlePresentation();
        this.busy = busy; save.setEnabled(!busy && canvas != null); categories.setAlpha(busy ? .35f : 1);
        if (canvas != null) canvas.setInputEnabled(!busy);
        if (panel != null) setTreeEnabled(panel, !busy);
        if (cropToolbar != null) setTreeEnabled(cropToolbar, !busy);
        if (cropScale != null) setTreeEnabled(cropScale, !busy);
        setTreeEnabled(categories, !busy);
        if (exportDialog != null) {
            exportDialog.setCancelable(!busy); exportDialog.setCanceledOnTouchOutside(!busy);
            exportDialog.getButton(AlertDialog.BUTTON_POSITIVE).setEnabled(!busy);
            exportDialog.getButton(AlertDialog.BUTTON_NEGATIVE).setEnabled(!busy);
            setTreeEnabled(exportControls, !busy);
        }
        if (busy) subtitle.setText(message); else updateSubtitle(); updateHistory();
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

    private void persist() {
        if (source == null || completed || destroyed || sessionDirectory == null) return;
        final EditorDocument snapshot;
        try {
            snapshot = document.copy();
        } catch (OutOfMemoryError e) {
            failure(R.string.pe_session_failed); return;
        }
        final int request = ++sessionGeneration;
        final String savedSection = section.name(), savedCheckpoint = checkpoint;
        final String savedDrawTool = drawTool.name(), savedHideTool = hideTool.name();
        final int savedAdjustment = adjustmentIndex, savedColor = selectedColor;
        final int savedSide = exportSide, savedQuality = exportQuality;
        final float savedSize = brushSize;
        final boolean savedPng = exportPng;
        // Serialization and AtomicFile IO are ordered with other worker jobs and final cleanup.
        worker.execute(() -> {
            if (request != sessionGeneration) return;
            AtomicFile file = new AtomicFile(new File(sessionDirectory, "session.json"));
            FileOutputStream stream = null;
            try {
                JSONObject json = new JSONObject().put("hash", sourceHash).put("name", sourceName)
                        .put("document", snapshot.toJson()).put("section", savedSection)
                        .put("checkpoint", savedCheckpoint == null ? JSONObject.NULL : new JSONObject(savedCheckpoint))
                        .put("adjustment", savedAdjustment).put("drawTool", savedDrawTool).put("hideTool", savedHideTool)
                        .put("color", savedColor).put("size", savedSize).put("png", savedPng)
                        .put("side", savedSide).put("quality", savedQuality);
                byte[] bytes = json.toString().getBytes(StandardCharsets.UTF_8);
                if (request != sessionGeneration) return;
                stream = file.startWrite(); stream.write(bytes); file.finishWrite(stream);
            } catch (IOException | JSONException | OutOfMemoryError e) {
                if (stream != null) file.failWrite(stream);
                handler.post(() -> { if (!destroyed && request == sessionGeneration) failure(R.string.pe_session_failed); });
            }
        });
    }
    @Override protected void onSaveInstanceState(Bundle out) {
        if (sessionDirectory != null) out.putString(SESSION, sessionDirectory.getName());
        settleEditor(); persist(); super.onSaveInstanceState(out);
    }
    @Override public void onConfigurationChanged(android.content.res.Configuration configuration) {
        super.onConfigurationChanged(configuration);
        settleEditor(); if (canvas != null) buildPanel();
        handler.post(() -> { if (!destroyed && content != null) content.getRootView().requestLayout(); });
    }
    private void settleEditor() {
        panelMotion.release(); if (canvas != null) { canvas.finishGesture(true); canvas.settlePresentation(); }
    }
    @Override protected void onStop() { settleEditor(); persist(); super.onStop(); }
    @Override protected void onDestroy() {
        destroyed = true; panelMotion.release(); if (canvas != null) canvas.release();
        // Cleanup is ordered after every render/export job; bitmaps cannot be recycled under a running worker.
        worker.execute(() -> {
            if (source != null && !source.isRecycled()) source.recycle();
            for (Bitmap asset : assets.values()) if (!asset.isRecycled()) asset.recycle();
            for (Bitmap bitmap : thumbnails) if (!bitmap.isRecycled()) bitmap.recycle();
            if (completed && sessionDirectory != null) PhotoEditorSessionFiles.remove(sessionDirectory);
            if (sessionDirectory != null) PhotoEditorSessionFiles.unregister(sessionDirectory);
        }); worker.shutdown(); super.onDestroy();
    }
}
