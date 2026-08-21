package com.mishiranu.dashchan.chan.apachan;

import android.net.Uri;
import android.util.Pair;
import chan.content.ChanMarkup;
import chan.text.CommentEditor;

public class ApachanChanMarkup extends ChanMarkup {
	private static final int SUPPORTED_TAGS = TAG_BOLD | TAG_ITALIC | TAG_UNDERLINE | TAG_STRIKE | TAG_SPOILER
			| TAG_QUOTE;

	public ApachanChanMarkup() {
		addTag("b", TAG_BOLD);
		addTag("strong", TAG_BOLD);
		addTag("i", TAG_ITALIC);
		addTag("em", TAG_ITALIC);
		addTag("u", TAG_UNDERLINE);
		addTag("ins", TAG_UNDERLINE);
		addTag("s", TAG_STRIKE);
		addTag("del", TAG_STRIKE);
		addTag("span", "spoiler", TAG_SPOILER);
		addTag("span", "quote", TAG_QUOTE);
	}

	@Override
	public CommentEditor obtainCommentEditor(String boardName) {
		CommentEditor editor = new CommentEditor.BulletinBoardCodeCommentEditor();
		editor.addTag(TAG_QUOTE, "[quote]", "[/quote]");
		return editor;
	}

	@Override
	public boolean isTagSupported(String boardName, int tag) {
		return (SUPPORTED_TAGS & tag) == tag;
	}

	@Override
	public Pair<String, String> obtainPostLinkThreadPostNumbers(String uriString) {
		Uri uri = Uri.parse(uriString);
		String fragment = uri.getFragment();
		if (fragment == null || !fragment.startsWith("c") || fragment.length() == 1) return null;
		return new Pair<>(uri.getQueryParameter("id"), fragment.substring(1));
	}
}
