package com.mishiranu.dashchan.widget;

import static org.junit.Assert.*;
import android.content.Context;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import androidx.test.platform.app.InstrumentationRegistry;
import com.mishiranu.dashchan.R;
import com.mishiranu.dashchan.content.Preferences;
import org.junit.Test;

/** No real keyboard or conversion: exercise the shared editor geometry and the actual information View. */
public class PostingInsideCounterTest {
	private static void layout(View root, int width, int height) {
		root.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
				View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY)); root.layout(0, 0, width, height);
	}
	private static int count(View view, int id) {
		int count = view.getId() == id ? 1 : 0;
		if (view instanceof ViewGroup) for (int i = 0; i < ((ViewGroup) view).getChildCount(); i++) count += count(((ViewGroup) view).getChildAt(i), id);
		return count;
	}
	@Test public void singleCounterStaysInsideTheFrameAndItsMeasuredHeightIsReservedInTheImeViewport() {
		InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
			String key = Preferences.KEY_POSTING_EDITOR_HEIGHT;
			Object previous = Preferences.PREFERENCES.getAll().get(key);
			Preferences.PREFERENCES.edit().put(key, "automatic").close();
			Context context = PostingTestContext.create();
			View root = LayoutInflater.from(context).inflate(R.layout.activity_posting, null);
			LayoutInflater.from(context).inflate(R.layout.activity_posting_footer_common, (FrameLayout) root.findViewById(R.id.footer_container));
			PostingFormAppearance owner = new PostingFormAppearance(root);
			try {
				EditText editor = root.findViewById(R.id.comment);
				LinearLayout container = root.findViewById(R.id.comment_parent);
				ScrollView scroll = root.findViewById(R.id.scroll_view);
				PostingAttachmentSummaryView summary = root.findViewById(R.id.posting_attachment_summary);
				root.findViewById(R.id.personal_data_block).setVisibility(View.GONE); root.findViewById(R.id.subject).setVisibility(View.GONE);
				owner.setupEditor(container, editor, new LinearLayout(context));
				Button send = new Button(context); ((LinearLayout) root.findViewById(R.id.captcha_input_parent)).addView(send);
				owner.finishSetup(send, root.findViewById(R.id.sage_checkbox), root.findViewById(R.id.original_poster_checkbox));
				String draft = "Long draft\nSecond line\nLast editable line";
				editor.setText(draft); editor.setSelection(3, 7); assertTrue(editor.requestFocus());
				float density = context.getResources().getDisplayMetrics().density;
				int width = Math.round(400 * density), height = Math.round(500 * density);
				for (int i = 0; i < 3; i++) { layout(root, width, height); owner.updateLayout(true); }
				int without = PostingFormAppearance.keyboardEditorInputHeight(editor, scroll.getHeight());
				summary.setSummary("2/4 files", "18 MB/40 MB"); summary.setVisibility(View.VISIBLE);
				for (int i = 0; i < 3; i++) { layout(root, width, height); owner.updateLayout(true); }
				int with = PostingFormAppearance.keyboardEditorInputHeight(editor, scroll.getHeight());
				assertTrue(without > 0); assertEquals(without - summary.getMeasuredHeight(), with);
				for (boolean keyboard : new boolean[] {false, true, false}) {
					owner.updateLayout(keyboard); layout(root, width, height);
					assertSame(container, summary.getParent()); assertSame(editor, container.getChildAt(0));
					assertTrue(container.indexOfChild(summary) > container.indexOfChild(editor));
					assertTrue(container.indexOfChild(summary) < container.getChildCount() - 1);
					assertEquals(1, count(root, R.id.posting_attachment_summary));
					assertEquals(draft, editor.getText().toString()); assertEquals(3, editor.getSelectionStart()); assertEquals(7, editor.getSelectionEnd());
					assertFalse(summary.isClickable()); assertFalse(summary.isFocusable());
				}
			} finally {
				owner.close();
				if (previous instanceof String) Preferences.PREFERENCES.edit().put(key, (String) previous).close();
				else Preferences.PREFERENCES.edit().remove(key).close();
			}
		});
	}
	@Test public void narrowTextWrapsBetweenGroupsWithoutAStrandedSeparatorOrFontShrinking() {
		InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
			PostingAttachmentSummaryView view = new PostingAttachmentSummaryView(PostingTestContext.create());
			view.setTextSize(12); view.setPadding(16, 4, 16, 8);
			String files = "2/4 files", size = "18 MB/40 MB";
			view.setSummary(files, size); float fontSize = view.getTextSize();
			int padding = view.getCompoundPaddingLeft() + view.getCompoundPaddingRight();
			int narrow = padding + (int) Math.ceil(Math.max(android.text.Layout.getDesiredWidth(files, view.getPaint()), android.text.Layout.getDesiredWidth(size, view.getPaint()))) + 1;
			view.measure(View.MeasureSpec.makeMeasureSpec(narrow, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED));
			assertEquals(files + "\n" + size, view.getText().toString()); assertEquals(2, view.getLineCount());
			assertEquals(fontSize, view.getTextSize(), .01f);
			view.measure(View.MeasureSpec.makeMeasureSpec(narrow * 3, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED));
			assertFalse(view.getText().toString().contains("\n")); assertTrue(view.getText().toString().contains("\u00b7"));
			CharSequence before = view.getText(); view.setSummary(files, size); assertSame(before, view.getText());
		});
	}
}
