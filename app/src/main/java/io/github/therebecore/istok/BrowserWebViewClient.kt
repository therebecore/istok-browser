package io.github.therebecore.istok

import android.graphics.Bitmap
import android.net.Uri
import android.net.http.SslError
import android.webkit.RenderProcessGoneDetail
import android.webkit.SafeBrowsingResponse
import android.webkit.SslErrorHandler
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import java.io.ByteArrayInputStream
import java.util.UUID
import java.util.concurrent.CopyOnWriteArraySet

/**
 * Решения о навигации и обработка ошибок загрузки.
 *
 * [onRendererGone] вызывается, когда умер процесс рендерера: сам WebView после этого
 * непригоден и его обязана пересоздать Activity - здесь нет доступа к иерархии View.
 */
internal class BrowserWebViewClient(
    private val onRendererGone: (crashed: Boolean) -> Unit,
    /**
     * Показан экран, на котором пользователь принимает решение о безопасности.
     * Требование H-1: такой экран не должен принимать клик, подгаданный страницей
     * под нажатие OK - подробности в `CursorController.onDecisionScreen`.
     */
    private val onDecisionScreen: () -> Unit,
    /** Состояние навигации изменилось: панели пора пересчитать активность кнопок. */
    private val onNavigation: () -> Unit,
    /**
     * Началась загрузка другой страницы. Отдельно от [onNavigation], который зовётся
     * ещё и на `pushState`: там страница остаётся той же самой, и закрывать клавиатуру
     * у человека из-под рук нельзя - одностраничные сайты меняют адрес прямо во время
     * набора в своём же поле.
     */
    private val onLoadStarted: () -> Unit,
    /**
     * Страница показана и её заголовок уже известен - момент записи в историю (ADR-025).
     * Отдельно от [onNavigation] именно из-за заголовка: тот приходит к концу загрузки,
     * а [onNavigation] зовётся и в её начале, когда заголовок ещё от прошлой страницы.
     */
    private val onVisited: () -> Unit,
    /**
     * Нажата плитка домашней страницы: номер избранного или [LAST_ID]. Сам адрес
     * известен только приложению - в разметке страницы его нет вовсе, см. [homePage].
     */
    private val onOpen: (String?) -> Unit,
    /**
     * Команда локального экрана: имя и сегменты пути. Всё, что меняет состояние, несёт
     * первым сегментом одноразовый ключ, и проверяет его приложение - оно же его выдало,
     * собирая страницу (требование H-8).
     */
    private val onCommand: (String, List<String>) -> Unit,
    /**
     * Адрес ведёт в домашнюю сеть, и её нужно разрешить (ADR-061). `true` - можно
     * грузить прямо сейчас, `false` - загрузка отменяется, потому что на экране висит
     * системный запрос разрешения; ответ на него повторит загрузку сам.
     */
    private val onLocalNetwork: (String) -> Boolean,
    /**
     * Сказать коротко и не занимая экрана: переход не состоялся, а страница под ним
     * жива (ADR-063). Единственный сегодня повод - сертификат адреса, на который увела
     * сама страница; полноэкранная ошибка на её месте снесла бы то, что человек смотрел.
     */
    private val onQuietNotice: (String) -> Unit,
    /**
     * Показан наш экран вместо страницы: ошибка сети, сертификат, подделка, блокировка,
     * согласие на http. Списки о таком не знают, потому что рисует эти экраны клиент,
     * а не `LocalScreens` (`B-112`). Пока состояние там оставалось прежним, BACK
     * с экрана «Подделка адреса», открытого **с домашней**, закрывал браузер:
     * `goBackOrFinish` видел `LocalScreen.HOME` и честно выходил.
     */
    private val onOwnScreen: () -> Unit,
) : WebViewClient() {

    /**
     * Источники, для которых пользователь разрешил http на эту сессию (ADR-011).
     *
     * Ключ - `хост:порт`, а не один хост: `http://example.com` и `http://example.com:8080`
     * это разные службы, и согласие на первую не является согласием на вторую.
     *
     * Живёт только в памяти и умирает вместе с процессом - на диск не пишется ничего.
     * Перезапуск браузера возвращает вопрос, и это намеренно: разрешение, выданное
     * однажды и навсегда, пользователь забудет через день.
     *
     * Тип потокобезопасный не для красоты: читается из сетевого потока
     * в [shouldInterceptRequest], а пишется из главного.
     */
    private val allowedHttpOrigins = CopyOnWriteArraySet<String>()

    /**
     * Подтверждение http, которое сейчас показано на экране. Только главный поток.
     *
     * Всю защиту здесь несёт одноразовый [Pending.nonce]. Кнопка на нашем экране ведёт
     * на `istok://allow-http/<nonce>`, и это значение существует ровно в одном месте -
     * в DOM нашего же документа, загруженного с пустым происхождением и без единой
     * строчки JavaScript. Чужой странице его неоткуда взять: наружу оно не уходит,
     * при переходе грузится [Pending.url] без него.
     *
     * Отсюда следуют обе гарантии. Ссылку `istok://allow-http` без nonce, размещённую
     * на чужом сайте, мы не примем. Документ, подсунутый поверх нашего экрана, не сможет
     * нарисовать кнопку с нужным адресом. Первая же неверная попытка сжигает
     * подтверждение: [handleInternal] стирает поле до сверки, перебора нет.
     *
     * Инвариант, на котором всё держится: **на локальных страницах JavaScript
     * не используется**. Появится - понадобится проверять и происхождение клика
     * (`request.hasGesture()`).
     */
    private var pending: Pending? = null

    /**
     * Адрес последней навигации **главного фрейма**. По нему отличается ошибка самой
     * страницы от ошибки её подресурса там, где платформа этого не говорит, -
     * в [onReceivedSslError]. Пишется в [shouldInterceptRequest].
     *
     * `@Volatile` по той же причине, что и у [allowedHttpOrigins]: пишется из сетевого
     * потока, читается из главного. Без него главный поток вправе не увидеть последнюю
     * запись, сверка хостов сравнит адрес предыдущей страницы - и экран ошибки
     * сертификата либо не покажется вовсе, либо назовёт не ту причину.
     */
    @Volatile
    private var mainFrameUrl: Uri? = null

    /**
     * Адрес, который попросили открыть **мы сами**: адресная строка, закладка, история,
     * восстановление после отпущенного движка. Пишется в [load] и [reload] - в двух
     * единственных местах, откуда исходит наша собственная навигация.
     *
     * Нужен ошибке сертификата (ADR-063). Экран ошибки уместен там, где человек сам
     * попросил этот адрес: ему объясняют, почему не открылось. Там, где адрес выбрала
     * страница, объяснять нечего - зато есть что терять.
     */
    private var requestedUrl: String? = null

    /**
     * Адрес страницы, которая **реально показана**. Пишется в [onPageCommitVisible] -
     * единственном колбэке, который приходит по факту отрисовки, а не по началу загрузки:
     * навигация, сорвавшаяся на рукопожатии TLS, сюда не попадает вовсе.
     */
    private var committedUrl: String? = null

    private class Pending(val url: String, val origin: String, val nonce: String)

    /**
     * Забыть висящее согласие (требование H-2). Нужно снаружи потому, что наши
     * собственные экраны грузятся `loadDataWithBaseURL` мимо [load] - единственного
     * места, где согласие сбрасывалось само. Уход с экрана подтверждения кнопкой
     * «домой» его не отменял, и объект жил дальше без своего экрана.
     */
    fun forgetPending() {
        pending = null
    }

    /**
     * Панель обновляется по началу и по концу загрузки: раньше об этих моментах
     * знать было некому, теперь от них зависит, какие кнопки активны.
     */
    override fun onPageStarted(view: WebView, url: String, favicon: Bitmap?) {
        // Поля прежней страницы больше нет, а вместе с ней устарело и соединение ввода:
        // без этого символы уходили бы в документ, которого на экране уже не осталось.
        (view as? BrowserWebView)?.releaseConnection()
        onLoadStarted()
        onNavigation()
    }

    override fun onPageFinished(view: WebView, url: String) {
        onNavigation()
        onVisited()
    }

    /**
     * Страница дошла до первого кадра, то есть заменила собой предыдущую. Только отсюда
     * видно, что именно сейчас на экране: [onPageStarted] приходит и на ту навигацию,
     * которая до экрана не доберётся (ADR-063).
     */
    /**
     * Показать наш экран вместо страницы - **единственная точка** на все экраны клиента.
     * Здесь же гасится состояние списков (`B-112`): рисуем не мы `LocalScreens`, и без
     * этого BACK на «Подделке адреса», открытой с домашней, закрывал браузер.
     */
    private fun WebView.showOwn(html: String) {
        onOwnScreen()
        showLocal(html)
    }

    override fun onPageCommitVisible(view: WebView, url: String) {
        committedUrl = url
    }

    /**
     * Единственный колбэк, который приходит на `history.pushState` - загрузки при этом
     * не происходит, а адрес страницы меняется. Без него адресная строка показывала бы
     * прежний адрес сколь угодно долго, то есть врала бы (требование C-2).
     */
    override fun doUpdateVisitedHistory(view: WebView, url: String, isReload: Boolean) {
        onNavigation()
        // Переход внутри одностраничного сайта - для истории это такая же смена адреса,
        // как обычная загрузка, и другого сигнала о ней не будет.
        onVisited()
    }

    /**
     * Загрузка адреса, пришедшего от нас самих: ввод в адресной строке и восстановление
     * страницы после того, как движок отпустили ради памяти (ADR-019).
     *
     * Отдельный метод существует не для порядка. `loadUrl` **не вызывает**
     * [shouldOverrideUrlLoading] - это документированное поведение платформы, и значит
     * весь блок проверок оттуда для нашей собственной навигации не исполняется вовсе.
     * Требование C-5 (`https://sberbank.ru@203.0.113.5/` читается как адрес банка,
     * а ведёт на чужой сервер) держалось только там - и через адресную строку обходилось.
     * Гейт http при этом уцелел бы и без этой проверки: он продублирован
     * в [shouldInterceptRequest], который вызывается для всех загрузок.
     *
     * Правило на будущее: **любая наша собственная загрузка идёт через этот метод.**
     * На Этапе 6 точек вызова станет больше - домашняя страница, история, закладки.
     */
    fun load(view: WebView, url: String) {
        // Уход с экрана подтверждения обесценивает его, каким бы ни был новый адрес
        // (требование H-2). Историческая и программная навигация мимо
        // shouldOverrideUrlLoading не проходят и сами подтверждение не сбросят.
        pending = null

        val uri = Uri.parse(url)

        // Белый список схем - тот же, что в shouldOverrideUrlLoading. Сегодня сюда
        // приходит только http/https, но на Этапе 6 точек вызова станет больше:
        // `javascript:`-адрес, попавший в историю или в закладки, выполнился бы
        // в контексте открытой страницы мимо всех колбэков - shouldInterceptRequest
        // для него не вызывается вовсе.
        val scheme = uri.scheme?.lowercase()
        if (scheme != "http" && scheme != "https") {
            view.showOwn(view.blockedPage(uri))
            return
        }

        if (uri.userInfo != null) {
            view.showOwn(view.spoofPage(uri))
            return
        }

        // Домашняя сеть требует разрешения (ADR-061). Спрашивается здесь, а не при
        // запуске: вопрос без повода человек не понимает, а с адресом роутера на экране
        // понимает сразу.
        if (isLocalNetworkHost(uri.host) && !onLocalNetwork(url)) return

        requestedUrl = url
        view.loadUrl(url)
    }

    /**
     * Разрешения на домашнюю сеть не дали (ADR-061). Показывается ровно то, что
     * произошло: адрес внутри дома, доступа к дому нет. Без этого экрана попытка просто
     * висела бы до таймаута - система молча отбрасывает такие соединения.
     */
    fun showNoLocalNetwork(view: WebView, url: String) {
        view.showOwn(
            errorPage(
                title = view.str(R.string.err_lan_title),
                message = view.str(R.string.err_lan_message),
                detail = displayHost(Uri.parse(url)),
            )
        )
    }

    /** Обновление страницы. Отдельный метод - ради того же сброса подтверждения. */
    fun reload(view: WebView) {
        pending = null
        requestedUrl = view.url
        view.reload()
    }

    override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
        val url = request.url
        val scheme = url.scheme?.lowercase()

        // Подфрейм собой весь экран не занимает: наш экран поверх страницы пользователя -
        // это то, чем реклама в iframe затирала бы открытый сайт, а клик по такому экрану
        // повышал бы адрес фрейма до навигации верхнего уровня. Поэтому здесь только
        // да или нет, без своих страниц.
        // Требование C-5: `https://sberbank.ru@203.0.113.5/` ведёт на 203.0.113.5, но
        // читается как адрес банка. Проверка стоит до разбора схемы: раньше она была
        // только в ветке http, и по https такой адрес открывался беспрепятственно.
        // Адресной строки ещё нет, но она появится на Этапе 4 - и покажет чужой хост.
        if (url.userInfo != null) {
            if (request.isForMainFrame) {
                // Тот же сброс, что и ниже по коду: уход с экрана подтверждения
                // обесценивает его, каким бы ни был новый адрес.
                pending = null
                view.showOwn(view.spoofPage(url))
            }
            return true
        }

        if (!request.isForMainFrame) {
            return when (scheme) {
                "https" -> false
                "http" -> !isHttpAllowed(url)
                "about" -> false
                else -> true
            }
        }

        if (scheme == INTERNAL_SCHEME) {
            handleInternal(view, url)
            return true
        }

        // Любая другая навигация обесценивает висящее подтверждение: пользователь ушёл
        // с нашего экрана, и старое намерение больше не подтверждается этим кликом.
        pending = null

        // Ссылка в домашнюю сеть - тот же случай, что и набранный адрес (ADR-061).
        if ((scheme == "http" || scheme == "https") &&
            isLocalNetworkHost(url.host) &&
            !onLocalNetwork(url.toString())
        ) {
            return true
        }

        return when (scheme) {
            "https" -> false

            "http" -> if (isHttpAllowed(url)) {
                false
            } else {
                askHttp(view, url)
                true
            }

            // `about:blank` из чужого приложения не ведёт никуда: это пустой документ,
            // и через него работает распространённый приём `window.open('about:blank')`
            // с последующей записью в него. Мы окон не заводим (ADR-004), так что приём
            // приходит сюда обычной навигацией - и заблокировать его значит снести
            // открытую пользователем страницу ради ничего.
            "about" -> false

            // Требование C-3: наружу не выпускаем ничего. Опаснее прочих intent://,
            // через который страница запускает компоненты чужих приложений
            // с подконтрольными ей параметрами.
            else -> {
                view.showOwn(view.blockedPage(url))
                true
            }
        }
    }

    /**
     * Требование B-4: последняя проверка http перед выходом в сеть.
     *
     * [shouldOverrideUrlLoading] пропускает часть навигаций мимо себя - документировано,
     * что он не вызывается для отправки формы методом POST, и это не единственный случай.
     * Через такую навигацию http-страница открылась бы вообще без вопроса. Здесь запрос
     * отменяется до отправки, то есть тело формы на сервер не уходит.
     *
     * Метод вызывается в сетевом потоке и на каждый подресурс страницы, поэтому первым
     * делом отсекается всё, кроме навигации главного фрейма: подмешанный в https-страницу
     * http-подресурс закрыт настройкой `MIXED_CONTENT_NEVER_ALLOW` (требование B-1).
     */
    override fun shouldInterceptRequest(
        view: WebView,
        request: WebResourceRequest,
    ): WebResourceResponse? {
        if (!request.isForMainFrame) return null

        val url = request.url
        // Единственное место, где точно видно адрес главного фрейма и видно его раньше
        // сетевого запроса. Нужен он ошибке сертификата: та про фрейм ничего не знает,
        // а показывать экран поверх работающей страницы из-за стороннего счётчика нельзя.
        mainFrameUrl = url

        // Требование C-5, вторая линия и единственная надёжная. `Uri.parse` и парсер
        // Chromium расходятся: строку без `//` после схемы (`https:sberbank.ru@203.0.113.5/`)
        // `Uri` считает opaque и отдаёт `userInfo == null`, а Chromium пропускает любое
        // число слэшей и разбирает остаток как authority - то есть открывает чужой сервер.
        // Проверка перед загрузкой такой адрес не ловит, а сюда он приходит уже
        // канонизированным движком. Здесь же закрывается серверный редирект на userinfo:
        // shouldOverrideUrlLoading вызывается для редиректов не во всех версиях WebView.
        if (url.userInfo != null) {
            view.post {
                if (view.parent == null) return@post
                pending = null
                view.stopLoading()
                view.showOwn(view.spoofPage(url))
            }
            return blank()
        }

        if (url.scheme?.lowercase() != "http") return null
        if (isHttpAllowed(url)) return null

        // Пустой ответ - это не отмена навигации, а её завершение пустым документом,
        // и он остаётся в истории записью по адресу заблокированного хоста. На BACK
        // такая запись не приводит: разрешения на этот источник нет, а http без
        // разрешения не возвращаем - см. canReturnTo.
        view.post {
            // Между постановкой этой задачи в сетевом потоке и её исполнением WebView мог
            // умереть вместе с рендерером и быть снятым с экрана. Обращение к уничтоженному
            // экземпляру - это исключение и падение приложения на ровном месте.
            if (view.parent == null) return@post

            view.stopLoading()
            askHttp(view, url)
        }
        return blank()
    }

    /** Пустой ответ: навигация завершается пустым документом, в сеть ничего не уходит. */
    private fun blank() =
        WebResourceResponse("text/html", "utf-8", ByteArrayInputStream(ByteArray(0)))

    private fun askHttp(view: WebView, url: Uri) {
        val origin = httpOrigin(url)
        if (origin == null) {
            view.showOwn(view.blockedPage(url))
            return
        }

        val nonce = UUID.randomUUID().toString()
        pending = Pending(url.toString(), origin, nonce)
        onDecisionScreen()

        view.showOwn(
            httpConfirmPage(
                title = view.str(R.string.http_title),
                message = view.str(R.string.http_message),
                host = displayHost(url),
                url = url.toString(),
                actionHref = "$INTERNAL_SCHEME://$CMD_ALLOW_HTTP/$nonce",
                actionLabel = view.str(R.string.http_action),
            )
        )
    }

    private fun handleInternal(view: WebView, url: Uri) {
        // Переход по плитке домашней страницы. Ключа не требует, в отличие от команд,
        // которые что-то меняют (требование H-8): ссылку `istok://open/0` может
        // разместить и чужая страница, но всё, чего она этим добьётся, - уведёт
        // пользователя на его же закладку, а увести его она и так может обычной ссылкой.
        if (url.host == CMD_OPEN) {
            onOpen(url.pathSegments.firstOrNull())
            return
        }

        // Экран закладок и переключение режима правки - это переходы, а не изменения:
        // ключа не требуют по тому же правилу, что и `open` (H-8).
        if (url.host == CMD_BOOKMARKS ||
            url.host == CMD_CLEAR_ASK || url.host == CMD_EDIT || url.host == CMD_SETTINGS ||
            url.host == CMD_ABOUT || url.host == CMD_WIPE_ASK || url.host == CMD_DONATE
        ) {
            onCommand(url.host!!, emptyList())
            return
        }

        // История несёт вкладку в адресе, поэтому сегменты доходят до обработчика.
        // Переход, а не изменение: ключа не требует по тому же правилу, что и `open`.
        if (url.host == CMD_HISTORY) {
            onCommand(url.host!!, url.pathSegments)
            return
        }

        // Открытие поля переименования: ключа не требует, но номер записи несёт.
        if (url.host == CMD_RENAME) {
            onCommand(url.host!!, url.pathSegments)
            return
        }

        // Всё, что меняет состояние, идёт сюда: первый сегмент - ключ, дальше аргументы.
        // Проверяет ключ приложение - оно же его и выдавало, собирая страницу.
        if (url.host == CMD_FAVORITE || url.host == CMD_REMOVE || url.host == CMD_MOVE ||
            url.host == CMD_CLEAR || url.host == CMD_SET || url.host == CMD_WIPE ||
            url.host == CMD_PIN || url.host == CMD_MARK || url.host == CMD_CHECK_UPDATE ||
            url.host == CMD_LANG
        ) {
            onCommand(url.host!!, url.pathSegments)
            return
        }

        // Подтверждения не было вовсе - значит и кнопки на экране не было. Так выглядят
        // два случая, и в обоих правильно молча ничего не делать: второе нажатие OK на
        // пульте с автоповтором (первое уже разрешило адрес и начало грузить сайт -
        // затирать его чем-либо нельзя) и ссылка `istok://` с чужой страницы, которой
        // мы не дадим заменить себя нашим полноэкранным сообщением.
        val confirmed = pending ?: return
        pending = null

        if (url.host == CMD_ALLOW_HTTP && url.pathSegments.firstOrNull() == confirmed.nonce) {
            allowedHttpOrigins += confirmed.origin
            view.loadUrl(confirmed.url)
            return
        }

        // Кнопка на экране была, но пришло не то, что мы в неё положили. Своего кода
        // на этом экране нет и взяться такому переходу неоткуда, поэтому единственное
        // объяснение - подделка, и о ней пользователю нужно сказать.
        view.showOwn(
            errorPage(
                title = view.str(R.string.err_stale_title),
                message = view.str(R.string.err_stale_message),
            )
        )
    }

    /**
     * Шаг назад по истории. Возвращает false, если возвращаться некуда - тогда BACK
     * должен закрыть приложение.
     *
     * Просто `goBack()` здесь недостаточно. Отменённая http-навигация оставляет
     * в истории запись: пустой ответ из [shouldInterceptRequest] - это не отмена,
     * а завершение навигации пустым документом по адресу заблокированного хоста.
     * Рядом ложится запись нашего собственного экрана подтверждения. Ни на то,
     * ни на другое возврат смысла не имеет: пустой документ показывать нечего,
     * а кнопка на старом экране подтверждения уже не работает - одноразовый nonce
     * сгорел, и нажатие дало бы сообщение о подделке.
     *
     * Поэтому такие записи пропускаются: BACK уходит на ближайшую страницу,
     * которую действительно можно показать.
     */
    fun goBack(view: WebView): Boolean {
        // Уход назад обесценивает висящее подтверждение ровно так же, как любая другая
        // навигация - но исторический переход мимо shouldOverrideUrlLoading не проходит
        // и сам его не сбросит. Без этой строки экран подтверждения остался бы "открытым"
        // для кода: чужая страница смогла бы заменить себя нашим сообщением о подделке,
        // а настоящая ошибка загрузки по тому же адресу - потеряться в onReceivedError.
        pending = null

        val steps = stepsBack(view)
        if (steps == 0) return false

        view.goBackOrForward(-steps)
        return true
    }

    /**
     * Есть ли куда возвращаться. Кнопка «назад» на панели гаснет именно по этому ответу,
     * а не по `WebView.canGoBack()`: тот считает и записи, которые мы пропускаем,
     * и кнопка оставалась бы активной там, где нажатие ничего не делает.
     */
    fun canGoBack(view: WebView): Boolean = stepsBack(view) > 0

    /**
     * Шаг вперёд по истории. Фильтр тот же, что и у [goBack], и по той же причине:
     * пропущенные при возврате записи - пустой документ и сгоревший экран подтверждения -
     * остаются впереди, и без фильтра «вперёд» вело бы ровно на них (требование H-4,
     * оно про навигацию по истории, а не про одно направление).
     */
    fun goForward(view: WebView): Boolean {
        pending = null

        val steps = stepsForward(view)
        if (steps == 0) return false

        view.goBackOrForward(steps)
        return true
    }

    fun canGoForward(view: WebView): Boolean = stepsForward(view) > 0

    private fun stepsForward(view: WebView): Int {
        val list = view.copyBackForwardList()
        var steps = 1
        while (list.currentIndex + steps < list.size &&
            !canReturnTo(list.getItemAtIndex(list.currentIndex + steps).url)
        ) {
            steps++
        }
        return if (list.currentIndex + steps >= list.size) 0 else steps
    }

    /**
     * На сколько записей назад лежит ближайшая, которую есть смысл показать.
     * 0 - такой записи нет.
     */
    private fun stepsBack(view: WebView): Int {
        val list = view.copyBackForwardList()
        var steps = 1
        while (list.currentIndex - steps >= 0 &&
            !canReturnTo(list.getItemAtIndex(list.currentIndex - steps).url)
        ) {
            steps++
        }
        return if (list.currentIndex - steps < 0) 0 else steps
    }

    /**
     * Фильтр здесь построен как белый список - так же, как фильтр схем для навигации
     * (требование H-4). Чёрный список пропускал бы всё, что попадает в историю мимо
     * [shouldOverrideUrlLoading]: `blob:`, `filesystem:`, а на Этапе 6 - собственную
     * схему локальных страниц. Наши экраны при этом отсекаются схемами `about` и `data`:
     * через них попадает в историю всё, что грузится `loadDataWithBaseURL`
     * с пустым происхождением.
     */
    private fun canReturnTo(url: String): Boolean {
        val uri = Uri.parse(url)
        if (uri.userInfo != null) return false

        return when (uri.scheme?.lowercase()) {
            "https" -> true
            // Разрешение на http живёт до перезапуска браузера. Если оно ещё есть,
            // страница откроется как обычно; если нет - возврат снова упёрся бы
            // в вопрос про незащищённое соединение.
            //
            // Эта же строка отсекает пустые документы из shouldInterceptRequest:
            // они появляются только там, где разрешения не было, - иначе запрос
            // ушёл бы в сеть и пустого ответа не случилось бы вовсе.
            "http" -> isHttpAllowed(uri)
            else -> false
        }
    }

    private fun isHttpAllowed(url: Uri): Boolean {
        if (url.userInfo != null) return false
        val origin = httpOrigin(url) ?: return false
        return origin in allowedHttpOrigins
    }

    /**
     * Совпадает ли хост ошибки с хостом страницы, которую сейчас грузит главный фрейм.
     *
     * Не знаем адреса главного фрейма - считаем ошибку главной: пропустить настоящую
     * ошибку сертификата страшнее, чем показать лишний экран.
     */
    private fun isMainFrameHost(errorUrl: String?): Boolean {
        val main = mainFrameUrl?.host?.lowercase() ?: return true
        val host = Uri.parse(errorUrl ?: return true).host?.lowercase() ?: return true
        return host == main
    }

    /**
     * Снесёт ли экран ошибки работающую страницу (ADR-063).
     *
     * Найдено на живом телевизоре 2026-08-25: человек смотрел фильм, плеер увёл верхний
     * фрейм на рекламную вставку со сломанным сертификатом - и вместо фильма встало
     * полноэкранное «Сертификат сайта недействителен». Сверка из `B-46` тут не помогает
     * вовсе: это **и есть** навигация главного фрейма, просто попросил её не человек.
     *
     * Три случая, когда терять нечего и экран правилен:
     * - на экране ещё ничего нет;
     * - ломается та же страница, что показана, - обновление или переход внутри сайта;
     * - адрес попросили мы сами, то есть человек его набрал, выбрал в закладках
     *   или в истории. Ему и объясняют, почему не открылось.
     */
    private fun takesAwayWorkingPage(errorUrl: String?): Boolean {
        val shown = committedUrl ?: return false
        return !sameHost(shown, errorUrl) && !sameHost(requestedUrl, errorUrl)
    }

    private fun sameHost(a: String?, b: String?): Boolean {
        val one = Uri.parse(a ?: return false).host?.lowercase() ?: return false
        val two = Uri.parse(b ?: return false).host?.lowercase() ?: return false
        return one == two
    }

    /** Ключ разрешения: `хост:порт` с подставленным умолчанием http. */
    private fun httpOrigin(url: Uri): String? {
        val host = url.host?.lowercase()
        if (host.isNullOrEmpty()) return null
        val port = if (url.port >= 0) url.port else HTTP_DEFAULT_PORT
        return "$host:$port"
    }

    /**
     * Требование B-2: продолжения с невалидным сертификатом не существует.
     * Продолжение через `handler` в этом проекте не встречается нигде - на телевизоре
     * пользователь не в состоянии оценить, чем отличается просроченный сертификат
     * от подменённого.
     */
    override fun onReceivedSslError(view: WebView, handler: SslErrorHandler, error: SslError) {
        handler.cancel()

        // Ошибка подресурса страницу собой не заменяет - ровно как в onReceivedError.
        // Найдено на живом телевизоре: страница с фильмом тянет счётчик со сторонним
        // сертификатом, соединение с ним мы рвём (и правильно), но полноэкранное
        // «Сертификат недействителен» вставало поверх работающей страницы и выглядело
        // так, будто не открывается сам сайт.
        //
        // `SslError` не говорит, главный это фрейм или нет, поэтому сверяемся с адресом
        // последней навигации главного фрейма: его запоминает shouldInterceptRequest,
        // который для главного фрейма вызывается всегда и до сетевого запроса.
        if (!isMainFrameHost(error.url)) return

        // Страница жива, а увела на битый адрес она сама (ADR-063): забирать у человека
        // то, что он смотрел, ради объяснения про чужую рекламу нельзя. Соединение при
        // этом уже разорвано выше - `handler.cancel()` стоит первой строкой.
        if (takesAwayWorkingPage(error.url)) {
            onQuietNotice(displayHost(Uri.parse(error.url ?: "")))
            return
        }

        val reason = when (error.primaryError) {
            // SSL_DATE_INVALID - то, что Chromium возвращает для просроченного сертификата
            // на практике; SSL_EXPIRED приходит реже, но означает для пользователя то же самое
            SslError.SSL_EXPIRED, SslError.SSL_DATE_INVALID -> R.string.ssl_expired
            SslError.SSL_IDMISMATCH -> R.string.ssl_idmismatch
            SslError.SSL_UNTRUSTED -> R.string.ssl_untrusted
            SslError.SSL_NOTYETVALID -> R.string.ssl_notyetvalid
            else -> R.string.ssl_other
        }
        view.showOwn(
            errorPage(
                title = view.str(R.string.err_ssl_title),
                // Человек, упёршийся в этот экран, идёт искать выключатель - и находит
                // «Проверку опасных сайтов», которая тут ни при чём: она про список
                // адресов у Google, а сертификат проверяет TLS-стек движка. Пользователь
                // проделал это дважды (`B-180`), поэтому фраза стоит здесь, а не
                // в настройках: ищут её в этот момент и на этом экране.
                message = view.str(reason) + " " + view.str(R.string.ssl_not_a_setting),
                detail = error.url,
                severe = true,
            )
        )
    }

    override fun onReceivedError(
        view: WebView,
        request: WebResourceRequest,
        error: WebResourceError,
    ) {
        // Ошибки картинок, шрифтов и прочих подресурсов на экран не выносим:
        // страница обычно остаётся пригодной, а подменять её ошибкой - грубее самой ошибки.
        if (!request.isForMainFrame) return

        // Отменённый нами же запрос (см. shouldInterceptRequest) приходит сюда как ошибка.
        // Свой экран в этот момент уже показан или вот-вот встанет, и перекрывать его
        // сообщением "сайт не отвечает" значит соврать о причине.
        //
        // Сверяется именно адрес, а не сам факт висящего подтверждения: с появлением
        // кнопки BACK стало возможным уйти назад с открытого экрана подтверждения,
        // и настоящая ошибка загрузки следующей страницы оказалась бы проглочена.
        if (request.url.toString() == pending?.url) return

        view.showOwn(
            errorPage(
                title = view.str(R.string.err_net_title),
                message = view.str(R.string.err_net_message),
                detail = request.url.toString() + "\n" + error.description,
            )
        )
    }

    /**
     * Требование A-3. Обхода в один клик нет: уводим со страницы и объясняем причину.
     *
     * Отчёт в Google не отправляем (`report = false`) - продукт не отправляет наружу
     * ничего о том, куда ходил пользователь, и исключения из этого правила нет
     * даже здесь. Сама проверка Safe Browsing при этом работает.
     *
     * Колбэк появился в API 27, а minSdk у нас 26 (ADR-003). На Android 8.0 он просто
     * не вызовется, и угрозу покажет собственный экран движка - защита остаётся, свой
     * текст пользователь не увидит. Подавление осознанное, поднимать minSdk ради
     * оформления одного экрана неразумно.
     */
    @Suppress("NewApi")
    override fun onSafeBrowsingHit(
        view: WebView,
        request: WebResourceRequest,
        threatType: Int,
        callback: SafeBrowsingResponse,
    ) {
        callback.backToSafety(false)

        view.showOwn(
            errorPage(
                title = view.str(R.string.err_threat_title),
                message = view.str(R.string.err_threat_message),
                detail = request.url.toString(),
                severe = true,
            )
        )
    }

    /**
     * С Android 8.0 рендерер живёт отдельным процессом и умирает отдельно: от нехватки
     * памяти на телевизоре или от собственной ошибки на тяжёлой странице. Без `return true`
     * система убивает всё приложение целиком - для пользователя это выглядит как вылет
     * браузера на ровном месте.
     */
    override fun onRenderProcessGone(view: WebView, detail: RenderProcessGoneDetail): Boolean {
        onRendererGone(detail.didCrash())
        return true
    }

    private companion object {
        const val CMD_ALLOW_HTTP = "allow-http"
        const val HTTP_DEFAULT_PORT = 80
    }
}

private fun WebView.str(id: Int): String = context.getString(id)

private fun WebView.showLocal(html: String) =
    loadDataWithBaseURL(null, html, "text/html", "utf-8", null)

/**
 * То, что видит пользователь, когда решает судьбу адреса: имя хоста и, если он
 * нестандартный, порт. Ни пути, ни параметров, ни userinfo - только то, кому он
 * собирается доверить незащищённое соединение.
 */
private fun displayHost(url: Uri): String {
    val host = url.host?.lowercase() ?: return ""
    return if (url.port >= 0) "$host:${url.port}" else host
}

private fun WebView.spoofPage(url: Uri) = errorPage(
    title = str(R.string.err_spoof_title),
    message = str(R.string.err_spoof_message),
    detail = displayHost(url),
    severe = true,
)

private fun WebView.blockedPage(url: Uri) = errorPage(
    title = str(R.string.err_blocked_title),
    message = str(R.string.err_blocked_message),
    detail = url.toString(),
)
