package com.mishiranu.dashchan.chan.e444;

import android.net.Uri;
import chan.content.ChanLocator;
import java.util.List;
import java.util.regex.Pattern;

public class E444ChanLocator extends ChanLocator {
    private static final Pattern BOARD_PATH = Pattern.compile("/[\\w-]+(?:/(?:(?:index|catalog|\\d+)\\.html)?)?");
    private static final Pattern THREAD_PATH = Pattern.compile("/[\\w-]+/res/(\\d+)\\.html");
    private static final Pattern ATTACHMENT_PATH = Pattern.compile("/[\\w-]+/src/(\\d+)/\\d+\\.\\w+");

    public E444ChanLocator() {
        addChanHost("ech.bz");
        addChanHost("ech.ist");
        addConvertableChanHost("ech.u");
        setHttpsMode(HttpsMode.HTTPS_ONLY);
    }

    public boolean isKnownHostOrRelative(Uri uri) {
        String host = uri.getHost();
        return isChanHostOrRelative(uri);
    }

    @Override
    public boolean isBoardUri(Uri uri) {
        return isKnownHostOrRelative(uri) && isPathMatches(uri, BOARD_PATH);
    }

    @Override
    public boolean isThreadUri(Uri uri) {
        return isKnownHostOrRelative(uri) && isPathMatches(uri, THREAD_PATH);
    }

    @Override
    public boolean isAttachmentUri(Uri uri) {
        return isKnownHostOrRelative(uri) && isPathMatches(uri, ATTACHMENT_PATH);
    }

    @Override
    public String getBoardName(Uri uri) {
        List<String> segments = uri.getPathSegments();
        return segments.isEmpty() ? null : segments.get(0);
    }

    @Override
    public String getThreadNumber(Uri uri) {
        String value = getGroupValue(uri.getPath(), THREAD_PATH, 1);
        if (value == null) {
            value = getGroupValue(uri.getPath(), ATTACHMENT_PATH, 1);
        }
        return value;
    }

    @Override
    public String getPostNumber(Uri uri) {
        return uri.getFragment();
    }

    @Override
    public Uri createBoardUri(String boardName, int pageNumber) {
        return pageNumber > 0 ? buildPath(boardName, pageNumber + ".html") : buildPath(boardName, "");
    }

    @Override
    public Uri createThreadUri(String boardName, String threadNumber) {
        return buildPath(boardName, "res", threadNumber + ".html");
    }

    @Override
    public Uri createPostUri(String boardName, String threadNumber, String postNumber) {
        return createThreadUri(boardName, threadNumber)
                .buildUpon()
                .fragment(postNumber)
                .build();
    }
}
