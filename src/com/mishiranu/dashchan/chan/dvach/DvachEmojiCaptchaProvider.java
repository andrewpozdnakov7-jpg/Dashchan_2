package com.mishiranu.dashchan.chan.dvach;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.net.Uri;
import android.util.Base64;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.util.ArrayList;
import java.util.List;

import chan.content.ChanPerformer;
import chan.http.HttpException;
import chan.http.HttpRequest;
import chan.http.HttpResponse;
import chan.http.SimpleEntity;
import chan.text.JsonSerial;
import chan.text.ParseException;

/**
 * Separate class which contains logic for solving emoji_captcha type on 2ch.hk.
 * Emoji captcha shows a picture with some emojis, and custom keyboard with emojis. User should
 * use keyboard to select all emojis from picture (order-independent). Keyboard changes after each
 * user input.
 */
class DvachEmojiCaptchaProvider {

    private final DvachChanLocator locator;
    private final String captchaId;

    private final DvachChanPerformer.ReadCaptchaData data;

    private final DvachEmojiCaptchaAnswerRetriever answerRetriever;

    /**
     * Main constructor. For each captcha task we creating new instance of this class.
     * @param data - input info about captcha
     * @param locator - dvach locator for making requests
     * @param id - captcha id
     * @param answerRetriever - lambda for emoji selection on keyboard by user
     */
    DvachEmojiCaptchaProvider(DvachChanPerformer.ReadCaptchaData data,
                                     DvachChanLocator locator,
                                     String id,
                                     DvachEmojiCaptchaAnswerRetriever answerRetriever) {
        this.locator = locator;
        this.data = data;
        this.captchaId = id;
        this.answerRetriever = answerRetriever;
    }

    /**
     * Entrypoint for solver. No params since we set them all in constructor. Method gets the task
     * and starts the solving cycle.
     * @return final captcha result.
     * @throws HttpException if some request was failed
     */
    ChanPerformer.ReadCaptchaResult loadEmojiCaptcha() throws HttpException {
        // First, we request initial captcha state
        Uri uri = locator.buildPath("api", "captcha", "emoji", "show").buildUpon()
                .appendQueryParameter("id", captchaId).build();
        HttpResponse response = doWithRetries(uri, data, 3);
        EmojiCaptchaResponse parsedResponse = parseEmojiCaptcha(response);

        //prepare selected emojis state
        SelectedEmojis selected = new SelectedEmojis();

        return solveEmojiCaptchaLoop(parsedResponse, selected);
    }

