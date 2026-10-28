package com.mishiranu.dashchan.chan.e444;

import java.io.IOException;
import java.util.ArrayList;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

/** Explicit wire adapters derived from ech-bz/Dashchan-Extensions; no reflective model binding. */
public final class E444JsonUtils {
    private E444JsonUtils() {}
    public static <T> T fromJson(String value, Class<T> type) throws IOException {
        try {
            JSONObject object = new JSONObject(value != null ? value : "{}");
            if (type == E444Model.Board.class) return type.cast(readBoard(object));
            if (type == E444Model.Thread.class) return type.cast(readThread(object));
            if (type == E444Model.Post.class) return type.cast(readPost(object));
            if (type == E444Model.File.class) return type.cast(readFile(object));
            if (type == E444Model.BoardsResponse.class) return type.cast(readBoardsResponse(object));
            if (type == E444Model.PostsResponse.class) return type.cast(readPostsResponse(object));
            if (type == E444Model.ApiResponse.class) return type.cast(readApiResponse(object));
            if (type == E444Model.ApiError.class) return type.cast(readApiError(object));
            if (type == E444Model.PostingResponse.class) return type.cast(readPostingResponse(object));
            if (type == E444Model.SlideCaptchaResponse.class) return type.cast(readSlideCaptchaResponse(object));
            if (type == E444Model.SlideCaptchaIdResponse.class) return type.cast(readSlideCaptchaIdResponse(object));
            if (type == E444Model.MobilePostResponse.class) return type.cast(readMobilePostResponse(object));
            if (type == E444Model.MenuLink.class) return type.cast(readMenuLink(object));
            if (type == E444Model.MenuSection.class) return type.cast(readMenuSection(object));
            if (type == E444Model.Reaction.class) return type.cast(readReaction(object));
            throw new IOException("Unsupported E444 response type");
        } catch (JSONException | IllegalArgumentException e) { throw new IOException("Invalid E444 response", e); }
    }
    private static E444Model.Board readBoard(JSONObject o) throws JSONException {
        E444Model.Board v = new E444Model.Board();
        v.id = o.optString("id", "");
        v.category = o.optString("category", "");
        v.name = o.optString("name", "");
        v.info = o.optString("info", "");
        v.defaultName = o.optString("default_name", "");
        v.reactions = new ArrayList<>();
        JSONArray a_reactions = o.optJSONArray("reactions");
        if (a_reactions != null) for (int i = 0; i < Math.min(a_reactions.length(), 10000); i++) {
            v.reactions.add(a_reactions.optString(i, ""));
        }
        v.fileTypes = new ArrayList<>();
        JSONArray a_fileTypes = o.optJSONArray("file_types");
        if (a_fileTypes != null) for (int i = 0; i < Math.min(a_fileTypes.length(), 10000); i++) {
            v.fileTypes.add(a_fileTypes.optString(i, ""));
        }
        v.enableDices = o.optInt("enable_dices", 0);
        v.enableFlags = o.optInt("enable_flags", 0);
        v.enableIcons = o.optInt("enable_icons", 0);
        v.enableLikes = o.optInt("enable_likes", 0);
        v.enableReactions = o.optInt("enable_reactions", 0);
        v.enableNames = o.optInt("enable_names", 0);
        v.enableOekaki = o.optInt("enable_oekaki", 0);
        v.enablePosting = o.optInt("enable_posting", 0);
        v.enableSage = o.optInt("enable_sage", 0);
        v.enableShield = o.optInt("enable_shield", 0);
        v.enableSubject = o.optInt("enable_subject", 0);
        v.enableThreadTags = o.optInt("enable_thread_tags", 0);
        v.enableTrips = o.optInt("enable_trips", 0);
        v.enableOpMod = o.optInt("enable_op_mod", 0);
        v.bumpLimit = o.optInt("bump_limit", 0);
        v.maxComment = o.optInt("max_comment", 0);
        v.maxFilesSize = o.optInt("max_files_size", 0);
        v.maxPages = o.optInt("max_pages", 0);
        v.speed = o.optInt("speed", 0);
        v.threads = o.optInt("threads", 0);
        v.threadsPerPage = o.optInt("threads_per_page", 0);
        v.uniquePosters = o.optInt("unique_posters", 0);
        v.lastNum = o.optInt("last_num", 0);
        return v;
    }
    private static E444Model.Thread readThread(JSONObject o) throws JSONException {
        E444Model.Thread v = new E444Model.Thread();
        v.threadNum = o.optInt("thread_num", 0);
        v.postsCount = o.optInt("posts_count", 0);
        v.filesCount = o.optInt("files_count", 0);
        v.posts = new ArrayList<>();
        JSONArray a_posts = o.optJSONArray("posts");
        if (a_posts != null) for (int i = 0; i < Math.min(a_posts.length(), 10000); i++) {
            v.posts.add(readPost(a_posts.getJSONObject(i)));
        }
        return v;
    }
    private static E444Model.Post readPost(JSONObject o) throws JSONException {
        E444Model.Post v = new E444Model.Post();
        v.num = o.optInt("num", 0);
        v.board = o.optString("board", "");
        v.parent = o.optInt("parent", 0);
        v.op = o.optInt("op", 0);
        v.sticky = o.optInt("sticky", 0);
        v.closed = o.optInt("closed", 0);
        v.endless = o.optInt("endless", 0);
        v.timestamp = o.optLong("timestamp", 0L);
        v.subject = o.optString("subject", "");
        v.comment = o.optString("comment", "");
        v.name = o.optString("name", "");
        v.trip = o.optString("trip", "");
        v.email = o.optString("email", "");
        v.files = new ArrayList<>();
        JSONArray a_files = o.optJSONArray("files");
        if (a_files != null) for (int i = 0; i < Math.min(a_files.length(), 10000); i++) {
            v.files.add(readFile(a_files.getJSONObject(i)));
        }
        v.postsCount = o.optInt("posts_count", 0);
        v.filesCount = o.optInt("files_count", 0);
        v.posts = new ArrayList<>();
        JSONArray a_posts = o.optJSONArray("posts");
        if (a_posts != null) for (int i = 0; i < Math.min(a_posts.length(), 10000); i++) {
            v.posts.add(readPost(a_posts.getJSONObject(i)));
        }
        v.menu = new ArrayList<>();
        JSONArray a_menu = o.optJSONArray("menu");
        if (a_menu != null) for (int i = 0; i < Math.min(a_menu.length(), 10000); i++) {
            v.menu.add(readMenuSection(a_menu.getJSONObject(i)));
        }
        v.reactions = new ArrayList<>();
        JSONArray a_reactions = o.optJSONArray("reactions");
        if (a_reactions != null) for (int i = 0; i < Math.min(a_reactions.length(), 10000); i++) {
            v.reactions.add(readReaction(a_reactions.getJSONObject(i)));
        }
        v.answers = new ArrayList<>();
        JSONArray a_answers = o.optJSONArray("answers");
        if (a_answers != null) for (int i = 0; i < Math.min(a_answers.length(), 10000); i++) {
            v.answers.add(a_answers.optString(i, ""));
        }
        v.poll_results_exact = new ArrayList<>();
        JSONArray a_poll_results_exact = o.optJSONArray("poll_results_exact");
        if (a_poll_results_exact != null) for (int i = 0; i < Math.min(a_poll_results_exact.length(), 10000); i++) {
            v.poll_results_exact.add(a_poll_results_exact.optInt(i, 0));
        }
        return v;
    }
    private static E444Model.File readFile(JSONObject o) throws JSONException {
        E444Model.File v = new E444Model.File();
        v.path = o.optString("path", "");
        v.thumbnail = o.optString("thumbnail", "");
        v.fullname = o.optString("fullname", "");
        v.size = o.optInt("size", 0);
        v.width = o.optInt("width", 0);
        v.height = o.optInt("height", 0);
        return v;
    }
    private static E444Model.BoardsResponse readBoardsResponse(JSONObject o) throws JSONException {
        E444Model.BoardsResponse v = new E444Model.BoardsResponse();
        v.boards = new ArrayList<>();
        JSONArray a_boards = o.optJSONArray("boards");
        if (a_boards != null) for (int i = 0; i < Math.min(a_boards.length(), 10000); i++) {
            v.boards.add(readBoard(a_boards.getJSONObject(i)));
        }
        return v;
    }
    private static E444Model.PostsResponse readPostsResponse(JSONObject o) throws JSONException {
        E444Model.PostsResponse v = new E444Model.PostsResponse();
        v.currentThread = o.optInt("current_thread", 0);
        v.filesCount = o.optInt("files_count", 0);
        v.postersCount = o.optInt("posters_count", 0);
        v.postsCount = o.optInt("posts_count", 0);
        v.isBoard = o.optBoolean("is_board", false);
        v.isIndex = o.optBoolean("is_index", false);
        v.isClosed = o.optInt("is_closed", 0);
        v.moderView = o.optInt("moder_view", 0);
        v.board = o.optJSONObject("board") != null ? readBoard(o.getJSONObject("board")) : null;
        v.threads = new ArrayList<>();
        JSONArray a_threads = o.optJSONArray("threads");
        if (a_threads != null) for (int i = 0; i < Math.min(a_threads.length(), 10000); i++) {
            v.threads.add(readThread(a_threads.getJSONObject(i)));
        }
        return v;
    }
    private static E444Model.ApiResponse readApiResponse(JSONObject o) throws JSONException {
        E444Model.ApiResponse v = new E444Model.ApiResponse();
        v.result = o.optInt("result", 0);
        v.error = o.optJSONObject("error") != null ? readApiError(o.getJSONObject("error")) : null;
        return v;
    }
    private static E444Model.ApiError readApiError(JSONObject o) throws JSONException {
        E444Model.ApiError v = new E444Model.ApiError();
        v.code = o.optInt("code", 0);
        v.message = o.optString("message", "");
        return v;
    }
    private static E444Model.PostingResponse readPostingResponse(JSONObject o) throws JSONException {
        E444Model.PostingResponse v = new E444Model.PostingResponse();
        v.result = o.optInt("result", 0);
        v.num = o.isNull("num") ? null : o.getInt("num");
        v.thread = o.isNull("thread") ? null : o.getInt("thread");
        v.error = o.optJSONObject("error") != null ? readApiError(o.getJSONObject("error")) : null;
        return v;
    }
    private static E444Model.SlideCaptchaResponse readSlideCaptchaResponse(JSONObject o) throws JSONException {
        E444Model.SlideCaptchaResponse v = new E444Model.SlideCaptchaResponse();
        v.code = o.optInt("code", 0);
        v.message = o.optString("message", "");
        return v;
    }
    private static E444Model.SlideCaptchaIdResponse readSlideCaptchaIdResponse(JSONObject o) throws JSONException {
        E444Model.SlideCaptchaIdResponse v = new E444Model.SlideCaptchaIdResponse();
        v.code = o.optInt("code", 0);
        v.captchaKey = o.optString("captcha_key", "");
        v.imageBase64 = o.optString("image_base64", "");
        v.tileBase64 = o.optString("tile_base64", "");
        v.tileWidth = o.optInt("tile_width", 0);
        v.tileHeight = o.optInt("tile_height", 0);
        v.tileX = o.optInt("tile_x", 0);
        v.tileY = o.optInt("tile_y", 0);
        return v;
    }
    private static E444Model.MobilePostResponse readMobilePostResponse(JSONObject o) throws JSONException {
        E444Model.MobilePostResponse v = new E444Model.MobilePostResponse();
        v.result = o.optInt("result", 0);
        v.post = o.optJSONObject("post") != null ? readPost(o.getJSONObject("post")) : null;
        return v;
    }
    private static E444Model.MenuLink readMenuLink(JSONObject o) throws JSONException {
        E444Model.MenuLink v = new E444Model.MenuLink();
        v.label = o.optString("label", "");
        v.url = o.optString("url", "");
        return v;
    }
    private static E444Model.MenuSection readMenuSection(JSONObject o) throws JSONException {
        E444Model.MenuSection v = new E444Model.MenuSection();
        v.sectionName = o.optString("sectionName", o.optString("section_name", ""));
        v.links = new ArrayList<>();
        JSONArray a_links = o.optJSONArray("links");
        if (a_links != null) for (int i = 0; i < Math.min(a_links.length(), 10000); i++) {
            v.links.add(readMenuLink(a_links.getJSONObject(i)));
        }
        return v;
    }
    private static E444Model.Reaction readReaction(JSONObject o) throws JSONException {
        E444Model.Reaction v = new E444Model.Reaction();
        v.icon = o.optString("icon", "");
        v.count = o.optInt("count", 0);
        return v;
    }
    public static String toJson(E444Model.Board v) {
        JSONObject o = new JSONObject();
        try {
            o.put("id", v.id);
            o.put("category", v.category);
            o.put("name", v.name);
            o.put("info", v.info);
            o.put("default_name", v.defaultName);
            o.put("reactions", new JSONArray(v.reactions != null ? v.reactions : java.util.Collections.emptyList()));
            o.put("file_types", new JSONArray(v.fileTypes != null ? v.fileTypes : java.util.Collections.emptyList()));
            o.put("enable_dices", v.enableDices);
            o.put("enable_flags", v.enableFlags);
            o.put("enable_icons", v.enableIcons);
            o.put("enable_likes", v.enableLikes);
            o.put("enable_reactions", v.enableReactions);
            o.put("enable_names", v.enableNames);
            o.put("enable_oekaki", v.enableOekaki);
            o.put("enable_posting", v.enablePosting);
            o.put("enable_sage", v.enableSage);
            o.put("enable_shield", v.enableShield);
            o.put("enable_subject", v.enableSubject);
            o.put("enable_thread_tags", v.enableThreadTags);
            o.put("enable_trips", v.enableTrips);
            o.put("enable_op_mod", v.enableOpMod);
            o.put("bump_limit", v.bumpLimit);
            o.put("max_comment", v.maxComment);
            o.put("max_files_size", v.maxFilesSize);
            o.put("max_pages", v.maxPages);
            o.put("speed", v.speed);
            o.put("threads", v.threads);
            o.put("threads_per_page", v.threadsPerPage);
            o.put("unique_posters", v.uniquePosters);
            o.put("last_num", v.lastNum);
        } catch (JSONException e) { throw new IllegalArgumentException(e); }
        return o.toString();
    }
}
