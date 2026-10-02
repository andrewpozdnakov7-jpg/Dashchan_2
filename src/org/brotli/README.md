# Brotli Java decoder

The Java sources in `dec/` are a copy of the pure-Java decoder
from Google's Brotli release `v1.2.0` (commit
`028fb5a23661f123017c060daa546b55cf4bde29`).
The only source edit removes one trailing space from `Transform.java`.

Upstream: https://github.com/google/brotli/tree/v1.2.0/java/org/brotli/dec
License: MIT; see `LICENSE` in this directory. A copy is also packaged in
`assets/licenses/brotli-MIT.txt` with the application.

Only the 12 production decoder Java files are included. Upstream's tests,
build files, JNI wrapper, and Kotlin decoder are not packaged. The app uses
`org.brotli.dec.BrotliInputStream` for HTTP `Content-Encoding: br` responses.

The decoder loads `DictionaryData` reflectively. Its keep rules from upstream
are retained in the app's `proguard-rules.pro`; removing them breaks
dictionary-encoded responses in minified APKs.