    /**
     * Captcha solving loop method. After each user input, we sent it to the server and getting a
     * new keyboard, until {@link EmojiCaptchaResponse.Success} is received.
     * @param parsedResponse current server response
     * @param selected current user selected emojis
     * @return captcha answer (probably from recursive method call)
     * @throws HttpException
     */
    private ChanPerformer.ReadCaptchaResult solveEmojiCaptchaLoop(
            EmojiCaptchaResponse parsedResponse,
            SelectedEmojis selected
    ) throws HttpException {
        // If we received new captcha content, then we show it to user, so that he chooses emoji from keyboard
        if (parsedResponse instanceof EmojiCaptchaResponse.Content) {
            EmojiCaptchaResponse.Content content = (EmojiCaptchaResponse.Content) parsedResponse;

            // prepare captcha task image with previously selected emojis
            Bitmap captchaImage = base64ToBitmap(content.image);
            Bitmap comboBitmap = createTaskWithSelectedBitmap(captchaImage, selected);

            // prepare captcha task keyboard array
            Bitmap[] keyboardImages = new Bitmap[content.keyboard.size()];
            for (int i = 0; i < content.keyboard.size(); i++) {
                Bitmap origKeyIcon = base64ToBitmap(content.keyboard.get(i));
                int maxSize = Math.max(origKeyIcon.getHeight(), origKeyIcon.getWidth());
                Bitmap keyBitmap = Bitmap.createBitmap(maxSize, maxSize, Bitmap.Config.ARGB_8888);
                Canvas keyCanvas = new Canvas(keyBitmap);
                // set key background to white so the black icon would not overlap with dark theme
                keyCanvas.drawARGB(255, 255, 255, 255);
                int x = Math.max((origKeyIcon.getHeight() - origKeyIcon.getWidth()) / 2, 0);
                int y = Math.max((origKeyIcon.getWidth() - origKeyIcon.getHeight()) / 2, 0);
                keyCanvas.drawBitmap(origKeyIcon, x, y, null);
                keyboardImages[i] = keyBitmap;
            }

            // send task image and keyboard, receive user input
            Integer answer = answerRetriever.getAnswer(comboBitmap, keyboardImages);

            // if user skipped answer, or made improper input, then we stopping captcha solving
            if (answer == null || answer == -1 || answer >= keyboardImages.length) {
                return new ChanPerformer.ReadCaptchaResult(ChanPerformer.CaptchaState.NEED_LOAD, null);
            } else {
                // if user made a valid selection, we process it
                // add selected emoji to list of selected
                Bitmap selectedBitmap = keyboardImages[answer];
                selected.bitmaps.add(Bitmap.createScaledBitmap(selectedBitmap,
                        selectedBitmap.getWidth() * SelectedEmojis.SIZE / selectedBitmap.getHeight(),
                        SelectedEmojis.SIZE, true));

                // send user selection to server
                try {
                    Uri uri = locator.buildPath("api", "captcha", "emoji", "click")
                            .buildUpon().build();
                    SimpleEntity entity = new SimpleEntity();
                    entity.setContentType("application/json; charset=utf-8");
                    JSONObject jsonObject = new JSONObject();
                    jsonObject.put("captchaTokenID", captchaId);
                    jsonObject.put("emojiNumber", answer);
                    entity.setData(jsonObject.toString());
                    HttpResponse response = new HttpRequest(uri, data)
                            .setPostMethod(entity).perform();

                    // server returns new response (this is either new state or finish signal)
                    EmojiCaptchaResponse parsedClickResponse = parseEmojiCaptcha(response);

                    //continue the loop
                    return solveEmojiCaptchaLoop(parsedClickResponse, selected);
                } catch (JSONException ex) {
                    // if something goes wrong, just drop the captcha solving process
                    return new ChanPerformer.ReadCaptchaResult(ChanPerformer.CaptchaState.NEED_LOAD, null);
                }
            }
        } else {
            // If we got "success" field in server response then we finish the process, and fill
            // the result
            EmojiCaptchaResponse.Success success = (EmojiCaptchaResponse.Success) parsedResponse;
            ChanPerformer.CaptchaData captchaData = new ChanPerformer.CaptchaData();
            ChanPerformer.ReadCaptchaResult result = new ChanPerformer.ReadCaptchaResult(ChanPerformer.CaptchaState.SKIP, captchaData);
            // Fill the challenge field with result, to use it later when we send post
            captchaData.put(ChanPerformer.CaptchaData.CHALLENGE, success.success);
            return result;
        }
    }

    private HttpResponse doWithRetries(Uri uri, HttpRequest.Preset data, int attempts) throws HttpException {
        while (true) {
            try {
                return new HttpRequest(uri, data).perform();
            } catch (HttpException e) {
                attempts--;
                if (attempts == 0 || e.getResponseCode() != HttpURLConnection.HTTP_INTERNAL_ERROR) {
                    throw e;
                }
                try {
                    int delayBetweenLoadCaptchaImageAttemptsMillis = 500;
                    Thread.sleep(delayBetweenLoadCaptchaImageAttemptsMillis);
                } catch (InterruptedException ex) {
                    throw e;
                }
            }
        }
    }

