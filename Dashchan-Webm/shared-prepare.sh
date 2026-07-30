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

# Pinned checksums for stable release archives. libyuv is fetched and verified
# separately by its full Git commit ID because Gitiles archives are not byte-stable.
DAV1D_SHA256="732010aa5ef461fa93355ed2c6c5fedb48ddc4b74e697eaabe8907eaeb943011"
FFMPEG_SHA256="b4925bd4411e654ad3884bc8da1860b0d860bd64a95a17220de48cfcd5f0a859"

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

prepare_git_source() {
	local name="$1"
	local version="$2"
	local url="$3"
	local target="$4"
	local marker="$target/.dashchan-version"
	local expected="$name:$version"
	local repository
	if [ -f "$marker" ] && [ "$(cat "$marker")" != "$expected" ]; then
		rm -rf "$target"
	fi
	if [ ! -f "$marker" ]; then
		repository="$(mktemp -d)"
		rm -rf "$target"
		mkdir -p "$target"
		git -C "$repository" init -q
		git -C "$repository" remote add origin "$url"
		git -C "$repository" fetch -q --depth=1 origin "$version"
		[ "$(git -C "$repository" rev-parse FETCH_HEAD)" = "$version" ] || {
			rm -rf "$repository" "$target"
			exit 1
		}
		git -C "$repository" archive FETCH_HEAD | tar -C "$target" -x
		rm -rf "$repository"
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

prepare_git_source yuv "$YUV_VERSION" \
	"https://chromium.googlesource.com/libyuv/libyuv" "$sources_yuv"
