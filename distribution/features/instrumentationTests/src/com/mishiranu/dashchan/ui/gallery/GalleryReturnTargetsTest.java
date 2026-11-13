package com.mishiranu.dashchan.ui.gallery;

import static org.junit.Assert.*;
import android.view.View;
import android.widget.LinearLayout;
import androidx.test.core.app.ActivityScenario;
import com.mishiranu.dashchan.widget.AttachmentView;
import com.mishiranu.dashchan.widget.StandardContentTestActivity;
import org.junit.Test;

/** Source-window routing fixtures; prepared only, requires device execution. */
public class GalleryReturnTargetsTest {
	private static AttachmentView add(LinearLayout root, String resource) {
		AttachmentView view = new AttachmentView(root.getContext(), null);
		view.resetImage("shared_thumbnail"); view.setMediaResource(resource);
		root.addView(view, new LinearLayout.LayoutParams(80, 80)); return view;
	}
	@Test public void pagingResolvesTheCurrentCellAndRejectsReboundOrDetachedCells() {
		try (ActivityScenario<StandardContentTestActivity> scenario = ActivityScenario.launch(StandardContentTestActivity.class)) {
			scenario.onActivity(activity -> {
				LinearLayout root = new LinearLayout(activity); activity.setContentView(root);
				AttachmentView first = add(root, "fixture:first"), second = add(root, "fixture:second");
				GalleryReturnTargets targets = new GalleryReturnTargets(first, "fixture:first");
				assertSame(first, targets.resolve("fixture:first"));
				assertSame(second, targets.resolve("fixture:second"));
				assertNull(targets.resolve("fixture:not_in_origin"));
				long generation = second.getImageBindingGeneration();
				second.setMediaResource("fixture:third");
				assertTrue(second.getImageBindingGeneration() > generation);
				assertNull(targets.resolve("fixture:second"));
				second.setMediaResource("fixture:second");
				assertNull(targets.resolve("fixture:second")); // No ABA after recycling.
				root.removeView(first); assertNull(targets.resolve("fixture:first"));
			});
		}
	}
	@Test public void hiddenAndDifferentOriginCellsAreNotUsedAndOpeningCellWinsDuplicates() {
		try (ActivityScenario<StandardContentTestActivity> scenario = ActivityScenario.launch(StandardContentTestActivity.class)) {
			scenario.onActivity(activity -> {
				LinearLayout root = new LinearLayout(activity); activity.setContentView(root);
				AttachmentView duplicate = add(root, "fixture:first"), first = add(root, "fixture:first");
				AttachmentView second = add(root, "fixture:second");
				GalleryReturnTargets targets = new GalleryReturnTargets(first, "fixture:first");
				assertSame(first, targets.resolve("fixture:first"));
				second.setVisibility(View.INVISIBLE); assertNull(targets.resolve("fixture:second"));
				second.setVisibility(View.VISIBLE); assertSame(second, targets.resolve("fixture:second"));
				LinearLayout other = new LinearLayout(activity);
				root.removeView(second); other.addView(second);
				assertNull(targets.resolve("fixture:second"));
				first.setMediaResource("fixture:changed");
				assertSame(duplicate, targets.resolve("fixture:first"));
			});
		}
	}
}
