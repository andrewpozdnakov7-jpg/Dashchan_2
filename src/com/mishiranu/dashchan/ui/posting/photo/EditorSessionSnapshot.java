package com.mishiranu.dashchan.ui.posting.photo;

import android.graphics.Color;

/** Session data detached by the Activity before it enters the worker queue.
 * The captured document belongs to this snapshot; it is not a shared UI document.
 */
public final class EditorSessionSnapshot {
    public enum Section { HOME, CROP, ADJUST, FILTERS, DRAW, TEXT, HIDE, STICKERS }

    public final EditorDocument document;
    public final String sourceHash;
    public final String sourceName;
    public final String checkpoint;
    public final Section section;
    public final int adjustmentIndex;
    public final EditorDocument.Kind drawTool;
    public final EditorDocument.Kind hideTool;
    public final int selectedColor;
    public final float brushSize;
    public final float mosaicSize;
    public final int blurPreset;
    public final boolean drawAll;
    public final boolean objectAboveEffects;
    public final boolean maskAll;
    public final int exportSide;
    public final int exportQuality;
    public final boolean exportPng;

    private EditorSessionSnapshot(Builder builder) {
        document = builder.document;
        sourceHash = builder.sourceHash;
        sourceName = builder.sourceName;
        checkpoint = builder.checkpoint;
        section = builder.section;
        adjustmentIndex = builder.adjustmentIndex;
        drawTool = builder.drawTool;
        hideTool = builder.hideTool;
        selectedColor = builder.selectedColor;
        brushSize = builder.brushSize;
        mosaicSize = builder.mosaicSize;
        blurPreset = builder.blurPreset;
        drawAll = builder.drawAll;
        objectAboveEffects = builder.objectAboveEffects;
        maskAll = builder.maskAll;
        exportSide = builder.exportSide;
        exportQuality = builder.exportQuality;
        exportPng = builder.exportPng;
    }

    /** Named fields also let the codec preserve the old incremental restore/fallback behavior. */
    public static final class Builder {
        public EditorDocument document;
        public String sourceHash;
        public String sourceName;
        public String checkpoint;
        public Section section = Section.HOME;
        public int adjustmentIndex;
        public EditorDocument.Kind drawTool = EditorDocument.Kind.PEN;
        public EditorDocument.Kind hideTool = EditorDocument.Kind.COVER;
        public int selectedColor = Color.WHITE;
        public float brushSize = .025f;
        public float mosaicSize = .025f;
        public int blurPreset = 1;
        public boolean drawAll = true;
        public boolean objectAboveEffects;
        public boolean maskAll = true;
        public int exportSide;
        public int exportQuality = 94;
        public boolean exportPng;

        public Builder(EditorDocument document, String sourceHash, String sourceName) {
            this.document = document;
            this.sourceHash = sourceHash;
            this.sourceName = sourceName;
        }

        public EditorSessionSnapshot build() {
            return new EditorSessionSnapshot(this);
        }
    }
}
