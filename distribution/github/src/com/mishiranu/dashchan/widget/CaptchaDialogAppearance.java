package com.mishiranu.dashchan.widget;

import android.app.AlertDialog;
import android.app.Activity;
import android.content.Context;
import android.content.ContextWrapper;
import android.content.DialogInterface;
import android.content.res.ColorStateList;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.RippleDrawable;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowInsets;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import androidx.core.graphics.ColorUtils;
import com.google.android.material.loadingindicator.LoadingIndicator;
import com.mishiranu.dashchan.R;
import com.mishiranu.dashchan.content.Preferences;
import com.mishiranu.dashchan.util.InterfaceMotion;
import com.mishiranu.dashchan.util.ResourceUtils;
import com.mishiranu.dashchan.util.ViewUtils;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Set;

/** Local presentation of the existing manually-operated server keyboard. No network or posting actions. */
public final class CaptchaDialogAppearance {
	public interface ModalListener { void onCaptchaModalChanged(boolean visible); }
	private static final Handler HANDLER = new Handler(Looper.getMainLooper());
	private static final Set<CaptchaDialogAppearance> MODALS = new HashSet<>();
	private static final ArrayList<java.lang.ref.WeakReference<ModalListener>> LISTENERS = new ArrayList<>();
	public static boolean isEnabled() { return Preferences.isNewPostingFormEnabled(); }
	public static void observe(ModalListener listener) {
		LISTENERS.add(new java.lang.ref.WeakReference<>(listener)); listener.onCaptchaModalChanged(!MODALS.isEmpty());
	}
	public static void unobserve(ModalListener listener) {
		LISTENERS.removeIf(reference -> reference.get() == null || reference.get() == listener);
	}
	private static void publish() {
		for (java.lang.ref.WeakReference<ModalListener> reference : new ArrayList<>(LISTENERS)) {
			ModalListener listener = reference.get();
			if (listener != null) listener.onCaptchaModalChanged(!MODALS.isEmpty()); else LISTENERS.remove(reference);
		}
	}
	private final Context context;
	private final LinearLayout content;
	private final int surface, text, accent, secondary;
	private final LinearLayout selectedRow;
	private final TextView error;
	private final FrameLayout processingSlot, refreshSlot;
	private final LoadingIndicator selectionLoading, refreshLoading;
	private final ImageView refreshIcon;
	private final Button refresh, cancel, done;
	private LinearLayout root;
	private Bitmap[] selected = new Bitmap[0];
	private boolean closed, started;
	private Runnable delayedSelection;
	private ImageView oldGrid;
	private ViewGroup gridParent;
	private View changingGrid;
	private android.animation.ValueAnimator gridFade;
	private boolean lastWaiting, lastRefreshing;

