package com.mishiranu.dashchan.widget;

import android.content.Context;
import android.os.Bundle;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import com.mishiranu.dashchan.ui.ContentFragment;
import com.mishiranu.dashchan.ui.DrawerForm;
import com.mishiranu.dashchan.ui.FragmentHandler;
import com.mishiranu.dashchan.content.model.PostNumber;
import com.mishiranu.dashchan.content.service.DownloadService;
import chan.content.ChanLocator;
import java.util.Collection;
import java.util.Collections;

/** Debug-only themed host for disposable stage-4 UI fixtures; no production tasks or navigation. */
public final class StandardContentTestActivity extends androidx.fragment.app.FragmentActivity implements FragmentHandler {
	private FrameLayout content;
	@Override protected void attachBaseContext(Context context) { super.attachBaseContext(ThemeEngine.attach(context)); }
	@Override protected void onCreate(Bundle state) {
		ThemeEngine.applyTheme(this); super.onCreate(state);
		content = new FrameLayout(this); content.setId(android.R.id.widget_frame); setContentView(content);
	}
	@Override public void setTitleSubtitle(CharSequence title, CharSequence subtitle) {}
	@Override public ViewGroup getToolbarView() { return content; }
	@Override public FrameLayout getToolbarExtra() { return content; }
	@Override public Context getToolbarContext() { return this; }
	@Override public void pushFragment(ContentFragment fragment) { throw new AssertionError("No navigation in fixture"); }
	@Override public void removeFragment() {}
	@Override public DownloadService.Binder getDownloadBinder() { return null; }
	@Override public boolean requestStorage() { throw new AssertionError("No storage request in fixture"); }
	@Override public void navigateTargetAllowReturn(String chanName, ChanLocator.NavigationData data) { throw new AssertionError("No navigation in fixture"); }
	@Override public void scrollToPost(String chanName, String board, String thread, PostNumber post) {}
	@Override public Collection<DrawerForm.Page> obtainDrawerPages() { return Collections.emptyList(); }
	@Override public void setActionBarLocked(String locker, boolean locked) {}
	@Override public void setNavigationAreaLocked(String locker, boolean locked) {}
}
