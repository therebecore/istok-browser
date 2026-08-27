# Security Policy / Политика безопасности

## Supported versions

Only the latest release is supported. Istok Browser ships as a single APK; fixes go into
the next release rather than into patches for older builds.

Поддерживается только последний выпуск. Исправления выходят следующей версией,
отдельных заплаток для старых сборок нет.

## Reporting a vulnerability / Как сообщить об уязвимости

**Use GitHub's private vulnerability reporting:** open the repository's **Security** tab
and press **Report a vulnerability**. The report stays private between you and the
maintainer until a fix is released.

**Пользуйтесь приватным каналом GitHub:** вкладка **Security** в репозитории, кнопка
**Report a vulnerability**. Сообщение видно только вам и сопровождающему до выхода
исправления.

Please do **not** open a public issue for a security problem - a public issue tells
everyone about the hole before there is a fix.

Пожалуйста, **не заводите публичный issue** по вопросам безопасности: он рассказывает
о дыре всем раньше, чем появляется исправление.

## What to include / Что приложить

- Version of the browser (Settings -> About) and Android version of the TV.
- What an attacker gains, not only what looks wrong.
- Steps to reproduce; a minimal page or APK if that is what triggers it.
- Whether it needs another app already installed on the device.

- Версия браузера (Настройки -> О браузере) и версия Android на телевизоре.
- Что получает атакующий, а не только что выглядит неправильно.
- Шаги воспроизведения; минимальная страница или APK, если дело в них.
- Нужно ли для этого другое приложение, уже стоящее на устройстве.

## Response times / Сроки

This is a one-person project, not a company. Expect:

- **first reply within 7 days**;
- an assessment (accepted / not a vulnerability / needs more information) **within 14 days**;
- a fix in the next release for anything accepted as High or above.

Проект ведёт один человек, а не компания. Ожидайте: **первый ответ в течение 7 дней**,
оценку в течение 14 дней, исправление в следующем выпуске для всего, что признано
высоким уровнем и выше.

If you get no reply within 14 days, the report was lost - please ping the same thread.

## Scope / Что в объёме

**In scope:** the browser code in this repository - URL handling, the WebView
configuration, local data, the on-air update mechanism (signature check, download,
installer handoff), the on-screen keyboard and cursor, and the release pipeline.

**Out of scope:**

- Vulnerabilities in Android System WebView itself - report those to Google; the browser
  renders pages with the system component and does not bundle an engine.
- Anything that requires physical access to an unlocked TV, or `adb` already enabled and
  authorised.
- Missing hardening that the project has declined on record - see the accepted risks in
  `docs/SECURITY.md`. Tell us if you can show the risk is worse than recorded; that is
  a valid report.
- Reports produced only by an automated scanner, with no working attack path.

**Вне объёма:** уязвимости самого System WebView (это к Google - свой движок мы
не встраиваем), всё, что требует физического доступа к разблокированному телевизору или
уже включённого `adb`, а также осознанно принятые риски из `docs/SECURITY.md`. Показать,
что принятый риск на деле хуже записанного, - **это годный отчёт**, присылайте.

## Disclosure / Раскрытие

Coordinated. Once a fix ships, the advisory is published with credit to the reporter
unless they ask otherwise.

Раскрытие согласованное: после выхода исправления публикуется advisory с упоминанием
того, кто сообщил, - если он не попросит об обратном.

## No bounty / Вознаграждения нет

There is no money in this project and no bug bounty. Credit in the advisory and in the
release notes is what can be offered.

Денег в проекте нет, программы вознаграждений тоже. Всё, что можно предложить, -
упоминание в advisory и в описании выпуска.
