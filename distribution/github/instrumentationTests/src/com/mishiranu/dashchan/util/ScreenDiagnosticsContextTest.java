package com.mishiranu.dashchan.util;

import static org.junit.Assert.*;
import android.content.Context;
import android.widget.FrameLayout;
import android.widget.TextView;
import androidx.test.platform.app.InstrumentationRegistry;
import com.mishiranu.dashchan.widget.ThemeEngine;
import org.junit.Test;

public class ScreenDiagnosticsContextTest {
	@Test public void detachedSystemRootDoesNotRequireOrCreateAnAppTheme() {
		InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
			Context base = InstrumentationRegistry.getInstrumentation().getTargetContext().getApplicationContext();
			FrameLayout root = new FrameLayout(base);
			assertNull(ThemeEngine.peekTheme(base)); assertNull(ScreenDiagnostics.themeContext(null, root));
			assertNull(ThemeEngine.peekTheme(base));
		});
	}
	@Test public void thematicChildProvidesReadOnlyFallbackForDecorLikeContext() {
		InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
			Context base = InstrumentationRegistry.getInstrumentation().getTargetContext().getApplicationContext();
			Context themed = ThemeEngine.attach(base); ThemeEngine.applyTheme(themed);
			FrameLayout root = new FrameLayout(base); root.addView(new TextView(themed));
			assertSame(themed, ScreenDiagnostics.themeContext(null, root));
			assertNotNull(ThemeEngine.peekTheme(themed)); assertNull(ThemeEngine.peekTheme(base));
		});
	}
}
