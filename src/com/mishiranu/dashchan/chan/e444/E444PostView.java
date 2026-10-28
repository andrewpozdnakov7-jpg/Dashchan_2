package com.mishiranu.dashchan.chan.e444;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.drawable.BitmapDrawable;
import android.util.LruCache;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;
import chan.content.Chan;
import chan.http.HttpHolder;
import chan.http.HttpRequest;
import chan.util.StringUtils;
import com.mishiranu.dashchan.R;
import com.mishiranu.dashchan.content.Preferences;
import com.mishiranu.dashchan.content.async.HttpHolderTask;
import com.mishiranu.dashchan.content.model.PostItem;
import com.mishiranu.dashchan.ui.navigator.manager.UiManager;
import com.mishiranu.dashchan.util.NavigationUtils;
import com.mishiranu.dashchan.util.ResourceUtils;
import com.mishiranu.dashchan.widget.ClickableToast;
import com.mishiranu.dashchan.widget.LinebreakLayout;
import com.mishiranu.dashchan.widget.EchPostAppearance;
import com.mishiranu.dashchan.widget.InterfaceAppearance;
import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Typed post-local binding. No activity observers, window scans, injected listeners or reflection. */
public final class E444PostView extends LinearLayout {
    private static final ExecutorService IO = Executors.newFixedThreadPool(2);
    private static final Object SELECTION_LOCK = new Object();
    private static final LruCache<String, Bitmap> ICONS = new LruCache<String, Bitmap>(256 * 1024) {
        @Override protected int sizeOf(String key, Bitmap bitmap) { return bitmap.getAllocationByteCount(); }
    };
    private final List<HttpHolderTask<?, ?>> tasks = new ArrayList<>();
    private PostItem item;
    private UiManager manager;
    private E444Model.Post data;
    private Object generation = new Object();
    private boolean interactionEnabled = true;
    private boolean busy;

    public E444PostView(Context context) {
        super(context);
        setOrientation(VERTICAL);
        setVisibility(GONE);
        int spacing = dp(6);
        setPadding(spacing, 0, spacing, spacing);
    }

    public void bind(UiManager manager, PostItem item, String chanName) {
        cancelTasks();
        this.manager = manager;
        this.item = "e444".equals(chanName) && !item.isDeleted() ? item : null;
        this.data = null;
        this.busy = false;
        if (this.item != null && item.getE444Data() != null) {
            try { data = E444JsonUtils.fromJson(item.getE444Data(), E444Model.Post.class); }
            catch (IOException ignored) { /* Older/corrupt optional metadata must not break post rendering. */ }
        }
        render();
    }

    public void setInteractionEnabled(boolean enabled) {
        interactionEnabled = enabled;
        enableChildren(this, enabled && !busy);
    }

    private static void enableChildren(android.view.ViewGroup group, boolean enabled) {
        for (int i = 0; i < group.getChildCount(); i++) {
            View child = group.getChildAt(i);
            child.setEnabled(enabled);
            if (child instanceof android.view.ViewGroup) enableChildren((android.view.ViewGroup) child, enabled);
        }
    }

    private int dp(int value) { return Math.round(value * ResourceUtils.obtainDensity(this)); }

    private String selected(String key) {
        boolean poll = "vote".equals(key);
        String raw = getContext().getSharedPreferences(poll ? "e444_polls" : "e444_reactions", Context.MODE_PRIVATE)
                .getString(poll ? "toggled_vote_index" : "toggled", "{}");
        try { return new org.json.JSONObject(raw).optString(item.getBoardName() + ":" + item.getPostNumber(), ""); }
        catch (org.json.JSONException ignored) { return ""; }
    }

    private void rememberSelection(String key, String value, PostItem post) {
        synchronized (SELECTION_LOCK) {
        boolean poll = "vote".equals(key);
        android.content.SharedPreferences preferences = getContext().getSharedPreferences(
                poll ? "e444_polls" : "e444_reactions", Context.MODE_PRIVATE);
        String name = poll ? "toggled_vote_index" : "toggled";
        try {
            org.json.JSONObject values = new org.json.JSONObject(preferences.getString(name, "{}"));
            String id = post.getBoardName() + ":" + post.getPostNumber();
            if (!poll && value.equals(values.optString(id))) values.remove(id);
            else values.put(id, poll ? Integer.valueOf(value) : value);
            while (values.length() > 512) values.remove(values.keys().next());
            preferences.edit().putString(name, values.toString()).apply();
        } catch (org.json.JSONException | NumberFormatException ignored) { /* Optional selection marker. */ }
        }
    }

    private TextView button(CharSequence label) {
        return button(label, false);
    }

