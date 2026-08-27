package io.github.therebecore.istok

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.util.AttributeSet
import android.util.TypedValue
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import android.widget.EditText

/**
 * Поле ввода, которое системе не отдаёт ничего: ни соединения ввода, ни повода показать
 * клавиатуру телевизора. Наши поля - адресная строка и переименование - наполняет наша
 * же экранная клавиатура, напрямую через `Editable` (ADR-023), так что соединение им
 * не нужно вовсе.
 *
 * Почему обычного `EditText` с `showSoftInputOnFocus = false` не хватило: на прошивке
 * Яндекс ТВ клавиатура всё равно вставала поверх нашей (`B-43`, вторая итерация).
 * `showSoftInputOnFocus` - это просьба не показывать окно по фокусу, и её выполняет
 * система; отказ выдать соединение выполнять нечего - **обслуживать нечего**. Тот же
 * приём уже работает в [BrowserWebView.inputSuppressed], и в комментарии к нему записано,
 * почему все остальные способы на телевизоре оказались слабее: флаг окна не действует,
 * если система уже привязана к окну, `hideSoftInputFromWindow` приходит раньше показа,
 * а `TYPE_NULL` заставляет телевизор поднять полноэкранную таблицу символов.
 *
 * Ввод с физической клавиатуры (у части приставок она есть) этим не задет: клавиши
 * приходят событиями, а не через соединение.
 */
internal class NoImeEditText @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : EditText(context, attrs) {

    override fun onCreateInputConnection(outAttrs: EditorInfo): InputConnection? = null

    /**
     * Ещё один путь показа, которым пользуется часть прошивок: система спрашивает View,
     * готова ли она к вводу текста. Наш ответ - нет, и это правда: текст в поле кладёт
     * приложение, а не IME.
     */
    override fun onCheckIsTextEditor(): Boolean = false

    /**
     * Каретка рисуется своя (`B-91`). Платформенная не появляется как раз из-за строки
     * выше: `Editor` считает курсор видимым только когда `TextView.isTextEditable()`,
     * а тот спрашивает `onCheckIsTextEditor()`. Вернуть «да» ради палочки нельзя -
     * это один из двух заслонов от клавиатуры телевизора (`B-43` открыт, эмулятор
     * дефект не воспроизводит), и снимать непроверяемую здесь защиту ради косметики
     * неправильно. Рисование же ничего не обещает системе.
     *
     * Мигания нет намеренно: таймер на неподвижной картинке будит приложение и стоит
     * кадров, а палочка нужна, чтобы видеть место правки, а не чтобы привлекать взгляд.
     */
    private val caret = Paint()
    private val caretWidth =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 2f, resources.displayMetrics)

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        // При выделении место правки показывает сама подсветка.
        val at = selectionEnd
        if (!isFocused || at < 0 || at != selectionStart) return
        val layout = layout ?: return
        val line = layout.getLineForOffset(at)
        // Холст здесь уже сдвинут на прокрутку поля, поэтому координаты берутся прямо
        // из разметки текста - так же, как их берёт сам `TextView`, когда её рисует.
        // Каретка в самом конце длинной строки приходится ровно на правую границу поля
        // и обрезается вместе с ней, а конец строки - как раз то место, где её ищут чаще
        // всего. Поэтому она прижимается к видимой части, а не вылезает за неё.
        val left = (layout.getPrimaryHorizontal(at) + compoundPaddingLeft).coerceIn(
            (scrollX + compoundPaddingLeft).toFloat(),
            (scrollX + width - compoundPaddingRight).toFloat() - caretWidth,
        )
        // Сдвиг по вертикали считается от базовой линии, а не от отступа сверху: у поля
        // адреса текст центрирован по высоте (`gravity`), и на эту поправку отступ ничего
        // не знает - каретка вставала над строкой на добрый десяток пикселей.
        val top = baseline - layout.getLineBaseline(0)
        caret.color = currentTextColor
        canvas.drawRect(
            left,
            (top + layout.getLineTop(line)).toFloat(),
            left + caretWidth,
            (top + layout.getLineBottom(line)).toFloat(),
            caret,
        )
    }

    /**
     * Каретка уехала за край видимой части поля - подтянуть текст к ней. Само поле
     * этого не делает: прокрутку к месту правки платформа держит в `Editor`, а он у нас
     * молчит по той же причине, что и каретка. Без этой строки правка длинного адреса
     * с пульта идёт вслепую.
     */
    override fun onSelectionChanged(selStart: Int, selEnd: Int) {
        super.onSelectionChanged(selStart, selEnd)
        if (layout != null) bringPointIntoView(selEnd)
        invalidate()
    }
}
