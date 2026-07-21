# Fragmented MP4 Playback Notes

Dashchan_2 bundles the FFmpeg libraries from `Dashchan-Webm` into its main APK. Fragmented MP4 and malformed timestamp fixes involve both these libraries and the client player, so tests must use a rebuilt main application rather than installing a separate WebM extension.

## Baseline

- Android Gradle Plugin 9.2.1;
- compile SDK 36 and target SDK 30 in the main app;
- minimum SDK 30 / Android 11;
- Build Tools 36.0.0 and NDK 29.0.14206865;
- FFmpeg 8.1.2 and dav1d 1.5.3.

## Useful Sample Traits

Regression samples may include:

- MP4 brands such as `iso6` and `dash`;
- `sidx` followed by multiple `moof`/`mdat` pairs;
- H.264 B-frames or HEVC streams;
- HE-AACv2 audio;
- non-zero, negative, discontinuous, or incorrectly trimmed timestamps;
- audio continuing after video frame delivery stalls.

Do not commit user-provided media unless redistribution is explicitly permitted and metadata has been audited.

## External Analysis

Keep generated variants outside the repository:

```sh
mkdir -p ../test-media
cp /path/to/original.mp4 ../test-media/original.mp4
ffmpeg -y -i ../test-media/original.mp4 -map 0 -c copy \
  -movflags +faststart ../test-media/remux_faststart.mp4
ffmpeg -y -i ../test-media/original.mp4 -c:v copy -c:a aac \
  -profile:a aac_low -b:a 96k ../test-media/aac_lc.mp4
ffmpeg -y -i ../test-media/original.mp4 -an -c:v copy \
  ../test-media/video_only.mp4
for file in ../test-media/*.mp4; do
  ffprobe -v error -show_format -show_streams -print_format json \
    "$file" > "$file.ffprobe.json"
done
```

## Device Matrix

| File | Purpose | Expected result |
| --- | --- | --- |
| Original | Exact forum regression case | Starts, advances, seeks, and reaches the end |
| Fast-start remux | Separates fragmented-container behavior | Same streams play normally |
| AAC-LC variant | Separates audio-clock/profile behavior | Audio and video remain synchronized |
| Video-only variant | Separates audio synchronization | Video frame delivery does not stall |
| Shifted/truncated sample | Exercises timestamp normalization | Duration and seeking remain usable |

For each file, test normal speed, a non-1x speed, seeking, pause/resume, rotation, and picture-in-picture. Record whether audio/video drift appears and whether returning from PiP restores one player instance with visible controls.
