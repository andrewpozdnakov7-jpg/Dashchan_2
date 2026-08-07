package com.mishiranu.dashchan.media;

import android.media.MediaCodecInfo;
import android.media.MediaCodecList;
import android.media.MediaFormat;

final class VideoDecoderCapabilities {
	private static final int FFMPEG_PROFILE_UNKNOWN = -99;
	private static final int FFMPEG_LEVEL_UNKNOWN = -99;
	private static final int FFMPEG_COLOR_TRC_SMPTE_2084 = 16;
	private static final int PROFILE_UNSPECIFIED = -1;
	private static final int PROFILE_UNSUPPORTED = -2;

	private VideoDecoderCapabilities() {}

	public static String findHardwareDecoder(String mimeType, int width, int height, float frameRate,
			int codecProfile, int codecLevel, int bitDepth, int colorTransfer) {
		if (!isSupportedMimeType(mimeType) || width <= 0 || height <= 0) {
			return null;
		}
		int androidProfile = getAndroidProfile(mimeType, codecProfile, bitDepth,
				colorTransfer == FFMPEG_COLOR_TRC_SMPTE_2084);
		if (androidProfile == PROFILE_UNSUPPORTED) {
			return null;
		}
		int androidLevel = getAndroidLevel(mimeType, codecLevel);
		MediaFormat format = MediaFormat.createVideoFormat(mimeType, width, height);
		if (frameRate > 0f && Float.isFinite(frameRate)) {
			format.setFloat(MediaFormat.KEY_FRAME_RATE, frameRate);
		}
		if (androidProfile != PROFILE_UNSPECIFIED) {
			format.setInteger(MediaFormat.KEY_PROFILE, androidProfile);
			if (androidLevel != 0) {
				format.setInteger(MediaFormat.KEY_LEVEL, androidLevel);
			}
		}
		try {
			for (MediaCodecInfo codecInfo :
					new MediaCodecList(MediaCodecList.REGULAR_CODECS).getCodecInfos()) {
				if (codecInfo.isEncoder() || !codecInfo.isHardwareAccelerated()) {
					continue;
				}
				for (String supportedType : codecInfo.getSupportedTypes()) {
					if (!mimeType.equalsIgnoreCase(supportedType)) {
						continue;
					}
					try {
						MediaCodecInfo.CodecCapabilities capabilities =
								codecInfo.getCapabilitiesForType(supportedType);
						MediaCodecInfo.VideoCapabilities videoCapabilities =
								capabilities.getVideoCapabilities();
						boolean sizeAndRateSupported = frameRate > 0f && Float.isFinite(frameRate)
								? videoCapabilities.areSizeAndRateSupported(width, height, frameRate)
								: videoCapabilities.isSizeSupported(width, height);
						if (sizeAndRateSupported && capabilities.isFormatSupported(format)) {
							return codecInfo.getName();
						}
					} catch (RuntimeException ignored) {
						// A broken vendor capability entry must not prevent software fallback.
					}
				}
			}
		} catch (RuntimeException ignored) {
			// MediaCodecList can be incomplete on vendor-modified Android builds.
		}
		return null;
	}

	public static boolean isSupportedMimeType(String mimeType) {
		return MediaFormat.MIMETYPE_VIDEO_AVC.equalsIgnoreCase(mimeType)
				|| MediaFormat.MIMETYPE_VIDEO_HEVC.equalsIgnoreCase(mimeType)
				|| MediaFormat.MIMETYPE_VIDEO_VP8.equalsIgnoreCase(mimeType)
				|| MediaFormat.MIMETYPE_VIDEO_VP9.equalsIgnoreCase(mimeType)
				|| MediaFormat.MIMETYPE_VIDEO_AV1.equalsIgnoreCase(mimeType);
	}

