package com.lista.launcher

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Typeface
import android.util.AttributeSet
import android.util.TypedValue
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import kotlin.math.min

/** Barra de letras à direita da lista: arraste o dedo para pular direto para uma letra. */
class AlphabetScrubber(context: Context, attrs: AttributeSet?) : View(context, attrs) {

    var onLetter: ((letter: Char, position: Int) -> Unit)? = null
    var onRelease: (() -> Unit)? = null

    private var letters: List<Char> = emptyList()
    private var positions: List<Int> = emptyList()
    private var activeIndex = -1
    private var startY = 0f
    private var step = 0f

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        textSize = sp(11f)
        color = 0x80FFFFFF.toInt()
        typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
    }
    private val activePaint = Paint(paint).apply {
        color = 0xFFFFFFFF.toInt()
        textSize = sp(14f)
        typeface = Typeface.create("sans-serif", Typeface.BOLD)
    }

    fun setSections(sections: List<Pair<Char, Int>>) {
        letters = sections.map { it.first }
        positions = sections.map { it.second }
        activeIndex = -1
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        if (letters.isEmpty()) return
        val available = (height - paddingTop - paddingBottom).toFloat()
        step = min(available / letters.size, sp(22f))
        startY = paddingTop + (available - step * letters.size) / 2f
        val cx = width / 2f
        for (i in letters.indices) {
            val p = if (i == activeIndex) activePaint else paint
            val y = startY + step * i + step / 2f - (p.descent() + p.ascent()) / 2f
            canvas.drawText(letters[i].toString(), cx, y, p)
        }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (letters.isEmpty() || step <= 0f) return false
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_MOVE -> {
                parent?.requestDisallowInterceptTouchEvent(true)
                val idx = ((event.y - startY) / step).toInt().coerceIn(0, letters.size - 1)
                if (idx != activeIndex) {
                    activeIndex = idx
                    performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                    onLetter?.invoke(letters[idx], positions[idx])
                    invalidate()
                }
                return true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                activeIndex = -1
                invalidate()
                onRelease?.invoke()
                return true
            }
        }
        return true
    }

    private fun sp(v: Float) =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, v, resources.displayMetrics)
}
