# Dashchan_2

[![Latest release](https://img.shields.io/github/v/release/andrewpozdnakov7-jpg/Dashchan_2?label=release)](https://github.com/andrewpozdnakov7-jpg/Dashchan_2/releases/latest)
[![Android 11+](https://img.shields.io/badge/Android-11%2B-3DDC84)](https://developer.android.com/about/versions/11)
[![License: GPL-3.0-or-later](https://img.shields.io/badge/license-GPL--3.0--or--later-blue)](COPYING)

## Русский

Dashchan_2 - неофициальная ветка Dashchan для Android 11 и новее. Она устанавливается рядом с оригинальным Dashchan, использует пакет `io.dashchan2` и содержит встроенную поддержку Двача, экспериментальный режим чтения 4chan и собственный видеоплеер на FFmpeg.

### Скачать

Стабильные APK публикуются только в [GitHub Releases](https://github.com/andrewpozdnakov7-jpg/Dashchan_2/releases). Для обычной установки нужен файл с окончанием `all-abi-signed.apk`. Не устанавливайте `unsigned`-файлы из сторонних источников.

| Компонент | Значение |
| --- | --- |
| Текущая версия | `3.2.8`, code `1088` |
| Android package | `io.dashchan2` |
| Минимальная версия | API 30 / Android 11 |
| ABI | `arm64-v8a`, `armeabi-v7a`, `x86` |
| Видеоплеер | FFmpeg 8.1.2, dav1d 1.5.3 |

Поддержка 2ch/Dvach и нативные библиотеки плеера встроены в основной APK. Отдельные дополнения Двача и WebM для нормальной работы больше не требуются. Старые отдельные дополнения можно удалить после проверки встроенных компонентов.

### Возможности

- просмотр досок, тредов, изображений и видео, избранное и фоновые уведомления об ответах;
- встроенный Двач с отправкой постов, Passcode, AI-фильтром и лайками/дизлайками на поддерживаемых досках;
- экспериментальный 4chan только для чтения, выключенный по умолчанию;
- FFmpeg-плеер для WebM, MP4, fMP4, MOV, H.264, HEVC, VP8, VP9 и AV1;
- скорость воспроизведения с сохранением тембра, перемотка двойным нажатием, жест громкости и режим «картинка в картинке»;
- галерея с фильтрами, копирование изображений и фоновое сохранение медиа;
- локальные архивы тредов в HTML и ZIP, встроенный просмотрщик и подключение дополнительных папок;
- встроенные, загружаемые и пользовательские темы с автоматическим дневным и ночным режимом;
- Predictive Back на Android 13+, масштаб текста, встроенные шрифты, OpenDyslexic и импорт TTF/OTF;
- выбор имени и значка приложения, а также создание собственного ярлыка;
- стабильный и добровольный beta-каналы обновлений с докачиванием APK.

### Ограничения

- 4chan пока не поддерживает отправку постов и может быть недоступен в отдельных сетях;
- Predictive Back и «картинка в картинке» включаются вручную;
- `targetSdk` намеренно остаётся 30 ради совместимости старого приложения; `minSdk` также равен 30.

### Сборка

Необходимы JDK 17+, Android SDK Platform 36, Build Tools 36.0.0, NDK 29.0.14206865 и Linux x86_64/WSL для нативных библиотек. Gradle Wrapper загружает Gradle 9.4.1.

```sh
./gradlew assembleNdebug \
  -PnativePlayerFfmpegFlavor=ffmpeg8 \
  -PnativeAbis=arm64-v8a,armeabi-v7a,x86
```

APK появится в `build/outputs/apk`. Первая сборка скачивает исходники FFmpeg, dav1d и libyuv и может занять заметное время. Репозиторий не содержит приватный ключ публикации и не создаёт официальный подписанный APK автоматически.

Подробные инструкции:

- [Сборка и окружение](docs/BUILDING.md)
- [Подписание APK](docs/SIGNING.md)
- [Обновление NDK 29](docs/NDK_UPGRADE_R29.md)
- [Проверки и автоматизация](docs/CI.md)
- [Ручное тестирование](docs/TESTING.md)
- [Чек-лист релиза](docs/RELEASE_CHECKLIST.md)
- [Configuration Cache](docs/GRADLE_CONFIGURATION_CACHE.md)
- [Метаданные обновлений](update/README.md)
- [Встроенные библиотеки плеера](Dashchan-Webm/README.md)

### Обратная связь

Перед сообщением об ошибке проверьте последний стабильный релиз. Баги и предложения оформляйте через [GitHub Issues](https://github.com/andrewpozdnakov7-jpg/Dashchan_2/issues). Для уязвимостей используйте инструкции из [SECURITY.md](SECURITY.md), а не публичный issue.

Правила участия находятся в [CONTRIBUTING.md](CONTRIBUTING.md), нормы общения - в [CODE_OF_CONDUCT.md](CODE_OF_CONDUCT.md).

### Происхождение и авторы

Dashchan_2 основан на исходном коде Dashchan. Отдельные идеи и участки кода были изучены и адаптированы из [TrixiEther/DashchanFork](https://github.com/TrixiEther/DashchanFork), а темы - из [TrixiEther/Dashchan-Meta](https://github.com/TrixiEther/Dashchan-Meta). Спасибо авторам оригинального Dashchan, TrixiEther и участникам этих проектов.

[OpenAI Codex](https://github.com/openai) и [Anthropic Claude](https://github.com/claude) использовались при анализе, разработке и проверке изменений. Решения и ответственность за публикацию остаются за сопровождающим проекта.

Проект распространяется по лицензии [GNU General Public License, version 3 or later](COPYING).

---

## English

Dashchan_2 is an unofficial Dashchan branch for Android 11 and newer. It installs alongside the original Dashchan under the `io.dashchan2` package and bundles Dvach support, experimental read-only 4chan support, and an FFmpeg-based video player.

### Download

Stable APKs are published only through [GitHub Releases](https://github.com/andrewpozdnakov7-jpg/Dashchan_2/releases). Regular users need the asset ending in `all-abi-signed.apk`. Do not install unsigned files obtained from third parties.

| Component | Value |
| --- | --- |
| Current version | `3.2.8`, code `1088` |
| Android package | `io.dashchan2` |
| Minimum Android | API 30 / Android 11 |
| ABIs | `arm64-v8a`, `armeabi-v7a`, `x86` |
| Video player | FFmpeg 8.1.2, dav1d 1.5.3 |

2ch/Dvach support and the native player libraries are bundled into the main APK. Separate Dvach and WebM extensions are no longer required for normal use.

### Highlights

- boards, threads, media gallery, favorites, thread watching, and background reply notifications;
- built-in Dvach posting, Passcode support, AI filtering, and votes on supported boards;
- optional experimental read-only 4chan channel;
- FFmpeg playback for WebM, MP4, fMP4, MOV, H.264, HEVC, VP8, VP9, and AV1;
- pitch-preserving speed control, double-tap seeking, volume gestures, and picture-in-picture;
- gallery filters, clipboard image copying, background media saving, and local HTML/ZIP thread archives;
- bundled, downloadable, and user-imported themes with automatic day and night switching;
- Predictive Back, text scaling, bundled fonts, OpenDyslexic, custom TTF/OTF fonts, and configurable app names and icons;
- stable and opt-in beta update channels with resumable APK downloads.

### Limitations

- 4chan posting is not implemented and the service may be unreachable on some networks;
- Predictive Back and picture-in-picture are opt-in settings;
- `targetSdk` intentionally remains 30 for legacy compatibility; `minSdk` is also 30.

### Building

Install JDK 17+, Android SDK Platform 36, Build Tools 36.0.0, NDK 29.0.14206865, and use Linux x86_64 or WSL for native libraries. The Gradle Wrapper downloads Gradle 9.4.1.

```sh
./gradlew assembleNdebug \
  -PnativePlayerFfmpegFlavor=ffmpeg8 \
  -PnativeAbis=arm64-v8a,armeabi-v7a,x86
```

The APK is written under `build/outputs/apk`. The first build downloads FFmpeg, dav1d, and libyuv sources. The repository does not contain the private release key and does not automatically produce the official signed APK.

See [docs/BUILDING.md](docs/BUILDING.md), [docs/TESTING.md](docs/TESTING.md), and [docs/RELEASE_CHECKLIST.md](docs/RELEASE_CHECKLIST.md) for the complete workflow.

### Feedback, Credits, And License

Use [GitHub Issues](https://github.com/andrewpozdnakov7-jpg/Dashchan_2/issues) for reproducible bugs and feature requests. Report vulnerabilities according to [SECURITY.md](SECURITY.md). Contributions are described in [CONTRIBUTING.md](CONTRIBUTING.md).

Dashchan_2 is based on Dashchan. Ideas and code paths were studied and adapted from [TrixiEther/DashchanFork](https://github.com/TrixiEther/DashchanFork), and themes were imported from [TrixiEther/Dashchan-Meta](https://github.com/TrixiEther/Dashchan-Meta). OpenAI Codex and Anthropic Claude assisted with development and verification; final responsibility remains with the maintainer.

Licensed under the [GNU General Public License, version 3 or later](COPYING).
