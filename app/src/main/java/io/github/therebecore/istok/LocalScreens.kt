package io.github.therebecore.istok

import android.content.Context
import android.os.Build
import android.webkit.WebView
import java.util.Collections
import java.util.UUID

/**
 * Наш собственный экран внутри WebView: домашняя страница, списки, вопрос перед
 * чисткой. Отличать их приходится самим - все они грузятся с пустым происхождением
 * и одинаково отдают движку `about:blank`.
 *
 * Перечисление, а не набор флагов: флагов было три, ставились они перед каждой
 * загрузкой врозь, и оба дефекта Этапа 6 в этом месте - забытый сброс и забытый гейт -
 * были ошибками расстановки, а не логики. Экран теперь ровно один, а всё, чем он
 * отличается от соседей, объявлено здесь.
 *
 * [NONE] - на экране сайт **или** наша страница, на которой управлять нечем (экран
 * ошибки, подтверждение http): для команд схемы и кнопки «домой» это одно и то же.
 *
 * @property decision следующее нажатие OK решает за пользователя необратимое, поэтому
 * показ экрана взводит гейт H-1: клик не засчитывается, пока не пройдёт
 * `DECISION_GUARD_MS` и курсор не сдвинут. Правило Этапа 6 - **любой** экран,
 * поднимаемый командой своей схемы и что-то решающий, обязан стоять здесь с `true`.
 */
internal enum class LocalScreen(val decision: Boolean = false) {
    NONE,
    HOME,
    BOOKMARKS,
    HISTORY,
    CLEAR_CONFIRM(decision = true),

    /**
     * Настройки взводят гейт наравне с экраном решения, хотя сами по себе ничего не
     * меняют. Причина - что на них лежит: одно нажатие выключает проверку опасных сайтов.
     * Цена - 600 мс и сдвиг курсора при открытии настроек, то есть один раз за поход в них.
     *
     * Второй барьер, а не единственный: с шага 9 команда `istok://settings` принимается
     * только с нашего же экрана (B-37, см. `OWN_SCREEN_ONLY`), и подставить настройки
     * под курсор чужая страница больше не может вовсе. Гейт остаётся, потому что
     * прикрывает и второй случай - нажатие OK, пришедшее автоповтором пульта.
     */
    SETTINGS(decision = true),

    /** Вопрос перед очисткой данных (E-3). Необратимо, значит гейт - по тому же правилу. */
    WIPE_CONFIRM(decision = true),

    /**
     * «О браузере» (ADR-056): версия, кто сделал, лицензия и обновление. Гейт взводится
     * по той же причине, что у настроек: на экране лежит выключатель автопроверки
     * обновлений, а он решает, ходит ли браузер в сеть сам.
     */
    ABOUT(decision = true),

    /**
     * Поддержка (ADR-060): кошельки и QR к ним. Гейт не взводит - на экране нет ни одной
     * кнопки, он только показывает. Списывать что-либо, открывать кошельки и ходить
     * в сеть браузер здесь не умеет и уметь не будет.
     */
    DONATE,
}

/**
 * Что локальным экранам нужно от Activity. Всё, что осталось за этой границей, -
 * ровно то, что живёт не в страницах, а в окне: сам WebView, согласие на http, курсор,
 * меню и поле переименования.
 */
internal interface ScreenHost {
    /** WebView, живой прямо сейчас. `null` - между смертью рендерера и его заменой. */
    fun currentPage(): WebView?

    /** Обесценить висящее согласие на http (требование H-2). */
    fun forgetPendingConsent()

    /** Взвести гейт H-1: показан экран, где следующее нажатие решает за пользователя. */
    fun onDecisionScreen()

    /** Уйти на сайт - только через единственную точку навигации (ADR-015, C-5). */
    fun openUrl(url: String)

    fun hideMenu()

    /** Открыть поле правки названия. Само поле - View приложения, а не страницы (ADR-009). */
    fun startRename(index: Int, favorite: Boolean, title: String)

    /** Настройку переключили: движку нужно узнать об этом сразу, а не после перезапуска. */
    fun onPreferencesChanged()

    /**
     * Применить выбранный язык (ADR-058). Живёт в Activity, потому что применяется
     * пересозданием: ресурсы выбираются один раз, когда Activity создаётся.
     */
    fun restartForLanguage()

    /**
     * Сходить за описанием последней версии прямо сейчас (ADR-055). Живёт в Activity,
     * потому что найденное обновление показывается полосой на её панели - той же самой,
     * которой отвечает автоматическая проверка.
     */
    fun checkUpdatesNow()

    /**
     * Стереть всё, что движок накопил о посещённых сайтах (требование E-3): куки, кэш,
     * хранилища страниц, данные форм, список «назад». Лежит в Activity, потому что почти
     * всё это чистится через сам WebView, а он живёт там.
     *
     * **Последним шагом закрывает браузер** (ADR-036): часть данных сайта живёт в памяти
     * движка, и только новый процесс начинает с пустого места. Значит, вызов сюда
     * не возвращается, и показывать после него нечего.
     */
    fun wipeEngineData()

    /**
     * Короткая плашка поверх страницы (ADR-035). Нужна там, где нажатие **не может**
     * сделать то, чего от него ждут: списки заполнены, и кнопка «Закрепить» или «+»
     * молча ничего не делала (`B-115`, `B-153`). Молчащая кнопка читается как сломанная.
     */
    fun showNotice(text: String)
}

