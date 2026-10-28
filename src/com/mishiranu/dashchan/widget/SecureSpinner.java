package com.mishiranu.dashchan.widget;

import android.app.AlertDialog;
import android.content.Context;
import android.os.Bundle;
import android.os.Parcelable;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.ListAdapter;
import android.widget.SpinnerAdapter;
import android.widget.Checkable;
import android.widget.CheckedTextView;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.view.Gravity;
import com.mishiranu.dashchan.util.ResourceUtils;
import com.mishiranu.dashchan.util.AndroidUtils;

/** Native collapsed Spinner; selection always uses a registered, shieldable dialog. */
public final class SecureSpinner extends DistributionSpinner {
	private AlertDialog selectionDialog;
	public SecureSpinner(Context context) {
		// No native dropdown forwarding: it must not open an unregistered window on touch
		// or restore. The native popup is never shown/saved; our selection dialog is separate.
		super(context);
	}

	AlertDialog createSelectionDialog() {
		SpinnerAdapter adapter = getAdapter();
		if (!isEnabled() || adapter == null || adapter.getCount() == 0) return null;
		BaseAdapter choices = new BaseAdapter() {
			@Override public int getCount() { return adapter.getCount(); }
			@Override public Object getItem(int position) { return adapter.getItem(position); }
			@Override public long getItemId(int position) { return adapter.getItemId(position); }
			@Override public boolean hasStableIds() { return adapter.hasStableIds(); }
			@Override public int getItemViewType(int position) { return adapter.getItemViewType(position); }
			@Override public int getViewTypeCount() { return adapter.getViewTypeCount(); }
			@Override public boolean areAllItemsEnabled() {
				return !(adapter instanceof ListAdapter) || ((ListAdapter) adapter).areAllItemsEnabled();
			}
			@Override public boolean isEnabled(int position) {
				return !(adapter instanceof ListAdapter) || ((ListAdapter) adapter).isEnabled(position);
			}
			@Override public View getView(int position, View convertView, ViewGroup parent) {
				ChoiceRow row = convertView instanceof ChoiceRow ? (ChoiceRow) convertView : null;
				if (row != null) InterfaceAppearance.resetSpinnerChoice(row);
				View recycled = row != null ? row.content.getChildAt(0) : convertView;
				View content = adapter.getDropDownView(position, recycled, parent);
				if (!InterfaceAppearance.isEnabled(getContext())) {
					if (row != null) {
						InterfaceAppearance.refreshTree(row);
						if (content.getParent() == row.content) row.content.removeView(content);
					}
					return content;
				}
				if (row == null) row = new ChoiceRow(parent.getContext());
				row.bind(content, isEnabled(position), position == getSelectedItemPosition());
				return row;
			}
		};
		return MotionDialogBuilder.standard(getContext()).setTitle(getPrompt())
				.setSingleChoiceItems(choices, getSelectedItemPosition(), (dialog, which) -> {
					if (getAdapter() == adapter && which >= 0 && which < adapter.getCount() && choices.isEnabled(which)) {
						setSelection(which);
						performItemClick(null, which, adapter.getItemId(which));
					}
					dialog.dismiss();
				}).create();
	}

