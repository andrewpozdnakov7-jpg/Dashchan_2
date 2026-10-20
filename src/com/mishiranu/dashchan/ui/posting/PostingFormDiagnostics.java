package com.mishiranu.dashchan.ui.posting;

import android.app.Activity;
import android.content.Context;
import android.graphics.Rect;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.text.Layout;
import android.text.Editable;
import android.text.TextWatcher;
import android.util.Log;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewTreeObserver;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;
import com.mishiranu.dashchan.BuildConfig;
import com.mishiranu.dashchan.R;
import com.mishiranu.dashchan.content.Preferences;
import com.mishiranu.dashchan.util.ConcurrentUtils;
import com.mishiranu.dashchan.widget.PostingFormAppearance;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.WeakHashMap;
import java.lang.ref.WeakReference;
import java.util.function.Consumer;

/** Permanent opt-in geometry diagnostics. Never record editable content or view.toString(). */
public final class PostingFormDiagnostics {
	private static final int MAX_CHARS = 4 * 1024 * 1024;
	private static final int LOGCAT_CHUNK = 2800;
	private static final WeakHashMap<View, WeakReference<Observer>> OBSERVERS = new WeakHashMap<>();
	private static final ArrayDeque<String> LINES = new ArrayDeque<>();
	private static final Handler MAIN = new Handler(Looper.getMainLooper());
	private static volatile boolean recording;
	private static volatile boolean saving;
	private static long started;
	private static long generation;
	private static int chars, dropped;
	private static String header = "";
	private static String pendingExport;

	private PostingFormDiagnostics() {}

	public static boolean isRecording() { return recording; }
	public static boolean isSaving() { return saving; }
	public static synchronized boolean hasPendingExport() { return pendingExport != null; }

	public static synchronized void start(Context context) {
		if (recording || saving) return;
		LINES.clear();
		chars = dropped = 0;
		pendingExport = null;
		started = SystemClock.elapsedRealtime();
		generation++;
		header = "Posting form geometry diagnostics v3\nviewportPolicy=keyboard_viewport_v2\napp=" + BuildConfig.VERSION_NAME
				+ " code=" + BuildConfig.VERSION_CODE + " android=" + Build.VERSION.RELEASE
				+ " sdk=" + Build.VERSION.SDK_INT + " manufacturer=" + Build.MANUFACTURER
				+ " model=" + Build.MODEL + " targetSdk=" + context.getApplicationInfo().targetSdkVersion
				+ "\nstartedUtcMillis=" + System.currentTimeMillis()
				+ "\nlimits: ringChars=" + MAX_CHARS + " sampleMs=50 treeMs=250 attachmentNodes=96 eventsPerSecond=160"
				+ "\nNo text, field values, filenames, file paths, URLs, credentials, clipboard, screenshots or key input are recorded.\n";
		recording = true;
		record("capture_start");
	}

	private static synchronized void record(String message) {
		if (!recording) return;
		String line = "+" + (SystemClock.elapsedRealtime() - started) + "ms " + message;
		LINES.addLast(line);
		chars += line.length();
		while (chars > MAX_CHARS && !LINES.isEmpty()) {
			chars -= LINES.removeFirst().length();
			dropped++;
		}
		for (int offset = 0; offset < line.length(); offset += LOGCAT_CHUNK) {
			Log.i("PostingForm", (offset > 0 ? "continued " : "")
					+ line.substring(offset, Math.min(line.length(), offset + LOGCAT_CHUNK)));
		}
	}

	/** Call only with developer-defined event names and numeric/enum/geometry data, never UI content. */
	public static void trace(View origin, String event, String details) { trace(origin, event, details, false); }
	public static void traceState(View origin, String event, String details) { trace(origin, event, details, true); }
	private static void trace(View origin, String event, String details, boolean deduplicate) {
		if (!recording || origin == null || Looper.myLooper() != Looper.getMainLooper()) return;
		View view = origin;
		for (int depth = 0; view != null && depth < 64; depth++) {
			WeakReference<Observer> reference = OBSERVERS.get(view);
			Observer observer = reference != null ? reference.get() : null;
			if (observer != null && !observer.closed) { observer.event(event, details, deduplicate); return; }
			view = view.getParent() instanceof View ? (View) view.getParent() : null;
		}
	}

