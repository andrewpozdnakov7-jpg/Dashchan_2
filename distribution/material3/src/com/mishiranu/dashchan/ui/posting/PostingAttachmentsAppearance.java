package com.mishiranu.dashchan.ui.posting;

import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.os.Bundle;
import android.text.TextUtils;
import android.view.DragEvent;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.accessibility.AccessibilityNodeInfo;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import androidx.core.graphics.ColorUtils;
import com.mishiranu.dashchan.R;
import com.mishiranu.dashchan.util.ResourceUtils;
import com.mishiranu.dashchan.widget.PostingFormAppearance;
import com.mishiranu.dashchan.widget.ThemeEngine;
import java.util.IdentityHashMap;
import java.util.List;

/** View-lifetime adapter only. The controller still owns files, ordering, drafts and actions. */
public final class PostingAttachmentsAppearance {
	public interface Host {
		List<AttachmentHolder> attachments();
		void move(AttachmentHolder holder, int index);
		void rebuild();
	}
	final LinearLayout container;
	final ScrollView scroll;
	final Host host;
	final ViewGroup grid;
	final AttachmentGridMotion motion;
	final AttachmentRemovalMotion removal;
	final int surface, previewSurface, ink, primary, error;
	private final IdentityHashMap<AttachmentHolder, Card> cards = new IdentityHashMap<>();
	private View editorSpacingAnchor;
	private int emptyEditorBottomMargin;

