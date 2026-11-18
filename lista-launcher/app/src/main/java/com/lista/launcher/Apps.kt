package com.lista.launcher

import android.content.ComponentName
import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.LauncherApps
import android.os.Process
import android.os.UserHandle
import android.os.UserManager
import java.text.Collator
import java.text.Normalizer
import java.util.Locale

/** App como o sistema informa (rótulo original). */
class RawApp(
    val key: String,
    val component: ComponentName,
    val user: UserHandle,
    val label: String,
    val isMainProfile: Boolean,
    val isSystem: Boolean,
) {
    val packageName: String get() = component.packageName
}

/** App pronto para exibir: rótulo final (talvez renomeado) e dados pré-calculados para a busca. */
class AppEntry(val raw: RawApp, val label: String) {
    val key get() = raw.key
    val packageName get() = raw.packageName

    val norm: String = AppSearch.normalize(label)
    val normOriginal: String = AppSearch.normalize(raw.label)
    val words: List<String> = AppSearch.words(norm)
    val compact: String = words.joinToString("")
    val initials: String = words.joinToString("") { it.take(1) }

    /** Letra usada na barra lateral (A–Z, ou # para números/símbolos/outros alfabetos). */
    val section: Char = norm.firstOrNull { it.isLetterOrDigit() }?.uppercaseChar()
        ?.takeIf { it in 'A'..'Z' } ?: '#'
}

class AppRepository(private val context: Context) {
    private val launcherApps = context.getSystemService(LauncherApps::class.java)!!
    private val userManager = context.getSystemService(UserManager::class.java)!!

    /** Lê todos os apps com ícone na gaveta, de todos os perfis (pessoal e trabalho). */
    fun loadRaw(): List<RawApp> {
        val me = Process.myUserHandle()
        val out = ArrayList<RawApp>()
        for (user in userManager.userProfiles) {
            val serial = userManager.getSerialNumberForUser(user)
            val isMain = user == me
            for (info in launcherApps.getActivityList(null, user)) {
                val cn = info.componentName
                if (cn.packageName == context.packageName) continue
                var label = info.label?.toString()?.trim().orEmpty().ifEmpty { cn.packageName }
                if (!isMain) label = context.packageManager.getUserBadgedLabel(label, user).toString()
                val flags = info.applicationInfo.flags
                out += RawApp(
                    key = "${cn.flattenToString()}#$serial",
                    component = cn,
                    user = user,
                    label = label,
                    isMainProfile = isMain,
                    isSystem = flags and ApplicationInfo.FLAG_SYSTEM != 0,
                )
            }
        }
        return out
    }

    fun buildEntries(raw: List<RawApp>, customNames: Map<String, String>): List<AppEntry> {
        val collator = Collator.getInstance(Locale.getDefault()).apply { strength = Collator.PRIMARY }
        return raw.map { AppEntry(it, customNames[it.key] ?: it.label) }
            .sortedWith { a, b ->
                val c = collator.compare(a.label, b.label)
                if (c != 0) c else a.key.compareTo(b.key)
            }
    }
}

object AppSearch {
    private val MARKS = Regex("\\p{Mn}+")
    private val NON_ALNUM = Regex("[^\\p{L}\\p{N}]+")

    /** Minúsculas e sem acentos: "Câmera" -> "camera". */
    fun normalize(s: String): String =
        MARKS.replace(Normalizer.normalize(s, Normalizer.Form.NFD), "").lowercase(Locale.ROOT)

    fun words(norm: String): List<String> = norm.split(NON_ALNUM).filter { it.isNotEmpty() }

    /**
     * Filtra e ordena por relevância: começa com > início de palavra > contém >
     * contém ignorando espaços > nome original (se renomeado) > iniciais ("gm" -> Google Maps).
     */
    fun filter(apps: List<AppEntry>, query: String): List<AppEntry> {
        val q = normalize(query).trim()
        if (q.isEmpty()) return apps
        val qc = NON_ALNUM.replace(q, "")
        return apps.asSequence()
            .mapIndexedNotNull { i, app -> score(app, q, qc)?.let { Triple(it, i, app) } }
            .sortedWith(compareBy({ it.first }, { it.second }))
            .map { it.third }
            .toList()
    }

    private fun score(a: AppEntry, q: String, qc: String): Int? = when {
        a.norm.startsWith(q) -> 0
        a.words.any { it.startsWith(q) } -> 1
        a.norm.contains(q) -> 2
        qc.isNotEmpty() && a.compact.contains(qc) -> 3
        a.normOriginal != a.norm && a.normOriginal.contains(q) -> 4
        qc.length >= 2 && a.initials.startsWith(qc) -> 5
        else -> null
    }
}
