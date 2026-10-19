package com.ideapp

import android.content.ContentValues
import android.content.Context
import android.database.DatabaseUtils
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import java.io.File

class DbHelper private constructor(context: Context) :
    SQLiteOpenHelper(context, "ideapp.db", null, 1) {

    override fun onConfigure(db: SQLiteDatabase) {
        db.setForeignKeyConstraintsEnabled(true)
    }

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            """CREATE TABLE categories (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                name TEXT NOT NULL,
                parent_id INTEGER REFERENCES categories(id)
            )"""
        )
        db.execSQL(
            """CREATE TABLE items (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                name TEXT NOT NULL,
                type TEXT NOT NULL,
                content TEXT NOT NULL DEFAULT '',
                file_path TEXT,
                category_id INTEGER NOT NULL REFERENCES categories(id),
                created_at INTEGER NOT NULL,
                updated_at INTEGER NOT NULL
            )"""
        )
        db.execSQL("CREATE INDEX idx_items_category ON items(category_id)")
        db.execSQL("CREATE INDEX idx_categories_parent ON categories(parent_id)")
        db.execSQL("INSERT INTO categories (name) VALUES ('General')")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        // Primera versión: no hay migraciones todavía.
    }

    companion object {
        @Volatile
        private var instance: DbHelper? = null

        fun get(context: Context): DbHelper =
            instance ?: synchronized(this) {
                instance ?: DbHelper(context.applicationContext).also { instance = it }
            }
    }
}

class Repo(context: Context) {
    private val db: SQLiteDatabase = DbHelper.get(context).writableDatabase

    // ---------- Categorías ----------

    fun categories(): List<Category> {
        val list = mutableListOf<Category>()
        db.rawQuery("SELECT id, name, parent_id FROM categories ORDER BY name COLLATE NOCASE", null).use { c ->
            while (c.moveToNext()) {
                list += Category(c.getLong(0), c.getString(1), if (c.isNull(2)) null else c.getLong(2))
            }
        }
        return list
    }

    fun topCategories(): List<Category> = categories().filter { it.parentId == null }

    /** Categorías principales seguidas de sus subcategorías, en orden alfabético. */
    fun categoryOptions(): List<CategoryOption> {
        val all = categories()
        val result = mutableListOf<CategoryOption>()
        for (top in all.filter { it.parentId == null }) {
            result += CategoryOption(top.id, top.name, top.name, null)
            for (sub in all.filter { it.parentId == top.id }) {
                result += CategoryOption(sub.id, "${top.name} › ${sub.name}", sub.name, top.id)
            }
        }
        return result
    }

    fun nameExists(name: String, parentId: Long?, excludeId: Long = 0): Boolean {
        val sql: String
        val args: Array<String>
        if (parentId == null) {
            sql = "SELECT COUNT(*) FROM categories WHERE parent_id IS NULL AND name = ? COLLATE NOCASE AND id <> ?"
            args = arrayOf(name, excludeId.toString())
        } else {
            sql = "SELECT COUNT(*) FROM categories WHERE parent_id = ? AND name = ? COLLATE NOCASE AND id <> ?"
            args = arrayOf(parentId.toString(), name, excludeId.toString())
        }
        return DatabaseUtils.longForQuery(db, sql, args) > 0
    }

    fun addCategory(name: String, parentId: Long?): Long {
        val cv = ContentValues().apply {
            put("name", name)
            if (parentId != null) put("parent_id", parentId) else putNull("parent_id")
        }
        return db.insert("categories", null, cv)
    }

    fun renameCategory(id: Long, name: String) {
        db.update("categories", ContentValues().apply { put("name", name) }, "id = ?", arrayOf(id.toString()))
    }

    fun itemCount(categoryId: Long): Int =
        DatabaseUtils.longForQuery(db, "SELECT COUNT(*) FROM items WHERE category_id = ?", arrayOf(categoryId.toString())).toInt()

    fun childCount(categoryId: Long): Int =
        DatabaseUtils.longForQuery(db, "SELECT COUNT(*) FROM categories WHERE parent_id = ?", arrayOf(categoryId.toString())).toInt()

    fun deleteCategory(id: Long) {
        db.delete("categories", "id = ?", arrayOf(id.toString()))
    }

    // ---------- Ítems ----------

    fun saveItem(item: Item): Long {
        val cv = ContentValues().apply {
            put("name", item.name)
            put("type", item.type.name)
            put("content", item.content)
            if (item.filePath != null) put("file_path", item.filePath) else putNull("file_path")
            put("category_id", item.categoryId)
            put("created_at", item.createdAt)
            put("updated_at", item.updatedAt)
        }
        if (item.id == 0L) {
            item.id = db.insert("items", null, cv)
        } else {
            db.update("items", cv, "id = ?", arrayOf(item.id.toString()))
        }
        return item.id
    }

    fun getItem(id: Long): Item? = readItems("$ITEM_SELECT WHERE i.id = ?", arrayOf(id.toString())).firstOrNull()

    fun recent(limit: Int): List<Item> =
        readItems("$ITEM_SELECT ORDER BY i.updated_at DESC LIMIT $limit", emptyArray())

    /**
     * Busca por nombre del ítem, nombre de categoría o subcategoría y texto.
     * Si se filtra por una categoría principal se incluyen también sus subcategorías.
     */
    fun search(text: String, categoryId: Long?): List<Item> {
        val where = mutableListOf<String>()
        val args = mutableListOf<String>()
        val query = text.trim()
        if (query.isNotEmpty()) {
            where += "(i.name LIKE ? OR c.name LIKE ? OR p.name LIKE ? OR i.content LIKE ?)"
            repeat(4) { args += "%$query%" }
        }
        if (categoryId != null) {
            where += "(c.id = ? OR c.parent_id = ?)"
            args += categoryId.toString()
            args += categoryId.toString()
        }
        val whereSql = if (where.isEmpty()) "" else " WHERE " + where.joinToString(" AND ")
        return readItems("$ITEM_SELECT$whereSql ORDER BY i.updated_at DESC", args.toTypedArray())
    }

    fun deleteItem(item: Item) {
        item.filePath?.let { File(it).delete() }
        db.delete("items", "id = ?", arrayOf(item.id.toString()))
    }

    private fun readItems(sql: String, args: Array<String>): List<Item> {
        val list = mutableListOf<Item>()
        db.rawQuery(sql, args).use { c ->
            while (c.moveToNext()) {
                val catName = c.getString(8)
                val parentName = if (c.isNull(9)) null else c.getString(9)
                list += Item(
                    id = c.getLong(0),
                    name = c.getString(1),
                    type = runCatching { ItemType.valueOf(c.getString(2)) }.getOrDefault(ItemType.TEXTO),
                    content = c.getString(3) ?: "",
                    filePath = if (c.isNull(4)) null else c.getString(4),
                    categoryId = c.getLong(5),
                    createdAt = c.getLong(6),
                    updatedAt = c.getLong(7),
                    categoryLabel = if (parentName == null) catName else "$parentName › $catName",
                )
            }
        }
        return list
    }

    private companion object {
        const val ITEM_SELECT =
            "SELECT i.id, i.name, i.type, i.content, i.file_path, i.category_id, i.created_at, i.updated_at, c.name, p.name " +
                "FROM items i JOIN categories c ON c.id = i.category_id " +
                "LEFT JOIN categories p ON p.id = c.parent_id"
    }
}
