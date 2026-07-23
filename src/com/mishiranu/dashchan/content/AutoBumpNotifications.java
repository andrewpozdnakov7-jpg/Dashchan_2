package com.mishiranu.dashchan.content;

import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import androidx.core.app.NotificationCompat;
import com.mishiranu.dashchan.C;
import com.mishiranu.dashchan.R;
import com.mishiranu.dashchan.content.storage.AutoBumpStorage;
import com.mishiranu.dashchan.ui.MainActivity;

public class AutoBumpNotifications {
	public static void configure(Context context) {
		NotificationManager manager = (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);
		NotificationChannel channel = new NotificationChannel(C.NOTIFICATION_CHANNEL_AUTO_BUMP,
				context.getString(R.string.auto_bump), NotificationManager.IMPORTANCE_DEFAULT);
		manager.createNotificationChannel(channel);
	}

	public static void notifyPaused(Context context, AutoBumpStorage.Task task, String reason) {
		configure(context);
		String address = "/" + task.boardName + "/res/" + task.threadNumber;
		NotificationCompat.Builder builder = new NotificationCompat.Builder(context,
				C.NOTIFICATION_CHANNEL_AUTO_BUMP)
				.setSmallIcon(R.drawable.ic_notification)
				.setContentTitle(context.getString(R.string.auto_bump_paused))
				.setContentText(address + ": " + reason)
				.setStyle(new NotificationCompat.BigTextStyle().bigText(address + "\n" + reason))
				.setAutoCancel(true);
		Intent intent = new Intent(context, MainActivity.class)
				.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP)
				.putExtra(C.EXTRA_CHAN_NAME, task.chanName)
				.putExtra(C.EXTRA_BOARD_NAME, task.boardName)
				.putExtra(C.EXTRA_THREAD_NUMBER, task.threadNumber);
		builder.setContentIntent(PendingIntent.getActivity(context, task.id.hashCode(), intent,
				PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE));
		NotificationManager manager = (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);
		manager.notify(task.id, C.NOTIFICATION_ID_AUTO_BUMP, builder.build());
	}
}
