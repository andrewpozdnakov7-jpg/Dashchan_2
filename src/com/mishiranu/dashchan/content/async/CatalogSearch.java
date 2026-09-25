package com.mishiranu.dashchan.content.async;

import java.util.Arrays;
import java.util.List;
import java.util.Locale;

/** Search sees only strings, never PostItem, spans, Views or forum markup implementations. */
public final class CatalogSearch {
	private CatalogSearch() {}

	public static final class Document {
		private final String subject;
		private final String comment;
		private volatile Normalized normalized;

		public Document(String subject, String comment) {
			this.subject = subject;
			this.comment = comment;
		}

		private Normalized prepare(Locale locale) {
			Normalized result = normalized;
			if (result == null || !result.locale.equals(locale)) {
				result = new Normalized(locale, subject.toLowerCase(locale), comment.toLowerCase(locale));
				normalized = result;
			}
			return result;
		}
	}

	private static final class Normalized {
		final Locale locale;
		final String subject;
		final String comment;

		Normalized(Locale locale, String subject, String comment) {
			this.locale = locale;
			this.subject = subject;
			this.comment = comment;
		}
	}

	public static final class Request {
		private final Document[] documents;
		private final String query;
		private final Locale locale;
		private volatile boolean cancelled;

		public Request(List<Document> documents, String query, Locale locale) {
			this.documents = documents.toArray(new Document[0]);
			this.query = query;
			this.locale = locale;
		}

		public void cancel() {
			cancelled = true;
		}

		public boolean isCancelled() {
			return cancelled || Thread.currentThread().isInterrupted();
		}

		/** Indices preserve the supplied sorting order. Null means cancellation, not zero matches. */
		public int[] find() {
			if (isCancelled()) return null;
			String text = query.toLowerCase(locale);
			int[] matches = new int[documents.length];
			int count = 0;
			for (int i = 0; i < documents.length; i++) {
				if (isCancelled()) return null;
				Normalized document = documents[i].prepare(locale);
				// Preserve the old substring, whitespace, locale and subject-or-comment semantics.
				if (document.subject.contains(text) || document.comment.contains(text)) matches[count++] = i;
			}
			return isCancelled() ? null : Arrays.copyOf(matches, count);
		}
	}
}
