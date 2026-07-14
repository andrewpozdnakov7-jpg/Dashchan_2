# Dashchan_2

## Русский

Dashchan_2 - неофициальная ветка Dashchan для Android 11+.

Цель проекта - сохранить рабочий клиент для имиджборд на современных Android,
обновить сборочную среду и встроить актуальный видеоплеер без отдельного WebM
расширения. Приложение ставится отдельно от оригинального Dashchan и использует
собственный package name.

### Текущие версии

| Компонент | Package | Версия | Минимальный Android |
| --- | --- | --- | --- |
| Dashchan_2 с поддержкой Двача | `io.dashchan2` | `3.1.9`, code `1077` | API 30 / Android 11+ |
| Fourchan extension | `io.dashchan2.chan.fourchan` | `1.27-read-only-1`, code `1` | API 30 / Android 11+ |

Поддержка 2ch/Dvach встроена в основной APK `Dashchan_2`. Отдельное дополнение
`Dashchan_2 for 2ch` больше не требуется; установленную старую версию дополнения
можно удалить после проверки встроенного Двача.

Для просмотра 4chan установите основной APK и отдельное расширение
`Dashchan_2 Fourchan`. Первая версия расширения работает только на чтение:
публикация постов пока отключена.

WebM2/FFmpeg/dav1d/yuv теперь встроены в основной APK. Отдельный WebM2 APK для
обычного воспроизведения видео больше не нужен.

### Что изменено

- Приложение переименовано в `Dashchan_2`.
- Application ID изменен на `io.dashchan2`.
- Добавлен отдельный file provider: `io.dashchan2.provider`.
- Поддержка Dvach встроена в основной APK; Fourchan остается отдельным расширением.
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
  `player_build`, `player_ffmpeg` и `speed_processing`.
- Добавлена проверка обновлений через `update/data.json` и GitHub Releases.
- Добавлены темы.
- Улучшено выделение и копирование текста в постах, включая обходной вариант
  для прошивок с урезанным системным меню выделения.
- Исправлена синяя полоса при резкой прокрутке и overscroll на пользовательских
  темах.
- Исправлено зависание некоторых HEVC-видео при продолжающемся звуке.
- Выбор скорости видео сохраняет нормальный тембр с помощью FFmpeg `atempo`.
- Добавлена настройка сохранения выбранной скорости между видео.
- Добавлен выбор названия приложения и создание ярлыка со своим именем.
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

Код поддержки 2ch/Dvach встроен в этот репозиторий и загружается как внутренний
канал `dvach`, благодаря чему сохраняются существующие настройки, история и
избранное. Актуальные исходники внешних расширений находятся в соседнем
репозитории `Dashchan_2-Extensions`; для 4chan используется package
`io.dashchan2.chan.fourchan`.

### Происхождение и авторы

Dashchan_2 основан на исходном коде Dashchan. Отдельные идеи и участки кода
были изучены и адаптированы из форка `TrixiEther/DashchanFork`, а темы - из
`TrixiEther/Dashchan-Meta`. Большое спасибо авторам оригинального Dashchan,
TrixiEther и участникам этих проектов.

При анализе, разработке и проверке изменений использовались
[OpenAI Codex](https://github.com/openai) и
[Anthropic Claude](https://github.com/claude). Окончательные решения и
ответственность за публикуемые изменения остаются за сопровождающим проекта.

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

### Current Versions

| Component | Package | Version | Minimum Android |
| --- | --- | --- | --- |
| Dashchan_2 with Dvach support | `io.dashchan2` | `3.1.9`, code `1077` | API 30 / Android 11+ |
| Fourchan extension | `io.dashchan2.chan.fourchan` | `1.27-read-only-1`, code `1` | API 30 / Android 11+ |

2ch/Dvach support is bundled into the main `Dashchan_2` APK. The separate
`Dashchan_2 for 2ch` extension is no longer required; an installed legacy copy
can be removed after verifying the built-in channel.

To browse 4chan, install the main APK and the separate
`Dashchan_2 Fourchan` extension. Its first release is read-only; posting is
currently disabled.

WebM2/FFmpeg/dav1d/yuv libraries are bundled into the main APK. A separate
WebM2 APK is no longer required for normal video playback.

### Changes

- Renamed the app to `Dashchan_2`.
- Changed the application ID to `io.dashchan2`.
- Added a separate file provider authority: `io.dashchan2.provider`.
- Bundled Dvach support into the main APK; Fourchan remains a separate extension.
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
  `player_build`, `player_ffmpeg`, and `speed_processing`.
- Added update checking through `update/data.json` and GitHub Releases.
- Added themes.
- Improved post text selection and copying, including a fallback for ROMs with
  limited system text-selection menus.
- Fixed the blue strip during fast scrolling and overscroll with custom themes.
- Fixed some HEVC videos freezing while audio continued.
- Playback speed now preserves pitch through FFmpeg `atempo`.
- Added an option to keep the selected playback speed between videos.
- Added application name presets and custom-named Home screen shortcuts.
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

The 2ch/Dvach implementation is bundled in this repository and registered as
the internal `dvach` channel, preserving existing settings, history, and
favorites. Current external extension sources are maintained in the sibling
`Dashchan_2-Extensions` repository; the 4chan extension uses
`io.dashchan2.chan.fourchan`.

### Upstream And Credits

Dashchan_2 is based on the original Dashchan source code. Some ideas and code
paths were studied and adapted from `TrixiEther/DashchanFork`, and downloadable
themes were imported from `TrixiEther/Dashchan-Meta`. Thanks to the original
Dashchan authors, TrixiEther, and contributors to those projects.

[OpenAI Codex](https://github.com/openai) and
[Anthropic Claude](https://github.com/claude) assisted with analysis,
development, and verification. Final decisions and responsibility for the
published changes remain with the project maintainer.

### License

Dashchan_2 is based on Dashchan and is available under the
[GNU General Public License, version 3 or later](COPYING).