	/** Resource identifiers and developer view types only; never view.toString or accessibility labels. */
	public static String viewLabel(View view) {
		if (view == null) return "none";
		String id = "no_id";
		if (view.getId() != View.NO_ID) {
			try { id = view.getResources().getResourceEntryName(view.getId()); }
			catch (android.content.res.Resources.NotFoundException e) { id = "generated_id"; }
		}
		return id + "/" + view.getClass().getSimpleName();
	}

	public static File getLastFile(Context context) {
		File file = new File(context.getCacheDir(), "posting-form-diagnostics.txt");
		return file.isFile() ? file : null;
	}

	public static void stopAndSave(Context context, Consumer<File> callback) {
		final String report;
		synchronized (PostingFormDiagnostics.class) {
			if (saving) return;
			if (recording) {
				record("capture_stop");
				recording = false;
				pendingExport = header + "droppedOldEvents=" + dropped + "\n" + String.join("\n", LINES) + "\n";
				LINES.clear();
				chars = 0;
			}
			if (pendingExport == null) return;
			report = pendingExport;
			saving = true;
		}
		File file = new File(context.getApplicationContext().getCacheDir(), "posting-form-diagnostics.txt");
		ConcurrentUtils.PARALLEL_EXECUTOR.execute(() -> {
			File result = null;
			try (FileOutputStream output = new FileOutputStream(file)) {
				output.write(report.getBytes(StandardCharsets.UTF_8));
				result = file;
			} catch (IOException e) {
				result = null;
				Log.w("PostingForm", "export_failed type=" + e.getClass().getSimpleName());
			}
			synchronized (PostingFormDiagnostics.class) {
				if (result != null) pendingExport = null;
				saving = false;
			}
			File completed = result;
			MAIN.post(() -> callback.accept(completed));
		});
	}

	public static final class Observer {
		private final Activity activity;
		private final View root, scroll, footer, captchaRoot, captchaInput;
		private final EditText editor;
		private final String session;
		private boolean closed, polling;
		private long lastSample, lastTree, captureGeneration = -1, eventWindow;
		private int eventCount, throttledEvents, lastAttachmentNodes;
		private final Map<String, String> geometryStates = new HashMap<>(), traceStates = new HashMap<>();
		private String lastState, lastDecision;
		private final ViewTreeObserver.OnGlobalFocusChangeListener focus = (before, after) -> {
			if (recording) event("focus", "from=" + viewLabel(before) + " to=" + viewLabel(after), false);
		};
		private final ViewTreeObserver.OnWindowFocusChangeListener windowFocus = focused ->
				event("window_focus", "focused=" + focused, false);
		private final ViewTreeObserver.OnScrollChangedListener scrolled = () -> {
			if (recording && !closed) sample("scroll", false);
		};
		private final View.OnLayoutChangeListener laidOut = (v, l, t, r, b, ol, ot, or, ob) -> {
			if (recording && (l != ol || t != ot || r != or || b != ob)) event("layout",
					"view=" + viewLabel(v) + " from=" + ol + "," + ot + "," + or + "," + ob
							+ " to=" + l + "," + t + "," + r + "," + b, false);
		};
		private final View.OnAttachStateChangeListener attachment = new View.OnAttachStateChangeListener() {
			@Override public void onViewAttachedToWindow(View view) { event("root_attach", "attached=true", false); }
			@Override public void onViewDetachedFromWindow(View view) {
				event("root_detach", "attached=false", false); MAIN.removeCallbacks(pollTask); polling = false;
			}
		};
		private final TextWatcher textChanges = new TextWatcher() {
			@Override public void beforeTextChanged(CharSequence text, int start, int count, int after) {}
			@Override public void onTextChanged(CharSequence text, int start, int before, int count) {
				// No CharSequence content is inspected, copied or retained.
				if (recording) event("editor_change", "start=" + start + " removed=" + before + " added=" + count, false);
			}
			@Override public void afterTextChanged(Editable text) {}
		};
		private final ViewTreeObserver.OnPreDrawListener preDraw = () -> {
			if (recording && !closed) {
				sample("pre_draw", false);
				if (!polling) {
					polling = true;
					MAIN.postDelayed(this.pollTask, 200);
				}
			}
			return true;
		};
		private final Runnable pollTask = this::poll;

