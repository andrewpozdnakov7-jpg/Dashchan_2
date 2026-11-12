package com.mishiranu.dashchan.content;

import android.content.Context;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.telephony.TelephonyManager;
import androidx.annotation.NonNull;
import com.mishiranu.dashchan.util.ConcurrentUtils;
import java.util.Locale;
import java.util.concurrent.CopyOnWriteArraySet;

public class NetworkObserver {
	private static final NetworkObserver INSTANCE = new NetworkObserver();

	public static NetworkObserver getInstance() {
		return INSTANCE;
	}

	/** Immutable, safely published to UI and worker threads. No mutable framework objects escape. */
	public static final class Snapshot {
		private static final Snapshot DISCONNECTED = new Snapshot(null);
		public final boolean available, internet, wifi, cellular, vpn, validated, metered;

		Snapshot(NetworkCapabilities capabilities) {
			this(capabilities != null,
					capabilities != null && capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET),
					capabilities != null && capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI),
					capabilities != null && capabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR),
					capabilities != null && capabilities.hasTransport(NetworkCapabilities.TRANSPORT_VPN),
					capabilities != null && capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED),
					capabilities == null || !capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED));
		}

		// Explicit flags also let policy fixtures avoid hidden NetworkCapabilities mutators.
		Snapshot(boolean available, boolean internet, boolean wifi, boolean cellular,
				boolean vpn, boolean validated, boolean metered) {
			this.available = available; this.internet = internet; this.wifi = wifi; this.cellular = cellular;
			this.vpn = vpn; this.validated = validated; this.metered = metered;
		}

		public boolean allowsPreload(String mode) {
			if (!internet) return false;
			if ("all".equals(mode) || "wifi_3g".equals(mode)) return true;
			return wifi && !cellular;
		}
	}

	private final ConnectivityManager connectivityManager;
	private final TelephonyManager telephonyManager;

	private final DefaultNetworkState<Network, Snapshot> state =
			new DefaultNetworkState<>(Snapshot.DISCONNECTED);
	private final CopyOnWriteArraySet<Runnable> listeners = new CopyOnWriteArraySet<>();
	private final boolean observing;

	private NetworkObserver() {
		Context context = MainApplication.getInstance();
		connectivityManager = (ConnectivityManager) context.getSystemService(Context.CONNECTIVITY_SERVICE);
		telephonyManager = (TelephonyManager) context.getSystemService(Context.TELEPHONY_SERVICE);
		boolean registered = false;
		try {
			connectivityManager.registerDefaultNetworkCallback(new ConnectivityManager.NetworkCallback() {
				@Override
				public void onAvailable(@NonNull Network network) {
					state.available(network);
				}

				@Override
				public void onCapabilitiesChanged(@NonNull Network network,
						@NonNull NetworkCapabilities capabilities) {
					// Use the delivered capabilities, not synchronous queries from inside the callback.
					if (state.capabilities(network, new Snapshot(capabilities))) notifyListeners();
				}

				@Override
				public void onLost(@NonNull Network network) {
					if (state.lost(network)) notifyListeners();
				}
			});
			registered = true;
		} catch (RuntimeException ignored) {
			// If an OEM refuses registration, retain live queries rather than an indefinitely stale cache.
		}
		observing = registered;
		getSnapshot();
	}

	public Snapshot getSnapshot() {
		Snapshot snapshot = state.get();
		if (observing && snapshot != null) return snapshot;
		// Bootstrap or the short onAvailable -> onCapabilitiesChanged interval only.
		for (int attempt = 0; attempt < 2; attempt++) {
			long revision;
			synchronized (state) {
				snapshot = state.get();
				if (observing && snapshot != null) return snapshot;
				revision = state.revision();
			}
			Network network;
			NetworkCapabilities capabilities;
			try {
				network = connectivityManager.getActiveNetwork();
				capabilities = network != null ? connectivityManager.getNetworkCapabilities(network) : null;
			} catch (SecurityException e) {
				return Snapshot.DISCONNECTED;
			}
			Snapshot queried = new Snapshot(capabilities);
			if (!observing) return queried;
			// An incomplete answer must not freeze the cache before the first capabilities event.
			if (network != null && capabilities == null) {
				snapshot = state.get();
				return snapshot != null ? snapshot : Snapshot.DISCONNECTED;
			}
			if (state.initialize(revision, network, queried)) return queried;
		}
		snapshot = state.get();
		return snapshot != null ? snapshot : Snapshot.DISCONNECTED;
	}

	public boolean addListener(Runnable listener) {
		if (!observing) return false;
		listeners.add(listener);
		return true;
	}
	public void removeListener(Runnable listener) { listeners.remove(listener); }

	private void notifyListeners() {
		// Publish flags on Android's callback thread, dispatch gallery work on the UI thread only.
		ConcurrentUtils.HANDLER.post(() -> {
			for (Runnable listener : listeners) listener.run();
		});
	}

	public boolean hasActiveWifiTransport() {
		return getSnapshot().wifi;
	}

	public boolean isWifiConnected() {
		Snapshot snapshot = getSnapshot();
		// Preserve the existing thumbnail policy: any available non-cellular network is classified as Wi-Fi.
		return snapshot.available && !snapshot.cellular;
	}

	public boolean isVpnConnected() {
		return getSnapshot().vpn;
	}

	public String getCountryIso() {
		String countryIso = null;
		if (telephonyManager != null) {
			try {
				countryIso = telephonyManager.getNetworkCountryIso();
				if (countryIso == null || countryIso.isEmpty()) {
					countryIso = telephonyManager.getSimCountryIso();
				}
			} catch (SecurityException | UnsupportedOperationException ignored) {
				// The locale below is a safe fallback when carrier information is unavailable.
			}
		}
		if (countryIso == null || countryIso.isEmpty()) {
			countryIso = Locale.getDefault().getCountry();
		}
		return countryIso != null ? countryIso.toUpperCase(Locale.US) : "";
	}

	public boolean isWifiOrMobileConnected() {
		// Keep the existing connectivity/VPN classification, without inspecting radio generation.
		return getSnapshot().available;
	}
}
