package com.mishiranu.dashchan.chan.e444.captcha;

import android.graphics.Bitmap;
import chan.content.ChanPerformer;
import chan.content.InvalidResponseException;
import chan.http.HttpException;
import chan.http.UrlEncodedEntity;
import com.mishiranu.dashchan.chan.e444.E444ChanConfiguration;
import com.mishiranu.dashchan.chan.e444.E444ChanPerformer;
import com.mishiranu.dashchan.chan.e444.E444Model;
import com.mishiranu.dashchan.chan.e444.E444RequestPerformer;

public final class CaptchaReader {
    private static final int SLIDE_CHOICES_COUNT = 7;

    @FunctionalInterface
    public interface SlideCaptchaChooser {
        Integer choose(Bitmap[] images) throws HttpException;
    }

    private CaptchaReader() {}

    private static boolean checkSlideCaptcha(ChanPerformer.ReadCaptchaData data, String key, String value, int x, int y)
            throws HttpException, InvalidResponseException {
        UrlEncodedEntity entity = new UrlEncodedEntity();
        entity.add("point", x + "," + y);
        entity.add("key", key);
        E444Model.SlideCaptchaResponse response = E444RequestPerformer.request(data, "api", "captcha", "slide", "check")
                .param("v", value)
                .configure(request -> request.setPostMethod(entity))
                .performJson(E444Model.SlideCaptchaResponse.class);
        return response.code == 0;
    }

    public static ChanPerformer.ReadCaptchaResult onReadCaptcha(
            E444ChanPerformer performer, ChanPerformer.ReadCaptchaData data, SlideCaptchaChooser slideCaptchaChooser)
            throws HttpException, InvalidResponseException {
        E444ChanConfiguration configuration = E444ChanConfiguration.get(performer);
        String usercode = configuration.getCookie("passcode_auth");
        ChanPerformer.ReadCaptchaResult result;
        if (usercode != null) {
            ChanPerformer.CaptchaData captchaData = new ChanPerformer.CaptchaData();
            captchaData.put(ChanPerformer.CaptchaData.API_KEY, usercode);
            result = new ChanPerformer.ReadCaptchaResult(ChanPerformer.CaptchaState.PASS, captchaData);
        } else if (data.captchaType.equals(E444ChanConfiguration.CAPTCHA_TYPE_SLIDER)) {
            if (data.mayShowLoadButton) {
                return new ChanPerformer.ReadCaptchaResult(ChanPerformer.CaptchaState.NEED_LOAD, null);
            }
            E444Model.SlideCaptchaIdResponse response = E444RequestPerformer.request(
                            data, "api", "captcha", "slide", "id")
                    .param("v", Long.toString(System.currentTimeMillis()))
                    .performJson(E444Model.SlideCaptchaIdResponse.class);
            Bitmap image;
            Bitmap tile;
            int tileY;
            if (response.code != 0) {
                throw new InvalidResponseException();
            }
            image = SlideCaptchaUtils.decodeBase64Bitmap(response.imageBase64);
            tile = SlideCaptchaUtils.decodeBase64Bitmap(response.tileBase64);
            tileY = response.tileY;
            tileY = Math.max(0, Math.min(tileY, Math.max(0, image.getHeight() - tile.getHeight())));
            Integer selectedX;
            try {
                selectedX = SlideCaptchaUtils.chooseSlideCaptchaX(
                        image, tile, tileY, SLIDE_CHOICES_COUNT, slideCaptchaChooser::choose);
            } finally {
                image.recycle();
                tile.recycle();
            }
            if (selectedX == null) {
                return new ChanPerformer.ReadCaptchaResult(ChanPerformer.CaptchaState.NEED_LOAD, null);
            }
            if (!checkSlideCaptcha(
                    data, response.captchaKey, Long.toString(System.currentTimeMillis()), selectedX, tileY)) {
                return new ChanPerformer.ReadCaptchaResult(ChanPerformer.CaptchaState.NEED_LOAD, null);
            }
            ChanPerformer.CaptchaData captchaData = new ChanPerformer.CaptchaData();
            captchaData.put(ChanPerformer.CaptchaData.CHALLENGE, response.captchaKey);
            captchaData.put("answer", selectedX + "," + tileY);
            result = new ChanPerformer.ReadCaptchaResult(ChanPerformer.CaptchaState.PASS, captchaData);
        } else {
            throw new IllegalStateException();
        }
        result.setCaptchaType(data.captchaType);
        return result;
    }
}
