package com.mishiranu.dashchan.widget;

import android.content.Context;
import android.view.ContextThemeWrapper;
import androidx.test.platform.app.InstrumentationRegistry;

/** Apply the same app theme as MainActivity, without changing the shared Application theme. */
final class PostingTestContext {
	private PostingTestContext() {}
	static Context create() {
		return create(InstrumentationRegistry.getInstrumentation().getTargetContext());
	}
	static Context create(Context base) {
		Context context = ThemeEngine.attach(new ContextThemeWrapper(base, android.R.style.Theme_Material_Light));
		ThemeEngine.applyTheme(context);
		return context;
	}
}
