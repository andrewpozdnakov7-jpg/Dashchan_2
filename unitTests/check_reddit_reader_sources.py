#!/usr/bin/env python3
"""Source/JS syntax checks only. Does not execute WebView, compile Java or run Gradle."""
import re
import shutil
import subprocess
import sys
from pathlib import Path
from check_photo_editor_sources import lexical_balance

ROOT = Path(__file__).resolve().parents[1]
ASSETS = ROOT / "assets/reddit"

def main():
    fragment_path = ROOT / "src/com/mishiranu/dashchan/ui/RedditWebReaderFragment.java"
    builder_path = ROOT / "src/com/mishiranu/dashchan/ui/reddit/RedditReaderScripts.java"
    test_path = ROOT / "instrumentationTests/src/com/mishiranu/dashchan/ui/reddit/RedditReaderScriptsTest.java"
    for path in (fragment_path, builder_path, test_path): lexical_balance(path)
    fragment = fragment_path.read_text(encoding="utf-8")
    builder = builder_path.read_text(encoding="utf-8")
    templates = ""
    scripts = []
    for name in ("app_promo", "board", "reader", "hybrid"):
        js = (ASSETS / (name + ".js")).read_text(encoding="utf-8")
        css = (ASSETS / (name + ".css")).read_text(encoding="utf-8")
        assert "function (nativeReaderConfig)" in js and js.rstrip().endswith("})"), name
        assert "nativeReaderConfig.css" in js, name
        assert css.strip() and css.count("{") == css.count("}"), name
        templates += css
        scripts.append(ASSETS / (name + ".js"))
    required = set(re.findall(r"@@(\w+)@@", templates))
    supplied = set(re.findall(r'css\.replace\("@@(\w+)@@"', builder))
    assert required == supplied, (required - supplied, supplied - required)
    assert 'assets.open("reddit/" + name)' in builder
    assert 'StandardCharsets.UTF_8' in builder and 'config.toString()' in builder
    assert 'return factory + "(" + json + ");"' in builder
    assert '.replace("\\u2028", "\\\\u2028")' in builder
    assert '.replace("\\u2029", "\\\\u2029")' in builder
    assert 'assets.srcDirs = [\'assets\', \'metadata\']' in (ROOT / "build.gradle").read_text(encoding="utf-8")
    for guard in ('boardStyleScript = !authorizationMode && Preferences.isRedditBoardStyleEnabled()',
                  'readerStyleScript = !authorizationMode && Preferences.isRedditWebReaderStyleEnabled()',
                  'hybridReaderScript = readerStyleScript != null ? buildHybridReaderScript() : null',
                  'return webView == view && pageLoadGeneration == generation', 'handler.cancel()', 'webView.destroy()'):
        assert guard in fragment, guard
    presentation = fragment[fragment.index('private void applyPagePresentation('):fragment.index('private void applyDiscussionPresentation(')]
    assert presentation.index('if (authorizationMode)') < presentation.index('evaluateJavascript(')
    assert presentation.index('buildAppPromoSuppressionScript()') < presentation.index('evaluateJavascript(boardStyleScript')
    discussion = fragment[fragment.index('private void applyDiscussionPresentation('):fragment.index('private class RedditWebViewClient')]
    assert discussion.index('evaluateJavascript(readerStyleScript') < discussion.index('evaluateJavascript(hybridReaderScript')
    assert 'String css =' not in fragment and 'String escapedCss =' not in fragment
    assert 'version: VERSION' in (ASSETS / 'hybrid.js').read_text(encoding='utf-8')
    node = shutil.which("node")
    if node:
        for script in scripts:
            subprocess.run([node, "--check", str(script)], check=True, capture_output=True, text=True)
        print("PASS: four JavaScript assets parse with node --check")
    else:
        print("NOT RUN: JS syntax check; node is unavailable")
    print(f"PASS: four JS/CSS pairs, {len(required)} native theme tokens, UTF-8 and JSON config boundary")
    print("PASS: authorization/generation/SSL/destroy guards and unchanged injection order in source")
    print("NOT RUN: Java compilation, Android tests, WebView/runtime fixtures")

if __name__ == "__main__":
    try:
        main()
    except (AssertionError, OSError, subprocess.CalledProcessError, ValueError) as error:
        print(f"FAIL: {error}", file=sys.stderr)
        sys.exit(1)
