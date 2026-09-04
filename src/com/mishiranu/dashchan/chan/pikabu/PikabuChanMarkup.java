package com.mishiranu.dashchan.chan.pikabu;

import android.net.Uri;
import android.util.Pair;
import chan.content.ChanMarkup;
import chan.text.CommentEditor;
import chan.util.StringUtils;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class PikabuChanMarkup extends ChanMarkup {
	private static final int SUPPORTED_TAGS = TAG_BOLD | TAG_ITALIC | TAG_STRIKE | TAG_QUOTE;
	private static final Pattern THREAD_PATH = Pattern.compile("/story/(?:[^/?#]*_)?(\\d+)/?");
	private static final Pattern COMMENT_FRAGMENT = Pattern.compile("comment_(\\d+)");

	public PikabuChanMarkup() {
		addTag("b", TAG_BOLD);
		addTag("strong", TAG_BOLD);
		addTag("i", TAG_ITALIC);
		addTag("em", TAG_ITALIC);
		addTag("u", TAG_UNDERLINE);
		addTag("ins", TAG_UNDERLINE);
		addTag("s", TAG_STRIKE);
		addTag("del", TAG_STRIKE);
		addTag("strike", TAG_STRIKE);
		addTag("blockquote", TAG_QUOTE);
		addTag("code", TAG_CODE);
		addTag("pre", TAG_CODE);
		addTag("span", "spoiler", TAG_SPOILER);
	}

	@Override
	public CommentEditor obtainCommentEditor(String boardName) {
		CommentEditor editor = new CommentEditor();
		editor.addTag(TAG_BOLD, "[b]", "[/b]", CommentEditor.FLAG_ONE_LINE);
		editor.addTag(TAG_ITALIC, "[i]", "[/i]", CommentEditor.FLAG_ONE_LINE);
		editor.addTag(TAG_STRIKE, "[s]", "[/s]", CommentEditor.FLAG_ONE_LINE);
		return editor;
	}

	@Override
	public boolean isTagSupported(String boardName, int tag) {
		return (SUPPORTED_TAGS & tag) == tag;
	}

	@Override
	public Pair<String, String> obtainPostLinkThreadPostNumbers(String uriString) {
		Uri uri = Uri.parse(uriString);
		Matcher matcher = THREAD_PATH.matcher(StringUtils.emptyIfNull(uri.getPath()));
		if (!matcher.matches()) return null;
		String postNumber = uri.isHierarchical() ? uri.getQueryParameter("cid") : null;
		if (StringUtils.isEmpty(postNumber)) {
			Matcher fragmentMatcher = COMMENT_FRAGMENT.matcher(StringUtils.emptyIfNull(uri.getFragment()));
			if (fragmentMatcher.matches()) postNumber = fragmentMatcher.group(1);
		}
		return new Pair<>(matcher.group(1), postNumber);
	}
}
