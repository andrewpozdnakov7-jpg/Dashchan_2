package com.mishiranu.dashchan.content.context;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.function.BooleanSupplier;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Element;

/** Parse actual hrefs, never infer an edge from the displayed number or plain text. */
public final class ContextLinks {
	public record Links(List<String> hrefs, boolean incomplete) {}
	public static Links read(String html, BooleanSupplier cancelled) {
		checkCancelled(cancelled);
		if (html.length() > ContextGraph.MAX_POST_TEXT) return new Links(Collections.emptyList(), true);
		List<String> hrefs = new ArrayList<>();
		// The existing bundled parser also works in JVM tests. Parsing a string performs no I/O.
		for (Element link : Jsoup.parseBodyFragment(html).select("a[href]")) {
			checkCancelled(cancelled);
			boolean code = false;
			int depth = 0;
			for (Element parent = link.parent(); parent != null; parent = parent.parent()) {
				checkCancelled(cancelled);
				if (++depth > 256) return new Links(Collections.unmodifiableList(hrefs), true);
				if ("code".equals(parent.normalName()) || "pre".equals(parent.normalName())) { code = true; break; }
			}
			String href = link.attr("href");
			if (code || href.isEmpty()) continue;
			if (hrefs.size() == 256) return new Links(Collections.unmodifiableList(hrefs), true);
			hrefs.add(href);
		}
		checkCancelled(cancelled);
		return new Links(Collections.unmodifiableList(hrefs), false);
	}
	private static void checkCancelled(BooleanSupplier cancelled) {
		if (cancelled.getAsBoolean()) throw new java.util.concurrent.CancellationException();
	}
}
