package com.mishiranu.dashchan.widget;

import static org.junit.Assert.*;
import android.content.Context;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import androidx.test.core.app.ActivityScenario;
import androidx.test.platform.app.InstrumentationRegistry;
import com.mishiranu.dashchan.R;
import com.mishiranu.dashchan.content.Preferences;
import com.mishiranu.dashchan.ui.posting.PostingAttachmentState;
import com.mishiranu.dashchan.ui.posting.PostingCommentSizing;
import org.junit.Assume;
import org.junit.Test;

/** Prepared closed-IME sizing fixtures: no file picker, actual media or posting is used. */
public class PostingClosedEditorSizingTest {
	private static final class Fixture implements AutoCloseable {
		final View root;
		final EditText editor;
		final LinearLayout attachments;
		final ScrollView scroll;
		final PostingFormAppearance appearance;
		final int width, height;
		Fixture(PostingGestureTestActivity activity) {
			Context context = PostingTestContext.create(activity); float density = context.getResources().getDisplayMetrics().density;
			width = Math.min(context.getResources().getDisplayMetrics().widthPixels, Math.round(400 * density));
			height = Math.min(context.getResources().getDisplayMetrics().heightPixels - Math.round(120 * density), Math.round(760 * density));
			root = LayoutInflater.from(context).inflate(R.layout.activity_posting, null);
			LayoutInflater.from(context).inflate(R.layout.activity_posting_footer_common,
					(FrameLayout) root.findViewById(R.id.footer_container));
			editor = root.findViewById(R.id.comment); editor.setShowSoftInputOnFocus(false);
			editor.setMinLines(4); editor.setMaxLines(9); editor.setText("retained draft"); editor.setSelection(3);
			root.findViewById(R.id.personal_data_block).setVisibility(View.GONE);
			EditText subject = root.findViewById(R.id.subject); subject.setVisibility(View.VISIBLE);
			attachments = root.findViewById(R.id.attachment_container); scroll = root.findViewById(R.id.scroll_view);
			appearance = new PostingFormAppearance(root);
			appearance.setupEditor(root.findViewById(R.id.comment_parent), editor, new LinearLayout(context));
			appearance.decorateFields(subject);
			Button send = new Button(context);
			((LinearLayout) root.findViewById(R.id.captcha_input_parent)).addView(send);
			appearance.finishSetup(send, root.findViewById(R.id.sage_checkbox), root.findViewById(R.id.original_poster_checkbox));
			appearance.setAttachmentAction(() -> {}); appearance.setAttachmentState(new PostingAttachmentState(0, 8, false));
			FrameLayout host = new FrameLayout(context); host.addView(root, new FrameLayout.LayoutParams(width, height));
			activity.setContentView(host); layout();
		}
		void layout() {
			root.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
					View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY)); root.layout(0, 0, width, height);
		}
		void setCards(int count) {
			attachments.removeAllViews();
			int cardHeight = Math.round(168 * root.getResources().getDisplayMetrics().density);
			for (int i = 0; i < count; i++) attachments.addView(new View(root.getContext()), new LinearLayout.LayoutParams(-1, cardHeight));
			appearance.setAttachmentState(new PostingAttachmentState(count, 8, false));
		}
		@Override public void close() { appearance.close(); }
	}

	private static void settle(ActivityScenario<PostingGestureTestActivity> scenario, Fixture f) {
		for (int pass = 0; pass < 4; pass++) {
			scenario.onActivity(activity -> { f.layout(); f.appearance.updateLayout(false); });
			InstrumentationRegistry.getInstrumentation().waitForIdleSync();
		}
	}

	@Test public void attachmentChangesReachTheCanonicalAutomaticHeightWithoutAnImeCycle() {
		try (ActivityScenario<PostingGestureTestActivity> scenario = ActivityScenario.launch(PostingGestureTestActivity.class)) {
			Fixture[] fixture = new Fixture[1]; Object[] previous = new Object[1];
			scenario.onActivity(activity -> {
				previous[0] = Preferences.PREFERENCES.getAll().get(Preferences.KEY_POSTING_EDITOR_HEIGHT);
				Preferences.PREFERENCES.edit().put(Preferences.KEY_POSTING_EDITOR_HEIGHT, "automatic").close();
				fixture[0] = new Fixture(activity);
			});
			try {
				Fixture f = fixture[0]; settle(scenario, f);
				for (int count : new int[]{1, 6, 0}) {
					scenario.onActivity(activity -> {
						Assume.assumeFalse(PostingCommentSizing.isKeyboardVisible(f.scroll));
						// Reproduce the stale small editor left by an early import/layout pass.
						f.editor.setHeight(PostingFormAppearance.minimumEditorInputHeight(f.editor)); f.setCards(count);
					});
					settle(scenario, f);
					scenario.onActivity(activity -> {
						int automatic = f.editor.getMinHeight();
						assertTrue(automatic > PostingFormAppearance.minimumEditorInputHeight(f.editor));
						PostingCommentSizing.apply(f.scroll, f.editor, null); f.layout();
						assertEquals("No later keyboard-triggered sizing pass should change the height", automatic, f.editor.getMinHeight());
						assertEquals("retained draft", f.editor.getText().toString()); assertEquals(3, f.editor.getSelectionEnd());
					});
				}
			} finally {
				scenario.onActivity(activity -> {
					fixture[0].close();
					if (previous[0] instanceof String) Preferences.PREFERENCES.edit().put(Preferences.KEY_POSTING_EDITOR_HEIGHT, (String) previous[0]).close();
					else Preferences.PREFERENCES.edit().remove(Preferences.KEY_POSTING_EDITOR_HEIGHT).close();
				});
			}
		}
	}

	@Test public void attachmentChangesDoNotReplaceUserSelectedLineBasedModes() {
		try (ActivityScenario<PostingGestureTestActivity> scenario = ActivityScenario.launch(PostingGestureTestActivity.class)) {
			Fixture[] fixture = new Fixture[1]; Object[] previous = new Object[1];
			scenario.onActivity(activity -> {
				previous[0] = Preferences.PREFERENCES.getAll().get(Preferences.KEY_POSTING_EDITOR_HEIGHT);
				Preferences.PREFERENCES.edit().put(Preferences.KEY_POSTING_EDITOR_HEIGHT, "fixed").close(); fixture[0] = new Fixture(activity);
				PostingCommentSizing.apply(fixture[0].scroll, fixture[0].editor, null);
			});
			try {
				Fixture f = fixture[0]; int[] lines = new int[2];
				scenario.onActivity(activity -> { lines[0] = f.editor.getMinLines(); lines[1] = f.editor.getMaxLines(); f.setCards(1); });
				settle(scenario, f);
				scenario.onActivity(activity -> {
					assertEquals(lines[0], f.editor.getMinLines()); assertEquals(lines[1], f.editor.getMaxLines());
					assertEquals("retained draft", f.editor.getText().toString());
				});
			} finally {
				scenario.onActivity(activity -> {
					fixture[0].close();
					if (previous[0] instanceof String) Preferences.PREFERENCES.edit().put(Preferences.KEY_POSTING_EDITOR_HEIGHT, (String) previous[0]).close();
					else Preferences.PREFERENCES.edit().remove(Preferences.KEY_POSTING_EDITOR_HEIGHT).close();
				});
			}
		}
	}
}
