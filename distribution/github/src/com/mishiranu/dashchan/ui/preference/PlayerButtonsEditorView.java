package com.mishiranu.dashchan.ui.preference;

import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Canvas;
import android.graphics.DashPathEffect;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.os.Bundle;
import android.text.Layout;
import android.text.StaticLayout;
import android.view.DragEvent;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.accessibility.AccessibilityNodeInfo;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import androidx.core.graphics.ColorUtils;
import androidx.core.view.accessibility.AccessibilityNodeInfoCompat;
import androidx.recyclerview.widget.RecyclerView;
import com.mishiranu.dashchan.R;
import com.mishiranu.dashchan.content.Preferences;
import com.mishiranu.dashchan.media.PlayerButtonsLayout;
import com.mishiranu.dashchan.media.PlayerButtonsLayout.Button;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.function.Supplier;
import java.util.function.Consumer;

/** Stable, local preview; none of these buttons execute player actions. */
final class PlayerButtonsEditorView extends FrameLayout {
	final PlayerButtonsColors colors;
	final Board board;
	final PlayerButtonsMotion motion;
	private final LinearLayout content;
	final LinearLayout previewCard;
	final List<View> furniture = new ArrayList<>();
	RectF animatedCard;
	private final Paint cardPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
	private final ReservedStatus status;
	private final TextView columnsValue, effectiveNote;
	private final View columnsRow, reset;
	private final Supplier<ViewGroup> root;
	private final Supplier<PlayerButtonsLayout> read;
	private final Consumer<PlayerButtonsLayout> write;
	private boolean changingGeometry;

