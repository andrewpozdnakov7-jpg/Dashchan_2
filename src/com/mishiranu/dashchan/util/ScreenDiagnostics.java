package com.mishiranu.dashchan.util;

import android.app.Activity;
import android.app.Application;
import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Rect;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.DrawableWrapper;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.LayerDrawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewTreeObserver;
import android.view.Window;
import android.view.WindowManager;
import android.widget.ImageView;
import android.widget.TextView;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;
import com.mishiranu.dashchan.BuildConfig;
import com.mishiranu.dashchan.R;
import com.mishiranu.dashchan.content.Preferences;
import com.mishiranu.dashchan.ui.posting.PostingFormDiagnostics;
import com.mishiranu.dashchan.widget.InterfaceAppearance;
import com.mishiranu.dashchan.widget.ThemeEngine;
import com.mishiranu.dashchan.widget.ThemeChoiceRow;
import java.io.FileInputStream;
import java.lang.ref.WeakReference;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.WeakHashMap;

/**
 * Opt-in, read-only window geometry in the existing form report. No Window.Callback
 * replacement, hidden APIs, text/Intent inspection, screenshots or accessibility dumps.
 */
public final class ScreenDiagnostics {
	private static final Handler MAIN = new Handler(Looper.getMainLooper());
	private static final WeakHashMap<View, WeakReference<Probe>> WINDOWS = new WeakHashMap<>();
	private static boolean installed;
	private static int nextId;
	private ScreenDiagnostics() {}

