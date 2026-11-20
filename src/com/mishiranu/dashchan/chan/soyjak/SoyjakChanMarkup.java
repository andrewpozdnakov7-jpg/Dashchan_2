package com.mishiranu.dashchan.chan.soyjak;

import android.net.Uri;
import android.util.Pair;
import chan.content.ChanMarkup;

public class SoyjakChanMarkup extends ChanMarkup {
	public SoyjakChanMarkup() {
		addTag("span", "quote", TAG_QUOTE);
		addTag("span", "quote2", TAG_QUOTE);
		addTag("span", "spoiler", TAG_SPOILER);
		addTag("s", TAG_SPOILER);
		addTag("pre", TAG_CODE);
	}

	@Override
	public Pair<String, String> obtainPostLinkThreadPostNumbers(String uriString) {
		Uri uri = Uri.parse(uriString);
		SoyjakChanLocator locator = SoyjakChanLocator.get(this);
		if (locator.isThreadUri(uri)) {
			return new Pair<>(locator.getThreadNumber(uri), locator.getPostNumber(uri));
		}
		if (uriString.startsWith("#")) {
			String postNumber = locator.getPostNumber(uri);
			if (postNumber != null) {
				return new Pair<>(null, postNumber);
			}
		}
		return null;
	}
}
