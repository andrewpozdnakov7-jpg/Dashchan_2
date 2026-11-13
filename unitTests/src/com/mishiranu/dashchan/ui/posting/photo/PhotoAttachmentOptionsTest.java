package com.mishiranu.dashchan.ui.posting.photo;

import com.mishiranu.dashchan.util.GraphicsUtils;
import org.junit.Test;
import static org.junit.Assert.*;

public class PhotoAttachmentOptionsTest {
    @Test public void privacyOnlyAndUnchangedSavesDoNotRequestAnEncode() {
        PhotoAttachmentOptions o=new PhotoAttachmentOptions.Builder().build();
        assertFalse(o.needsExport("source",false));
        PhotoAttachmentOptions flags=o.withFlags(true,true,true,true);
        assertFalse(flags.needsExport("source",false));
        assertEquals(o.outputSignature(),flags.outputSignature());
        assertTrue(flags.uniqueHash);assertTrue(flags.removeMetadata);assertTrue(flags.removeFileName);assertTrue(flags.spoiler);
    }
    @Test public void legacyReduceAndLowQualityAreAppliedExactlyOnce() {
        PhotoAttachmentOptions o=PhotoAttachmentOptions.legacy(true,false,true,false,
                new GraphicsUtils.Reencoding("jpeg",30,2),1001,751,false);
        assertEquals(30,o.quality);assertArrayEquals(new int[]{500,375},o.dimensions(1001,751));
        assertTrue(o.needsExport("source",false));assertNotNull(o.pending("source"));
        PhotoAttachmentOptions applied=o.applied("result");
        assertFalse(applied.needsExport("result",false));assertNull(applied.pending("result"));
        assertArrayEquals(new int[]{500,375},applied.dimensions(500,375));
    }
    @Test public void newEditsChangedOutputOrLostMarkerCannotReuseAppliedPixels() {
        PhotoAttachmentOptions.Builder b=new PhotoAttachmentOptions.Builder();b.reencode=true;b.quality=30;
        PhotoAttachmentOptions o=b.build().applied("result");
        assertTrue(o.needsExport("result",true));assertTrue(o.needsExport("different",false));
        b=new PhotoAttachmentOptions.Builder(o);b.quality=31;assertTrue(b.build().needsExport("result",false));
        b=new PhotoAttachmentOptions.Builder(o);b.appliedSignature="bad";assertNotNull(b.build().pending("result"));
        b=new PhotoAttachmentOptions.Builder(o);b.width=456;b.height=512;assertTrue(b.build().needsExport("result",false));
    }
    @Test public void forcedExportDoesNotRewriteUserChoicesAndPngIgnoresJpegQualityForPixels() {
        PhotoAttachmentOptions.Builder b=new PhotoAttachmentOptions.Builder();b.png=true;b.removeMetadata=false;
        PhotoAttachmentOptions o=b.build();
        assertTrue(o.needsExport("source",true));assertFalse(o.reencode);assertFalse(o.removeMetadata);
        o=o.applied("result"); b=new PhotoAttachmentOptions.Builder(o);b.quality=1;
        assertTrue(b.build().isApplied("result"));assertEquals(1,b.build().quality);
        b.reencode=true;PhotoAttachmentOptions requested=b.build();
        b=new PhotoAttachmentOptions.Builder(requested);b.reencode=false;
        assertFalse(b.build().removeMetadata);
    }
    @Test public void customTargetsStayBoundedAndAreNeverAppliedAsAnotherReduceFactor() {
        PhotoAttachmentOptions.Builder b=new PhotoAttachmentOptions.Builder();b.width=456;b.height=512;
        PhotoAttachmentOptions o=b.build();assertArrayEquals(new int[]{456,512},o.dimensions(912,1024));
        assertArrayEquals(new int[]{456,512},o.dimensions(456,512));
        assertArrayEquals(new int[]{200,100},o.dimensions(200,100));
        assertArrayEquals(new int[]{456,228},o.dimensions(1000,500));
    }
}
