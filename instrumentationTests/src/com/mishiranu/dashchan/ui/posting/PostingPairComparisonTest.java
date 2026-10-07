package com.mishiranu.dashchan.ui.posting;

import static org.junit.Assert.*;
import android.util.Pair;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.Test;
import org.junit.runner.RunWith;

/** Standalone legacy bugfix: exercises the real helper/Android Pair without attaching a form. */
@RunWith(AndroidJUnit4.class)
public class PostingPairComparisonTest {
	private static boolean same(List<Pair<String, String>> first, List<Pair<String, String>> second) {
		AtomicReference<Boolean> result = new AtomicReference<>();
		InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
			try {
				Method method = PostingFragment.class.getDeclaredMethod("compareListOfPairs", List.class, List.class);
				method.setAccessible(true);
				result.set((Boolean) method.invoke(new PostingFragment(), first, second));
			} catch (ReflectiveOperationException e) {
				throw new AssertionError("Cannot invoke real pair comparison", e);
			}
		});
		return result.get();
	}

	@Test public void emptyListsAreEqual() {
		assertTrue(same(Collections.emptyList(), new ArrayList<>()));
	}

	@Test public void separateEqualPairsWithDifferentKeyAndLabelAreEqual() {
		List<Pair<String, String>> first = Arrays.asList(new Pair<>("safe", "Safe"), new Pair<>("other", "Other"));
		List<Pair<String, String>> second = Arrays.asList(new Pair<>("safe", "Safe"), new Pair<>("other", "Other"));
		assertTrue(same(first, second));
		assertTrue(same(first, first));
	}

	@Test public void differentKeysAndLabelsAreDetectedIndependently() {
		List<Pair<String, String>> original = Collections.singletonList(new Pair<>("safe", "Safe"));
		assertFalse(same(original, Collections.singletonList(new Pair<>("other", "Safe"))));
		assertFalse(same(original, Collections.singletonList(new Pair<>("safe", "Changed label"))));
	}

	@Test public void everyPositionAndOrderAreCompared() {
		Pair<String, String> a = new Pair<>("a", "First"), b = new Pair<>("b", "Second");
		List<Pair<String, String>> original = Arrays.asList(a, b);
		assertFalse(same(original, Arrays.asList(b, a)));
		assertFalse(same(original, Arrays.asList(a, new Pair<>("b", "Changed second label"))));
		assertFalse(same(original, Arrays.asList(a, new Pair<>("c", "Second"))));
	}

	@Test public void differentSizesAreNotEqualInEitherDirection() {
		List<Pair<String, String>> one = Collections.singletonList(new Pair<>("a", "First"));
		assertFalse(same(one, Collections.emptyList()));
		assertFalse(same(Collections.emptyList(), one));
		assertFalse(same(one, Arrays.asList(one.get(0), new Pair<>("b", "Second"))));
	}

	@Test public void nullFieldsRetainExistingNullSafeSemantics() {
		List<Pair<String, String>> original = Arrays.asList(new Pair<>(null, "Label"), new Pair<>("key", null), new Pair<>(null, null));
		assertTrue(same(original, Arrays.asList(new Pair<>(null, "Label"), new Pair<>("key", null), new Pair<>(null, null))));
		assertFalse(same(original, Arrays.asList(new Pair<>("key", "Label"), new Pair<>("key", null), new Pair<>(null, null))));
		assertFalse(same(original, Arrays.asList(new Pair<>(null, "Label"), new Pair<>("key", "Label"), new Pair<>(null, null))));
	}
}
