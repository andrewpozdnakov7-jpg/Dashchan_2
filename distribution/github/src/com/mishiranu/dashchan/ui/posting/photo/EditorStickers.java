package com.mishiranu.dashchan.ui.posting.photo;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.view.View;
import com.mishiranu.dashchan.R;

/** Original procedural artwork. Stable IDs, no system emoji fonts or downloaded packs. */
public final class EditorStickers {
    private EditorStickers() {}
    public static final String[][] GROUPS = {
            {"smile", "laugh", "surprise", "wink", "love", "cool", "cry", "angry", "think", "tired", "sleep", "party", "facepalm", "grin", "sad", "confused", "neutral", "shy", "relieved", "scared", "proud", "unamused", "cheeky", "grateful"},
            {"heart", "star", "check", "attention", "cross", "question", "info", "plus", "minus", "lightning", "flame", "sparkles", "eye", "lock", "flag", "pin", "target", "clock", "play", "pause", "stop", "thumb_up", "thumb_down", "peace"},
            {"bubble", "burst", "ribbon", "arrow", "rounded_label", "oval_label", "cloud_label", "tag_label", "ticket_label", "folded_label", "underline_label", "bracket_label"}};
    private static final int[][] LABELS = {
            {R.string.pe_sticker_smile, R.string.pe_sticker_laugh, R.string.pe_sticker_surprise, R.string.pe_sticker_wink, R.string.pe_sticker_love, R.string.pe_sticker_cool, R.string.pe_sticker_cry, R.string.pe_sticker_angry, R.string.pe_sticker_think, R.string.pe_sticker_tired, R.string.pe_sticker_sleep, R.string.pe_sticker_party, R.string.pe_sticker_facepalm, R.string.pe_sticker_grin, R.string.pe_sticker_sad, R.string.pe_sticker_confused, R.string.pe_sticker_neutral, R.string.pe_sticker_shy, R.string.pe_sticker_relieved, R.string.pe_sticker_scared, R.string.pe_sticker_proud, R.string.pe_sticker_unamused, R.string.pe_sticker_cheeky, R.string.pe_sticker_grateful},
            {R.string.pe_sticker_heart, R.string.pe_sticker_star, R.string.pe_sticker_check, R.string.pe_sticker_attention, R.string.pe_sticker_cross, R.string.pe_sticker_question, R.string.pe_sticker_info, R.string.pe_sticker_plus, R.string.pe_sticker_minus, R.string.pe_sticker_lightning, R.string.pe_sticker_flame, R.string.pe_sticker_sparkles, R.string.pe_sticker_eye, R.string.pe_sticker_lock, R.string.pe_sticker_flag, R.string.pe_sticker_pin, R.string.pe_sticker_target, R.string.pe_sticker_clock, R.string.pe_sticker_play, R.string.pe_sticker_pause, R.string.pe_sticker_stop, R.string.pe_sticker_thumb_up, R.string.pe_sticker_thumb_down, R.string.pe_sticker_peace},
            {R.string.pe_sticker_bubble, R.string.pe_sticker_burst, R.string.pe_sticker_ribbon, R.string.pe_sticker_arrow, R.string.pe_sticker_rounded_label, R.string.pe_sticker_oval_label, R.string.pe_sticker_cloud_label, R.string.pe_sticker_tag_label, R.string.pe_sticker_ticket_label, R.string.pe_sticker_folded_label, R.string.pe_sticker_underline_label, R.string.pe_sticker_bracket_label}};
    private static final int INK = 0xff252936, CREAM = 0xffffdba5, BLUE = 0xffb3cfff, PINK = 0xffffb8c9;

