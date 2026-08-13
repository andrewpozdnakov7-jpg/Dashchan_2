package com.mishiranu.dashchan.chan.ejchan;

import android.util.Pair;
import chan.content.ChanMarkup;
import chan.text.CommentEditor;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class EjchanChanMarkup extends ChanMarkup {
	private static final int SUPPORTED_TAGS = TAG_BOLD | TAG_ITALIC | TAG_SPOILER | TAG_QUOTE;
	private static final Pattern THREAD_LINK = Pattern.compile("(?:res/)?(\\d+)\\.html(?:#(?:q)?(\\d+))?$");

	public EjchanChanMarkup() {
		addTag("strong", TAG_BOLD);
		addTag("em", TAG_ITALIC);
		addTag("span", "spoiler", TAG_SPOILER);
		addTag("span", "quote", TAG_QUOTE);
		addColorable("span");
	}

	@Override
	public CommentEditor obtainCommentEditor(String boardName) {
		CommentEditor editor = new CommentEditor();
		editor.addTag(TAG_SPOILER, "**", "**", CommentEditor.FLAG_ONE_LINE);
		return editor;
	}

	@Override
	public boolean isTagSupported(String boardName, int tag) {
		return (SUPPORTED_TAGS & tag) == tag;
	}

	@Override
	public Pair<String, String> obtainPostLinkThreadPostNumbers(String uriString) {
		Matcher matcher = THREAD_LINK.matcher(uriString);
		return matcher.find() ? new Pair<>(matcher.group(1), matcher.group(2)) : null;
	}
}
