package com.mishiranu.dashchan.widget;

import android.annotation.SuppressLint;
import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.Rect;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.os.Parcel;
import android.os.Parcelable;
import android.widget.CheckBox;
import androidx.core.view.ViewCompat;
import com.mishiranu.dashchan.R;
import com.mishiranu.dashchan.util.OpaqueColors;
import com.mishiranu.dashchan.util.ResourceUtils;
import java.util.Arrays;

/** Label-free tri-state presentation. CheckBox retains click, key and accessibility semantics. */
// Like the other native widgets in this app, this control deliberately uses the framework
// theme (minSdk 30). Its glyph and ripple are drawn here; no AppCompat tint adapter is needed.
@SuppressLint("AppCompatCustomView")
public final class ScopeCheckBox extends CheckBox {
	private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
	private final Path path = new Path();
	private final Rect glyph = new Rect();
	private int[] colors;
	private boolean mixed;

	public ScopeCheckBox(Context context) {
		super(context);
		ThemeEngine.applyStyle(this);
		setButtonDrawable(null);
		setText(null);
		setPadding(0, 0, 0, 0);
		setMinWidth(0); setMinHeight(0);
		setMinimumWidth(dp(48)); setMinimumHeight(dp(48));
		refreshAppearance();
	}

	public void refreshAppearance() {
		int[] next = InterfaceAppearance.standardScopeColors(getContext());
		if (next == null) {
			ThemeEngine.Theme theme = ThemeEngine.getTheme(getContext());
			int surface = ResourceUtils.getColor(getContext(), android.R.attr.colorBackground);
			next = new int[] {ResourceUtils.getColor(getContext(), android.R.attr.textColorSecondary),
					theme.accent, OpaqueColors.readable(Color.WHITE, theme.accent, 3,
					OpaqueColors.Policy.INTERFACE_COMPATIBILITY), surface, (theme.accent & 0xffffff) | 0x26000000};
		}
		if (Arrays.equals(colors, next)) return;
		colors = next;
		GradientDrawable mask = new GradientDrawable(); mask.setColor(Color.WHITE); mask.setCornerRadius(dp(24));
		setBackgroundTintList(null);
		setBackground(new RippleDrawable(ColorStateList.valueOf(colors[4]), null, mask));
		invalidate();
	}

	public void setMixed(boolean value) {
		if (mixed != value) { mixed = value; invalidate(); }
		updateStateDescription();
	}
	public boolean isMixed() { return mixed; }
	@Override public void setChecked(boolean value) { super.setChecked(value); updateStateDescription(); }
	private void updateStateDescription() {
		ViewCompat.setStateDescription(this, getContext().getText(mixed ? R.string.partially_enabled
				: isChecked() ? R.string.enabled : R.string.disabled));
	}
	private int dp(int value) { return Math.round(value * ResourceUtils.obtainDensity(this)); }
	public void getGlyphBounds(Rect out) {
		int size = dp(24), left = (getWidth() - size) / 2, top = (getHeight() - size) / 2;
		out.set(left, top, left + size, top + size);
	}
	@Override protected void onAttachedToWindow() { super.onAttachedToWindow(); refreshAppearance(); }
	@Override protected void onDraw(Canvas canvas) {
		super.onDraw(canvas);
		if (colors == null) return;
		getGlyphBounds(glyph);
		int save = canvas.save(); canvas.translate(glyph.left, glyph.top);
		canvas.scale(glyph.width() / 24f, glyph.height() / 24f);
		boolean filled = mixed || isChecked();
		int color = colors[filled ? 1 : 0];
		paint.setColor(isEnabled() ? color : OpaqueColors.blend(colors[3], color, .38f));
		paint.setStyle(filled ? Paint.Style.FILL : Paint.Style.STROKE);
		paint.setStrokeWidth(2); paint.setStrokeCap(Paint.Cap.ROUND); paint.setStrokeJoin(Paint.Join.ROUND);
		canvas.drawRoundRect(3, 3, 21, 21, 4, 4, paint);
		if (filled) {
			paint.setColor(isEnabled() ? colors[2] : colors[3]); paint.setStyle(Paint.Style.STROKE);
			if (mixed) canvas.drawLine(7, 12, 17, 12, paint);
			else { path.reset(); path.moveTo(7, 12); path.lineTo(10.5f, 15.5f); path.lineTo(17, 9); canvas.drawPath(path, paint); }
		}
		canvas.restoreToCount(save);
	}
	@Override public Parcelable onSaveInstanceState() {
		SavedState state = new SavedState(super.onSaveInstanceState()); state.mixed = mixed; return state;
	}
	@Override public void onRestoreInstanceState(Parcelable value) {
		if (!(value instanceof SavedState)) { super.onRestoreInstanceState(value); return; }
		SavedState state = (SavedState) value; super.onRestoreInstanceState(state.getSuperState()); setMixed(state.mixed);
	}
	public static final class SavedState extends BaseSavedState {
		boolean mixed;
		SavedState(Parcelable state) { super(state); }
		private SavedState(Parcel in) { super(in); mixed = in.readInt() != 0; }
		@Override public void writeToParcel(Parcel out, int flags) { super.writeToParcel(out, flags); out.writeInt(mixed ? 1 : 0); }
		public static final Parcelable.Creator<SavedState> CREATOR = new Parcelable.Creator<SavedState>() {
			@Override public SavedState createFromParcel(Parcel in) { return new SavedState(in); }
			@Override public SavedState[] newArray(int size) { return new SavedState[size]; }
		};
	}
}
