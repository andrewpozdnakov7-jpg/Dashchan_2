package com.mishiranu.dashchan.widget;

import android.app.Activity;
import android.content.Context;
import android.content.res.ColorStateList;
import android.content.res.Configuration;
import android.content.res.TypedArray;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Rect;
import android.graphics.drawable.Drawable;
import android.os.Build;
import android.os.SystemClock;
import android.provider.Settings;
import android.util.Log;
import android.view.ContextThemeWrapper;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.view.accessibility.AccessibilityManager;
import android.widget.LinearLayout;
import android.widget.TextView;
import androidx.core.graphics.ColorUtils;
import com.mishiranu.dashchan.BuildConfig;
import java.util.Arrays;
import java.util.Locale;

/** Opt-in, bounded Logcat diagnostics. Never records text, URLs, view tags or window tokens. */
final class ToastDiagnostics {
	private static final String TAG = "ToastDiag";
	private static long expires;
	private static int remaining;
	private static int sequence;

	private ToastDiagnostics() {}

	static void start(Activity activity, int layoutId) {
		expires = SystemClock.elapsedRealtime() + 10 * 60 * 1000L;
		remaining = 80;
		safe(0, () -> {
			log(0, "session_start duration_min=10 max_popups=80 version=" + BuildConfig.VERSION_NAME
					+ " version_code=" + BuildConfig.VERSION_CODE
					+ " sdk=" + Build.VERSION.SDK_INT + " release=" + Build.VERSION.RELEASE
					+ " manufacturer=" + Build.MANUFACTURER + " brand=" + Build.BRAND + " model=" + Build.MODEL
					+ " security_patch=" + Build.VERSION.SECURITY_PATCH);
			log(0, "privacy=no_text_no_urls_no_cookies_no_identifiers pixels=background_only_software_samples");
			environment(0, activity);
		});
		probe(activity, layoutId, "activity");
		probe(activity.getApplicationContext(), layoutId, "application");
		probe(new ContextThemeWrapper(activity.getApplicationContext(), android.R.style.Theme_Material_Light),
				layoutId, "system_light");
		probe(new ContextThemeWrapper(activity.getApplicationContext(), android.R.style.Theme_Material),
				layoutId, "system_dark");
	}

	static int next() {
		if (SystemClock.elapsedRealtime() >= expires || remaining <= 0) return 0;
		remaining--;
		return ++sequence;
	}

	static void source(int id) {
		if (id == 0) return;
		safe(id, () -> {
			for (StackTraceElement frame : Thread.currentThread().getStackTrace()) {
				String name = frame.getClassName();
				if (name.startsWith("com.mishiranu.dashchan.")
						&& !name.startsWith(ToastDiagnostics.class.getName())
						&& !name.startsWith(ClickableToast.class.getName())
						&& !name.startsWith("com.mishiranu.dashchan.util.ConcurrentUtils")) {
					log(id, "caller=" + name + "." + frame.getMethodName() + " line=" + frame.getLineNumber());
					break;
				}
			}
		});
	}

	static void event(int id, String event) {
		if (id != 0) log(id, event);
	}

	private static void log(int id, String details) {
		Log.i(TAG, "id=" + id + " uptime_ms=" + SystemClock.uptimeMillis() + " " + details);
	}

	private static void safe(int id, Runnable action) {
		try {
			action.run();
		} catch (RuntimeException e) {
			// Exception messages can contain arbitrary application data; log only the type.
			log(id, "diagnostic_error=" + e.getClass().getSimpleName());
		}
	}

	static void environment(int id, Activity activity) {
		Configuration config = activity.getResources().getConfiguration();
		ThemeEngine.Theme theme = ThemeEngine.getTheme(activity);
		log(id, "environment activity=" + activity.getClass().getSimpleName()
				+ " theme=" + (theme.builtIn ? theme.name : "custom") + " base=" + theme.base
				+ " dynamic=" + theme.dynamic + " theme_window=" + hex(theme.window)
				+ " theme_primary=" + hex(theme.primary) + " theme_post=" + hex(theme.post)
				+ " night=" + (config.uiMode & Configuration.UI_MODE_NIGHT_MASK)
				+ " orientation=" + config.orientation + " width_dp=" + config.screenWidthDp
				+ " height_dp=" + config.screenHeightDp + " density_dpi=" + config.densityDpi
				+ " font_scale=" + config.fontScale + " layout_direction=" + config.getLayoutDirection()
				+ " locales=" + config.getLocales().toLanguageTags());
		AccessibilityManager manager = activity.getSystemService(AccessibilityManager.class);
		log(id, "accessibility enabled=" + (manager != null && manager.isEnabled())
				+ " touch_exploration=" + (manager != null && manager.isTouchExplorationEnabled())
				+ " high_text_contrast=" + Settings.Secure.getInt(activity.getContentResolver(),
						"high_text_contrast_enabled", 0));
		attributes(id, activity, "activity");
		view(id, "activity_decor", activity.getWindow().getDecorView());
		window(id, "activity_window", activity.getWindow().getAttributes());
	}

