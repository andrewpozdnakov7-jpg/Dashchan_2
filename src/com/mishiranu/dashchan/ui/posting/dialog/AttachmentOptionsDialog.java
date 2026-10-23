package com.mishiranu.dashchan.ui.posting.dialog;

import com.mishiranu.dashchan.widget.MotionDialogBuilder;
import android.app.Activity;
import android.app.AlertDialog;
import android.app.Dialog;
import android.content.Context;
import android.os.Bundle;
import android.util.SparseBooleanArray;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.TextView;
import androidx.annotation.NonNull;
import androidx.fragment.app.DialogFragment;
import chan.content.ChanConfiguration;
import com.mishiranu.dashchan.R;
import com.mishiranu.dashchan.content.model.FileHolder;
import com.mishiranu.dashchan.content.storage.DraftsStorage;
import com.mishiranu.dashchan.graphics.TransparentTileDrawable;
import com.mishiranu.dashchan.ui.posting.AttachmentHolder;
import com.mishiranu.dashchan.ui.posting.PostingDialogCallback;
import com.mishiranu.dashchan.util.GraphicsUtils;
import com.mishiranu.dashchan.util.ResourceUtils;
import com.mishiranu.dashchan.widget.PostingFormAppearance;
import java.util.ArrayList;
import java.util.HashMap;

public class AttachmentOptionsDialog extends DialogFragment implements AdapterView.OnItemClickListener {
	public static final String TAG = AttachmentOptionsDialog.class.getName();

	private static final String EXTRA_ATTACHMENT_INDEX = "attachmentIndex";

	private enum Type {UNIQUE_HASH, REMOVE_METADATA, REENCODE_IMAGE, REMOVE_FILE_NAME, SPOILER}

	private static class OptionItem {
		public final String title;
		public final Type type;
		public final boolean checked;

		public OptionItem(String title, Type type, boolean checked) {
			this.title = title;
			this.type = type;
			this.checked = checked;
		}
	}

	private final ArrayList<OptionItem> optionItems = new ArrayList<>();
	private final HashMap<Type, Integer> optionIndices = new HashMap<>();

	private ListView listView;

	public AttachmentOptionsDialog() {}

	public AttachmentOptionsDialog(int attachmentIndex) {
		Bundle args = new Bundle();
		args.putInt(EXTRA_ATTACHMENT_INDEX, attachmentIndex);
		setArguments(args);
	}

	private static class ItemsAdapter extends ArrayAdapter<String> {
		private final SparseBooleanArray enabledItems = new SparseBooleanArray();

		public ItemsAdapter(Context context, int resId, ArrayList<String> items) {
			super(context, resId, android.R.id.text1, items);
		}

		@NonNull
		@Override
		public View getView(int position, View convertView, @NonNull ViewGroup parent) {
			View view = super.getView(position, convertView, parent);
			view.setEnabled(isEnabled(position));
			com.mishiranu.dashchan.widget.InterfaceAppearance.standardChoiceRow(view, view.findViewById(android.R.id.text1));
			return view;
		}

		@Override
		public boolean isEnabled(int position) {
			return enabledItems.get(position, true);
		}

		@Override public boolean areAllItemsEnabled() {
			return !com.mishiranu.dashchan.widget.InterfaceAppearance.isEnabled(getContext());
		}

		public void setEnabled(int index, boolean enabled) {
			enabledItems.put(index, enabled);
		}
	}

	private AttachmentHolder getAttachmentHolder() {
		return ((PostingDialogCallback) getParentFragment())
				.getAttachmentHolder(requireArguments().getInt(EXTRA_ATTACHMENT_INDEX));
	}

