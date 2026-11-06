package com.mishiranu.dashchan.media;

import static org.junit.Assert.*;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Proxy;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.Test;
import org.junit.runner.RunWith;

/** Fake JNI holder; run serially on a disposable install with no real player sessions. */
@RunWith(AndroidJUnit4.class)
public class VideoPlayerReadLifetimeTest {
	private static Field field(String name) throws Exception {
		Field field = VideoPlayer.class.getDeclaredField(name);
		field.setAccessible(true);
		return field;
	}

	@Test public void closingWaitsForExistingReadersAndNewGettersDoNotWaitForDestroy() throws Exception {
		Field holderField = field("holder");
		Object original = holderField.get(null);
		CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1);
		AtomicBoolean active = new AtomicBoolean(), destroyed = new AtomicBoolean();
		AtomicReference<Throwable> failure = new AtomicReference<>();
		VideoPlayer player = new VideoPlayer(null, false);
		Object fake = Proxy.newProxyInstance(VideoPlayer.class.getClassLoader(),
				new Class<?>[] {holderField.getType()}, (proxy, method, args) -> {
			if (method.getName().equals("getDuration")) {
				assertFalse(destroyed.get());
				active.set(true); entered.countDown();
				try { assertTrue(release.await(5, TimeUnit.SECONDS)); return 50L; }
				finally { active.set(false); }
			}
			if (method.getName().equals("destroy")) { assertFalse(active.get()); destroyed.set(true); }
			Class<?> type = method.getReturnType();
			if (type == long.class) return 0L;
			if (type == int.class) return 0;
			if (type == boolean.class) return false;
			return null;
		});
		Class<?> sessionClass = Class.forName(VideoPlayer.class.getName() + "$SessionData");
		Constructor<?> constructor = sessionClass.getDeclaredConstructor(long.class, VideoPlayer.RangeCallback.class);
		constructor.setAccessible(true);
		field("sessionData").set(player, constructor.newInstance(1L, null));
		holderField.set(null, fake);
		Thread reader = new Thread(() -> {
			try { assertEquals(50L, player.getDuration()); } catch (Throwable e) { failure.set(e); }
		});
		Thread closer = new Thread(() -> {
			try { player.close(); } catch (Throwable e) { failure.set(e); }
		});
		try {
			reader.start(); assertTrue(entered.await(2, TimeUnit.SECONDS));
			closer.start();
			long deadline = android.os.SystemClock.uptimeMillis() + 2000;
			while (!(boolean) field("closing").get(player) && android.os.SystemClock.uptimeMillis() < deadline) {
				Thread.sleep(1);
			}
			assertTrue((boolean) field("closing").get(player));
			assertFalse(destroyed.get());
			assertEquals(0L, player.getDuration());
			assertEquals(0L, player.getPosition());
			assertFalse(player.isAudioPresent());
		} finally {
			release.countDown(); reader.join(3000); closer.join(3000);
			if (!reader.isAlive() && !closer.isAlive()) holderField.set(null, original);
		}
		assertFalse(reader.isAlive()); assertFalse(closer.isAlive());
		assertNull(failure.get()); assertTrue(destroyed.get());
	}
}
