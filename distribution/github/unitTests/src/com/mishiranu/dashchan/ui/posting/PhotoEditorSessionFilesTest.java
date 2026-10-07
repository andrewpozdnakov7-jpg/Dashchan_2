package com.mishiranu.dashchan.ui.posting;

import com.mishiranu.dashchan.ui.posting.photo.PhotoEditorSessionFiles;
import java.io.File;
import java.nio.file.Files;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import static org.junit.Assert.*;

public class PhotoEditorSessionFilesTest {
    @Rule public TemporaryFolder temporary = new TemporaryFolder();

    @Test public void cleanupRemovesOnlyStaleInactiveEditorSessions() throws Exception {
        File root = temporary.newFolder("photo-editor");
        File stale = session(root, "a"), active = session(root, "b"), fresh = session(root, "c");
        long now = System.currentTimeMillis(), old = now - PhotoEditorSessionFiles.RETENTION_MILLIS - 10000;
        age(stale, old); age(active, old);
        PhotoEditorSessionFiles.register(active);
        try {
            PhotoEditorSessionFiles.prune(root, now);
            assertFalse(stale.exists()); assertTrue(active.exists()); assertTrue(fresh.exists());
        } finally { PhotoEditorSessionFiles.unregister(active); }
    }

    @Test public void recreatedActivityStillProtectsSessionAfterOldActivityUnregisters() throws Exception {
        File root = temporary.newFolder("photo-editor"), session = session(root, "a");
        long now = System.currentTimeMillis();
        age(session, now - PhotoEditorSessionFiles.RETENTION_MILLIS - 10000);
        PhotoEditorSessionFiles.register(session); PhotoEditorSessionFiles.register(session);
        PhotoEditorSessionFiles.unregister(session);
        try {
            PhotoEditorSessionFiles.prune(root, now); assertTrue(session.exists());
        } finally { PhotoEditorSessionFiles.unregister(session); }
    }

    @Test public void cleanupDoesNotTouchUnknownFilesOrOtherCacheDirectories() throws Exception {
        File root = temporary.newFolder("photo-editor"), session = session(root, "a");
        Files.writeString(new File(session, "unrelated.txt").toPath(), "keep");
        assertFalse(PhotoEditorSessionFiles.remove(session));
        assertTrue(new File(session, "session.json").exists());
        File unrelatedRoot = temporary.newFolder("other-cache"), other = session(unrelatedRoot, "a");
        assertFalse(PhotoEditorSessionFiles.remove(other)); assertTrue(other.exists());
    }

    private static File session(File root, String character) throws Exception {
        File directory = new File(root, character.repeat(32));
        assertTrue(directory.mkdir());
        Files.writeString(new File(directory, "session.json").toPath(), "{}");
        return directory;
    }

    private static void age(File directory, long timestamp) {
        for (File file : directory.listFiles()) assertTrue(file.setLastModified(timestamp));
        assertTrue(directory.setLastModified(timestamp));
    }
}
