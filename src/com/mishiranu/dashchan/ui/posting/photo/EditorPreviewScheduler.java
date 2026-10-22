package com.mishiranu.dashchan.ui.posting.photo;

/** UI-thread queue with one running job and one coalesced latest revision. Only epoch is read by a worker. */
public final class EditorPreviewScheduler {
    public static final class Request {
        public final long epoch, revision;
        public final boolean live;
        private Request(long epoch, long revision, boolean live) {
            this.epoch = epoch; this.revision = revision; this.live = live;
        }
    }
    private volatile long epoch;
    private long revision, displayed;
    private boolean pending;
    private Request running;
    public void change(boolean compatible) { if (!compatible) ++epoch; ++revision; pending = true; }
    public boolean canStart() { return pending && running == null; }
    public Request start(boolean live) {
        if (!canStart()) return null;
        pending = false; running = new Request(epoch, revision, live); return running;
    }
    public boolean cancelled(Request request) { return request.epoch != epoch; }
    public boolean complete(Request request) {
        if (running != request) return false;
        running = null;
        if (cancelled(request) || request.revision <= displayed || (!request.live && request.revision != revision)) return false;
        displayed = request.revision; return true;
    }
}
