package com.mishiranu.dashchan.ui.posting;

import com.mishiranu.dashchan.ui.posting.photo.EditorPreviewScheduler;
import org.junit.Test;
import static org.junit.Assert.*;

/** No Android dependency: continuous input must not cancel every in-flight live preview. */
public class EditorPreviewSchedulerTest {
    @Test public void continuousMovesCoalesceAndStillPublish() {
        EditorPreviewScheduler queue = new EditorPreviewScheduler(); queue.change(false);
        EditorPreviewScheduler.Request first = queue.start(true);
        for (int i = 0; i < 200; i++) {
            queue.change(true); assertNull(queue.start(true)); assertFalse(queue.cancelled(first));
        }
        assertTrue(queue.complete(first)); assertTrue(queue.canStart());
        EditorPreviewScheduler.Request latest = queue.start(true);
        assertEquals(first.revision + 200, latest.revision);
        assertTrue(queue.complete(latest)); assertFalse(queue.canStart());
    }
    @Test public void slowWorkerMakesProgressDuringAnEntireGesture() {
        EditorPreviewScheduler queue = new EditorPreviewScheduler(); queue.change(false);
        for (int frame = 0; frame < 20; frame++) {
            EditorPreviewScheduler.Request request = queue.start(true);
            assertNotNull(request);
            for (int move = 0; move < 12; move++) queue.change(true);
            assertTrue(queue.complete(request));
        }
    }
    @Test public void pointerUpRetainsProgressThenPublishesExactFinalRevision() {
        EditorPreviewScheduler queue = new EditorPreviewScheduler(); queue.change(false);
        EditorPreviewScheduler.Request live = queue.start(true);
        queue.change(true); queue.change(true); // Last MOVE and UP, same gesture epoch.
        assertTrue(queue.complete(live));
        EditorPreviewScheduler.Request finalFrame = queue.start(false);
        assertEquals(live.revision + 2, finalFrame.revision);
        assertTrue(queue.complete(finalFrame)); assertFalse(queue.canStart());
    }
    @Test public void cancelOrUndoInvalidatesOldGesture() {
        EditorPreviewScheduler queue = new EditorPreviewScheduler(); queue.change(false);
        EditorPreviewScheduler.Request live = queue.start(true);
        queue.change(false); assertTrue(queue.cancelled(live)); assertFalse(queue.complete(live));
        assertTrue(queue.complete(queue.start(false)));
    }
    @Test public void staleNonLiveOrRepeatedCallbackCannotReplaceNewerFrame() {
        EditorPreviewScheduler queue = new EditorPreviewScheduler(); queue.change(false);
        EditorPreviewScheduler.Request old = queue.start(false); queue.change(true);
        assertFalse(queue.complete(old));
        EditorPreviewScheduler.Request current = queue.start(false);
        assertFalse(queue.complete(old)); assertNull(queue.start(false));
        assertTrue(queue.complete(current)); assertFalse(queue.complete(current));
    }
    @Test public void incompatiblePendingChangesAreStillOneLatestJob() {
        EditorPreviewScheduler queue = new EditorPreviewScheduler(); queue.change(false);
        EditorPreviewScheduler.Request old = queue.start(true);
        for (int i = 0; i < 100; i++) queue.change(false);
        assertFalse(queue.complete(old));
        EditorPreviewScheduler.Request latest = queue.start(false);
        assertEquals(old.epoch + 100, latest.epoch); assertEquals(old.revision + 100, latest.revision);
        assertTrue(queue.complete(latest)); assertFalse(queue.canStart());
    }
}
