package com.mishiranu.dashchan.ui.preference;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.ImageDecoder;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.drawable.Drawable;
import android.net.Uri;
import android.os.Bundle;
import android.text.InputFilter;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.ScrollView;
import android.widget.TextView;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import chan.util.StringUtils;
import com.mishiranu.dashchan.C;
import com.mishiranu.dashchan.R;
import com.mishiranu.dashchan.content.LauncherIconManager;
import com.mishiranu.dashchan.ui.ContentFragment;
import com.mishiranu.dashchan.ui.FragmentHandler;
import com.mishiranu.dashchan.util.ConcurrentUtils;
import com.mishiranu.dashchan.util.ResourceUtils;
import com.mishiranu.dashchan.widget.ClickableToast;
import com.mishiranu.dashchan.widget.SafePasteEditText;
import com.mishiranu.dashchan.widget.ThemeEngine;
import com.mishiranu.dashchan.widget.ViewFactory;
import java.io.IOException;

public class CustomShortcutFragment extends ContentFragment {
	private static final String EXTRA_URI = "uri";
	private static final String EXTRA_NAME = "name";
	private static final String EXTRA_FIT = "fit";
	private static final int OUTPUT_SIZE = 512;
	private static final int MAX_DECODE_SIZE = 2048;

	private SafePasteEditText nameEdit;
	private ImageView preview;
	private ProgressBar progressBar;
	private Button createButton;
	private RadioButton cropButton;
	private RadioButton fitButton;
	private Uri selectedUri;
	private Bitmap sourceBitmap;
	private Bitmap shortcutBitmap;
	private volatile int decodeGeneration;

