package io.github.therebecore.istok

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.Process
import android.os.SystemClock
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.TextUtils
import android.text.style.ForegroundColorSpan
import android.view.Gravity
import android.view.KeyCharacterMap
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.view.inputmethod.InputConnection
import android.view.inputmethod.InputMethodManager
import android.webkit.CookieManager
import android.webkit.WebChromeClient
import android.webkit.WebStorage
import android.webkit.WebView
import android.webkit.WebViewDatabase
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.TextView
import android.window.OnBackInvokedDispatcher
import io.github.therebecore.istok.databinding.ActivityMainBinding
import java.io.File

/**
 * Единственная Activity приложения (ADR-008).
 *
 * WebView хранится отдельной ссылкой, а не берётся из layout каждый раз: после смерти
 * процесса рендерера старый экземпляр непригоден и заменяется новым, которого в layout
 * уже нет. Между смертью и заменой ссылка пуста.
 */
class MainActivity : Activity(), ScreenHost {

    private var root: FrameLayout? = null
    private var webView: WebView? = null
    private var cursor: CursorController? = null

    /**
     * Панель навигации. Хранится целиком биндингом: её элементы нужны из разных мест
     * и переживают пересоздание WebView, в отличие от самого WebView.
     */
    private var panel: ActivityMainBinding? = null

    /** Живёт ровно столько же, сколько текущий [webView]: у него хранятся разрешения http. */
    private var client: BrowserWebViewClient? = null

    /** Видны ли мы сейчас пользователю. Между `onStart` и `onStop`. */
    private var started = false

    /** Пользователь набирает адрес: пульт в этот момент принадлежит клавиатуре, не курсору. */
    private var editing = false

    /**
     * Что пользователь оставил в адресной строке, не отправив (B-90). Живёт до следующего
     * перехода: он и означает, что набранное больше не нужно.
     */
    private var unsentAddress: String? = null

    /** Адрес в домашней сети, который ждёт ответа на запрос разрешения (ADR-061). */
    private var pendingLocalUrl: String? = null

    /**
     * Спрашивали ли уже разрешение на домашнюю сеть в этом запуске (ADR-061). Нужно,
     * чтобы отличить «человек отказал» от «ещё не спрашивали»: до первого вопроса
     * система тоже говорит, что объяснять нечего.
     */
    private var localNetworkAsked = false

    /** Куда уходит ввод, не похожий на адрес (ADR-018). Читается с диска при старте. */
    private var engine = SearchEngine.DUCKDUCKGO

    /** Закладки, избранные и история на диске (ADR-025). */
    private var storage: Storage? = null

    /**
     * Наши собственные экраны: домашняя страница, списки, вопрос перед чисткой -
     * вместе с их состоянием и командами (`LocalScreens.kt`). Появляются вместе
     * с хранилищем и живут столько же.
     */
    private var screens: LocalScreens? = null

    /**
     * Страница, с которой ушли на домашнюю. Пока она есть и мы дома, кнопка «домой»
     * превращается в кнопку возврата: уход на домашнюю не должен означать потерю того,
     * что человек читал.
     */
    private var homeReturnUrl: String? = null

    /** Что сейчас нарисовано на кнопке «домой». `null` - ещё ничего. */
    private var homeShowsReturn: Boolean? = null

    /** Что сейчас нарисовано на кнопке «в закладки». `null` - ещё ничего. */
    private var bookmarkShowsSaved: Boolean? = null

    /**
     * View плеера, занявшего весь экран, и обратный вызов к нему (ADR-033).
     * `null` у обоих - полноэкранного видео сейчас нет.
     */
    private var fullscreenView: View? = null
    private var fullscreenCallback: WebChromeClient.CustomViewCallback? = null

    /**
     * Что переименовывают: номер записи и в каком списке. `-1` - поле закрыто.
     * Номер живёт ровно до подтверждения: список за это время измениться не может,
     * потому что весь наш интерфейс на это время занят полем ввода.
     */
    private var renameIndex = -1
    private var renameFavorite = false

    /**
     * Клавиатуру закрыл сам пользователь, а поле страницы фокус не отдало.
     *
     * Без этого признака закрыть её было невозможно, и найдено это не рассуждением,
     * а на живом сайте: в выпадающем списке фильтров DuckDuckGo клавиша «закрыть»
     * давала мерцание - клавиатура пропадала и мгновенно возвращалась, - а следующее
     * нажатие уже не срабатывало вовсе. Цепочка такая: [hideKeyboard] снимает
     * `FLAG_ALT_FOCUSABLE_IM`, окно снова обслуживается системным IME, движок тут же
     * просит соединение ввода для того же поля, приходит `onInputStarted` - и клавиатура
     * встаёт обратно. Этот подъём взводит гейт H-6, который по построению не засчитывает
     * клик, пока курсор не сдвинули после появления клавиатуры: второе нажатие в ту же
     * точку мёртвое.
     *
     * Поэтому просьбы страницы игнорируются, пока пользователь не покажет, что снова
     * хочет ввод: не коснётся страницы ([BrowserWebView.onTouched]), не откроет
     * клавиатуру сам ([showKeyboard]) и не уйдёт на другую страницу. Гейт H-6 при этом
     * остаётся на месте - он про другое: про клик, подсунутый под курсор.
     */
    private var keyboardDismissed = false

    /**
     * «Пользователь закрыл клавиатуру сам» - одно состояние, но хранится в двух местах:
     * здесь оно решает, поднимать ли клавиатуру по просьбе страницы, а
     * [BrowserWebView.inputSuppressed] - выдавать ли движку соединение ввода (без него
     * системе нечего обслуживать и своей клавиатуры она не покажет). Ставить и снимать
     * их врозь нельзя, и это была настоящая находка круга Этапа 6: снимались они
     * в разных местах, `inputSuppressed` переживал уход на другую страницу - и после
     * одного закрытия клавиатуры автоподъём переставал работать на всех страницах,
     * открытых **не кликом**: адресной строкой, кнопкой «домой», плиткой, возвратом назад.
     */
    private fun setInputDismissed(dismissed: Boolean) {
        keyboardDismissed = dismissed
        (webView as? BrowserWebView)?.inputSuppressed = dismissed
    }

    /**
     * Что уже записано в историю для текущей страницы. Момент «страница открылась»
     * приходит несколькими колбэками подряд, а заголовок к первому из них ещё не готов:
     * без этой пары одна страница дала бы в логе две записи - с пустым заголовком
     * и с настоящим.
     */
    private var visitedUrl: String? = null
    private var visitedTitle: String? = null

    /** Текст для экрана, который встанет вместо умершей страницы. `null` - восстанавливать нечего. */
    private var restoreMessage: Int? = null

    /**
     * Адрес, который был открыт в момент, когда движок отпустили ради памяти телевизора
     * (ADR-019). `null` - там была наша собственная страница, её возвращать неоткуда
     * и незачем.
     */
    private var releasedUrl: String? = null

    /** Адрес, который сейчас написан в панели. Нужен, чтобы не перерисовывать её зря. */
    private var shownUrl: String? = null

    /**
     * Раскладка платформы для символов, у которых есть код клавиши. `load` ходит
     * в системную службу, а нужна карта на каждое нажатие - берём её один раз.
     */
    private val keyMap by lazy { KeyCharacterMap.load(KeyCharacterMap.VIRTUAL_KEYBOARD) }

    /**
     * Язык интерфейса (ADR-058) применяется здесь: ресурсы Activity выбираются один раз,
     * до создания первой View, и подменить их позже - значит получить экран, собранный
     * из двух языков сразу.
     */
    override fun attachBaseContext(base: Context) {
        super.attachBaseContext(Settings.localized(base))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        hideOverlayWindows()

        // System WebView - отдельный пакет, который может обновляться через Play Store,
        // быть отключённым или вовсе отсутствовать в урезанной прошивке приставки.
        // В эти моменты инфляция layout с WebView бросает исключение, и без обработки
        // приложение падает до первого кадра. На дешёвых ТВ это массовый сценарий.
        val inflated = try {
            ActivityMainBinding.inflate(layoutInflater)
        } catch (_: Exception) {
            setContentView(errorView())
            return
        }

        setContentView(inflated.root)
        root = inflated.root
        panel = inflated
        cursor = CursorController(
            area = inflated.root,
            cursor = inflated.cursor,
            // В полноэкранном режиме страница живёт не в самом WebView, а в View,
            // которую отдал движок: сам WebView в это время GONE. Пока наведение шло
            // в него, страница в полноэкранном не получала ни одного `mousemove` -
            // то есть панель плеера, которая показывается по движению указателя,
            // с пульта не появлялась вовсе (`B-65`, найдено 2026-08-12).
            page = { fullscreenView ?: webView },
            onMoved = { x, y ->
                if (keyboardOpen()) inflated.keyboard.highlightAt(x, y)
                if (renameIndex >= 0) highlightRenameButtons(x, y)
            },
            // Требование H-7a: всё, что лежит поверх страницы, перечислено здесь и только
            // здесь. Новый оверлей добавляется в эту строку - иначе он потечёт наведением
            // и прокруткой так же, как течела клавиатура до H-7.
            //
            // Подложка меню накрывает экран целиком, поэтому координаты ей не нужны.
            // Клавиатура занимает всю ширину, поэтому её верхней границы достаточно:
            // всё, что ниже, принадлежит ей, а не странице.
            //
            // Поле переименования занимает экран не целиком, но пока оно открыто, странице
            // под ним ни наведение, ни клик не нужны: править название и одновременно
            // тыкать в страницу нельзя.
            //
            // Полоса обновления (ADR-051) занимает не всю ширину, но глушится полоса
            // во всю ширину экрана: она висит внизу, живёт недолго и убирается кнопкой
            // «Позже», а точные границы узкой плашки стоили бы кадрового бюджета
            // на каждом движении курсора.
            //
            // **Подсказка режима прокрутки сюда не входит, и это разобрано** (`B-154`).
            // Она висит внизу - ровно там, куда упирают курсор, чтобы листать краем.
            // Внеси её в список - и `drag()` выходил бы по `overOwnUi` на каждом ходу:
            // прокрутка вниз перестала бы работать в том самом режиме, который для неё
            // и заведён. Утечка наведения здесь мала своим чередом: в режиме прокрутки
            // `sendHover` почти всегда выходит раньше, по `dragging`.
            overOwnUi = { _, y ->
                menuOpen() || renameIndex >= 0 || (keyboardOpen() && y >= inflated.keyboard.top) ||
                    (inflated.updateBar.visibility == View.VISIBLE && y >= inflated.updateBar.top)
            },
            // Удержание имеет смысл только на клавиатуре: там оно даёт цифру (ADR-034).
            // Везде остальном нажатие остаётся мгновенной парой DOWN и UP - страница
            // не должна получать долгих касаний оттого, что пользователь замешкался
            // с пультом.
            holdArea = { _, y -> keyboardOpen() && y >= inflated.keyboard.top },
            // Подсказка режима прокрутки (ADR-046). Висит всё время режима: он модальный,
            // и без подписи замерший курсор читается как зависший браузер.
            scrollHint = { on ->
                inflated.scrollHint.visibility = if (on) View.VISIBLE else View.GONE
            },
            probe = ::probeScroller,
        )
        registerBackHandler()

        val web = inflated.webView
        webView = web
        configure(web)

        // Кнопка «назад» на панели не закрывает приложение, в отличие от кнопки пульта:
        // на панели это средство ходить по истории, и выход из браузера нажатием
        // в углу экрана был бы неожиданностью.
        inflated.back.setOnClickListener { webView?.let { web -> client?.goBack(web) } }
        inflated.forward.setOnClickListener { webView?.let { web -> client?.goForward(web) } }
        inflated.reload.setOnClickListener { webView?.let { web -> client?.reload(web) } }
        inflated.home.setOnClickListener { onHomeButton() }

        inflated.address.setOnClickListener { startEditing() }
        // Поле не просит системную клавиатуру вовсе - ввод идёт только через нашу
        // (ADR-023). Как и у поля переименования, свойство ставится кодом: XML-атрибута
        // с таким именем нет.
        //
        // Починкой `B-43` эта строка не является, хотя стоит рядом с ним по смыслу:
        // на живом Яндекс ТВ клавиатура телевизора вставала поверх нашей и при ней.
        // Запись открыта и ждёт ручной приёмки на Этапе 9 - эмулятор дефект
        // не воспроизводит.
        inflated.address.showSoftInputOnFocus = false
        // Клик мимо строки - по странице или по кнопке панели - означает, что ввод
        // передумали. Оставлять поле в режиме правки после этого нельзя: пульт продолжал
        // бы уходить в клавиатуру, а не курсору.
        inflated.address.setOnFocusChangeListener { _, focused -> if (!focused) stopEditing() }

        inflated.keys.setOnClickListener { if (keyboardOpen()) closeInput() else showKeyboard() }
        inflated.keyboard.onChar = CharInput(::typeChar)
        inflated.keyboard.onAction = ::onKeyAction
        // Гейт H-6: в режиме пульта клавиша срабатывает от `performClick`, то есть мимо
        // проверки внутри курсора. Курсора может не быть только до его создания, когда
        // и клавиатуры на экране ещё нет.
        inflated.keyboard.inputAllowed = CursorGate { cursor?.inputAllowed() != false }
        // В режиме пульта стрелки принадлежат клавиатуре, и курсор в это время
        // не двигается: висящая поверх клавиш стрелка только мешала бы целиться.
        inflated.keyboard.onModeChanged = ModeSwitch { dpad -> if (dpad) cursor?.stop() }

        inflated.bookmark.setOnClickListener { toggleBookmark() }
        inflated.menu.setOnClickListener { showMenu() }
        inflated.menuScrim.setOnClickListener { hideMenu() }
        // Клик мимо поля - то же, что BACK: название остаётся прежним.
        inflated.renameScrim.setOnClickListener { stopRename() }
        inflated.renameCancel.setOnClickListener { stopRename() }
        // Клавиатуру можно убрать и вернуть нажатием на само поле. Системная при этом
        // не всплывает: поле её не просит вовсе - без этого клик по полю поднимал
        // клавиатуру телевизора мимо курсора (ADR-023). Свойство ставится кодом:
        // XML-атрибута с таким именем в платформе нет.
        inflated.rename.showSoftInputOnFocus = false
        inflated.rename.setOnClickListener { if (renameIndex >= 0) showKeyboard() }
        inflated.renameOk.setOnClickListener { commitRename() }
        inflated.menuBookmarks.setOnClickListener { screens?.openList(bookmarks = true) }
        inflated.menuHistory.setOnClickListener { screens?.openList(bookmarks = false) }
        inflated.menuSettings.setOnClickListener { screens?.showSettings() }
        inflated.menuAbout.setOnClickListener { screens?.showAbout() }
        inflated.menuDonate.setOnClickListener { screens?.showDonate() }
        inflated.menuSearch.setOnClickListener { cycleEngine() }
        // Выход (B-87). Без подтверждения - причина у пункта в разметке.
        inflated.menuExit.setOnClickListener { finish() }

        engine = Settings.searchEngine(this)
        val store = Storage(this)
        storage = store
        screens = LocalScreens(this, store, this)
        markSelectedEngine()
        updatePanel()

        // Обновление по воздуху (ADR-051). Спрашивает не чаще раза в сутки и молчит,
        // если новой версии нет или проверка выключена в настройках.
        Updates.check(this) { info -> showUpdate(info) }

        // Процесс могли убить, пока браузер был в фоне: тогда возвращаем ту же страницу,
        // а не начинаем с чистого листа (ADR-019).
        val saved = savedInstanceState?.getString(STATE_URL)
        if (saved != null) client?.load(web, saved) else openStartPage(web)
    }

