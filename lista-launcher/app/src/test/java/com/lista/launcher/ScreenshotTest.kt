package com.lista.launcher

import android.app.Activity
import android.content.ComponentName
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.Shader
import android.os.Looper
import android.os.Process
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import android.widget.EditText
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowDialog
import java.io.File
import java.io.FileOutputStream
import java.time.Duration

/**
 * Gera PNGs das telas para conferência visual (sem emulador).
 * Rodar: SCREENSHOT_DIR=/algum/dir ./gradlew testReleaseUnitTest -Pscreenshots
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w393dp-h852dp-xhdpi")
class ScreenshotTest {

    private val outDir = File(System.getenv("SCREENSHOT_DIR") ?: "build/screenshots").apply { mkdirs() }

    private val names = listOf(
        "99", "Agenda", "Amazon", "Banco do Brasil", "Bradesco", "Calculadora", "Câmera", "Chrome",
        "Configurações", "Contatos", "Drive", "Duolingo", "Fotos", "Gmail", "Google", "Google Maps",
        "iFood", "Instagram", "Itaú", "Kindle", "LinkedIn", "Mensagens", "Mercado Livre", "Netflix",
        "Notas", "Nubank", "Picpay", "Pinterest", "Play Store", "Relógio", "Shopee", "Spotify",
        "Telefone", "Telegram", "TikTok", "Uber", "Waze", "WhatsApp", "X", "YouTube", "YouTube Music",
    )

    private fun key(name: String) = "pkg.${name.hashCode().toUInt()}/.Main#0"

    private fun fakeApps() = names.map { n ->
        val pkg = if (n == "WhatsApp") "com.whatsapp" else "pkg.${n.hashCode().toUInt()}"
        RawApp(key(n), ComponentName(pkg, ".Main"), Process.myUserHandle(), n, isMainProfile = true, isSystem = false)
    }

    private fun idle(ms: Long = 600) = shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(ms))

    private fun start(favorites: List<String>): Activity {
        val ctx = RuntimeEnvironment.getApplication()
        ctx.getSharedPreferences("lista", Context.MODE_PRIVATE).edit()
            .putString("favorites", favorites.joinToString("\n") { key(it) })
            .putBoolean("initialized", true)
            .commit()
        MainActivity.testApps = fakeApps()
        val activity = Robolectric.buildActivity(MainActivity::class.java).setup().get()
        activity.findViewById<View>(R.id.defaultBanner).visibility = View.GONE
        idle()
        return activity
    }

    @After fun reset() {
        MainActivity.testApps = null
    }

    private fun wallpaper(c: Canvas, w: Int, h: Int, light: Boolean) {
        val p = Paint(Paint.ANTI_ALIAS_FLAG)
        if (light) {
            p.shader = LinearGradient(0f, 0f, w.toFloat(), h.toFloat(), Color.rgb(236, 226, 208), Color.rgb(170, 196, 214), Shader.TileMode.CLAMP)
        } else {
            p.shader = LinearGradient(0f, 0f, w * 0.3f, h.toFloat(), Color.rgb(28, 44, 66), Color.rgb(12, 14, 26), Shader.TileMode.CLAMP)
        }
        c.drawRect(0f, 0f, w.toFloat(), h.toFloat(), p)
        val blobs = if (light) {
            listOf(Triple(0.8f, 0.25f, Color.argb(160, 255, 190, 150)), Triple(0.15f, 0.7f, Color.argb(140, 140, 180, 255)))
        } else {
            listOf(Triple(0.85f, 0.3f, Color.argb(150, 70, 110, 170)), Triple(0.1f, 0.75f, Color.argb(120, 150, 80, 120)))
        }
        for ((x, y, color) in blobs) {
            p.shader = RadialGradient(w * x, h * y, w * 0.7f, color, Color.TRANSPARENT, Shader.TileMode.CLAMP)
            c.drawRect(0f, 0f, w.toFloat(), h.toFloat(), p)
        }
    }

    private fun capture(activity: Activity, name: String, light: Boolean = false, withDialog: Boolean = false) {
        idle()
        val decor = activity.window.decorView
        val bmp = Bitmap.createBitmap(decor.width, decor.height, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        // Animações de view só começam no primeiro desenho: desenha, avança o relógio, desenha de novo.
        decor.draw(c)
        idle(900)
        c.drawColor(Color.TRANSPARENT, android.graphics.PorterDuff.Mode.CLEAR)
        wallpaper(c, decor.width, decor.height, light)
        decor.draw(c)
        if (withDialog) {
            val dialogDecor = ShadowDialog.getLatestDialog().window!!.decorView
            c.drawColor(Color.argb(140, 0, 0, 0))
            c.save()
            c.translate(0f, (decor.height - dialogDecor.height).toFloat())
            dialogDecor.draw(c)
            c.restore()
        }
        FileOutputStream(File(outDir, name)).use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    private fun touch(activity: Activity, action: Int, x: Float, y: Float, down: Long) {
        val e = MotionEvent.obtain(down, SystemClock.uptimeMillis(), action, x, y, 0)
        activity.dispatchTouchEvent(e)
        e.recycle()
    }

    private fun call(activity: Activity, method: String, vararg args: Any) {
        val m = MainActivity::class.java.declaredMethods.first { it.name == method && it.parameterCount == args.size }
        m.isAccessible = true
        m.invoke(activity, *args)
    }

    /**
     * A animação de entrada das linhas usa o relógio de quadros da janela, que não anda
     * quando a captura chama draw() manualmente; aqui só pulamos para o estado final.
     */
    private fun finishRowAnimation(activity: Activity) {
        val list = activity.findViewById<android.widget.ListView>(R.id.appList)
        list.layoutAnimation = null
        for (i in 0 until list.childCount) list.getChildAt(i).clearAnimation()
    }

    @Test fun telas() {
        val favorites = listOf("Telefone", "WhatsApp", "Câmera", "Chrome", "Spotify", "Nubank")
        val a = start(favorites)
        capture(a, "1-home.png")
        capture(a, "2-home-claro.png", light = true)

        // Arrastar na borda direita da tela inicial: abre a lista e rola junto com o dedo.
        val decor = a.window.decorView
        val x = decor.width - 12f
        val t0 = SystemClock.uptimeMillis()
        touch(a, MotionEvent.ACTION_DOWN, x, decor.height * 0.40f, t0)
        idle(50)
        for (i in 1..10) {
            touch(a, MotionEvent.ACTION_MOVE, x, decor.height * (0.40f + 0.018f * i), t0)
            idle(16)
        }
        idle(300)
        capture(a, "3-borda-rolando.png")
        touch(a, MotionEvent.ACTION_UP, x, decor.height * 0.58f, t0)
        idle(400)
        capture(a, "4-lista-apos-soltar.png")

        // Lista aberta pelo topo.
        call(a, "closeDrawer", false)
        idle()
        call(a, "openDrawer", false)
        idle(1000)
        finishRowAnimation(a)
        capture(a, "5-lista.png")

        a.findViewById<EditText>(R.id.search).setText("go")
        capture(a, "6-busca.png")
        a.findViewById<EditText>(R.id.search).setText("")

        // Menu de um app (painel inferior).
        val entry = MainActivity::class.java.getDeclaredField("allApps").let {
            it.isAccessible = true
            @Suppress("UNCHECKED_CAST")
            (it.get(a) as List<AppEntry>).first { app -> app.label == "Spotify" }
        }
        call(a, "showAppMenu", entry)
        capture(a, "7-menu-app.png", withDialog = true)
        ShadowDialog.getLatestDialog().dismiss()

        call(a, "closeDrawer", false)
        call(a, "showSettings")
        capture(a, "8-opcoes.png", withDialog = true)
    }
}
