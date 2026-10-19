package com.ideapp

import android.os.Bundle
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.ListView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity

class CategoriesActivity : AppCompatActivity() {
    private lateinit var repo: Repo
    private lateinit var list: ListView
    private var options: List<CategoryOption> = emptyList()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_categories)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        repo = Repo(this)

        list = findViewById(R.id.list_categories)
        findViewById<Button>(R.id.btn_new_category).setOnClickListener {
            CategoryDialogs.create(this, repo, parentId = null, askParent = true) { refresh() }
        }
        list.setOnItemClickListener { _, _, position, _ -> showActions(options[position]) }
    }

    override fun onResume() {
        super.onResume()
        refresh()
    }

    override fun onSupportNavigateUp(): Boolean {
        finish()
        return true
    }

    private fun refresh() {
        options = repo.categoryOptions()
        val rows = options.map { option ->
            val n = repo.itemCount(option.id)
            val countText = if (n == 1) "1 ítem" else "$n ítems"
            if (option.parentId == null) "📁 ${option.name}  ($countText)"
            else "        └ ${option.name}  ($countText)"
        }
        list.adapter = ArrayAdapter(this, android.R.layout.simple_list_item_1, rows)
    }

    private fun showActions(option: CategoryOption) {
        val actions = mutableListOf<Pair<String, () -> Unit>>()
        actions += "Ver sus ítems" to { startActivity(SearchActivity.intent(this, option.id)) }
        if (option.parentId == null) {
            actions += "Añadir subcategoría" to {
                CategoryDialogs.create(this, repo, parentId = option.id, askParent = false) { refresh() }
            }
        }
        actions += "Renombrar" to { CategoryDialogs.rename(this, repo, option) { refresh() } }
        actions += "Eliminar" to { confirmDelete(option) }

        AlertDialog.Builder(this)
            .setTitle(option.label)
            .setItems(actions.map { it.first }.toTypedArray()) { _, which -> actions[which].second() }
            .show()
    }

    private fun confirmDelete(option: CategoryOption) {
        val items = repo.itemCount(option.id)
        val children = repo.childCount(option.id)
        if (items > 0 || children > 0) {
            val reason = buildList {
                if (children > 0) add("tiene $children subcategoría(s)")
                if (items > 0) add("tiene $items ítem(s)")
            }.joinToString(" y ")
            AlertDialog.Builder(this)
                .setTitle("No se puede eliminar")
                .setMessage("\"${option.name}\" $reason. Muévelos o elimínalos antes.")
                .setPositiveButton("Entendido", null)
                .show()
            return
        }
        AlertDialog.Builder(this)
            .setTitle("Eliminar \"${option.name}\"")
            .setMessage("¿Seguro que quieres eliminar esta categoría?")
            .setPositiveButton("Eliminar") { _, _ ->
                repo.deleteCategory(option.id)
                refresh()
            }
            .setNegativeButton("Cancelar", null)
            .show()
    }
}
