package com.mishiranu.dashchan.util;

import static org.junit.Assert.*;
import android.content.Intent;
import android.os.Bundle;
import android.os.Parcel;
import android.os.Parcelable;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import com.mishiranu.dashchan.ui.StackItem;
import com.mishiranu.dashchan.ui.navigator.PageItem;
import java.util.ArrayList;
import java.util.Arrays;
import org.junit.Test;
import org.junit.runner.RunWith;

/** Real deserialization with a cached, non-enclosed CREATOR reproduces the API 33 failure.
 * Run on API 30/33/34+, including an optimized target APK. No Activity or user data is changed. */
@RunWith(AndroidJUnit4.class)
public class AndroidUtilsParcelableTest {
	public static final class Value implements Parcelable {
		public static final Creator<Value> CREATOR = new AndroidUtilsDetachedCreator();
		final int number;
		public Value(int number) { this.number = number; }
		@Override public int describeContents() { return 0; }
		@Override public void writeToParcel(Parcel dest, int flags) { dest.writeInt(number); }
	}

	private static Bundle restored(Bundle original) {
		Parcel parcel = Parcel.obtain();
		try {
			original.writeToParcel(parcel, 0);
			parcel.setDataPosition(0);
			Bundle result = new Bundle();
			result.setClassLoader(Value.class.getClassLoader());
			result.readFromParcel(parcel);
			return result;
		} finally { parcel.recycle(); }
	}

	private static Intent restored(Intent original) {
		Parcel parcel = Parcel.obtain();
		try {
			original.writeToParcel(parcel, 0);
			parcel.setDataPosition(0);
			Intent result = Intent.CREATOR.createFromParcel(parcel);
			result.setExtrasClassLoader(Value.class.getClassLoader());
			return result;
		} finally { parcel.recycle(); }
	}

	private static ArrayList<Value> values() {
		return new ArrayList<>(Arrays.asList(new Value(17), null, new Value(29)));
	}

	private static void assertValues(ArrayList<Value> values) {
		assertNotNull(values);
		assertEquals(3, values.size());
		assertEquals(17, values.get(0).number);
		assertNull(values.get(1));
		assertEquals(29, values.get(2).number);
	}

	@Test public void bundleReadersSurviveRepeatedUnparcelingWithDetachedCreator() {
		assertNull(Value.CREATOR.getClass().getEnclosingClass());
		Bundle original = new Bundle();
		original.putParcelable("single", new Value(41));
		original.putParcelableArrayList("list", values());
		original.putParcelableArray("array", new Value[] {new Value(17), null, new Value(29)});
		for (int i = 0; i < 3; i++) {
			Bundle bundle = restored(original);
			assertEquals(41, AndroidUtils.getParcelable(bundle, "single", Value.class).number);
			assertValues(AndroidUtils.getParcelableArrayList(bundle, "list", Value.class));
			Parcelable[] array = AndroidUtils.getParcelableArray(bundle, "array", Value.class);
			assertNotNull(array); assertEquals(3, array.length);
			assertEquals(17, ((Value) array[0]).number); assertNull(array[1]);
			assertEquals(29, ((Value) array[2]).number);
		}
	}

	@Test public void intentReadersSurviveRepeatedUnparcelingWithDetachedCreator() {
		Intent original = new Intent();
		original.putExtra("single", new Value(41));
		original.putParcelableArrayListExtra("list", values());
		for (int i = 0; i < 3; i++) {
			Intent intent = restored(original);
			assertEquals(41, AndroidUtils.getParcelableExtra(intent, "single", Value.class).number);
			assertValues(AndroidUtils.getParcelableArrayListExtra(intent, "list", Value.class));
		}
	}

	@Test public void parcelReaderSurvivesRepeatedReadsAndPreservesNull() {
		for (int i = 0; i < 3; i++) {
			Parcel parcel = Parcel.obtain();
			try {
				parcel.writeParcelable(new Value(41), 0);
				parcel.writeParcelable(null, 0);
				parcel.setDataPosition(0);
				assertEquals(41, AndroidUtils.readParcelable(parcel, Value.class.getClassLoader(), Value.class).number);
				assertNull(AndroidUtils.readParcelable(parcel, Value.class.getClassLoader(), Value.class));
			} finally { parcel.recycle(); }
		}
	}

	@Test public void absentAndWrongTypeSingleValuesReturnNull() {
		Bundle bundle = new Bundle(); bundle.putParcelable("wrong", new Value(41));
		bundle = restored(bundle);
		assertNull(AndroidUtils.getParcelable(bundle, "missing", Value.class));
		assertNull(AndroidUtils.getParcelable(bundle, "wrong", PageItem.class));
		assertNull(AndroidUtils.getParcelableArrayList(bundle, "missing", Value.class));
		assertNull(AndroidUtils.getParcelableArray(bundle, "missing", Value.class));
		Intent intent = new Intent(); intent.putExtra("wrong", new Value(41));
		intent = restored(intent);
		assertNull(AndroidUtils.getParcelableExtra(intent, "missing", Value.class));
		assertNull(AndroidUtils.getParcelableExtra(intent, "wrong", PageItem.class));
		assertNull(AndroidUtils.getParcelableArrayListExtra(intent, "missing", Value.class));
	}

	@Test public void navigationStateRetainsCurrentPageAndStackAcrossUnparceling() {
		Bundle arguments = new Bundle(); arguments.putString("threadNumber", "123");
		StackItem stack = new StackItem("com.mishiranu.dashchan.ui.navigator.PageFragment", arguments, null);
		PageItem current = new PageItem();
		current.createdRealtime = 42L; current.threadTitle = "title"; current.allowReturn = true;
		Bundle original = new Bundle(); original.putParcelable("current", current);
		original.putParcelableArrayList("stack", new ArrayList<>(Arrays.asList(stack)));
		for (int i = 0; i < 3; i++) {
			Bundle bundle = restored(original);
			PageItem page = AndroidUtils.getParcelable(bundle, "current", PageItem.class);
			assertNotNull(page); assertEquals(42L, page.createdRealtime);
			assertEquals("title", page.threadTitle); assertTrue(page.allowReturn);
			ArrayList<StackItem> items = AndroidUtils.getParcelableArrayList(bundle, "stack", StackItem.class);
			assertNotNull(items); assertEquals(1, items.size());
			assertEquals(stack.className, items.get(0).className);
			assertEquals("123", items.get(0).arguments.getString("threadNumber"));
		}
	}
}

/** Intentionally top-level: Android 13's typed cached-reader must not assume an enclosing class. */
final class AndroidUtilsDetachedCreator implements Parcelable.Creator<AndroidUtilsParcelableTest.Value> {
	@Override public AndroidUtilsParcelableTest.Value createFromParcel(Parcel source) {
		return new AndroidUtilsParcelableTest.Value(source.readInt());
	}
	@Override public AndroidUtilsParcelableTest.Value[] newArray(int size) {
		return new AndroidUtilsParcelableTest.Value[size];
	}
}
