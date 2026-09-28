package com.mishiranu.dashchan.ui.posting;

import android.app.Activity;
import android.content.Context;
import android.graphics.Rect;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.text.Layout;
import android.util.Log;
import android.view.View;
import android.view.ViewTreeObserver;
import android.widget.EditText;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;
import com.mishiranu.dashchan.BuildConfig;
import com.mishiranu.dashchan.util.ConcurrentUtils;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.function.Consumer;

/** Permanent opt-in geometry diagnostics. Never record editable content or view.toString(). */
public final class PostingFormDiagnostics {
	private static final int MAX_CHARS = 1024 * 1024;
	private static final ArrayDeque<String> LINES = new ArrayDeque<>();
	private static final Handler MAIN = new Handler(Looper.getMainLooper());
	private static volatile boolean recording;
	private static volatile boolean saving;
	private static long started;
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
		header = "Posting form geometry diagnostics v1\napp=" + BuildConfig.VERSION_NAME
				+ " code=" + BuildConfig.VERSION_CODE + " android=" + Build.VERSION.RELEASE
				+ " sdk=" + Build.VERSION.SDK_INT + " manufacturer=" + Build.MANUFACTURER
				+ " model=" + Build.MODEL + " targetSdk=" + context.getApplicationInfo().targetSdkVersion
				+ "\nstartedUtcMillis=" + System.currentTimeMillis()
				+ "\nNo text, credentials, URLs, screenshots or keyboard input are recorded.\n";
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
		Log.i("PostingForm", line);
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
		private final View root, scroll, footer;
		private final EditText editor;
		private final String session;
		private boolean closed, polling;
		private long lastSample;
		private String lastState, lastDecision;
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
			session = Integer.toHexString(System.identityHashCode(this));
			root.getViewTreeObserver().addOnPreDrawListener(preDraw);
			sample("attach", true);
		}

		private void poll() {
			polling = false;
			if (!recording || closed) return;
			sample("poll", false);
			polling = true;
			MAIN.postDelayed(pollTask, 200);
		}

		public void close() {
			sample("detach", true);
			closed = true;
			MAIN.removeCallbacks(pollTask);
			if (root.getViewTreeObserver().isAlive()) root.getViewTreeObserver().removeOnPreDrawListener(preDraw);
		}

		public void decision(String details) {
			if (!recording || closed || details.equals(lastDecision)) return;
			lastDecision = details;
			record("session=" + session + " decision " + details);
		}

		private static String geometry(View view) {
			if (view == null) return "none";
			int[] xy = new int[2];
			view.getLocationOnScreen(xy);
			Rect visible = new Rect();
			boolean shown = view.getGlobalVisibleRect(visible);
			return "xy=" + xy[0] + "," + xy[1] + " size=" + view.getWidth() + "x" + view.getHeight()
					+ " measured=" + view.getMeasuredWidth() + "x" + view.getMeasuredHeight()
					+ " scrollY=" + view.getScrollY() + " padding=" + view.getPaddingTop() + "," + view.getPaddingBottom()
					+ " visible=" + shown + ":" + visible.toShortString() + " layout=" + view.isLayoutRequested();
		}

		private void sample(String event, boolean force) {
			if (!recording || closed) return;
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
				String state = "ime=" + (insets != null ? insets.isVisible(WindowInsetsCompat.Type.ime()) : "unknown")
						+ " imeBottom=" + (insets != null ? insets.getInsets(WindowInsetsCompat.Type.ime()).bottom : -1)
						+ " decorIme=" + (decorInsets != null ? decorInsets.isVisible(WindowInsetsCompat.Type.ime()) : "unknown")
						+ " decorImeBottom=" + (decorInsets != null ? decorInsets.getInsets(WindowInsetsCompat.Type.ime()).bottom : -1)
						+ " bars=" + (insets != null ? insets.getInsets(WindowInsetsCompat.Type.systemBars()) : "unknown")
						+ " frame=" + frame.toShortString() + " softInput=" + activity.getWindow().getAttributes().softInputMode
						+ " flags=" + activity.getWindow().getAttributes().flags + " windowFocus=" + decor.hasWindowFocus()
						+ " orientation=" + root.getResources().getConfiguration().orientation
						+ " density=" + root.getResources().getDisplayMetrics().density
						+ " fontScale=" + root.getResources().getConfiguration().fontScale
						+ " textSizePx=" + editor.getTextSize() + " lineHeight=" + editor.getLineHeight()
						+ " decor{" + geometry(decor) + "} root{" + geometry(root) + "} viewport{" + geometry(scroll)
						+ "} editor{" + geometry(editor) + "} footer{" + geometry(footer) + "}"
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
			} catch (RuntimeException e) {
				record("session=" + session + " snapshot_failed type=" + e.getClass().getSimpleName());
			}
		}
	}
}
