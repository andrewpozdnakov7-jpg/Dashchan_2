package com.mishiranu.dashchan.widget;

import static org.junit.Assert.*;
import android.content.Context;
import android.content.res.XmlResourceParser;
import android.util.AttributeSet;
import android.util.Xml;
import android.view.View;
import android.view.ViewGroup;
import androidx.annotation.NonNull;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import com.mishiranu.dashchan.R;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.xmlpull.v1.XmlPullParser;

/** Checks the real framework scrollbar flag without relying on hidden drawing callbacks. */
@RunWith(AndroidJUnit4.class)
public class PaddedRecyclerViewScrollBarTest {
	private static final class TestList extends PaddedRecyclerView {
		private int range;
		private int extent;

		TestList(Context context) {
			super(context);
			configure();
		}

		TestList(Context context, AttributeSet attrs, int defStyleAttr) {
			super(context, attrs, defStyleAttr);
			configure();
		}

		TestList(Context context, AttributeSet attrs) {
			super(context, attrs);
			configure();
		}

		private void configure() {
			setLayoutManager(new LinearLayoutManager(getContext()));
			setAdapter(new RecyclerView.Adapter<RecyclerView.ViewHolder>() {
				@NonNull @Override public RecyclerView.ViewHolder onCreateViewHolder(
						@NonNull ViewGroup parent, int viewType) {
					View view = new View(parent.getContext());
					view.setLayoutParams(new RecyclerView.LayoutParams(100, 100));
					return new RecyclerView.ViewHolder(view) {};
				}
				@Override public void onBindViewHolder(@NonNull RecyclerView.ViewHolder holder, int position) {}
				@Override public int getItemCount() { return 20; }
			});
		}

		@Override public int computeVerticalScrollRange() { return range; }
		@Override public int computeVerticalScrollExtent() { return extent; }
		@Override public int computeVerticalScrollOffset() { return 0; }

		void refreshMetrics(int range, int extent) {
			this.range = range;
			this.extent = extent;
			forceLayout();
			measure(View.MeasureSpec.makeMeasureSpec(200, View.MeasureSpec.EXACTLY),
					View.MeasureSpec.makeMeasureSpec(400, View.MeasureSpec.EXACTLY));
			layout(0, 0, 200, 400);
		}
	}

	private static Context context() {
		return ThemeEngine.attach(InstrumentationRegistry.getInstrumentation().getTargetContext());
	}

	@Test public void longListUsesOnlyFastScrollerAndRestoresNativeWhenDisabled() {
		InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
			TestList list = new TestList(context());
			list.setVerticalScrollBarEnabled(true);
			list.refreshMetrics(1200, 400);
			assertTrue(list.isVerticalScrollBarEnabled());
			list.setFastScrollerEnabled(true);
			assertFalse(list.isVerticalScrollBarEnabled());
			list.setFastScrollerEnabled(false);
			assertTrue(list.isVerticalScrollBarEnabled());
			list.setFastScrollerEnabled(true);
			assertFalse(list.isVerticalScrollBarEnabled());
		});
	}

	@Test public void layoutSwitchesBetweenShortAndLongListsIncludingTheThreshold() {
		InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
			TestList list = new TestList(context());
			list.setVerticalScrollBarEnabled(true);
			list.setFastScrollerEnabled(true);
			assertTrue(list.isVerticalScrollBarEnabled());
			for (int direction : new int[] {View.LAYOUT_DIRECTION_LTR, View.LAYOUT_DIRECTION_RTL}) {
				list.setLayoutDirection(direction);
				list.refreshMetrics(1200, 400);
				assertFalse(list.isVerticalScrollBarEnabled());
				list.refreshMetrics(799, 400);
				assertTrue(list.isVerticalScrollBarEnabled());
				list.refreshMetrics(800, 400);
				assertFalse(list.isVerticalScrollBarEnabled());
				list.refreshMetrics(0, 0);
				assertTrue(list.isVerticalScrollBarEnabled());
			}
		});
	}

	@Test public void nativeChoiceMadeDuringFastScrollingIsPreservedNotImmediatelyDrawn() {
		InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
			TestList list = new TestList(context());
			list.refreshMetrics(1200, 400);
			list.setVerticalScrollBarEnabled(true);
			list.setFastScrollerEnabled(true);
			list.setVerticalScrollBarEnabled(false);
			list.setFastScrollerEnabled(false);
			assertFalse(list.isVerticalScrollBarEnabled());
			list.setFastScrollerEnabled(true);
			list.setVerticalScrollBarEnabled(true);
			assertFalse(list.isVerticalScrollBarEnabled());
			list.setFastScrollerEnabled(false);
			assertTrue(list.isVerticalScrollBarEnabled());
		});
	}

	@Test public void contextConstructorDoesNotInventANativeFallback() {
		InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
			TestList list = new TestList(context());
			assertFalse(list.isVerticalScrollBarEnabled());
			list.refreshMetrics(1200, 400);
			list.setFastScrollerEnabled(true);
			list.setFastScrollerEnabled(false);
			assertFalse(list.isVerticalScrollBarEnabled());
			list.refreshMetrics(600, 400);
			assertFalse(list.isVerticalScrollBarEnabled());
		});
	}

	@Test public void attributeConstructorsPreserveTheXmlNativeFallback() {
		InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
			Context context = context();
			for (boolean withStyle : new boolean[] {false, true}) {
				try (XmlResourceParser parser = context.getResources().getXml(R.xml.scrollbars)) {
					while (parser.next() != XmlPullParser.START_TAG) {}
					AttributeSet attrs = Xml.asAttributeSet(parser);
					TestList list = withStyle ? new TestList(context, attrs, 0) : new TestList(context, attrs);
					assertTrue(list.isVerticalScrollBarEnabled());
					list.refreshMetrics(1200, 400);
					list.setFastScrollerEnabled(true);
					assertFalse(list.isVerticalScrollBarEnabled());
					list.setFastScrollerEnabled(false);
					assertTrue(list.isVerticalScrollBarEnabled());
				} catch (Exception e) {
					throw new AssertionError(e);
				}
			}
		});
	}
}
