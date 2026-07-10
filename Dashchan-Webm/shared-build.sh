#!/bin/bash

set -e
sources="$1"
libraries="$2"
external="$3"
{ [ -n "$sources" ] && [ -n "$libraries" ] && [ -n "$external" ]; } || {
	echo 'Invalid usage' >&2
	exit 1
}
[ -n "$ANDROID_NDK_HOME" ] || {
	echo 'ANDROID_NDK_HOME is not defined' >&2
	exit 1
}
[ -x "$ANDROID_NDK_HOME/ndk-build" ] || {
	echo 'ndk-build is missing in ANDROID_NDK_HOME' >&2
	exit 1
}
toolchain="$ANDROID_NDK_HOME/toolchains/llvm/prebuilt/linux-x86_64"
native_abis="${NATIVE_ABIS:-arm64-v8a armeabi-v7a x86}"

has_abi() {
	case " $native_abis " in
		*" $1 "*) return 0 ;;
		*) return 1 ;;
	esac
}

cores="$EXTERNAL_CORES"
[ -z "$cores" ] && cores="$(python -c 'import multiprocessing as m; print(m.cpu_count())' || true)"
[ -z "$cores" ] && cores="$(nproc || true)"
makeflags=
[ -n "$cores" ] && makeflags="-j$((cores + 1))"

sources_dav1d="$sources/dav1d"
sources_ffmpeg="$sources/ffmpeg"
sources_yuv="$sources/yuv"
libraries_dav1d="$libraries/dav1d"
libraries_ffmpeg="$libraries/ffmpeg"
libraries_yuv="$libraries/yuv"
external_ffmpeg="$external/ffmpeg"
external_yuv="$external/yuv"

prepare_sources() {
	cd "$libraries"
	rm -rf '.src'
	[ -z "$1" ] || {
		cp -R "$1" '.src'
		cd '.src'
	}
}

sanitize_ffmpeg_configuration() {
	sed -i \
		-e 's|^#define FFMPEG_CONFIGURATION .*$|#define FFMPEG_CONFIGURATION "Dashchan_2 FFmpeg 8 Android build"|' \
		'config.h'
}

ffmpeg_options=(
	'--enable-cross-compile'
	'--target-os=android'
	'--disable-static'
	'--enable-shared'
	'--disable-symver'
	'--disable-debug'
	'--disable-doc'
	'--disable-everything'
	'--disable-programs'
	'--enable-avfilter'
	'--disable-avdevice'
	'--enable-avcodec'
	'--enable-avformat'
	'--enable-swresample'
	'--enable-swscale'
	'--enable-filter=atempo'
	'--enable-demuxer=matroska'
	'--enable-demuxer=mov'
	'--enable-decoder=vp8'
	'--enable-decoder=vp9'
	'--enable-libdav1d'
	'--enable-decoder=libdav1d'
	'--enable-decoder=h264'
	'--enable-decoder=hevc'
	'--enable-decoder=vorbis'
	'--enable-decoder=opus'
	'--enable-decoder=aac'
	'--enable-decoder=mp3'
)

dav1d_build() {
	local ndk_arch="$1"
	local cpu_family="$2"
	local cpu="$3"
	local build="$4"
	local build_cc="$5"
	local target="$6"
	local android="$7"
	shift 7
	local prefix="$toolchain/bin/$build-linux-$target"
	local cc="$toolchain/bin/$build_cc-linux-$target$android-clang"
	prepare_sources "$sources_dav1d"
	cat > 'cross.txt' <<EOF
[binaries]
c = '$cc'
strip = '$toolchain/bin/llvm-strip'
[host_machine]
system = 'android'
cpu_family = '$cpu_family'
cpu = '$cpu'
endian = 'little'
EOF
	meson setup \
		--prefix="$(pwd)/.prefix" \
		--cross-file 'cross.txt' \
		"$@" '.build'
	ninja -C '.build' install
	mkdir -p "$libraries_dav1d/$ndk_arch"
	mv '.prefix/include' "$libraries_dav1d/$ndk_arch/include"
	mv '.prefix/lib/'*.so "$libraries_dav1d/$ndk_arch"
}

rm -rf "$libraries_dav1d"
has_abi 'armeabi-v7a' && dav1d_build 'armeabi-v7a' 'arm' 'armv7hl' 'arm' 'armv7a' 'androideabi' 21
has_abi 'arm64-v8a' && dav1d_build 'arm64-v8a' 'aarch64' 'arm64' 'aarch64' 'aarch64' 'android' 21
has_abi 'x86' && dav1d_build 'x86' 'x86' 'i686' 'i686' 'i686' 'android' 21 -Denable_asm=false

