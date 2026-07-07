# Dashchan_2

## Русский

Dashchan_2 - неофициальная ветка Dashchan для Android 11+.

Цель проекта - сохранить рабочий клиент для имиджборд на современных Android,
обновить сборочную среду и встроить актуальный видеоплеер без отдельного WebM
расширения. Приложение ставится отдельно от оригинального Dashchan и использует
собственный package name.

### Текущие APK

| Компонент | Package | Версия | Минимальный Android |
| --- | --- | --- | --- |
| Dashchan_2 | `io.dashchan2` | `3.1.4-r17-dev11`, code `1066` | API 30 / Android 11+ |
| Dvach extension | `io.dashchan2.chan.dvach` | `1.43-experimental-1.6-r11`, code `8` | API 30 / Android 11+ |

Для работы с 2ch/Dvach нужно установить оба APK:

- основной APK `Dashchan_2`;
- APK расширения `Dashchan_2 for 2ch`.

WebM2/FFmpeg/dav1d/yuv теперь встроены в основной APK. Отдельный WebM2 APK для
обычного воспроизведения видео больше не нужен.

### Что изменено

- Приложение переименовано в `Dashchan_2`.
- Application ID изменен на `io.dashchan2`.
- Добавлен отдельный file provider: `io.dashchan2.provider`.
- Поддерживается отдельное Dvach-расширение `io.dashchan2.chan.dvach`.
- Добавлены явные Android package queries для известных расширений Dashchan_2.
- Убрана необходимость `QUERY_ALL_PACKAGES` для поиска расширений.
- Минимальная версия Android поднята до API 30 / Android 11+.
- Сборка обновлена до Android Gradle Plugin 9.2.1, Gradle 9.4.1,
  compile SDK 36, Build Tools 36.0.0 и NDK 29.0.14206865.
- Встроенный видеоплеер обновлен до FFmpeg 8.1.2.
- Добавлена поддержка `arm64-v8a`, `armeabi-v7a` и `x86`.
- Исправлено воспроизведение проблемных fragmented MP4/fMP4 роликов.
- Исправлен расчет длительности аудиобуфера в native player.
- Добавлена информация о сборке native player в metadata dialog:
  `player_build` и `player_ffmpeg`.
- Добавлена проверка обновлений через `update/data.json` и GitHub Releases.
- Добавлены темы.
- Встроенные WebM/FFmpeg исходники находятся в папке `Dashchan-Webm`.

### Сборка

Требуется:

- JDK 17 или новее;
- Android SDK Platform 36;
- Android SDK Build Tools 36.0.0;
- Android NDK 29.0.14206865;
- Gradle 9.4.1;
- Linux/WSL для сборки native FFmpeg/WebM библиотек.

Локальная all-ABI сборка:

```sh
../tools/gradle-9.4.1/bin/gradle assembleNdebug \
  -PnativePlayerFfmpegFlavor=ffmpeg8 \
  -PnativeAbis=arm64-v8a,armeabi-v7a,x86
```

APK появится в `build/outputs/apk`.

Gradle configuration cache включен по умолчанию. Если локальная Gradle-задача
падает из-за configuration cache, повторите команду с `--no-configuration-cache`
и сообщите имя задачи.

Документация по сборке и релизу:

- `docs/SIGNING.md`
- `docs/NDK_UPGRADE_R29.md`
- `docs/CI.md`
- `docs/TESTING.md`
- `docs/RELEASE_CHECKLIST.md`
- `docs/GRADLE_CONFIGURATION_CACHE.md`

### Расширения

Актуальные исходники расширений Dashchan_2 находятся в соседнем репозитории
`Dashchan_2-Extensions`. Для 2ch/Dvach используется package
`io.dashchan2.chan.dvach`.

### Лицензия

Dashchan_2 основан на Dashchan и распространяется по лицензии
[GNU General Public License, version 3 or later](COPYING).

---

## English

Dashchan_2 is an unofficial Dashchan branch for Android 11+.

The project keeps a working imageboard client usable on modern Android devices,
updates the build environment, and bundles the current video player directly
into the main APK instead of requiring a separate WebM extension. The app can be
installed beside the original Dashchan because it uses its own package name.

### Current APKs

| Component | Package | Version | Minimum Android |
| --- | --- | --- | --- |
| Dashchan_2 | `io.dashchan2` | `3.1.4-r17-dev11`, code `1066` | API 30 / Android 11+ |
| Dvach extension | `io.dashchan2.chan.dvach` | `1.43-experimental-1.6-r11`, code `8` | API 30 / Android 11+ |

Install both APKs to use 2ch/Dvach:

- the main `Dashchan_2` APK;
- the `Dashchan_2 for 2ch` extension APK.

WebM2/FFmpeg/dav1d/yuv libraries are bundled into the main APK. A separate
WebM2 APK is no longer required for normal video playback.

### Changes

- Renamed the app to `Dashchan_2`.
- Changed the application ID to `io.dashchan2`.
- Added a separate file provider authority: `io.dashchan2.provider`.
- Added support for the Dashchan_2 Dvach extension
  `io.dashchan2.chan.dvach`.
- Added explicit Android package queries for known Dashchan_2 extensions.
- Removed the need for `QUERY_ALL_PACKAGES` in extension discovery.
- Raised the minimum Android version to API 30 / Android 11+.
- Updated the build stack to Android Gradle Plugin 9.2.1, Gradle 9.4.1,
  compile SDK 36, Build Tools 36.0.0, and NDK 29.0.14206865.
- Updated the built-in video player to FFmpeg 8.1.2.
- Added `arm64-v8a`, `armeabi-v7a`, and `x86` native builds.
- Fixed playback of problematic fragmented MP4/fMP4 videos.
- Fixed native-player audio-buffer duration calculation.
- Added native-player metadata keys shown in the metadata dialog:
  `player_build` and `player_ffmpeg`.
- Added update checking through `update/data.json` and GitHub Releases.
- Added themes.
- Bundled WebM/FFmpeg source is stored in `Dashchan-Webm`.

### Building

Requirements:

- JDK 17 or newer;
- Android SDK Platform 36;
- Android SDK Build Tools 36.0.0;
- Android NDK 29.0.14206865;
- Gradle 9.4.1;
- Linux/WSL for native FFmpeg/WebM builds.

Local all-ABI build:

```sh
../tools/gradle-9.4.1/bin/gradle assembleNdebug \
  -PnativePlayerFfmpegFlavor=ffmpeg8 \
  -PnativeAbis=arm64-v8a,armeabi-v7a,x86
```

The APK will be written to `build/outputs/apk`.

Gradle configuration cache is enabled by default. If a local Gradle task fails
because of configuration cache, rerun it once with `--no-configuration-cache`
and report the task name.

Build and release documentation:

- `docs/SIGNING.md`
- `docs/NDK_UPGRADE_R29.md`
- `docs/CI.md`
- `docs/TESTING.md`
- `docs/RELEASE_CHECKLIST.md`
- `docs/GRADLE_CONFIGURATION_CACHE.md`

### Extensions

Current Dashchan_2 extension sources are maintained in the sibling
`Dashchan_2-Extensions` repository. The 2ch/Dvach extension package is
`io.dashchan2.chan.dvach`.

### License

Dashchan_2 is based on Dashchan and is available under the
[GNU General Public License, version 3 or later](COPYING).
