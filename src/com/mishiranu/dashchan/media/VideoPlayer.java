package com.mishiranu.dashchan.media;

import android.annotation.SuppressLint;
import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.graphics.Bitmap;
import android.graphics.Point;
import android.graphics.SurfaceTexture;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.ParcelFileDescriptor;
import android.os.Process;
import android.util.Pair;
import android.view.Surface;
import android.view.TextureView;
import android.view.View;
import android.view.ViewGroup;
import chan.content.ChanManager;
import chan.util.StringUtils;
import com.mishiranu.dashchan.BuildConfig;
import com.mishiranu.dashchan.content.Preferences;
import com.mishiranu.dashchan.util.ConcurrentUtils;
import dalvik.system.PathClassLoader;
import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.lang.ref.WeakReference;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.Collections;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.concurrent.Semaphore;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

public class VideoPlayer {
	private static boolean loaded = false;
	private static HolderInterface holder;
	private static boolean playbackSpeedSupported = true;

	// Extraction requirements. The Holder static initializer below keeps the
	// actual System.loadLibrary order explicit because the JNI dependencies are order-sensitive.
	private static final boolean BUNDLED_ATEMPO = "ffmpeg8".equals(BuildConfig.NATIVE_PLAYER_FFMPEG_FLAVOR);
	private static final String[] PLAYER_LIBRARIES = BUNDLED_ATEMPO
			? new String[] {"player", "avfilter"} : new String[] {"player"};
	private static final String[] WEBM_REQUIRED_LIBRARIES =
			{"avutil", "swresample", "swscale", "avcodec", "avformat", "yuv"};
	private static final String[] WEBM_OPTIONAL_LIBRARIES = {"dav1d"};
	private static final String[] BUNDLED_REQUIRED_LIBRARIES = BUNDLED_ATEMPO
			? new String[] {"player", "dav1d", "avutil", "swresample", "swscale", "avcodec", "avformat",
					"avfilter", "yuv"}
			: new String[] {"player", "dav1d", "avutil", "swresample", "swscale", "avcodec", "avformat", "yuv"};

	public static Pair<Boolean, String> loadLibraries(Context context) {
		synchronized (VideoPlayer.class) {
			if (loaded) {
				return new Pair<>(true, null);
			}
			NativeLibraryPlan plan;
			try {
				plan = NativeLibraryPlan.createBundled(context);
			} catch (IOException e) {
				ChanManager.ExtensionItem extensionItem = ChanManager.getInstance().getWebmLibraryExtension();
				if (extensionItem == null) {
					e.printStackTrace();
					return new Pair<>(false, formatLoadFailure("Bundled video libraries missing or broken", e));
				}
				try {
					plan = NativeLibraryPlan.createExternal(context, extensionItem);
				} catch (IOException externalException) {
					e.printStackTrace();
					externalException.printStackTrace();
					return new Pair<>(false, formatLoadFailure("Bundled video libraries missing or broken", e) +
							"\n\n" + formatLoadFailure("External WebM2 fallback failed", externalException));
				}
			}
			// System.loadLibrary uses a path from ClassLoader, so I must create one
			// containing all paths to native libraries (client + webm libraries package).
			// Holder class is loaded from this class loader, so all libraries will load correctly.
			PathClassLoader classLoader = new PathClassLoader(plan.dexPath,
					plan.librarySearchPath, Context.class.getClassLoader());
			try {
				// Initialize class (invoke static block)
				Class<?> holderClass = Class.forName(Holder.class.getName(), true, classLoader);
				Field instanceField = holderClass.getDeclaredField("INSTANCE");
				instanceField.setAccessible(true);
				InvocationHandler handler = (InvocationHandler) instanceField.get(null);
				holder = (HolderInterface) Proxy.newProxyInstance(VideoPlayer.class.getClassLoader(),
						new Class[] { HolderInterface.class }, handler);
				loaded = true;
				if (VideoDiagnostics.isRecording()) {
					startDiagnosticCapture();
				}
				return new Pair<>(true, null);
			} catch (Exception | LinkageError e) {
				e.printStackTrace();
				String message = StringUtils.emptyIfNull(e.getMessage());
				message = shortenMessagePath(context.getPackageCodePath(), message);
				message = shortenMessagePath(plan.librarySearchPath, message);
				if (message.endsWith("...")) {
					message = message.substring(0, message.length() - 3);
				} else if (message.endsWith(".")) {
					message = message.substring(0, message.length() - 1);
				}
				if (StringUtils.isEmpty(message)) {
					message = e.getClass().getName();
				}
				return new Pair<>(false, message + "\n" + plan.describe());
			}
		}
	}

	private static String formatLoadFailure(String label, IOException exception) {
		String message = exception.getMessage();
		if (StringUtils.isEmpty(message)) {
			message = exception.getClass().getName();
		}
		return label + ":\n" + message;
	}

	private static String shortenMessagePath(String path, String message) {
		if (path != null) {
			File file = new File(path);
			if (!file.isDirectory()) {
				file = file.getParentFile();
			}
			return message.replace(file.getPath() + "/", "");
		} else {
			return message;
		}
	}

	private static String joinArray(String[] array) {
		StringBuilder builder = new StringBuilder();
		for (String item : array) {
			if (builder.length() > 0) {
				builder.append(", ");
			}
			builder.append(item);
		}
		return builder.toString();
	}

	public static boolean isLoaded() {
		synchronized (VideoPlayer.class) {
			return loaded;
		}
	}

	static void startDiagnosticCapture() {
		synchronized (VideoPlayer.class) {
			if (holder != null) {
				try {
					holder.startDiagnostics();
				} catch (RuntimeException | LinkageError ignored) {
					// Diagnostics are unavailable with an older external native player.
				}
			}
		}
	}

	static String stopDiagnosticCapture() {
		synchronized (VideoPlayer.class) {
			if (holder != null) {
				try {
					return holder.stopDiagnostics();
				} catch (RuntimeException | LinkageError ignored) {
					return "native_diagnostics_unavailable=true\n";
				}
			}
			return null;
		}
	}

