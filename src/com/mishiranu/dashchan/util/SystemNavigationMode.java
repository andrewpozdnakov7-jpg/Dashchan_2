package com.mishiranu.dashchan.util;

import android.os.Build;
import android.view.View;
import android.view.ViewTreeObserver;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;
import com.mishiranu.dashchan.BuildConfig;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.Set;

/** Passive public-insets observation. Does not replace any window's inset listener. */
public final class SystemNavigationMode {
	public enum Mode { UNKNOWN, GESTURES, BUTTONS }
	private static volatile Mode mode = Mode.UNKNOWN;
	private static final Set<Runnable> listeners = new LinkedHashSet<>();
	private SystemNavigationMode() {}

	public static Mode get() { return mode; }
	public static void addListener(Runnable listener) { listeners.add(listener); }
	public static void removeListener(Runnable listener) { listeners.remove(listener); }

	static Mode classify(Insets navigation, Insets tappable, Insets gestures) {
		// Side Back regions also identify gesture navigation with a visible tablet taskbar.
		if (gestures.left > 0 || gestures.right > 0) return Mode.GESTURES;
		boolean nav = navigation.left > 0 || navigation.right > 0 || navigation.bottom > 0;
		if (!nav) return Mode.UNKNOWN; // Unattached/immersive windows are not proof of buttons.
		boolean buttons = navigation.left > 0 && tappable.left > 0
				|| navigation.right > 0 && tappable.right > 0 || navigation.bottom > 0 && tappable.bottom > 0;
		return buttons ? Mode.BUTTONS : Mode.GESTURES;
	}
	public static boolean isPredictiveBackEnabled() {
		return supportsPredictiveBack(Build.VERSION.SDK_INT, BuildConfig.ENABLE_PREDICTIVE_BACK, mode);
	}

	static boolean supportsPredictiveBack(int api, boolean available, Mode detected) {
		return api >= Build.VERSION_CODES.TIRAMISU && available && detected == Mode.GESTURES;
	}

	public static void refresh(View root) {
		if (Build.VERSION.SDK_INT < 29 || root == null || !root.isAttachedToWindow()) return;
		WindowInsetsCompat insets = ViewCompat.getRootWindowInsets(root);
		if (insets == null) return;
		int navType = WindowInsetsCompat.Type.navigationBars(), tapType = WindowInsetsCompat.Type.tappableElement();
		Insets nav = Build.VERSION.SDK_INT >= 30 ? insets.getInsetsIgnoringVisibility(navType) : insets.getInsets(navType);
		Insets tap = Build.VERSION.SDK_INT >= 30 ? insets.getInsetsIgnoringVisibility(tapType) : insets.getInsets(tapType);
		Mode detected = classify(nav, tap, insets.getInsets(WindowInsetsCompat.Type.systemGestures()));
		if (detected == Mode.UNKNOWN) return; // Preserve the last known mode through transient zero insets.
		updateMode(detected);
	}

	static void updateMode(Mode detected) {
		if (mode == detected) return;
		mode = detected;
		for (Runnable listener : new ArrayList<>(listeners)) listener.run();
	}

	public static final class Observer implements ViewTreeObserver.OnGlobalLayoutListener, AutoCloseable {
		private final View root;
		private final Runnable changed;
		private final ViewTreeObserver tree;
		private Mode previous = Mode.UNKNOWN;
		private boolean closed;
		public Observer(View root, Runnable changed) {
			this.root = root; this.changed = changed; tree = root.getViewTreeObserver();
			tree.addOnGlobalLayoutListener(this);
		}
		@Override public void onGlobalLayout() { refresh(); }
		public void refresh() {
			if (closed) return;
			SystemNavigationMode.refresh(root);
			Mode current = get();
			if (current != previous) { previous = current; changed.run(); }
		}
		@Override public void close() {
			if (closed) return;
			closed = true;
			ViewTreeObserver observer = tree.isAlive() ? tree : root.getViewTreeObserver();
			if (observer.isAlive()) observer.removeOnGlobalLayoutListener(this);
		}
	}
}
