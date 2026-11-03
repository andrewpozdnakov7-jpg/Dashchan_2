package com.mishiranu.dashchan.widget;

import static org.junit.Assert.*;
import android.content.Context;
import android.content.res.Configuration;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Outline;
import android.graphics.RectF;
import android.view.View;
import android.view.ViewOutlineProvider;
import androidx.core.graphics.ColorUtils;
import androidx.recyclerview.widget.RecyclerView;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import org.junit.Test;
import org.junit.runner.RunWith;

/** Draw-only fixtures. No touch injection, hidden flags, real adapters, preferences or data. ADDED / NOT RUN. */
@RunWith(AndroidJUnit4.class)
public class ThreadHideSwipeAppearanceTest {
	private RecyclerView list(float fontScale) {
		Context base = InstrumentationRegistry.getInstrumentation().getTargetContext();
		Configuration config = new Configuration(base.getResources().getConfiguration()); config.fontScale = fontScale;
		RecyclerView view = new RecyclerView(PostingTestContext.create(base.createConfigurationContext(config)));
		view.layout(0, 0, 400, 300); view.setPadding(10, 10, 10, 10); return view;
	}
	@Test public void bothDirectionsClipToTheOriginalCellAndNeverPaintOverTheCardOrNeighbors() {
		InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
			RecyclerView list = list(1); ThreadHideSwipeAppearance appearance = new ThreadHideSwipeAppearance(list.getContext());
			ThreadHideSwipeAppearance.Shape cell = new ThreadHideSwipeAppearance.Shape(new RectF(20, 20, 180, 220), 0);
			for (int direction : new int[] {-1, 1}) {
				Bitmap bitmap = Bitmap.createBitmap(400, 300, Bitmap.Config.ARGB_8888);
				try {
					appearance.draw(new Canvas(bitmap), list, cell, direction * 40, .2f);
					assertEquals(Color.TRANSPARENT, bitmap.getPixel(200, 100)); // next grid cell
					assertEquals(Color.TRANSPARENT, bitmap.getPixel(100, 5)); // viewport inset
					assertEquals(Color.TRANSPARENT, bitmap.getPixel(100, 100)); // still covered by the card
					assertEquals(StandardAppearance.colors(list.getContext()).primaryContainer,
							bitmap.getPixel(direction < 0 ? 179 : 21, 25));
				} finally { bitmap.recycle(); }
			}
		});
	}
	@Test public void shapeUsesTheRealOutlineAndTheNarrowLargeFontCellRemainsDrawOnly() {
		InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
			RecyclerView list = list(2); View card = new View(list.getContext()); card.layout(20, 30, 180, 230);
			card.setOutlineProvider(new ViewOutlineProvider() {
				@Override public void getOutline(View view, Outline outline) { outline.setRoundRect(3, 4, 157, 196, 7); }
			});
			ThreadHideSwipeAppearance appearance = new ThreadHideSwipeAppearance(list.getContext());
			ThreadHideSwipeAppearance.Shape actual = appearance.shape(card);
			assertEquals(new RectF(23, 34, 177, 226), actual.bounds); assertEquals(7f, actual.radius, .01f);
			Bitmap bitmap = Bitmap.createBitmap(400, 300, Bitmap.Config.ARGB_8888);
			try {
				Canvas canvas = new Canvas(bitmap);
				appearance.draw(canvas, list, new ThreadHideSwipeAppearance.Shape(new RectF(20, 20, 65, 220), 0), 45, 1);
				assertEquals(Color.TRANSPARENT, bitmap.getPixel(100, 100));
				assertEquals(1f, card.getAlpha(), 0); assertEquals(0f, card.getTranslationX(), 0);
				AppearanceColors colors = StandardAppearance.colors(list.getContext());
				assertTrue(ColorUtils.calculateContrast(colors.onPrimaryContainer, colors.primaryContainer) >= 4.5);
			} finally { bitmap.recycle(); }
		});
	}
}