    public static boolean contains(String id) {
        for (String[] group : GROUPS) for (String value : group) if (value.equals(id)) return true;
        return false;
    }
    public static int label(String id) {
        for (int g = 0; g < GROUPS.length; g++) for (int i = 0; i < GROUPS[g].length; i++)
            if (GROUPS[g][i].equals(id)) return LABELS[g][i];
        return R.string.pe_stickers;
    }
    private static void shape(Canvas canvas, Paint paint, Path path, int color) {
        paint.setStyle(Paint.Style.FILL); paint.setColor(color); canvas.drawPath(path, paint);
        paint.setStyle(Paint.Style.STROKE); paint.setColor(INK); canvas.drawPath(path, paint);
    }
    private static Path polygon(float... xy) {
        Path p = new Path(); p.moveTo(xy[0], xy[1]);
        for (int i = 2; i < xy.length; i += 2) p.lineTo(xy[i], xy[i + 1]); p.close(); return p;
    }
    private static Path star(int count, float inner, float outer, float angle) {
        Path p = new Path();
        for (int i = 0; i < count * 2; i++) {
            double a = Math.toRadians(angle + i * 180f / count); float r = (i & 1) == 0 ? outer : inner;
            float x = 50 + r * (float) Math.cos(a), y = 50 + r * (float) Math.sin(a);
            if (i == 0) p.moveTo(x, y); else p.lineTo(x, y);
        }
        p.close(); return p;
    }
    public static void draw(Canvas canvas, String id, RectF bounds) {
        if (!contains(id) || bounds.isEmpty()) return;
        int saved = canvas.save(); canvas.translate(bounds.left, bounds.top); canvas.scale(bounds.width() / 100, bounds.height() / 100);
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG); p.setStrokeWidth(3.5f);
        p.setStrokeJoin(Paint.Join.ROUND); p.setStrokeCap(Paint.Cap.ROUND);
        if (id.equals("smile") || id.equals("laugh") || id.equals("surprise") || id.equals("wink")) {
            Path face = new Path(); face.addCircle(50, 50, 42, Path.Direction.CW); shape(canvas, p, face, CREAM);
            p.setStyle(Paint.Style.FILL); p.setColor(INK);
            if (id.equals("wink")) { p.setStyle(Paint.Style.STROKE); canvas.drawLine(30, 40, 40, 43, p); }
            else canvas.drawOval(31, 34, 38, 46, p);
            p.setStyle(Paint.Style.FILL); canvas.drawOval(62, 34, 69, 46, p);
            if (id.equals("surprise")) canvas.drawOval(42, 56, 58, 77, p);
            else if (id.equals("laugh")) {
                Path mouth = new Path(); mouth.moveTo(28, 56); mouth.lineTo(72, 56); mouth.cubicTo(68, 85, 32, 85, 28, 56);
                shape(canvas, p, mouth, INK); p.setStyle(Paint.Style.FILL); p.setColor(PINK); canvas.drawOval(39, 67, 61, 78, p);
            } else { p.setStyle(Paint.Style.STROKE); canvas.drawArc(28, 43, 72, 72, 12, 156, false, p); }
        } else if (id.equals("heart")) {
            Path heart = new Path(); heart.moveTo(50, 86); heart.cubicTo(39, 77, 8, 53, 8, 33);
            heart.cubicTo(8, 9, 37, 4, 50, 25); heart.cubicTo(63, 4, 92, 9, 92, 33);
            heart.cubicTo(92, 53, 61, 77, 50, 86); heart.close(); shape(canvas, p, heart, PINK);
        } else if (id.equals("star")) shape(canvas, p, star(5, 20, 43, -90), CREAM);
        else if (id.equals("check")) {
            Path circle = new Path(); circle.addCircle(50, 50, 40, Path.Direction.CW); shape(canvas, p, circle, 0xffb5e5cd);
            p.setStrokeWidth(8); canvas.drawLine(28, 51, 43, 67, p); canvas.drawLine(43, 67, 73, 34, p);
        } else if (id.equals("attention")) {
            shape(canvas, p, polygon(50, 8, 94, 88, 6, 88), CREAM);
            p.setStrokeWidth(7); canvas.drawLine(50, 35, 50, 59, p); canvas.drawPoint(50, 73, p);
        } else if (id.equals("bubble")) {
            Path bubble = new Path(); bubble.moveTo(27, 75); bubble.cubicTo(4, 70, 2, 23, 26, 17);
            bubble.cubicTo(82, 0, 110, 45, 82, 68); bubble.cubicTo(72, 77, 51, 81, 39, 77);
            bubble.lineTo(16, 94); bubble.close(); shape(canvas, p, bubble, BLUE);
        } else if (id.equals("burst")) shape(canvas, p, star(12, 30, 44, -90), PINK);
        else if (id.equals("ribbon")) {
            shape(canvas, p, polygon(9, 26, 91, 26, 83, 50, 91, 74, 9, 74, 17, 50), BLUE);
            canvas.drawLine(26, 36, 26, 64, p); canvas.drawLine(74, 36, 74, 64, p);
        } else if (id.equals("arrow")) shape(canvas, p, polygon(7, 39, 55, 39, 55, 14, 94, 50, 55, 86, 55, 61, 7, 61), BLUE);
        else if (inGroup(id, 0)) drawReaction(canvas, p, id);
        else if (inGroup(id, 1)) drawSymbol(canvas, p, id);
        else drawPlaque(canvas, p, id);
        canvas.restoreToCount(saved);
    }

    private static boolean inGroup(String id, int group) {
        for (String value : GROUPS[group]) if (value.equals(id)) return true;
        return false;
    }
    public static boolean isPlaque(String id) { return inGroup(id, 2); }

    private static void ink(Paint p) { p.setColor(INK); p.setStyle(Paint.Style.STROKE); p.setStrokeWidth(3.5f); }
    private static void dot(Canvas c, Paint p, float x, float y) {
        p.setStyle(Paint.Style.FILL); p.setColor(INK); c.drawCircle(x, y, 3.5f, p); ink(p);
    }
    private static void heart(Canvas c, Paint p, float x, float y, float size) {
        int saved = c.save(); c.translate(x, y); c.scale(size, size);
        shape(c, p, polygon(0, 3, -9, -5, -8, -11, -3, -13, 0, -9, 3, -13, 8, -11, 9, -5), PINK);
        c.restoreToCount(saved);
    }
    private static void drawReaction(Canvas c, Paint p, String id) {
        Path face = new Path(); face.addCircle(50, 50, 42, Path.Direction.CW);
        shape(c, p, face, id.equals("angry") ? 0xffffb7a8 : id.equals("scared") ? BLUE : CREAM);
        ink(p);
        if (id.equals("love")) { heart(c, p, 34, 45, 1); heart(c, p, 66, 45, 1); }
        else if (id.equals("cool")) {
            shape(c, p, polygon(22, 34, 44, 34, 42, 49, 28, 49), INK);
            shape(c, p, polygon(56, 34, 78, 34, 72, 49, 58, 49), INK); c.drawLine(44, 37, 56, 37, p);
        } else if (id.equals("sleep") || id.equals("relieved") || id.equals("grateful")) {
            c.drawArc(25, 31, 43, 44, 15, 150, false, p); c.drawArc(57, 31, 75, 44, 15, 150, false, p);
        } else if (id.equals("unamused") || id.equals("tired")) {
            c.drawLine(26, 40, 41, 40, p); c.drawLine(59, 40, 74, 40, p);
        } else if (id.equals("proud")) {
            c.drawArc(26, 31, 42, 45, 190, 150, false, p); c.drawArc(58, 31, 74, 45, 190, 150, false, p);
        } else if (id.equals("angry")) {
            dot(c,p,34,43); dot(c,p,66,43); c.drawLine(26, 29, 42, 35, p); c.drawLine(58, 35, 74, 29, p);
        } else if (id.equals("think") || id.equals("confused")) {
            dot(c,p,37,42); dot(c,p,69,42); c.drawLine(27, 30, 42, 33, p); c.drawArc(57, 22, 77, 35, 190, 140, false, p);
        } else { dot(c,p,34,40); dot(c,p,66,40); }
        ink(p);
        if (id.equals("neutral")) c.drawLine(37, 65, 63, 65, p);
        else if (id.equals("sad") || id.equals("angry") || id.equals("cry")) c.drawArc(32, 60, 68, 84, 195, 150, false, p);
        else if (id.equals("scared")) { p.setStyle(Paint.Style.FILL); c.drawOval(40, 56, 60, 78, p); ink(p); }
        else if (id.equals("confused")) c.drawLine(40, 68, 61, 62, p);
        else if (id.equals("think")) c.drawLine(39, 64, 58, 64, p);
        else if (id.equals("tired") || id.equals("unamused")) c.drawArc(38, 58, 62, 75, 205, 125, false, p);
        else if (id.equals("grin") || id.equals("cheeky")) {
            Path mouth = new Path(); mouth.moveTo(28, 56); mouth.lineTo(72, 56); mouth.cubicTo(69, 82, 31, 82, 28, 56); mouth.close();
            shape(c,p,mouth,id.equals("grin") ? 0xfffaf6ed : INK);
            if (id.equals("grin")) { c.drawLine(30, 65, 70, 65, p); c.drawLine(50, 56, 50, 77, p); }
            else { p.setStyle(Paint.Style.FILL); p.setColor(PINK); c.drawOval(44, 64, 62, 81, p); }
        } else if (id.equals("sleep")) { p.setStyle(Paint.Style.FILL); c.drawOval(44, 62, 56, 76, p); }
        else c.drawArc(29, 43, 71, 74, 15, 150, false, p);
        if (id.equals("cry")) shape(c,p,polygon(30,47,23,63,28,71,36,65),BLUE);
        if (id.equals("tired")) { ink(p); c.drawArc(25,42,42,53,10,160,false,p); c.drawArc(58,42,75,53,10,160,false,p); }
        if (id.equals("unamused")) { ink(p); c.drawLine(27,29,41,32,p); c.drawLine(59,32,73,29,p); }
        if (id.equals("shy")) { p.setStyle(Paint.Style.FILL); p.setColor(PINK); c.drawOval(19,50,36,61,p); c.drawOval(64,50,81,61,p); }
        if (id.equals("facepalm")) shape(c,p,polygon(38,30,46,25,64,28,68,67,56,83,43,71),PINK);
        if (id.equals("think")) shape(c,p,polygon(58,68,80,61,85,71,66,83,58,78),PINK);
        if (id.equals("party")) { shape(c,p,polygon(34,24,56,0,68,24),PINK); ink(p); c.drawLine(5,15,10,23,p); c.drawLine(84,11,91,8,p); }
        if (id.equals("grateful")) { heart(c,p,50,83,.65f); }
        if (id.equals("sleep")) {
            ink(p); c.drawLine(76, 16, 89, 16,p); c.drawLine(89,16,76,27,p); c.drawLine(76,27,89,27,p);
        }
    }

    private static void drawSymbol(Canvas c, Paint p, String id) {
        // Line-only symbols need their own light backing on dark photos and tiles.
        if (id.equals("cross") || id.equals("question") || id.equals("info") || id.equals("plus")
                || id.equals("minus") || id.equals("target") || id.equals("clock")) {
            Path badge = new Path(); badge.addCircle(50, 50, 46, Path.Direction.CW);
            shape(c, p, badge, id.equals("cross") || id.equals("question") ? PINK : CREAM);
        }
        ink(p); p.setStrokeWidth(6);
        switch (id) {
            case "cross": c.drawLine(25,25,75,75,p); c.drawLine(75,25,25,75,p); break;
            case "question": {
                Path q = new Path(); q.moveTo(32,30); q.cubicTo(30,5,80,5,74,35); q.cubicTo(70,46,50,46,50,62);
                c.drawPath(q,p); dot(c,p,50,81); break;
            }
            case "info": c.drawCircle(50,50,39,p); dot(c,p,50,28); p.setStrokeWidth(6); c.drawLine(50,44,50,73,p); break;
            case "plus": c.drawLine(50,17,50,83,p); c.drawLine(17,50,83,50,p); break;
            case "minus": c.drawLine(17,50,83,50,p); break;
            case "lightning": shape(c,p,polygon(56,7,21,55,46,55,36,94,81,39,55,39),CREAM); break;
            case "flame": {
                Path f = new Path(); f.moveTo(51,7); f.cubicTo(73,26,57,33,78,42); f.cubicTo(105,91,19,108,18,62);
                f.cubicTo(17,39,39,34,36,24); f.cubicTo(42,29,43,42,47,45); f.cubicTo(60,29,41,25,51,7);
                shape(c,p,f,PINK); shape(c,p,polygon(50,49,67,78,50,91,35,79),CREAM); break;
            }
            case "sparkles": {
                int n=c.save(); c.translate(-9,7); c.scale(.9f,.9f); shape(c,p,star(4,9,38,-90),CREAM); c.restoreToCount(n);
                n=c.save(); c.translate(50,-13); c.scale(.55f,.55f); shape(c,p,star(4,9,38,-90),BLUE); c.restoreToCount(n); break;
            }
            case "eye": {
                Path eye=new Path(); eye.moveTo(6,50); eye.cubicTo(33,13,67,13,94,50); eye.cubicTo(67,87,33,87,6,50); eye.close();
                shape(c,p,eye,BLUE); p.setStyle(Paint.Style.FILL); p.setColor(INK); c.drawCircle(50,50,15,p); break;
            }
            case "lock": c.drawRoundRect(30,12,70,64,20,20,p); p.setStyle(Paint.Style.FILL); p.setColor(BLUE);
                c.drawRoundRect(17,42,83,90,10,10,p); ink(p); c.drawRoundRect(17,42,83,90,10,10,p); dot(c,p,50,61); c.drawLine(50,61,50,75,p); break;
            case "flag": c.drawLine(22,11,22,92,p); shape(c,p,polygon(23,13,82,13,69,34,82,54,23,54),PINK); break;
            case "pin": {
                Path pin=new Path(); pin.moveTo(50,94); pin.cubicTo(20,56,8,20,40,9); pin.cubicTo(98,-7,102,44,50,94);
                shape(c,p,pin,PINK); ink(p); c.drawCircle(50,35,12,p); break;
            }
            case "target": c.drawCircle(50,50,41,p); c.drawCircle(50,50,25,p); dot(c,p,50,50); break;
            case "clock": c.drawCircle(50,50,41,p); c.drawLine(50,21,50,50,p); c.drawLine(50,50,69,62,p); break;
            case "play": shape(c,p,polygon(25,14,85,50,25,86),BLUE); break;
            case "pause": shape(c,p,polygon(24,16,40,16,40,84,24,84),BLUE); shape(c,p,polygon(60,16,76,16,76,84,60,84),BLUE); break;
            case "stop": shape(c,p,polygon(18,18,82,18,82,82,18,82),PINK); break;
            case "thumb_up": case "thumb_down": {
                int n=c.save(); if(id.equals("thumb_down")) { c.translate(100,100); c.rotate(180); }
                shape(c,p,polygon(27,49,41,37,47,11,56,10,63,24,58,42,83,42,89,53,79,87,38,87,27,79),CREAM);
                shape(c,p,polygon(11,47,27,47,27,88,11,88),BLUE); c.restoreToCount(n); break;
            }
            case "peace": shape(c,p,polygon(37,92,22,76,21,57,30,52,21,15,29,9,46,47,49,47,62,8,71,10,64,54,78,51,85,58,76,85,62,94),CREAM); break;
            default: break;
        }
    }

    private static void drawPlaque(Canvas c, Paint p, String id) {
        Path path=new Path();
        switch(id) {
            case "rounded_label": path.addRoundRect(new RectF(6,24,94,76),14,14,Path.Direction.CW); shape(c,p,path,BLUE); break;
            case "oval_label": path.addOval(new RectF(4,20,96,80),Path.Direction.CW); shape(c,p,path,CREAM); break;
            case "cloud_label": path.moveTo(14,74); path.cubicTo(-2,67,1,45,16,42); path.cubicTo(7,14,40,7,49,26);
                path.cubicTo(69,4,99,23,88,43); path.cubicTo(111,54,100,82,77,77); path.close(); shape(c,p,path,BLUE); break;
            case "tag_label": shape(c,p,polygon(6,35,25,18,94,18,94,82,25,82,6,65),CREAM); ink(p); c.drawCircle(18,50,3,p); break;
            case "ticket_label": path.moveTo(7,22); path.lineTo(93,22); path.lineTo(93,40); path.cubicTo(77,40,77,60,93,60);
                path.lineTo(93,78); path.lineTo(7,78); path.lineTo(7,60); path.cubicTo(23,60,23,40,7,40); path.close(); shape(c,p,path,PINK); break;
            case "folded_label": shape(c,p,polygon(7,20,77,20,93,37,93,80,7,80),BLUE);
                shape(c,p,polygon(77,20,77,37,93,37),CREAM); break;
            case "underline_label": path.addRoundRect(new RectF(5,23,95,92),9,9,Path.Direction.CW); shape(c,p,path,CREAM);
                ink(p); p.setStrokeWidth(8); c.drawLine(10,77,90,77,p); p.setColor(PINK); p.setStrokeWidth(4); c.drawLine(16,86,84,86,p); break;
            case "bracket_label": path.addRoundRect(new RectF(4,16,96,84),8,8,Path.Direction.CW); shape(c,p,path,BLUE);
                ink(p); p.setStrokeWidth(6); c.drawLine(19,20,8,20,p); c.drawLine(8,20,8,80,p); c.drawLine(8,80,19,80,p);
                c.drawLine(81,20,92,20,p); c.drawLine(92,20,92,80,p); c.drawLine(92,80,81,80,p); break;
            default: break;
        }
    }

    /** Caption is part of the sticker, not an independent object. Clip, fit and rotate together. */
    public static void drawCaption(Canvas canvas, EditorDocument.Item item, RectF bounds) {
        if (!isPlaque(item.asset) || item.text.trim().isEmpty()) return;
        int saved=canvas.save(); canvas.translate(bounds.left,bounds.top); canvas.scale(bounds.width()/100,bounds.height()/100);
        RectF textBounds;
        switch (item.asset) {
            case "arrow": textBounds=new RectF(12,42,54,58); break;
            case "rounded_label": textBounds=new RectF(12,32,88,68); break;
            case "bubble": textBounds=new RectF(22,29,78,63); break;
            case "tag_label": textBounds=new RectF(30,30,87,70); break;
            case "folded_label": textBounds=new RectF(14,40,86,70); break;
            case "ribbon": textBounds=new RectF(29,34,71,66); break;
            case "burst": textBounds=new RectF(25,34,75,66); break;
            default: textBounds=new RectF(20,32,80,70); break;
        }
        canvas.clipRect(textBounds);
        Paint p=new Paint(Paint.ANTI_ALIAS_FLAG); p.setColor(item.color); p.setTextAlign(Paint.Align.CENTER);
        p.setTypeface(android.graphics.Typeface.create(android.graphics.Typeface.DEFAULT, android.graphics.Typeface.BOLD));
        String[] lines=item.text.split("\n",-1); int count=Math.min(3,lines.length); float size=18;
        p.setTextSize(size); float widest=0;
        for(int i=0;i<count;i++) widest=Math.max(widest,p.measureText(lines[i]));
        if(widest>textBounds.width()) size*=textBounds.width()/widest;
        p.setTextSize(size);
        if(p.getFontSpacing()*count>textBounds.height()) size*=textBounds.height()/(p.getFontSpacing()*count);
        p.setTextSize(size); Paint.FontMetrics fm=p.getFontMetrics();
        float y=textBounds.centerY()-(count-1)*p.getFontSpacing()/2-(fm.ascent+fm.descent)/2;
        for(int i=0;i<count;i++) { canvas.drawText(lines[i],textBounds.centerX(),y,p); y+=p.getFontSpacing(); }
        canvas.restoreToCount(saved);
    }

    /** Accessible native tile; the canvas artwork is identical in preview and export. */
    public static final class Tile extends View {
        private final String id;
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final RectF rect = new RectF();
        public Tile(Context context, String id, Runnable click) {
            super(context); this.id = id; setFocusable(true); setClickable(true);
            setContentDescription(context.getString(label(id))); setOnClickListener(v -> click.run());
            setMinimumWidth(Math.round(dp(48))); setMinimumHeight(Math.round(dp(48)));
            setForeground(new android.graphics.drawable.RippleDrawable(android.content.res.ColorStateList.valueOf(0x33a8c7fa), null,
                    new android.graphics.drawable.ColorDrawable(Color.WHITE)));
        }
        private float dp(float value) { return value * getResources().getDisplayMetrics().density; }
        @Override protected void onDraw(Canvas canvas) {
            rect.set(dp(4), dp(4), getWidth() - dp(4), getHeight() - dp(4));
            paint.setStyle(Paint.Style.FILL); paint.setColor(isFocused() ? EditorPalette.SELECTED : EditorPalette.SECONDARY);
            canvas.drawRoundRect(rect, dp(16), dp(16), paint);
            float side = Math.min(getWidth(), getHeight()) - dp(24); float x = (getWidth() - side) / 2, y = (getHeight() - side) / 2;
            rect.set(x, y, x + side, y + side); EditorStickers.draw(canvas, id, rect);
        }
        @Override public CharSequence getAccessibilityClassName() { return android.widget.Button.class.getName(); }
    }
}
