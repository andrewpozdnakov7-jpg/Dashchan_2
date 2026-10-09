package com.mishiranu.dashchan.widget;

import android.animation.StateListAnimator;
import android.app.AlertDialog;
import android.content.Context;
import android.content.ContextWrapper;
import android.content.res.ColorStateList;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.ColorFilter;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.drawable.ClipDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.LayerDrawable;
import android.graphics.drawable.RippleDrawable;
import android.graphics.drawable.StateListDrawable;
import android.text.method.TransformationMethod;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.CompoundButton;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.SeekBar;
import android.widget.Switch;
import android.widget.TextView;
import androidx.core.graphics.ColorUtils;
import androidx.core.view.OneShotPreDrawListener;
import com.mishiranu.dashchan.R;
import com.mishiranu.dashchan.util.InterfaceMotion;
import com.mishiranu.dashchan.util.ResourceUtils;
import java.util.WeakHashMap;

/** Explicit component roles. Does not replace widgets, listeners, data or the application's theme. */
public final class InterfaceAppearance {
	private InterfaceAppearance() {}
	enum Role {ROW, HEADER, TEXT, SECONDARY, ACTION, TEXT_ACTION, FIELD, CHECK, SWITCH, SLIDER, ICON, AUDIO_TITLE}
	private static final WeakHashMap<Context, Palette> PALETTES = new WeakHashMap<>();
	public static boolean isEnabled(Context context) {
		return InterfaceMotion.isEnabled() && supported(context);
	}
	private static boolean supported(Context context) {
		for (Context current = context; current != null;) {
			if (current.getClass().getName().equals("com.mishiranu.dashchan.ui.posting.ExperimentalImageEditorActivity")) return false;
			Context next = current instanceof ContextWrapper ? ((ContextWrapper) current).getBaseContext() : null;
			if (next == current) break; current = next;
		}
		return true;
	}
	static int opaque(int color, int background) { return ColorUtils.compositeColors(color, background); }
	static int contrasting(int requested, int background, double ratio) {
		int foreground = opaque(requested, background);
		if (ColorUtils.calculateContrast(foreground, background) >= ratio) return foreground;
		int target = ColorUtils.calculateContrast(Color.BLACK, background) >= ColorUtils.calculateContrast(Color.WHITE, background) ? Color.BLACK : Color.WHITE;
		for (int i = 1; i <= 20; i++) {
			int candidate = ColorUtils.blendARGB(foreground, target, i / 20f);
			if (ColorUtils.calculateContrast(candidate, background) >= ratio) return candidate;
		}
		return target;
	}
	private static final class Palette {
		final ThemeEngine.Theme theme; final int surface, text, secondary, accent, onAccent, outline, tonal, error;
		Palette(Context context, ThemeEngine.Theme theme) {
			this.theme = theme;
			int window = opaque(theme.window, theme.base == ThemeEngine.Theme.Base.LIGHT ? Color.WHITE : Color.BLACK);
			surface = opaque(theme.card, window);
			text = contrasting(ResourceUtils.getColor(context, android.R.attr.textColorPrimary), surface, 4.5);
			secondary = contrasting(ColorUtils.blendARGB(surface, text, .72f), surface, 4.5);
			accent = opaque(theme.accent, surface); onAccent = contrasting(text, accent, 4.5);
			outline = contrasting(ColorUtils.blendARGB(surface, text, .5f), surface, 3);
			tonal = ColorUtils.blendARGB(surface, accent, .10f);
			error = contrasting(ResourceUtils.getColor(context, R.attr.colorTextError), surface, 3);
		}
	}
	private static Palette palette(View view) {
		Context context = view.getContext(); ThemeEngine.Theme theme = ThemeEngine.getTheme(context);
		Palette palette = PALETTES.get(context);
		if (palette == null || palette.theme != theme) { palette = new Palette(context, theme); PALETTES.put(context, palette); }
		return palette;
	}
	private static int dp(View view, float amount) { return Math.round(ResourceUtils.obtainDensity(view) * amount); }
	private static ColorStateList enabled(int color, int surface) {
		return new ColorStateList(new int[][] {{-android.R.attr.state_enabled}, {}}, new int[] {ColorUtils.blendARGB(surface, color, .38f), color});
	}
	private static GradientDrawable shape(View view, float radius, ColorStateList color) {
		GradientDrawable drawable = new GradientDrawable(); drawable.setColor(color); drawable.setCornerRadius(dp(view, radius)); return drawable;
	}
	private static Drawable ripple(View view, Drawable content, float radius, int color) {
		return new RippleDrawable(new ColorStateList(new int[][] {{-android.R.attr.state_enabled}, {}},
				new int[] {Color.TRANSPARENT, ColorUtils.setAlphaComponent(color, 36)}), content,
				shape(view, radius, ColorStateList.valueOf(Color.WHITE)));
	}
	private static void role(View view, Role role) {
		if (view == null || !supported(view.getContext())) return;
		Object tag = view.getTag(R.id.interface_appearance_state);
		State state = tag instanceof State ? (State) tag : new State(view);
		state.role = role; view.setTag(R.id.interface_appearance_state, state); render(view, state);
	}
	public static void row(View view, TextView title, TextView summary, boolean header) {
		role(view, header ? Role.HEADER : Role.ROW); if (title != view) role(title, header ? Role.HEADER : Role.TEXT); role(summary, Role.SECONDARY);
	}
	public static void action(Button view, boolean primary) { role(view, primary ? Role.ACTION : Role.TEXT_ACTION); }
	public static void field(EditText view) { role(view, Role.FIELD); }
	public static void check(CompoundButton view) { role(view, view instanceof Switch ? Role.SWITCH : Role.CHECK); }
	public static void slider(SeekBar view) { role(view, Role.SLIDER); }
	public static void icon(ImageView view) { role(view, Role.ICON); }
	public static void secondary(TextView view) { role(view, Role.SECONDARY); }
	public static void audioTitle(TextView view) { role(view, Role.AUDIO_TITLE); }
	public static void fieldError(EditText view, boolean error) {
		Object tag = view.getTag(R.id.interface_appearance_state);
		if (tag instanceof State && ((State) tag).role == Role.FIELD) {
			State state = (State) tag; state.rendered = false; state.error = error; state.legacyError = error ? view.getBackground() : null;
			if (isEnabled(view.getContext())) render(view, state);
		}
	}
	public static void refreshTree(View view) {
		if (view == null) return;
		Object state = view.getTag(R.id.interface_appearance_state);
		if (state instanceof State) render(view, (State) state);
		if (view instanceof ViewGroup) {
			ViewGroup group = (ViewGroup) view;
			for (int i = 0; i < group.getChildCount(); i++) refreshTree(group.getChildAt(i));
		}
	}
	public static void configureDialog(AlertDialog dialog) {
		if (!supported(dialog.getContext())) return;
		View decor = dialog.getWindow() != null ? dialog.getWindow().getDecorView() : null;
		if (decor == null) return;
		decor.addOnAttachStateChangeListener(new View.OnAttachStateChangeListener() {
			private OneShotPreDrawListener pending;
			@Override public void onViewAttachedToWindow(View view) {
				pending = OneShotPreDrawListener.add(view, () -> {
					pending = null;
					if (!dialog.isShowing()) return;
					action(dialog.getButton(AlertDialog.BUTTON_POSITIVE), true);
					action(dialog.getButton(AlertDialog.BUTTON_NEGATIVE), false);
					action(dialog.getButton(AlertDialog.BUTTON_NEUTRAL), false);
					View custom = dialog.findViewById(android.R.id.custom); styleInputs(custom);
				});
			}
			@Override public void onViewDetachedFromWindow(View view) { if (pending != null) { pending.removeListener(); pending = null; } }
		});
	}
	private static void styleInputs(View view) {
		if (view instanceof EditText) field((EditText) view);
		else if (view instanceof CheckBox || view instanceof Switch) check((CompoundButton) view);
		else if (view instanceof SeekBar) slider((SeekBar) view);
		if (view instanceof ViewGroup) {
			ViewGroup group = (ViewGroup) view; for (int i = 0; i < group.getChildCount(); i++) styleInputs(group.getChildAt(i));
		}
	}
	private static void render(View view, State state) {
		boolean enabled = isEnabled(view.getContext());
		Palette p = enabled ? palette(view) : null;
		if (state.rendered && state.applied == enabled && state.appliedRole == state.role
				&& state.appliedTheme == (p != null ? p.theme : null) && state.appliedError == state.error) return;
		state.restore(view); state.rendered = true; state.applied = enabled; state.appliedRole = state.role;
		state.appliedTheme = p != null ? p.theme : null; state.appliedError = state.error;
		if (!enabled) return;
		switch (state.role) {
			case TEXT: case SECONDARY: case AUDIO_TITLE: {
				TextView text = (TextView) view; text.setTextColor(enabled(state.role == Role.SECONDARY ? p.secondary : p.text, p.surface));
				if (state.role == Role.AUDIO_TITLE) { text.setSingleLine(false); text.setMaxLines(2); text.setTextSize(TypedValue.COMPLEX_UNIT_SP, 18); }
				break;
			}
			case HEADER: {
				if (view instanceof TextView) ((TextView) view).setTextColor(contrasting(p.accent, p.surface, 4.5));
				break;
			}
			case ROW: {
				if (view instanceof TextView) ((TextView) view).setTextColor(enabled(p.text, p.surface));
				view.setMinimumHeight(dp(view, 64));
				ColorStateList fill = new ColorStateList(new int[][] {{android.R.attr.state_selected}, {android.R.attr.state_activated}, {}},
						new int[] {p.tonal, p.tonal, Color.TRANSPARENT});
				view.setBackgroundTintList(null); view.setBackground(ripple(view, shape(view, 12, fill), 12, p.text)); break;
			}
			case ACTION: case TEXT_ACTION: case ICON: {
				boolean filled = state.role != Role.TEXT_ACTION;
				view.setBackgroundTintList(null); view.setBackground(ripple(view, shape(view, 28, enabled(filled ? p.accent : Color.TRANSPARENT, p.surface)), 28, filled ? p.onAccent : p.text));
				view.setElevation(0); view.setStateListAnimator(null);
				if (view instanceof Button) {
					Button button = (Button) view; button.setAllCaps(false); button.setTextColor(enabled(filled ? p.onAccent : contrasting(p.accent, p.surface, 4.5), filled ? p.accent : p.surface));
					button.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14); button.setMinimumHeight(dp(view, 48));
					button.setPaddingRelative(dp(view, 16), dp(view, 8), dp(view, 16), dp(view, 8));
				} else ((ImageView) view).setImageTintList(enabled(p.onAccent, p.accent));
				break;
			}
			case FIELD: {
				EditText edit = (EditText) view; edit.setBackgroundTintList(null);
				StateListDrawable background = new StateListDrawable();
				GradientDrawable focus = shape(view, 12, ColorStateList.valueOf(p.surface)); focus.setStroke(dp(view, 2), state.error ? p.error : contrasting(p.accent, p.surface, 3));
				GradientDrawable normal = shape(view, 12, enabled(p.tonal, p.surface)); normal.setStroke(dp(view, 1), state.error ? p.error : contrasting(p.outline, p.tonal, 3));
				background.addState(new int[] {android.R.attr.state_enabled, android.R.attr.state_focused}, focus); background.addState(new int[0], normal);
				edit.setBackground(background); edit.setMinimumHeight(Math.max(state.minimumHeight, dp(view, 48)));
				edit.setPaddingRelative(dp(view, 12), dp(view, 10), dp(view, 12), dp(view, 10));
				edit.setTextColor(enabled(contrasting(p.text, p.tonal, 4.5), p.surface)); edit.setHintTextColor(enabled(contrasting(p.secondary, p.tonal, 4.5), p.surface));
				break;
			}
			case CHECK: {
				CompoundButton check = (CompoundButton) view; check.setButtonTintList(null); check.setButtonDrawable(new Mark(view, p)); break;
			}
			case SWITCH: {
				Switch control = (Switch) view; control.setTrackTintList(null); control.setThumbTintList(null);
				GradientDrawable track = shape(view, 16, new ColorStateList(new int[][] {{-android.R.attr.state_enabled}, {android.R.attr.state_checked}, {}},
						new int[] {ColorUtils.blendARGB(p.surface, p.text, .12f), p.accent, p.tonal}));
				track.setSize(dp(view, 52), dp(view, 32)); track.setStroke(dp(view, 1), p.outline);
				GradientDrawable thumb = shape(view, 12, new ColorStateList(new int[][] {{-android.R.attr.state_enabled}, {android.R.attr.state_checked}, {}},
						new int[] {p.secondary, p.onAccent, p.outline})); thumb.setSize(dp(view, 24), dp(view, 24));
				control.setTrackDrawable(track); control.setThumbDrawable(new android.graphics.drawable.InsetDrawable(thumb, 0, dp(view, 4), 0, dp(view, 4))); control.setSwitchMinWidth(dp(view, 52)); control.setSplitTrack(false); break;
			}
			case SLIDER: {
				SeekBar seek = (SeekBar) view;
				GradientDrawable track = shape(view, 4, enabled(ColorUtils.blendARGB(p.surface, p.text, .16f), p.surface)); track.setSize(dp(view, 1), dp(view, 6));
				GradientDrawable progress = shape(view, 4, enabled(p.accent, p.surface)); progress.setSize(dp(view, 1), dp(view, 6));
				LayerDrawable layers = new LayerDrawable(new Drawable[] {track, new ClipDrawable(progress, Gravity.LEFT, ClipDrawable.HORIZONTAL)});
				layers.setId(0, android.R.id.background); layers.setId(1, android.R.id.progress);
				GradientDrawable thumb = shape(view, 10, enabled(contrasting(p.accent, p.surface, 3), p.surface)); thumb.setSize(dp(view, 20), dp(view, 20));
				seek.setProgressTintList(null); seek.setProgressBackgroundTintList(null); seek.setThumbTintList(null);
				seek.setProgressDrawable(layers); seek.setThumb(thumb); seek.setSplitTrack(false); seek.setThumbOffset(dp(view, 10));
				seek.setMinHeight(dp(view, 6)); seek.setMaxHeight(dp(view, 6)); seek.setMinimumHeight(Math.max(state.minimumHeight, dp(view, 48))); break;
			}
		}
		TouchFeedback.apply(view);
	}
	static final class State {
		Role role, appliedRole; boolean error, rendered, applied, appliedError; Drawable legacyError; ThemeEngine.Theme appliedTheme;
		final Drawable background; final ColorStateList backgroundTint; final StateListAnimator animator; final float elevation;
		final int start, top, end, bottom, minimumHeight;
		final ColorStateList text, hint, imageTint, buttonTint, trackTint, thumbTint, progressTint, progressBackgroundTint;
		final TransformationMethod transformation; final float textSize; final int maxLines;
		final Drawable mark, track, thumb, progress; final int switchWidth, thumbOffset, minTrackHeight, maxTrackHeight; final boolean splitTrack;
		State(View view) {
			background = view.getBackground(); backgroundTint = view.getBackgroundTintList(); animator = view.getStateListAnimator(); elevation = view.getElevation();
			start = view.getPaddingStart(); top = view.getPaddingTop(); end = view.getPaddingEnd(); bottom = view.getPaddingBottom(); minimumHeight = view.getMinimumHeight();
			TextView t = view instanceof TextView ? (TextView) view : null; text = t != null ? t.getTextColors() : null; hint = t != null ? t.getHintTextColors() : null;
			textSize = t != null ? t.getTextSize() : 0; transformation = t != null ? t.getTransformationMethod() : null; maxLines = t != null ? t.getMaxLines() : -1;
			imageTint = view instanceof ImageView ? ((ImageView) view).getImageTintList() : null;
			CompoundButton c = view instanceof CompoundButton ? (CompoundButton) view : null; mark = c != null ? c.getButtonDrawable() : null; buttonTint = c != null ? c.getButtonTintList() : null;
			Switch s = view instanceof Switch ? (Switch) view : null; SeekBar b = view instanceof SeekBar ? (SeekBar) view : null;
			track = s != null ? s.getTrackDrawable() : null; thumb = s != null ? s.getThumbDrawable() : b != null ? b.getThumb() : null;
			trackTint = s != null ? s.getTrackTintList() : null; thumbTint = s != null ? s.getThumbTintList() : b != null ? b.getThumbTintList() : null;
			switchWidth = s != null ? s.getSwitchMinWidth() : 0; splitTrack = s != null ? s.getSplitTrack() : b != null && b.getSplitTrack();
			progress = b != null ? b.getProgressDrawable() : null; progressTint = b != null ? b.getProgressTintList() : null;
			progressBackgroundTint = b != null ? b.getProgressBackgroundTintList() : null; thumbOffset = b != null ? b.getThumbOffset() : 0;
			minTrackHeight = b != null ? b.getMinHeight() : 0; maxTrackHeight = b != null ? b.getMaxHeight() : 0;
		}
		void restore(View view) {
			view.setBackground(role == Role.FIELD && error && legacyError != null ? legacyError : background); view.setBackgroundTintList(backgroundTint);
			view.setPaddingRelative(start, top, end, bottom); view.setMinimumHeight(minimumHeight);
			if (view instanceof TextView) {
				TextView t = (TextView) view; t.setTextColor(text); if (hint != null) t.setHintTextColor(hint);
				if (role == Role.ACTION || role == Role.TEXT_ACTION || role == Role.AUDIO_TITLE) {
					if (role == Role.AUDIO_TITLE) t.setSingleLine(true);
					t.setTransformationMethod(transformation); t.setTextSize(TypedValue.COMPLEX_UNIT_PX, textSize);
					if (role == Role.AUDIO_TITLE && maxLines >= 0) t.setMaxLines(maxLines);
				}
			}
			if (role == Role.ACTION || role == Role.TEXT_ACTION || role == Role.ICON) { view.setStateListAnimator(animator); view.setElevation(elevation); }
			if (view instanceof ImageView) ((ImageView) view).setImageTintList(imageTint);
			if (view instanceof CompoundButton) { CompoundButton c = (CompoundButton) view; c.setButtonDrawable(mark); c.setButtonTintList(buttonTint); }
			if (view instanceof Switch) {
				Switch s = (Switch) view; s.setTrackDrawable(track); s.setThumbDrawable(thumb); s.setTrackTintList(trackTint); s.setThumbTintList(thumbTint); s.setSwitchMinWidth(switchWidth); s.setSplitTrack(splitTrack);
			} else if (view instanceof SeekBar) {
				SeekBar b = (SeekBar) view; b.setProgressDrawable(progress); b.setThumb(thumb); b.setProgressTintList(progressTint); b.setProgressBackgroundTintList(progressBackgroundTint);
				b.setThumbTintList(thumbTint); b.setThumbOffset(thumbOffset); b.setSplitTrack(splitTrack); b.setMinHeight(minTrackHeight); b.setMaxHeight(maxTrackHeight);
			}
		}
	}
	private static final class Mark extends Drawable {
		final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG); final Path path = new Path(); final Palette palette; final int size; int alpha = 255;
		Mark(View view, Palette palette) { this.palette = palette; size = dp(view, 24); }
		@Override public boolean isStateful() { return true; }
		@Override protected boolean onStateChange(int[] state) { invalidateSelf(); return true; }
		@Override public int getIntrinsicWidth() { return size; }
		@Override public int getIntrinsicHeight() { return size; }
		@Override public void setAlpha(int alpha) { this.alpha = alpha; invalidateSelf(); }
		@Override public void setColorFilter(ColorFilter filter) { paint.setColorFilter(filter); invalidateSelf(); }
		@SuppressWarnings("deprecation") @Override public int getOpacity() { return PixelFormat.TRANSLUCENT; }
		@Override public void draw(Canvas canvas) {
			boolean checked = false, enabled = false; for (int state : getState()) { if (state == android.R.attr.state_checked) checked = true; if (state == android.R.attr.state_enabled) enabled = true; }
			Rect b = getBounds(); int save = canvas.save(); canvas.translate(b.left, b.top); canvas.scale(b.width() / 24f, b.height() / 24f);
			int color = checked ? palette.accent : palette.outline; if (!enabled) color = ColorUtils.blendARGB(palette.surface, color, .38f);
			paint.setColor(color); paint.setAlpha(alpha); paint.setStyle(checked ? Paint.Style.FILL : Paint.Style.STROKE); paint.setStrokeWidth(2);
			canvas.drawRoundRect(3, 3, 21, 21, 4, 4, paint);
			if (checked) { paint.setColor(palette.onAccent); paint.setAlpha(alpha); paint.setStyle(Paint.Style.STROKE); paint.setStrokeWidth(2); paint.setStrokeCap(Paint.Cap.ROUND); paint.setStrokeJoin(Paint.Join.ROUND);
				path.reset(); path.moveTo(7, 12); path.lineTo(10.5f, 15.5f); path.lineTo(17, 9); canvas.drawPath(path, paint); }
			canvas.restoreToCount(save);
		}
	}
}