/**
 * Локальные экраны браузера и команды, которые с них приходят.
 *
 * Отделено от `MainActivity` (`B-32`, ревью Этапа 6): там это была четверть файла,
 * связанная с остальным только через WebView. Здесь же живёт и **состояние экрана** -
 * какой из них показан, одноразовый ключ его разметки, режимы выбора и правки
 * избранного, - потому что состояние без своих экранов бессмысленно, а вместе с ними
 * читается как одно целое.
 *
 * Разметку собирает `Pages.kt`, хранилище - `Storage`. Здесь только то, что между ними:
 * какой экран показать, что положить в разметку и что сделать по команде из неё.
 */
internal class LocalScreens(
    private val context: Context,
    private val store: Storage,
    private val host: ScreenHost,
) {

    /** Какой наш экран сейчас показан. Меняется только в [show]. */
    var screen = LocalScreen.NONE
        private set

    /**
     * Одноразовый ключ страницы, которая сейчас на экране (требование H-8). Живёт
     * до первой команды и до следующей перерисовки - что случится раньше.
     */
    private var pageKey: String? = null

    /** Экран закладок открыт как выбор избранного - с домашней страницы, по плитке «+». */
    private var picking = false

    /**
     * Какая вкладка экрана истории показана: закреплённые или сама история.
     *
     * Кнопка в строке на этих вкладках делает разное - крестик открепляет либо удаляет
     * запись, лента кладёт в закладки из закреплённых либо из истории, - а команда
     * приходит по ссылке с одним номером. Отличает их только это поле: номер в разметке
     * всегда относится к списку, который в момент сборки был на экране.
     */
    private var onPinnedTab = false

    /** Домашняя страница в режиме правки избранного. */
    private var editingFavorites = false

    /**
     * Что показать справа у строки «Проверить сейчас»: «Проверяю…», ответ или ничего
     * (ADR-055). Живёт до ухода с настроек - вернувшись на экран, человек видит чистую
     * строку, а не ответ вчерашней проверки.
     */
    private var updateStatus: String? = null

    /**
     * Ушли на настоящий сайт, каким бы способом это ни случилось: плиткой, адресной
     * строкой или ссылкой на чужой странице.
     *
     * Сброс здесь - не симметрия ради симметрии. На [screen] держится единственная защита
     * команды `rename`: ключа она не требует (ADR-027), и от чужой страницы её отделяет
     * только «мы сейчас на своём списке». Пока состояние сбрасывалось лишь при показе
     * другого нашего экрана, оно переживало уход на сайт, и `istok://rename/0` с чужой
     * страницы открывал наше поле правки поверх неё (стенд `.bench\st9-forge.ps1`).
     *
     * Ключ гасится тем же движением: он выдавался разметке конкретного экрана, экрана
     * больше нет - живому ключу неоткуда взяться, но и жить ему незачем.
     */
    fun leftLocalPages() {
        screen = LocalScreen.NONE
        pageKey = null
    }

    /**
     * Домашняя страница (Этап 6). Предупреждения о телевизоре показываются именно здесь:
     * стартовый экран, живший секунду, был для такого сообщения плохим местом, а домашняя
     * остаётся на виду, пока пользователь не выберет, куда идти.
     */
    fun showHome() {
        show(
            LocalScreen.HOME,
            homePage(
                favorites = store.favorites(),
                last = Settings.lastVisit(context),
                lastHint = str(R.string.home_last),
                warnings = listOfNotNull(outdatedWebViewWarning(), debuggableBuildWarning()),
                nonce = newPageKey(),
                editing = editingFavorites,
                editLabel = str(R.string.home_edit),
                doneLabel = str(R.string.home_edit_done),
                removeLabel = str(R.string.act_unfavorite),
                renameLabel = str(R.string.act_rename),
                leftLabel = str(R.string.act_move_left),
                rightLabel = str(R.string.act_move_right),
            ),
        )
    }

    /**
     * Экран вместо умершей страницы. Наш, но управлять на нём нечем - ни команд,
     * ни решения, - поэтому и состояние у него [LocalScreen.NONE].
     */
    fun showRendererError(messageRes: Int) {
        show(
            LocalScreen.NONE,
            errorPage(title = str(R.string.err_renderer_title), message = str(messageRes)),
        )
    }

    /**
     * Экран списка - закладки или история (Этап 6, шаг 5). Читается **в момент открытия
     * и только здесь**: на старте эти файлы не трогаются вовсе, как и записано в ADR-025.
     *
     * Ключ выдаётся новый на каждую сборку страницы: команда, меняющая состояние, живёт
     * ровно до следующей перерисовки (требование H-8).
     */
    fun openList(bookmarks: Boolean, picking: Boolean = false, pinnedTab: Boolean = true) {
        host.hideMenu()
        val key = newPageKey()
        val html = if (bookmarks) {
            listPage(
                title = str(
                    if (picking) R.string.page_bookmarks_pick else R.string.page_bookmarks_title
                ),
                items = store.bookmarks(),
                empty = str(R.string.page_bookmarks_empty),
                prefix = BOOKMARK_PREFIX,
                nonce = key,
                favorites = store.favorites().mapTo(HashSet()) { it.url },
                actions = if (picking) null else RowActions(
                    favLabel = str(R.string.act_favorite),
                    unfavLabel = str(R.string.act_unfavorite),
                    removeLabel = str(R.string.act_remove),
                    renameLabel = str(R.string.act_rename),
                ),
                picking = picking,
            )
        } else {
            // Вкладок нет, пока ничего не закреплено: экран тогда ровно такой, каким был
            // до их появления. Закреплённые лежат в своём хранилище (ADR-054), поэтому
            // очистка истории их не трогает.
            val pins = store.pinned()
            val tabs = if (pins.isEmpty()) null else Tabs(
                pinnedLabel = str(R.string.page_history_pinned),
                historyLabel = str(R.string.page_history_title),
                onPinned = pinnedTab,
            )
            if (tabs != null && pinnedTab) {
                listPage(
                    title = str(R.string.page_history_title),
                    items = pins,
                    empty = str(R.string.page_pinned_empty),
                    prefix = PINNED_PREFIX,
                    nonce = key,
                    actions = RowActions(
                        favLabel = str(R.string.act_favorite),
                        unfavLabel = str(R.string.act_unfavorite),
                        removeLabel = str(R.string.act_unpin),
                        bookLabel = str(R.string.act_bookmark),
                    ),
                    favorites = store.favorites().mapTo(HashSet()) { it.url },
                    tabs = tabs,
                )
            } else {
                listPage(
                    title = str(R.string.page_history_title),
                    items = unpinnedHistory(),
                    empty = str(R.string.page_history_empty),
                    prefix = HISTORY_PREFIX,
                    nonce = key,
                    clearLabel = str(R.string.act_history_clear),
                    actions = RowActions(
                        pinLabel = str(R.string.act_pin),
                        bookLabel = str(R.string.act_bookmark),
                        removeLabel = str(R.string.act_forget),
                    ),
                    tabs = tabs,
                )
            }
        }
        this.picking = picking
        // Не «какую вкладку просили», а какая нарисована: закреплённых может не остаться
        // ни одной, и тогда экран показывает историю, сколько бы её ни просили с вкладки.
        this.onPinnedTab = !bookmarks && pinnedTab && store.pinned().isNotEmpty()
        show(if (bookmarks) LocalScreen.BOOKMARKS else LocalScreen.HISTORY, html)
    }

    /**
     * «О браузере» (ADR-056): версия, кто сделал, лицензия - и обновление, потому что
     * оно про саму программу, а не про то, как человек её настроил под себя.
     *
     * Ключ выдаётся новый на каждую сборку: с этого экрана уходят команды, меняющие
     * состояние (переключатель автопроверки, ручная проверка), - требование H-8.
     */
    fun showAbout() {
        host.hideMenu()
        val key = newPageKey()
        val facts = listOf(
            str(R.string.about_author) to str(R.string.about_author_name),
            str(R.string.about_tester) to str(R.string.about_tester_name),
            str(R.string.about_license) to str(R.string.about_license_name),
        )
        val updates = Toggle.UPDATES
        val on = Settings.isOn(context, updates)
        val rows = listOf(
            SettingRow(
                label = str(updates.label),
                hint = str(updates.hint),
                value = str(if (on) updates.onLabel else updates.offLabel),
                href = "$INTERNAL_SCHEME://$CMD_SET/$key/${updates.key}",
            ),
            SettingRow(
                label = str(R.string.set_check_now),
                hint = context.getString(
                    R.string.set_version,
                    BuildConfig.VERSION_NAME,
                    BuildConfig.VERSION_CODE,
                ),
                value = updateStatus.orEmpty(),
                href = "$INTERNAL_SCHEME://$CMD_CHECK_UPDATE/$key",
            ),
        )
        show(
            LocalScreen.ABOUT,
            aboutPage(
                product = str(R.string.about_product),
                sub = str(R.string.about_sub),
                tagline = str(R.string.about_tagline),
                facts = facts,
                rows = rows,
            ),
        )
    }

    /**
     * Экран поддержки (ADR-060). Данные для него зашиты в сборку (`Wallets.kt`),
     * ничего не читается с диска и тем более из сети: адрес кошелька - это то, что
     * нельзя дать подменить никому и никогда.
     */
    fun showDonate() {
        host.hideMenu()
        show(
            LocalScreen.DONATE,
            donatePage(
                product = str(R.string.about_product),
                sub = str(R.string.about_sub),
                // Свой текст, а не описание с «О браузере» плюс приписка: он начинается
                // теми же словами и продолжает мысль. Первые две фразы двух экранов
                // обязаны совпадать - иначе браузер описывает себя по-разному в двух
                // местах, и расхождение замечает читатель, а не мы.
                text = str(R.string.donate_text),
                wallets = WALLETS.map { str(it.label) to it },
            ),
        )
    }

    /**
     * Ответ проверки обновлений пришёл (ADR-055). Экран пересобирается, только если
     * человек всё ещё на настройках: ответ мог прийти после того, как он ушёл на сайт,
     * и подменять ему страницу под руками нельзя.
     */
    fun showUpdateStatus(text: String) {
        updateStatus = text
        if (screen == LocalScreen.ABOUT) showAbout()
    }

    /**
     * Следующий язык по кругу: как в системе -> Русский -> English -> как в системе.
     * Круг, а не список выбора: значений три, а строка настроек умеет ровно то же, что
     * и остальные - нажали и увидели новое значение. Отдельный экран ради трёх пунктов
     * стоил бы человеку двух лишних нажатий.
     */
    private fun nextLanguage(current: Language): Language =
        Language.entries[(current.ordinal + 1) % Language.entries.size]

    /**
     * Экран настроек (Этап 7, ADR-028). Строки собираются перебором [Toggle]: там же,
     * где ключ и умолчание, лежат и подписи, поэтому новая настройка не требует правок
     * здесь.
     *
     * Ключ выдаётся новый на каждую сборку, как и спискам: переключение меняет состояние
     * и потому идёт с ключом (H-8).
     */
    fun showSettings() {
        host.hideMenu()
        val key = newPageKey()
        // Обновление сюда не входит: и выключатель автопроверки, и кнопка «Проверить
        // сейчас» переехали на экран «О браузере» (ADR-056) - там же, где версия.
        // Язык стоит первой строкой, хотя по замыслу экрана наверху приватность: тому,
        // кому переключатель нужен, остальные подписи прочесть нечем, и искать его
        // в конце списка - то же самое, что не иметь его вовсе.
        val language = Settings.language(context)
        val languageRow = SettingRow(
            label = str(R.string.set_lang),
            hint = str(R.string.set_lang_hint),
            value = str(language.label),
            href = "$INTERNAL_SCHEME://$CMD_LANG/$key/${nextLanguage(language).tag}",
        )
        val rows = listOf(languageRow) + Toggle.entries.filter { it != Toggle.UPDATES }.map { toggle ->
            val on = Settings.isOn(context, toggle)
            SettingRow(
                label = str(toggle.label),
                hint = str(toggle.hint),
                value = str(if (on) toggle.onLabel else toggle.offLabel),
                href = "$INTERNAL_SCHEME://$CMD_SET/$key/${toggle.key}",
            )
        }
        val wipe = SettingRow(
            label = str(R.string.set_wipe),
            hint = str(R.string.set_wipe_hint),
            value = "",
            href = "$INTERNAL_SCHEME://$CMD_WIPE_ASK",
        )
        show(
            LocalScreen.SETTINGS,
            settingsPage(str(R.string.page_settings_title), rows, action = wipe),
        )
    }

    /**
     * Переход по записи локального экрана. Идентификатор - это номер записи, а не адрес:
     * адрес приложение берёт у себя (требование E-5), а приставка говорит, в каком списке
     * искать. Голое число - избранное с домашней страницы, без приставки, потому что так
     * было до появления списков и менять разметку домашней ради единообразия незачем.
     */
    fun openSaved(id: String?) {
        val entry = when {
            id == LAST_ID -> Settings.lastVisit(context)
            id == null -> null
            id.startsWith(BOOKMARK_PREFIX) ->
                store.bookmarks().getOrNull(id.drop(1).toIntOrNull() ?: return)
            id.startsWith(PINNED_PREFIX) ->
                store.pinned().getOrNull(id.drop(1).toIntOrNull() ?: return)
            // Тот же список, что нарисован на вкладке «История», - см. unpinnedHistory.
            id.startsWith(HISTORY_PREFIX) ->
                unpinnedHistory().getOrNull(id.drop(1).toIntOrNull() ?: return)
            else -> store.favorites().getOrNull(id.toIntOrNull() ?: return)
        } ?: return
        host.openUrl(entry.url)
    }

    /**
     * Название правки подтверждено. Список берётся заново, экран пересобирается.
     *
     * Пустое имя допустимо: тогда и на плитке, и в списке снова показывается хост -
     * ровно как у закладки, у которой заголовка никогда не было.
     */
    fun applyRename(index: Int, favorite: Boolean, title: String) {
        val items = (if (favorite) store.favorites() else store.bookmarks()).toMutableList()
        val entry = items.getOrNull(index) ?: return
        items[index] = SiteEntry(entry.url, title)
        if (favorite) {
            store.saveFavorites(items)
            showHome()
        } else {
            store.saveBookmarks(items)
            openList(bookmarks = true)
        }
    }

    /**
     * Команда с нашего локального экрана.
     *
     * Ключ проверяется здесь и **сгорает при любой попытке** - и верной, и неверной
     * (требование H-8). Чужая страница может разместить ссылку `istok://del/...`
     * с любым ключом, но угадать выданный ей неоткуда: он живёт только в разметке
     * нашего документа с пустым происхождением и меняется на каждой перерисовке.
     */
    fun onCommand(command: String, segments: List<String>) {
        // Требование B-37. Переход на наш экран ключа не требует - H-8 просит его только
        // у команд, меняющих состояние, - и потому от чужой страницы такую команду
        // отделяет ровно одна вещь: экран, с которого её нажали. Ссылки на них живут
        // только в нашей же разметке (плитка «+» на домашней, корзина у заголовка истории,
        // «Очистить данные» в настройках, отмена на экранах подтверждения), поэтому
        // команда, пришедшая с сайта, - подделка, и делать по ней нечего.
        //
        // Стоило это отдельной записи: `istok://bookmarks` с чужой страницы подставлял
        // под курсор наш список, а на нём кнопки удаления и звезды несут живой ключ
        // этой же страницы - то есть работают с первого нажатия.
        if (screen == LocalScreen.NONE && command in OWN_SCREEN_ONLY) return

        when (command) {
            CMD_BOOKMARKS -> {
                // Пришли сюда с домашней по плитке «+» - значит выбирают избранное.
                openList(bookmarks = true, picking = true)
                return
            }

            // Вкладка истории и возврат с экрана подтверждения чистки. Ключа не требует:
            // это переход. Без аргумента (возврат с подтверждения) открывается вкладка
            // истории - именно её пользователь и собирался чистить.
            CMD_HISTORY -> {
                openList(bookmarks = false, pinnedTab = segments.firstOrNull() == TAB_PINNED)
                return
            }

            // Корзина у заголовка истории. Сама по себе ничего не меняет - спрашивает.
            CMD_CLEAR_ASK -> {
                askClearHistory()
                return
            }

            // Пункт меню «Настройки». Переход, ключа не требует - но экран взводит гейт,
            // см. LocalScreen.SETTINGS.
            CMD_SETTINGS -> {
                showSettings()
                return
            }

            // Пункт меню «О браузере». Переход, ключа не требует, но экран взводит гейт
            // и принимается только с наших экранов - на нём лежит выключатель автопроверки.
            CMD_ABOUT -> {
                updateStatus = null
                showAbout()
                return
            }

            // Строка «Поддержать разработку» на экране «О браузере». Переход и ключа
            // не требует; место ему здесь, а не ниже проверки ключа, - там команда без
            // ключа молча отбрасывается, и экран просто не открывается.
            CMD_DONATE -> {
                showDonate()
                return
            }

            // Строка «Очистить данные браузера» на экране настроек. Как и вопрос
            // о чистке истории, сама по себе ничего не меняет - ключа не требует,
            // а от чужой страницы её прикрывает гейт H-1 (LocalScreen.WIPE_CONFIRM).
            CMD_WIPE_ASK -> {
                askWipe()
                return
            }

            // Карандаш. Ключа не требует (см. CMD_RENAME), поэтому чужую страницу
            // отсекаем здесь: команда принимается только с наших списков. На домашней
            // правят плитку, на экране закладок - закладку.
            CMD_RENAME -> {
                val index = segments.firstOrNull()?.toIntOrNull() ?: return
                when (screen) {
                    LocalScreen.HOME -> beginRename(index, favorite = true)
                    LocalScreen.BOOKMARKS -> beginRename(index, favorite = false)
                    // На экране истории карандаша нет с ADR-054: закреплённая запись
                    // больше не закладка, и правился бы здесь чужой список по чужому
                    // номеру. Переименование осталось у закладок и у плиток избранного.
                    else -> Unit
                }
                return
            }

            // Карандаш на домашней. Ключа не требует - вид, а не изменение, - но состояние
            // приложения всё-таки меняет, поэтому чужую страницу отсекаем экраном,
            // как у CMD_RENAME. Без этой строки чужой сайт подставлял домашнюю в режиме
            // правки, где под курсором оказываются крестики «убрать из избранного»
            // (аудит Этапа 6).
            CMD_EDIT -> {
                if (screen != LocalScreen.HOME) return
                // Правка пустого избранного - ловушка без выхода: плиток нет, а значит
                // нет ни «+», ни «Готово», и режим нечем выключить.
                if (!editingFavorites && store.favorites().isEmpty()) return
                editingFavorites = !editingFavorites
                showHome()
                return
            }
        }

        val key = pageKey
        pageKey = null
        if (key == null || segments.firstOrNull() != key) return

        // Чистка аргументов не имеет - разбор номера записи ниже к ней не относится.
        if (command == CMD_CLEAR) {
            store.clearHistory()
            // На вкладку истории, а не закреплённых: человек смотрит на результат чистки.
            openList(bookmarks = false, pinnedTab = false)
            return
        }

        // Очистка данных (E-3). Аргументов нет: одна кнопка чистит всё сразу - решение
        // пользователя 2026-08-10. Экрана после этого не будет никакого: `wipeEngineData`
        // последним шагом закрывает браузер (ADR-036), иначе установленный сайтом Service
        // Worker доживает в памяти движка до перезапуска. Экран подтверждения
        // предупреждает об этом красной строкой.
        if (command == CMD_WIPE) {
            store.clearHistory()
            host.wipeEngineData()
            return
        }

        if (command == CMD_CHECK_UPDATE) {
            if (screen != LocalScreen.ABOUT) return
            updateStatus = str(R.string.update_checking)
            showAbout()
            host.checkUpdatesNow()
            return
        }

        // Переключение настройки: аргумент - ключ настройки строкой, а не номер строки.
        // Неизвестный ключ молча отбрасывается, как и любой другой мусор в команде.
        if (command == CMD_SET) {
            val toggle = Toggle.entries.firstOrNull { it.key == segments.getOrNull(1) } ?: return
            Settings.setOn(context, toggle, !Settings.isOn(context, toggle))
            host.onPreferencesChanged()
            // Возврат на тот экран, где нажали: автопроверка обновлений живёт
            // на «О браузере», остальные настройки - на своём экране.
            if (screen == LocalScreen.ABOUT) showAbout() else showSettings()
            return
        }

        // Смена языка (ADR-058). Ресурсы Activity выбираются один раз при её создании,
        // поэтому язык применяется пересозданием - тем же способом, каким это делает
        // система, когда язык меняют в настройках телевизора.
        if (command == CMD_LANG) {
            val wanted = segments.getOrNull(1).orEmpty()
            val language = Language.entries.firstOrNull { it.tag == wanted } ?: return
            if (language == Settings.language(context)) return
            Settings.setLanguage(context, language)
            host.restartForLanguage()
            return
        }

        val index = segments.getOrNull(1)?.toIntOrNull() ?: return

        when (command) {
            // На домашней странице звезда убирает из избранного, в списках - переключает:
            // там она отвечает на вопрос «показывать ли эту запись плиткой». Список берётся
            // тот, что на экране: номер в разметке относится именно к нему.
            CMD_FAVORITE -> when {
                screen == LocalScreen.HOME -> removeFavorite(index)
                onPinnedTab -> toggleFavorite(store.pinned(), index)
                else -> toggleFavorite(store.bookmarks(), index)
            }
            // Крестик на экране истории означает разное на разных вкладках: у закреплённой
            // записи он снимает закрепление, у строки истории - удаляет её. Подтверждения
            // нет ни там, ни там (решение пользователя 2026-08-22).
            CMD_REMOVE -> when {
                screen != LocalScreen.HISTORY -> removeBookmark(index)
                onPinnedTab -> unpin(index)
                else -> forgetVisit(index)
            }
            CMD_PIN -> pinFromHistory(index)
            CMD_MARK -> if (onPinnedTab) bookmarkPinned(index) else bookmarkFromHistory(index)
            CMD_MOVE -> moveFavorite(index, segments.getOrNull(2))
        }
    }

    /**
     * Спросить перед чисткой истории (Этап 6, шаг 7). Вопрос задаёт корзина у заголовка
     * списка: в меню эту кнопку никто бы не нашёл. Экран, а не диалог: до отдельного
     * окна курсор не дотягивается (H-3).
     *
     * Кнопка несёт одноразовый ключ (H-8) - чистка меняет состояние. Ключ здесь не
     * формальность даже при том, что вопрос задаём мы сами: ссылка `istok://clear/...`
     * работает с любой страницы, и без ключа чужой сайт стёр бы историю кнопкой,
     * подписанной как угодно.
     *
     * И по той же причине экран объявлен решающим в [LocalScreen] - на нём взводится
     * гейт H-1, силами самой [show]. Показать **этот** экран чужая страница может:
     * `istok://clear-ask` ничего не меняет и потому ключа не требует. Без гейта
     * получалась полная цепочка, воспроизведённая аудитом Этапа 6: страница скриптом
     * ставит наш необратимый вопрос поверх себя в момент, который выбирает сама, зная
     * положение курсора из `mousemove`, - и следующее нажатие OK стирает историю.
     * Общее правило на будущее: **любой новый экран, показываемый по команде своей
     * схемы, обязан взводить гейт.**
     */
    private fun askClearHistory() {
        val key = newPageKey()
        show(
            LocalScreen.CLEAR_CONFIRM,
            confirmPage(
                title = str(R.string.clear_title),
                message = str(R.string.clear_message),
                actionHref = "$INTERNAL_SCHEME://$CMD_CLEAR/$key",
                actionLabel = str(R.string.clear_action),
                cancelHref = "$INTERNAL_SCHEME://$CMD_HISTORY",
                cancelLabel = str(R.string.clear_cancel),
            ),
        )
    }

    /**
     * Спросить перед очисткой данных (Этап 7, шаг 7, требование E-3). Устроено как вопрос
     * о чистке истории и по тем же причинам: экран, а не диалог (H-3), кнопка с одноразовым
     * ключом (H-8), гейт H-1 через [LocalScreen.WIPE_CONFIRM].
     *
     * Отмена возвращает на настройки - туда, откуда сюда попадают. Возврат на историю,
     * как у [askClearHistory], здесь был бы неожиданностью.
     */
    private fun askWipe() {
        val key = newPageKey()
        show(
            LocalScreen.WIPE_CONFIRM,
            confirmPage(
                title = str(R.string.wipe_title),
                message = str(R.string.wipe_message),
                actionHref = "$INTERNAL_SCHEME://$CMD_WIPE/$key",
                actionLabel = str(R.string.wipe_action),
                cancelHref = "$INTERNAL_SCHEME://$CMD_SETTINGS",
                cancelLabel = str(R.string.clear_cancel),
                note = str(R.string.wipe_note),
            ),
        )
    }

    private fun beginRename(index: Int, favorite: Boolean) {
        val entry = (if (favorite) store.favorites() else store.bookmarks())
            .getOrNull(index) ?: return
        host.startRename(index, favorite, entry.title)
    }

    /** [source] - список, который сейчас на экране: закладки или закреплённые. */
    private fun toggleFavorite(source: List<SiteEntry>, index: Int) {
        val entry = source.getOrNull(index) ?: return
        val items = store.favorites().toMutableList()
        val existing = items.indexOfFirst { it.url == entry.url }
        // Выбор с домашней страницы только добавляет: человек нажал «+», чтобы получить
        // плитку, и снятие избранного тем же нажатием было бы неожиданностью.
        if (picking) {
            if (existing < 0 && items.size >= Storage.MAX_FAVORITES) {
                host.showNotice(str(R.string.limit_favorites))
            } else if (existing < 0) {
                items.add(entry)
                store.saveFavorites(items)
            }
            showHome()
            return
        }
        when {
            existing >= 0 -> items.removeAt(existing)
            // Больше шести плиток раскладка не держит - это решение принято вместе
            // с макетом, и молча выкидывать седьмую нельзя: пользователь сам решит,
            // какую убрать, поэтому просто ничего не делаем.
            items.size >= Storage.MAX_FAVORITES -> return
            else -> items.add(entry)
        }
        store.saveFavorites(items)
        // Возврат на тот экран, где нажали: звезда есть и в закладках, и на закреплённых.
        openList(bookmarks = screen != LocalScreen.HISTORY, pinnedTab = onPinnedTab)
    }

    private fun removeFavorite(index: Int) {
        val items = store.favorites().toMutableList()
        if (index !in items.indices) return
        items.removeAt(index)
        store.saveFavorites(items)
        if (items.isEmpty()) editingFavorites = false
        showHome()
    }

    private fun moveFavorite(index: Int, direction: String?) {
        val items = store.favorites().toMutableList()
        val target = when (direction) {
            MOVE_LEFT -> index - 1
            MOVE_RIGHT -> index + 1
            else -> return
        }
        if (index !in items.indices || target !in items.indices) return
        Collections.swap(items, index, target)
        store.saveFavorites(items)
        showHome()
    }

    private fun removeBookmark(index: Int) {
        val items = store.bookmarks().toMutableList()
        if (index !in items.indices) return
        items.removeAt(index)
        store.saveBookmarks(items)
        openList(bookmarks = true)
    }

    /**
     * Закрепить запись истории (B-88, устройство изменено в ADR-054): она уходит
     * в **закреплённые**, своё хранилище, а не в закладки. Закрепление и закладка -
     * разные вещи: первое поднимает страницу в истории, вторая заводит на неё запись
     * в списке закладок.
     *
     * Повторное закрепление невозможно: закреплённой строки в списке истории уже нет.
     * Проверка всё равно стоит здесь - разметка живёт в документе, а команда приходит
     * по ссылке.
     */
    private fun pinFromHistory(index: Int) {
        if (screen != LocalScreen.HISTORY) return
        val entry = unpinnedHistory().getOrNull(index) ?: return
        val items = store.pinned().toMutableList()
        if (items.any { it.url == entry.url }) return
        if (items.size >= Storage.MAX_PINNED) {
            host.showNotice(str(R.string.limit_pinned))
            return
        }
        items.add(entry)
        store.savePinned(items)
        // На вкладку закреплённых: пользователь только что её пополнил, туда и смотрит.
        openList(bookmarks = false, pinnedTab = true)
    }

    /** Открепить: запись возвращается в обычную историю, если она там ещё есть. */
    private fun unpin(index: Int) {
        val items = store.pinned().toMutableList()
        if (index !in items.indices) return
        items.removeAt(index)
        store.savePinned(items)
        openList(bookmarks = false, pinnedTab = true)
    }

    /**
     * Строку истории удалили корзиной в самой строке. Без подтверждения - решение
     * пользователя 2026-08-22: спрашивают перед чисткой всей истории, а не перед
     * одной записью, которую и вернуть-то можно новым заходом на сайт.
     */
    private fun forgetVisit(index: Int) {
        val entry = unpinnedHistory().getOrNull(index) ?: return
        store.removeVisit(entry.url)
        openList(bookmarks = false, pinnedTab = false)
    }

    /**
     * Запись **переезжает** в закладки (решение пользователя 2026-08-22): она пропадает
     * с экрана истории и появляется в списке закладок. Из истории адрес при этом
     * удаляется по-настоящему, как по корзине, - иначе «переезд» был бы обманом:
     * строка исчезла с глаз, а адрес остался в логе.
     */
    private fun bookmarkFromHistory(index: Int) {
        if (screen != LocalScreen.HISTORY) return
        val entry = unpinnedHistory().getOrNull(index) ?: return
        // Удалять исходную запись можно только после того, как она где-то появилась:
        // на заполненном списке закладок переезд не состоится, и запись пропала бы
        // из обеих сторон разом (`B-153`).
        if (!addBookmark(entry)) return
        store.removeVisit(entry.url)
        openList(bookmarks = false, pinnedTab = false)
    }

    /**
     * То же для закреплённой записи: она уходит из закреплённых в закладки. Адрес
     * удаляется и из истории - иначе «переезд» кончался бы тем, что запись сваливается
     * обратно на соседнюю вкладку, откуда её когда-то и закрепили.
     */
    private fun bookmarkPinned(index: Int) {
        if (screen != LocalScreen.HISTORY) return
        val items = store.pinned().toMutableList()
        val entry = items.getOrNull(index) ?: return
        if (!addBookmark(entry)) return          // то же, что в `bookmarkFromHistory`: `B-153`
        items.removeAt(index)
        store.savePinned(items)
        store.removeVisit(entry.url)
        openList(bookmarks = false, pinnedTab = true)
    }

    /**
     * Добавление в закладки одной строкой на оба переезда. Повторов не заводим: адрес
     * мог попасть в закладки и панелью браузера, а список с двумя одинаковыми строками -
     * это то, что человек будет разбирать руками.
     */
    private fun addBookmark(entry: SiteEntry): Boolean {
        val items = store.bookmarks().toMutableList()
        // Адрес уже в закладках - переезд считается состоявшимся: цель достигнута,
        // и держать вторую копию в истории незачем.
        if (items.any { it.url == entry.url }) return true
        if (items.size >= Storage.MAX_BOOKMARKS) {
            host.showNotice(str(R.string.limit_bookmarks))
            return false
        }
        items.add(entry)
        store.saveBookmarks(items)
        return true
    }

    /**
     * История без закреплённых записей. **Единственный источник номеров строк вкладки
     * «История»** - и потому она же обязана отвечать на переход по номеру ([openSaved]),
     * на закрепление ([pinFromHistory]), на удаление ([forgetVisit]) и на переезд
     * в закладки ([bookmarkFromHistory]). Разойдись эти списки хоть на одну запись,
     * нажатие на строку открывало бы соседнюю: номера в разметке - это позиции в том
     * списке, который сейчас на экране.
     */
    private fun unpinnedHistory(): List<SiteEntry> {
        val pinned = store.pinned().mapTo(HashSet()) { it.url }
        return store.history().filter { it.url !in pinned }
    }

    /**
     * Показ нашей собственной страницы - единственная точка на все экраны, и она же
     * единственное место, где меняется [screen].
     *
     * Раньше каждый вызывающий расставлял состояние сам, парой флагов перед загрузкой.
     * Так и появилась половина находок круга Этапа 6: где-то забыли сбросить флаг,
     * где-то забыли взвести гейт H-1 на экране решения. Здесь этого забыть уже нельзя -
     * то, чем экран отличается от остальных, объявлено один раз в [LocalScreen].
     *
     * Общего у наших страниц три вещи. Загрузка с пустым происхождением: базовый URL
     * `null`, никаких унаследованных прав. Обязанность обесценить висящее согласие
     * на http (требование H-2): сбрасывается оно в [BrowserWebViewClient.load], а
     * локальные экраны грузятся мимо него, и после ухода с экрана подтверждения нашей же
     * кнопкой «домой» объект согласия продолжал жить (аудит Этапа 6). И гейт H-1 на тех
     * экранах, где следующее нажатие OK решает за пользователя.
     */
    private fun show(screen: LocalScreen, html: String) {
        val web = host.currentPage() ?: return
        this.screen = screen
        host.forgetPendingConsent()
        web.loadDataWithBaseURL(null, html, "text/html", "utf-8", null)
        if (screen.decision) host.onDecisionScreen()
    }

    /** Новый одноразовый ключ для собираемой страницы. Старый с этого момента мёртв. */
    private fun newPageKey(): String = UUID.randomUUID().toString().also { pageKey = it }

    private fun str(id: Int): String = context.getString(id)

    /**
     * Митигация ADR-001: движок нам не принадлежит, и на приставке он может оказаться
     * сколь угодно старым. Safe Browsing начал работать в System WebView 66; на более
     * ранних версиях `setSafeBrowsingEnabled(true)` принимается, но не делает ничего,
     * то есть требование A-3 выполнялось бы только на бумаге.
     *
     * Пользователь узнаёт об этом сразу при запуске, а не в момент, когда уже открыл
     * вредоносный сайт. Обновляется System WebView через Google Play, отдельно
     * от прошивки телевизора.
     */
    private fun outdatedWebViewWarning(): String? {
        val version = WebView.getCurrentWebViewPackage()?.versionName ?: return null
        val major = version.substringBefore('.').toIntOrNull() ?: return null
        if (major >= SAFE_BROWSING_MIN_VERSION) return null
        return context.getString(R.string.webview_outdated, version)
    }

    /**
     * Принятый риск `B-03`, о котором пользователь имеет право знать. На прошивке
     * с `ro.debuggable=1` System WebView сам поднимает сокет отладчика, и любое
     * приложение с доступом к нему видит содержимое открытых страниц и может выполнять
     * в них свой код. Закрыть сокет нечем: он принадлежит движку, а не нам, и повторный
     * `setWebContentsDebuggingEnabled(false)` его не гасит - проверено на стенде.
     * Единственное доступное действие - сказать об этом.
     *
     * Отладочные прошивки на дешёвых приставках не редкость, и продаются они обычной
     * розницей: пользователь о таком не догадывается по внешнему виду телевизора.
     */
    private fun debuggableBuildWarning(): String? =
        if (isDebuggableBuild()) context.getString(R.string.build_debuggable) else null

    /**
     * Само свойство `ro.debuggable` публичным API не отдаётся, а скрытый доступ на новых
     * Android может оказаться закрыт. Поэтому два источника: сначала свойство, а если его
     * прочитать не дали - тип сборки. Тип грубее (`user` бывает и с `ro.debuggable=1`
     * на переделанных прошивках), но он публичный и не исчезнет.
     */
    private fun isDebuggableBuild(): Boolean {
        val flag = try {
            @Suppress("PrivateApi")
            Class.forName("android.os.SystemProperties")
                .getMethod("get", String::class.java)
                .invoke(null, "ro.debuggable") as? String
        } catch (e: Exception) {
            null
        }
        return if (!flag.isNullOrEmpty()) flag == "1" else Build.TYPE != "user"
    }

    private companion object {
        /**
         * Команды-переходы, ссылки на которые существуют только в разметке наших экранов.
         * Такая команда, пришедшая с сайта, отбрасывается (B-37).
         *
         * `open` сюда не входит: он уводит на сохранённый адрес, а увести пользователя
         * чужая страница может и обычной ссылкой. `rename` и `edit` не входят потому,
         * что проверяют экран строже - им нужен не «наш», а конкретный.
         */
        val OWN_SCREEN_ONLY = setOf(
            CMD_BOOKMARKS,
            CMD_HISTORY,
            CMD_CLEAR_ASK,
            CMD_SETTINGS,
            CMD_ABOUT,
            CMD_DONATE,
            CMD_WIPE_ASK,
        )

        /** Версия System WebView, начиная с которой Safe Browsing действительно работает. */
        const val SAFE_BROWSING_MIN_VERSION = 66

        /**
         * Приставка к номеру записи в ссылке локального экрана: по ней видно, в каком
         * списке искать адрес. Голое число осталось за избранным на домашней странице.
         */
        const val BOOKMARK_PREFIX = 'b'
        const val HISTORY_PREFIX = 'h'

        /** Закреплённые - свой список с ADR-054, и номера в нём свои. */
        const val PINNED_PREFIX = 'p'
    }
}
