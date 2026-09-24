package com.mishiranu.dashchan.content;

import com.mishiranu.dashchan.util.IOUtils;
import java.io.File;

final class ShareFileCopy {
	private ShareFileCopy() {}

	/** Destination is a new, private staging file, never the original user's file. */
	static boolean copy(File source, File destination) {
		if (IOUtils.copyInternalFile(source, destination)) return true;
		destination.delete();
		return false;
	}
}
