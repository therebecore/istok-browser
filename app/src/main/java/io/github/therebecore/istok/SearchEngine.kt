package io.github.therebecore.istok

import android.annotation.SuppressLint
import android.content.Context
import android.content.res.Configuration
import java.util.Locale

/**
 * Поисковик, в который уходит ввод, не похожий на адрес (ADR-018).
 *
 * По умолчанию DuckDuckGo: он не строит профиль пользователя, а браузер не собирает
 * о нём вообще ничего - умолчание, которое молча отдавало бы каждый запрос
 * рекламной сети, противоречило бы этому.
 *
 * [id] хранится в настройках вместо порядкового номера: перестановка строк
 * в перечислении не должна менять чужой сохранённый выбор.
 */
internal enum class SearchEngine(val id: String, val template: String) {
    DUCKDUCKGO("duckduckgo", "https://duckduckgo.com/?q=%s"),
    GOOGLE("google", "https://www.google.com/search?q=%s"),
    // ya.ru, а не yandex.ru: с 2024 года поиск живёт на ya.ru, а yandex.ru отдаёт
    // редирект на портал. Лишний переход на телевизоре стоит заметно дороже.
    YANDEX("yandex", "https://ya.ru/search/?text=%s"),
}

/**
 * Язык интерфейса (ADR-058). Значений три, поэтому это не [Toggle]: кроме русского
 * и английского нужно состояние «как в системе» - оно умолчание и остаётся верным,
 * когда человек меняет язык телевизора.
 *
 * [tag] хранится на диске вместо порядкового номера - по той же причине, что
 * у [SearchEngine.id]. Пустая строка означает «не выбирал»; читать её как «система»
 * должен код, а не человек, поэтому подпись у неё своя.
 *
 * Названия языков лежат в ресурсах как непереводимые: «Русский» и «English» пишутся
 * одинаково в обеих локалях - язык в списке принято называть на нём самом, иначе тот,
 * кому список и нужен, своего языка в нём не найдёт.
 */
internal enum class Language(val tag: String, val label: Int) {
    SYSTEM("", R.string.lang_system),
    RUSSIAN("ru", R.string.lang_ru),
    ENGLISH("en", R.string.lang_en),
}

/**
 * Настройки-выключатели (Этап 7). Все они булевы, поэтому лежат одной таблицей:
 * ключ на диске и значение по умолчанию объявлены рядом, в одном месте на весь браузер.
 *
 * Умолчания выбраны так, чтобы браузер из коробки вёл себя как раньше, - настройка
 * даёт **возможность** изменить поведение, а не меняет его сама:
 *
 * - [AUTOFILL] включено: платформа отдаёт содержимое полей системному сервису
 *   автозаполнения, и на приставке им нередко оказывается вендорский. Это принятый риск
 *   (`SECURITY.md`, решение пользователя от 2026-08-04) - настройка выносит его наружу.
 * - [SAFE_BROWSING] включено: требование A-3. Выключается осознанно - Safe Browsing
 *   обменивается с Google хешами адресов, и человек вправе этого не хотеть.
 * - [PRIVATE] выключено: в приватном режиме не пишется ни история, ни «последний сайт».
 * - [JAVASCRIPT] включено: без него не работает большинство сайтов.
 * - [DESKTOP_UA] выключено: мобильная версия сайта на телевизоре читается с трёх метров
 *   лучше десктопной и весит меньше.
 * - [START_LAST] выключено: запуск открывает домашнюю страницу, а не последний сайт.
 *
 * Ключ - строка, а не порядковый номер: перестановка строк в перечислении не должна
 * менять чужой сохранённый выбор. Та же причина, что и у [SearchEngine.id]. Порядок же
 * задаёт вид экрана настроек - он строится перебором этого перечисления, поэтому наверху
 * приватность, ниже удобства.
 *
 * Подписи лежат здесь, рядом с ключом и умолчанием: добавить настройку - значит дописать
 * одну строку, а не править ещё и сборку экрана. [onLabel] и [offLabel] переопределяются
 * там, где «включено» и «выключено» ничего не сказали бы: у версии сайтов и стартовой
 * страницы это выбор из двух, а не тумблер.
 */
