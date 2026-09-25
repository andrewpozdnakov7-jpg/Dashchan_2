package com.mishiranu.dashchan.content;

import android.content.res.Resources;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import com.mishiranu.dashchan.util.ResourceUtils;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InterruptedIOException;

/** Reads/decode limits apply only to previews. Streams are read once, including archive/network streams. */
public final class ThumbnailDecoder {
	public static final int MAX_INPUT_BYTES = 32 * 1024 * 1024;

	private ThumbnailDecoder() {}

	public static int targetSize(Resources resources) {
		// Preserve the existing 72dp short-side preview quality.
		return Math.max(1, Math.min(1024, (int) (72f * ResourceUtils.obtainDensity(resources))));
	}

	public static final class LimitedOutput extends ByteArrayOutputStream {
		private void check(int length) {
			if (length < 0 || length > MAX_INPUT_BYTES - count) {
				throw new IllegalArgumentException("Thumbnail input exceeds size limit");
			}
			if (Thread.currentThread().isInterrupted()) {
				throw new IllegalStateException("Thumbnail loading interrupted");
			}
		}

		@Override
		public synchronized void write(int value) {
			check(1);
			super.write(value);
		}

		@Override
		public synchronized void write(byte[] bytes, int offset, int length) {
			check(length);
			super.write(bytes, offset, length);
		}

		public Bitmap decode(int targetSize) {
			return ThumbnailDecoder.decode(buf, count, targetSize);
		}
	}

	public static Bitmap decode(InputStream input, int targetSize) throws IOException {
		LimitedOutput output = new LimitedOutput();
		byte[] buffer = new byte[16 * 1024];
		int count;
		while ((count = input.read(buffer)) >= 0) {
			if (Thread.currentThread().isInterrupted()) throw new InterruptedIOException();
			if (count == 0) {
				int value = input.read();
				if (value < 0) break;
				if (output.size() == MAX_INPUT_BYTES) throw new IOException("Thumbnail input exceeds size limit");
				output.write(value);
				continue;
			}
			if (count > MAX_INPUT_BYTES - output.size()) throw new IOException("Thumbnail input exceeds size limit");
			output.write(buffer, 0, count);
		}
		return output.decode(targetSize);
	}

	public static Bitmap decode(byte[] bytes, int length, int targetSize) {
		if (length <= 0 || length > MAX_INPUT_BYTES || length > bytes.length) return null;
		BitmapFactory.Options options = new BitmapFactory.Options();
		options.inJustDecodeBounds = true;
		BitmapFactory.decodeByteArray(bytes, 0, length, options);
		if (!prepare(options, targetSize)) return null;
		return resize(BitmapFactory.decodeByteArray(bytes, 0, length, options), targetSize);
	}

	public static Bitmap decode(File file, int targetSize) {
		if (file.length() <= 0 || file.length() > MAX_INPUT_BYTES) return null;
		BitmapFactory.Options options = new BitmapFactory.Options();
		options.inJustDecodeBounds = true;
		BitmapFactory.decodeFile(file.getPath(), options);
		if (!prepare(options, targetSize)) return null;
		return resize(BitmapFactory.decodeFile(file.getPath(), options), targetSize);
	}

	private static boolean prepare(BitmapFactory.Options options, int targetSize) {
		if (Thread.currentThread().isInterrupted()) return false;
		options.inSampleSize = ThumbnailSizing.sampleSize(options.outWidth, options.outHeight, targetSize);
		options.inJustDecodeBounds = false;
		options.inScaled = false;
		options.inPreferredConfig = Bitmap.Config.ARGB_8888;
		return options.inSampleSize > 0;
	}

	private static Bitmap resize(Bitmap bitmap, int targetSize) {
		if (bitmap == null) return null;
		if (Thread.currentThread().isInterrupted()) {
			bitmap.recycle();
			return null;
		}
		int[] size = ThumbnailSizing.dimensions(bitmap.getWidth(), bitmap.getHeight(), targetSize);
		Bitmap result = bitmap;
		try {
			if (size[0] != bitmap.getWidth() || size[1] != bitmap.getHeight()) {
				result = Bitmap.createScaledBitmap(bitmap, size[0], size[1], true);
			}
			return result;
		} catch (RuntimeException | OutOfMemoryError e) {
			bitmap.recycle();
			throw e;
		} finally {
			if (result != bitmap) bitmap.recycle();
		}
	}
}
