package com.mishiranu.dashchan.chan.e444;

import android.net.Uri;
import chan.content.ApiException;
import chan.content.ChanPerformer;
import chan.content.InvalidResponseException;
import chan.content.model.Board;
import chan.content.model.BoardCategory;
import chan.content.model.FileAttachment;
import chan.content.model.Post;
import chan.content.model.Posts;
import chan.http.HttpException;
import chan.http.HttpRequest;
import chan.http.HttpResponse;
import chan.http.MultipartEntity;
import chan.http.UrlEncodedEntity;
import chan.util.StringUtils;
import com.mishiranu.dashchan.chan.e444.captcha.CaptchaReader;
import com.mishiranu.dashchan.chan.e444.captcha.CaptchaSender;
import java.util.ArrayList;
import java.util.HashMap;

public class E444ChanPerformer extends ChanPerformer {
    public E444ChanPerformer() {
    }

    public static Post createPost(E444Model.Post postJson, Object linked) throws InvalidResponseException {
        E444ChanLocator locator = E444ChanLocator.get(linked);
        if (postJson == null || postJson.num <= 0) throw new InvalidResponseException();
        Post post = new Post();
        if (postJson.parent > 0) {
            post.setParentPostNumber(Integer.toString(postJson.parent));
        }
        post.setThreadNumber(Integer.toString(postJson.parent > 0 ? postJson.parent : postJson.num));
        post.setPostNumber(Integer.toString(postJson.num));
        post.setOriginalPoster(postJson.op != 0);
        post.setSticky(postJson.sticky != 0);
        post.setClosed(postJson.closed != 0);
        post.setCyclical(postJson.endless != 0);
        post.setTimestamp(postJson.timestamp * 1000L);
        post.setSubject(StringUtils.clearHtml(StringUtils.emptyIfNull(postJson.subject)).trim());
        post.setComment(postJson.comment);
        post.setName(StringUtils.clearHtml(StringUtils.emptyIfNull(postJson.name)).trim());
        post.setTripcode(postJson.trip);
        if (!StringUtils.isEmpty(postJson.email)) {
            if ("sage".equals(postJson.email)) {
                post.setSage(true);
            } else {
                post.setEmail(postJson.email);
            }
        }
        if (postJson.files != null) {
            ArrayList<FileAttachment> attachments = new ArrayList<>();
            for (E444Model.File fileJson : postJson.files) {
                if (StringUtils.isEmpty(fileJson.path)) continue;
                FileAttachment fileAttachment = new FileAttachment();
                fileAttachment.setFileUri(locator, Uri.parse(fileJson.path));
                if (!StringUtils.isEmpty(fileJson.thumbnail)) fileAttachment.setThumbnailUri(locator, Uri.parse(fileJson.thumbnail));
                fileAttachment.setOriginalName(fileJson.fullname);
                fileAttachment.setSize((int) Math.min(Integer.MAX_VALUE, Math.max(0L, (long) fileJson.size * 1024L)));
                if (fileJson.width != 0 && fileJson.height != 0) {
                    fileAttachment.setWidth(fileJson.width);
                    fileAttachment.setHeight(fileJson.height);
                }
                attachments.add(fileAttachment);
            }
            post.setAttachments(attachments);
        }

        post.setE444Data(E444PostData.encode(postJson));

        return post;
    }

    @Override
    public ReadBoardsResult onReadBoards(ReadBoardsData data) throws HttpException, InvalidResponseException {
        E444ChanConfiguration configuration = E444ChanConfiguration.get(this);
        E444Model.BoardsResponse response =
                E444RequestPerformer.request(data, "index.json").performJson(E444Model.BoardsResponse.class);
        HashMap<String, ArrayList<Board>> boardsMap = new HashMap<>();
        for (E444Model.Board board : response.boards) {
            configuration.updateFromBoards(board);
            ArrayList<Board> boards = boardsMap.get(board.category);
            if (boards == null) {
                boards = new ArrayList<>();
                boardsMap.put(board.category, boards);
            }
            boards.add(new Board(
                    board.id, board.name, StringUtils.clearHtml(board.info).trim()));
        }
        ArrayList<BoardCategory> boardCategories = new ArrayList<>();
        for (HashMap.Entry<String, ArrayList<Board>> entry : boardsMap.entrySet()) {
            boardCategories.add(new BoardCategory(entry.getKey(), entry.getValue()));
        }
        return new ReadBoardsResult(boardCategories);
    }

