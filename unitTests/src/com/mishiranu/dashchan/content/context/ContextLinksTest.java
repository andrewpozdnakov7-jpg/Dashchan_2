package com.mishiranu.dashchan.content.context;

import static org.junit.Assert.*;
import java.util.List;
import org.junit.Test;

public class ContextLinksTest {
	@Test public void destinationComesFromHrefNotDisplayedNumber() {
		assertEquals(List.of("/b/res/200.html#100"), ContextLinks.read(
				"<a href='/b/res/200.html#100'>&gt;&gt;999</a>", () -> false).hrefs());
	}
	@Test public void plainTextCodeAndMissingHrefDoNotCreateEdges() {
		assertTrue(ContextLinks.read(
				">>1 <a>&gt;&gt;2</a><pre><a href='#3'>&gt;&gt;3</a></pre>", () -> false).hrefs().isEmpty());
	}
	@Test public void oversizedInputIsNotPartiallyParsed() {
		ContextLinks.Links result = ContextLinks.read("x".repeat(ContextGraph.MAX_POST_TEXT + 1), () -> false);
		assertTrue(result.incomplete()); assertTrue(result.hrefs().isEmpty());
	}
	@Test public void linkBudgetIsEnforced() {
		ContextLinks.Links result = ContextLinks.read("<a href='#1'>x</a>".repeat(300), () -> false);
		assertEquals(256, result.hrefs().size()); assertTrue(result.incomplete());
	}
	@Test public void hrefEntitiesAreDecoded() {
		assertEquals(List.of("/b/res/200.html?x=1&y=2#100"), ContextLinks.read(
				"<a href='/b/res/200.html?x=1&amp;y=2#100'>quote</a>", () -> false).hrefs());
	}
	@Test(expected = java.util.concurrent.CancellationException.class) public void cancellationStopsParsing() {
		ContextLinks.read("<a href='#1'>quote</a>", () -> true);
	}
}
