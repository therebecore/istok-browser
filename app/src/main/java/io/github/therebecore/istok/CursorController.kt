package io.github.therebecore.istok

import android.os.SystemClock
import android.view.Choreographer
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.webkit.WebView

/**
 * Позиция курсора, отданная наружу **без упаковки**.
 *
 * Обычная `(Float, Float) -> Unit` здесь не годится: пока такой параметр был один,
 * R8 вставлял его тело прямо в вызывающий метод, а со вторым синтетический класс стал
 * общим для обоих, инлайн стал невозможен и вызов пошёл через `Function2.invoke`
 * с двумя `Float.valueOf` на каждый кадр движения. Кэша у `Float.valueOf` нет,
 * а бюджет аллокаций на кадровом пути нулевой (`perf-guardian`, Этап 5).
 */
internal fun interface CursorPoint {
    fun at(x: Float, y: Float)
}

/** То же самое для проверки: `Boolean` не упаковывается, координаты тоже. */
internal fun interface CursorPointCheck {
    fun matches(x: Float, y: Float): Boolean
}

/** Гейт H-6 наружу: спрашивают те, кто засчитывает нажатие сам, минуя [CursorController]. */
internal fun interface CursorGate {
    fun allowed(): Boolean
}

/**
 * Просьба измерить, что у страницы под курсором (`B-93`, ADR-038). Ответ приходит
 * не сразу и не всегда - через [CursorController.setScroller].
 */
/**
 * Показать или убрать подсказку режима прокрутки (ADR-046). Отдельным интерфейсом,
 * а не лямбдой, по той же причине, что и остальные (`B-35`): в этом файле так заведено.
 */
internal fun interface ScrollHint {
    fun locked(on: Boolean)
}

internal fun interface ScrollerProbe {
    fun at(x: Float, y: Float)
}

/**
 * Движение курсора по нажатиям D-pad (ADR-005, ADR-040).
 *
 * **Движение непрерывное, как у мыши:** курсор едет с первого кадра нажатия и до
 * отпускания, скорость нарастает прямой от [SPEED_START_DP] до [SPEED_MAX_DP].
 * Ни порога, ни дискретного шага - они были пробованы и забракованы, см. ADR-040.
 *
 * Позиция пересчитывается на каждом кадре по фактическому времени между кадрами,
 * а не по числу событий автоповтора клавиши - частота автоповтора зависит
 * от прошивки и пульта, а кадры идут ровно.
 *
 * Пока ни одна клавиша не нажата, цикл кадров не работает: в покое курсор не стоит
 * приложению ничего.
 */
