package com.mishiranu.dashchan.content.async;

import static org.junit.Assert.*;
import androidx.lifecycle.Lifecycle;
import androidx.lifecycle.LifecycleOwner;
import androidx.lifecycle.LifecycleRegistry;
import androidx.lifecycle.ViewModelStore;
import androidx.lifecycle.ViewModel;
import androidx.lifecycle.ViewModelProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import com.mishiranu.dashchan.content.model.Post;
import com.mishiranu.dashchan.content.model.PostNumber;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.junit.Test;
import org.junit.runner.RunWith;

/** Real owner/lifecycle/result delivery; the existing task is queued but never runs archive IO. */
@RunWith(AndroidJUnit4.class)
public class LocalArchiveViewModelTest {
	private static final class Owner implements LifecycleOwner {
		final LifecycleRegistry lifecycle = new LifecycleRegistry(this);
		Owner() { lifecycle.setCurrentState(Lifecycle.State.STARTED); }
		@Override public Lifecycle getLifecycle() { return lifecycle; }
		void destroy() { lifecycle.setCurrentState(Lifecycle.State.DESTROYED); }
	}

	private static final class RecordingTask extends SendLocalArchiveTask {
		final Callback callback;
		int cancellations;
		RecordingTask(Callback callback, LocalArchiveViewModel.Request request) {
			super(callback, null, request.boardName, request.threadNumber, request.threadTitle, request.posts,
					request.saveThumbnails, request.saveFiles, request.createZip);
			this.callback = callback;
		}
		@Override protected Result run() { throw new AssertionError("Archive IO must not run in this test"); }
		@Override public void cancel() { cancellations++; super.cancel(); }
		boolean cancelled() { return isCancelled(); }
	}

	private static final class Fixture {
		final ViewModelStore store = new ViewModelStore();
		final ArrayList<Runnable> queued = new ArrayList<>();
		final LocalArchiveViewModel model;
		LocalArchiveViewModel.Request received;
		RecordingTask task;
		int creations;
		Fixture() {
			LocalArchiveViewModel operation = new LocalArchiveViewModel((callback, request) -> {
				creations++;
				received = request;
				task = new RecordingTask(callback, request);
				return task;
			}, queued::add);
			model = new ViewModelProvider(store, new ViewModelProvider.Factory() {
				@Override public <T extends ViewModel> T create(Class<T> modelClass) {
					return modelClass.cast(operation);
				}
			}).get(LocalArchiveViewModel.class);
		}
		void close() {
			store.clear();
			// A manually completed fake task can still be queued; never execute it during cleanup.
			if (task != null && !task.cancelled()) task.cancel();
			queued.clear();
		}
	}

	private static Post post(int number) {
		Post.Builder builder = new Post.Builder();
		builder.number = new PostNumber(number, 0);
		builder.comment = "fixture";
		return builder.build(false);
	}
	private static LocalArchiveViewModel.Request request(List<Post> posts, int options) {
		return new LocalArchiveViewModel.Request("fixture-forum", "board", "123", "thread title", posts,
				(options & 1) != 0, (options & 2) != 0, (options & 4) != 0);
	}

