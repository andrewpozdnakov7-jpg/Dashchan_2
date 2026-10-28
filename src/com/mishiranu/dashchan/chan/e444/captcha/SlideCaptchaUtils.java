package com.mishiranu.dashchan.chan.e444.captcha;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.util.Base64;
import chan.content.InvalidResponseException;
import chan.util.StringUtils;
import java.util.Arrays;

public final class SlideCaptchaUtils {
    private SlideCaptchaUtils() {}

    public interface SlideCaptchaChoiceCallback<T extends Throwable> {
        Integer choose(Bitmap[] images) throws T;
    }

    public static Bitmap decodeBase64Bitmap(String base64) throws InvalidResponseException {
        String value = StringUtils.emptyIfNull(base64);
        int comma = value.indexOf(',');
        if (comma >= 0) {
            value = value.substring(comma + 1);
        }
        try {
            byte[] bytes = Base64.decode(value, Base64.DEFAULT);
            Bitmap bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.length);
            if (bitmap == null) {
                throw new InvalidResponseException();
            }
            return bitmap;
        } catch (IllegalArgumentException e) {
            throw new InvalidResponseException(e);
        }
    }

    public static <T extends Throwable> Integer chooseSlideCaptchaX(
            Bitmap image, Bitmap tile, int tileY, int maxCount, SlideCaptchaChoiceCallback<T> callback) throws T {
        int min = 0;
        int max = image.getWidth() - tile.getWidth();
        if (max < 0) {
            return null;
        }
        int minStep = 2;
        Bitmap[] bitmaps = new Bitmap[maxCount];
        Canvas[] canvases = new Canvas[maxCount];
        try {
            while (max - min >= 2 * minStep) {
                int count = Math.min(maxCount, Math.max(2, (max - min + minStep - 1) / minStep + 1));
                for (int i = 0; i < count; i++) {
                    if (bitmaps[i] == null) {
                        bitmaps[i] = Bitmap.createBitmap(image.getWidth(), image.getHeight(), Bitmap.Config.ARGB_8888);
                        canvases[i] = new Canvas(bitmaps[i]);
                    }
                    int x = min + (max - min) * i / (count - 1);
                    bitmaps[i].eraseColor(0x00000000);
                    canvases[i].drawBitmap(image, 0, 0, null);
                    canvases[i].drawBitmap(tile, x, tileY, null);
                }
                for (int i = count; i < maxCount; i++) {
                    if (bitmaps[i] == null) {
                        bitmaps[i] = Bitmap.createBitmap(image.getWidth(), image.getHeight(), Bitmap.Config.ARGB_8888);
                        canvases[i] = new Canvas(bitmaps[i]);
                    }
                    bitmaps[i].eraseColor(0x00000000);
                }
                Integer result = callback.choose(Arrays.copyOf(bitmaps, maxCount));
                if (result == null) {
                    return null;
                } else if (result < 0 || result >= count) {
                    break;
                } else if (result == 0) {
                    max = min + (max - min) / (count - 1) - 1;
                } else if (result == count - 1) {
                    min = min + (max - min) * (count - 2) / (count - 1) + 1;
                } else {
                    int newMin = min + (max - min) * (result - 1) / (count - 1) + 1;
                    int newMax = min + (max - min) * (result + 1) / (count - 1) - 1;
                    min = newMin;
                    max = newMax;
                }
            }
            return (min + max) / 2;
        } finally {
            for (Bitmap bitmap : bitmaps) {
                if (bitmap != null) {
                    bitmap.recycle();
                }
            }
        }
    }
}
