package com.mishiranu.dashchan.content.async;

import static org.junit.Assert.*;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import org.junit.Test;

public class CatalogSearchTest {
	private static int[] find(List<CatalogSearch.Document> documents, String query, Locale locale) {
		return new CatalogSearch.Request(documents, query, locale).find();
	}

	@Test public void subjectOrCommentMatchesWithoutChangingOrder() {
		List<CatalogSearch.Document> documents = Arrays.asList(
				new CatalogSearch.Document("Android", "first"),
				new CatalogSearch.Document("second", "ANDROID in text"),
				new CatalogSearch.Document("third", "nothing"));
		assertArrayEquals(new int[] {0, 1}, find(documents, "android", Locale.US));
		assertArrayEquals(new int[] {1}, find(documents, "in text", Locale.US));
		assertArrayEquals(new int[0], find(documents, "missing", Locale.US));
	}

	@Test public void queryIsNotTrimmedTokenizedOrJoinedAcrossFields() {
		List<CatalogSearch.Document> documents = Collections.singletonList(
				new CatalogSearch.Document("one", "two  three\nfour"));
		assertArrayEquals(new int[0], find(documents, "one two", Locale.US));
		assertArrayEquals(new int[0], find(documents, "two three", Locale.US));
		assertArrayEquals(new int[0], find(documents, " one ", Locale.US));
		assertArrayEquals(new int[] {0}, find(documents, "three\nfour", Locale.US));
	}

	@Test public void matchesOldAlgorithmForCyrillicLatinAndWhitespace() {
		String[][] values = {{"ВопросоТред", "Ёжик и Ежик"}, {"I İ ı i", "mixed CASE"},
				{"", " >цитата\nссылка & текст "}, {"some subject", "line\n\nline"}};
		ArrayList<CatalogSearch.Document> documents = new ArrayList<>();
		for (String[] value : values) documents.add(new CatalogSearch.Document(value[0], value[1]));
		for (Locale locale : new Locale[] {Locale.US, Locale.forLanguageTag("ru"), Locale.forLanguageTag("tr")}) {
			for (String query : new String[] {"", "ВОПРОС", "ёж", "еж", "I", "İ", "CASE", " ", "\n", "&", "no match"}) {
				ArrayList<Integer> expected = new ArrayList<>();
				for (int i = 0; i < values.length; i++) {
					if (values[i][0].toLowerCase(locale).contains(query.toLowerCase(locale)) ||
							values[i][1].toLowerCase(locale).contains(query.toLowerCase(locale))) expected.add(i);
				}
				assertArrayEquals(expected.stream().mapToInt(Integer::intValue).toArray(), find(documents, query, locale));
			}
		}
	}

	@Test public void cachedNormalizationTracksLocale() {
		List<CatalogSearch.Document> documents = Collections.singletonList(new CatalogSearch.Document("I", ""));
		assertArrayEquals(new int[] {0}, find(documents, "i", Locale.US));
		assertArrayEquals(new int[0], find(documents, "i", Locale.forLanguageTag("tr")));
		assertArrayEquals(new int[] {0}, find(documents, "i", Locale.US));
	}

	@Test public void requestCopiesSourceList() {
		ArrayList<CatalogSearch.Document> documents = new ArrayList<>();
		documents.add(new CatalogSearch.Document("old", ""));
		CatalogSearch.Request request = new CatalogSearch.Request(documents, "old", Locale.US);
		documents.clear();
		documents.add(new CatalogSearch.Document("new", ""));
		assertArrayEquals(new int[] {0}, request.find());
		assertArrayEquals(new int[0], find(documents, "old", Locale.US));
	}

	@Test public void cancelledRequestCannotProduceResults() {
		List<CatalogSearch.Document> documents = Collections.singletonList(new CatalogSearch.Document("match", ""));
		CatalogSearch.Request old = new CatalogSearch.Request(documents, "match", Locale.US);
		old.cancel();
		assertNull(old.find());
		assertArrayEquals(new int[] {0}, find(documents, "match", Locale.US));
	}

	@Test public void interruptedWorkerStopsWithoutClearingInterrupt() {
		Thread.currentThread().interrupt();
		try {
			assertNull(find(Collections.singletonList(new CatalogSearch.Document("match", "")), "match", Locale.US));
			assertTrue(Thread.currentThread().isInterrupted());
		} finally {
			Thread.interrupted();
		}
	}

	@Test public void emptyCatalogHasNoResults() {
		assertArrayEquals(new int[0], find(Collections.emptyList(), "text", Locale.US));
	}
}