	private static int getAndroidProfile(String mimeType, int codecProfile, int bitDepth, boolean hdr10) {
		if (MediaFormat.MIMETYPE_VIDEO_VP9.equalsIgnoreCase(mimeType)) {
			switch (codecProfile) {
				case 0: return bitDepth <= 0 || bitDepth <= 8
						? MediaCodecInfo.CodecProfileLevel.VP9Profile0 : PROFILE_UNSUPPORTED;
				case 1: return bitDepth <= 0 || bitDepth <= 8
						? MediaCodecInfo.CodecProfileLevel.VP9Profile1 : PROFILE_UNSUPPORTED;
				case 2:
					if (bitDepth > 10) return PROFILE_UNSUPPORTED;
					return hdr10 ? MediaCodecInfo.CodecProfileLevel.VP9Profile2HDR
							: MediaCodecInfo.CodecProfileLevel.VP9Profile2;
				case 3:
					if (bitDepth > 10) return PROFILE_UNSUPPORTED;
					return hdr10 ? MediaCodecInfo.CodecProfileLevel.VP9Profile3HDR
							: MediaCodecInfo.CodecProfileLevel.VP9Profile3;
				case FFMPEG_PROFILE_UNKNOWN: return PROFILE_UNSPECIFIED;
				default: return PROFILE_UNSUPPORTED;
			}
		}
		if (MediaFormat.MIMETYPE_VIDEO_AV1.equalsIgnoreCase(mimeType)) {
			if (codecProfile == FFMPEG_PROFILE_UNKNOWN) {
				return PROFILE_UNSPECIFIED;
			}
			if (codecProfile != 0 || bitDepth > 10) {
				return PROFILE_UNSUPPORTED;
			}
			if (bitDepth <= 0) {
				return PROFILE_UNSPECIFIED;
			}
			if (bitDepth <= 8) {
				return MediaCodecInfo.CodecProfileLevel.AV1ProfileMain8;
			}
			return hdr10 ? MediaCodecInfo.CodecProfileLevel.AV1ProfileMain10HDR10
					: MediaCodecInfo.CodecProfileLevel.AV1ProfileMain10;
		}
		return PROFILE_UNSPECIFIED;
	}

	private static int getAndroidLevel(String mimeType, int codecLevel) {
		if (codecLevel == FFMPEG_LEVEL_UNKNOWN) {
			return 0;
		}
		if (MediaFormat.MIMETYPE_VIDEO_VP9.equalsIgnoreCase(mimeType)) {
			switch (codecLevel) {
				case 10: return MediaCodecInfo.CodecProfileLevel.VP9Level1;
				case 11: return MediaCodecInfo.CodecProfileLevel.VP9Level11;
				case 20: return MediaCodecInfo.CodecProfileLevel.VP9Level2;
				case 21: return MediaCodecInfo.CodecProfileLevel.VP9Level21;
				case 30: return MediaCodecInfo.CodecProfileLevel.VP9Level3;
				case 31: return MediaCodecInfo.CodecProfileLevel.VP9Level31;
				case 40: return MediaCodecInfo.CodecProfileLevel.VP9Level4;
				case 41: return MediaCodecInfo.CodecProfileLevel.VP9Level41;
				case 50: return MediaCodecInfo.CodecProfileLevel.VP9Level5;
				case 51: return MediaCodecInfo.CodecProfileLevel.VP9Level51;
				case 52: return MediaCodecInfo.CodecProfileLevel.VP9Level52;
				case 60: return MediaCodecInfo.CodecProfileLevel.VP9Level6;
				case 61: return MediaCodecInfo.CodecProfileLevel.VP9Level61;
				case 62: return MediaCodecInfo.CodecProfileLevel.VP9Level62;
				default: return 0;
			}
		}
		if (MediaFormat.MIMETYPE_VIDEO_AV1.equalsIgnoreCase(mimeType)) {
			int[] levels = {
					MediaCodecInfo.CodecProfileLevel.AV1Level2,
					MediaCodecInfo.CodecProfileLevel.AV1Level21,
					MediaCodecInfo.CodecProfileLevel.AV1Level22,
					MediaCodecInfo.CodecProfileLevel.AV1Level23,
					MediaCodecInfo.CodecProfileLevel.AV1Level3,
					MediaCodecInfo.CodecProfileLevel.AV1Level31,
					MediaCodecInfo.CodecProfileLevel.AV1Level32,
					MediaCodecInfo.CodecProfileLevel.AV1Level33,
					MediaCodecInfo.CodecProfileLevel.AV1Level4,
					MediaCodecInfo.CodecProfileLevel.AV1Level41,
					MediaCodecInfo.CodecProfileLevel.AV1Level42,
					MediaCodecInfo.CodecProfileLevel.AV1Level43,
					MediaCodecInfo.CodecProfileLevel.AV1Level5,
					MediaCodecInfo.CodecProfileLevel.AV1Level51,
					MediaCodecInfo.CodecProfileLevel.AV1Level52,
					MediaCodecInfo.CodecProfileLevel.AV1Level53,
					MediaCodecInfo.CodecProfileLevel.AV1Level6,
					MediaCodecInfo.CodecProfileLevel.AV1Level61,
					MediaCodecInfo.CodecProfileLevel.AV1Level62,
					MediaCodecInfo.CodecProfileLevel.AV1Level63,
					MediaCodecInfo.CodecProfileLevel.AV1Level7,
					MediaCodecInfo.CodecProfileLevel.AV1Level71,
					MediaCodecInfo.CodecProfileLevel.AV1Level72,
					MediaCodecInfo.CodecProfileLevel.AV1Level73
			};
			return codecLevel >= 0 && codecLevel < levels.length ? levels[codecLevel] : 0;
		}
		return 0;
	}
}
