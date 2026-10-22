package com.ideapp

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.MediaPlayer
import android.media.MediaRecorder
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.text.InputType
import android.view.LayoutInflater
import android.view.View
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.activity.addCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import java.io.File

/** Pantalla de una idea: nombre, categoría y varios elementos (texto, audio, foto, vídeo). */
class ItemActivity : AppCompatActivity() {
    private lateinit var repo: Repo
    private var item: Item? = null
    private val entries = mutableListOf<Entry>()

    private var originalName = ""
    private var originalCategoryId = 0L
    private var textEdited = false

    /** Fichero que la cámara está escribiendo ahora mismo. */
    private var pendingCapturePath: String? = null
    private var pendingCaptureType: ItemType? = null

    private var recorder: MediaRecorder? = null
    private var recordingPath: String? = null
    private var recordingStart = 0L
    private var audioDialog: AlertDialog? = null
    private var afterMicPermission: (() -> Unit)? = null

    private var player: MediaPlayer? = null
    private var playingEntry: Entry? = null

    private val handler = Handler(Looper.getMainLooper())
    private var options: List<CategoryOption> = emptyList()

    private lateinit var nameLayout: TextInputLayout
    private lateinit var nameInput: TextInputEditText
    private lateinit var categorySpinner: Spinner
    private lateinit var entriesContainer: LinearLayout
    private lateinit var noEntries: TextView

    private val takePicture = registerForActivityResult(ActivityResultContracts.TakePicture()) { ok ->
        finishCapture(ok)
    }

    private val captureVideo = registerForActivityResult(ActivityResultContracts.CaptureVideo()) { ok ->
        finishCapture(ok)
    }

