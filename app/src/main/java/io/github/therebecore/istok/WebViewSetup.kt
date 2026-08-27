package io.github.therebecore.istok

import android.content.Context
import android.view.View
import android.webkit.CookieManager
import android.webkit.WebSettings
import android.webkit.WebView

/**
 * Приведение WebView в безопасное состояние - скилл `webview-security`, раздел 1.
 *
 * Главный принцип: значения по умолчанию у WebView небезопасны, и они менялись
 * от версии к версии Android. Поэтому здесь выставлено явно всё, что имеет значение,
 * даже там, где текущий default уже совпадает с нужным нам. Отсутствие явного
 * отключения - такая же ошибка, как явное включение.
 */
internal fun WebView.applySecureDefaults() {
    // Отладка содержимого страниц только в debug-сборке. С безусловным true любой
    // процесс с доступом к adb инспектирует страницы и выполняет на них произвольный JS.
    WebView.setWebContentsDebuggingEnabled(BuildConfig.DEBUG)

    settings.apply {
        // --- Выключаем: доступ к локальным ресурсам устройства (требования A-1, A-2) ---

        // file:// целиком. До API 30 default был true
        allowFileAccess = false
        // чтение других локальных файлов со страницы, открытой по file://
        @Suppress("DEPRECATION")
        allowFileAccessFromFileURLs = false
        // обход same-origin со страницы file:// - исторический вектор кражи локальных данных
        @Suppress("DEPRECATION")
        allowUniversalAccessFromFileURLs = false
        // content:// - доступ к провайдерам других приложений на устройстве. Default true
        allowContentAccess = false

        // --- Выключаем: то, что сайт не должен решать за пользователя ---

        // попапы без действия пользователя
        javaScriptCanOpenWindowsAutomatically = false

        // Вкладка одна (ADR-004). С false ссылки target="_blank" и window.open
        // открываются в этом же WebView - ровно то поведение, которое нам нужно,
        // и обработчик onCreateWindow при этом не требуется. С true они бы молча
        // перестали работать, пока кто-нибудь не создаст второе окно вручную
        setSupportMultipleWindows(false)
        // геолокация не нужна браузеру на телевизоре ни в каком виде (требование A-4)
        setGeolocationEnabled(false)
        // Сохранение введённого в формы. С API 26, то есть на всём нашем диапазоне,
        // настройка не делает ничего: платформа отдала эту работу системному сервису
        // автозаполнения, и содержимое полей страницы видно ему, а не нам. Строка
        // оставлена как явное «мы своего хранения форм не заводим»; про автозаполнение
        // см. принятый риск в SECURITY.md - решение пользователя от 2026-08-04.
        @Suppress("DEPRECATION")
        saveFormData = false

        // http-подресурсы внутри https-страницы. Отдельное подтверждение по домену
        // (ADR-011) на это не распространяется: там пользователь соглашается открыть
        // незащищённую страницу целиком и видит это, а подмешанный http-скрипт
        // на защищённой странице не виден никому и опаснее (требование B-1)
        mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW

        // --- Включаем ---

        // Без этих двух не работает большинство сайтов
        domStorageEnabled = true
        loadsImagesAutomatically = true

        // Мобильная версия сайта на телевизоре читается с трёх метров лучше десктопной
        // и весит меньше. Переключатель desktop/mobile - `applyPreferences`
        useWideViewPort = true
        loadWithOverviewMode = true
    }

    // Safe Browsing (A-3) и JavaScript выставляет `applyPreferences`: с Этапа 7 у них
    // есть выключатель. Здесь их нет намеренно - иначе значение стояло бы в двух местах,
    // и вопрос «какое из них настоящее» решался бы порядком вызовов.

    // Сторонние куки - это трекинг через сайты и лишний трафик на слабом канале
    CookieManager.getInstance().setAcceptThirdPartyCookies(this, false)
}