ffmpeg_build() {
	local ndk_arch="$1"
	local arch="$2"
	local build="$3"
	local build_cc="$4"
	local target="$5"
	local android="$6"
	shift 6
	local prefix="$toolchain/bin/$build-linux-$target-"
	local cc="$toolchain/bin/$build_cc-linux-$target$android-clang"
	prepare_sources "$sources_ffmpeg"
	cat > 'libavutil/dashchan_legacy_channel_layout.c' <<'EOF'
#include <stdint.h>

#include "libavutil/channel_layout.h"

int64_t av_get_default_channel_layout(int nb_channels);
int av_get_channel_layout_nb_channels(uint64_t channel_layout);

int64_t av_get_default_channel_layout(int nb_channels) {
	AVChannelLayout channel_layout = {0};
	av_channel_layout_default(&channel_layout, nb_channels);
	if (channel_layout.order != AV_CHANNEL_ORDER_NATIVE) {
		av_channel_layout_uninit(&channel_layout);
		return 0;
	}
	int64_t mask = channel_layout.u.mask;
	av_channel_layout_uninit(&channel_layout);
	return mask;
}

int av_get_channel_layout_nb_channels(uint64_t channel_layout) {
	AVChannelLayout layout = {0};
	if (channel_layout == 0 || av_channel_layout_from_mask(&layout, channel_layout) < 0) {
		return 0;
	}
	int nb_channels = layout.nb_channels;
	av_channel_layout_uninit(&layout);
	return nb_channels;
}
EOF
	printf '\nOBJS += dashchan_legacy_channel_layout.o\n' >> 'libavutil/Makefile'
	cat > 'libavcodec/dashchan_legacy_avcodec.c' <<'EOF'
#include "libavcodec/avcodec.h"

int avcodec_close(AVCodecContext *avctx);

int avcodec_close(AVCodecContext *avctx) {
	(void) avctx;
	return 0;
}
EOF
	printf '\nOBJS += dashchan_legacy_avcodec.o\n' >> 'libavcodec/Makefile'
	mkdir -p '.prefix/bin'
	cat > '.prefix/bin/pkg-config' <<EOF
#!/bin/sh
for arg in "\$@"; do
	case "\$arg" in
		*dav1d*) has_dav1d=1 ;;
	esac
done
case "\$1" in
	--version)
		echo 0.29.2
		exit 0
		;;
	--exists|--print-errors)
		[ "\$has_dav1d" = 1 ] && exit 0
		;;
	--modversion)
		[ "\$has_dav1d" = 1 ] && echo "$DAV1D_VERSION" && exit 0
		;;
	--atleast-version=*)
		[ "\$has_dav1d" = 1 ] && exit 0
		;;
	--cflags)
		[ "\$has_dav1d" = 1 ] &&
		echo $(printf '%q' "-I$libraries_dav1d/$ndk_arch/include") && exit 0
		;;
	--libs)
		[ "\$has_dav1d" = 1 ] &&
		echo $(printf '%q' "-L$libraries_dav1d/$ndk_arch") -ldav1d && exit 0
		;;
esac
exit 1
EOF
	chmod a+x '.prefix/bin/pkg-config'
	./configure \
		--prefix='.prefix' \
		--arch="$arch" --cross-prefix="$prefix" \
		--sysroot="$toolchain/sysroot" \
		--cc="$cc" --ld="$cc" \
		--ar="$toolchain/bin/llvm-ar" \
		--nm="$toolchain/bin/llvm-nm" \
		--ranlib="$toolchain/bin/llvm-ranlib" \
		--strip="$toolchain/bin/llvm-strip" \
		--pkg-config='.prefix/bin/pkg-config' \
		"$@" "${ffmpeg_options[@]}"
	sanitize_ffmpeg_configuration
	make $makeflags
	make install
	mkdir -p "$external_ffmpeg/include"
	mv '.prefix/include' "$external_ffmpeg/include/$ndk_arch"
	mkdir -p "$libraries_ffmpeg/$ndk_arch"
	mv '.prefix/lib/'*.so "$libraries_ffmpeg/$ndk_arch"
}

rm -rf "$libraries_ffmpeg" "$external_ffmpeg"
mkdir -p "$libraries_ffmpeg" "$external_ffmpeg"
has_abi 'armeabi-v7a' && ffmpeg_build 'armeabi-v7a' 'arm' 'arm' 'armv7a' 'androideabi' 21 --cpu=armv7-a
has_abi 'arm64-v8a' && ffmpeg_build 'arm64-v8a' 'arm64' 'aarch64' 'aarch64' 'android' 21
has_abi 'x86' && ffmpeg_build 'x86' 'x86' 'i686' 'i686' 'android' 21 --enable-pic --disable-asm

prepare_sources "$sources_yuv"
"$ANDROID_NDK_HOME/ndk-build" \
	APP_PLATFORM=android-21 \
	APP_BUILD_SCRIPT=Android.mk \
	NDK_PROJECT_PATH=. \
	APP_ABI="$native_abis" \
	LIBYUV_DISABLE_JPEG='"yes"' $makeflags
rm -rf "$libraries_yuv" "$external_yuv"
mkdir -p "$libraries_yuv" "$external_yuv"
cp -R libs/* "$libraries_yuv"
cp -R include "$external_yuv"

make_symbols() {
	pushd "$1"
	for so in */*.so; do
		local name="${so%.so}.c"
		local out="$2/symbols/$name"
		local dir="${out%/*}"
		mkdir -p "$dir"
		build=
		target=
		case "${dir##*/}" in
			armeabi-v7a)
				build=arm
				target=androideabi
				;;
			arm64-v8a)
				build=aarch64
				target=android
				;;
			x86)
				build=i686
				target=android
				;;
		esac
		echo "GEN $name"
		echo "/* generated from ${so##*/} */" > "$out"
		"$toolchain/bin/llvm-readelf" -Ws "$so" |
		grep -v ' UND ' | grep -v ' WEAK ' | grep ' \(FUNC\|OBJECT\) ' |
		sed -e 's/@.*//' -e 's/.* \(FUNC\|OBJECT\).* \(.*\)/\1 \2/' \
		-e 's/^FUNC \(.*\)$/void \1() {};/' -e 's/^OBJECT \(.*\)$/int \1;/' |
		sort -u >> "$out"
	done
	popd
}

prepare_sources
make_symbols "$libraries_ffmpeg" "$external_ffmpeg"
make_symbols "$libraries_yuv" "$external_yuv"