		public Observer(Activity activity, View root, View scroll, EditText editor, View footer) {
			this.activity = activity;
			this.root = root;
			this.scroll = scroll;
			this.editor = editor;
			this.footer = footer;
			captchaRoot = root.findViewById(R.id.captcha_input_root);
			captchaInput = root.findViewById(R.id.captcha_input);
			session = Integer.toHexString(System.identityHashCode(this));
			root.getViewTreeObserver().addOnPreDrawListener(preDraw);
			root.getViewTreeObserver().addOnGlobalFocusChangeListener(focus);
			root.getViewTreeObserver().addOnWindowFocusChangeListener(windowFocus);
			root.getViewTreeObserver().addOnScrollChangedListener(scrolled);
			root.addOnLayoutChangeListener(laidOut); scroll.addOnLayoutChangeListener(laidOut);
			editor.addOnLayoutChangeListener(laidOut); footer.addOnLayoutChangeListener(laidOut);
			root.addOnAttachStateChangeListener(attachment); editor.addTextChangedListener(textChanges);
			OBSERVERS.put(root, new WeakReference<>(this));
			sample("attach", true);
		}

		private void poll() {
			polling = false;
			if (!recording || closed || !root.isAttachedToWindow()) return;
			sample("poll", false);
			polling = true;
			MAIN.postDelayed(pollTask, 200);
		}

		public void close() {
			sample("detach", true);
			closed = true;
			MAIN.removeCallbacks(pollTask);
			if (root.getViewTreeObserver().isAlive()) {
				root.getViewTreeObserver().removeOnPreDrawListener(preDraw);
				root.getViewTreeObserver().removeOnGlobalFocusChangeListener(focus);
				root.getViewTreeObserver().removeOnWindowFocusChangeListener(windowFocus);
				root.getViewTreeObserver().removeOnScrollChangedListener(scrolled);
			}
			root.removeOnLayoutChangeListener(laidOut); scroll.removeOnLayoutChangeListener(laidOut);
			editor.removeOnLayoutChangeListener(laidOut); footer.removeOnLayoutChangeListener(laidOut);
			root.removeOnAttachStateChangeListener(attachment); editor.removeTextChangedListener(textChanges);
			WeakReference<Observer> reference = OBSERVERS.get(root);
			if (reference != null && reference.get() == this) OBSERVERS.remove(root);
			geometryStates.clear(); traceStates.clear();
		}

		public void decision(String details) {
			if (!recording || closed) return;
			resetCapture();
			if (details.equals(lastDecision)) return;
			lastDecision = details;
			event("decision", details, false);
		}

		private void resetCapture() {
			if (captureGeneration == generation) return;
			captureGeneration = generation; lastSample = lastTree = 0; lastState = lastDecision = null;
			lastAttachmentNodes = -1;
			eventWindow = 0; eventCount = throttledEvents = 0; geometryStates.clear(); traceStates.clear();
		}
		private void event(String event, String details, boolean deduplicate) {
			if (!recording || closed) return;
			resetCapture();
			if (deduplicate && details.equals(traceStates.get(event))) return;
			long now = SystemClock.elapsedRealtime();
			if (now - eventWindow >= 1000) {
				if (throttledEvents > 0) record("session=" + session + " events_throttled=" + throttledEvents);
				eventWindow = now; eventCount = throttledEvents = 0;
			}
			if (eventCount++ >= 160) { throttledEvents++; return; }
			if (deduplicate) traceStates.put(event, details);
			record("session=" + session + " " + event + " " + details);
		}

