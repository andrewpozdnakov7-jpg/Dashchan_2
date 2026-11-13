package com.mishiranu.dashchan.ui.posting.photo;

import android.content.Intent;
import org.json.JSONException;

/** Both editors return a single detached transaction; posting validates identity before touching its holder. */
public final class PhotoEditorContract {
    public static final String OPTIONS="photoOptionsV1", ID="photoAttachmentId", REQUEST="photoEditorRequest";
    public static final String SPOILER="photoSpoilerSupported", METADATA="photoMetadataSupported", ENCODED="photoEncoded";
    private PhotoEditorContract() {}
    public static Intent input(Intent intent, PhotoAttachmentOptions options, String id, String request,
            boolean spoiler, boolean metadata) {
        try { intent.putExtra(OPTIONS,options.toJson().toString()); } catch (JSONException e) { throw new IllegalStateException(e); }
        return intent.putExtra(ID,id).putExtra(REQUEST,request).putExtra(SPOILER,spoiler).putExtra(METADATA,metadata);
    }
    public static PhotoAttachmentOptions read(Intent intent) { return PhotoAttachmentOptions.parse(intent.getStringExtra(OPTIONS)); }
    public static Intent result(Intent input, String hash, String name, String sourceHash, String sourceName,
            int index, PhotoAttachmentOptions options, boolean encoded) {
        Intent result=new Intent().putExtra("resultHash",hash).putExtra("resultName",name)
                .putExtra("resultSourceHash",sourceHash).putExtra("resultSourceName",sourceName).putExtra("resultAttachmentIndex",index)
                .putExtra(ID,input.getStringExtra(ID)).putExtra(REQUEST,input.getStringExtra(REQUEST)).putExtra(ENCODED,encoded);
        try { return result.putExtra(OPTIONS,options.toJson().toString()); } catch (JSONException e) { throw new IllegalStateException(e); }
    }
}
