package com.ideapp

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.EditText
import android.widget.ListView
import android.widget.Spinner
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity

class SearchActivity : AppCompatActivity() {
    private lateinit var repo: Repo
    private lateinit var query: EditText
    private lateinit var filter: Spinner
    private lateinit var count: TextView
    private val adapter = ItemAdapter()
    private var options: List<CategoryOption> = emptyList()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_search)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        repo = Repo(this)

        query = findViewById(R.id.et_query)
        filter = findViewById(R.id.spinner_filter)
        count = findViewById(R.id.tv_count)
        val list = findViewById<ListView>(R.id.list_results)
        list.adapter = adapter
        list.setOnItemClickListener { _, _, position, _ ->
            startActivity(ItemActivity.editIntent(this, adapter.getItem(position).id))
        }

        loadFilter(intent.getLongExtra(EXTRA_CATEGORY_ID, 0))

        query.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) = runSearch()
        })
        filter.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) = runSearch()
            override fun onNothingSelected(parent: AdapterView<*>?) = runSearch()
        }
    }

    override fun onResume() {
        super.onResume()
        runSearch()
    }

    override fun onSupportNavigateUp(): Boolean {
        finish()
        return true
    }

    private fun loadFilter(selectId: Long) {
        options = listOf(CategoryOption(0, "Todas las categorías", "", null)) + repo.categoryOptions()
        filter.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, options).also {
            it.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        }
        val index = options.indexOfFirst { it.id == selectId }
        if (index >= 0) filter.setSelection(index)
    }

    private fun runSearch() {
        val selected = filter.selectedItem as? CategoryOption
        val categoryId = selected?.id?.takeIf { it != 0L }
        adapter.items = repo.search(query.text.toString(), categoryId)
        count.text = when (adapter.items.size) {
            0 -> "Sin resultados"
            1 -> "1 resultado"
            else -> "${adapter.items.size} resultados"
        }
    }

    companion object {
        private const val EXTRA_CATEGORY_ID = "category_id"

        fun intent(context: Context, categoryId: Long) =
            Intent(context, SearchActivity::class.java).putExtra(EXTRA_CATEGORY_ID, categoryId)
    }
}
