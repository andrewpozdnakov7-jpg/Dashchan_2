package com.mishiranu.dashchan.ui.reddit;

import android.content.Context;
import android.os.SystemClock;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import com.mishiranu.dashchan.widget.ThemeEngine;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.json.JSONObject;
import org.json.JSONTokener;
import org.junit.After;
import org.junit.Test;
import org.junit.runner.RunWith;
import static org.junit.Assert.*;

/** Offline DOM fixtures with real WebView. Prepared, not executed in source-only mode. */
@RunWith(AndroidJUnit4.class)
public class RedditReaderScriptsTest {
    private static final String THREAD = "https://www.reddit.com/r/test/comments/example/title/";
    private static final String DISCUSSION = "<shreddit-post id='post' author='poster' score='12' comment-count='2' "
            + "subreddit-prefixed-name='r/test'><h1 slot='title'>Fixture title</h1>"
            + "<div slot='text-body'><p>Post body</p></div></shreddit-post><section id='comment-tree'>"
            + "<shreddit-comment thingid='root' author='first' score='5' depth='0' permalink='/r/test/comments/example/root/'>"
            + "<div slot='comment'>First comment</div></shreddit-comment>"
            + "<shreddit-comment thingid='child' author='second' score='-2' depth='1' permalink='/r/test/comments/example/child/'>"
            + "<div slot='comment'>Second comment</div></shreddit-comment></section>";
    private WebView webView;

    private static Context context() {
        return InstrumentationRegistry.getInstrumentation().getTargetContext();
    }

    private static ThemeEngine.Theme theme(boolean dark) {
        return new ThemeEngine.Theme(dark ? ThemeEngine.Theme.Base.DARK : ThemeEngine.Theme.Base.LIGHT,
                "fixture", false, false, "{}", 0xff102030, 0xff223344, 0xffabcdef, 0xff203040,
                0xff203040, 0xffdfcfbf, 0xff918273, 0xff102030, 0xffdecabc, 0xffabcdef,
                0xffabcdef, 0xffabcdef, 1f, 0xffdfcfbf, .5f);
    }