		private static String geometry(View view) {
			if (view == null) return "none";
			int[] xy = new int[2];
			view.getLocationOnScreen(xy);
			Rect visible = new Rect();
			boolean shown = view.getGlobalVisibleRect(visible);
			ViewGroup.LayoutParams params = view.getLayoutParams();
			String layout = params != null ? params.width + "x" + params.height : "none";
			if (params instanceof ViewGroup.MarginLayoutParams) {
				ViewGroup.MarginLayoutParams margins = (ViewGroup.MarginLayoutParams) params;
				layout += " margins=" + margins.leftMargin + "," + margins.topMargin + "," + margins.rightMargin + "," + margins.bottomMargin;
			}
			if (params instanceof LinearLayout.LayoutParams) layout += " weight=" + ((LinearLayout.LayoutParams) params).weight;
			return "type=" + viewLabel(view) + " xy=" + xy[0] + "," + xy[1] + " size=" + view.getWidth() + "x" + view.getHeight()
					+ " measured=" + view.getMeasuredWidth() + "x" + view.getMeasuredHeight()
					+ " local=" + view.getLeft() + "," + view.getTop() + "," + view.getRight() + "," + view.getBottom()
					+ " scroll=" + view.getScrollX() + "," + view.getScrollY()
					+ " padding=" + view.getPaddingLeft() + "," + view.getPaddingTop() + "," + view.getPaddingRight() + "," + view.getPaddingBottom()
					+ " lp=" + layout + " min=" + view.getMinimumWidth() + "," + view.getMinimumHeight()
					+ " visibility=" + view.getVisibility() + " visible=" + shown + ":" + visible.toShortString()
					+ " attached=" + view.isAttachedToWindow() + " layout=" + view.isLayoutRequested()
					+ " focus=" + view.hasFocus() + " enabled=" + view.isEnabled()
					+ " click=" + view.isClickable() + "," + view.isLongClickable()
					+ " transform=" + view.getTranslationX() + "," + view.getTranslationY() + "," + view.getScaleX() + "," + view.getScaleY()
					+ " alpha=" + view.getAlpha() + " elevation=" + view.getElevation()
					+ " clip=" + view.getClipBounds() + "," + view.getClipToOutline()
					+ (view instanceof ViewGroup ? " groupClip=" + ((ViewGroup) view).getClipChildren() + "," + ((ViewGroup) view).getClipToPadding()
							+ " children=" + ((ViewGroup) view).getChildCount() : "")
					+ (view instanceof TextView ? " textMetrics=" + ((TextView) view).getTextSize() + "," + ((TextView) view).getLineHeight() : "");
		}

		private void viewState(String name, View view, boolean force) {
			String state = geometry(view);
			if (force || !state.equals(geometryStates.get(name))) {
				geometryStates.put(name, state); record("session=" + session + " geometry " + name + "{" + state + "}");
			}
		}
		private void attachmentTree(View view, String label, int depth, int[] budget, boolean force) {
			if (view == null || budget[0] >= 96) return;
			budget[0]++; viewState(label, view, force);
			if (depth < 4 && view instanceof ViewGroup) {
				ViewGroup group = (ViewGroup) view;
				for (int i = 0; i < group.getChildCount() && budget[0] < 96; i++) {
					attachmentTree(group.getChildAt(i), label + "/" + i, depth + 1, budget, force);
				}
			}
		}