	public interface Listener {
		void onComplete(VideoPlayer player);
		void onBusyStateChange(VideoPlayer player, boolean busy);
		void onDimensionChange(VideoPlayer player);
	}

	public interface RangeCallback {
		void requestPartFromPosition(long start);
	}

	private static class SessionData {
		public final long pointer;
		public final RangeCallback rangeCallback;

		public long request;
		public long size;
		public long total = -1;
		public long partStart;
		public long partEnd;

		public SessionData(long pointer, RangeCallback rangeCallback) {
			this.pointer = pointer;
			this.rangeCallback = rangeCallback;
		}
	}

	private volatile Listener listener;
	private final boolean seekAnyFrame;
	private final boolean audioEnabled;

	private SessionData sessionData;
	private SessionData initData;

	private boolean consumed = false;
	private boolean playing = false;
	private int playbackSpeed = 1000;

	private boolean lastSeeking = false;
	private volatile boolean lastBuffering = false;

	public VideoPlayer(Listener listener, boolean seekAnyFrame) {
		this(listener, seekAnyFrame, true);
	}

	private VideoPlayer(Listener listener, boolean seekAnyFrame, boolean audioEnabled) {
		this.listener = listener;
		this.seekAnyFrame = seekAnyFrame;
		this.audioEnabled = audioEnabled;
	}

	public void setListener(Listener listener) {
		this.listener = listener;
	}

	public static Bitmap createThumbnail(File file) throws IOException, InterruptedException {
		if (!isLoaded()) {
			return null;
		}
		VideoPlayer player = new VideoPlayer(null, true, false);
		SurfaceTexture surfaceTexture = null;
		Surface surface = null;
		try {
			player.init(file, null);
			Point dimensions = player.getDimensions();
			surfaceTexture = new SurfaceTexture(false);
			surfaceTexture.setDefaultBufferSize(Math.max(dimensions.x, 1), Math.max(dimensions.y, 1));
			surface = new Surface(surfaceTexture);
			player.setSurface(surface);
			player.setPlaying(true);
			long timeout = System.currentTimeMillis() + 3000L;
			while (!Thread.currentThread().isInterrupted() && System.currentTimeMillis() < timeout) {
				Bitmap bitmap = player.getCurrentFrame();
				if (bitmap != null) {
					return bitmap;
				}
				Thread.sleep(40L);
			}
			if (Thread.currentThread().isInterrupted()) {
				throw new InterruptedException();
			}
			return null;
		} finally {
			player.setPlaying(false);
			if (surface != null) {
				surface.release();
			}
			if (surfaceTexture != null) {
				surfaceTexture.release();
			}
			player.destroy();
		}
	}

	public void init(File file, RangeCallback rangeCallback) throws IOException {
		int priority = Process.getThreadPriority(Process.myTid());
		Process.setThreadPriority(Process.THREAD_PRIORITY_DISPLAY);
		try {
			SessionData initData = null;
			synchronized (this) {
				if (sessionData == null && initData == null && !consumed) {
					long initPointer;
					try (ParcelFileDescriptor descriptor = ParcelFileDescriptor
							.open(file, ParcelFileDescriptor.MODE_READ_ONLY)) {
						int fd = descriptor.detachFd();
						initPointer = holder.preInit(fd);
					}
					if (rangeCallback == null) {
						long length = file.length();
						holder.setRange(initPointer, 0, length, length);
					}
					initData = new SessionData(initPointer, rangeCallback);
					this.initData = initData;
					if (!audioEnabled) {
						holder.setAudioEnabled(initPointer, false);
					}
					holder.setHardwareAcceleration(initPointer,
							audioEnabled && Preferences.isHardwareVideoAcceleration());
				}
			}
			if (initData != null) {
				holder.init(initData.pointer, new NativeBridge(this), seekAnyFrame);
				synchronized (this) {
					this.initData = null;
					if (consumed) {
						holder.destroy(initData.pointer, false);
					} else {
						int errorCode = holder.getErrorCode(initData.pointer);
						if (errorCode != 0) {
							destroyInternal(initData);
							throw new InitializationException(errorCode);
						}
						sessionData = initData;
						applyPlaybackSpeedLocked();
						seekerThread.start();
					}
				}
			}
		} finally {
			Process.setThreadPriority(priority);
		}
	}

	public static class InitializationException extends IOException {
		private InitializationException(int errorCode) {
			super("Can't initialize player: CODE=" + errorCode);
		}
	}

	private SessionData getInitOrSessionDataLocked() {
		return sessionData != null ? sessionData : initData;
	}

	public void setDownloadRange(long size, long total) {
		synchronized (this) {
			SessionData sessionData = getInitOrSessionDataLocked();
			if (sessionData != null) {
				sessionData.size = size;
				sessionData.total = total;
				if (size > sessionData.request || sessionData.request <= 0) {
					if (sessionData.request > 0) {
						sessionData.request = 0;
						sessionData.rangeCallback.requestPartFromPosition(0);
					}
					setRangeLocked(0, size, total);
				}
			}
		}
	}

	public void setPartRange(long start, long end) {
		synchronized (this) {
			SessionData sessionData = getInitOrSessionDataLocked();
			if (sessionData != null && sessionData.rangeCallback != null &&
					sessionData.request >= start && sessionData.request <= end) {
				sessionData.partStart = start;
				sessionData.partEnd = end;
				// Shift request position for higher priority
				sessionData.request = end;
				setRangeLocked(start, end, sessionData.total);
			}
		}
	}

