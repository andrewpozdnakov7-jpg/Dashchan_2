package chan.content;

import android.content.Context;
import android.content.res.Configuration;
import android.content.res.Resources;
import java.util.concurrent.atomic.AtomicLong;

/** Process-local resource owner. Resources and their cache generation are published together. */
final class ChanResources {
	private static final AtomicLong NEXT_GENERATION = new AtomicLong();

	static final class Snapshot {
		final Resources resources;
		final long generation;

		Snapshot(Resources resources) {
			this.resources = resources;
			generation = NEXT_GENERATION.incrementAndGet();
		}
	}

	private final Context baseContext;
	private Configuration configuration;
	private volatile Snapshot snapshot;

	ChanResources(Context baseContext, Configuration configuration) {
		this.baseContext = baseContext;
		update(configuration);
	}

	Snapshot getSnapshot() {
		return snapshot;
	}

	synchronized boolean update(Configuration newConfiguration) {
		Configuration copy = new Configuration(newConfiguration);
		if (configuration != null && copy.equals(configuration)) return false;
		Context configured = baseContext.createConfigurationContext(copy);
		Snapshot replacement = new Snapshot(configured.getResources());
		configuration = copy;
		snapshot = replacement;
		return true;
	}
}
