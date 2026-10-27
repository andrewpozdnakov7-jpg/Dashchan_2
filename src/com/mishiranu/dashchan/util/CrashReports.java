package com.mishiranu.dashchan.util;

import android.app.Activity;
import android.app.ActivityManager;
import android.app.Application;
import android.app.ApplicationExitInfo;
import android.content.Context;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.Process;
import android.os.SystemClock;
import com.mishiranu.dashchan.BuildConfig;
import com.mishiranu.dashchan.content.security.PrivacyReset;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.locks.ReentrantLock;

/** Local-only diagnostics. No network client, uploads, content text, URLs, account IDs or raw dumps. */
public final class CrashReports {
	public enum Event { START, ACTIVITY_RESUMED, ACTIVITY_PAUSED, SCREEN, GALLERY_OPEN, GALLERY_PAGE,
		VIDEO_START, PIP_ENTER, POSTING_OPEN, TEST_CRASH }
	private static final ExecutorService IO = Executors.newSingleThreadExecutor(r -> new Thread(r, "CrashReports"));
	private static final Handler MAIN = new Handler(Looper.getMainLooper());
	private static final CopyOnWriteArrayList<Runnable> LISTENERS = new CopyOnWriteArrayList<>();
	private static volatile CrashReports instance;
	private final Application application;
	private final String session = UUID.randomUUID().toString().replace("-", "");
	private final ReentrantLock lock = new ReentrantLock();
	private final ArrayDeque<String> events = new ArrayDeque<>();
	private final AtomicBoolean crashing = new AtomicBoolean();
	private final Thread.UncaughtExceptionHandler previous;
	private CrashReportStore store;
	private volatile boolean ready;
	private long lastSummary = Long.MIN_VALUE;
	private byte[] emergencyMemory = new byte[16 * 1024];
	private CrashReports(Application application, boolean mainProcess) {
		this.application = application;
		previous = Thread.getDefaultUncaughtExceptionHandler();
		Thread.setDefaultUncaughtExceptionHandler(this::uncaught);
		try {
			store = new CrashReportStore(new File(application.getNoBackupFilesDir(), "crash-reports-v1"));
			if (mainProcess) store.cursor(System.currentTimeMillis());
		} catch (IOException | RuntimeException ignored) { store = null; }
		application.registerActivityLifecycleCallbacks(new Application.ActivityLifecycleCallbacks() {
			@Override public void onActivityResumed(Activity activity) { record(Event.ACTIVITY_RESUMED, activity.getClass()); }
			@Override public void onActivityPaused(Activity activity) { record(Event.ACTIVITY_PAUSED, activity.getClass()); }
			@Override public void onActivityCreated(Activity a, Bundle b) {}
			@Override public void onActivityStarted(Activity a) {}
			@Override public void onActivityStopped(Activity a) {}
			@Override public void onActivitySaveInstanceState(Activity a, Bundle b) {}
			@Override public void onActivityDestroyed(Activity a) {}
		});
		if (mainProcess) execute(() -> {
			try { collectPrevious(); pruneShares(application); }
			catch (IOException | RuntimeException ignored) { /* Optional diagnostics must never break startup. */ }
			finally { ready = true; for (Runnable listener : LISTENERS) MAIN.post(listener); }
		}); else ready = true;
	}
	public static synchronized void install(Application application, boolean mainProcess) {
		if (instance != null) return;
		instance = new CrashReports(application, mainProcess);
		record(Event.START);
	}
	public static void execute(Runnable work) {
		IO.execute(() -> { try { work.run(); } catch (RuntimeException ignored) { /* Optional diagnostics only. */ } });
	}
	public static boolean ready() { return instance == null || instance.ready; }
	public static void listen(Runnable listener) { LISTENERS.add(listener); }
	public static void unlisten(Runnable listener) { LISTENERS.remove(listener); }
	private static void changed() { for (Runnable listener : LISTENERS) MAIN.post(listener); }
	public static void record(Event event) { record(event, null); }
	/** Typed events and Class objects only; cannot accept UI text, filenames, URLs or exception messages. */
	public static void record(Event event, Class<?> component) {
		CrashReports current = instance;
		if (current == null || current.crashing.get()) return;
		String label = event.name() + (component == null ? "" : ":" + CrashReportPolicy.token(component.getSimpleName(), 64));
		synchronized (current.events) {
			if (current.events.size() == 32) current.events.removeFirst();
			current.events.addLast(SystemClock.elapsedRealtime() + " " + label);
		}
		// Android retains this <=128-byte summary with the process exit record, even for native crashes.
		long now = SystemClock.uptimeMillis();
		synchronized (current) {
			if (current.lastSummary != Long.MIN_VALUE && now - current.lastSummary < 1000) return;
			current.lastSummary = now;
		}
		try {
			ActivityManager manager = current.application.getSystemService(ActivityManager.class);
			if (manager != null) manager.setProcessStateSummary(CrashReportPolicy.summary(current.session,
					BuildConfig.VERSION_CODE, BuildConfig.VERSION_NAME, label, Build.VERSION.SDK_INT,
					BuildConfig.FLAVOR + ":" + BuildConfig.BUILD_TYPE).getBytes(StandardCharsets.US_ASCII));
		} catch (RuntimeException ignored) { /* System rate limits and unavailable services are non-fatal. */ }
	}
	private String header(String kind, long timestamp, String crashedSession, String version, String code, String sdk, String variant) {
		StringBuilder text = new StringBuilder("Slooop local crash report v1\nKind: ").append(kind)
				.append("\nTimestamp: ").append(timestamp).append("\nSession: ").append(crashedSession)
				.append("\nCrashed build: ").append(version).append(" / ").append(code)
				.append("\nCrashed Android API: ").append(sdk).append("\nCrashed variant: ").append(variant)
				.append("\nReporter build: ").append(CrashReportPolicy.token(BuildConfig.VERSION_NAME, 24))
				.append(" / ").append(BuildConfig.VERSION_CODE).append("\nReporter variant: ")
				.append(CrashReportPolicy.token(BuildConfig.FLAVOR + ":" + BuildConfig.BUILD_TYPE, 64))
				.append("\nReporter feature: crash-reports-v1-20261026\nNative player: ")
				.append(CrashReportPolicy.token(BuildConfig.NATIVE_PLAYER_FFMPEG_FLAVOR, 32)).append("\nPackaged native ABI: ")
				.append(CrashReportPolicy.token(String.join(",", BuildConfig.NATIVE_PLAYER_ABIS), 100))
				.append("\nReporter Android: ").append(CrashReportPolicy.token(Build.VERSION.RELEASE, 40))
				.append(" / API ").append(Build.VERSION.SDK_INT).append("\nOS build: ")
				.append(CrashReportPolicy.token(Build.ID, 100)).append("\nDevice: ")
				.append(CrashReportPolicy.token(Build.MANUFACTURER, 64)).append(' ')
				.append(CrashReportPolicy.token(Build.MODEL, 80)).append("\nABI: ")
				.append(CrashReportPolicy.token(String.join(",", Build.SUPPORTED_ABIS), 100)).append('\n');
		return text.toString();
	}
	private void uncaught(Thread thread, Throwable error) {
		emergencyMemory = null;
		boolean held = false;
		try {
			if (crashing.compareAndSet(false, true) && store != null && (held = lock.tryLock(150, TimeUnit.MILLISECONDS))) {
				long now = System.currentTimeMillis();
				StringBuilder text = new StringBuilder(header(error instanceof TestCrash ? "TEST_JAVA" : "JAVA", now,
						session, CrashReportPolicy.token(BuildConfig.VERSION_NAME, 24), Integer.toString(BuildConfig.VERSION_CODE),
						Integer.toString(Build.VERSION.SDK_INT), CrashReportPolicy.token(BuildConfig.FLAVOR + ":" + BuildConfig.BUILD_TYPE, 64)));
				text.append("Main thread: ").append(thread == Looper.getMainLooper().getThread()).append('\n');
				synchronized (events) { for (String event : events) text.append("Event: ").append(event).append('\n'); }
				text.append(CrashReportPolicy.stack(error));
				store.write("report-" + Math.max(0, now) + "-" + session.substring(0, 12) + ".txt", text.toString(), now);
			}
		} catch (Throwable ignored) {
			// No dialogs, waiting on logcat, recursive reporting or recovery attempts in a dying process.
		} finally {
			if (held) lock.unlock();
			if (previous != null) previous.uncaughtException(thread, error);
			else { Process.killProcess(Process.myPid()); System.exit(10); }
		}
	}
	private void collectPrevious() throws IOException {
		if (store == null || PrivacyReset.requiresRecovery(application)) return;
		lock.lock();
		try {
			long cutoff = store.cursor(System.currentTimeMillis()), cursor = cutoff;
			ActivityManager manager = application.getSystemService(ActivityManager.class);
			if (manager == null) return;
			List<ApplicationExitInfo> exits = new ArrayList<>(manager.getHistoricalProcessExitReasons(null, 0, 32));
			exits.sort(Comparator.comparingLong(ApplicationExitInfo::getTimestamp));
			for (ApplicationExitInfo exit : exits) {
				long timestamp = exit.getTimestamp();
				if (timestamp <= cutoff) continue;
				if (CrashReportPolicy.crashReason(exit.getReason())
						&& System.currentTimeMillis() - timestamp <= CrashReportPolicy.MAX_AGE) {
					byte[] summary = exit.getProcessStateSummary();
					String[] state = summary != null ? CrashReportPolicy.parseSummary(new String(summary, StandardCharsets.US_ASCII)) : null;
					if (state == null || exit.getReason() != ApplicationExitInfo.REASON_CRASH || !store.containsSession(state[1])) {
						String kind = exit.getReason() == ApplicationExitInfo.REASON_CRASH_NATIVE ? "NATIVE"
								: exit.getReason() == ApplicationExitInfo.REASON_ANR ? "ANR" : "SYSTEM_JAVA";
						StringBuilder text = new StringBuilder(header(kind, timestamp, state != null ? state[1] : "unknown",
								state != null ? state[3] : "unknown", state != null ? state[2] : "unknown",
								state != null ? state[5] : "unknown", state != null ? state[6] : "unknown"));
						text.append("System reason: ").append(exit.getReason()).append("\nStatus/signal: ")
								.append(exit.getStatus()).append("\nPID: ").append(exit.getPid())
								.append("\nMemory PSS/RSS KB: ").append(exit.getPss()).append('/').append(exit.getRss()).append('\n');
						if (state != null) text.append("Last sampled event: ").append(state[4]).append('\n');
						appendSystemTrace(text, exit);
						String suffix = String.format(java.util.Locale.US, "%012x", ((long) exit.getPid() << 8) | exit.getReason());
						store.write("report-" + Math.max(0, timestamp) + "-" + suffix + ".txt", text.toString(), System.currentTimeMillis());
					}
				}
				cursor = Math.max(cursor, timestamp);
			}
			store.advanceCursor(cursor); store.prune(System.currentTimeMillis());
		} finally { lock.unlock(); }
	}
	private static void appendSystemTrace(StringBuilder text, ApplicationExitInfo exit) {
		try (InputStream input = exit.getTraceInputStream()) {
			if (input == null) { text.append("System trace: unavailable\n"); return; }
			if (exit.getReason() != ApplicationExitInfo.REASON_ANR) {
				text.append("System trace available; raw tombstone/memory is not attached for privacy.\n"); return;
			}
			ByteArrayOutputStream bytes = new ByteArrayOutputStream(); byte[] buffer = new byte[2048]; int read;
			while (bytes.size() < 128 * 1024 && (read = input.read(buffer, 0, Math.min(buffer.length, 128 * 1024 - bytes.size()))) > 0) {
				bytes.write(buffer, 0, read);
			}
			text.append("Filtered ANR Java frames (no thread names, messages or raw trace):\n");
			for (String line : new String(bytes.toByteArray(), StandardCharsets.UTF_8).split("\n")) {
				if (text.length() > 28000) break;
				String frame = CrashReportPolicy.anrFrame(line);
				if (frame != null) text.append(frame).append('\n');
			}
		} catch (IOException | RuntimeException ignored) { text.append("System trace: unavailable\n"); }
	}
	private static CrashReports available() throws IOException {
		CrashReports current = instance;
		if (current == null || current.store == null) throw new IOException("Report storage unavailable");
		return current;
	}
	public static ArrayList<File> list() throws IOException {
		CrashReports current = available(); current.lock.lock();
		try { current.store.prune(System.currentTimeMillis()); return current.store.list(); } finally { current.lock.unlock(); }
	}
	public static String nextUnseen() throws IOException {
		CrashReports current = available(); current.lock.lock();
		try { for (File file : current.store.list()) if (!current.store.offered(file.getName())) return file.getName(); return null; }
		finally { current.lock.unlock(); }
	}
	public static boolean markOffered(String id) throws IOException {
		CrashReports current = available(); current.lock.lock();
		try { return current.store.markOffered(id); } finally { current.lock.unlock(); }
	}
	public static String read(String id) throws IOException {
		CrashReports current = available(); current.lock.lock();
		try { return current.store.read(id); } finally { current.lock.unlock(); }
	}
	public static void delete(String id) throws IOException {
		CrashReports current = available(); current.lock.lock();
		try { current.store.delete(id); clearShares(current.application); } finally { current.lock.unlock(); changed(); }
	}
	private static File shareDirectory(Context context) { return new File(context.getCacheDir(), "crash-report-shares"); }
	public static File export(Context context, String id) throws IOException {
		String report = read(id);
		File directory = shareDirectory(context);
		if (!directory.isDirectory() && !directory.mkdirs()) throw new IOException("Share storage unavailable");
		pruneShares(context);
		File file = new File(directory, "share-" + UUID.randomUUID().toString().replace("-", "") + ".txt");
		try (FileOutputStream stream = new FileOutputStream(file)) { stream.write(report.getBytes(StandardCharsets.UTF_8)); }
		return file;
	}
	public static File resolveShare(Context context, String name) throws IOException {
		if (!CrashReportPolicy.shareName(name)) return null;
		File directory = shareDirectory(context).getCanonicalFile(), file = new File(directory, name);
		return file.getCanonicalFile().equals(file.getAbsoluteFile()) && Files.isRegularFile(file.toPath(), LinkOption.NOFOLLOW_LINKS)
				&& CrashReportPolicy.freshShare(file.lastModified(), System.currentTimeMillis()) ? file : null;
	}
	private static void pruneShares(Context context) throws IOException {
		File[] files = shareDirectory(context).listFiles(); if (files == null) return;
		java.util.Arrays.sort(files, Comparator.comparingLong(File::lastModified).reversed()); int retained = 0;
		for (File file : files) if (CrashReportPolicy.shareName(file.getName())) {
			if (resolveShare(context, file.getName()) == null || ++retained >= CrashReportPolicy.MAX_REPORTS) Files.deleteIfExists(file.toPath());
		}
	}
	private static void clearShares(Context context) throws IOException {
		File[] files = shareDirectory(context).listFiles(); if (files == null) return;
		for (File file : files) if (CrashReportPolicy.shareName(file.getName())) Files.deleteIfExists(file.toPath());
	}
	public static final class TestCrash extends RuntimeException {
		private static final long serialVersionUID = 1L;
		private TestCrash() { super("Intentional local crash-report test"); }
	}
	public static void crashForUserTest() { record(Event.TEST_CRASH); throw new TestCrash(); }
}