	private void requestRange(long start) {
		synchronized (this) {
			SessionData sessionData = getInitOrSessionDataLocked();
			if (!consumed && sessionData != null && sessionData.rangeCallback != null) {
				int awaitLength = 200000;
				boolean request = true;
				if (start > 0 && sessionData.request > 0 && (start == sessionData.request ||
						start >= sessionData.partStart && start <= sessionData.partEnd + awaitLength)) {
					// Use current part range request
					request = false;
				}
				if (request && start <= sessionData.size + awaitLength) {
					// Use download range
					request = false;
					if (sessionData.request > 0) {
						// Cancel part range request
						sessionData.request = 0;
						sessionData.rangeCallback.requestPartFromPosition(0);
					}
					setRangeLocked(0, sessionData.size, sessionData.total);
				}
				if (request) {
					sessionData.request = start;
					sessionData.partStart = 0;
					sessionData.partEnd = 0;
					sessionData.rangeCallback.requestPartFromPosition(start);
				}
			}
		}
	}

	private void setRangeLocked(long start, long end, long total) {
		if (!consumed) {
			SessionData sessionData = getInitOrSessionDataLocked();
			if (sessionData != null) {
				holder.setRange(sessionData.pointer, start, end, total);
			}
		}
	}

	private boolean isInitialized() {
		return !consumed && sessionData != null;
	}

	private final int[] summaryOutput = new int[3];

	private boolean obtainSummary() {
		if (isInitialized()) {
			holder.getSummary(sessionData.pointer, summaryOutput);
			return true;
		}
		return false;
	}

	public Point getDimensions() {
		return obtainSummary() ? new Point(summaryOutput[0], summaryOutput[1]) : null;
	}

	public boolean isAudioPresent() {
		return obtainSummary() ? summaryOutput[2] != 0 : null;
	}

	public long getDuration() {
		return isInitialized() ? holder.getDuration(sessionData.pointer) : 0;
	}

	public long getPosition() {
		SeekToPosition seekToPosition = this.seekToPosition;
		return isInitialized() ? seekToPosition != null ? seekToPosition.position
				: holder.getPosition(sessionData.pointer) : 0;
	}

	private void cancelSetPositionLocked() {
		boolean locked = seekerMutex.tryAcquire();
		if (!locked) {
			holder.setCancelSeek(sessionData.pointer, true);
			seekerMutex.acquireUninterruptibly();
			holder.setCancelSeek(sessionData.pointer, false);
		}
		seekerMutex.release();
	}

	public void setPosition(long position) {
		synchronized (this) {
			if (isInitialized()) {
				if (playing) {
					cancelSetPositionLocked();
				}
				synchronized (seekerThread) {
					seekToPosition = new SeekToPosition(position, playing);
					seekerThread.notifyAll();
				}
			}
		}
	}

	private void onComplete() {
		Listener listener = this.listener;
		if (listener != null && !consumed) {
			listener.onComplete(this);
		}
	}

	private void onDimensionChange() {
		if (videoView != null) {
			videoView.requestLayout();
		}
		Listener listener = this.listener;
		if (listener != null) {
			listener.onDimensionChange(this);
		}
	}

	@SuppressLint("ViewConstructor")
	private static class PlayerTextureView extends TextureView implements TextureView.SurfaceTextureListener {
		private final WeakReference<VideoPlayer> player;
		private final int diagnosticsId = System.identityHashCode(this);
		private Surface playerSurface;
		private int surfaceUpdates;

		public PlayerTextureView(Context context, VideoPlayer player) {
			super(context);
			this.player = new WeakReference<>(player);
			setSurfaceTextureListener(this);
			setClickable(false);
			setFocusable(false);
			VideoDiagnostics.recordUi("texture=" + diagnosticsId + " created");
		}

		@Override
		protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
			super.onMeasure(widthMeasureSpec, heightMeasureSpec);
			VideoPlayer player = this.player.get();
			if (player == null) {
				return;
			}
			Point dimensions = player.getDimensions();
			if (dimensions != null && dimensions.x > 0 && dimensions.y > 0) {
				int width = getMeasuredWidth();
				int height = getMeasuredHeight();
				if (dimensions.x * height > dimensions.y * width) {
					height = dimensions.y * width / dimensions.x;
				} else {
					width = dimensions.x * height / dimensions.y;
				}
				setMeasuredDimension(width, height);
			}
		}

		@Override
		public void onSurfaceTextureAvailable(SurfaceTexture surface, int width, int height) {
			VideoPlayer player = this.player.get();
			if (player == null) {
				return;
			}
			releasePlayerSurface();
			playerSurface = new Surface(surface);
			surfaceUpdates = 0;
			VideoDiagnostics.recordUi("texture=" + diagnosticsId + " surface_available"
					+ " surface_size=" + width + "x" + height
					+ " view_size=" + getWidth() + "x" + getHeight()
					+ " visibility=" + getVisibility() + " alpha=" + getAlpha());
			player.setSurface(playerSurface);
		}

		@Override
		public boolean onSurfaceTextureDestroyed(SurfaceTexture surface) {
			VideoDiagnostics.recordUi("texture=" + diagnosticsId + " surface_destroyed"
					+ " updates=" + surfaceUpdates);
			releasePlayerSurface();
			return true;
		}

		@Override
		public void onSurfaceTextureSizeChanged(SurfaceTexture surface, int width, int height) {}

		@Override
		public void onSurfaceTextureUpdated(SurfaceTexture surface) {
			surfaceUpdates++;
			if (surfaceUpdates == 1 || surfaceUpdates == 30 || surfaceUpdates % 120 == 0) {
				VideoDiagnostics.recordUi("texture=" + diagnosticsId + " surface_updated"
						+ " count=" + surfaceUpdates + " view_size=" + getWidth() + "x" + getHeight()
						+ " visibility=" + getVisibility() + " alpha=" + getAlpha());
			}
		}

		@Override
		protected void onAttachedToWindow() {
			super.onAttachedToWindow();
			VideoDiagnostics.recordUi("texture=" + diagnosticsId + " attached"
					+ " visibility=" + getVisibility());
		}

		@Override
		protected void onDetachedFromWindow() {
			VideoDiagnostics.recordUi("texture=" + diagnosticsId + " detached"
					+ " updates=" + surfaceUpdates);
			releasePlayerSurface();
			super.onDetachedFromWindow();
		}