	private static void attributes(int id, Context context, String name) {
		int[] attrs = {android.R.attr.textColorPrimary, android.R.attr.textColorSecondary,
				android.R.attr.textColorPrimaryInverse, android.R.attr.colorBackground,
				android.R.attr.colorForeground, android.R.attr.forceDarkAllowed};
		TypedArray array = context.obtainStyledAttributes(attrs);
		try {
			StringBuilder values = new StringBuilder("attributes context=").append(name);
			for (int i = 0; i < attrs.length - 1; i++) {
				ColorStateList colors = array.getColorStateList(i);
				values.append(' ').append(context.getResources().getResourceEntryName(attrs[i]))
						.append('=').append(colors != null ? hex(colors.getDefaultColor()) : "unset");
			}
			log(id, values + " forceDarkAllowed=" + array.getBoolean(attrs.length - 1, true));
		} finally {
			array.recycle();
		}
	}

	private static void probe(Context context, int layoutId, String name) {
		safe(0, () -> {
			log(0, "template_probe=" + name + " resource=" + context.getResources().getResourceName(layoutId));
			attributes(0, context, name);
			View root = LayoutInflater.from(context).inflate(layoutId, null);
			tree(0, name, root, 0);
		});
	}

	private static void tree(int id, String name, View root, int depth) {
		view(id, name, root);
		if (root.getBackground() != null) background(id, name, root.getBackground(), root.getResources());
		if (depth < 3 && root instanceof ViewGroup) {
			ViewGroup group = (ViewGroup) root;
			for (int i = 0; i < Math.min(6, group.getChildCount()); i++) {
				tree(id, name + "/" + i, group.getChildAt(i), depth + 1);
			}
		}
	}

	static void snapshot(int id, String phase, Activity activity, View container, TextView message,
			TextView button, ViewGroup root, Drawable drawable, boolean hardwareCanvas) {
		if (id == 0) return;
		safe(id, () -> {
			log(id, "phase=" + phase + " background_canvas_hardware=" + hardwareCanvas);
			if ("before_show".equals(phase)) environment(id, activity);
			view(id, "container", container);
			view(id, "message", message);
			view(id, "button", button);
			if (container instanceof LinearLayout) {
				Drawable divider = ((LinearLayout) container).getDividerDrawable();
				if (divider != null) background(id, "divider", divider, container.getResources());
			}
			if (root != null) {
				view(id, "popup_root", root);
				if (root.getLayoutParams() instanceof WindowManager.LayoutParams) {
					window(id, "popup_window", (WindowManager.LayoutParams) root.getLayoutParams());
				}
			}
			Integer color = background(id, "popup_background", drawable, container.getResources());
			if (color != null) {
				if (Color.alpha(color) == 255) {
					log(id, "software_center_contrast message=" + ratio(message.getCurrentTextColor(), color)
							+ " button=" + ratio(button.getCurrentTextColor(), color)
							+ " note=not_compositor_pixels");
				} else {
					log(id, "contrast=unknown_translucent_background alpha=" + Color.alpha(color));
				}
			}
		});
	}