	PlayerButtonsEditorView(Context context, Supplier<ViewGroup> root, Runnable chooseColumns) {
		this(context, root, chooseColumns, Preferences::getVideoButtonsLayout, Preferences::setVideoButtonsLayout);
	}
	PlayerButtonsEditorView(Context context, Supplier<ViewGroup> root, Runnable chooseColumns, Runnable showHelp) {
		this(context, root, chooseColumns, Preferences::getVideoButtonsLayout, Preferences::setVideoButtonsLayout, showHelp);
	}
	/** Store seam allows behavioral UI fixtures without editing the installed user's preferences. */
	PlayerButtonsEditorView(Context context, Supplier<ViewGroup> root, Runnable chooseColumns,
			Supplier<PlayerButtonsLayout> read, Consumer<PlayerButtonsLayout> write) {
		this(context, root, chooseColumns, read, write, () -> {});
	}
	private PlayerButtonsEditorView(Context context, Supplier<ViewGroup> root, Runnable chooseColumns,
			Supplier<PlayerButtonsLayout> read, Consumer<PlayerButtonsLayout> write, Runnable showHelp) {
		super(context);
		this.root = root;
		this.read = read; this.write = write;
		colors = new PlayerButtonsColors(context);
		setBackgroundColor(colors.background);
		setForceDarkAllowed(false);
		setClipChildren(false);
		setClipToPadding(false);
		content = new LinearLayout(context);
		content.setOrientation(LinearLayout.VERTICAL);
		content.setClipChildren(false);
		content.setClipToPadding(false);
		content.setPadding(dp(16), dp(16), dp(16), dp(16));
		addView(content, new FrameLayout.LayoutParams(-1, -2, Gravity.TOP | Gravity.CENTER_HORIZONTAL));
		TextView hint = text(14, colors.secondary, false);
		hint.setText(R.string.player_buttons_instruction); hint.setLineHeight(sp(20));
		add(content, hint, 0);
		LinearLayout section = new LinearLayout(context);
		section.setGravity(Gravity.CENTER_VERTICAL); section.setMinimumHeight(dp(48));
		TextView sectionTitle = text(18, colors.ink, true); sectionTitle.setText(R.string.player_buttons_preview_title);
		section.addView(sectionTitle, new LinearLayout.LayoutParams(0, -2, 1));
		ImageView help = new ImageView(context);
		help.setImageResource(R.drawable.player_buttons_info); help.setImageTintList(ColorStateList.valueOf(colors.primary));
		help.setPadding(dp(12), dp(12), dp(12), dp(12));
		help.setContentDescription(getResources().getString(R.string.player_buttons_help_title));
		help.setFocusable(true); help.setBackground(colors.ripple(colors.background, dp(24)));
		help.setOnClickListener(v -> { if (!isDragging()) { finishVisuals(); showHelp.run(); } });
		section.addView(help, new LinearLayout.LayoutParams(dp(48), dp(48)));
		add(content, section, 20);
		LinearLayout card = new LinearLayout(context);
		previewCard = card;
		card.setOrientation(LinearLayout.VERTICAL);
		card.setClipChildren(false);
		card.setClipToPadding(false);
		// Reclaim horizontal space for four full-size cells next to the right panel.
		card.setPadding(dp(8), dp(8), dp(8), dp(8));
		add(content, card, 8);
		board = new Board(context);
		add(card, board, 0);
		furniture.add(board.leftLabel);
		furniture.add(board.rightLabel);
		status = new ReservedStatus(context);
		add(content, status, 8);
		furniture.add(status);
		effectiveNote = text(12, colors.secondary, false);
		effectiveNote.setLineHeight(sp(16));
		add(content, effectiveNote, 8);
		furniture.add(effectiveNote);
		motion = new PlayerButtonsMotion(this);
		for (Button button : Button.values()) installButton(board.buttons.get(button), button);

		LinearLayout row = new LinearLayout(context);
		row.setGravity(Gravity.CENTER_VERTICAL);
		row.setPadding(0, dp(12), 0, dp(12));
		row.setMinimumHeight(dp(72));
		row.setBackground(colors.ripple(colors.background, dp(12)));
		LinearLayout columnLabels = new LinearLayout(context);
		columnLabels.setOrientation(LinearLayout.VERTICAL);
		columnLabels.setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS);
		addText(columnLabels, R.string.player_buttons_per_row, 16, colors.ink, true, 0);
		addText(columnLabels, R.string.player_buttons_left_label, 14, colors.secondary, false, 4);
		row.addView(columnLabels, new LinearLayout.LayoutParams(0, -2, 1));
		columnsValue = text(18, colors.primary, true);
		columnsValue.setPadding(dp(12), dp(4), dp(12), dp(4));
		columnsValue.setMinWidth(dp(40)); columnsValue.setMinHeight(dp(32));
		columnsValue.setGravity(Gravity.CENTER); columnsValue.setBackground(PlayerButtonsColors.shape(colors.active, dp(16)));
		columnsValue.setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
		row.addView(columnsValue, new LinearLayout.LayoutParams(-2, -2));
		ImageView chevron = new ImageView(context);
		chevron.setImageResource(R.drawable.player_buttons_chevron); chevron.setImageTintList(ColorStateList.valueOf(colors.secondary));
		chevron.setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
		LinearLayout.LayoutParams chevronParams = new LinearLayout.LayoutParams(dp(24), dp(24)); chevronParams.setMarginStart(dp(8));
		row.addView(chevron, chevronParams);
		row.setFocusable(true);
		row.setOnClickListener(v -> { if (!isDragging()) { finishVisuals(); chooseColumns.run(); } });
		row.setAccessibilityDelegate(new View.AccessibilityDelegate() {
			@Override public void onInitializeAccessibilityNodeInfo(View host, AccessibilityNodeInfo info) {
				super.onInitializeAccessibilityNodeInfo(host, info);
				info.setClassName("android.widget.Button");
			}
		});
		add(content, row, 24);
		furniture.add(row);
		columnsRow = row;
		View divider = new View(context); divider.setBackgroundColor(colors.divider);
		content.addView(divider, new LinearLayout.LayoutParams(-1, dp(1))); furniture.add(divider);
		TextView saved = text(12, colors.secondary, false); saved.setText(R.string.player_buttons_saved);
		saved.setMinHeight(dp(48)); saved.setGravity(Gravity.CENTER_VERTICAL);
		android.graphics.drawable.Drawable savedIcon = context.getDrawable(R.drawable.ic_editor_done);
		if (savedIcon != null) { savedIcon.setBounds(0, 0, dp(18), dp(18)); saved.setCompoundDrawablesRelative(savedIcon, null, null, null); }
		saved.setCompoundDrawableTintList(ColorStateList.valueOf(colors.primary)); saved.setCompoundDrawablePadding(dp(12));
		add(content, saved, 0); furniture.add(saved);
		TextView resetText = text(14, PlayerButtonsColors.contrast(colors.primary, colors.background, 4.5), true);
		resetText.setText(R.string.player_buttons_reset);
		android.graphics.drawable.Drawable resetIcon = context.getDrawable(android.R.drawable.ic_menu_revert);
		if (resetIcon != null) {
			resetIcon.setBounds(0, 0, dp(24), dp(24));
			resetText.setCompoundDrawablesRelative(resetIcon, null, null, null);
		}
		resetText.setCompoundDrawableTintList(ColorStateList.valueOf(resetText.getCurrentTextColor()));
		resetText.setCompoundDrawablePadding(dp(12));
		resetText.setGravity(Gravity.CENTER_VERTICAL);
		resetText.setPadding(0, dp(8), 0, dp(8));
		resetText.setMinHeight(dp(48));
		resetText.setBackground(colors.ripple(colors.background, dp(24)));
		resetText.setOnClickListener(v -> { if (!isDragging()) applyCommand(PlayerButtonsLayout.defaults(), null); });
		add(content, resetText, 0);
		furniture.add(resetText);
		reset = resetText;
		reload();
	}
	int dp(float value) { return Math.round(value * getResources().getDisplayMetrics().density); }
	private int sp(float value) { return Math.round(android.util.TypedValue.applyDimension(
			android.util.TypedValue.COMPLEX_UNIT_SP, value, getResources().getDisplayMetrics())); }
	ViewGroup overlayRoot() { return root.get(); }
	RecyclerView recycler() {
		for (android.view.ViewParent p = getParent(); p != null; p = p.getParent()) {
			if (p instanceof RecyclerView) return (RecyclerView) p;
		}
		return null;
	}
	boolean isDragging() { return motion.isDragging(); }
	PlayerButtonsLayout readLayout() { return read.get(); }
	void saveLayout(PlayerButtonsLayout layout) { write.accept(layout); }
	void finishVisuals() { motion.cleanup(true); }
	void reload() {
		board.model = readLayout();
		columnsValue.setText(String.valueOf(board.model.columns));
		columnsRow.setContentDescription(getResources().getString(R.string.player_buttons_per_row)
				+ ", " + board.model.columns);
		board.rightSubtitle.setText(getResources().getString(R.string.player_buttons_count, board.model.getRight().size()));
		board.rightLabel.setContentDescription(board.model.getRight().isEmpty()
				? getResources().getString(R.string.player_buttons_empty_right) : null);
		refreshButtonStates();
		board.requestLayout();
		board.invalidate();
	}
	void refreshButtonStates() {
		for (Button button : Button.values()) {
			View cell = board.buttons.get(button);
			boolean enabled = board.model.isEnabled(button);
			int color = enabled ? colors.ink : colors.disabled;
			View icon = ((FrameLayout) cell).getChildAt(0);
			if (icon instanceof TextView) ((TextView) icon).setTextColor(color);
			else ((ImageView) icon).setImageTintList(ColorStateList.valueOf(color));
			// Hidden in the player, not disabled in this editor: it must remain clickable and draggable.
			cell.setSelected(enabled);
			cell.setStateDescription(getResources().getString(enabled ? R.string.player_buttons_shown : R.string.player_buttons_hidden));
		}
	}
	private void toggleButton(Button button) {
		if (isDragging()) return;
		finishVisuals();
		saveLayout(readLayout().toggle(button));
		reload();
	}
	void applyCommand(PlayerButtonsLayout next, Button focus) {
		if (isDragging()) return;
		motion.command(next, focus);
	}
	void setDragging(boolean dragging) { columnsRow.setEnabled(!dragging); reset.setEnabled(!dragging); }
	void normalStatus() {
		String caption = getResources().getString(R.string.player_buttons_note);
		if (board.model.getLeft().size() > board.effectiveColumns) caption += "\n" + getResources().getString(R.string.player_buttons_order);
		status.setText(caption);
	}
	void sayStatus(String value) { status.setText(value); }
	String label(Button button) { return getResources().getString(PlayerButtonsFragment.title(button)); }
	void updateEffective() {
		int n = board.model.columns, m = board.effectiveColumns;
		String value = m < n ? getResources().getString(R.string.player_buttons_effective, n, m) : "";
		if (!value.contentEquals(effectiveNote.getText())) effectiveNote.setText(value);
		effectiveNote.setVisibility(m < n ? VISIBLE : GONE);
		if (!isDragging()) normalStatus();
	}
	@Override protected void onMeasure(int widthSpec, int heightSpec) {
		int width = MeasureSpec.getSize(widthSpec);
		int contentWidth = Math.min(width, dp(560)), padding = width <= dp(380) ? dp(16) : dp(24);
		content.getLayoutParams().width = contentWidth;
		content.setPadding(padding, dp(16), padding, dp(16));
		super.onMeasure(widthSpec, heightSpec);
	}
	@Override protected void onSizeChanged(int w, int h, int oldW, int oldH) {
		super.onSizeChanged(w, h, oldW, oldH);
		if (oldW > 0 && oldW != w && !changingGeometry) {
			changingGeometry = true;
			finishVisuals();
			changingGeometry = false;
		}
	}
	@Override protected void onDetachedFromWindow() { finishVisuals(); super.onDetachedFromWindow(); }
	@Override protected void dispatchDraw(Canvas canvas) {
		RectF rect = animatedCard;
		if (rect == null) {
			rect = new RectF(previewCard.getLeft() + content.getLeft(), previewCard.getTop() + content.getTop(),
					previewCard.getRight() + content.getLeft(), previewCard.getBottom() + content.getTop());
		}
		cardPaint.setColor(colors.card);
		canvas.drawRoundRect(rect, dp(20), dp(20), cardPaint);
		super.dispatchDraw(canvas);
	}
	@Override public boolean dispatchDragEvent(DragEvent event) {
		// A single stable router owns the session, including when the finger is over a child.
		// Hit testing in Board gives button targets priority over the containing free zone.
		if (motion.owns(event.getLocalState())) return motion.dispatch(this, event);
		return super.dispatchDragEvent(event);
	}
	private TextView text(int size, int color, boolean medium) {
		TextView result = new TextView(getContext());
		result.setTextSize(size); result.setTextColor(color);
		result.setHyphenationFrequency(Layout.HYPHENATION_FREQUENCY_NORMAL);
		if (medium) result.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
		return result;
	}
	private void addText(LinearLayout parent, int res, int size, int color, boolean medium, int top) {
		TextView text = text(size, color, medium); text.setText(res); add(parent, text, top);
	}
	private void add(LinearLayout parent, View child, int top) {
		LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2); lp.topMargin = dp(top);
		parent.addView(child, lp);
	}
	private void installButton(View view, Button button) {
		view.setOnTouchListener(new View.OnTouchListener() {
			float x, y;
			@Override public boolean onTouch(View v, MotionEvent event) {
				if (event.getActionMasked() == MotionEvent.ACTION_DOWN
						|| event.getActionMasked() == MotionEvent.ACTION_MOVE) { x = event.getX(); y = event.getY(); }
				motion.setGrab(button, x, y);
				return false;
			}
		});
		view.setOnClickListener(v -> toggleButton(button));
		// Consume a long press even if native dragging cannot start; it must never turn into a toggle.
		view.setOnLongClickListener(v -> { motion.start(button); return true; });
		view.setAccessibilityDelegate(new View.AccessibilityDelegate() {
			final int[] swaps = {R.id.player_buttons_swap_speed, R.id.player_buttons_swap_pip,
					R.id.player_buttons_swap_tiktok, R.id.player_buttons_swap_save,
					R.id.player_buttons_swap_mute, R.id.player_buttons_swap_fullscreen};
			@Override public void onInitializeAccessibilityNodeInfo(View host, AccessibilityNodeInfo info) {
				super.onInitializeAccessibilityNodeInfo(host, info);
				info.setClassName("android.widget.ToggleButton");
				PlayerButtonsLayout layout = readLayout();
				info.setCheckable(true);
				AccessibilityNodeInfoCompat.wrap(info).setChecked(layout.isEnabled(button)
						? AccessibilityNodeInfoCompat.CHECKED_STATE_TRUE : AccessibilityNodeInfoCompat.CHECKED_STATE_FALSE);
				boolean right = layout.getRight().contains(button);
				if (right || layout.getRight().size() < 2) info.addAction(new AccessibilityNodeInfo.AccessibilityAction(
						R.id.player_buttons_move, getResources().getString(right
						? R.string.player_buttons_move_left : R.string.player_buttons_move_right)));
				for (Button other : Button.values()) if (other != button) info.addAction(
						new AccessibilityNodeInfo.AccessibilityAction(swaps[other.ordinal()],
								getResources().getString(R.string.player_buttons_swap, label(other))));
			}
			@Override public boolean performAccessibilityAction(View host, int action, Bundle arguments) {
				if (isAttachedToWindow() && !isDragging()) {
					PlayerButtonsLayout layout = readLayout();
					if (action == R.id.player_buttons_move) {
						applyCommand(layout.moveToEnd(button, !layout.getRight().contains(button)), button); return true;
					}
					for (Button other : Button.values()) if (other != button && action == swaps[other.ordinal()]) {
						applyCommand(layout.swap(button, other), button); return true;
					}
				}
				return super.performAccessibilityAction(host, action, arguments);
			}
		});
	}

	/** Measured status capacity prevents hover messages from moving the drop targets. */
	private final class ReservedStatus extends androidx.appcompat.widget.AppCompatTextView {
		final List<String> variants = new ArrayList<>();
		int cachedWidth = -1, cachedHeight;
		ReservedStatus(Context context) {
			super(context); setTextSize(12); setTextColor(colors.secondary); setLineHeight(sp(16));
			variants.add(getResources().getString(R.string.player_buttons_note));
			variants.add(getResources().getString(R.string.player_buttons_note) + "\n" + getResources().getString(R.string.player_buttons_order));
			variants.add(getResources().getString(R.string.player_buttons_full));
			variants.add(getResources().getString(R.string.player_buttons_noop));
			for (Button a : Button.values()) {
				for (int res : new int[] {R.string.player_buttons_drag_start, R.string.player_buttons_drag_left,
						R.string.player_buttons_drag_right}) variants.add(getResources().getString(res, label(a)));
				for (Button b : Button.values()) if (a != b) variants.add(getResources().getString(
						R.string.player_buttons_drag_swap, label(a), label(b)));
			}
			setText(R.string.player_buttons_note);
		}
		@Override protected void onMeasure(int widthSpec, int heightSpec) {
			int width = Math.max(1, MeasureSpec.getSize(widthSpec) - getPaddingLeft() - getPaddingRight());
			if (cachedWidth != width) {
				cachedWidth = width; cachedHeight = 0;
				for (String value : variants) cachedHeight = Math.max(cachedHeight, StaticLayout.Builder.obtain(value, 0,
						value.length(), getPaint(), width).setIncludePad(getIncludeFontPadding())
						.setLineSpacing(getLineSpacingExtra(), getLineSpacingMultiplier()).build().getHeight());
				setMinHeight(cachedHeight + getPaddingTop() + getPaddingBottom());
			}
			super.onMeasure(widthSpec, heightSpec);
		}
	}

	/** Labels and real cells are persistent; only measured bounds and the two surfaces change. */
	final class Board extends ViewGroup {
		final EnumMap<Button, View> buttons = new EnumMap<>(Button.class);
		final RectF leftZone = new RectF(), rightZone = new RectF(), leftPanel = new RectF(), rightPanel = new RectF();
		final RectF leftSurface = new RectF(), rightSurface = new RectF();
		final LinearLayout leftLabel, rightLabel;
		final TextView rightSubtitle;
		final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
		PlayerButtonsLayout model = readLayout();
		int effectiveColumns = 1, cellWidth, cellHeight, labelHeight, leftWidth, rightWidth;
		boolean stacked, hidePanels;
		RectF highlight;
		boolean invalidHighlight;
		float highlightAlpha;
		Board(Context context) {
			super(context); setWillNotDraw(false); setClipChildren(false); setLayoutDirection(LAYOUT_DIRECTION_LTR);
			leftLabel = new LinearLayout(context); leftLabel.setOrientation(LinearLayout.VERTICAL);
			leftLabel.setPadding(dp(8), dp(8), dp(8), 0);
			addText(leftLabel, R.string.player_buttons_zone_left, 14, colors.ink, true, 0);
			rightLabel = new LinearLayout(context); rightLabel.setOrientation(LinearLayout.VERTICAL);
			rightLabel.setPadding(dp(8), dp(8), dp(8), 0);
			rightSubtitle = text(14, colors.ink, true);
			add(rightLabel, rightSubtitle, 0);
			addView(leftLabel); addView(rightLabel);
			for (Button button : Button.values()) {
				FrameLayout cell = new FrameLayout(context);
				cell.setBackground(colors.ripple(colors.cell, dp(12)));
				if (button == Button.SPEED) {
					TextView speed = text(14, colors.ink, true); speed.setText("×1");
					speed.setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
					cell.addView(speed, new FrameLayout.LayoutParams(-2, -2, Gravity.CENTER));
				} else {
					ImageView icon = new ImageView(context);
					icon.setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
					int drawable;
					switch (button) {
						case PIP: drawable = R.drawable.ic_picture_in_picture; break;
						case TIKTOK: drawable = R.drawable.ic_tiktok_mode; break;
						case SAVE: drawable = R.drawable.ic_save; break;
						case MUTE: drawable = R.drawable.ic_volume_off; break;
						case FULLSCREEN: drawable = R.drawable.ic_fullscreen; break;
						default: throw new IllegalArgumentException();
					}
					icon.setImageResource(drawable); icon.setImageTintList(ColorStateList.valueOf(colors.ink));
					cell.addView(icon, new FrameLayout.LayoutParams(dp(24), dp(24), Gravity.CENTER));
				}
				cell.setContentDescription(label(button)); cell.setTooltipText(label(button));
				cell.setFocusable(true); cell.setClickable(false);
				buttons.put(button, cell); addView(cell);
			}
		}
		@Override protected void onMeasure(int widthSpec, int heightSpec) {
			int width = MeasureSpec.getSize(widthSpec), natural = MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED);
			View speed = ((FrameLayout) buttons.get(Button.SPEED)).getChildAt(0);
			speed.measure(natural, natural);
			cellWidth = Math.max(dp(48), speed.getMeasuredWidth());
			cellHeight = Math.max(dp(48), speed.getMeasuredHeight());
			rightLabel.measure(natural, natural);
			rightWidth = Math.max(dp(96), Math.max(rightLabel.getMeasuredWidth(),
					Math.max(1, model.getRight().size()) * cellWidth + dp(8)));
			// Prefer the requested row width over squeezing cells into a narrow left zone.
			// When both panels cannot fit side by side, move the right panel below instead.
			PlayerButtonsPreviewGeometry geometry = new PlayerButtonsPreviewGeometry(
					width, model.columns, cellWidth, rightWidth, dp(8), dp(8));
			stacked = geometry.stacked; leftWidth = geometry.leftWidth; rightWidth = geometry.rightWidth;
			leftLabel.measure(MeasureSpec.makeMeasureSpec(leftWidth, MeasureSpec.EXACTLY), natural);
			rightLabel.measure(MeasureSpec.makeMeasureSpec(rightWidth, MeasureSpec.EXACTLY), natural);
			labelHeight = Math.max(leftLabel.getMeasuredHeight(), rightLabel.getMeasuredHeight());
			effectiveColumns = geometry.columns;
			int leftH = ((model.getLeft().size() + effectiveColumns - 1) / effectiveColumns) * cellHeight + dp(8);
			int rightH = model.getRight().isEmpty() ? dp(56) : cellHeight + dp(8);
			int top = labelHeight + dp(8), band = dp(48);
			if (!stacked) {
				int bottom = top + band + Math.max(leftH, rightH);
				leftZone.set(0, top, leftWidth, bottom); rightZone.set(leftWidth + dp(8), top, width, bottom);
			} else {
				leftZone.set(0, top, width, top + band + leftH);
				float rightTop = leftZone.bottom + dp(16) + labelHeight + dp(8);
				rightZone.set(0, rightTop, width, rightTop + band + rightH);
			}
			leftPanel.set(leftZone.left, leftZone.bottom - leftH,
					leftZone.left + Math.min(model.getLeft().size(), effectiveColumns) * cellWidth + dp(8), leftZone.bottom);
			int rightW = model.getRight().isEmpty() ? dp(56) : model.getRight().size() * cellWidth + dp(8);
			rightPanel.set(rightZone.right - rightW, rightZone.bottom - rightH, rightZone.right, rightZone.bottom);
			// Decoration is separate from the unchanged cell layout and free-drop hit zones.
			leftSurface.set(leftZone.left, 0, leftZone.right, leftZone.bottom);
			rightSurface.set(rightZone.left, stacked ? leftZone.bottom + dp(16) : 0, rightZone.right, rightZone.bottom);
			for (View button : buttons.values()) button.measure(MeasureSpec.makeMeasureSpec(cellWidth, MeasureSpec.EXACTLY),
					MeasureSpec.makeMeasureSpec(cellHeight, MeasureSpec.EXACTLY));
			setMeasuredDimension(width, (int) Math.ceil(rightZone.bottom));
		}
		@Override protected void onLayout(boolean changed, int l, int t, int r, int b) {
			leftLabel.layout(0, 0, leftWidth, leftLabel.getMeasuredHeight());
			int x = stacked ? 0 : leftWidth + dp(8);
			int y = stacked ? (int) leftZone.bottom + dp(16) : 0;
			rightLabel.layout(x, y, x + rightWidth, y + rightLabel.getMeasuredHeight());
			layoutButtons(model.getLeft(), leftPanel, effectiveColumns);
			layoutButtons(model.getRight(), rightPanel, 2);
			updateEffective();
		}
		private void layoutButtons(List<Button> list, RectF panel, int columns) {
			for (int i = 0; i < list.size(); i++) {
				int x = (int) panel.left + dp(4) + PlayerButtonsLayout.column(i, columns) * cellWidth;
				int y = (int) panel.bottom - dp(4) - (1 + PlayerButtonsLayout.rowFromBottom(i, columns)) * cellHeight;
				buttons.get(list.get(i)).layout(x, y, x + cellWidth, y + cellHeight);
			}
		}
		@Override protected LayoutParams generateDefaultLayoutParams() { return new LayoutParams(-2, -2); }
		@Override protected void onDraw(Canvas canvas) {
			super.onDraw(canvas);
			// Highlight the free area above its surface, but below the opaque button children.
			if (!hidePanels) {
				drawPanel(canvas, leftSurface, false); drawPanel(canvas, rightSurface, model.getRight().isEmpty());
			}
			if (highlight != null) drawHighlight(canvas);
		}
		void drawPanel(Canvas canvas, RectF rect, boolean empty) {
			paint.setStyle(Paint.Style.FILL); paint.setColor(colors.panel);
			canvas.drawRoundRect(rect, dp(16), dp(16), paint);
			if (empty) {
				paint.setStyle(Paint.Style.STROKE); paint.setStrokeWidth(dp(1)); paint.setColor(colors.outline);
				paint.setPathEffect(new DashPathEffect(new float[] {dp(4), dp(4)}, 0));
				RectF outline = new RectF(rect); outline.inset(dp(1), dp(1));
				canvas.drawRoundRect(outline, dp(16), dp(16), paint); paint.setPathEffect(null);
				canvas.drawLine(rect.centerX() - dp(6), rect.centerY(), rect.centerX() + dp(6), rect.centerY(), paint);
				canvas.drawLine(rect.centerX(), rect.centerY() - dp(6), rect.centerX(), rect.centerY() + dp(6), paint);
				paint.setStyle(Paint.Style.FILL);
			}
		}
		private void drawHighlight(Canvas canvas) {
			int color = invalidHighlight ? colors.error : colors.primary;
			paint.setStyle(Paint.Style.FILL);
			paint.setColor(ColorUtils.setAlphaComponent(color, Math.round(40 * highlightAlpha)));
			canvas.drawRoundRect(highlight, dp(12), dp(12), paint);
			paint.setStyle(Paint.Style.STROKE); paint.setStrokeWidth(dp(2));
			paint.setColor(ColorUtils.setAlphaComponent(color, Math.round(255 * highlightAlpha)));
			RectF line = new RectF(highlight); line.inset(dp(1), dp(1));
			canvas.drawRoundRect(line, dp(12), dp(12), paint); paint.setStyle(Paint.Style.FILL);
		}
		PlayerButtonsMotion.Target target(float x, float y) {
			for (Button button : Button.values()) {
				View view = buttons.get(button);
				if (x >= view.getLeft() && x < view.getRight() && y >= view.getTop() && y < view.getBottom()) {
					return new PlayerButtonsMotion.Target(button, false);
				}
			}
			if (rightZone.contains(x, y)) return new PlayerButtonsMotion.Target(null, true);
			if (leftZone.contains(x, y)) return new PlayerButtonsMotion.Target(null, false);
			return null;
		}
	}
}
