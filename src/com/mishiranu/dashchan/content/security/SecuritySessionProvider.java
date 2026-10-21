package com.mishiranu.dashchan.content.security;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.os.Binder;
import android.os.Bundle;
import android.os.IBinder;
import android.os.Process;
import android.os.RemoteException;
import java.io.IOException;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;

/** Private RAM session authority. Durable receipts are verified by SecurityStore, never on the input path. */
public final class SecuritySessionProvider extends ContentProvider {
	private static final IBinder CLIENT = new Binder();
	private final HashSet<String> unlocked = new HashSet<>();
	private final HashSet<String> restoreBlocked = new HashSet<>();
	private final HashSet<String> rememberable = new HashSet<>();
	private final Map<String, Long> backgroundStarts = new HashMap<>();
	private final Map<String, HashSet<IBinder>> foreground = new HashMap<>();
	private final Map<IBinder, IBinder.DeathRecipient> clients = new HashMap<>();
	private final Map<String, Long> generations = new HashMap<>();
	private final Map<String, IBinder> authenticationOwners = new HashMap<>();
	private final long initialGeneration = new java.security.SecureRandom().nextLong() & 0x3fff_ffff_ffff_ffffL;
	@Override public boolean onCreate() { return true; }
	private long generation(String key) { return generations.getOrDefault(key, initialGeneration); }
	private void revoke(String key) {
		authenticationOwners.remove(key);
		suspend(key);
	}
	private void suspend(String key) {
		rememberable.remove(key); backgroundStarts.remove(key);
		boolean changed = unlocked.remove(key); generations.put(key, generation(key) + 1);
		if (changed) AppSecurity.notifyChanged(getContext());
	}
	private Long beginBackground(String key) {
		boolean remember = unlocked.contains(key) && rememberable.contains(key) && !restoreBlocked.contains(key);
		long now = android.os.SystemClock.elapsedRealtime();
		suspend(key); // Close access BEFORE the caller attempts the atomic receipt write.
		if (!remember) return null;
		backgroundStarts.put(key, now);
		return now;
	}
	private boolean registerClient(IBinder client) {
		if (client == null || !client.isBinderAlive()) return false;
		if (clients.containsKey(client)) return true;
		IBinder.DeathRecipient death = () -> {
			synchronized (SecuritySessionProvider.this) {
				clients.remove(client);
				for (Map.Entry<String, HashSet<IBinder>> entry : foreground.entrySet()) {
					entry.getValue().remove(client);
					if (entry.getValue().isEmpty() || authenticationOwners.get(entry.getKey()) == client) revoke(entry.getKey());
				}
			}
		};
		try { client.linkToDeath(death, 0); clients.put(client, death); return true; }
		catch (RemoteException e) { return false; }
	}
	@Override public synchronized Bundle call(String method, String key, Bundle extras) {
		if (Binder.getCallingUid() != Process.myUid() || key == null) throw new SecurityException();
		HashSet<IBinder> active = foreground.get(key);
		if (active == null) { active = new HashSet<>(); foreground.put(key, active); }
		boolean lostClient = active.removeIf(client -> !client.isBinderAlive());
		IBinder owner = authenticationOwners.get(key);
		if (owner != null && !owner.isBinderAlive() || active.isEmpty() && lostClient) revoke(key);
		else if (active.isEmpty() && unlocked.contains(key)) suspend(key);
		IBinder caller = extras != null ? extras.getBinder("client") : null;
		Long backgroundAt = null;
		if ("lock".equals(method)) { restoreBlocked.add(key); revoke(key); }
		else if ("suspend".equals(method)) {
			if (unlocked.contains(key)) backgroundAt = beginBackground(key);
			else if (!backgroundStarts.containsKey(key)) suspend(key);
			// Duplicate screen-off callbacks must not invalidate an in-flight receipt write.
		}
		else if ("active".equals(method)) {
			if (registerClient(caller)) { active.add(caller); backgroundStarts.remove(key); }
		} else if ("inactive".equals(method)) {
			boolean removed = active.remove(caller);
			if (removed && active.isEmpty() && unlocked.contains(key)) backgroundAt = beginBackground(key);
		} else if ("unlock".equals(method) || "authenticated".equals(method) || "restore".equals(method)) {
			if (caller != null && active.contains(caller) && caller.isBinderAlive()
					&& extras.getLong("generation", -1) == generation(key)) {
				long now = android.os.SystemClock.elapsedRealtime();
				boolean restoring = "restore".equals(method);
				boolean allowed = true;
				if ("authenticated".equals(method) || restoring) {
					ReauthenticationPolicy.Grant grant = new ReauthenticationPolicy.Grant(
							extras.getLong("authenticated_at", now), extras.getInt("minutes", 0));
					if (restoring) allowed = !restoreBlocked.contains(key) && grant.valid(now);
					else restoreBlocked.remove(key); // Only fresh proof can undo an explicit revocation.
				}
				if (allowed) {
					authenticationOwners.put(key, caller);
					if (("authenticated".equals(method) || restoring)
							&& ReauthenticationPolicy.normalize(extras.getInt("minutes", 0)) > 0) rememberable.add(key);
					else rememberable.remove(key);
					backgroundStarts.remove(key);
				}
				// Restoring a verified receipt never refreshes authenticatedAt.
				if (allowed && unlocked.add(key)) AppSecurity.notifyChanged(getContext());
			}
		} else if (!"query".equals(method) && !"background-grant".equals(method)) throw new IllegalArgumentException("Unknown session operation");
		Bundle result = new Bundle();
		result.putBoolean("unlocked", unlocked.contains(key)); result.putLong("generation", generation(key));
		if (backgroundAt != null) result.putLong("background_at", backgroundAt);
		if ("background-grant".equals(method)) {
			Long pending = backgroundStarts.get(key);
			result.putBoolean("background_grant", extras != null && pending != null && !unlocked.contains(key)
					&& !restoreBlocked.contains(key) && extras.getLong("generation", -1) == generation(key)
					&& extras.getLong("authenticated_at", -1) == pending);
		}
		return result;
	}
	static final class BackgroundGrant {
		final long at, generation;
		BackgroundGrant(long at, long generation) { this.at = at; this.generation = generation; }
		static BackgroundGrant from(Bundle result) {
			return result != null && result.containsKey("background_at")
					? new BackgroundGrant(result.getLong("background_at"), result.getLong("generation", -1)) : null;
		}
	}
	static boolean canRememberBackground(Context context, BackgroundGrant grant) {
		try {
			Bundle result = request(context, "background-grant", grant.generation, null, grant.at);
			return result != null && result.getBoolean("background_grant");
		} catch (RuntimeException e) { return false; }
	}
	private static Bundle request(Context context, String method, Long generation) {
		return request(context, method, generation, null, null);
	}
	private static Bundle request(Context context, String method, Long generation, Integer minutes, Long authenticatedAt) {
		Bundle extras = new Bundle(); extras.putBinder("client", CLIENT);
		if (generation != null) extras.putLong("generation", generation);
		if (minutes != null) extras.putInt("minutes", minutes);
		if (authenticatedAt != null) extras.putLong("authenticated_at", authenticatedAt);
		return context.getContentResolver().call(Uri.parse("content://" + context.getPackageName() + ".security.session"),
				method, SecurityStore.directory(context).getAbsolutePath(), extras);
	}
	static boolean isUnlocked(Context context) {
		try { Bundle state = request(context, "query", null); return state != null && state.getBoolean("unlocked"); }
		catch (RuntimeException e) { return false; }
	}
	static void revoke(Context context) {
		try { request(context, "lock", null); } catch (RuntimeException ignored) { /* A missing/dead authority denies access. */ }
	}
	static BackgroundGrant suspend(Context context) {
		try { return BackgroundGrant.from(request(context, "suspend", null)); }
		catch (RuntimeException ignored) { return null; /* Missing authority denies access. */ }
	}
	static long generation(Context context) {
		try { Bundle state = request(context, "query", null); return state != null ? state.getLong("generation", -1) : -1; }
		catch (RuntimeException e) { return -1; }
	}
	static void grant(Context context, long generation) throws IOException {
		grant(context, generation, null, null);
	}
	static void grantAuthenticated(Context context, long generation, int minutes, long authenticatedAt) throws IOException {
		grant(context, generation, minutes, authenticatedAt);
	}
	private static void grant(Context context, long generation, Integer minutes, Long authenticatedAt) throws IOException {
		try {
			Bundle state = request(context, minutes != null ? "authenticated" : "unlock", generation, minutes, authenticatedAt);
			if (state == null || !state.getBoolean("unlocked") || state.getLong("generation", -1) != generation) throw new IOException("Session unavailable");
		} catch (RuntimeException e) { throw new IOException("Session unavailable"); }
	}
	static void restore(Context context, long generation, int minutes, long authenticatedAt) throws IOException {
		try { if (request(context, "restore", generation, minutes, authenticatedAt) == null) throw new IOException("Session unavailable"); }
		catch (RuntimeException e) { throw new IOException("Session unavailable"); }
	}
	static BackgroundGrant foreground(Context context, boolean active) throws IOException {
		try {
			Bundle result = request(context, active ? "active" : "inactive", null);
			if (result == null) throw new IOException("Session unavailable");
			return BackgroundGrant.from(result);
		}
		catch (RuntimeException e) { throw new IOException("Session unavailable"); }
	}
	@Override public Cursor query(Uri uri, String[] projection, String selection, String[] args, String order) { throw new UnsupportedOperationException(); }
	@Override public String getType(Uri uri) { return null; }
	@Override public Uri insert(Uri uri, ContentValues values) { throw new UnsupportedOperationException(); }
	@Override public int delete(Uri uri, String selection, String[] args) { throw new UnsupportedOperationException(); }
	@Override public int update(Uri uri, ContentValues values, String selection, String[] args) { throw new UnsupportedOperationException(); }
}
