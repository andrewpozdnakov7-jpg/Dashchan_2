package com.mishiranu.dashchan.ui;

import static org.junit.Assert.*;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.os.SystemClock;
import android.view.MotionEvent;
import android.view.ViewGroup;
import android.widget.TextView;
import androidx.annotation.NonNull;
import androidx.recyclerview.widget.DefaultItemAnimator;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import androidx.test.core.app.ActivityScenario;
import androidx.test.platform.app.InstrumentationRegistry;
import com.mishiranu.dashchan.content.Preferences;
import com.mishiranu.dashchan.content.model.PostNumber;
import com.mishiranu.dashchan.util.ResourceUtils;
import com.mishiranu.dashchan.widget.PaddedRecyclerView;
import com.mishiranu.dashchan.widget.PostingGestureTestActivity;
import com.mishiranu.dashchan.widget.ThemeEngine;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.Assume;
import org.junit.Test;

/** Real touch/helper dispatch fixture; does not navigate a real thread or request a real IME. */
public class ReplySwipeControllerTest {
	private static final class Holder extends RecyclerView.ViewHolder {
		Holder(TextView text) { super(text); }
	}
	private static final class Items extends RecyclerView.Adapter<Holder> {
		@Override public int getItemCount() { return 4; }
		@NonNull @Override public Holder onCreateViewHolder(@NonNull ViewGroup parent, int type) {
			TextView text = new TextView(parent.getContext()); text.setText("Post");
			text.setLayoutParams(new RecyclerView.LayoutParams(-1, Math.round(96 * ResourceUtils.obtainDensity(text))));
			return new Holder(text);
		}
		@Override public void onBindViewHolder(@NonNull Holder holder, int position) {}
	}
	private static final class Fixture implements AutoCloseable {
		final PaddedRecyclerView list;
		final ReplySwipeController controller;
		final LinkedBlockingQueue<PostNumber> replies = new LinkedBlockingQueue<>();
		final DefaultItemAnimator unrelatedAnimator = new DefaultItemAnimator() {
			@Override public boolean isRunning() { return true; }
		};
		long down;
		float startX, y, endX;
		Fixture(PostingGestureTestActivity activity) {
			list = new PaddedRecyclerView(ThemeEngine.attach(activity)); list.setLayoutManager(new LinearLayoutManager(activity));
			Items items = new Items(); list.setAdapter(items); list.setItemAnimator(unrelatedAnimator); activity.setContentView(list);
			controller = new ReplySwipeController(list, items, new ReplySwipeController.Host() {
				@Override public PostNumber post(RecyclerView.ViewHolder holder) {
					int position = holder.getBindingAdapterPosition(); return position >= 0 ? new PostNumber(position + 1, 0) : null;
				}
				@Override public boolean canReply() { return true; }
				@Override public void reply(PostNumber post, ReplySwipeOverlap.Request request) {
					assertTrue(request.getAsBoolean());
					// No Activity presentation here: an early request must fall back to the completed return.
					if (!request.prepare(null, null, null)) return;
					for (int i = 0; i < list.getChildCount(); i++) assertEquals(0, list.getChildAt(i).getTranslationX(), .01f);
					replies.add(post);
				}
			});
			controller.resume();
		}
		void begin(int position) {
			Assume.assumeTrue(Preferences.isSwipeReplyEnabled() && list.hasWindowFocus());
			Holder holder = (Holder) list.findViewHolderForAdapterPosition(position); assertNotNull(holder);
			float density = ResourceUtils.obtainDensity(list); Assume.assumeTrue(list.getWidth() > 200 * density);
			boolean right = Preferences.isVideoRightHandControls();
			startX = list.getWidth() * (right ? .25f : .75f); endX = startX + (right ? 90 : -90) * density;
			y = (holder.itemView.getTop() + holder.itemView.getBottom()) / 2f; down = SystemClock.uptimeMillis();
			event(MotionEvent.ACTION_DOWN, startX); event(MotionEvent.ACTION_MOVE, endX);
			Bitmap frame = Bitmap.createBitmap(list.getWidth(), list.getHeight(), Bitmap.Config.ARGB_8888);
			try { list.draw(new Canvas(frame)); } finally { frame.recycle(); }
		}
		void event(int action, float x) {
			MotionEvent event = MotionEvent.obtain(down, SystemClock.uptimeMillis(), action, x, y, 0);
			try { list.dispatchTouchEvent(event); } finally { event.recycle(); }
		}
		@Override public void close() { controller.close(); }
	}
	@Test public void twoRepliesDoNotWaitForAnUnrelatedAnimatorOrRetainSuccessfulSwipeCleanup() throws Exception {
		try (ActivityScenario<PostingGestureTestActivity> scenario = ActivityScenario.launch(PostingGestureTestActivity.class)) {
			AtomicReference<Fixture> fixture = new AtomicReference<>();
			scenario.onActivity(activity -> fixture.set(new Fixture(activity)));
			InstrumentationRegistry.getInstrumentation().waitForIdleSync();
			try {
				for (int position = 0; position < 2; position++) {
					int index = position;
					scenario.onActivity(activity -> { fixture.get().begin(index); fixture.get().event(MotionEvent.ACTION_UP, fixture.get().endX); });
					assertEquals(new PostNumber(position + 1, 0), fixture.get().replies.poll(5, TimeUnit.SECONDS));
					scenario.onActivity(activity -> assertSame(fixture.get().unrelatedAnimator, fixture.get().list.getItemAnimator()));
				}
				assertTrue(fixture.get().replies.isEmpty());
			} finally { scenario.onActivity(activity -> fixture.get().close()); }
		}
	}
}
