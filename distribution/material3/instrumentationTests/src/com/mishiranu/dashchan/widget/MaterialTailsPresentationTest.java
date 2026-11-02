package com.mishiranu.dashchan.widget;

import static org.junit.Assert.*;
import android.content.Context;
import android.content.res.Configuration;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.RippleDrawable;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.ImageButton;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import com.mishiranu.dashchan.R;
import org.junit.Test;
import org.junit.runner.RunWith;

/** Real measured media controls. No network, showcase writes, image export or Activity launch. ADDED / NOT RUN. */
@RunWith(AndroidJUnit4.class)
public class MaterialTailsPresentationTest {
    private Context context(float scale) {
        Context base = InstrumentationRegistry.getInstrumentation().getTargetContext();
        Configuration c = new Configuration(base.getResources().getConfiguration()); c.fontScale = scale;
        return PostingTestContext.create(base.createConfigurationContext(c));
    }
    private static int dp(Context c, int n) { return Math.round(n * c.getResources().getDisplayMetrics().density); }
    private static void layout(View view, int width, int height) {
        view.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY), height > 0
                ? View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY) : View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED));
        view.layout(0, 0, view.getMeasuredWidth(), view.getMeasuredHeight());
    }
    @Test public void attachmentCaptionsGrowBelowImagesAndResponsiveGridKeepsOriginalClickIndices() {
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
            for (float scale : new float[]{1, 1.3f, 2}) for (int width : new int[]{240, 320, 600}) {
                Context c = context(scale); AttachmentTilesLayout grid = new AttachmentTilesLayout(c); int[] selected = {-1};
                for (int i = 0; i < 5; i++) {
                    View tile = LayoutInflater.from(c).inflate(R.layout.list_item_attachment_material, grid, false);
                    TextView caption = tile.findViewById(R.id.attachment_info);
                    caption.setText("very-long-attachment-description.png\n10000 x 10000, PNG, 12345 KB");
                    InterfaceAppearance.mediaCaption(caption); MediaContentAppearance.resolve(caption, true);
                    View click = tile.findViewById(R.id.attachment_click); click.setTag(i);
                    click.setOnClickListener(v -> selected[0] = (int) v.getTag()); grid.addView(tile);
                }
                layout(grid, dp(c, width), 0);
                for (int i = 0; i < 5; i++) {
                    View tile = grid.getChildAt(i), image = tile.findViewById(R.id.thumbnail), caption = tile.findViewById(R.id.attachment_info);
                    assertTrue(tile.getLeft() >= 0); assertTrue(tile.getRight() <= grid.getWidth());
                    assertEquals(image.getWidth(), image.getHeight()); assertTrue(caption.getTop() >= image.getBottom());
                    assertTrue(caption.getHeight() > 0); assertEquals(tile.getHeight(), caption.getBottom());
                    View click = tile.findViewById(R.id.attachment_click); assertSame(image, ((View) click.getParent()).findViewById(R.id.thumbnail));
                    click.performClick(); assertEquals(i, selected[0]);
                }
            }
        });
    }
    @Test public void showcaseActionRemainsReachableAtLargeFontAndSmallHeightWithoutAutomaticConfirmation() {
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
            Context c = context(2); int[] confirms = {0};
            boolean before = com.mishiranu.dashchan.content.Preferences.isShowcaseGalleryEnabled();
            for (boolean modern : new boolean[]{true, false}) for (int i = 0; i < 3; i++) {
                GalleryShowcaseView view = new GalleryShowcaseView(c, () -> confirms[0]++, modern);
                layout(view, dp(c, 320), dp(c, 180));
                ScrollView scroll = (ScrollView) view.getChildAt(0); ViewGroup column = (ViewGroup) scroll.getChildAt(0);
                Button action = (Button) column.getChildAt(column.getChildCount() - 1);
                assertTrue(scroll.getMeasuredWidth() <= view.getWidth()); assertTrue(scroll.getHeight() <= view.getHeight());
                assertEquals(0, confirms[0]); scroll.scrollTo(0, Math.max(0, column.getHeight() - scroll.getHeight()));
                assertTrue(action.getBottom() - scroll.getScrollY() <= scroll.getHeight());
                assertTrue(action.getTop() - scroll.getScrollY() >= 0);
                if (!modern && i == 2) { action.performClick(); assertEquals(1, confirms[0]); }
            }
            assertEquals(before, com.mishiranu.dashchan.content.Preferences.isShowcaseGalleryEnabled());
        });
    }
    @Test public void quickNavigationRippleReapplyPreservesGeometryGravityPaddingAndClickOwnership() {
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
            Context c = context(1); ImageButton button = new ImageButton(c);
            ColorDrawable legacy = new ColorDrawable(Color.RED); button.setBackground(legacy); button.setPadding(dp(c, 8), dp(c, 8), dp(c, 8), dp(c, 8));
            button.setLayoutParams(new FrameLayout.LayoutParams(dp(c, 64), dp(c, 64), Gravity.LEFT | Gravity.TOP));
            int[] clicks = {0}; button.setOnClickListener(v -> clicks[0]++); InterfaceAppearance.quickNavigationIcon(button);
            for (int i = 0; i < 3; i++) {
                MediaContentAppearance.resolve(button, true); assertTrue(button.getBackground() instanceof RippleDrawable);
                assertEquals(dp(c, 64), button.getLayoutParams().width); assertEquals(dp(c, 64), button.getLayoutParams().height);
                assertEquals(dp(c, 8), button.getPaddingLeft()); assertEquals(Gravity.LEFT | Gravity.TOP, ((FrameLayout.LayoutParams) button.getLayoutParams()).gravity);
                assertTrue(button.getImageTintList().isStateful()); assertEquals(0, clicks[0]);
                MediaContentAppearance.resolve(button, false); assertSame(legacy, button.getBackground());
            }
            button.performClick(); assertEquals(1, clicks[0]);
        });
    }
    @Test public void determinateProgressKeepsValuesAcrossAppearanceChangesAndAnotherNavigation() {
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
            Context c = context(1); ProgressBar bar = new ProgressBar(c, null, android.R.attr.progressBarStyleHorizontal);
            bar.setMax(100); bar.setLayoutParams(new FrameLayout.LayoutParams(-1, dp(c, 3)));
            android.graphics.drawable.Drawable original = bar.getProgressDrawable(); InterfaceAppearance.standardLinearProgress(bar);
            for (int value : new int[]{0, 25, 75, 100, 0, 25}) {
                bar.setProgress(value); NativeContentAppearance.resolveLinear(bar, true);
                assertEquals(value, bar.getProgress()); assertEquals(dp(c, 6), bar.getLayoutParams().height);
                NativeContentAppearance.resolveLinear(bar, false); assertEquals(value, bar.getProgress());
                assertSame(original, bar.getProgressDrawable()); assertEquals(dp(c, 3), bar.getLayoutParams().height);
            }
        });
    }
    @Test public void legacyEditorTargetsAndSmallFooterRestoreTheirOwnOffProfileWithoutDuplicatedActions() {
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
            Context c = context(2); ImageButton tool = new ImageButton(c); tool.setLayoutParams(new FrameLayout.LayoutParams(dp(c, 34), dp(c, 58)));
            tool.setPadding(3, 3, 3, 3); InterfaceAppearance.mediaIcon(tool);
            TextView footer = new TextView(c); footer.setAlpha(.65f); footer.setPadding(8, 0, 8, 4); InterfaceAppearance.compactFooter(footer);
            int[] clicks = {0}; tool.setOnClickListener(v -> clicks[0]++);
            for (int i = 0; i < 3; i++) {
                MediaContentAppearance.resolve(tool, true); MediaContentAppearance.resolve(footer, true);
                assertEquals(dp(c, 48), tool.getLayoutParams().width); assertEquals(1f, footer.getAlpha(), 0);
                assertEquals(8, footer.getPaddingLeft()); assertEquals(0, clicks[0]);
                MediaContentAppearance.resolve(tool, false); MediaContentAppearance.resolve(footer, false);
                assertEquals(dp(c, 34), tool.getLayoutParams().width); assertEquals(.65f, footer.getAlpha(), 0);
            }
            tool.performClick(); assertEquals(1, clicks[0]);
        });
    }
}
