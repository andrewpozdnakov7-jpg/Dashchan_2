package com.mishiranu.dashchan.ui;

import static org.junit.Assert.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.Test;

/** Source routing contracts, not an executed Activity navigation or network test. */
public class ToolbarBackContractTest {
	private static String source(String relative) throws Exception {
		Path root = Path.of(".").toAbsolutePath();
		while (root != null) {
			Path file = root.resolve(relative);
			if (Files.isRegularFile(file)) return Files.readString(file, StandardCharsets.UTF_8);
			root = root.getParent();
		}
		throw new AssertionError("Missing source: " + relative);
	}

	private static String activity() throws Exception {
		return source("src/com/mishiranu/dashchan/ui/MainActivity.java");
	}

	private static String method(String name) throws Exception {
		String text = activity();
		Matcher matcher = Pattern.compile("(?m)^\\t(?:private|protected|public) void " + Pattern.quote(name) + "\\(").matcher(text);
		assertTrue("Missing method: " + name, matcher.find());
		int start = text.indexOf('{', matcher.end()) + 1;
		int depth = 1, end = start;
		while (end < text.length() && depth > 0) {
			char c = text.charAt(end++);
			if (c == '{') depth++;
			if (c == '}') depth--;
		}
		assertEquals("Unbalanced method: " + name, 0, depth);
		return text.substring(start, end - 1).replaceAll("\\s+", " ");
	}

	@Test public void toolbarArrowUsesSystemBackInsteadOfOpeningParentBoard() throws Exception {
		String text = activity();
		int start = text.indexOf("if (item.getItemId() == android.R.id.home)");
		assertTrue(start >= 0);
		int end = text.indexOf("} else if (item.getItemId()", start);
		assertTrue(end > start);
		String home = text.substring(start, end);
		assertTrue(home.contains("onSystemBackPressed();"));
		assertTrue(home.contains("return true;"));
		for (String forbidden : new String[] {"onHomePressed()", "navigateData(", "navigateInitial(", "removeFragment()",
				"stackPageItems", "preservedPageItems", "FLAG_DATA_FROM_CACHE"}) {
			assertFalse("Independent toolbar path: " + forbidden, home.contains(forbidden));
		}
		assertFalse(text.contains("clearStackAndCurrent"));
	}

	@Test public void systemBackKeepsItsCallbacksExitPolicyAndPredictiveCleanup() throws Exception {
		String back = method("onSystemBackPressed");
		assertTrue(back.contains("onBackPressed(false, true, this::performDefaultBack)"));
		assertTrue(back.contains("resetPredictiveBackView(predictiveBackView, true)"));
		assertTrue(back.contains("scheduleSystemBackCallbackUpdate()"));
		String dispatch = method("onBackPressed");
		assertTrue(dispatch.contains("currentFragment.onBackPressed()"));
		assertTrue(dispatch.contains("navigateSavedPage(savedPageItem, true)"));
		assertTrue(dispatch.contains("Preferences.isCloseOnBack()"));
	}

	@Test public void savedPageReturnDoesNotRequestForcedLoading() throws Exception {
		String restore = method("navigateSavedPage");
		assertTrue(restore.contains("savedPageItem.create()"));
		assertTrue(restore.contains("navigateFragment(pair.first, pair.second, closeOverlays)"));
		assertFalse(restore.contains("setInitRequest("));
		assertFalse(restore.contains("navigateData("));
		String page = source("src/com/mishiranu/dashchan/ui/navigator/page/ListPage.java");
		assertTrue(page.contains("EMPTY_REQUEST = new InitRequest(false, null, null)"));
		String threads = source("src/com/mishiranu/dashchan/ui/navigator/page/ThreadsPage.java");
		assertTrue(threads.contains("!initRequest.shouldLoad && !retainableExtra.cachedPostItems.isEmpty()"));
	}
}
