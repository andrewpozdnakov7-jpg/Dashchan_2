package com.mishiranu.dashchan.widget;

import static org.junit.Assert.*;
import android.graphics.Paint;
import android.text.Spanned;
import android.widget.TextView;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import com.mishiranu.dashchan.R;
import org.junit.Test;
import org.junit.runner.RunWith;

@RunWith(AndroidJUnit4.class)
public class VideoMetadataSpanTest {
	private static void ui(Runnable action) {
		InstrumentationRegistry.getInstrumentation().runOnMainSync(action);
	}

	private static TextView view() {
		return new TextView(InstrumentationRegistry.getInstrumentation().getTargetContext());
	}

	private static VideoMetadataSpan span(TextView view) {
		VideoMetadataSpan[] spans = ((Spanned) view.getText()).getSpans(0, view.length(), VideoMetadataSpan.class);
		assertEquals(1, spans.length);
		return spans[0];
	}

	@Test public void videoPreservesRawCaptionAndAccessibleFormatWithoutPlaceholders() {
		ui(() -> {
			TextView view = view();
			String description = "MP4 1280×720 18.3 MB";
			VideoMetadataSpan.bind(view, description, true);
			assertEquals(description, view.getText().toString());
			VideoMetadataSpan span = span(view);
			assertEquals("MP4", span.getContentDescription().toString());
			Spanned text = (Spanned) view.getText();
			assertEquals(0, text.getSpanStart(span));
			assertEquals(3, text.getSpanEnd(span));
			assertFalse(view.isClickable());
		});
	}

	@Test public void expandedVideoKeepsAllThreeLinesAndOnlyMarksTheFirstToken() {
		ui(() -> {
			TextView view = view();
			String description = "WEBM\n18.3 MB\n1280×720";
			VideoMetadataSpan.bind(view, description, true);
			assertEquals(description, view.getText().toString());
			VideoMetadataSpan span = span(view);
			assertEquals(4, ((Spanned) view.getText()).getSpanEnd(span));
		});
	}

	@Test public void repeatedBindAndSwitchToPhotoNeverAccumulateVideoMarkers() {
		ui(() -> {
			TextView view = view();
			VideoMetadataSpan.bind(view, "MP4 10 MB", true);
			VideoMetadataSpan.bind(view, view.getText(), true);
			span(view);
			VideoMetadataSpan.bind(view, view.getText(), false);
			assertEquals(0, ((Spanned) view.getText()).getSpans(0, view.length(), VideoMetadataSpan.class).length);
			VideoMetadataSpan.bind(view, "JPG 1024×768", false);
			assertEquals("JPG 1024×768", view.getText().toString());
		});
	}

	@Test public void collapsedGroupStaysUndecoratedAndEmptyVideoHasLocalizedText() {
		ui(() -> {
			TextView view = view();
			VideoMetadataSpan.bind(view, "18 MB, 4 files", false);
			assertEquals("18 MB, 4 files", view.getText().toString());
			assertFalse(view.getText() instanceof Spanned);
			VideoMetadataSpan.bind(view, "", true);
			assertEquals(view.getContext().getString(R.string.gallery_filter_video), view.getText().toString());
			assertEquals(view.getText().toString(), span(view).getContentDescription().toString());
		});
	}

	@Test public void markerFollowsTextScaleWithoutChangingLineMetrics() {
		ui(() -> {
			TextView view = view();
			VideoMetadataSpan.bind(view, "MP4", true);
			VideoMetadataSpan span = span(view);
			Paint paint = new Paint(view.getPaint());
			paint.setTextSize(20);
			Paint.FontMetricsInt expected = paint.getFontMetricsInt();
			Paint.FontMetricsInt actual = new Paint.FontMetricsInt();
			int small = span.getSize(paint, "MP4", 0, 3, actual);
			assertEquals(expected.top, actual.top);
			assertEquals(expected.ascent, actual.ascent);
			assertEquals(expected.descent, actual.descent);
			assertEquals(expected.bottom, actual.bottom);
			assertEquals(expected.leading, actual.leading);
			paint.setTextSize(40);
			assertTrue(span.getSize(paint, "MP4", 0, 3, null) > small);
		});
	}

	@Test public void rtlAndProviderLabelsPreserveTheirOriginalTextAndToken() {
		ui(() -> {
			TextView view = view();
			view.setLayoutDirection(android.view.View.LAYOUT_DIRECTION_RTL);
			for (String text : new String[] {"MP4 1280×720", "Embedded\nYouTube", "فيديو 18 MB"}) {
				VideoMetadataSpan.bind(view, text, true);
				assertEquals(text, view.getText().toString());
				VideoMetadataSpan span = span(view);
				assertTrue(span.getSize(view.getPaint(), view.getText(), 0,
						((Spanned) view.getText()).getSpanEnd(span), null) > 0);
			}
		});
	}
}