internal enum class Toggle(
    val key: String,
    val byDefault: Boolean,
    val label: Int,
    val hint: Int,
    val onLabel: Int = R.string.set_on,
    val offLabel: Int = R.string.set_off,
) {
    PRIVATE("private", false, R.string.set_private, R.string.set_private_hint),
    SAFE_BROWSING("safe_browsing", true, R.string.set_safe, R.string.set_safe_hint),
    UPDATES("updates", true, R.string.set_updates, R.string.set_updates_hint),
    AUTOFILL("autofill", true, R.string.set_autofill, R.string.set_autofill_hint),
    JAVASCRIPT("javascript", true, R.string.set_js, R.string.set_js_hint),
    DESKTOP_UA(
        "desktop_ua", false, R.string.set_ua, R.string.set_ua_hint,
        onLabel = R.string.set_ua_on, offLabel = R.string.set_ua_off,
    ),
    START_LAST(
        "start_last", false, R.string.set_start, R.string.set_start_hint,
        onLabel = R.string.set_start_on, offLabel = R.string.set_start_off,
    ),
    LISTS("lists", true, R.string.set_lists, R.string.set_lists_hint),
}

/**
 * Настройки на диске. Своего слоя настроек в проекте нет и заводить его незачем:
 * `SharedPreferences` - часть платформы и пишет асинхронно, то есть не трогает
 * главный поток.
 */
internal object Settings {

    fun isOn(context: Context, toggle: Toggle): Boolean =
        prefs(context).getBoolean(toggle.key, toggle.byDefault)

    fun setOn(context: Context, toggle: Toggle, on: Boolean) {
        prefs(context).edit().putBoolean(toggle.key, on).apply()
    }

    fun language(context: Context): Language {
        val saved = prefs(context).getString(KEY_LANGUAGE, null)
        return Language.entries.firstOrNull { it.tag == saved } ?: Language.SYSTEM
    }

    fun setLanguage(context: Context, language: Language) {
        prefs(context).edit().putString(KEY_LANGUAGE, language.tag).apply()
    }

    /**
     * Контекст с выбранным языком. Вызывается из `attachBaseContext` до того, как
     * появится хоть одна View: ресурсы выбираются один раз на создание Activity,
     * и подменять их позже - значит получить экран из двух языков сразу.
     *
     * [Locale.setDefault] здесь не для ресурсов, а для всего, что спрашивает язык
     * у платформы напрямую: разметка локальных экранов ставит его в атрибут `lang`.
     *
     * «Как в системе» не трогает ничего: платформа уже выбрала локаль сама, и наш
     * `setLocale` поверх неё только заменил бы, например, `ru-BY` на голый `ru`.
     *
     * Подавление lint: он предупреждает, что App Bundle раскладывает ресурсы по локалям
     * и скачивает их по требованию, поэтому свой переключатель языка в нём ломается.
     * Мы раздаём APK со страницы релиза - все языки лежат внутри него; Google Play
     * исключён решением пользователя от 2026-08-10.
     */
    @Suppress("AppBundleLocaleChanges")
    fun localized(base: Context): Context {
        val tag = language(base).tag
        if (tag.isEmpty()) return base
        val locale = Locale(tag)
        Locale.setDefault(locale)
        val config = Configuration(base.resources.configuration)
        config.setLocale(locale)
        return base.createConfigurationContext(config)
    }

    fun searchEngine(context: Context): SearchEngine {
        val saved = prefs(context).getString(KEY_SEARCH, null)
        return SearchEngine.entries.firstOrNull { it.id == saved } ?: SearchEngine.DUCKDUCKGO
    }

    fun setSearchEngine(context: Context, engine: SearchEngine) {
        prefs(context).edit().putString(KEY_SEARCH, engine.id).apply()
    }