		@Override
		protected void onVisibilityChanged(View changedView, int visibility) {
			super.onVisibilityChanged(changedView, visibility);
			if (changedView == this) {
				VideoDiagnostics.recordUi("texture=" + diagnosticsId
						+ " visibility=" + visibility + " updates=" + surfaceUpdates);
			}
		}

		private void releasePlayerSurface() {
			Surface surface = playerSurface;
			if (surface != null) {
				playerSurface = null;
				surface.release();
				VideoDiagnostics.recordUi("texture=" + diagnosticsId + " surface_released"
						+ " native_replacement_deferred=true");
			}
		}

		public Bitmap getCurrentFrame() {
			if (isAvailable() && surfaceUpdates > 0 && getWidth() > 0 && getHeight() > 0) {
				try {
					Bitmap bitmap = getBitmap();
					if (bitmap != null) {
						VideoDiagnostics.recordUi("texture=" + diagnosticsId
								+ " snapshot=" + bitmap.getWidth() + "x" + bitmap.getHeight());
					}
					return bitmap;
				} catch (RuntimeException e) {
					VideoDiagnostics.recordUi("texture=" + diagnosticsId
							+ " snapshot_failed=" + e.getClass().getSimpleName());
				}
			}
			return null;
		}
	}

	private View videoView;

	public View getVideoView(Context context) {
		if (videoView == null) {
			videoView = new PlayerTextureView(context, this);
		}
		return videoView;
	}

	public void releaseVideoView() {
		View videoView = this.videoView;
		this.videoView = null;
		if (videoView instanceof PlayerTextureView) {
			((PlayerTextureView) videoView).releasePlayerSurface();
		}
		if (videoView != null && videoView.getParent() instanceof ViewGroup) {
			((ViewGroup) videoView.getParent()).removeView(videoView);
		}
	}

	public void releaseVideoViewAndDestroyAsync() {
		VideoDiagnostics.recordUi("player_release_and_destroy scheduled");
		releaseVideoView();
		ConcurrentUtils.SEPARATE_EXECUTOR.execute(() -> {
			VideoDiagnostics.recordUi("player_release_and_destroy native_destroy_started");
			destroy();
		});
	}

	private void setSurface(Surface surface) {
		synchronized (this) {
			if (isInitialized()) {
				boolean playing = isPlaying();
				if (playing) {
					setPlaying(false);
				}
				long position = holder.getPosition(sessionData.pointer);
				boolean decoderReset = holder.setSurface(sessionData.pointer, surface);
				if (decoderReset) {
					setPosition(position);
				}
				if (playing) {
					setPlaying(true);
				}
			}
		}
	}

	public void setPlaying(boolean playing) {
		synchronized (this) {
			if (isInitialized() && this.playing != playing) {
				SeekToPosition seekToPosition = this.seekToPosition;
				cancelSetPositionLocked();
				this.playing = playing;
				if (playing) {
					holder.setPlaying(sessionData.pointer, true);
					if (seekToPosition != null) {
						setPosition(seekToPosition.position);
					}
				} else {
					holder.setPlaying(sessionData.pointer, false);
					// Restore value after cancelSetPosition
					this.seekToPosition = seekToPosition != null
							? new SeekToPosition(seekToPosition.position, false) : null;
				}
			}
		}
	}

	public void setPlaybackSpeed(int speed) {
		if (speed < 100) {
			speed = 100;
		} else if (speed > 4000) {
			speed = 4000;
		}
		synchronized (this) {
			playbackSpeed = speed;
			applyPlaybackSpeedLocked();
		}
	}

	public boolean setMuted(boolean muted) {
		synchronized (this) {
			if (isInitialized()) {
				try {
					return holder.setMuted(sessionData.pointer, muted);
				} catch (RuntimeException | LinkageError e) {
					return false;
				}
			}
			return false;
		}
	}

	private void applyPlaybackSpeedLocked() {
		if (isInitialized() && playbackSpeedSupported) {
			try {
				holder.setPlaybackSpeed(sessionData.pointer, playbackSpeed);
			} catch (RuntimeException | LinkageError e) {
				playbackSpeedSupported = false;
			}
		}
	}

	public boolean isPlaying() {
		synchronized (this) {
			return isInitialized() && playing;
		}
	}

	private final int[] dimensionsOutput = new int[2];

	public Bitmap getCurrentFrame() {
		synchronized (this) {
			if (isInitialized()) {
				View videoView = this.videoView;
				if (videoView instanceof PlayerTextureView) {
					Bitmap bitmap = ((PlayerTextureView) videoView).getCurrentFrame();
					if (bitmap != null) {
						return bitmap;
					}
				}
				int[] dimensions = dimensionsOutput;
				int[] frame = holder.getCurrentFrame(sessionData.pointer, dimensions);
				if (frame != null) {
					try {
						return Bitmap.createBitmap(frame, dimensions[0], dimensions[1], Bitmap.Config.ARGB_8888);
					} catch (Exception e) {
						// Ignore exception
					}
				}
			}
			return null;
		}
	}

	public Map<String, String> getMetadata() {
		synchronized (this) {
			if (isInitialized()) {
				String[] array = holder.getMetadata(sessionData.pointer);
				if (array != null) {
					HashMap<String, String> result = new HashMap<>();
					for (int i = 0; i < array.length; i += 2) {
						String key = array[i];
						String value = array[i + 1];
						if (key != null && value != null) {
							result.put(key, value);
						}
					}
					result.put("player_build", BuildConfig.NATIVE_PLAYER_FFMPEG_FLAVOR
							+ " / " + joinArray(BuildConfig.NATIVE_PLAYER_ABIS));
					result.put("device_abis", joinArray(Build.SUPPORTED_ABIS));
					return result;
				}
			}
			return Collections.emptyMap();
		}
	}

	public void destroy() {
		destroyInternal(null);
	}

	public void destroyAsync() {
		ConcurrentUtils.SEPARATE_EXECUTOR.execute(this::destroy);
	}

	private void destroyInternal(SessionData preInitSessionData) {
		synchronized (this) {
			if (!consumed) {
				consumed = true;
				if (initData != null) {
					holder.destroy(initData.pointer, true);
				}
				SessionData sessionData = preInitSessionData != null ? preInitSessionData : this.sessionData;
				if (sessionData != null) {
					cancelSetPositionLocked();
					synchronized (seekerThread) {
						seekerThread.notifyAll();
					}
					holder.destroy(sessionData.pointer, false);
				}
			}
		}
	}

	@Override
	protected void finalize() throws Throwable {
		try {
			destroy();
		} finally {
			super.finalize();
		}
	}

	private void onSeekingBufferingStateChange(boolean seekingChange, boolean bufferingChange) {
		if (seekingChange && lastBuffering) {
			return;
		}
		if (bufferingChange && lastSeeking) {
			return;
		}
		Listener listener = this.listener;
		if (listener != null && !consumed) {
			listener.onBusyStateChange(this, lastSeeking || lastBuffering);
		}
	}

	private enum Message {PLAYBACK_COMPLETE, SIZE_CHANGED, START_SEEKING, END_SEEKING, START_BUFFERING, END_BUFFERING,
		RETRY_SET_POSITION, REQUEST_RANGE}

	private final Handler handler = new Handler(Looper.getMainLooper(), msg -> {
		switch (Message.values()[msg.what]) {
			case PLAYBACK_COMPLETE: {
				onComplete();
				return true;
			}
			case SIZE_CHANGED: {
				onDimensionChange();
				return true;
			}
			case START_SEEKING:
			case END_SEEKING: {
				boolean seeking = msg.what == Message.START_SEEKING.ordinal();
				if (lastSeeking != seeking) {
					lastSeeking = seeking;
					onSeekingBufferingStateChange(true, false);
				}
				return true;
			}
			case START_BUFFERING:
			case END_BUFFERING: {
				boolean buffering = msg.what == Message.START_BUFFERING.ordinal();
				if (lastBuffering != buffering) {
					lastBuffering = buffering;
					onSeekingBufferingStateChange(false, true);
				}
				return true;
			}
			case RETRY_SET_POSITION: {
				long position = (long) msg.obj;
				// Sometimes player hangs during setPosition (ffmpeg seek too far away from real position)
				// I can do nothing better than repeat seeking
				setPosition(position);
				return true;
			}
			case REQUEST_RANGE: {
				long position = (long) msg.obj;
				requestRange(position);
				return true;
			}
		}
		return false;
	});

	private static class SeekToPosition {
		public final long position;
		public final boolean allow;

		public SeekToPosition(long position, boolean allow) {
			this.position = position;
			this.allow = allow;
		}
	}

	private SeekToPosition seekToPosition = null;
	private final Semaphore seekerMutex = new Semaphore(1);
	private final Thread seekerThread = new Thread(this::seekerThread);

	private void seekerThread() {
		Thread seekerThread = Thread.currentThread();
		while (true) {
			long position;
			synchronized (seekerThread) {
				SeekToPosition seekToPosition;
				while (true) {
					seekToPosition = this.seekToPosition;
					if ((seekToPosition == null || !seekToPosition.allow) && !consumed) {
						try {
							seekerThread.wait();
						} catch (InterruptedException e) {
							// Uninterruptible wait
						}
					} else {
						break;
					}
				}
				if (consumed) {
					return;
				}
				if (sessionData != null) {
					position = seekToPosition.position;
					seekerMutex.acquireUninterruptibly();
				} else {
					continue;
				}
			}
			try {
				handler.removeMessages(Message.END_BUFFERING.ordinal());
				handler.sendEmptyMessageDelayed(Message.START_BUFFERING.ordinal(), 200);
				handler.sendMessageDelayed(handler.obtainMessage
						(Message.RETRY_SET_POSITION.ordinal(), position), 1000);
				holder.setPosition(sessionData.pointer, position);
				handler.removeMessages(Message.RETRY_SET_POSITION.ordinal());
				handler.removeMessages(Message.START_BUFFERING.ordinal());
				handler.sendEmptyMessageDelayed(Message.END_BUFFERING.ordinal(), 100);
			} finally {
				seekToPosition = null;
				seekerMutex.release();
			}
		}
	}

	@SuppressWarnings("unused")
	private static class NativeBridge {
		private static final int BRIDGE_MESSAGE_PLAYBACK_COMPLETE = 1;
		private static final int BRIDGE_MESSAGE_SIZE_CHANGED = 2;
		private static final int BRIDGE_MESSAGE_START_SEEKING = 3;
		private static final int BRIDGE_MESSAGE_END_SEEKING = 4;

		private final WeakReference<VideoPlayer> player;

		public NativeBridge(VideoPlayer player) {
			this.player = new WeakReference<>(player);
		}

		public void onSeek(long position) {
			VideoPlayer player = this.player.get();
			if (player != null) {
				player.handler.removeMessages(Message.REQUEST_RANGE.ordinal());
				player.handler.obtainMessage(Message.REQUEST_RANGE.ordinal(), position).sendToTarget();
			}
		}

		public void onMessage(int what) {
			VideoPlayer player = this.player.get();
			if (player != null) {
				switch (what) {
					case BRIDGE_MESSAGE_PLAYBACK_COMPLETE: {
						player.handler.sendEmptyMessageDelayed(Message.PLAYBACK_COMPLETE.ordinal(), 200);
						break;
					}
					case BRIDGE_MESSAGE_SIZE_CHANGED: {
						player.handler.sendEmptyMessage(Message.SIZE_CHANGED.ordinal());
						break;
					}
					case BRIDGE_MESSAGE_START_SEEKING: {
						player.handler.removeMessages(Message.END_SEEKING.ordinal());
						if (player.lastBuffering) {
							player.handler.sendEmptyMessage(Message.START_SEEKING.ordinal());
						} else {
							player.handler.sendEmptyMessageDelayed(Message.START_SEEKING.ordinal(), 500);
						}
						break;
					}
					case BRIDGE_MESSAGE_END_SEEKING: {
						player.handler.removeMessages(Message.START_SEEKING.ordinal());
						player.handler.sendEmptyMessage(Message.END_SEEKING.ordinal());
						break;
					}
				}
			}
		}
	}

	private interface HolderInterface {
		long preInit(int fd);
		void init(long pointer, Object nativeBridge, boolean seekAnyFrame);
		void destroy(long pointer, boolean initOnly);

		int getErrorCode(long pointer);
		void getSummary(long pointer, int[] output);

		long getDuration(long pointer);
		long getPosition(long pointer);
		void setPosition(long pointer, long position);

		void setRange(long pointer, long start, long end, long total);
		void setCancelSeek(long pointer, boolean cancelSeek);

		void setAudioEnabled(long pointer, boolean audioEnabled);
		void setHardwareAcceleration(long pointer, boolean hardwareAcceleration);
		void setPlaybackSpeed(long pointer, int speed);
		boolean setMuted(long pointer, boolean muted);
		boolean setSurface(long pointer, Surface surface);
		void setPlaying(long pointer, boolean playing);

		int[] getCurrentFrame(long pointer, int[] dimensions);
		String[] getMetadata(long pointer);
		void startDiagnostics();
		String stopDiagnostics();
	}

	private static class Holder implements HolderInterface, InvocationHandler {
		// Extracted via reflection
		@SuppressWarnings("unused")
		private static final Holder INSTANCE = new Holder();

		private final Map<String, Method> methods;

		private Holder() {
			HashMap<String, Method> methods = new HashMap<>();
			// Collect all native methods declared in interface for faster access
			for (Method method : HolderInterface.class.getMethods()) {
				// Assume there are no overloaded methods
				methods.put(method.getName(), method);
			}
			this.methods = Collections.unmodifiableMap(methods);
		}

		@Override
		public Object invoke(Object proxy, Method method, Object[] args) throws Exception {
			// Class loader bridge
			return methods.get(method.getName()).invoke(this, args);
		}

		@Override public native long preInit(int fd);
		@Override public native void init(long pointer, Object nativeBridge, boolean seekAnyFrame);
		@Override public native void destroy(long pointer, boolean initOnly);

		@Override public native int getErrorCode(long pointer);
		@Override public native void getSummary(long pointer, int[] output);

		@Override public native long getDuration(long pointer);
		@Override public native long getPosition(long pointer);
		@Override public native void setPosition(long pointer, long position);

		@Override public native void setRange(long pointer, long start, long end, long total);
		@Override public native void setCancelSeek(long pointer, boolean busy);

		@Override public native void setAudioEnabled(long pointer, boolean audioEnabled);
		@Override public native void setHardwareAcceleration(long pointer, boolean hardwareAcceleration);
		@Override public native void setPlaybackSpeed(long pointer, int speed);
		@Override public native boolean setMuted(long pointer, boolean muted);
		@Override public native boolean setSurface(long pointer, Surface surface);
		@Override public native void setPlaying(long pointer, boolean playing);

		@Override public native int[] getCurrentFrame(long pointer, int[] dimensions);
		@Override public native String[] getMetadata(long pointer);
		@Override public native void startDiagnostics();
		@Override public native String stopDiagnostics();

		static {
			try {
				System.loadLibrary("dav1d");
			} catch (UnsatisfiedLinkError ignored) {
				// Optional in older WebM library builds.
			}
			System.loadLibrary("avutil");
			System.loadLibrary("swresample");
			System.loadLibrary("swscale");
			System.loadLibrary("avcodec");
			System.loadLibrary("avformat");
			if (BUNDLED_ATEMPO) {
				System.loadLibrary("avfilter");
			}
			System.loadLibrary("yuv");
			System.loadLibrary("player");
		}
	}

	private static class NativeLibraryPlan {
		public final String dexPath;
		public final String librarySearchPath;
		private final String description;

		private NativeLibraryPlan(String dexPath, String librarySearchPath, String description) {
			this.dexPath = dexPath;
			this.librarySearchPath = librarySearchPath;
			this.description = description;
		}

		public String describe() {
			return description;
		}

		public static NativeLibraryPlan createBundled(Context context)
				throws IOException {
			ApplicationInfo applicationInfo = context.getApplicationInfo();
			String applicationSourceDir = requirePath(applicationInfo.sourceDir, "Application APK is unavailable");
			File applicationSourceFile = requireSourceFile("app", applicationSourceDir);
			try (ZipFile applicationZipFile = new ZipFile(applicationSourceFile)) {
				String abi = findBundledAbi(applicationZipFile);
				if (abi == null) {
					throw new IOException(createBundledAbiError(applicationZipFile));
				}
				File bundledExtractedDir = extractLibraries(context, "bundled-webm", applicationZipFile,
						applicationSourceFile, context.getPackageName(), BuildConfig.VERSION_CODE, abi,
						BUNDLED_REQUIRED_LIBRARIES, null);
				cleanupStaleNativeCache(context, bundledExtractedDir);
				String librarySearchPath = bundledExtractedDir.getPath();
				StringBuilder description = new StringBuilder();
				description.append("bundledWebm=true");
				description.append("\ndexPath=").append(applicationSourceDir);
				description.append("\nlibrarySearchPath=").append(librarySearchPath);
				description.append("\nplayerBuild=").append(BuildConfig.NATIVE_PLAYER_FFMPEG_FLAVOR)
						.append(" / ").append(joinArray(BuildConfig.NATIVE_PLAYER_ABIS));
				description.append("\ndeviceAbis=").append(joinArray(Build.SUPPORTED_ABIS));
				description.append("\nselectedAbi=").append(abi);
				description.append("\nappAvailableAbis=").append(describeAvailableAbis(applicationZipFile));
				description.append("\nbundledWebmMissing=")
						.append(StringUtils.emptyIfNull(describeMissingLibraries(applicationZipFile,
								abi, BUNDLED_REQUIRED_LIBRARIES)));
				description.append("\nbundledExtracted=").append(bundledExtractedDir.getPath());
				return new NativeLibraryPlan(applicationSourceDir, librarySearchPath, description.toString());
			}
		}

		public static NativeLibraryPlan createExternal(Context context, ChanManager.ExtensionItem extensionItem)
				throws IOException {
			ApplicationInfo applicationInfo = context.getApplicationInfo();
			String applicationSourceDir = requirePath(applicationInfo.sourceDir, "Application APK is unavailable");
			String extensionSourceDir = requirePath(extensionItem.getSourceDir(),
					"WebM2 extension APK is unavailable: " + extensionItem.packageName);
			File applicationSourceFile = requireSourceFile("app", applicationSourceDir);
			File extensionSourceFile = requireSourceFile("webm", extensionSourceDir);
			LinkedHashSet<String> dexPaths = new LinkedHashSet<>();
			dexPaths.add(applicationSourceDir);
			dexPaths.add(extensionSourceDir);
			LinkedHashSet<String> libraryPaths = new LinkedHashSet<>();
			try (ZipFile applicationZipFile = new ZipFile(applicationSourceFile);
					ZipFile extensionZipFile = new ZipFile(extensionSourceFile)) {
				String abi = findCommonAbi(applicationZipFile, extensionZipFile);
				if (abi == null) {
					throw new IOException(createCommonAbiError(applicationZipFile, extensionZipFile));
				}
				File applicationExtractedDir = extractLibraries(context, "app", applicationZipFile,
						applicationSourceFile, context.getPackageName(), BuildConfig.VERSION_CODE, abi,
						PLAYER_LIBRARIES, null);
				File extensionExtractedDir = extractLibraries(context, "webm", extensionZipFile,
						extensionSourceFile, extensionItem.packageName, extensionItem.versionCode, abi,
						WEBM_REQUIRED_LIBRARIES, WEBM_OPTIONAL_LIBRARIES);
				cleanupStaleNativeCache(context, applicationExtractedDir, extensionExtractedDir);
				libraryPaths.add(applicationExtractedDir.getPath());
				libraryPaths.add(extensionExtractedDir.getPath());
				String librarySearchPath = joinPaths(libraryPaths);
				StringBuilder description = new StringBuilder();
				description.append("bundledWebm=false");
				description.append("\nexternalWebm=true");
				description.append("\ndexPath=").append(joinPaths(dexPaths));
				description.append("\nlibrarySearchPath=").append(librarySearchPath);
				description.append("\nplayerBuild=").append(BuildConfig.NATIVE_PLAYER_FFMPEG_FLAVOR)
						.append(" / ").append(joinArray(BuildConfig.NATIVE_PLAYER_ABIS));
				description.append("\ndeviceAbis=").append(joinArray(Build.SUPPORTED_ABIS));
				description.append("\nselectedAbi=").append(abi);
				description.append("\nappAvailableAbis=").append(describeAvailableAbis(applicationZipFile));
				description.append("\nwebmAvailableAbis=").append(describeAvailableAbis(extensionZipFile));
				description.append("\nappExtracted=").append(applicationExtractedDir.getPath());
				description.append("\nwebmExtracted=").append(extensionExtractedDir.getPath());
				return new NativeLibraryPlan(joinPaths(dexPaths), librarySearchPath, description.toString());
			}
		}

		private static String requirePath(String path, String message) throws IOException {
			if (StringUtils.isEmpty(path)) {
				throw new IOException(message);
			}
			return path;
		}

		private static File requireSourceFile(String label, String sourceDir) throws IOException {
			File sourceFile = new File(sourceDir);
			if (!sourceFile.isFile()) {
				throw new IOException(label + " APK is unavailable: " + sourceDir);
			}
			return sourceFile;
		}

		private static String joinPaths(Iterable<String> paths) {
			StringBuilder builder = new StringBuilder();
			for (String path : paths) {
				if (StringUtils.isEmpty(path)) {
					continue;
				}
				if (builder.length() > 0) {
					builder.append(File.pathSeparatorChar);
				}
				builder.append(path);
			}
			return builder.toString();
		}

		private static File extractLibraries(Context context, String label, ZipFile zipFile, File sourceFile,
				String packageName, long versionCode, String abi, String[] requiredLibraries,
				String[] optionalLibraries) throws IOException {
			File targetDir = new File(getNativeCacheDir(context), sanitize(label + "-" + packageName)
					+ "-" + versionCode + "-" + sourceFile.lastModified() + "-" + abi);
			if (!isExtractionComplete(targetDir, requiredLibraries)) {
				deleteRecursively(targetDir);
				if (!targetDir.mkdirs() && !targetDir.isDirectory()) {
					throw new IOException("Unable to create native library cache: " + targetDir.getPath());
				}
				extractLibrarySet(zipFile, abi, targetDir, requiredLibraries, true);
				extractLibrarySet(zipFile, abi, targetDir, optionalLibraries, false);
				File marker = new File(targetDir, ".complete");
				if (!marker.createNewFile() && !marker.isFile()) {
					throw new IOException("Unable to mark native library cache complete: " + targetDir.getPath());
				}
			}
			return targetDir;
		}

		private static File getNativeCacheDir(Context context) {
			File cacheDir = context.getCodeCacheDir();
			if (cacheDir == null) {
				cacheDir = context.getCacheDir();
			}
			return new File(cacheDir, "native-player");
		}

		private static String findBundledAbi(ZipFile applicationZipFile) {
			for (String abi : Build.SUPPORTED_ABIS) {
				if (hasLibraries(applicationZipFile, abi, BUNDLED_REQUIRED_LIBRARIES)) {
					return abi;
				}
			}
			return null;
		}

		private static String findCommonAbi(ZipFile applicationZipFile, ZipFile extensionZipFile) {
			for (String abi : Build.SUPPORTED_ABIS) {
				if (hasLibraries(applicationZipFile, abi, PLAYER_LIBRARIES) &&
						hasLibraries(extensionZipFile, abi, WEBM_REQUIRED_LIBRARIES)) {
					return abi;
				}
			}
			return null;
		}

		private static boolean hasLibraries(ZipFile zipFile, String abi, String[] libraries) {
			for (String library : libraries) {
				if (zipFile.getEntry(getEntryName(abi, library)) == null) {
					return false;
				}
			}
			return true;
		}

		private static String createBundledAbiError(ZipFile applicationZipFile) {
			StringBuilder builder = new StringBuilder();
			builder.append("Missing bundled native ABI for video player");
			builder.append(": device=").append(joinArray(Build.SUPPORTED_ABIS));
			builder.append(", appApk=").append(describeAvailableAbis(applicationZipFile));
			builder.append(", playerBuild=").append(BuildConfig.NATIVE_PLAYER_FFMPEG_FLAVOR)
					.append(" / ").append(joinArray(BuildConfig.NATIVE_PLAYER_ABIS));
			for (String abi : Build.SUPPORTED_ABIS) {
				String bundledMissing = describeMissingLibraries(applicationZipFile, abi, BUNDLED_REQUIRED_LIBRARIES);
				if (!StringUtils.isEmpty(bundledMissing)) {
					builder.append("\n").append(abi).append(": bundledMissing=").append(bundledMissing);
				}
			}
			return builder.toString();
		}

		private static String createCommonAbiError(ZipFile applicationZipFile, ZipFile extensionZipFile) {
			StringBuilder builder = new StringBuilder();
			builder.append("Missing common native ABI for video player");
			builder.append(": device=").append(joinArray(Build.SUPPORTED_ABIS));
			builder.append(", appApk=").append(describeAvailableAbis(applicationZipFile));
			builder.append(", webmApk=").append(describeAvailableAbis(extensionZipFile));
			for (String abi : Build.SUPPORTED_ABIS) {
				String appMissing = describeMissingLibraries(applicationZipFile, abi, PLAYER_LIBRARIES);
				String webmMissing = describeMissingLibraries(extensionZipFile, abi, WEBM_REQUIRED_LIBRARIES);
				if (!StringUtils.isEmpty(appMissing) || !StringUtils.isEmpty(webmMissing)) {
					builder.append("\n").append(abi).append(": appMissing=")
							.append(StringUtils.emptyIfNull(appMissing));
					builder.append(", webmMissing=").append(StringUtils.emptyIfNull(webmMissing));
				}
			}
			return builder.toString();
		}

		private static String describeMissingLibraries(ZipFile zipFile, String abi, String[] libraries) {
			StringBuilder builder = new StringBuilder();
			for (String library : libraries) {
				if (zipFile.getEntry(getEntryName(abi, library)) == null) {
					if (builder.length() > 0) {
						builder.append(", ");
					}
					builder.append("lib").append(library).append(".so");
				}
			}
			return builder.toString();
		}

		private static String describeAvailableAbis(ZipFile zipFile) {
			LinkedHashSet<String> abis = new LinkedHashSet<>();
			Enumeration<? extends ZipEntry> entries = zipFile.entries();
			while (entries.hasMoreElements()) {
				String name = entries.nextElement().getName();
				if (name.startsWith("lib/") && name.endsWith(".so")) {
					int start = "lib/".length();
					int end = name.indexOf('/', start);
					if (end > start) {
						abis.add(name.substring(start, end));
					}
				}
			}
			return abis.isEmpty() ? "none" : joinArray(abis.toArray(new String[0]));
		}

		private static void cleanupStaleNativeCache(Context context, File... keepDirs) throws IOException {
			File cacheDir = getNativeCacheDir(context);
			File[] children = cacheDir.listFiles();
			if (children == null) {
				return;
			}
			LinkedHashSet<String> keepPaths = new LinkedHashSet<>();
			for (File keepDir : keepDirs) {
				keepPaths.add(keepDir.getCanonicalPath());
			}
			for (File child : children) {
				if (!keepPaths.contains(child.getCanonicalPath())) {
					deleteRecursively(child);
				}
			}
		}

		private static boolean isExtractionComplete(File targetDir, String[] requiredLibraries) {
			if (!new File(targetDir, ".complete").isFile()) {
				return false;
			}
			for (String library : requiredLibraries) {
				if (!new File(targetDir, "lib" + library + ".so").isFile()) {
					return false;
				}
			}
			return true;
		}

		private static void extractLibrarySet(ZipFile zipFile, String abi, File targetDir,
				String[] libraries, boolean required) throws IOException {
			if (libraries == null) {
				return;
			}
			for (String library : libraries) {
				ZipEntry entry = zipFile.getEntry(getEntryName(abi, library));
				if (entry == null) {
					if (required) {
						throw new IOException("Missing native library: " + library + " for " + abi);
					}
				} else {
					extractLibrary(zipFile, entry, new File(targetDir, "lib" + library + ".so"));
				}
			}
		}

		private static String getEntryName(String abi, String library) {
			return "lib/" + abi + "/lib" + library + ".so";
		}

		private static void extractLibrary(ZipFile zipFile, ZipEntry entry, File targetFile) throws IOException {
			try (InputStream input = new BufferedInputStream(zipFile.getInputStream(entry));
					OutputStream output = new BufferedOutputStream(new FileOutputStream(targetFile))) {
				byte[] buffer = new byte[64 * 1024];
				int count;
				while ((count = input.read(buffer)) != -1) {
					output.write(buffer, 0, count);
				}
			}
			targetFile.setReadable(true, true);
		}

		private static void deleteRecursively(File file) throws IOException {
			if (!file.exists()) {
				return;
			}
			if (file.isDirectory()) {
				File[] files = file.listFiles();
				if (files != null) {
					for (File child : files) {
						deleteRecursively(child);
					}
				}
			}
			if (!file.delete() && file.exists()) {
				throw new IOException("Unable to delete stale native cache file: " + file.getPath());
			}
		}

		private static String sanitize(String value) {
			return value.replaceAll("[^a-zA-Z0-9._-]", "_");
		}

	}
}
