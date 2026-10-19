package com.ideapp

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.MediaPlayer
import android.media.MediaRecorder
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.View
import android.widget.ArrayAdapter
import android.widget.Button
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

class ItemActivity : AppCompatActivity() {
    private lateinit var repo: Repo
    private var item: Item? = null
    private lateinit var type: ItemType

    /** Fichero (audio o foto) ya guardado en la base de datos. */
    private var savedFilePath: String? = null

    /** Fichero nuevo todavía sin guardar; sustituye al guardado al pulsar Guardar. */
    private var newFilePath: String? = null

    /** Foto que la cámara está escribiendo ahora mismo. */
    private var pendingPhotoPath: String? = null

    private var recorder: MediaRecorder? = null
    private var recordingPath: String? = null
    private var recordingStart = 0L
    private var player: MediaPlayer? = null
    private val handler = Handler(Looper.getMainLooper())
    private val ticker = object : Runnable {
        override fun run() {
            refreshMedia()
            handler.postDelayed(this, 500)
        }
    }

    private var options: List<CategoryOption> = emptyList()
    private var originalName = ""
    private var originalContent = ""
    private var originalCategoryId = 0L

    private lateinit var nameLayout: TextInputLayout
    private lateinit var nameInput: TextInputEditText
    private lateinit var contentLayout: TextInputLayout
    private lateinit var contentInput: TextInputEditText
    private lateinit var categorySpinner: Spinner
    private lateinit var audioSection: LinearLayout
    private lateinit var audioStatus: TextView
    private lateinit var recordButton: Button
    private lateinit var playButton: Button
    private lateinit var photoSection: LinearLayout
    private lateinit var takePhotoButton: Button
    private lateinit var photoView: ImageView

    private val takePicture = registerForActivityResult(ActivityResultContracts.TakePicture()) { ok ->
        val path = pendingPhotoPath ?: return@registerForActivityResult
        pendingPhotoPath = null
        val file = File(path)
        if (ok && file.exists() && file.length() > 0) setNewFile(path) else file.delete()
    }