    /**
     * Последний посещённый сайт для полосы внизу домашней страницы (ADR-025). Лежит
     * здесь, а не в логе истории, именно потому, что нужен **на старте**: ради одной
     * строки открывать весь лог значило бы вернуть себе ту самую цену, из-за которой
     * мы отказались от базы. `null` - истории ещё нет или её вычистили.
     */
    fun lastVisit(context: Context): SiteEntry? {
        val prefs = prefs(context)
        val url = prefs.getString(KEY_LAST_URL, null) ?: return null
        // Требование E-5, третье и последнее место. Записываем сюда только http и https,
        // но файл настроек на устройстве с доступом к отладке подменяется - и тогда
        // `javascript:alert(1)` печатался в полосе «последний сайт» внизу домашней
        // страницы, причём дважды: у такого адреса нет хоста, а подпись показывает
        // весь адрес, когда хоста нет. Открыть его браузер и так отказывался, но
        // показывать запись, которую мы отказываемся открыть, незачем. Проверено
        // аудитом Этапа 6, `.bench\sa6b-last.ps1`.
        if (!isWebUrl(url)) return null
        return SiteEntry(url, prefs.getString(KEY_LAST_TITLE, null) ?: "")
    }

    fun setLastVisit(context: Context, url: String?, title: String?) {
        prefs(context).edit()
            .putString(KEY_LAST_URL, url)
            .putString(KEY_LAST_TITLE, title)
            .apply()
    }

    /**
     * Забыть последний сайт при очистке данных - **синхронно**, в отличие от [setLastVisit].
     *
     * `apply()` отдаёт запись фоновому потоку `QueuedWork`, который система сбрасывает
     * на `onStop`, а его не будет: очистка снимает процесс (ADR-036). Полоса «последний
     * сайт» пережила бы чистку, и при включённом «открывать последний сайт» браузер
     * после перезапуска ушёл бы на стёртый адрес.
     *
     * Отсюда и подавление проверки lint: она права в общем случае и неправа здесь -
     * запись идёт ровно один раз на нажатие, и её незавершённость стоит дороже, чем
     * пара миллисекунд на главном потоке.
     */
    @SuppressLint("ApplySharedPref")
    fun clearLastVisit(context: Context) {
        prefs(context).edit()
            .putString(KEY_LAST_URL, null)
            .putString(KEY_LAST_TITLE, null)
            .commit()
    }

    /**
     * Когда в последний раз **удачно** спросили про новую версию (ADR-051). Ноль -
     * не спрашивали ни разу. Хранится здесь, а не в файле: строка, читаемая на старте.
     */
    fun lastUpdateCheck(context: Context): Long = prefs(context).getLong(KEY_UPDATE_CHECK, 0L)

    fun setLastUpdateCheck(context: Context, at: Long) {
        prefs(context).edit().putLong(KEY_UPDATE_CHECK, at).apply()
    }

    /**
     * Версия формата данных на диске (ADR-048). Метка одна на весь каталог приложения -
     * и файлы хранилища, и эти настройки. `0` - метки нет: так выглядят данные сборок
     * до Этапа 9, и их формат по определению первый.
     *
     * Значение читает и пишет только [Storage.migrate]; здесь оно живёт потому, что
     * все настройки живут здесь, а не потому, что относится к настройкам.
     */
    fun formatVersion(context: Context): Int = prefs(context).getInt(KEY_FORMAT, 0)

    fun setFormatVersion(context: Context, version: Int) {
        prefs(context).edit().putInt(KEY_FORMAT, version).apply()
    }

    private fun prefs(context: Context) =
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    private const val FILE = "settings"
    private const val KEY_FORMAT = "format_version"
    private const val KEY_UPDATE_CHECK = "update_checked_at"
    private const val KEY_SEARCH = "search_engine"
    private const val KEY_LANGUAGE = "language"
    private const val KEY_LAST_URL = "last_url"
    private const val KEY_LAST_TITLE = "last_title"
}
