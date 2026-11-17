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
	private static final NotificationContentCache<android.app.Notification> NOTIFICATION_CONTENTS = new NotificationContentCache<>();
	private static final String NOTIFICATION_HIDDEN = "slooop.notification.content_hidden";
	private static final java.util.List<java.lang.ref.WeakReference<android.view.Window>> WINDOWS = new java.util.ArrayList<>();
	private static final String WINDOW_SHIELD = "slooop.security.window.shield";
	private static final java.util.List<Popup> POPUPS = new java.util.ArrayList<>();
	private static final class Popup {
		final java.lang.ref.WeakReference<View> view;
		final java.lang.ref.WeakReference<WindowManager> manager;
		final java.lang.ref.WeakReference<Context> context;
		final boolean originallySecure;
		final boolean authenticationWindow;
		Integer visibility;
		Popup(Context context, View view, WindowManager manager, boolean originallySecure, boolean authenticationWindow) {
			this.view = new java.lang.ref.WeakReference<>(view);
			this.manager = new java.lang.ref.WeakReference<>(manager);
			this.context = new java.lang.ref.WeakReference<>(context);
			this.originallySecure = originallySecure;
			this.authenticationWindow = authenticationWindow;
		}
	}
	static void registerPopup(Context context, View view, WindowManager manager, boolean originallySecure, boolean cover) {
		Popup popup = new Popup(context, view, manager, originallySecure, authenticationContext(context));
		if (cover) { popup.visibility = view.getVisibility(); view.setVisibility(View.INVISIBLE); }
		synchronized (POPUPS) { POPUPS.add(popup); }
	}
	static void unregisterPopup(View view) {
		synchronized (POPUPS) { POPUPS.removeIf(p -> p.view.get() == null || p.view.get() == view); }
	}
	static boolean popupAlwaysSecure(View view) {
		synchronized (POPUPS) {
			for (Popup popup : POPUPS) if (popup.view.get() == view) {
				return popup.originallySecure || popup.authenticationWindow;
			}
		}
		return false;
	}
	private static void refreshPopups(boolean protect, boolean cover, boolean captureBlocked) {
		synchronized (POPUPS) {
			java.util.Iterator<Popup> iterator = POPUPS.iterator();
			while (iterator.hasNext()) {
				Popup popup = iterator.next(); View view = popup.view.get(); WindowManager manager = popup.manager.get();
				if (view == null || manager == null) { iterator.remove(); continue; }
				if (!view.isAttachedToWindow()) continue;
				if (view.getLayoutParams() instanceof WindowManager.LayoutParams) {
					WindowManager.LayoutParams params = (WindowManager.LayoutParams) view.getLayoutParams();
					int flags = params.flags;
					Context owner = popup.context.get();
					boolean designCapture = owner != null && SecurityStore.designCaptureAllowed(owner);
					if (!designCapture && (captureBlocked || popup.originallySecure || popup.authenticationWindow
							|| owner == null || shouldSecureWindow(owner))) params.flags |= WindowManager.LayoutParams.FLAG_SECURE;
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
		if (!com.mishiranu.dashchan.BuildConfig.ENABLE_APP_SECURITY_SETTINGS) return;
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
		applyCaptureFlag(window, false);
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
		boolean hidePip = protect && SecurityStore.hidePipVideo(context);
		boolean locked = protect && SecurityStore.locked(context);
		refreshPopups(protect, cover, protect && cover || shouldSecureWindow(context));
		synchronized (WINDOWS) {
			java.util.Iterator<java.lang.ref.WeakReference<android.view.Window>> iterator = WINDOWS.iterator();
			while (iterator.hasNext()) {
				android.view.Window window = iterator.next().get();
				if (window == null) { iterator.remove(); continue; }
				Activity owner = windowActivity(window.getContext());
				// One paused window must not cover another resumed window (e.g. the PiP host).
				boolean backgroundCover = owner != null && instance != null ? !instance.resumed.contains(owner) : cover;
				boolean windowCover = PipProtectionPolicy.cover(protect, locked, visiblePip(owner), hidePip, backgroundCover);
				applyCaptureFlag(window, windowCover);
				if (window.getCallback() instanceof SecurityWindowCallback) ((SecurityWindowCallback) window.getCallback()).setProtected(protect);
				ViewGroup decor = (ViewGroup) window.peekDecorView();
				if (decor == null) continue;
				View shield = decor.findViewWithTag(WINDOW_SHIELD);
				if (windowCover && shield == null) {
					concealAccessibility(decor);
					shield = new View(window.getContext()); shield.setTag(WINDOW_SHIELD);
					shield.setBackgroundColor(Color.BLACK); shield.setClickable(true); shield.setFocusableInTouchMode(true);
					shield.setElevation(10000 * context.getResources().getDisplayMetrics().density);
					decor.addView(shield, new ViewGroup.LayoutParams(-1, -1)); shield.requestFocus();
				} else if (!windowCover && shield != null) { decor.removeView(shield); restoreAccessibility(decor); }
			}
		}
	}
	private final Application application;
	private final Map<Activity, View> shields = new IdentityHashMap<>();
	private final Set<Activity> started = Collections.newSetFromMap(new IdentityHashMap<>());
	private final Set<Activity> resumed = Collections.newSetFromMap(new IdentityHashMap<>());
	private Boolean notificationForeground;
	private boolean notificationPrivacyEnabled() {
		if (!enabled()) return false;
		try { return SecurityStore.hideReplyTextInBackground(application); }
		catch (IOException e) { return true; }
	}
	private void updateNotificationForeground() {
		android.os.PowerManager power = application.getSystemService(android.os.PowerManager.class);
		boolean active = false;
		if (power != null && power.isInteractive()) for (Activity activity : resumed) {
			if (!exempt(activity) && !activity.isFinishing() && !activity.isInPictureInPictureMode()) active = true;
		}
		if (notificationForeground == null || notificationForeground != active) {
			if (SecuritySessionProvider.setNotificationForeground(application, active)) notificationForeground = active;
			if (notificationPrivacyEnabled()) refreshNotificationContents(application);
		}
	}
	private final android.os.Handler handler = new android.os.Handler(android.os.Looper.getMainLooper());
	private final Runnable checkForeground = this::checkForeground;
	private void checkForeground() {
		boolean active = false;
		for (Activity activity : resumed) if (!activity.isFinishing() && !activity.isInPictureInPictureMode()) active = true;
		// Android pauses a visible PiP Activity. It is not a hidden/background client.
		for (Activity activity : started) if (visiblePip(activity) && !activity.isFinishing()) active = true;
		try { SecurityStore.foreground(application, active); } catch (IOException ignored) {}
		if (SecurityStore.locked(application)) protectAll();
	}
	private boolean openingLock;
	private AppSecurity(Application application) { this.application = application; }

	public static void install(Application application, boolean mainProcess) {
		if (!com.mishiranu.dashchan.BuildConfig.ENABLE_APP_SECURITY_SETTINGS) return;
		instance = new AppSecurity(application);
		try { SecurityStore.processStarted(application, mainProcess); }
		catch (IOException ignored) { /* All gates fail closed on storage failure. */ }
		application.registerActivityLifecycleCallbacks(instance);
		IntentFilter filter = new IntentFilter(Intent.ACTION_SCREEN_OFF);
		application.registerReceiver(new BroadcastReceiver() {
			@Override public void onReceive(Context context, Intent intent) {
				SecurityStore.suspend(context);
				SecuritySessionProvider.setNotificationForeground(application, false);
				instance.notificationForeground = false;
				if (instance.notificationPrivacyEnabled()) refreshNotificationContents(application);
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
		if (!com.mishiranu.dashchan.BuildConfig.ENABLE_APP_SECURITY_SETTINGS) return false;
		try { return SecurityStore.enabled(context); }
		catch (IOException e) { return true; }
	}

	private static Activity windowActivity(Context context) {
		while (context instanceof android.content.ContextWrapper) {
			if (context instanceof Activity) return (Activity) context;
			Context base = ((android.content.ContextWrapper) context).getBaseContext();
			if (base == context) break;
			context = base;
		}
		return null;
	}
	private static boolean authenticationContext(Context context) {
		Activity activity = windowActivity(context);
		return activity != null && exempt(activity);
	}

	private static boolean visiblePip(Activity activity) {
		return activity instanceof com.mishiranu.dashchan.ui.gallery.VideoPipActivity
				&& activity.isInPictureInPictureMode();
	}

	private static boolean coverActivity(Activity activity, boolean backgroundCover) {
		boolean protect = isProtected(activity);
		boolean hidePip = protect && SecurityStore.hidePipVideo(activity);
		return PipProtectionPolicy.cover(protect, protect && SecurityStore.locked(activity), visiblePip(activity),
				hidePip, backgroundCover);
	}

	/** Reconcile both shields after the platform confirms entry/exit (callback order varies). */
	public static void pictureInPictureModeChanged(Activity activity) {
		if (instance == null) return;
		instance.checkForeground();
		boolean cover = coverActivity(activity, !instance.resumed.contains(activity));
		if (cover) instance.shield(activity); else instance.unshield(activity);
		refreshPrivateWindows(activity, !instance.resumed.contains(activity));
		applyCaptureFlag(activity.getWindow(), cover);
	}

	/** Capture policy is separate from authentication, shielding and notification redaction. */
	public static boolean shouldSecureWindow(Context context) {
		if (!com.mishiranu.dashchan.BuildConfig.ENABLE_APP_SECURITY_SETTINGS) return false;
		if (SecurityStore.designCaptureAllowed(context)) return false;
		if (authenticationContext(context) || SecurityStore.screenCaptureBlocked(context)) return true;
		Activity activity = windowActivity(context);
		// A still-valid RAM lease during a short Activity handoff is not permission to capture
		// a paused window. This also covers delayed dialog/popup updates in the background.
		return activity != null && instance != null && !instance.resumed.contains(activity)
				&& !visiblePip(activity) && isProtected(context);
	}

	private static void applyCaptureFlag(Window window, boolean covered) {
		boolean designCapture = SecurityStore.designCaptureAllowed(window.getContext());
		boolean secure = !designCapture && (covered || shouldSecureWindow(window.getContext()));
		if (secure == ((window.getAttributes().flags & WindowManager.LayoutParams.FLAG_SECURE) != 0)) return;
		if (secure) window.addFlags(WindowManager.LayoutParams.FLAG_SECURE);
		else window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE);
	}

	static void refreshCaptureWindow(Activity activity) {
		applyCaptureFlag(activity.getWindow(), false);
	}

	public static void redactNotification(Context context, androidx.core.app.NotificationCompat.Builder builder) {
		if (SecurityStore.shouldHideNotificationText(context)) {
			redact(builder, context);
		}
	}

	public static void redactReplyNotification(Context context, androidx.core.app.NotificationCompat.Builder builder) {
		if (SecurityStore.shouldHideReplyText(context)) redact(builder, context);
	}

	private static void redact(androidx.core.app.NotificationCompat.Builder builder, Context context) {
		builder.getExtras().clear();
		builder.setStyle(null).setLargeIcon((android.graphics.Bitmap) null).setPublicVersion(null)
				.setCustomContentView(null).setCustomBigContentView(null).setCustomHeadsUpContentView(null)
				.setContentTitle("Slooop").setContentText(context.getString(com.mishiranu.dashchan.R.string.security_notification_hidden))
				.setTicker(null).setSubText(null).setContentInfo(null).setVisibility(androidx.core.app.NotificationCompat.VISIBILITY_SECRET);
		builder.getExtras().putBoolean(NOTIFICATION_HIDDEN, true);
	}

	public static android.app.Notification redactNotification(Context context, android.app.Notification notification) {
		if (!com.mishiranu.dashchan.BuildConfig.ENABLE_APP_SECURITY_SETTINGS || notification == null) return notification;
		if (!SecurityStore.shouldHideNotificationText(context)) return notification;
		androidx.core.app.NotificationCompat.Builder builder = new androidx.core.app.NotificationCompat.Builder(context, notification.clone());
		redact(builder, context);
		return builder.build();
	}

	/** Keep the producer's builder untouched so subsequent progress updates retain their original presentation. */
	public static android.app.Notification prepareNotification(Context context, String tag, int id,
			android.app.Notification notification) {
		if (notification == null || !com.mishiranu.dashchan.BuildConfig.ENABLE_APP_SECURITY_SETTINGS) return notification;
		NOTIFICATION_CONTENTS.put(tag, id, notification.getChannelId(), notification.when, notification.clone());
		return redactNotification(context, notification);
	}

	static void clearNotificationContents() { NOTIFICATION_CONTENTS.clear(); }

	/** Rebind only active notices. Replies have a separate authoritative source in MyPostsStorage. */
	private static void refreshNotificationContents(Context context) {
		try {
			android.app.NotificationManager manager = context.getSystemService(android.app.NotificationManager.class);
			if (manager != null) {
				android.service.notification.StatusBarNotification[] active = manager.getActiveNotifications();
				java.util.HashSet<String> keys = new java.util.HashSet<>();
				for (android.service.notification.StatusBarNotification item : active) keys.add(NotificationContentCache.key(item.getTag(), item.getId()));
				NOTIFICATION_CONTENTS.retain(keys);
				boolean hide = SecurityStore.shouldHideNotificationText(context);
				for (android.service.notification.StatusBarNotification item : active) {
					android.app.Notification shown = item.getNotification();
					if (com.mishiranu.dashchan.C.NOTIFICATION_CHANNEL_REPLIES.equals(shown.getChannelId())) continue;
					boolean hidden = shown.extras != null && (shown.extras.getBoolean(NOTIFICATION_HIDDEN)
							|| "Slooop".contentEquals(shown.extras.getCharSequence(android.app.Notification.EXTRA_TITLE, ""))
							&& context.getString(com.mishiranu.dashchan.R.string.security_notification_hidden)
									.contentEquals(shown.extras.getCharSequence(android.app.Notification.EXTRA_TEXT, "")));
					if (hidden == hide) continue;
					android.app.Notification original = NOTIFICATION_CONTENTS.get(item.getTag(), item.getId(), shown.getChannelId(), shown.when);
					if (original == null) {
						if (!hide) continue; // A process restart cannot reconstruct already-hidden service content.
						original = shown;
						NOTIFICATION_CONTENTS.put(item.getTag(), item.getId(), shown.getChannelId(), shown.when, shown.clone());
					}
					androidx.core.app.NotificationCompat.Builder builder = new androidx.core.app.NotificationCompat.Builder(context, original.clone())
							.setOnlyAlertOnce(true).setSilent(true);
					android.app.Notification updated = redactNotification(context, builder.build());
					// A dismissed or newly replaced notification must not be resurrected or overwritten.
					for (android.service.notification.StatusBarNotification current : manager.getActiveNotifications()) {
						if (item.getId() == current.getId() && java.util.Objects.equals(item.getTag(), current.getTag())
								&& item.getPostTime() == current.getPostTime()) {
							manager.notify(item.getTag(), item.getId(), updated); break;
						}
					}
				}
			}
		} catch (RuntimeException ignored) { /* Publishing retains the same policy on its next update. */ }
		com.mishiranu.dashchan.content.WatcherNotifications.refreshReplyContents(context);
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
		else for (Activity activity : instance.started) {
			if (coverActivity(activity, false)) instance.shield(activity);
			else instance.unshield(activity);
		}
		for (Activity activity : instance.started) {
			applyCaptureFlag(activity.getWindow(), instance.shields.containsKey(activity));
		}
		refreshNotificationContents(instance.application);
	}

	/** Side-effect-only entry activities must defer before opening another app or importing content. */
	public static boolean deferIfLocked(Activity activity) {
		if (!com.mishiranu.dashchan.BuildConfig.ENABLE_APP_SECURITY_SETTINGS) return false;
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
		applyCaptureFlag(activity.getWindow(), false);
	}

	@Override public void onActivityPostCreated(Activity activity, Bundle state) {
		if (!exempt(activity)) registerWindow(activity.getWindow());
		if (SecurityStore.locked(activity)) shield(activity);
	}

	@Override public void onActivityStarted(Activity activity) {
		started.add(activity);
		applyCaptureFlag(activity.getWindow(), false);
		try { SecurityStore.foreground(activity, true); } catch (IOException ignored) {}
		consumeEmptyProfileToken(activity);
	}

	@Override public void onActivityPreResumed(Activity activity) {
		resumed.add(activity);
		// Screen-off can pause without a new onStart. Restore only a bounded recent
		// authentication before deciding whether an unlock Activity is needed.
		try { SecurityStore.foreground(activity, true); } catch (IOException ignored) {}
		if (exempt(activity)) { openingLock = false; return; }
		updateNotificationForeground();
		if (SecurityStore.locked(activity)) {
			shield(activity);
			if (!openingLock && !activity.isFinishing()) {
				openingLock = true;
				activity.startActivity(new Intent(activity, AppLockActivity.class));
			}
		} else {
			// The unlock Activity belongs to the main process. A private-browser
			// client must re-arm its own launch gate after observing the shared unlock.
			openingLock = false;
			unshield(activity);
			refreshPrivateWindows(activity, false);
			applyCaptureFlag(activity.getWindow(), false);
		}
	}

	@Override public void onActivityPrePaused(Activity activity) {
		resumed.remove(activity);
		updateNotificationForeground();
		if (enabled()) {
			// Paused does not mean hidden for PiP. A true lock still overrides the opt-out.
			boolean cover = coverActivity(activity, true);
			applyCaptureFlag(activity.getWindow(), cover);
			if (cover) shield(activity); else unshield(activity);
			refreshPrivateWindows(activity, true);
		}
	}

	@Override public void onActivityStopped(Activity activity) {
		started.remove(activity);
		if (activity.isChangingConfigurations()) return;
		boolean foreground = false;
		for (Activity other : started) if (!other.isFinishing()) foreground = true;
		try { SecurityStore.foreground(activity, foreground); } catch (IOException ignored) {}
		if (SecurityStore.locked(activity)) protectAll();
	}

	@Override public void onActivityDestroyed(Activity activity) {
		if (exempt(activity)) openingLock = false;
		started.remove(activity); resumed.remove(activity); shields.remove(activity); updateNotificationForeground();
	}
	@Override public void onActivityCreated(Activity activity, Bundle state) {}
	@Override public void onActivityResumed(Activity activity) {
		resumed.add(activity);
		updateNotificationForeground();
		refreshCaptureWindow(activity);
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
