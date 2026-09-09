package com.mishiranu.dashchan.chan.d3ru;

import android.net.Uri;
import android.util.Pair;
import chan.content.ChanMarkup;
import chan.util.StringUtils;

public class D3ChanMarkup extends ChanMarkup {
	public D3ChanMarkup() {
		addTag("b", TAG_BOLD);
		addTag("strong", TAG_BOLD);
		addTag("i", TAG_ITALIC);
		addTag("em", TAG_ITALIC);
		addTag("u", TAG_UNDERLINE);
		addTag("s", TAG_STRIKE);
		addTag("del", TAG_STRIKE);
		addTag("strike", TAG_STRIKE);
		addTag("blockquote", TAG_QUOTE);
		addTag("code", TAG_CODE);
		addTag("pre", TAG_CODE);
	}

	@Override
	public Pair<String, String> obtainPostLinkThreadPostNumbers(String uriString) {
		Uri uri = Uri.parse(uriString);
		D3ChanLocator locator = D3ChanLocator.get(this);
		String threadNumber = locator.getThreadNumber(uri);
		return !StringUtils.isEmpty(threadNumber)
				? new Pair<>(threadNumber, locator.getPostNumber(uri)) : null;
	}
}
