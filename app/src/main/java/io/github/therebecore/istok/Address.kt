package io.github.therebecore.istok

import android.net.Uri
import java.net.IDN

/**
 * Разбор адреса для показа в панели (требования C-2 и C-4).
 *
 * Строка адреса - единственное место, где пользователь может понять, чей сайт перед ним,
 * и потому единственное, чему обязана верить защита от подделок. Отсюда два правила,
 * которым подчинён весь разбор.
 *
 * Первое: показывается **хост, а не адрес целиком**. Хост выделен, всё остальное -
 * путь, параметры, якорь - идёт тусклым и обрезается с конца. В длинном адресе легко
 * спрятать знакомое имя так, что строка целиком прочитается как адрес другого сайта:
 * `https://зловред.ru/sberbank.ru/login` отличается от настоящего банка ровно одним
 * местом, и это место должно быть заметно.
 *
 * Второе: адрес берётся у движка ([android.webkit.WebView.getUrl]), а не у страницы.
 * Страница не может ни задать его, ни закрасить панель собой: панель лежит вне WebView
 * и видна всегда (ADR-017). Единственное место, где панели на экране нет, -
 * полноэкранный режим (ADR-033), и там имя хозяина экрана показывает отдельная плашка
 * `MainActivity.showFullscreenHost` (требование C-4, ADR-035).
 */
internal enum class AddressKind {
    /** Наша собственная страница: заглушка старта, экран ошибки, подтверждение http. */
    LOCAL,

    /**
     * Документ, который не грузили ни мы, ни сеть: `blob:`, `filesystem:`, `data:`.
     *
     * Отдельно от [LOCAL] по требованию C-4. Пока «всё, что не http и не https» значило
     * «наша страница», чужой документ, дошедший до экрана мимо
     * [android.webkit.WebViewClient.shouldOverrideUrlLoading], представлялся бы строкой
     * «Страница браузера» - то есть подписывался бы нашим именем. Собственных страниц
     * у нас ровно один вид: `loadDataWithBaseURL` с пустым base, и адрес у них
     * `about:blank`. Всё остальное показывается своей схемой и без замка: незнакомое
     * слово в панели честнее знакомого.
     */
    UNKNOWN,

    /** http: соединение открыто и читается любым, кто имеет доступ к сети. */
    INSECURE,

    /**
     * https с действительным сертификатом. Оговорка «с действительным» здесь не лишняя
     * и не подразумевается схемой: замок означает защиту только потому, что в этом
     * браузере ошибка сертификата всегда отменяет загрузку (требование B-2, продолжения
     * не существует), а смешанное содержимое запрещено (`MIXED_CONTENT_NEVER_ALLOW`).
     */
    SECURE,
}

/** [host] показывается ярко, [rest] - тускло и обрезается с конца. */
internal class Address(val host: String, val rest: String, val kind: AddressKind)

/**
 * Настоящий сайт, у которого есть хост и происхождение. Только такой адрес попадает
 * в историю и закладки, и только к нему ведёт кнопка «домой»: у [AddressKind.UNKNOWN]
 * возвращать нечего - документ живёт в памяти движка и после ухода не существует.
 */
internal val AddressKind.isSite: Boolean
    get() = this == AddressKind.SECURE || this == AddressKind.INSECURE

private val LOCAL_ADDRESS = Address("", "", AddressKind.LOCAL)

