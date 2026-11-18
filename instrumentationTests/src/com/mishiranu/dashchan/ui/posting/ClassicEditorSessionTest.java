package com.mishiranu.dashchan.ui.posting;

import static org.junit.Assert.*;

import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.os.SystemClock;
import android.util.AtomicFile;
import androidx.test.core.app.ActivityScenario;
import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import com.mishiranu.dashchan.content.model.FileHolder;
import com.mishiranu.dashchan.content.storage.DraftsStorage;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.junit.runner.RunWith;

@RunWith(AndroidJUnit4.class)
public class ClassicEditorSessionTest {
	@Rule public final TemporaryFolder files = new TemporaryFolder();

	@Test public void restoresLatestPublicationAcrossInterruptedWritesAndUndo() throws Exception {
		Class<?> snapshotType = Class.forName(ImageEditorActivity.class.getName() + "$EditorView$Snapshot");
		Constructor<?> snapshot = snapshotType.getDeclaredConstructor(Bitmap.class, Bitmap.class, ArrayList.class, long.class);
		Method write = ImageEditorActivity.class.getDeclaredMethod("writeSession", AtomicFile.class, String.class, snapshotType);
		Method read = ImageEditorActivity.class.getDeclaredMethod("readSession", AtomicFile.class, String.class);
		Method recycle = snapshotType.getDeclaredMethod("recycle");
		Field revision = snapshotType.getDeclaredField("revision");
		Field base = snapshotType.getDeclaredField("base");
		for (var member : new java.lang.reflect.AccessibleObject[] {snapshot, write, read, recycle, revision, base}) {
			member.setAccessible(true);
		}
		AtomicFile file = new AtomicFile(new File(files.getRoot(), "classic.zip"));
		interruptWrite(file);
		assertTrue(assertThrows(InvocationTargetException.class, () -> read.invoke(null, file, "attachment-a"))
				.getCause() instanceof IOException);
		file.delete();
		Bitmap image = Bitmap.createBitmap(2, 2, Bitmap.Config.ARGB_8888);
		Bitmap overlay = Bitmap.createBitmap(2, 2, Bitmap.Config.ARGB_8888);
		try {
			image.eraseColor(Color.RED);
			write.invoke(null, file, "attachment-a", snapshot.newInstance(image, overlay, new ArrayList<>(), 2L));
			interruptWrite(file);
			Object restored = read.invoke(null, file, "attachment-a");
			try {
				assertEquals(2L, revision.getLong(restored));
				assertEquals(Color.RED, ((Bitmap) base.get(restored)).getPixel(0, 0));
			} finally {
				recycle.invoke(restored);
			}
			image.eraseColor(Color.BLUE);
			write.invoke(null, file, "attachment-a", snapshot.newInstance(image, overlay, new ArrayList<>(), 5L));
			image.eraseColor(Color.RED);
			write.invoke(null, file, "attachment-a", snapshot.newInstance(image, overlay, new ArrayList<>(), 2L));
			restored = read.invoke(null, file, "attachment-a");
			try {
				assertEquals(2L, revision.getLong(restored));
				assertEquals(Color.RED, ((Bitmap) base.get(restored)).getPixel(0, 0));
			} finally {
				recycle.invoke(restored);
			}
			assertTrue(assertThrows(InvocationTargetException.class, () -> read.invoke(null, file, "attachment-b"))
					.getCause() instanceof IOException);
			try (FileOutputStream corrupt = new FileOutputStream(file.getBaseFile())) { corrupt.write(1); }
			assertTrue(assertThrows(InvocationTargetException.class, () -> read.invoke(null, file, "attachment-a"))
					.getCause() instanceof IOException);
		} finally {
			image.recycle();
			overlay.recycle();
		}
	}

	@Test public void latestPixelsSurviveRecreationWhileFirstWriteIsQueued() throws Exception {
		File input = files.newFile("session.png");
		Bitmap bitmap = Bitmap.createBitmap(3, 2, Bitmap.Config.ARGB_8888);
		try (FileOutputStream output = new FileOutputStream(input)) {
			bitmap.eraseColor(Color.BLUE);
			bitmap.setPixel(0, 0, Color.RED);
			// Make the attachment unique to this test before deleting it on exit.
			bitmap.setPixel(1, 0, Color.BLACK | (int) (System.nanoTime() & 0xffffff));
			assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, output));
		} finally {
			bitmap.recycle();
		}
		DraftsStorage drafts = DraftsStorage.getInstance();
		String hash = drafts.storeAttachmentFile(FileHolder.obtain(input));
		assertNotNull(hash);
		Context context = ApplicationProvider.getApplicationContext();
		Intent intent = new Intent(context, ImageEditorActivity.class).putExtra("sourceHash", hash)
				.putExtra("sourceName", "session.png").putExtra("attachmentIndex", 0);
		Field queue = ImageEditorActivity.class.getDeclaredField("SESSION_IO");
		queue.setAccessible(true);
		Executor io = (Executor) queue.get(null);
		Method save = ImageEditorActivity.class.getDeclaredMethod("persistSession");
		save.setAccessible(true);
		CountDownLatch release = new CountDownLatch(1);
		try (ActivityScenario<ImageEditorActivity> scenario = ActivityScenario.launch(intent)) {
			Object first = awaitCanvas(scenario);
			Method rotate = first.getClass().getDeclaredMethod("rotate");
			rotate.setAccessible(true);
			io.execute(() -> {
				try {
					release.await();
				} catch (InterruptedException e) {
					Thread.currentThread().interrupt();
				}
			});
			try {
				scenario.onActivity(activity -> {
					try {
						rotate.invoke(first);
						save.invoke(activity); // A is queued, and cannot publish before destruction.
						rotate.invoke(first);
					} catch (ReflectiveOperationException e) {
						throw new AssertionError(e);
					}
				});
				scenario.recreate();
			} finally {
				release.countDown();
			}
			Object restored = awaitCanvas(scenario);
			assertNotSame(first, restored);
			Field base = restored.getClass().getDeclaredField("baseBitmap");
			base.setAccessible(true);
			scenario.onActivity(activity -> {
				try {
					Bitmap result = (Bitmap) base.get(restored);
					assertEquals(3, result.getWidth());
					assertEquals(2, result.getHeight());
					assertEquals(Color.RED, result.getPixel(2, 1));
					assertEquals(Color.BLUE, result.getPixel(0, 0));
				} catch (IllegalAccessException e) {
					throw new AssertionError(e);
				}
			});
		} finally {
			File stored = drafts.getAttachmentDraftFile(hash);
			if (stored != null) stored.delete();
		}
	}

	private static Object awaitCanvas(ActivityScenario<ImageEditorActivity> scenario) throws Exception {
		Field editor = ImageEditorActivity.class.getDeclaredField("editorView");
		editor.setAccessible(true);
		AtomicReference<Object> result = new AtomicReference<>();
		long deadline = SystemClock.elapsedRealtime() + 10000L;
		do {
			scenario.onActivity(activity -> {
				try {
					result.set(editor.get(activity));
				} catch (IllegalAccessException e) {
					throw new AssertionError(e);
				}
			});
			if (result.get() != null) return result.get();
			SystemClock.sleep(20L);
		} while (SystemClock.elapsedRealtime() < deadline);
		throw new AssertionError("Classic editor did not load its canvas");
	}

	private static void interruptWrite(AtomicFile file) throws IOException {
		try (FileOutputStream interrupted = file.startWrite()) { interrupted.write(1); }
	}
}