internal class CursorController(
    private val area: ViewGroup,
    private val cursor: CursorOverlayView,
    /**
     * Страница под панелью. Именно функция, а не ссылка: WebView пересоздаётся
     * после смерти рендерера, и в промежутке его нет вовсе.
     *
     * Нужна не для кликов - те уходят в [area] и достаются тому, что под курсором
     * (требование H-3), - а для прокрутки краем экрана и для наведения: и то,
     * и другое имеет смысл только для страницы.
     */
    private val page: () -> View?,
    /**
     * Курсор переехал. Нужно тем нашим View, которые обязаны отзываться на наведение:
     * наведение (`hover`) уходит прямо в страницу и до них не доходит.
     */
    private val onMoved: CursorPoint,
    /**
     * **Единственная точка, где перечислено то, что лежит поверх страницы** - клавиатура,
     * подложка меню и всё, что появится дальше (требование H-7a). Курсор в этой точке
     * адресуется нашему элементу, а не странице под ним, поэтому странице не уходит
     * ни наведение ([sendHover]), ни прокрутка краем ([scrollPage]).
     *
     * Панель навигации сюда **не входит**, хотя она тоже наша: она не лежит поверх
     * страницы, а стоит рядом - страница начинается под ней. Наведение туда странице
     * не адресовано и отсекается по её собственной границе, а вот прокрутка остаётся:
     * верхний край экрана занят панелью, и запрет отнял бы единственный способ
     * прокрутить страницу вверх.
     */
    private val overOwnUi: CursorPointCheck,
    /**
     * Точка, в которой удержание OK означает не тот же клик, а отдельное действие
     * (ADR-034): наша клавиатура, где долгое нажатие на букве даёт цифру.
     *
     * Обычное нажатие курсором - мгновенная пара DOWN+UP (ADR-005), и удержания
     * платформа в ней не видит в принципе. Здесь же DOWN отправляется по нажатию,
     * а UP - по отпусканию, и долгое нажатие отмеряет сама платформа тем же таймером,
     * которым меряет его для пальца.
     */
    private val holdArea: CursorPointCheck,
    /**
     * Подсказка режима прокрутки. Режим модальный - стрелки перестают двигать курсор, -
     * и без подписи он читается как зависание: пользователь застрял на первой же проверке.
     */
    private val scrollHint: ScrollHint,
    /**
     * Спросить у страницы, что под курсором (`B-93`). Зовётся на нажатие клавиши
     * и не чаще [PROBE_INTERVAL_NANOS] при упоре в край списка - но не на каждом кадре:
     * это разговор с движком, а не проверка поля.
     */
    private val probe: ScrollerProbe,
) : Choreographer.FrameCallback {

    /** Идёт удержание: DOWN уже отправлен, UP ждёт отпускания клавиши. */
    private var holding = false

    /**
     * Режим прокрутки (`B-122`, ADR-046): курсор прилип к точке, и стрелки листают
     * то, что под ним, вместо того чтобы его двигать.
     *
     * Нужен там, где границы списка нам не видны: список серий внутри чужого плеера
     * мы не можем ни опознать, ни обвести - правило движка не пускает нас внутрь чужой
     * вставки. Гадать пробой оказалось хуже: узкая колонка списка занимает десятую часть
     * ширины экрана, и попадание в неё выходило случайным. Здесь не гадает никто -
     * место выбирает пользователь.
     */
    private var scrollLocked = false

    /** Клавиша OK уже сделала своё дело на нажатии: отпускание больше ничего не шлёт. */
    private var okHandled = false

    /** Нажатие пришло, когда курсора не было видно: оно только возвращает курсор. */
    private var okBlind = false

    /**
     * Идёт протяжка страницы: палец прижат и едет за курсором (`B-100`, ADR-042).
     * Точка касания своя, потому что она уходит от курсора - тот стоит у края.
     */
    private var dragging = false
    private var dragX = 0f
    private var dragY = 0f
    private var dragDownAt = 0L

    /** Время и точка отправленного DOWN: UP обязан прийти в ту же точку и с тем же началом. */
    private var holdDownAt = 0L
    private var holdX = 0f
    private var holdY = 0f

    /** Битовая маска нажатых направлений: см. константы `DIR_*`. */
    private var pressed = 0

    /** Скорости заданы в dp; множитель нужен на каждом кадре, поэтому снят один раз. */
    private val density = cursor.resources.displayMetrics.density

    private val choreographer = Choreographer.getInstance()

    private var x = 0f
    private var y = 0f

    /** Позиция ещё не выставлена: неизвестен размер экрана. */
    private var placed = false

    /** Виден ли курсор сейчас. См. [show] и [IDLE_HIDE_MS]. */
    private var visible = true

    /**
     * Когда показывался последний экран принятия решения. 0 - не показывался ни разу
     * за жизнь контроллера; обратно в 0 поле не возвращается. См. [onDecisionScreen].
     */
    private var decisionShownAt = 0L

    /** Двигали ли курсор после появления такого экрана. */
    private var movedSinceDecision = false

    private var running = false
    private var lastFrameNanos = 0L

    /** Сколько времени клавиши удерживаются непрерывно - из этого считается скорость. */
    private var heldNanos = 0L

    /**
     * До какого момента цикл кадров живёт после отпускания последней клавиши, не двигая
     * курсор (см. [RELEASE_GRACE_NANOS]). 0 - клавиши нажаты, отпускания не было.
     */
    private var releaseAtNanos = 0L

    /** Время последнего отправленного hover - для прореживания, см. [HOVER_INTERVAL_NANOS]. */
    private var lastHoverNanos = 0L

    /**
     * Границы области страницы с собственной прокруткой под курсором и стороны, в которые
     * ей ещё есть куда ехать (`B-93`). Пусто - под курсором такой области нет, или ответ
     * ещё не пришёл, или пользователь выключил это в настройках.
     *
     * Числа - экранные, страницу в них перевёл тот, кто отвечал: здесь система координат
     * одна на всё, и точка курсора сравнивается с границами напрямую.
     */
    private var scroller = false
    private var scrollerLeft = 0f
    private var scrollerTop = 0f
    private var scrollerRight = 0f
    private var scrollerBottom = 0f
    private var scrollerUp = false
    private var scrollerDown = false
    private var scrollerLeftward = false
    private var scrollerRightward = false

    /**
     * Область под курсором - чужая вставка, и куда ей есть куда ехать, неизвестно
     * (`B-101`): внутрь чужого документа движок смотреть не даёт. Такую курсор
     * придерживает **на доверии** и проверяет делом, см. [tryInner] и [pinned].
     */
    private var scrollerUnknown = false

    /** Когда последний раз спрашивали страницу - чтобы не спрашивать её на каждом кадре. */
    private var lastProbeNanos = 0L

    /** С какого момента курсор придерживается краем списка. 0 - не придерживается. */

    /**
     * Проба внутри чужой вставки (`B-107`, ADR-043). Внутрь неё не видно, поэтому узнать,
     * есть ли под курсором что листать, можно только одним способом - потянуть и посмотреть,
     * поехала ли страница под вставкой.
     *
     * 0 - проба не начата; иначе момент её начала.
     */
    private var tryingSinceNanos = 0L

    /** Прокрутка страницы на момент начала пробы - по ней и виден ответ. */
    private var tryScrollY = 0

    /** В этом удержании уже пробовали, и листать под курсором было нечего. */
    private var tryFailed = false

    /** Проба удалась: под курсором что-то listается, и туда уходит весь ход. */
    private var innerScrolling = false

    init {
        area.addOnLayoutChangeListener { _, left, top, right, bottom, _, _, _, _ ->
            onAreaChanged(right - left, bottom - top)
        }
    }

    /**
     * Обработка нажатия. Возвращает true, если событие наше и дальше идти не должно:
     * иначе WebView попытается ещё и увести фокус по своей D-pad навигации.
     */
    fun onKeyEvent(event: KeyEvent): Boolean {
        if (event.keyCode == KeyEvent.KEYCODE_DPAD_CENTER || event.keyCode == KeyEvent.KEYCODE_ENTER) {
            if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) {
                // Первое нажатие после того, как курсор пропал, только возвращает его:
                // куда именно пришёлся бы клик, пользователь в этот момент не видит.
                okBlind = !visible
                okHandled = false
                show()
                // Над нашей клавиатурой всё как было: DOWN уходит сразу, а долгое нажатие
                // отмеряет платформа своим таймером (там оно означает повтор символа,
                // а не прокрутку).
                if (!okBlind && clickAllowed() && holdArea.matches(x, y)) {
                    startHold()
                    okHandled = true
                } else if (!okBlind && clickAllowed()) {
                    // Над страницей клик ждёт отпускания: удержание OK - это вход
                    // в режим прокрутки (ADR-046), и кликать перед ним нельзя, иначе
                    // выбор серии случится раньше, чем пользователь успеет задержать
                    // клавишу. Разница между «по нажатию» и «по отпусканию» на глаз
                    // не видна - это сотые доли секунды, - а ADR-005 в этой части
                    // заменён ADR-046.
                    cursor.postDelayed(scrollLockStart, SCROLL_LOCK_MS)
                }
            }
            if (event.action == KeyEvent.ACTION_UP) {
                cursor.removeCallbacks(scrollLockStart)
                if (holding) {
                    endHold()
                } else if (!okHandled && !okBlind && clickAllowed()) {
                    // Короткое нажатие в режиме прокрутки - это выбор того, на чём стоим
                    // (серии в списке), и он же выключает режим: дальше курсор снова ездит.
                    if (scrollLocked) unlockScroll()
                    click()
                }
                okHandled = false
                scheduleHide()
            }
            return true
        }

        val direction = when (event.keyCode) {
            KeyEvent.KEYCODE_DPAD_LEFT -> DIR_LEFT
            KeyEvent.KEYCODE_DPAD_RIGHT -> DIR_RIGHT
            KeyEvent.KEYCODE_DPAD_UP -> DIR_UP
            KeyEvent.KEYCODE_DPAD_DOWN -> DIR_DOWN
            else -> return false
        }

        when (event.action) {
            KeyEvent.ACTION_DOWN -> {
                // Автоповтор шлёт ACTION_DOWN десятки раз в секунду, а show() каждый раз
                // ходит в очередь Handler снимать отложенное скрытие. Достаточно первого:
                // пока клавишу держат, скрывать курсор всё равно нечему.
                if (event.repeatCount == 0) {
                    show()
                    // Новое нажатие - новый вопрос странице: что под курсором сейчас
                    // (`B-93`). Прошлый ответ выбрасывается сразу: страница могла
                    // смениться, уехать или закрыть свой список, а пользоваться
                    // устаревшими границами - значит упереть курсор в пустоту.
                    //
                    // Но «новое нажатие» здесь - то же самое, что для разгона: жест
                    // считается новым, только когда цикл кадров уже снят. Часть пультов
                    // переводит удержание в серию коротких нажатий по 30-40 мс (см.
                    // [RELEASE_GRACE_NANOS]), и сброс на каждом из них обнулял пробу
                    // раньше, чем ей хватало [TRY_WINDOW_NANOS]: на таком пульте `B-107`
                    // не срабатывал ни разу, ни за какое время удержания. Внутри серии
                    // ответ обновит повторный опрос из [scrollPage] - не позже 330 мс.
                    if (!running) askScroller()
                }
                pressed = pressed or direction
                releaseAtNanos = 0
                start()
            }

            KeyEvent.ACTION_UP -> {
                pressed = pressed and direction.inv()
                if (pressed == 0) {
                    // Цикл кадров НЕ снимается сразу: он живёт ещё [RELEASE_GRACE_NANOS]
                    // и всё это время не двигает курсор, но продолжает считать удержание.
                    // Иначе автоповтор, приходящий серией нажатий с отпусканиями, рвал бы
                    // разгон тридцать раз в секунду - см. [RELEASE_GRACE_NANOS].
                    releaseAtNanos = System.nanoTime() + RELEASE_GRACE_NANOS
                    // Курсор встал: дослать точную позицию, иначе последнее, что знает
                    // страница о курсоре, - место на кадр раньше остановки.
                    sendHover()
                    scheduleHide()
                }
            }
        }
        return true
    }

    /**
     * Курсор поверх страницы мешает читать, а на телевизоре страницу чаще читают,
     * чем по ней кликают. Поэтому в покое он исчезает и возвращается от первого
     * же нажатия.
     */
    private fun show() {
        cursor.removeCallbacks(hide)
        if (!visible) {
            visible = true
            cursor.visibility = View.VISIBLE
        }
    }

    private fun scheduleHide() {
        cursor.removeCallbacks(hide)
        cursor.postDelayed(hide, IDLE_HIDE_MS)
    }

    private val hide = Runnable {
        visible = false
        cursor.visibility = View.INVISIBLE
    }

    /**
     * Экран, на котором пользователь принимает решение о безопасности, показан прямо
     * сейчас (требование H-1).
     *
     * Позиция курсора переживает навигацию, а страница знает её точно: мы сами шлём ей
     * `mousemove` двадцать раз в секунду. Этого достаточно, чтобы поймать момент:
     * страница рисует приманку там, где через мгновение окажется наша кнопка «всё равно
     * открыть», ждёт по `mousemove`, когда курсор встанет на неё, и в этот момент уводит
     * себя на http-адрес. Пользователь дожимает OK, целясь в приманку, - и разрешает
     * незащищённое соединение, не успев прочитать ни слова.
     *
     * Поэтому после показа такого экрана клик не засчитывается, пока не выполнено оба:
     * прошло [DECISION_GUARD_MS] и курсор сдвинули **после** появления экрана. Так же
     * устроена защита интерстишиалов в Chromium.
     */
    fun onDecisionScreen() {
        decisionShownAt = SystemClock.uptimeMillis()
        movedSinceDecision = false
    }

    /**
     * Можно ли засчитывать действие пользователя прямо сейчас. Спрашивают те клавиши
     * нашей клавиатуры, которые шлют события в страницу сами, а не через [click]:
     * иначе защита обходилась бы простым выбором другой клавиши.
     */
    fun inputAllowed(): Boolean = clickAllowed()

    private fun clickAllowed(): Boolean {
        if (decisionShownAt == 0L) return true
        if (!movedSinceDecision) return false
        return SystemClock.uptimeMillis() - decisionShownAt >= DECISION_GUARD_MS
    }

    /**
     * Вход в режим прокрутки по удержанию OK (ADR-046). Точка касания дальше будет
     * своя - там, где стоит курсор, - поэтому включаемся только над страницей.
     */
    private val scrollLockStart = Runnable {
        if (page() !is WebView || overOwnUi.matches(x, y)) return@Runnable
        scrollLocked = true
        okHandled = true
        cursor.setLocked(true)
        scrollHint.locked(true)
    }

    /** Выход из режима: курсор снова ездит, начатая протяжка закрывается. */
    private fun unlockScroll() {
        if (!scrollLocked) return
        scrollLocked = false
        endDrag()
        cursor.setLocked(false)
        scrollHint.locked(false)
    }

    /**
     * Выход из режима по кнопке «назад». Возвращает true, если кнопка израсходована
     * здесь и уходить со страницы не надо.
     */
    fun onBack(): Boolean {
        if (!scrollLocked) return false
        unlockScroll()
        return true
    }

    /**
     * Клик в текущей точке (ADR-005). Пара `ACTION_DOWN` + `ACTION_UP` одним временем:
     * то, во что попал курсор, видит обычный тап без движения.
     */
    private fun click() {
        val time = SystemClock.uptimeMillis()
        sendTouch(MotionEvent.ACTION_DOWN, time, time, x, y)
        sendTouch(MotionEvent.ACTION_UP, time, time, x, y)
    }

    /**
     * Начало удержания над клавиатурой: уходит только DOWN. Дальше время отмеряет
     * платформа - тем же таймером, что и для пальца, - и по нему решает, был это
     * обычный клик или долгое нажатие.
     */
    private fun startHold() {
        if (holding) endHold()
        holding = true
        holdDownAt = SystemClock.uptimeMillis()
        holdX = x
        holdY = y
        sendTouch(MotionEvent.ACTION_DOWN, holdDownAt, holdDownAt, holdX, holdY)
        // Страховка от потерянного ACTION_UP: пульт может отвалиться, а окно - смениться
        // прямо во время удержания. Без неё клавиша осталась бы нажатой навсегда.
        cursor.postDelayed(holdTimeout, HOLD_LIMIT_MS)
    }

    /**
     * Конец удержания. UP уходит **в ту же точку**, где был DOWN: между ними курсор
     * могли увести стрелкой, а пара событий обязана достаться одной и той же клавише.
     *
     * [canceled] - жест прерван не пользователем, а уходом с экрана (`B-53`). Тогда
     * вместо UP идёт `ACTION_CANCEL`: к этому моменту клавиатура уже скрыта (`onStop`
     * зовёт `closeInput` раньше `stop`), и UP достался бы не клавише, а тому, что под
     * ней, - то есть странице. CANCEL снимает нажатие у того, кто получил DOWN,
     * и никого ни на что не нажимает.
     *
     * Про «нажмёт на сайте» из самой записи: **замером это не подтвердилось**
     * (`.bench\st8-b53.ps1`, шаг 8 Этапа 8). Ушедший в страницу одиночный `UP` движок
     * в клик не превращает - пары для него не было, `DOWN` достался клавише. Правка
     * оставлена не поэтому: незакрытая пара `DOWN`/`UP` - это состояние нажатия, повисшее
     * на `View`, и закрывать его отменой правильно независимо от того, видно ли
     * последствия снаружи.
     */
    private fun endHold(canceled: Boolean = false) {
        if (!holding) return
        holding = false
        cursor.removeCallbacks(holdTimeout)
        val action = if (canceled) MotionEvent.ACTION_CANCEL else MotionEvent.ACTION_UP
        sendTouch(action, holdDownAt, SystemClock.uptimeMillis(), holdX, holdY)
    }

    private val holdTimeout = Runnable { endHold() }

    /**
     * Курсор стоит над страницей, а не над панелью навигации и не над нашим оверлеем.
     * Одно условие на два места: [sendHover] шлёт наведение только туда же.
     */
    private fun overPage(): Boolean {
        val view = page() ?: return false
        return y >= view.top && !overOwnUi.matches(x, y)
    }

    /**
     * Протяжка страницы в точке курсора - то же самое, что палец на телефоне
     * (`B-100`, ADR-042). Единственный способ прокрутки, которым мы пользуемся.
     *
     * **Колесо не годится, и это измерено** (`st9c-1.ps1`): `ACTION_SCROLL` в точке внутри
     * `iframe` уводит **главный документ**, а вложенный не двигается вовсе - ни чужой,
     * ни свой (контроль на своём же origin дал `scrollTop = 0` у ребёнка при 949 px
     * у родителя). Протяжка в той же точке двигает ровно вложенный документ и не двигает
     * главный - и для чужого origin тоже. На плеерах список серий живёт как раз в чужом
     * iframe, и достаётся он только так.
     *
     * Она же дешевле: на локальной `/heavy`, одна дистанция, две доставки - колесо
     * 93 кадра за 3 с и 98.92% просроченных, протяжка 125 кадров и 62.40%.
     *
     * Курсор упёрся в нижний край и продолжает давить вниз - значит человек хочет
     * увидеть то, что ниже, - значит палец едет **вверх**.
     */
    private fun drag(dx: Float, dy: Float) {
        if (dx == 0f && dy == 0f) return
        val target = page() as? WebView ?: return
        // Курсор уехал на наш UI посреди протяжки - типично, когда клавиатура поднялась
        // во время прокрутки краем. Касание закрывается здесь, а не оставляется
        // на `stopFrames()` (`B-118`): открытый жест переживает уход и достаётся
        // странице обрывком.
        if (overOwnUi.matches(x, y)) {
            endDrag()
            return
        }

        // Касание идёт **прямо в страницу**, а не в [area] через общий разбор координат,
        // как клик. Причина - верхний край: там курсор стоит выше страницы, на панели
        // навигации, а прокрутить вверх оттуда надо (панель занимает весь верх экрана,
        // и другого места для этого нет). Через [area] такое касание досталось бы кнопке
        // панели и нажало бы её. Здесь же точка зажимается в границы страницы, и палец
        // тянет её край, оставаясь внутри.
        // Отступ от края обязателен, и это измерено (`.bench\st9c-3.ps1`): палец,
        // поставленный в самую нижнюю строку пикселей, достаётся **главному документу**,
        // а не тому, что там нарисовано, - на экране 1080 свайп с 1079 листает документ,
        // а с 1075 и ниже уже список внутри чужого iframe. Курсор же стоит ровно на краю,
        // потому что его туда и упёрли.
        val inset = PIN_INSET_DP * density
        // Стороны нулевые у WebView, только что вставленного [restoreWebView]: разметка
        // ещё не прошла, а кадр уже идёт - `CALLBACK_ANIMATION` исполняется раньше
        // `CALLBACK_TRAVERSAL`. `coerceIn(inset, width - 1 - inset)` в этот момент получает
        // нижнюю границу больше верхней и бросает `IllegalArgumentException` (`B-109`),
        // то есть падал бы обработчик, написанный ради того, чтобы приложение не падало.
        // Ждать края экрана для этого не нужно: в режиме прокрутки [move] зовёт [drag]
        // на каждый ход. Соседний `probeScroller` закрыт от того же, но проверкой на ноль.
        // Граница здесь ровно та, которую требует `coerceIn`, а не на волос меньше:
        // при дробном `inset` между ними оставалось целое значение ширины, проходившее
        // проверку и всё равно бросавшее (`B-174`).
        if (target.width < 2 * inset + 1 || target.height < 2 * inset + 1) return
        val time = SystemClock.uptimeMillis()
        if (!dragging) {
            dragging = true
            dragDownAt = time
            dragX = (x - target.left).coerceIn(inset, target.width - 1 - inset)
            dragY = (y - target.top).coerceIn(inset, target.height - 1 - inset)
            target.dispatchTouch(MotionEvent.ACTION_DOWN, dragDownAt, time, dragX, dragY)
        }
        val nextX = dragX - dx
        val nextY = dragY - dy
        // Палец не выходит за страницу: у края он отрывается, и следующий кадр начинает
        // новое касание от позиции курсора - так же, как человек доводит длинный список
        // несколькими движениями руки.
        if (nextX < inset || nextY < inset ||
            nextX > target.width - 1 - inset || nextY > target.height - 1 - inset
        ) {
            endDrag()
            return
        }
        dragX = nextX
        dragY = nextY
        target.dispatchTouch(MotionEvent.ACTION_MOVE, dragDownAt, time, dragX, dragY)
    }

    /**
     * Касание в систему координат самой страницы, см. [drag].
     *
     * Наше касание - способ прокрутки, а не выбор места ввода. [BrowserWebView] иначе
     * читает `ACTION_DOWN` как «человек ткнул в поле» (единственный сигнал, по которому
     * это видно), снимает заслон от системной клавиатуры и поднимает нашу: на сайте
     * с автофокусом первый же кадр протяжки возвращал клавиатуру на экран, курсор
     * оказывался на ней, и прокрутка краем умирала совсем. До ADR-042 прокрутка шла
     * колесом и в `dispatchTouchEvent` не попадала - канал появился вместе с протяжкой.
     */
    private fun View.dispatchTouch(action: Int, downTime: Long, eventTime: Long, atX: Float, atY: Float) {
        val event = MotionEvent.obtain(downTime, eventTime, action, atX, atY, 0)
        event.source = InputDevice.SOURCE_TOUCHSCREEN
        val web = this as? BrowserWebView
        web?.synthetic = true
        // Бросок из `dispatchTouchEvent` - не гипотеза, а `B-109`. Без `finally` он
        // оставлял бы флаг поднятым навсегда: `onTouched` умирал бы до конца жизни
        // экземпляра, то есть клавиатура перестала бы подниматься по касанию (`B-120`).
        // Событие возвращается системе там же - иначе бросок съедал бы его из пула.
        try {
            dispatchTouchEvent(event)
        } finally {
            web?.synthetic = false
            event.recycle()
        }
    }

    /**
     * Палец отрывается **отменой, а не отпусканием**. `ACTION_UP` после короткого хода -
     * это для движка обычный тап: курсор у края экрана стоит на ссылке, и короткое нажатие
     * стрелки, сдвинувшее страницу меньше чем на порог жеста, открывало бы её. `CANCEL`
     * снимает касание у того, кто получил `DOWN`, и никого ни на что не нажимает; заодно
     * не остаётся инерции, которая на пульте только мешает целиться.
     */
    private fun endDrag() {
        if (!dragging) return
        dragging = false
        (page() as? WebView)?.dispatchTouch(
            MotionEvent.ACTION_CANCEL, dragDownAt, SystemClock.uptimeMillis(), dragX, dragY,
        )
    }

    /**
     * Требование H-3: под курсором может быть и страница, и панель навигации, которая
     * с Этапа 4 видна всегда (ADR-017). Поэтому событие уходит не в WebView, а в [area],
     * и дальше платформа сама выбирает потомка по координатам и пересчитывает их
     * в его систему - тем же кодом, которым разносит настоящие касания.
     *
     * Сам курсор лежит поверх всех и получает событие первым, но не перехватывает его:
     * [CursorOverlayView] не кликабельна, а некликабельная View возвращает false
     * и пропускает событие к тем, кто ниже.
     */
    private fun sendTouch(action: Int, downTime: Long, eventTime: Long, atX: Float, atY: Float) {
        val event = MotionEvent.obtain(downTime, eventTime, action, atX, atY, 0)
        event.source = InputDevice.SOURCE_TOUCHSCREEN
        area.dispatchTouchEvent(event)
        event.recycle()
    }

    /**
     * Сообщение странице, что указатель переехал: без него не работают `:hover`
     * и выпадающие меню, ради которых hover и добавлен в ADR-005.
     *
     * Источник - мышь: только для неё движок заводит указатель и генерирует
     * `mousemove`. Клик остаётся касанием, потому что тап проходит на сайтах,
     * которые мышиных кнопок не ждут.
     *
     * Наведение идёт прямо в страницу, а не через [area]: над панелью указателя
     * для страницы нет вовсе, и слать ей координаты выше её собственной границы -
     * значит подсвечивать то, на что курсор не наведён.
     *
     * По той же причине наведение молчит, когда курсор стоит на том, что лежит поверх
     * страницы - список таких элементов один на всё приложение, см. [overOwnUi].
     * Она лежит поверх страницы, страница под ней не видна и реагировать не должна,
     * а платит за это она полной ценой: на тяжёлой странице один `mousemove` стоит
     * около 14 мс кадра, и двадцать раз в секунду это 86% просроченных кадров против
     * 0.19% без него (замер `perf-guardian`, Этап 5). Вторая причина важнее первой:
     * координаты клавиш постоянны, а перед нажатием курсор замирает на клавише -
     * то есть по одному наведению страница восстанавливала бы набираемое, включая
     * пароль, не получив ни единого нажатия.
     */
    private fun sendHover() {
        val view = page() ?: return
        if (!overPage()) return
        // Курсор упёрся в край и тянет страницу: он стоит на месте, а наведение стоит
        // до 14 мс кадра на тяжёлой странице. Слать одну и ту же точку во время протяжки
        // незачем, и это ровно тот случай, на который пользователь жалуется как на рывки.
        if (dragging) return

        val time = SystemClock.uptimeMillis()
        val event = MotionEvent.obtain(
            time, time, MotionEvent.ACTION_HOVER_MOVE, x - view.left, y - view.top, 0,
        )
        event.source = InputDevice.SOURCE_MOUSE
        view.dispatchGenericMotionEvent(event)
        event.recycle()
    }

    /**
     * Уход с экрана. `ACTION_UP` за зажатую в этот момент клавишу уже не придёт,
     * поэтому цикл кадров и отложенное скрытие снимаются здесь принудительно:
     * в фоне у приложения не должно остаться ни одной работающей задачи.
     *
     * Удержание отменяется первой строкой (`B-53`): без этого пара DOWN/UP осталась бы
     * незакрытой, а страховочный [holdTimeout] дожил бы до трёх секунд и отправил `UP`
     * в точку, где клавиатуры уже нет, - то есть в страницу.
     */
    fun stop() {
        // Отложенный вход в режим прокрутки снимается первым. Держим OK над страницей
        // и в пределах 500 мс жмём HOME: `ACTION_UP` за OK уже не придёт, а задача
        // сработала бы **в фоне** - человек вернулся бы в браузер и застал модальную
        // прокрутку, о которой не просил (`B-152`).
        cursor.removeCallbacks(scrollLockStart)
        unlockScroll()
        endHold(canceled = true)
        stopFrames()
        cursor.removeCallbacks(hide)
        hide.run()
    }

    private fun stopFrames() {
        pressed = 0
        releaseAtNanos = 0
        // Палец отрывается здесь, а не по отпусканию стрелки: удержание приходит серией
        // нажатий с отпусканиями по 30-40 мс (см. [RELEASE_GRACE_NANOS]), и отрыв на
        // каждом из них рвал бы протяжку на куски короче порога распознавания жеста -
        // страница не сдвинулась бы вообще. Тот же выбег склеивает и разгон.
        endDrag()
        if (running) {
            running = false
            choreographer.removeFrameCallback(this)
        }
    }

    override fun doFrame(frameTimeNanos: Long) {
        if (!running) return

        val deltaNanos = (frameTimeNanos - lastFrameNanos).coerceIn(0L, MAX_FRAME_NANOS)
        lastFrameNanos = frameTimeNanos
        heldNanos += deltaNanos

        // Выбег после отпускания: курсор стоит, разгон продолжает считаться. Кончился
        // выбег - цикл снимается, и следующее нажатие начнётся со стартовой скорости.
        if (pressed == 0) {
            if (frameTimeNanos >= releaseAtNanos) {
                stopFrames()
                return
            }
            choreographer.postFrameCallback(this)
            return
        }

        val speed = speed()
        move(speed * deltaNanos / NANOS_IN_SECOND)

        // Hover стоит дорого и потому идёт не на каждом кадре и не на любой скорости.
        // Замер `gfxinfo framestats`: одно такое событие добавляет 3.3-4.5 мс записи
        // display list WebView - движок ищет элемент под указателем и перерисовывается.
        // Пока курсор летит через экран, подсветка пролетающих ссылок не нужна никому:
        // пользователь смотрит, куда едет. На медленном ходу hover идёт, и ещё один
        // досылается по остановке - именно он делает `:hover` верным.
        //
        // Интервал постоянный, и это проверено (`B-14`, шаг 4 Этапа 8): прореживание
        // по времени кадра пробовали и отклонили замером. Сигнала «система не успевает»
        // у Choreographer для этого нет - он зовёт нас каждый vsync, и промежуток между
        // вызовами остаётся 16.7 мс даже там, где половина кадров рисуется по 18 мс
        // (`Missed Vsync` ноль). А защищать оказалось нечего: постоянный интервал сам
        // даёт под нагрузкой 20.7 события в секунду против 24.8 в покое.
        if (speed <= HOVER_MAX_SPEED_DP * density &&
            frameTimeNanos - lastHoverNanos >= HOVER_INTERVAL_NANOS
        ) {
            lastHoverNanos = frameTimeNanos
            sendHover()
        }

        choreographer.postFrameCallback(this)
    }

    /** [distance] - на сколько пикселей курсор едет прямо сейчас: за кадр или за шаг. */
    private fun move(distance: Float) {
        var dx = 0f
        var dy = 0f
        if (pressed and DIR_LEFT != 0) dx -= 1f
        if (pressed and DIR_RIGHT != 0) dx += 1f
        if (pressed and DIR_UP != 0) dy -= 1f
        if (pressed and DIR_DOWN != 0) dy += 1f

        // Нажаты две оси сразу: без этого по диагонали курсор шёл бы в 1.41 раза быстрее.
        if (dx != 0f && dy != 0f) {
            dx *= DIAGONAL
            dy *= DIAGONAL
        }

        // Режим прокрутки: курсор стоит, а весь ход уходит в то, что под ним. Ни проб,
        // ни догадок - место выбрал пользователь, и оно не меняется до выхода из режима.
        if (scrollLocked) {
            drag(dx * distance, dy * distance)
            return
        }

        // Уход вбок снимает удержание (`B-122`, решение пользователя 2026-08-18):
        // «выйдет из списка либо нажав на серию, либо сместив курсор влево или вправо».
        // Это единственный выход из прокрутки, кроме клика, - таймеров больше нет.
        if (dx != 0f) releaseInner()

        val targetX = x + dx * distance
        val targetY = y + dy * distance
        val prevX = x
        val prevY = y
        // Курсор упирается не только в край экрана, но и в край списка под собой (`B-93`).
        // Дальше он не идёт, а то, что не поместилось, уезжает в этот список - тем же
        // способом, что и в страницу у края экрана. Так список посреди экрана листается
        // сам собой, без единой новой кнопки: довёл курсор до его края и держишь дальше.
        var stopX = targetX
        var stopY = targetY
        if (pinned()) {
            // Придерживаем только там, где списку ещё есть куда ехать. Кончился -
            // курсор идёт дальше, и запереть его в списке страница не может.
            //
            // Останавливаем **внутри** списка, а не на его границе: ровно на границе
            // страница видит уже не список, а то, что под ним, и прокрутка уезжала документу
            // (проверено стендом: курсор вставал на край и залипал там навсегда).
            val inset = PIN_INSET_DP * density
            if (scrollerDown && targetY > scrollerBottom - inset) stopY = scrollerBottom - inset
            if (scrollerUp && targetY < scrollerTop + inset) stopY = scrollerTop + inset
            if (scrollerRightward && targetX > scrollerRight - inset) stopX = scrollerRight - inset
            if (scrollerLeftward && targetX < scrollerLeft + inset) stopX = scrollerLeft + inset
        }
        setPosition(stopX, stopY)

        // Требование H-1 говорит про сдвиг курсора пользователем, а не про любое
        // изменение позиции. Флаг снимается отсюда, а не из setPosition: тот вызывается
        // ещё и при смене размера области, а переложенный системой курсор согласием
        // пользователя не является.
        if (x != prevX || y != prevY) movedSinceDecision = true

        // Что под курсором - спрашиваем на ходу, а не только когда он уже упёрся
        // (`B-124`). Пользователь **ведёт курсор по списку**, то есть въезжает в него
        // внутри одного удержания: «берёшь открываешь список серий и курсором ведёшь
        // по нему вниз». Опрос только при упоре такой въезд не замечал вовсе - вставка
        // под курсором оставалась неизвестной до следующего нажатия.
        probeIfDue()

        // Курсор упёрся в край, а клавишу продолжают держать: то, что не поместилось
        // на экране, уезжает в страницу. Отдельного жеста для прокрутки на пульте
        // нет, и край экрана - единственное место, где движение курсора заведомо
        // ничего не значит.
        val leftoverX = targetX - x
        val leftoverY = targetY - y
        // Упёрся в край чужой вставки - сначала пробуем листать то, что внутри неё
        // (`B-107`). Список серий у плеера живёт внутри такой вставки, и попасть
        // в него можно только протяжкой в точке, где стоит курсор.
        if (tryInner(leftoverX, leftoverY)) return
        scrollPage(leftoverX, leftoverY)
    }

    /**
     * Спросить страницу, что под курсором, если пора (`B-124`). Не чаще
     * [PROBE_INTERVAL_NANOS], только когда курсор реально движется, и никогда -
     * над нашим UI: опрос идёт в главном мире страницы и несёт ей координаты
     * курсора (требование H-7, см. [askScroller]).
     */
    private fun probeIfDue() {
        val now = System.nanoTime()
        if (now - lastProbeNanos < PROBE_INTERVAL_NANOS) return
        if (overOwnUi.matches(x, y)) return
        lastProbeNanos = now
        probe.at(x, y)
    }

    /**
     * Стоит ли курсор внутри известной нам области с собственной прокруткой (`B-93`).
     *
     * Границы приходят от страницы и могут быть враньём во весь экран. Пока курсор
     * не на нашем UI, это только заморозка на её же территории; заехав на нашу
     * клавиатуру, он утащил бы туда и чужие границы (`B-119`) - отсюда последняя
     * проверка, она же делает правдой обещание требования H-7.
     */
    private fun insideScroller(): Boolean = scroller &&
        x >= scrollerLeft && x <= scrollerRight && y >= scrollerTop && y <= scrollerBottom &&
        !overOwnUi.matches(x, y)

    /**
     * Проба внутри чужой вставки (ADR-043, переработана ADR-045). Возвращает true, пока
     * остаток хода должен уходить внутрь вставки, а не в страницу под ней.
     *
     * Вызывается, только когда курсор **уже упёрся в край вставки** - то есть проехал
     * по ней сверху вниз и дальше ему некуда. Так требование пользователя («список
     * прокручивается по нижнему и верхнему края самого списка») выполняется настолько,
     * насколько мы вообще видим: край внутреннего списка нам не виден, виден только край
     * вставки, и при выпадающем списке на всю её площадь это одно и то же.
     *
     * Порядок: тянем [TRY_WINDOW_NANOS] и смотрим страницу. **Поехала** - значит внутри
     * листать нечего (движок отдал прокрутку документу под вставкой): возвращаем страницу
     * на место и до конца удержания больше не пробуем, а курсор перестаёт держаться краем.
     * **Не поехала** - значит внутри вставки что-то поехало, и дальше курсор стоит,
     * а стрелки листают. Кончилось внутреннее - страница снова поедет, и мы это увидим.
     */
    private fun tryInner(dx: Float, dy: Float): Boolean {
        if (!scrollerUnknown || !insideScroller() || tryFailed) return false
        if (dx == 0f && dy == 0f) return false
        val web = page() as? WebView ?: return false

        if (innerScrolling) {
            // Внутреннее кончилось - прокрутку снова получает страница. Тогда листать
            // здесь больше нечего, и курсор возвращается к движению. Предела по времени
            // нет: пока список едет, удержание держится сколько угодно (`B-122`),
            // а выход - вбок или кликом.
            if (web.scrollY != tryScrollY) {
                releaseInner()
                return false
            }
            drag(dx, dy)
            return true
        }

        if (tryingSinceNanos == 0L) {
            tryingSinceNanos = System.nanoTime()
            tryScrollY = web.scrollY
            drag(dx, dy)
            return true
        }
        if (System.nanoTime() - tryingSinceNanos < TRY_WINDOW_NANOS) {
            drag(dx, dy)
            return true
        }

        if (web.scrollY != tryScrollY) {
            web.scrollBy(0, tryScrollY - web.scrollY)
            endDrag()
            tryFailed = true
            tryingSinceNanos = 0
            return false
        }
        innerScrolling = true
        drag(dx, dy)
        return true
    }

    /**
     * Отпустить всё, что держит курсор внутри чужой вставки: уход вбок, клик и смена
     * жеста снимают удержание немедленно (`B-122`).
     */
    private fun releaseInner() {
        if (tryingSinceNanos == 0L && !innerScrolling) return
        innerScrolling = false
        tryFailed = true
        tryingSinceNanos = 0
        endDrag()
    }

    /**
     * Держит ли курсор край списка под ним. **Предела по времени нет** - решение
     * пользователя 2026-08-18 (`B-122`): удержание держится, пока курсор давит в край,
     * и снимается уходом вбок ([releaseInner]) либо кликом по тому, что под курсором.
     *
     * Прежние восемь секунд для своего списка и две для чужой вставки убраны: они
     * обрывали честное листание посреди работы - длинный список серий не пролистывался
     * за один жест. От вранья страницы про свои границы защищает не таймер, а сам ход
     * пользователя: границы обновляются опросом ([probeIfDue]) трижды в секунду,
     * а курсор всегда можно увести вбок.
     *
     * Чужая вставка, которая пробу не прошла ([tryFailed]), край больше не держит:
     * листать внутри неё нечего, значит и держаться не за что.
     */
    private fun pinned(): Boolean = insideScroller() && !tryFailed

    /**
     * Спросить страницу, что под курсором (`B-93`). Ответ придёт в [setScroller] позже,
     * поэтому прошлый забывается прямо здесь: устаревшие границы хуже отсутствующих -
     * по ним курсор упрётся там, где давно ничего нет.
     */
    private fun askScroller() {
        scroller = false
        scrollerUnknown = false
        tryingSinceNanos = 0L
        tryFailed = false
        innerScrolling = false
        lastProbeNanos = System.nanoTime()
        // Требование H-7. Опрос исполняется в главном мире страницы и несёт ей
        // координаты курсора, поэтому над нашим UI он не отправляется вовсе. Иначе
        // страница, подменившая `document.elementFromPoint`, читала бы, по каким
        // клавишам нашей клавиатуры ходит курсор, и восстанавливала набираемое
        // посимвольно - раскладка постоянна, а перед нажатием курсор замирает
        // на клавише. Прошлый ответ при этом уже забыт выше, то есть придержание
        // клавиатуре не достанется.
        if (overOwnUi.matches(x, y)) return
        probe.at(x, y)
    }

    /**
     * Ответ на [askScroller], уже в экранных координатах. Пустой ответ - под курсором
     * обычная страница, и тогда курсор ничто не придерживает.
     */
    fun setScroller(
        left: Float,
        top: Float,
        right: Float,
        bottom: Float,
        up: Boolean,
        down: Boolean,
        leftward: Boolean,
        rightward: Boolean,
        unknown: Boolean,
    ) {
        scroller = true
        scrollerUnknown = unknown
        scrollerLeft = left
        scrollerTop = top
        scrollerRight = right
        scrollerBottom = bottom
        scrollerUp = up
        scrollerDown = down
        scrollerLeftward = leftward
        scrollerRightward = rightward
    }

    fun clearScroller() {
        scroller = false
        scrollerUnknown = false
    }

    /**
     * Требование H-7a. Курсор, упёршийся в нижний край при открытой клавиатуре, стоит
     * **на клавише**, а не на странице - и до этой правки продолжал уводить страницу
     * под клавиатурой. Это и странность интерфейса (попытка дотянуться до нижнего ряда
     * увозит страницу), и канал наружу: на прокрутку Chromium досылает себе `mousemove`
     * по последней известной позиции, то есть страница узнаёт, что пользователь давит
     * в нижний край, хотя наведение туда мы уже не шлём.
     */
    private fun scrollPage(dx: Float, dy: Float) {
        if (dx == 0f && dy == 0f) return
        if (overOwnUi.matches(x, y)) return
        // Только страница. В полноэкранном режиме `page()` отдаёт View плеера, которая
        // принадлежит движку, и прокрутка уводила вбок саму картинку - прокручивать там
        // нечего, а видео уезжало за край (B-89, найдено пользователем на живом ТВ).
        if (page() !is WebView) return
        drag(dx, dy)
    }

    /**
     * Скорость хода: прямой разгон от [SPEED_START_DP] до [SPEED_MAX_DP] за [RAMP_SECONDS].
     *
     * Ход идёт **с первого кадра нажатия**, без порога и без дискретного шага. Порог
     * и шаг были пробованы (`B-92`, первый заход) и пользователь забраковал их сразу:
     * курсор, который на короткое нажатие телепортируется на постоянные 16dp, читается
     * не как точность, а как крупная сетка - «пиксель 10 см, двигается как уёбан».
     * Плавность здесь важнее предсказуемости расстояния: движение обязано быть
     * непрерывным, как у мыши.
     *
     * Разгон **прямой, а не кривой** `p * p`: кривая держала первые доли секунды почти
     * на стартовой скорости, и это и есть та «непредсказуемость», с которой пользователь
     * пришёл, - расстояние за одинаковое на ощупь нажатие менялось нелинейно.
     */
    private fun speed(): Float {
        val p = (heldNanos / NANOS_IN_SECOND / RAMP_SECONDS).coerceAtMost(1f)
        return (SPEED_START_DP + (SPEED_MAX_DP - SPEED_START_DP) * p) * density
    }

    private fun start() {
        if (running) return
        running = true
        // Разгон обнуляется здесь, и это единственное место. Сюда попадают только те
        // нажатия, у которых цикл кадров уже снят, - то есть после паузы длиннее
        // [RELEASE_GRACE_NANOS]. Серия автоповтора внутрь не попадает: цикл в её паузах
        // жив, и `start()` выходит по первой строке.
        heldNanos = 0
        lastFrameNanos = System.nanoTime()
        choreographer.postFrameCallback(this)
    }

    private fun onAreaChanged(width: Int, height: Int) {
        if (width == 0 || height == 0) return
        if (!placed) {
            placed = true
            setPosition(width / 2f, height / 2f)
            // Курсор показывается в центре сразу при запуске - иначе непонятно, чем
            // тут вообще управляют. Но если к нему не притронулись, он уходит сам.
            scheduleHide()
            return
        }
        // Экран изменился (смена разрешения выхода, окно в Google TV) - вернуть курсор внутрь.
        setPosition(x, y)
    }

    /**
     * Единственное место, где меняется позиция. Остриё курсора не выпускается за пределы
     * экрана; хвост стрелки у правого и нижнего края уходит за границу и обрезается -
     * так же ведёт себя системный курсор, и это лучше, чем недоступная полоса у края.
     */
    private fun setPosition(newX: Float, newY: Float) {
        x = newX.coerceIn(0f, (area.width - 1).toFloat())
        y = newY.coerceIn(0f, (area.height - 1).toFloat())
        cursor.translationX = x - cursor.hotspotOffset
        cursor.translationY = y - cursor.hotspotOffset
        onMoved.at(x, y)
    }

    private companion object {
        const val DIR_LEFT = 1
        const val DIR_RIGHT = 2
        const val DIR_UP = 4
        const val DIR_DOWN = 8

        /**
         * Скорость в первый момент нажатия. Она же определяет, на сколько сдвинется
         * курсор от короткого тычка: 250 dp/с - это около 25dp за обычное нажатие
         * в 100 мс, то есть половина строки меню.
         */
        const val SPEED_START_DP = 250f

        /**
         * Скорость после разгона. Ширина экрана телевизора - около 960dp: на этих числах
         * она проходится примерно за секунду при требовании ADR-005 в полторы.
         *
         * Было 1400 за 0.7 секунды - пользователь назвал это «ультра быстрым», и он прав:
         * экран пролетал целиком быстрее, чем человек успевает решить, где остановиться.
         * Целились в «плавно, но быстро»: разгон длиннее, потолок ниже, к максимуму
         * скорость подходит мягко, а не рывком.
         */
        const val SPEED_MAX_DP = 950f
        const val RAMP_SECONDS = 0.8f

        /**
         * Сколько цикл кадров живёт после отпускания последней клавиши, не двигая курсор,
         * но продолжая считать удержание.
         *
         * Нужен потому, что **удержание приходит не всегда одним нажатием**: и окно
         * эмулятора, и часть пультов переводят автоповтор в серию коротких нажатий
         * с отпусканиями по 30-40 мс. Без выбега каждое такое отпускание обнуляло разгон,
         * и получалось ровно то, на что жаловался пользователь: держишь стрелку вниз -
         * скорость не растёт, а падает (на каждом обрыве терялось ещё и время между
         * кадрами), а добавишь вбок - едет быстро, потому что серии двух клавиш
         * перекрываются и ноль нажатых не наступает.
         *
         * 80 мс - это вдвое больше паузы автоповтора и заметно меньше паузы между
         * человеческими тычками (125 мс и больше даже при быстром нажимании). Поэтому
         * серия склеивается в удержание, а тычки остаются отдельными и каждый начинается
         * со [SPEED_START_DP].
         *
         * Курсор при этом встаёт **мгновенно**: во время выбега нажатых направлений нет,
         * и двигать его нечему. Выбег стоит нескольких пустых кадров.
         */
        const val RELEASE_GRACE_NANOS = 80_000_000L

        const val DIAGONAL = 0.707f
        const val NANOS_IN_SECOND = 1_000_000_000f

        /**
         * Как часто можно переспрашивать страницу про список под курсором (`B-93`).
         * Это выполнение скрипта в чужом документе, а не чтение поля: на тяжёлой странице
         * оно стоит кадра. Раз в треть секунды - предел, при котором конец списка
         * замечается сразу, а цена остаётся незаметной.
         */
        const val PROBE_INTERVAL_NANOS = 330_000_000L

        /**
         * На сколько курсор останавливается внутри списка, не доходя до его границы
         * (`B-93`). Пары пикселей мало: у списков бывает своя рамка и полоса прокрутки,
         * и попасть пальцем надо в содержимое, а не в край.
         */
        const val PIN_INSET_DP = 6f

        /**
         * Длина пробы: тянем вставку столько и смотрим, поехала ли страница под ней.
         * Хватает нескольких кадров, чтобы движок успел ответить.
         */
        const val TRY_WINDOW_NANOS = 120_000_000L

        /**
         * Сколько держать OK, чтобы курсор прилип к тому, что под ним (ADR-046).
         *
         * Полсекунды: заметно дольше обычного нажатия, но короче, чем пользователь
         * успеет усомниться, нажалось ли вообще. Столько же платформа отмеряет
         * для долгого нажатия пальцем.
         */
        const val SCROLL_LOCK_MS = 500L

        /** Не чаще 20 hover в секунду - примерно каждый третий кадр. */
        const val HOVER_INTERVAL_NANOS = 50_000_000L

        /**
         * Скорость, выше которой hover не отправляется вовсе. Достигается примерно
         * через 0.2 с удержания - то есть подсветка работает при точном наведении
         * и не работает на быстром ходу через экран.
         */
        const val HOVER_MAX_SPEED_DP = 500f

        /** Сколько курсор ждёт нажатия, прежде чем убраться с экрана. */
        const val IDLE_HIDE_MS = 5_000L

        /** Сколько экран принятия решения не принимает клик после показа. */
        const val DECISION_GUARD_MS = 600L

        /**
         * Предел одного удержания. Отпускание клавиши приходит своим событием, но если
         * оно потеряется - а на телевизоре теряется и пульт, и фокус окна, - нажатая
         * клавиша обязана отпуститься сама. Дольше этого удержание не означает ничего
         * нового: долгое нажатие платформа засчитывает через полсекунды.
         */
        const val HOLD_LIMIT_MS = 3_000L

        /**
         * Ограничение шага на кадр. Если система задержала кадры (сборка мусора, загрузка
         * тяжёлой страницы), курсор не должен прыгать через полэкрана - лучше отстать.
         */
        const val MAX_FRAME_NANOS = 100_000_000L
    }
}
