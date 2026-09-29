package com.tagtune.app

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.graphics.Color
import android.view.Gravity
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.provider.DocumentsContract

class MainActivity : Activity() {

    private lateinit var folderText: TextView
    private lateinit var countText: TextView
    private lateinit var tracksContainer: LinearLayout

    private lateinit var database: MusicDatabase

    companion object {
        private const val PICK_FOLDER = 1001

        private val MUSIC_EXTENSIONS = setOf(
            "flac",
            "mp3",
            "aac",
            "ogg",
            "opus",
            "m4a"
        )
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        database = MusicDatabase(this)

        showMainScreen()
    }

    private fun showMainScreen() {

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(32, 50, 32, 16)
            setBackgroundColor(Color.WHITE)
        }

        val title = TextView(this).apply {
            text = "TagTune"
            textSize = 30f
            setTextColor(Color.BLACK)
            gravity = Gravity.CENTER
        }

        val subtitle = TextView(this).apply {
            text = "Музыкальная библиотека"
            textSize = 16f
            setTextColor(Color.DKGRAY)
            gravity = Gravity.CENTER
            setPadding(0, 8, 0, 20)
        }

        val chooseButton = Button(this).apply {
            text = "Выбрать папку"

            setOnClickListener {
                openFolderPicker()
            }
        }

        val scanButton = Button(this).apply {
            text = "Сканировать"

            setOnClickListener {
                scanSelectedFolder()
            }
        }

        folderText = TextView(this).apply {
            text = "Папка не выбрана"
            textSize = 14f
            setTextColor(Color.DKGRAY)
            gravity = Gravity.CENTER
            setPadding(0, 12, 0, 12)
        }

        countText = TextView(this).apply {
            text = "Найдено треков: 0"
            textSize = 18f
            setTextColor(Color.BLACK)
            setPadding(0, 12, 0, 12)
        }

        tracksContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }

        val scrollView = ScrollView(this).apply {
            addView(tracksContainer)
        }

        root.addView(title)
        root.addView(subtitle)

        root.addView(
            chooseButton,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        )

        root.addView(
            scanButton,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        )

        root.addView(folderText)
        root.addView(countText)

        root.addView(
            scrollView,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                0,
                1f
            )
        )

        val navigation = TextView(this).apply {
            text = "Медиатека        Теги        Поиск        Настройки"
            textSize = 14f
            setTextColor(Color.DKGRAY)
            gravity = Gravity.CENTER
            setPadding(0, 16, 0, 16)
        }

        root.addView(navigation)

        setContentView(root)
    }

    private fun openFolderPicker() {

        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT_TREE)

        intent.addFlags(
            Intent.FLAG_GRANT_READ_URI_PERMISSION or
                    Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION
        )

        startActivityForResult(intent, PICK_FOLDER)
    }

    @Deprecated("Deprecated in Android API")
    override fun onActivityResult(
        requestCode: Int,
        resultCode: Int,
        data: Intent?
    ) {
        super.onActivityResult(requestCode, resultCode, data)

        if (requestCode == PICK_FOLDER && resultCode == RESULT_OK) {

            val uri = data?.data ?: return

            try {
                contentResolver.takePersistableUriPermission(
                    uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION
                )
            } catch (_: SecurityException) {
            }

            folderText.text = "Выбрано:\n$uri"

            countText.text = "Нажми «Сканировать»"

            tracksContainer.removeAllViews()
        }
    }

    private fun scanSelectedFolder() {

        val uriText = folderText.text.toString()

        if (!uriText.startsWith("Выбрано:")) {
            countText.text = "Сначала выбери папку"
            return
        }

        val uriString = uriText.substringAfter("\n").trim()

        val folderUri = try {
            Uri.parse(uriString)
        } catch (_: Exception) {
            null
        }

        if (folderUri == null) {
            countText.text = "Не удалось открыть папку"
            return
        }

        countText.text = "Сканирование..."

        tracksContainer.removeAllViews()

        Thread {

            val tracks = mutableListOf<String>()

            scanFolder(folderUri, tracks)

            saveTracksToDatabase(folderUri, tracks)

            runOnUiThread {

                countText.text =
                    "Найдено треков: ${tracks.size}"

                if (tracks.isEmpty()) {

                    val emptyText = TextView(this).apply {
                        text = "Музыкальные файлы не найдены"
                        textSize = 16f
                        setPadding(0, 20, 0, 20)
                    }

                    tracksContainer.addView(emptyText)

                } else {

                    tracks.forEach { fileName ->

                        val trackText = TextView(this).apply {
                            text = fileName
                            textSize = 16f
                            setTextColor(Color.BLACK)
                            setPadding(8, 14, 8, 14)
                        }

                        tracksContainer.addView(trackText)
                    }
                }
            }

        }.start()
    }

    private fun saveTracksToDatabase(
        folderUri: Uri,
        tracks: List<String>
    ) {

        val db = database.writableDatabase

        for (fileName in tracks) {

            val fileUri = DocumentsContract.buildChildDocumentsUriUsingTree(
                folderUri,
                DocumentsContract.getTreeDocumentId(folderUri)
            )

            val values = android.content.ContentValues().apply {
                put("uri", "$fileUri/$fileName")
                put("file_name", fileName)
                put("date_added", System.currentTimeMillis())
            }

            db.insertWithOnConflict(
                "tracks",
                null,
                values,
                android.database.sqlite.SQLiteDatabase.CONFLICT_IGNORE
            )
        }
    }

    private fun scanFolder(
        folderUri: Uri,
        tracks: MutableList<String>
    ) {

        val childrenUri =
            DocumentsContract.buildChildDocumentsUriUsingTree(
                folderUri,
                DocumentsContract.getTreeDocumentId(folderUri)
            )

        val projection = arrayOf(
            DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_MIME_TYPE
        )

        contentResolver.query(
            childrenUri,
            projection,
            null,
            null,
            null
        )?.use { cursor ->

            val idIndex = cursor.getColumnIndex(
                DocumentsContract.Document.COLUMN_DOCUMENT_ID
            )

            val nameIndex = cursor.getColumnIndex(
                DocumentsContract.Document.COLUMN_DISPLAY_NAME
            )

            val mimeIndex = cursor.getColumnIndex(
                DocumentsContract.Document.COLUMN_MIME_TYPE
            )

            while (cursor.moveToNext()) {

                val id = cursor.getString(idIndex)
                val name = cursor.getString(nameIndex)
                val mime = cursor.getString(mimeIndex)

                if (mime == DocumentsContract.Document.MIME_TYPE_DIR) {

                    val childUri =
                        DocumentsContract.buildDocumentUriUsingTree(
                            folderUri,
                            id
                        )

                    scanFolder(childUri, tracks)

                } else {

                    val extension = name
                        .substringAfterLast('.', "")
                        .lowercase()

                    if (extension in MUSIC_EXTENSIONS) {
                        tracks.add(name)
                    }
                }
            }
        }
    }
}
