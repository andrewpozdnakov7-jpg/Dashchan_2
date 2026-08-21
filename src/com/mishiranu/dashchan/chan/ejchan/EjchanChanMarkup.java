package com.mishiranu.dashchan.chan.ejchan;

import android.util.Pair;
import chan.content.ChanMarkup;
import chan.text.CommentEditor;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class EjchanChanMarkup extends ChanMarkup {
	private static final int SUPPORTED_TAGS = TAG_BOLD | TAG_ITALIC | TAG_UNDERLINE | TAG_STRIKE | TAG_SPOILER
			| TAG_QUOTE | TAG_CODE | TAG_HEADING;
	private static final Pattern THREAD_LINK = Pattern.compile("(?:res/)?(\\d+)\\.html(?:#(?:q)?(\\d+))?$");

	public EjchanChanMarkup() {
		addTag("strong", TAG_BOLD);
		addTag("em", TAG_ITALIC);
		addTag("u", TAG_UNDERLINE);
		addTag("ins", TAG_UNDERLINE);
		addTag("s", TAG_STRIKE);
		addTag("del", TAG_STRIKE);
		addTag("span", "spoiler", TAG_SPOILER);
		addTag("span", "quote", TAG_QUOTE);
		addTag("code", TAG_CODE);
		addPreformatted("pre");
		addTag("span", "heading", TAG_HEADING);
		addColorable("span");
	}

	@Override
	public CommentEditor obtainCommentEditor(String boardName) {
		CommentEditor editor = new CommentEditor();
		editor.addTag(TAG_BOLD, "[b]", "[/b]");
		editor.addTag(TAG_ITALIC, "[i]", "[/i]");
		editor.addTag(TAG_UNDERLINE, "__", "__");
		editor.addTag(TAG_STRIKE, "~~", "~~");
		editor.addTag(TAG_SPOILER, "**", "**");
		editor.addTag(TAG_CODE, "```\n", "\n```");
		editor.addTag(TAG_HEADING, "==", "==", CommentEditor.FLAG_ONE_LINE);
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
