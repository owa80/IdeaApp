package com.ideapp

import android.app.Activity
import android.widget.Toast
import androidx.appcompat.app.AlertDialog

/** Menú que sale al mantener pulsada una idea en cualquier lista. */
object ItemActions {

    fun show(activity: Activity, repo: Repo, item: Item, onChanged: () -> Unit) {
        AlertDialog.Builder(activity)
            .setTitle(item.name)
            .setItems(arrayOf("Mover a otra categoría", "Eliminar")) { _, which ->
                if (which == 0) move(activity, repo, item, onChanged) else confirmDelete(activity, repo, item, onChanged)
            }
            .show()
    }

    private fun move(activity: Activity, repo: Repo, item: Item, onChanged: () -> Unit) {
        val options = repo.categoryOptions()
        val current = options.indexOfFirst { it.id == item.categoryId }
        AlertDialog.Builder(activity)
            .setTitle("Mover a…")
            .setSingleChoiceItems(options.map { it.label }.toTypedArray(), current) { dialog, which ->
                val target = options[which]
                if (target.id != item.categoryId) {
                    item.categoryId = target.id
                    item.updatedAt = System.currentTimeMillis()
                    repo.saveItem(item)
                    Toast.makeText(activity, "Movida a ${target.label}", Toast.LENGTH_SHORT).show()
                    onChanged()
                }
                dialog.dismiss()
            }
            .setNegativeButton("Cancelar", null)
            .show()
    }

    private fun confirmDelete(activity: Activity, repo: Repo, item: Item, onChanged: () -> Unit) {
        AlertDialog.Builder(activity)
            .setTitle("Eliminar \"${item.name}\"")
            .setMessage("Se borrará para siempre.")
            .setPositiveButton("Eliminar") { _, _ ->
                repo.deleteItem(item)
                Toast.makeText(activity, "Eliminada", Toast.LENGTH_SHORT).show()
                onChanged()
            }
            .setNegativeButton("Cancelar", null)
            .show()
    }
}
