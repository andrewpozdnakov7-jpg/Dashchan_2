package com.mishiranu.dashchan.ui.posting;

import android.graphics.Paint;
import android.view.View;
import android.widget.EditText;
import android.widget.ScrollView;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;
import com.mishiranu.dashchan.content.Preferences;
import com.mishiranu.dashchan.widget.PostingFormAppearance;
import java.util.function.Consumer;

/** One sizing path: no dependency on posting, captcha, drafts or submission controllers. */
public final class PostingCommentSizing {
	private PostingCommentSizing() {}

	public static boolean isKeyboardVisible(ScrollView scroll) {
		WindowInsetsCompat insets = scroll != null ? ViewCompat.getRootWindowInsets(scroll) : null;
		return insets != null && insets.isVisible(WindowInsetsCompat.Type.ime());
	}

	public static void apply(ScrollView scroll, EditText editor, Consumer<String> diagnostics) {
		if (scroll == null || editor == null || scroll.getWidth() <= 0) return;
		int available = scroll.getHeight() - scroll.getPaddingTop() - scroll.getPaddingBottom();
		if (available <= 0) return;
		int padding = editor.getCompoundPaddingTop() + editor.getCompoundPaddingBottom();
		if (editor.getIncludeFontPadding()) {
			Paint.FontMetricsInt metrics = editor.getPaint().getFontMetricsInt();
			padding += metrics.ascent - metrics.top + metrics.bottom - metrics.descent;
		}
		boolean keyboard = isKeyboardVisible(scroll);
		EditorHeightPolicy.Mode mode = Preferences.getPostingEditorHeightMode();
		int adaptiveMinimum = mode == EditorHeightPolicy.Mode.AUTOMATIC
				? PostingFormAppearance.minimumEditorInputHeight(editor) : 0;
		int otherHeight = 0;
		if (mode == EditorHeightPolicy.Mode.AUTOMATIC && (!keyboard || adaptiveMinimum > 0)) {
			View form = scroll.getChildAt(0);
			if (form == null) return;
			int width = scroll.getWidth() - scroll.getPaddingLeft() - scroll.getPaddingRight();
			form.measure(View.MeasureSpec.makeMeasureSpec(Math.max(0, width), View.MeasureSpec.EXACTLY),
					View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED));
			otherHeight = form.getMeasuredHeight() - editor.getMeasuredHeight();
		}
		if ((keyboard && adaptiveMinimum == 0) || mode != EditorHeightPolicy.Mode.AUTOMATIC) {
			available = PostingFormAppearance.editorAvailableHeight(editor, available);
		}
		EditorHeightPolicy.Bounds bounds = EditorHeightPolicy.calculate(mode, keyboard && adaptiveMinimum == 0, available, otherHeight,
				padding, editor.getLineHeight(), Preferences.getPostingEditorFixedLines());
		int pixelHeight = adaptiveMinimum > 0 ? Math.max(adaptiveMinimum, available - otherHeight) : bounds.minimum;
		if (diagnostics != null) diagnostics.accept("resize ime=" + keyboard + " mode=" + mode
				+ " available=" + available + " min=" + bounds.minimum + " max=" + bounds.maximum
				+ " pixels=" + bounds.pixels + " adaptive_min=" + adaptiveMinimum
				+ " target_pixels=" + (bounds.pixels ? pixelHeight : 0));
		if (bounds.pixels) {
			int height = pixelHeight;
			if (editor.getMinHeight() != height || editor.getMaxHeight() != height) {
				editor.setHeight(height);
			}
		} else {
			if (editor.getMinLines() != bounds.minimum) editor.setMinLines(bounds.minimum);
			if (editor.getMaxLines() != bounds.maximum) editor.setMaxLines(bounds.maximum);
		}
	}
}
