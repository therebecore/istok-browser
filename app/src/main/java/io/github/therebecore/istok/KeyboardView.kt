package io.github.therebecore.istok

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.util.AttributeSet
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewConfiguration
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView

/**
 * Набран символ. Обычная `(Char) -> Unit` упаковывает каждую букву в `Character`:
 * кэш там только на первые 128 значений, то есть кириллица создаёт объект на нажатие.
 */
internal fun interface CharInput {
    fun type(char: Char)
}

/**
 * Сменился способ управления. Тоже `fun interface`, а не `(Boolean) -> Unit` (`B-35`):
 * упаковка `Boolean` дешевле буквы - `valueOf` отдаёт один из двух готовых объектов, -
 * но два соседних обратных вызова одного класса, устроенных по-разному, заставляют
 * разбираться, чем они отличаются, каждый раз заново. Отличаться им нечем.
 */
internal fun interface ModeSwitch {
    fun onModeChanged(dpad: Boolean)
}

/** Клавиша, которая делает что-то с текстом, а не добавляет символ. */
internal enum class KeyAction {
    BACKSPACE,
    ENTER,
    CLEAR,
    CLOSE,
    LEFT,
    RIGHT,
    PASTE,
    COPY,
    CUT,
    SELECT_ALL,
}

/**
 * Экранная клавиатура (ADR-020..024, раскладка - ADR-034).
 *
 * Своя, а не системный IME: клавиатура телевизора живёт в отдельном окне во весь экран,
 * а курсор доставляет нажатие диспетчеризацией по нашему корню (требование H-3)
 * и до чужого окна не дотягивается.
 *
 * Набранного текста клавиатура **не показывает** (ADR-020): символ уходит прямо
 * в поле, эхо рисует само поле. Своего буфера здесь нет вовсе, поэтому маскировать
 * в нём нечего - требование F-3 выполняется тем, что показывать нечего.
 *
 * Расположение (ADR-024, уточнено ADR-034): сама клавиатура - обычная QWERTY,
 * справа от неё столбец правки (стереть, ввод, очистить, убрать), слева - действия
 * над текстом целиком: буфер обмена, выделение, способ управления. Разделены они
 * не разметкой, а расстоянием: промах по «убрать клавиатуру» вместо «ввода» дороже
 * любой экономии места.
 *
 * **Ряд делится долями, а не набирается из клавиш постоянной ширины** (ADR-034):
 * долей двенадцать, буква - одна, регистр - две, служебная клавиша нижнего ряда -
 * полторы, пробел забирает остаток. Ряды разной длины (латиница 10 букв, кириллица 12),
 * и постоянная ширина оставляла бы у короткого ряда поля по краям. Цифры - удержанием
 * клавиши верхнего ряда, оттуда же символы адреса.
 *
 * Клавиши не фокусируемы намеренно. С API 26 `android:focusable` по умолчанию
 * выводится из кликабельности, и клавиша забирала бы фокус себе - вместе с ним
 * ушёл бы фокус из поля страницы, куда мы и целимся символом.
 *
 * **Обходы по индексу здесь намеренные, а не небрежность.** `highlightAt`,
 * `updateSelection`, `stepRow`, `rebuildRows` и `onLayout` лежат на кадровом пути
 * движения курсора и на пути нажатия стрелки, а бюджет аллокаций там нулевой:
 * `for (key in line)` создаёт итератор, и `perf-guardian` снял их разбором dex
 * (Этап 5, два блокера). Привести это к `forEach` значит вернуть находку.
 */
internal class KeyboardView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : LinearLayout(context, attrs) {

    /** Набран символ. Регистр уже применён. */
    var onChar: CharInput? = null

    /** Нажата клавиша действия. */
    var onAction: ((KeyAction) -> Unit)? = null

    /**
     * Можно ли засчитывать нажатие прямо сейчас - гейт H-6 у курсора. Спрашивает его
     * только режим пульта: нажатие курсором проверяется в самом `CursorController`,
     * а здесь клавиша срабатывает от `performClick`, то есть мимо него.
     */
    var inputAllowed: CursorGate? = null

    /** Сменился способ управления: true - клавишами пульта, false - курсором. */
    var onModeChanged: ModeSwitch? = null

    /** Клавишами пульта ходят по самой клавиатуре, а не курсором по экрану. */
    private var dpadMode = false