	private static void view(int id, String name, View view) {
		Rect visible = new Rect();
		boolean visibleRect = view.getGlobalVisibleRect(visible);
		log(id, "view=" + name + " class=" + view.getClass().getName()
				+ " measured=" + view.getMeasuredWidth() + "x" + view.getMeasuredHeight()
				+ " bounds=" + view.getLeft() + "," + view.getTop() + "," + view.getRight() + "," + view.getBottom()
				+ " visible_rect=" + (visibleRect ? visible.toShortString() : "none")
				+ " padding=" + view.getPaddingLeft() + "," + view.getPaddingTop() + ","
				+ view.getPaddingRight() + "," + view.getPaddingBottom()
				+ " visibility=" + view.getVisibility() + " alpha=" + view.getAlpha()
				+ " enabled=" + view.isEnabled() + " attached=" + view.isAttachedToWindow()
				+ " window_focus=" + view.hasWindowFocus() + " hardware=" + view.isHardwareAccelerated()
				+ " layer=" + view.getLayerType() + " force_dark=" + view.isForceDarkAllowed()
				+ " elevation=" + view.getElevation() + " translation=" + view.getTranslationX() + ","
				+ view.getTranslationY() + " scale=" + view.getScaleX() + "," + view.getScaleY()
				+ " state=" + Arrays.toString(view.getDrawableState())
				+ " background_tint=" + view.getBackgroundTintList()
				+ " background_tint_mode=" + view.getBackgroundTintMode());
		if (view instanceof TextView) {
			TextView text = (TextView) view;
			log(id, "text_style=" + name + " current=" + hex(text.getCurrentTextColor())
					+ " colors=" + text.getTextColors() + " text_size_px=" + text.getTextSize()
					+ " lines=" + text.getLineCount() + " line_height=" + text.getLineHeight()
					+ " max_lines=" + text.getMaxLines() + " gravity=" + text.getGravity()
					+ " shadow=" + hex(text.getShadowColor()) + " shadow_radius=" + text.getShadowRadius()
					+ " paint_color=" + hex(text.getPaint().getColor())
					+ " paint_alpha=" + text.getPaint().getAlpha() + " ellipsize=" + text.getEllipsize());
		}
	}

	private static void window(int id, String name, WindowManager.LayoutParams params) {
		log(id, name + " type=" + params.type + " flags=0x" + Integer.toHexString(params.flags)
				+ " format=" + params.format + " size=" + params.width + "x" + params.height
				+ " gravity=" + params.gravity + " offset=" + params.x + "," + params.y
				+ " alpha=" + params.alpha + " dim=" + params.dimAmount
				+ " animations=" + params.windowAnimations + " soft_input=" + params.softInputMode);
	}

	private static Integer background(int id, String name, Drawable drawable,
			android.content.res.Resources resources) {
		if (drawable == null) {
			log(id, "drawable=" + name + " null");
			return null;
		}
		Drawable current = drawable.getCurrent();
		Rect padding = new Rect();
		drawable.getPadding(padding);
		log(id, "drawable=" + name + " class=" + drawable.getClass().getName()
				+ " current=" + current.getClass().getName() + " alpha=" + drawable.getAlpha()
				+ " state=" + Arrays.toString(drawable.getState()) + " stateful=" + drawable.isStateful()
				+ " bounds=" + drawable.getBounds().toShortString() + " padding=" + padding.toShortString()
				+ " minimum=" + drawable.getMinimumWidth() + "x" + drawable.getMinimumHeight()
				+ " intrinsic=" + drawable.getIntrinsicWidth() + "x" + drawable.getIntrinsicHeight()
				+ " color_filter=" + (drawable.getColorFilter() != null
						? drawable.getColorFilter().getClass().getSimpleName() : "none"));
		Drawable.ConstantState state = drawable.getConstantState();
		if (state == null) {
			log(id, "drawable=" + name + " sample=unavailable_no_safe_clone");
			return null;
		}
		Drawable copy = state.newDrawable(resources).mutate();
		copy.setState(drawable.getState());
		copy.setLevel(drawable.getLevel());
		copy.setLayoutDirection(drawable.getLayoutDirection());
		copy.setAlpha(drawable.getAlpha());
		copy.setColorFilter(drawable.getColorFilter());
		Bitmap bitmap = Bitmap.createBitmap(192, 96, Bitmap.Config.ARGB_8888);
		try {
			copy.setBounds(0, 0, bitmap.getWidth(), bitmap.getHeight());
			copy.draw(new Canvas(bitmap));
			int center = bitmap.getPixel(96, 48);
			log(id, "drawable=" + name + " software_samples center=" + hex(center)
					+ " left=" + hex(bitmap.getPixel(48, 48)) + " right=" + hex(bitmap.getPixel(144, 48))
					+ " top=" + hex(bitmap.getPixel(96, 24)) + " bottom=" + hex(bitmap.getPixel(96, 72)));
			return center;
		} finally {
			bitmap.recycle();
		}
	}

	private static String ratio(int foreground, int background) {
		return String.format(Locale.US, "%.2f", ColorUtils.calculateContrast(foreground, background));
	}

	private static String hex(int color) {
		return String.format(Locale.US, "#%08X", color);
	}
}
