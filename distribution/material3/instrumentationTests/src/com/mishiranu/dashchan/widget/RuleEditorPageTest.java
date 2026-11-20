package com.mishiranu.dashchan.widget;

import android.app.Dialog;
import android.view.WindowManager;
import android.widget.CompoundButton;
import android.widget.EditText;
import androidx.test.platform.app.InstrumentationRegistry;
import com.mishiranu.dashchan.R;
import org.junit.Test;
import static org.junit.Assert.*;

/** Detached controls: exercising the new layout must not write preferences or hide any real posts. */
public class RuleEditorPageTest {
    @Test public void dialogCanBeCreatedBeforeWindowIsAttached() {
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
            RuleEditorPage page = new RuleEditorPage(PostingTestContext.create(), false, () -> {}, () -> {}, () -> {});
            Dialog dialog = page.dialog();
            assertFalse(dialog.isShowing());
            assertNotNull(dialog.findViewById(R.id.value));
            int softInputMode = dialog.getWindow().getAttributes().softInputMode;
            assertEquals(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_NOTHING,
                    softInputMode & WindowManager.LayoutParams.SOFT_INPUT_MASK_ADJUST);
            assertEquals(WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN,
                    softInputMode & WindowManager.LayoutParams.SOFT_INPUT_MASK_STATE);
        });
    }

    @Test public void independentScopesMayBothBeOnOrBothOff() {
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
            RuleEditorPage page = new RuleEditorPage(PostingTestContext.create(),false,()->{},()->{},()->{});
            CompoundButton op = page.scroll.findViewById(R.id.autohide_original_post);
            CompoundButton replies = page.scroll.findViewById(R.id.autohide_replies);
            op.setChecked(true); replies.setChecked(true);
            assertTrue(op.isChecked()); assertTrue(replies.isChecked());
            op.performClick(); assertFalse(op.isChecked()); assertTrue(replies.isChecked());
            replies.performClick(); assertFalse(replies.isChecked()); assertFalse(op.isChecked());
        });
    }
    @Test public void disclosuresRetainInputAndDoNotExecuteSave() {
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
            int[] writes = {0};
            RuleEditorPage page = new RuleEditorPage(PostingTestContext.create(),true,()->{},()->writes[0]++,()->writes[0]++);
            assertFalse(page.scopeOpen()); assertFalse(page.testOpen());
            EditText example = page.scroll.findViewById(R.id.test_string);
            page.expandTest(true); example.setText("test draft"); example.setSelection(2);
            page.expandTest(false); page.expandScope(true); page.expandTest(true);
            assertSame(example,page.scroll.findViewById(R.id.test_string));
            assertEquals("test draft",example.getText().toString()); assertEquals(2,example.getSelectionStart());
            assertEquals(0,writes[0]);
        });
    }
}
