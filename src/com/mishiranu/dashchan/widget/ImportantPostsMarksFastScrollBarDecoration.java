package com.mishiranu.dashchan.widget;

import android.graphics.Canvas;
import android.graphics.Paint;
import com.mishiranu.dashchan.util.ResourceUtils;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;

public class ImportantPostsMarksFastScrollBarDecoration {
	private final Paint userPostMarkPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
	private final Paint replyMarkPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
	private final float postMarkMinSize;
	private Data data;

	public ImportantPostsMarksFastScrollBarDecoration(PaddedRecyclerView recyclerView) {
		postMarkMinSize = ResourceUtils.obtainDensity(recyclerView);
		PostMarksColorProvider.Colors colors = PostMarksColorProvider.obtain(recyclerView.getContext(), true);
		userPostMarkPaint.setColor(colors.userPost);
		replyMarkPaint.setColor(colors.reply);
	}

	public void setData(Data data) {
		this.data = data;
	}

	public boolean hasMarks() {
		return data != null && data.totalPostsCount > 0 &&
				(!data.userPostsPositions.isEmpty() || !data.repliesPositions.isEmpty());
	}

	public void draw(int scrollBarLeft, int scrollBarTop, int scrollBarRight, int scrollBarBottom,
			Canvas canvas) {
		if (data == null || data.totalPostsCount <= 1) {
			return;
		}
		// Positions and their range must come from the same immutable snapshot. The adapter item count
		// can fluctuate while posts are preloaded, filtered, or replaced with window placeholders;
		// reading it on every frame rescales the entire mark map and makes all marks jump while scrolling.
		int postPositionRange = data.totalPostsCount - 1;
		int scrollBarHeight = scrollBarBottom - scrollBarTop;
		if (scrollBarHeight <= 0) {
			return;
		}
		// Use the same stable adapter-position range as PaddedRecyclerView.scroll(). Deriving this
		// range from visible children makes every mark jump when differently sized posts enter or
		// leave the viewport.
		float postMarkRealHeight = scrollBarHeight / (float) postPositionRange;
		drawPostMarks(data.userPostsPositions, userPostMarkPaint, postMarkRealHeight,
				scrollBarLeft, scrollBarTop, scrollBarRight, postPositionRange, canvas);
		drawPostMarks(data.repliesPositions, replyMarkPaint, postMarkRealHeight,
				scrollBarLeft, scrollBarTop, scrollBarRight, postPositionRange, canvas);
	}

	private void drawPostMarks(List<Integer> postPositions, Paint postMarkPaint, float postMarkRealHeight,
			int scrollBarLeft, int scrollBarTop, int scrollBarRight, int postPositionRange, Canvas canvas) {
		int postPositionIndex = 0;
		while (postPositionIndex < postPositions.size()) {
			int postPosition = postPositions.get(postPositionIndex++);
			int contiguousPostMarks = 1;
			while (postPositionIndex < postPositions.size()) {
				int nextPostPosition = postPositions.get(postPositionIndex);
				if (postPosition + contiguousPostMarks == nextPostPosition) {
					contiguousPostMarks++;
					postPositionIndex++;
				} else {
					break;
				}
			}

			int markPosition = Math.min(postPosition, postPositionRange);
			float postMarkTop = scrollBarTop + postMarkRealHeight * markPosition;
			float postMarkHeight = postMarkRealHeight *
					Math.min(contiguousPostMarks, Math.max(1, postPositionRange - markPosition));
			float postMarkMinHeight = postMarkMinSize * contiguousPostMarks;
			if (postMarkHeight < postMarkMinHeight) {
				postMarkHeight = postMarkMinHeight;
			}
			canvas.drawRect(scrollBarLeft, postMarkTop,
					scrollBarRight, postMarkTop + postMarkHeight, postMarkPaint);
		}
	}

	public static class Data {
		private final List<Integer> userPostsPositions = new ArrayList<>();
		private final List<Integer> repliesPositions = new ArrayList<>();
		private final int totalPostsCount;

		public Data(Set<Integer> userPostsPositions, Set<Integer> repliesPositions, int totalPostsCount) {
			this.totalPostsCount = totalPostsCount;
			this.userPostsPositions.addAll(userPostsPositions);
			Collections.sort(this.userPostsPositions);
			for (Integer replyPosition : repliesPositions) {
				if (Collections.binarySearch(this.userPostsPositions, replyPosition) < 0) {
					this.repliesPositions.add(replyPosition);
				}
			}
			Collections.sort(this.repliesPositions);
		}
	}
}
