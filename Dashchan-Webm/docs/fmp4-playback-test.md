# Fragmented MP4 playback test notes

Dashchan-Webm supplies the FFmpeg native libraries used by the Dashchan built-in
video player. When playback is slow for a valid MP4 file, first check whether the
file is a fragmented MP4/DASH-like container and whether a library-only update is
enough before changing Dashchan core player timing code.

## Modern SDK baseline

The project is migrated to Android Gradle Plugin 9.2.x, compile SDK 36, target
SDK 36, SDK Build Tools 36.0.0, and Android NDK 29.0.14206865.

The minimum SDK is raised from 16 to 21 because Android NDK 29 only provides
toolchain wrappers for API 21 and higher. The extension keeps the same
`applicationId` and `lib.extension.name=webm`, so Dashchan can still discover it
as the WebM library extension.

## Sample markers

The problematic sample used for this investigation has these relevant traits:

- MP4 major brand `iso6` with compatible brand `dash`
- top-level `sidx` followed by multiple `moof`/`mdat` pairs
- H.264 video with B-frames
- HE-AACv2 audio
- approximately 20.2 seconds duration

Do not commit sample media files unless their redistribution is explicitly
allowed.

## Test variants

Generate media variants outside the repository:

```sh
mkdir -p ../test-media
cp /path/to/original.mp4 ../test-media/original.mp4
ffmpeg -y -i ../test-media/original.mp4 -map 0 -c copy -movflags +faststart ../test-media/remux_faststart.mp4
ffmpeg -y -i ../test-media/original.mp4 -c:v copy -c:a aac -profile:a aac_low -b:a 96k ../test-media/aac_lc.mp4
ffmpeg -y -i ../test-media/original.mp4 -c:v libx264 -x264-params bframes=0 -c:a copy ../test-media/no_bframes.mp4
ffmpeg -y -i ../test-media/original.mp4 -an -c:v copy ../test-media/video_only.mp4
for file in ../test-media/*.mp4; do
	ffprobe -v error -show_format -show_streams -print_format json "$file" > "$file.ffprobe.json"
done
```

## Manual playback matrix

Install the rebuilt WebM extension, trust it in Dashchan if prompted, and test:

| File | Purpose | Expected result |
| --- | --- | --- |
| `original.mp4` | fragmented MP4 + H.264 + HE-AACv2 regression case | plays at normal speed |
| `remux_faststart.mp4` | same streams with conventional MP4 layout | separates fMP4 container issues |
| `aac_lc.mp4` | replaces HE-AACv2 audio | separates AAC/audio-clock issues |
| `no_bframes.mp4` | removes B-frames | separates PTS/DTS ordering issues |
| `video_only.mp4` | removes audio sync | separates audio/video sync issues |

Capture `adb logcat` around each run and record whether playback starts, whether
speed is normal, whether audio is present, and whether audio/video drift is
visible.
