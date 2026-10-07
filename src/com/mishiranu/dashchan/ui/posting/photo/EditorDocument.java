package com.mishiranu.dashchan.ui.posting.photo;

import android.graphics.Color;
import android.graphics.RectF;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import java.util.ArrayDeque;
import java.util.ArrayList;

/** Bitmap-free editing state. All annotations use normalized, EXIF-oriented source coordinates. */
public final class EditorDocument {
    public enum Kind { PEN, MARKER, ERASER, LINE, ARROW, RECTANGLE, OVAL, TEXT, IMAGE, COVER, MOSAIC, BLUR }
    public enum Filter { NONE, MONO, SEPIA, WARM, COOL, VIVID, FADE }
    public static final int HISTORY_LIMIT = 24;
    public static final int ITEM_LIMIT = 200, PATH_FLOAT_LIMIT = 2048, TOTAL_PATH_FLOAT_LIMIT = 8000, TEXT_LIMIT = 8000;

    public static final class Item {
        public Kind kind = Kind.PEN;
        public final ArrayList<Float> points = new ArrayList<>();
        public float x, y, endX, endY;
        public float size = 0.025f, angle, scale = 1f;
        public int color = Color.WHITE;
        public boolean background, outline, mirrored;
        public String text = "", asset = "";

        public Item copy() {
            Item out = new Item();
            out.kind = kind; out.points.addAll(points);
            out.x = x; out.y = y; out.endX = endX; out.endY = endY;
            out.size = size; out.angle = angle; out.scale = scale; out.color = color;
            out.background = background; out.outline = outline; out.mirrored = mirrored; out.text = text; out.asset = asset;
            return out;
        }

        JSONObject toJson() throws JSONException {
            return new JSONObject().put("kind", kind.name()).put("points", new JSONArray(points))
                    .put("x", x).put("y", y).put("endX", endX).put("endY", endY)
                    .put("size", size).put("angle", angle).put("scale", scale).put("color", color)
                    .put("background", background).put("outline", outline).put("mirrored", mirrored).put("text", text).put("asset", asset);
        }

        static Item fromJson(JSONObject json) throws JSONException {
            Item out = new Item();
            out.kind = Kind.valueOf(json.getString("kind"));
            JSONArray points = json.getJSONArray("points");
            if (points.length() > PATH_FLOAT_LIMIT || (points.length() & 1) != 0) throw new JSONException("Invalid path");
            for (int i = 0; i < points.length(); i++) out.points.add((float) points.getDouble(i));
            out.x = (float) json.getDouble("x"); out.y = (float) json.getDouble("y");
            out.endX = (float) json.getDouble("endX"); out.endY = (float) json.getDouble("endY");
            out.size = (float) json.getDouble("size"); out.angle = (float) json.getDouble("angle");
            out.scale = (float) json.getDouble("scale"); out.color = json.getInt("color");
            out.background = json.optBoolean("background"); out.outline = json.optBoolean("outline");
            out.mirrored = json.optBoolean("mirrored");
            out.text = json.optString("text"); out.asset = json.optString("asset");
            if (out.text.length() > 2000 || !Float.isFinite(out.x) || !Float.isFinite(out.y)
                    || !Float.isFinite(out.endX) || !Float.isFinite(out.endY) || !Float.isFinite(out.size)
                    || !Float.isFinite(out.scale) || !Float.isFinite(out.angle) || out.size <= 0 || out.size > 1
                    || out.scale < .15f || out.scale > 8 || out.x < 0 || out.x > 1 || out.y < 0 || out.y > 1
                    || out.endX < 0 || out.endX > 1 || out.endY < 0 || out.endY > 1) throw new JSONException("Invalid annotation");
            for (float point : out.points) if (!Float.isFinite(point) || point < 0 || point > 1) throw new JSONException("Invalid point");
            if (out.asset.contains("/") || out.asset.contains("\\") || out.asset.contains("..")) {
                throw new JSONException("Invalid asset name");
            }
            return out;
        }
    }

    public static final class State {
        public int quarterTurns;
        public float straighten;
        public boolean mirror;
        public final RectF crop = new RectF(0, 0, 1, 1);
        public float aspect; // zero = free; ratio is in output orientation
        public final int[] adjustments = new int[7]; // brightness, contrast, saturation, warmth, shadows, highlights, sharpness
        public Filter filter = Filter.NONE;
        public int filterStrength = 100;
        public final ArrayList<Item> items = new ArrayList<>();

        public State copy() {
            State out = new State();
            out.quarterTurns = quarterTurns; out.straighten = straighten; out.mirror = mirror;
            out.crop.set(crop); out.aspect = aspect;
            System.arraycopy(adjustments, 0, out.adjustments, 0, adjustments.length);
            out.filter = filter; out.filterStrength = filterStrength;
            for (Item item : items) out.items.add(item.copy());
            return out;
        }

        JSONObject toJson() throws JSONException {
            JSONArray nodes = new JSONArray();
            for (Item item : items) nodes.put(item.toJson());
            return new JSONObject().put("turns", quarterTurns).put("straighten", straighten).put("mirror", mirror)
                    .put("crop", new JSONArray(new double[] {crop.left, crop.top, crop.right, crop.bottom}))
                    .put("aspect", aspect).put("adjustments", new JSONArray(adjustments))
                    .put("filter", filter.name()).put("strength", filterStrength).put("items", nodes);
        }

