package com.mishiranu.dashchan.chan.e444;

import java.util.Collections;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

/** Small persisted UI payload; survives thread-cache restore without a process-global widget map. */
public final class E444PostData {
    private E444PostData() {}

    public static String encode(E444Model.Post post) {
        try {
            JSONObject data = new JSONObject();
            data.put("num", post.num);
            data.put("board", post.board);
            data.put("answers", new JSONArray(post.answers != null ? post.answers : Collections.emptyList()));
            data.put("poll_results_exact", new JSONArray(post.poll_results_exact != null
                    ? post.poll_results_exact : Collections.emptyList()));
            JSONArray reactions = new JSONArray();
            if (post.reactions != null) for (E444Model.Reaction reaction : post.reactions) {
                JSONObject value = new JSONObject();
                value.put("icon", reaction.icon);
                value.put("count", Math.max(0, reaction.count));
                reactions.put(value);
            }
            data.put("reactions", reactions);
            JSONArray menu = new JSONArray();
            if (post.menu != null) for (E444Model.MenuSection section : post.menu) {
                JSONObject value = new JSONObject();
                value.put("sectionName", section.sectionName);
                JSONArray links = new JSONArray();
                if (section.links != null) for (E444Model.MenuLink link : section.links) {
                    JSONObject item = new JSONObject();
                    item.put("label", link.label);
                    item.put("url", link.url);
                    links.put(item);
                }
                value.put("links", links);
                menu.put(value);
            }
            data.put("menu", menu);
            return data.toString();
        } catch (JSONException e) { throw new IllegalArgumentException("Invalid E444 post metadata", e); }
    }
}