	@Override
	public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
			@Nullable Bundle savedInstanceState) {
		Context context = inflater.getContext();
		float density = ResourceUtils.obtainDensity(context);
		int padding = Math.round(16f * density);
		int smallPadding = Math.round(8f * density);

		ScrollView scrollView = new ScrollView(context);
		scrollView.setFillViewport(true);
		scrollView.setOverScrollMode(View.OVER_SCROLL_IF_CONTENT_SCROLLS);
		LinearLayout layout = new LinearLayout(context);
		layout.setOrientation(LinearLayout.VERTICAL);
		layout.setPadding(padding, 0, padding, padding);
		scrollView.addView(layout, new ScrollView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
				ViewGroup.LayoutParams.WRAP_CONTENT));

		TextView promptHeader = ViewFactory.makeListTextHeader(layout);
		promptHeader.setText(R.string.custom_shortcut_ai_prompt_title);
		promptHeader.setPadding(0, padding, 0, smallPadding);
		layout.addView(promptHeader);

		TextView prompt = new TextView(context);
		prompt.setText(R.string.custom_shortcut_ai_prompt);
		prompt.setTextIsSelectable(true);
		prompt.setPadding(0, 0, 0, smallPadding);
		layout.addView(prompt, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
				ViewGroup.LayoutParams.WRAP_CONTENT));

		Button copyButton = new Button(context);
		copyButton.setText(R.string.copy_prompt);
		Drawable copyIcon = ResourceUtils.getActionBarIcon(context, R.attr.iconActionCopy);
		copyButton.setCompoundDrawablesWithIntrinsicBounds(copyIcon, null, null, null);
		copyButton.setCompoundDrawablePadding(smallPadding);
		copyButton.setOnClickListener(v -> {
			StringUtils.copyToClipboard(requireContext(), getString(R.string.custom_shortcut_ai_prompt));
			ClickableToast.show(R.string.custom_shortcut_prompt_copied);
		});
		layout.addView(copyButton, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
				ViewGroup.LayoutParams.WRAP_CONTENT));

		TextView shortcutHeader = ViewFactory.makeListTextHeader(layout);
		shortcutHeader.setText(R.string.shortcut_image);
		shortcutHeader.setPadding(0, padding, 0, smallPadding);
		layout.addView(shortcutHeader);

		nameEdit = new SafePasteEditText(context);
		nameEdit.setSingleLine(true);
		nameEdit.setHint(R.string.shortcut_name);
		nameEdit.setFilters(new InputFilter[] {new InputFilter.LengthFilter(40)});
		layout.addView(nameEdit, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
				ViewGroup.LayoutParams.WRAP_CONTENT));

		preview = new ImageView(context);
		preview.setContentDescription(getString(R.string.shortcut_image));
		preview.setScaleType(ImageView.ScaleType.CENTER_CROP);
		preview.setBackgroundColor(ThemeEngine.getTheme(context).card);
		int previewSize = Math.round(256f * density);
		LinearLayout.LayoutParams previewParams = new LinearLayout.LayoutParams(previewSize, previewSize);
		previewParams.gravity = Gravity.CENTER_HORIZONTAL;
		previewParams.topMargin = padding;
		previewParams.bottomMargin = smallPadding;
		layout.addView(preview, previewParams);

		progressBar = new ProgressBar(context);
		progressBar.setVisibility(View.GONE);
		LinearLayout.LayoutParams progressParams = new LinearLayout.LayoutParams(
				ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
		progressParams.gravity = Gravity.CENTER_HORIZONTAL;
		progressParams.bottomMargin = smallPadding;
		layout.addView(progressBar, progressParams);

		Button selectButton = new Button(context);
		selectButton.setText(R.string.select_shortcut_image);
		selectButton.setOnClickListener(v -> selectImage());
		layout.addView(selectButton, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
				ViewGroup.LayoutParams.WRAP_CONTENT));

		RadioGroup radioGroup = new RadioGroup(context);
		radioGroup.setOrientation(LinearLayout.VERTICAL);
		cropButton = new RadioButton(context);
		cropButton.setId(View.generateViewId());
		cropButton.setText(R.string.shortcut_crop_center);
		fitButton = new RadioButton(context);
		fitButton.setId(View.generateViewId());
		fitButton.setText(R.string.shortcut_fit_image);
		radioGroup.addView(cropButton);
		radioGroup.addView(fitButton);
		cropButton.setChecked(true);
		radioGroup.setOnCheckedChangeListener((group, checkedId) -> rebuildShortcutBitmap());
		LinearLayout.LayoutParams radioParams = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
				ViewGroup.LayoutParams.WRAP_CONTENT);
		radioParams.topMargin = smallPadding;
		layout.addView(radioGroup, radioParams);

		createButton = new Button(context);
		createButton.setText(R.string.create_shortcut);
		createButton.setEnabled(false);
		createButton.setOnClickListener(v -> createShortcut());
		LinearLayout.LayoutParams createParams = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
				ViewGroup.LayoutParams.WRAP_CONTENT);
		createParams.topMargin = smallPadding;
		layout.addView(createButton, createParams);

		if (savedInstanceState != null) {
			nameEdit.setText(savedInstanceState.getString(EXTRA_NAME));
			fitButton.setChecked(savedInstanceState.getBoolean(EXTRA_FIT));
			String uriString = savedInstanceState.getString(EXTRA_URI);
			if (!StringUtils.isEmpty(uriString)) {
				selectedUri = Uri.parse(uriString);
				loadImage(selectedUri);
			}
		}
		return scrollView;
	}

	@Override
	public void onActivityCreated(Bundle savedInstanceState) {
		super.onActivityCreated(savedInstanceState);
		((FragmentHandler) requireActivity()).setTitleSubtitle(getString(R.string.custom_application_shortcut), null);
	}

	private void selectImage() {
		Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT)
				.addCategory(Intent.CATEGORY_OPENABLE)
				.setType("image/*")
				.putExtra("android.content.extra.SHOW_ADVANCED", true);
		startActivityForResult(intent, C.REQUEST_CODE_ATTACH);
	}

	@Override
	public void onActivityResult(int requestCode, int resultCode, Intent data) {
		super.onActivityResult(requestCode, resultCode, data);
		if (requestCode == C.REQUEST_CODE_ATTACH && resultCode == Activity.RESULT_OK && data != null) {
			Uri uri = data.getData();
			if (uri != null) {
				selectedUri = uri;
				loadImage(uri);
			}
		}
	}

	private void loadImage(Uri uri) {
		int generation = ++decodeGeneration;
		setLoading(true);
		Context context = requireContext().getApplicationContext();
		ConcurrentUtils.PARALLEL_EXECUTOR.execute(() -> {
			Bitmap bitmap;
			try {
				ImageDecoder.Source source = ImageDecoder.createSource(context.getContentResolver(), uri);
				bitmap = ImageDecoder.decodeBitmap(source, (decoder, info, imageSource) -> {
					int width = info.getSize().getWidth();
					int height = info.getSize().getHeight();
					int largest = Math.max(width, height);
					if (largest > MAX_DECODE_SIZE) {
						float scale = (float) MAX_DECODE_SIZE / largest;
						decoder.setTargetSize(Math.max(1, Math.round(width * scale)),
								Math.max(1, Math.round(height * scale)));
					}
					decoder.setAllocator(ImageDecoder.ALLOCATOR_SOFTWARE);
				});
			} catch (IOException | RuntimeException e) {
				bitmap = null;
			}
			Bitmap decodedBitmap = bitmap;
			ConcurrentUtils.HANDLER.post(() -> {
				if (generation != decodeGeneration || !isAdded() || preview == null) {
					if (decodedBitmap != null) {
						decodedBitmap.recycle();
					}
					return;
				}
				setLoading(false);
				if (decodedBitmap == null) {
					ClickableToast.show(R.string.custom_shortcut_image_error);
					return;
				}
				recycleSourceBitmap();
				sourceBitmap = decodedBitmap;
				rebuildShortcutBitmap();
			});
		});
	}

	private void setLoading(boolean loading) {
		if (progressBar != null) {
			progressBar.setVisibility(loading ? View.VISIBLE : View.GONE);
		}
		if (createButton != null) {
			createButton.setEnabled(!loading && shortcutBitmap != null);
		}
	}

	private void rebuildShortcutBitmap() {
		if (sourceBitmap == null || sourceBitmap.isRecycled() || preview == null) {
			return;
		}
		Bitmap newBitmap = renderShortcutBitmap(sourceBitmap, fitButton != null && fitButton.isChecked());
		recycleShortcutBitmap();
		shortcutBitmap = newBitmap;
		preview.setImageBitmap(shortcutBitmap);
		createButton.setEnabled(true);
	}

	private static Bitmap renderShortcutBitmap(Bitmap source, boolean fit) {
		Bitmap result = Bitmap.createBitmap(OUTPUT_SIZE, OUTPUT_SIZE, Bitmap.Config.ARGB_8888);
		Canvas canvas = new Canvas(result);
		Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
		int width = source.getWidth();
		int height = source.getHeight();
		if (fit) {
			canvas.drawColor(sampleOpaqueBackground(source));
			float scale = Math.min((float) OUTPUT_SIZE / width, (float) OUTPUT_SIZE / height);
			float drawWidth = width * scale;
			float drawHeight = height * scale;
			float left = (OUTPUT_SIZE - drawWidth) / 2f;
			float top = (OUTPUT_SIZE - drawHeight) / 2f;
			canvas.drawBitmap(source, null, new RectF(left, top, left + drawWidth, top + drawHeight), paint);
		} else {
			int size = Math.min(width, height);
			int left = (width - size) / 2;
			int top = (height - size) / 2;
			canvas.drawColor(Color.BLACK);
			canvas.drawBitmap(source, new Rect(left, top, left + size, top + size),
					new Rect(0, 0, OUTPUT_SIZE, OUTPUT_SIZE), paint);
		}
		return result;
	}

	private static int sampleOpaqueBackground(Bitmap bitmap) {
		int maxX = bitmap.getWidth() - 1;
		int maxY = bitmap.getHeight() - 1;
		int[] colors = {bitmap.getPixel(0, 0), bitmap.getPixel(maxX, 0),
				bitmap.getPixel(0, maxY), bitmap.getPixel(maxX, maxY)};
		int red = 0;
		int green = 0;
		int blue = 0;
		for (int color : colors) {
			red += Color.red(color);
			green += Color.green(color);
			blue += Color.blue(color);
		}
		return Color.rgb(red / colors.length, green / colors.length, blue / colors.length);
	}

	private void createShortcut() {
		String name = nameEdit.getText().toString().trim();
		if (name.isEmpty()) {
			ClickableToast.show(R.string.enter_valid_data);
		} else if (shortcutBitmap == null) {
			ClickableToast.show(R.string.custom_shortcut_select_image_first);
		} else if (LauncherIconManager.requestCustomShortcut(requireContext(), name, shortcutBitmap)) {
			ClickableToast.show(R.string.confirm_custom_shortcut);
		} else {
			ClickableToast.show(R.string.custom_shortcuts_not_supported);
		}
	}

	@Override
	public void onSaveInstanceState(@NonNull Bundle outState) {
		super.onSaveInstanceState(outState);
		if (selectedUri != null) {
			outState.putString(EXTRA_URI, selectedUri.toString());
		}
		if (nameEdit != null) {
			outState.putString(EXTRA_NAME, nameEdit.getText().toString());
		}
		outState.putBoolean(EXTRA_FIT, fitButton != null && fitButton.isChecked());
	}

	@Override
	public void onDestroyView() {
		decodeGeneration++;
		if (preview != null) {
			preview.setImageDrawable(null);
		}
		recycleShortcutBitmap();
		recycleSourceBitmap();
		nameEdit = null;
		preview = null;
		progressBar = null;
		createButton = null;
		cropButton = null;
		fitButton = null;
		super.onDestroyView();
	}

	private void recycleSourceBitmap() {
		if (sourceBitmap != null) {
			sourceBitmap.recycle();
			sourceBitmap = null;
		}
	}

	private void recycleShortcutBitmap() {
		if (shortcutBitmap != null) {
			shortcutBitmap.recycle();
			shortcutBitmap = null;
		}
	}
}