    /**
     * С чего начинается запуск (настройка «стартовая страница», Этап 7).
     *
     * Вариантов два, и произвольного адреса среди них нет намеренно: ввод адреса тянет
     * за собой клавиатуру и проверку схемы ради настройки, которую меняют один раз.
     * Последний сайт мы и так знаем - он лежит в настройках рядом (ADR-025) и проверен
     * по схеме при чтении (E-5), так что открывать его безопасно. Нет его - домашняя.
     */
    private fun openStartPage(web: WebView) {
        val last = if (Settings.isOn(this, Toggle.START_LAST)) Settings.lastVisit(this) else null
        if (last != null) client?.load(web, last.url) else screens?.showHome()
    }

    // --- ScreenHost: всё, что локальным экранам нужно от окна ------------------------
    //
    // Граница проведена здесь намеренно. За ней (`LocalScreens.kt`) - сами страницы,
    // их состояние и команды; тут остаётся то, что принадлежит окну: живой WebView,
    // согласие на http, курсор, меню и поле правки названия.

    override fun currentPage(): WebView? = webView

    override fun forgetPendingConsent() = client?.forgetPending() ?: Unit

    override fun showNotice(text: String) = showQuietNotice(text)

    override fun onDecisionScreen() {
        cursor?.onDecisionScreen()
    }

    override fun openUrl(url: String) {
        val web = webView ?: return
        // Переход состоялся - недонабранное больше не нужно (B-90).
        unsentAddress = null
        client?.load(web, url)
    }

    /**
     * Домашняя сеть: можно ли грузить этот адрес прямо сейчас (ADR-061).
     *
     * До Android 17 разрешения не существует, и обращения внутрь дома идут по одному
     * только `INTERNET`. С targetSdk 37 их нужно спрашивать, иначе соединение молча
     * висит до таймаута - ни ошибки, ни объяснения.
     *
     * Спрашиваем **по поводу**: человек уже набрал адрес роутера, и системный вопрос
     * приходит в понятный момент. Отказ запоминает сама система; повторная попытка
     * вопрос не задаст, а сразу покажет экран отказа.
     */
    private fun allowLocalNetwork(url: String): Boolean {
        if (Build.VERSION.SDK_INT < ANDROID_17) return true
        if (checkSelfPermission(LOCAL_NETWORK) == PackageManager.PERMISSION_GRANTED) return true

        // Система больше не показывает вопрос: человек отказал совсем. Спрашивать
        // нечего - объясняем и не грузим.
        if (!shouldShowRequestPermissionRationale(LOCAL_NETWORK) && localNetworkAsked) {
            webView?.let { web -> client?.showNoLocalNetwork(web, url) }
            return false
        }

        pendingLocalUrl = url
        localNetworkAsked = true
        requestPermissions(arrayOf(LOCAL_NETWORK), REQ_LOCAL_NETWORK)
        return false
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray,
    ) {
        if (requestCode != REQ_LOCAL_NETWORK) {
            super.onRequestPermissionsResult(requestCode, permissions, grantResults)
            return
        }
        val url = pendingLocalUrl ?: return
        pendingLocalUrl = null
        val granted = grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED
        val web = webView ?: return
        if (granted) client?.load(web, url) else client?.showNoLocalNetwork(web, url)
    }

    /**
     * Настройка переключена - движок узнаёт об этом сейчас, а не при следующем запуске.
     * Настройка, требующая перезапуска браузера, на телевизоре бесполезна: пользователь
     * не поймёт, почему ничего не изменилось, и переключит обратно.
     */
    override fun onPreferencesChanged() {
        webView?.applyPreferences(this)
    }

    /**
     * Язык выбран - Activity пересоздаётся (ADR-058). Тем же способом применяет смену
     * языка и сама система, когда его меняют в настройках телевизора.
     *
     * Цена известна и принята: открытая страница закрывается вместе со списком «назад»,
     * человек оказывается там, откуда браузер начинается. Закладки, избранное, история
     * и сами настройки лежат на диске и переезд переживают.
     */
    override fun restartForLanguage() {
        recreate()
    }

    /**
     * Кнопка «Проверить сейчас» в настройках (ADR-055).
     *
     * Найденное обновление показывается той же полосой, что и при автоматической
     * проверке, - второго пути установки заводить незачем; строка настроек при этом
     * говорит, что версия найдена, потому что полоса живёт на панели и с экрана
     * настроек её видно не всегда.
     */
    override fun checkUpdatesNow() {
        Updates.checkNow(this) { info, ok ->
            val text = when {
                info != null -> {
                    showUpdate(info)
                    getString(R.string.update_available, info.versionName)
                }
                ok -> getString(R.string.update_none)
                else -> getString(R.string.update_check_failed)
            }
            screens?.showUpdateStatus(text)
        }
    }

    /**
     * Что у страницы под курсором: есть ли там своя прокручиваемая область и куда ей
     * ещё есть куда ехать (`B-93`, ADR-038). Спрашивается коротким скриптом, потому что
     * другого способа узнать про содержимое чужого документа у приложения нет: платформа
     * отдаёт только тип элемента (`getHitTestResult`), а нам нужны границы.
     *
     * Ответ - девять чисел (границы, четыре признака и «чужая вставка»), больше ничего
     * со страницы не берётся. Контракт один на два места: [scrollerScript] его составляет,
     * [CursorController.setScroller] разбирает - менять только парой.
     * Он **недоверенный**, и это учтено: страница может назвать своими границами весь
     * экран, и тогда курсор упрётся там, где списка нет. Цена ошибки - одно нажатие:
     * границы забываются на каждом новом (`askScroller`), а признак «ехать некуда»
     * освобождает курсор сразу. **На наш собственный UI враньё не распространяется
     * вовсе** (`B-119`): [CursorController.insideScroller] отбрасывает границы страницы,
     * как только курсор оказался на нашей клавиатуре или панели.
     *
     * Скрипт наружу ничего не выносит: `evaluateJavascript` - это разовое выполнение
     * с ответом в колбэк, а не мост между страницей и приложением. Запрет ADR-009
     * на `addJavascriptInterface` остаётся в силе - там страница получает **наши**
     * методы, здесь мы получаем одну строку.
     */
    private fun probeScroller(x: Float, y: Float) {
        val cursor = cursor ?: return
        val web = webView ?: return
        if (!Settings.isOn(this, Toggle.LISTS)) return
        // Полноэкранное видео листать нечем и незачем: `page()` там отдаёт View плеера.
        if (fullscreenView != null) return
        if (web.width == 0 || web.height == 0) return
        // Точка передаётся долей от окна, а не в пикселях, и границы возвращаются так же.
        // Пиксель страницы и пиксель экрана - разные величины (здесь один к двум),
        // а множитель между ними знает только движок: `WebView.getScale` отдаёт единицу
        // там, где страница считает в половинном масштабе, и точка уезжала за край
        // документа - скрипт находил под ней пустоту и список не listался вовсе.
        // Доля же не зависит ни от масштаба, ни от плотности экрана.
        val fractionX = (x - web.left) / web.width
        val fractionY = (y - web.top) / web.height
        web.evaluateJavascript(scrollerScript(fractionX, fractionY)) { answer ->
            val parts = answer?.trim('"', ' ')?.split(",") ?: return@evaluateJavascript
            if (parts.size != 9) {
                cursor.clearScroller()
                return@evaluateJavascript
            }
            val numbers = parts.map { it.toFloatOrNull() ?: return@evaluateJavascript }
            cursor.setScroller(
                left = numbers[0] * web.width + web.left,
                top = numbers[1] * web.height + web.top,
                right = numbers[2] * web.width + web.left,
                bottom = numbers[3] * web.height + web.top,
                up = numbers[4] != 0f,
                down = numbers[5] != 0f,
                leftward = numbers[6] != 0f,
                rightward = numbers[7] != 0f,
                unknown = numbers[8] != 0f,
            )
        }
    }

    /**
     * Скрипт для [probeScroller]. Ищет ближайшего предка точки, у которого своя полоса
     * прокрутки, и отдаёт его границы и стороны, куда ему ещё есть куда ехать.
     *
     * До `body` не доходит намеренно: прокрутка самого документа - это край экрана,
     * она работала и до этой правки. Ответ - строка из девяти чисел или пустая строка;
     * любая другая - для нас то же самое, что пустая.
     */
    /**
     * Постоянная часть скрипта лежит константами, а не собирается литералом на каждом
     * опросе: подставляются ровно две доли, и склейка стоит четырёх присоединений вместо
     * полутора килобайт мусора на вызов (`B-127`). На кадровый путь опрос не попадает -
     * он прорежен, - но при удержании внутри списка вызовы идут пачкой.
     */
    private fun scrollerScript(fractionX: Float, fractionY: Float): String =
        SCROLLER_HEAD + fractionX + "*w," + fractionY + "*h" + SCROLLER_TAIL

    /**
     * Очистка данных движка (требование E-3). Историю и «последний сайт» стирает
     * `Storage` - это наше, а здесь всё, что накопил WebView.
     *
     * Чистится каждым способом, каким платформа это позволяет, и списком, а не одним
     * вызовом: единой кнопки «забыть всё» у WebView нет, а у каждого хранилища свой
     * владелец. [WebStorage] - это localStorage, sessionStorage и IndexedDB страниц;
     * [WebViewDatabase] - сохранённое в формах и пароли HTTP-авторизации, они переживают
     * даже удаление кэша; `clearHistory` - список «назад», иначе кнопка на панели вернула
     * бы на сайт, с которого пользователь только что вышел.
     *
     * `removeAllCookies` асинхронный, и `flush` после него не формальность: без него
     * пустые куки остаются только в памяти, а на диске лежат прежние - выключение
     * телевизора в этот момент вернуло бы вход на сайты.
     */
    override fun wipeEngineData() {
        CookieManager.getInstance().apply {
            removeAllCookies(null)
            flush()
        }
        WebStorage.getInstance().deleteAllData()
        WebViewDatabase.getInstance(this).apply {
            clearFormData()
            clearHttpAuthUsernamePassword()
        }
        webView?.apply {
            clearCache(true)
            clearFormData()
            clearHistory()
        }
        wipeEngineDirs()
        // Закрытие браузера - часть чистки, а не отдельный шаг вызывающего (ADR-036).
        // Стоит здесь, чтобы порядок «сначала стереть, потом закрыть» нельзя было
        // разорвать: всё, что живёт в памяти процесса, - согласия на http, адрес возврата,
        // последний переход - исчезает вместе с ним, и отдельно обнулять их незачем.
        closeBrowser()
    }

