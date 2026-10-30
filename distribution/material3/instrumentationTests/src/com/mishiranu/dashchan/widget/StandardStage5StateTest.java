package com.mishiranu.dashchan.widget;

import static org.junit.Assert.*;
import android.app.AlertDialog;
import android.os.Bundle;
import android.widget.CheckBox;
import android.widget.EditText;
import androidx.annotation.NonNull;
import androidx.lifecycle.LifecycleOwner;
import androidx.lifecycle.Observer;
import androidx.lifecycle.ViewModel;
import androidx.lifecycle.ViewModelProvider;
import androidx.test.core.app.ActivityScenario;
import com.mishiranu.dashchan.content.async.ReadApachanEditPostTask;
import com.mishiranu.dashchan.content.async.RolloverThreadTask;
import com.mishiranu.dashchan.content.async.SendApachanEditPostTask;
import com.mishiranu.dashchan.ui.ApachanEditPostDialog;
import com.mishiranu.dashchan.ui.posting.RolloverThreadDialog;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import org.junit.Test;

/** Prepared synthetic lifecycle fixtures. Held models never construct/execute a network task. */
public class StandardStage5StateTest {
	private static void set(Object target, String name, Object value) {
		try { Field f = target.getClass().getDeclaredField(name); f.setAccessible(true); f.set(target, value); }
		catch (ReflectiveOperationException e) { throw new AssertionError(e); }
	}
	@Test public void rolloverRepeatedRenderKeepsWaitingMissingAndStaleStatesWithoutHandoffOrTask() {
		try (ActivityScenario<StandardContentTestActivity> scenario = ActivityScenario.launch(StandardContentTestActivity.class)) {
			scenario.onActivity(a -> StandardCustomContentTest.profile(true, () -> {
				RolloverThreadDialog f = new RolloverThreadDialog();
				// Missing source arrays deliberately make initialize() stale BEFORE any draft/profile access.
				f.setArguments(new Bundle()); f.showNow(a.getSupportFragmentManager(), "stage5-rollover");
				Object state = StandardStage5DialogsTest.field(f, "state");
				try {
					AlertDialog d = (AlertDialog) f.requireDialog(); assertFalse(d.getButton(AlertDialog.BUTTON_POSITIVE).isEnabled());
					set(state, "requiresConfirmation", true); set(state, "started", true); set(state, "stale", false);
					f.onStart(); f.onStart();
					android.widget.ProgressBar progress = (android.widget.ProgressBar) StandardStage5DialogsTest.field(f, "progress");
					assertEquals(android.view.View.VISIBLE, progress.getVisibility());
					assertNull(StandardStage5DialogsTest.field(state, "task")); assertEquals(false, StandardStage5DialogsTest.field(f, "handoffPosted"));
					RolloverThreadTask.Result missing = new RolloverThreadTask.Result(); missing.failed.add("synthetic-missing.bin"); set(state, "result", missing);
					f.onStart(); f.onStart(); assertEquals(android.view.View.GONE, progress.getVisibility());
					assertTrue(d.getButton(AlertDialog.BUTTON_POSITIVE).isEnabled()); assertEquals(false, StandardStage5DialogsTest.field(f, "handoffPosted"));
					set(state, "stale", true); f.onStart(); assertFalse(d.getButton(AlertDialog.BUTTON_POSITIVE).isEnabled());
					assertNull(StandardStage5DialogsTest.field(state, "task")); assertEquals(false, StandardStage5DialogsTest.field(state, "committed"));
				} finally { f.dismissNow(); }
			}));
		}
	}
	@Test public void apachanMultilineTextAndDeleteAttachmentRestoreAndEmptyConfirmDoesNotSend() {
		try (ActivityScenario<StandardContentTestActivity> scenario = ActivityScenario.launch(StandardContentTestActivity.class)) {
			scenario.onActivity(a -> StandardCustomContentTest.profile(true, () -> {
				ApachanEditPostDialog.EditDialog f;
				try {
					Bundle args = new Bundle(); args.putString("subject", "Synthetic subject"); args.putString("comment", "Line 1\nLine 2");
					args.putBoolean("hasAttachment", true); args.putBoolean("originalPost", false);
					Constructor<ApachanEditPostDialog.EditDialog> ctor = ApachanEditPostDialog.EditDialog.class.getDeclaredConstructor(Bundle.class);
					ctor.setAccessible(true); f = ctor.newInstance(args);
				} catch (ReflectiveOperationException e) { throw new AssertionError(e); }
				f.showNow(a.getSupportFragmentManager(), "stage5-edit"); AlertDialog restored = null;
				try {
					EditText comment = (EditText) StandardStage5DialogsTest.field(f, "commentView");
					CheckBox delete = (CheckBox) StandardStage5DialogsTest.field(f, "deleteAttachmentView");
					comment.setText("Edited line 1\nEdited line 2"); delete.setChecked(true); Bundle saved = new Bundle(); f.onSaveInstanceState(saved);
					restored = (AlertDialog) f.onCreateDialog(saved); restored.show();
					comment = (EditText) StandardStage5DialogsTest.field(f, "commentView");
					assertEquals("Edited line 1\nEdited line 2", comment.getText().toString()); assertTrue(comment.getMinLines() >= 6);
					assertTrue(((CheckBox) StandardStage5DialogsTest.field(f, "deleteAttachmentView")).isChecked());
					comment.setText("   "); restored.getButton(AlertDialog.BUTTON_POSITIVE).performClick();
					assertTrue(restored.isShowing()); assertNull(a.getSupportFragmentManager().findFragmentByTag("apachanEditPostSend"));
				} finally { if (restored != null) restored.dismiss(); f.dismissNow(); }
			}));
		}
	}
	public static final class HeldLoad extends ApachanEditPostDialog.LoadViewModel {
		int observes;
		@Override public boolean hasTaskOrValue() { return true; }
		@Override public void observe(LifecycleOwner owner, Observer<? super ReadApachanEditPostTask.Result> observer) {
			observes++; super.observe(owner, observer);
		}
	}
	public static final class HeldSend extends ApachanEditPostDialog.SendViewModel {
		int observes;
		@Override public boolean hasTaskOrValue() { return true; }
		@Override public void observe(LifecycleOwner owner, Observer<? super SendApachanEditPostTask.Result> observer) {
			observes++; super.observe(owner, observer);
		}
	}
	private static final ViewModelProvider.Factory HELD = new ViewModelProvider.Factory() {
		@NonNull @Override public <T extends ViewModel> T create(@NonNull Class<T> type) {
			return type.cast(type == ApachanEditPostDialog.LoadViewModel.class ? new HeldLoad() : new HeldSend());
		}
	};
	public static final class LoadFixture extends ApachanEditPostDialog.LoadDialog {
		@NonNull @Override public ViewModelProvider.Factory getDefaultViewModelProviderFactory() { return HELD; }
	}
	public static final class SendFixture extends ApachanEditPostDialog.SendDialog {
		@NonNull @Override public ViewModelProvider.Factory getDefaultViewModelProviderFactory() { return HELD; }
	}
	@Test public void loadAndSendRegisterOncePerInstanceWithHeldModelsAcrossRepeatedStarts() {
		try (ActivityScenario<StandardContentTestActivity> scenario = ActivityScenario.launch(StandardContentTestActivity.class)) {
			scenario.onActivity(a -> {
				LoadFixture load = new LoadFixture(); load.setArguments(new Bundle()); load.showNow(a.getSupportFragmentManager(), "held-load");
				SendFixture send = new SendFixture(); send.setArguments(new Bundle()); send.showNow(a.getSupportFragmentManager(), "held-send");
				load.onStart(); send.onStart(); load.onStart(); send.onStart();
				HeldLoad l = (HeldLoad) new ViewModelProvider(load).get(ApachanEditPostDialog.LoadViewModel.class);
				HeldSend s = (HeldSend) new ViewModelProvider(send).get(ApachanEditPostDialog.SendViewModel.class);
				assertEquals(1, l.observes); assertEquals(1, s.observes); assertNull(l.getTask()); assertNull(s.getTask());
				load.dismissNow(); send.dismissNow();
			});
		}
	}
}