internal fun parseAddress(url: String?): Address {
    if (url.isNullOrEmpty()) return LOCAL_ADDRESS

    val uri = Uri.parse(url)
    val scheme = uri.scheme?.lowercase()
    val kind = when (scheme) {
        "https" -> AddressKind.SECURE
        "http" -> AddressKind.INSECURE
        // Наши страницы грузятся с пустым base и потому всегда `about:blank`. Навигацию
        // главного фрейма на `about:` страница провести не может - схемы, кроме http
        // и https, отсекает `shouldOverrideUrlLoading` (требование C-3), - так что
        // этот адрес принадлежит нам по построению, а не по остаточному принципу.
        "about" -> return LOCAL_ADDRESS
        // Схема без хоста и без нашей загрузки: показывается она сама, ярко и целиком,
        // чтобы чужой документ нельзя было принять ни за сайт, ни за нашу страницу.
        else -> return Address(scheme?.plus(":") ?: "", "", AddressKind.UNKNOWN)
    }

    val host = uri.host?.lowercase()
    if (host.isNullOrEmpty()) return LOCAL_ADDRESS

    // userInfo в строку не попадает никогда. Такие адреса мы и не открываем (C-5),
    // но именно они существуют ради того, чтобы быть прочитанными как имя сайта.
    val shown = displayableHost(host) + if (uri.port >= 0) ":${uri.port}" else ""

    val rest = StringBuilder()
    rest.append(uri.encodedPath.orEmpty())
    uri.encodedQuery?.let { rest.append('?').append(it) }
    uri.encodedFragment?.let { rest.append('#').append(it) }

    return Address(shown, rest.toString(), kind)
}

/**
 * Требование C-2: имя домена в человеческом виде, но только когда его невозможно
 * прочитать как чужое.
 *
 * Движок отдаёт хост уже в punycode (`xn--80ak6aa92e.com`), и показывать так - безопасно,
 * но нечитаемо: владелец `президент.рф` ни в чём не виноват. Обратно в юникод строка
 * разворачивается только если весь домен написан **одним алфавитом**: смешение алфавитов -
 * это и есть homograph-атака, где `аpple.com` с кириллической первой буквой неотличим
 * от настоящего.
 *
 * Правило намеренно строже необходимого. Японские домены законно смешивают кандзи
 * с каной и покажутся в punycode - некрасиво, но безошибочно. Ослаблять это можно
 * только списком разрешённых сочетаний, а не догадками в коде.
 */
private fun displayableHost(host: String): String {
    if (!host.contains(PUNYCODE_PREFIX)) return host

    val unicode = try {
        IDN.toUnicode(host)
    } catch (_: IllegalArgumentException) {
        return host
    }
    return if (singleScript(unicode)) unicode else host
}

/**
 * Написан ли текст одним алфавитом. Цифры, точка и дефис нейтральны и есть в любом
 * домене. Всё, что не буква и не из этого списка, - повод оставить punycode: невидимые
 * пробелы и знаки-модификаторы в имени домена не нужны никому, кроме атакующего.
 */
private fun singleScript(text: String): Boolean {
    var script: Character.UnicodeScript? = null
    var i = 0
    while (i < text.length) {
        val point = text.codePointAt(i)
        i += Character.charCount(point)

        if (point == '.'.code || point == '-'.code || Character.isDigit(point)) continue
        if (!Character.isLetter(point)) return false

        val current = Character.UnicodeScript.of(point)
        if (script == null) script = current else if (script != current) return false
    }
    return true
}

/** Метка домена, закодированная в ASCII, начинается с этого - см. RFC 3492. */
private const val PUNYCODE_PREFIX = "xn--"

/**
 * Требование C-1: что делать с тем, что пользователь ввёл в адресную строку.
 * Возвращает адрес для загрузки либо `null`, если грузить нечего.
 *
 * Схема, если она указана, решает всё. Веб-схемы открываются как адрес, **любая другая
 * уходит в поиск как обычный текст**: `javascript:`, `data:`, `content:` и `file:` -
 * это не адреса, а способ выполнить что-то внутри самого браузера с его правами.
 * Такой ввод не блокируется молча и не показывает ошибку - он ищется, потому что
 * набирают его почти всегда не сами, а копируют из чужого сообщения, и человеку
 * полезнее увидеть, что это вообще такое.
 *
 * Без схемы решает форма: пробел или отсутствие домена - поиск, домен - адрес.
 * Схема к домену подставляется **https**, а не http: незащищённое соединение
 * в этом браузере требует отдельного согласия (ADR-011), и делать его умолчанием
 * для всего, что вводится руками, было бы обходом собственного правила.
 */
