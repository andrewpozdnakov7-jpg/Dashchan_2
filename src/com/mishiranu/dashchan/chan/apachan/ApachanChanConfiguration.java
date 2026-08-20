package com.mishiranu.dashchan.chan.apachan;

import android.util.Pair;
import chan.content.ChanConfiguration;

public class ApachanChanConfiguration extends ChanConfiguration {
	public ApachanChanConfiguration() {
		request(OPTION_READ_POSTS_COUNT);
		setDefaultName("Аноним");
	}

	@Override
	public Board obtainBoardConfiguration(String boardName) {
		Board board = new Board();
		board.allowPosting = true;
		return board;
	}

	@Override
	public Posting obtainPostingConfiguration(String boardName, boolean newThread) {
		Posting posting = new Posting();
		posting.allowSubject = true;
		posting.attachmentCount = 1;
		posting.attachmentMimeTypes.add("image/*");
		posting.userIcons.add(new Pair<>("ALL", "Случайная картинка"));
		String[] userImages = {"Альфабет", "Петросян (Apachan.net)", "Петросян", "Анонимус",
				"ПеКа-фейс", "Мразиш", "Фейспалм", "Ракодил (New)", "Ракодил (Apachan.net)", "Илита",
				"Базированный гигачэд", "Блеать", "Два чая", "Cool story", "Котики-няшки",
				"Боевые картинки", "Биопроблемы", "Зеленый слоник", "Беспредел", "Слоупок", "Варг",
				"Ебать дебил", "Пиздолис", "Колёк", "Wojak", "Ватник", "Либератник", "Пепе",
				"Пичаль", "Шар", "Страшнотян", "Pepe.gif", "оМЕГАКИНО", "Райан Гослинг",
				"Мадс Миккельсен", "Хоумлендер"};
		for (int i = 0; i < userImages.length; i++) {
			posting.userIcons.add(new Pair<>(Integer.toString(i), userImages[i]));
		}
		return posting;
	}
}
