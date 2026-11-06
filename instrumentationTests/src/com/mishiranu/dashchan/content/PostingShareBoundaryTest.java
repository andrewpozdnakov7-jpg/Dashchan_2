package com.mishiranu.dashchan.content;

import static org.junit.Assert.*;
import android.content.ClipData;
import android.content.Intent;
import android.net.Uri;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import com.mishiranu.dashchan.BuildConfig;
import java.lang.reflect.Method;
import java.util.ArrayList;
import org.junit.Test;
import org.junit.runner.RunWith;

@RunWith(AndroidJUnit4.class)
public class PostingShareBoundaryTest {
	@SuppressWarnings("unchecked")
	private static ArrayList<Uri> streams(Intent intent) throws Exception {
		Method method = PostingShareActivity.class.getDeclaredMethod("collectStreamUris", Intent.class);
		method.setAccessible(true);
		return (ArrayList<Uri>) method.invoke(null, intent);
	}
	@Test public void hiddenExtraStreamAndOwnProviderNeverBecomeAttachments() throws Exception {
		Uri privateFile = Uri.parse("content://" + BuildConfig.FILE_PROVIDER_AUTHORITY + "/updates/private.apk");
		Intent hidden = new Intent(Intent.ACTION_SEND).putExtra(Intent.EXTRA_STREAM, privateFile);
		hidden.setClipData(ClipData.newPlainText("unrelated", "text"));
		assertTrue(streams(hidden).isEmpty());
		Intent own = new Intent(Intent.ACTION_SEND);
		own.setClipData(ClipData.newRawUri("private", privateFile));
		assertTrue(streams(own).isEmpty());
		Uri qualified = Uri.parse("content://0@" + BuildConfig.FILE_PROVIDER_AUTHORITY + "/updates/private.apk");
		Intent qualifiedShare = new Intent(Intent.ACTION_SEND);
		qualifiedShare.setClipData(ClipData.newRawUri("private", qualified));
		assertTrue(streams(qualifiedShare).isEmpty());
	}
	@Test public void platformUriListKeepsOrderAndDeduplicatesData() throws Exception {
		Uri first = Uri.parse("content://share-test.invalid/first");
		Uri second = Uri.parse("content://share-test.invalid/second");
		ClipData clip = ClipData.newRawUri("shared", first);
		clip.addItem(new ClipData.Item(second));
		Intent shared = new Intent(Intent.ACTION_SEND_MULTIPLE).setData(first);
		shared.setClipData(clip);
		assertEquals(java.util.Arrays.asList(first, second), streams(shared));
	}
}
