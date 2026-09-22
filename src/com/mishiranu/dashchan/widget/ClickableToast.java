package com.mishiranu.dashchan.widget;

import android.annotation.SuppressLint;
import android.app.AlertDialog;
import android.content.Context;
import android.content.res.Resources;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.ColorFilter;
import android.graphics.PixelFormat;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffColorFilter;
import android.graphics.Rect;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.view.accessibility.AccessibilityManager;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;
import androidx.activity.ComponentActivity;
import androidx.annotation.NonNull;
import androidx.core.graphics.ColorUtils;
import androidx.core.widget.TextViewCompat;
import androidx.lifecycle.DefaultLifecycleObserver;
import androidx.lifecycle.Lifecycle;
import androidx.lifecycle.LifecycleOwner;
import com.mishiranu.dashchan.R;
import com.mishiranu.dashchan.content.model.ErrorItem;
import com.mishiranu.dashchan.graphics.BaseDrawable;
import com.mishiranu.dashchan.util.ConcurrentUtils;
import com.mishiranu.dashchan.util.FlagUtils;
import com.mishiranu.dashchan.util.GraphicsUtils;
import com.mishiranu.dashchan.util.ResourceUtils;
import com.mishiranu.dashchan.util.ViewUtils;
import java.lang.ref.WeakReference;
import java.lang.reflect.Field;
import java.util.Objects;
import java.util.UUID;

public class ClickableToast implements DefaultLifecycleObserver {
	private static final int Y_OFFSET;
	private static final int LAYOUT_ID;

	private static final int TIMEOUT = 3500;

	static {
		Resources resources = Resources.getSystem();
		Y_OFFSET = resources.getDimensionPixelSize(resources.getIdentifier("toast_y_offset", "dimen", "android"));
		LAYOUT_ID = resources.getIdentifier("transient_notification", "layout", "android");
	}

	private final ComponentActivity activity;
	private final WindowManager windowManager;
	private final View container;

	private final PartialClickDrawable partialClickDrawable;
	private final TextView message;
	private final TextView button;

	private ViewGroup currentContainer;
	private AlertDialog overflowDialog;
	private Runnable onClickListener;
	private String showing;
	private boolean clickable;
	private boolean realClickable;
	private boolean clickableOnlyWhenRoot;

	private boolean resumed;
	private int diagnosticId;
	private boolean diagnosticHardwareCanvas;

	private static WeakReference<ComponentActivity> currentActivity;

	private static View getTagView(ComponentActivity activity) {
		return activity.getWindow().getDecorView();
	}

	private static ClickableToast getToast(ComponentActivity activity) {
		ClickableToast toast = (ClickableToast) getTagView(activity).getTag(R.id.tag_clickable_toast);
		return toast != null && toast.isForActivity(activity) ? toast : null;
	}

	private static ClickableToast getCurrentToast() {
		if (currentActivity != null) {
			ComponentActivity activity = currentActivity.get();
			return activity != null ? getToast(activity) : null;
		}
		return null;
	}

	public static void register(@NonNull ComponentActivity activity) {
		Objects.requireNonNull(activity);
		if (currentActivity != null) {
			ComponentActivity oldActivity = currentActivity.get();
			if (oldActivity == activity) {
				return;
			}
			if (oldActivity != null) {
				getToast(oldActivity).cancelInternal();
			}
		}
		currentActivity = null;
		Lifecycle.State state = activity.getLifecycle().getCurrentState();
		if (state.isAtLeast(Lifecycle.State.INITIALIZED)) {
			if (getToast(activity) == null) {
				getTagView(activity).setTag(R.id.tag_clickable_toast, new ClickableToast(activity));
			}
			currentActivity = new WeakReference<>(activity);
		}
	}

	public static class Button {
		private final int titleResId;
		private final boolean clickableOnlyWhenRoot;
		private final Runnable callback;

