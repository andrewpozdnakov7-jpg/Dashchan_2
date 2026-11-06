package com.mishiranu.dashchan.media;

import static org.junit.Assert.*;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.Test;
import org.junit.runner.RunWith;

/** Exercises the real native-message handler without loading a codec or allocating native frames. */
@RunWith(AndroidJUnit4.class)
public class VideoPlayerFailureDeliveryTest {
	private static VideoPlayer.Listener listener(AtomicInteger errors) {
		return new VideoPlayer.Listener() {
			@Override public void onError(VideoPlayer player) { errors.incrementAndGet(); }
			@Override public void onComplete(VideoPlayer player) { fail("Failure must not be reported as EOF"); }
			@Override public void onBusyStateChange(VideoPlayer player, boolean busy) {}
			@Override public void onDimensionChange(VideoPlayer player) {}
		};
	}

	@Test public void duplicateErrorsAreCoalescedAndFailureFollowsTheListenerTransfer() throws Exception {
		AtomicInteger first = new AtomicInteger();
		AtomicInteger second = new AtomicInteger();
		VideoPlayer player = new VideoPlayer(listener(first), false);
		Class<?> bridgeClass = Class.forName(VideoPlayer.class.getName() + "$NativeBridge");
		Constructor<?> constructor = bridgeClass.getDeclaredConstructor(VideoPlayer.class);
		constructor.setAccessible(true);
		Object bridge = constructor.newInstance(player);
		Method message = bridgeClass.getDeclaredMethod("onMessage", int.class);
		message.setAccessible(true);
		try {
			message.invoke(bridge, 5);
			message.invoke(bridge, 5);
			InstrumentationRegistry.getInstrumentation().waitForIdleSync();
			assertEquals(1, first.get());
			player.setListener(listener(second));
			InstrumentationRegistry.getInstrumentation().waitForIdleSync();
			assertEquals(1, second.get());
			assertEquals(1, first.get());
		} finally {
			player.close();
		}
		message.invoke(bridge, 5);
		InstrumentationRegistry.getInstrumentation().waitForIdleSync();
		assertEquals(1, second.get());
	}
}
