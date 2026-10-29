package com.mishiranu.dashchan.widget;

import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.util.AttributeSet;
import android.util.TypedValue;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.Spinner;
import android.widget.TextView;
import androidx.annotation.NonNull;
import com.mishiranu.dashchan.util.ResourceUtils;
import com.mishiranu.dashchan.util.ViewUtils;
import java.util.Arrays;
import java.util.Collection;

public class DropdownView extends FrameLayout {
	private interface TextViewFactory {
		TextView newInstance();
	}

	private final Spinner spinner;
	private final TextViewFactory factory;
	private ColorStateList standardArrow;
	private final Paint arrowPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
	private final Path arrowPath = new Path();

	public DropdownView(Context context) {
		this(context, null);
	}

	public DropdownView(Context context, AttributeSet attrs) {
		super(context, attrs);

		spinner = new SecureSpinner(context);
		spinner.setId(android.R.id.edit);
		spinner.setPadding(0, 0, 0, 0);
		addView(spinner, LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT);
		EditText editText = new EditText(context);
		ThemeEngine.applyStyle(editText);
		setBackground(editText.getBackground());
		setBackgroundTintList(editText.getBackgroundTintList());
		setPadding(0, 0, 0, 0);
		setAddStatesFromChildren(true);
		int paddingLeft = editText.getPaddingLeft();
		int paddingTop = editText.getPaddingTop();
		int paddingRight = editText.getPaddingRight();
		int paddingBottom = editText.getPaddingBottom();
		ColorStateList textColors = editText.getTextColors();
		float textSize = editText.getTextSize();
		Typeface typeface = editText.getTypeface();
		factory = () -> {
			TextView textView = new TextView(context);
			textView.setPadding(paddingLeft, paddingTop, paddingRight, paddingBottom);
			textView.setTextColor(textColors);
			textView.setTextSize(TypedValue.COMPLEX_UNIT_PX, textSize);
			textView.setTypeface(typeface);
			textView.setSingleLine(true);
			return textView;
		};

		editText.setText("XXXXXXXXXX");
		int measureSpec = MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED);
		editText.measure(measureSpec, measureSpec);
		Drawable background = spinner.getBackground();
		Bitmap bitmap = Bitmap.createBitmap(editText.getMeasuredWidth(),
				editText.getMeasuredHeight(), Bitmap.Config.ARGB_8888);
		Canvas canvas = new Canvas(bitmap);
		background.setBounds(0, 0, editText.getMeasuredWidth(), editText.getMeasuredHeight());
		background.draw(canvas);
		int[] pixels = new int[bitmap.getHeight()];
		@SuppressWarnings("MismatchedReadAndWriteOfArray")
		int[] zeroPixels = new int[bitmap.getHeight()];
		int left = -1;
		int right = -1;
		for (int i = 0; i < bitmap.getWidth(); i++) {
			bitmap.getPixels(pixels, 0, 1, i, 0, 1, bitmap.getHeight());
			if (!Arrays.equals(pixels, zeroPixels)) {
				left = i;
				break;
			}
		}
		for (int i = bitmap.getWidth() - 1; i >= 0; i--) {
			bitmap.getPixels(pixels, 0, 1, i, 0, 1, bitmap.getHeight());
			if (!Arrays.equals(pixels, zeroPixels)) {
				right = bitmap.getWidth() - 1 - i;
				break;
			}
		}
		bitmap.recycle();
		if (left >= 0 && right >= 0) {
			int imagePadding = Math.min(left, right);
			int textPadding = Math.min(paddingLeft, paddingRight);
			float density = ResourceUtils.obtainDensity(context);
			int targetPadding = (int) (8f * density) + textPadding;
			int margin = targetPadding - imagePadding;
			ViewUtils.setNewMarginRelative(spinner, 0, 0, margin, 0);
		}
		refreshAppearance();
	}

	public void refreshAppearance() { InterfaceAppearance.standardDropdownField(this, spinner, spinner.getSelectedView()); }
	public void setStandardArrow(ColorStateList color) { standardArrow = color; invalidate(); }
	@Override public void setEnabled(boolean enabled) {
		super.setEnabled(enabled);
		if (spinner != null) {
			spinner.setEnabled(enabled);
			if (spinner.getSelectedView() != null) spinner.getSelectedView().setEnabled(enabled);
		}
	}
	@Override protected void onAttachedToWindow() { super.onAttachedToWindow(); refreshAppearance(); }
	@Override public void draw(Canvas canvas) {
		super.draw(canvas);
		if (standardArrow == null) return;
		float density = ResourceUtils.obtainDensity(this);
		float x = getLayoutDirection() == LAYOUT_DIRECTION_RTL ? 20 * density : getWidth() - 20 * density;
		float y = getHeight() / 2f;
		arrowPaint.setColor(standardArrow.getColorForState(getDrawableState(), standardArrow.getDefaultColor()));
		arrowPaint.setStyle(Paint.Style.STROKE); arrowPaint.setStrokeWidth(2 * density);
		arrowPaint.setStrokeCap(Paint.Cap.ROUND); arrowPaint.setStrokeJoin(Paint.Join.ROUND);
		arrowPath.reset(); arrowPath.moveTo(x - 4 * density, y - 2 * density);
		arrowPath.lineTo(x, y + 2 * density); arrowPath.lineTo(x + 4 * density, y - 2 * density);
		canvas.drawPath(arrowPath, arrowPaint);
	}

	public void setItems(Collection<? extends CharSequence> collection) {
		ArrayAdapter<CharSequence> adapter = new ArrayAdapter<CharSequence>(getContext(),
				android.R.layout.simple_spinner_item) {
			@Override
			public View getView(int position, View convertView, @NonNull ViewGroup parent) {
				if (convertView == null && factory != null) {
					convertView = factory.newInstance();
				}
				View selected = super.getView(position, convertView, parent);
				selected.setEnabled(spinner.isEnabled());
				InterfaceAppearance.standardDropdownField(DropdownView.this, spinner, selected);
				return selected;
			}
		};
		adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
		adapter.addAll(collection);
		spinner.setAdapter(adapter);
		refreshAppearance();
	}

	public void setSelection(int position) {
		spinner.setSelection(position);
	}

	public int getSelectedItemPosition() {
		return spinner.getSelectedItemPosition();
	}
}
