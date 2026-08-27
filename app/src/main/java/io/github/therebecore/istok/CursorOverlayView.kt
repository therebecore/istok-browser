package io.github.therebecore.istok

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.util.AttributeSet
import android.view.View

/**
 * Экранный курсор (ADR-005).
 *
 * View размером с сам курсор, а не во весь экран. Движение выполняется сдвигом
 * (`translationX`/`translationY`), поэтому содержимое перерисовывается ровно один раз -
 * дальше система только переносит готовый DisplayList. ADR-005 требует "инвалидацию
 * только области курсора"; сдвиг маленькой View - тот же результат более дешёвым способом:
 * `onDraw` при движении не вызывается вообще.
 *
 * Остриё стрелки лежит не в самом углу View, а в [hotspotOffset] от него: половина
 * обводки уходит наружу контура, и на неё View расширена с каждой стороны. Поэтому
 * сдвиг равен координатам курсора минус эта поправка - её вносит вызывающий код.
 */
internal class CursorOverlayView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : View(context, attrs) {

    private val density = context.resources.displayMetrics.density

    /** Толщина обводки. Половина уходит наружу контура, на неё расширяется View. */
    private val strokeWidth = OUTLINE_DP * density

    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = Color.WHITE
    }

    /**
     * Чёрный контур - единственное, что делает белую стрелку видимой на светлой странице.
     * Тень (`setShadowLayer`) дала бы тот же эффект мягче, но она не поддерживается
     * аппаратным слоем на всех версиях и дороже при отрисовке.
     */
    private val outline = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = Color.BLACK
        strokeJoin = Paint.Join.ROUND
        strokeWidth = this@CursorOverlayView.strokeWidth
    }

    /** Множитель из единиц контура в пиксели экрана. */
    private val scale = HEIGHT_DP * density / SHAPE_HEIGHT

    /** Строится один раз: форма не зависит ни от чего, что меняется во время работы. */
    private val shape = Path().apply {
        val offset = strokeWidth / 2f
        moveTo(offset, offset)
        for (i in POINTS.indices step 2) {
            lineTo(POINTS[i] * scale + offset, POINTS[i + 1] * scale + offset)
        }
        close()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        setMeasuredDimension(
            (SHAPE_WIDTH * scale + strokeWidth).toInt(),
            (SHAPE_HEIGHT * scale + strokeWidth).toInt(),
        )
    }

    /**
     * Режим прокрутки (ADR-046). Курсор в нём заливается акцентным цветом: пользователь
     * должен видеть, что стрелки сейчас листают страницу, а не двигают курсор, - иначе
     * замерший курсор читается как зависший браузер.
     *
     * Берётся светлый тон синего, а не заливочный: курсор лежит поверх чужой страницы
     * любого цвета, и тёмно-синяя стрелка на тёмном сайте пропадает вместе с режимом.
     */
    fun setLocked(locked: Boolean) {
        val next = if (locked) context.getColor(R.color.accent_line) else Color.WHITE
        if (fill.color == next) return
        fill.color = next
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        canvas.drawPath(shape, outline)
        canvas.drawPath(shape, fill)
    }

    /** Смещение остриё-курсора относительно левого верхнего угла View. */
    val hotspotOffset: Float get() = strokeWidth / 2f

    private companion object {
        /** Высота стрелки на экране. 28dp - примерно 56px на телевизоре 1080p. */
        const val HEIGHT_DP = 28f
        const val OUTLINE_DP = 1.5f

        /** Контур стрелки без острия, в собственных единицах. Остриё - в (0, 0). */
        val POINTS = floatArrayOf(
            0f, 17.1f,
            4.1f, 13.2f,
            6.6f, 18.9f,
            9.2f, 17.8f,
            6.7f, 12.2f,
            11.6f, 12.2f,
        )
        /**
         * Габариты контура в его собственных единицах: самая правая и самая нижняя
         * точки [POINTS] - то есть `11.6f` из последней пары и `18.9f` из третьей.
         * Числа продублированы намеренно: вычисление их по массиву стоит лишнего кода
         * в APK ради двух значений, которые меняются только вместе с самой формой.
         * Меняешь [POINTS] - проверь эти две строки.
         */
        const val SHAPE_WIDTH = 11.6f
        const val SHAPE_HEIGHT = 18.9f
    }
}
