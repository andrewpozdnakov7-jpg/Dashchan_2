#!/usr/bin/env python3
"""Fails when the Arhivach Cloudflare session bridge disappears from the sources.

The bridge is a user-visible feature: the person passes the site verification in the
visible WebView and the app reuses that session for native requests. It is intentionally
kept in a small number of places, so an accidental removal is easy to detect here.
"""

from pathlib import Path
import sys

ROOT = Path(__file__).resolve().parents[1]

REQUIRED_FILES = (
    "src/com/mishiranu/dashchan/chan/arhivach/ArhivachBrowserSession.java",
)

CLASS_MARKERS = (
    "TTL_MS",
    "resetAccess",
    "clearStoredClearance",
    "cookieStatus",
    "userAgentStatus",
    "storeCookies",
)

MARKERS = (
    ("src/com/mishiranu/dashchan/chan/arhivach/ArhivachChanPerformer.java",
     ('ArhivachBrowserSession.log("native_request', 'clearStoredClearance("http_403")')),
    ("src/com/mishiranu/dashchan/ui/BrowserFragment.java",
     ("ArhivachBrowserSession.storeCookies", "ArhivachBrowserSession.isSite")),
    ("src/com/mishiranu/dashchan/ui/navigator/PageFragment.java",
     ("menu_arhivach_open_browser", "menu_arhivach_reset_access", "retryArhivachPage")),
    ("src/com/mishiranu/dashchan/util/WebViewUtils.java",
     ("ArhivachBrowserSession.clearStoredClearance",)),
)


def main() -> int:
    problems = []

    for relative in REQUIRED_FILES:
        if not (ROOT / relative).is_file():
            problems.append(f"missing file: {relative}")

    session = ROOT / "src/com/mishiranu/dashchan/chan/arhivach/ArhivachBrowserSession.java"
    if session.is_file():
        text = session.read_text(encoding="utf-8", errors="replace")
        for marker in CLASS_MARKERS:
            if marker not in text:
                problems.append(f"ArhivachBrowserSession: missing '{marker}'")

    for relative, markers in MARKERS:
        path = ROOT / relative
        if not path.is_file():
            problems.append(f"missing file: {relative}")
            continue
        text = path.read_text(encoding="utf-8", errors="replace")
        for marker in markers:
            if marker not in text:
                problems.append(f"{relative}: missing '{marker}'")

    if problems:
        print("Arhivach bridge is incomplete:")
        for problem in problems:
            print("  - " + problem)
        return 1

    print("OK: Arhivach bridge is present.")
    return 0


if __name__ == "__main__":
    sys.exit(main())
