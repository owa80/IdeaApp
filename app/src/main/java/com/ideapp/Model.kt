package com.ideapp

enum class ItemType(val label: String, val emoji: String) {
    TEXTO("Texto", "📝"),
    AUDIO("Audio", "🎤"),
    FOTO("Foto", "📷"),
    VIDEO("Vídeo", "🎬"),
}

data class Category(val id: Long, val name: String, val parentId: Long?)

/** Una opción de los desplegables: la ruta completa, por ejemplo "Categoría › Subcategoría › Otra". */
data class CategoryOption(val id: Long, val label: String, val name: String, val parentId: Long?, val depth: Int = 0) {
    override fun toString() = label
}

/** Una idea: tiene nombre, una única categoría y varios elementos ([Entry]). */
data class Item(
    var id: Long = 0,
    var name: String,
    var categoryId: Long,
    var createdAt: Long,
    var updatedAt: Long,
    var categoryLabel: String = "",
    var summary: String = "",
)

/** Un elemento dentro de una idea: texto, audio, foto o vídeo. */
data class Entry(
    var id: Long = 0,
    var itemId: Long = 0,
    var type: ItemType,
    var content: String = "",
    var filePath: String? = null,
    var createdAt: Long,
)