    /** Размеры клавиш снимаются один раз: при сборке они спрашиваются полсотни раз. */
    private val keyHeight = resources.getDimensionPixelSize(R.dimen.key_height)
    private val sideWidth = resources.getDimensionPixelSize(R.dimen.key_side_width)
    private val margin = resources.getDimensionPixelSize(R.dimen.key_margin)

    private val letters = ArrayList<KeyText>(ROWS * COLUMNS)
    private val leftColumn = ArrayList<View>(LINES)
    private val rightColumn = ArrayList<View>(LINES)

    /** Состав каждого ряда в порядке слева направо, включая скрытые сейчас клавиши. */
    private val blockRows = ArrayList<List<View>>(LINES)

    /**
     * Клавиши по рядам в том порядке, в каком по ним ходит пульт. Скрытых здесь нет.
     *
     * Списки создаются один раз и дальше только переписываются: [rebuildRows] зовётся
     * на каждой смене регистра, то есть на каждой заглавной букве, а четыре новых
     * списка на нажатие - это мусор ровно там, где пользователь ждёт отклика.
     */
    private val walkRows = ArrayList<ArrayList<View>>(LINES).also {
        for (index in 0 until LINES) it.add(ArrayList(COLUMNS + 3))
    }

    // Создаются не в `init`, а в [build] при первом показе (`B-69`), поэтому `lateinit`.
    private lateinit var layoutKey: TextView
    private lateinit var digitsKey: TextView
    private lateinit var shiftKey: ImageView
    private lateinit var modeKey: ImageView

