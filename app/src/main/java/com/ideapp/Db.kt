package com.ideapp

import android.content.ContentValues
import android.content.Context
import android.database.DatabaseUtils
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import java.io.File

class DbHelper private constructor(context: Context) :
    SQLiteOpenHelper(context, "ideapp.db", null, 2) {

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
        createEntries(db)
        db.execSQL("CREATE INDEX idx_items_category ON items(category_id)")
        db.execSQL("CREATE INDEX idx_categories_parent ON categories(parent_id)")
        db.execSQL("INSERT INTO categories (name) VALUES ('General')")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        if (oldVersion < 2) {
            // Antes cada idea era un único texto, audio o foto; ahora una idea tiene varios elementos.
            createEntries(db)
            db.execSQL(
                """INSERT INTO entries (item_id, type, content, file_path, created_at)
                   SELECT id, type, content, file_path, created_at FROM items
                   WHERE content <> '' OR file_path IS NOT NULL"""
            )
        }
    }

    private fun createEntries(db: SQLiteDatabase) {
        db.execSQL(
            """CREATE TABLE entries (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                item_id INTEGER NOT NULL REFERENCES items(id) ON DELETE CASCADE,
                type TEXT NOT NULL,
                content TEXT NOT NULL DEFAULT '',
                file_path TEXT,
                created_at INTEGER NOT NULL
            )"""
        )
        db.execSQL("CREATE INDEX idx_entries_item ON entries(item_id)")
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

    /**
     * Todas las categorías en forma de árbol, sin límite de niveles: cada una va seguida de sus
     * subcategorías, en orden alfabético. La etiqueta lleva la ruta completa ("A › B › C").
     */
    fun categoryOptions(): List<CategoryOption> {
        val byParent = categories().groupBy { it.parentId }
        val result = mutableListOf<CategoryOption>()
        fun walk(parentId: Long?, path: String, depth: Int) {
            for (cat in byParent[parentId].orEmpty()) {
                val label = if (path.isEmpty()) cat.name else "$path › ${cat.name}"
                result += CategoryOption(cat.id, label, cat.name, parentId, depth)
                walk(cat.id, label, depth + 1)
            }
        }
        walk(null, "", 0)
        return result
    }

    /** Ids de una categoría y de todas las que cuelgan de ella, a cualquier profundidad. */
    private fun withDescendants(ids: Collection<Long>): Set<Long> {
        val byParent = categories().groupBy { it.parentId }
        val result = mutableSetOf<Long>()
        fun walk(id: Long) {
            if (result.add(id)) byParent[id].orEmpty().forEach { walk(it.id) }
        }
        ids.forEach { walk(it) }
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

    // ---------- Ideas ----------

    fun saveItem(item: Item): Long {
        val cv = ContentValues().apply {
            put("name", item.name)
            // type, content y file_path son columnas antiguas, ya no se usan.
            put("type", ItemType.TEXTO.name)
            put("content", "")
            putNull("file_path")
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
     * Busca por nombre de la idea, nombre de categoría o subcategoría y texto de sus elementos.
     * Si se filtra por una categoría principal se incluyen también sus subcategorías.
     */
    fun search(text: String, categoryId: Long?): List<Item> {
        val where = mutableListOf<String>()
        val args = mutableListOf<String>()
        val query = text.trim()
        if (query.isNotEmpty()) {
            // También coincide si el nombre está en la categoría de la idea o en cualquiera de sus ancestras.
            val matching = withDescendants(categories().filter { it.name.contains(query, ignoreCase = true) }.map { it.id })
            val byCategory = if (matching.isEmpty()) "" else "i.category_id IN (${matching.joinToString(",")}) OR "
            where += "(i.name LIKE ? OR $byCategory" +
                "EXISTS (SELECT 1 FROM entries e WHERE e.item_id = i.id AND e.content LIKE ?))"
            repeat(2) { args += "%$query%" }
        }
        if (categoryId != null) {
            where += "i.category_id IN (${withDescendants(listOf(categoryId)).joinToString(",")})"
        }
        val whereSql = if (where.isEmpty()) "" else " WHERE " + where.joinToString(" AND ")
        return readItems("$ITEM_SELECT$whereSql ORDER BY i.updated_at DESC", args.toTypedArray())
    }

    /** Otras ideas a las que se puede mover un elemento. */
    fun otherItems(excludeId: Long): List<Item> = search("", null).filter { it.id != excludeId }

    fun deleteItem(item: Item) {
        entries(item.id).forEach { entry -> entry.filePath?.let { File(it).delete() } }
        db.delete("items", "id = ?", arrayOf(item.id.toString()))
    }

    private fun readItems(sql: String, args: Array<String>): List<Item> {
        val list = mutableListOf<Item>()
        val labels = categoryOptions().associate { it.id to it.label }
        db.rawQuery(sql, args).use { c ->
            while (c.moveToNext()) {
                list += Item(
                    id = c.getLong(0),
                    name = c.getString(1),
                    categoryId = c.getLong(2),
                    createdAt = c.getLong(3),
                    updatedAt = c.getLong(4),
                    categoryLabel = labels[c.getLong(2)] ?: "",
                )
            }
        }
        if (list.isNotEmpty()) {
            val summaries = mutableMapOf<Long, StringBuilder>()
            db.rawQuery("SELECT item_id, type FROM entries ORDER BY id", null).use { c ->
                while (c.moveToNext()) {
                    val type = runCatching { ItemType.valueOf(c.getString(1)) }.getOrDefault(ItemType.TEXTO)
                    val sb = summaries.getOrPut(c.getLong(0)) { StringBuilder() }
                    if (!sb.contains(type.emoji)) sb.append(type.emoji)
                }
            }
            list.forEach { it.summary = summaries[it.id]?.toString() ?: "" }
        }
        return list
    }

    // ---------- Elementos de una idea ----------

    fun entries(itemId: Long): List<Entry> {
        val list = mutableListOf<Entry>()
        db.rawQuery(
            "SELECT id, item_id, type, content, file_path, created_at FROM entries WHERE item_id = ? ORDER BY id",
            arrayOf(itemId.toString()),
        ).use { c ->
            while (c.moveToNext()) {
                list += Entry(
                    id = c.getLong(0),
                    itemId = c.getLong(1),
                    type = runCatching { ItemType.valueOf(c.getString(2)) }.getOrDefault(ItemType.TEXTO),
                    content = c.getString(3) ?: "",
                    filePath = if (c.isNull(4)) null else c.getString(4),
                    createdAt = c.getLong(5),
                )
            }
        }
        return list
    }

    fun saveEntry(entry: Entry): Long {
        val cv = ContentValues().apply {
            put("item_id", entry.itemId)
            put("type", entry.type.name)
            put("content", entry.content)
            if (entry.filePath != null) put("file_path", entry.filePath) else putNull("file_path")
            put("created_at", entry.createdAt)
        }
        if (entry.id == 0L) {
            entry.id = db.insert("entries", null, cv)
        } else {
            db.update("entries", cv, "id = ?", arrayOf(entry.id.toString()))
        }
        return entry.id
    }

    fun deleteEntry(entry: Entry) {
        entry.filePath?.let { File(it).delete() }
        db.delete("entries", "id = ?", arrayOf(entry.id.toString()))
    }

    fun moveEntry(entry: Entry, targetItemId: Long) {
        db.update(
            "entries",
            ContentValues().apply { put("item_id", targetItemId) },
            "id = ?",
            arrayOf(entry.id.toString()),
        )
        entry.itemId = targetItemId
        touch(targetItemId)
    }

    fun touch(itemId: Long) {
        db.update(
            "items",
            ContentValues().apply { put("updated_at", System.currentTimeMillis()) },
            "id = ?",
            arrayOf(itemId.toString()),
        )
    }

    private companion object {
        const val ITEM_SELECT =
            "SELECT i.id, i.name, i.category_id, i.created_at, i.updated_at " +
                "FROM items i JOIN categories c ON c.id = i.category_id"
    }
}
