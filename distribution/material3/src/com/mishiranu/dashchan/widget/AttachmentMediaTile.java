package com.mishiranu.dashchan.widget;

import android.content.Context;
import android.util.AttributeSet;
import android.view.View;
import android.widget.FrameLayout;
import com.mishiranu.dashchan.R;

/** Square image plus a growing caption below it; large text cannot cover the thumbnail. */
public final class AttachmentMediaTile extends FrameLayout {
    public AttachmentMediaTile(Context context, AttributeSet attrs) { super(context, attrs); }
    @Override protected void onMeasure(int widthSpec, int heightSpec) {
        int width = MeasureSpec.getSize(widthSpec);
        View caption = findViewById(R.id.attachment_info), image = findViewById(R.id.thumbnail), click = findViewById(R.id.attachment_click);
        caption.measure(MeasureSpec.makeMeasureSpec(width, MeasureSpec.EXACTLY), MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED));
        int height = width + caption.getMeasuredHeight();
        image.measure(MeasureSpec.makeMeasureSpec(width, MeasureSpec.EXACTLY), MeasureSpec.makeMeasureSpec(width, MeasureSpec.EXACTLY));
        click.measure(MeasureSpec.makeMeasureSpec(width, MeasureSpec.EXACTLY), MeasureSpec.makeMeasureSpec(height, MeasureSpec.EXACTLY));
        setMeasuredDimension(width, height);
    }
    @Override protected void onLayout(boolean changed, int l, int t, int r, int b) {
        int width = getWidth();
        findViewById(R.id.thumbnail).layout(0, 0, width, width);
        findViewById(R.id.attachment_info).layout(0, width, width, getHeight());
        findViewById(R.id.attachment_click).layout(0, 0, width, getHeight());
    }
}