    private val pickImage = registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri == null) return@registerForActivityResult
        val file = Media.newFile(this, "foto", "jpg")
        try {
            contentResolver.openInputStream(uri)?.use { input ->
                file.outputStream().use { output -> input.copyTo(output) }
            }
            setNewFile(file.absolutePath)
        } catch (e: Exception) {
            file.delete()
            toast("No se pudo copiar la imagen")
        }
    }

    private val requestMic = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) startRecording() else toast("Sin permiso de micrófono no se puede grabar")
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_item)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        repo = Repo(this)

        nameLayout = findViewById(R.id.til_name)
        nameInput = findViewById(R.id.et_name)
        contentLayout = findViewById(R.id.til_content)
        contentInput = findViewById(R.id.et_content)
        categorySpinner = findViewById(R.id.spinner_category)
        audioSection = findViewById(R.id.section_audio)
        audioStatus = findViewById(R.id.tv_audio_status)
        recordButton = findViewById(R.id.btn_record)
        playButton = findViewById(R.id.btn_play)
        photoSection = findViewById(R.id.section_photo)
        takePhotoButton = findViewById(R.id.btn_take_photo)
        photoView = findViewById(R.id.iv_photo)

        val itemId = intent.getLongExtra(EXTRA_ID, 0)
        val existing = if (itemId > 0) repo.getItem(itemId) else null
        if (itemId > 0 && existing == null) {
            toast("Este ítem ya no existe")
            finish()
            return
        }
        item = existing
        type = existing?.type
            ?: runCatching { ItemType.valueOf(intent.getStringExtra(EXTRA_TYPE) ?: "") }.getOrDefault(ItemType.TEXTO)
        savedFilePath = existing?.filePath
        originalName = existing?.name ?: ""
        originalContent = existing?.content ?: ""
        originalCategoryId = existing?.categoryId
            ?: getSharedPreferences(PREFS, MODE_PRIVATE).getLong(PREF_LAST_CATEGORY, 0)

        if (savedInstanceState != null) {
            newFilePath = savedInstanceState.getString(STATE_NEW_FILE)
            pendingPhotoPath = savedInstanceState.getString(STATE_PENDING_PHOTO)
        } else if (existing != null) {
            nameInput.setText(existing.name)
            contentInput.setText(existing.content)
        }

        title = existing?.name ?: "Nuevo: ${type.label}"
        findViewById<TextView>(R.id.tv_type).text = "${type.emoji} ${type.label}"
        contentLayout.hint = if (type == ItemType.TEXTO) "Texto" else "Notas (opcional)"
        if (type == ItemType.TEXTO) contentInput.minLines = 8

        loadCategories(if (savedInstanceState == null) originalCategoryId else -1)
        findViewById<Button>(R.id.btn_add_category).setOnClickListener {
            CategoryDialogs.create(this, repo, parentId = null, askParent = true) { newId -> loadCategories(newId) }
        }

        recordButton.setOnClickListener { onRecordClicked() }
        playButton.setOnClickListener { togglePlayback() }
        takePhotoButton.setOnClickListener { takePhoto() }
        findViewById<Button>(R.id.btn_pick_photo).setOnClickListener {
            try {
                pickImage.launch("image/*")
            } catch (e: ActivityNotFoundException) {
                toast("No hay galería disponible")
            }
        }
        photoView.setOnClickListener { openPhoto() }

        findViewById<Button>(R.id.btn_save).setOnClickListener { save() }
        val deleteButton = findViewById<Button>(R.id.btn_delete)
        if (existing != null) {
            deleteButton.visibility = View.VISIBLE
            deleteButton.setOnClickListener { confirmDelete() }
            findViewById<TextView>(R.id.tv_dates).apply {
                visibility = View.VISIBLE
                text = "Creado: ${Media.formatDate(existing.createdAt)}\nModificado: ${Media.formatDate(existing.updatedAt)}"
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

        refreshMedia()

        // Acceso rápido: en un audio nuevo, el teclado se abre en el nombre.
        if (existing == null && savedInstanceState == null) nameInput.requestFocus()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putString(STATE_NEW_FILE, newFilePath)
        outState.putString(STATE_PENDING_PHOTO, pendingPhotoPath)
    }

    override fun onSupportNavigateUp(): Boolean {
        onBackPressedDispatcher.onBackPressed()
        return true
    }

    override fun onPause() {
        super.onPause()
        if (recorder != null) stopRecording()
        stopPlayback()
    }

    override fun onDestroy() {
        super.onDestroy()
        handler.removeCallbacks(ticker)
        recorder?.release()
        recorder = null
        player?.release()
        player = null
        if (isFinishing) {
            // Lo que no se ha guardado se descarta.
            newFilePath?.let { File(it).delete() }
            recordingPath?.let { File(it).delete() }
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

    // ---------- Audio ----------

    private fun onRecordClicked() {
        if (recorder != null) {
            stopRecording()
            return
        }
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
            startRecording()
        } else {
            requestMic.launch(Manifest.permission.RECORD_AUDIO)
        }
    }

    private fun startRecording() {
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
            toast("No se pudo iniciar la grabación")
            return
        }
        recorder = r
        recordingPath = file.absolutePath
        recordingStart = SystemClock.elapsedRealtime()
        handler.post(ticker)
    }

    @Suppress("DEPRECATION")
    private fun createLegacyRecorder() = MediaRecorder()

    private fun stopRecording() {
        val r = recorder ?: return
        recorder = null
        handler.removeCallbacks(ticker)
        val path = recordingPath
        recordingPath = null
        val ok = try {
            r.stop()
            true
        } catch (e: RuntimeException) {
            false
        }
        r.release()
        if (ok && path != null) {
            setNewFile(path)
        } else {
            path?.let { File(it).delete() }
            toast("La grabación es demasiado corta")
            refreshMedia()
        }
    }

    private fun togglePlayback() {
        if (player != null) {
            stopPlayback()
            return
        }
        val path = currentFile() ?: return
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
        refreshMedia()
    }

    private fun stopPlayback() {
        player?.let {
            try {
                it.stop()
            } catch (_: IllegalStateException) {
            }
            it.release()
        }
        player = null
        if (::playButton.isInitialized) refreshMedia()
    }

    // ---------- Foto ----------

    private fun takePhoto() {
        val file = Media.newFile(this, "foto", "jpg")
        val uri = FileProvider.getUriForFile(this, "$packageName.fileprovider", file)
        pendingPhotoPath = file.absolutePath
        try {
            takePicture.launch(uri)
        } catch (e: ActivityNotFoundException) {
            pendingPhotoPath = null
            toast("No hay ninguna app de cámara")
        }
    }

    private fun openPhoto() {
        val path = currentFile() ?: return
        val uri = FileProvider.getUriForFile(this, "$packageName.fileprovider", File(path))
        val view = Intent(Intent.ACTION_VIEW)
            .setDataAndType(uri, "image/*")
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        try {
            startActivity(view)
        } catch (e: ActivityNotFoundException) {
            toast("No hay ninguna app para ver fotos")
        }
    }

    // ---------- Estado común ----------

    private fun currentFile(): String? = newFilePath ?: savedFilePath

    private fun setNewFile(path: String) {
        newFilePath?.takeIf { it != path }?.let { File(it).delete() }
        newFilePath = path
        refreshMedia()
    }

    private fun refreshMedia() {
        audioSection.visibility = if (type == ItemType.AUDIO) View.VISIBLE else View.GONE
        photoSection.visibility = if (type == ItemType.FOTO) View.VISIBLE else View.GONE
        val file = currentFile()

        if (type == ItemType.AUDIO) {
            val recording = recorder != null
            audioStatus.text = when {
                recording -> "● Grabando… ${Media.formatDuration(SystemClock.elapsedRealtime() - recordingStart)}"
                file != null -> "Audio de ${Media.formatDuration(Media.audioDuration(file))}"
                else -> "Pulsa Grabar para empezar"
            }
            recordButton.text = when {
                recording -> "⏹ Detener"
                file != null -> "🎤 Volver a grabar"
                else -> "🎤 Grabar"
            }
            playButton.isEnabled = file != null && !recording
            playButton.text = if (player != null) "⏹ Parar" else "▶ Escuchar"
        }

        if (type == ItemType.FOTO) {
            takePhotoButton.text = if (file != null) "📷 Otra foto" else "📷 Sacar foto"
            val bitmap = file?.let { Media.loadBitmap(it, 1280) }
            photoView.setImageBitmap(bitmap)
            photoView.visibility = if (bitmap != null) View.VISIBLE else View.GONE
        }
    }

    private fun hasUnsavedChanges(): Boolean =
        newFilePath != null || recorder != null ||
            nameInput.text.toString() != originalName ||
            contentInput.text.toString() != originalContent ||
            (item != null && selectedCategoryId() != originalCategoryId)

    private fun save() {
        if (recorder != null) stopRecording()
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
        if (type == ItemType.AUDIO && currentFile() == null) {
            toast("Graba un audio antes de guardar")
            return
        }
        if (type == ItemType.FOTO && currentFile() == null) {
            toast("Saca o elige una foto antes de guardar")
            return
        }

        val now = System.currentTimeMillis()
        val target = item ?: Item(name = name, type = type, categoryId = categoryId, createdAt = now, updatedAt = now)
        target.name = name
        target.content = contentInput.text.toString().trim()
        target.categoryId = categoryId
        target.updatedAt = now
        newFilePath?.let { fresh ->
            savedFilePath?.takeIf { it != fresh }?.let { File(it).delete() }
            target.filePath = fresh
        }
        repo.saveItem(target)
        newFilePath = null

        getSharedPreferences(PREFS, MODE_PRIVATE).edit().putLong(PREF_LAST_CATEGORY, categoryId).apply()
        toast("Guardado")
        finish()
    }

    private fun confirmDelete() {
        val current = item ?: return
        AlertDialog.Builder(this)
            .setTitle("Eliminar \"${current.name}\"")
            .setMessage("Se borrará para siempre.")
            .setPositiveButton("Eliminar") { _, _ ->
                stopPlayback()
                repo.deleteItem(current)
                toast("Eliminado")
                finish()
            }
            .setNegativeButton("Cancelar", null)
            .show()
    }

    private fun toast(message: String) = Toast.makeText(this, message, Toast.LENGTH_SHORT).show()

    companion object {
        private const val EXTRA_ID = "item_id"
        private const val EXTRA_TYPE = "item_type"
        private const val STATE_NEW_FILE = "new_file"
        private const val STATE_PENDING_PHOTO = "pending_photo"
        private const val PREFS = "ideapp"
        private const val PREF_LAST_CATEGORY = "last_category"

        fun newIntent(context: Context, type: ItemType) =
            Intent(context, ItemActivity::class.java).putExtra(EXTRA_TYPE, type.name)

        fun editIntent(context: Context, id: Long) =
            Intent(context, ItemActivity::class.java).putExtra(EXTRA_ID, id)
    }
}
