package com.mishiranu.dashchan.widget;

import static org.junit.Assert.*;
import android.app.AlertDialog;
import android.content.Context;
import android.util.Pair;
import android.view.View;
import android.widget.ArrayAdapter;
import android.widget.CheckBox;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.RadioGroup;
import android.widget.TextView;
import androidx.fragment.app.Fragment;
import androidx.test.core.app.ActivityScenario;
import androidx.test.platform.app.InstrumentationRegistry;
import chan.content.ChanConfiguration;
import com.mishiranu.dashchan.R;
import com.mishiranu.dashchan.content.async.SendMultifunctionalTask;
import com.mishiranu.dashchan.ui.InstanceDialog;
import com.mishiranu.dashchan.ui.navigator.manager.DialogUnit;
import com.mishiranu.dashchan.ui.posting.AttachmentHolder;
import com.mishiranu.dashchan.ui.posting.PostingDialogCallback;
import com.mishiranu.dashchan.ui.posting.dialog.AttachmentOptionsDialog;
import com.mishiranu.dashchan.ui.posting.dialog.AttachmentRatingDialog;
import com.mishiranu.dashchan.util.GraphicsUtils;
import com.mishiranu.dashchan.util.ResourceUtils;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import org.junit.Test;

/** Prepared, not executed. Real adapters/forms with synthetic holders; no posting or real attachment storage. */
public class StandardOperationContentTest {
	private static AttachmentHolder holder(Context context) {
		return new AttachmentHolder(new View(context), new TextView(context), new TextView(context), new ImageView(context),
				new View(context), new View(context), new View(context), new View(context));
	}
	@Test public void attachmentAdapterKeepsIndicesCheckedStateAndReencodingDependencyThroughRebind() {
		InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> StandardCustomContentTest.profile(true, () -> {
			try {
				Context context = PostingTestContext.create();
				Class<?> adapterClass = Class.forName(AttachmentOptionsDialog.class.getName() + "$ItemsAdapter");
				Constructor<?> constructor = adapterClass.getDeclaredConstructor(Context.class, int.class, ArrayList.class); constructor.setAccessible(true);
				@SuppressWarnings("unchecked") ArrayAdapter<String> adapter = (ArrayAdapter<String>) constructor.newInstance(context,
						ResourceUtils.obtainAlertDialogLayoutResId(context, ResourceUtils.DialogLayout.MULTI_CHOICE),
						new ArrayList<>(Arrays.asList("Hash", "Metadata", "Reencode", "Filename")));
				AttachmentOptionsDialog owner = new AttachmentOptionsDialog();
				@SuppressWarnings("unchecked") java.util.Map<Object, Integer> indices =
						(java.util.Map<Object, Integer>) StandardCustomContentTest.member(owner, "optionIndices");
				Class<?> type = Class.forName(AttachmentOptionsDialog.class.getName() + "$Type");
				for (Object value : type.getEnumConstants()) {
					if (value.toString().equals("REMOVE_METADATA")) indices.put(value, 1);
					if (value.toString().equals("REENCODE_IMAGE")) indices.put(value, 2);
				}
				Method update = AttachmentOptionsDialog.class.getDeclaredMethod("updateItemsEnabled", adapterClass, AttachmentHolder.class); update.setAccessible(true);
				AttachmentHolder holder = holder(context); holder.reencoding = new GraphicsUtils.Reencoding("jpeg", 90, 1);
				update.invoke(owner, adapter, holder);
				assertFalse(adapter.areAllItemsEnabled()); assertFalse(adapter.isEnabled(1)); assertTrue(adapter.isEnabled(2));
				ListView list = new ListView(context); list.setChoiceMode(ListView.CHOICE_MODE_MULTIPLE); list.setAdapter(adapter);
				list.setItemChecked(0, true); list.setItemChecked(2, true); int[] clicks = {0}; list.setOnItemClickListener((p, v, i, id) -> clicks[0]++);
				View recycled = adapter.getView(1, null, list); assertFalse(recycled.isEnabled());
				InterfaceAppearance.refreshTree(recycled); recycled = adapter.getView(2, recycled, list); assertTrue(recycled.isEnabled());
				assertEquals("Reencode", ((TextView) recycled.findViewById(android.R.id.text1)).getText().toString());
				assertEquals(0, list.getHeaderViewsCount()); assertEquals(4, list.getCount());
				assertTrue(list.isItemChecked(0)); assertTrue(list.isItemChecked(2)); assertEquals(0, clicks[0]);
				holder.reencoding = null; update.invoke(owner, adapter, holder); assertTrue(adapter.isEnabled(1));
			} catch (ReflectiveOperationException e) { throw new AssertionError(e); }
		}));
	}
	public static class RatingParent extends Fragment implements PostingDialogCallback {
		AttachmentHolder attachment;
		@Override public AttachmentHolder getAttachmentHolder(int index) { assertEquals(0, index); return attachment; }
		@Override public List<Pair<String, String>> getAttachmentRatingItems() {
			return Arrays.asList(new Pair<>("safe", "Safe"), new Pair<>("adult", "Adult"), new Pair<>("other", "Other"));
		}
		@Override public ChanConfiguration.Posting getPostingConfiguration() { return null; }
	}
	@Test public void attachmentRatingKeepsSavedMappingAndDoesNotApplyDuringRefresh() {
		try (ActivityScenario<StandardContentTestActivity> scenario = ActivityScenario.launch(StandardContentTestActivity.class)) {
			scenario.onActivity(activity -> StandardCustomContentTest.profile(true, () -> {
				RatingParent parent = new RatingParent(); parent.attachment = holder(activity); parent.attachment.rating = "adult";
				activity.getSupportFragmentManager().beginTransaction().add(parent, "synthetic_rating").commitNow();
				AttachmentRatingDialog fragment = new AttachmentRatingDialog(0); fragment.showNow(parent.getChildFragmentManager(), "rating");
				AlertDialog dialog = (AlertDialog) fragment.getDialog(); assertNotNull(dialog);
				assertTrue(dialog.getListView().isItemChecked(1)); InterfaceAppearance.refreshTree(dialog.getWindow().getDecorView());
				assertEquals("adult", parent.attachment.rating);
				fragment.onClick(dialog, 2); assertEquals("other", parent.attachment.rating);
				activity.getSupportFragmentManager().executePendingTransactions();
			}));
		}
	}
	private AlertDialog operation(Context context, SendMultifunctionalTask.State state, String type, String text, List<String> options) {
		try {
			InstanceDialog.Provider provider = (InstanceDialog.Provider) Proxy.newProxyInstance(InstanceDialog.Provider.class.getClassLoader(),
					new Class<?>[]{InstanceDialog.Provider.class}, (proxy, method, args) -> {
						if (method.getName().equals("getContext")) return context;
						throw new AssertionError("Unexpected task/navigation during styling: " + method.getName());
					});
			Method create = DialogUnit.class.getDeclaredMethod("createPerformSendDialog", InstanceDialog.Provider.class,
					SendMultifunctionalTask.State.class, String.class, String.class, List.class, java.util.Collection.class, boolean.class);
			create.setAccessible(true); return (AlertDialog) create.invoke(null, provider, state, type, text, options, Collections.emptyList(), true);
		} catch (ReflectiveOperationException e) { throw new AssertionError(e); }
	}
	@Test public void operationFormsPreserveIndicesOptionsAndCancelWithoutStartingTasks() {
		try (ActivityScenario<StandardContentTestActivity> scenario = ActivityScenario.launch(StandardContentTestActivity.class)) {
			scenario.onActivity(activity -> StandardCustomContentTest.profile(true, () -> {
				for (SendMultifunctionalTask.Operation kind : new SendMultifunctionalTask.Operation[]{
						SendMultifunctionalTask.Operation.DELETE, SendMultifunctionalTask.Operation.REPORT, SendMultifunctionalTask.Operation.ARCHIVE}) {
					SendMultifunctionalTask.State state = new SendMultifunctionalTask.State(kind, "fixture", "fixture", "1",
							Arrays.asList(new Pair<>("a", "First"), new Pair<>("b", "Second")),
							Arrays.asList(new Pair<>(DialogUnit.OPTION_THUMBNAILS, "Thumbnails"), new Pair<>(DialogUnit.OPTION_FILES, "Files")), true);
					AlertDialog dialog = operation(activity, state, "b", "Synthetic reason", Collections.singletonList(DialogUnit.OPTION_FILES));
					try {
						dialog.show(); RadioGroup types = StandardCustomContentTest.find(dialog.getWindow().getDecorView(), RadioGroup.class);
						assertEquals(1, types.getCheckedRadioButtonId());
						assertEquals("Synthetic reason", StandardCustomContentTest.find(dialog.getWindow().getDecorView(), android.widget.EditText.class).getText().toString());
						CheckBox first = StandardCustomContentTest.find(dialog.getWindow().getDecorView(), CheckBox.class);
						LinearLayout choices = (LinearLayout) first.getParent(); assertEquals(2, choices.getChildCount());
						assertTrue(((CheckBox) choices.getChildAt(1)).isChecked());
						assertEquals(kind == SendMultifunctionalTask.Operation.ARCHIVE, first.isChecked());
						InterfaceAppearance.refreshTree(dialog.getWindow().getDecorView()); InterfaceAppearance.refreshTree(dialog.getWindow().getDecorView());
						assertEquals(1, types.getCheckedRadioButtonId()); assertTrue(((CheckBox) choices.getChildAt(1)).isChecked());
						assertEquals(kind == SendMultifunctionalTask.Operation.DELETE, Boolean.TRUE.equals(dialog.getWindow().getDecorView().getTag(R.id.standard_destructive_action)));
						dialog.getButton(AlertDialog.BUTTON_NEGATIVE).performClick(); assertFalse(dialog.isShowing());
					} finally { dialog.dismiss(); }
				}
			}));
		}
	}
}
