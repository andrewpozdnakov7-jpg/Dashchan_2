package com.mishiranu.dashchan.ui.posting.photo;

import android.content.Context;
import android.content.SharedPreferences;
import android.content.res.ColorStateList;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RadialGradient;
import android.graphics.Shader;
import android.graphics.SweepGradient;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.view.accessibility.AccessibilityNodeInfo;
import android.widget.Button;
import android.widget.EditText;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.TextView;
import com.mishiranu.dashchan.R;
import java.util.ArrayList;
import java.util.List;
import java.util.function.IntConsumer;

/** One local draft, shared by brush, cover and text. No image/history mutation before confirmation. */
public final class EditorColorPickerView extends LinearLayout {
    public static final int[] QUICK = {Color.WHITE, Color.BLACK, 0xfff44336, 0xffff9800,
            0xffffeb3b, 0xff4caf50, 0xff2196f3, 0xff9c27b0};
    private final EditorColorState state;
    private final ColorSurface surface;
    private final SeekBar brightness;
    private final TextView current;
    private final Swatch sample;
    private EditText hex;
    private LinearLayout hexRow;
    private final Button grid, wheel;
    private final ArrayList<Swatch> samples = new ArrayList<>();
    private final SharedPreferences preferences;
    private final List<Integer> recent = new ArrayList<>();
    private boolean circle, closed;