	/** Labels and decorative images remain adapter-owned; only the surrounding choice is ours. */
	static final class ChoiceRow extends LinearLayout implements Checkable {
		final FrameLayout content;
		final CheckedTextView marker;
		private boolean checked, hasNativeMark;
		ChoiceRow(Context context) {
			super(context); setOrientation(HORIZONTAL); setGravity(Gravity.CENTER_VERTICAL);
			content = new FrameLayout(context); addView(content, new LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f));
			marker = new CheckedTextView(context);
			marker.setGravity(Gravity.CENTER);
			marker.setCheckMarkDrawable(ResourceUtils.getDrawable(context, android.R.attr.listChoiceIndicatorSingle, 0));
			marker.setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO); marker.setFocusable(false);
			LayoutParams params = new LayoutParams(Math.round(24 * ResourceUtils.obtainDensity(this)), LayoutParams.MATCH_PARENT);
			params.setMarginStart(Math.round(16 * ResourceUtils.obtainDensity(this)));
			addView(marker, params);
		}
		void bind(View view, boolean enabled, boolean checked) {
			if (view.getParent() != content) {
				content.removeAllViews();
				if (view.getParent() instanceof ViewGroup) ((ViewGroup) view.getParent()).removeView(view);
				content.addView(view, new FrameLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT));
			}
			hasNativeMark = hasMark(view); setEnabled(enabled); setChecked(checked); refreshAppearance();
		}
		private static boolean hasMark(View view) {
			if (view instanceof CheckedTextView && ((CheckedTextView) view).getCheckMarkDrawable() != null
					|| view instanceof android.widget.CompoundButton) return true;
			if (view instanceof ViewGroup) {
				ViewGroup group = (ViewGroup) view;
				for (int i = 0; i < group.getChildCount(); i++) if (hasMark(group.getChildAt(i))) return true;
			}
			return false;
		}
		private static void checkContent(View view, boolean checked) {
			// A nested CompoundButton may own an application listener; do not toggle it while styling.
			if (view instanceof CheckedTextView) ((CheckedTextView) view).setChecked(checked);
			if (view instanceof ViewGroup) {
				ViewGroup group = (ViewGroup) view;
				for (int i = 0; i < group.getChildCount(); i++) checkContent(group.getChildAt(i), checked);
			}
		}
		@Override public boolean isChecked() { return checked; }
		@Override public void toggle() { setChecked(!checked); }
		@Override public void setChecked(boolean checked) {
			this.checked = checked; setSelected(checked); marker.setChecked(checked);
			checkContent(content, checked);
		}
		void refreshAppearance() {
			boolean standard = InterfaceAppearance.isEnabled(getContext());
			marker.setVisibility(standard && !hasNativeMark ? VISIBLE : GONE);
			if (!standard) { setPadding(0, 0, 0, 0); setMinimumHeight(0); setBackground(null); }
			InterfaceAppearance.standardSpinnerChoice(this, content, marker);
		}
	}

	@Override protected void onLayout(boolean changed, int left, int top, int right, int bottom) {
		super.onLayout(changed, left, top, right, bottom);
		// The owning form retains its field surface and insets; opt in only the selected label.
		InterfaceAppearance.standardSpinnerField(getSelectedView());
	}

	@Override public boolean performClick() {
		if (!isEnabled()) return false;
		if (selectionDialog != null && selectionDialog.isShowing()) return true;
		selectionDialog = createSelectionDialog();
		if (selectionDialog == null) return false;
		selectionDialog.show();
		return true;
	}
	@Override public void setAdapter(SpinnerAdapter adapter) {
		if (selectionDialog != null) selectionDialog.dismiss();
		selectionDialog = null;
		super.setAdapter(adapter);
	}
	@Override public Parcelable onSaveInstanceState() {
		Bundle state = new Bundle();
		// Only our dialog is shown, so this native snapshot cannot contain an open popup.
		state.putParcelable("secure-spinner-closed-state", super.onSaveInstanceState());
		return state;
	}
	@Override public void onRestoreInstanceState(Parcelable state) {
		Parcelable closed = state instanceof Bundle ? AndroidUtils.getParcelable((Bundle) state,
				"secure-spinner-closed-state", Parcelable.class) : null;
		// An old plain Spinner snapshot may request that its unregistered popup reopen.
		// Ignore that widget-only state; the owning screen still supplies its stored selection.
		super.onRestoreInstanceState(closed != null ? closed : super.onSaveInstanceState());
	}
	@Override protected void onDetachedFromWindow() {
		if (selectionDialog != null) selectionDialog.dismiss();
		selectionDialog = null;
		super.onDetachedFromWindow();
	}
}
