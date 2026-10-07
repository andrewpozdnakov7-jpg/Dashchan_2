package com.mishiranu.dashchan.ui.posting.photo;

import java.io.File;
import java.io.IOException;
import java.util.concurrent.ConcurrentHashMap;

/** Bounded cleanup of this editor's private cache only; active sessions are never pruned. */
public final class PhotoEditorSessionFiles {
    public static final long RETENTION_MILLIS = 7L * 24 * 60 * 60 * 1000;
    private static final ConcurrentHashMap<File, Integer> ACTIVE = new ConcurrentHashMap<>();
    private PhotoEditorSessionFiles() {}

    public static void register(File session) { ACTIVE.merge(session.getAbsoluteFile(), 1, Integer::sum); }
    public static void unregister(File session) {
        ACTIVE.computeIfPresent(session.getAbsoluteFile(), (file, count) -> count > 1 ? count - 1 : null);
    }

    private static boolean directChild(File parent, File child) throws IOException {
        return parent.getCanonicalFile().equals(child.getCanonicalFile().getParentFile());
    }

    public static void prune(File root, long now) {
        if (root == null || !"photo-editor".equals(root.getName())) return;
        File[] sessions = root.listFiles();
        if (sessions == null) return;
        for (File session : sessions) {
            if (!session.getName().matches("[a-f0-9]{32}") || !session.isDirectory()
                    || ACTIVE.containsKey(session.getAbsoluteFile())) continue;
            try {
                if (!directChild(root, session)) continue;
                File[] files = session.listFiles();
                if (files == null) continue;
                long modified = session.lastModified();
                for (File file : files) modified = Math.max(modified, file.lastModified());
                // Keep entries if the clock moved backwards or timestamps are unavailable.
                if (modified > 0 && now >= modified && now - modified > RETENTION_MILLIS) remove(session);
            } catch (IOException | SecurityException ignored) {
                // Cache cleanup must not prevent editing or disclose paths in diagnostics.
            }
        }
    }

    public static boolean remove(File session) {
        File root = session.getParentFile();
        if (root == null || !"photo-editor".equals(root.getName()) || !session.getName().matches("[a-f0-9]{32}")) return false;
        try {
            if (!directChild(root, session)) return false;
            File[] files = session.listFiles();
            if (files == null) return false;
            for (File file : files) {
                if (!file.isFile() || !directChild(session, file)
                        || !file.getName().matches("session\\.json(?:\\.bak|\\.new)?|export\\.(?:jpg|png)|asset-[a-f0-9-]{36}\\.png")) return false;
            }
            for (File file : files) if (!file.delete()) return false;
            return session.delete();
        } catch (IOException | SecurityException e) { return false; }
    }
}
