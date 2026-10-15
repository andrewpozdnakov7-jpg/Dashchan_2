package com.mishiranu.dashchan.content.security;

import android.app.Activity;
import android.app.Application;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.Color;
import android.os.Bundle;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import java.io.IOException;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.Set;

/** One gate for all activities, including editor, external intents and the private-browser process. */
public final class AppSecurity implements Application.ActivityLifecycleCallbacks {
	private static AppSecurity instance;
	private static final java.util.List<java.lang.ref.WeakReference<android.view.Window>> WINDOWS = new java.util.ArrayList<>();
	private static final String WINDOW_SHIELD = "slooop.security.window.shield";
	private static final java.util.List<Popup> POPUPS = new java.util.ArrayList<>();
	private static final class Popup {
		final java.lang.ref.WeakReference<View> view;
		final java.lang.ref.WeakReference<WindowManager> manager;
		final boolean originallySecure;
		Integer visibility;
		Popup(View view, WindowManager manager, boolean originallySecure) {
			this.view = new java.lang.ref.WeakReference<>(view);
			this.manager = new java.lang.ref.WeakReference<>(manager);
			this.originallySecure = originallySecure;
		}
	}
	static void registerPopup(View view, WindowManager manager, boolean originallySecure, boolean cover) {
		Popup popup = new Popup(view, manager, originallySecure);
		if (cover) { popup.visibility = view.getVisibility(); view.setVisibility(View.INVISIBLE); }
		synchronized (POPUPS) { POPUPS.add(popup); }
	}
	static void unregisterPopup(View view) {
		synchronized (POPUPS) { POPUPS.removeIf(p -> p.view.get() == null || p.view.get() == view); }
	}
	private static void refreshPopups(boolean protect, boolean cover) {
		synchronized (POPUPS) {
			java.util.Iterator<Popup> iterator = POPUPS.iterator();
			while (iterator.hasNext()) {
				Popup popup = iterator.next(); View view = popup.view.get(); WindowManager manager = popup.manager.get();
				if (view == null || manager == null) { iterator.remove(); continue; }
				if (!view.isAttachedToWindow()) continue;
				if (view.getLayoutParams() instanceof WindowManager.LayoutParams) {
					WindowManager.LayoutParams params = (WindowManager.LayoutParams) view.getLayoutParams();
					int flags = params.flags;
					if (protect || popup.originallySecure) params.flags |= WindowManager.LayoutParams.FLAG_SECURE;
					else params.flags &= ~WindowManager.LayoutParams.FLAG_SECURE;
					if (flags != params.flags) manager.updateViewLayout(view, params);
				}
				if (protect && cover && popup.visibility == null) { popup.visibility = view.getVisibility(); view.setVisibility(View.INVISIBLE); }
				else if ((!protect || !cover) && popup.visibility != null) { view.setVisibility(popup.visibility); popup.visibility = null; }
			}
		}
	}
	private static final java.util.Map<View, Integer> ACCESSIBILITY = new java.util.WeakHashMap<>();
	private static void concealAccessibility(View view) {
		if (!ACCESSIBILITY.containsKey(view)) ACCESSIBILITY.put(view, view.getImportantForAccessibility());
		view.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS);
	}
	private static void restoreAccessibility(View view) {
		Integer original = ACCESSIBILITY.remove(view);
		if (original != null) view.setImportantForAccessibility(original);
	}
	public static void protectDialog(android.app.Dialog dialog) {
		android.view.Window window = dialog.getWindow();
		registerWindow(window);
	}
	private static void registerWindow(Window window) {
		if (window == null) return;
		synchronized (WINDOWS) {
			boolean found = false;
			for (java.lang.ref.WeakReference<android.view.Window> reference : WINDOWS) if (reference.get() == window) found = true;
			if (!found) WINDOWS.add(new java.lang.ref.WeakReference<>(window));
		}
		boolean protect = isProtected(window.getContext());
		if (protect) window.addFlags(WindowManager.LayoutParams.FLAG_SECURE);
		Window.Callback callback = window.getCallback();
		if (callback != null && !(callback instanceof SecurityWindowCallback)) window.setCallback(new SecurityWindowCallback(window, callback, protect));
	}
	static void notifyChanged(Context context) {
		context.sendBroadcast(new Intent(context.getPackageName() + ".security.STATE_CHANGED").setPackage(context.getPackageName()));
	}
	static void dialogWindowAttached(android.view.Window window) {
		boolean locked = SecurityStore.locked(window.getContext());
		refreshPrivateWindows(window.getContext(), locked);
		if (!locked || instance == null || instance.openingLock) return;
		Context context = window.getContext();
		while (context instanceof android.content.ContextWrapper && !(context instanceof Activity)) {
			Context base = ((android.content.ContextWrapper) context).getBaseContext();
			if (base == context) return; context = base;
		}
		if (context instanceof Activity && instance.resumed.contains((Activity) context)
				&& !exempt((Activity) context) && !((Activity) context).isFinishing()) {
			instance.openingLock = true;
			context.startActivity(new Intent(context, AppLockActivity.class));
		}
	}
	private static void refreshPrivateWindows(Context context, boolean cover) {
		boolean protect = isProtected(context);
		refreshPopups(protect, cover);
		synchronized (WINDOWS) {
			java.util.Iterator<java.lang.ref.WeakReference<android.view.Window>> iterator = WINDOWS.iterator();
			while (iterator.hasNext()) {
				android.view.Window window = iterator.next().get();
				if (window == null) { iterator.remove(); continue; }
				boolean secure = (window.getAttributes().flags & WindowManager.LayoutParams.FLAG_SECURE) != 0;
				if (protect != secure) {
					if (protect) window.addFlags(WindowManager.LayoutParams.FLAG_SECURE);
					else window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE);
				}
				if (window.getCallback() instanceof SecurityWindowCallback) ((SecurityWindowCallback) window.getCallback()).setProtected(protect);
				ViewGroup decor = (ViewGroup) window.peekDecorView();
				if (decor == null) continue;
				View shield = decor.findViewWithTag(WINDOW_SHIELD);
				if (protect && cover && shield == null) {
					concealAccessibility(decor);
					shield = new View(window.getContext()); shield.setTag(WINDOW_SHIELD);
					shield.setBackgroundColor(Color.BLACK); shield.setClickable(true); shield.setFocusableInTouchMode(true);
					shield.setElevation(10000 * context.getResources().getDisplayMetrics().density);
					decor.addView(shield, new ViewGroup.LayoutParams(-1, -1)); shield.requestFocus();
				} else if ((!protect || !cover) && shield != null) { decor.removeView(shield); restoreAccessibility(decor); }
			}
		}
	}
	private final Application application;
	private final Map<Activity, View> shields = new IdentityHashMap<>();
	private final Set<Activity> started = Collections.newSetFromMap(new IdentityHashMap<>());
	private final Set<Activity> resumed = Collections.newSetFromMap(new IdentityHashMap<>());
	private final android.os.Handler handler = new android.os.Handler(android.os.Looper.getMainLooper());
	private final Runnable checkForeground = this::checkForeground;
	private void checkForeground() {
		boolean active = false;
		for (Activity activity : resumed) if (!activity.isFinishing() && !activity.isInPictureInPictureMode()) active = true;
		try { SecurityStore.foreground(application, active); } catch (IOException ignored) {}
		if (SecurityStore.locked(application)) protectAll();
	}
	private boolean openingLock;
	private AppSecurity(Application application) { this.application = application; }

	public static void install(Application application, boolean mainProcess) {
		instance = new AppSecurity(application);
		try { SecurityStore.processStarted(application, mainProcess); }
		catch (IOException ignored) { /* All gates fail closed on storage failure. */ }
		application.registerActivityLifecycleCallbacks(instance);
		IntentFilter filter = new IntentFilter(Intent.ACTION_SCREEN_OFF);
		application.registerReceiver(new BroadcastReceiver() {
			@Override public void onReceive(Context context, Intent intent) {
				try { SecurityStore.lock(context); } catch (IOException ignored) {}
				instance.protectAll();
			}
		}, filter);
		androidx.core.content.ContextCompat.registerReceiver(application, new BroadcastReceiver() {
			@Override public void onReceive(Context context, Intent intent) { refresh(); }
		}, new IntentFilter(application.getPackageName() + ".security.STATE_CHANGED"),
				androidx.core.content.ContextCompat.RECEIVER_NOT_EXPORTED);
	}

	private boolean enabled() {
		return isProtected(application);
	}

	public static boolean isProtected(Context context) {
		try { return SecurityStore.enabled(context); }
		catch (IOException e) { return true; }
	}

	public static void redactNotification(Context context, androidx.core.app.NotificationCompat.Builder builder) {
		if (isProtected(context)) {
			builder.getExtras().clear();
			builder.setStyle(null).setLargeIcon((android.graphics.Bitmap) null).setPublicVersion(null)
					.setCustomContentView(null).setCustomBigContentView(null).setCustomHeadsUpContentView(null)
					.setContentTitle("Slooop").setContentText(context.getString(com.mishiranu.dashchan.R.string.security_notification_hidden))
					.setTicker(null).setSubText(null).setContentInfo(null).setVisibility(androidx.core.app.NotificationCompat.VISIBILITY_SECRET);
		}
	}

	public static android.app.Notification redactNotification(Context context, android.app.Notification notification) {
		if (!isProtected(context)) return notification;
		androidx.core.app.NotificationCompat.Builder builder = new androidx.core.app.NotificationCompat.Builder(context, notification);
		redactNotification(context, builder);
		return builder.build();
	}

	private static boolean exempt(Activity activity) {
		return activity instanceof AppLockActivity || activity instanceof PrivacyResetActivity;
	}

	private void shield(Activity activity) {
		if (exempt(activity) || shields.containsKey(activity)) return;
		ViewGroup decor = (ViewGroup) activity.getWindow().getDecorView();
		concealAccessibility(decor);
		View view = new View(activity);
		view.setBackgroundColor(Color.BLACK);
		view.setClickable(true); view.setFocusable(true); view.setFocusableInTouchMode(true);
		view.setElevation(10000 * activity.getResources().getDisplayMetrics().density);
		decor.addView(view, new ViewGroup.LayoutParams(-1, -1));
		shields.put(activity, view);
		view.requestFocus();
	}

	private void unshield(Activity activity) {
		View view = shields.remove(activity);
		if (view != null && view.getParent() instanceof ViewGroup) {
			ViewGroup parent = (ViewGroup) view.getParent(); parent.removeView(view); restoreAccessibility(parent);
		}
	}

	private void protectAll() {
		if (!enabled()) return;
		refreshPrivateWindows(application, true);
		application.stopService(new Intent(application, com.mishiranu.dashchan.content.service.AudioPlayerService.class));
		for (Activity activity : started.toArray(new Activity[0])) {
			shield(activity);
			if (activity.isInPictureInPictureMode()) activity.finishAndRemoveTask();
		}
	}

	public static void refresh() {
		if (instance == null) return;
		boolean locked = SecurityStore.locked(instance.application);
		refreshPrivateWindows(instance.application, locked);
		if (locked) instance.protectAll();
		else for (Activity activity : instance.started) instance.unshield(activity);
		for (Activity activity : instance.started) {
			if (instance.enabled() || exempt(activity)) activity.getWindow().addFlags(WindowManager.LayoutParams.FLAG_SECURE);
			else activity.getWindow().clearFlags(WindowManager.LayoutParams.FLAG_SECURE);
		}
		if (instance.enabled()) {
			android.app.NotificationManager notifications = instance.application.getSystemService(android.app.NotificationManager.class);
			if (notifications != null) for (android.service.notification.StatusBarNotification active : notifications.getActiveNotifications()) {
				notifications.notify(active.getTag(), active.getId(), redactNotification(instance.application, active.getNotification()));
			}
		}
	}

	/** Side-effect-only entry activities must defer before opening another app or importing content. */
	public static boolean deferIfLocked(Activity activity) {
		if (!SecurityStore.locked(activity)) return false;
		Intent target = new Intent(activity.getIntent()).setComponent(activity.getComponentName());
		Intent gate = new Intent(activity, AppLockActivity.class).putExtra("resume", target);
		gate.setClipData(target.getClipData());
		gate.addFlags(target.getFlags() & (Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION));
		activity.startActivity(gate);
		activity.finish();
		return true;
	}

	private void consumeEmptyProfileToken(Activity activity) {
		if (activity instanceof com.mishiranu.dashchan.ui.MainActivity) {
			String token = activity.getIntent().getStringExtra("empty_profile_token");
			activity.getIntent().removeExtra("empty_profile_token");
			if (token != null) try {
				long lease = SecuritySessionProvider.generation(activity);
				SecurityStore.consumeEmptyProfileToken(activity, token, lease);
			} catch (IOException ignored) {}
		}
	}
	@Override public void onActivityPreCreated(Activity activity, Bundle savedInstanceState) {
		if (enabled()) activity.getWindow().addFlags(WindowManager.LayoutParams.FLAG_SECURE);
	}

	@Override public void onActivityPostCreated(Activity activity, Bundle state) {
		if (!exempt(activity)) registerWindow(activity.getWindow());
		if (SecurityStore.locked(activity)) shield(activity);
	}

	@Override public void onActivityStarted(Activity activity) {
		started.add(activity);
		if (enabled() || exempt(activity)) activity.getWindow().addFlags(WindowManager.LayoutParams.FLAG_SECURE);
		else activity.getWindow().clearFlags(WindowManager.LayoutParams.FLAG_SECURE);
		try { SecurityStore.foreground(activity, true); } catch (IOException ignored) {}
		consumeEmptyProfileToken(activity);
	}

	@Override public void onActivityPreResumed(Activity activity) {
		if (exempt(activity)) { openingLock = false; return; }
		if (SecurityStore.locked(activity)) {
			shield(activity);
			if (!openingLock && !activity.isFinishing()) {
				openingLock = true;
				activity.startActivity(new Intent(activity, AppLockActivity.class));
			}
		} else unshield(activity);
	}

	@Override public void onActivityPrePaused(Activity activity) {
		if (enabled()) shield(activity); // No sensitive recents snapshot during the background transition.
	}

	@Override public void onActivityStopped(Activity activity) {
		started.remove(activity);
		if (activity.isChangingConfigurations()) return;
		boolean foreground = false;
		for (Activity other : started) if (!other.isFinishing() && !other.isInPictureInPictureMode()) foreground = true;
		try { SecurityStore.foreground(activity, foreground); } catch (IOException ignored) {}
		if (SecurityStore.locked(activity)) protectAll();
	}

	@Override public void onActivityDestroyed(Activity activity) { started.remove(activity); resumed.remove(activity); shields.remove(activity); }
	@Override public void onActivityCreated(Activity activity, Bundle state) {}
	@Override public void onActivityResumed(Activity activity) {
		resumed.add(activity);
		handler.removeCallbacks(checkForeground);
		try { SecurityStore.foreground(activity, true); } catch (IOException ignored) {}
	}
	@Override public void onActivityPaused(Activity activity) {
		resumed.remove(activity);
		handler.removeCallbacks(checkForeground);
		if (!activity.isChangingConfigurations()) handler.postDelayed(checkForeground, 500);
	}
	@Override public void onActivitySaveInstanceState(Activity activity, Bundle state) {}
}