	public CaptchaDialogAppearance(Context context, LinearLayout content, String instruction) {
		this.context = context; this.content = content;
		ThemeEngine.Theme theme = ThemeEngine.getTheme(context);
		surface = ColorUtils.compositeColors(theme.card,
				ColorUtils.compositeColors(theme.window, theme.base == ThemeEngine.Theme.Base.LIGHT ? Color.WHITE : Color.BLACK));
		text = InterfaceAppearance.contrasting(ResourceUtils.getColor(context, android.R.attr.textColorPrimary), surface, 4.5);
		accent = InterfaceAppearance.contrasting(theme.accent, surface, 3);
		secondary = InterfaceAppearance.contrasting(ColorUtils.blendARGB(surface, text, .65f), surface, 4.5);
		content.setPadding(0, 0, 0, 0);
		LinearLayout header = new LinearLayout(context); header.setGravity(Gravity.CENTER_VERTICAL);
		TextView title = label(instruction, 16, text);
		header.addView(title, new LinearLayout.LayoutParams(0, -2, 1f));
		processingSlot = new FrameLayout(context); selectionLoading = loading();
		processingSlot.addView(selectionLoading, new FrameLayout.LayoutParams(dp(32), dp(32), Gravity.CENTER));
		LinearLayout.LayoutParams processingParams = new LinearLayout.LayoutParams(dp(32), dp(32));
		processingParams.setMarginStart(dp(12)); header.addView(processingSlot, processingParams);
		LinearLayout.LayoutParams titleParams = new LinearLayout.LayoutParams(-1, -2); titleParams.bottomMargin = dp(16);
		content.addView(header, titleParams);
		selectedRow = new LinearLayout(context); selectedRow.setGravity(Gravity.CENTER_VERTICAL);
		selectedRow.setContentDescription(context.getString(R.string.captcha_dialog_selected));
		selectedRow.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_YES);
		HorizontalScrollView selectedScroll = new SelectionScroll();
		selectedScroll.setHorizontalScrollBarEnabled(false); selectedScroll.setFillViewport(true);
		selectedScroll.addView(selectedRow, new FrameLayout.LayoutParams(-2, -1));
		LinearLayout.LayoutParams panelParams = new LinearLayout.LayoutParams(-1, dp(64)); panelParams.bottomMargin = dp(12);
		content.addView(selectedScroll, panelParams);
		error = label(context.getString(R.string.captcha_dialog_retry_error), 14,
				InterfaceAppearance.contrasting(ResourceUtils.getColor(context, R.attr.colorTextError), surface, 4.5));
		error.setVisibility(View.GONE);
		refreshSlot = new FrameLayout(context);
		refresh = button("", accent); refresh.setContentDescription(context.getString(R.string.refresh_captcha));
		refresh.setPadding(0, 0, 0, 0);
		refresh.setTooltipText(refresh.getContentDescription());
		refreshSlot.addView(refresh, new FrameLayout.LayoutParams(-1, -1));
		refreshIcon = new ImageView(context);
		Drawable icon = ResourceUtils.getDrawable(context, R.attr.iconActionRefresh, 0);
		if (icon != null) { icon = icon.mutate(); icon.setTint(accent); }
		refreshIcon.setImageDrawable(icon); refreshIcon.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
		refreshSlot.addView(refreshIcon, new FrameLayout.LayoutParams(dp(24), dp(24), Gravity.CENTER));
		refreshLoading = loading(); refreshSlot.addView(refreshLoading, new FrameLayout.LayoutParams(dp(32), dp(32), Gravity.CENTER));
		cancel = button(context.getString(android.R.string.cancel), accent);
		done = button(context.getString(R.string.captcha_dialog_done), Color.WHITE);
		int primary = PostingFormAppearance.primaryBackground(accent);
		android.graphics.drawable.StateListDrawable doneBackground = new android.graphics.drawable.StateListDrawable();
		doneBackground.addState(new int[]{android.R.attr.state_enabled}, shape(24, primary));
		doneBackground.addState(new int[0], shape(24, ColorUtils.blendARGB(surface, accent, .16f)));
		done.setBackground(new RippleDrawable(ColorStateList.valueOf(0x33ffffff), doneBackground, shape(24, Color.WHITE)));
		done.setTextColor(new ColorStateList(new int[][]{new int[]{android.R.attr.state_enabled}, new int[0]},
				new int[]{Color.WHITE, secondary}));
		bindSelection(new Bitmap[0], false);
	}

	/** Four stable slots use the measured viewport; additional server-provided answers remain scrollable. */
	private final class SelectionScroll extends HorizontalScrollView {
		SelectionScroll() { super(CaptchaDialogAppearance.this.context); }
		@Override protected void onMeasure(int widthSpec, int heightSpec) {
			super.onMeasure(widthSpec, heightSpec);
			int width = Math.max(0, getMeasuredWidth() - getPaddingLeft() - getPaddingRight());
			int gap = Math.min(dp(8), width / 3);
			int available = Math.max(0, width - 3 * gap), remainder = available % 4;
			boolean changed = false;
			for (int i = 0; i < selectedRow.getChildCount(); i++) {
				FrameLayout slot = (FrameLayout) selectedRow.getChildAt(i);
				int slotWidth = available / 4 + (i % 4 < remainder ? 1 : 0);
				LinearLayout.LayoutParams params = (LinearLayout.LayoutParams) slot.getLayoutParams();
				int margin = i + 1 < selectedRow.getChildCount() ? gap : 0;
				if (params.width != slotWidth || params.getMarginEnd() != margin) {
					params.width = slotWidth; params.setMarginEnd(margin); slot.setLayoutParams(params); changed = true;
				}
				ImageView symbol = (ImageView) slot.getChildAt(0);
				int side = Math.min(dp(64), slotWidth);
				FrameLayout.LayoutParams imageParams = (FrameLayout.LayoutParams) symbol.getLayoutParams();
				if (imageParams.width != side || imageParams.height != side) {
					imageParams.width = side; imageParams.height = side; symbol.setLayoutParams(imageParams); changed = true;
				}
			}
			if (changed) super.onMeasure(widthSpec, heightSpec);
		}
	}
	private int dp(float value) { return Math.round(value * ResourceUtils.obtainDensity(content)); }
	private GradientDrawable shape(int radius, int color) {
		GradientDrawable result = new GradientDrawable(); result.setColor(color); result.setCornerRadius(dp(radius)); return result;
	}
	private TextView label(CharSequence value, int size, int color) {
		TextView view = new TextView(context); view.setText(value); view.setTextColor(color); ViewUtils.setTextSizeScaled(view, size); return view;
	}
	private Button button(String caption, int color) {
		Button result = new Button(context); result.setText(caption); result.setAllCaps(false); result.setTextColor(color);
		result.setBackgroundTintList(null);
		ViewUtils.setTextSizeScaled(result, 14); result.setPadding(dp(12), 0, dp(12), 0);
		result.setMinWidth(0); result.setMinimumWidth(0); result.setMinHeight(dp(48)); result.setMinimumHeight(dp(48));
		result.setBackground(new RippleDrawable(ColorStateList.valueOf(ColorUtils.setAlphaComponent(accent, 32)),
				shape(24, Color.TRANSPARENT), shape(24, Color.WHITE))); return result;
	}
	private LoadingIndicator loading() {
		LoadingIndicator view = PullLoadingIndicator.createMaterial(context);
		view.setContainerWidth(dp(32)); view.setContainerHeight(dp(32)); view.setIndicatorSize(dp(26));
		view.setContainerColor(Color.TRANSPARENT); view.setIndicatorColor(accent); view.setVisibility(View.INVISIBLE);
		view.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO); return view;
	}
	public ImageView taskImage(Bitmap image) {
		ImageView view = new ImageView(context); view.setAdjustViewBounds(true); view.setScaleType(ImageView.ScaleType.FIT_CENTER);
		view.setImageBitmap(image); content.addView(view, new LinearLayout.LayoutParams(-1, dp(120))); return view;
	}
	public void bindSelection(Bitmap[] next, boolean animate) {
		if (next == null) next = new Bitmap[0];
		int prefix = 0;
		while (prefix < next.length && prefix < selected.length && next[prefix] == selected[prefix]) prefix++;
		cancelSelectionAnimations();
		selectedRow.removeAllViews();
		for (int i = 0; i < Math.max(4, next.length); i++) {
			FrameLayout slot = new FrameLayout(context); slot.setBackground(shape(8, ColorUtils.blendARGB(surface, accent, .12f)));
			slot.setClipToOutline(false);
			ImageView symbol = new ImageView(context); symbol.setScaleType(ImageView.ScaleType.FIT_CENTER);
			symbol.setPadding(0, 0, 0, 0);
			if (i < next.length) {
				symbol.setImageBitmap(next[i]);
				symbol.setContentDescription(context.getString(R.string.captcha_dialog_selected_symbol, i + 1));
			} else symbol.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
			slot.addView(symbol, new FrameLayout.LayoutParams(dp(64), dp(64), Gravity.CENTER));
			LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(0, dp(64));
			selectedRow.addView(slot, params);
			if (animate && i < next.length && i >= prefix && InterfaceMotion.duration(150) > 0) {
				symbol.setAlpha(0f); symbol.animate().alpha(1f).setDuration(InterfaceMotion.duration(150)).start();
			}
		}
		selected = next.clone();
	}
	private void cancelSelectionAnimations() {
		for (int i = 0; i < selectedRow.getChildCount(); i++) {
			View slot = selectedRow.getChildAt(i); slot.animate().cancel();
			((ViewGroup) slot).getChildAt(0).animate().cancel();
		}
	}
	public Button button(int which) {
		return which == DialogInterface.BUTTON_NEUTRAL ? refresh : which == DialogInterface.BUTTON_POSITIVE ? done : cancel;
	}
	static int contentHeightLimit(int height, WindowInsets insets, int outerSpacing) {
		// A symbol picker is not an IME target: only physical safe areas limit it.
		// Do not shrink it while the posting editor's keyboard is still hiding.
		android.graphics.Insets safe = insets.getInsets(WindowInsets.Type.systemBars() | WindowInsets.Type.displayCutout());
		return Math.max(1, height - safe.top - safe.bottom - outerSpacing);
	}
	private void hideEditorKeyboard() {
		Context owner = context;
		while (owner instanceof ContextWrapper && !(owner instanceof Activity)) {
			Context base = ((ContextWrapper) owner).getBaseContext();
			if (base == owner) break;
			owner = base;
		}
		if (owner instanceof Activity) {
			android.view.WindowInsetsController controller = ((Activity) owner).getWindow().getInsetsController();
			if (controller != null) controller.hide(WindowInsets.Type.ime());
		}
	}
	public AlertDialog create(View scroll, DialogInterface.OnClickListener actions, Runnable dismiss) {
		root = new LinearLayout(context) {
			@Override protected void onMeasure(int widthSpec, int heightSpec) {
				android.view.WindowMetrics metrics = ((android.view.WindowManager) context.getSystemService(Context.WINDOW_SERVICE)).getCurrentWindowMetrics();
				int limit = contentHeightLimit(metrics.getBounds().height(), metrics.getWindowInsets(), dp(48));
				int size = MeasureSpec.getMode(heightSpec) == MeasureSpec.UNSPECIFIED ? limit : Math.min(MeasureSpec.getSize(heightSpec), limit);
				super.onMeasure(widthSpec, MeasureSpec.makeMeasureSpec(size, MeasureSpec.AT_MOST));
			}
		};
		root.setOrientation(LinearLayout.VERTICAL); root.setPadding(dp(24), dp(24), dp(24), dp(16));
		root.setBackground(shape(28, surface)); root.setClipToOutline(true);
		root.addView(scroll, new LinearLayout.LayoutParams(-1, -2, 1f));
		root.addView(error, new LinearLayout.LayoutParams(-1, -2));
		LinearLayout footer = new LinearLayout(context) {
			@Override protected void onMeasure(int widthSpec, int heightSpec) {
				int available = MeasureSpec.getSize(widthSpec);
				cancel.measure(MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED), heightSpec);
				done.measure(MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED), heightSpec);
				// Large fonts/narrow windows keep every action reachable, rather than squeezing text or hit targets.
				setOrientation(dp(48 + 8) + cancel.getMeasuredWidth() + done.getMeasuredWidth() > available ? VERTICAL : HORIZONTAL);
				super.onMeasure(widthSpec, heightSpec);
			}
		}; footer.setGravity(Gravity.CENTER_VERTICAL);
		LinearLayout.LayoutParams footerParams = new LinearLayout.LayoutParams(-1, -2); footerParams.topMargin = dp(16);
		root.addView(footer, footerParams);
		footer.addView(refreshSlot, new LinearLayout.LayoutParams(dp(48), dp(48)));
		footer.addView(new View(context), new LinearLayout.LayoutParams(0, 0, 1f));
		footer.addView(cancel, new LinearLayout.LayoutParams(-2, -2));
		LinearLayout.LayoutParams doneParams = new LinearLayout.LayoutParams(-2, -2); doneParams.setMarginStart(dp(8)); footer.addView(done, doneParams);
		AlertDialog dialog = new com.mishiranu.dashchan.content.security.SecureDialogBuilder(context).create();
		// The explicit-spacing overload belongs to AlertDialog, not its Builder.
		dialog.setView(root, 0, 0, 0, 0);
		Window window = dialog.getWindow();
		if (window != null) {
			// Keep this focusable modal above the IME. No text entry is needed here;
			// do not let AlertDialog's available frame be reduced by the editor's IME.
			window.addFlags(android.view.WindowManager.LayoutParams.FLAG_ALT_FOCUSABLE_IM);
		}
		hideEditorKeyboard();
		refresh.setOnClickListener(view -> actions.onClick(dialog, DialogInterface.BUTTON_NEUTRAL));
		done.setOnClickListener(view -> actions.onClick(dialog, DialogInterface.BUTTON_POSITIVE));
		cancel.setOnClickListener(view -> dismiss.run());
		return dialog;
	}
	public void start(AlertDialog dialog) {
		Window window = dialog.getWindow();
		if (window != null) {
			window.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT)); window.getDecorView().setPadding(0, 0, 0, 0);
			android.view.WindowMetrics metrics = window.getWindowManager().getCurrentWindowMetrics();
			android.graphics.Insets insets = metrics.getWindowInsets().getInsets(WindowInsets.Type.systemBars() | WindowInsets.Type.displayCutout());
			window.setLayout(Math.min(dp(560), Math.max(1, metrics.getBounds().width() - insets.left - insets.right - dp(48))), -2);
			window.setDimAmount(.32f);
			window.setWindowAnimations(InterfaceMotion.duration(1) > 0 ? R.style.Motion_Captcha_Dialog : 0);
			// This symbol picker has no text editor. Safe bounds already use WindowInsets
			// in onMeasure; do not install the deprecated keyboard resize workaround.
		}
		if (!started && !closed) { started = true; MODALS.add(this); publish(); }
	}
	public void waiting(boolean waiting, boolean refreshing, boolean retryError) {
		if (delayedSelection != null) { HANDLER.removeCallbacks(delayedSelection); delayedSelection = null; }
		selectionLoading.setVisibility(View.INVISIBLE);
		boolean refreshingNow = waiting && refreshing;
		if (lastWaiting != waiting || lastRefreshing != refreshing) {
			refreshLoading.animate().cancel(); refreshIcon.animate().cancel();
			refreshLoading.setVisibility(View.VISIBLE); refreshIcon.setVisibility(View.VISIBLE);
			if (!started || InterfaceMotion.duration(120) == 0) {
				refreshLoading.setAlpha(refreshingNow ? 1f : 0f); refreshIcon.setAlpha(refreshingNow ? 0f : 1f);
			} else {
				refreshLoading.animate().alpha(refreshingNow ? 1f : 0f).setDuration(InterfaceMotion.duration(120)).withEndAction(() -> {
					if (!closed && !lastRefreshing) refreshLoading.setVisibility(View.INVISIBLE);
				}).start();
				refreshIcon.animate().alpha(refreshingNow ? 0f : 1f).setDuration(InterfaceMotion.duration(120)).start();
			}
		}
		refresh.setStateDescription(context.getString(refreshingNow ? R.string.captcha_dialog_refreshing : R.string.refresh_captcha));
		processingSlot.setImportantForAccessibility(waiting && !refreshing ? View.IMPORTANT_FOR_ACCESSIBILITY_YES : View.IMPORTANT_FOR_ACCESSIBILITY_NO);
		processingSlot.setContentDescription(context.getString(R.string.captcha_dialog_processing));
		processingSlot.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
		error.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
		error.setVisibility(retryError ? View.VISIBLE : View.GONE);
		lastWaiting = waiting; lastRefreshing = refreshingNow;
		if (waiting && !refreshing) {
			delayedSelection = () -> { delayedSelection = null; if (!closed) selectionLoading.setVisibility(View.VISIBLE); };
			HANDLER.postDelayed(delayedSelection, 300);
		}
	}
	private void settleGrid() {
		if (gridFade != null) { gridFade.removeAllUpdateListeners(); gridFade.removeAllListeners(); gridFade.cancel(); gridFade = null; }
		if (changingGrid != null) changingGrid.setAlpha(1f);
		if (oldGrid != null && gridParent != null) { gridParent.getOverlay().remove(oldGrid); oldGrid.setImageDrawable(null); }
		oldGrid = null; changingGrid = null; gridParent = null;
	}
	public void beforeContentChange(View grid) {
		settleGrid();
		if (closed || !grid.isAttachedToWindow() || grid.getWidth() == 0 || grid.getHeight() == 0 || InterfaceMotion.duration(160) == 0) return;
		// Bounded native keyboard-only snapshot, never a form, response token or user text.
		if ((long) grid.getWidth() * grid.getHeight() > 2048L * 2048L) return;
		Bitmap bitmap = Bitmap.createBitmap(grid.getWidth(), grid.getHeight(), Bitmap.Config.ARGB_8888);
		grid.draw(new Canvas(bitmap)); changingGrid = grid; gridParent = (ViewGroup) grid.getParent();
		oldGrid = new ImageView(context); oldGrid.setImageBitmap(bitmap); oldGrid.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
		oldGrid.layout(grid.getLeft(), grid.getTop(), grid.getRight(), grid.getBottom()); gridParent.getOverlay().add(oldGrid);
	}
	public void afterContentChange(View grid) {
		if (oldGrid == null) return;
		grid.setAlpha(0f); gridFade = android.animation.ValueAnimator.ofFloat(0f, 1f);
		gridFade.setDuration(InterfaceMotion.duration(160));
		gridFade.addUpdateListener(value -> { float fraction = (float) value.getAnimatedValue(); grid.setAlpha(fraction); if (oldGrid != null) oldGrid.setAlpha(1f - fraction); });
		gridFade.addListener(new android.animation.AnimatorListenerAdapter() {
			@Override public void onAnimationEnd(android.animation.Animator animation) { settleGrid(); }
		}); gridFade.start();
	}
	public void close() {
		if (closed) return; closed = true;
		if (delayedSelection != null) HANDLER.removeCallbacks(delayedSelection);
		selectionLoading.setVisibility(View.INVISIBLE); refreshLoading.setVisibility(View.INVISIBLE);
		refreshLoading.animate().cancel(); refreshIcon.animate().cancel();
		settleGrid();
		cancelSelectionAnimations();
		if (started) HANDLER.postDelayed(() -> { MODALS.remove(this); publish(); }, InterfaceMotion.duration(180));
	}
}
