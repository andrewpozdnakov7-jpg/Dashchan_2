package com.mishiranu.dashchan.ui.posting;
import android.content.Context;
import android.view.View;
import android.widget.LinearLayout;
/** Inert compatibility decoration. The excluded capability never creates it. */
final class VideoConversionBadge extends LinearLayout {
    enum Stage {NONE, QUEUED, PREPARING, PROGRESS, OPTIMIZING, CHECKING, SUCCESS, ERROR}
    VideoConversionBadge(Context context, Context materialContext, View preview) { super(context); setVisibility(GONE); }
    void bind(String token, Stage stage, int percent, CharSequence text, CharSequence description, long time) {}
    void release() {}
}
