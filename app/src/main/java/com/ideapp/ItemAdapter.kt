package com.ideapp

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.TextView

class ItemAdapter : BaseAdapter() {
    var items: List<Item> = emptyList()
        set(value) {
            field = value
            notifyDataSetChanged()
        }

    override fun getCount() = items.size

    override fun getItem(position: Int): Item = items[position]

    override fun getItemId(position: Int) = items[position].id

    override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
        val view = convertView ?: LayoutInflater.from(parent.context).inflate(R.layout.row_item, parent, false)
        val item = items[position]
        view.findViewById<TextView>(R.id.tv_title).text = "${item.summary.ifEmpty { "💡" }} ${item.name}"
        view.findViewById<TextView>(R.id.tv_subtitle).text =
            "${item.categoryLabel} · ${Media.formatDate(item.updatedAt)}"
        return view
    }
}
