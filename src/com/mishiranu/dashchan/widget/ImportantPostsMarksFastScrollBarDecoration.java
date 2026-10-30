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
			Canvas canvas, FastScrollGeometry geometry) {
		draw(scrollBarLeft, scrollBarTop, scrollBarRight, scrollBarBottom, canvas, geometry, null, 0L);
	}
	void draw(int scrollBarLeft, int scrollBarTop, int scrollBarRight, int scrollBarBottom,
			Canvas canvas, FastScrollGeometry geometry, FastScrollProjection projection, long time) {
		if (data == null || data.totalPostsCount <= 1) {
			return;
		}
		// Keep mark positions/count in their immutable data snapshot. Project them through the same
		// continuous viewport scale as navigation; do not use an integer visible-child count.
		double viewportFraction = geometry != null ? geometry.viewportFraction() : 1d / data.totalPostsCount;
		int scrollBarHeight = scrollBarBottom - scrollBarTop;
		if (scrollBarHeight <= 0) {
			return;
		}
		drawPostMarks(data.userPostsPositions, userPostMarkPaint, viewportFraction,
				scrollBarLeft, scrollBarTop, scrollBarRight, scrollBarBottom, canvas, projection, time);
		drawPostMarks(data.repliesPositions, replyMarkPaint, viewportFraction,
				scrollBarLeft, scrollBarTop, scrollBarRight, scrollBarBottom, canvas, projection, time);
	}

	private void drawPostMarks(List<Integer> postPositions, Paint postMarkPaint, double viewportFraction,
			int scrollBarLeft, int scrollBarTop, int scrollBarRight, int scrollBarBottom, Canvas canvas,
			FastScrollProjection projection, long time) {
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

			int height = scrollBarBottom - scrollBarTop;
			float start = FastScrollGeometry.markOffset(postPosition, data.totalPostsCount, viewportFraction);
			float end = FastScrollGeometry.markOffset(postPosition + contiguousPostMarks, data.totalPostsCount, viewportFraction);
			float postMarkTop = scrollBarTop + height * (projection != null ? projection.project(start, time) : start);
			float postMarkEnd = scrollBarTop + height * (projection != null ? projection.project(end, time) : end);
			float postMarkHeight = postMarkEnd - postMarkTop;
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
