package com.mishiranu.dashchan;

import static org.junit.Assert.*;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import chan.text.JsonSerial;
import com.mishiranu.dashchan.content.storage.DraftsStorage;
import com.mishiranu.dashchan.ui.posting.photo.*;
import com.mishiranu.dashchan.util.GraphicsUtils;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;

/** Prepared for Android execution: JSON/session/draft compatibility, no network or actual posting. */
@RunWith(AndroidJUnit4.class)
public class PhotoAttachmentOptionsRoundTripTest {
    @Test public void processedDraftRoundTripRetainsUiChoicesWithoutRevivingPendingEncode() throws Exception {
        String hash=String.format(java.util.Locale.US,"%064x",1);
        PhotoAttachmentOptions o=PhotoAttachmentOptions.legacy(true,false,true,true,new GraphicsUtils.Reencoding("jpeg",30,2),1001,751,false).applied(hash);
        String id=java.util.UUID.randomUUID().toString(), request=java.util.UUID.randomUUID().toString();
        DraftsStorage.AttachmentDraft draft=new DraftsStorage.AttachmentDraft(hash,"photo.jpg","rating",true,false,true,true,
                new GraphicsUtils.Reencoding("jpeg",90,8),false,null,o,id,request);
        assertNull(draft.reencoding);
        try(JsonSerial.Writer w=JsonSerial.writer()) {
            draft.serialize(w);
            try(JsonSerial.Reader r=JsonSerial.reader(w.build())) {
                DraftsStorage.AttachmentDraft restored=DraftsStorage.AttachmentDraft.deserialize(r);
                assertEquals(o,restored.photoOptions);assertEquals(id,restored.attachmentId);assertEquals(request,restored.photoEditorRequest);
                assertNull(restored.reencoding);assertTrue(restored.optionSpoiler);assertEquals("rating",restored.rating);
            }
        }
    }
    @Test public void sessionAndUnconfirmedSheetDraftRemainSeparateAfterRestore() throws Exception {
        PhotoAttachmentOptions.Builder b=new PhotoAttachmentOptions.Builder();b.reencode=true;b.quality=30;b.width=456;b.height=512;
        PhotoAttachmentOptions confirmed=b.build();b.removeFileName=true;
        JSONObject sheet=new JSONObject().put("draft",b.build().toJson()).put("sizePage",true).put("pendingWidth",200).put("pendingHeight",100);
        EditorSessionSnapshot.Builder session=new EditorSessionSnapshot.Builder(new EditorDocument(),"source","photo.jpg");
        session.photoOptions=confirmed;session.fileSheetState=sheet.toString();
        EditorSessionSnapshot.Builder restored=new EditorSessionSnapshot.Builder(new EditorDocument(),"source","photo.jpg");
        EditorSessionCodec.restore(EditorSessionCodec.encode(session.build()),restored);
        assertEquals(confirmed,restored.photoOptions);assertFalse(restored.photoOptions.removeFileName);
        assertTrue(PhotoAttachmentOptions.fromJson(new JSONObject(restored.fileSheetState).getJSONObject("draft")).removeFileName);
        assertEquals(30,restored.photoOptions.quality);assertEquals(456,restored.photoOptions.width);
    }
    @Test public void malformedMarkerDoesNotSkipOutputAndOldDraftKeepsLegacyPending() throws Exception {
        PhotoAttachmentOptions.Builder b=new PhotoAttachmentOptions.Builder();b.reencode=true;
        PhotoAttachmentOptions o=b.build().applied("hash");JSONObject j=o.toJson();j.put("appliedSignature","wrong");
        assertNotNull(PhotoAttachmentOptions.fromJson(j).pending("hash"));
        assertNull(PhotoAttachmentOptions.parse("{broken"));
        DraftsStorage.AttachmentDraft old=new DraftsStorage.AttachmentDraft(String.format(java.util.Locale.US,"%064x",2),"photo.jpg",null,
                true,false,true,false,new GraphicsUtils.Reencoding("jpeg",30,2));
        assertNull(old.photoOptions);assertEquals(30,old.reencoding.quality);assertEquals(2,old.reencoding.reduce);
    }
}
