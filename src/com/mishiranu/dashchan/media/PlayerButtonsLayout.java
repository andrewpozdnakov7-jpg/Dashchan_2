package com.mishiranu.dashchan.media;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;

/** Immutable, versioned placement. Positions start at the bottom left, never at the top row. */
public final class PlayerButtonsLayout {
	public enum Button {SPEED, PIP, TIKTOK, SAVE, MUTE, FULLSCREEN}
	public static final int DEFAULT_COLUMNS = 4;
	private final List<Button> left;
	private final List<Button> right;
	private final Set<Button> hidden;
	public final int columns;

	private PlayerButtonsLayout(List<Button> left, List<Button> right, int columns) {
		this(left, right, columns, Collections.emptySet());
	}
	private PlayerButtonsLayout(List<Button> left, List<Button> right, int columns, Set<Button> hidden) {
		this.left = Collections.unmodifiableList(new ArrayList<>(left));
		this.right = Collections.unmodifiableList(new ArrayList<>(right));
		EnumSet<Button> copy = EnumSet.noneOf(Button.class); copy.addAll(hidden);
		this.hidden = Collections.unmodifiableSet(copy);
		this.columns = Math.max(1, Math.min(6, columns));
	}

	public static PlayerButtonsLayout defaults() {
		return new PlayerButtonsLayout(Arrays.asList(Button.SPEED, Button.PIP, Button.TIKTOK, Button.SAVE),
				Arrays.asList(Button.MUTE, Button.FULLSCREEN), DEFAULT_COLUMNS);
	}

	public List<Button> getLeft() { return left; }
	public List<Button> getRight() { return right; }
	public boolean isEnabled(Button button) { return button != null && !hidden.contains(button); }
	/** Effective speed visibility is shared by the editor and the modern player. */
	public boolean isShown(Button button, boolean speedControl) {
		return isEnabled(button) && (button != Button.SPEED || speedControl);
	}
	public List<Button> getVisibleLeft() { return visible(left); }
	public List<Button> getVisibleRight() { return visible(right); }
	private List<Button> visible(List<Button> order) {
		ArrayList<Button> result = new ArrayList<>();
		for (Button button : order) if (isEnabled(button)) result.add(button);
		return Collections.unmodifiableList(result);
	}
	public PlayerButtonsLayout toggle(Button button) {
		if (button == null) return this;
		EnumSet<Button> next = EnumSet.noneOf(Button.class); next.addAll(hidden);
		if (!next.remove(button)) next.add(button);
		return new PlayerButtonsLayout(left, right, columns, next);
	}

	public static PlayerButtonsLayout decode(String value) {
		if (value != null && value.length() <= 300) {
			String[] parts = value.split(";", -1);
			if ((parts.length == 4 && "1".equals(parts[0])) || (parts.length == 5 && "2".equals(parts[0]))) {
				try {
					int columns = Integer.parseInt(parts[1]);
					List<Button> left = decodeButtons(parts[2]), right = decodeButtons(parts[3]);
					ArrayList<Button> all = new ArrayList<>(left); all.addAll(right);
					List<Button> disabled = parts.length == 5 ? decodeButtons(parts[4]) : Collections.emptyList();
					Set<Button> hidden = new HashSet<>(disabled);
					if (columns >= 1 && columns <= 6 && right.size() <= 2 && all.size() == Button.values().length
							&& new HashSet<>(all).size() == all.size() && hidden.size() == disabled.size()
							&& all.containsAll(hidden)) {
						return new PlayerButtonsLayout(left, right, columns, hidden);
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

	public String encode() {
		String placement = columns + ";" + encodeButtons(left) + ";" + encodeButtons(right);
		// Retain the original format when all buttons are enabled; version 1 migrates without any loss.
		return hidden.isEmpty() ? "1;" + placement : "2;" + placement + ";" + encodeButtons(new ArrayList<>(hidden));
	}
	private static String encodeButtons(List<Button> buttons) {
		StringBuilder result = new StringBuilder();
		for (Button button : buttons) { if (result.length() > 0) result.append(','); result.append(button.name()); }
		return result.toString();
	}

	public PlayerButtonsLayout withColumns(int columns) { return new PlayerButtonsLayout(left, right, columns, hidden); }

	public PlayerButtonsLayout swap(Button first, Button second) {
		if (first == null || second == null || first == second) return this;
		ArrayList<Button> a = new ArrayList<>(left), b = new ArrayList<>(right);
		for (List<Button> panel : Arrays.asList(a, b)) for (int i = 0; i < panel.size(); i++) {
			Button button = panel.get(i);
			if (button == first) panel.set(i, second); else if (button == second) panel.set(i, first);
		}
		return new PlayerButtonsLayout(a, b, columns, hidden);
	}

	public PlayerButtonsLayout moveToEnd(Button button, boolean toRight) {
		if (button == null || (toRight && right.size() >= 2) || (toRight ? right : left).contains(button)) return this;
		ArrayList<Button> a = new ArrayList<>(left), b = new ArrayList<>(right);
		if (!a.remove(button) && !b.remove(button)) return this;
		(toRight ? b : a).add(button);
		return new PlayerButtonsLayout(a, b, columns, hidden);
	}

	public static int effectiveColumns(int requested, int availableWidth, int cellWidth) {
		return Math.max(1, Math.min(Math.max(1, Math.min(6, requested)), availableWidth / Math.max(1, cellWidth)));
	}
	public static int rowFromBottom(int position, int columns) { return position / Math.max(1, columns); }
	public static int column(int position, int columns) { return position % Math.max(1, columns); }
}