    private val pickImage = registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) copyFromGallery(uri, ItemType.FOTO, "jpg")
    }

    private val pickVideo = registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) copyFromGallery(uri, ItemType.VIDEO, "mp4")
    }

    private val requestMic = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        val next = afterMicPermission
        afterMicPermission = null
        if (granted) next?.invoke() else toast("Sin permiso de micrófono no se puede grabar")
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_item)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        repo = Repo(this)

        nameLayout = findViewById(R.id.til_name)
        nameInput = findViewById(R.id.et_name)
        categorySpinner = findViewById(R.id.spinner_category)
        entriesContainer = findViewById(R.id.entries_container)
        noEntries = findViewById(R.id.tv_no_entries)

        val itemId = intent.getLongExtra(EXTRA_ID, 0)
        val existing = if (itemId > 0) repo.getItem(itemId) else null
        if (itemId > 0 && existing == null) {
            toast("Esta idea ya no existe")
            finish()
            return
        }
        item = existing
        if (existing != null) entries += repo.entries(existing.id)
        originalName = existing?.name ?: ""
        originalCategoryId = existing?.categoryId
            ?: getSharedPreferences(PREFS, MODE_PRIVATE).getLong(PREF_LAST_CATEGORY, 0)

        if (savedInstanceState != null) {
            pendingCapturePath = savedInstanceState.getString(STATE_CAPTURE_PATH)
            pendingCaptureType = savedInstanceState.getString(STATE_CAPTURE_TYPE)
                ?.let { runCatching { ItemType.valueOf(it) }.getOrNull() }
        } else if (existing != null) {
            nameInput.setText(existing.name)
        }

        title = existing?.name ?: "Nueva idea"

        loadCategories(if (savedInstanceState == null) originalCategoryId else -1)
        findViewById<Button>(R.id.btn_add_category).setOnClickListener {
            CategoryDialogs.create(this, repo, parentId = null, askParent = true) { newId -> loadCategories(newId) }
        }

        findViewById<Button>(R.id.btn_add_text).setOnClickListener { startAdd(ItemType.TEXTO) }
        findViewById<Button>(R.id.btn_add_audio).setOnClickListener { startAdd(ItemType.AUDIO) }
        findViewById<Button>(R.id.btn_add_photo).setOnClickListener { startAdd(ItemType.FOTO) }
        findViewById<Button>(R.id.btn_add_video).setOnClickListener { startAdd(ItemType.VIDEO) }

        findViewById<Button>(R.id.btn_save).setOnClickListener { save() }
        val deleteButton = findViewById<Button>(R.id.btn_delete)
        if (existing != null) {
            deleteButton.visibility = View.VISIBLE
            deleteButton.text = "Eliminar idea"
            deleteButton.setOnClickListener { confirmDeleteItem() }
            findViewById<TextView>(R.id.tv_dates).apply {
                visibility = View.VISIBLE
                text = "Creada: ${Media.formatDate(existing.createdAt)}\nModificada: ${Media.formatDate(existing.updatedAt)}"
            }
        }

        onBackPressedDispatcher.addCallback(this) {
            if (hasUnsavedChanges()) {
                AlertDialog.Builder(this@ItemActivity)
                    .setMessage("¿Salir sin guardar los cambios?")
                    .setPositiveButton("Salir") { _, _ -> finish() }
                    .setNegativeButton("Seguir editando", null)
                    .show()
            } else {
                finish()
            }
        }

        renderEntries()

        // Acceso rápido desde la pantalla principal: abre directamente el tipo elegido.
        if (existing == null && savedInstanceState == null) {
            val first = runCatching { ItemType.valueOf(intent.getStringExtra(EXTRA_TYPE) ?: "") }.getOrNull()
            if (first != null) nameInput.post { startAdd(first) }
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putString(STATE_CAPTURE_PATH, pendingCapturePath)
        outState.putString(STATE_CAPTURE_TYPE, pendingCaptureType?.name)
    }

    override fun onSupportNavigateUp(): Boolean {
        onBackPressedDispatcher.onBackPressed()
        return true
    }

    override fun onPause() {
        super.onPause()
        audioDialog?.dismiss()
        stopPlayback()
    }

    override fun onDestroy() {
        super.onDestroy()
        audioDialog?.dismiss()
        recorder?.let {
            runCatching { it.release() }
            recorder = null
        }
        player?.release()
        player = null
        if (isFinishing) {
            // Lo que no se ha guardado se descarta.
            entries.filter { it.id == 0L }.forEach { entry -> entry.filePath?.let { File(it).delete() } }
        }
    }

    // ---------- Categorías ----------

    private fun loadCategories(selectId: Long) {
        val previous = (categorySpinner.selectedItem as? CategoryOption)?.id
        options = repo.categoryOptions()
        categorySpinner.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, options).also {
            it.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        }
        val wanted = if (selectId >= 0) selectId else previous
        val index = options.indexOfFirst { it.id == wanted }
        if (index >= 0) categorySpinner.setSelection(index)
    }

    private fun selectedCategoryId(): Long? = (categorySpinner.selectedItem as? CategoryOption)?.id

    // ---------- Lista de elementos ----------

    private fun renderEntries() {
        entriesContainer.removeAllViews()
        noEntries.visibility = if (entries.isEmpty()) View.VISIBLE else View.GONE
        val inflater = LayoutInflater.from(this)
        for (entry in entries) {
            val row = inflater.inflate(R.layout.row_entry, entriesContainer, false)
            val title = row.findViewById<TextView>(R.id.tv_entry_title)
            val text = row.findViewById<TextView>(R.id.tv_entry_text)
            val thumb = row.findViewById<ImageView>(R.id.iv_entry_thumb)
            val main = row.findViewById<Button>(R.id.btn_entry_main)
            val move = row.findViewById<Button>(R.id.btn_entry_move)
            val delete = row.findViewById<Button>(R.id.btn_entry_delete)

            title.text = "${entry.type.emoji} ${entry.type.label} · ${Media.formatDate(entry.createdAt)}"
            val path = entry.filePath

            when (entry.type) {
                ItemType.TEXTO -> {
                    text.visibility = View.VISIBLE
                    text.text = entry.content
                    main.text = "✏ Editar"
                    main.setOnClickListener { showTextDialog(entry) }
                }
                ItemType.AUDIO -> {
                    val duration = path?.let { Media.formatDuration(Media.audioDuration(it)) } ?: "00:00"
                    main.text = if (playingEntry === entry) "⏹ Parar" else "▶ Escuchar ($duration)"
                    main.setOnClickListener { togglePlayback(entry) }
                }
                ItemType.FOTO -> {
                    val bitmap = path?.let { Media.loadBitmap(it, 800) }
                    if (bitmap != null) {
                        thumb.visibility = View.VISIBLE
                        thumb.setImageBitmap(bitmap)
                        thumb.setOnClickListener { openFile(entry) }
                    }
                    main.text = "🔍 Ver"
                    main.setOnClickListener { openFile(entry) }
                }
                ItemType.VIDEO -> {
                    val bitmap = path?.let { Media.videoThumbnail(it) }
                    if (bitmap != null) {
                        thumb.visibility = View.VISIBLE
                        thumb.setImageBitmap(bitmap)
                        thumb.setOnClickListener { openFile(entry) }
                    }
                    main.text = "▶ Ver vídeo"
                    main.setOnClickListener { openFile(entry) }
                }
            }

            // Mover solo tiene sentido para elementos ya guardados en esta idea.
            if (entry.id > 0) {
                move.setOnClickListener { showMoveDialog(entry) }
            } else {
                move.visibility = View.GONE
            }
            delete.setOnClickListener { confirmDeleteEntry(entry) }
            entriesContainer.addView(row)
        }
    }

    private fun addEntry(type: ItemType, content: String = "", filePath: String? = null) {
        entries += Entry(type = type, content = content, filePath = filePath, createdAt = System.currentTimeMillis())
        renderEntries()
    }

    // ---------- Añadir elementos ----------

    private fun startAdd(type: ItemType) {
        when (type) {
            ItemType.TEXTO -> showTextDialog(null)
            ItemType.AUDIO -> showAudioDialog()
            ItemType.FOTO -> AlertDialog.Builder(this)
                .setTitle("Añadir foto")
                .setItems(arrayOf("📷 Hacer una foto", "🖼 Elegir de la galería")) { _, which ->
                    if (which == 0) capture(ItemType.FOTO) else launchPicker(pickImage, "image/*")
                }
                .show()
            ItemType.VIDEO -> AlertDialog.Builder(this)
                .setTitle("Añadir vídeo")
                .setItems(arrayOf("🎬 Grabar un vídeo", "🖼 Elegir de la galería")) { _, which ->
                    if (which == 0) capture(ItemType.VIDEO) else launchPicker(pickVideo, "video/*")
                }
                .show()
        }
    }

    private fun showTextDialog(entry: Entry?) {
        val pad = (20 * resources.displayMetrics.density).toInt()
        val input = EditText(this).apply {
            hint = "Escribe aquí"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or
                InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
            minLines = 5
            gravity = android.view.Gravity.TOP or android.view.Gravity.START
            setText(entry?.content ?: "")
            setSelection(text.length)
        }
        val layout = LinearLayout(this).apply {
            setPadding(pad, pad / 2, pad, 0)
            addView(input, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        }
        val dialog = AlertDialog.Builder(this)
            .setTitle(if (entry == null) "Nuevo texto" else "Editar texto")
            .setView(layout)
            .setPositiveButton(if (entry == null) "Añadir" else "Guardar", null)
            .setNegativeButton("Cancelar", null)
            .create()
        dialog.setOnShowListener {
            input.requestFocus()
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val value = input.text.toString().trim()
                if (value.isEmpty()) {
                    input.error = "Escribe algo"
                    return@setOnClickListener
                }
                if (entry == null) {
                    addEntry(ItemType.TEXTO, content = value)
                } else {
                    entry.content = value
                    textEdited = true
                    renderEntries()
                }
                dialog.dismiss()
            }
        }
        dialog.show()
    }

    private fun showAudioDialog() {
        val pad = (20 * resources.displayMetrics.density).toInt()
        val status = TextView(this).apply {
            text = "Pulsa Grabar para empezar"
            textSize = 16f
        }
        val recordButton = Button(this).apply {
            text = "🎤 Grabar"
            isAllCaps = false
        }
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad / 2, pad, 0)
            addView(status)
            addView(recordButton)
        }
        var recorded: String? = null
        var accepted = false

        val ticker = object : Runnable {
            override fun run() {
                if (recorder != null) {
                    status.text = "● Grabando… ${Media.formatDuration(SystemClock.elapsedRealtime() - recordingStart)}"
                    handler.postDelayed(this, 500)
                }
            }
        }

        fun beginRecording() {
            recorded?.let { File(it).delete() }
            recorded = null
            val path = startRecorder()
            if (path == null) {
                toast("No se pudo iniciar la grabación")
                return
            }
            recordButton.text = "⏹ Detener"
            handler.post(ticker)
        }

        recordButton.setOnClickListener {
            if (recorder != null) {
                recorded = stopRecorder()
                recordButton.text = "🎤 Volver a grabar"
                status.text = recorded?.let { "Audio de ${Media.formatDuration(Media.audioDuration(it))}" }
                    ?: "La grabación es demasiado corta"
            } else if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) ==
                PackageManager.PERMISSION_GRANTED
            ) {
                beginRecording()
            } else {
                afterMicPermission = { beginRecording() }
                requestMic.launch(Manifest.permission.RECORD_AUDIO)
            }
        }

        val dialog = AlertDialog.Builder(this)
            .setTitle("Nuevo audio")
            .setView(layout)
            .setPositiveButton("Añadir", null)
            .setNegativeButton("Cancelar", null)
            .create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                if (recorder != null) recorded = stopRecorder()
                val path = recorded
                if (path == null) {
                    toast("Graba un audio antes de añadirlo")
                } else {
                    accepted = true
                    addEntry(ItemType.AUDIO, filePath = path)
                    dialog.dismiss()
                }
            }
        }
        dialog.setOnDismissListener {
            handler.removeCallbacks(ticker)
            if (recorder != null) recorded = stopRecorder()
            if (!accepted) recorded?.let { File(it).delete() }
            if (audioDialog === dialog) audioDialog = null
        }
        audioDialog = dialog
        dialog.setCanceledOnTouchOutside(false)
        dialog.show()
    }

    private fun startRecorder(): String? {
        stopPlayback()
        val file = Media.newFile(this, "audio", "m4a")
        val r = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) MediaRecorder(this) else createLegacyRecorder()
        try {
            r.setAudioSource(MediaRecorder.AudioSource.MIC)
            r.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            r.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
            r.setAudioEncodingBitRate(96000)
            r.setAudioSamplingRate(44100)
            r.setOutputFile(file.absolutePath)
            r.prepare()
            r.start()
        } catch (e: Exception) {
            r.release()
            file.delete()
            return null
        }
        recorder = r
        recordingPath = file.absolutePath
        recordingStart = SystemClock.elapsedRealtime()
        return file.absolutePath
    }

    @Suppress("DEPRECATION")
    private fun createLegacyRecorder() = MediaRecorder()

    /** Para la grabación y devuelve el fichero, o null si era demasiado corta. */
    private fun stopRecorder(): String? {
        val r = recorder ?: return null
        recorder = null
        val path = recordingPath
        recordingPath = null
        val ok = try {
            r.stop()
            true
        } catch (e: RuntimeException) {
            false
        }
        r.release()
        if (!ok) {
            path?.let { File(it).delete() }
            return null
        }
        return path
    }

    private fun capture(type: ItemType) {
        val file = Media.newFile(this, if (type == ItemType.FOTO) "foto" else "video", if (type == ItemType.FOTO) "jpg" else "mp4")
        val uri = FileProvider.getUriForFile(this, "$packageName.fileprovider", file)
        pendingCapturePath = file.absolutePath
        pendingCaptureType = type
        try {
            if (type == ItemType.FOTO) takePicture.launch(uri) else captureVideo.launch(uri)
        } catch (e: ActivityNotFoundException) {
            pendingCapturePath = null
            pendingCaptureType = null
            file.delete()
            toast("No hay ninguna app de cámara")
        }
    }

    private fun finishCapture(ok: Boolean) {
        val path = pendingCapturePath ?: return
        val type = pendingCaptureType ?: ItemType.FOTO
        pendingCapturePath = null
        pendingCaptureType = null
        val file = File(path)
        if (ok && file.exists() && file.length() > 0) addEntry(type, filePath = path) else file.delete()
    }

    private fun launchPicker(launcher: androidx.activity.result.ActivityResultLauncher<String>, mime: String) {
        try {
            launcher.launch(mime)
        } catch (e: ActivityNotFoundException) {
            toast("No hay galería disponible")
        }
    }

    private fun copyFromGallery(uri: Uri, type: ItemType, ext: String) {
        val file = Media.newFile(this, if (type == ItemType.FOTO) "foto" else "video", ext)
        try {
            contentResolver.openInputStream(uri)?.use { input ->
                file.outputStream().use { output -> input.copyTo(output) }
            }
            addEntry(type, filePath = file.absolutePath)
        } catch (e: Exception) {
            file.delete()
            toast("No se pudo copiar el archivo")
        }
    }

    // ---------- Acciones sobre un elemento ----------

    private fun togglePlayback(entry: Entry) {
        val wasPlaying = playingEntry === entry
        stopPlayback()
        if (wasPlaying) return
        val path = entry.filePath ?: return
        val p = MediaPlayer()
        try {
            p.setDataSource(path)
            p.prepare()
        } catch (e: Exception) {
            p.release()
            toast("No se pudo reproducir el audio")
            return
        }
        p.setOnCompletionListener { stopPlayback() }
        p.start()
        player = p
        playingEntry = entry
        renderEntries()
    }

    private fun stopPlayback() {
        val had = player != null
        player?.let {
            try {
                it.stop()
            } catch (_: IllegalStateException) {
            }
            it.release()
        }
        player = null
        playingEntry = null
        if (had && ::entriesContainer.isInitialized) renderEntries()
    }

    private fun openFile(entry: Entry) {
        val path = entry.filePath ?: return
        val uri = FileProvider.getUriForFile(this, "$packageName.fileprovider", File(path))
        val mime = if (entry.type == ItemType.VIDEO) "video/*" else "image/*"
        val view = Intent(Intent.ACTION_VIEW)
            .setDataAndType(uri, mime)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        try {
            startActivity(view)
        } catch (e: ActivityNotFoundException) {
            toast("No hay ninguna app para abrirlo")
        }
    }

    private fun showMoveDialog(entry: Entry) {
        val current = item ?: return
        val targets = repo.otherItems(current.id)
        if (targets.isEmpty()) {
            toast("No hay otras ideas a las que mover este elemento")
            return
        }
        val labels = targets.map { "${it.name}  —  ${it.categoryLabel}" }.toTypedArray()
        AlertDialog.Builder(this)
            .setTitle("Mover a otra idea")
            .setItems(labels) { _, which ->
                val target = targets[which]
                if (playingEntry === entry) stopPlayback()
                repo.moveEntry(entry, target.id)
                repo.touch(current.id)
                entries.remove(entry)
                renderEntries()
                toast("Movido a «${target.name}»")
            }
            .setNegativeButton("Cancelar", null)
            .show()
    }

    private fun confirmDeleteEntry(entry: Entry) {
        AlertDialog.Builder(this)
            .setTitle("Borrar ${entry.type.label.lowercase()}")
            .setMessage("Se borrará para siempre.")
            .setPositiveButton("Borrar") { _, _ ->
                if (playingEntry === entry) stopPlayback()
                if (entry.id > 0) {
                    repo.deleteEntry(entry)
                    item?.let { repo.touch(it.id) }
                } else {
                    entry.filePath?.let { File(it).delete() }
                }
                entries.remove(entry)
                renderEntries()
            }
            .setNegativeButton("Cancelar", null)
            .show()
    }

    // ---------- Guardar y eliminar la idea ----------

    private fun hasUnsavedChanges(): Boolean =
        entries.any { it.id == 0L } || textEdited ||
            nameInput.text.toString() != originalName ||
            (item != null && selectedCategoryId() != originalCategoryId)

    private fun save() {
        val name = nameInput.text.toString().trim()
        if (name.isEmpty()) {
            nameLayout.error = "Ponle un nombre"
            nameInput.requestFocus()
            return
        }
        nameLayout.error = null
        val categoryId = selectedCategoryId()
        if (categoryId == null) {
            toast("Crea primero una categoría con «+ Nueva»")
            return
        }

        val now = System.currentTimeMillis()
        val target = item ?: Item(name = name, categoryId = categoryId, createdAt = now, updatedAt = now)
        target.name = name
        target.categoryId = categoryId
        target.updatedAt = now
        repo.saveItem(target)
        for (entry in entries) {
            entry.itemId = target.id
            repo.saveEntry(entry)
        }

        getSharedPreferences(PREFS, MODE_PRIVATE).edit().putLong(PREF_LAST_CATEGORY, categoryId).apply()
        toast("Guardada")
        finish()
    }

    private fun confirmDeleteItem() {
        val current = item ?: return
        AlertDialog.Builder(this)
            .setTitle("Eliminar \"${current.name}\"")
            .setMessage("Se borrará la idea con todos sus elementos para siempre.")
            .setPositiveButton("Eliminar") { _, _ ->
                stopPlayback()
                repo.deleteItem(current)
                // Los elementos sin guardar de esta pantalla ya no tienen idea a la que pertenecer.
                entries.filter { it.id == 0L }.forEach { entry -> entry.filePath?.let { File(it).delete() } }
                entries.clear()
                toast("Eliminada")
                finish()
            }
            .setNegativeButton("Cancelar", null)
            .show()
    }

    private fun toast(message: String) = Toast.makeText(this, message, Toast.LENGTH_SHORT).show()

    companion object {
        private const val EXTRA_ID = "item_id"
        private const val EXTRA_TYPE = "item_type"
        private const val STATE_CAPTURE_PATH = "capture_path"
        private const val STATE_CAPTURE_TYPE = "capture_type"
        private const val PREFS = "ideapp"
        private const val PREF_LAST_CATEGORY = "last_category"

        fun newIntent(context: Context, type: ItemType) =
            Intent(context, ItemActivity::class.java).putExtra(EXTRA_TYPE, type.name)

        fun editIntent(context: Context, id: Long) =
            Intent(context, ItemActivity::class.java).putExtra(EXTRA_ID, id)
    }
}
