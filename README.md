# Slooop (Dashchan_2)

[![Latest release](https://img.shields.io/github/v/release/andrewpozdnakov7-jpg/Dashchan_2?label=release)](https://github.com/andrewpozdnakov7-jpg/Dashchan_2/releases/latest)
[![Android 11+](https://img.shields.io/badge/Android-11%2B-3DDC84)](https://developer.android.com/about/versions/11)
[![License: GPL-3.0-or-later](https://img.shields.io/badge/license-GPL--3.0--or--later-blue)](COPYING)

<a href="https://f-droid.org/packages/io.dashchan2/">
  <img src="https://fdroid.gitlab.io/artwork/badge/get-it-on.png" alt="Get it on F-Droid" height="80">
</a>

[Политика конфиденциальности / Privacy Policy](PRIVACY.md) · [Wiki](https://github.com/andrewpozdnakov7-jpg/Dashchan_2/wiki)

## Русский

Slooop - пользовательское название неофициальной ветки Dashchan_2 для Android 11 и новее. Приложение устанавливается рядом с оригинальным Dashchan, использует пакет `io.dashchan2` и содержит встроенную поддержку Двача, «Кекабу» (Pikabu), d3.ru, Ежчана, ECH (ech.bz), Апачана, Архивача, Zchan и Endchan, экспериментальные режимы 4chan, Soyjak.party и Reddit и собственный видеоплеер на FFmpeg.

### Скачать

Slooop доступен в [GitHub Releases](https://github.com/andrewpozdnakov7-jpg/Dashchan_2/releases) и в [официальном каталоге F-Droid](https://f-droid.org/packages/io.dashchan2/). Для обычной установки GitHub-версии нужен universal-файл с окончанием `all-abi-signed.apk`. F-Droid самостоятельно пересобирает приложение и публикует отдельные варианты для поддерживаемых ABI, поэтому новая версия в его каталоге может появиться позже GitHub-релиза. Не устанавливайте `unsigned`-файлы из сторонних источников.

| Компонент | Значение |
| --- | --- |
| Android package | `io.dashchan2` |
| Минимальная версия | API 30 / Android 11 |
| ABI | `arm64-v8a`, `armeabi-v7a`, `x86` |
| Видеоплеер | FFmpeg 8.1.3, dav1d 1.5.4 |

Поддержка борд и нативные библиотеки плеера встроены в основной APK. Отдельные дополнения Двача и WebM для нормальной работы больше не требуются. Старые отдельные дополнения можно удалить после проверки встроенных компонентов.

GitHub-версия содержит встроенную проверку обновлений. Офлайн-переводчик Google ML Kit для неё устанавливается по желанию отдельным дополнением размером около 50 МБ и не увеличивает основной APK. Языковой пакет также загружается отдельно. Свободные шрифты и готовые фоновые изображения скачиваются из публичного каталога дополнений только по выбору пользователя.

В F-Droid обновления устанавливаются через каталог, а ответы могут проверяться локально в фоне. В эту сборку не входят встроенное обновление, Firebase, Google Play Services, серверные push-уведомления, Google ML Kit и Gemini Nano. Со сборки 3.2.85 доступны офлайн-перевод Mozilla Bergamot со сборкой движка из исходников, защита приложения и Predictive Back. Со сборки 3.2.91 доступна локальная конвертация видео для вложений. Доступные версии указаны в [каталоге F-Droid](https://f-droid.org/packages/io.dashchan2/).

### Возможности

- Доски и треды, избранное, свои ленты «Мои доски» и уведомления об ответах.
- Офлайн-перевод постов и локальные архивы тредов с обновлением сохранённой копии.
- Галерея, фоторедактор и поиск по картинке.
- Видеоплеер с PiP и конвертер видео для вложений.
- Темы, шрифты, масштаб текста, Predictive Back и настройка бокового меню и ярлыка.
- Защита приложения с PIN и биометрией.

### Ограничения

- Возможность публикации зависит от форума. Подробнее — в [справке по форумам](https://github.com/andrewpozdnakov7-jpg/Dashchan_2/wiki/Boards).
- Встроенные режимы Reddit и YouTube экспериментальные и зависят от работы сайтов.

### Сборка

Необходимы JDK 21, Android SDK Platform 37, Build Tools 36.0.0, NDK 29.0.14206865 и Linux x86_64/WSL для нативных библиотек. Gradle Wrapper загружает Gradle 9.6.1; используется Android Gradle Plugin 9.4.0.

```sh
./gradlew assembleGithubNdebug \
  -PnativePlayerFfmpegFlavor=ffmpeg8 \
  -PnativeAbis=arm64-v8a,armeabi-v7a,x86
```

APK появится в `build/outputs/apk`. Первая сборка скачивает исходники FFmpeg и dav1d и может занять заметное время. Репозиторий не содержит приватный ключ публикации. Защищённый ручной workflow может создать временный подписанный кандидат, но ничего не публикует автоматически.

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

### Благодарности участникам развития проекта

1. **Vados** (анонимный участник) — тестирование приложения и идеи по развитию галереи, видеоплеера и поддержки Reddit.
2. **[kamik-hub](https://github.com/kamik-hub)** (Самсунг-кун) — тестирование сборок, отчёты о падениях и идеи по развитию интерфейса и видеоконвертера.
3. **squad wow** (fsdfdsfsd575756@gmail.com) — независимая проверка воспроизводимости сборок F-Droid, поиск багов в работе с расширениями, идеи по навигации в тредах и приватности уведомлений.
4. **Max Relax** (анонимный участник) — тестирование приложения и помощь в улучшении формы ответа, работы клавиатуры и функции «Поделиться».

### Происхождение и авторы

Dashchan_2 основан на исходном коде Dashchan. Отдельные идеи и участки кода были изучены и адаптированы из [TrixiEther/DashchanFork](https://github.com/TrixiEther/DashchanFork), а темы - из [TrixiEther/Dashchan-Meta](https://github.com/TrixiEther/Dashchan-Meta). Спасибо авторам оригинального Dashchan, TrixiEther и участникам этих проектов.

[OpenAI Codex](https://github.com/openai) и [Anthropic Claude](https://github.com/claude) использовались при анализе, разработке и проверке изменений. Решения и ответственность за публикацию остаются за сопровождающим проекта.

Проект распространяется по лицензии [GNU General Public License, version 3 or later](COPYING).

---

## English

Slooop is the user-facing name of the unofficial Dashchan_2 branch for Android 11 and newer. It installs alongside the original Dashchan under the `io.dashchan2` package and bundles support for Dvach, Kekabu (Pikabu), d3.ru, Ejchan, ECH (ech.bz), Apachan, Arhivach, Zchan, Endchan, experimental 4chan, Soyjak.party, and Reddit modes, and an FFmpeg-based video player.

### Download

Slooop is available through [GitHub Releases](https://github.com/andrewpozdnakov7-jpg/Dashchan_2/releases) and the [official F-Droid repository](https://f-droid.org/packages/io.dashchan2/). For a regular GitHub installation, use the universal asset ending in `all-abi-signed.apk`. F-Droid independently rebuilds the app and publishes separate variants for supported ABIs, so a new version may reach its repository later than the corresponding GitHub release. Do not install unsigned files obtained from third parties.

| Component | Value |
| --- | --- |
| Android package | `io.dashchan2` |
| Minimum Android | API 30 / Android 11 |
| ABIs | `arm64-v8a`, `armeabi-v7a`, `x86` |
| Video player | FFmpeg 8.1.3, dav1d 1.5.4 |

Forum support and the native player libraries are bundled into the main APK. Separate Dvach and WebM extensions are no longer required for normal use.

The GitHub build includes the in-app update checker. Its Google ML Kit offline translator is an optional separate add-on of approximately 50 MB and does not increase the main APK size. Its language pack is downloaded separately as well. Free fonts and ready-made wallpapers are downloaded from the public add-ons catalog only when selected by the user.

F-Droid delivers updates through its repository. Local background reply checks remain available. This build excludes the self-updater, Firebase, Google Play Services, server push notifications, Google ML Kit, and Gemini Nano. F-Droid builds starting with 3.2.85 support offline translation with a source-built Mozilla Bergamot engine, app protection, and Predictive Back. Local video conversion for attachments is available starting with 3.2.91. See the [F-Droid listing](https://f-droid.org/packages/io.dashchan2/) for available versions.

### Highlights

- Boards and threads, favorites, custom My Boards feeds, and reply notifications.
- Offline post translation and local thread archives that can be updated.
- Gallery, photo editor, and reverse image search.
- Video player with picture-in-picture and a video converter for attachments.
- Themes, fonts, text scaling, Predictive Back, and a customizable side menu and launcher shortcut.
- App protection with a PIN and biometrics.

### Limitations

- Posting options vary by forum. See the [Boards guide](https://github.com/andrewpozdnakov7-jpg/Dashchan_2/wiki/Boards) for details.
- The built-in Reddit and YouTube modes are experimental and depend on the websites.

### Building

Install JDK 21, Android SDK Platform 37, Build Tools 36.0.0, NDK 29.0.14206865, and use Linux x86_64 or WSL for native libraries. The Gradle Wrapper downloads Gradle 9.6.1; the project uses Android Gradle Plugin 9.4.0.

```sh
./gradlew assembleGithubNdebug \
  -PnativePlayerFfmpegFlavor=ffmpeg8 \
  -PnativeAbis=arm64-v8a,armeabi-v7a,x86
```

The APK is written under `build/outputs/apk`. The first build downloads FFmpeg and dav1d sources. The repository does not contain the private release key. A protected manual workflow can create a temporary signed candidate, but it never publishes a release automatically.

See [docs/BUILDING.md](docs/BUILDING.md), [docs/TESTING.md](docs/TESTING.md), and [docs/RELEASE_CHECKLIST.md](docs/RELEASE_CHECKLIST.md) for the complete workflow.

### Acknowledgments

1. **Vados** (anonymous contributor) — app testing and ideas for improving the gallery, video player, and Reddit support.
2. **[kamik-hub](https://github.com/kamik-hub)** (Самсунг-кун) — testing builds, reporting crashes, and suggesting improvements to the interface and video converter.
3. **squad wow** (fsdfdsfsd575756@gmail.com) — independently verifying reproducible F-Droid builds, finding bugs in extension handling, and suggesting improvements to thread navigation and notification privacy.
4. **Max Relax** (anonymous contributor) — testing the app and helping improve the reply form, keyboard behavior, and sharing.

### Feedback, Credits, and License

Use [GitHub Issues](https://github.com/andrewpozdnakov7-jpg/Dashchan_2/issues) for reproducible bugs and feature requests. Report vulnerabilities according to [SECURITY.md](SECURITY.md). Contributions are described in [CONTRIBUTING.md](CONTRIBUTING.md).

Dashchan_2 is based on Dashchan. Ideas and code paths were studied and adapted from [TrixiEther/DashchanFork](https://github.com/TrixiEther/DashchanFork), and themes were imported from [TrixiEther/Dashchan-Meta](https://github.com/TrixiEther/Dashchan-Meta). OpenAI Codex and Anthropic Claude assisted with development and verification; final responsibility remains with the maintainer.

Licensed under the [GNU General Public License, version 3 or later](COPYING).
