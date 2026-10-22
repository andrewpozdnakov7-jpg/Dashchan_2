package com.mishiranu.dashchan.ui.posting.photo;

import android.graphics.Bitmap;
import java.util.HashMap;
import java.util.Map;
import org.json.JSONException;

/** Captured on the UI thread, consumed by the existing editor worker. */
public final class EditorExportRequest {
    public static final class Options {
        public final int side, quality;
        public final boolean png;

        public Options(int side, int quality, boolean png) {
            this.side = side;
            this.quality = quality;
            this.png = png;
        }
    }

    public static final class Source {
        public final String hash, name;
        public final int attachmentIndex, width, height;

        public Source(String hash, String name, int attachmentIndex, int width, int height) {
            this.hash = hash;
            this.name = name;
            this.attachmentIndex = attachmentIndex;
            this.width = width;
            this.height = height;
        }
    }

    public final EditorDocument.State state;
    public final Options options;
    public final Source source;
    public final boolean attach;
    public final String cacheKey;
    // Only the map is copied. Its Bitmaps remain borrowed until queued Activity cleanup.
    public final Map<String, Bitmap> assets;

    private EditorExportRequest(EditorDocument.State state, Options options, Source source,
            boolean attach, String cacheKey, Map<String, Bitmap> assets) {
        this.state = state;
        this.options = options;
        this.source = source;
        this.attach = attach;
        this.cacheKey = cacheKey;
        this.assets = assets;
    }

    public static EditorExportRequest capture(EditorDocument document, Options options, Source source,
            boolean attach, Map<String, Bitmap> assets) throws JSONException {
        EditorDocument.State state = document.state().copy();
        // Keep the old key exactly: history and UI presentation do not affect encoded pixels.
        String cacheKey = document.toJson().getJSONObject("state").toString()
                + ":" + options.side + ":" + options.quality + ":" + options.png;
        return new EditorExportRequest(state, options, source, attach, cacheKey, new HashMap<>(assets));
    }
}
