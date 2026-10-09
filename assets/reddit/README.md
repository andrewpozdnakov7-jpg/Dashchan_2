# Reddit presentation assets

`RedditReaderScripts` reads these UTF-8 files from the existing shared assets source set.
Each JS file is a parenthesized function expression, without a call or trailing semicolon.
The builder calls it with a JSON object; do not paste theme values or translated strings into JS quotes.
Only packaged text is cached; theme colors and configuration are rebuilt for every call.

| Pair | Config | Responsibility |
| --- | --- | --- |
| `app_promo.js` / `.css` | `css` | App-promo suppression, header/banner suppression, shadow-root scanning |
| `board.js` / `.css` | `css` | Board styling and signed score decoration |
| `reader.js` / `.css` | `css` | Discussion styling |
| `hybrid.js` / `.css` | `css`, `russian`, `logPrefix` | Hybrid discussion, actions, loading more replies, original/reader switching |

The CSS templates use explicit `@@token@@` names expanded by the native builder.
CR, LF and TAB characters format the CSS files and are removed at assembly time;
ordinary spaces inside selectors and values must be preserved. Do not put literal control
characters in CSS strings. The assembled CSS matches the former inline styles.

Keep DOM selectors, URL guards, window markers, listener/observer cleanup and hybrid version
semantics stable during structural changes. `RedditWebReaderFragment` still owns preferences,
authorization mode, WebView lifecycle, translation, navigation and injection order.

Source-only check: `python3 unitTests/check_reddit_reader_sources.py`.
Real WebView fixtures are in `instrumentationTests/src/com/mishiranu/dashchan/ui/reddit/RedditReaderScriptsTest.java`.
They require separate authorization and external Android execution under the repository modes.
