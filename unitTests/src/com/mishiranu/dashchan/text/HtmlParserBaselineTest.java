package com.mishiranu.dashchan.text;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.util.ArrayList;
import java.util.Arrays;
import org.junit.Test;
import org.xml.sax.Attributes;

/** Regression checks for HTML text and markup callbacks. */
public class HtmlParserBaselineTest {
	@Test
	public void clearPreservesBasicTextAndEntityDecoding() {
		assertEquals("first & second", HtmlParser.clear("<b>first</b> &amp; second"));
		assertEquals("A B", HtmlParser.clear("A&nbsp;B"));
		assertEquals("A B", HtmlParser.clear("A  B"));
		assertEquals("unclosed", HtmlParser.clear("<b>unclosed"));
		assertEquals("shown", HtmlParser.clear("<script>ignore me</script>shown"));
	}

	@Test
	public void clearPreservesLineAndBlockBreaks() {
		assertEquals("A\nB", HtmlParser.clear("A<br>B"));
		assertEquals("one\n\ntwo", HtmlParser.clear("<p>one</p><p>two</p>"));
		assertEquals("A  B\nC", HtmlParser.clear("<pre>A  B\nC</pre>"));
	}

	@Test
	public void clearKeepsEntitiesAndIgnoresComments() {
		assertEquals("quote >>42", HtmlParser.clear("<span>quote &gt;&gt;42</span>"));
		assertEquals("visible", HtmlParser.clear("<!-- hidden -->visible"));
	}

	@Test
	public void spanifyAndUnmarkKeepMarkupCallbacks() {
		ProbeMarkup spanMarkup = new ProbeMarkup();
		assertEquals("quote", HtmlParser.spanify("<a href='#42'>quote</a>",
				spanMarkup, null, null, null).toString());
		assertEquals(Arrays.asList("start:a:#42@0", "end:a@5"), spanMarkup.events);

		ProbeMarkup unmarkMarkup = new ProbeMarkup();
		assertEquals("quote", HtmlParser.unmark("<a href='#42'>quote</a>", unmarkMarkup, null));
		assertEquals(Arrays.asList("start:a:#42@0", "end:a@5"), unmarkMarkup.events);
	}

	@Test
	public void spanifyAndUnmarkKeepTextAcrossCommonFragments() {
		String[][] fragments = {
				{"plain &amp; text", "plain & text"},
				{"<b>bold</b> and <i>italic</i>", "bold and italic"},
				{"before<br>after", "before\nafter"},
				{"<script>hidden</script>visible", "visible"}
		};
		for (String[] fragment : fragments) {
			assertEquals(fragment[1], HtmlParser.spanify(fragment[0],
					new ProbeMarkup(), null, null, null).toString());
			assertEquals(fragment[1], HtmlParser.unmark(fragment[0], new ProbeMarkup(), null));
		}
	}

	@Test
	public void tableCallbacksSkipOnlyImplicitTbody() {
		ProbeMarkup implicit = new ProbeMarkup(true);
		HtmlParser.spanify("<table><tr><td>A</td><td>B</td></tr></table>",
				implicit, null, null, null);
		assertEquals(Arrays.asList("start:html:null@0", "start:body:null@0", "start:table:null@0",
				"start:tr:null@0", "start:td:null@0", "end:td@4", "start:td:null@5",
				"end:td@9", "end:tr@10", "end:table@11", "end:body@11", "end:html@11"),
				implicit.events);

		ProbeMarkup explicit = new ProbeMarkup(true);
		HtmlParser.spanify("<table><tbody><tr><td>A</td></tr></tbody></table>",
				explicit, null, null, null);
		assertTrue(explicit.events.stream().anyMatch(event -> event.startsWith("start:tbody:")));
		assertTrue(explicit.events.stream().anyMatch(event -> event.startsWith("end:tbody@")));
	}

	private static final class ProbeMarkup implements HtmlParser.Markup<Void, Void, HtmlParser.SpanProvider<Void>> {
		private final ArrayList<String> events = new ArrayList<>();
		private final boolean traceAllTags;

		private ProbeMarkup() {
			this(false);
		}

		private ProbeMarkup(boolean traceAllTags) {
			this.traceAllTags = traceAllTags;
		}

		@Override
		public Void onBeforeTagStart(HtmlParser<Void, Void, HtmlParser.SpanProvider<Void>> parser,
				StringBuilder builder, String tagName, Attributes attributes, HtmlParser.TagData tagData) {
			return null;
		}

		@Override
		public void onTagStart(HtmlParser<Void, Void, HtmlParser.SpanProvider<Void>> parser,
				StringBuilder builder, String tagName, Attributes attributes, Void object) {
			if (traceAllTags || "a".equals(tagName)) {
				events.add("start:" + tagName + ":" + attributes.getValue("", "href") + "@" + builder.length());
			}
		}

		@Override
		public void onTagEnd(HtmlParser<Void, Void, HtmlParser.SpanProvider<Void>> parser,
				StringBuilder builder, String tagName) {
			if (traceAllTags || "a".equals(tagName)) {
				events.add("end:" + tagName + "@" + builder.length());
			}
		}

		@Override
		public int onListLineStart(HtmlParser<Void, Void, HtmlParser.SpanProvider<Void>> parser,
				StringBuilder builder, boolean ordered, int line) {
			return 0;
		}

		@Override
		public void onCutBlock(HtmlParser<Void, Void, HtmlParser.SpanProvider<Void>> parser,
				StringBuilder builder) {}

		@Override
		public HtmlParser.SpanProvider<Void> initSpanProvider(
				HtmlParser<Void, Void, HtmlParser.SpanProvider<Void>> parser) {
			return (htmlParser, builder) -> builder.toString();
		}
	}
}
