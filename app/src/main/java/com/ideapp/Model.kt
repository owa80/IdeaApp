package com.ideapp

enum class ItemType(val label: String, val emoji: String) {
    TEXTO("Nota de texto", "📝"),
    AUDIO("Audio", "🎤"),
    FOTO("Foto", "📷"),
}

data class Category(val id: Long, val name: String, val parentId: Long?)

/** Una opción de los desplegables: categoría principal o "Categoría › Subcategoría". */
data class CategoryOption(val id: Long, val label: String, val name: String, val parentId: Long?) {
    override fun toString() = label
}

data class Item(
    var id: Long = 0,
    var name: String,
    var type: ItemType,
    var content: String = "",
    var filePath: String? = null,
    var categoryId: Long,
    var createdAt: Long,
    var updatedAt: Long,
    var categoryLabel: String = "",
)
