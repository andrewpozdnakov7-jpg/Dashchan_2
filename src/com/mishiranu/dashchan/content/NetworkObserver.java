package com.mishiranu.dashchan.content;

import android.content.Context;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.os.SystemClock;
import android.telephony.TelephonyManager;
import android.util.Pair;
import androidx.annotation.NonNull;
import com.mishiranu.dashchan.util.ConcurrentUtils;

public class NetworkObserver {
	private static final NetworkObserver INSTANCE = new NetworkObserver();

	public static NetworkObserver getInstance() {
		return INSTANCE;
	}

	private enum NetworkState {WIFI, MOBILE, UNDEFINED}

	private final ConnectivityManager connectivityManager;

	private NetworkState networkState = NetworkState.UNDEFINED;
	private long last3GChecked;
	private boolean last3GAvailable;

	private NetworkObserver() {
		Context context = MainApplication.getInstance();
		connectivityManager = (ConnectivityManager) context.getSystemService(Context.CONNECTIVITY_SERVICE);
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

	private Pair<Network, NetworkCapabilities> getNetwork28() {
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

	public boolean isMobile3GConnected() {
		switch (networkState) {
			case WIFI: {
				return true;
			}
			case MOBILE: {
				if (SystemClock.elapsedRealtime() - last3GChecked >= 2000) {
					update3GConnected28();
					last3GChecked = SystemClock.elapsedRealtime();
				}
				return last3GAvailable;
			}
			case UNDEFINED:
			default: {
				return false;
			}
		}
	}

	@SuppressWarnings("deprecation")
	private void update3GConnected28() {
		boolean is3GAvailable = false;
		Pair<Network, NetworkCapabilities> pair = getNetwork28();
		if (pair.second != null && pair.second.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)) {
			// Is there a non-deprecated way to get network subtype without READ_PHONE_STATE permission?
			android.net.NetworkInfo networkInfo = connectivityManager.getNetworkInfo(pair.first);
			is3GAvailable = isNetworkType3G(networkInfo.getSubtype());
		}
		last3GAvailable = is3GAvailable;
	}

	@SuppressWarnings({"DuplicateBranchesInSwitch", "deprecation"})
	private boolean isNetworkType3G(int type) {
		switch (type) {
			case TelephonyManager.NETWORK_TYPE_UMTS:
			case TelephonyManager.NETWORK_TYPE_EVDO_0:
			case TelephonyManager.NETWORK_TYPE_EVDO_A:
			case TelephonyManager.NETWORK_TYPE_HSDPA:
			case TelephonyManager.NETWORK_TYPE_HSUPA:
			case TelephonyManager.NETWORK_TYPE_HSPA:
			case TelephonyManager.NETWORK_TYPE_EVDO_B:
			case TelephonyManager.NETWORK_TYPE_EHRPD:
			case TelephonyManager.NETWORK_TYPE_HSPAP:
			case TelephonyManager.NETWORK_TYPE_TD_SCDMA: {
				// 3G
				return true;
			}
			case TelephonyManager.NETWORK_TYPE_LTE:
			case TelephonyManager.NETWORK_TYPE_IWLAN: {
				// 4G
				return true;
			}
			case TelephonyManager.NETWORK_TYPE_NR: {
				// 5G
				return true;
			}
			default: {
				return false;
			}
		}
	}

	private void onActiveNetworkChange() {
		updateNetworkState28();
		last3GChecked = 0L;
	}

	private void updateNetworkState28() {
		NetworkState networkState = NetworkState.UNDEFINED;
		Pair<Network, NetworkCapabilities> pair = getNetwork28();
		if (pair != null) {
			networkState = pair.second.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)
					? NetworkState.MOBILE : NetworkState.WIFI;
		}
		this.networkState = networkState;
	}
}
