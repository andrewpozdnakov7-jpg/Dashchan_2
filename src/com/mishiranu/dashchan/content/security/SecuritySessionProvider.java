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

/** Private session authority. No disk authorization, PID identity, or disk I/O on the input path. */
public final class SecuritySessionProvider extends ContentProvider {
	private static final IBinder CLIENT = new Binder();
	private final HashSet<String> unlocked = new HashSet<>();
	private final Map<String, HashSet<IBinder>> foreground = new HashMap<>();
	private final Map<IBinder, IBinder.DeathRecipient> clients = new HashMap<>();
	private final Map<String, Long> generations = new HashMap<>();
	private static final class Remembered {
		final IBinder owner;
		final ReauthenticationPolicy.Grant grant;
		Remembered(IBinder owner, ReauthenticationPolicy.Grant grant) { this.owner = owner; this.grant = grant; }
		boolean valid(long now) { return owner.isBinderAlive() && grant.valid(now); }
	}
	private final Map<String, Remembered> remembered = new HashMap<>();
	private final long initialGeneration = new java.security.SecureRandom().nextLong() & 0x3fff_ffff_ffff_ffffL;
	@Override public boolean onCreate() { return true; }
	private long generation(String key) { return generations.getOrDefault(key, initialGeneration); }
	private void revoke(String key) {
		remembered.remove(key);
		suspend(key);
	}
	private void suspend(String key) {
		boolean changed = unlocked.remove(key); generations.put(key, generation(key) + 1);
		if (changed) AppSecurity.notifyChanged(getContext());
	}
	private boolean registerClient(IBinder client) {
		if (client == null || !client.isBinderAlive()) return false;
		if (clients.containsKey(client)) return true;
		IBinder.DeathRecipient death = () -> {
			synchronized (SecuritySessionProvider.this) {
				clients.remove(client);
				for (Map.Entry<String, HashSet<IBinder>> entry : foreground.entrySet()) {
					entry.getValue().remove(client);
					Remembered auth = remembered.get(entry.getKey());
					if (entry.getValue().isEmpty() || auth != null && auth.owner == client) revoke(entry.getKey());
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
		Remembered auth = remembered.get(key);
		if (auth != null && !auth.owner.isBinderAlive() || active.isEmpty() && lostClient) revoke(key);
		else if (active.isEmpty() && unlocked.contains(key)) suspend(key);
		IBinder caller = extras != null ? extras.getBinder("client") : null;
		if ("lock".equals(method)) revoke(key);
		else if ("suspend".equals(method)) suspend(key);
		else if ("active".equals(method)) {
			if (registerClient(caller)) active.add(caller);
		} else if ("inactive".equals(method)) {
			active.remove(caller);
			if (active.isEmpty() && unlocked.contains(key)) suspend(key);
		} else if ("unlock".equals(method) || "authenticated".equals(method) || "resume".equals(method)) {
			if (caller != null && active.contains(caller) && caller.isBinderAlive()
					&& extras.getLong("generation", -1) == generation(key)) {
				long now = android.os.SystemClock.elapsedRealtime();
				if ("authenticated".equals(method)) {
					ReauthenticationPolicy.Grant grant = new ReauthenticationPolicy.Grant(
							extras.getLong("authenticated_at", now), extras.getInt("minutes", 0));
					if (grant.valid(now)) remembered.put(key, new Remembered(caller, grant)); else remembered.remove(key);
				}
				boolean allowed = !"resume".equals(method);
				if (!allowed) {
					Remembered grant = remembered.get(key);
					allowed = grant != null && grant.valid(now);
					if (!allowed) remembered.remove(key);
				}
				// Resume does not refresh authenticatedAt: reopening cannot extend the interval.
				if (allowed && unlocked.add(key)) AppSecurity.notifyChanged(getContext());
			}
		} else if (!"query".equals(method)) throw new IllegalArgumentException("Unknown session operation");
		Bundle result = new Bundle();
		result.putBoolean("unlocked", unlocked.contains(key)); result.putLong("generation", generation(key));
		return result;
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
	static void suspend(Context context) {
		try { request(context, "suspend", null); } catch (RuntimeException ignored) { /* Missing authority denies access. */ }
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
	static void resume(Context context, long generation) throws IOException {
		try { if (request(context, "resume", generation) == null) throw new IOException("Session unavailable"); }
		catch (RuntimeException e) { throw new IOException("Session unavailable"); }
	}
	static void foreground(Context context, boolean active) throws IOException {
		try { if (request(context, active ? "active" : "inactive", null) == null) throw new IOException("Session unavailable"); }
		catch (RuntimeException e) { throw new IOException("Session unavailable"); }
	}
	@Override public Cursor query(Uri uri, String[] projection, String selection, String[] args, String order) { throw new UnsupportedOperationException(); }
	@Override public String getType(Uri uri) { return null; }
	@Override public Uri insert(Uri uri, ContentValues values) { throw new UnsupportedOperationException(); }
	@Override public int delete(Uri uri, String selection, String[] args) { throw new UnsupportedOperationException(); }
	@Override public int update(Uri uri, ContentValues values, String selection, String[] args) { throw new UnsupportedOperationException(); }
}
