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
import com.mishiranu.dashchan.content.security.SecureDialogBuilder;
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
		if (adapter == null || adapter.getCount() == 0) return null;
		BaseAdapter choices = new BaseAdapter() {
			@Override public int getCount() { return adapter.getCount(); }
			@Override public Object getItem(int position) { return adapter.getItem(position); }
			@Override public long getItemId(int position) { return adapter.getItemId(position); }
			@Override public boolean hasStableIds() { return adapter.hasStableIds(); }
			@Override public boolean areAllItemsEnabled() {
				return !(adapter instanceof ListAdapter) || ((ListAdapter) adapter).areAllItemsEnabled();
			}
			@Override public boolean isEnabled(int position) {
				return !(adapter instanceof ListAdapter) || ((ListAdapter) adapter).isEnabled(position);
			}
			@Override public View getView(int position, View convertView, ViewGroup parent) {
				return adapter.getDropDownView(position, convertView, parent);
			}
		};
		return new SecureDialogBuilder(getContext()).setTitle(getPrompt())
				.setSingleChoiceItems(choices, getSelectedItemPosition(), (dialog, which) -> {
					if (getAdapter() == adapter && which >= 0 && which < adapter.getCount() && choices.isEnabled(which)) {
						setSelection(which);
						performItemClick(null, which, adapter.getItemId(which));
					}
					dialog.dismiss();
				}).create();
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