    private TextView button(CharSequence label, boolean poll) {
        TextView view = new ActionView(poll);
        view.setText(label);
        view.setAllCaps(false);
        view.setTextSize(13f * Preferences.getTextScale());
        view.setMinHeight(dp(40));
        view.setPadding(dp(8), dp(6), dp(8), dp(6));
        view.setEnabled(interactionEnabled && !busy);
        view.setFocusable(true);
        EchPostAppearance.action(view, poll);
        return view;
    }

    private final class ActionView extends TextView {
        final boolean poll;
        ActionView(boolean poll) { super(E444PostView.this.getContext(), null, 0, R.style.Widget_PostButton); this.poll = poll; }
    }

    /** A theme/font refresh only restyles existing views. It never calls render or loads icons. */
    public void refreshAppearance() {
        refreshChildren(this);
        requestLayout();
    }

    private static void refreshChildren(android.view.ViewGroup group) {
        if (group instanceof E444FlowLayout) {
            int spacing = Math.round((InterfaceAppearance.isEnabled(group.getContext()) ? 8 : 4) * ResourceUtils.obtainDensity(group));
            ((E444FlowLayout) group).setHorizontalSpacing(spacing);
            ((E444FlowLayout) group).setVerticalSpacing(spacing);
        }
        for (int i = 0; i < group.getChildCount(); i++) {
            View child = group.getChildAt(i);
            if (child instanceof ActionView) EchPostAppearance.action((TextView) child, ((ActionView) child).poll);
            else if (child instanceof TextView) EchPostAppearance.section((TextView) child);
            else if (child instanceof android.view.ViewGroup) refreshChildren((android.view.ViewGroup) child);
        }
    }

    private TextView section(CharSequence label) {
        TextView view = new TextView(getContext());
        view.setText(label); view.setTextSize(13f * Preferences.getTextScale());
        view.setPadding(dp(8), dp(6), dp(8), dp(6));
        view.setAccessibilityHeading(true);
        EchPostAppearance.section(view);
        return view;
    }