    /**
     * Хранилища движка, до которых вызовы очистки не достают, - удаляются файлами
     * (ADR-036).
     *
     * Service Worker и его Cache Storage не покрыты вообще ничем: [WebStorage] знает про
     * localStorage, sessionStorage и IndexedDB, `clearCache` - про сетевой кэш,
     * а установленный сайтом воркер не входит ни туда, ни туда. Проверено стендом
     * `.bench\sa7-store.ps1`: после «Очистить всё» страница по-прежнему видела свой воркер
     * и содержимое своего кэша.
     *
     * Остальное в списке - по другой причине, из-за того, что после очистки
     * браузер закрывается. `deleteAllData` только ставит работу в очередь движка,
     * и снятый процесс отменяет её на полпути: тем же стендом localStorage пережил чистку,
     * хотя до закрытия браузера исправно исчезал. Удаление файлами не зависит от того,
     * успел движок до своей смерти или нет.
     *
     * Путь берётся у платформы (`getDir("webview")`), а не пишется строкой: это тот же
     * каталог, который движок создаёт себе сам. Результат [deleteTree] не проверяется
     * намеренно: бросить она не может, а чистка идёт списком - остановка на одном пункте
     * означала бы не выполнить остальные.
     */
    private fun wipeEngineDirs() {
        val webviewDir = getDir(WEBVIEW_DIR, MODE_PRIVATE)
        for (name in WIPED_DIRS) {
            deleteTree(File(webviewDir, name))
        }
        // Сетевой кэш лежит не у движка, а в общем кэше приложения, и хранит адреса
        // и тела просмотренных страниц - самое читаемое из всего, что чистка обещает
        // удалить. Аудит Этапа 7 поймал здесь отказ: `clearCache(true)` тоже асинхронный,
        // и в одном прогоне из пяти не удалилось ни одного файла.
        deleteTree(File(cacheDir, ENGINE_CACHE_DIR))
    }

    /**
     * Снести каталог со всем содержимым.
     *
     * Своя рекурсия вместо `File.deleteRecursively` из stdlib (`B-68`): та тянет в dex
     * восемь классов семейства `FileTreeWalk` - 15 методов и 780 Б кода ради обхода,
     * который здесь занимает пять строк. Умеет она при этом ровно то же: у нас нет
     * ни символьных ссылок, ни обхода в глубину с обратными вызовами - обычные каталоги
     * движка на своём же разделе.
     */
    private fun deleteTree(file: File) {
        file.listFiles()?.forEach { deleteTree(it) }
        file.delete()
    }

    /**
     * Закрыть браузер - последний шаг очистки (ADR-036).
     *
     * Удаления файлов мало: воркер, зарегистрированный в этой сессии, живёт в памяти
     * движка, пока жив процесс, - проверено `.bench\sa7-store.ps1` (страница видела свой
     * воркер после чистки, и переставала после перезапуска). Единственный способ снять
     * его с публичным API - начать новый процесс.
     *
     * Процесс снимается явно, а не одним `finishAndRemoveTask`: система держит пустой
     * процесс в кэше и переиспользует его при следующем запуске - вместе с движком
     * и всем, что тот помнит.
     */
    private fun closeBrowser() {
        finishAndRemoveTask()
        Process.killProcess(Process.myPid())
    }