internal fun resolveInput(input: String, engine: SearchEngine): String? {
    val text = input.trim()
    if (text.isEmpty()) return null

    val scheme = SCHEME.find(text)?.groupValues?.get(1)?.lowercase()
    if (scheme != null) {
        return if (scheme == "http" || scheme == "https") text else search(text, engine)
    }

    if (text.any { it.isWhitespace() }) return search(text, engine)
    // IP-адрес - тоже адрес, хотя буквенной зоны в нём нет. Без этой ветки `192.168.1.1`
    // уходил бы в поиск, то есть до роутера, камеры и NAS из адресной строки было бы
    // не добраться - а именно ради них в ADR-011 существует подтверждение http.
    if (HOSTNAME.matches(text) || IPV4.matches(text)) return "https://$text"
    return search(text, engine)
}

private fun search(query: String, engine: SearchEngine): String =
    engine.template.replace("%s", Uri.encode(query))

/** Схема в начале строки по RFC 3986. */
private val SCHEME = Regex("^([a-zA-Z][a-zA-Z0-9+.\\-]*):")

/**
 * Похоже ли на имя сайта: имя, точка, буквенная зона, дальше необязательные порт и путь.
 * Зона именно буквенная - иначе адресом считалось бы любое число с точкой. Буквы берутся
 * юникодные: `президент.рф` вводят как есть, в punycode его превратит уже движок.
 */
private val HOSTNAME = Regex("^[\\p{L}\\p{N}._~\\-]+\\.\\p{L}{2,}(:\\d{1,5})?([/?#].*)?$")

/** Адрес в цифрах: локальные устройства домашней сети набирают именно так. */
private val IPV4 = Regex("^\\d{1,3}(\\.\\d{1,3}){3}(:\\d{1,5})?([/?#].*)?$")

/**
 * Ведёт ли адрес в домашнюю сеть (ADR-061).
 *
 * С targetSdk 37 такие обращения требуют отдельного разрешения: система считает, что
 * приложение, само по себе шарящее по домашней сети, узнаёт о человеке слишком много.
 * Для браузера это не так - в домашнюю сеть его ведёт сам человек, набирая
 * `192.168.1.1`, - но разрешение всё равно нужно, иначе соединение просто зависнет
 * до таймаута.
 *
 * Проверяется **литеральный адрес**, а не результат разрешения имени: узнать заранее,
 * куда указывает `router.home`, значит сходить в сеть - то есть сделать ровно то,
 * на что разрешения ещё нет. Имена в зоне `.local` считаются локальными по определению
 * (mDNS), остальные имена дойдут до сети и, если окажутся локальными, упрутся в отказ -
 * его показывает [BrowserWebViewClient].
 *
 * Петля (`127.0.0.1`, `localhost`, `::1`) локальной сетью не считается: она никуда
 * не выходит и разрешения не требует.
 */
internal fun isLocalNetworkHost(host: String?): Boolean {
    val name = host?.lowercase()?.trim('[', ']') ?: return false
    if (name.isEmpty()) return false
    if (name.endsWith(".local")) return true
    if (name.contains(':')) return isLocalIpv6(name)

    val parts = name.split('.')
    if (parts.size != 4) return false
    val octets = parts.map { it.toIntOrNull() ?: return false }
    if (octets.any { it !in 0..255 }) return false
    return when {
        octets[0] == 10 -> true                                  // 10.0.0.0/8
        octets[0] == 172 && octets[1] in 16..31 -> true           // 172.16.0.0/12
        octets[0] == 192 && octets[1] == 168 -> true              // 192.168.0.0/16
        octets[0] == 169 && octets[1] == 254 -> true              // 169.254.0.0/16, APIPA
        else -> false
    }
}

/** Уникальные локальные (`fc00::/7`) и канальные (`fe80::/10`) адреса IPv6. */
private fun isLocalIpv6(name: String): Boolean {
    val head = name.substringBefore('%').take(4)
    return head.startsWith("fc") || head.startsWith("fd") ||
        head.startsWith("fe8") || head.startsWith("fe9") ||
        head.startsWith("fea") || head.startsWith("feb")
}
