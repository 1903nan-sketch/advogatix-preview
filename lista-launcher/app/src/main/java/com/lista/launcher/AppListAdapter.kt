package com.lista.launcher

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.TextView

class AppListAdapter(private val inflater: LayoutInflater) : BaseAdapter() {
    private var items: List<AppEntry> = emptyList()
    private var showSections = true
    private var markFirst = false

    /** Enquanto o dedo está na barra de letras, os apps das outras letras ficam apagados. */
    var activeSection: Char? = null
        set(value) {
            if (field != value) {
                field = value
                notifyDataSetChanged()
            }
        }

    /** [sections] mostra a letra no primeiro app de cada grupo; [markFirst] destaca o resultado que o Enter abre. */
    fun submit(list: List<AppEntry>, sections: Boolean, markFirst: Boolean) {
        items = list
        showSections = sections
        this.markFirst = markFirst
        notifyDataSetChanged()
    }

    override fun getCount() = items.size
    override fun getItem(position: Int): AppEntry = items[position]
    override fun getItemId(position: Int) = position.toLong()

    private class Holder(val letter: TextView, val name: TextView)

    override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
        val row = convertView ?: inflater.inflate(R.layout.item_app, parent, false).also {
            it.tag = Holder(it.findViewById(R.id.letter), it.findViewById(R.id.name))
        }
        val holder = row.tag as Holder
        val app = items[position]
        holder.name.text = app.label
        holder.letter.text = when {
            markFirst && position == 0 -> "→"
            showSections && (position == 0 || items[position - 1].section != app.section) -> app.section.toString()
            else -> ""
        }
        val active = activeSection
        row.alpha = if (active == null || app.section == active) 1f else 0.22f
        return row
    }
}
