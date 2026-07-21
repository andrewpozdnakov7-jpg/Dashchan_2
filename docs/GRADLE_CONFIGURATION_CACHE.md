# Gradle Configuration Cache

`org.gradle.configuration-cache=true` is enabled in `gradle.properties` to reduce configuration time on repeated builds.

## Expected Use

Run normal tasks without extra flags. Gradle will report whether the configuration cache was stored or reused.

```sh
./gradlew test
./gradlew assembleNdebug -PnativePlayerFfmpegFlavor=ffmpeg8 -PnativeAbis=arm64-v8a
```

## Troubleshooting

If a task fails only while configuration cache is enabled, retry once:

```sh
./gradlew TASK_NAME --no-configuration-cache
```

Record the exact task, Gradle version, stack trace, and generated problems report. Do not permanently disable the cache merely to hide a task bug. Native build failures inside FFmpeg, dav1d, libyuv, or NDK tools are usually unrelated to configuration cache.

After editing Gradle scripts, use `--configuration-cache-problems=warn` only for investigation; release builds should not silently accept new cache problems.
