package com.mishiranu.dashchan;

import static org.junit.Assert.*;
import android.net.Uri;
import android.os.Parcel;
import androidx.fragment.app.Fragment;
import androidx.fragment.app.FragmentFactory;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import com.mishiranu.dashchan.ui.BrowserFragment;
import com.mishiranu.dashchan.ui.InstanceDialog;
import com.mishiranu.dashchan.ui.LocalArchiveViewerFragment;
import com.mishiranu.dashchan.ui.StackItem;
import com.mishiranu.dashchan.ui.navigator.Page;
import com.mishiranu.dashchan.ui.navigator.PageFragment;
import com.mishiranu.dashchan.ui.posting.PostingFragment;
import com.mishiranu.dashchan.ui.preference.AboutFragment;
import com.mishiranu.dashchan.ui.preference.ChanFragment;
import com.mishiranu.dashchan.ui.preference.CookiesFragment;
import com.mishiranu.dashchan.ui.preference.TextFragment;
import com.mishiranu.dashchan.ui.preference.UpdateFragment;
import com.mishiranu.dashchan.util.AndroidUtils;
import java.util.Collections;
import org.junit.Test;
import org.junit.runner.RunWith;

/** Actual Android Bundle/Parcel and the application's StackItem restore path.
 * No website requests, posting, preference edits or model downloads are performed. */
@RunWith(AndroidJUnit4.class)
public class FragmentRestoreSmokeTest {
	@Test public void screenShellsCanBeRecreatedByTheDefaultFactory() {
		InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
			Class<?>[] classes = {PageFragment.class, PostingFragment.class, BrowserFragment.class,
					LocalArchiveViewerFragment.class, ChanFragment.class, CookiesFragment.class,
					TextFragment.class, UpdateFragment.class, AboutFragment.class, InstanceDialog.class};
			FragmentFactory factory = new FragmentFactory();
			for (Class<?> type : classes) {
				assertEquals(type, factory.instantiate(type.getClassLoader(), type.getName()).getClass());
			}
		});
	}

	@Test public void navigationArgumentsSurviveTheApplicationParcelRestorePath() {
		InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
			Page page = new Page(Page.Content.POSTS, "dvach", "test", "123", null);
			PageFragment restoredPage = (PageFragment) restore(new PageFragment(page, "smoke-retain"));
			assertEquals(Page.Content.POSTS, restoredPage.getPage().content);
			assertEquals("dvach", restoredPage.getPage().chanName);
			assertEquals("test", restoredPage.getPage().boardName);
			assertEquals("123", restoredPage.getPage().threadNumber);
			assertEquals("smoke-retain", restoredPage.getRetainId());
			assertEquals("reddit-web-reader", restore(new ChanFragment("reddit-web-reader"))
					.requireArguments().getString("chanName"));
			assertEquals("dvach", restore(new CookiesFragment("dvach")).requireArguments().getString("chanName"));
			PostingFragment posting = (PostingFragment) restore(new PostingFragment("dvach", "test", "123",
					Collections.emptyList()));
			assertTrue(posting.check("dvach", "test", "123"));
			assertEquals("PRIVACY_POLICY", restore(new TextFragment(TextFragment.Type.PRIVACY_POLICY))
					.requireArguments().getString("type"));
			BrowserFragment browser = new BrowserFragment(Uri.parse("https://example.invalid/"));
			assertEquals(AndroidUtils.getParcelable(browser.requireArguments(), "uri", Uri.class),
					AndroidUtils.getParcelable(restore(browser).requireArguments(), "uri", Uri.class));
		});
	}

	private static Fragment restore(Fragment original) {
		Parcel parcel = Parcel.obtain();
		try {
			new StackItem(original.getClass().getName(), original.getArguments(), null).writeToParcel(parcel, 0);
			parcel.setDataPosition(0);
			Fragment restored = StackItem.CREATOR.createFromParcel(parcel).create(null);
			assertEquals(original.getClass(), restored.getClass());
			assertNotSame(original, restored);
			return restored;
		} finally {
			parcel.recycle();
		}
	}
}
