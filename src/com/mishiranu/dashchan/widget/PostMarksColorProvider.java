package com.mishiranu.dashchan.widget;

import android.content.Context;
import com.mishiranu.dashchan.R;
import com.mishiranu.dashchan.content.Preferences;
import com.mishiranu.dashchan.util.ResourceUtils;

public final class PostMarksColorProvider {
	public static final class Colors {
		public final int userPost;
		public final int reply;

		private Colors(int userPost, int reply) {
			this.userPost = userPost;
			this.reply = reply;
		}
	}

	private PostMarksColorProvider() {}

	public static Colors obtain(Context context, boolean forScrollBar) {
		switch (Preferences.getPostMarksColorMode()) {
			case THEME: {
				ThemeEngine.Theme theme = ThemeEngine.getTheme(context);
				return new Colors(theme.quote, theme.link);
			}
			case CUSTOM:
				return new Colors(Preferences.getUserPostMarkColor(), Preferences.getReplyPostMarkColor());
			default:
				if (forScrollBar) {
					return new Colors(ResourceUtils.getColor(context, R.attr.colorPostMarkUserPost),
							ResourceUtils.getColor(context, R.attr.colorPostMarkReply));
				}
				int link = ThemeEngine.getTheme(context).link;
				return new Colors(link, link);
		}
	}
}
