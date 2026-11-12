package com.mishiranu.dashchan.content;

import static org.junit.Assert.*;
import android.net.NetworkCapabilities;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import org.junit.Test;
import org.junit.runner.RunWith;

@RunWith(AndroidJUnit4.class)
public class NetworkSnapshotTest {
	@Test public void wifiAllowsAllExistingPreloadModes() {
		NetworkObserver.Snapshot snapshot = new NetworkObserver.Snapshot(true, true, true, false, false, true, false);
		assertTrue(snapshot.allowsPreload("all"));
		assertTrue(snapshot.allowsPreload("wifi_3g"));
		assertTrue(snapshot.allowsPreload("wifi"));
	}

	@Test public void mobileCannotPassWifiOnlyIncludingMixedVpnTransports() {
		NetworkObserver.Snapshot snapshot = new NetworkObserver.Snapshot(true, true, true, true, true, true, true);
		assertTrue(snapshot.vpn);
		assertTrue(snapshot.allowsPreload("all"));
		assertTrue(snapshot.allowsPreload("wifi_3g"));
		assertFalse(snapshot.allowsPreload("wifi"));
	}

	@Test public void vpnOnWifiStillAllowsWifiPreload() {
		NetworkObserver.Snapshot snapshot = new NetworkObserver.Snapshot(true, true, true, false, true, true, false);
		assertTrue(snapshot.vpn);
		assertTrue(snapshot.allowsPreload("wifi"));
	}

	@Test public void localOnlyAndDisconnectedNetworksCannotPreload() {
		NetworkObserver.Snapshot local = new NetworkObserver.Snapshot(true, false, true, false, false, false, false);
		assertFalse(local.allowsPreload("all"));
		assertFalse(local.allowsPreload("wifi"));
		NetworkObserver.Snapshot offline = new NetworkObserver.Snapshot(null);
		assertFalse(offline.available);
		assertFalse(offline.allowsPreload("all"));
		assertFalse(offline.allowsPreload("wifi_3g"));
	}

	@Test public void frameworkCapabilitiesAreReadIntoFinalPrimitiveFlags() {
		NetworkCapabilities capabilities = new NetworkCapabilities();
		NetworkObserver.Snapshot snapshot = new NetworkObserver.Snapshot(capabilities);
		assertTrue(snapshot.available);
		assertEquals(capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET), snapshot.internet);
		assertEquals(capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI), snapshot.wifi);
		assertEquals(capabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR), snapshot.cellular);
		assertEquals(capabilities.hasTransport(NetworkCapabilities.TRANSPORT_VPN), snapshot.vpn);
		assertEquals(capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED), snapshot.validated);
		assertEquals(!capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED), snapshot.metered);
		for (Field field : NetworkObserver.Snapshot.class.getDeclaredFields()) {
			if (!Modifier.isStatic(field.getModifiers())) {
				assertEquals(boolean.class, field.getType()); assertTrue(Modifier.isFinal(field.getModifiers()));
			}
		}
	}
}