    @Override
    public ReadThreadsResult onReadThreads(ReadThreadsData data) throws HttpException, InvalidResponseException {
        E444ChanLocator locator = E444ChanLocator.get(this);
        E444Model.PostsResponse response = E444RequestPerformer.request(
                        data,
                        data.boardName,
                        (data.isCatalog() ? "catalog" : data.pageNumber == 0 ? "index" : data.pageNumber) + ".json")
                .configure(request -> request.setValidator(data.validator))
                .performJson(E444Model.PostsResponse.class);
        E444ChanConfiguration configuration = E444ChanConfiguration.get(this);
        configuration.updateFromBoards(response.board);
        ArrayList<Posts> threads = new ArrayList<>();
        for (E444Model.Thread thread : response.threads) {
            ArrayList<Post> posts = new ArrayList<>();
            for (E444Model.Post post : thread.posts) {
                posts.add(createPost(post, locator));
            }
            threads.add(new Posts(posts).addPostsCount(thread.postsCount).addPostsWithFilesCount(thread.filesCount));
        }
        return new ReadThreadsResult(threads).setBoardSpeed(response.board.speed);
    }

    @Override
    public ReadPostsResult onReadPosts(ReadPostsData data) throws HttpException, InvalidResponseException {
        E444ChanLocator locator = E444ChanLocator.get(this);
        E444ChanConfiguration configuration = E444ChanConfiguration.get(this);
        // Widgets are populated via createPost(), so always read a full thread payload.
        E444Model.PostsResponse response = E444RequestPerformer.request(
                        data, data.boardName, "res", data.threadNumber + ".json")
                .performJson(E444Model.PostsResponse.class);
        configuration.updateFromBoards(response.board);
        ArrayList<Post> posts = new ArrayList<>();
        if (response.threads.isEmpty()) throw new InvalidResponseException();
        for (E444Model.Post postJson : response.threads.get(0).posts) {
            posts.add(createPost(postJson, locator));
        }
        return new ReadPostsResult(new Posts(posts).setUniquePosters(response.postersCount)).setFullThread(true);
    }

    @Override
    public ReadSinglePostResult onReadSinglePost(ReadSinglePostData data) throws HttpException, InvalidResponseException {
        E444Model.MobilePostResponse response = E444RequestPerformer.request(data, "api", "mobile", "v2", "post",
                data.boardName, data.postNumber).performJson(E444Model.MobilePostResponse.class);
        if (response.post == null || response.post.num <= 0) throw new InvalidResponseException();
        if (StringUtils.isEmpty(response.post.board)) response.post.board = data.boardName;
        return new ReadSinglePostResult(createPost(response.post, this));
    }

    @Override
    public ReadCaptchaResult onReadCaptcha(ReadCaptchaData data) throws HttpException, InvalidResponseException {
        return CaptchaReader.onReadCaptcha(
                this,
                data,
                images -> requireUserImageSingleChoice(-1, images,
                        com.mishiranu.dashchan.content.MainApplication.getInstance()
                                .getString(com.mishiranu.dashchan.R.string.ech_captcha_prompt), null));
    }

    @Override
    public CheckAuthorizationResult onCheckAuthorization(CheckAuthorizationData data)
            throws HttpException, InvalidResponseException {
        E444ChanConfiguration configuration = E444ChanConfiguration.get(this);
        UrlEncodedEntity entity = new UrlEncodedEntity();
        if (data.authorizationData == null || data.authorizationData.length == 0)
            return new CheckAuthorizationResult(false);
        entity.add("passcode", data.authorizationData[0]);
        HttpResponse response = E444RequestPerformer.request(data, "user", "passlogin")
                .configure(
                        request -> request.setPostMethod(entity).setRedirectHandler(HttpRequest.RedirectHandler.NONE))
                .perform();
        String passcodeAuth = response.getCookieValue("passcode_auth");
        String usercodeAuth = response.getCookieValue("usercode_auth");
        configuration.storeCookie("passcode_auth", passcodeAuth, null);
        configuration.storeCookie("usercode_auth", usercodeAuth, null);
        return new CheckAuthorizationResult(passcodeAuth != null);
    }

