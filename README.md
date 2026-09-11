# Slooop (Dashchan_2)

[![Latest release](https://img.shields.io/github/v/release/andrewpozdnakov7-jpg/Dashchan_2?label=release)](https://github.com/andrewpozdnakov7-jpg/Dashchan_2/releases/latest)
[![Android 11+](https://img.shields.io/badge/Android-11%2B-3DDC84)](https://developer.android.com/about/versions/11)
[![License: GPL-3.0-or-later](https://img.shields.io/badge/license-GPL--3.0--or--later-blue)](COPYING)

<a href="https://f-droid.org/packages/io.dashchan2/">
  <img src="https://fdroid.gitlab.io/artwork/badge/get-it-on.png" alt="Get it on F-Droid" height="80">
</a>

[Политика конфиденциальности / Privacy Policy](PRIVACY.md)

## Русский

Slooop - пользовательское название неофициальной ветки Dashchan_2 для Android 11 и новее. Приложение устанавливается рядом с оригинальным Dashchan, использует пакет `io.dashchan2` и содержит встроенную поддержку Двача, «Кекабу» (Pikabu), d3.ru, Ежчана, Апачана, Архивача, Zchan и Endchan, экспериментальные режимы 4chan и Reddit и собственный видеоплеер на FFmpeg.

### Скачать

Slooop доступен в [GitHub Releases](https://github.com/andrewpozdnakov7-jpg/Dashchan_2/releases) и в [официальном каталоге F-Droid](https://f-droid.org/packages/io.dashchan2/). Для обычной установки GitHub-версии нужен universal-файл с окончанием `all-abi-signed.apk`. F-Droid самостоятельно пересобирает приложение и публикует отдельные варианты для поддерживаемых ABI, поэтому новая версия в его каталоге может появиться позже GitHub-релиза. Не устанавливайте `unsigned`-файлы из сторонних источников.

| Компонент | Значение |
| --- | --- |
| Android package | `io.dashchan2` |
| Минимальная версия | API 30 / Android 11 |
| ABI | `arm64-v8a`, `armeabi-v7a`, `x86` |
| Видеоплеер | FFmpeg 8.1.2, dav1d 1.5.3 |

Поддержка борд и нативные библиотеки плеера встроены в основной APK. Отдельные дополнения Двача и WebM для нормальной работы больше не требуются. Старые отдельные дополнения можно удалить после проверки встроенных компонентов.

GitHub-версия содержит встроенную проверку обновлений. Офлайн-переводчик Google ML Kit для неё устанавливается по желанию отдельным дополнением размером около 50 МБ и не увеличивает основной APK. Языковой пакет также загружается отдельно. Свободные шрифты и готовые фоновые изображения скачиваются из публичного каталога дополнений только по выбору пользователя.

F-Droid-версия не содержит встроенный механизм обновления приложения, Firebase, Google Play Services, серверные push-уведомления, экспериментальный офлайн-переводчик и интеграцию с дополнением Google ML Kit. Обновления устанавливаются через F-Droid; локальная фоновая проверка ответов остаётся доступной средствами Android.

### Возможности

- просмотр досок, тредов, изображений и видео, избранное, слежение за тредами и фоновые уведомления;
- встроенный Двач с отправкой постов, Passcode, AI-фильтром и лайками/дизлайками на поддерживаемых досках;
- встроенный «Кекабу»: каталог до 200 популярных сообществ с категориями, сворачиваемыми разделами и поиском, добавление собственных сообществ, теги, тематические ленты, вход через официальный сайт, оценки историй и комментариев, отправка и удаление своих комментариев;
- встроенные Ежчан, Апачан, Zchan и Endchan с чтением и отправкой сообщений, а также d3.ru, Архивач и экспериментальный 4chan в режиме чтения;
- экспериментальные режимы Reddit: официальный сайт внутри приложения, каталог до 200 популярных сообществ и отдельные режимы доски и чтения обсуждений, выключенные по умолчанию;
- редактирование постов и сортировка тредов на Апачане по последнему бампу, дате создания или количеству сообщений;
- «Мои доски» для объединения досок разных имиджборд в несколько собственных лент;
- экспериментальный раздел «Ответы», включаемый вручную в настройках: локальное запоминание отправленных через Slooop сообщений, автоматическая проверка ответов, счётчик непрочитанных, системные уведомления и очищаемый журнал последних 50 ответов; свежие треды проверяются чаще, а давно неактивные — реже;
- офлайн-перевод постов через Mozilla Bergamot, отдельное дополнение Google ML Kit или Gemini Nano на поддерживаемых устройствах;
- FFmpeg-плеер для WebM, MP4, fMP4, MOV, H.264, HEVC, VP8, VP9 и AV1;
- скорость воспроизведения с сохранением тембра и редактируемыми пресетами, перемотка двойным нажатием, масштабирование видео до 10×, отдельная громкость, настраиваемый жест громкости, полноэкранный режим, «картинка в картинке» и TikTok-режим для переключения видео вертикальными свайпами;
- выбор открытия YouTube-ссылок во внешнем приложении или в экспериментальном плеере Slooop с переходом в PiP;
- галерея с фильтрами, редактор изображений, копирование и поиск по изображениям, а также фоновое сохранение медиа;
- локальные архивы тредов в HTML и ZIP, встроенный просмотрщик и подключение дополнительных папок;
- встроенные, загружаемые и пользовательские темы, фоновые изображения и автоматический дневной и ночной режим;
- поиск по настройкам, Predictive Back на Android 13+, масштаб текста, сворачивание длинного списка открытых тредов, загружаемый каталог свободных шрифтов, OpenDyslexic и импорт TTF/OTF;
- выбор имени и значка приложения, включая народное имя «ТОГДАЧ», а также создание собственного ярлыка;
- мгновенное открытие локального списка изменений с фоновой загрузкой только новых записей;
- проверка стабильных обновлений с докачиванием APK.

### Ограничения

- d3.ru, 4chan и Архивач не поддерживают отправку постов; «Кекабу» пока не публикует истории и не обходит возрастные ограничения Пикабу;
- режимы Reddit и внутреннее воспроизведение YouTube/PiP экспериментальны, зависят от сайтов, WebView, сети и устройства и могут работать не везде; веб-интерфейсы поддерживаемых сайтов могут меняться;
- Google ML Kit доступен только как отдельное дополнение GitHub-версии, а Gemini Nano работает только на поддерживаемых устройствах;
- Predictive Back и «картинка в картинке» включаются вручную;
- `targetSdk` равен 37 для совместимости с Android 17; `minSdk` остаётся равен 30.

### Сборка

Необходимы JDK 21, Android SDK Platform 37, Build Tools 36.0.0, NDK 29.0.14206865 и Linux x86_64/WSL для нативных библиотек. Gradle Wrapper загружает Gradle 9.4.1.

```sh
./gradlew assembleGithubNdebug \
  -PnativePlayerFfmpegFlavor=ffmpeg8 \
  -PnativeAbis=arm64-v8a,armeabi-v7a,x86
```

APK появится в `build/outputs/apk`. Первая сборка скачивает исходники FFmpeg, dav1d и libyuv и может занять заметное время. Репозиторий не содержит приватный ключ публикации. Защищённый ручной workflow может создать временный подписанный кандидат, но ничего не публикует автоматически.

Подробные инструкции:

- [Сборка и окружение](docs/BUILDING.md)
- [Подготовка сборки F-Droid](docs/FDROID.md)
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

Slooop is the user-facing name of the unofficial Dashchan_2 branch for Android 11 and newer. It installs alongside the original Dashchan under the `io.dashchan2` package and bundles support for Dvach, Kekabu (Pikabu), d3.ru, Ejchan, Apachan, Arhivach, Zchan, Endchan, experimental 4chan and Reddit modes, and an FFmpeg-based video player.

### Download

Slooop is available through [GitHub Releases](https://github.com/andrewpozdnakov7-jpg/Dashchan_2/releases) and the [official F-Droid repository](https://f-droid.org/packages/io.dashchan2/). For a regular GitHub installation, use the universal asset ending in `all-abi-signed.apk`. F-Droid independently rebuilds the app and publishes separate variants for supported ABIs, so a new version may reach its repository later than the corresponding GitHub release. Do not install unsigned files obtained from third parties.

| Component | Value |
| --- | --- |
| Android package | `io.dashchan2` |
| Minimum Android | API 30 / Android 11 |
| ABIs | `arm64-v8a`, `armeabi-v7a`, `x86` |
| Video player | FFmpeg 8.1.2, dav1d 1.5.3 |

Forum support and the native player libraries are bundled into the main APK. Separate Dvach and WebM extensions are no longer required for normal use.

The GitHub build includes the in-app update checker. Its Google ML Kit offline translator is an optional separate add-on of approximately 50 MB and does not increase the main APK size. Its language pack is downloaded separately as well. Free fonts and ready-made wallpapers are downloaded from the public add-ons catalog only when selected by the user.

The F-Droid build excludes the application self-updater, Firebase, Google Play Services, server push notifications, the experimental offline translator, and integration with the Google ML Kit add-on. Updates are delivered through F-Droid, while local background reply checks remain available through Android.

### Highlights

- boards, threads, media gallery, favorites, thread watching, and background notifications;
- built-in Dvach posting, Passcode support, AI filtering, and votes on supported boards;
- built-in Kekabu access with a catalog of up to 200 popular communities, collapsible categories, search and custom communities, official-site sign-in, account-available 18+ media, user-initiated story and comment votes, and posting and deletion of the user's own comments;
- built-in Ejchan, Apachan, Zchan, and Endchan reading and posting, plus read-only d3.ru and Arhivach and experimental read-only 4chan access;
- experimental Reddit modes with the official website inside the app, a catalog of up to 200 popular communities, and separate opt-in board and discussion-reader modes;
- Apachan post editing and thread sorting by last bump, creation date, or post count;
- My Boards feeds that combine boards from different imageboards into several custom feeds;
- an opt-in experimental Replies section that locally remembers messages sent through Slooop, automatically checks for replies, shows an unread counter and Android notifications, and keeps a clearable history of the latest 50 replies; recent threads are checked more frequently than inactive ones;
- offline post translation through Mozilla Bergamot, the separate Google ML Kit add-on, or Gemini Nano on supported devices;
- FFmpeg playback for WebM, MP4, fMP4, MOV, H.264, HEVC, VP8, VP9, and AV1;
- pitch-preserving speed control with editable presets, double-tap seeking, video zoom up to 10×, per-video volume, configurable volume gestures, fullscreen playback, picture-in-picture, and a TikTok mode for switching videos with vertical swipes;
- a choice between opening YouTube links externally or through the experimental Slooop player with PiP;
- gallery filters, an image editor, clipboard image copying, reverse-image search, background media saving, and local HTML/ZIP thread archives;
- bundled, downloadable, and user-imported themes, optional wallpapers, and automatic day and night switching;
- settings search, Predictive Back, text scaling, collapsible long lists of open threads, a downloadable catalog of free fonts, OpenDyslexic, custom TTF/OTF fonts, and configurable app names and icons, including the community name “ТОГДАЧ”;
- immediate loading of the bundled changelog followed by background retrieval of newer entries only;
- stable update checks with resumable APK downloads.

### Limitations

- d3.ru, 4chan, and Arhivach posting are not implemented; Kekabu does not publish stories or bypass Pikabu age restrictions;
- Reddit modes and internal YouTube/PiP playback are experimental, depend on the websites, WebView, network, and device, and may not work everywhere; supported websites may change their interfaces;
- Google ML Kit is available only as a separate add-on for the GitHub build, while Gemini Nano requires a supported device;
- Predictive Back and picture-in-picture are opt-in settings;
- `targetSdk` is 37 for Android 17 compatibility; `minSdk` remains 30.

### Building

Install JDK 21, Android SDK Platform 37, Build Tools 36.0.0, NDK 29.0.14206865, and use Linux x86_64 or WSL for native libraries. The Gradle Wrapper downloads Gradle 9.4.1.

```sh
./gradlew assembleGithubNdebug \
  -PnativePlayerFfmpegFlavor=ffmpeg8 \
  -PnativeAbis=arm64-v8a,armeabi-v7a,x86
```

The APK is written under `build/outputs/apk`. The first build downloads FFmpeg, dav1d, and libyuv sources. The repository does not contain the private release key. A protected manual workflow can create a temporary signed candidate, but it never publishes a release automatically.

See [docs/BUILDING.md](docs/BUILDING.md), [docs/TESTING.md](docs/TESTING.md), and [docs/RELEASE_CHECKLIST.md](docs/RELEASE_CHECKLIST.md) for the complete workflow.

### Feedback, Credits, And License

Use [GitHub Issues](https://github.com/andrewpozdnakov7-jpg/Dashchan_2/issues) for reproducible bugs and feature requests. Report vulnerabilities according to [SECURITY.md](SECURITY.md). Contributions are described in [CONTRIBUTING.md](CONTRIBUTING.md).

Dashchan_2 is based on Dashchan. Ideas and code paths were studied and adapted from [TrixiEther/DashchanFork](https://github.com/TrixiEther/DashchanFork), and themes were imported from [TrixiEther/Dashchan-Meta](https://github.com/TrixiEther/Dashchan-Meta). OpenAI Codex and Anthropic Claude assisted with development and verification; final responsibility remains with the maintainer.

Licensed under the [GNU General Public License, version 3 or later](COPYING).
