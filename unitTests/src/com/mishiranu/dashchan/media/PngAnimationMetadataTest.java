package com.mishiranu.dashchan.media;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.zip.CRC32;
import org.junit.Test;
import static org.junit.Assert.*;

public class PngAnimationMetadataTest {
    private static void chunk(DataOutputStream out,String type,byte[] bytes) throws Exception {
        byte[] tag=type.getBytes(StandardCharsets.US_ASCII);CRC32 crc=new CRC32();crc.update(tag);crc.update(bytes);
        out.writeInt(bytes.length);out.write(tag);out.write(bytes);out.writeInt((int)crc.getValue());
    }
    private static byte[] png(boolean animated) throws Exception {
        ByteArrayOutputStream bytes=new ByteArrayOutputStream();DataOutputStream out=new DataOutputStream(bytes);
        out.write(new byte[]{(byte)137,80,78,71,13,10,26,10});
        chunk(out,"IHDR",ByteBuffer.allocate(13).putInt(1).putInt(1).put((byte)8).put((byte)6).array());
        if(animated)chunk(out,"acTL",ByteBuffer.allocate(8).putInt(2).putInt(0).array());
        chunk(out,"IEND",new byte[0]);return bytes.toByteArray();
    }
    @Test public void animationIsDetectedByChunksAndIncompletePngIsNotAssumedStatic() throws Exception {
        byte[] bytes=png(false);PngData still=PngData.extract(new ByteArrayInputStream(bytes));
        assertTrue(still.complete);assertFalse(still.animated);
        PngData animation=PngData.extract(new ByteArrayInputStream(png(true)));
        assertTrue(animation.complete);assertTrue(animation.animated);
        assertFalse(PngData.extract(new ByteArrayInputStream(Arrays.copyOf(bytes,bytes.length-12))).complete);
    }
}
