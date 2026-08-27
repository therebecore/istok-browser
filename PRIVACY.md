# Privacy / Приватность

**Short version: Istok Browser has no analytics, no telemetry, no crash reporter and no
advertising SDK. Nothing about you is sent anywhere by the browser itself, and there is
no server of ours to send it to.**

Коротко: в браузере нет аналитики, телеметрии, сборщика падений и рекламных SDK. Своего
сервера у нас нет вовсе, поэтому и отправлять данные некуда. Это не упущение,
а продуктовое решение.

## What stays on your TV / Что остаётся на телевизоре

Everything the browser remembers lives in the app's own folder on the device, and dies
with the app when you uninstall it:

- bookmarks, favourites and the pinned row;
- browsing history;
- settings, including the interface language;
- cookies, cache and site storage - kept by the system WebView, not by us.

Всё это лежит в каталоге приложения и удаляется вместе с ним. «Очистить всё»
в настройках стирает то же самое немедленно.

**Backup is off** (`android:allowBackup="false"`). Without it, history and cookies could
be pulled off the device with `adb backup` by anyone with a cable.

## The three times the browser talks to someone else

Это единственные три случая, когда браузер обращается наружу сам.

### 1. The site you opened

Obvious, but worth stating: opening a page means talking to that site, and the site sees
what any site sees - your IP address, the user agent, the cookies it set itself.
Third-party cookies are blocked (`setAcceptThirdPartyCookies(false)`).

### 2. Google Safe Browsing - **can be turned off**

When "warn about dangerous sites" is on, the system WebView checks the address you are
opening against Google's database of phishing and malware sites. That check is done by
the WebView, not by us, and it means the address goes to Google.

Проверка адреса по базе Google - **единственное обращение наружу, которое можно
выключить в настройках**. Выключается оно целиком: адреса перестают уходить, но и
предупреждения о заражённых сайтах пропадают.

**Certificates are a different mechanism and are always checked.** The switch does not
touch them: a site with an invalid certificate does not open, and there is no
"continue anyway" button anywhere in this browser.

### 3. GitHub, to look for an update

The browser asks GitHub whether a newer version exists. The request carries what any
plain HTTPS request carries - your IP address and the fact that some Istok Browser
looked for an update. No identifier of your device, of your installation or of you
is sent, because none exists: the browser never generates one.

Автопроверка обновлений **выключается** в настройках («О браузере»). Кнопка «Проверить
сейчас» остаётся и работает по вашему нажатию.

## What the browser never does / Чего браузер не делает никогда

- No analytics, no telemetry, no crash reporting, no advertising SDK - **the app has zero
  third-party dependencies at all**, so there is nothing in it that could phone home
  without us noticing.
- No account, no sign-in, no sync, no cloud.
- No device identifier, no installation identifier, no advertising ID.
- No URL and no user input in the system log (requirement D-4). What you type and where
  you go does not reach `logcat`, where any app with the right permission could read it.
- No permissions beyond what is needed. The manifest asks for five, and every one of them
  has a written justification in `docs/ARCHITECTURE.md`.

## Where to check / Как проверить

The source is open and the build is reproducible from it. If you would rather verify
than believe:

- `app/src/main/AndroidManifest.xml` - the full list of permissions;
- `app/build.gradle.kts` - the dependency block, which is empty;
- `docs/SECURITY.md` - the threat model and every requirement with its status;
- `docs/ARCHITECTURE.md` - why each decision was made, including the ones we rejected.

Нашли расхождение между этим текстом и кодом - это уязвимость,
и сообщать о ней надо как об уязвимости: `.github/SECURITY.md`.