    /** Кисть для мелкой цифры в углу клавиши. Одна на все клавиши: рисуют они по очереди. */
    private val digitPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = context.getColor(R.color.panel_text)
        alpha = DIGIT_ALPHA
        textAlign = Paint.Align.RIGHT
        textSize = DIGIT_TEXT_SP * resources.displayMetrics.scaledDensity
    }

    /** Границы клавиш в своей системе координат: для подсветки под курсором. */
    private val bounds = HashMap<View, Rect>()

    /** Клавиша, подсвеченная сейчас курсором. */
    private var highlighted: View? = null

    /** Выбранный язык: 0 - латиница, 1 - кириллица. Цифровая раскладка сюда не входит. */
    private var language = 0

    /**
     * Показана цифро-символьная раскладка. Язык при этом не забывается: возврат из цифр
     * ведёт туда же, откуда пришли, - переключать язык заново после каждой цифры
     * пользователь не должен.
     */
    private var digits = false

    private val layoutIndex: Int get() = if (digits) DIGITS_LAYOUT else language

    private var shift = false
    private var row = 0
    private var column = 0

    /** Начало удержания OK в режиме пульта. 0 - клавишу сейчас не держат. */
    private var holdStartedAt = 0L

    /** Порог удержания - тот же, по которому платформа отмеряет долгое нажатие пальцем. */
    private val holdThreshold = ViewConfiguration.getLongPressTimeout().toLong()

    init {
        orientation = HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        // Клавиатура перекрывает страницу собой: нажатие, не попавшее в клавишу,
        // не должно проваливаться в то, что под ней.
        isClickable = true
        isFocusable = false
    }

    /**
     * Клавиши построены. До первого показа их нет вовсе (`B-69`).
     *
     * Разметка надувается целиком в `onCreate`, то есть до первого кадра, а клавиатура
     * в ней - полсотни View: 36 клавиш-букв со своей кистью, две боковые колонки, четыре
     * ряда и карта границ. При этом она `gone` и до первого показа может пройти вся
     * сессия: на телевизоре ввод нужен далеко не всегда.
     */
    private var built = false

    /**
     * Строит клавиши при первом показе.
     *
     * Переопределена именно видимость, а не заведён публичный метод: показ идёт из трёх
     * мест `MainActivity`, и метод пришлось бы звать в каждом - забытый вызов дал бы
     * пустую клавиатуру, то есть отказ ввода вместо ошибки сборки.
     */
    override fun setVisibility(visibility: Int) {
        if (visibility == VISIBLE && !built) build()
        super.setVisibility(visibility)
    }

    private fun build() {
        built = true
        // Ширина 0 у всех клавиш, которые попадут в [share]: долю им назначает ряд,
        // и заданное здесь число точек он всё равно затирает (`B-56`). Настоящая
        // ширина нужна только клавишам боковых колонок - там доли нет.
        shiftKey = iconKey(R.drawable.ic_key_shift, R.string.key_shift, 0)
        shiftKey.setOnClickListener {
            shift = !shift
            applyLayout()
        }
        // Язык и цифры разведены по разным клавишам: в кольце из трёх раскладок путь
        // от латиницы к кириллице проходил через цифры, то есть смена языка стоила
        // двух нажатий из трёх положений кольца.
        layoutKey = textKey("", 0)
        layoutKey.setOnClickListener {
            language = (language + 1) % LANGUAGES
            digits = false
            applyLayout()
        }
        digitsKey = textKey(context.getString(R.string.key_digits), 0)
        digitsKey.setOnClickListener {
            digits = !digits
            applyLayout()
        }
        modeKey = iconKey(R.drawable.ic_key_mode_cursor, R.string.key_mode, 0)
        modeKey.setOnClickListener { setDpadMode(!dpadMode) }

        // В каждой боковой колонке ровно LINES клавиш: обход пультом берёт из них
        // по одной на ряд по номеру ряда. Клавиша лишняя или недостающая - падение
        // при первой же смене раскладки.
        // Слева работа с текстом целиком и в том же порядке, в каком её делают:
        // выделить, вырезать или скопировать, вставить.
        addView(sideColumn(target = leftColumn, keys = listOf(
            actionKey(R.drawable.ic_key_select_all, R.string.key_select_all, KeyAction.SELECT_ALL),
            actionKey(R.drawable.ic_key_cut, R.string.key_cut, KeyAction.CUT),
            actionKey(R.drawable.ic_key_copy, R.string.key_copy, KeyAction.COPY),
            actionKey(R.drawable.ic_key_paste, R.string.key_paste, KeyAction.PASTE),
        )))
        addView(letterBlock())
        addView(sideColumn(target = rightColumn, keys = listOf(
            actionKey(R.drawable.ic_key_backspace, R.string.key_backspace, KeyAction.BACKSPACE),
            actionKey(R.drawable.ic_key_enter, R.string.key_enter, KeyAction.ENTER),
            actionKey(R.drawable.ic_key_clear, R.string.key_clear, KeyAction.CLEAR),
            actionKey(R.drawable.ic_key_close, R.string.key_close, KeyAction.CLOSE),
        )))
        applyLayout()
        // Прямоугольник границ живёт столько же, сколько сама клавиша: в onLayout его
        // только переписывают. Создавать их там нельзя - проход разметки случается
        // на каждой смене регистра, а аллокация в нём это пауза сборщика мусора.
        // Отсюда инвариант: клавиша, не попавшая в эту карту, подсветку не получит.
        for (index in 0 until LINES) {
            bounds[leftColumn[index]] = Rect()
            bounds[rightColumn[index]] = Rect()
            val block = blockRows[index]
            for (position in block.indices) bounds[block[position]] = Rect()
        }
    }

    private fun sideColumn(target: MutableList<View>, keys: List<View>): LinearLayout {
        val column = LinearLayout(context)
        column.orientation = VERTICAL
        keys.forEach { key ->
            column.addView(key)
            target.add(key)
        }
        return column
    }

    /**
     * Сама клавиатура: три ряда букв и нижний ряд. Занимает всё, что осталось от боковых
     * колонок, и делит эту ширину между клавишами долями.
     *
     * Доли, а не постоянная ширина клавиши: ряды разной длины, и с постоянной шириной
     * короткий ряд оставлял бы пустые поля по краям - первое, что видно на клавиатуре
     * телевизора с трёх метров. Долей в каждом ряду ровно [COLUMNS], поэтому колонки
     * всех рядов совпадают, а клавиша регистра занимает две.
     */
    private fun letterBlock(): LinearLayout {
        val block = LinearLayout(context)
        block.orientation = VERTICAL
        block.layoutParams = LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f)

        for (index in 0 until ROWS) block.addView(letterRow(index))
        block.addView(bottomRow())
        return block
    }

    private fun letterRow(index: Int): LinearLayout {
        val line = LinearLayout(context)
        line.orientation = HORIZONTAL
        line.isBaselineAligned = false

        val keys = ArrayList<View>(COLUMNS + 1)
        // Регистр стоит там же, где на настоящей клавиатуре, - слева в третьем ряду,
        // вплотную к буквам. Две доли: ряд из десяти символов вместе с ним даёт те же
        // двенадцать колонок, что и ряды выше.
        if (index == SHIFT_ROW) {
            line.addView(share(shiftKey, SHIFT_SHARE))
            keys.add(shiftKey)
        }
        // Состав ряда запоминается здесь и только здесь: обход пультом берёт его
        // отсюда же, иначе перекладка клавиш требовала бы двух синхронных правок.
        // Позиции создаются один раз на максимальную длину ряда, а раскладка меняет
        // только надписи (ADR-021): расположение у всех трёх одинаковое, и держать
        // ради них три набора View незачем.
        for (position in 0 until COLUMNS) {
            val key = textKey("", 0)
            share(key, 1f)
            // Слушатель ставится один раз на всю жизнь клавиши и берёт символ
            // с неё же: иначе каждая смена регистра создавала бы тридцать шесть
            // новых замыканий, а меняются в ней только надписи.
            key.setOnClickListener { if (key.text.isNotEmpty()) type(key.text[0]) }
            // Удержание вместо буквы даёт цифру, как на телефоне (ADR-034). Долгое
            // нажатие отмеряет платформа: курсор над клавиатурой шлёт настоящую пару
            // DOWN и UP, а не мгновенный тап. Возврат true отменяет обычное нажатие -
            // иначе за одно удержание в поле ушло бы и буква, и цифра.
            key.setOnLongClickListener {
                val digit = key.digit
                if (digit == NO_DIGIT) false else { type(digit); true }
            }
            letters.add(key)
            keys.add(key)
            line.addView(key)
        }
        blockRows.add(keys)
        return line
    }

    /**
     * Нижний ряд: язык, цифры, способ управления, каретка, пробел и два окончания адреса.
     *
     * `.com` и `.ru` стоят здесь не для красоты: на телевизоре адрес набирают по букве,
     * и четыре нажатия вместо одного - это четыре прицеливания курсором.
     */
    private fun bottomRow(): LinearLayout {
        val line = LinearLayout(context)
        line.orientation = HORIZONTAL
        // Иначе ряд выравнивает клавиши по базовой линии текста, и клавиши с более мелким
        // шрифтом - `.com` и `.ru` - съезжают вниз относительно соседей. Заметно сразу.
        line.isBaselineAligned = false

        val space = iconKey(R.drawable.ic_key_space, R.string.key_space, 0)
        space.setOnClickListener { type(' ') }

        // Доли того же ряда из двенадцати: служебные клавиши шире буквы, стрелки каретки
        // равны букве, а всё, что осталось, забирает пробел - пустых мест в ряду нет.
        val keys = listOf(
            share(layoutKey, WIDE_SHARE),
            share(digitsKey, WIDE_SHARE),
            share(modeKey, WIDE_SHARE),
            share(actionKey(R.drawable.ic_key_left, R.string.key_left, KeyAction.LEFT, 0), 1f),
            share(actionKey(R.drawable.ic_key_right, R.string.key_right, KeyAction.RIGHT, 0), 1f),
            share(space, SPACE_SHARE),
            share(phraseKey(R.string.key_dot_com), WIDE_SHARE),
            share(phraseKey(R.string.key_dot_ru), WIDE_SHARE),
        )
        keys.forEach { line.addView(it) }
        blockRows.add(keys)
        return line
    }

    /** Клавиша занимает долю ряда, а не заданное число точек. См. [letterBlock]. */
    private fun share(key: View, share: Float): View {
        val params = key.layoutParams as LayoutParams
        params.width = 0
        params.weight = share
        return key
    }

    /** Клавиша, которая печатает не символ, а готовый кусок адреса. Всегда доля ряда. */
    private fun phraseKey(text: Int): TextView {
        val phrase = context.getString(text)
        val key = textKey(phrase, 0)
        key.textSize = PHRASE_TEXT_SP
        key.setOnClickListener { typeAll(phrase) }
        return key
    }

    private fun actionKey(
        icon: Int,
        description: Int,
        action: KeyAction,
        width: Int = sideWidth,
    ): ImageView {
        val key = iconKey(icon, description, width)
        // «Ввод» выделен цветом: на клавиатуре из полусотни одинаковых тёмных клавиш это
        // единственная, которая заканчивает набор, и глазу с трёх метров нужно за что-то
        // зацепиться. Синий приглушённый - яркий на таком поле спорит с подсветкой
        // выбранной клавиши в режиме пульта.
        if (action == KeyAction.ENTER) key.setBackgroundResource(R.drawable.key_background_enter)
        key.setOnClickListener { onAction?.invoke(action) }
        return key
    }

    private fun iconKey(icon: Int, description: Int, width: Int): ImageView {
        val key = ImageView(context)
        key.setImageResource(icon)
        key.scaleType = ImageView.ScaleType.CENTER
        key.contentDescription = context.getString(description)
        key.setBackgroundResource(R.drawable.key_background)
        key.layoutParams = keyParams(width)
        key.isFocusable = false
        return key
    }

    private fun textKey(label: String, width: Int): KeyText {
        val key = KeyText(context, digitPaint)
        key.text = label
        key.gravity = Gravity.CENTER
        key.textSize = KEY_TEXT_SP
        key.setTextColor(context.getColor(R.color.panel_text))
        key.setBackgroundResource(R.drawable.key_background)
        key.layoutParams = keyParams(width)
        key.isFocusable = false
        return key
    }

    private fun keyParams(width: Int) = LayoutParams(width, keyHeight).apply {
        marginStart = margin
        marginEnd = margin
        topMargin = margin
        bottomMargin = margin
    }

    /** Надписи текущей раскладки и регистра. Лишние позиции ряда прячутся. */
    private fun applyLayout() {
        val layout = LAYOUTS[layoutIndex]
        for (line in 0 until ROWS) {
            val chars = layout[line]
            for (position in 0 until COLUMNS) {
                val key = letters[line * COLUMNS + position]
                key.digit = digitOf(line, position)
                if (position >= chars.length) {
                    key.visibility = View.GONE
                    continue
                }
                val char = if (shift) chars[position].uppercaseChar() else chars[position]
                key.visibility = View.VISIBLE
                key.text = char.toString()
            }
        }
        // Клавиша языка показывает тот язык, на который переключит, а не текущий:
        // подпись «АБВ» на латинской раскладке читается как обещание, а не как отчёт.
        layoutKey.text = context.getString(SWITCH_LABELS[(language + 1) % LANGUAGES])
        // Клавиша цифр залипающая, как регистр, и подписана всегда одинаково. Надпись
        // «АБВ» на ней в цифровом режиме стояла бы рядом с «ABC» клавиши языка - две
        // почти одинаковые подписи в соседних клавишах, и обе про возврат к буквам.
        digitsKey.alpha = if (digits) 1f else INACTIVE_ALPHA
        shiftKey.alpha = if (shift) 1f else INACTIVE_ALPHA
        rebuildRows()
    }

    /**
     * Цифра, которая выдаётся удержанием этой клавиши. Стоит она только в верхнем ряду
     * буквенных раскладок: в цифровой цифры и так нажимаются напрямую, а в нижних рядах
     * подписи не на чем разместить - там и так символы.
     */
    private fun digitOf(line: Int, position: Int): Char =
        if (line == 0 && !digits && position < DIGIT_ROW.length) DIGIT_ROW[position] else NO_DIGIT

    /** Порядок обхода пультом: левая колонка, клавиши ряда, правая колонка. */
    private fun rebuildRows() {
        for (index in 0 until LINES) {
            val visible = walkRows[index]
            visible.clear()
            visible.add(leftColumn[index])
            val block = blockRows[index]
            for (position in block.indices) {
                val key = block[position]
                if (key.visibility == View.VISIBLE) visible.add(key)
            }
            visible.add(rightColumn[index])
        }
        column = column.coerceAtMost(walkRows[row].size - 1)
        updateSelection()
    }

    /**
     * Заглавная буква нужна ровно одна - имена и пароли набираются так. Залипающий
     * регистр на пульте означал бы лишний заход на клавишу после каждой заглавной.
     */
    private fun type(char: Char) {
        onChar?.type(char)
        if (shift) {
            shift = false
            applyLayout()
        }
    }

    /** Готовый кусок текста - по символу, тем же путём, что и обычный набор. */
    private fun typeAll(text: String) {
        val target = onChar ?: return
        for (index in text.indices) target.type(text[index])
        if (shift) {
            shift = false
            applyLayout()
        }
    }

    /**
     * Способ управления. Курсором целиться быстрее, клавишами пульта - точнее,
     * и на разных пультах удобно по-разному: у части телевизионных пультов
     * крестовина жёсткая, а разгон курсора требует удержания.
     */
    private fun setDpadMode(enabled: Boolean) {
        dpadMode = enabled
        modeKey.setImageResource(
            if (enabled) R.drawable.ic_key_mode_dpad else R.drawable.ic_key_mode_cursor,
        )
        updateSelection()
        onModeChanged?.onModeChanged(enabled)
    }

    /** Клавиатуру убрали: следующий показ начинается с курсора, как чаще всего и нужно. */
    fun reset() {
        // Гасить клавишу надо здесь: клавиатуру чаще всего закрывают её же клавишей
        // «убрать», то есть курсор в этот момент стоит на клавише. Без снятия
        // выделения она осталась бы подсвеченной до следующего показа, и рядом
        // с ней подсветилась бы вторая - та, на которую курсор приехал.
        highlighted?.isSelected = false
        highlighted = null
        if (dpadMode) setDpadMode(false)
        if (shift) {
            shift = false
            applyLayout()
        }
    }

    /**
     * Ходьба по клавишам клавишами пульта. Возвращает true, если событие наше:
     * иначе курсор увёл бы стрелку по экрану одновременно с выбором клавиши.
     */
    fun onKeyEvent(event: KeyEvent): Boolean {
        if (!dpadMode) return false
        if (event.keyCode !in NAVIGATION_KEYS) return false

        if (event.keyCode == KeyEvent.KEYCODE_DPAD_CENTER || event.keyCode == KeyEvent.KEYCODE_ENTER) {
            return onSelectKey(event)
        }
        if (event.action != KeyEvent.ACTION_DOWN) return true

        when (event.keyCode) {
            KeyEvent.KEYCODE_DPAD_LEFT -> step(-1)
            KeyEvent.KEYCODE_DPAD_RIGHT -> step(1)
            KeyEvent.KEYCODE_DPAD_UP -> stepRow(-1)
            KeyEvent.KEYCODE_DPAD_DOWN -> stepRow(1)
        }
        return true
    }

    /**
     * Нажатие OK в режиме пульта. Курсором удержание отмеряет платформа по настоящей
     * паре DOWN и UP, а сюда нажатие приходит клавишей, и длительность приходится
     * считать самим - порог берётся тот же, платформенный.
     */
    private fun onSelectKey(event: KeyEvent): Boolean {
        val key = walkRows.getOrNull(row)?.getOrNull(column) ?: return true
        val digit = (key as? KeyText)?.digit ?: NO_DIGIT

        // Клавиша без цифры срабатывает по нажатию: так отзывчивее. Автоповтор здесь
        // не отсекается, в отличие от курсора: удержание OK на «стереть» - это быстрое
        // стирание, ровно то, чего ждут от клавиатуры.
        if (digit == NO_DIGIT) {
            if (event.action == KeyEvent.ACTION_DOWN) pressKey(key)
            return true
        }

        // У клавиши с цифрой решение принимается по отпусканию: до него неизвестно,
        // букву набирают или цифру. Автоповтор пульта при этом ничего не значит -
        // удержание одно, и цифра из него выходит ровно одна.
        when (event.action) {
            KeyEvent.ACTION_DOWN -> if (event.repeatCount == 0) holdStartedAt = event.eventTime
            KeyEvent.ACTION_UP -> {
                val held = holdStartedAt != 0L && event.eventTime - holdStartedAt >= holdThreshold
                holdStartedAt = 0L
                // Гейт H-6 спрашивается и здесь: цифра уходит в поле мимо `performClick`,
                // то есть мимо проверки внутри [pressKey].
                if (!held) pressKey(key) else if (inputAllowed?.allowed() != false) type(digit)
            }
        }
        return true
    }

    /**
     * Нажатие клавиши в режиме пульта. Гейт H-6 спрашивается здесь, а не в каждой
     * клавише: в режиме курсора его проверяет `CursorController.click`, а сюда нажатие
     * приходит от пульта напрямую - без этой строки защита обходилась бы переходом
     * в режим пульта.
     *
     * Клавиша режима исключена намеренно. Она ничего не отправляет ни в страницу,
     * ни в поле, а гейт открывается **движением курсора** - в режиме пульта курсор
     * остановлен, и запрет запер бы пользователя в нём без единственного выхода.
     */
    private fun pressKey(key: View) {
        if (key !== modeKey && inputAllowed?.allowed() == false) return
        key.performClick()
    }

    private fun step(delta: Int) {
        column = (column + delta).coerceIn(0, walkRows[row].size - 1)
        updateSelection()
    }

    /**
     * Переход между рядами - по экранной координате, а не по номеру клавиши: ряды
     * разной длины, и «вниз» обязано означать клавишу под текущей, а не ту же по счёту.
     */
    private fun stepRow(delta: Int) {
        val next = (row + delta).coerceIn(0, walkRows.size - 1)
        if (next == row) return
        val target = bounds[walkRows[row][column]]?.centerX() ?: return
        row = next
        // Ближайшая по горизонтали клавиша ищется обычным циклом: `minByOrNull`
        // по `indices` упаковывает каждый номер в объект, а нажатие стрелки - такой же
        // путь с нулевым бюджетом аллокаций, как и кадр движения курсора.
        val line = walkRows[row]
        var best = 0
        var bestDistance = Int.MAX_VALUE
        for (index in line.indices) {
            val center = bounds[line[index]]?.centerX() ?: continue
            val distance = kotlin.math.abs(center - target)
            if (distance < bestDistance) {
                bestDistance = distance
                best = index
            }
        }
        column = best
        updateSelection()
    }

    /**
     * Выделение одной клавиши: в режиме пульта - выбранной стрелками, иначе - той,
     * на которой стоит курсор. Подсветку курсора приходится возвращать здесь же:
     * этот метод вызывается и после смены раскладки или регистра, а курсор за это
     * время никуда не уехал.
     */
    private fun updateSelection() {
        for (lineIndex in walkRows.indices) {
            val line = walkRows[lineIndex]
            for (keyIndex in line.indices) line[keyIndex].isSelected = false
        }
        if (dpadMode) {
            walkRows.getOrNull(row)?.getOrNull(column)?.isSelected = true
        } else {
            highlighted?.isSelected = true
        }
    }

    /**
     * Подсветка клавиши под курсором. Координаты - в системе корня, того же, в котором
     * лежит сама клавиатура.
     *
     * Курсор на телевизоре - стрелка размером с палец на клавише размером с два, и без
     * подсветки промах виден только по результату. Наведение (`hover`) сюда не доходит:
     * курсор шлёт его прямо в страницу, а клавиатура - наши View.
     */
    fun highlightAt(x: Float, y: Float) {
        // В режиме пульта выбор ведут стрелки, и курсор в это время остановлен:
        // перебивать выбранную клавишу последней позицией курсора было бы неверно.
        if (dpadMode) return

        val localX = (x - left).toInt()
        val localY = (y - top).toInt()
        // Курсор чаще всего ползёт внутри той же клавиши, что и на прошлом кадре.
        // Одна проверка вместо полусотни обращений к карте границ. Верно, пока
        // подсвеченная клавиша видима: у скрытой границы в карте остаются старые,
        // а обновляет их onLayout только по walkRows, где скрытых нет.
        val current = highlighted
        if (current != null && bounds[current]?.contains(localX, localY) == true) return

        // Обход по индексу, а не `for (key in line)`: этот метод зовётся на каждом
        // кадре движения, а итератор коллекции - объект, и бюджет аллокаций нулевой.
        var found: View? = null
        for (lineIndex in walkRows.indices) {
            val line = walkRows[lineIndex]
            for (keyIndex in line.indices) {
                val key = line[keyIndex]
                if (bounds[key]?.contains(localX, localY) == true) {
                    found = key
                    break
                }
            }
            if (found != null) break
        }
        if (found === highlighted) return
        highlighted?.isSelected = false
        found?.isSelected = true
        highlighted = found
    }

    /**
     * Границы клавиш в своей системе координат. Считаются один раз на разметку:
     * спрашивать их у платформы на каждом кадре движения курсора значило бы обходить
     * цепочку родителей полсотни раз в кадр.
     */
    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        super.onLayout(changed, l, t, r, b)
        // Прямоугольники переписываются, а не создаются заново: клавиши те же самые,
        // а проход разметки случается на каждой смене регистра и раскладки - скрытая
        // клавиша меняет видимость, и это `requestLayout`.
        for (lineIndex in walkRows.indices) {
            val line = walkRows[lineIndex]
            for (keyIndex in line.indices) {
                val key = line[keyIndex]
                fillBounds(key, bounds[key] ?: continue)
            }
        }
    }

    /**
     * Клавиатуру показали. Нажатие снимается здесь, а не при закрытии: клавиатуру
     * убирают её же клавишей «убрать», то есть в момент закрытия OK ещё удерживают,
     * и отпускание приходит уже в исчезнувшую клавишу - нажатой она бы и осталась.
     */
    override fun onVisibilityChanged(changedView: View, visibility: Int) {
        super.onVisibilityChanged(changedView, visibility)
        if (changedView !== this || visibility != View.VISIBLE) return
        for (lineIndex in walkRows.indices) {
            val line = walkRows[lineIndex]
            for (keyIndex in line.indices) line[keyIndex].isPressed = false
        }
    }

    private fun fillBounds(key: View, into: Rect) {
        var x = 0
        var y = 0
        var view: View = key
        while (view !== this) {
            x += view.left
            y += view.top
            view = view.parent as? View ?: break
        }
        into.set(x, y, x + key.width, y + key.height)
    }

    /**
     * Клавиша с символом и мелкой цифрой в углу - той, которую даёт удержание.
     *
     * Цифра рисуется, а не подставляется в текст: `TextView` умеет только один текст
     * на всю площадь, а цифра обязана стоять в углу и не мешать читать саму букву
     * с трёх метров. Второй `TextView` поверх означал бы вдвое больше View на клавишу -
     * на клавиатуре их и так под полсотни.
     */
    private class KeyText(context: Context, private val digitPaint: Paint) : TextView(context) {

        /** Цифра для удержания. [NO_DIGIT] - удержание на этой клавише ничего не даёт. */
        var digit: Char = NO_DIGIT
            set(value) {
                if (field == value) return
                field = value
                digitChar[0] = value
                invalidate()
            }

        /** Массив на один символ: `drawText` со строкой создавал бы объект на отрисовку. */
        private val digitChar = CharArray(1)

        private val inset = DIGIT_INSET_DP * resources.displayMetrics.density

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            if (digit == NO_DIGIT) return
            canvas.drawText(digitChar, 0, 1, width - inset, inset - digitPaint.ascent(), digitPaint)
        }
    }

    private companion object {
        /** Рядов букв - три, всего рядов на клавиатуре - четыре. */
        const val ROWS = 3
        const val LINES = 4

        /** Регистр стоит в третьем ряду, как на настоящей клавиатуре. */
        const val SHIFT_ROW = 2

        /** Самый длинный ряд из всех раскладок - кириллический. */
        const val COLUMNS = 12

        /**
         * Три раскладки (ADR-021). Кириллица в MVP: без неё русский поиск не набрать.
         *
         * Пустых мест в рядах нет: короткие ряды дополнены символами адреса (`ADR-034`).
         * Точка, слэш, двоеточие и собака достаются без переключения раскладки - из них
         * состоит адрес, а адрес на телевизоре набирают чаще всего. Кириллице достаётся
         * только точка: её ряды и без символов заняты буквами.
         */
        val LAYOUTS = arrayOf(
            arrayOf("qwertyuiop-_", "asdfghjkl./:", "zxcvbnm@?,"),
            arrayOf("йцукенгшщзхъ", "фывапролджэ.", "ячсмитьбюё"),
            arrayOf("1234567890+=", "-_/:;()&@~#$", ".,?!'\"%*[]"),
        )

        /** Раскладка цифр и символов - последняя; остальные две это языки. */
        const val DIGITS_LAYOUT = 2
        const val LANGUAGES = 2

        val SWITCH_LABELS = intArrayOf(R.string.key_latin, R.string.key_cyrillic)

        /**
         * Доли ряда. Регистр занимает две колонки из двенадцати, служебные клавиши -
         * полторы, пробел забирает всё, что осталось от нижнего ряда.
         */
        const val SHIFT_SHARE = 2f
        const val WIDE_SHARE = 1.5f
        const val SPACE_SHARE = 2.5f

        /** Цифры удержанием стоят на верхнем ряду по порядку, как на телефоне. */
        const val DIGIT_ROW = "1234567890"

        /** Клавиша без цифры. Пробелом, а не нулём: он же рисуется как «ничего». */
        const val NO_DIGIT = ' '

        const val KEY_TEXT_SP = 20f

        /** `.com` и `.ru` длиннее буквы, поэтому набраны шрифтом помельче. */
        const val PHRASE_TEXT_SP = 15f

        /** Цифра в углу - подсказка, а не надпись: мельче буквы и приглушена. */
        const val DIGIT_TEXT_SP = 11f
        const val DIGIT_ALPHA = 150
        const val DIGIT_INSET_DP = 5f

        /** Насколько гаснет невключённый регистр - тот же язык, что у кнопок панели. */
        const val INACTIVE_ALPHA = 0.3f

        val NAVIGATION_KEYS = intArrayOf(
            KeyEvent.KEYCODE_DPAD_LEFT,
            KeyEvent.KEYCODE_DPAD_RIGHT,
            KeyEvent.KEYCODE_DPAD_UP,
            KeyEvent.KEYCODE_DPAD_DOWN,
            KeyEvent.KEYCODE_DPAD_CENTER,
            KeyEvent.KEYCODE_ENTER,
        )
    }
}