    public EditorColorPickerView(Context context, int initialColor, IntConsumer accept, Runnable cancel) {
        super(context); setOrientation(VERTICAL); setBackgroundColor(EditorPalette.SURFACE);
        state = new EditorColorState(initialColor);
        preferences = context.getSharedPreferences("photo-editor-ui", Context.MODE_PRIVATE);
        for (String value : preferences.getString("recent-colors", "").split(",")) {
            Integer color = EditorColorState.parseHex(value);
            if (color != null && !recent.contains(color) && recent.size() < 8) recent.add(color);
        }
        ScrollView scroll = new ScrollView(context); scroll.setFillViewport(false);
        addView(scroll, new LayoutParams(-1, 0, 1));
        LinearLayout body = new LinearLayout(context); body.setOrientation(VERTICAL);
        body.setPadding(dp(20), dp(12), dp(20), dp(8)); scroll.addView(body);
        TextView title = text(R.string.pe_color); title.setTextSize(20); body.addView(title);
        LinearLayout modes = new LinearLayout(context); body.addView(modes);
        grid = button(R.string.pe_color_grid, () -> setCircle(false)); wheel = button(R.string.pe_color_circle, () -> setCircle(true));
        modes.addView(grid, new LayoutParams(0, -2, 1)); modes.addView(wheel, new LayoutParams(0, -2, 1));
        surface = new ColorSurface(context); body.addView(surface, new LayoutParams(-1, dp(220)));
        TextView brightnessTitle = text(R.string.pe_color_brightness); body.addView(brightnessTitle);
        brightness = new SeekBar(context); brightness.setMax(1000);
        brightness.setContentDescription(context.getString(R.string.pe_color_brightness));
        brightness.setThumbTintList(ColorStateList.valueOf(EditorPalette.ACCENT));
        brightness.setProgressTintList(ColorStateList.valueOf(Color.TRANSPARENT));
        brightness.setProgressBackgroundTintList(ColorStateList.valueOf(Color.TRANSPARENT));
        body.addView(brightness, new LayoutParams(-1, dp(48)));
        brightness.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar bar, int progress, boolean user) {
                if (user) { hex.clearFocus(); hideIme(); state.setValue(progress / 1000d); refresh(); }
            }
            @Override public void onStartTrackingTouch(SeekBar bar) {}
            @Override public void onStopTrackingTouch(SeekBar bar) {}
        });
        // The brightness row keeps its measured place in grid mode too.
        brightnessTitle.setVisibility(INVISIBLE); brightness.setVisibility(INVISIBLE);
        brightness.setTag(brightnessTitle);
        LinearLayout selected = new LinearLayout(context); selected.setGravity(Gravity.CENTER_VERTICAL); body.addView(selected);
        sample = new Swatch(context, state.color()); sample.setFocusable(false);
        sample.setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
        selected.addView(sample, new LayoutParams(dp(48), dp(48)));
        current = text(R.string.pe_color); selected.addView(current);
        body.addView(text(R.string.pe_color_quick)); addSamples(body, QUICK);
        body.addView(text(R.string.pe_color_recent));
        if (recent.isEmpty()) body.addView(text(R.string.pe_color_no_recent));
        else { int[] values = new int[recent.size()]; for (int i = 0; i < values.length; i++) values[i] = recent.get(i); addSamples(body, values); }
        body.addView(button(R.string.pe_color_hex, () -> {
            hexRow.setVisibility(hexRow.getVisibility() == VISIBLE ? GONE : VISIBLE);
            if (hexRow.getVisibility() == GONE) { hex.clearFocus(); hideIme(); }
        }));
        hexRow = new LinearLayout(context); hexRow.setGravity(Gravity.CENTER_VERTICAL); hexRow.setVisibility(GONE);
        hex = new EditText(context); hex.setSingleLine(); hex.setHint("#RRGGBB"); hex.setTextColor(EditorPalette.TEXT);
        hex.setHintTextColor(EditorPalette.MUTED); hex.setTextSize(16);
        hex.setInputType(android.text.InputType.TYPE_CLASS_TEXT | android.text.InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS);
        GradientDrawable input = shape(EditorPalette.INPUT, 12); input.setStroke(dp(1), EditorPalette.BORDER);
        hex.setBackground(input); hex.setBackgroundTintList(null); hex.setPadding(dp(12), dp(8), dp(12), dp(8));
        hexRow.addView(hex, new LayoutParams(0, -2, 1)); hexRow.addView(button(R.string.pe_color_use_hex, this::applyHex)); body.addView(hexRow);
        LinearLayout actions = new LinearLayout(context); actions.setGravity(Gravity.END); actions.setPadding(dp(12), 0, dp(12), dp(8));
        actions.addView(button(android.R.string.cancel, () -> { if (!closed) { closed = true; hideIme(); cancel.run(); } }), new LayoutParams(0, -2, 1));
        actions.addView(button(R.string.pe_color_done, () -> {
            if (closed || hexRow.getVisibility() == VISIBLE && !applyHex()) return;
            closed = true; hideIme();
            StringBuilder stored = new StringBuilder();
            for (int color : EditorColorState.recent(recent, state.color())) { if (stored.length() > 0) stored.append(','); stored.append(EditorColorState.hex(color)); }
            preferences.edit().putString("recent-colors", stored.toString()).apply();
            accept.accept(state.color());
        }), new LayoutParams(0, -2, 1)); addView(actions, new LayoutParams(-1, -2));
        refresh();
    }

    @Override protected void onMeasure(int width, int height) {
        int cap = Math.min(dp(600), Math.round(getResources().getDisplayMetrics().heightPixels * .75f));
        if (MeasureSpec.getMode(height) != MeasureSpec.UNSPECIFIED) cap = Math.min(cap, MeasureSpec.getSize(height));
        super.onMeasure(width, MeasureSpec.makeMeasureSpec(cap, MeasureSpec.EXACTLY));
    }
    private int dp(float n) { return Math.round(n * getResources().getDisplayMetrics().density); }
    private GradientDrawable shape(int color, float radius) { GradientDrawable d = new GradientDrawable(); d.setColor(color); d.setCornerRadius(dp(radius)); return d; }
    private TextView text(int res) { TextView t = new TextView(getContext()); t.setText(res); t.setTextSize(14); t.setTextColor(EditorPalette.TEXT); t.setPadding(0, dp(6), 0, dp(6)); return t; }
    private Button button(int res, Runnable action) {
        Button b = new Button(getContext()); b.setText(res); b.setAllCaps(false); b.setTextSize(14);
        b.setMinHeight(dp(48)); b.setMinimumHeight(dp(48)); b.setTextColor(EditorPalette.TEXT);
        b.setBackground(shape(EditorPalette.SECONDARY, 22)); b.setBackgroundTintList(null);
        b.setOnClickListener(v -> action.run()); return b;
    }
    private void hideIme() {
        android.view.inputmethod.InputMethodManager ime = (android.view.inputmethod.InputMethodManager) getContext().getSystemService(Context.INPUT_METHOD_SERVICE);
        if (ime != null) ime.hideSoftInputFromWindow(getWindowToken(), 0);
    }
    private void addSamples(LinearLayout parent, int[] colors) {
        HorizontalScrollView scroll = new HorizontalScrollView(getContext()); parent.addView(scroll);
        LinearLayout row = new LinearLayout(getContext()); scroll.addView(row);
        for (int color : colors) {
            Swatch swatch = new Swatch(getContext(), color); samples.add(swatch);
            row.addView(swatch, new LayoutParams(dp(48), dp(48)));
            swatch.setOnClickListener(v -> { hex.clearFocus(); hideIme(); state.setRgb(color); refresh(); });
        }
    }
    private boolean applyHex() {
        Integer color = EditorColorState.parseHex(hex.getText().toString());
        if (color == null) { hex.setError(getContext().getString(R.string.pe_invalid_color)); return false; }
        hex.setError(null); state.setRgb(color); refresh(); return true;
    }
    private void setCircle(boolean value) {
        if (circle == value) return;
        circle = value; surface.animate().cancel(); surface.setAlpha(1);
        if (EditorMotion.enabled()) { surface.setAlpha(.45f); surface.animate().alpha(1).setDuration(150).start(); }
        refresh();
    }
    private void refresh() {
        sample.setColor(state.color()); current.setText(EditorColorState.hex(state.color()));
        if (!hex.hasFocus()) hex.setText(EditorColorState.hex(state.color()));
        for (Swatch s : samples) s.setSelected(s.color == state.color());
        for (Button b : new Button[] {grid, wheel}) {
            boolean active = b == (circle ? wheel : grid); b.setSelected(active);
            b.setTextColor(active ? EditorPalette.ACCENT : EditorPalette.TEXT);
            b.setBackground(shape(active ? EditorPalette.SELECTED : EditorPalette.SECONDARY, 22));
        }
        brightness.setVisibility(circle ? VISIBLE : INVISIBLE); ((View) brightness.getTag()).setVisibility(circle ? VISIBLE : INVISIBLE);
        brightness.setProgress((int) Math.round(state.value() * 1000));
        GradientDrawable gradient = new GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT,
                new int[] {Color.BLACK, EditorColorState.hsv(state.hue(), state.saturation(), 1)});
        gradient.setCornerRadius(dp(8));
        brightness.setBackground(new android.graphics.drawable.InsetDrawable(gradient, dp(16), dp(18), dp(16), dp(18)));
        surface.setContentDescription(getContext().getString(circle ? R.string.pe_color_wheel_description : R.string.pe_color_grid_description,
                EditorColorState.hex(state.color()))); surface.invalidate();
    }
    @Override protected void onDetachedFromWindow() { surface.animate().cancel(); surface.setAlpha(1); super.onDetachedFromWindow(); }

    /** A real 48dp control with a 30dp opaque swatch and independent contrast ring. */
    public static final class Swatch extends View {
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private int color;
        public Swatch(Context context, int color) { super(context); setFocusable(true); setColor(color); }
        public void setColor(int color) { this.color = color | 0xff000000; setContentDescription(getContext().getString(R.string.pe_color_sample, EditorColorState.hex(this.color))); invalidate(); }
        @Override public void setSelected(boolean selected) { super.setSelected(selected); invalidate(); }
        @Override protected void onDraw(Canvas canvas) {
            float density = getResources().getDisplayMetrics().density, radius = Math.min(15 * density, Math.min(getWidth(), getHeight()) / 2f - 3 * density);
            paint.setStyle(Paint.Style.FILL); paint.setColor(color); canvas.drawCircle(getWidth()/2f, getHeight()/2f, radius, paint);
            paint.setStyle(Paint.Style.STROKE); paint.setStrokeWidth(2 * density); paint.setColor(isSelected() ? EditorPalette.ACCENT : EditorPalette.BORDER);
            canvas.drawCircle(getWidth()/2f, getHeight()/2f, radius + density, paint);
        }
        @Override public void onInitializeAccessibilityNodeInfo(AccessibilityNodeInfo info) { super.onInitializeAccessibilityNodeInfo(info); info.setClassName(Button.class.getName()); }
    }

    private final class ColorSurface extends View {
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private boolean dragging;
        ColorSurface(Context context) { super(context); setFocusable(true); setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_YES); }
        private float radius() { return Math.max(1, Math.min(getWidth(), getHeight()) / 2f - dp(8)); }
        @Override protected void onDraw(Canvas canvas) {
            if (circle) {
                float cx = getWidth()/2f, cy = getHeight()/2f, r = radius();
                int[] colors = new int[7]; for (int i=0;i<7;i++) colors[i]=EditorColorState.hsv(i*60,1,state.value());
                paint.setStyle(Paint.Style.FILL); paint.setShader(new SweepGradient(cx,cy,colors,null)); canvas.drawCircle(cx,cy,r,paint);
                int gray = EditorColorState.hsv(0,0,state.value());
                paint.setShader(new RadialGradient(cx,cy,r,new int[] {gray,gray & 0x00ffffff},null,Shader.TileMode.CLAMP));
                canvas.drawCircle(cx,cy,r,paint); paint.setShader(null);
                double angle=Math.toRadians(state.hue()); marker(canvas,cx+(float)(Math.cos(angle)*r*state.saturation()),cy+(float)(Math.sin(angle)*r*state.saturation()));
            } else {
                Path clip=new Path(); clip.addRoundRect(0,0,getWidth(),getHeight(),dp(16),dp(16),Path.Direction.CW);
                int save=canvas.save(); canvas.clipPath(clip); paint.setStyle(Paint.Style.FILL);
                float w=getWidth()/(float)EditorColorState.COLUMNS,h=getHeight()/(float)EditorColorState.ROWS;
                for(int i=0;i<120;i++){paint.setColor(EditorColorState.cell(i));int row=i/12,col=i%12;canvas.drawRect(col*w,row*h,(col+1)*w,(row+1)*h,paint);}
                int selected=state.selectedCell(); if(selected>=0)marker(canvas,(selected%12+.5f)*w,(selected/12+.5f)*h);
                canvas.restoreToCount(save);
            }
        }
        private void marker(Canvas canvas,float x,float y) {
            paint.setStyle(Paint.Style.STROKE); paint.setColor(Color.BLACK); paint.setStrokeWidth(dp(4)); canvas.drawCircle(x,y,dp(6),paint);
            paint.setColor(Color.WHITE); paint.setStrokeWidth(dp(2)); canvas.drawCircle(x,y,dp(6),paint); paint.setStyle(Paint.Style.FILL);
        }
        private boolean hit(float x,float y){return circle?Math.hypot(x-getWidth()/2f,y-getHeight()/2f)<=radius():x>=0&&y>=0&&x<getWidth()&&y<getHeight();}
        private void select(float x,float y) {
            hex.clearFocus(); hideIme();
            if(circle){double dx=x-getWidth()/2f,dy=y-getHeight()/2f;state.setHueSaturation(Math.toDegrees(Math.atan2(dy,dx)),Math.hypot(dx,dy)/radius());}
            else {int col=Math.max(0,Math.min(11,(int)(x*12/Math.max(1,getWidth())))),row=Math.max(0,Math.min(9,(int)(y*10/Math.max(1,getHeight()))));state.setRgb(EditorColorState.cell(row*12+col));}
            refresh();
        }
        @Override public boolean onTouchEvent(MotionEvent event) {
            switch(event.getActionMasked()) {
                case MotionEvent.ACTION_DOWN: if(!hit(event.getX(),event.getY()))return false;dragging=true;requestFocus();getParent().requestDisallowInterceptTouchEvent(true);select(event.getX(),event.getY());return true;
                case MotionEvent.ACTION_MOVE: if(!dragging)return false;select(event.getX(),event.getY());return true;
                case MotionEvent.ACTION_UP: if(!dragging)return false;select(event.getX(),event.getY());dragging=false;getParent().requestDisallowInterceptTouchEvent(false);performClick();return true;
                case MotionEvent.ACTION_CANCEL: dragging=false;getParent().requestDisallowInterceptTouchEvent(false);return true;
                default: return dragging;
            }
        }
        @Override public boolean performClick(){super.performClick();return true;}
        private void step(int amount,boolean vertical) {
            hex.clearFocus(); hideIme();
            if(circle)state.setHueSaturation(state.hue()+(vertical?0:amount*5),state.saturation()+(vertical?amount*.05:0));
            else {int cell=Math.max(0,state.selectedCell());state.setRgb(EditorColorState.cell(Math.max(0,Math.min(119,cell+amount*(vertical?12:1)))));}
            refresh();
        }
        @Override public boolean onKeyDown(int key,KeyEvent event) {
            if(key==KeyEvent.KEYCODE_DPAD_LEFT||key==KeyEvent.KEYCODE_DPAD_RIGHT){step(key==KeyEvent.KEYCODE_DPAD_LEFT?-1:1,false);return true;}
            if(key==KeyEvent.KEYCODE_DPAD_UP||key==KeyEvent.KEYCODE_DPAD_DOWN){step(key==KeyEvent.KEYCODE_DPAD_UP?-1:1,true);return true;}
            return super.onKeyDown(key,event);
        }
        @Override public void onInitializeAccessibilityNodeInfo(AccessibilityNodeInfo info) {
            super.onInitializeAccessibilityNodeInfo(info);info.setClassName(SeekBar.class.getName());
            info.setRangeInfo(new AccessibilityNodeInfo.RangeInfo(AccessibilityNodeInfo.RangeInfo.RANGE_TYPE_INT,0,circle?359:119,circle?(float)state.hue():Math.max(0,state.selectedCell())));
            info.addAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_FORWARD);info.addAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_BACKWARD);
            info.addAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SET_PROGRESS);
        }
        @Override public boolean performAccessibilityAction(int action,Bundle args) {
            if(action==AccessibilityNodeInfo.ACTION_SCROLL_FORWARD||action==AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD){step(action==AccessibilityNodeInfo.ACTION_SCROLL_FORWARD?1:-1,false);return true;}
            if(action==AccessibilityNodeInfo.AccessibilityAction.ACTION_SET_PROGRESS.getId()&&args!=null){float v=args.getFloat(AccessibilityNodeInfo.ACTION_ARGUMENT_PROGRESS_VALUE);if(circle)state.setHueSaturation(v,state.saturation());else state.setRgb(EditorColorState.cell(Math.max(0,Math.min(119,Math.round(v)))));refresh();return true;}
            return super.performAccessibilityAction(action,args);
        }
    }
}