    /**
     * Class for parsing server captcha response.
     * @param response - raw response
     * @return {@link EmojiCaptchaResponse.Success} or {@link EmojiCaptchaResponse.Content} object
     * @throws HttpException
     * @throws RuntimeException
     */
    private EmojiCaptchaResponse parseEmojiCaptcha(HttpResponse response) throws HttpException, RuntimeException {
        String image = "";
        ArrayList<String> keyboard = new ArrayList<>();
        try (InputStream input = response.open();
             JsonSerial.Reader reader = JsonSerial.reader(input)) {
            reader.startObject();
            while (!reader.endStruct()) {
                switch (reader.nextName()) {
                    case "image": {
                        image = reader.nextString();
                        break;
                    }
                    case "keyboard": {
                        reader.startArray();
                        while (!reader.endStruct()) {
                            keyboard.add(reader.nextString());
                        }
                        break;
                    }
                    case "success": {
                        return new EmojiCaptchaResponse.Success(reader.nextString());
                    }
                }
            }
        } catch (IOException | ParseException ex) {
            throw new RuntimeException(ex.getMessage());
        }
        return new EmojiCaptchaResponse.Content(image, keyboard);
    }

    /**
     * This method merges captcha task image and previously selected emojis
     * @param captchaImage captcha task image
     * @param selected selected emoji container
     * @return resulting bitmap
     */
    private Bitmap createTaskWithSelectedBitmap(Bitmap captchaImage, SelectedEmojis selected) {
        Bitmap comboBitmap;

        int width, height;
        width = captchaImage.getWidth();
        height = captchaImage.getHeight() + SelectedEmojis.SIZE_WITH_PADDING;
        comboBitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
        Canvas comboImage = new Canvas(comboBitmap);

        // Ensure that captcha has white background, so we will have black
        // selected icons on white canvas, despite of app theme
        if (!selected.bitmaps.isEmpty()) {
            comboImage.drawARGB(255, 255, 255, 255);
        }

        for (int i = 0; i < selected.bitmaps.size(); i++) {
            comboImage.drawBitmap(selected.bitmaps.get(i),
                    i * SelectedEmojis.SIZE_WITH_PADDING, 0f, null);
        }
        comboImage.drawBitmap(captchaImage, 0, SelectedEmojis.SIZE_WITH_PADDING, null);
        return comboBitmap;
    }

    private Bitmap base64ToBitmap(String base64) {
        byte[] decodedString = Base64.decode(base64, Base64.DEFAULT);
        return BitmapFactory.decodeByteArray(decodedString, 0, decodedString.length);
    }

    /**
     * This interface is used for selecting emoji from keyboard from user
     */
    interface DvachEmojiCaptchaAnswerRetriever {

        /**
         * @param task captcha task bitmap, also showing previously selected emojis
         * @param keyboard captcha keyboard bitmap array
         * @return keyboard selection index
         */
        Integer getAnswer(Bitmap task, Bitmap[] keyboard);
    }

    /**
     * This class is used for storing previously selected user emojis, to show them in emoji
     * selection dialog alongside with captcha task
     */
    private static class SelectedEmojis {

        private static final int SIZE = 40;
        private static final int PADDING = 5;

        private static final int SIZE_WITH_PADDING = SIZE + PADDING;

        private final List<Bitmap> bitmaps = new ArrayList<>();
    }


    /**
     * Base data class for emoji captcha info from server.
     */
    private abstract static class EmojiCaptchaResponse {

        /**
         * This class is received from the server if the captcha solving is finished.
         */
        private static class Success extends EmojiCaptchaResponse {
            private final String success;

            /**
             * @param success this is captcha result, we send it with the post.
             */
            private Success(String success) {
                this.success = success;
            }
        }

        /**
         * This class is received from the server if the captcha solving is in process.
         */
        private static class Content extends EmojiCaptchaResponse {

            private final String image;

            private final List<String> keyboard;

            /**
             * @param image base64 picture, captcha task
             * @param keyboard base64 picture array, actual task keyboard
             */
            private Content(String image, List<String> keyboard) {
                this.image = image;
                this.keyboard = keyboard;
            }
        }
    }

}
