package com.lista.launcher

import android.content.Context

/** Preferências do launcher: favoritos (em ordem), apps ocultos, nomes personalizados. */
class Prefs(context: Context) {
    private val sp = context.getSharedPreferences("lista", Context.MODE_PRIVATE)
    private val names = context.getSharedPreferences("lista_names", Context.MODE_PRIVATE)

    var favorites: List<String>
        get() = sp.getString(KEY_FAVORITES, "").orEmpty().split('\n').filter { it.isNotEmpty() }
        set(value) = sp.edit().putString(KEY_FAVORITES, value.joinToString("\n")).apply()

    var hidden: Set<String>
        get() = sp.getStringSet(KEY_HIDDEN, null)?.toSet() ?: emptySet()
        set(value) = sp.edit().putStringSet(KEY_HIDDEN, HashSet(value)).apply()

    var autoKeyboard: Boolean
        get() = sp.getBoolean(KEY_AUTO_KEYBOARD, false)
        set(value) = sp.edit().putBoolean(KEY_AUTO_KEYBOARD, value).apply()

    var edgeScroll: Boolean
        get() = sp.getBoolean(KEY_EDGE_SCROLL, true)
        set(value) = sp.edit().putBoolean(KEY_EDGE_SCROLL, value).apply()

    /** Arrastar para baixo na tela inicial abre a busca (true) ou as notificações (false). */
    var swipeDownSearch: Boolean
        get() = sp.getBoolean(KEY_SWIPE_DOWN_SEARCH, true)
        set(value) = sp.edit().putBoolean(KEY_SWIPE_DOWN_SEARCH, value).apply()

    var initialized: Boolean
        get() = sp.getBoolean(KEY_INITIALIZED, false)
        set(value) = sp.edit().putBoolean(KEY_INITIALIZED, value).apply()

    fun customNames(): Map<String, String> =
        names.all.entries.mapNotNull { (k, v) -> (v as? String)?.let { k to it } }.toMap()

    fun setCustomName(key: String, name: String?) {
        val editor = names.edit()
        if (name.isNullOrBlank()) editor.remove(key) else editor.putString(key, name)
        editor.apply()
    }

    private companion object {
        const val KEY_FAVORITES = "favorites"
        const val KEY_HIDDEN = "hidden"
        const val KEY_AUTO_KEYBOARD = "auto_keyboard"
        const val KEY_INITIALIZED = "initialized"
        const val KEY_EDGE_SCROLL = "edge_scroll"
        const val KEY_SWIPE_DOWN_SEARCH = "swipe_down_search"
    }
}