        static State fromJson(JSONObject json) throws JSONException {
            State out = new State();
            out.quarterTurns = json.getInt("turns") & 3;
            out.straighten = Math.max(-45f, Math.min(45f, (float) json.getDouble("straighten")));
            out.mirror = json.getBoolean("mirror");
            JSONArray crop = json.getJSONArray("crop");
            out.crop.set((float) crop.getDouble(0), (float) crop.getDouble(1),
                    (float) crop.getDouble(2), (float) crop.getDouble(3));
            if (!Float.isFinite(out.crop.left) || !Float.isFinite(out.crop.top)
                    || !Float.isFinite(out.crop.right) || !Float.isFinite(out.crop.bottom)
                    || out.crop.left < 0 || out.crop.top < 0 || out.crop.right > 1 || out.crop.bottom > 1
                    || out.crop.width() < 0.000001f || out.crop.height() < 0.000001f) throw new JSONException("Invalid crop");
            out.aspect = (float) json.getDouble("aspect");
            if (!Float.isFinite(out.aspect) || out.aspect < 0 || !Float.isFinite(out.straighten)) throw new JSONException("Invalid geometry");
            JSONArray adjustments = json.getJSONArray("adjustments");
            for (int i = 0; i < out.adjustments.length; i++) {
                out.adjustments[i] = Math.max(-100, Math.min(100, adjustments.getInt(i)));
            }
            out.filter = Filter.valueOf(json.getString("filter"));
            out.filterStrength = Math.max(0, Math.min(100, json.getInt("strength")));
            JSONArray nodes = json.getJSONArray("items");
            if (nodes.length() > ITEM_LIMIT) throw new JSONException("Too many annotations");
            for (int i = 0; i < nodes.length(); i++) out.items.add(Item.fromJson(nodes.getJSONObject(i)));
            if (out.pathFloatCount() > TOTAL_PATH_FLOAT_LIMIT) throw new JSONException("Too many path points");
            if (out.textLength() > TEXT_LIMIT) throw new JSONException("Too much text");
            return out;
        }

        public int pathFloatCount() {
            int count = 0; for (Item item : items) count += item.points.size(); return count;
        }
        public int textLength() {
            int count = 0; for (Item item : items) count += item.text.length(); return count;
        }
    }

    private State state = new State();
    private final ArrayDeque<State> undo = new ArrayDeque<>(), redo = new ArrayDeque<>();
    private State transactionStart;
    public State state() { return state; }
    public boolean canUndo() { return !undo.isEmpty(); }
    public boolean canRedo() { return !redo.isEmpty(); }
    public boolean isInTransaction() { return transactionStart != null; }

    /** A tool is a transaction; slider ticks and finger moves never consume separate history slots. */
    public void begin() {
        if (transactionStart == null) transactionStart = state.copy();
    }

    public void commit() {
        if (transactionStart == null) return;
        if (!same(transactionStart, state)) {
            undo.addLast(transactionStart);
            while (undo.size() > HISTORY_LIMIT) undo.removeFirst();
            redo.clear();
        }
        transactionStart = null;
    }

    public void cancel() {
        if (transactionStart != null) state = transactionStart;
        transactionStart = null;
    }

    public void undo() {
        if (isInTransaction() || undo.isEmpty()) return;
        redo.addLast(state); state = undo.removeLast();
    }

    public void redo() {
        if (isInTransaction() || redo.isEmpty()) return;
        undo.addLast(state); state = redo.removeLast();
    }

    public boolean hasChanges() { return !same(new State(), state); }

    /** Detach mutable UI state before serializing it on the worker. */
    public EditorDocument copy() {
        EditorDocument out = new EditorDocument();
        out.state = state.copy();
        for (State s : undo) out.undo.addLast(s.copy());
        for (State s : redo) out.redo.addLast(s.copy());
        out.transactionStart = transactionStart == null ? null : transactionStart.copy();
        return out;
    }

    private static boolean same(State a, State b) {
        try { return a.toJson().toString().equals(b.toJson().toString()); }
        catch (JSONException e) { throw new IllegalStateException(e); }
    }

    public JSONObject toJson() throws JSONException {
        JSONArray back = new JSONArray(), forward = new JSONArray();
        for (State s : undo) back.put(s.toJson());
        for (State s : redo) forward.put(s.toJson());
        return new JSONObject().put("version", 1).put("state", state.toJson()).put("undo", back).put("redo", forward)
                .put("transaction", transactionStart == null ? JSONObject.NULL : transactionStart.toJson());
    }

    public static EditorDocument fromJson(JSONObject json) throws JSONException {
        if (json.getInt("version") != 1) throw new JSONException("Unsupported editor session");
        EditorDocument out = new EditorDocument();
        out.state = State.fromJson(json.getJSONObject("state"));
        JSONArray back = json.getJSONArray("undo"), forward = json.getJSONArray("redo");
        if (back.length() > HISTORY_LIMIT || forward.length() > HISTORY_LIMIT) throw new JSONException("Invalid history");
        for (int i = 0; i < back.length(); i++) out.undo.addLast(State.fromJson(back.getJSONObject(i)));
        for (int i = 0; i < forward.length(); i++) out.redo.addLast(State.fromJson(forward.getJSONObject(i)));
        if (!json.isNull("transaction")) out.transactionStart = State.fromJson(json.getJSONObject("transaction"));
        return out;
    }
}
