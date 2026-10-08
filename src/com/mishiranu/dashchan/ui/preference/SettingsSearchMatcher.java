package com.mishiranu.dashchan.ui.preference;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Local settings-text matching only: no network, models or query logging. */
final class SettingsSearchMatcher {
	private static final Pattern DIACRITICS = Pattern.compile("\\p{M}+");
	private static final Pattern WORD = Pattern.compile("[\\p{L}\\p{N}]+");
	private static final int MAX_FUZZY_WORD_LENGTH = 64;
	private SettingsSearchMatcher() {}

	static String normalize(String value) {
		String text = value != null ? value : "";
		return DIACRITICS.matcher(Normalizer.normalize(text, Normalizer.Form.NFD))
				.replaceAll("").toLowerCase(Locale.ROOT).replace('\u0451', '\u0435');
	}

	static final class Text {
		final String normalized;
		final String[] words;
		Text(String text) {
			normalized = normalize(text);
			ArrayList<String> words = new ArrayList<>();
			Matcher matcher = WORD.matcher(normalized);
			while (matcher.find()) words.add(matcher.group());
			this.words = words.toArray(new String[0]);
		}
	}

	private static String[] alternatives(String word) {
		switch (word) {
			case "пип": case "pip": case "picture-in-picture":
				return new String[] {"pip", "picture-in-picture", "картинка в картинке"};
			case "тикток": case "tiktok": case "тик-ток":
				return new String[] {"tiktok", "tik tok", "тикток", "тик ток"};
			case "вайфай": case "вай-фай": case "wifi": case "wi-fi":
				return new String[] {"wi-fi", "wifi", "wi fi", "вайфай"};
			case "кеш": case "кэш": case "cache":
				return new String[] {"кеш", "кэш", "cache"};
			case "ночная": case "темная": case "night": case "dark":
				return new String[] {"ночн", "темн", "night", "dark"};
			case "тема": case "темы": case "theme": case "themes":
				return new String[] {"тема", "темы", "theme"};
			default: return new String[0];
		}
	}

	private static final class Term {
		final String word;
		final String[] alternatives;
		final int limit;
		Term(String word, boolean allowFuzzy) {
			this.word = word;
			alternatives = alternatives(word);
			limit = allowFuzzy && word.length() >= 4 && word.length() <= MAX_FUZZY_WORD_LENGTH
					&& WORD.matcher(word).matches() ? (word.length() >= 8 ? 2 : 1) : 0;
		}
	}

	/** One query/workspace per search call. Entries remain immutable and reusable. */
	static final class Query {
		private final Term[] terms;
		private final int[][] rows = new int[3][MAX_FUZZY_WORD_LENGTH + 3];
		Query(String query) {
			String normalized = normalize(query).trim();
			if ("тик ток".equals(normalized)) normalized = "тикток";
			String[] words = normalized.isEmpty() ? new String[0] : normalized.split("\\s+");
			terms = new Term[words.length];
			for (int i = 0; i < words.length; i++) terms[i] = new Term(words[i], words.length <= 12);
		}
		boolean isEmpty() { return terms.length == 0; }
		int score(Text text) {
			int total = 0;
			for (Term term : terms) {
				// Keep the previous substring/all-words behavior, including incomplete words.
				if (text.normalized.contains(term.word)) continue;
				boolean alias = false;
				for (String alternative : term.alternatives) {
					if (text.normalized.contains(alternative)) { alias = true; break; }
				}
				if (alias) { total += 5; continue; }
				if (term.limit == 0) return -1;
				int best = term.limit + 1;
				for (String candidate : text.words) {
					best = Math.min(best, prefixDistance(term.word, candidate, term.limit));
					if (best == 1) break;
				}
				if (best > term.limit) return -1;
				total += 20 + best * 10;
			}
			return total;
		}

		/** Restricted Damerau distance, also considering a word prefix for incomplete input.
		 * Only up to 66 candidate characters participate; three rows are reused across entries. */
		private int prefixDistance(String word, String candidate, int limit) {
			int columns = Math.min(candidate.length(), word.length() + limit);
			if (columns < word.length() - limit) return limit + 1;
			int[] previousPrevious = rows[0], previous = rows[1], current = rows[2];
			for (int j = 0; j <= columns; j++) previous[j] = j;
			for (int i = 1; i <= word.length(); i++) {
				current[0] = i;
				for (int j = 1; j <= columns; j++) {
					int cost = word.charAt(i - 1) == candidate.charAt(j - 1) ? 0 : 1;
					int distance = Math.min(Math.min(current[j - 1] + 1, previous[j] + 1),
							previous[j - 1] + cost);
					if (i > 1 && j > 1 && word.charAt(i - 1) == candidate.charAt(j - 2)
							&& word.charAt(i - 2) == candidate.charAt(j - 1)) {
						distance = Math.min(distance, previousPrevious[j - 2] + 1);
					}
					current[j] = distance;
				}
				int[] reuse = previousPrevious;
				previousPrevious = previous; previous = current; current = reuse;
			}
			int best = limit + 1;
			for (int j = Math.max(1, word.length() - limit); j <= columns; j++) {
				best = Math.min(best, previous[j]);
			}
			return best;
		}
	}
}
