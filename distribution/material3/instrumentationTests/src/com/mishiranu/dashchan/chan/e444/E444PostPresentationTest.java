package com.mishiranu.dashchan.chan.e444;

import static org.junit.Assert.*;
import android.content.Context;
import android.content.res.Configuration;
import android.graphics.Bitmap;
import android.graphics.drawable.BitmapDrawable;
import android.util.LruCache;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;
import androidx.test.platform.app.InstrumentationRegistry;
import chan.content.Chan;
import com.mishiranu.dashchan.content.Preferences;
import com.mishiranu.dashchan.content.model.Post;
import com.mishiranu.dashchan.content.model.PostItem;
import com.mishiranu.dashchan.content.model.PostNumber;
import com.mishiranu.dashchan.widget.InterfaceAppearance;
import com.mishiranu.dashchan.widget.ThemeEngine;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.Test;

/** Synthetic, detached post only. No real votes, reactions, accounts or icon downloads. NOT executed. */
public class E444PostPresentationTest {
	private static Object field(Object target, String name) throws ReflectiveOperationException {
		Field f = E444PostView.class.getDeclaredField(name); f.setAccessible(true); return f.get(target);
	}
	private static void actions(View root, List<TextView> result, AtomicInteger clicks) {
		if (root instanceof TextView && root.isClickable()) {
			result.add((TextView) root); root.setOnClickListener(v -> clicks.incrementAndGet());
		}
		if (root instanceof ViewGroup) for (int i = 0; i < ((ViewGroup) root).getChildCount(); i++) actions(((ViewGroup) root).getChildAt(i), result, clicks);
	}
	@Test public void syntheticPostWrapsWithoutOverlappingAndRefreshKeepsIconsStateAndCallbacks() {
		InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
			Object previous = Preferences.PREFERENCES.getAll().get(Preferences.KEY_NEW_INTERFACE_MOTION);
			Bitmap bitmap = Bitmap.createBitmap(12, 12, Bitmap.Config.ARGB_8888);
			try {
				Preferences.PREFERENCES.edit().put(Preferences.KEY_NEW_INTERFACE_MOTION, true).close();
				@SuppressWarnings("unchecked") LruCache<String, Bitmap> cache = (LruCache<String, Bitmap>) field(null, "ICONS");
				cache.put("codex_fixture_icon", bitmap);
				for (float fontScale : new float[]{1, 2}) {
					Context base = InstrumentationRegistry.getInstrumentation().getTargetContext();
					Configuration config = new Configuration(base.getResources().getConfiguration()); config.fontScale = fontScale;
					Context context = ThemeEngine.attach(base.createConfigurationContext(config)); ThemeEngine.applyTheme(context);
					E444Model.Post model = new E444Model.Post(); model.num = 2147000000; model.board = "codex_fixture";
					model.menu = new ArrayList<>(); model.reactions = new ArrayList<>();
					E444Model.MenuSection section = new E444Model.MenuSection(); section.sectionName = "Fixture section";
					E444Model.MenuLink link = new E444Model.MenuLink(); link.label = "Long synthetic link label with wrapping"; link.url = "/fixture";
					section.links = Arrays.asList(link); model.menu.add(section);
					E444Model.Reaction reaction = new E444Model.Reaction(); reaction.icon = "codex_fixture_icon"; reaction.count = 123456;
					model.reactions.add(reaction); model.answers = Arrays.asList("A very long synthetic poll answer that needs several lines", "Short answer");
					model.poll_results_exact = Arrays.asList(0, 123456);
					Post.Builder post = new Post.Builder(); post.number = new PostNumber(model.num, 0); post.e444Data = E444PostData.encode(model);
					PostItem item = PostItem.createPost(post.build(false), Chan.get("e444"), model.board, "2147000000", post.number);
					Map<String, ?> polls = context.getSharedPreferences("e444_polls", Context.MODE_PRIVATE).getAll();
					Map<String, ?> reactions = context.getSharedPreferences("e444_reactions", Context.MODE_PRIVATE).getAll();
					E444PostView view = new E444PostView(context); view.bind(null, item, "e444");
					assertFalse(view.isAttachedToWindow()); Object generation = field(view, "generation");
					AtomicInteger clicks = new AtomicInteger(); List<TextView> buttons = new ArrayList<>(); actions(view, buttons, clicks);
					assertFalse(buttons.isEmpty()); TextView chosen = buttons.get(1); chosen.setSelected(true);
					InterfaceAppearance.refreshTree(view); float size = chosen.getTextSize();
					InterfaceAppearance.refreshTree(view); assertEquals(size, chosen.getTextSize(), .01f);
					assertTrue(chosen.isSelected()); assertSame(generation, field(view, "generation"));
					assertTrue(((List<?>) field(view, "tasks")).isEmpty()); assertEquals(0, clicks.get());
					assertEquals(polls, context.getSharedPreferences("e444_polls", Context.MODE_PRIVATE).getAll());
					assertEquals(reactions, context.getSharedPreferences("e444_reactions", Context.MODE_PRIVATE).getAll());
					assertFalse(view.getChildAt(0).isClickable()); assertFalse(view.getChildAt(0).isFocusable());
					int width = Math.round(224 * context.getResources().getDisplayMetrics().density);
					view.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED));
					view.layout(0, 0, width, view.getMeasuredHeight());
					for (TextView button : buttons) {
						int target = Math.round(48 * context.getResources().getDisplayMetrics().density);
						assertTrue(button.getWidth() >= target); assertTrue(button.getHeight() >= target);
						assertNull(button.getCompoundDrawableTintList());
						for (TextView other : buttons) if (button != other && button.getParent() == other.getParent()) {
							assertFalse(button.getLeft() < other.getRight() && other.getLeft() < button.getRight()
									&& button.getTop() < other.getBottom() && other.getTop() < button.getBottom());
						}
					}
					TextView icon = buttons.get(buttons.size() - 1);
					assertSame(bitmap, ((BitmapDrawable) icon.getCompoundDrawables()[0]).getBitmap());
					view.setInteractionEnabled(false); for (TextView button : buttons) assertFalse(button.isEnabled());
					view.setInteractionEnabled(true); icon.performClick(); assertEquals(1, clicks.get());
				}
				cache.remove("codex_fixture_icon");
			} catch (ReflectiveOperationException e) { throw new AssertionError(e); }
			finally {
				bitmap.recycle();
				if (previous instanceof Boolean) Preferences.PREFERENCES.edit().put(Preferences.KEY_NEW_INTERFACE_MOTION, (Boolean) previous).close();
				else Preferences.PREFERENCES.edit().remove(Preferences.KEY_NEW_INTERFACE_MOTION).close();
			}
		});
	}
}
