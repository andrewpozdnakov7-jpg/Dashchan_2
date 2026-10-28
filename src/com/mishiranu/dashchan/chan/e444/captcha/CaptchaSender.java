package com.mishiranu.dashchan.chan.e444.captcha;

import chan.content.ChanPerformer;
import chan.http.MultipartEntity;
import chan.util.StringUtils;
import com.mishiranu.dashchan.chan.e444.E444ChanConfiguration;

public final class CaptchaSender {
    public static final class CaptchaPostData {
        public String captchaType;
        public String captchaId;
        public String captchaValue;
        public String passcode;

        public void addToEntity(MultipartEntity entity) {
            entity.add("usercode", StringUtils.emptyIfNull(passcode));
            entity.add("captcha_type", StringUtils.emptyIfNull(captchaType));
            entity.add("captcha_id", StringUtils.emptyIfNull(captchaId));
            entity.add("captcha_value", StringUtils.emptyIfNull(captchaValue));
        }
    }

    private CaptchaSender() {}

    public static CaptchaPostData buildCaptchaPostData(ChanPerformer.SendPostData data) {
        CaptchaPostData captcha = new CaptchaPostData();
        if (data.captchaData != null) {
            captcha.passcode = data.captchaData.get(ChanPerformer.CaptchaData.API_KEY);
            String challenge = data.captchaData.get(ChanPerformer.CaptchaData.CHALLENGE);
            String answer = data.captchaData.get("answer");
            if (data.captchaType.equals(E444ChanConfiguration.CAPTCHA_TYPE_SLIDER)) {
                captcha.captchaType = "captcha";
                captcha.captchaId = StringUtils.emptyIfNull(challenge);
                captcha.captchaValue = StringUtils.emptyIfNull(answer);
            }
        }
        return captcha;
    }
}
