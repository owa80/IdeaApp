package com.ideapp

import android.app.Activity
import android.content.DialogInterface
import android.text.InputType
import android.widget.ArrayAdapter
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog

object CategoryDialogs {

    /**
     * Pide el nombre de una categoría nueva.
     * Si [parentId] es null y [askParent] es true, deja elegir dentro de qué categoría crearla.
     */
    fun create(
        activity: Activity,
        repo: Repo,
        parentId: Long?,
        askParent: Boolean,
        onCreated: (Long) -> Unit,
    ) {
        val pad = (20 * activity.resources.displayMetrics.density).toInt()
        val layout = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad / 2, pad, 0)
        }
        val nameInput = EditText(activity).apply {
            hint = "Nombre"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
            maxLines = 1
        }
        layout.addView(nameInput)

        var parentSpinner: Spinner? = null
        val parents = listOf(CategoryOption(0, "Ninguna (categoría principal)", "", null)) +
            repo.categoryOptions()
        if (askParent && parentId == null) {
            layout.addView(TextView(activity).apply {
                text = "Dentro de:"
                setPadding(0, pad / 2, 0, 0)
            })
            parentSpinner = Spinner(activity).apply {
                adapter = ArrayAdapter(activity, android.R.layout.simple_spinner_item, parents).also {
                    it.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
                }
            }
            layout.addView(parentSpinner)
        }

        val title = if (parentId != null) "Nueva subcategoría" else "Nueva categoría"
        val dialog = AlertDialog.Builder(activity)
            .setTitle(title)
            .setView(layout)
            .setPositiveButton("Crear", null)
            .setNegativeButton("Cancelar", null)
            .create()
        dialog.setOnShowListener {
            dialog.getButton(DialogInterface.BUTTON_POSITIVE).setOnClickListener {
                val name = nameInput.text.toString().trim()
                val chosenParent = parentId
                    ?: (parentSpinner?.selectedItem as? CategoryOption)?.id?.takeIf { it != 0L }
                when {
                    name.isEmpty() -> nameInput.error = "Escribe un nombre"
                    repo.nameExists(name, chosenParent) -> nameInput.error = "Ya existe una con ese nombre"
                    else -> {
                        val id = repo.addCategory(name, chosenParent)
                        dialog.dismiss()
                        Toast.makeText(activity, "Creada: $name", Toast.LENGTH_SHORT).show()
                        onCreated(id)
                    }
                }
            }
        }
        dialog.show()
    }

    fun rename(activity: Activity, repo: Repo, option: CategoryOption, onDone: () -> Unit) {
        val pad = (20 * activity.resources.displayMetrics.density).toInt()
        val nameInput = EditText(activity).apply {
            setText(option.name)
            setSelection(option.name.length)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
            maxLines = 1
        }
        val layout = LinearLayout(activity).apply {
            setPadding(pad, pad / 2, pad, 0)
            addView(nameInput, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        }
        val dialog = AlertDialog.Builder(activity)
            .setTitle("Renombrar")
            .setView(layout)
            .setPositiveButton("Guardar", null)
            .setNegativeButton("Cancelar", null)
            .create()
        dialog.setOnShowListener {
            dialog.getButton(DialogInterface.BUTTON_POSITIVE).setOnClickListener {
                val name = nameInput.text.toString().trim()
                when {
                    name.isEmpty() -> nameInput.error = "Escribe un nombre"
                    repo.nameExists(name, option.parentId, option.id) -> nameInput.error = "Ya existe una con ese nombre"
                    else -> {
                        repo.renameCategory(option.id, name)
                        dialog.dismiss()
                        onDone()
                    }
                }
            }
        }
        dialog.show()
    }
}
