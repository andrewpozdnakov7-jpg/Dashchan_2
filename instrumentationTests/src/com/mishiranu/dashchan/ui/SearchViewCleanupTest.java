package com.mishiranu.dashchan.ui;

import android.view.ContextThemeWrapper;
import android.view.View;
import androidx.test.platform.app.InstrumentationRegistry;
import com.mishiranu.dashchan.R;
import com.mishiranu.dashchan.ui.navigator.PageFragment;
import com.mishiranu.dashchan.widget.CustomSearchView;
import java.lang.ref.WeakReference;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import org.junit.Test;
import static org.junit.Assert.*;

/** Synthetic view ownership only: no Activity, thread/network load or profile mutation. NOT RUN. */
public class SearchViewCleanupTest {
	@Test public void detachedPageCleanupDoesNotTryToObtainSharedSearchView() {
		InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
			PageFragment page = new PageFragment();
			page.setCustomSearchView(null);
			page.setCustomSearchView(null);
			View late = new View(InstrumentationRegistry.getInstrumentation().getTargetContext());
			page.setCustomSearchView(late);
			assertNull(late.getParent());
		});
	}
	@Test public void departingOwnerCannotClearTheNextOwnersCustomContent() {
		InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
			try {
				ContentFragment.ViewHolderFragment holder = new ContentFragment.ViewHolderFragment();
				PageFragment departed = new PageFragment(), current = new PageFragment();
				CustomSearchView search = new CustomSearchView(new ContextThemeWrapper(
						InstrumentationRegistry.getInstrumentation().getTargetContext(), R.style.Theme_Special_White));
				View marker = new View(search.getContext()); search.setCustomView(marker);
				Field viewField = holder.getClass().getDeclaredField("searchView"); viewField.setAccessible(true); viewField.set(holder, search);
				Field ownerField = holder.getClass().getDeclaredField("searchViewOwner"); ownerField.setAccessible(true); ownerField.set(holder, new WeakReference<ContentFragment>(current));
				Method clear = holder.getClass().getDeclaredMethod("clearCustomSearchView", ContentFragment.class); clear.setAccessible(true);
				clear.invoke(holder, departed); assertNotNull(marker.getParent());
				clear.invoke(holder, current); assertNull(marker.getParent());
			} catch (ReflectiveOperationException e) { throw new AssertionError(e); }
		});
	}
}
