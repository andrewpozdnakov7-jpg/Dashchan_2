package com.mishiranu.dashchan.ui.preference;

import com.mishiranu.dashchan.media.PlayerButtonsLayout;

/** One width decision for both panel placement and the actual cell layout. No fixed three-column cap. */
final class PlayerButtonsPreviewGeometry {
	final boolean stacked;
	final int leftWidth, rightWidth, columns;
	PlayerButtonsPreviewGeometry(int width, int requestedColumns, int cellWidth, int preferredRightWidth,
			int gap, int panelPadding) {
		int requested = Math.max(1, Math.min(6, requestedColumns));
		stacked = width < preferredRightWidth + gap + requested * cellWidth + panelPadding;
		leftWidth = stacked ? width : width - preferredRightWidth - gap;
		rightWidth = stacked ? width : preferredRightWidth;
		columns = PlayerButtonsLayout.effectiveColumns(requested, leftWidth - panelPadding, cellWidth);
	}
}
