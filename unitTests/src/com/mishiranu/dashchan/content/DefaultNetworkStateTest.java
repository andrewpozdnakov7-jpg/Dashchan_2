package com.mishiranu.dashchan.content;

import static org.junit.Assert.*;
import org.junit.Test;

public class DefaultNetworkStateTest {
	@Test public void bootstrapProvidesStateUntilTheFirstCallback() {
		DefaultNetworkState<String, String> state = new DefaultNetworkState<>("offline");
		assertNull(state.get());
		assertTrue(state.initialize(state.revision(), "wifi", "wifi+vpn"));
		state.available("wifi");
		assertEquals("wifi+vpn", state.get());
		assertTrue(state.capabilities("wifi", "wifi"));
		assertEquals("wifi", state.get());
	}

	@Test public void newDefaultNeverKeepsTheOldTransportFlags() {
		DefaultNetworkState<String, String> state = new DefaultNetworkState<>("offline");
		state.available("wifi");
		state.capabilities("wifi", "wifi+vpn");
		state.available("mobile");
		assertNull(state.get());
		assertTrue(state.capabilities("mobile", "cellular"));
		assertEquals("cellular", state.get());
	}

	@Test public void lateEventsFromThePreviousNetworkDoNotReplaceTheNewDefault() {
		DefaultNetworkState<String, String> state = new DefaultNetworkState<>("offline");
		state.available("wifi");
		state.available("mobile");
		state.capabilities("mobile", "cellular");
		assertFalse(state.lost("wifi"));
		assertFalse(state.capabilities("wifi", "wifi+vpn"));
		assertEquals("cellular", state.get());
	}

	@Test public void lossClearsStateAndLateCapabilitiesCannotResurrectIt() {
		DefaultNetworkState<String, String> state = new DefaultNetworkState<>("offline");
		state.available("wifi");
		state.capabilities("wifi", "wifi+vpn");
		assertTrue(state.lost("wifi"));
		assertEquals("offline", state.get());
		assertFalse(state.capabilities("wifi", "wifi+vpn"));
		assertEquals("offline", state.get());
		state.available("new-wifi");
		state.capabilities("new-wifi", "wifi");
		assertEquals("wifi", state.get());
	}

	@Test public void synchronousAnswerCannotOverwriteAConcurrentNetworkEvent() {
		DefaultNetworkState<String, String> state = new DefaultNetworkState<>("offline");
		long revision = state.revision();
		state.available("mobile");
		state.capabilities("mobile", "cellular");
		assertFalse(state.initialize(revision, "wifi", "wifi+vpn"));
		assertEquals("cellular", state.get());
	}

	@Test public void fallbackCannotSeedAnAlreadySelectedDifferentNetwork() {
		DefaultNetworkState<String, String> state = new DefaultNetworkState<>("offline");
		state.available("mobile");
		assertFalse(state.initialize(state.revision(), "wifi", "wifi+vpn"));
		assertFalse(state.initialize(state.revision(), null, "offline"));
		assertNull(state.get());
		assertTrue(state.initialize(state.revision(), "mobile", "cellular"));
		assertEquals("cellular", state.get());
	}

	@Test public void offlineBootstrapDoesNotPreventLaterConnection() {
		DefaultNetworkState<String, String> state = new DefaultNetworkState<>("offline");
		assertTrue(state.initialize(state.revision(), null, "offline"));
		state.available("wifi");
		assertNull(state.get());
		state.capabilities("wifi", "wifi");
		assertEquals("wifi", state.get());
	}
}
