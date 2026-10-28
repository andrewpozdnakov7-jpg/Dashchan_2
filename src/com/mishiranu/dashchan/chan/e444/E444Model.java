package com.mishiranu.dashchan.chan.e444;

import java.util.List;

public final class E444Model {
    private E444Model() {}
    public static final class Board {
        public String id;
        public String category;
        public String name;
        public String info;
        public String defaultName;
        public List<String> reactions;
        public List<String> fileTypes;

        public int enableDices;
        public int enableFlags;
        public int enableIcons;
        public int enableLikes;
        public int enableReactions;
        public int enableNames;
        public int enableOekaki;
        public int enablePosting;
        public int enableSage;
        public int enableShield;
        public int enableSubject;
        public int enableThreadTags;
        public int enableTrips;
        public int enableOpMod;

        public int bumpLimit;
        public int maxComment;
        public int maxFilesSize;
        public int maxPages;
        public int speed;
        public int threads;
        public int threadsPerPage;
        public int uniquePosters;
        public int lastNum;
    }
    public static final class Thread {
        public int threadNum;
        public int postsCount;
        public int filesCount;
        public List<Post> posts;
    }
    public static final class Post {
        public int num;
        public String board;
        public int parent;
        public int op;
        public int sticky;
        public int closed;
        public int endless;
        public long timestamp;
        public String subject;
        public String comment;
        public String name;
        public String trip;
        public String email;
        public List<File> files;
        public int postsCount;
        public int filesCount;

        public List<Post> posts;
        public List<MenuSection> menu;
        public List<Reaction> reactions;
        public List<String> answers;
        public List<Integer> poll_results_exact;
    }
    public static final class File {
        public String path;
        public String thumbnail;
        public String fullname;
        public int size;
        public int width;
        public int height;
    }
    public static final class BoardsResponse {
        public List<Board> boards;
    }
    public static final class PostsResponse {
        public int currentThread;
        public int filesCount;
        public int postersCount;
        public int postsCount;
        public boolean isBoard;
        public boolean isIndex;
        public int isClosed;
        public int moderView;
        public Board board;
        public List<Thread> threads;
    }
    public static final class ApiResponse {
        public int result;
        public ApiError error;
    }
    public static final class ApiError {
        public int code;
        public String message;
    }
    public static final class PostingResponse {
        public int result;
        public Integer num;
        public Integer thread;
        public ApiError error;
    }
    public static final class SlideCaptchaResponse {
        public int code;
        public String message;
    }
    public static final class SlideCaptchaIdResponse {
        public int code;
        public String captchaKey;
        public String imageBase64;
        public String tileBase64;
        public int tileWidth;
        public int tileHeight;
        public int tileX;
        public int tileY;
    }
    public static final class MobilePostResponse {
        public int result;
        public Post post;
    }
    public static final class MenuLink {
        public String label;
        public String url;
    }
    public static final class MenuSection {
        public String sectionName;
        public List<MenuLink> links;
    }
    public static final class Reaction {
        public String icon;
        public int count;
    }
}