    private LinebreakLayout row() {
        LinebreakLayout row = new E444FlowLayout(getContext());
        int spacing = dp(InterfaceAppearance.isEnabled(getContext()) ? 8 : 4);
        row.setHorizontalSpacing(spacing);
        row.setVerticalSpacing(spacing);
        addView(row, new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT));
        return row;
    }

    private void render() {
        cancelTasks();
        removeAllViews();
        if (item == null || data == null) { setVisibility(GONE); return; }
        for (E444Model.MenuSection section : data.menu) {
            if (!StringUtils.isEmpty(section.sectionName)) addView(section(StringUtils.clearHtml(section.sectionName)));
            LinebreakLayout links = row();
            for (E444Model.MenuLink link : section.links) {
                android.net.Uri uri;
                try { uri = android.net.Uri.parse(new java.net.URI("https://ech.bz/")
                        .resolve(StringUtils.emptyIfNull(link.url)).toString()); }
                catch (java.net.URISyntaxException | IllegalArgumentException ignored) { continue; }
                if (!"https".equalsIgnoreCase(uri.getScheme()) && !"http".equalsIgnoreCase(uri.getScheme())) continue;
                final android.net.Uri target = uri;
                TextView view = button(StringUtils.clearHtml(link.label));
                view.setOnClickListener(v -> NavigationUtils.handleUri(getContext(), "e444", target,
                        NavigationUtils.BrowserType.AUTO));
                links.addView(view);
            }
        }
        long total = 0;
        for (Integer count : data.poll_results_exact) total += Math.max(0, count);
        final long votes = total;
        for (int i = 0; i < Math.min(data.answers.size(), 32); i++) {
            final int index = i;
            int count = i < data.poll_results_exact.size() ? Math.max(0, data.poll_results_exact.get(i)) : 0;
            int percent = votes > 0 ? (int) Math.round(count * 100.0 / votes) : 0;
            TextView answer = button(StringUtils.clearHtml(data.answers.get(i)) + " — " + percent + "% (" + count + ")", true);
            boolean chosen = Integer.toString(index).equals(selected("vote"));
            answer.setSelected(chosen);
            EchPostAppearance.action(answer, true);
            answer.setOnClickListener(v -> send("api/polls/vote", "vote", Integer.toString(index)));
            addView(answer, new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT));
        }
        LinkedHashSet<String> icons = new LinkedHashSet<>();
        for (E444Model.Reaction reaction : data.reactions) icons.add(reaction.icon);
        E444Model.Board board = ((E444ChanConfiguration) Chan.get("e444").configuration).getCachedBoard(item.getBoardName());
        if (board.enableReactions != 0 && board.reactions != null) icons.addAll(board.reactions);
        if (!icons.isEmpty()) {
            LinebreakLayout reactions = row();
            int shown = 0;
            for (String icon : icons) {
                if (icon == null || !icon.matches("[A-Za-z0-9_.-]{1,120}") || ++shown > 64) continue;
                int count = 0;
                for (E444Model.Reaction reaction : data.reactions) if (icon.equals(reaction.icon)) count = Math.max(0, reaction.count);
                TextView view = button(Integer.toString(count));
                boolean chosen = icon.equals(selected("icon"));
                view.setSelected(chosen);
                EchPostAppearance.action(view, false);
                view.setContentDescription(icon + ": " + count);
                view.setOnClickListener(v -> send("api/react", "icon", icon));
                reactions.addView(view);
                Bitmap bitmap = ICONS.get(icon);
                if (bitmap != null) applyIcon(view, bitmap);
                else { view.setText(icon + " " + count); if (isAttachedToWindow()) loadIcon(view, icon); }
            }
        }
        setVisibility(getChildCount() > 0 ? VISIBLE : GONE);
        setInteractionEnabled(interactionEnabled);
    }

    private void applyIcon(TextView view, Bitmap bitmap) {
        BitmapDrawable drawable = new BitmapDrawable(getResources(), bitmap);
        drawable.setBounds(0, 0, dp(20), dp(20));
        view.setCompoundDrawables(drawable, null, null, null);
        view.setCompoundDrawablePadding(dp(4));
    }

    private void loadIcon(TextView view, String icon) {
        Object token = generation;
        HttpHolderTask<Void, Bitmap> task = new HttpHolderTask<Void, Bitmap>(Chan.get("e444")) {
            @Override protected Bitmap run(HttpHolder holder) {
                try { return E444RequestPerformer.request(() -> holder, "static", "img", "reactions", icon)
                        .perform().readBitmap(); }
                catch (Exception ignored) { return null; }
            }
            @Override protected void onComplete(Bitmap bitmap) {
                tasks.remove(this);
                if (bitmap != null && bitmap.getAllocationByteCount() <= 256 * 1024) ICONS.put(icon, bitmap);
                if (token == generation && isAttachedToWindow() && bitmap != null) applyIcon(view, bitmap);
            }
        };
        tasks.add(task);
        task.execute(IO);
    }

    private void send(String path, String key, String value) {
        if (busy || !interactionEnabled || item == null || !isAttachedToWindow()) return;
        busy = true;
        setInteractionEnabled(interactionEnabled);
        PostItem original = item;
        Object token = generation;
        HttpHolderTask<Void, String[]> task = new HttpHolderTask<Void, String[]>(Chan.get("e444")) {
            @Override protected String[] run(HttpHolder holder) {
                try {
                    E444Model.ApiResponse response = E444RequestPerformer.request(() -> holder, path.split("/"))
                            .param("board", original.getBoardName()).param("num", original.getPostNumber().toString())
                            .param(key, value).configure(HttpRequest::setGetMethod)
                            .performJson(E444Model.ApiResponse.class);
                    if ((response.error != null && response.error.code != 0) || ("vote".equals(key) && response.result != 1))
                        return new String[] {null, response.error != null ? response.error.message : "E444 request failed"};
                    rememberSelection(key, value, original);
                    E444Model.MobilePostResponse refreshed = E444RequestPerformer.request(() -> holder, "api", "mobile", "v2",
                            "post", original.getBoardName(), original.getPostNumber().toString())
                            .param("_", Long.toString(System.currentTimeMillis())).performJson(E444Model.MobilePostResponse.class);
                    return new String[] {refreshed.post != null ? E444PostData.encode(refreshed.post) : null, null};
                } catch (Exception e) { return new String[] {null, getContext().getString(R.string.ech_request_failed)}; }
            }
            @Override protected void onComplete(String[] result) {
                tasks.remove(this);
                if (token != generation || !isAttachedToWindow()) return;
                busy = false;
                if (result[0] != null) {
                    original.setE444Data(result[0]);
                    try { data = E444JsonUtils.fromJson(result[0], E444Model.Post.class); }
                    catch (IOException ignored) {}
                    render();
                    manager.sendPostItemMessage(original, UiManager.Message.POST_INVALIDATE_ALL_VIEWS);
                } else { setInteractionEnabled(interactionEnabled); }
                if (!StringUtils.isEmpty(result[1])) ClickableToast.show(result[1]);
            }
        };
        tasks.add(task);
        task.execute(IO);
    }

    private void cancelTasks() {
        generation = new Object();
        for (HttpHolderTask<?, ?> task : tasks) task.cancel();
        tasks.clear();
    }

    @Override protected void onDetachedFromWindow() {
        cancelTasks();
        busy = false;
        super.onDetachedFromWindow();
    }

    @Override protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        if (data != null) render();
    }
}
