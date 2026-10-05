# Vendored GIFLIB decoder

Source: GIFLIB 6.1.3, official release archive
`https://sourceforge.net/projects/giflib/files/giflib-6.x/giflib-6.1.3.tar.gz/`

Archive SHA-256:
`b65b66b99f0424b93525f987386f22fc5efb9da2bfc92ad4a532249aaffbab0e`

The following files are copied from the root of that archive:
`dgif_lib.c`, `gifalloc.c`, `gif_err.c`, `gif_hash.h`, `gif_lib.h`,
`gif_lib_private.h`, and `openbsd-reallocarray.c`. They are byte-for-byte
unchanged except that one whitespace-only line in `gifalloc.c` was normalized
to satisfy `git diff --check`, and `DGifSlurp` casts both validated image
dimensions to `size_t` before multiplying them. The existing positive-size
and `INT_MAX` overflow guards are unchanged. This avoids a CodeQL integer
multiplication warning without increasing the accepted image size.
The upstream `COPYING` license is included here.
Only the decoder sources needed by the app are
compiled by `Android.mk`; upstream encoder and command-line utilities are not
part of this build. App-specific JNI integration remains in the parent `gif`
directory.
