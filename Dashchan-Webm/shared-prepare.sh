#!/bin/bash

set -e
sources="$1"
[ -n "$sources" ] || {
	echo 'Invalid usage' >&2
	exit 1
}
[ -n "$DAV1D_VERSION" ] || {
	echo 'DAV1D_VERSION is not defined' >&2
	exit 1
}
[ -n "$FFMPEG_VERSION" ] || {
	echo 'FFMPEG_VERSION is not defined' >&2
	exit 1
}
[ -n "$YUV_VERSION" ] || {
	echo 'YUV_VERSION is not defined' >&2
	exit 1
}

# Optional checksum hooks. Keep empty until release checksums are pinned.
DAV1D_SHA256="${DAV1D_SHA256:-}"
FFMPEG_SHA256="${FFMPEG_SHA256:-}"
YUV_SHA256="${YUV_SHA256:-}"

download_and_extract() {
	local url="$1"
	local checksum="$2"
	local target="$3"
	shift 3
	local archive
	archive="$(mktemp)"
	rm -rf "$target"
	mkdir -p "$target"
	curl -L "$url" -o "$archive" || {
		rm -f "$archive"
		rm -rf "$target"
		exit 1
	}
	if [ -n "$checksum" ]; then
		echo "$checksum  $archive" | sha256sum -c - || {
			rm -f "$archive"
			rm -rf "$target"
			exit 1
		}
	fi
	tar -C "$target" "$@" -f "$archive" || {
		rm -f "$archive"
		rm -rf "$target"
		exit 1
	}
	rm -f "$archive"
}

prepare_source() {
	local name="$1"
	local version="$2"
	local url="$3"
	local checksum="$4"
	local target="$5"
	local marker="$target/.dashchan-version"
	local expected="$name:$version"
	shift 5
	if [ -f "$marker" ] && [ "$(cat "$marker")" != "$expected" ]; then
		rm -rf "$target"
	fi
	if [ ! -f "$marker" ]; then
		download_and_extract "$url" "$checksum" "$target" "$@"
		printf '%s\n' "$expected" > "$marker"
	fi
}

sources_dav1d="$sources/dav1d"
sources_ffmpeg="$sources/ffmpeg"
sources_yuv="$sources/yuv"

prepare_source dav1d "$DAV1D_VERSION" \
	"https://downloads.videolan.org/videolan/dav1d/$DAV1D_VERSION/dav1d-$DAV1D_VERSION.tar.xz" \
	"$DAV1D_SHA256" "$sources_dav1d" -xJ --touch --strip-components=1

prepare_source ffmpeg "$FFMPEG_VERSION" \
	"https://ffmpeg.org/releases/ffmpeg-$FFMPEG_VERSION.tar.bz2" \
	"$FFMPEG_SHA256" "$sources_ffmpeg" -xj --touch --strip-components=1

prepare_source yuv "$YUV_VERSION" \
	"https://chromium.googlesource.com/libyuv/libyuv/+archive/$YUV_VERSION.tar.gz" \
	"$YUV_SHA256" "$sources_yuv" -xz --touch
