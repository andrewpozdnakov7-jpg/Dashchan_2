package com.mishiranu.dashchan.chan.aoba;

import android.util.Pair;
import chan.content.ChanMarkup;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class AobaChanMarkup extends ChanMarkup {
	private static final Pattern THREAD_LINK = Pattern.compile("(?:^|/)res/([1-9][0-9]*)\\.html(?:#(?:q|i)?([1-9][0-9]*))?$");

	public AobaChanMarkup() {
		addTag("strong", TAG_BOLD);
		addTag("em", TAG_ITALIC);
		addTag("u", TAG_UNDERLINE);
		addTag("del", TAG_STRIKE);
		addTag("span", "spoiler", TAG_SPOILER);
		addTag("span", "quote", TAG_QUOTE);
		addTag("code", TAG_CODE);
		addPreformatted("pre", true);
	}

	@Override
	public Pair<String, String> obtainPostLinkThreadPostNumbers(String uriString) {
		Matcher matcher = THREAD_LINK.matcher(uriString);
		return matcher.find() ? new Pair<>(matcher.group(1), matcher.group(2)) : null;
	}
}
