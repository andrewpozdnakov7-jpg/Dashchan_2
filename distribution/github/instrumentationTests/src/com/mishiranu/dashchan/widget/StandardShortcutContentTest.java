package com.mishiranu.dashchan.widget;

import static org.junit.Assert.*;
import android.app.Activity;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.drawable.BitmapDrawable;
import android.net.Uri;
import android.view.View;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.ProgressBar;
import androidx.test.core.app.ActivityScenario;
import com.mishiranu.dashchan.content.Preferences;
import com.mishiranu.dashchan.ui.preference.CustomShortcutFragment;
import java.io.File;
import java.io.FileOutputStream;
import java.lang.reflect.Method;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.Test;

/** Prepared, not run. Tiny throwaway cache images only; no shortcut is actually created. */
public class StandardShortcutContentTest {
	private File image(File cache, int color) {
		try {
			File file = File.createTempFile("stage4-shortcut-", ".png", cache);
			Bitmap bitmap = Bitmap.createBitmap(8, 4, Bitmap.Config.ARGB_8888); bitmap.eraseColor(color);
			try (FileOutputStream stream = new FileOutputStream(file)) { assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, stream)); }
			finally { bitmap.recycle(); }
			return file;
		} catch (java.io.IOException e) { throw new AssertionError(e); }
	}
	private void picker(CustomShortcutFragment fragment, int result, File file) {
		try {
			Method selected = CustomShortcutFragment.class.getDeclaredMethod("onImageSelected", int.class, Intent.class); selected.setAccessible(true);
			selected.invoke(fragment, result, new Intent().setData(Uri.fromFile(file)));
		} catch (ReflectiveOperationException e) { throw new AssertionError(e); }
	}
	@Test public void newestPickerResultWinsOldDecodeAndCancelDoesNotRestartLoading() throws InterruptedException {
		Object previous = Preferences.PREFERENCES.getAll().get(Preferences.KEY_NEW_INTERFACE_MOTION);
		AtomicReference<CustomShortcutFragment> current = new AtomicReference<>();
		AtomicReference<File> first = new AtomicReference<>(), second = new AtomicReference<>();
		Preferences.PREFERENCES.edit().put(Preferences.KEY_NEW_INTERFACE_MOTION, true).close();
		try (ActivityScenario<StandardContentTestActivity> scenario = ActivityScenario.launch(StandardContentTestActivity.class)) {
			scenario.onActivity(activity -> {
				first.set(image(activity.getCacheDir(), Color.RED)); second.set(image(activity.getCacheDir(), Color.GREEN));
				CustomShortcutFragment fragment = new CustomShortcutFragment(); current.set(fragment);
				activity.getSupportFragmentManager().beginTransaction().add(android.R.id.widget_frame, fragment, "synthetic_shortcut").commitNow();
				picker(fragment, Activity.RESULT_OK, first.get()); picker(fragment, Activity.RESULT_OK, second.get());
				assertEquals(2, StandardCustomContentTest.member(fragment, "decodeGeneration"));
				assertEquals(View.VISIBLE, ((ProgressBar) StandardCustomContentTest.member(fragment, "progressBar")).getVisibility());
				assertFalse(((Button) StandardCustomContentTest.member(fragment, "createButton")).isEnabled());
			});
			AtomicBoolean finished = new AtomicBoolean(); long end = android.os.SystemClock.uptimeMillis() + 5000;
			while (!finished.get() && android.os.SystemClock.uptimeMillis() < end) {
				scenario.onActivity(activity -> finished.set(((ProgressBar) StandardCustomContentTest.member(current.get(), "progressBar")).getVisibility() == View.GONE));
				if (!finished.get()) Thread.sleep(10);
			}
			assertTrue("Synthetic decode did not complete", finished.get());
			scenario.onActivity(activity -> {
				CustomShortcutFragment fragment = current.get(); ImageView preview = (ImageView) StandardCustomContentTest.member(fragment, "preview");
				Bitmap result = ((BitmapDrawable) preview.getDrawable()).getBitmap(); assertEquals(Color.GREEN, result.getPixel(256, 256));
				assertTrue(((Button) StandardCustomContentTest.member(fragment, "createButton")).isEnabled());
				assertEquals(Uri.fromFile(second.get()), StandardCustomContentTest.member(fragment, "selectedUri"));
				preview.measure(View.MeasureSpec.makeMeasureSpec(StandardAppearance.dp(preview, 180), View.MeasureSpec.EXACTLY),
						View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED));
				assertEquals(preview.getMeasuredWidth(), preview.getMeasuredHeight()); assertTrue(preview.getMeasuredWidth() <= StandardAppearance.dp(preview, 180));
				picker(fragment, Activity.RESULT_CANCELED, first.get());
				assertEquals(Uri.fromFile(second.get()), StandardCustomContentTest.member(fragment, "selectedUri"));
				assertEquals(View.GONE, ((ProgressBar) StandardCustomContentTest.member(fragment, "progressBar")).getVisibility());
				int generation = (Integer) StandardCustomContentTest.member(fragment, "decodeGeneration");
				activity.getSupportFragmentManager().beginTransaction().remove(fragment).commitNow();
				assertEquals(generation + 1, StandardCustomContentTest.member(fragment, "decodeGeneration"));
				assertNull(StandardCustomContentTest.member(fragment, "preview")); assertNull(StandardCustomContentTest.member(fragment, "progressBar"));
			});
		} finally {
			if (previous instanceof Boolean) Preferences.PREFERENCES.edit().put(Preferences.KEY_NEW_INTERFACE_MOTION, (Boolean) previous).close();
			else Preferences.PREFERENCES.edit().remove(Preferences.KEY_NEW_INTERFACE_MOTION).close();
			if (first.get() != null) assertTrue(first.get().delete()); if (second.get() != null) assertTrue(second.get().delete());
		}
	}
}