	@Test public void requestFreezesPostOrderAndPassesAllOptionsWithoutNormalizing() {
		InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
			Post first = post(3), second = post(1);
			ArrayList<Post> posts = new ArrayList<>(Arrays.asList(first, second));
			LocalArchiveViewModel.Request frozen = request(posts, 2);
			posts.clear();
			assertEquals(Arrays.asList(first, second), frozen.posts);
			try { frozen.posts.clear(); fail("Request posts must be immutable"); }
			catch (UnsupportedOperationException expected) {}
			for (int options = 0; options < 8; options++) {
				Fixture fixture = new Fixture();
				try {
					LocalArchiveViewModel.Request supplied = request(frozen.posts, options);
					fixture.model.startIfNeeded(supplied);
					assertSame(supplied, fixture.received);
					assertEquals("fixture-forum", fixture.received.chanName);
					assertEquals("board", fixture.received.boardName);
					assertEquals("123", fixture.received.threadNumber);
					assertEquals("thread title", fixture.received.threadTitle);
					assertEquals(Arrays.asList(first, second), fixture.received.posts);
					assertEquals((options & 1) != 0, fixture.received.saveThumbnails);
					assertEquals((options & 2) != 0, fixture.received.saveFiles);
					assertEquals((options & 4) != 0, fixture.received.createZip);
					assertEquals(1, fixture.queued.size());
					assertSame(fixture.task, fixture.model.getTask());
				} finally { fixture.close(); }
			}
		});
	}

	@Test public void reconnectingUiKeepsTaskAndProgressWithoutCancellingOrRestarting() {
		InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
			Fixture fixture = new Fixture();
			Owner first = new Owner(), replacement = new Owner();
			try {
				LocalArchiveViewModel.Request supplied = request(Arrays.asList(post(1)), 7);
				fixture.model.startIfNeeded(supplied);
				ArrayList<Integer> progress = new ArrayList<>();
				fixture.model.progress.observe(first, progress::add);
				fixture.task.callback.onLocalArchivationProgressUpdate(17);
				assertEquals(Arrays.asList(17), progress);
				first.destroy();
				fixture.model.startIfNeeded(supplied);
				progress.clear();
				fixture.model.progress.observe(replacement, progress::add);
				assertEquals(Arrays.asList(17), progress);
				assertEquals(1, fixture.creations);
				assertEquals(1, fixture.queued.size());
				assertSame(fixture.task, fixture.model.getTask());
				assertFalse(fixture.task.cancelled());
			} finally { first.destroy(); replacement.destroy(); fixture.close(); }
		});
	}

	@Test public void preparedContinuationWaitsForUiAndIsDeliveredOnlyOnce() {
		InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
			Fixture fixture = new Fixture();
			Owner delivery = new Owner(), replacement = new Owner();
			try {
				LocalArchiveViewModel.Request supplied = request(Arrays.asList(post(1)), 0);
				fixture.model.startIfNeeded(supplied);
				int[] handoffs = {0};
				SendLocalArchiveTask.DownloadResult continuation = binder -> handoffs[0]++;
				fixture.task.callback.onLocalArchivationComplete(continuation);
				assertTrue(fixture.model.hasTaskOrValue());
				fixture.model.startIfNeeded(supplied);
				assertEquals(1, fixture.creations);
				assertEquals(0, handoffs[0]);
				fixture.model.observe(delivery, result -> {
					assertEquals(LocalArchiveViewModel.PreparationResult.Status.PREPARED, result.status);
					assertSame(continuation, result.downloadResult);
					// The fake continuation ignores the binder: no service or filesystem is touched.
					result.downloadResult.run(null);
				});
				assertEquals(1, handoffs[0]);
				assertFalse(fixture.model.hasTaskOrValue());
				delivery.destroy();
				fixture.model.startIfNeeded(supplied);
				fixture.model.observe(replacement, result -> fail("Consumed result must not replay"));
				assertEquals(1, fixture.creations);
				fixture.store.clear();
				assertEquals(0, fixture.task.cancellations);
				assertEquals(1, handoffs[0]);
			} finally { delivery.destroy(); replacement.destroy(); fixture.close(); }
		});
	}

	@Test public void preparationFailureIsAnExplicitErrorWithoutDownloadContinuation() {
		InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
			Fixture fixture = new Fixture();
			Owner owner = new Owner();
			try {
				LocalArchiveViewModel.Request supplied = request(Arrays.asList(post(1)), 7);
				fixture.model.startIfNeeded(supplied);
				fixture.task.callback.onLocalArchivationComplete(null);
				fixture.model.startIfNeeded(supplied);
				int[] errors = {0};
				fixture.model.observe(owner, result -> {
					assertEquals(LocalArchiveViewModel.PreparationResult.Status.ERROR, result.status);
					assertNull(result.downloadResult);
					errors[0]++;
				});
				assertEquals(1, errors[0]);
				assertEquals(1, fixture.creations);
				fixture.model.startIfNeeded(supplied);
				assertEquals(1, fixture.creations);
			} finally { owner.destroy(); fixture.close(); }
		});
	}

	@Test public void clearingDialogScopeCancelsTheActivePreparation() {
		InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
			Fixture fixture = new Fixture();
			try {
				fixture.model.startIfNeeded(request(Arrays.asList(post(1)), 0));
				assertFalse(fixture.task.cancelled());
				fixture.store.clear();
				assertTrue(fixture.task.cancelled());
				assertEquals(1, fixture.task.cancellations);
				assertNull(fixture.model.getTask());
			} finally { fixture.close(); }
		});
	}

	@Test public void separateDialogScopesMaySaveTheSameThreadAgain() {
		InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
			Fixture first = new Fixture(), second = new Fixture();
			try {
				LocalArchiveViewModel.Request supplied = request(Arrays.asList(post(1)), 0);
				first.model.startIfNeeded(supplied);
				second.model.startIfNeeded(supplied);
				assertEquals(1, first.creations);
				assertEquals(1, second.creations);
				assertNotSame(first.task, second.task);
			} finally { first.close(); second.close(); }
		});
	}
}
