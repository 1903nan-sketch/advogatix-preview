package com.lista.launcher

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.Drawable

/**
 * Ícones pequenos dos apps. São desenhados uma vez em segundo plano (em bitmaps de 32dp)
 * e guardados em memória; o acesso ao cache é sempre na thread principal.
 */
class IconCache(private val context: Context) {

    enum class Style { COLOR, MONO, NONE }

    private val bitmaps = HashMap<String, Bitmap>()
    private val renderPx = (RENDER_DP * context.resources.displayMetrics.density).toInt()
    private val grayscale = ColorMatrixColorFilter(ColorMatrix().apply { setSaturation(0f) })

    fun missing(apps: List<RawApp>) = apps.filter { it.key !in bitmaps }

    fun putAll(map: Map<String, Bitmap>) = bitmaps.putAll(map)

    /** Esquece os ícones de um pacote (app atualizado ou removido). */
    fun drop(packageName: String) {
        bitmaps.keys.removeAll { it.startsWith("$packageName/") }
    }

    fun clear() = bitmaps.clear()

    /** Roda em segundo plano. */
    fun render(app: RawApp): Bitmap? = try {
        val icon = MainActivity.testIcons?.invoke(app) ?: app.info?.getBadgedIcon(0)
        icon?.let { d ->
            val bmp = Bitmap.createBitmap(renderPx, renderPx, Bitmap.Config.ARGB_8888)
            d.setBounds(0, 0, renderPx, renderPx)
            d.draw(Canvas(bmp))
            bmp
        }
    } catch (_: Exception) {
        null
    }

    /**
     * Ícone pronto para colocar ao lado do nome. Enquanto não carregou, devolve um espaço
     * vazio do mesmo tamanho, para o texto não "pular" quando o ícone chegar.
     */
    fun drawable(key: String, sizePx: Int, style: Style): Drawable? {
        if (style == Style.NONE) return null
        val bmp = bitmaps[key]
        val d: Drawable = if (bmp == null) {
            ColorDrawable(Color.TRANSPARENT)
        } else {
            BitmapDrawable(context.resources, bmp).apply {
                if (style == Style.MONO) {
                    colorFilter = grayscale
                    alpha = 200
                }
            }
        }
        d.setBounds(0, 0, sizePx, sizePx)
        return d
    }

    private companion object {
        const val RENDER_DP = 32
    }
}
