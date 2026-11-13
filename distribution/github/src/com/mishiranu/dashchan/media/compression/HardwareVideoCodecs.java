package com.mishiranu.dashchan.media.compression;

import android.media.MediaCodecInfo;
import android.media.MediaCodecList;
import java.util.ArrayList;
import java.util.List;

/** Hardware video codecs only. Audio is independent and may use the platform AAC software codec. */
public final class HardwareVideoCodecs {
	public static final String AVC = "video/avc", HEVC = "video/hevc";
	private HardwareVideoCodecs() {}
	public static List<MediaCodecInfo> find(String mime, boolean encoder) {
		List<MediaCodecInfo> result = new ArrayList<>();
		try {
			for (MediaCodecInfo info : new MediaCodecList(MediaCodecList.REGULAR_CODECS).getCodecInfos()) {
				if (info.isAlias() || info.isEncoder() != encoder || !info.isHardwareAccelerated() || info.isSoftwareOnly()) continue;
				for (String type : info.getSupportedTypes()) if (mime.equalsIgnoreCase(type)) { result.add(info); break; }
			}
		} catch (RuntimeException e) { /* Unavailable codec service is not a positive result. */ }
		return result;
	}
	public static boolean available(String mime) { return !find(mime, true).isEmpty(); }
}
