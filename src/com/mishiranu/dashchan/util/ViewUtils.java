package com.mishiranu.dashchan.util;

import android.app.AlertDialog;
import android.content.Context;
import android.content.DialogInterface;
import android.content.res.Configuration;
import android.graphics.Canvas;
import android.graphics.Insets;
import android.graphics.Outline;
import android.graphics.Paint;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffXfermode;
import android.graphics.Rect;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.text.Editable;
import android.text.TextWatcher;
import android.util.TypedValue;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewOutlineProvider;
import android.view.ViewParent;
import android.view.Window;
import android.view.WindowInsets;
import android.view.WindowManager;
import android.widget.EdgeEffect;
import android.widget.EditText;
import android.widget.ScrollView;
import android.widget.TextView;
import androidx.core.view.ViewCompat;
import com.mishiranu.dashchan.R;
import java.util.ArrayList;

public class ViewUtils {
	public static final int STATUS_OVERLAY_TRANSPARENT = 0x4d000000;

	@SuppressWarnings("deprecation")
	public static final int SOFT_INPUT_ADJUST_RESIZE_COMPAT = WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE;

	public static final DialogInterface.OnShowListener ALERT_DIALOG_LONGER_TITLE = dialog -> {
		if (dialog instanceof AlertDialog) {
			View view = ((AlertDialog) dialog).getWindow().getDecorView();
			int id = view.getResources().getIdentifier("alertTitle", "id", "android");
			if (id == 0) {
				id = android.R.id.title;
			}
			View titleView = view.findViewById(id);
			if (titleView instanceof TextView) {
				TextView textView = (TextView) titleView;
				int maxLines = textView.getMaxLines();
				if (maxLines > 0 && maxLines < 4) {
					textView.setMaxLines(4);
				}
			}
		}
	};

	public static void removeFromParent(View view) {
		ViewParent viewParent = view.getParent();
		if (viewParent instanceof ViewGroup) {
			((ViewGroup) viewParent).removeView(view);
		}
	}

	public static boolean isDrawerLockable(Configuration configuration) {
		// Should always result "true" for tablets in landscape mode (+ in portrait mode on large screens).
		// Sometimes it will result "true" for screens with low DPI configuration, which is intentional.
		return configuration.screenWidthDp >= 720;
	}

	public static boolean isGestureNavigationOverlap(View view, boolean checkLeft, boolean checkRight) {
		WindowInsets windowInsets = view.getRootWindowInsets();
		Insets insets = windowInsets.getInsets(WindowInsets.Type.systemGestures());
		if (checkLeft && insets.left > 0 || checkRight && insets.right > 0) {
			int left = view.getLeft();
			View parentView = (View) view.getParent();
			while (true) {
				left += parentView.getLeft();
				ViewParent parent = parentView.getParent();
				if (parent instanceof View) {
					parentView = (View) parent;
				} else {
					break;
				}
			}
			int right = parentView.getWidth() - left - view.getWidth();
			return checkLeft && insets.left > left || checkRight && insets.right > right;
		}
		return false;
	}

