package com.ideapp

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.ListView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity

class MainActivity : AppCompatActivity() {
    private lateinit var repo: Repo
    private val adapter = ItemAdapter()
    private lateinit var list: ListView
    private lateinit var empty: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        repo = Repo(this)

        findViewById<Button>(R.id.btn_new_text).setOnClickListener { openNew(ItemType.TEXTO) }
        findViewById<Button>(R.id.btn_new_audio).setOnClickListener { openNew(ItemType.AUDIO) }
        findViewById<Button>(R.id.btn_new_photo).setOnClickListener { openNew(ItemType.FOTO) }
        findViewById<Button>(R.id.btn_search).setOnClickListener {
            startActivity(Intent(this, SearchActivity::class.java))
        }
        findViewById<Button>(R.id.btn_categories).setOnClickListener {
            startActivity(Intent(this, CategoriesActivity::class.java))
        }

        list = findViewById(R.id.list_recent)
        empty = findViewById(R.id.tv_empty)
        list.adapter = adapter
        list.setOnItemClickListener { _, _, position, _ ->
            startActivity(ItemActivity.editIntent(this, adapter.getItem(position).id))
        }
        list.setOnItemLongClickListener { _, _, position, _ ->
            ItemActions.show(this, repo, adapter.getItem(position)) { refresh() }
            true
        }
    }

    override fun onResume() {
        super.onResume()
        refresh()
    }

    private fun refresh() {
        adapter.items = repo.recent(30)
        val isEmpty = adapter.items.isEmpty()
        list.visibility = if (isEmpty) View.GONE else View.VISIBLE
        empty.visibility = if (isEmpty) View.VISIBLE else View.GONE
    }

    private fun openNew(type: ItemType) {
        startActivity(ItemActivity.newIntent(this, type))
    }
}
