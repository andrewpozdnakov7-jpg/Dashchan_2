package com.mishiranu.dashchan.content.push;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import com.mishiranu.dashchan.content.model.PostNumber;

public class ReplyPushMockReceiver extends BroadcastReceiver {
	@Override
	public void onReceive(Context context, Intent intent) {
		if (!"io.dashchan2.DEBUG_MOCK_REPLY".equals(intent.getAction())) {
			return;
		}
		String boardName = intent.getStringExtra("board");
		String threadNumber = intent.getStringExtra("thread_id");
		PostNumber watchedPostNumber = PostNumber.parseNullable(
				intent.getStringExtra("watched_post_id"));
		PostNumber replyPostNumber = PostNumber.parseNullable(intent.getStringExtra("reply_post_id"));
		if (watchedPostNumber != null && replyPostNumber != null) {
			ReplyPushManager.handleMockReply(context, boardName, threadNumber,
					watchedPostNumber, replyPostNumber, intent.getBooleanExtra("repeat", false));
		}
	}
}
