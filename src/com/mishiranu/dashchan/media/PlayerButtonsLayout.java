package com.mishiranu.dashchan.media;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;

/** Immutable, versioned placement. Positions start at the bottom left, never at the top row. */
public final class PlayerButtonsLayout {
	public enum Button {SPEED, PIP, TIKTOK, SAVE, MUTE, FULLSCREEN}
	public static final int DEFAULT_COLUMNS = 4;
	private final List<Button> left;
	private final List<Button> right;
	public final int columns;

	private PlayerButtonsLayout(List<Button> left, List<Button> right, int columns) {
		this.left = Collections.unmodifiableList(new ArrayList<>(left));
		this.right = Collections.unmodifiableList(new ArrayList<>(right));
		this.columns = Math.max(1, Math.min(6, columns));
	}

	public static PlayerButtonsLayout defaults() {
		return new PlayerButtonsLayout(Arrays.asList(Button.SPEED, Button.PIP, Button.TIKTOK, Button.SAVE),
				Arrays.asList(Button.MUTE, Button.FULLSCREEN), DEFAULT_COLUMNS);
	}

	public List<Button> getLeft() { return left; }
	public List<Button> getRight() { return right; }

	public static PlayerButtonsLayout decode(String value) {
		if (value != null && value.length() <= 200) {
			String[] parts = value.split(";", -1);
			if (parts.length == 4 && "1".equals(parts[0])) {
				try {
					int columns = Integer.parseInt(parts[1]);
					List<Button> left = decodeButtons(parts[2]), right = decodeButtons(parts[3]);
					ArrayList<Button> all = new ArrayList<>(left); all.addAll(right);
					if (columns >= 1 && columns <= 6 && right.size() <= 2 && all.size() == Button.values().length
							&& new HashSet<>(all).size() == all.size()) {
						return new PlayerButtonsLayout(left, right, columns);
					}
				} catch (IllegalArgumentException e) { /* Invalid/older record: use the complete default layout. */ }
			}
		}
		return defaults();
	}

	private static List<Button> decodeButtons(String text) {
		ArrayList<Button> buttons = new ArrayList<>();
		if (!text.isEmpty()) for (String name : text.split(",", -1)) buttons.add(Button.valueOf(name));
		return buttons;
	}

	public String encode() { return "1;" + columns + ";" + encodeButtons(left) + ";" + encodeButtons(right); }
	private static String encodeButtons(List<Button> buttons) {
		StringBuilder result = new StringBuilder();
		for (Button button : buttons) { if (result.length() > 0) result.append(','); result.append(button.name()); }
		return result.toString();
	}

	public PlayerButtonsLayout withColumns(int columns) { return new PlayerButtonsLayout(left, right, columns); }

	public PlayerButtonsLayout swap(Button first, Button second) {
		if (first == null || second == null || first == second) return this;
		ArrayList<Button> a = new ArrayList<>(left), b = new ArrayList<>(right);
		for (List<Button> panel : Arrays.asList(a, b)) for (int i = 0; i < panel.size(); i++) {
			Button button = panel.get(i);
			if (button == first) panel.set(i, second); else if (button == second) panel.set(i, first);
		}
		return new PlayerButtonsLayout(a, b, columns);
	}

	public PlayerButtonsLayout moveToEnd(Button button, boolean toRight) {
		if (button == null || (toRight && right.size() >= 2) || (toRight ? right : left).contains(button)) return this;
		ArrayList<Button> a = new ArrayList<>(left), b = new ArrayList<>(right);
		if (!a.remove(button) && !b.remove(button)) return this;
		(toRight ? b : a).add(button);
		return new PlayerButtonsLayout(a, b, columns);
	}

	public static int effectiveColumns(int requested, int availableWidth, int cellWidth) {
		return Math.max(1, Math.min(Math.max(1, Math.min(6, requested)), availableWidth / Math.max(1, cellWidth)));
	}
	public static int rowFromBottom(int position, int columns) { return position / Math.max(1, columns); }
	public static int column(int position, int columns) { return position % Math.max(1, columns); }
}
