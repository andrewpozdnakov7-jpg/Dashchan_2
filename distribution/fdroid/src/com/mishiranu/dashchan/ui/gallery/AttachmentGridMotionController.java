package com.mishiranu.dashchan.ui.gallery;

import android.view.View;
import android.view.Window;

/** Keep the legacy attachment dialog presentation. */
public final class AttachmentGridMotionController {
	public AttachmentGridMotionController(View content, Window window, View source) {}
	public void open() {}
	public boolean close(Runnable cancel) { return false; }
	public boolean isRunning() { return false; }
	public void finish() {}
	public void dispose() {}
}
