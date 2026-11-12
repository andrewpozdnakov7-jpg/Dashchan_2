package com.mishiranu.dashchan.content.security;

import android.content.Context;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.Parcelable;
import androidx.activity.ComponentActivity;
import androidx.activity.result.ActivityResultCallback;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContract;
import androidx.fragment.app.Fragment;
import androidx.lifecycle.Lifecycle;
import androidx.lifecycle.LifecycleEventObserver;
import androidx.lifecycle.LifecycleOwner;
import androidx.savedstate.SavedStateRegistry;
import androidx.savedstate.SavedStateRegistryOwner;
import java.io.IOException;
import java.util.function.Supplier;

/** System pickers may return before onResume. Keep their payload, not permission to apply it. */
public final class ProtectedActivityResults {
	private ProtectedActivityResults() {}
	public static <I, O extends Parcelable> ActivityResultLauncher<I> register(Fragment owner, String key,
			ActivityResultContract<I, O> contract, ActivityResultCallback<O> callback) {
		Delivery<O> delivery = new Delivery<>(owner, owner, owner::requireContext, key, callback);
		return owner.registerForActivityResult(contract, delivery::receive);
	}
	public static <I, O extends Parcelable> ActivityResultLauncher<I> register(ComponentActivity owner, String key,
			ActivityResultContract<I, O> contract, ActivityResultCallback<O> callback) {
		Delivery<O> delivery = new Delivery<>(owner, owner, () -> owner, key, callback);
		return owner.registerForActivityResult(contract, delivery::receive);
	}

	static final class Delivery<O extends Parcelable> implements LifecycleEventObserver,
			SavedStateRegistry.SavedStateProvider {
		private final LifecycleOwner owner;
		private final SavedStateRegistry registry;
		private final Supplier<Context> context;
		private final ActivityResultCallback<O> callback;
		private final String key;
		private final Handler handler = new Handler(Looper.getMainLooper());
		private final Runnable drain = this::drain;
		private boolean pending;
		private O result;
		private long profile = -1;
		Delivery(LifecycleOwner owner, SavedStateRegistryOwner savedState, Supplier<Context> context,
				String key, ActivityResultCallback<O> callback) {
			this.owner = owner; registry = savedState.getSavedStateRegistry(); this.context = context;
			this.callback = callback; this.key = "security.picker." + key;
			registry.registerSavedStateProvider(this.key, this);
			owner.getLifecycle().addObserver(this);
		}
		void receive(O value) {
			if (owner.getLifecycle().getCurrentState() == Lifecycle.State.DESTROYED) return;
			Context current = context.get();
			if (finished(current)) { clear(); return; }
			if (!SecurityStore.locked(current)) { clear(); callback.onActivityResult(value); return; }
			// Like ActivityResultRegistry, each launcher owns its latest undelivered result.
			// This record cannot unlock, authorize settings, or cross a destructive profile reset.
			pending = true; result = value;
			try { profile = ProfileGeneration.current(current); }
			catch (IOException e) { clear(); }
		}
		private void drain() {
			if (!pending || !owner.getLifecycle().getCurrentState().isAtLeast(Lifecycle.State.RESUMED)) return;
			Context current = context.get();
			if (finished(current)) { clear(); return; }
			if (SecurityStore.locked(current)) return;
			if (!ProfileGeneration.canWrite(current, profile)) { clear(); return; }
			O delivered = result; clear(); callback.onActivityResult(delivered);
		}
		private void clear() { pending = false; result = null; profile = -1; handler.removeCallbacks(drain); }
		private boolean finished(Context current) {
			return current instanceof android.app.Activity && (((android.app.Activity) current).isFinishing()
					|| ((android.app.Activity) current).isDestroyed());
		}
		@Override public Bundle saveState() {
			Bundle state = new Bundle();
			if (pending) {
				state.putBoolean("pending", true); state.putParcelable("result", result); state.putLong("profile", profile);
			}
			return state;
		}
		@Override public void onStateChanged(LifecycleOwner source, Lifecycle.Event event) {
			if (event == Lifecycle.Event.ON_CREATE) {
				Bundle state = registry.consumeRestoredStateForKey(key);
				if (state != null && state.getBoolean("pending")) {
					try {
						state.setClassLoader(ProtectedActivityResults.class.getClassLoader());
						result = readResult(state); profile = state.getLong("profile", -1); pending = true;
					} catch (RuntimeException e) { clear(); }
				}
			} else if (event == Lifecycle.Event.ON_RESUME) {
				// FragmentManager may still be moving a Fragment through resume. Deliver on
				// the next UI turn, after checking both authorization and owner again.
				handler.post(drain);
			} else if (event == Lifecycle.Event.ON_PAUSE) handler.removeCallbacks(drain);
			else if (event == Lifecycle.Event.ON_DESTROY) {
				clear(); registry.unregisterSavedStateProvider(key); owner.getLifecycle().removeObserver(this);
			}
		}
		@SuppressWarnings({"deprecation", "unchecked"})
		private O readResult(Bundle state) { return (O) state.getParcelable("result"); }
	}
}
