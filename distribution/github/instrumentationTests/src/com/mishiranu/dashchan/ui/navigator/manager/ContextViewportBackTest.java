package com.mishiranu.dashchan.ui.navigator.manager;

import static org.junit.Assert.*;
import android.os.SystemClock;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.TextView;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import androidx.test.core.app.ActivityScenario;
import chan.content.Chan;
import com.mishiranu.dashchan.content.Preferences;
import com.mishiranu.dashchan.content.context.ContextGraph;
import com.mishiranu.dashchan.content.context.ContextSource;
import com.mishiranu.dashchan.content.database.PagesDatabase;
import com.mishiranu.dashchan.content.model.GalleryItem;
import com.mishiranu.dashchan.content.model.Post;
import com.mishiranu.dashchan.content.model.PostItem;
import com.mishiranu.dashchan.content.model.PostNumber;
import com.mishiranu.dashchan.widget.BackPreviewBudget;
import com.mishiranu.dashchan.widget.HistoryBackPreview;
import com.mishiranu.dashchan.widget.ListPosition;
import com.mishiranu.dashchan.widget.PredictiveTransition;
import com.mishiranu.dashchan.widget.StandardContentTestActivity;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Map;
import java.util.Set;
import java.util.function.BooleanSupplier;
import org.junit.Assume;
import org.junit.Test;

