package com.mishiranu.dashchan.content.storage;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.os.Parcel;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import com.mishiranu.dashchan.content.storage.AutohideStorage.AutohideItem;
import com.mishiranu.dashchan.content.storage.AutohideStorage.HideMode;
import java.util.Arrays;
import java.util.Collections;
import org.json.JSONArray;
import org.junit.Test;
import org.junit.runner.RunWith;

@RunWith(AndroidJUnit4.class)
public class AutohideModeTest {
	@Test
	public void ruleOverridesDefaultAndHigherPriorityNeverShowsMore() {
		assertFalse(HideMode.COLLAPSE.removes(true));
		assertFalse(HideMode.INHERIT.removes(false));
		assertTrue(HideMode.INHERIT.removes(true));
		assertTrue(HideMode.REMOVE.removes(false));
		for (boolean removeByDefault : new boolean[] {false, true}) {
			for (HideMode first : HideMode.values()) {
				for (HideMode second : HideMode.values()) {
					HideMode strongest = Collections.max(Arrays.asList(first, second));
					assertEquals(first.removes(removeByDefault) || second.removes(removeByDefault),
							strongest.removes(removeByDefault));
				}
			}
		}
	}

	@Test
	public void savedRulesKeepOnlyExplicitModesInBackup() throws Exception {
		AutohideItem inherited = rule(AutohideItem.MatchMode.REGEX, HideMode.INHERIT);
		AutohideItem collapsed = rule(AutohideItem.MatchMode.REGEX, HideMode.COLLAPSE);
		AutohideItem removed = rule(AutohideItem.MatchMode.LITERAL, HideMode.REMOVE);
		JSONArray rules = AutohideStorage.getInstance().onSerialize(Arrays.asList(inherited,
				new AutohideItem(collapsed), new AutohideItem(removed))).getJSONArray("data");
		assertFalse(rules.getJSONObject(0).has("hideMode"));
		assertEquals("collapse", rules.getJSONObject(1).getString("hideMode"));
		assertEquals("remove", rules.getJSONObject(2).getString("hideMode"));
		assertEquals("literal", rules.getJSONObject(2).getString("matchMode"));
	}

	@Test
	public void parcelsKeepBothModes() {
		for (AutohideItem.MatchMode matchMode : AutohideItem.MatchMode.values()) {
			for (HideMode hideMode : HideMode.values()) {
				Parcel parcel = Parcel.obtain();
				try {
					rule(matchMode, hideMode).writeToParcel(parcel, 0);
					parcel.setDataPosition(0);
					AutohideItem restored = AutohideItem.CREATOR.createFromParcel(parcel);
					assertEquals(matchMode, restored.matchMode);
					assertEquals(hideMode, restored.hideMode);
				} finally {
					parcel.recycle();
				}
			}
		}
	}

	private static AutohideItem rule(AutohideItem.MatchMode matchMode, HideMode hideMode) {
		AutohideItem rule = new AutohideItem(null, null, null, true, true, false, true,
				true, false, false, matchMode, "test");
		rule.hideMode = hideMode;
		return rule;
	}
}
