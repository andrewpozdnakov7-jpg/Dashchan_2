package com.mishiranu.dashchan.widget;

import android.content.Context;
import android.graphics.Rect;
import android.view.View;
import androidx.annotation.NonNull;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

public class PostsLayoutManager extends LinearLayoutManager {
	public PostsLayoutManager(Context context) {
		super(context);
	}

	@Override
	public boolean requestChildRectangleOnScreen(@NonNull RecyclerView parent, @NonNull View child,
			@NonNull Rect rect, boolean immediate, boolean focusedChildVisible) {
		// Text selection uses the regular, animated request and must not move the post list. Immediate
		// requests are also used by Android scroll capture and must perform the requested pixel scroll.
		if (child instanceof PostLinearLayout && !immediate) {
			return true;
		}
		return super.requestChildRectangleOnScreen(parent, child, rect, immediate, focusedChildVisible);
	}
}
