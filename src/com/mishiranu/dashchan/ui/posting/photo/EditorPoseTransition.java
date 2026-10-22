package com.mishiranu.dashchan.ui.posting.photo;

/** Single retargetable transition. Generation fences late cancel/end callbacks. No document access. */
public final class EditorPoseTransition {
    private final EditorPose current = new EditorPose(), start = new EditorPose(), target = new EditorPose();
    private long generation;
    private boolean initialized, running;
    public EditorPose current() { return current; }
    public float targetAngle() { return target.frameAngle; }
    public boolean initialized() { return initialized; }
    public boolean running() { return running; }
    public long invalidate() { running = false; return ++generation; }
    public void direct(EditorPose pose) {
        if (initialized) {
            pose.reframe(EditorPose.nearest(pose.frameAngle + (target.frameReversed != pose.frameReversed ? 180 : 0), target.frameAngle));
            pose.angle = EditorPose.nearest(pose.angle, target.angle);
        }
        current.set(pose); start.set(pose); target.set(pose); initialized = true; invalidate();
    }
    public long begin(EditorPose visible, EditorPose destination, float quarterDelta) {
        float goal = initialized ? target.frameAngle : destination.frameAngle;
        boolean reflected = visible.mirror != destination.mirror;
        destination.reframe(quarterDelta != 0 ? goal + quarterDelta
                : reflected ? destination.frameAngle + 180f * Math.round((goal - destination.frameAngle) / 180f)
                : EditorPose.nearest(destination.frameAngle + (target.frameReversed != destination.frameReversed ? 180 : 0), goal));
        destination.angle = EditorPose.nearest(destination.angle, initialized ? target.angle + quarterDelta : destination.angle);
        start.set(visible);
        if (reflected) {
            // Reflection is a pixel crossfade, never a scale through zero or a spurious half-turn.
            // Keep the visible frame (even mid-rotation). Remap the retained scene into these
            // new image axes, so pixel reflection and frame movement share one orientation.
            start.angle = destination.angle - (destination.frameAngle - visible.frameAngle);
        }
        start.mirror = destination.mirror; target.set(destination);
        current.set(start); initialized = true; running = true; return ++generation;
    }
    public boolean sample(long token, float fraction) {
        if (token != generation || !running) return false;
        EditorPose.interpolate(start, target, fraction, current); return true;
    }
    public boolean finish(long token) {
        if (token != generation || !running) return false;
        current.set(target); running = false; return true;
    }
}