    private void load(String url, String body) throws Exception {
        CountDownLatch loaded = new CountDownLatch(1);
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
            webView = new WebView(context());
            webView.getSettings().setJavaScriptEnabled(true);
            webView.getSettings().setBlockNetworkLoads(true);
            webView.setWebViewClient(new WebViewClient() {
                @Override public void onPageFinished(WebView view, String page) { loaded.countDown(); }
            });
            webView.loadDataWithBaseURL(url, "<!doctype html><html><head></head><body>" + body
                    + "</body></html>", "text/html", "UTF-8", null);
        });
        assertTrue("Fixture did not load", loaded.await(10, TimeUnit.SECONDS));
    }

    private String evaluate(String script) throws Exception {
        CountDownLatch done = new CountDownLatch(1);
        AtomicReference<String> value = new AtomicReference<>();
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> webView.evaluateJavascript(script, result -> {
            value.set(result);
            done.countDown();
        }));
        assertTrue("JavaScript callback timed out", done.await(10, TimeUnit.SECONDS));
        return value.get();
    }

    private void awaitTrue(String expression) throws Exception {
        long deadline = SystemClock.uptimeMillis() + 5000;
        do {
            if ("true".equals(evaluate(expression))) return;
            SystemClock.sleep(25);
        } while (SystemClock.uptimeMillis() < deadline);
        fail("Fixture condition did not become true: " + expression);
    }

    @After public void release() {
        if (webView != null) {
            InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> webView.destroy());
            webView = null;
        }
    }

    @Test public void jsonConfigurationEscapesTextWithoutExecutingIt() throws Exception {
        load(THREAD, "");
        String text = "quote \" single ' slash \\ newline\nРусский\u2028\u2029</script>";
        JSONObject config = new JSONObject().put("css", text).put("russian", true)
                .put("logPrefix", "\";window.__injected=true;//");
        evaluate("window.__injected=false;");
        String value = evaluate(RedditReaderScripts.configure(
                "(function(nativeReaderConfig){return JSON.stringify(nativeReaderConfig)})", config));
        JSONObject restored = new JSONObject((String) new JSONTokener(value).nextValue());
        assertEquals(text, restored.getString("css"));
        assertEquals(config.getString("logPrefix"), restored.getString("logPrefix"));
        assertTrue(restored.getBoolean("russian"));
        assertEquals("false", evaluate("window.__injected"));
    }

    @Test public void appPromoSuppressesKnownAndDynamicShadowPromptsWithoutHidingDialogs() throws Exception {
        load(THREAD, "<shreddit-app-selector id='promo'>Install Reddit app</shreddit-app-selector>"
                + "<div role='dialog' id='legitimate'>Confirm action</div><shreddit-post id='post'>Post</shreddit-post>");
        String script = RedditReaderScripts.appPromo(context().getAssets());
        evaluate(script);
        assertEquals("true", evaluate("getComputedStyle(document.getElementById('promo')).display==='none'"
                + "&&getComputedStyle(document.getElementById('legitimate')).display!=='none'"
                + "&&getComputedStyle(document.getElementById('post')).display!=='none'"));
        evaluate("window.fixturePromo=window.__slooopRedditPromoGuard;var host=document.createElement('div');"
                + "host.id='shadow-fixture';host.attachShadow({mode:'open'}).innerHTML="
                + "'<shreddit-app-selector>Install Reddit app</shreddit-app-selector>';document.body.appendChild(host);");
        awaitTrue("getComputedStyle(document.getElementById('shadow-fixture').shadowRoot.querySelector('shreddit-app-selector')).display==='none'");
        evaluate(script);
        assertEquals("true", evaluate("window.fixturePromo===window.__slooopRedditPromoGuard&&document.head.querySelectorAll('style').length===1"));
    }

    @Test public void boardDecoratesSignedScoresAndCleansUpOnReinjectionAndNavigation() throws Exception {
        load("https://www.reddit.com/r/test/", "<shreddit-post id='positive' score='12'></shreddit-post>"
                + "<shreddit-post id='negative' score='-3'></shreddit-post>");
        evaluate(RedditReaderScripts.board(context().getAssets(), theme(false)));
        assertEquals("true", evaluate("document.documentElement.classList.contains('slooop-reddit-board')"));
        assertEquals("\"positive\"", evaluate("document.getElementById('positive').getAttribute('data-slooop-score-sign')"));
        assertEquals("\"negative\"", evaluate("document.getElementById('negative').getAttribute('data-slooop-score-sign')"));
        assertEquals("true", evaluate("document.getElementById('slooop-reddit-board-style').textContent.indexOf('#2e7d32')>=0"));
        evaluate("window.fixtureStyle=window.__slooopRedditBoardStyle;window.fixtureDisconnected=false;"
                + "var disconnect=window.fixtureStyle.observer.disconnect.bind(window.fixtureStyle.observer);window.fixtureStyle.observer.disconnect=function(){window.fixtureDisconnected=true;disconnect();};");
        evaluate(RedditReaderScripts.board(context().getAssets(), theme(true)));
        assertEquals("true", evaluate("window.fixtureDisconnected&&window.fixtureStyle!==window.__slooopRedditBoardStyle"));
        assertEquals("true", evaluate("document.querySelectorAll('#slooop-reddit-board-style').length===1"
                + "&&document.getElementById('slooop-reddit-board-style').textContent.indexOf('#81c784')>=0"));
        evaluate("history.pushState({},'', '/r/test/comments/example/title/');dispatchEvent(new PopStateEvent('popstate'));");
        awaitTrue("!document.documentElement.classList.contains('slooop-reddit-board')");
    }

    @Test public void readerStyleTracksDiscussionPathsAndKeepsThemeValues() throws Exception {
        load(THREAD, DISCUSSION);
        evaluate(RedditReaderScripts.reader(context().getAssets(), theme(false)));
        assertEquals("true", evaluate("document.documentElement.classList.contains('slooop-reddit-reader')"));
        assertEquals("true", evaluate("document.getElementById('slooop-reddit-reader-style').textContent.indexOf('#dfcfbf')>=0"
                + "&&document.getElementById('slooop-reddit-reader-style').textContent.indexOf('@@')<0"));
        evaluate("history.pushState({},'', '/r/test/');dispatchEvent(new PopStateEvent('popstate'));");
        awaitTrue("!document.documentElement.classList.contains('slooop-reddit-reader')");
    }

    @Test public void hybridKeepsCommentOrderActionsTranslationAndOriginalSwitching() throws Exception {
        load(THREAD, DISCUSSION);
        String script = RedditReaderScripts.hybrid(context().getAssets(), theme(false), false, "SLOOOP_REDDIT ");
        evaluate(script);
        assertEquals("true", evaluate("document.getElementById('slooop-hybrid-reader-host').shadowRoot"
                + ".querySelector('.original').textContent==='Original'"));
        evaluate("document.getElementById('slooop-hybrid-reader-host').shadowRoot.querySelector('.reader-return').click();");
        awaitTrue("document.documentElement.classList.contains('slooop-hybrid-reader-active')");
        assertEquals("true", evaluate("(function(){var cards=document.getElementById('slooop-hybrid-reader-host').shadowRoot"
                + ".querySelectorAll('.comment');return cards.length===2&&cards[0].dataset.commentKey==='root'"
                + "&&cards[1].dataset.commentKey==='child';})()"));
        evaluate("(function(){var s=document.getElementById('slooop-hybrid-reader-host').shadowRoot;"
                + "s.querySelector('.comment-actions button').click();s.querySelector('[data-command=collapse]').click();})()");
        assertEquals("true", evaluate("document.getElementById('slooop-hybrid-reader-host').shadowRoot.querySelectorAll('.comment')[1].hidden"));
        evaluate("window.__slooopRedditTranslationEnabled=true;document.querySelector('[thingid=root] [slot=comment]').textContent='Translated comment';");
        awaitTrue("document.getElementById('slooop-hybrid-reader-host').shadowRoot.querySelector('.comment-body').textContent==='Translated comment'");
        evaluate("document.getElementById('slooop-hybrid-reader-host').shadowRoot.querySelector('.original').click();"
                + "window.fixtureHybrid=window.__slooopRedditHybridReader;");
        assertEquals("false", evaluate("document.documentElement.classList.contains('slooop-hybrid-reader-active')"));
        evaluate(script);
        assertEquals("true", evaluate("window.fixtureHybrid===window.__slooopRedditHybridReader"
                + "&&document.querySelectorAll('#slooop-hybrid-reader-host').length===1"));
    }

    @Test public void russianHybridAndDynamicMoreRepliesUseTheExistingDomControls() throws Exception {
        load(THREAD, DISCUSSION.replace("</section>", "<faceplate-partial slot='children'>"
                + "<button id='more'>Load replies</button></faceplate-partial></section>"));
        evaluate("document.getElementById('more').onclick=function(){var c=document.createElement('shreddit-comment');"
                + "c.setAttribute('thingid','added');c.setAttribute('author','third');c.setAttribute('depth','0');"
                + "var body=document.createElement('div');body.setAttribute('slot','comment');body.textContent='New comment';"
                + "c.appendChild(body);document.getElementById('comment-tree').appendChild(c);document.getElementById('more').parentElement.remove();};");
        evaluate(RedditReaderScripts.hybrid(context().getAssets(), theme(true), true, "SLOOOP_REDDIT "));
        assertEquals("true", evaluate("document.getElementById('slooop-hybrid-reader-host').shadowRoot.querySelector('.original').textContent==='Оригинал'"));
        evaluate("document.getElementById('slooop-hybrid-reader-host').shadowRoot.querySelector('.reader-return').click();");
        awaitTrue("document.getElementById('slooop-hybrid-reader-host').shadowRoot.querySelectorAll('.comment').length>=2");
        evaluate("(function(){var more=document.getElementById('slooop-hybrid-reader-host').shadowRoot.querySelector('.more');"
                + "if(more&&!more.disabled)more.click();})()");
        awaitTrue("document.getElementById('slooop-hybrid-reader-host').shadowRoot.querySelectorAll('.comment').length===3");
    }
}
