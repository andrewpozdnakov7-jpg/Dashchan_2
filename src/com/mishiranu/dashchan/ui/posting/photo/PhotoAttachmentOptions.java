package com.mishiranu.dashchan.ui.posting.photo;

import com.mishiranu.dashchan.util.GraphicsUtils;
import java.util.Objects;
import org.json.JSONException;
import org.json.JSONObject;

/** Immutable per-attachment snapshot. Requested privacy/output choices are independent of applied pixels. */
public final class PhotoAttachmentOptions {
    public final boolean uniqueHash, removeMetadata, removeFileName, spoiler, reencode, png;
    public final int quality, width, height;
    public final String appliedHash, appliedSignature;

    private PhotoAttachmentOptions(Builder b) {
        uniqueHash = b.uniqueHash; removeMetadata = b.removeMetadata; removeFileName = b.removeFileName;
        spoiler = b.spoiler; reencode = b.reencode; png = b.png;
        quality = Math.max(1, Math.min(100, b.quality));
        if (b.width < 0 || b.height < 0 || b.width > 1000000 || b.height > 1000000
                || (b.width == 0) != (b.height == 0)) throw new IllegalArgumentException("Invalid photo size");
        width = b.width; height = b.height;
        appliedHash = b.appliedHash; appliedSignature = b.appliedSignature;
    }
    public static final class Builder {
        public boolean uniqueHash, removeMetadata, removeFileName, spoiler, reencode, png;
        public int quality = 94, width, height;
        public String appliedHash, appliedSignature;
        public Builder() {}
        public Builder(PhotoAttachmentOptions o) {
            uniqueHash=o.uniqueHash; removeMetadata=o.removeMetadata; removeFileName=o.removeFileName;
            spoiler=o.spoiler; reencode=o.reencode; png=o.png; quality=o.quality;
            width=o.width; height=o.height; appliedHash=o.appliedHash; appliedSignature=o.appliedSignature;
        }
        public PhotoAttachmentOptions build() { return new PhotoAttachmentOptions(this); }
    }
    public static PhotoAttachmentOptions legacy(boolean unique, boolean metadata, boolean name, boolean spoiler,
            GraphicsUtils.Reencoding pending, int width, int height, boolean sourcePng) {
        Builder b = new Builder(); b.uniqueHash=unique; b.removeMetadata=metadata; b.removeFileName=name;
        b.spoiler=spoiler; b.png=sourcePng;
        if (pending != null) {
            b.reencode=true; b.png=GraphicsUtils.Reencoding.FORMAT_PNG.equals(pending.format); b.quality=pending.quality;
            if (pending.width > 0) { b.width=pending.width; b.height=pending.height; }
            else if (pending.reduce > 1 && width > 0 && height > 0) {
                b.width=Math.max(1,width/pending.reduce); b.height=Math.max(1,height/pending.reduce);
            }
        }
        return b.build();
    }
    public PhotoAttachmentOptions withFlags(boolean unique, boolean metadata, boolean name, boolean spoiler) {
        Builder b=new Builder(this); b.uniqueHash=unique; b.removeMetadata=metadata;
        b.removeFileName=name; b.spoiler=spoiler; return b.build();
    }
    public String outputSignature() { return (png ? "png" : "jpeg:"+quality)+":"+width+"x"+height; }
    public boolean isApplied(String hash) {
        return hash != null && !hash.isEmpty() && hash.equals(appliedHash) && outputSignature().equals(appliedSignature);
    }
    public boolean needsExport(String hash, boolean pixelsChanged) { return pixelsChanged || reencode && !isApplied(hash); }
    public PhotoAttachmentOptions applied(String hash) {
        Builder b=new Builder(this); b.appliedHash=hash; b.appliedSignature=outputSignature(); return b.build();
    }
    /** Absolute limits survive reopening; the old reduce factor is never applied to the result again. */
    public int[] dimensions(float sourceWidth, float sourceHeight) { return dimensions(sourceWidth,sourceHeight,width,height); }
    public static int[] dimensions(float w, float h, int targetWidth, int targetHeight) {
        return com.mishiranu.dashchan.util.ImageOutputSizing.fit(w,h,targetWidth,targetHeight);
    }
    public GraphicsUtils.Reencoding pending(String hash) {
        return reencode && !isApplied(hash) ? new GraphicsUtils.Reencoding(png ? GraphicsUtils.Reencoding.FORMAT_PNG
                : GraphicsUtils.Reencoding.FORMAT_JPEG,quality,1,width,height) : null;
    }
    public JSONObject toJson() throws JSONException {
        return new JSONObject().put("version",1).put("unique",uniqueHash).put("metadata",removeMetadata)
                .put("name",removeFileName).put("spoiler",spoiler).put("reencode",reencode).put("png",png)
                .put("quality",quality).put("width",width).put("height",height)
                .put("appliedHash",appliedHash==null?JSONObject.NULL:appliedHash)
                .put("appliedSignature",appliedSignature==null?JSONObject.NULL:appliedSignature);
    }
    public static PhotoAttachmentOptions fromJson(JSONObject j) throws JSONException {
        if (j.getInt("version")!=1) throw new JSONException("Unsupported photo options");
        Builder b=new Builder(); b.uniqueHash=j.getBoolean("unique"); b.removeMetadata=j.getBoolean("metadata");
        b.removeFileName=j.getBoolean("name"); b.spoiler=j.getBoolean("spoiler"); b.reencode=j.getBoolean("reencode");
        b.png=j.getBoolean("png"); b.quality=j.getInt("quality"); b.width=j.getInt("width"); b.height=j.getInt("height");
        if (b.quality<1 || b.quality>100) throw new JSONException("Invalid quality");
        b.appliedHash=j.isNull("appliedHash")?null:j.optString("appliedHash",null);
        b.appliedSignature=j.isNull("appliedSignature")?null:j.optString("appliedSignature",null);
        try { return b.build(); } catch (IllegalArgumentException e) { throw new JSONException("Invalid photo options"); }
    }
    public static PhotoAttachmentOptions parse(String json) {
        try { return json==null?null:fromJson(new JSONObject(json)); } catch (JSONException | RuntimeException e) { return null; }
    }
    @Override public boolean equals(Object other) {
        if (!(other instanceof PhotoAttachmentOptions)) return false;
        PhotoAttachmentOptions o=(PhotoAttachmentOptions)other;
        return uniqueHash==o.uniqueHash && removeMetadata==o.removeMetadata && removeFileName==o.removeFileName
                && spoiler==o.spoiler && reencode==o.reencode && png==o.png && quality==o.quality && width==o.width && height==o.height
                && Objects.equals(appliedHash,o.appliedHash) && Objects.equals(appliedSignature,o.appliedSignature);
    }
    @Override public int hashCode() { return Objects.hash(uniqueHash,removeMetadata,removeFileName,spoiler,reencode,png,quality,width,height,appliedHash,appliedSignature); }
}