		public Button(int titleResId, boolean clickableOnlyWhenRoot, Runnable callback) {
			this.titleResId = titleResId;
			this.clickableOnlyWhenRoot = clickableOnlyWhenRoot;
			this.callback = callback;
		}
	}

	public static String show(int message) {
		return show(new ErrorItem(message));
	}

	public static String show(ErrorItem errorItem) {
		return show((errorItem != null ? errorItem : new ErrorItem(ErrorItem.Type.UNKNOWN)).toString());
	}

	public static String show(CharSequence message) {
		return show(message, null, null);
	}

	public static String show(CharSequence message, String updateId, Button button) {
		if (ConcurrentUtils.isMain()) {
			ClickableToast toast = getCurrentToast();
			if (toast != null) {
				return toast.showInternal(message, updateId, button, false);
			} else {
				return null;
			}
		} else {
			return ConcurrentUtils.mainGet(() -> show(message, updateId, button));
		}
	}

	public static void showDiagnosticTest() {
		if (!ConcurrentUtils.isMain()) {
			ConcurrentUtils.HANDLER.post(ClickableToast::showDiagnosticTest);
			return;
		}
		ClickableToast toast = getCurrentToast();
		if (toast != null) {
			ToastDiagnostics.start(toast.activity, LAYOUT_ID);
			toast.showInternal(toast.activity.getResources().getQuantityString(
					R.plurals.number_new_posts__format, 1, 1), null,
					new Button(R.string.show, true, () -> ToastDiagnostics.event(toast.diagnosticId,
							"test_action_clicked navigation=false")), true);
		}
	}

	public static boolean isShowing(String id) {
		ClickableToast toast = getCurrentToast();
		return toast != null && id != null && id.equals(toast.showing);
	}

	public static void cancel() {
		ClickableToast toast = getCurrentToast();
		if (toast != null) {
			toast.cancelInternal();
		}
	}