/**
 * То, что выбрал пользователь (Этап 7). Ложится **поверх** [applySecureDefaults]
 * и вызывается не только при создании WebView, но и при каждом переключении: настройка,
 * требующая перезапуска браузера, на телевизоре бесполезна.
 *
 * Здесь только то, что живёт в самом движке. Приватный режим сюда не входит - он про
 * то, что мы записываем у себя, и движка не касается вовсе.
 *
 * Автозаполнение выключается на уровне View: сама `WebSettings.saveFormData` с API 26
 * не делает ничего, содержимое полей платформа отдаёт системному сервису. Отказ
 * от него - это `IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS`: без `EXCLUDE_DESCENDANTS`
 * запрет не распространился бы на поля внутри страницы, то есть на всё, ради чего он
 * и ставится.
 */
internal fun WebView.applyPreferences(context: Context) {
    settings.javaScriptEnabled = Settings.isOn(context, Toggle.JAVASCRIPT)
    settings.safeBrowsingEnabled = Settings.isOn(context, Toggle.SAFE_BROWSING)
    importantForAutofill = if (Settings.isOn(context, Toggle.AUTOFILL)) {
        View.IMPORTANT_FOR_AUTOFILL_YES
    } else {
        View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS
    }
    // null возвращает строку платформы - именно так, а не запомненным «прежним значением»:
    // системный WebView обновляется отдельно от приложения, и сохранённая строка со временем
    // начала бы врать про версию движка. Действует со следующей загрузки страницы;
    // перезагружать текущую незачем - переключают её с нашего экрана настроек, а на сайт
    // пользователь уходит уже после.
    settings.userAgentString =
        if (Settings.isOn(context, Toggle.DESKTOP_UA)) desktopUserAgent(context) else null
}

/**
 * Десктопная строка User-Agent, собранная из настоящей.
 *
 * Целиком выдуманная строка не годится: версия движка в ней разойдётся с настоящей, и сайт
 * отдаст верстку либо для несуществующего браузера, либо для старого. Поэтому от платформы
 * берётся её же версия Chrome, а меняется только то, по чему сайт решает «мобильный»:
 * платформа в скобках и слово `Mobile`. Заодно уходит метка `wv`, по которой часть сайтов
 * узнаёт WebView и подсовывает урезанную версию.
 *
 * Платформа - Linux, а не Windows: Android и есть Linux, так что это наименьшее враньё
 * из возможных, а десктопную верстку по нему отдают все.
 *
 * Client Hints (`Sec-CH-UA-Mobile`) этой подменой не покрываются: в WebView их не выключить
 * без androidx.webkit, а зависимость ради одного заголовка мы не берём. На свежих движках
 * сайт, смотрящий на них, останется при мобильной верстке - записано в `BACKLOG.md`.
 */
private fun desktopUserAgent(context: Context): String {
    val stock = WebSettings.getDefaultUserAgent(context)
    val chrome = CHROME_VERSION.find(stock)?.value ?: "Chrome/120.0.0.0"
    return "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 " +
        "(KHTML, like Gecko) $chrome Safari/537.36"
}

private val CHROME_VERSION = Regex("""Chrome/[\d.]+""")

/**
 * Приоритет процесса рендерера. Вызывается из `onStart` и `onStop`, а не один раз
 * при настройке WebView.
 *
 * Задача прежняя: пока нас нет на экране, рендерер с его десятками мегабайт должен
 * уходить под нож раньше лаунчера телевизора. Изменился способ. Штатный флаг
 * "понижать, когда не виден" полагается на то, что движок сам отследит видимость -
 * и на System WebView 58 он этого не делает: замер `dumpsys activity oom` показал
 * рендерер в `cch-client-act` с adj 900 при активной Activity на переднем плане,
 * то есть первым в очереди на убийство ровно тогда, когда пользователь смотрит
 * страницу. Понижение однажды случалось и обратно не отыгрывалось.
 *
 * Видимость мы знаем и без движка, поэтому ставим приоритет сами и флаг не используем.
 *
 * @param visible виден ли WebView пользователю прямо сейчас
 */
internal fun WebView.applyRendererPriority(visible: Boolean) {
    val priority =
        if (visible) WebView.RENDERER_PRIORITY_IMPORTANT else WebView.RENDERER_PRIORITY_WAIVED
    setRendererPriorityPolicy(priority, false)
}
