package com.mishiranu.dashchan.widget;

import static org.junit.Assert.*;
import android.content.Context;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;
import androidx.test.platform.app.InstrumentationRegistry;
import com.mishiranu.dashchan.R;
import com.mishiranu.dashchan.content.Preferences;
import org.junit.Test;

/** Geometry fixture only: no keyboard service, conversion, network or submission. */
public class PostingConversionCaptionTest {
	@Test public void retargetDuringTheSameSystemTransitionStartsAtTheDisplayedPose() {
		assertEquals(0f, PostingImeMotion.retargetFraction(.6f, .6f), .0001f);
		assertEquals(.5f, PostingImeMotion.retargetFraction(.8f, .6f), .0001f);
		assertEquals(1f, PostingImeMotion.retargetFraction(1f, .6f), .0001f);
		assertEquals(.25f, PostingImeMotion.retargetFraction(.25f, 0f), .0001f);
	}
	private static void layout(View root, int width, int height) {
		root.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
				View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY)); root.layout(0, 0, width, height);
	}
	@Test public void captionUsesTheSameActionContainerAndReleasesItsSpaceWithoutTouchingTheDraft() {
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
				owner.setupEditor(root.findViewById(R.id.comment_parent), editor, new LinearLayout(context));
				root.findViewById(R.id.personal_data_block).setVisibility(View.GONE);
				root.findViewById(R.id.subject).setVisibility(View.GONE);
				Button send = new Button(context); int[] submissions = {0}; send.setOnClickListener(v -> submissions[0]++);
				((LinearLayout) root.findViewById(R.id.captcha_input_parent)).addView(send);
				owner.finishSetup(send, root.findViewById(R.id.sage_checkbox), root.findViewById(R.id.original_poster_checkbox));
				editor.setText("draft remains intact"); editor.setSelection(3); assertTrue(editor.requestFocus());
				float density = context.getResources().getDisplayMetrics().density;
				int width = Math.round(400 * density), height = Math.round(420 * density);
				TextView caption = root.findViewById(R.id.compression_status);
				View actions = root.findViewById(R.id.posting_action_container);
				PostingFormAppearance.setConversionStatus(root, "Compressing video · 65%");
				for (int i = 0; i < 3; i++) { layout(root, width, height); owner.updateLayout(true); }
				layout(root, width, height);
				assertEquals(View.VISIBLE, caption.getVisibility());
				assertSame(send.getParent().getParent().getParent(), caption.getParent());
				int withCaption = actions.getMeasuredHeight();
				PostingFormAppearance.setConversionStatus(root, "Compressing video · 66%");
				layout(root, width, height); assertEquals(withCaption, actions.getMeasuredHeight());
				PostingFormAppearance.setConversionStatus(root, "");
				for (int i = 0; i < 3; i++) { layout(root, width, height); owner.updateLayout(true); }
				layout(root, width, height);
				assertEquals(View.GONE, caption.getVisibility()); assertTrue(actions.getMeasuredHeight() < withCaption);
				assertEquals("draft remains intact", editor.getText().toString()); assertEquals(3, editor.getSelectionStart());
				assertTrue(editor.hasFocus()); assertEquals(0, submissions[0]);
				PostingFormAppearance.setConversionStatus(root, "Compressing video · 12%"); owner.updateLayout(false);
				assertEquals(View.GONE, caption.getVisibility());
			} finally {
				owner.close();
				if (previous instanceof String) Preferences.PREFERENCES.edit().put(key, (String) previous).close();
				else Preferences.PREFERENCES.edit().remove(key).close();
			}
		});
	}
}
