package com.mishiranu.dashchan.chan.zchan;

import android.util.Pair;
import chan.content.ChanMarkup;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class ZchanChanMarkup extends ChanMarkup {
	private static final Pattern THREAD_LINK = Pattern.compile(
			"(?:^|/)[A-Za-z0-9_-]+/(\\d+)(?:#(\\d+))?$");

	public ZchanChanMarkup() {
		addTag("strong", TAG_BOLD);
		addTag("b", TAG_BOLD);
		addTag("em", TAG_ITALIC);
		addTag("i", TAG_ITALIC);
		addTag("span", "spoiler", TAG_SPOILER);
		addTag("span", "quote", TAG_QUOTE);
		addColorable("span");
	}

	@Override
	public Pair<String, String> obtainPostLinkThreadPostNumbers(String uriString) {
		Matcher matcher = THREAD_LINK.matcher(uriString);
		return matcher.find() ? new Pair<>(matcher.group(1), matcher.group(2)) : null;
	}
}
