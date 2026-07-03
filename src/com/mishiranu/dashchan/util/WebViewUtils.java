package com.mishiranu.dashchan.util;

import android.app.Application;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.net.Proxy;
import android.net.ProxyInfo;
import android.util.Pair;
import android.webkit.CookieManager;
import android.webkit.WebStorage;
import android.webkit.WebView;
import android.webkit.WebViewDatabase;
import androidx.webkit.ProxyConfig;
import androidx.webkit.ProxyController;
import androidx.webkit.WebViewFeature;
import chan.http.HttpClient;
import com.mishiranu.dashchan.content.MainApplication;
import java.lang.reflect.Field;
import java.util.Map;
import java.util.concurrent.Executor;

public class WebViewUtils {
	@SuppressWarnings("deprecation")
	public static void clearCookie() {
		CookieManager.getInstance().removeAllCookie();
	}

	@SuppressWarnings("deprecation")
	public static void clearAll(WebView webView) {
		clearCookie();
		if (webView != null) {
			webView.clearCache(true);
		}
		WebViewDatabase webViewDatabase = WebViewDatabase.getInstance(MainApplication.getInstance());
		webViewDatabase.clearFormData();
		webViewDatabase.clearHttpAuthUsernamePassword();
		WebStorage.getInstance().deleteAllData();
	}

	private static final Field FIELD_APPLICATION_LOADED_APK;
	private static final Field FIELD_LOADED_APK_RECEIVERS;

	static {
		Field applicationLoadedApkField = null;
		Field loadedApkReceiversField = null;
		try {
			applicationLoadedApkField = Application.class.getField("mLoadedApk");
			loadedApkReceiversField = applicationLoadedApkField.getType().getDeclaredField("mReceivers");
			loadedApkReceiversField.setAccessible(true);
		} catch (Exception e) {
			applicationLoadedApkField = null;
			loadedApkReceiversField = null;
		}
		FIELD_APPLICATION_LOADED_APK = applicationLoadedApkField;
		FIELD_LOADED_APK_RECEIVERS = loadedApkReceiversField;
	}

	private static BroadcastReceiver findProxyChangeReceiver(Map<?, ?> receivers) {
		for (Map.Entry<?, ?> entry : receivers.entrySet()) {
			Object value = entry.getValue();
			if (value instanceof Map) {
				BroadcastReceiver result = findProxyChangeReceiver((Map<?, ?>) value);
				if (result != null) {
					return result;
				}
			} else {
				Object key = entry.getKey();
				if (key instanceof BroadcastReceiver && key.getClass().getName().contains("ProxyChangeListener")) {
					return (BroadcastReceiver) key;
				}
			}
		}
		return null;
	}

	private static final Executor EXECUTOR = Runnable::run;

	public static void setProxy(Context context, HttpClient.ProxyData proxyData, Runnable callback) {
		if (WebViewFeature.isFeatureSupported(WebViewFeature.PROXY_OVERRIDE)) {
			if (proxyData != null) {
				String uriString = (proxyData.socks ? "socks" : "http") + "://"
						+ proxyData.host + ":" + proxyData.port;
				ProxyController.getInstance().setProxyOverride(new ProxyConfig.Builder()
						.addProxyRule(uriString).build(), EXECUTOR, callback);
			} else {
				ProxyController.getInstance().clearProxyOverride(EXECUTOR, callback);
			}
		} else {
			setHttpProxy(context, proxyData != null && !proxyData.socks
					? new Pair<>(proxyData.host, proxyData.port) : null);
			if (callback != null) {
				callback.run();
			}
		}
	}

	private static void setHttpProxy(Context context, Pair<String, Integer> proxy) {
		String hostProperty = proxy != null ? proxy.first : "";
		String portProperty = proxy != null ? proxy.second.toString() : "";
		System.setProperty("http.proxyHost", hostProperty);
		System.setProperty("http.proxyPort", portProperty);
		System.setProperty("https.proxyHost", hostProperty);
		System.setProperty("https.proxyPort", portProperty);

		if (FIELD_APPLICATION_LOADED_APK != null && FIELD_LOADED_APK_RECEIVERS != null) {
			Context applicationContext = context.getApplicationContext();
			Map<?, ?> receivers = null;
			try {
				Object loadedApk = FIELD_APPLICATION_LOADED_APK.get(applicationContext);
				receivers = (Map<?, ?>) FIELD_LOADED_APK_RECEIVERS.get(loadedApk);
			} catch (Exception e) {
				e.printStackTrace();
			}
			BroadcastReceiver proxyChangeListener = receivers != null ? findProxyChangeReceiver(receivers) : null;
			if (proxyChangeListener != null) {
				Intent intent = new Intent(Proxy.PROXY_CHANGE_ACTION);
				String name = "android.intent.extra.PROXY_INFO";
				intent.putExtra(name, proxy != null ? ProxyInfo.buildDirectProxy(proxy.first, proxy.second) : null);
				proxyChangeListener.onReceive(applicationContext, intent);
			}
		}
	}
}