	public static void setTextSizeScaled(TextView textView, int sizeSp) {
		// Avoid fractional sizes (the same logic is used for sizes specified in XML)
		int sizePx = (int) (TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, sizeSp,
				textView.getResources().getDisplayMetrics()) + 0.5f);
		textView.setTextSize(TypedValue.COMPLEX_UNIT_PX, sizePx);
	}

	public static void applyScaleSize(float scale, View... views) {
		for (View view : views) {
			if (view != null) {
				if (view instanceof TextView) {
					TextView textView = (TextView) view;
					int size = (int) (textView.getTextSize() * scale + 0.5f);
					textView.setTextSize(TypedValue.COMPLEX_UNIT_PX, size);
				}
				ViewGroup.LayoutParams params = view.getLayoutParams();
				if (params != null) {
					if (params.width > 0) {
						params.width = (int) (params.width * scale);
					}
					if (params.height > 0) {
						params.height = (int) (params.height * scale);
					}
				}
			}
		}
	}

	public static void applyScaleMarginLR(float scale, View... views) {
		for (View view : views) {
			if (view != null) {
				ViewGroup.LayoutParams params = view.getLayoutParams();
				if (params instanceof ViewGroup.MarginLayoutParams) {
					ViewGroup.MarginLayoutParams marginParams = (ViewGroup.MarginLayoutParams) params;
					marginParams.leftMargin = (int) (marginParams.leftMargin * scale);
					marginParams.rightMargin = (int) (marginParams.rightMargin * scale);
				}
			}
		}
	}

	public static void makeRoundedCorners(View view, final int radius, final boolean withPaddings) {
		view.setClipToOutline(true);
		view.setOutlineProvider(new ViewOutlineProvider() {
			private final Rect rect = new Rect();

			@Override
			public void getOutline(View view, Outline outline) {
				Rect rect = this.rect;
				if (withPaddings) {
					rect.set(view.getPaddingLeft(), view.getPaddingTop(), view.getWidth() - view.getPaddingRight(),
							view.getHeight() - view.getPaddingBottom());
				} else {
					rect.set(0, 0, view.getWidth(), view.getHeight());
				}
				outline.setRoundRect(rect, radius);
			}
		});
	}

	public static void setEdgeEffectColor(ScrollView scrollView, int color) {
		scrollView.setEdgeEffectColor(color);
	}

	public static void setEdgeEffectColor(EdgeEffect edgeEffect, int color) {
		edgeEffect.setColor(color);
	}

	public static void setSelectableItemBackground(View view) {
		setBackgroundPreservePadding(view, ResourceUtils
				.getDrawable(view.getContext(), android.R.attr.selectableItemBackground, 0));
	}

	public static void setBackgroundPreservePadding(View view, Drawable drawable) {
		// Setting background drawable may reset padding
		int left = view.getPaddingLeft();
		int top = view.getPaddingTop();
		int right = view.getPaddingRight();
		int bottom = view.getPaddingBottom();
		view.setBackground(drawable);
		view.setPadding(left, top, right, bottom);
	}

	public static void setNewPadding(View view, Integer left, Integer top, Integer right, Integer bottom) {
		int oldLeft = view.getPaddingLeft();
		int oldTop = view.getPaddingTop();
		int oldRight = view.getPaddingRight();
		int oldBottom = view.getPaddingBottom();
		int newLeft = left != null ? left : oldLeft;
		int newTop = top != null ? top : oldTop;
		int newRight = right != null ? right : oldRight;
		int newBottom = bottom != null ? bottom : oldBottom;
		if (oldLeft != newLeft || oldTop != newTop || oldRight != newRight || oldBottom != newBottom) {
			view.setPadding(newLeft, newTop, newRight, newBottom);
		}
	}

	public static void setNewMargin(View view, Integer left, Integer top, Integer right, Integer bottom) {
		ViewGroup.MarginLayoutParams layoutParams = (ViewGroup.MarginLayoutParams) view.getLayoutParams();
		boolean changed = false;
		if (left != null && layoutParams.leftMargin != left) {
			layoutParams.leftMargin = left;
			changed = true;
		}
		if (top != null && layoutParams.topMargin != top) {
			layoutParams.topMargin = top;
			changed = true;
		}
		if (right != null && layoutParams.rightMargin != right) {
			layoutParams.rightMargin = right;
			changed = true;
		}
		if (bottom != null && layoutParams.bottomMargin != bottom) {
			layoutParams.bottomMargin = bottom;
			changed = true;
		}
		if (changed) {
			view.requestLayout();
		}
	}

	public static void setNewMarginRelative(View view, Integer start, Integer top, Integer end, Integer bottom) {
		if (ViewCompat.getLayoutDirection(view) == ViewCompat.LAYOUT_DIRECTION_RTL) {
			setNewMargin(view, end, top, start, bottom);
		} else {
			setNewMargin(view, start, top, end, bottom);
		}
	}

	public static void applyMonospaceTypeface(EditText editText) {
		Typeface initialTypeface = editText.getTypeface();
		Typeface monospaceTypeface = Typeface.MONOSPACE;
		boolean[] empty = {true};
		TextWatcher textWatcher = new TextWatcher() {
			@Override
			public void beforeTextChanged(CharSequence s, int start, int count, int after) {}

			@Override
			public void onTextChanged(CharSequence s, int start, int before, int count) {}

			@Override
			public void afterTextChanged(Editable s) {
				boolean newEmpty = s.length() == 0;
				if (newEmpty != empty[0]) {
					empty[0] = newEmpty;
					editText.setTypeface(newEmpty ? initialTypeface : monospaceTypeface);
				}
			}
		};
		textWatcher.afterTextChanged(editText.getText());
		editText.addTextChangedListener(textWatcher);
	}

	public static void setWindowLayoutFullscreen(Window window) {
		setDecorFitsSystemWindows(window, false);
	}

	@SuppressWarnings("deprecation")
	private static void setDecorFitsSystemWindows(Window window, boolean decorFitsSystemWindows) {
		// Edge-to-edge behavior is targetSdk-sensitive; keep the R-era behavior until that migration.
		window.setDecorFitsSystemWindows(decorFitsSystemWindows);
	}

	@SuppressWarnings("deprecation")
	public static int getStatusBarColor(Window window) {
		// Window color APIs are kept for targetSdk 30 system-bars behavior.
		return window.getStatusBarColor();
	}

	@SuppressWarnings("deprecation")
	public static int getNavigationBarColor(Window window) {
		// Window color APIs are kept for targetSdk 30 system-bars behavior.
		return window.getNavigationBarColor();
	}

	@SuppressWarnings("deprecation")
	public static void setStatusBarColor(Window window, int color) {
		// Window color APIs are kept for targetSdk 30 system-bars behavior.
		window.setStatusBarColor(color);
	}

	@SuppressWarnings("deprecation")
	public static void setNavigationBarColor(Window window, int color) {
		// Window color APIs are kept for targetSdk 30 system-bars behavior.
		window.setNavigationBarColor(color);
	}

	public static void drawSystemInsetsOver(View view, Canvas canvas, boolean gestureNavigation) {
		Paint paint = (Paint) view.getTag(R.id.tag_insets_draw_data);
		if (paint == null) {
			paint = new Paint(Paint.ANTI_ALIAS_FLAG);
			paint.setColor(STATUS_OVERLAY_TRANSPARENT);
			paint.setXfermode(new PorterDuffXfermode(PorterDuff.Mode.SRC_ATOP));
			view.setTag(R.id.tag_insets_draw_data, paint);
		}
		int x = view.getScrollX();
		int y = view.getScrollY();
		boolean translate = x != 0 || y != 0;
		if (translate) {
			canvas.save();
			canvas.translate(x, y);
		}
		int left = view.getPaddingLeft();
		int top = view.getPaddingTop();
		int right = view.getPaddingRight();
		int bottom = gestureNavigation ? 0 : view.getPaddingBottom();
		int width = view.getWidth();
		int height = view.getHeight();
		// Draw system insets over dialogs
		canvas.drawRect(0, 0, width, top, paint);
		canvas.drawRect(0, height - bottom, width, height, paint);
		canvas.drawRect(0, top, left, height - bottom, paint);
		canvas.drawRect(width - right, top, width, height - bottom, paint);
		if (translate) {
			canvas.restore();
		}
	}

	public static View getDecorView(View view) {
		View decorView = view;
		while (true) {
			ViewParent viewParent = decorView.getParent();
			if (viewParent instanceof View) {
				decorView = (View) viewParent;
			} else {
				break;
			}
		}
		return decorView;
	}

	private static class WindowFocusListenerView extends View {
		private final ArrayList<OnFocusChangeListener> listeners = new ArrayList<>();

		public WindowFocusListenerView(Context context) {
			super(context);
		}

		@Override
		public void onWindowFocusChanged(boolean hasWindowFocus) {
			super.onWindowFocusChanged(hasWindowFocus);
			for (OnFocusChangeListener listener : listeners) {
				listener.onFocusChange((View) getParent(), hasWindowFocus);
			}
		}

		private static WindowFocusListenerView get(View view, boolean create) {
			ViewGroup decorView = (ViewGroup) getDecorView(view);
			int childCount = decorView.getChildCount();
			for (int i = 0; i < childCount; i++) {
				View child = decorView.getChildAt(i);
				if (child instanceof WindowFocusListenerView) {
					return (WindowFocusListenerView) child;
				}
			}
			if (create) {
				WindowFocusListenerView listenerView = new WindowFocusListenerView(decorView.getContext());
				decorView.addView(listenerView, 0, 0);
				return listenerView;
			}
			return null;
		}
	}

	public static void addWindowFocusListener(View view, View.OnFocusChangeListener listener) {
		WindowFocusListenerView listenerView = WindowFocusListenerView.get(view, true);
		listenerView.listeners.add(listener);
	}

	public static void removeWindowFocusListener(View view, View.OnFocusChangeListener listener) {
		WindowFocusListenerView listenerView = WindowFocusListenerView.get(view, false);
		if (listenerView != null) {
			listenerView.listeners.remove(listener);
		}
	}

	public static boolean getOutlineRect(Outline outline, Rect outRect) {
		return outline.getRect(outRect);
	}

	public static float getOutlineRadius(Outline outline) {
		return outline.getRadius();
	}
}
