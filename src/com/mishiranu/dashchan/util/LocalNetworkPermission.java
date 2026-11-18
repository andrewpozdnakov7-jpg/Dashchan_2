package com.mishiranu.dashchan.util;

import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.ConnectivityManager;
import android.net.InetAddresses;
import android.net.LinkProperties;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.RouteInfo;
import android.net.Uri;
import android.os.Build;
import android.provider.Settings;
import java.net.InetAddress;
import java.util.Locale;

public final class LocalNetworkPermission {
	public static final String PERMISSION = "android.permission.ACCESS_LOCAL_NETWORK";

	private LocalNetworkPermission() {}

	public static boolean applies(Context context) {
		return Build.VERSION.SDK_INT >= 37 && context.getApplicationInfo().targetSdkVersion >= 37;
	}

	public static boolean isMissing(Context context) {
		return applies(context) && context.checkSelfPermission(PERMISSION) != PackageManager.PERMISSION_GRANTED;
	}

	/** An advisory for a failed connection. Android still chooses the route and enforces permission. */
	public static boolean isLikelyLocalTarget(Context context, String host) {
		if (host == null || !isMissing(context)) return false;
		ConnectivityManager manager = context.getSystemService(ConnectivityManager.class);
		Network network = manager != null ? manager.getActiveNetwork() : null;
		NetworkCapabilities capabilities = network != null ? manager.getNetworkCapabilities(network) : null;
		if (capabilities == null || (!capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)
				&& !capabilities.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET))) return false;
		if (host.startsWith("[") && host.endsWith("]")) host = host.substring(1, host.length() - 1);
		String name = host.toLowerCase(Locale.ROOT);
		if (name.endsWith(".local") || name.endsWith(".local.")) return true;
		// Scope names apply to routing. They are unnecessary for classifying the numeric address.
		int scope = host.indexOf('%');
		if (scope >= 0 && host.indexOf(':') >= 0) host = host.substring(0, scope);
		InetAddress address;
		try {
			address = InetAddresses.parseNumericAddress(host);
		} catch (IllegalArgumentException e) {
			return false;
		}
		if (address.isLoopbackAddress() || address.isAnyLocalAddress()) return false;
		LinkProperties properties = manager.getLinkProperties(network);
		if (properties == null) return false;
		RouteInfo best = null;
		for (RouteInfo route : properties.getRoutes()) {
			if (route.matches(address) && (best == null
					|| route.getDestination().getPrefixLength() > best.getDestination().getPrefixLength())) {
				best = route;
			}
		}
		return best != null && best.getDestination().getPrefixLength() > 0 && !best.hasGateway();
	}

	public static Intent appSettings(Context context) {
		return new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
				Uri.fromParts("package", context.getPackageName(), null));
	}
}
