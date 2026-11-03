package com.mishiranu.dashchan.widget;

import android.annotation.SuppressLint;
import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Rect;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.os.Parcel;
import android.os.Parcelable;
import android.view.View;
import android.widget.CheckBox;
import androidx.core.view.AccessibilityDelegateCompat;
import androidx.core.view.ViewCompat;
import androidx.core.view.accessibility.AccessibilityNodeInfoCompat;
import com.mishiranu.dashchan.R;
import com.mishiranu.dashchan.util.ResourceUtils;
import java.util.Arrays;

/** Label-free tri-state presentation. CheckBox retains click, key and accessibility semantics. */
// Like the other native widgets in this app, this control deliberately uses the framework
// theme (minSdk 30). Its glyph and ripple are drawn here; no AppCompat tint adapter is needed.
@SuppressLint("AppCompatCustomView")
public final class ScopeCheckBox extends CheckBox {
	private final ChoiceIndicatorDrawable indicator = new ChoiceIndicatorDrawable(this, false);
	private final Rect glyph = new Rect();
	private int[] colors;
	private boolean mixed;
	private final boolean drawerPalette;

	public ScopeCheckBox(Context context) {
		this(context, false);
	}

	ScopeCheckBox(Context context, boolean drawerPalette) {
		super(context);
		this.drawerPalette = drawerPalette;
		indicator.setStateColors(() -> InterfaceAppearance.scopeColors(this, drawerPalette));
		ThemeEngine.applyStyle(this);
		setButtonDrawable(null);
		setText(null);
		setPadding(0, 0, 0, 0);
		setMinWidth(0); setMinHeight(0);
		setMinimumWidth(dp(48)); setMinimumHeight(dp(48));
		refreshAppearance();
	}

	public void refreshAppearance() {
		refreshAppearance(InterfaceAppearance.isEnabled(getContext()));
	}
	void refreshAppearance(boolean material) {
		// Indicator design is global, independently of the surrounding interface preference.
		int[] next = InterfaceAppearance.scopeColors(this, drawerPalette);
		if (Arrays.equals(colors, next)) return;
		colors = next;
		indicator.setColors(colors);
		GradientDrawable mask = new GradientDrawable(); mask.setColor(Color.WHITE); mask.setCornerRadius(dp(24));
		setBackgroundTintList(null);
		setBackground(new RippleDrawable(ColorStateList.valueOf(colors[4]), null, mask));
		invalidate();
	}

	/** The native OFF profile and the centered drawer glyph share the existing 48dp column. */
	public static CheckBox createDrawerSelection(Context context) {
		return createDrawerSelection(context, InterfaceAppearance.isEnabled(context));
	}
	static CheckBox createDrawerSelection(Context context, boolean material) {
		CheckBox result = material ? new ScopeCheckBox(context, true) : new CheckBox(context);
		if (!material) ThemeEngine.applyStyle(result);
		result.setClickable(false); result.setFocusable(false);
		result.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
		return result;
	}

	/** Install once at row creation. The row retains its existing click/long-click ownership. */
	public static void attachDrawerSelection(View row, CheckBox indicator) {
		ViewCompat.setAccessibilityDelegate(row, new AccessibilityDelegateCompat() {
			@Override public void onInitializeAccessibilityNodeInfo(View host, AccessibilityNodeInfoCompat info) {
				super.onInitializeAccessibilityNodeInfo(host, info);
				boolean selectable = indicator.getVisibility() == View.VISIBLE;
				info.setCheckable(selectable);
				info.setChecked(selectable && indicator.isChecked());
				info.setSelected(selectable && indicator.isChecked());
			}
		});
	}

	public static void bindDrawerSelection(View row, CheckBox indicator, boolean selectable, boolean selected) {
		indicator.setVisibility(selectable ? View.VISIBLE : View.GONE);
		if (indicator instanceof ScopeCheckBox) {
			ScopeCheckBox scope = (ScopeCheckBox) indicator;
			scope.setMixed(false); scope.refreshAppearance();
		}
		indicator.setChecked(selectable && selected);
		ViewCompat.setScreenReaderFocusable(row, selectable);
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
		indicator.setBounds(glyph); indicator.setState(getDrawableState()); indicator.setMixed(mixed); indicator.draw(canvas);
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