	private ClickableToast(ComponentActivity activity) {
		this.activity = activity;
		windowManager = (WindowManager) activity.getSystemService(Context.WINDOW_SERVICE);
		ViewUtils.addWindowFocusListener(getTagView(activity), windowFocusListener);

		activity.getLifecycle().addObserver(this);
		resumed = activity.getLifecycle().getCurrentState() == Lifecycle.State.RESUMED;
		float density = ResourceUtils.obtainDensity(activity);
		int innerPadding = (int) (8f * density);
		LayoutInflater inflater = LayoutInflater.from(activity);
		View toast1 = inflater.inflate(LAYOUT_ID, null);
		View toast2 = inflater.inflate(LAYOUT_ID, null);
		TextView message1 = toast1.findViewById(android.R.id.message);
		TextView message2 = toast2.findViewById(android.R.id.message);
		// Keep the OEM shape, but resolve both foreground and background from the same app theme.
		TextViewCompat.setTextAppearance(message1, R.style.ClickableToastTextAppearance);
		TextViewCompat.setTextAppearance(message2, R.style.ClickableToastTextAppearance);
		message1.setShadowLayer(0f, 0f, 0f, 0);
		message2.setShadowLayer(0f, 0f, 0f, 0);
		Drawable backgroundDrawable = toast1.getBackground();
		View backgroundView = toast1;
		if (backgroundDrawable == null) {
			View view = message1;
			while (view != null) {
				backgroundDrawable = view.getBackground();
				if (backgroundDrawable != null) {
					backgroundView = view;
					break;
				}
				view = (View) view.getParent();
			}
		}

		// Make long text to avoid minimum widths
		StringBuilder builder = new StringBuilder();
		for (int i = 0; i < 100; i++) {
			builder.append('W');
		}
		message1.setText(builder);
		int measureSize = ViewUtils.getWindowContentSize(activity).x;
		toast1.measure(View.MeasureSpec.makeMeasureSpec(measureSize, View.MeasureSpec.AT_MOST),
				View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED));
		int lineCount = message1.getLayout().getLineCount();
		if (lineCount >= 2) {
			builder.setLength(message1.getLayout().getLineEnd(0));
			message1.setText(builder);
			toast1.measure(View.MeasureSpec.makeMeasureSpec(measureSize, View.MeasureSpec.AT_MOST),
					View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED));
		}
		toast1.layout(0, 0, toast1.getMeasuredWidth(), toast1.getMeasuredHeight());
		Rect totalPadding = new Rect(message1.getPaddingLeft(), message1.getPaddingTop(),
				message1.getPaddingRight(), message1.getPaddingBottom());
		int messageMeasuredHeight = message1.getHeight();
		View measureView = message1;
		while (true) {
			View parent = (View) measureView.getParent();
			if (parent == null || backgroundDrawable != null && measureView == backgroundView) {
				break;
			}
			totalPadding.left += measureView.getLeft();
			totalPadding.top += measureView.getTop();
			totalPadding.right += parent.getWidth() - measureView.getRight();
			totalPadding.bottom += parent.getHeight() - measureView.getBottom();
			measureView = parent;
		}
		message1.measure(View.MeasureSpec.makeMeasureSpec(message1.getWidth(), View.MeasureSpec.EXACTLY),
				View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED));
		int extraHeight = messageMeasuredHeight - message1.getMeasuredHeight();
		totalPadding.top += extraHeight / 2;
		totalPadding.bottom += extraHeight / 2;
		int horizontalPadding = Math.max(totalPadding.left, totalPadding.right);

		ViewUtils.removeFromParent(message1);
		ViewUtils.removeFromParent(message2);
		LinearLayout linearLayout = new LinearLayout(activity) {
			@Override
			protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
				// Keep wrapping inside the current window, including narrow multi-window layouts.
				int width = Math.max(1, ViewUtils.getWindowContentSize(activity).x - (int) (32f * density));
				if (MeasureSpec.getMode(widthMeasureSpec) != MeasureSpec.UNSPECIFIED) {
					width = Math.min(width, MeasureSpec.getSize(widthMeasureSpec));
				}
				int contentWidth = Math.max(1, width - getPaddingLeft() - getPaddingRight());
				// A long translated action label must leave room for the actual message.
				message2.setMaxWidth(Math.max(1, contentWidth / 3));
				super.onMeasure(MeasureSpec.makeMeasureSpec(width, MeasureSpec.AT_MOST), heightMeasureSpec);
			}
		};
		linearLayout.setOrientation(LinearLayout.HORIZONTAL);
		// The palette already follows the app theme; do not let system night mode invert it again.
		linearLayout.setForceDarkAllowed(false);
		linearLayout.setGravity(Gravity.CENTER_VERTICAL);
		linearLayout.setDividerDrawable(new ToastDividerDrawable(message1.getTextColors().getDefaultColor(),
				(int) (density + 0.5f)));
		linearLayout.setShowDividers(LinearLayout.SHOW_DIVIDER_MIDDLE);
		linearLayout.setDividerPadding((int) (4f * density));
		linearLayout.setTag(this);
		linearLayout.addView(message1, LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
		linearLayout.addView(message2, LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
		((LinearLayout.LayoutParams) message1.getLayoutParams()).weight = 1f;
		linearLayout.setPadding(horizontalPadding, totalPadding.top, horizontalPadding, totalPadding.bottom);

		int backgroundColor = ResourceUtils.getColor(activity, R.attr.colorClickableToastBackground);
		if (backgroundDrawable == null) {
			GradientDrawable fallback = new GradientDrawable();
			fallback.setColor(backgroundColor);
			fallback.setCornerRadius(8f * density);
			backgroundDrawable = fallback;
		}
		partialClickDrawable = new PartialClickDrawable(backgroundDrawable.mutate(), backgroundColor);
		message1.setBackground(null);
		message2.setBackground(null);
		linearLayout.setBackground(partialClickDrawable);
		linearLayout.setOnTouchListener(partialClickDrawable);
		message1.setPadding(0, 0, 0, 0);
		message2.setPaddingRelative(innerPadding, 0, 0, 0);
		// Reset limits inherited from the system/OEM toast template for both labels.
		message1.setSingleLine(false);
		message2.setSingleLine(false);
		message1.setMaxLines(Integer.MAX_VALUE);
		message2.setMaxLines(Integer.MAX_VALUE);
		message1.setEllipsize(null);
		message2.setEllipsize(null);
		container = linearLayout;
		message = message1;
		button = message2;
	}

	@Override
	public void onResume(@NonNull LifecycleOwner owner) {
		resumed = true;
		updateAndApplyLayoutChecked();
	}

	@Override
	public void onPause(@NonNull LifecycleOwner owner) {
		resumed = false;
		updateAndApplyLayoutChecked();
	}

	@Override
	public void onDestroy(@NonNull LifecycleOwner owner) {
		if (currentActivity != null && currentActivity.get() == owner) {
			currentActivity = null;
		}
		cancelInternal();
		// Unbind toast from DecorView, which may be reused on configuration change
		View tagView = getTagView((ComponentActivity) owner);
		if (tagView.getTag(R.id.tag_clickable_toast) == this) {
			tagView.setTag(R.id.tag_clickable_toast, null);
		}
		ViewUtils.removeWindowFocusListener(tagView, windowFocusListener);
	}

	private boolean isForActivity(ComponentActivity activity) {
		// Toast is bound to DecorView, which may be reused on configuration change
		return this.activity == activity;
	}

	private final View.OnFocusChangeListener windowFocusListener = (v, hasFocus) -> updateAndApplyLayoutChecked();

	private String showInternal(CharSequence message, String updateId, Button button, boolean diagnosticTest) {
		boolean update = updateId != null && updateId.equals(showing);
		if (update) {
			ConcurrentUtils.HANDLER.removeCallbacks(cancelRunnable);
		} else {
			cancelInternal();
		}
		diagnosticId = ToastDiagnostics.next();
		ToastDiagnostics.source(diagnosticId);
		diagnosticHardwareCanvas = false;
		ToastDiagnostics.event(diagnosticId, "show_requested source=" + (diagnosticTest ? "test" : "application")
				+ " update=" + update + " action=" + (button != null)
				+ " system_layout=" + Integer.toHexString(LAYOUT_ID) + " y_offset=" + Y_OFFSET);
		clickable = button != null;
		this.message.setText(message);
		if (button != null) {
			this.button.setText(button.titleResId);
		}
		onClickListener = button != null ? button.callback : null;
		partialClickDrawable.clicked = false;
		partialClickDrawable.invalidateSelf();
		clickableOnlyWhenRoot = button == null || button.clickableOnlyWhenRoot;
		updateLayout();
		// A transient popup cannot display an arbitrarily large message. Keep the full text in a
		// scrollable standard dialog when it would take up more than half of the available window.
		int availableHeight = Math.max(1, ViewUtils.getWindowContentSize(activity).y - Y_OFFSET);
		container.measure(View.MeasureSpec.makeMeasureSpec(ViewUtils.getWindowContentSize(activity).x,
				View.MeasureSpec.AT_MOST), View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED));
		diagnosticSnapshot("before_show");
		if (overflowDialog != null || container.getMeasuredHeight() > availableHeight / 2) {
			removeCurrentContainer();
			if (overflowDialog != null) {
				overflowDialog.setOnDismissListener(null);
				overflowDialog.dismiss();
			}
			String id = update ? updateId : UUID.randomUUID().toString();
			AlertDialog.Builder builder = new AlertDialog.Builder(activity)
					.setMessage(message).setPositiveButton(android.R.string.ok, null);
			if (realClickable && button != null) {
				Runnable callback = button.callback;
				builder.setNeutralButton(button.titleResId, (dialog, which) -> {
					if (callback != null) callback.run();
				});
			}
			AlertDialog dialog = builder.create();
			overflowDialog = dialog;
			showing = id;
			dialog.setOnDismissListener(dismissed -> {
				if (overflowDialog == dialog) {
					overflowDialog = null;
					cancelInternal();
				}
			});
			try {
				dialog.show();
				ToastDiagnostics.event(diagnosticId, "shown_as_overflow_dialog");
				return id;
			} catch (WindowManager.BadTokenException e) {
				ToastDiagnostics.event(diagnosticId, "dialog_bad_token");
				cancelInternal();
				return null;
			}
		}
		int timeout = Math.max(TIMEOUT, Math.min(10000, this.message.length() * 50));
		AccessibilityManager accessibilityManager = activity.getSystemService(AccessibilityManager.class);
		if (accessibilityManager != null) {
			timeout = accessibilityManager.getRecommendedTimeoutMillis(timeout, AccessibilityManager.FLAG_CONTENT_TEXT
					| (realClickable ? AccessibilityManager.FLAG_CONTENT_CONTROLS : 0));
		}
		ToastDiagnostics.event(diagnosticId, "timeout_ms=" + timeout + " resumed=" + resumed
				+ " action_visible=" + realClickable + " root_only=" + clickableOnlyWhenRoot);
		if (update) {
			applyLayout();
			diagnosticAfterShow();
			ConcurrentUtils.HANDLER.postDelayed(cancelRunnable, timeout);
			return updateId;
		} else if (addContainerToWindowManager()) {
			String id = UUID.randomUUID().toString();
			showing = id;
			diagnosticAfterShow();
			ConcurrentUtils.HANDLER.postDelayed(cancelRunnable, timeout);
			return id;
		} else {
			return null;
		}
	}

	private void diagnosticSnapshot(String phase) {
		ToastDiagnostics.snapshot(diagnosticId, phase, activity, container, message, button,
				currentContainer, partialClickDrawable.drawable, diagnosticHardwareCanvas);
	}

	private void diagnosticAfterShow() {
		if (diagnosticId == 0) return;
		int id = diagnosticId;
		for (long delay : new long[] {250L, 1000L}) {
			ConcurrentUtils.HANDLER.postDelayed(() -> {
				if (diagnosticId == id && showing != null && currentContainer != null) {
					diagnosticSnapshot("after_show_" + delay + "ms");
				}
			}, delay);
		}
	}

	private boolean addContainerToWindowManager() {
		// Keep the toast inside the owning activity. A global application overlay can block trusted touches and
		// makes Android warn that the application is drawing over other apps.
		return addContainerToWindowManager(WindowManager.LayoutParams.TYPE_APPLICATION);
	}

	private boolean addContainerToWindowManager(int type) {
		boolean success = false;
		try {
			currentContainer = new FrameLayout(activity);
			currentContainer.setForceDarkAllowed(false);
			currentContainer.addView(container, new FrameLayout.LayoutParams(FrameLayout.LayoutParams.WRAP_CONTENT,
					FrameLayout.LayoutParams.WRAP_CONTENT));
			windowManager.addView(currentContainer, createLayoutParams(type));
			ToastDiagnostics.event(diagnosticId, "window_added type=" + type);
			success = true;
		} catch (WindowManager.BadTokenException e) {
			ToastDiagnostics.event(diagnosticId, "window_bad_token");
			String errorMessage = e.getMessage();
			if (errorMessage == null || !(errorMessage.contains("permission denied") ||
					errorMessage.contains("has already been added"))) {
				throw e;
			}
		} finally {
			if (!success) {
				removeCurrentContainer();
			}
		}
		return success;
	}

	private WindowManager.LayoutParams updateLayoutParams(WindowManager.LayoutParams layoutParams) {
		layoutParams.flags = FlagUtils.set(layoutParams.flags,
				WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE, !realClickable);
		return layoutParams;
	}

	private WindowManager.LayoutParams createLayoutParams(int type) {
		WindowManager.LayoutParams layoutParams = new WindowManager.LayoutParams();
		layoutParams.type = type;
		layoutParams.format = PixelFormat.TRANSLUCENT;
		layoutParams.width = WindowManager.LayoutParams.WRAP_CONTENT;
		layoutParams.height = WindowManager.LayoutParams.WRAP_CONTENT;
		layoutParams.flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE |
				WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL;
		// For hierarchy viewer (layout inspector)
		layoutParams.setTitle(activity.getPackageName() + "/" + getClass().getName());
		layoutParams.windowAnimations = android.R.style.Animation_Toast;
		layoutParams.gravity = Gravity.CENTER_HORIZONTAL | Gravity.BOTTOM;
		layoutParams.y = Y_OFFSET;
		try {
			Field field = WindowManager.LayoutParams.class.getField("privateFlags");
			// PRIVATE_FLAG_NO_MOVE_ANIMATION == 0x00000040
			field.set(layoutParams, field.getInt(layoutParams) | 0x00000040);
		} catch (Exception e) {
			e.printStackTrace();
		}
		return updateLayoutParams(layoutParams);
	}

	private void updateAndApplyLayoutChecked() {
		if (showing != null && clickable) {
			updateLayout();
			applyLayout();
			ToastDiagnostics.event(diagnosticId, "focus_or_lifecycle_changed resumed=" + resumed
					+ " action_visible=" + realClickable);
		}
	}

	private void updateLayout() {
		boolean focused = activity.getWindow().getDecorView().hasWindowFocus();
		realClickable = clickable && (focused || !clickableOnlyWhenRoot) && resumed;
		button.setVisibility(realClickable ? View.VISIBLE : View.GONE);
		message.setPadding(realClickable ? button.getPaddingRight() : 0, 0,
				realClickable ? button.getPaddingLeft() : 0, 0);
	}

	private void applyLayout() {
		if (currentContainer != null) {
			windowManager.updateViewLayout(currentContainer,
					updateLayoutParams((WindowManager.LayoutParams) currentContainer.getLayoutParams()));
		}
	}

	private void cancelInternal() {
		ConcurrentUtils.HANDLER.removeCallbacks(cancelRunnable);
		ToastDiagnostics.event(diagnosticId, "cancel");
		diagnosticId = 0;
		if (showing == null) {
			return;
		}
		onClickListener = null;
		showing = null;
		if (overflowDialog != null) {
			overflowDialog.setOnDismissListener(null);
			overflowDialog.dismiss();
			overflowDialog = null;
		}
		clickable = false;
		realClickable = false;
		removeCurrentContainer();
	}

	private void removeCurrentContainer() {
		if (currentContainer != null) {
			if (currentContainer.getParent() != null) {
				windowManager.removeViewImmediate(currentContainer);
			}
			currentContainer.removeView(container);
			currentContainer = null;
		}
	}

	private final Runnable cancelRunnable = this::cancelInternal;

	private class PartialClickDrawable extends BaseDrawable implements View.OnTouchListener, Drawable.Callback {
		private final Drawable drawable;
		private final ColorFilter normalColorFilter;
		private final ColorFilter colorFilter;

		private boolean clicked = false;

		public PartialClickDrawable(Drawable drawable, int color) {
			this.drawable = drawable;
			boolean isLight = GraphicsUtils.isLight(color);
			normalColorFilter = new PorterDuffColorFilter(color, PorterDuff.Mode.SRC_IN);
			int pressedColor = ColorUtils.blendARGB(color, isLight ? Color.BLACK : Color.WHITE,
					isLight ? 0.15f : 0.2f);
			colorFilter = new PorterDuffColorFilter(pressedColor, PorterDuff.Mode.SRC_IN);
			drawable.setColorFilter(normalColorFilter);
			drawable.setCallback(this);
		}

		private View getView() {
			return getCallback() instanceof View ? ((View) getCallback()) : null;
		}

		@SuppressLint("ClickableViewAccessibility")
		@Override
		public boolean onTouch(View v, MotionEvent event) {
			if (event.getActionMasked() != MotionEvent.ACTION_MOVE) {
				ToastDiagnostics.event(diagnosticId, "touch action=" + event.getActionMasked()
						+ " enabled=" + realClickable + " pressed=" + clicked);
			}
			if (!realClickable) {
				return false;
			}
			if (event.getAction() == MotionEvent.ACTION_DOWN) {
				if (event.getX() >= button.getLeft()) {
					clicked = true;
					View view = getView();
					if (view != null) {
						view.invalidate();
					}
				}
			}
			if (event.getAction() == MotionEvent.ACTION_UP || event.getAction() == MotionEvent.ACTION_CANCEL) {
				if (clicked) {
					clicked = false;
					View view = getView();
					if (view != null) {
						view.invalidate();
						if (event.getAction() == MotionEvent.ACTION_UP) {
							float x = event.getX(), y = event.getY();
							if (x >= button.getLeft() && x <= view.getWidth() && y >= 0 && y <= view.getHeight()) {
								ConcurrentUtils.HANDLER.removeCallbacks(cancelRunnable);
								ConcurrentUtils.HANDLER.post(cancelRunnable);
								if (onClickListener != null) {
									onClickListener.run();
								}
							}
						}
					}
					return true;
				}
			}
			return clicked;
		}

		@Override
		public void setBounds(int left, int top, int right, int bottom) {
			super.setBounds(left, top, right, bottom);
			drawable.setBounds(left, top, right, bottom);
		}

		@NonNull
		@Override
		public Rect getDirtyBounds() {
			return drawable.getDirtyBounds();
		}

		@Override
		public void draw(@NonNull Canvas canvas) {
			diagnosticHardwareCanvas = canvas.isHardwareAccelerated();
			drawable.draw(canvas);
			if (clicked) {
				drawable.setColorFilter(colorFilter);
				int saveCount = canvas.save();
				try {
					Rect bounds = getBounds();
					if (button.getLayoutDirection() == View.LAYOUT_DIRECTION_RTL) {
						int shift = button.getRight();
						canvas.clipRect(bounds.left, bounds.top, bounds.left + shift, bounds.bottom);
					} else {
						int shift = button.getLeft();
						canvas.clipRect(bounds.left + shift, bounds.top, bounds.right, bounds.bottom);
					}
					drawable.draw(canvas);
				} finally {
					canvas.restoreToCount(saveCount);
					// Clearing the filter would restore the mismatched OEM background after a press.
					drawable.setColorFilter(normalColorFilter);
				}
			}
		}

		@SuppressWarnings("deprecation")
		@Override
		public int getOpacity() {
			return drawable.getOpacity();
		}

		@Override
		public void setAlpha(int alpha) {
			drawable.setAlpha(alpha);
		}

		@Override
		public int getIntrinsicWidth() {
			return drawable.getIntrinsicWidth();
		}

		@Override
		public int getIntrinsicHeight() {
			return drawable.getIntrinsicHeight();
		}

		@Override
		public void invalidateDrawable(@NonNull Drawable who) {
			invalidateSelf();
		}

		@Override
		public void scheduleDrawable(@NonNull Drawable who, @NonNull Runnable what, long when) {
			scheduleSelf(what, when);
		}

		@Override
		public void unscheduleDrawable(@NonNull Drawable who, @NonNull Runnable what) {
			unscheduleSelf(what);
		}
	}

	private static class ToastDividerDrawable extends ColorDrawable {
		private final int width;

		public ToastDividerDrawable(int color, int width) {
			super(color);
			this.width = width;
		}

		@Override
		public int getIntrinsicWidth() {
			return width;
		}
	}
}
