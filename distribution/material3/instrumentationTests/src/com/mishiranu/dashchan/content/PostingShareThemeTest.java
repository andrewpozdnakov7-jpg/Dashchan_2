package com.mishiranu.dashchan.content;

import static org.junit.Assert.*;
import android.content.Context;
import android.content.res.Resources;
import android.content.res.TypedArray;
import android.view.ContextThemeWrapper;
import android.widget.FrameLayout;
import android.widget.ProgressBar;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import com.mishiranu.dashchan.widget.InterfaceAppearance;
import com.mishiranu.dashchan.widget.ThemeEngine;
import org.junit.Test;
import org.junit.runner.RunWith;

/** UI-context regression only: no incoming share, file read, draft, navigation or network. NOT RUN. */
@RunWith(AndroidJUnit4.class)
public class PostingShareThemeTest {
	private static boolean translucent(Context context) {
		TypedArray attrs = context.obtainStyledAttributes(new int[] {android.R.attr.windowIsTranslucent});
		try { return attrs.getBoolean(0, false); } finally { attrs.recycle(); }
	}
	private static void profile(boolean enabled, Runnable check) {
		Object previous = Preferences.PREFERENCES.getAll().get(Preferences.KEY_NEW_INTERFACE_MOTION);
		try {
			Preferences.PREFERENCES.edit().put(Preferences.KEY_NEW_INTERFACE_MOTION, enabled).close(); check.run();
		} finally {
			if (previous instanceof Boolean) Preferences.PREFERENCES.edit().put(Preferences.KEY_NEW_INTERFACE_MOTION, (Boolean) previous).close();
			else Preferences.PREFERENCES.edit().remove(Preferences.KEY_NEW_INTERFACE_MOTION).close();
		}
	}
	private static Context shareOwner() {
		return new ContextThemeWrapper(InstrumentationRegistry.getInstrumentation().getTargetContext(),
				android.R.style.Theme_Translucent_NoTitleBar);
	}
	@Test public void materialImportViewsResolveTheirPaletteWithoutChangingTheOwnerWindowTheme() {
		InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> profile(true, () -> {
			Context owner = shareOwner(); Resources.Theme original = owner.getTheme();
			assertTrue(translucent(owner)); assertNull(ThemeEngine.peekTheme(owner));
			Context content = PostingShareActivity.createImportContext(owner);
			assertNotSame(owner, content); assertNotNull(ThemeEngine.peekTheme(content));
			FrameLayout page = new FrameLayout(content); ProgressBar loading = new ProgressBar(content);
			InterfaceAppearance.standardPage(page); InterfaceAppearance.standardLoading(loading);
			page.addView(loading); assertNotNull(page.getBackground());
			assertSame(original, owner.getTheme()); assertTrue(translucent(owner));
			assertNull(ThemeEngine.peekTheme(owner));
		}));
	}
	@Test public void legacyImportKeepsTheExactOriginalContextAndTranslucentTheme() {
		InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> profile(false, () -> {
			Context owner = shareOwner(); Resources.Theme original = owner.getTheme();
			Context content = PostingShareActivity.createImportContext(owner);
			assertSame(owner, content);
			FrameLayout page = new FrameLayout(content); ProgressBar loading = new ProgressBar(content);
			InterfaceAppearance.standardPage(page); InterfaceAppearance.standardLoading(loading);
			assertSame(original, owner.getTheme()); assertTrue(translucent(owner));
			assertNull(ThemeEngine.peekTheme(content));
		}));
	}
}
