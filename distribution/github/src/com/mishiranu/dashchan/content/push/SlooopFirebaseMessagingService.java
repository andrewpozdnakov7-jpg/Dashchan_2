package com.mishiranu.dashchan.content.push;

import androidx.annotation.NonNull;
import com.google.firebase.messaging.FirebaseMessagingService;
import com.google.firebase.messaging.RemoteMessage;
import com.mishiranu.dashchan.content.Preferences;

public class SlooopFirebaseMessagingService extends FirebaseMessagingService {
	@Override
	public void onRegistered(@NonNull String installationId) {
		if (Preferences.isTrackMyPostsEnabled() && Preferences.isReplyPushEnabled()) {
			ReplyPushPrivateStore.setFirebaseRegistrationId(this, installationId);
			ReplyPushSyncWorker.enqueueSync(this);
		} else {
			ReplyPushPrivateStore.setFirebaseRegistrationId(this, null);
		}
	}

	@Override
	public void onUnregistered(@NonNull String installationId) {
		ReplyPushPrivateStore.setFirebaseRegistrationId(this, null);
	}

	@Override
	public void onMessageReceived(@NonNull RemoteMessage remoteMessage) {
		ReplyPushManager.handleData(this, remoteMessage.getData());
	}
}