		private void sample(String event, boolean force) {
			if (!recording || closed) return;
			resetCapture();
			long now = SystemClock.elapsedRealtime();
			if (!force && now - lastSample < 50) return;
			lastSample = now;
			try {
				View decor = activity.getWindow().getDecorView();
				WindowInsetsCompat insets = ViewCompat.getRootWindowInsets(scroll);
				WindowInsetsCompat decorInsets = ViewCompat.getRootWindowInsets(decor);
				Rect frame = new Rect();
				decor.getWindowVisibleDisplayFrame(frame);
				int selection = editor.getSelectionEnd();
				Layout layout = editor.getLayout();
				int line = layout != null && selection >= 0 && selection <= editor.length()
						? layout.getLineForOffset(selection) : -1;
				int[] xy = new int[2];
				editor.getLocationOnScreen(xy);
				int caretBottom = line >= 0 ? xy[1] + editor.getTotalPaddingTop()
						+ layout.getLineBottom(line) - editor.getScrollY() : -1;
				View comment = root.findViewById(R.id.comment_parent);
				if (comment == null) comment = editor;
				View toolbar = activity.findViewById(R.id.toolbar);
				int[] commentAt = new int[2], viewportAt = new int[2], decorAt = new int[2], toolbarAt = new int[2];
				comment.getLocationOnScreen(commentAt); scroll.getLocationOnScreen(viewportAt);
				decor.getLocationOnScreen(decorAt);
				if (toolbar != null) toolbar.getLocationOnScreen(toolbarAt);
				int imeBottom = decorInsets != null ? decorInsets.getInsets(WindowInsetsCompat.Type.ime()).bottom : 0;
				int keyboardTop = decorAt[1] + decor.getHeight() - imeBottom;
				int topEdge = Math.max(viewportAt[1] + scroll.getPaddingTop(),
						toolbar != null && toolbar.isShown() ? toolbarAt[1] + toolbar.getHeight() : 0);
				int topGap = commentAt[1] - topEdge, bottomGap = keyboardTop - commentAt[1] - comment.getHeight();
				float density = root.getResources().getDisplayMetrics().density;
				String state = "ime=" + (insets != null ? insets.isVisible(WindowInsetsCompat.Type.ime()) : "unknown")
						+ " imeBottom=" + (insets != null ? insets.getInsets(WindowInsetsCompat.Type.ime()).bottom : -1)
						+ " decorIme=" + (decorInsets != null ? decorInsets.isVisible(WindowInsetsCompat.Type.ime()) : "unknown")
						+ " decorImeBottom=" + (decorInsets != null ? decorInsets.getInsets(WindowInsetsCompat.Type.ime()).bottom : -1)
						+ " bars=" + (insets != null ? insets.getInsets(WindowInsetsCompat.Type.systemBars()) : "unknown")
						+ " status=" + (decorInsets != null ? decorInsets.getInsets(WindowInsetsCompat.Type.statusBars()) : "unknown")
						+ " navigation=" + (decorInsets != null ? decorInsets.getInsets(WindowInsetsCompat.Type.navigationBars()) : "unknown")
						+ " cutout=" + (decorInsets != null ? decorInsets.getInsets(WindowInsetsCompat.Type.displayCutout()) : "unknown")
						+ " gestures=" + (decorInsets != null ? decorInsets.getInsets(WindowInsetsCompat.Type.systemGestures()) : "unknown")
						+ " frame=" + frame.toShortString() + " softInput=" + activity.getWindow().getAttributes().softInputMode
						+ " flags=" + activity.getWindow().getAttributes().flags + " windowFocus=" + decor.hasWindowFocus()
						+ " orientation=" + root.getResources().getConfiguration().orientation
						+ " density=" + root.getResources().getDisplayMetrics().density
						+ " fontScale=" + root.getResources().getConfiguration().fontScale
						+ " screenDp=" + root.getResources().getConfiguration().screenWidthDp + ","
								+ root.getResources().getConfiguration().screenHeightDp
						+ " layoutDirection=" + root.getLayoutDirection()
						+ " multiWindow=" + activity.isInMultiWindowMode() + " pip=" + activity.isInPictureInPictureMode()
						+ " textSizePx=" + editor.getTextSize() + " lineHeight=" + editor.getLineHeight()
						+ " viewport=" + scroll.getWidth() + "x" + scroll.getHeight() + " scrollY=" + scroll.getScrollY()
						+ " keyboardTopPx=" + keyboardTop + " topGapPx=" + topGap + " bottomGapPx=" + bottomGap
						+ " topGapDp=" + topGap / density + " bottomGapDp=" + bottomGap / density
						+ " gapsValidIme=" + (imeBottom > 0)
						+ " viewportOwner=" + PostingFormAppearance.ownsKeyboardViewport(editor)
						+ " newForm=" + PostingFormAppearance.isEnabled() + " heightMode=" + Preferences.getPostingEditorHeightMode()
						+ " fixedLines=" + Preferences.getPostingEditorFixedLines()
						+ " hiddenFields=" + Preferences.isPostingNameHidden() + "," + Preferences.isPostingEmailHidden()
								+ "," + Preferences.isPostingPasswordHidden() + "," + Preferences.isPostingSubjectHidden()
						+ " length=" + editor.length() + " lines=" + editor.getLineCount()
						+ " textLayoutHeight=" + (layout != null ? layout.getHeight() : -1)
						+ " selection=" + editor.getSelectionStart() + "," + selection + " caretLine=" + line
						+ " caretBottom=" + caretBottom + " focus=" + editor.hasFocus()
						+ " minMaxLines=" + editor.getMinLines() + "," + editor.getMaxLines()
						+ " minMaxHeight=" + editor.getMinHeight() + "," + editor.getMaxHeight()
						+ " canScroll=" + editor.canScrollVertically(-1) + "," + editor.canScrollVertically(1);
				if (force || !state.equals(lastState)) {
					lastState = state;
					record("session=" + session + " " + event + " " + state);
				}
				Map<String, View> views = new LinkedHashMap<>();
				views.put("decor", decor); views.put("root", root); views.put("viewport", scroll);
				views.put("editor", editor); views.put("comment", comment); views.put("footer", footer);
				views.put("toolbar", toolbar); views.put("toolbarLayout", activity.findViewById(R.id.toolbar_layout));
				// Original captcha widgets may temporarily be lent to a separate dialog.
				// Keep geometry references, not their input values, while outside this root.
				views.put("captchaOwnedRoot", captchaRoot); views.put("captchaOwnedInput", captchaInput);
				views.put("captchaWindow", captchaRoot != null && captchaRoot.isAttachedToWindow() ? captchaRoot.getRootView() : null);
				int[] ids = {R.id.posting_layout, R.id.posting_action_container, R.id.subject, R.id.personal_data_block,
						R.id.name, R.id.email, R.id.password, R.id.icon, R.id.remaining_characters,
						R.id.checkbox_parent, R.id.sage_checkbox, R.id.spoiler_checkbox, R.id.original_poster_checkbox,
						R.id.attachment_container, R.id.captcha_input_root, R.id.captcha_input};
				for (int id : ids) views.put(root.getResources().getResourceEntryName(id), root.findViewById(id));
				for (Map.Entry<String, View> entry : views.entrySet()) viewState(entry.getKey(), entry.getValue(), force);
				View parent = root.getParent() instanceof View ? (View) root.getParent() : null;
				for (int depth = 0; parent != null && depth < 6; depth++) {
					viewState("ancestor/" + depth, parent, force);
					parent = parent.getParent() instanceof View ? (View) parent.getParent() : null;
				}
				if (force || now - lastTree >= 250) {
					lastTree = now;
					int[] budget = {0}; attachmentTree(root.findViewById(R.id.attachment_container), "attachments", 0, budget, force);
					if (budget[0] != lastAttachmentNodes || force) {
						event("attachment_tree", "nodes=" + budget[0] + " limitReached=" + (budget[0] == 96), true);
						lastAttachmentNodes = budget[0];
					}
					attachmentTree(comment, "editorTree", 0, new int[] {0}, force);
				}
			} catch (RuntimeException e) {
				record("session=" + session + " snapshot_failed type=" + e.getClass().getSimpleName());
			}
		}
	}
}
