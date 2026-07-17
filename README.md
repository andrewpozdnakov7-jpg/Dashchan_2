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
| Dashchan_2 со встроенными Dvach и 4chan | `io.dashchan2` | `3.2.3`, code `1083` | API 30 / Android 11+ |

Поддержка 2ch/Dvach встроена в основной APK `Dashchan_2`. Отдельное дополнение
`Dashchan_2 for 2ch` больше не требуется; установленную старую версию дополнения
можно удалить после проверки встроенного Двача.

Экспериментальная поддержка 4chan также встроена в основной APK, работает
только на чтение и по умолчанию выключена. Ее можно включить в разделе
`Настройки` > `Форум`; публикация постов пока отключена.

WebM2/FFmpeg/dav1d/yuv теперь встроены в основной APK. Отдельный WebM2 APK для
обычного воспроизведения видео больше не нужен.

### Что изменено

- Приложение переименовано в `Dashchan_2`.
- Application ID изменен на `io.dashchan2`.
- Добавлен отдельный file provider: `io.dashchan2.provider`.
- Поддержка Dvach и экспериментальная read-only поддержка 4chan встроены в основной APK.
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
- Исправлено воспроизведение и перемотка некорректно обрезанных видео со
  смещенной временной шкалой.
- Выбор скорости видео сохраняет нормальный тембр с помощью FFmpeg `atempo`.
- Добавлена настройка сохранения выбранной скорости между видео.
- Устранены задержки интерфейса при архивации больших тредов, сохранении
  оригиналов и миниатюр.
- Добавлена экспериментальная поддержка Predictive Back на Android 13+.
- Добавлена настройка цветов меток своих постов и ответов.
- Добавлен выбор названия приложения и создание ярлыка со своим именем и
  изображением; доступен шаблон промта для генерации иконки.
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

Код поддержки 2ch/Dvach и экспериментальной read-only поддержки 4chan встроен
в этот репозиторий. Каналы загружаются внутри основного приложения, благодаря
чему для них не требуются отдельные APK. 4chan по умолчанию выключен и пока не
поддерживает публикацию постов. Исходники других внешних расширений находятся
в соседнем репозитории `Dashchan_2-Extensions`.

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
| Dashchan_2 with built-in Dvach and 4chan | `io.dashchan2` | `3.2.3`, code `1083` | API 30 / Android 11+ |

2ch/Dvach support is bundled into the main `Dashchan_2` APK. The separate
`Dashchan_2 for 2ch` extension is no longer required; an installed legacy copy
can be removed after verifying the built-in channel.

Experimental 4chan support is also bundled into the main APK. It is read-only
and disabled by default. Enable it under `Settings` > `Forum`; posting is
currently disabled.

WebM2/FFmpeg/dav1d/yuv libraries are bundled into the main APK. A separate
WebM2 APK is no longer required for normal video playback.

### Changes

- Renamed the app to `Dashchan_2`.
- Changed the application ID to `io.dashchan2`.
- Added a separate file provider authority: `io.dashchan2.provider`.
- Bundled Dvach and experimental read-only 4chan support into the main APK.
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
- Fixed playback and seeking in incorrectly trimmed videos with shifted
  timelines.
- Playback speed now preserves pitch through FFmpeg `atempo`.
- Added an option to keep the selected playback speed between videos.
- Removed UI delays when archiving large threads and saving original files and
  thumbnails.
- Added experimental Predictive Back support on Android 13+.
- Added color settings for markers of your posts and replies.
- Added application name presets and Home screen shortcuts with custom names
  and images; an image-generation prompt template is included.
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

The 2ch/Dvach implementation and experimental read-only 4chan support are
bundled in this repository. Both channels load inside the main application and
do not require separate APKs. 4chan is disabled by default and does not yet
support posting. Sources for other external extensions are maintained in the
sibling `Dashchan_2-Extensions` repository.

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
