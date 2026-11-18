package com.lista.launcher

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.view.accessibility.AccessibilityEvent

/**
 * Serviço de acessibilidade mínimo: launchers de terceiros não podem abrir a tela de
 * apps recentes diretamente, então este serviço aciona a ação global "Recentes".
 * Não observa outros apps nem lê o conteúdo da tela.
 */
class ActionsService : AccessibilityService() {

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
    }

    override fun onUnbind(intent: Intent?): Boolean {
        instance = null
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        instance = null
        super.onDestroy()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) = Unit

    override fun onInterrupt() = Unit

    companion object {
        @Volatile
        private var instance: ActionsService? = null

        val isRunning: Boolean get() = instance != null

        /** Abre a tela de apps abertos. Devolve false se o serviço não estiver ativo. */
        fun showRecents(): Boolean =
            instance?.performGlobalAction(GLOBAL_ACTION_RECENTS) ?: false
    }
}
