package com.mishiranu.dashchan.ui.posting.photo;

import android.graphics.Color;
import org.json.JSONException;
import org.json.JSONObject;

/** Version-1 document/session JSON. IO, generations and UI error reporting stay in the Activity. */
public final class EditorSessionCodec {
    private EditorSessionCodec() {}

    public static JSONObject encode(EditorSessionSnapshot snapshot) throws JSONException {
        JSONObject json = new JSONObject();
        json.put("hash", snapshot.sourceHash);
        json.put("name", snapshot.sourceName);
        json.put("document", snapshot.document.toJson());
        json.put("section", snapshot.section.name());
        json.put("checkpoint", snapshot.checkpoint == null ? JSONObject.NULL : new JSONObject(snapshot.checkpoint));
        json.put("adjustment", snapshot.adjustmentIndex);
        json.put("drawTool", snapshot.drawTool.name());
        json.put("hideTool", snapshot.hideTool.name());
        json.put("color", snapshot.selectedColor);
        json.put("size", snapshot.brushSize);
        json.put("png", snapshot.exportPng);
        json.put("toolSettingsV2", true);
        json.put("mosaicSize", snapshot.mosaicSize);
        json.put("blurPreset", snapshot.blurPreset);
        json.put("drawAll", snapshot.drawAll);
        json.put("objectAboveEffects", snapshot.objectAboveEffects);
        json.put("side", snapshot.exportSide);
        json.put("quality", snapshot.exportQuality);
        json.put("exportOptionsV2", true);
        json.put("maskAll", snapshot.maskAll);
        if(snapshot.photoOptions!=null) json.put("photoOptionsV1",snapshot.photoOptions.toJson());
        if(snapshot.fileSheetState!=null) json.put("fileSheet",new JSONObject(snapshot.fileSheetState));
        return json;
    }

    /** Mutates a seeded builder in the original read order.
     * If a later field is invalid, the Activity resets document/checkpoint/section only,
     * retaining already read options exactly as the previous loader did.
     */
    public static void restore(JSONObject json, EditorSessionSnapshot.Builder restored) throws JSONException {
        if (!restored.sourceHash.equals(json.getString("hash"))
                || !restored.sourceName.equals(json.getString("name"))) {
            throw new JSONException("Source mismatch");
        }
        restored.document = EditorDocument.fromJson(json.getJSONObject("document"));
        restored.checkpoint = json.isNull("checkpoint") ? null : json.getJSONObject("checkpoint").toString();
        restored.section = EditorSessionSnapshot.Section.valueOf(json.getString("section"));
        restored.selectedColor = json.optInt("color", Color.WHITE);
        restored.brushSize = (float) json.optDouble("size", .025);
        restored.exportPng = json.optBoolean("png", restored.exportPng);
        restored.exportSide = json.optInt("side", 0);
        if (!json.optBoolean("exportOptionsV2", false) || restored.exportSide == 16384) restored.exportSide = 0;
        restored.maskAll = json.optBoolean("maskAll", true);
        restored.exportQuality = json.optInt("quality", 94);
        restored.adjustmentIndex = EditorDocument.Adjustment.clampStorageIndex(json.optInt("adjustment", 0));
        restored.drawTool = EditorDocument.Kind.valueOf(json.optString("drawTool", "PEN"));
        restored.hideTool = EditorDocument.Kind.valueOf(json.optString("hideTool", "COVER"));
        restored.brushSize = EditorBlurPresets.brush(restored.brushSize);
        restored.mosaicSize = EditorBlurPresets.brush((float) json.optDouble("mosaicSize", restored.brushSize));
        restored.blurPreset = EditorBlurPresets.clamp(json.optInt("blurPreset",
                json.optBoolean("toolSettingsV2", false) ? 1 : EditorBlurPresets.nearest(restored.brushSize)));
        restored.drawAll = json.optBoolean("drawAll", true);
        restored.objectAboveEffects = json.optBoolean("objectAboveEffects", false);
        if(restored.exportSide<0 || restored.exportSide>1000000) restored.exportSide=0;
        restored.exportQuality = Math.max(1, Math.min(100, restored.exportQuality));
        if(json.has("photoOptionsV1")) restored.photoOptions=PhotoAttachmentOptions.fromJson(json.getJSONObject("photoOptionsV1"));
        else if(restored.photoOptions!=null) {
            PhotoAttachmentOptions.Builder options=new PhotoAttachmentOptions.Builder(restored.photoOptions);
            options.reencode=options.reencode || restored.exportSide>0 || options.png!=restored.exportPng || options.quality!=restored.exportQuality;
            options.png=restored.exportPng;options.quality=restored.exportQuality;
            if(restored.exportSide>0 && restored.sourceWidth>0 && restored.sourceHeight>0) {
                int[] size=EditorExportSizing.dimensions(restored.sourceWidth,restored.sourceHeight,restored.exportSide);
                options.width=size[0];options.height=size[1];
            }
            restored.photoOptions=options.build();
        }
        restored.fileSheetState=json.has("fileSheet")?json.getJSONObject("fileSheet").toString():null;
    }
}
