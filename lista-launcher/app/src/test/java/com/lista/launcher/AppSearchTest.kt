package com.lista.launcher

import android.content.ComponentName
import android.os.Parcel
import android.os.UserHandle
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AppSearchTest {

    private fun app(label: String, custom: String? = null) = AppEntry(
        RawApp(
            key = "pkg.$label/.Main#0",
            component = ComponentName("pkg.$label", ".Main"),
            user = UserHandle(Parcel.obtain()),
            label = label,
            isMainProfile = true,
            isSystem = false,
        ),
        custom ?: label,
    )

    private val apps = listOf(
        "Agenda", "Câmera", "Configurações", "Gmail", "Google Maps", "Maps.me",
        "Mensagens", "Telefone", "WhatsApp", "YouTube", "123 Fotos",
    ).map { app(it) }

    private fun labels(q: String) = AppSearch.filter(apps, q).map { it.label }

    @Test fun ignoraAcentosEMaiusculas() {
        assertEquals(listOf("Câmera"), labels("CAME"))
        assertEquals("Configurações", labels("configuracoes").first())
    }

    @Test fun comecoDoNomeVemAntesDeComecoDePalavra() {
        assertEquals(listOf("Maps.me", "Google Maps"), labels("maps"))
    }

    @Test fun iniciaisEncontramApp() {
        // "Gmail" começa com "gm"; "Google Maps" entra pelas iniciais.
        assertEquals(listOf("Gmail", "Google Maps"), labels("gm"))
    }

    @Test fun ignoraEspacosNaBusca() {
        assertEquals(listOf("WhatsApp"), labels("whats app"))
    }

    @Test fun appRenomeadoTambemAchaPeloNomeOriginal() {
        val renamed = listOf(app("Instagram", custom = "Insta"))
        assertEquals(listOf("Insta"), AppSearch.filter(renamed, "gram").map { it.label })
    }

    @Test fun buscaVaziaDevolveTudo() {
        assertEquals(apps.size, labels("  ").size)
        assertTrue(labels("xyzxyz").isEmpty())
    }

    @Test fun letraDaBarraLateral() {
        assertEquals('A', app("Água").section)
        assertEquals('N', app("ñandu").section)
        assertEquals('#', app("123 Fotos").section)
        assertEquals('#', app("微信").section)
    }
}
