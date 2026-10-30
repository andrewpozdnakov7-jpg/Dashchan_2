package com.mishiranu.dashchan.widget;

import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.Typeface;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import com.mishiranu.dashchan.R;
import com.mishiranu.dashchan.content.FontManager;
import com.mishiranu.dashchan.util.ResourceUtils;

/** Per-row state layered on the existing standard roles, not another palette or widget stack. */
final class ServiceAppearance {
	private ServiceAppearance() {}
	enum Kind {PREVIEW, TEXT, CATEGORY, ACTION, EMPTY, TAB, THEME, HEADER}
	static final class State {
		final View root;
		final TextView title;
		final int top, end, bottom, minHeight, textMinHeight, height;
		final int textStart, textTop, textEnd, textBottom;
		final int containerHeight;
		final float textWeight;
		Typeface legacyTypeface;
		float legacyAlpha;
		Kind kind;
		TextView metadata, date;
		View indicator;
		ImageView icon, check;
		ColorStateList iconTint;
		int iconMarginEnd;
		boolean muted, medium, nested, selected, dialog, applied;
		State(View root, TextView title) {
			this.root = root; this.title = title;
			top = root.getPaddingTop(); end = root.getPaddingEnd(); bottom = root.getPaddingBottom();
			minHeight = title.getMinimumHeight(); textMinHeight = title.getMinHeight();
			height = title.getLayoutParams() != null ? title.getLayoutParams().height : ViewGroup.LayoutParams.WRAP_CONTENT;
			containerHeight = root.getLayoutParams() != null ? root.getLayoutParams().height : ViewGroup.LayoutParams.WRAP_CONTENT;
			textWeight = title.getLayoutParams() instanceof LinearLayout.LayoutParams ? ((LinearLayout.LayoutParams) title.getLayoutParams()).weight : 0f;
			textStart = title.getPaddingStart(); textTop = title.getPaddingTop(); textEnd = title.getPaddingEnd(); textBottom = title.getPaddingBottom();
			legacyTypeface = title.getTypeface(); legacyAlpha = title.getAlpha();
		}
		void apply() { applyResolved(InterfaceAppearance.isEnabled(root.getContext())); }
		void applyResolved(boolean enabled) {
			if (!enabled) {
				// Binding can supply a different unread/category state while the profile
				// is already OFF. Restore that latest model state, not the first holder.
				if (kind == Kind.PREVIEW || kind == Kind.EMPTY) alpha(legacyAlpha);
				if (kind == Kind.PREVIEW || kind == Kind.TEXT) title.setTypeface(legacyTypeface);
				if (kind == Kind.TEXT) root.setPaddingRelative(StandardAppearance.dp(root, nested ? 32 : 16), top, end, bottom);
				if (applied) {
					if (kind == Kind.CATEGORY) title.setTypeface(legacyTypeface);
					if (kind == Kind.CATEGORY || kind == Kind.TAB) {
						title.setPaddingRelative(textStart, textTop, textEnd, textBottom);
						title.setMinimumHeight(minHeight); title.setMinHeight(textMinHeight);
					}
					if (kind == Kind.CATEGORY) icon.setImageTintList(iconTint);
					if (kind == Kind.TAB) {
						title.getLayoutParams().height = height;
						if (title.getLayoutParams() instanceof LinearLayout.LayoutParams) ((LinearLayout.LayoutParams) title.getLayoutParams()).weight = textWeight;
						if (root.getLayoutParams() != null) root.getLayoutParams().height = containerHeight;
					}
					if (kind == Kind.THEME) {
						((ViewGroup.MarginLayoutParams) icon.getLayoutParams()).setMarginEnd(iconMarginEnd);
						((View) check.getParent()).setVisibility(View.GONE); root.setSelected(false);
						title.setContentDescription(null);
					}
				}
				if (kind == Kind.TAB) tabColors(ThemeEngine.getTheme(root.getContext()).accent,
						ResourceUtils.getColor(root.getContext(), android.R.attr.textColorSecondary));
				applied = false; return;
			}
			applied = true;
			AppearanceColors c = StandardAppearance.colors(root.getContext());
			switch (kind) {
				case HEADER:
					// Standalone service headers, not every existing preference header.
					root.setPaddingRelative(StandardAppearance.dp(root, 16), StandardAppearance.dp(root, 16),
							StandardAppearance.dp(root, 16), StandardAppearance.dp(root, 8)); break;
				case PREVIEW:
					alpha(1f); title.setMaxLines(5);
					title.setTypeface(FontManager.resolveTypeface(root.getContext(), medium));
					title.setTextColor(StandardAppearance.serviceTextColors(muted ? c.onSurfaceVariant : c.onSurface, c.window, c));
					break;
				case TEXT:
					root.setPaddingRelative(StandardAppearance.dp(root, nested ? 32 : 16), root.getPaddingTop(),
							StandardAppearance.dp(root, 16), root.getPaddingBottom());
					title.setTypeface(FontManager.resolveTypeface(root.getContext(), medium)); break;
				case CATEGORY:
					// Keep the original 48dp arrow box inside the row's 48dp minimum,
					// rather than adding another 8dp above and below that box.
					root.setPaddingRelative(StandardAppearance.dp(root, 16), 0, StandardAppearance.dp(root, 16), 0);
					title.setPaddingRelative(0, StandardAppearance.dp(root, 8), StandardAppearance.dp(root, 56), StandardAppearance.dp(root, 8));
					title.setMinimumHeight(0); title.setMinHeight(0);
					title.setTypeface(FontManager.resolveTypeface(root.getContext(), true));
					icon.setImageTintList(ColorStateList.valueOf(c.onSurfaceVariant)); break;
				case EMPTY:
					alpha(1f); root.setBackground(null); break;
				case ACTION:
					title.setTextColor(StandardAppearance.textColors(InterfaceAppearance.contrasting(c.primary, c.window, 4.5), c.window)); break;
				case TAB:
					title.getLayoutParams().height = ViewGroup.LayoutParams.WRAP_CONTENT;
					if (title.getLayoutParams() instanceof LinearLayout.LayoutParams) ((LinearLayout.LayoutParams) title.getLayoutParams()).weight = 1f;
					if (root.getLayoutParams() != null) root.getLayoutParams().height = ViewGroup.LayoutParams.MATCH_PARENT;
					title.setMinHeight(StandardAppearance.dp(root, 46)); title.setMinimumHeight(StandardAppearance.dp(root, 46));
					title.setPaddingRelative(StandardAppearance.dp(root, 8), StandardAppearance.dp(root, 8),
							StandardAppearance.dp(root, 8), StandardAppearance.dp(root, 8));
					title.setSingleLine(false); title.setMaxLines(Integer.MAX_VALUE);
					tabColors(InterfaceAppearance.contrasting(c.primary, c.window, 4.5), c.onSurfaceVariant); break;
				case THEME:
					// Never tint the theme preview: it represents the actual prospective palette.
					((ViewGroup.MarginLayoutParams) icon.getLayoutParams()).setMarginEnd(StandardAppearance.dp(root, 8));
					((View) check.getParent()).setVisibility(View.VISIBLE);
					check.setVisibility(selected ? View.VISIBLE : View.INVISIBLE);
					check.setImageTintList(ColorStateList.valueOf(InterfaceAppearance.contrasting(c.primary,
							selected ? c.primaryContainer : c.window, 3)));
					root.setSelected(selected); title.setDuplicateParentStateEnabled(true);
					title.setMaxLines(2); title.setContentDescription(title.getText());
					int background = dialog ? c.surfaceContainer : c.window;
					title.setTextColor(StandardAppearance.serviceTextColors(InterfaceAppearance.contrasting(c.onSurface,
							background, 4.5), background, c)); break;
			}
		}
		private void tabColors(int accent, int secondary) {
			title.setTextColor(selected ? accent : secondary);
			indicator.setBackgroundColor(selected ? accent : Color.TRANSPARENT);
			root.setSelected(selected);
		}
		private void alpha(float value) {
			title.setAlpha(value); if (metadata != null) metadata.setAlpha(value); if (date != null) date.setAlpha(value);
		}
	}
	static State prepare(View root, TextView title) {
		Object tag = root.getTag(R.id.standard_service_appearance);
		if (tag instanceof State) return (State) tag;
		State state = new State(root, title); root.setTag(R.id.standard_service_appearance, state); return state;
	}
	static void refresh(View view) {
		Object state = view.getTag(R.id.standard_service_appearance);
		if (state instanceof State) ((State) state).apply();
	}
}
