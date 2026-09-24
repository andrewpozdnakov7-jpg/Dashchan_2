package com.mishiranu.dashchan.chan.kohlchan;

import android.util.Pair;
import chan.content.ChanConfiguration;
import chan.content.ChanMarkup;
import chan.text.CommentEditor;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class KohlchanChanMarkup extends ChanMarkup {
	private static final int SUPPORTED_TAGS = TAG_BOLD | TAG_ITALIC | TAG_UNDERLINE | TAG_STRIKE | TAG_SPOILER
			| TAG_QUOTE | TAG_CODE;
	private static final Pattern THREAD_LINK = Pattern.compile("(?:^|/)([0-9]+)\\.html(?:#([0-9]+))?$");

	public KohlchanChanMarkup() {
		addTag("strong", TAG_BOLD);
		addTag("b", TAG_BOLD);
		addTag("em", TAG_ITALIC);
		addTag("i", TAG_ITALIC);
		addTag("u", TAG_UNDERLINE);
		addTag("s", TAG_STRIKE);
		addTag("pre", TAG_CODE);
		addTag("code", TAG_CODE);
		addTag("span", "greenText", TAG_QUOTE);
		addTag("span", "spoiler", TAG_SPOILER);
		addTag("span", "redText", TAG_HEADING);
		addTag("span", "aa", TAG_ASCII_ART);
		addBlock("span", "aa", true, false);
	}

	@Override
	public CommentEditor obtainCommentEditor(String boardName) {
		CommentEditor editor = new CommentEditor();
		editor.addTag(TAG_BOLD, "[b]", "[/b]");
		editor.addTag(TAG_ITALIC, "[i]", "[/i]");
		editor.addTag(TAG_UNDERLINE, "[u]", "[/u]");
		editor.addTag(TAG_STRIKE, "[s]", "[/s]");
		editor.addTag(TAG_SPOILER, "[spoiler]", "[/spoiler]");
		editor.addTag(TAG_CODE, "[code]", "[/code]");
		return editor;
	}

	@Override
	public boolean isTagSupported(String boardName, int tag) {
		if (tag == TAG_CODE) {
			KohlchanChanConfiguration configuration = ChanConfiguration.get(this);
			return configuration.isCodeEnabled(boardName);
		}
		return (SUPPORTED_TAGS & tag) == tag;
	}

	@Override
	public Pair<String, String> obtainPostLinkThreadPostNumbers(String uriString) {
		Matcher matcher = THREAD_LINK.matcher(uriString);
		return matcher.find() ? new Pair<>(matcher.group(1), matcher.group(2)) : null;
	}
}
