package com.mishiranu.dashchan.content;

import android.content.Context;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.telephony.TelephonyManager;
import android.util.Pair;
import androidx.annotation.NonNull;
import com.mishiranu.dashchan.util.ConcurrentUtils;
import java.util.Locale;

public class NetworkObserver {
	private static final NetworkObserver INSTANCE = new NetworkObserver();

	public static NetworkObserver getInstance() {
		return INSTANCE;
	}

	private enum NetworkState {WIFI, MOBILE, UNDEFINED}

	private final ConnectivityManager connectivityManager;
	private final TelephonyManager telephonyManager;

	private NetworkState networkState = NetworkState.UNDEFINED;

	private NetworkObserver() {
		Context context = MainApplication.getInstance();
		connectivityManager = (ConnectivityManager) context.getSystemService(Context.CONNECTIVITY_SERVICE);
		telephonyManager = (TelephonyManager) context.getSystemService(Context.TELEPHONY_SERVICE);
		onActiveNetworkChange();
		Runnable onActiveNetworkChange = NetworkObserver.this::onActiveNetworkChange;
		connectivityManager.registerDefaultNetworkCallback(new ConnectivityManager.NetworkCallback() {
			private void handleChange() {
				ConcurrentUtils.HANDLER.removeCallbacks(onActiveNetworkChange);
				ConcurrentUtils.HANDLER.postDelayed(onActiveNetworkChange, 500L);
			}

			@Override
			public void onCapabilitiesChanged(@NonNull Network network,
					@NonNull NetworkCapabilities networkCapabilities) {
				handleChange();
			}

			@Override
			public void onLost(@NonNull Network network) {
				handleChange();
			}
		});
	}

	private Pair<Network, NetworkCapabilities> getActiveNetworkWithCapabilities() {
		Network network = connectivityManager.getActiveNetwork();
		if (network != null) {
			NetworkCapabilities capabilities = connectivityManager.getNetworkCapabilities(network);
			return capabilities != null ? new Pair<>(network, capabilities) : null;
		}
		return null;
	}

	public boolean isWifiConnected() {
		return networkState == NetworkState.WIFI;
	}

	public boolean isVpnConnected() {
		Pair<Network, NetworkCapabilities> pair = getActiveNetworkWithCapabilities();
		return pair != null && pair.second.hasTransport(NetworkCapabilities.TRANSPORT_VPN);
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
		return networkState == NetworkState.WIFI || networkState == NetworkState.MOBILE;
	}

	private void onActiveNetworkChange() {
		updateNetworkState();
	}

	private void updateNetworkState() {
		NetworkState networkState = NetworkState.UNDEFINED;
		Pair<Network, NetworkCapabilities> pair = getActiveNetworkWithCapabilities();
		if (pair != null) {
			networkState = pair.second.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)
					? NetworkState.MOBILE : NetworkState.WIFI;
		}
		this.networkState = networkState;
	}
}
