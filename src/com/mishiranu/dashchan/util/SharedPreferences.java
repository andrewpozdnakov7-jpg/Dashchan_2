package com.mishiranu.dashchan.util;

import android.annotation.SuppressLint;
import android.content.Context;
import java.io.Closeable;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;

public class SharedPreferences {
	public static class Editor implements Closeable {
		private final SharedPreferences preferences;
		private final android.content.SharedPreferences.Editor editor;
		private final boolean asynchronous;
		private int auditEdits;
		private String auditReason = "empty";

		private void auditEdit(String key) {
			String label = AuditPreferenceKeys.label(key);
			if (auditEdits++ == 0) auditReason = label;
			else if (!auditReason.equals(label)) auditReason = "multiple";
		}

		@SuppressLint("CommitPrefEdits")
		private Editor(SharedPreferences preferences, boolean asynchronous) {
			this.preferences = preferences;
			this.asynchronous = asynchronous;
			editor = preferences.shared.edit();
		}

		public Editor put(String key, String value) {
			auditEdit(key);
			if (value != null) {
				editor.putString(key, value);
			} else {
				editor.remove(key);
			}
			return this;
		}

		public Editor put(String key, Set<String> values) {
			auditEdit(key);
			if (values != null) {
				editor.putStringSet(key, values);
			} else {
				editor.remove(key);
			}
			return this;
		}

		public Editor put(String key, int value) {
			auditEdit(key);
			editor.putInt(key, value);
			return this;
		}

		public Editor put(String key, long value) {
			auditEdit(key);
			editor.putLong(key, value);
			return this;
		}

		public Editor put(String key, float value) {
			auditEdit(key);
			editor.putFloat(key, value);
			return this;
		}

		public Editor put(String key, boolean value) {
			auditEdit(key);
			editor.putBoolean(key, value);
			return this;
		}

		public Editor remove(String key) {
			auditEdit(key);
			editor.remove(key);
			return this;
		}

		private boolean commitInternal() {
			long started = PerformanceDiagnostics.now();
			boolean success = false;
			try (AuditDiagnostics.Scope scope = AuditDiagnostics.begin(asynchronous
					? "Audit/Preferences/apply" : "Audit/Preferences/commit").reason(auditReason).count(auditEdits)) {
				preferences.shouldUpdateMap = true;
				if (asynchronous) {
					// Android updates memory/listeners before returning; only disk persistence is deferred.
					editor.apply();
					success = true;
				} else {
					success = editor.commit();
				}
				scope.result(success ? "ok" : "failed");
				return success;
			} finally {
				try (AuditDiagnostics.Scope scope = AuditDiagnostics.begin("Audit/Preferences/updateMap").reason(auditReason)) {
					preferences.updateMapIfNeeded();
					scope.result("ok");
				}
				PerformanceDiagnostics.finish(asynchronous ? "preferences.apply" : "preferences.commit",
						started, success, true);
			}
		}

		@Override
		public void close() {
			if (ConcurrentUtils.isMain()) {
				commitInternal();
			} else {
				// Uninterruptible process
				try (AuditDiagnostics.Scope scope = AuditDiagnostics.begin("Audit/Preferences/workerMainRoundTrip")
						.reason(auditReason).count(auditEdits)) {
					ConcurrentUtils.mainGet(this::commitInternal);
					scope.result("ok");
				}
			}
		}
	}

	public interface Listener {
		void onChanged(String key);
	}

	private final android.content.SharedPreferences shared;
	private Map<String, ?> map = Collections.emptyMap();
	private boolean shouldUpdateMap = true;

	public SharedPreferences(Context context, String name) {
		this.shared = context.getSharedPreferences(name, Context.MODE_PRIVATE);
		updateMapIfNeeded();
	}

	private void updateMapIfNeeded() {
		if (shouldUpdateMap) {
			// SharedPreferences.getAll() returns a new HashMap instance
			map = Collections.unmodifiableMap(shared.getAll());
			shouldUpdateMap = false;
		}
	}

	public Map<String, ?> getAll() {
		return map;
	}

	public String getString(String key, String defValue) {
		Object value = map.get(key);
		return value instanceof String ? (String) value : defValue;
	}

	@SuppressWarnings("unchecked")
	public Set<String> getStringSet(String key, Set<String> defValues) {
		Object value = map.get(key);
		return value instanceof Set ? (Set<String>) value : defValues;
	}

	public int getInt(String key, int defValue) {
		Object value = map.get(key);
		return value instanceof Number ? ((Number) value).intValue() : defValue;
	}

	public long getLong(String key, long defValue) {
		Object value = map.get(key);
		return value instanceof Number ? ((Number) value).longValue() : defValue;
	}

	public float getFloat(String key, float defValue) {
		Object value = map.get(key);
		return value instanceof Number ? ((Number) value).floatValue() : defValue;
	}

	public boolean getBoolean(String key, boolean defValue) {
		Object value = map.get(key);
		return value instanceof Boolean ? (boolean) value : defValue;
	}

	public boolean contains(String key) {
		return map.containsKey(key);
	}

	public Editor edit() {
		return new Editor(this, false);
	}

	/** Opt-in for visual preferences only. close() publishes memory now, but does not await disk durability. */
	public Editor editAsync() {
		return new Editor(this, true);
	}

	private final Map<Listener, android.content.SharedPreferences
			.OnSharedPreferenceChangeListener> listeners = new HashMap<>();

	public void register(Listener listener) {
		synchronized (listeners) {
			if (!listeners.containsKey(listener)) {
				android.content.SharedPreferences.OnSharedPreferenceChangeListener internalListener = (p, key) -> {
					updateMapIfNeeded();
					listener.onChanged(key);
				};
				shared.registerOnSharedPreferenceChangeListener(internalListener);
				listeners.put(listener, internalListener);
			}
		}
	}

	public void unregister(Listener listener) {
		synchronized (listeners) {
			android.content.SharedPreferences.OnSharedPreferenceChangeListener
					internalListener = listeners.remove(listener);
			if (internalListener != null) {
				shared.unregisterOnSharedPreferenceChangeListener(internalListener);
			}
		}
	}
}
