<div align="center">

<picture>
  <source media="(prefers-color-scheme: dark)" srcset="docs/logo-dark.png">
  <img src="docs/logo-light.png" width="440" alt="ISTOK Browser">
</picture>

**Настоящий интернет на экране телевизора. Обычным пультом.**

Istok открывает полные версии сайтов, а не урезанные ТВ-заглушки: по экрану ходит
курсор, которым вы управляете стрелками пульта. 139 КБ, ноль сторонних библиотек,
ноль слежки.

[![License](https://img.shields.io/badge/license-Apache%202.0-blue)](LICENSE)
[![Android](https://img.shields.io/badge/Android-8.0%2B-brightgreen)](#установка)
[![APK](https://img.shields.io/badge/APK-139%20КБ-brightgreen)](#установка)
[![Dependencies](https://img.shields.io/badge/зависимостей-0-brightgreen)](#установка)

<img src="docs/screens/02-site.png" width="820" alt="Сайт открыт, курсор наведён на поле поиска">

</div>

---

## Возможности

- **Курсор вместо прыжков по ссылкам.** Ускоряется при удержании стрелки, прокручивает
  страницу упором в край экрана, тянет ползунки в плеерах и раскрывает выпадающие списки.
  Работает то, что на телевизоре обычно недоступно вовсе.
- **Своя экранная клавиатура.** Плотная раскладка под пульт: цифры удержанием клавиши,
  русская и латинская раскладки, вставка из буфера обмена.
- **Избранное, закладки и история** на домашней странице. Всё добавляется, правится
  и удаляется с пульта, без единого захода в настройки.
- **Полноэкранное видео** с плашкой, которая называет настоящий адрес плеера.
- **Настройки под себя:** приватный режим, отключаемый JavaScript, выбор поисковика,
  мобильная или десктопная версия сайтов, русский и английский интерфейс.
- **Обновления по воздуху.** Браузер сам находит новую версию на GitHub, проверяет
  подпись APK и предлагает установку. Закладки и настройки остаются на месте.

<table>
<tr>
<td width="50%"><img src="docs/screens/01-home.png" alt="Домашняя страница"><br><sub>Домашняя страница</sub></td>
<td width="50%"><img src="docs/screens/03-keyboard.png" alt="Экранная клавиатура"><br><sub>Экранная клавиатура</sub></td>
</tr>
<tr>
<td><img src="docs/screens/07-settings.png" alt="Настройки"><br><sub>Настройки</sub></td>
<td><img src="docs/screens/04-menu.png" alt="Меню"><br><sub>Меню</sub></td>
</tr>
</table>

## Установка

Android 8.0 (API 26) и новее. Страницы рисует системный Android System WebView,
поэтому весь браузер занимает 139 КБ и не замедляет телевизор. В Google Play его нет
и не будет.

1. Скачайте `istok-<версия>.apk` со страницы
   [Releases](https://github.com/therebecore/istok-browser/releases).
2. Разрешите установку из этого источника, когда телевизор спросит.
3. Дальше браузер обновляет себя сам.

> **"Приложение заблокировано для защиты устройства".** Play Защита показывает это
> предупреждение для любого APK мимо магазина. Нажмите "Подробнее", затем
> "Всё равно установить". Если такой кнопки нет, поставьте через `adb install`.

SHA-256 каждого выпуска опубликован на его странице релиза.

## Приватность

Ни аналитики, ни телеметрии, ни сборщика падений, ни рекламных SDK. Аккаунт не нужен,
своего сервера у проекта нет: история и закладки лежат только на вашем телевизоре.
Подробно в [PRIVACY.md](PRIVACY.md), модель угроз в [docs/SECURITY.md](docs/SECURITY.md),
сообщить об уязвимости через [.github/SECURITY.md](.github/SECURITY.md).

## Собрать из исходников

```bash
git clone https://github.com/therebecore/istok-browser.git
cd istok-browser
./gradlew assembleRelease
```

Нужен JDK 17 и Android SDK с платформой 37. Сборка воспроизводима: сторонних
зависимостей нет, версии проверяются `gradle/verification-metadata.xml`.

## Поддержать

Браузер бесплатный и без рекламы, поэтому развивается только на энтузиазме
и на вашей поддержке. Проще всего поддержать криптовалютой: QR-коды и адреса
кошельков есть прямо в браузере, в меню, раздел "Поддержать".

<img src="docs/screens/05-donate.png" width="640" alt="Экран поддержки с QR-кодами">

| Сеть | Адрес |
|---|---|
| **Bitcoin** | `bc1qz9wyg7vp3x6ecysvuzpxt24hc58u48p79rgu2a` |
| **Ethereum** | `0xd50c79e9E2f5A12B744B24f9c5B2a65cf34CE7b5` |
| **TON** (GRAM и USDT) | `UQCe04GZ2qYFK-rKlYOrETc3GNfpE5xqghXrXtmcZwnAW6U6` |
| **Tron** (USDT TRC-20) | `TToWoNkkMnoGfhz7mQwxD89BPHGiXs3RDy` |

Помогает и бесплатное: звезда репозиторию, подробный баг-репорт и рассказ о том,
как браузер ведёт себя на вашей модели телевизора.

## Лицензия

[Apache License 2.0](LICENSE).

---

<details>
<summary><b>In English</b></summary>

## Istok Browser

**The real web on your TV screen, driven by the remote you already have.**

Istok loads full desktop sites instead of stripped-down TV versions: an on-screen cursor
follows your remote's arrow keys. It weighs 139 KB, ships zero third-party dependencies
and collects nothing about you.

**Features:** cursor navigation with acceleration, edge scrolling and drag support inside
players and dropdowns; a built-in on-screen keyboard designed for a remote; favourites,
bookmarks and history on the home screen; fullscreen video with a plate naming the real
host; private mode, optional JavaScript, search engine choice and an English or Russian
interface; over-the-air updates from GitHub with APK signature verification.

**Install:** Android 8.0 (API 26) or newer. Download the APK from
[Releases](https://github.com/therebecore/istok-browser/releases) and allow installation
from that source. Play Protect warns about any sideloaded APK: tap "More details", then
"Install anyway". Pages are rendered by the system Android System WebView. Google Play
is deliberately out of scope.

**Privacy:** no analytics, no telemetry, no crash reporter, no ad SDK and no account.
The project runs no server of its own. Details in [PRIVACY.md](PRIVACY.md), threat model
in [docs/SECURITY.md](docs/SECURITY.md), vulnerability reports through
[.github/SECURITY.md](.github/SECURITY.md).

**Build:** `./gradlew assembleRelease` with JDK 17 and Android SDK platform 37.

**Support the project:** the browser is free and ad-free, so it lives on donations.
Wallet addresses are in the table above, and QR codes are built into the browser under
Menu, "Donate". A star or a detailed bug report helps just as much.

**License:** [Apache 2.0](LICENSE).

</details>
