package com.mishiranu.dashchan.content.translation;

import android.content.Context;
import com.mishiranu.dashchan.R;
import java.io.File;

public final class TranslationModel {
	private static final String BASE_URL = "https://storage.googleapis.com/" +
			"moz-fx-translations-data--303e-prod-translations-data/";

	public static final class FileSpec {
		public final String url;
		public final String compressedSha256;
		public final String uncompressedSha256;
		public final long compressedSize;
		public final long uncompressedSize;
		public final String outputName;

		private FileSpec(String path, String compressedSha256, String uncompressedSha256,
				long compressedSize, long uncompressedSize, String outputName) {
			this.url = BASE_URL + path;
			this.compressedSha256 = compressedSha256;
			this.uncompressedSha256 = uncompressedSha256;
			this.compressedSize = compressedSize;
			this.uncompressedSize = uncompressedSize;
			this.outputName = outputName;
		}
	}

	public enum Direction {
		EN_RU("en", "ru", "en-ru-base-memory-v1", 23_786_432L, 35_240_782L,
				"models/en-ru/retrain_base-memory_KJ23-iDVTcymG1ZldWY17w/exported/",
				new FileSpecData[] {
						new FileSpecData("lex.50.50.enru.s2t.bin.gz",
								"9c111984d8207f8dc6b9dd5c17ab4d18042f7c0e34105a09a35909538facc958",
								"4d91839726b960e70b6d05c53d0cffd16262832b1c0e1ea99d66f412dcc6a239",
								1_378_563L, 2_774_540L, "lex.bin"),
						new FileSpecData("model.enru.intgemm.alphas.bin.gz",
								"e766bc83434f4a9ff4110abe5450043a62cd402c6a7a8bca5e3e4d83b408dcb4",
								"184cb5cda528eeefc0f75f5d0035d787b71d74af135e3c5608d01ae02ecfb920",
								21_988_864L, 31_561_787L, "model.bin"),
						new FileSpecData("vocab.enru.spm.gz",
								"9aa498dce5d27c02ac998c646c51a7475a421500ecdbbd1dff9ff3e77e64b21c",
								"56ee63e14e8cb926c394242adc3ed7cc602644c3d33058cff2ce2959d52a6258",
								419_005L, 904_455L, "vocab.spm")
				}),
		RU_EN("ru", "en", "ru-en-tiny-v1", 14_995_467L, 22_530_152L,
				"models/ru-en/spring-2024_QrcdYgbwS7e7xbhtOSdoNQ/exported/",
				new FileSpecData[] {
						new FileSpecData("lex.50.50.ruen.s2t.bin.gz",
								"6524f5c898f1fef52992bd2565a6d4acfafb6a4e8dcd6aef237bd888239418a0",
								"f654693577505fd38b1f3d220cdd4ffffbb45afb900a60cf751f0724eadc74e0",
								1_962_008L, 4_483_844L, "lex.bin"),
						new FileSpecData("model.ruen.intgemm.alphas.bin.gz",
								"4a8a7b9b07c9e06a167ec5bf2542528817321516db4edf614fda45011fa8e5d1",
								"b1d85c13cfbb05e1d326dd6f0fb5ef270a2011b547450260f96567a93f446c94",
								12_613_599L, 17_141_051L, "model.bin"),
						new FileSpecData("vocab.ruen.spm.gz",
								"cd70b828e99e4d0c79d48cd56d8579d656c87c1db20bf88883da3085dcbfef75",
								"93bdc941b16e523695c319f74778bca9fd8b75a25ad75020cdc98aef74cdc0fc",
								419_860L, 905_257L, "vocab.spm")
				});

		public final String sourceLanguage;
		public final String targetLanguage;
		public final String id;
		public final long compressedSize;
		public final long uncompressedSize;
		public final FileSpec[] files;

		Direction(String sourceLanguage, String targetLanguage, String id,
				long compressedSize, long uncompressedSize, String prefix, FileSpecData[] files) {
			this.sourceLanguage = sourceLanguage;
			this.targetLanguage = targetLanguage;
			this.id = id;
			this.compressedSize = compressedSize;
			this.uncompressedSize = uncompressedSize;
			this.files = new FileSpec[files.length];
			for (int i = 0; i < files.length; i++) {
				FileSpecData file = files[i];
				this.files[i] = new FileSpec(prefix + file.name, file.compressedSha256,
						file.uncompressedSha256, file.compressedSize, file.uncompressedSize, file.outputName);
			}
		}

		public String getDisplayName(Context context) {
			return context.getString("ru".equals(sourceLanguage) ? R.string.translation_language_russian
					: R.string.translation_language_english) + " \u2192 " +
					context.getString("ru".equals(targetLanguage) ? R.string.translation_language_russian
							: R.string.translation_language_english);
		}
	}

	private static final class FileSpecData {
		private final String name;
		private final String compressedSha256;
		private final String uncompressedSha256;
		private final long compressedSize;
		private final long uncompressedSize;
		private final String outputName;

		private FileSpecData(String name, String compressedSha256, String uncompressedSha256,
				long compressedSize, long uncompressedSize, String outputName) {
			this.name = name;
			this.compressedSha256 = compressedSha256;
			this.uncompressedSha256 = uncompressedSha256;
			this.compressedSize = compressedSize;
			this.uncompressedSize = uncompressedSize;
			this.outputName = outputName;
		}
	}

	private TranslationModel() {}

	public static Direction forNativeLanguage(String language) {
		return "en".equals(language) ? Direction.RU_EN : Direction.EN_RU;
	}

	public static boolean isForeignChan(Direction direction, String chanName) {
		return direction == Direction.EN_RU ? "fourchan".equals(chanName) : "dvach".equals(chanName);
	}

	public static File getRootDirectory(Context context) {
		return new File(context.getFilesDir(), "translation/models");
	}

	public static File getModelDirectory(Context context, Direction direction) {
		return new File(getRootDirectory(context), direction.id);
	}

	public static boolean isInstalled(Context context, Direction direction) {
		File directory = getModelDirectory(context, direction);
		if (!new File(directory, "installed.marker").isFile()) {
			return false;
		}
		for (FileSpec file : direction.files) {
			File installed = new File(directory, file.outputName);
			if (!installed.isFile() || installed.length() != file.uncompressedSize) {
				return false;
			}
		}
		return true;
	}
}
