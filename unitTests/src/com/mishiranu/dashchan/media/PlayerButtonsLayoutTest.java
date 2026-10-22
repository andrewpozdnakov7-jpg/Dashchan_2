package com.mishiranu.dashchan.media;

import static org.junit.Assert.*;
import com.mishiranu.dashchan.media.PlayerButtonsLayout.Button;
import java.util.Arrays;
import java.util.ArrayList;
import java.util.HashSet;
import org.junit.Test;

public class PlayerButtonsLayoutTest {
	@Test public void speedVisibilityMatchesGlobalControlWithoutLosingLayoutOrHiddenChoices() {
		PlayerButtonsLayout layout = PlayerButtonsLayout.defaults();
		String saved = layout.encode();
		assertFalse(layout.isShown(Button.SPEED, false));
		assertTrue(layout.isShown(Button.SPEED, true));
		assertFalse(layout.toggle(Button.SPEED).isShown(Button.SPEED, true));
		assertTrue(layout.isShown(Button.SAVE, false));
		assertFalse(layout.toggle(Button.SAVE).isShown(Button.SAVE, true));
		assertEquals(saved, layout.encode());
	}
	@Test public void defaultsAndRoundTripKeepTheAgreedOrder() {
		PlayerButtonsLayout layout = PlayerButtonsLayout.defaults();
		assertEquals(4, layout.columns);
		assertEquals(Arrays.asList(Button.SPEED, Button.PIP, Button.TIKTOK, Button.SAVE), layout.getLeft());
		assertEquals(Arrays.asList(Button.MUTE, Button.FULLSCREEN), layout.getRight());
		assertEquals(layout.encode(), PlayerButtonsLayout.decode(layout.encode()).encode());
	}
	@Test public void extraRowsGrowUpwardsStartingAtTheLeft() {
		for (int i = 0; i < 4; i++) {
			assertEquals(0, PlayerButtonsLayout.rowFromBottom(i, 4));
			assertEquals(i, PlayerButtonsLayout.column(i, 4));
		}
		assertEquals(1, PlayerButtonsLayout.rowFromBottom(4, 4));
		assertEquals(0, PlayerButtonsLayout.column(4, 4));
		assertEquals(1, PlayerButtonsLayout.rowFromBottom(5, 4));
		assertEquals(1, PlayerButtonsLayout.column(5, 4));
	}
	@Test public void columnLimitIsConfigurableAndAdaptsWithoutShrinkingTargets() {
		for (int columns = 1; columns <= 6; columns++) {
			PlayerButtonsLayout layout = PlayerButtonsLayout.defaults().withColumns(columns);
			assertEquals(columns, PlayerButtonsLayout.decode(layout.encode()).columns);
			assertEquals(columns, PlayerButtonsLayout.effectiveColumns(columns, 1000, 48));
		}
		assertEquals(3, PlayerButtonsLayout.effectiveColumns(6, 150, 48));
		assertEquals(1, PlayerButtonsLayout.effectiveColumns(6, 20, 48));
	}
	@Test public void fullRightPanelCannotAcceptAThirdButtonButCanSwap() {
		PlayerButtonsLayout layout = PlayerButtonsLayout.defaults();
		assertSame(layout, layout.moveToEnd(Button.SAVE, true));
		PlayerButtonsLayout changed = layout.swap(Button.SAVE, Button.MUTE);
		assertEquals(Arrays.asList(Button.SPEED, Button.PIP, Button.TIKTOK, Button.MUTE), changed.getLeft());
		assertEquals(Arrays.asList(Button.SAVE, Button.FULLSCREEN), changed.getRight());
		assertEquals(PlayerButtonsLayout.defaults().encode(), layout.encode());
	}
	@Test public void movingBothRightButtonsLeftAllowsSixButtonsAndRestoringThem() {
		PlayerButtonsLayout layout = PlayerButtonsLayout.defaults().moveToEnd(Button.MUTE, false)
				.moveToEnd(Button.FULLSCREEN, false);
		assertEquals(6, layout.getLeft().size()); assertTrue(layout.getRight().isEmpty());
		assertEquals(6, new HashSet<>(layout.getLeft()).size());
		layout = layout.moveToEnd(Button.MUTE, true).moveToEnd(Button.FULLSCREEN, true);
		assertEquals(PlayerButtonsLayout.defaults().encode(), layout.encode());
	}
	@Test public void anySwapRetainsAllSixUniqueButtonsAndPanelCapacity() {
		for (Button a : Button.values()) for (Button b : Button.values()) {
			PlayerButtonsLayout layout = PlayerButtonsLayout.defaults().swap(a, b);
			ArrayList<Button> all = new ArrayList<>(layout.getLeft()); all.addAll(layout.getRight());
			assertEquals(6, all.size()); assertEquals(6, new HashSet<>(all).size());
			assertTrue(layout.getRight().size() <= 2);
			assertEquals(layout.encode(), PlayerButtonsLayout.decode(layout.encode()).encode());
		}
	}
	@Test public void corruptOrUnknownRecordsFallBackWithoutLosingAnyButton() {
		for (String value : new String[]{null, "", "2;4;SPEED;PIP", "1;4;SPEED,SPEED;MUTE,FULLSCREEN",
				"1;7;SPEED,PIP,TIKTOK,SAVE;MUTE,FULLSCREEN", "1;4;SPEED,PIP,TIKTOK;SAVE,MUTE,FULLSCREEN",
				"1;4;SPEED,PIP,TIKTOK,SAVE;UNKNOWN,FULLSCREEN"}) {
			assertEquals(PlayerButtonsLayout.defaults().encode(), PlayerButtonsLayout.decode(value).encode());
		}
	}
	@Test public void oldRecordsKeepOrderAndEnableEveryButton() {
		PlayerButtonsLayout layout = PlayerButtonsLayout.decode("1;3;SAVE,SPEED,TIKTOK,PIP;FULLSCREEN,MUTE");
		assertEquals(3, layout.columns);
		assertEquals(Arrays.asList(Button.SAVE, Button.SPEED, Button.TIKTOK, Button.PIP), layout.getLeft());
		for (Button button : Button.values()) assertTrue(layout.isEnabled(button));
		assertEquals(layout.getLeft(), layout.getVisibleLeft());
		assertEquals(layout.getRight(), layout.getVisibleRight());
	}
	@Test public void togglingDoesNotDeleteMoveOrForgetTheButton() {
		PlayerButtonsLayout original = PlayerButtonsLayout.defaults();
		for (Button button : Button.values()) {
			PlayerButtonsLayout hidden = original.toggle(button);
			assertFalse(hidden.isEnabled(button));
			assertEquals(original.getLeft(), hidden.getLeft()); assertEquals(original.getRight(), hidden.getRight());
			assertFalse(hidden.getVisibleLeft().contains(button)); assertFalse(hidden.getVisibleRight().contains(button));
			assertEquals(hidden.encode(), PlayerButtonsLayout.decode(hidden.encode()).encode());
			assertFalse(PlayerButtonsLayout.decode(hidden.encode()).isEnabled(button));
			assertEquals(original.encode(), hidden.toggle(button).encode());
		}
	}
	@Test public void hiddenStateFollowsIdentityAcrossSwapsMovesAndColumns() {
		PlayerButtonsLayout hidden = PlayerButtonsLayout.defaults().toggle(Button.SAVE).toggle(Button.MUTE);
		PlayerButtonsLayout changed = hidden.swap(Button.SAVE, Button.MUTE).withColumns(2).moveToEnd(Button.FULLSCREEN, false);
		assertFalse(changed.isEnabled(Button.SAVE)); assertFalse(changed.isEnabled(Button.MUTE));
		assertEquals(Arrays.asList(Button.SAVE), changed.getRight()); assertTrue(changed.getVisibleRight().isEmpty());
		assertEquals(Arrays.asList(Button.SPEED, Button.PIP, Button.TIKTOK, Button.FULLSCREEN), changed.getVisibleLeft());
		assertEquals(changed.encode(), PlayerButtonsLayout.decode(changed.encode()).encode());
	}
	@Test public void allButtonsCanBeHiddenAndRestoredWithoutLosingTheirPlaces() {
		PlayerButtonsLayout layout = PlayerButtonsLayout.defaults();
		for (Button button : Button.values()) layout = layout.toggle(button);
		assertTrue(layout.getVisibleLeft().isEmpty()); assertTrue(layout.getVisibleRight().isEmpty());
		assertEquals(4, layout.getLeft().size()); assertEquals(2, layout.getRight().size());
		layout = PlayerButtonsLayout.decode(layout.encode());
		for (Button button : Button.values()) {
			assertFalse(layout.isEnabled(button)); layout = layout.toggle(button);
		}
		assertEquals(PlayerButtonsLayout.defaults().encode(), layout.encode());
	}
	@Test public void duplicateUnknownOrMalformedDisabledStateIsRejected() {
		for (String flags : new String[]{"SAVE,SAVE", "UNKNOWN", "SAVE,", ",MUTE"}) {
			assertEquals(PlayerButtonsLayout.defaults().encode(), PlayerButtonsLayout.decode(
					"2;4;SPEED,PIP,TIKTOK,SAVE;MUTE,FULLSCREEN;" + flags).encode());
		}
	}
	@Test public void everyVisibilityCombinationRoundTripsAndCompactsOnlyRuntimeLists() {
		for (int mask = 0; mask < (1 << Button.values().length); mask++) {
			PlayerButtonsLayout layout = PlayerButtonsLayout.defaults();
			for (Button button : Button.values()) if ((mask & (1 << button.ordinal())) != 0) layout = layout.toggle(button);
			layout = PlayerButtonsLayout.decode(layout.encode());
			assertEquals(PlayerButtonsLayout.defaults().getLeft(), layout.getLeft());
			assertEquals(PlayerButtonsLayout.defaults().getRight(), layout.getRight());
			assertEquals(Button.values().length - Integer.bitCount(mask), layout.getVisibleLeft().size() + layout.getVisibleRight().size());
			for (Button button : Button.values()) assertEquals((mask & (1 << button.ordinal())) == 0, layout.isEnabled(button));
		}
	}
}
