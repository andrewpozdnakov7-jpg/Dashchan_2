package com.mishiranu.dashchan.text.style;

import android.graphics.Typeface;
import android.text.TextPaint;
import android.text.style.MetricAffectingSpan;
import androidx.annotation.NonNull;
import com.mishiranu.dashchan.util.ResourceUtils;

public class LightSpan extends MetricAffectingSpan {
	@Override
	public void updateDrawState(@NonNull TextPaint paint) {
		Typeface current = paint.getTypeface();
		boolean italic = current != null && current.isItalic();
		paint.setTypeface(Typeface.create(ResourceUtils.TYPEFACE_LIGHT, 300, italic));
	}

	@Override
	public void updateMeasureState(@NonNull TextPaint paint) {
		updateDrawState(paint);
	}
}
