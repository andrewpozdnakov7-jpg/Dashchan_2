package com.mishiranu.dashchan.content;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import chan.util.DataFile;

public class OpenFileActivity extends Activity {
	private static final String EXTRA_FILE_TARGET = "fileTarget";
	private static final String EXTRA_FILE_PATH = "filePath";
	private static final String EXTRA_ALLOW_WRITE = "allowWrite";

	public static Intent createIntent(Context context, DataFile file, boolean allowWrite) {
		return new Intent(context, OpenFileActivity.class)
				.putExtra(EXTRA_FILE_TARGET, file.getTarget().name())
				.putExtra(EXTRA_FILE_PATH, file.getRelativePath())
				.putExtra(EXTRA_ALLOW_WRITE, allowWrite);
	}

	@Override
	protected void onCreate(Bundle savedInstanceState) {
		super.onCreate(savedInstanceState);
		Intent intent = getIntent();
		String target = intent != null ? intent.getStringExtra(EXTRA_FILE_TARGET) : null;
		String path = intent != null ? intent.getStringExtra(EXTRA_FILE_PATH) : null;
		DataFile file = null;
		if (target != null && path != null) {
			try {
				file = DataFile.obtain(DataFile.Target.valueOf(target), path);
			} catch (IllegalArgumentException ignored) {}
		}
		if (file != null) {
			DownloadedFileOpener.open(this, file,
					intent.getBooleanExtra(EXTRA_ALLOW_WRITE, false), this::finish);
		} else {
			finish();
		}
	}
}