    @Override
    public ReadContentResult onReadContent(ReadContentData data) throws HttpException, InvalidResponseException {
        E444ChanLocator locator = E444ChanLocator.get(this);
        if (locator.isKnownHostOrRelative(data.uri)) {
            E444RequestPerformer request = E444RequestPerformer.request(
                    data, data.uri.getPathSegments().toArray(new String[0]));
            for (String queryName : data.uri.getQueryParameterNames()) {
                for (String queryValue : data.uri.getQueryParameters(queryName)) {
                    request.param(queryName, queryValue);
                }
            }
            return new ReadContentResult(request.perform());
        }
        return super.onReadContent(data);
    }

    @Override
    public SendPostResult onSendPost(SendPostData data) throws HttpException, ApiException, InvalidResponseException {
        E444ChanConfiguration configuration = E444ChanConfiguration.get(this);

        MultipartEntity entity = new MultipartEntity();
        entity.add("task", "post");
        entity.add("board", StringUtils.emptyIfNull(data.boardName));
        entity.add("thread", data.threadNumber != null ? data.threadNumber : "0");
        entity.add("code", "");
        entity.add("client", "dashchan");
        entity.add("enable_poll", "0");
        entity.add("hat", "");
        entity.add("timer", "0");
        entity.add("email", StringUtils.emptyIfNull(data.email));
        entity.add("name", "");
        entity.add("trip", StringUtils.emptyIfNull(data.name));
        entity.add("subject", StringUtils.emptyIfNull(data.subject));
        entity.add("comment", StringUtils.emptyIfNull(data.comment));
        entity.add("poll_answers[]", "");
        entity.add("makaka_id", "");
        entity.add("makaka_answer", "");
        CaptchaSender.buildCaptchaPostData(data).addToEntity(entity);
        if (data.attachments != null) {
            for (SendPostData.Attachment attachment : data.attachments) {
                attachment.addToEntity(entity, "file[]");
            }
        }

        E444Model.PostingResponse response = E444RequestPerformer.request(data, "user", "posting")
                .configure(request -> request.setPostMethod(entity))
                .performJson(E444Model.PostingResponse.class);
        if (response.num != null) {
            return new SendPostResult(response.thread != null ? response.thread.toString() : data.threadNumber, response.num.toString());
        }
        if (response.thread != null) {
            return new SendPostResult(response.thread.toString(), null);
        }
        throw new ApiException(response.error != null ? response.error.message : "E444 request failed");
    }

    @Override
    public SendDeletePostsResult onSendDeletePosts(SendDeletePostsData data)
            throws HttpException, ApiException, InvalidResponseException {
        MultipartEntity entity = new MultipartEntity();
        entity.add("board", data.boardName);
        entity.add("thread", data.threadNumber);
        if (data.postNumbers.isEmpty()) throw new InvalidResponseException();
        entity.add("post", data.postNumbers.get(0));
        E444Model.ApiResponse response = E444RequestPerformer.request(data, "user", "delete")
                .configure(request -> request.setPostMethod(entity))
                .performJson(E444Model.ApiResponse.class);
        if (response.result == 1) {
            return null;
        }
        throw new ApiException(response.error != null ? response.error.message : "E444 request failed");
    }

    @Override
    public SendReportPostsResult onSendReportPosts(SendReportPostsData data)
            throws HttpException, ApiException, InvalidResponseException {
        MultipartEntity entity = new MultipartEntity();
        entity.add("board", data.boardName);
        entity.add("thread", data.threadNumber);
        if (data.postNumbers.isEmpty()) throw new InvalidResponseException();
        entity.add("post", data.postNumbers.get(0));
        entity.add("comment", data.comment);
        E444Model.ApiResponse response = E444RequestPerformer.request(data, "user", "report")
                .configure(request -> request.setPostMethod(entity))
                .performJson(E444Model.ApiResponse.class);
        if (response.result == 1) {
            return null;
        }
        throw new ApiException(response.error != null ? response.error.message : "E444 request failed");
    }
}
