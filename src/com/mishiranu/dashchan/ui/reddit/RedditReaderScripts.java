package com.mishiranu.dashchan.ui.reddit;

import android.content.res.AssetManager;
import android.graphics.Color;
import com.mishiranu.dashchan.util.IOUtils;
import com.mishiranu.dashchan.widget.ThemeEngine;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import org.json.JSONException;
import org.json.JSONObject;

/** Builds page presentation only. The Fragment owns WebView, navigation, preferences and injection order. */
public final class RedditReaderScripts {
    // Packaged text is immutable for this process. Cache strings only, never an Activity or AssetManager.
    private static final Map<String, String> ASSET_TEXT = new HashMap<>();

    private RedditReaderScripts() {}

    public static String appPromo(AssetManager assets) {
        return build(assets, "app_promo", null, false, "");
    }

    public static String board(AssetManager assets, ThemeEngine.Theme theme) {
        return build(assets, "board", theme, false, "");
    }

    public static String reader(AssetManager assets, ThemeEngine.Theme theme) {
        return build(assets, "reader", theme, false, "");
    }

    public static String hybrid(AssetManager assets, ThemeEngine.Theme theme, boolean russian, String logPrefix) {
        return build(assets, "hybrid", theme, russian, logPrefix);
    }

    private static String build(AssetManager assets, String name, ThemeEngine.Theme theme,
            boolean russian, String logPrefix) {
        String css = compactCss(readAsset(assets, name + ".css"));
        if (theme != null) css = renderTheme(css, theme);
        try {
            JSONObject config = new JSONObject().put("css", css);
            if ("hybrid".equals(name)) {
                config.put("russian", russian);
                config.put("logPrefix", logPrefix);
            }
            return configure(readAsset(assets, name + ".js"), config);
        } catch (JSONException e) {
            throw new IllegalStateException("Invalid Reddit presentation config", e);
        }
    }

    /** JSON is passed as a function argument, never interpolated into a quoted JS/CSS fragment. */
    static String configure(String factory, JSONObject config) {
        String json = config.toString().replace("\u2028", "\\u2028").replace("\u2029", "\\u2029");
        return factory + "(" + json + ");";
    }

    private static synchronized String readAsset(AssetManager assets, String name) {
        String cached = ASSET_TEXT.get(name);
        if (cached != null) return cached;
        try (InputStream input = assets.open("reddit/" + name);
                ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            IOUtils.copyStream(input, output);
            String text = new String(output.toByteArray(), StandardCharsets.UTF_8);
            ASSET_TEXT.put(name, text);
            return text;
        } catch (IOException e) {
            throw new IllegalStateException("Missing Reddit presentation asset: " + name, e);
        }
    }

    // The CSS files use only these controls for layout. Do not trim selector/value spaces.
    static String compactCss(String css) {
        return css.replace("\r", "").replace("\n", "").replace("\t", "");
    }

    private static String renderTheme(String css, ThemeEngine.Theme theme) {
        int scorePositive = theme.base == ThemeEngine.Theme.Base.DARK ? 0x81c784 : 0x2e7d32;
        int scoreNegative = theme.base == ThemeEngine.Theme.Base.DARK ? 0xef9a9a : 0xc62828;
        css = css.replace("@@card@@", color(theme.card));
        css = css.replace("@@window@@", color(theme.window));
        css = css.replace("@@post@@", color(theme.post));
        css = css.replace("@@meta@@", color(theme.meta));
        css = css.replace("@@link@@", color(theme.link));
        css = css.replace("@@colorScheme@@", (theme.base == ThemeEngine.Theme.Base.DARK ? "dark" : "light"));
        css = css.replace("@@accent@@", color(theme.accent));
        css = css.replace("@@scorePositive@@", color(scorePositive));
        css = css.replace("@@scoreNegative@@", color(scoreNegative));
        css = css.replace("@@meta_45@@", colorWithAlpha(theme.meta, 0x45));
        css = css.replace("@@accent_78@@", colorWithAlpha(theme.accent, 0x78));
        css = css.replace("@@accent_6b@@", colorWithAlpha(theme.accent, 0x6b));
        css = css.replace("@@window_f2@@", colorWithAlpha(theme.window, 0xf2));
        css = css.replace("@@accent_80@@", colorWithAlpha(theme.accent, 0x80));
        css = css.replace("@@black_55@@", colorWithAlpha(Color.BLACK, 0x55));
        css = css.replace("@@link_16@@", colorWithAlpha(theme.link, 0x16));
        css = css.replace("@@meta_70@@", colorWithAlpha(theme.meta, 0x70));
        css = css.replace("@@accent_92@@", colorWithAlpha(theme.accent, 0x92));
        css = css.replace("@@black_72@@", colorWithAlpha(Color.BLACK, 0x72));
        css = css.replace("@@meta_35@@", colorWithAlpha(theme.meta, 0x35));
        if (css.contains("@@")) throw new IllegalStateException("Unresolved Reddit theme token");
        return css;
    }

    private static String color(int color) {
        return String.format(Locale.US, "#%06x", color & 0x00ffffff);
    }

    private static String colorWithAlpha(int color, int alpha) {
        return String.format(Locale.US, "rgba(%d,%d,%d,%.3f)", Color.red(color), Color.green(color),
                Color.blue(color), alpha / 255f);
    }
}