	public static PostingAttachmentsAppearance create(LinearLayout container, ScrollView scroll, Host host) {
		return PostingFormAppearance.isEnabled() ? new PostingAttachmentsAppearance(container, scroll, host) : null;
	}
	/** An empty adapter still owns a Grid child: count cards, not that wrapper. */
	public static int cardCount(View container) {
		if (!(container instanceof ViewGroup)) return 0;
		ViewGroup group = (ViewGroup) container;
		for (int i = 0; i < group.getChildCount(); i++) {
			View child = group.getChildAt(i);
			if (child instanceof PostingAttachmentsAppearance.Grid) return ((ViewGroup) child).getChildCount();
		}
		return group.getChildCount();
	}
	PostingAttachmentsAppearance(LinearLayout container, ScrollView scroll, Host host) {
		this.container = container; this.scroll = scroll; this.host = host;
		Context context = container.getContext();
		ThemeEngine.Theme theme = ThemeEngine.getTheme(context);
		int window = ColorUtils.setAlphaComponent(theme.window, 255);
		surface = opaqueBlend(window, theme.accent, .055f);
		previewSurface = opaqueBlend(surface, theme.accent, .065f);
		ink = contrasting(ResourceUtils.getColor(context,
				android.R.attr.textColorPrimary), surface, 4.5);
		primary = contrasting(theme.accent, surface, 3);
		error = contrasting(ResourceUtils.getColor(context, R.attr.colorTextError), surface, 4.5);
		grid = new Grid(context);
		container.addView(grid, new LinearLayout.LayoutParams(-1, -2));
		motion = new AttachmentGridMotion(this);
		removal = new AttachmentRemovalMotion(this);
	}
	int dp(float value) { return Math.round(value * ResourceUtils.obtainDensity(container)); }
	static int opaqueBlend(int from, int to, float fraction) {
		return ColorUtils.setAlphaComponent(ColorUtils.blendARGB(from, to, fraction), 255);
	}
	static int contrasting(int color, int background, double ratio) {
		color = ColorUtils.compositeColors(color, background);
		if (ColorUtils.calculateContrast(color, background) >= ratio) return color;
		int end = ColorUtils.calculateContrast(Color.BLACK, background) > ColorUtils.calculateContrast(Color.WHITE, background)
				? Color.BLACK : Color.WHITE;
		for (int step = 1; step <= 100; step++) {
			int candidate = opaqueBlend(color, end, step / 100f);
			if (ColorUtils.calculateContrast(candidate, background) >= ratio) return candidate;
		}
		return end;
	}
	GradientDrawable shape(int color, float radius) {
		GradientDrawable shape = new GradientDrawable(); shape.setColor(color); shape.setCornerRadius(dp(radius));
		return shape;
	}
	private void selectable(View view) {
		view.setForeground(new RippleDrawable(ColorStateList.valueOf(ColorUtils.setAlphaComponent(primary, 32)),
				null, shape(Color.WHITE, 0)));
	}
	private ImageView button(LinearLayout parent, int icon, boolean attribute) {
		ImageView button = new ImageView(parent.getContext());
		button.setImageDrawable(attribute ? ResourceUtils.getDrawable(parent.getContext(), icon, 0)
				: parent.getContext().getDrawable(icon));
		button.setImageTintList(new ColorStateList(new int[][] {{-android.R.attr.state_enabled}, {}},
				new int[] {ColorUtils.setAlphaComponent(ink, 96), ink}));
		button.setScaleType(ImageView.ScaleType.FIT_CENTER);
		button.setPadding(dp(13), dp(13), dp(13), dp(13));
		parent.addView(button, new LinearLayout.LayoutParams(0, dp(48), 1));
		button.setMinimumWidth(dp(48)); button.setMinimumHeight(dp(48)); button.setFocusable(true);
		selectable(button);
		return button;
	}
	private ImageView action(LinearLayout parent, int icon, boolean attribute) {
		ImageView button = new AttachmentActionButton(parent.getContext(), surface, primary, ink);
		button.setImageDrawable(attribute ? ResourceUtils.getDrawable(parent.getContext(), icon, 0)
				: parent.getContext().getDrawable(icon));
		button.setScaleType(ImageView.ScaleType.FIT_CENTER);
		LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(dp(48), dp(48));
		params.gravity = Gravity.CENTER_VERTICAL;
		parent.addView(button, params);
		return button;
	}
	public AttachmentHolder createCard(View.OnClickListener options, View.OnClickListener warning,
			View.OnClickListener rating, View.OnClickListener edit, View.OnClickListener remove,
			View.OnClickListener preview, View.OnLongClickListener drag) {
		Card card = new Card(container.getContext());
		AttachmentHolder holder = new AttachmentHolder(card, card.name, card.info, card.image, card.preview,
				card.warning, card.rating, card.edit);
		cards.put(holder, card);
		bindClick(card.stage, holder, options); bindClick(card.name, holder, options);
		bindClick(card.warning, holder, warning); bindClick(card.rating, holder, rating);
		bindClick(card.edit, holder, edit); bindClick(card.remove, holder, remove);
		bindClick(card.preview, holder, preview);
		bindDrag(card.stage, holder, drag);
		// The video's play overlay retains its short-tap action, but must not mask
		// the same long-press gesture on the rest of the preview.
		bindDrag(card.preview, holder, drag);
		card.stage.setAccessibilityDelegate(new View.AccessibilityDelegate() {
			@Override public void onInitializeAccessibilityNodeInfo(View view, AccessibilityNodeInfo info) {
				super.onInitializeAccessibilityNodeInfo(view, info);
				info.addAction(new AccessibilityNodeInfo.AccessibilityAction(AccessibilityNodeInfo.ACTION_LONG_CLICK,
						container.getContext().getString(R.string.attachment_reorder_named, holder.name)));
				int index = host.attachments().indexOf(holder);
				if (index > 0) info.addAction(new AccessibilityNodeInfo.AccessibilityAction(
						AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD, container.getContext().getString(R.string.attachment_move_earlier)));
				if (index >= 0 && index + 1 < host.attachments().size()) info.addAction(new AccessibilityNodeInfo.AccessibilityAction(
						AccessibilityNodeInfo.ACTION_SCROLL_FORWARD, container.getContext().getString(R.string.attachment_move_later)));
			}
			@Override public boolean performAccessibilityAction(View view, int action, Bundle arguments) {
				if (action == AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD || action == AccessibilityNodeInfo.ACTION_SCROLL_FORWARD) {
					int from = host.attachments().indexOf(holder);
					int to = from + (action == AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD ? -1 : 1);
					if (from < 0 || to < 0 || to >= host.attachments().size()) return false;
					motion.cancel(); host.move(holder, to);
					card.stage.post(() -> {
						if (cards.containsKey(holder) && card.isAttachedToWindow()) card.stage.performAccessibilityAction(
								AccessibilityNodeInfo.ACTION_ACCESSIBILITY_FOCUS, null);
					});
					return true;
				}
				return super.performAccessibilityAction(view, action, arguments);
			}
		});
		return holder;
	}
	private void bindDrag(View view, AttachmentHolder holder, View.OnLongClickListener listener) {
		view.setTag(holder);
		view.setOnTouchListener((target, event) -> { motion.grab(holder, target, event); return false; });
		view.setOnLongClickListener(target -> cards.containsKey(holder)
				&& host.attachments().contains(holder) && listener.onLongClick(target));
		// A consumed native long click supplies the standard system haptic feedback.
		// Do not vibrate a second time or consume ordinary clicks / scroll gestures.
	}
	private void bindClick(View view, AttachmentHolder holder, View.OnClickListener listener) {
		view.setTag(holder); view.setOnClickListener(v -> {
			motion.cancel();
			removal.finish();
			if (cards.containsKey(holder) && host.attachments().contains(holder)) listener.onClick(v);
		});
	}
	public void addCard(View view) {
		grid.addView(view, new ViewGroup.LayoutParams(-1, -2));
		updatePadding();
	}
	public void clearCards() {
		motion.beforeRebuild(); removal.beforeRebuild(); grid.removeAllViews(); updatePadding();
	}
	public void prepareRemoval(AttachmentHolder holder) { motion.cancel(); removal.prepare(holder); }
	public boolean deferRebuild() { return motion.isCommitting(); }
	private void updatePadding() {
		boolean hasCards = grid.getChildCount() > 0;
		updateEditorSpacing(hasCards);
		grid.setPadding(0, hasCards ? dp(6) : 0, 0, hasCards ? dp(12) : 0);
	}
	private void updateEditorSpacing(boolean hasCards) {
		if (PostingFormAppearance.syncAttachmentSpacing(container, hasCards)) return;
		if (editorSpacingAnchor == null && container.getParent() instanceof View) {
			View editor = ((View) container.getParent()).findViewById(R.id.comment_parent);
			if (editor != null && editor.getLayoutParams() instanceof ViewGroup.MarginLayoutParams) {
				editorSpacingAnchor = editor;
				emptyEditorBottomMargin = ((ViewGroup.MarginLayoutParams) editor.getLayoutParams()).bottomMargin;
			}
		}
		if (editorSpacingAnchor != null) {
			ViewGroup.MarginLayoutParams params = (ViewGroup.MarginLayoutParams) editorSpacingAnchor.getLayoutParams();
			int bottom = hasCards ? 0 : emptyEditorBottomMargin;
			if (params.bottomMargin != bottom) {
				params.bottomMargin = bottom; editorSpacingAnchor.setLayoutParams(params);
			}
		}
	}
	public void prune() {
		cards.keySet().retainAll(host.attachments());
		motion.prune();
		updatePadding();
		removal.committed();
	}
	public int thumbnailSize() {
		int width = grid.getWidth() > 0 ? grid.getWidth() : container.getResources().getDisplayMetrics().widthPixels - dp(32);
		return Math.max(dp(96), AttachmentGridGeometry.cardWidth(width, dp(8), dp(144)));
	}
	public void bindFile(AttachmentHolder holder, String details, boolean available, boolean image,
			boolean video, boolean editable) {
		Card card = cards.get(holder); if (card == null) return;
		card.name.setText(AttachmentDisplayName.withoutExtension(holder.name));
		card.info.setText(details);
		card.image.setContentDescription(null);
		card.stage.setContentDescription(holder.name + ", " + details);
		card.name.setContentDescription(holder.name);
		card.edit.setVisibility(View.VISIBLE); card.edit.setEnabled(editable);
		card.edit.setContentDescription(container.getContext().getString(editable
				? R.string.attachment_edit_named : R.string.attachment_edit_unavailable_named, holder.name));
		card.remove.setContentDescription(container.getContext().getString(R.string.attachment_remove_named, holder.name));
		card.warning.setContentDescription(container.getContext().getString(R.string.attachment_warning_named, holder.name));
		card.rating.setContentDescription(container.getContext().getString(R.string.attachment_rating_named, holder.name));
		card.preview.setContentDescription(container.getContext().getString(R.string.preview_video_attachment) + ": " + holder.name);
		card.status.setText(available ? "" : container.getContext().getString(R.string.attachment_file_unavailable));
		card.status.setVisibility(available ? View.GONE : View.VISIBLE);
		card.placeholder.setImageDrawable(ResourceUtils.getDrawable(container.getContext(),
				available ? video ? R.attr.iconAttachmentVideo : R.attr.iconAttachmentFile : R.attr.iconAttachmentWarning, 0));
		card.placeholder.setVisibility(View.VISIBLE);
		card.expectsPreview = available && (image || video);
		card.image.setVisibility(View.INVISIBLE);
		holder.view.getLayoutParams().height = ViewGroup.LayoutParams.WRAP_CONTENT;
	}
	public void thumbnailFinished(AttachmentHolder holder, boolean success) {
		Card card = cards.get(holder); if (card == null) return;
		card.placeholder.setVisibility(success ? View.GONE : View.VISIBLE);
		if (!success && card.expectsPreview) {
			motion.cancel();
			removal.finish();
			card.status.setText(R.string.attachment_preview_unavailable); card.status.setVisibility(View.VISIBLE);
		}
	}
	public boolean startDrag(AttachmentHolder holder) { removal.finish(); return motion.start(holder); }
	public boolean dispatchDrag(DragEvent event) { return motion.dispatch(event); }
	public void cancelMotion() { motion.cancel(); removal.finish(); }
	public void close() { motion.close(); removal.close(); updateEditorSpacing(false); editorSpacingAnchor = null;
		cards.clear(); container.removeView(grid); }