	public static void install(Application app) {
		if (installed) return;
		installed = true;
		app.registerActivityLifecycleCallbacks(new Application.ActivityLifecycleCallbacks() {
			@Override public void onActivityCreated(Activity a, Bundle state) { observe(a.getWindow(), "activity"); }
			@Override public void onActivityResumed(Activity a) { observe(a.getWindow(), "activity"); }
			@Override public void onActivityDestroyed(Activity a) {
				View root = a.getWindow().peekDecorView();
				WeakReference<Probe> reference = root != null ? WINDOWS.remove(root) : null;
				Probe probe = reference != null ? reference.get() : null;
				if (probe != null) probe.close();
			}
			@Override public void onActivityStarted(Activity a) {}
			@Override public void onActivityPaused(Activity a) {}
			@Override public void onActivityStopped(Activity a) {}
			@Override public void onActivitySaveInstanceState(Activity a, Bundle state) {}
		});
	}
	public static void observe(Window window, String kind) {
		if (window == null || Looper.myLooper() != Looper.getMainLooper()) return;
		observeRoot(window.getDecorView(), kind, window);
	}
	/** ThemeEngine fallback also sees older native dialogs/overlays outside MotionDialogBuilder. */
	public static void observeRoot(View root, String kind) { observeRoot(root, kind, null); }
	public static String appCaller() {
		if (!PostingFormDiagnostics.isRecording()) return "inactive";
		for (StackTraceElement frame : Thread.currentThread().getStackTrace()) {
			String type = frame.getClassName();
			if (type.startsWith("com.mishiranu.dashchan.") && !type.equals(ScreenDiagnostics.class.getName())
					&& !type.equals("com.mishiranu.dashchan.widget.MotionDialogBuilder")) {
				return type + "#" + frame.getMethodName();
			}
		}
		return "unknown";
	}
	private static void observeRoot(View root, String kind, Window window) {
		if (root == null || Looper.myLooper() != Looper.getMainLooper()) return;
		WeakReference<Probe> reference = WINDOWS.get(root);
		Probe probe = reference != null ? reference.get() : null;
		if (probe == null) {
			probe = new Probe(root, kind, window);
			WINDOWS.put(root, new WeakReference<>(probe));
		} else if (window != null) probe.window = new WeakReference<>(window);
		if (PostingFormDiagnostics.isRecording()) probe.begin(PostingFormDiagnostics.captureId());
	}
	/** Developer-defined phase/details only, never strings obtained from UI or window attributes. */
	public static void note(Window window, String phase, String details) {
		if (!PostingFormDiagnostics.isRecording() || window == null || Looper.myLooper() != Looper.getMainLooper()) return;
		observe(window, "dialog");
		View root = window.peekDecorView();
		WeakReference<Probe> reference = root != null ? WINDOWS.get(root) : null;
		Probe probe = reference != null ? reference.get() : null;
		if (probe != null) {
			String value = "phase=" + phase + " " + details;
			if (!value.equals(probe.lastNote)) {
				probe.lastNote = value;
				PostingFormDiagnostics.recordScreen(probe.capture, "window=" + probe.id + " style " + value);
			}
		}
	}
	public static void recordingStarted(Context context, long capture) {
		Context app = context.getApplicationContext();
		MAIN.post(() -> {
			if (!PostingFormDiagnostics.isRecording() || capture != PostingFormDiagnostics.captureId()) return;
			for (WeakReference<Probe> ref : new ArrayList<>(WINDOWS.values())) {
				Probe probe = ref.get(); if (probe != null) probe.begin(capture);
			}
		});
		// Hash our own installed base APK off the UI thread; never output its local path.
		ConcurrentUtils.PARALLEL_EXECUTOR.execute(() -> {
			String result;
			try (FileInputStream input = new FileInputStream(app.getApplicationInfo().sourceDir)) {
				MessageDigest digest = MessageDigest.getInstance("SHA-256"); byte[] bytes = new byte[64 * 1024];
				for (int n; (n = input.read(bytes)) != -1;) digest.update(bytes, 0, n);
				StringBuilder hex = new StringBuilder();
				for (byte b : digest.digest()) hex.append(Character.forDigit((b >>> 4) & 15, 16)).append(Character.forDigit(b & 15, 16));
				result = "baseApkSha256=" + hex + " splitCount="
						+ (app.getApplicationInfo().splitSourceDirs != null ? app.getApplicationInfo().splitSourceDirs.length : 0);
			} catch (Exception e) { result = "baseApkHashUnavailable=" + e.getClass().getSimpleName(); }
			PostingFormDiagnostics.recordScreen(capture, result);
		});
	}
	public static void recordingStopped() {
		MAIN.post(() -> {
			// A new capture may have started before this cleanup runnable.
			if (PostingFormDiagnostics.isRecording()) return;
			for (WeakReference<Probe> ref : new ArrayList<>(WINDOWS.values())) {
				Probe probe = ref.get(); if (probe != null) probe.suspend();
			}
		});
	}
	private static String color(int color) { return Integer.toHexString(color); }
	/** Prefer the actual Window owner; DecorContext need not wrap the app's ThemeContext. */
	static Context themeContext(Window window, View root) {
		if (window != null && ThemeEngine.peekTheme(window.getContext()) != null) return window.getContext();
		return themeContext(root, new int[]{0}, 0);
	}
	private static Context themeContext(View view, int[] visited, int depth) {
		if (visited[0]++ >= 128 || depth > 20) return null;
		if (ThemeEngine.peekTheme(view.getContext()) != null) return view.getContext();
		if (view instanceof ViewGroup) {
			ViewGroup group = (ViewGroup) view;
			for (int i = 0; i < group.getChildCount() && visited[0] < 128; i++) {
				Context context = themeContext(group.getChildAt(i), visited, depth + 1);
				if (context != null) return context;
			}
		}
		return null;
	}
	private static void themeRows(View view, int depth, int[] budget, StringBuilder out) {
		if (depth > 24 || budget[0]++ >= 512 || budget[1] >= 16) return;
		Object row = view.getTag(R.id.screen_theme_choice);
		if (row instanceof ThemeChoiceRow) {
			out.append("\n themeRow=").append(budget[1]++).append(' ');
			try { out.append(((ThemeChoiceRow) row).diagnosticState()); }
			catch (RuntimeException e) { out.append("stateUnavailable=").append(e.getClass().getSimpleName()); }
		}
		if (view instanceof ViewGroup) {
			ViewGroup group = (ViewGroup) view;
			for (int i = 0; i < group.getChildCount() && budget[0] < 512 && budget[1] < 16; i++) themeRows(group.getChildAt(i), depth + 1, budget, out);
		}
	}
	private static String tint(ColorStateList colors) {
		return colors == null ? "none" : color(colors.getDefaultColor()) + "/disabled="
				+ color(colors.getColorForState(new int[]{-android.R.attr.state_enabled}, colors.getDefaultColor()));
	}
	private static String background(Drawable drawable, int depth) {
		if (drawable == null) return "none";
		String result = drawable.getClass().getSimpleName() + "(alpha=" + drawable.getAlpha();
		Rect padding = new Rect(); drawable.getPadding(padding);
		result += " paddingPx=" + padding.toShortString();
		if (drawable instanceof ColorDrawable) result += " color=" + color(((ColorDrawable) drawable).getColor());
		if (drawable instanceof GradientDrawable) {
			GradientDrawable shape = (GradientDrawable) drawable;
			result += " fill=" + tint(shape.getColor()) + " radiusPx=" + shape.getCornerRadius()
					+ " radiiPx=" + Arrays.toString(shape.getCornerRadii());
		}
		if (depth < 3 && drawable instanceof LayerDrawable) {
			LayerDrawable layers = (LayerDrawable) drawable;
			for (int i = 0; i < Math.min(3, layers.getNumberOfLayers()); i++) result += " layer" + i + "=" + background(layers.getDrawable(i), depth + 1);
		} else if (depth < 3 && drawable instanceof DrawableWrapper) result += " child=" + background(((DrawableWrapper) drawable).getDrawable(), depth + 1);
		else if (depth < 3 && drawable.getCurrent() != drawable) result += " current=" + background(drawable.getCurrent(), depth + 1);
		return result + ")";
	}
	private static final class Probe implements View.OnAttachStateChangeListener,
			ViewTreeObserver.OnGlobalLayoutListener, ViewTreeObserver.OnWindowFocusChangeListener {
		final View root;
		final String kind;
		final int id = ++nextId;
		WeakReference<Window> window;
		long capture = -1, lastSample;
		String lastSnapshot, lastNote;
		ViewTreeObserver tree;
		boolean active;
		final Runnable poll = () -> sample("sample", false);
		Probe(View root, String kind, Window window) {
			this.root = root; this.kind = kind; this.window = new WeakReference<>(window);
			root.addOnAttachStateChangeListener(this);
		}
		void begin(long capture) {
			if (this.capture != capture) {
				this.capture = capture; lastSnapshot = lastNote = null; lastSample = 0;
			}
			if (!root.isAttachedToWindow() || active) return;
			active = true; tree = root.getViewTreeObserver();
			tree.addOnGlobalLayoutListener(this); tree.addOnWindowFocusChangeListener(this);
			sample("attach", true);
		}
		void suspend() {
			active = false; MAIN.removeCallbacks(poll);
			if (tree != null && tree.isAlive()) {
				tree.removeOnGlobalLayoutListener(this); tree.removeOnWindowFocusChangeListener(this);
			}
			tree = null;
		}
		void close() { sample("destroy", true); suspend(); root.removeOnAttachStateChangeListener(this); }
		@Override public void onViewAttachedToWindow(View v) {
			if (PostingFormDiagnostics.isRecording()) begin(PostingFormDiagnostics.captureId());
		}
		@Override public void onViewDetachedFromWindow(View v) { sample("detach", true); suspend(); }
		@Override public void onGlobalLayout() { sample("layout", false); }
		@Override public void onWindowFocusChanged(boolean focused) { sample("focus", true); }
		void sample(String event, boolean force) {
			if (!active || !PostingFormDiagnostics.isRecording()) { suspend(); return; }
			if (capture != PostingFormDiagnostics.captureId()) { begin(PostingFormDiagnostics.captureId()); }
			long now = SystemClock.elapsedRealtime();
			MAIN.removeCallbacks(poll); MAIN.postDelayed(poll, 500);
			if (!force && now - lastSample < 250) return;
			lastSample = now;
			String stage = "geometry";
			try {
				Window w = window.get(); Context context = root.getContext();
				android.content.res.Configuration config = context.getResources().getConfiguration();
				float density = context.getResources().getDisplayMetrics().density;
				Rect frame = new Rect(); root.getWindowVisibleDisplayFrame(frame);
				WindowInsetsCompat insets = ViewCompat.getRootWindowInsets(root);
				Context themed = themeContext(w, root);
				ThemeEngine.Theme theme = themed != null ? ThemeEngine.peekTheme(themed) : null;
				StringBuilder out = new StringBuilder("kind=").append(kind)
						.append(" context=").append(context.getClass().getName())
						.append(" density=").append(density).append(" fontScale=").append(config.fontScale)
						.append(" screenDp=").append(config.screenWidthDp).append(",").append(config.screenHeightDp)
						.append(" orientation=").append(config.orientation).append(" framePx=").append(frame.toShortString())
						.append(" materialSetting=").append(Preferences.isNewInterfaceMotionEnabled())
						.append(" materialEffective=").append(InterfaceAppearance.isEnabled(themed != null ? themed : context))
						.append(" materialBuild=").append(BuildConfig.ENABLE_EXPERIMENTAL_INTERFACE_MOTION)
						.append(" standardTag=").append(root.getTag(R.id.standard_dialog_appearance) != null)
						.append(" roundedSetting=").append(Preferences.isRoundedDialogs())
						.append(" animators=").append(android.animation.ValueAnimator.areAnimatorsEnabled())
						.append(" themeContext=").append(themed != null ? themed.getClass().getName() : "unavailable")
						.append(" windowFocus=").append(root.hasWindowFocus());
				if (theme != null) out.append(" themeBase=").append(theme.base).append(" windowColor=").append(color(theme.window))
						.append(" accent=").append(color(theme.accent)).append(" primary=").append(color(theme.primary));
				if (w != null) {
					WindowManager.LayoutParams p = w.getAttributes();
					out.append(" lpPx=").append(p.width).append("x").append(p.height)
							.append(" flags=").append(p.flags).append(" softInput=").append(p.softInputMode)
							.append(" type=").append(p.type).append(" gravity=").append(p.gravity)
							.append(" dim=").append(p.dimAmount);
					if (w.getInsetsController() != null) out.append(" barsAppearance=").append(w.getInsetsController().getSystemBarsAppearance());
				}
				if (insets != null) out.append(" imeVisible=").append(insets.isVisible(WindowInsetsCompat.Type.ime()))
						.append(" ime=").append(insets.getInsets(WindowInsetsCompat.Type.ime()))
						.append(" bars=").append(insets.getInsets(WindowInsetsCompat.Type.systemBars()))
						.append(" cutout=").append(insets.getInsets(WindowInsetsCompat.Type.displayCutout()));
				int[] budget = {0};
				stage = "nodes";
				View toolbar = root.findViewById(R.id.toolbar);
				if (toolbar != null) node(toolbar, "toolbar", 0, budget, density, out);
				node(root, "root", 0, budget, density, out);
				stage = "theme_rows";
				themeRows(root, 0, new int[]{0, 0}, out);
				String snapshot = out.toString();
				if (force || !snapshot.equals(lastSnapshot)) {
					lastSnapshot = snapshot;
					PostingFormDiagnostics.recordScreen(capture, "window=" + id + " event=" + event + " " + snapshot);
				}
			} catch (RuntimeException e) {
				String failure = "stage=" + stage + " snapshotUnavailable=" + e.getClass().getSimpleName();
				if (force || !failure.equals(lastSnapshot)) {
					lastSnapshot = failure;
					PostingFormDiagnostics.recordScreen(capture, "window=" + id + " " + failure);
				}
			}
		}
		private void node(View view, String path, int depth, int[] count, float density, StringBuilder out) {
			if (count[0]++ >= 48) return;
			try { nodeProperties(view, path, density, out); }
			catch (RuntimeException e) { out.append("\n node=").append(path).append(" propertiesUnavailable=").append(e.getClass().getSimpleName()); }
			if (depth < 8 && view instanceof ViewGroup) {
				ViewGroup group = (ViewGroup) view;
				out.append(" children=").append(group.getChildCount()).append(" clipChildren=").append(group.getClipChildren());
				for (int i = 0; i < group.getChildCount() && count[0] < 48; i++) {
					View child = group.getChildAt(i);
					if (child.getVisibility() != View.GONE) node(child, path + "/" + i, depth + 1, count, density, out);
				}
			}
		}
		private void nodeProperties(View view, String path, float density, StringBuilder out) {
			int[] xy = new int[2]; view.getLocationOnScreen(xy);
			out.append("\n node=").append(path).append(" type=").append(PostingFormDiagnostics.viewLabel(view))
					.append(" sizePx=").append(view.getWidth()).append("x").append(view.getHeight())
					.append(" sizeDp=").append(view.getWidth() / density).append("x").append(view.getHeight() / density)
					.append(" measuredPx=").append(view.getMeasuredWidth()).append("x").append(view.getMeasuredHeight())
					.append(" xyPx=").append(xy[0]).append(",").append(xy[1])
					.append(" paddingPx=").append(view.getPaddingLeft()).append(",").append(view.getPaddingTop()).append(",")
					.append(view.getPaddingRight()).append(",").append(view.getPaddingBottom())
					.append(" minPx=").append(view.getMinimumWidth()).append(",").append(view.getMinimumHeight())
					.append(" visible=").append(view.getVisibility()).append(" alpha=").append(view.getAlpha())
					.append(" enabled=").append(view.isEnabled()).append(" selected=").append(view.isSelected())
					.append(" shown=").append(view.isShown()).append(" clipOutline=").append(view.getClipToOutline())
					.append(" background=").append(background(view.getBackground(), 0))
					.append(" tint=").append(tint(view.getBackgroundTintList()));
			ViewGroup.LayoutParams params = view.getLayoutParams();
			if (params != null) out.append(" lpPx=").append(params.width).append("x").append(params.height);
			if (params instanceof ViewGroup.MarginLayoutParams) {
				ViewGroup.MarginLayoutParams m = (ViewGroup.MarginLayoutParams) params;
				out.append(" marginsPx=").append(m.leftMargin).append(",").append(m.topMargin)
						.append(",").append(m.rightMargin).append(",").append(m.bottomMargin);
			}
			if (view instanceof TextView) {
				TextView text = (TextView) view;
				out.append(" textColor=").append(tint(text.getTextColors())).append(" textSizePx=").append(text.getTextSize())
						.append(" lineHeightPx=").append(text.getLineHeight())
						.append(" textMinMaxPx=").append(text.getMinHeight()).append(",").append(text.getMaxHeight())
						.append(" minMaxLines=").append(text.getMinLines()).append(",").append(text.getMaxLines())
						.append(" gravity=").append(text.getGravity()).append(" fontPadding=").append(text.getIncludeFontPadding())
						.append(" lines=").append(text.getLineCount());
				android.graphics.Paint.FontMetricsInt metrics = text.getPaint().getFontMetricsInt();
				out.append(" fontMetricsPx=").append(metrics.top).append(",").append(metrics.ascent).append(",")
						.append(metrics.descent).append(",").append(metrics.bottom);
			}
			if (view instanceof android.widget.ListView) out.append(" dividerHeightPx=").append(((android.widget.ListView) view).getDividerHeight());
			if (view instanceof ImageView) out.append(" iconTint=").append(tint(((ImageView) view).getImageTintList()));
		}
	}
}