	@NonNull
	@Override
	public Dialog onCreateDialog(Bundle savedInstanceState) {
		Activity activity = getActivity();
		AttachmentHolder holder = getAttachmentHolder();
		FileHolder fileHolder = holder != null ? DraftsStorage.getInstance()
				.getAttachmentDraftFileHolder(holder.hash) : null;
		if (holder == null || fileHolder == null) {
			dismiss();
			return new com.mishiranu.dashchan.content.security.SecureDialog(activity);
		}
		ChanConfiguration.Posting postingConfiguration = ((PostingDialogCallback) getParentFragment())
				.getPostingConfiguration();
		int index = 0;
		optionItems.clear();
		optionIndices.clear();
		optionItems.add(new OptionItem(getString(R.string.unique_hash), Type.UNIQUE_HASH,
				holder.optionUniqueHash));
		optionIndices.put(Type.UNIQUE_HASH, index++);
		if (GraphicsUtils.canRemoveMetadata(fileHolder)) {
			optionItems.add(new OptionItem(getString(R.string.remove_metadata), Type.REMOVE_METADATA,
					holder.optionRemoveMetadata));
			optionIndices.put(Type.REMOVE_METADATA, index++);
		}
		if (GraphicsUtils.canReencode(fileHolder)) {
			optionItems.add(new OptionItem(getString(R.string.reencode_image), Type.REENCODE_IMAGE,
					holder.reencoding != null));
			optionIndices.put(Type.REENCODE_IMAGE, index++);
		}
		optionItems.add(new OptionItem(getString(R.string.remove_file_name), Type.REMOVE_FILE_NAME,
				holder.optionRemoveFileName));
		optionIndices.put(Type.REMOVE_FILE_NAME, index++);
		if (postingConfiguration.attachmentSpoiler) {
			optionItems.add(new OptionItem(getString(R.string.spoiler), Type.SPOILER,
					holder.optionSpoiler));
			// noinspection UnusedAssignment
			optionIndices.put(Type.SPOILER, index++);
		}
		ArrayList<String> items = new ArrayList<>();
		for (OptionItem optionItem : optionItems) {
			items.add(optionItem.title);
		}
		LinearLayout linearLayout = new LinearLayout(activity);
		linearLayout.setOrientation(LinearLayout.VERTICAL);
		boolean standard = com.mishiranu.dashchan.widget.InterfaceAppearance.isEnabled(activity);
		boolean compact = PostingFormAppearance.isEnabled();
		if (compact || standard) {
			TextView details = new TextView(activity);
			details.setText(holder.name + "\n" + holder.fileSize.getText());
			details.setTextIsSelectable(true);
			int padding = Math.round((standard ? 24 : 16) * ResourceUtils.obtainDensity(activity));
			details.setPadding(padding, padding, padding, padding / 2);
			com.mishiranu.dashchan.widget.InterfaceAppearance.standardCustomContent(details);
			linearLayout.addView(details, LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
		}
		ImageView imageView = new ImageView(activity);
		imageView.setBackground(new TransparentTileDrawable(activity, true));
		imageView.setImageDrawable(holder.imageView.getDrawable());
		imageView.setScaleType(standard || compact ? ImageView.ScaleType.FIT_CENTER : ImageView.ScaleType.CENTER_CROP);
		if (standard) {
			float density = ResourceUtils.obtainDensity(activity);
			int previewHeight = Math.round(Math.min(160f, activity.getResources().getConfiguration().screenHeightDp / 4f) * density);
			linearLayout.addView(imageView, new LinearLayout.LayoutParams(-1, previewHeight));
		} else linearLayout.addView(imageView, new LinearLayout.LayoutParams(-1, 0, 1));
		listView = new ListView(activity) {
			@Override protected void onMeasure(int widthSpec, int heightSpec) {
				// A single outer viewport owns scrolling, so large text cannot push choices off-screen.
				super.onMeasure(widthSpec, standard ? View.MeasureSpec.makeMeasureSpec(0x1fffffff, View.MeasureSpec.AT_MOST) : heightSpec);
			}
		};
		linearLayout.addView(listView, LinearLayout.LayoutParams.MATCH_PARENT,
				LinearLayout.LayoutParams.WRAP_CONTENT);
		listView.setChoiceMode(ListView.CHOICE_MODE_MULTIPLE);
		int resId = ResourceUtils.obtainAlertDialogLayoutResId(activity, ResourceUtils.DialogLayout.MULTI_CHOICE);
		listView.setDividerHeight(0);
		ItemsAdapter adapter = new ItemsAdapter(activity, resId, items);
		listView.setAdapter(adapter);
		for (int i = 0; i < optionItems.size(); i++) {
			listView.setItemChecked(i, optionItems.get(i).checked);
		}
		listView.setOnItemClickListener(this);
		updateItemsEnabled(adapter, holder);
		View content = linearLayout;
		if (standard) {
			android.widget.ScrollView scroll = new android.widget.ScrollView(activity);
			scroll.addView(linearLayout); content = scroll;
		}
		AlertDialog dialog = MotionDialogBuilder.standard(activity).setView(content).create();
		dialog.setCanceledOnTouchOutside(true);
		return dialog;
	}

	private void updateItemsEnabled(ItemsAdapter adapter, AttachmentHolder holder) {
		Integer reencodeIndex = optionIndices.get(Type.REENCODE_IMAGE);
		boolean allowRemoveMetadata = reencodeIndex == null || holder.reencoding == null;
		Integer removeMetadataIndex = optionIndices.get(Type.REMOVE_METADATA);
		if (removeMetadataIndex != null) {
			adapter.setEnabled(removeMetadataIndex, allowRemoveMetadata);
			adapter.notifyDataSetChanged();
		}
	}

	@Override
	public void onItemClick(AdapterView<?> parent, View view, int position, long id) {
		AttachmentHolder holder = getAttachmentHolder();
		Type type = optionItems.get(position).type;
		boolean checked = listView.isItemChecked(position);
		switch (type) {
			case UNIQUE_HASH: {
				holder.optionUniqueHash = checked;
				break;
			}
			case REMOVE_METADATA: {
				holder.optionRemoveMetadata = checked;
				break;
			}
			case REENCODE_IMAGE: {
				if (checked) {
					listView.setItemChecked(position, false);
					new ReencodingDialog().show(getChildFragmentManager(), ReencodingDialog.TAG);
				} else {
					holder.reencoding = null;
				}
				break;
			}
			case REMOVE_FILE_NAME: {
				holder.optionRemoveFileName = checked;
				break;
			}
			case SPOILER: {
				holder.optionSpoiler = checked;
				break;
			}
		}
		updateItemsEnabled((ItemsAdapter) listView.getAdapter(), holder);
	}

	public void setReencoding(GraphicsUtils.Reencoding reencoding) {
		AttachmentHolder holder = getAttachmentHolder();
		Integer reencodeIndex = optionIndices.get(Type.REENCODE_IMAGE);
		if (reencodeIndex != null) {
			holder.reencoding = reencoding;
			listView.setItemChecked(reencodeIndex, reencoding != null);
			updateItemsEnabled((ItemsAdapter) listView.getAdapter(), holder);
		}
	}
}
