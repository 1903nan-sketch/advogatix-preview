package com.lista.launcher

import android.app.Activity
import android.app.Dialog
import android.graphics.drawable.Drawable
import android.os.Build
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView

/** Painel que sobe da parte de baixo da tela, no lugar das caixas de diálogo padrão. */
class Sheet(private val activity: Activity) {

    class Item(
        val label: CharSequence,
        val detail: CharSequence? = null,
        val group: Int = 0,
        val danger: Boolean = false,
        /** Mantém o painel aberto (ex.: liga/desliga) e permite atualizar o texto à direita. */
        val keepOpen: Boolean = false,
        val icon: Drawable? = null,
        val action: (detail: TextView) -> Unit,
    )

    fun show(title: CharSequence?, items: List<Item>, message: CharSequence? = null): Dialog {
        val dialog = Dialog(activity, R.style.ListaSheet)
        val inflater = LayoutInflater.from(dialog.context)
        val root = inflater.inflate(R.layout.sheet, null)
        setTitle(root, title)
        if (!message.isNullOrBlank()) {
            root.findViewById<TextView>(R.id.sheetMessage).apply {
                text = message
                visibility = View.VISIBLE
            }
        }
        val list = root.findViewById<LinearLayout>(R.id.sheetItems)
        var lastGroup = items.firstOrNull()?.group
        for (item in items) {
            if (item.group != lastGroup) {
                list.addView(divider())
                lastGroup = item.group
            }
            val row = inflater.inflate(R.layout.sheet_item, list, false)
            val label = row.findViewById<TextView>(R.id.label)
            val detail = row.findViewById<TextView>(R.id.detail)
            label.text = item.label
            if (item.icon != null) {
                label.setCompoundDrawablesRelative(item.icon, null, null, null)
                label.compoundDrawablePadding = (16 * activity.resources.displayMetrics.density).toInt()
            }
            if (item.danger) label.setTextColor(activity.getColor(R.color.danger))
            if (item.detail != null) {
                detail.text = item.detail
                detail.visibility = View.VISIBLE
            }
            row.setOnClickListener {
                if (!item.keepOpen) dialog.dismiss()
                item.action(detail)
            }
            list.addView(row)
        }
        present(dialog, root, keyboard = false)
        return dialog
    }

    fun showInput(
        title: CharSequence,
        initial: String,
        primary: CharSequence,
        secondary: CharSequence?,
        onPrimary: (String) -> Unit,
        onSecondary: (() -> Unit)?,
    ): Dialog {
        val dialog = Dialog(activity, R.style.ListaSheet)
        val root = LayoutInflater.from(dialog.context).inflate(R.layout.sheet_input, null)
        setTitle(root, title)
        val input = root.findViewById<EditText>(R.id.input)
        val primaryView = root.findViewById<TextView>(R.id.primary)
        val secondaryView = root.findViewById<TextView>(R.id.secondary)
        input.setText(initial)
        input.setSelection(initial.length)
        primaryView.text = primary
        val submit = {
            dialog.dismiss()
            onPrimary(input.text.toString())
        }
        primaryView.setOnClickListener { submit() }
        input.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_DONE) {
                submit(); true
            } else {
                false
            }
        }
        if (secondary != null && onSecondary != null) {
            secondaryView.text = secondary
            secondaryView.setOnClickListener {
                dialog.dismiss()
                onSecondary()
            }
        } else {
            secondaryView.visibility = View.GONE
        }
        present(dialog, root, keyboard = true)
        input.requestFocus()
        return dialog
    }

    private fun setTitle(root: View, title: CharSequence?) {
        val titleView = root.findViewById<TextView>(R.id.sheetTitle)
        if (title.isNullOrBlank()) titleView.visibility = View.GONE else titleView.text = title
    }

    private fun divider() = View(activity).apply {
        setBackgroundColor(activity.getColor(R.color.divider))
        val dp = resources.displayMetrics.density
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, (1 * dp).toInt().coerceAtLeast(1)).apply {
            setMargins((28 * dp).toInt(), (6 * dp).toInt(), (28 * dp).toInt(), (6 * dp).toInt())
        }
    }

    private fun present(dialog: Dialog, root: View, keyboard: Boolean) {
        dialog.setContentView(root)
        dialog.window?.apply {
            setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            setGravity(Gravity.BOTTOM)
            setSoftInputMode(
                WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE or
                    if (keyboard) WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_VISIBLE else 0,
            )
        }
        // Em telas "de ponta a ponta" (Android 15+), o painel passa por baixo da barra de
        // navegação: completa o espaço embaixo para o conteúdo não ficar escondido.
        val basePadding = root.paddingBottom
        root.setOnApplyWindowInsetsListener { v, insets ->
            @Suppress("DEPRECATION")
            val bottom = if (Build.VERSION.SDK_INT >= 30) {
                insets.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.ime()).bottom
            } else {
                insets.systemWindowInsetBottom
            }
            v.setPadding(v.paddingLeft, v.paddingTop, v.paddingRight, basePadding + bottom)
            insets
        }
        dialog.show()
    }
}
