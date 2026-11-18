package com.lista.launcher

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.util.AttributeSet
import android.util.TypedValue
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.animation.DecelerateInterpolator
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min

/**
 * Barra de letras na borda direita. Ao arrastar o dedo, as letras perto dele
 * "abrem" numa onda para a esquerda e a lista rola acompanhando o dedo.
 *
 * A view é larga (para a onda caber), mas só aceita o toque inicial na faixa
 * da direita; toques à esquerda passam para a lista embaixo.
 */
class AlphabetScrubber(context: Context, attrs: AttributeSet?) : View(context, attrs) {

    /** [index] letra sob o dedo, [fraction] posição do dedo dentro dela (0..1), [y] em px nesta view. */
    var onScrub: ((index: Int, fraction: Float, y: Float) -> Unit)? = null
    var onRelease: (() -> Unit)? = null

    var letters: List<Char> = emptyList()
        private set

    private var startY = 0f
    private var step = 0f
    private var touchY = 0f
    private var activeIndex = -1
    private var wave = 0f
    private var waveAnimator: ValueAnimator? = null

    private val railInset = dp(18f)
    private val touchWidth = dp(44f)
    private val maxShift = dp(56f)
    private val maxStep = sp(21f)
    private val baseSize = sp(10.5f)

    private val restColor = context.getColor(R.color.text_tertiary)
    private val nearColor = context.getColor(R.color.text_primary)
    private val accentColor = context.getColor(R.color.accent)
    private val medium: Typeface = context.resources.getFont(R.font.manrope_medium)

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        typeface = medium
    }

    fun setLetters(list: List<Char>) {
        letters = list
        activeIndex = -1
        computeLayout()
        invalidate()
    }

    /** O toque inicial só vale na faixa da direita (onde as letras ficam). */
    fun acceptsDown(x: Float) = x >= width - touchWidth

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        computeLayout()
    }

    private fun computeLayout() {
        val available = (height - paddingTop - paddingBottom).toFloat()
        if (letters.isEmpty() || available <= 0f) {
            step = 0f
            return
        }
        step = min(available / letters.size, maxStep)
        startY = paddingTop + (available - step * letters.size) / 2f
    }

    override fun onDraw(canvas: Canvas) {
        if (letters.isEmpty() || step <= 0f) return
        val railX = width - railInset
        val radius = max(step * 4.5f, dp(90f))
        for (i in letters.indices) {
            val cy = startY + step * (i + 0.5f)
            val d = abs(cy - touchY)
            val g = if (wave > 0f && d < radius) ((cos(PI * d / radius) + 1.0) / 2.0).toFloat() * wave else 0f
            val active = i == activeIndex && wave > 0f
            paint.textSize = baseSize * (1f + 1.15f * g)
            paint.color = if (active) accentColor else blend(restColor, nearColor, g)
            paint.isFakeBoldText = active
            val y = cy - (paint.descent() + paint.ascent()) / 2f
            canvas.drawText(letters[i].toString(), railX - maxShift * g, y, paint)
        }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean = handleTouch(event, force = false)

    /** [force] = toque vindo da borda da tela inicial (aceita qualquer x). */
    fun handleTouch(event: MotionEvent, force: Boolean): Boolean {
        if (letters.isEmpty() || step <= 0f) return false
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                if (!force && !acceptsDown(event.x)) return false
                parent?.requestDisallowInterceptTouchEvent(true)
                activeIndex = -1
                animateWave(1f)
                update(event.y)
            }
            MotionEvent.ACTION_MOVE -> update(event.y)
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                activeIndex = -1
                animateWave(0f)
                onRelease?.invoke()
            }
        }
        return true
    }

    private fun update(y: Float) {
        touchY = y
        val rel = (y - startY) / step
        val idx = floor(rel).toInt().coerceIn(0, letters.size - 1)
        val fraction = (rel - idx).coerceIn(0f, 1f)
        if (idx != activeIndex) {
            activeIndex = idx
            performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
        }
        onScrub?.invoke(idx, fraction, y)
        invalidate()
    }

    private fun animateWave(target: Float) {
        waveAnimator?.cancel()
        waveAnimator = ValueAnimator.ofFloat(wave, target).apply {
            duration = if (target > wave) 140L else 240L
            interpolator = DecelerateInterpolator()
            addUpdateListener {
                wave = it.animatedValue as Float
                invalidate()
            }
            start()
        }
    }

    private fun blend(from: Int, to: Int, t: Float): Int {
        fun ch(a: Int, b: Int) = (a + (b - a) * t).toInt()
        return Color.argb(
            ch(Color.alpha(from), Color.alpha(to)),
            ch(Color.red(from), Color.red(to)),
            ch(Color.green(from), Color.green(to)),
            ch(Color.blue(from), Color.blue(to)),
        )
    }

    private fun dp(v: Float) = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, resources.displayMetrics)
    private fun sp(v: Float) = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, v, resources.displayMetrics)
}
