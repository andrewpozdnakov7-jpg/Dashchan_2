package com.mishiranu.dashchan.ui.preference;

import static org.junit.Assert.*;
import org.junit.Test;

public class SettingsSearchMatcherTest {
	private int score(String query, String text) {
		return new SettingsSearchMatcher.Query(query).score(new SettingsSearchMatcher.Text(text));
	}
	@Test public void preservesSubstringAllWordsCaseAndDiacritics() {
		assertEquals(0, score("  ускор  АППАРАТ  ", "Использовать аппаратное ускорение"));
		assertEquals(0, score("елки cafe", "Ёлки CAFÉ"));
		assertEquals(-1, score("ускорение прокси", "Использовать аппаратное ускорение"));
		assertTrue(new SettingsSearchMatcher.Query(" \t ").isEmpty());
		assertTrue(new SettingsSearchMatcher.Query(null).isEmpty());
	}
	@Test public void supportsSubstitutionInsertionDeletionAndAdjacentSwap() {
		assertTrue(score("предзагруска", "Предзагрузка фото и видео") > 0);
		assertTrue(score("галлерея", "Галерея") > 0);
		assertTrue(score("галеря", "Галерея") > 0);
		assertTrue(score("пркоси", "Прокси") > 0);
		assertTrue(score("галар", "Прокручивать галерею к текущему файлу") > 0);
	}
	@Test public void twoEditsAreAllowedOnlyForLongWords() {
		assertTrue(score("preloxdimg", "Preloading") > 0);
		assertEquals(-1, score("pxeloxd", "Preload"));
		assertEquals(-1, score("кот", "Кит"));
		assertEquals(-1, score("я", "Настройки"));
	}
	@Test public void exactAliasAndTypoScoresHaveCorrectPriority() {
		assertEquals(0, score("галерея", "Галерея"));
		int alias = score("пип", "Воспроизведение «картинка в картинке»");
		int typo = score("галарея", "Галерея");
		assertTrue(alias > 0); assertTrue(typo > alias);
		assertTrue(score("wifi", "Только Wi-Fi") >= 0);
		assertTrue(score("тик ток", "Режим TikTok") >= 0);
		assertTrue(score("ночная тема", "Dark theme") >= 0);
	}
	@Test public void scratchRowsAreReusableWithoutChangingResults() {
		SettingsSearchMatcher.Query query = new SettingsSearchMatcher.Query("предзагруска");
		SettingsSearchMatcher.Text match = new SettingsSearchMatcher.Text("Предзагрузка видео");
		int expected = query.score(match);
		for (int i = 0; i < 30; i++) {
			assertEquals(-1, query.score(new SettingsSearchMatcher.Text("Интерфейс шрифты звук галерея")));
			assertEquals(expected, query.score(match));
		}
	}
	@Test public void oversizedAndManyWordQueriesDoNotEnableUnboundedFuzzyWork() {
		String longWord = String.join("", java.util.Collections.nCopies(65, "а"));
		assertEquals(0, score(longWord, longWord));
		assertEquals(-1, score(longWord, longWord.substring(1) + "б"));
		String words = String.join(" ", java.util.Collections.nCopies(13, "галарея"));
		assertEquals(-1, score(words, "Галерея"));
	}
}
