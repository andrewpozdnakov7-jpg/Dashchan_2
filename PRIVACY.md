# Политика конфиденциальности Slooop / Slooop Privacy Policy

**This Privacy Policy is provided in English and Russian. Both versions describe the same data practices. English is presented first for convenience.**

**Политика конфиденциальности составлена на английском и русском языках. Обе версии описывают одинаковые правила обработки данных. Для удобства английская версия приведена первой.**

[Русская версия](#русская-версия)

## English version

**Effective and last updated: September 1, 2026.**

### 1. Scope

This policy applies to official builds of the Slooop Android application (package `io.dashchan2`). It describes the categories of data the app may process, why they are needed, and who may receive them.

Slooop is an independent, non-commercial client for third-party forums and other public websites. The project does not operate those websites and is not responsible for their privacy practices.

### 2. Summary

- Slooop does not require a separate Slooop account.
- The app contains no advertising, advertising identifiers, built-in behavioral analytics, or automatic crash-report uploads to the developer.
- Most requests go directly to the website selected by the user, its CDN, or another provider of a requested feature. Normal forum browsing is not proxied through a Slooop server.
- Settings, history, favorites, open pages, drafts, cookies, cache, and other working data are primarily stored on the device.
- Optional server-side reply notifications use Google Firebase Cloud Messaging and the Slooop notification service only after explicit user consent.
- Any website or network provider may receive ordinary connection data, including the IP address, request time, and HTTP headers.

### 3. Data processed when using supported websites

When a user opens a board, thread, profile, feed, image, or video, Slooop sends the relevant website the data required for that request: the resource address; site, board, thread, or post identifiers; sorting and search parameters; cookies; and technical HTTP headers.

Where a website supports user actions, Slooop may, on the user's explicit command, send that website the entered text, selected attachments, name or other completed fields, captcha response, passcode, or authorization session. Features differ between websites, and some integrations are read-only.

The website and its infrastructure determine how they retain the data they receive. Users should review the relevant website's rules before signing in or submitting content. Some legacy or user-provided resources may use unencrypted HTTP; transport protection depends on the particular website and URL.

### 4. Sign-in, cookies, and WebView

Slooop may store website cookies, passcodes, anti-bot or OAuth tokens, an authorized username, and completed website sessions in the app's private storage. They are used for requests to the website that issued them and are not intended to be sent to the Slooop service. If a website uses OAuth, the provider displays the requested permissions and Slooop receives the issued token, not the password for that website.

Pikabu sign-in opens the official `pikabu.ru` website and permitted official identity providers in an embedded window. Slooop does not read the values of login or password fields. After sign-in, the app detects successful authorization, may store the displayed username, and stores the resulting cookie session for later Pikabu requests. Signing out removes the stored session from the app.

Embedded web pages may use JavaScript, cookies, DOM storage, and third-party cookies where required for sign-in, captcha, or website protection. Their processing is also governed by the relevant website or identity provider.

### 5. Optional reply push notifications

The GitHub build lets users separately enable server-side push notifications. Firebase is not initialized for this feature and the Slooop service is not registered until the user accepts the warning. The F-Droid build does not contain this integration.

After opt-in, the app creates a random installation identifier and secret, obtains a Firebase registration token, and sends the Slooop notification service:

- the random installation identifier, its secret, and the FCM token;
- the application identifier, version, and platform type;
- identifiers of the supported website, board, thread, and watched post;
- technical watch and event identifiers required for delivery and deduplication.

To form a notification, the reply identifier, time, and reply text may be processed. Server-side checking is limited to explicitly supported sections and does not receive a forum password, Google account, email address, Android ID, serial number, or device advertising identifier.

The installation secret and FCM token are stored in a separate private file excluded from Android cloud backup and device-to-device transfer. Registration can be removed by disabling the feature or resetting the identifier; inactive server records also have a limited operational lifetime. Firebase may create its own service installation identifier. Google and the notification service's cloud host may receive the IP address and standard request or log metadata; the project does not use an IP address as an account and does not intentionally build a history of user IP addresses.

Local reply checking can operate independently from Firebase. For this feature, the app stores identifiers of the user's posts and replies, text, timestamps, check state, and read state on the device.

### 6. External tools and user-selected actions

Some actions intentionally disclose data to an external service selected by the user:

- a reverse-image search sends Google Lens or Yandex either the image URL or, for a local file, a prepared copy of the image itself;
- opening or sending a link gives that URL to an external browser, messenger, or other selected application;
- using Share gives the selected application the chosen text or file;
- built-in or separately installed translators and system AI components receive the selected text for processing; built-in translation is designed to run on the device, but model downloads may contact provider infrastructure;
- themes, fonts, wallpapers, language models, add-ons, and updates selected by the user may be downloaded from GitHub or other listed public repositories.

Slooop does not send this data to a Slooop server for advertising or profiling. Processing by an external service is governed by that service's own policy.

### 7. Data stored on the device

Depending on the features used, the app may store:

- app settings and enabled websites and boards;
- history, favorites, open pages, custom boards, and feeds;
- drafts, hiding rules, in-app usage statistics, and selected themes;
- cached threads, posts, images, thumbnails, and videos;
- website cookies, passcodes, and completed sessions;
- records of the user's posts and detected replies;
- downloads, local archives, edited images, and user-created backups.

Cache and temporary files are periodically limited or removed by the app and Android. Other data remains until the user deletes it, clears app data, signs out of the relevant account, removes the feature, or uninstalls the app. Files exported to Downloads or another selected folder must be deleted separately.

### 8. Android backup and manual export

Standard Android backup is enabled for the app. Ordinary settings, databases, and some sessions may therefore be included by Android in cloud backup or device transfer, depending on system settings and the backup provider. The separate private push store containing the installation secret and FCM token is excluded from these operations.

At the user's request, Slooop can also create a ZIP backup in Downloads. It may contain settings, history, favorites, custom feeds, filters, statistics, and themes. The user is responsible for storing, sharing, and deleting the exported file.

### 9. Diagnostics

Slooop does not upload diagnostic reports automatically. Crashes may create a local file containing the stack trace and technical information. A user may also manually enable Logcat or video-player diagnostics and then choose to send the resulting file to the developer.

Such reports may include the app and Android versions, device manufacturer and model, supported ABIs and decoders, timestamps, UI state, stack traces, and technical messages. Depending on the failure, general Logcat may contain URLs or other contextual data. Users should review a report before publishing it. Logs voluntarily received by the developer are used for troubleshooting and are not sold, but a file attached to a public issue becomes available to visitors of that platform.

### 10. Data Slooop intentionally does not collect

The current official version contains no code for advertising, sale of personal data, behavioral analytics, or automatic cloud crash reporting. Slooop does not request access to contacts, precise location, microphone, or camera, and it does not use the advertising ID, Android ID, or serial number as a user identifier.

This does not provide complete anonymity on the internet: websites, Firebase, GitHub, CDNs, identity providers, and internet providers may receive the IP address and other standard connection data.

### 11. Security and deletion

Local data is protected by Android's app sandbox and device security, but no storage or network connection can be guaranteed absolutely secure. Users can reduce stored data by:

- removing cookies or signing out of a website in Slooop settings;
- disabling push notifications or resetting the push identifier;
- clearing history, cache, favorites, and other data with app controls;
- clearing Slooop data in Android settings or uninstalling the app;
- separately deleting downloads, archives, logs, and backup files;
- disabling Android system backup if it is not wanted.

Requests concerning data voluntarily sent to the developer, such as a private diagnostic file, can be made through the contact method below. Requests concerning data sent to a third-party website or service must be directed to its operator.

### 12. Age and third-party content

Slooop is not specifically directed at children. Supported websites may contain 18+ content and impose their own age restrictions. Slooop does not verify or store the user's date of birth. Users are responsible for complying with website rules and applicable local law.

### 13. Changes to this policy

This policy is reviewed periodically and updated when the categories of data, processing purposes, recipients, or user controls change materially. Routine bug fixes and internal implementation changes that do not alter the described privacy behavior may not result in a separate policy revision. The latest revision date appears at the top of this document.

### 14. Contact

Privacy questions may be submitted through [GitHub Issues](https://github.com/andrewpozdnakov7-jpg/Dashchan_2/issues), without publishing cookies, tokens, passwords, or other secrets. Security vulnerabilities should be reported according to [SECURITY.md](SECURITY.md), not through a public issue.

---

## Русская версия

**Дата вступления в силу и последнего обновления: 1 сентября 2026 г.**

### 1. Область действия

Эта политика относится к официальным сборкам Android-приложения Slooop (пакет `io.dashchan2`) и описывает категории данных, которые приложение может обрабатывать, зачем это необходимо и кому данные могут передаваться.

Slooop — независимый некоммерческий клиент для сторонних форумов и других публичных сайтов. Проект не управляет этими сайтами и не отвечает за их правила конфиденциальности.

### 2. Кратко

- Slooop не требует создания отдельной учётной записи Slooop.
- В приложении нет рекламы, рекламных идентификаторов, встроенной аналитики поведения или автоматической отправки отчётов о сбоях разработчику.
- Основные запросы отправляются непосредственно выбранному пользователем сайту, его CDN или другому поставщику функции. Обычный просмотр форумов не проходит через сервер Slooop.
- Настройки, история, избранное, открытые страницы, черновики, cookies, кэш и другие рабочие данные в основном хранятся на устройстве.
- Необязательные серверные push-уведомления об ответах используют Google Firebase Cloud Messaging и сервер уведомлений Slooop только после явного согласия пользователя.
- Любой сайт или сетевой поставщик может видеть обычные технические данные соединения, включая IP-адрес, время запроса и HTTP-заголовки.

### 3. Данные, обрабатываемые при работе с поддерживаемыми сайтами

Когда пользователь открывает доску, тред, профиль, ленту, изображение или видео, Slooop передаёт соответствующему сайту данные, необходимые для запроса: адрес ресурса, идентификаторы сайта, доски, треда или сообщения, параметры сортировки и поиска, cookies и технические HTTP-заголовки.

Если сайт поддерживает действия пользователя, Slooop может по его явной команде передать этому сайту введённый текст, выбранные вложения, имя или другие заполненные поля, captcha-ответ, passcode либо авторизационную сессию. Возможности различаются между сайтами; некоторые интеграции доступны только для чтения.

Сайт и его инфраструктура самостоятельно определяют хранение полученных данных. Перед использованием учётной записи или отправкой материалов следует ознакомиться с правилами соответствующего сайта. Часть старых или пользовательских ресурсов может использовать незашифрованный HTTP; защита транспорта зависит от конкретного сайта и адреса.

### 4. Авторизация, cookies и WebView

Slooop может хранить в приватном хранилище приложения cookies, passcode, токены защиты от ботов или OAuth, имя авторизованного пользователя и готовые сессии поддерживаемых сайтов. Они используются для выполнения запросов к тому сайту, которому принадлежат, и не предназначены для передачи серверу Slooop. Если сайт использует OAuth, разрешения показываются на странице самого поставщика; Slooop получает выданный токен, а не пароль от этого сайта.

Авторизация Пикабу открывается во встроенном окне официального сайта `pikabu.ru` и разрешённых официальных поставщиков входа. Slooop не читает значения полей логина и пароля. После входа приложение определяет факт успешной авторизации, может сохранить отображаемое имя пользователя и сохраняет готовую cookie-сессию для последующих запросов к Пикабу. При выходе сохранённая сессия удаляется из приложения.

Встроенные веб-страницы могут использовать JavaScript, cookies, DOM-хранилище и сторонние cookies, когда это необходимо для авторизации, captcha или защиты сайта. Их обработка также регулируется политикой соответствующего сайта или поставщика входа.

### 5. Необязательные push-уведомления об ответах

В GitHub-версии пользователь может отдельно включить серверные push-уведомления. До принятия предупреждения Firebase не инициализируется для этой функции и регистрация на сервере Slooop не выполняется. F-Droid-версия не содержит этой интеграции.

После включения приложение создаёт случайный идентификатор установки и секрет, получает регистрационный токен Firebase и передаёт серверу уведомлений Slooop:

- случайный идентификатор установки, его секрет и FCM-токен;
- идентификатор приложения, версию и тип платформы;
- идентификаторы поддерживаемого сайта, доски, треда и отслеживаемого сообщения;
- технические идентификаторы подписки и события, необходимые для доставки и устранения повторов.

Для формирования уведомления могут обрабатываться идентификатор ответа, время и текст ответа. Серверная проверка предназначена только для явно поддерживаемых разделов и не получает пароль от форума, аккаунт Google, адрес электронной почты, Android ID, серийный номер или рекламный идентификатор устройства.

Секрет установки и FCM-токен хранятся в отдельном приватном файле, исключённом из Android cloud backup и переноса данных между устройствами. Регистрацию можно удалить отключением функции или сбросом идентификатора; неактивные серверные записи также ограничены операционным сроком хранения. Firebase может создавать собственный служебный идентификатор установки. Google и облачный хостинг сервера могут получать IP-адрес и стандартные технические данные запроса и журналов; проект не использует IP-адрес как учётную запись и намеренно не создаёт историю IP-адресов пользователей.

Локальная проверка ответов может работать независимо от Firebase. Для неё приложение хранит на устройстве идентификаторы собственных сообщений и ответов, текст, время, состояние проверки и признак прочтения.

### 6. Внешние инструменты и выбранные пользователем действия

Некоторые действия намеренно передают данные выбранному внешнему сервису:

- при обратном поиске изображения Google Lens или Яндекс получает URL изображения либо, для локального файла, подготовленную копию самого изображения;
- при открытии или отправке ссылки внешний браузер, мессенджер или другое выбранное приложение получает этот URL;
- при использовании команды «Поделиться» выбранное приложение получает переданный текст или файл;
- встроенные или устанавливаемые отдельно переводчики и системные AI-компоненты получают выбранный текст для обработки; встроенный перевод рассчитан на работу на устройстве, но загрузка моделей может обращаться к инфраструктуре поставщика;
- выбранные пользователем темы, шрифты, обои, языковые модели, дополнения и обновления могут загружаться с GitHub или других указанных публичных хранилищ.

Slooop не отправляет эти данные на сервер Slooop для рекламы или профилирования. Обработка внешним сервисом регулируется его собственной политикой.

### 7. Данные на устройстве

В зависимости от используемых функций приложение может сохранять:

- настройки приложения, включённые сайты и доски;
- историю, избранное, открытые страницы, пользовательские доски и ленты;
- черновики, правила скрытия, статистику использования внутри приложения и выбранные темы;
- кэшированные треды, сообщения, изображения, миниатюры и видео;
- cookies, passcode и готовые сессии сайтов;
- сведения о собственных сообщениях и найденных ответах;
- скачанные файлы, локальные архивы, отредактированные изображения и созданные пользователем резервные копии.

Кэш и временные файлы периодически ограничиваются или очищаются приложением и Android. Остальные данные сохраняются до удаления пользователем, очистки данных приложения, выхода из соответствующей учётной записи, удаления функции или деинсталляции. Файлы, экспортированные в Downloads или другую выбранную папку, нужно удалять отдельно.

### 8. Резервные копии Android и ручной экспорт

Для приложения разрешено стандартное резервное копирование Android. Поэтому обычные настройки, базы и некоторые сессии могут быть включены Android в cloud backup или перенос на новое устройство в зависимости от системных настроек и поставщика резервного копирования. Отдельное приватное push-хранилище с секретом установки и FCM-токеном из этих операций исключено.

По команде пользователя Slooop также может создать ZIP-резервную копию в папке Downloads. Она может содержать настройки, историю, избранное, пользовательские ленты, фильтры, статистику и темы. Пользователь самостоятельно отвечает за хранение, передачу и удаление экспортированного файла.

### 9. Диагностика

Slooop не загружает диагностические отчёты автоматически. Сбои могут создавать локальный файл со стеком ошибки и техническими сведениями; пользователь также может вручную включить запись Logcat или диагностику видеоплеера и затем самостоятельно отправить файл разработчику.

Такие отчёты могут содержать версию приложения и Android, производителя и модель устройства, поддерживаемые ABI и декодеры, временные отметки, состояние интерфейса, стек ошибки и технические сообщения. Обычный Logcat в зависимости от причины сбоя может содержать URL или другие контекстные данные. Перед публикацией отчёт следует просмотреть. Полученные от пользователя логи используются для диагностики и не продаются, но публично приложенный к issue файл становится доступен посетителям соответствующей площадки.

### 10. Что Slooop намеренно не собирает

В текущей официальной версии нет кода для рекламы, продажи персональных данных, поведенческой аналитики или автоматической облачной отчётности о сбоях. Slooop не запрашивает доступ к контактам, точному местоположению, микрофону или камере и не использует рекламный ID, Android ID или серийный номер как идентификатор пользователя.

Это не означает полной анонимности в интернете: сайты, Firebase, GitHub, CDN, поставщики входа и интернет-провайдеры могут получать IP-адрес и другие стандартные данные соединения.

### 11. Безопасность и удаление данных

Локальные данные защищены механизмами изоляции приложений и блокировки устройства Android, но ни одно хранилище или сетевое соединение нельзя считать абсолютно безопасным. Пользователь может уменьшить объём данных следующими способами:

- удалить cookies или выйти из учётной записи сайта в настройках Slooop;
- отключить push-уведомления или сбросить push-идентификатор;
- очистить историю, кэш, избранное и другие данные средствами приложения;
- очистить данные Slooop в системных настройках Android или удалить приложение;
- отдельно удалить скачанные файлы, архивы, логи и резервные копии;
- отключить системное резервное копирование Android, если оно не требуется.

Запросы о данных, которые пользователь добровольно отправил разработчику (например, приватный диагностический файл), можно направить через контакты ниже. Для удаления данных, переданных стороннему сайту или сервису, следует обращаться к его владельцу.

### 12. Возраст и сторонний контент

Slooop не предназначен специально для детей. Поддерживаемые сайты могут содержать материалы 18+ и устанавливать собственные возрастные ограничения. Slooop не проверяет и не сохраняет дату рождения пользователя; ответственность за соблюдение правил сайта и местного законодательства лежит на пользователе.

### 13. Изменения политики

Политика пересматривается периодически и обновляется, когда существенно меняются категории данных, цели обработки, получатели данных или доступные пользователю средства контроля. Обычные исправления ошибок и внутренние изменения реализации, не меняющие описанное поведение, могут не сопровождаться отдельной редакцией политики. Дата последнего обновления указана в начале документа.

### 14. Контакты

Вопросы о конфиденциальности можно создать в [GitHub Issues](https://github.com/andrewpozdnakov7-jpg/Dashchan_2/issues), не публикуя cookies, токены, пароли или иные секреты. Сообщения об уязвимостях следует отправлять по инструкции из [SECURITY.md](SECURITY.md), а не через публичный issue.