    /**
     * Прячем чужие оверлеи, пока браузер на экране (ADR-016, требование H-5).
     *
     * Обычная защита от подмены нажатия нам не годится: события ввода порождает курсор,
     * а `FLAG_WINDOW_IS_OBSCURED` платформа выставляет только на своих. Приложение,
     * рисующее поверх других, могло бы показать одно, а клик курсором ушёл бы на то,
     * что под оверлеем, - и согласие на экране подтверждения http означало бы не то,
     * что человек прочитал.
     *
     * Скрываем постоянно, а не только на экранах решений: подменить можно и ссылку
     * на странице, и адресную строку, которой пользователь проверяет, чей это сайт.
     *
     * Мера появилась в Android 12. На 8..11 её нет вовсе - там это принятый риск,
     * записанный в `SECURITY.md`.
     */
    private fun hideOverlayWindows() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return
        window.setHideOverlayWindows(true)
    }

    private fun configure(web: WebView) {
        web.applySecureDefaults()
        // Поверх безопасных умолчаний - выбор пользователя. Порядок обязателен:
        // JavaScript и Safe Browsing живут в настройках, и последнее слово за ними.
        web.applyPreferences(this)
        // Клавиатура поднимается сама, как только фокус попал в поле страницы (ADR-023).
        // При открытом меню - не поднимается: меню объявило страницу недоступной
        // собственной подложкой, а клавиатура лежит в корне после неё и рисовалась бы
        // поверх, то есть страница получила бы свой элемент управления поверх нашего
        // модального экрана.
        // Флаг ставится в том же вызове, которым движок просит соединение, - до того,
        // как система решит показать своё окно. Отложенный запрет опаздывал: на прошивке
        // Яндекс ТВ клавиатура телевизора успевала встать поверх нашей (`B-43`).
        (web as? BrowserWebView)?.onInputStarting = {
            window.addFlags(WindowManager.LayoutParams.FLAG_ALT_FOCUSABLE_IM)
        }
        (web as? BrowserWebView)?.onInputStarted = {
            // Просьбу отклоняем - но окно всё равно приходится закрывать от системного
            // IME: сам факт просьбы означает, что движок уже позвал систему, и та
            // поднимет своё окно во весь экран, если ей не запретить.
            if (menuOpen() || keyboardDismissed) suppressSystemIme() else showKeyboard()
        }
        // Касание страницы - единственный сигнал, по которому видно, что ввод снова нужен
        // человеку, а не сайту. Нового соединения при этом не создаётся (поле то же
        // самое), поэтому `onInputStarted` больше не придёт и поднимать клавиатуру
        // приходится здесь.
        (web as? BrowserWebView)?.onTouched = {
            setInputDismissed(false)
            if (!menuOpen() && (web as? BrowserWebView)?.connection != null) showKeyboard()
        }
        // Приоритет привязан к видимости и потому задаётся здесь, а не внутри
        // applySecureDefaults: WebView может родиться и на экране (замена умершего),
        // и до первого onStart.
        web.applyRendererPriority(visible = started)
        client = BrowserWebViewClient(
            onRendererGone = ::onRendererGone,
            // Экран решения приходит поверх всего, а клавиатура прижата к низу и накрыла
            // бы и текст предупреждения, и кнопку: решение принимают по половине фразы.
            onDecisionScreen = {
                closeInput()
                cursor?.onDecisionScreen()
            },
            onNavigation = { updatePanel() },
            // Уходим на другую страницу: клавиатура вела в поле, которого больше нет.
            // Подавление при этом снимается - новая страница вправе попросить ввод сама.
            onLoadStarted = {
                // Недонабранное живёт до **следующего перехода** - любого, а не только
                // отправленного из адресной строки (`B-111`, `B-103`). Иначе на новом
                // сайте поле открывалось бы с текстом, набранным для старого, и повторный
                // «ввод» уводил бы на прежнюю цель - при том, что адресная строка это
                // как раз то место, где человек проверяет, чей перед ним сайт (C-4).
                unsentAddress = null
                closeInput()
                setInputDismissed(false)
                window.clearFlags(WindowManager.LayoutParams.FLAG_ALT_FOCUSABLE_IM)
                // Плеер принадлежал странице, которой больше нет. Его View движок сам
                // не снимет, и она осталась бы поверх новой страницы (ADR-033).
                exitFullscreen()
            },
            onVisited = ::recordVisit,
            onOpen = { id -> screens?.openSaved(id) },
            onCommand = { command, segments -> screens?.onCommand(command, segments) },
            onLocalNetwork = ::allowLocalNetwork,
            onQuietNotice = { host -> showQuietNotice(getString(R.string.quiet_ssl, host)) },
            // Экран клиента - не экран списков: состояние гасится, иначе BACK ушёл бы
            // по чужой логике (`B-112`).
            onOwnScreen = { screens?.leftLocalPages() },
        ).also { web.webViewClient = it }
        web.webChromeClient = BrowserChromeClient(
            onProgress = ::showProgress,
            onTitle = ::recordVisit,
            onFullscreen = ::enterFullscreen,
            onFullscreenExit = ::exitFullscreen,
        )
    }

    /**
     * Плеер получает весь экран (ADR-033, требование `B-44`).
     *
     * Панель и страница уходят: кадр обязан занимать экран целиком, а панель поверх
     * фильма - это чужая полоса на картинке. Курсор **остаётся** и лежит выше плеера:
     * кнопки внутри плеера - его собственные элементы, и целиться в них с пульта нечем,
     * кроме курсора.
     *
     * Клавиатура и меню закрываются: они принадлежат странице, которой на экране больше
     * нет, а их появление поверх видео было бы неожиданностью.
     */
    private fun enterFullscreen(view: View, callback: WebChromeClient.CustomViewCallback) {
        val parent = root ?: return
        // Второй запрос подряд платформа допускает: первый плеер обязан уйти, иначе
        // его View останется висеть под новым и продолжит играть звук.
        if (fullscreenView != null) exitFullscreen()
        showFullscreenHost()

        fullscreenView = view
        fullscreenCallback = callback
        closeInput()
        hideMenu()
        panel?.navBar?.visibility = View.GONE
        webView?.visibility = View.GONE
        // Индекс 0 - под курсором и панелью, ровно там же, где живёт сам WebView.
        parent.addView(
            view,
            0,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
            ),
        )
        parent.postDelayed(watchInputInFullscreen, IME_POLL_MS)
    }

    /**
     * Страница просит ввод, пока идёт полноэкранный режим (`B-77`, ADR-037).
     *
     * Перехват ввода стоит в [BrowserWebView.onCreateInputConnection] (ADR-023), но в
     * полноэкранном режиме поле принадлежит не ему, а View, которую отдал движок: соединение
     * система спрашивает у неё, и вся политика ввода обходится стороной - гейт H-6 не
     * спрашивается, наша клавиатура не поднимается, поднимается системная клавиатура
     * телевизора. Экрана хуже для этого нет: адресной строки на нём не осталось, плашка
     * с хостом уже ушла, и поле пароля под чужой шапкой не отличить от настоящего.
     *
     * **Опрос, а не событие, и это вынужденно.** Проверено на стенде, каждое отдельно:
     * события смены фокуса не приходит вовсе; View-посредника, у которой система берёт
     * соединение, в дереве нет - движок держит её вне иерархии, поэтому не срабатывает
     * и слушатель добавления потомков; запрет системного IME флагом `FLAG_ALT_FOCUSABLE_IM`
     * не действует - флаг на окне стоит, а клавиатура телевизора всё равно поднимается;
     * снятая фокусируемость с View движка не меняет ничего. Единственное, что видно
     * снаружи, - что система **уже** принимает текст, и спросить об этом можно только
     * самому.
     *
     * Цена опроса: три обращения к [InputMethodManager] в секунду и только пока идёт
     * полноэкранный режим. Против декодирования видео это ничто, а реже нельзя - интервал
     * это задержка, с которой клавиатура телевизора успевает мелькнуть на кадре.
     *
     * Системную клавиатуру после выхода приходится убирать явно: к моменту, когда опрос
     * её заметил, она уже поднята, а `FLAG_ALT_FOCUSABLE_IM` в этом состоянии бессилен -
     * ровно та гонка, которая описана в ADR-023.
     */
    private val watchInputInFullscreen = object : Runnable {
        override fun run() {
            if (fullscreenView == null) return
            val accepting = getSystemService(InputMethodManager::class.java)?.isAcceptingText == true
            if (accepting) {
                exitFullscreen()
                // Подавление ввода поставил наш же `closeInput` при входе в полноэкранный
                // режим. Оно означает «пользователь закрыл клавиатуру сам», а здесь всё
                // наоборот - ввод понадобился; без снятия движок получил бы отказ
                // в соединении и наша клавиатура не поднялась бы вовсе.
                setInputDismissed(false)
                getSystemService(InputMethodManager::class.java)
                    ?.hideSoftInputFromWindow(window.decorView.windowToken, 0)
                return
            }
            root?.postDelayed(this, IME_POLL_MS)
        }
    }

    /**
     * Кто именно занял экран (требование C-4, ADR-035).
     *
     * Панель с адресом в полноэкранном режиме уходит, а решение о входе принимает
     * страница: `onShowCustomView` приходит на `requestFullscreen` **любого** элемента,
     * не только видео. Без этой плашки страница вправе нарисовать поверх кадра свою
     * панель с чужим хостом и замком, и опровергнуть её пользователю будет нечем -
     * настоящего адреса на экране не останется.
     *
     * Хост берётся у движка, как и в панели: страница не может ни задать его, ни
     * подделать. Плашка висит [FULLSCREEN_HOST_MS] и уходит - на кадре ей не место,
     * а прочитать её успевают: это то же время, что даёт себе на такое сообщение
     * любой телевизор.
     */
    private fun showFullscreenHost() {
        val plate = panel?.fullscreenHost ?: return
        val address = parseAddress(webView?.url)
        plate.text = if (address.kind.isSite) {
            getString(R.string.fullscreen_host, address.host)
        } else {
            getString(R.string.fullscreen_host_unknown)
        }
        plate.visibility = View.VISIBLE
        plate.removeCallbacks(hideFullscreenHost)
        plate.postDelayed(hideFullscreenHost, FULLSCREEN_HOST_MS)
    }

    private val hideFullscreenHost = Runnable {
        panel?.fullscreenHost?.visibility = View.GONE
    }

    /**
     * Короткое сообщение, которое ничего не занимает и само уходит (ADR-063).
     *
     * Своей плашки не заводится: та, что показывает хост в полноэкранном режиме, уже
     * висит поверх страницы, уже посчитана в кадровом бюджете и уже разобрана как
     * пассивная - в предикат `overOwnUi` она намеренно не входит (ADR-035), потому что
     * ничего не принимает и ничего собой не скрывает. Второй такой же View был бы
     * лишним весом ради одной строки текста.
     *
     * Столкнуться два сообщения могут только теоретически: вход в полноэкранный режим
     * и сорванный переход - разные события, и побеждает последнее, как и должно.
     */
    private fun showQuietNotice(text: String) {
        val plate = panel?.fullscreenHost ?: return
        plate.text = text
        plate.visibility = View.VISIBLE
        plate.removeCallbacks(hideFullscreenHost)
        plate.postDelayed(hideFullscreenHost, FULLSCREEN_HOST_MS)
    }

    /**
     * Экран возвращается странице. Зовётся и самим плеером (кнопка «свернуть»),
     * и нами по BACK - платформа требует в обоих случаях сообщить движку `onCustomViewHidden`,
     * иначе он считает себя всё ещё полноэкранным и следующий запрос проигнорирует.
     *
     * Единственное исключение - смерть рендерера: там обратный вызов принадлежит движку,
     * которого уже нет, и обращение к нему может бросить. Поэтому в `onRendererGone`
     * ссылка снимается **до** захода сюда, и падать в обработчике, написанном ради того,
     * чтобы не падать, нечему.
     */
    private fun exitFullscreen() {
        val view = fullscreenView ?: return
        fullscreenView = null
        root?.removeCallbacks(watchInputInFullscreen)
        panel?.fullscreenHost?.let {
            it.removeCallbacks(hideFullscreenHost)
            it.visibility = View.GONE
        }
        root?.removeView(view)
        webView?.visibility = View.VISIBLE
        panel?.navBar?.visibility = View.VISIBLE
        fullscreenCallback?.onCustomViewHidden()
        fullscreenCallback = null
    }

    /**
     * Активность кнопок панели. «Вперёд» имеет смысл только после ухода назад,
     * «назад» - только пока есть куда возвращаться, и считает это сам клиент:
     * часть записей истории мы пропускаем (ADR-015).
     *
     * Погашенная кнопка не просто игнорирует нажатие, а видно тусклая: на телевизоре
     * пользователь целится курсором и должен понимать, что кнопка сейчас мертва,
     * до нажатия, а не после.
     */
    private fun updatePanel() {
        val binding = panel ?: return
        val web = webView
        val client = this.client
        setEnabled(binding.back, web != null && client != null && client.canGoBack(web))
        setEnabled(binding.forward, web != null && client != null && client.canGoForward(web))
        setEnabled(binding.reload, web != null)
        // Открылся настоящий сайт - значит мы больше не дома и не в списке, каким бы
        // способом туда ни ушли: плиткой, адресной строкой или ссылкой на чужой странице.
        // Почему это важно и что при этом гаснет - `LocalScreens.leftLocalPages`.
        // UNKNOWN в это условие попадает намеренно: чужой документ - тоже уход
        // с нашего экрана.
        if (parseAddress(web?.url).kind != AddressKind.LOCAL) screens?.leftLocalPages()
        updateHomeButton(binding)
        updateBookmarkButton(binding)
        updateAddress()
    }

    /**
     * Кнопка «в закладки». На наших собственных страницах гаснет: закладка ставится
     * сайту, а домашняя страница и списки сайтами не являются.
     *
     * Список закладок читается **лениво и один раз** - при первом попадании на настоящий
     * сайт, а не на старте: старт открывает домашнюю страницу, где кнопка и так мертва,
     * и лишнего чтения файла до первого кадра быть не должно (ADR-025, наблюдение B-25).
     * Держит эту лень порядок вычисления: пока `real` ложно, до хранилища дело не доходит.
     *
     * Своей копии адресов у панели больше нет - список и так лежит в памяти `Storage`
     * (`B-30`), а вторая копия того же самого требовала ручной правки в трёх местах.
     * Перебор здесь дешёвый: он на навигации, не на кадре, и закладок не больше двухсот.
     */
    private fun updateBookmarkButton(binding: ActivityMainBinding) {
        val url = webView?.url
        val real = url != null && parseAddress(url).kind.isSite
        setEnabled(binding.bookmark, real)
        val saved = real && storage?.bookmarks()?.any { it.url == url } == true
        if (bookmarkShowsSaved == saved) return
        bookmarkShowsSaved = saved
        binding.bookmark.setImageResource(
            if (saved) R.drawable.ic_bookmark_added else R.drawable.ic_bookmark_add
        )
        binding.bookmark.contentDescription =
            getString(if (saved) R.string.nav_bookmark_remove else R.string.nav_bookmark_add)
    }

    /**
     * Нажата кнопка «в закладки»: страница добавляется, а если уже сохранена - убирается.
     * Отдельной кнопки удаления в панели нет намеренно - на пульте это лишний элемент,
     * а состояние видно по самому значку.
     */
    private fun toggleBookmark() {
        val web = webView ?: return
        val store = storage ?: return
        val url = web.url ?: return
        if (!parseAddress(url).kind.isSite) return

        val items = store.bookmarks().toMutableList()
        val existing = items.indexOfFirst { it.url == url }
        if (existing >= 0) {
            items.removeAt(existing)
        } else {
            // Свежая закладка идёт первой: список читается сверху, а искать только что
            // добавленное в конце длинного списка - работа, которой можно не быть.
            items.add(0, SiteEntry(url, web.title.orEmpty()))
        }
        store.saveBookmarks(items)
        panel?.let { updateBookmarkButton(it) }
    }

    /**
     * Кнопка «домой» и она же кнопка возврата.
     *
     * Дома и есть куда вернуться - показывает возврат. Не дома - показывает дом.
     * Дома, но возвращаться некуда (браузер только что запустили) - дом, погашенный:
     * на телевизоре кнопка обязана быть видимо мёртвой до нажатия, а не после.
     */
    private fun updateHomeButton(binding: ActivityMainBinding) {
        val atHome = screens?.screen == LocalScreen.HOME
        val returning = atHome && homeReturnUrl != null
        setEnabled(binding.home, returning || !atHome)
        if (homeShowsReturn == returning) return
        homeShowsReturn = returning
        binding.home.setImageResource(
            if (returning) R.drawable.ic_home_return else R.drawable.ic_home
        )
        binding.home.contentDescription =
            getString(if (returning) R.string.nav_home_return else R.string.nav_home)
    }

    private fun onHomeButton() {
        val web = webView ?: return
        if (screens?.screen == LocalScreen.HOME) {
            val back = homeReturnUrl ?: return
            homeReturnUrl = null
            client?.load(web, back)
            return
        }
        // Запоминаем только настоящий адрес: возвращаться на экран ошибки
        // или на подтверждение http незачем.
        homeReturnUrl = web.url?.takeIf { parseAddress(it).kind.isSite }
        screens?.showHome()
    }

    private fun setEnabled(button: ImageButton, enabled: Boolean) {
        button.isEnabled = enabled
        button.alpha = if (enabled) 1f else DISABLED_ALPHA
    }

    /**
     * Запись перехода в историю (ADR-025).
     *
     * Наши собственные страницы - стартовая, экраны ошибок и подтверждения - в историю
     * не идут: возвращаться на них незачем, а полоса «последний сайт» на домашней
     * странице, показывающая саму домашнюю страницу, была бы просто нелепой.
     *
     * Заголовок берётся у движка. Он бывает пустым - тогда пусть будет пустым:
     * подставлять вместо него адрес здесь нельзя, иначе экран истории не отличит
     * настоящее название от нашей выдумки и покажет адрес дважды.
     */
    private fun recordVisit() {
        val web = webView ?: return
        val url = web.url ?: return
        if (!parseAddress(url).kind.isSite) return
        // Приватный режим отсекает запись здесь, в единственной точке: ниже по течению
        // и лог истории, и полоса «последний сайт» пишутся из `Storage.recordVisit`,
        // поэтому забыть одно из двух невозможно.
        if (Settings.isOn(this, Toggle.PRIVATE)) return
        val title = web.title ?: ""
        if (url == visitedUrl && title == visitedTitle) return
        visitedUrl = url
        visitedTitle = title
        storage?.recordVisit(SiteEntry(url, title))
    }

    /**
     * Адресная строка и индикатор защищённости (требования C-2 и C-4).
     *
     * Адрес спрашивается у самого движка, а не берётся из навигации: страница может
     * менять его без загрузки (`history.pushState`), и строка обязана следовать за
     * настоящим состоянием, а не за последним увиденным нами переходом.
     *
     * У наших собственных страниц адреса нет вовсе - они грузятся с пустым
     * происхождением. Показывать вместо адреса внутренности `data:` бессмысленно,
     * а индикатор защищённости на них означал бы неправду в обе стороны: соединения
     * там нет никакого.
     */
    private fun updateAddress(force: Boolean = false) {
        val binding = panel ?: return
        // Пока пользователь набирает адрес, поле принадлежит ему: загрузившаяся тем
        // временем страница не имеет права затирать набранное на середине слова.
        if (editing) return

        // Страница может дёргать `history.pushState` сколь угодно часто, вплоть
        // до нескольких раз в секунду из обработчика прокрутки. Перекладывать панель
        // на каждый такой вызов незачем: адрес от этого не меняется.
        val url = webView?.url
        if (!force && url == shownUrl) return
        // Ширина поля ещё не известна - обрезать нечем, и показанный сейчас адрес
        // считать показанным нельзя: иначе первый же следующий колбэк выйдет по
        // равенству адресов, а хост так и останется необрезанным. Обрезкой займётся
        // поле, а оно режет с конца - ровно то, что запрещает C-2.
        shownUrl = if (addressWidth(binding.address) > 0f) url else null

        val address = parseAddress(url)

        if (address.kind == AddressKind.LOCAL) {
            binding.security.visibility = View.GONE
            binding.address.setText(R.string.address_local)
            return
        }

        // Чужой документ без происхождения: показывается его схема, и ничего больше.
        // Назвать его «страницей браузера» значило бы подписать чужое своим именем (C-4).
        if (address.kind == AddressKind.UNKNOWN) {
            binding.security.visibility = View.GONE
            binding.address.setText(address.host)
            return
        }

        val secure = address.kind == AddressKind.SECURE
        binding.security.visibility = View.VISIBLE
        binding.security.setImageResource(
            if (secure) R.drawable.ic_lock else R.drawable.ic_insecure,
        )
        binding.security.contentDescription =
            getString(if (secure) R.string.security_secure else R.string.security_insecure)

        val shownHost = fitHost(address.host)
        // Требование C-2, `B-17`: хост и остаток адреса изолируются друг от друга.
        // Само поле уже LTR (`textDirection` в разметке), но домен целиком на арабском
        // или иврите остаётся законным - смешанные алфавиты уходят в punycode, а один
        // не уходит. Внутри LTR-строки такой хост рисуется справа налево, и соседние
        // нейтральные символы (`/`, `:`, `.`) прилипают к нему, перенося видимую
        // границу хоста внутрь пути. Пара FSI/PDI запирает каждый кусок в себе.
        val text = SpannableStringBuilder()
        text.append(ISOLATE_START).append(shownHost).append(ISOLATE_END)
        val rest = fitRest(address.rest, shownHost)
        if (rest.isNotEmpty()) {
            text.append(ISOLATE_START).append(rest).append(ISOLATE_END)
        }
        // Подсветкой хоста накрываются и изоляты: они ничего не рисуют, но оставлять
        // их вне span значит зависеть от того, где именно проходит его граница.
        text.setSpan(
            ForegroundColorSpan(getColor(R.color.panel_text)),
            0,
            shownHost.length + 2,
            Spanned.SPAN_EXCLUSIVE_EXCLUSIVE,
        )
        binding.address.setText(text)
    }

    /**
     * Имя хоста, урезанное **с начала** (требование C-2).
     *
     * Обрезка с конца, которую делает само поле, защищает только от длинного пути.
     * Но и хост бывает любой длины: `sberbank.ru.security-check.aaaa…aaaa.evil.com`
     * в поле шириной в шестьдесят символов прочитается как адрес банка, а `evil.com`
     * уедет за край - причём молча, `EditText` даже многоточия не поставит.
     * Настоящее имя сайта - в конце строки, поэтому именно конец обязан быть виден.
     */
    private fun fitHost(host: String): CharSequence {
        val field = panel?.address ?: return host
        val available = addressWidth(field)
        if (available <= 0f || field.paint.measureText(host) <= available) return host
        return TextUtils.ellipsize(host, field.paint, available, TextUtils.TruncateAt.START)
    }

    /** Путь и параметры занимают то, что осталось от хоста, и обрезаются с конца. */
    private fun fitRest(rest: String, shownHost: CharSequence): CharSequence {
        if (rest.isEmpty()) return rest
        val field = panel?.address ?: return rest
        val available = addressWidth(field) - field.paint.measureText(shownHost, 0, shownHost.length)
        if (available <= 0f) return ""
        return TextUtils.ellipsize(rest, field.paint, available, TextUtils.TruncateAt.END)
    }

    /** Ноль означает «разметки ещё не было»: тогда показываем адрес как есть. */
    private fun addressWidth(field: EditText): Float =
        (field.width - field.paddingStart - field.paddingEnd).toFloat()

    /**
     * Режим ввода адреса. Работает своя клавиатура (ADR-020..023): системный IME
     * телевизора - окно во весь экран, и открытым он перекрывает и панель, и всё
     * остальное.
     *
     * Поле открывается с текущим адресом, выделенным целиком. Так закрываются оба
     * долга Этапа 4: первый же набранный символ заменяет выделенное - это и есть
     * очистка одним нажатием, а «Стереть» убирает выделение и оставляет адрес
     * под правку. Раньше это не работало: системный IME сбрасывал выделение
     * в конец примерно через 600 мс, и набранное дописывалось к старому адресу.
     *
     * Вне режима ввода строка не фокусируется вовсе: фокус на телевизоре живёт своей
     * жизнью и, доставшись адресной строке случайно, увёл бы туда весь D-pad, которым
     * управляется курсор.
     */
    private fun startEditing() {
        val field = panel?.address ?: return
        // Повторное нажатие по строке, из которой не выходили, поле НЕ перезаполняет
        // (`B-90`, вторая половина). Иначе стёртый адрес возвращался целиком: набранное
        // сохраняется только при выходе из поля, а здесь выхода не было - и строка ниже
        // молча писала туда адрес страницы поверх того, что человек только что стёр.
        // Клавиатура при этом поднимается: её могли закрыть, чтобы дотянуться до строки.
        if (editing) {
            showKeyboard()
            return
        }
        editing = true
        shownUrl = null

        // У наших собственных страниц адреса нет: движок отдаёт для них `about:blank`,
        // и править эту строку пользователю нечего.
        val url = webView?.url
        // Правится только настоящий адрес. Ни наш экран, ни чужой документ без
        // происхождения набирать заново нечем - поле открывается пустым.
        //
        // Недонабранное возвращается на место (B-90). На пульте стереть адрес стоит
        // десятка нажатий, и терять эту работу из-за случайного выхода из поля - обидно
        // настолько, что пользователь заметил это первым же вечером.
        field.setText(unsentAddress ?: if (parseAddress(url).kind.isSite) url else "")
        field.setTextColor(getColor(R.color.panel_text))

        // Своя клавиатура поднимается **до** фокуса, и это не косметика порядка строк.
        // Флаг `FLAG_ALT_FOCUSABLE_IM` запрещает системе показывать своё окно только при
        // смене цели ввода: если IME уже поднят фокусом, снять его нечем -
        // `hideSoftInputFromWindow` эту гонку проигрывает (ADR-023). Пока фокус ставился
        // первым, на прошивке Яндекс ТВ клавиатура телевизора вставала поверх нашей
        // (`B-43`), а на эмуляторе того же не происходило: там IME пассивный.
        showKeyboard()

        field.isFocusableInTouchMode = true
        field.requestFocus()
        field.setSelection(0, field.text.length)
    }

    private fun stopEditing() {
        if (!editing) return
        val field = panel?.address ?: return
        editing = false
        // Набранное, но не отправленное - до следующего перехода (B-90).
        unsentAddress = field.text.toString()

        hideKeyboard()
        field.clearFocus()
        field.isFocusableInTouchMode = false
        field.isFocusable = false
        field.setTextColor(getColor(R.color.panel_hint))
        // Принудительно: в поле сейчас набранное пользователем, а не показанный адрес,
        // и вернуть туда адрес нужно, даже если сама страница не менялась.
        updateAddress(force = true)
    }

    /**
     * Пока наша клавиатура на экране, окно не обслуживается системным IME
     * (`FLAG_ALT_FOCUSABLE_IM`). Разовым скрытием системную клавиатуру не убрать:
     * движок просит её показать, система поднимает окно во весь экран, и любое наше
     * `hideSoftInputFromWindow` - гонка, которую видно на стенде. Флаг же не действие,
     * а состояние: пока он стоит, окно системного IME не появляется вовсе, и нажатия
     * достаются нашим клавишам. Снимает его [hideKeyboard].
     *
     * Показ клавиатуры подчиняется тому же правилу, что и экраны принятия решений
     * (требование H-1), и по той же причине. Момент появления выбирает **страница**:
     * `element.focus()` из скрипта поднимает клавиатуру без единого нажатия, а курсор
     * странице виден - мы сами шлём ей наведение. Значит сайт может нарисовать приманку
     * там, где через кадр окажется клавиша «вставить», дождаться по `mousemove`, пока
     * пользователь наведётся, и поднять клавиатуру ему под палец: одно нажатие OK -
     * и общий буфер телевизора уехал в страницу. Воспроизведено на стенде.
     *
     * Гейт закрывает ровно это: первый клик не засчитывается, пока не прошло 600 мс
     * и курсор не сдвинули после показа. Цена нулевая - к клавише всё равно идут
     * курсором. Режим пульта отдельного гейта не требует: попасть в него можно только
     * кликнув курсором по клавише режима, то есть уже пройдя эту проверку.
     *
     * **Взводится гейт только на подъёме клавиатуры из скрытого состояния**, и это
     * половина требования. Пока `showKeyboard` отрабатывал каждую просьбу страницы,
     * моментом взведения управляла она же: `setInterval` с `blur()/focus()` чаще, чем
     * раз в 600 мс, держал гейт закрытым вечно, а он - единственная проверка на пути
     * **любого** клика курсором. Не работало ничего: ни клавиши, ни кнопки панели,
     * ни адресная строка, ни меню, и BACK не спасал - он закрывал клавиатуру,
     * а страница поднимала её обратно. Выход был только через HOME. Вредоносной
     * страницы для этого не требуется: в режиме пульта курсор неподвижен, поэтому
     * хватало **одного** `focus()`, чтобы навсегда убить клавиши, идущие через
     * `sendKey`, а автофокус на поле поиска есть у любого поисковика.
     */
    private fun showKeyboard() {
        // Сюда доходят либо явные вызовы - кнопка панели, правка адреса, - либо просьба
        // страницы, уже прошедшая проверку в `onInputStarted`. И то и другое означает,
        // что клавиатура снова нужна.
        setInputDismissed(false)
        // Гейт - только на подъёме из скрытого состояния. Всё остальное здесь
        // повторяется намеренно: флаг и подавление системного IME нужны на каждый
        // вызов. Стоило пропустить их ранним выходом - и системная клавиатура
        // вылезла поверх нашей на первой же странице, которая просит фокус в цикле.
        val raising = !keyboardOpen()
        panel?.keyboard?.visibility = View.VISIBLE
        if (raising) cursor?.onDecisionScreen()
        suppressSystemIme()
    }

    /**
     * Закрыть окно от системного IME, не показывая свою клавиатуру.
     *
     * Нужно отдельно от [showKeyboard] потому, что отказ поднимать свою клавиатуру
     * не отменяет просьбы движка: система готова показать окно во весь экран,
     * и тогда пользователь получит именно ту клавиатуру телевизора, от которой
     * мы ушли в ADR-023, - поверх нашего интерфейса и мимо курсора.
     */
    private fun suppressSystemIme() {
        window.addFlags(WindowManager.LayoutParams.FLAG_ALT_FOCUSABLE_IM)
        hideSystemIme()
        // Второй раз - следующим проходом очереди. Флаг запрещает системе показывать
        // своё окно только при смене цели ввода; если она уже привязана к нашему окну,
        // движок просит показ напрямую, и этот показ случается **после** нашего запроса
        // на скрытие. Одного вызова тут не хватает - проверено на стенде: клавиатура
        // телевизора вставала поверх нашей на поле, в которое ткнули второй раз.
        window.decorView.post { hideSystemIme() }
    }

    private fun hideSystemIme() {
        getSystemService(InputMethodManager::class.java)
            ?.hideSoftInputFromWindow(window.decorView.windowToken, 0)
    }

    private fun hideKeyboard() {
        val keyboard = panel?.keyboard ?: return
        keyboard.visibility = View.GONE
        keyboard.reset()
        // Соединение с полем здесь **не отпускается**: страница та же, поле то же,
        // и по касанию страницы клавиатуру надо будет поднять обратно - а взять новое
        // соединение неоткуда, движок создаёт его только при смене фокуса. Набрать
        // что-либо в закрытую клавиатуру нельзя, так что жить ему до ухода со страницы
        // безопасно; там его и обнуляет `onPageStarted`.
        //
        // Подавление системного IME здесь **не снимается**, и это главное в правке.
        // Стоит снять флаг - и на поле, которое не отдаёт фокус (выпадающий список
        // DuckDuckGo), движок немедленно зовёт систему: её окно во весь экран встаёт
        // поверх нашего раньше, чем мы успеваем запретить показ, а
        // `hideSoftInputFromWindow` эту гонку проигрывает (ADR-023). Обратно клавиатуру
        // поднимает касание страницы, см. `onTouched` в [configure]. Флаг снимается
        // при уходе на другую страницу, где поле исчезает вместе с документом.
    }

    private fun keyboardOpen(): Boolean = panel?.keyboard?.visibility == View.VISIBLE


    /**
     * Убрать клавиатуру откуда угодно: с её собственной клавиши, с кнопки панели,
     * по BACK и при уходе с экрана. Разница только в том, правился ли адрес: тогда
     * из режима правки надо ещё и выйти, иначе панель останется с недонабранной
     * строкой и перестанет показывать адрес открытой страницы.
     */
    private fun closeInput() {
        // Пока клавиатура закрыта по воле пользователя, движку не выдаётся соединение:
        // без него системе нечего обслуживать и своей клавиатуры она не показывает.
        setInputDismissed(true)
        // Переименование клавиша «убрать клавиатуру» **не** отменяет: под полем есть
        // «ОК» и «Отмена», и убрать клавиатуру, чтобы дотянуться до них, - осмысленное
        // действие. Раньше поле закрывалось вместе с клавиатурой, и это был дефект.
        if (renameIndex >= 0) {
            hideKeyboard()
            return
        }
        if (editing) stopEditing() else hideKeyboard()
    }

    /**
     * Переименование закладки или плитки (Этап 6, шаг 7a). Поле - наше View: набранное
     * в поле локальной страницы к приложению не попадёт, связь только переходом
     * по своей схеме (ADR-009).
     *
     * Текущее название лежит в поле выделенным целиком - как и в адресной строке: чаще
     * его переписывают, а не дописывают, и стирать посимвольно с пульта дорого.
     */
    override fun startRename(index: Int, favorite: Boolean, title: String) {
        val field = panel?.rename ?: return
        renameIndex = index
        renameFavorite = favorite
        panel?.renameScrim?.visibility = View.VISIBLE
        field.setText(title)
        field.isFocusableInTouchMode = true
        field.requestFocus()
        field.setSelection(0, field.text.length)
        showKeyboard()
    }

    /**
     * Подсветка кнопок под полем при наведении курсора. Своими руками, потому что
     * событий наведения платформы здесь нет: курсор шлёт нашим View обычные касания,
     * и `state_hovered` не сработал бы никогда. Так же подсвечиваются клавиши.
     *
     * Лежит на кадровом пути, поэтому считает границы сложением полей View и не
     * выделяет памяти: `getLocationOnScreen` создавал бы массив на каждый кадр.
     */
    /**
     * Показать полосу «вышла новая версия» (ADR-051).
     *
     * Кнопки убираются сразу после нажатия «Обновить»: скачивание идёт своим ходом,
     * и второе нажатие завело бы вторую загрузку. Дальше полоса работает подписью
     * происходящего - на медленном канале молчащий экран читается как зависший.
     */
    /** Найденное обновление: нужно, чтобы вернуть кнопки после чужого экрана. */
    private var pendingUpdate: UpdateInfo? = null

    private fun showUpdate(info: UpdateInfo) {
        val bar = panel ?: return
        pendingUpdate = info
        bar.updateText.text = getString(R.string.update_ready, info.versionName)
        bar.updateBar.visibility = View.VISIBLE
        bar.updateNow.visibility = View.VISIBLE
        bar.updateLater.visibility = View.VISIBLE
        bar.updateNow.setOnClickListener {
            bar.updateNow.visibility = View.GONE
            bar.updateLater.visibility = View.GONE
            Updates.install(this, info) { state -> panel?.updateText?.setText(state) }
        }
        bar.updateLater.setOnClickListener {
            bar.updateBar.visibility = View.GONE
            pendingUpdate = null
        }
    }

    /**
     * Вернулись из системного установщика, а обновление не встало - значит человеку надо
     * дать нажать «Обновить» ещё раз.
     *
     * Так выглядит **первое** обновление на любом телевизоре: система не пускает установку
     * сразу, а сначала открывает свой экран «Установка неизвестных приложений» - разрешение
     * даётся один раз и руками. После него наша сессия установки уже закрыта, и без этой
     * строчки полоса навсегда застывала на «Открываю установщик...» с убранными кнопками.
     * Проверено на эмуляторе 2026-08-20: до правки выйти из тупика можно было только
     * перезапуском браузера.
     *
     * Удачная установка сюда не попадает: система заменяет пакет и убивает наш процесс.
     */
    private fun restoreUpdateButtons() {
        val bar = panel ?: return
        val info = pendingUpdate ?: return
        if (bar.updateBar.visibility != View.VISIBLE) return
        if (bar.updateNow.visibility == View.VISIBLE) return
        showUpdate(info)
    }

    private fun highlightRenameButtons(x: Float, y: Float) {
        val ok = panel?.renameOk ?: return
        val cancel = panel?.renameCancel ?: return
        ok.isSelected = hitsRename(ok, x, y)
        cancel.isSelected = hitsRename(cancel, x, y)
    }

    private fun hitsRename(view: View, x: Float, y: Float): Boolean {
        val row = view.parent as? View ?: return false
        val column = row.parent as? View ?: return false
        val left = column.left + row.left + view.left
        val top = column.top + row.top + view.top
        return x >= left && x <= left + view.width && y >= top && y <= top + view.height
    }

    private fun stopRename() {
        val field = panel?.rename ?: return
        renameIndex = -1
        panel?.renameScrim?.visibility = View.GONE
        // Подсветка снимается явно: иначе кнопка откроется в следующий раз уже
        // подсвеченной, хотя курсор в другом месте.
        panel?.renameOk?.isSelected = false
        panel?.renameCancel?.isSelected = false
        field.clearFocus()
        field.isFocusableInTouchMode = false
        field.isFocusable = false
        hideKeyboard()
    }

    /**
     * Название подтверждено. Поле закрывается здесь, а список правит [LocalScreens]:
     * хранилище и перерисовка экрана - его работа, наша - только поле ввода.
     */
    private fun commitRename() {
        val index = renameIndex
        val favorite = renameFavorite
        val title = panel?.rename?.text?.toString()?.trim().orEmpty()
        stopRename()
        if (index < 0) return
        screens?.applyRename(index, favorite, title)
    }

    /**
     * Набранный символ уходит туда, где сейчас идёт ввод, и нигде по дороге не хранится
     * (ADR-020): в адресную строку - текстом, в страницу - событиями клавиши, то есть
     * тем же путём, которым приходит физическая клавиатура.
     *
     * Своей раскладки у платформы для кириллицы нет: [KeyCharacterMap] строит события
     * только для символов, которые есть на виртуальной клавиатуре, и на «я» возвращает
     * null. Такие символы отдаются движку через соединение ввода - тем же способом,
     * которым их отдаёт системная клавиатура. Событие со строкой (`ACTION_MULTIPLE`)
     * здесь не годится: движок его молча игнорирует, проверено на стенде.
     */
    private fun typeChar(char: Char) {
        if (inputField() != null) {
            typeIntoField(char.toString())
            return
        }
        val web = webView ?: return
        val events = keyMap.getEvents(charArrayOf(char))
        if (events != null) {
            events.forEach { event -> web.dispatchKeyEvent(event) }
            return
        }
        commitToPage(char.toString())
    }

    /**
     * Текст в поле страницы через перехваченное соединение ввода (ADR-023).
     *
     * Соединение движка живёт на своём потоке и о нём же сообщает через `getHandler`:
     * вызов с чужого потока он молча теряет.
     *
     * Работа собрана одним `Runnable` на оба пути (`B-34`): раньше здесь стояла обёртка
     * `onConnection` с лямбдой-параметром, и каждое нажатие клавиши создавало объект
     * лямбды с захватом поверх того же `Runnable`. Вызывающий у обёртки был один.
     */
    private fun commitToPage(text: String) {
        val connection = pageConnection() ?: return
        val commit = Runnable { connection.commitText(text, 1) }
        val handler = connection.handler
        if (handler != null) handler.post(commit) else commit.run()
    }

    /** Соединение с полем страницы, если движок его запрашивал. */
    private fun pageConnection(): InputConnection? = (webView as? BrowserWebView)?.connection

    /**
     * Поле, в которое сейчас идёт ввод с нашей клавиатуры: адресная строка или поле
     * переименования. `null` - ввод уходит в страницу.
     *
     * Один вопрос на все клавиши: их девять, и каждая иначе спрашивала бы «правится ли
     * адрес» отдельно. Появление второго поля тогда означало бы девять новых ветвлений
     * там, где ошибка видна только на стенде.
     */
    private fun inputField(): EditText? = when {
        renameIndex >= 0 -> panel?.rename
        editing -> panel?.address
        else -> null
    }

    private fun onKeyAction(action: KeyAction) {
        when (action) {
            KeyAction.BACKSPACE -> typeBackspace()
            KeyAction.ENTER -> typeEnter()
            KeyAction.CLEAR -> clearField()
            KeyAction.CLOSE -> closeInput()
            KeyAction.LEFT -> moveCaret(-1)
            KeyAction.RIGHT -> moveCaret(1)
            KeyAction.PASTE -> paste()
            KeyAction.COPY -> copyOut(cut = false)
            KeyAction.CUT -> copyOut(cut = true)
            KeyAction.SELECT_ALL -> selectAll()
        }
    }

    /**
     * Копирование и вырезание выделенного (`ADR-034`). Клавиши парные к вставке:
     * забирать текст из браузера пользователь должен уметь так же, как приносить.
     *
     * В поле страницы своего представления о тексте у нас нет, поэтому работают те же
     * сочетания, что и с физической клавиатуры, - ими же сделано «выделить всё».
     */
    private fun copyOut(cut: Boolean) {
        val field = inputField()
        if (field != null) {
            val start = minOf(field.selectionStart, field.selectionEnd).coerceAtLeast(0)
            val end = maxOf(field.selectionStart, field.selectionEnd).coerceAtLeast(0)
            // Пустое выделение молча ничего не делает: затирать буфер обмена телевизора
            // пустой строкой - потеря того, что пользователь клал туда в другом приложении.
            if (start == end) return
            val manager = getSystemService(ClipboardManager::class.java) ?: return
            // На части прошивок служба буфера отвечает исключением (`B-54`). Тогда
            // вырезание обязано остановиться здесь: удалить текст, не положив его
            // в буфер, - это потеря того, что пользователь набрал.
            try {
                manager.setPrimaryClip(
                    ClipData.newPlainText(null, field.text.substring(start, end)),
                )
            } catch (_: Exception) {
                return
            }
            if (cut) field.text.delete(start, end)
            return
        }
        sendKey(if (cut) KeyEvent.KEYCODE_X else KeyEvent.KEYCODE_C, KeyEvent.META_CTRL_ON)
    }

    /**
     * Очистить поле целиком. На пульте это не роскошь: стереть длинный адрес
     * посимвольно - десятки нажатий, а начинают ввод чаще всего с чистого места.
     */
    private fun clearField() {
        val field = inputField()
        if (field != null) {
            field.setText("")
            return
        }
        // Только когда движок сам попросил ввод: иначе «выделить всё» уйдёт в документ
        // целиком, стирать там нечего, и страница останется выделенной без способа
        // это снять. Клавиатуру ведь можно поднять и кнопкой панели, не встав в поле.
        if (pageConnection() == null) return
        // В поле страницы - теми же сочетаниями, что и с физической клавиатуры.
        // Пустой `commitText` поверх выделения движок игнорирует (проверено на стенде),
        // а выделение с последующим стиранием проходит одной очередью событий:
        // оба сообщения идут в движок с потока разметки и в том порядке, в каком посланы.
        selectAllInPage()
        sendKey(KeyEvent.KEYCODE_DEL)
    }

    /**
     * Каретка на символ в сторону. В поле страницы это те же клавиши, которыми
     * её двигает физическая клавиатура, - своего представления о тексте страницы
     * у нас нет и быть не должно.
     */
    private fun moveCaret(delta: Int) {
        val field = inputField()
        if (field != null) {
            field.setSelection((field.selectionEnd + delta).coerceIn(0, field.text.length))
            return
        }
        sendKey(if (delta < 0) KeyEvent.KEYCODE_DPAD_LEFT else KeyEvent.KEYCODE_DPAD_RIGHT)
    }

    /**
     * Вставка из буфера обмена. Читаем его только по явному нажатию клавиши:
     * буфер телевизора общий для всех приложений, и заглядывать в него без спроса
     * браузер не должен.
     */
    private fun paste() {
        // Чтение буфера тоже бывает исключением (`B-54`): вставлять тогда нечего,
        // и нажатие просто ничего не делает.
        val clip = try {
            getSystemService(ClipboardManager::class.java)?.primaryClip
        } catch (_: Exception) {
            null
        } ?: return
        if (clip.itemCount == 0) return
        // Только настоящий текст. `coerceToText` для элемента-URI открывает поток через
        // `ContentResolver` и вычитывает его целиком: чужое приложение кладёт в общий
        // буфер ссылку на свой провайдер, и мы по нажатию «вставить» читаем оттуда
        // сколько угодно. Мы же намеренно запрещаем content-доступ самому WebView.
        val text = clip.getItemAt(0)?.text?.toString().orEmpty()
        if (text.isEmpty()) return

        if (inputField() != null) typeIntoField(text) else commitToPage(text)
    }

    private fun selectAll() {
        val field = inputField()
        if (field != null) {
            field.setSelection(0, field.text.length)
            return
        }
        selectAllInPage()
    }

    /**
     * «Выделить всё» в поле страницы. Способ один на обе клавиши - «выделить всё»
     * и «очистить»: два разных пути к движку означали бы и два разных потока,
     * а очистка от этого один раз уже сломалась.
     */
    private fun selectAllInPage() = sendKey(KeyEvent.KEYCODE_A, KeyEvent.META_CTRL_ON)

    /** Символ встаёт вместо выделенного: при открытии выделен весь текст поля. */
    private fun typeIntoField(text: String) {
        val field = inputField() ?: return
        val at = minOf(field.selectionStart, field.selectionEnd).coerceAtLeast(0)
        val to = maxOf(field.selectionStart, field.selectionEnd).coerceAtLeast(0)
        field.text.replace(at, to, text)
        // Каретку ставим сами: после замены выделенного `Editable` оставляет выделенным
        // вставленное, и следующий символ затирал бы предыдущий.
        field.setSelection(at + text.length)
    }

    /** Стирание идёт в само поле: буфера, из которого можно было бы стереть, у нас нет. */
    private fun typeBackspace() {
        val field = inputField()
        if (field != null) {
            val start = field.selectionStart.coerceAtLeast(0)
            val end = field.selectionEnd.coerceAtLeast(0)
            when {
                start != end -> field.text.delete(minOf(start, end), maxOf(start, end))
                start > 0 -> field.text.delete(start - 1, start)
            }
            return
        }
        sendKey(KeyEvent.KEYCODE_DEL)
    }

    private fun typeEnter() {
        if (renameIndex >= 0) {
            commitRename()
            return
        }
        if (editing) {
            val target = resolveInput(panel?.address?.text?.toString().orEmpty(), engine)
            stopEditing()
            if (target != null) {
                // Отправленное недонабранным не считается (`B-90`). `stopEditing` строкой
                // выше запомнил содержимое поля, не зная, отправляют его или бросают, -
                // и без этой строки поисковый запрос жил в памяти вечно: следующий заход
                // в адресную строку показывал «игра престолов кинопоиск» вместо адреса
                // открытой страницы, то есть править адрес было нечем.
                unsentAddress = null
                webView?.let { web -> client?.load(web, target) }
            }
            return
        }
        sendKey(KeyEvent.KEYCODE_ENTER)
    }

    /**
     * Клавиша, которая шлёт событие прямо в страницу. Такие идут мимо [CursorController.click],
     * поэтому защиту H-1 они обязаны спрашивать сами: без этого «ввод» или «каретка
     * вправо» на подсунутой странице сработали бы там, где клик курсором засчитан не был.
     */
    private fun sendKey(keyCode: Int, meta: Int = 0) {
        val web = webView ?: return
        if (cursor?.inputAllowed() == false) return
        val time = SystemClock.uptimeMillis()
        web.dispatchKeyEvent(KeyEvent(time, time, KeyEvent.ACTION_DOWN, keyCode, 0, meta))
        web.dispatchKeyEvent(KeyEvent(time, time, KeyEvent.ACTION_UP, keyCode, 0, meta))
    }

    /**
     * Меню панели: пока в нём только выбор поисковика (ADR-018), на Этапе 7 отсюда
     * откроется общий экран настроек.
     *
     * Открытое меню перекрывает экран целиком собственной подложкой, и это не украшение:
     * подложка забирает нажатия себе, поэтому клик мимо меню закрывает его и не уходит
     * ни в страницу, ни в кнопки панели.
     */
    private fun showMenu() {
        // Ввод и меню одновременно не живут. Клавиатура лежит в корне после подложки
        // меню, то есть рисуется поверх неё и забирает нажатия себе - открытое меню
        // с живой клавиатурой означало бы ввод в страницу, которую подложка объявила
        // недоступной.
        closeInput()
        panel?.menuScrim?.visibility = View.VISIBLE
    }

    override fun hideMenu() {
        panel?.menuScrim?.visibility = View.GONE
    }

    private fun menuOpen(): Boolean = panel?.menuScrim?.visibility == View.VISIBLE

    /**
     * Поисковик переключается по кругу нажатием на строку меню, а не выбором из списка.
     * Так решено при утверждении макета: значений всего три, а вложенный экран стоит
     * на пульте двух лишних нажатий. Меню при этом **не закрывается** - иначе перебрать
     * три значения означало бы три раза открыть меню заново.
     */
    private fun cycleEngine() {
        val order = SearchEngine.entries
        engine = order[(order.indexOf(engine) + 1) % order.size]
        Settings.setSearchEngine(this, engine)
        markSelectedEngine()
    }

    private fun markSelectedEngine() {
        panel?.menuSearchValue?.setText(
            when (engine) {
                SearchEngine.DUCKDUCKGO -> R.string.search_duckduckgo
                SearchEngine.GOOGLE -> R.string.search_google
                SearchEngine.YANDEX -> R.string.search_yandex
            }
        )
    }

    /**
     * Полоса загрузки. Показывается только пока страница грузится: постоянно висящая
     * полоса на нуле не сообщает ничего и лишь занимает кромку панели.
     */
    private fun showProgress(percent: Int) {
        val bar = panel?.progress ?: return
        bar.progress = percent
        bar.visibility = if (percent in 1..99) View.VISIBLE else View.INVISIBLE
    }

    /**
     * Смерть процесса рендерера.
     *
     * Старый экземпляр после этого события не оживает: любое обращение к нему приводит
     * к исключению, поэтому он снимается с экрана и уничтожается сразу. А вот новый
     * создаётся только когда мы на экране: в фоне мы сами просим систему убивать наш
     * рендерер (`RENDERER_PRIORITY_BOUND`), и поднимать его там значит немедленно
     * занять десятки мегабайт памяти телевизора ради страницы, которой никто не видит.
     *
     * Открытая страница теряется - восстанавливать её нечем и незачем, рендерер чаще
     * всего умирает именно на ней.
     */
    private fun onRendererGone(crashed: Boolean) {
        // Система убила рендерер, пока нас не было на экране, - то же самое, ради чего
        // существует ADR-019, только решение приняли за нас. Адрес известен, значит
        // возвращать пользователя на экран «не хватило памяти» незачем: вернём страницу.
        val killedInBackground = !crashed && !started
        // Ввод закрываем до того, как страница исчезнет: иначе клавиатура останется
        // висеть поверх экрана-объяснения, а писать ей уже некуда.
        closeInput()
        // И полноэкранный режим - тоже до того. View плеера принадлежит умершему
        // рендереру: она переживёт его и останется чёрным прямоугольником поверх всего,
        // с погашенной панелью и BACK как единственным выходом.
        //
        // Обратный вызов снимаем до выхода: он принадлежит движку, чей рендерер только
        // что умер, и обращение к такому экземпляру бросает исключение - падение пришлось
        // бы ровно на обработчик, который написан, чтобы приложение не падало. Сообщать
        // «мы вышли» тому, кого через три строки ждёт `destroy()`, всё равно незачем.
        fullscreenCallback = null
        exitFullscreen()
        webView?.let { old ->
            if (killedInBackground) rememberUrl(old)
            root?.removeView(old)
            old.destroy()
        }
        webView = null
        client = null
        // Курсор снимается здесь, а не выше вместе с [closeInput] (`B-110`). Его состояние
        // привязано к умершему движку и переживает замену целиком: `dragging` остался бы
        // истинным, и первая протяжка после восстановления ушла бы новому WebView как
        // `ACTION_MOVE` без `ACTION_DOWN`; `scrollLocked` запер бы курсор в режиме прокрутки
        // с висящей подсказкой, а запертый режим зовёт [CursorController.drag] на каждый ход.
        //
        // **Именно здесь, ниже обнуления полей, а не рядом с [closeInput].** `stop()` шлёт
        // `CANCEL` через `page()`, а тот отдаёт `fullscreenView ?: webView` - то есть выше
        // по функции касание ушло бы в экземпляр, который вот-вот уничтожат, и падение
        // пришлось бы на обработчик, написанный ради того, чтобы приложение не падало.
        // После двух обнулений `page()` отдаёт null, и события не уходят никуда, а флаги
        // всё равно снимаются: `endDrag` и `endHold` ставят `dragging`/`holding = false`
        // **до** отправки.
        cursor?.stop()
        restoreMessage = when {
            killedInBackground && releasedUrl != null -> null
            crashed -> R.string.err_renderer_crashed
            else -> R.string.err_renderer_killed
        }

        if (started) restoreWebView()
    }

    private fun restoreWebView() {
        if (webView != null) return
        val parent = root ?: return
        val message = restoreMessage
        val url = releasedUrl
        restoreMessage = null
        releasedUrl = null

        // Та же причина, что и в onCreate: пакет System WebView может обновляться прямо
        // сейчас - и это одна из причин, по которой рендерер только что умер. Без обработки
        // приложение падает уже без всякого экрана-объяснения.
        val fresh = try {
            BrowserWebView(this)
        } catch (_: Exception) {
            setContentView(errorView())
            root = null
            // Иначе курсор остался бы жив поверх экрана-объяснения: он продолжал бы
            // забирать себе весь пульт и двигать View, которой больше нет на экране.
            cursor?.stop()
            cursor = null
            return
        }

        fresh.layoutParams = FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT,
        ).apply {
            // Тот же отступ, что и в разметке: иначе новая страница уехала бы под панель.
            topMargin = resources.getDimensionPixelSize(R.dimen.panel_height)
        }
        configure(fresh)
        // Индекс 0: новый WebView встаёт под панель и курсор, а не поверх них.
        parent.addView(fresh, 0)
        webView = fresh
        updatePanel()

        when {
            // Рендерер умер: объяснение важнее содержимого, страницу возвращать нечем.
            // Экран наш, но управлять на нём нечем - ни команд, ни решения, поэтому
            // и состояние у него NONE.
            message != null -> screens?.showRendererError(message)
            // Движок отпустили ради памяти (ADR-019) - возвращаем то, что читали.
            // Через клиент, а не `loadUrl`: адрес мог быть открыт до того, как проверки
            // ужесточились, и восстановление не должно быть дырой в обход них.
            url != null -> client?.load(fresh, url)
            else -> screens?.showHome()
        }
    }

    /**
     * Освобождение памяти по требованию системы.
     *
     * Реагируем начиная с [TRIM_MEMORY_UI_HIDDEN] - то есть только когда нас не видно.
     * Уровни `RUNNING_*` приходят, пока страница на экране, и чистить кэш там значило бы
     * замедлить сайт, которым пользователь прямо сейчас пользуется.
     *
     * Кэш чистится только в памяти: удаление файлов с диска заставит перекачивать
     * ресурсы заново, а на телевизоре канал обычно узкий.
     *
     * Проверка на null здесь не формальность: колбэк приходит и после `onDestroy`,
     * когда WebView уже уничтожен.
     */
    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        if (level >= TRIM_MEMORY_UI_HIDDEN) {
            webView?.clearCache(false)
        }
        // С MODERATE система уже выбирает, кого убить, и мы в этом списке (ADR-019).
        // Отдать движок самим лучше, чем дождаться, пока систему убьёт наш рендерер:
        // адрес мы запоминаем и страницу вернём, а смерть рендерера вернуть нечем.
        if (level >= TRIM_MEMORY_MODERATE && !started) {
            releaseWebView()
        }
    }

    /**
     * Отдать движок вместе с открытой страницей (ADR-019).
     *
     * Живой WebView с разобранной страницей стоит десятки мегабайт приватной памяти,
     * и в фоне это память, отнятая у телевизора. Отпускаем только по сигналу системы:
     * пока памяти хватает всем, страница ждёт возвращения пользователя.
     */
    private fun releaseWebView() {
        val web = webView ?: return
        // Плеер уходит вместе с движком, который его нарисовал: иначе возврат из лаунчера
        // показывает мёртвый кадр поверх восстановленной страницы (та же причина,
        // что и в [onRendererGone]).
        exitFullscreen()
        rememberUrl(web)
        root?.removeView(web)
        web.destroy()
        webView = null
        client = null
    }

    /**
     * Запомнить, что читал пользователь, чтобы вернуть это при следующем показе.
     *
     * Наши собственные страницы грузятся с пустым происхождением - возвращать по адресу
     * нечего, при следующем показе встанет обычная стартовая. Схема проверяется строкой,
     * без разбора URI: метод вызывается в момент, когда системе не хватает памяти.
     */
    private fun rememberUrl(web: WebView) {
        val url = web.url
        releasedUrl = if (url != null && (url.startsWith("http://") || url.startsWith("https://"))) {
            url
        } else {
            null
        }
    }

    /**
     * D-pad уводится курсору целиком. Возврат true отсюда означает, что дальше событие
     * не пойдёт: иначе WebView параллельно двигал бы фокус по своей навигации, а на
     * большинстве сайтов она работает плохо - ради этого курсор и делается (ADR-005).
     */
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        // BACK в полноэкранном режиме перехватывается здесь, до всех остальных: View,
        // которую движок отдаёт в onShowCustomView, забирает BACK себе и возвращает
        // true, поэтому onBackPressed до нас не доходит и goBackOrFinish не зовётся
        // вовсе. Без этой ветки из полноэкранного режима нельзя выйти ничем: своей
        // кнопки «свернуть» плеер даёт не всегда, а других клавиш для этого на пульте
        // нет (`B-65`, найдено 2026-08-12).
        if (fullscreenView != null && event.keyCode == KeyEvent.KEYCODE_BACK) {
            // Событие не пускается дальше целиком, обоими действиями: иначе нажатие
            // получит и плеер, и мы, а выход считается по отпусканию - так удержание
            // BACK не превращается в очередь выходов.
            if (event.action == KeyEvent.ACTION_UP) exitFullscreen()
            return true
        }
        // BACK выходит из режима прокрутки, не уходя со страницы (ADR-046): человек
        // прилип курсором к списку, передумал и хочет вернуть курсору свободу -
        // терять при этом открытую страницу он не просил.
        if (event.keyCode == KeyEvent.KEYCODE_BACK && event.action == KeyEvent.ACTION_UP &&
            cursor?.onBack() == true
        ) {
            return true
        }
        // Клавиатура забирает пульт себе, только если пользователь сам выбрал этот
        // способ управления. По умолчанию по клавишам ходят курсором - тем же самым,
        // что и по странице: клавиатура нарисована такими же View внутри нашего корня.
        if (keyboardOpen() && panel?.keyboard?.onKeyEvent(event) == true) return true
        if (cursor?.onKeyEvent(event) == true) return true
        return super.dispatchKeyEvent(event)
    }

    /**
     * BACK на пульте - шаг назад по странице, и только с первой страницы - выход
     * из приложения. Это ожидаемое поведение браузера, а на телевизоре других способов
     * вернуться у пользователя нет вовсе.
     */
    private fun goBackOrFinish() {
        // Полноэкранное видео BACK сворачивает - первое, чего от него ждут, когда
        // на экране фильм. Уйти со страницы, оставив плеер на весь экран, нельзя:
        // его View переживёт навигацию и останется висеть поверх новой страницы.
        if (fullscreenView != null) {
            exitFullscreen()
            return
        }

        // Набор адреса BACK отменяет, а не уводит со страницы: это ближайшее назад,
        // которого ждёт пользователь, и единственный способ выйти из поля,
        // если клавиатура уже закрылась.
        if (editing) {
            stopEditing()
            return
        }

        // Переименование BACK отменяет по той же причине: название остаётся прежним.
        if (renameIndex >= 0) {
            stopRename()
            return
        }

        // Открытое меню BACK закрывает: уйти со страницы, не убрав меню, пользователь
        // не просил, а другого способа закрыть его с пульта, кроме клика мимо, нет.
        if (menuOpen()) {
            hideMenu()
            return
        }

        // То же и с клавиатурой: она перекрывает низ страницы, и первое, чего ждут
        // от BACK при открытой клавиатуре, - убрать её, а не уйти со страницы.
        if (keyboardOpen()) {
            closeInput()
            return
        }

        // На домашней странице BACK закрывает браузер, а не идёт по истории. Без этого
        // выйти с пульта нельзя вовсе (B-86, живой ТВ 2026-08-15): каждый локальный экран
        // оставляет в истории запись, возврат ходит по ним и до её конца не добирается.
        // Это и обычное поведение браузера: домашняя страница - начало, дальше только выход.
        if (screens?.screen == LocalScreen.HOME) {
            finish()
            return
        }

        val web = webView
        val client = this.client
        if (web != null && client != null && client.goBack(web)) return

        // Возвращаться по истории некуда - но это ещё не повод закрывать браузер (`B-112`).
        // Экраны, которые рисует клиент (ошибка сети, сертификат, подделка, согласие
        // на http), попадают в историю как `data:` и `about:` и фильтром возврата
        // отсекаются нарочно (H-4). Значит с такого экрана BACK упирался в `finish()`
        // и закрывал браузер - в том числе когда человек пришёл на него **с домашней**.
        // Домашняя - единственное место, откуда BACK выходит (`B-86`), и проверка выше
        // это уже разобрала: сюда мы попадаем, только если мы не там.
        screens?.showHome()
    }

    /**
     * Путь для Android 13 и новее. Приложениям с `targetSdk` 16 и выше система начиная
     * с Android 16 не вызывает [onBackPressed] и не рассылает `KEYCODE_BACK` вовсе -
     * кнопка приходит только сюда. У нас `targetSdk` 37, то есть это единственный путь
     * на всех новых платформах. Диспетчер платформенный: AndroidX ради одной кнопки в проект
     * не тянем, зависимостей у модуля ноль.
     */
    private fun registerBackHandler() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        onBackInvokedDispatcher.registerOnBackInvokedCallback(
            OnBackInvokedDispatcher.PRIORITY_DEFAULT,
        ) { goBackOrFinish() }
    }

    /**
     * Путь для Android 8..12, где диспетчера ещё нет. Подавление осознанное: на версиях,
     * где предупреждение линта справедливо, работает [registerBackHandler], а этот метод
     * там уже не вызывается.
     */
    @Suppress("DEPRECATION", "GestureBackNavigation", "MissingSuperCall")
    override fun onBackPressed() = goBackOrFinish()

    /**
     * Останавливаем WebView, когда экран ушёл. На телевизоре приложение почти всегда
     * в фоне, и там оно обязано не мешать остальным: `onPause` гасит отрисовку
     * и анимации, `pauseTimers` - таймеры JavaScript во всех WebView процесса.
     *
     * Выбран именно `onStop`, а не `onPause`: последний срабатывает и когда поверх
     * приложения появился диалог, то есть когда страница ещё видна пользователю.
     */
    override fun onStop() {
        started = false
        // Экран ушёл вместе с открытой клавиатурой - возвращаться в наполовину
        // набранный адрес незачем. А если её оставить, то после ADR-019 (движок
        // отпущен в фоне) пользователь вернётся на перезагруженную страницу
        // с клавиатурой, которой некуда писать.
        closeInput()
        hideMenu()
        // Клавиша могла остаться зажатой в момент ухода с экрана: ACTION_UP за ней
        // уже не придёт, и цикл кадров крутился бы в фоне.
        cursor?.stop()
        // По той же причине снимается опрос ввода (ADR-037): он останавливается сам
        // только по выходу из полноэкранного режима, а уход с экрана - не выход.
        // Замер в фоне: 5 тиков процессорного времени за две минуты против 1 без него.
        // Дело не в этих сорока миллисекундах, а в том, что процесс будят три раза
        // в секунду и он не уходит в сон, - в фоне у нас не должно работать ничего.
        root?.removeCallbacks(watchInputInFullscreen)
        webView?.let { web ->
            web.onPause()
            web.pauseTimers()
            web.applyRendererPriority(visible = false)
        }
        super.onStop()
    }

    /**
     * Сигнал `TRIM_MEMORY_MODERATE`, по которому мы отдаём движок (ADR-019), означает,
     * что система ищет, кого убить. Нас она может убить следом - и тогда единственная
     * копия адреса пропала бы вместе с процессом, а обещание «страницу вернём»
     * не выполнилось бы ровно в том случае, ради которого всё это делается.
     */
    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        webView?.let { web -> rememberUrl(web) }
        releasedUrl?.let { url -> outState.putString(STATE_URL, url) }
    }

    override fun onStart() {
        super.onStart()
        started = true
        restoreWebView()
        // Вернулись в полноэкранный режим, снятый с паузы: опрос ввода снят в `onStop`
        // и обязан подняться обратно, иначе страница поднимет системную клавиатуру
        // телевизора и `B-77` вернётся. Если движок в фоне отдали (ADR-019),
        // `fullscreenView` уже null и поднимать нечего.
        if (fullscreenView != null) root?.postDelayed(watchInputInFullscreen, IME_POLL_MS)
        restoreUpdateButtons()
        webView?.let { web ->
            web.onResume()
            web.resumeTimers()
            web.applyRendererPriority(visible = true)
        }
    }

    /**
     * Порядок важен: сначала снять WebView с родителя, потом destroy().
     * Иначе WebView удерживает ссылку на Activity и та не собирается сборщиком мусора.
     */
    override fun onDestroy() {
        webView?.let { web ->
            (web.parent as? ViewGroup)?.removeView(web)
            web.destroy()
        }
        webView = null
        client = null
        cursor = null
        root = null
        panel = null
        // Держит Context этой Activity и Storage - обнуляется по тому же правилу,
        // что и остальные поля, а не потому, что течёт: замер циклов утечки не показал.
        screens = null
        // Фоновый поток записи гасится вместе со всем остальным: после ухода браузера
        // с экрана у него не должно остаться ни одной живой задачи.
        storage?.close()
        storage = null
        super.onDestroy()
    }

    private fun errorView(): TextView = TextView(this).apply {
        text = getString(R.string.error_no_webview)
        textSize = 22f
        gravity = Gravity.CENTER
        setPadding(96, 96, 96, 96)
    }

    private companion object {
        /** Насколько гаснет кнопка панели, которой сейчас нечего делать. */
        const val DISABLED_ALPHA = 0.3f

        /**
         * Опрос страницы про элемент под курсором: есть ли под этой точкой что-то
         * со своей прокруткой (`B-93`). Разрезан ровно там, где подставляются доли
         * координат, - всё остальное неизменно и живёт константой (`B-127`).
         */
        const val SCROLLER_HEAD =
            "(function(){try{var w=window.innerWidth,h=window.innerHeight;" +
                "var e=document.elementFromPoint("

        const val SCROLLER_TAIL =
            ");" +
                "while(e&&e!==document.body&&e!==document.documentElement){" +
                "var s=window.getComputedStyle(e),oy=s.overflowY,ox=s.overflowX;" +
                "var vy=(oy==='auto'||oy==='scroll')&&e.scrollHeight-e.clientHeight>2;" +
                "var vx=(ox==='auto'||ox==='scroll')&&e.scrollWidth-e.clientWidth>2;" +
                "if(vy||vx){var r=e.getBoundingClientRect();" +
                "return [r.left/w,r.top/h,r.right/w,r.bottom/h," +
                "(vy&&e.scrollTop>1)?1:0,(vy&&e.scrollHeight-e.clientHeight-e.scrollTop>1)?1:0," +
                "(vx&&e.scrollLeft>1)?1:0,(vx&&e.scrollWidth-e.clientWidth-e.scrollLeft>1)?1:0," +
                "0].join(',');}" +
                // Чужая вставка (`B-101`). Внутрь смотреть нельзя правилом движка, поэтому
                // границы её собственные, а стороны - все: куда ей есть куда ехать, знает
                // только она сама. Отвечает за это не скрипт, а курсор: он отпустит вставку,
                // как только увидит, что прокрутку взял документ под ней.
                //
                // Мелкие пропускаются: счётчики, пиксели и тонкие полоски прокручивать
                // нечего. Отсечь по размеру саму рекламу этот порог не может и не пытается -
                // типовой блок 300x250 крупнее любого разумного порога, - за это отвечает
                // проверка делом в `CursorController.tryInner`: придержали на доверии -
                // и сразу смотрим, взял ли вложенный элемент прокрутку на самом деле.
                "if(e.tagName==='IFRAME'){var q=e.getBoundingClientRect();" +
                "if(q.right-q.left>=100&&q.bottom-q.top>=100){" +
                "return [q.left/w,q.top/h,q.right/w,q.bottom/h,1,1,1,1,1].join(',');}}" +
                "e=e.parentElement;}return '';}catch(err){return '';}})()"


        /**
         * Разрешение на домашнюю сеть (ADR-061). Строкой, а не через `Manifest.permission`:
         * константа появилась в API 37, а собираемся мы для 26 - на старых версиях
         * этой ветки просто нет, и подставлять туда символ из нового SDK незачем.
         */
        const val LOCAL_NETWORK = "android.permission.ACCESS_LOCAL_NETWORK"

        /** Android 17: с него домашняя сеть закрыта разрешением. */
        const val ANDROID_17 = 37

        /** Код запроса разрешения на домашнюю сеть. */
        const val REQ_LOCAL_NETWORK = 1

        /** Ключ сохранённого адреса: переживает смерть процесса в фоне (ADR-019). */
        const val STATE_URL = "url"

        /** Сколько висит плашка с хостом при входе в полноэкранный режим (C-4). */
        const val FULLSCREEN_HOST_MS = 3_500L

        /** Как часто в полноэкранном режиме проверяется, не просят ли ввод (`B-77`). */
        const val IME_POLL_MS = 300L

        /**
         * Пара изоляции двунаправленного текста (требование C-2, `B-17`): FSI открывает
         * изолят, PDI закрывает. Записаны кодами намеренно - сами символы невидимы,
         * и в исходнике их не отличить ни от пустоты, ни друг от друга.
         */
        const val ISOLATE_START = '\u2068'
        const val ISOLATE_END = '\u2069'

        /** Имя каталога данных движка у платформы: `getDir` превращает его в `app_webview`. */
        const val WEBVIEW_DIR = "webview"

        /**
         * Что очистка сносит файлами внутри каталога движка (E-3, ADR-036): воркеры
         * вместе с их Cache Storage, localStorage, базы страниц, IndexedDB, метку
         * установки, которую движок заводит себе сам, и реестр квот - он хранит имена
         * origin и своей асинхронной чистки не переживает (`B-73`). Имена принадлежат
         * движку, не нам, - список сверен с тем, что он создаёт на устройстве (`B-67`).
         */
        val WIPED_DIRS =
            listOf(
                "Service Worker", "Local Storage", "databases", "IndexedDB", "metrics_guid",
                "QuotaManager", "QuotaManager-journal",
            )

        /** Сетевой кэш движка - он лежит не у движка, а в `cacheDir` приложения. */
        const val ENGINE_CACHE_DIR = "org.chromium.android_webview"
    }
}
