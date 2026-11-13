package com.mishiranu.dashchan.media.compression;
import android.content.Context;
import androidx.lifecycle.MutableLiveData;
import java.io.File;
import java.util.*;
/** Data-only posting compatibility; creating an export is unsupported, even via a stale route. */
public final class VideoCompressionJob {
    public enum Phase {PREPARING, READY, RUNNING, DONE, ERROR, CANCELLED}
    public static final class Source {
        public final String hash, name;
        public final File file;
        public Source(String hash, String name, File file) { this.hash=hash; this.name=name; this.file=file; }
    }
    public static final class Result {
        public final String hash, name, codec;
        public final long before, after;
        public Result(String hash, String name, String codec, long before, long after) {
            this.hash=hash; this.name=name; this.codec=codec; this.before=before; this.after=after;
        }
    }
    public final MutableLiveData<VideoCompressionJob> changes = new MutableLiveData<>();
    public final Context context;
    public final String id = "unavailable";
    public final long profile;
    public final List<Source> sources;
    public final List<Source> queue = Collections.emptyList();
    public final Map<String, Result> results = Collections.emptyMap();
    public Phase phase = Phase.CANCELLED;
    public int index, error, percent=-1, itemPercent=-1, attempt=1;
    public boolean importing;
    public VideoCompressionJob(Context context, List<Source> sources, long profile, String chan, String board,
            boolean allowMp4, Set<String> forced) {
        throw new UnsupportedOperationException("Video export is not included in this distribution");
    }
    public static VideoCompressionJob restoreCompleted(Context c,List<Source> s,long p,String chan,String board,
            boolean mp4,Set<String> forced,Map<String,Result> results) {
        throw new UnsupportedOperationException("Video export is not included in this distribution");
    }
    public boolean current() { return false; }
    public boolean busy() { return false; }
    public boolean isManualSource(Source source) { return false; }
    public void prepare() { throw new UnsupportedOperationException("Video export unavailable"); }
    public void release() {}
    public void fail(int error) { this.error=error; phase=Phase.ERROR; }
}