/** Production ContextDialogProvider/graph/apply/Back with a measured RecyclerView and local data. ADDED / NOT RUN. */
public class ContextViewportBackTest {
	private static Object field(Object owner, String name) {
		try { java.lang.reflect.Field f = owner.getClass().getDeclaredField(name); f.setAccessible(true); return f.get(owner); }
		catch (ReflectiveOperationException e) { throw new AssertionError(e); }
	}
	private static final class Fixture implements AutoCloseable {
		final ActivityScenario<StandardContentTestActivity> activity;
		final Map<PostNumber, PostItem> items = new HashMap<>();
		final Set<PostNumber> hidden = new HashSet<>();
		final ArrayList<PostItem> rendered = new ArrayList<>();
		FrameLayout root; RecyclerView list; RecyclerView.Adapter<RecyclerView.ViewHolder> adapter;
		ContextDialogProvider provider; ContextDialogProvider.Factory factory;
		UiManager ui; UiManager.ConfigurationSet source;
		Object old; long budgetBefore;
		Fixture() {
			Assume.assumeTrue(android.os.Build.VERSION.SDK_INT >= 34 && android.animation.ValueAnimator.areAnimatorsEnabled());
			activity = ActivityScenario.launch(StandardContentTestActivity.class);
			activity.onActivity(a -> {
				old = Preferences.PREFERENCES.getAll().get(Preferences.KEY_PREDICTIVE_BACK);
				Preferences.PREFERENCES.edit().put(Preferences.KEY_PREDICTIVE_BACK, true).close();
				budgetBefore = BackPreviewBudget.usedBytes();
				root = new FrameLayout(a); list = new RecyclerView(a); list.setLayoutManager(new LinearLayoutManager(a));
				root.addView(list, new FrameLayout.LayoutParams(-1, -1)); a.setContentView(root);
				adapter = new RecyclerView.Adapter<RecyclerView.ViewHolder>() {
					@Override public RecyclerView.ViewHolder onCreateViewHolder(ViewGroup parent, int type) {
						TextView text = new TextView(a); text.setBackgroundColor(0xffe8eef4);
						text.setLayoutParams(new RecyclerView.LayoutParams(-1, Math.round(160 * a.getResources().getDisplayMetrics().density)));
						return new RecyclerView.ViewHolder(text) {};
					}
					@Override public void onBindViewHolder(RecyclerView.ViewHolder holder, int position) {
						PostItem post = rendered.get(position);
						((TextView) holder.itemView).setText(provider.placeholder(post) != null ? "placeholder" : post.getPostNumber().toString());
					}
					@Override public int getItemCount() { return rendered.size(); }
				};
				list.setAdapter(adapter);
				ui = new UiManager(a, new UiManager.Callback() {
					@Override public void onDialogStackOpen() {}
					@Override public com.mishiranu.dashchan.content.service.DownloadService.Binder getDownloadBinder() { return null; }
					@Override public com.mishiranu.dashchan.content.service.WatcherService.Client getWatcherClient() { return null; }
				}, (UiManager.LocalNavigator) java.lang.reflect.Proxy.newProxyInstance(UiManager.LocalNavigator.class.getClassLoader(),
						new Class<?>[]{UiManager.LocalNavigator.class}, (proxy, method, args) -> { throw new AssertionError("No external navigation"); }));
				Chan chan = Chan.get("dvach"); assertNotNull(chan.name);
				ContextGraph graph = new ContextGraph(); Map<ContextGraph.Key, Post> raw = new HashMap<>();
				for (int n = 1; n <= 60; n++) {
					Post.Builder builder = new Post.Builder(); builder.number = new PostNumber(n, 0); builder.comment = "local context fixture";
					Post post = builder.build(false); ContextGraph.Key key = ContextSource.key(chan.name, "fixture", "1", post.number);
					raw.put(key, post); graph.add(key, n > 1 ? Collections.singleton(
							ContextSource.key(chan.name, "fixture", "1", new PostNumber(n - 1, 0))) : Collections.emptySet());
					items.put(post.number, PostItem.createPost(post, chan, "fixture", "1", new PostNumber(1, 0)));
				}
				PagesDatabase db = PagesDatabase.getInstance();
				ContextSource.Snapshot data = new ContextSource.Snapshot(graph, raw, false, false,
						db.getCacheState(new PagesDatabase.ThreadKey(chan.name, "fixture", "1")), db.getContextEpoch(),
						ContextSource.key(chan.name, "fixture", "1", new PostNumber(20, 0)));
				UiManager.PostsProvider posts = new UiManager.PostsProvider() {
					@Override public Iterator<PostItem> iterator() { return items.values().iterator(); }
					@Override public PostItem findPostItem(PostNumber number) { return items.get(number); }
					@Override public ContextSource.Snapshot getContextSnapshot() { return data; }
				};
				source = new UiManager.ConfigurationSet(chan.name, null, posts, new UiManager.PostStateProvider() {
					@Override public boolean isHiddenResolve(PostItem post) { return hidden.contains(post.getPostNumber()); }
				}, post -> new GalleryItem.Set(true), a.getSupportFragmentManager(), null, null, null,
						false, false, false, false, false, null);
				factory = new ContextDialogProvider.Factory(chan.name, items.get(new PostNumber(20, 0)));
				bind();
			});
			await(this::ready);
		}
		void main(Runnable action) { activity.onActivity(a -> action.run()); }
		void bind() {
			provider = factory.create(ui, source); provider.createControls(list); provider.onViewBound(root);
			// Keep the production listener private; attach the fixture through its real setter.
			try {
				Class<?> listener = Class.forName(DialogUnit.class.getName() + "$StateListener");
				Object callback = java.lang.reflect.Proxy.newProxyInstance(listener.getClassLoader(),
						new Class<?>[]{listener}, (proxy, method, args) -> {
							if (method.getDeclaringClass() == Object.class) {
								if ("equals".equals(method.getName())) return proxy == args[0];
								if ("hashCode".equals(method.getName())) return System.identityHashCode(proxy);
								return "ContextStateListenerFixture";
							}
							rendered.clear(); for (PostItem post : provider) rendered.add(post);
							adapter.notifyDataSetChanged(); return true;
						});
				java.lang.reflect.Method setter = DialogUnit.DialogProvider.class.getDeclaredMethod("setStateListener", listener);
				setter.setAccessible(true); setter.invoke(provider, callback);
			} catch (ReflectiveOperationException e) { throw new AssertionError(e); }
		}
		boolean ready() {
			return !(boolean) field(provider, "loading") && field(provider, "restoreDraw") == null && list.getChildCount() > 0;
		}
		void await(BooleanSupplier condition) {
			boolean[] done = {false};
			for (int i = 0; i < 150; i++) { main(() -> done[0] = condition.getAsBoolean()); if (done[0]) return; SystemClock.sleep(40); }
			fail("Local context fixture timed out");
		}
		void scroll(int position, int offset) {
			main(() -> ((LinearLayoutManager) list.getLayoutManager()).scrollToPositionWithOffset(position, offset));
			await(() -> {
				ListPosition actual = ListPosition.obtain(list, null);
				return ready() && !list.isLayoutRequested() && actual != null
						&& actual.position == position && actual.offset == offset && field(provider, "currentFrame") != null
						&& (long) field(provider, "preparedScrollRevision") == (long) field(provider, "scrollRevision");
			});
		}
		ContextDialogProvider.Factory.Viewport viewport() {
			ListPosition p = ListPosition.obtain(list, null); assertNotNull(p);
			return new ContextDialogProvider.Factory.Viewport(ContextSource.key(source.chanName, "fixture", "1",
					rendered.get(p.position).getPostNumber()), p.offset, list);
		}
		void toB() { main(() -> sourceSelect(25)); await(this::ready); }
		void sourceSelect(int number) { provider.configurationSet.contextSelect.accept(items.get(new PostNumber(number, 0))); }
		void assertViewport(ContextDialogProvider.Factory.Viewport expected) {
			ContextDialogProvider.Factory.Viewport actual = viewport(); assertEquals(expected.anchor, actual.anchor);
			assertEquals(expected.offset, actual.offset);
		}
		@Override public void close() {
			main(() -> {
				provider.onCancel(); assertEquals(budgetBefore, BackPreviewBudget.usedBytes());
				if (old instanceof Boolean) Preferences.PREFERENCES.edit().put(Preferences.KEY_PREDICTIVE_BACK, (Boolean) old).close();
				else Preferences.PREFERENCES.edit().remove(Preferences.KEY_PREDICTIVE_BACK).close();
			}); activity.close();
		}
	}
	@Test public void productionProviderCommitRestoresThePreviousAnchorAndOffsetBeforeHandoff() {
		try (Fixture f = new Fixture()) {
			f.scroll(5, -31); ContextDialogProvider.Factory.Viewport[] a = {null};
			f.main(() -> a[0] = f.viewport()); f.toB(); f.scroll(7, -19);
			HistoryBackPreview[] back = {null};
			f.main(() -> {
				back[0] = f.provider.startBackPreview(true); assertNotNull(back[0]); back[0].progress(.7f);
				assertTrue(back[0].commit(() -> assertTrue(f.provider.onBackPressed())));
				((PredictiveTransition) field(back[0], "transition")).finish();
			});
			f.await(() -> f.ready() && !back[0].isBusy());
			f.main(() -> { f.assertViewport(a[0]); assertEquals(20, f.factory.target.major()); assertEquals(0, f.factory.history.size()); });
		}
	}
	@Test public void productionProviderCancelKeepsCurrentViewportSelectionAndHistory() {
		try (Fixture f = new Fixture()) {
			f.scroll(5, -31); f.toB(); f.scroll(7, -19);
			f.main(() -> {
				ContextDialogProvider.Factory.Viewport b = f.viewport();
				HistoryBackPreview back = f.provider.startBackPreview(false); assertNotNull(back);
				back.progress(.7f); back.cancel(); back.close(); back.close();
				f.assertViewport(b); assertEquals(25, f.factory.target.major()); assertEquals(1, f.factory.history.size());
			});
		}
	}
	@Test public void hiddenAnchorOrIncompatibleWidthUsesAVisibleTargetWithoutAnOuterPop() {
		for (boolean hide : new boolean[]{true, false}) try (Fixture f = new Fixture()) {
			f.scroll(5, -31); ContextDialogProvider.Factory.Viewport[] a = {null};
			f.main(() -> a[0] = f.viewport()); f.toB(); f.scroll(7, -19);
			f.main(() -> {
				if (hide) {
					PostNumber number = ContextSource.number(a[0].anchor); f.hidden.add(number);
					f.provider.onPostItemMessage(f.items.get(number), UiManager.Message.PERFORM_SWITCH_HIDE);
				} else { f.list.getLayoutParams().width = f.list.getWidth() / 2; f.list.requestLayout(); }
			});
			f.await(() -> f.ready() && (hide || f.list.getWidth() == f.list.getLayoutParams().width));
			f.main(() -> { assertNull(f.provider.startBackPreview(true)); assertTrue(f.provider.onBackPressed()); });
			f.await(f::ready);
			f.main(() -> {
				assertEquals(20, f.viewport().anchor.major()); assertEquals(0, f.viewport().offset);
				assertEquals(0, f.factory.history.size()); assertTrue(f.root.isShown()); assertFalse((boolean) field(f.provider, "closed"));
			});
		}
	}
	@Test public void recycledProviderKeepsLogicalHistoryAndRestoresItsViewportWithoutOldViews() {
		try (Fixture f = new Fixture()) {
			f.scroll(5, -31); ContextDialogProvider.Factory.Viewport[] a = {null}, b = {null};
			f.main(() -> a[0] = f.viewport()); f.toB(); f.scroll(7, -19);
			f.main(() -> { b[0] = f.viewport(); f.provider.onCancel(); f.bind(); });
			f.await(f::ready);
			f.main(() -> { f.assertViewport(b[0]); assertTrue(f.provider.onBackPressed()); });
			f.await(f::ready); f.main(() -> f.assertViewport(a[0]));
		}
	}
}