	private final class Card extends LinearLayout {
		final FrameLayout stage;
		final ImageView image, placeholder, preview, warning, rating, edit, remove;
		final TextView name, info, status;
		boolean expectsPreview;
		Card(Context context) {
			super(context); setOrientation(VERTICAL); setBackground(shape(surface, 12));
			setClipToOutline(true); setElevation(0); setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
			stage = new FrameLayout(context); stage.setBackgroundColor(previewSurface);
			addView(stage, new LinearLayout.LayoutParams(-1, dp(96))); selectable(stage);
			image = new ImageView(context); image.setScaleType(ImageView.ScaleType.FIT_CENTER);
			image.setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
			stage.addView(image, new FrameLayout.LayoutParams(-1, -1));
			placeholder = new ImageView(context); placeholder.setImageTintList(ColorStateList.valueOf(ink));
			placeholder.setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
			stage.addView(placeholder, new FrameLayout.LayoutParams(dp(32), dp(32), Gravity.CENTER));
			preview = new ImageView(context); preview.setImageDrawable(ResourceUtils.getDrawable(context, R.attr.iconAttachmentVideo, 0));
			preview.setImageTintList(ColorStateList.valueOf(ink)); preview.setPadding(dp(12), dp(12), dp(12), dp(12));
			preview.setBackground(shape(surface, 24)); selectable(preview); preview.setVisibility(GONE);
			stage.addView(preview, new FrameLayout.LayoutParams(dp(48), dp(48), Gravity.CENTER));
			name = text(12); name.setTypeface(ResourceUtils.TYPEFACE_MEDIUM); name.setSingleLine(true);
			name.setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO); // Stage already exposes full name/details.
			name.setEllipsize(TextUtils.TruncateAt.END); name.setMinWidth(0); name.setMinimumWidth(0);
			name.setMinHeight(dp(48)); name.setGravity(Gravity.CENTER_VERTICAL);
			name.setPaddingRelative(dp(8), 0, 0, 0); selectable(name);
			info = text(12); info.setVisibility(GONE); // Full details remain in the existing options dialog.
			status = text(12); status.setTextColor(error); status.setVisibility(GONE);
			addView(status, new LinearLayout.LayoutParams(-1, -2));
			LinearLayout extra = new LinearLayout(context);
			addView(extra, new LinearLayout.LayoutParams(-1, -2));
			warning = button(extra, R.attr.iconButtonWarning, true); warning.setVisibility(GONE);
			rating = button(extra, R.attr.iconButtonRating, true); rating.setVisibility(GONE);
			LinearLayout actions = new LinearLayout(context); actions.setGravity(Gravity.CENTER_VERTICAL);
			actions.setMinimumHeight(dp(48)); addView(actions, new LinearLayout.LayoutParams(-1, -2));
			actions.addView(name, new LinearLayout.LayoutParams(0, -2, 1));
			edit = action(actions, R.drawable.ic_edit, false);
			remove = action(actions, R.attr.iconButtonCancel, true);
		}
		private TextView text(int sp) {
			TextView view = new TextView(getContext()); view.setTextSize(sp); view.setTextColor(ink);
			view.setPadding(dp(8), 0, dp(8), 0); return view;
		}
	}
	/** One vertical form scroll; this view only measures and lays out row-major cards. */
	private final class Grid extends ViewGroup {
		private int columns = 1, cellWidth;
		Grid(Context context) { super(context); setLayoutDirection(LAYOUT_DIRECTION_LTR); }
		@Override protected void onMeasure(int widthSpec, int heightSpec) {
			int width = MeasureSpec.getSize(widthSpec), count = getChildCount(), height = getPaddingTop();
			columns = AttachmentGridGeometry.columns(width, dp(8), dp(144));
			cellWidth = AttachmentGridGeometry.cardWidth(width, dp(8), dp(144));
			for (int row = 0; row < count; row += columns) {
				int rowHeight = 0;
				for (int i = row; i < Math.min(row + columns, count); i++) {
					View child = getChildAt(i);
					child.measure(MeasureSpec.makeMeasureSpec(cellWidth, MeasureSpec.EXACTLY),
							MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED));
					rowHeight = Math.max(rowHeight, child.getMeasuredHeight());
				}
				for (int i = row; i < Math.min(row + columns, count); i++) getChildAt(i).measure(
						MeasureSpec.makeMeasureSpec(cellWidth, MeasureSpec.EXACTLY),
						MeasureSpec.makeMeasureSpec(rowHeight, MeasureSpec.EXACTLY));
				height += rowHeight + (row + columns < count ? dp(8) : 0);
			}
			setMeasuredDimension(resolveSize(width, widthSpec), resolveSize(height + getPaddingBottom(), heightSpec));
		}
		@Override protected void onLayout(boolean changed, int left, int top, int right, int bottom) {
			int y = getPaddingTop();
			for (int row = 0; row < getChildCount(); row += columns) {
				int rowHeight = getChildAt(row).getMeasuredHeight();
				for (int i = row; i < Math.min(row + columns, getChildCount()); i++) {
					int x = (i - row) * (cellWidth + dp(8));
					getChildAt(i).layout(x, y, x + cellWidth, y + rowHeight);
				}
				y += rowHeight + dp(8);
			}
		}
	}
}
