package com.lista.launcher

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.TextView

class AppListAdapter(private val inflater: LayoutInflater) : BaseAdapter() {
    private var items: List<AppEntry> = emptyList()

    fun submit(list: List<AppEntry>) {
        items = list
        notifyDataSetChanged()
    }

    override fun getCount() = items.size
    override fun getItem(position: Int): AppEntry = items[position]
    override fun getItemId(position: Int) = position.toLong()

    override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
        val view = (convertView ?: inflater.inflate(R.layout.item_app, parent, false)) as TextView
        view.text = items[position].label
        return view
    }
}
