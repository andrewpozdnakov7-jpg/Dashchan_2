package com.mishiranu.dashchan.chan.onechan;

import android.util.Pair;
import chan.content.ChanMarkup;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class OnechanChanMarkup extends ChanMarkup {
	private static final Pattern THREAD_LINK = Pattern.compile("^/news/res/([1-9][0-9]*)/?(?:#([1-9][0-9]*))?$");

	public OnechanChanMarkup() {
		addTag("strong", TAG_BOLD);
		addTag("em", TAG_ITALIC);
		addTag("del", TAG_STRIKE);
		addTag("span", "spoiler", TAG_SPOILER);
		addTag("code", TAG_CODE);
		addPreformatted("pre", true);
	}

	@Override
	public Pair<String, String> obtainPostLinkThreadPostNumbers(String uriString) {
		Matcher matcher = THREAD_LINK.matcher(uriString);
		return matcher.matches() ? new Pair<>(matcher.group(1), matcher.group(2)) : null;
	}
}
