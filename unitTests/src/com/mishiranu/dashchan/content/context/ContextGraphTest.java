package com.mishiranu.dashchan.content.context;

import static org.junit.Assert.*;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.junit.Test;

public class ContextGraphTest {
	private static ContextGraph.Key k(int n) { return new ContextGraph.Key("test", "b", "100", n, 0); }
	private static List<ContextGraph.Key> keys(ContextGraph.Result result) {
		List<ContextGraph.Key> keys = new ArrayList<>();
		for (ContextGraph.Entry entry : result.entries()) keys.add(entry.key());
		return keys;
	}
	private static ContextGraph.Result build(ContextGraph graph, int target, Set<ContextGraph.Key> expanded) {
		return graph.build(k(target), 3, expanded, 20, Collections.emptyList(), () -> false);
	}
	@Test public void chainRequiresExplicitContinuation() {
		ContextGraph g = new ContextGraph();
		g.add(k(120), List.of()); g.add(k(186), List.of(k(120))); g.add(k(241), List.of(k(186)));
		g.add(k(279), List.of(k(241))); g.add(k(302), List.of(k(279)));
		assertEquals(List.of(k(120), k(186), k(241), k(279)), keys(build(g, 241, Set.of())));
		assertEquals(List.of(k(120), k(186), k(241), k(279), k(302)), keys(build(g, 241, Set.of(k(279)))));
	}
	@Test public void siblingsAreNotIncludedThroughCommonParent() {
		ContextGraph g = new ContextGraph();
		g.add(k(2), List.of(k(1))); g.add(k(3), List.of(k(1)));
		assertEquals(List.of(k(1), k(2)), keys(build(g, 2, Set.of())));
	}
	@Test public void diamondAndDuplicateQuotesHaveSingleCard() {
		ContextGraph g = new ContextGraph();
		g.add(k(2), List.of(k(1))); g.add(k(3), List.of(k(1))); g.add(k(4), List.of(k(2), k(3), k(2)));
		assertEquals(List.of(k(1), k(2), k(3), k(4)), keys(build(g, 4, Set.of())));
	}
	@Test public void cyclesAndSelfReferencesTerminate() {
		ContextGraph g = new ContextGraph();
		g.add(k(1), List.of(k(1), k(3))); g.add(k(2), List.of(k(1))); g.add(k(3), List.of(k(2)));
		List<ContextGraph.Key> result = keys(build(g, 1, Set.of(k(2), k(3))));
		assertEquals(3, result.size()); assertEquals(3, new HashSet<>(result).size());
	}
	@Test public void crossThreadAndCrossBoardLinksRejected() {
		ContextGraph g = new ContextGraph();
		g.add(k(2), List.of(new ContextGraph.Key("test", "other", "100", 1, 0),
				new ContextGraph.Key("test", "b", "200", 1, 0), new ContextGraph.Key("other", "b", "100", 1, 0)));
		assertEquals(List.of(k(2)), keys(build(g, 2, Set.of())));
	}
	@Test public void missingTargetRemainsPresent() {
		assertEquals(List.of(k(42)), keys(build(new ContextGraph(), 42, Set.of())));
	}
	@Test public void largeFanoutAndRetainedPageAreBounded() {
		ContextGraph g = new ContextGraph();
		for (int i = 2; i < 1002; i++) g.add(k(i), List.of(k(1)));
		ContextGraph.Result first = build(g, 1, Set.of());
		assertEquals(20, first.entries().size()); assertTrue(first.limited());
		ContextGraph.Result next = g.build(k(1), 3, Set.of(), 40, keys(first), () -> false);
		assertTrue(keys(next).containsAll(keys(first))); assertEquals(40, next.entries().size());
		assertEquals(200, g.build(k(1), 99, Set.of(), 9000, List.of(), () -> false).entries().size());
	}
	@Test public void orderIsIndependentOfInsertion() {
		ContextGraph a = new ContextGraph(), b = new ContextGraph();
		for (int i = 2; i < 30; i++) a.add(k(i), List.of(k(1)));
		for (int i = 29; i >= 2; i--) b.add(k(i), List.of(k(1)));
		assertEquals(build(a, 1, Set.of()), build(b, 1, Set.of()));
	}
	@Test public void minorNumbersAreNumeric() {
		assertTrue(new ContextGraph.Key("s", "b", "t", 12, 2).compareTo(new ContextGraph.Key("s", "b", "t", 12, 10)) < 0);
	}
	@Test public void changedQuoteRemovesOldReverseLink() {
		ContextGraph g = new ContextGraph(); g.add(k(2), List.of(k(1))); g.add(k(2), List.of(k(3)));
		assertEquals(List.of(k(1)), keys(build(g, 1, Set.of())));
	}
	@Test(expected = java.util.concurrent.CancellationException.class) public void cancellationStopsWork() {
		new ContextGraph().build(k(1), 3, Set.of(), 20, List.of(), () -> true);
	}
	@Test public void depthIsBounded() {
		ContextGraph g = new ContextGraph(); for (int i = 2; i < 100; i++) g.add(k(i), List.of(k(i - 1)));
		assertEquals(List.of(k(7), k(8), k(9), k(10), k(11)), keys(build(g, 10, Set.of())));
	}
}
