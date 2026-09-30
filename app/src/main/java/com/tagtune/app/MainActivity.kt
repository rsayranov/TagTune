package com.tagtune.app

import android.app.Activity
import android.content.ContentValues
import android.content.Intent
import android.graphics.Color
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Bundle
import android.provider.DocumentsContract
import android.view.Gravity
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import java.util.Locale

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

        val root = LinearLayout(this)
        root.orientation = LinearLayout.VERTICAL
        root.setPadding(32, 50, 32, 16)
        root.setBackgroundColor(Color.WHITE)

        val title = TextView(this)
        title.text = "TagTune"
        title.textSize = 30f
        title.setTextColor(Color.BLACK)
        title.gravity = Gravity.CENTER

        val subtitle = TextView(this)
        subtitle.text = "Музыкальная библиотека"
        subtitle.textSize = 16f
        subtitle.setTextColor(Color.DKGRAY)
        subtitle.gravity = Gravity.CENTER

        val chooseButton = Button(this)
        chooseButton.text = "Выбрать папку"
        chooseButton.setOnClickListener {
            openFolderPicker()
        }

        val scanButton = Button(this)
        scanButton.text = "Сканировать"
        scanButton.setOnClickListener {
            scanSelectedFolder()
        }

        folderText = TextView(this)
        folderText.text = "Папка не выбрана"
        folderText.textSize = 14f
        folderText.setTextColor(Color.DKGRAY)
        folderText.gravity = Gravity.CENTER

        countText = TextView(this)
        countText.text = "Найдено треков: 0"
        countText.textSize = 18f
        countText.setTextColor(Color.BLACK)

        tracksContainer = LinearLayout(this)
        tracksContainer.orientation = LinearLayout.VERTICAL

        val scrollView = ScrollView(this)
        scrollView.addView(tracksContainer)

        root.addView(title)
        root.addView(subtitle)
        root.addView(chooseButton)
        root.addView(scanButton)
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
        super.onActivityResult(
            requestCode,
            resultCode,
            data
        )

        if (
            requestCode != PICK_FOLDER ||
            resultCode != RESULT_OK
        ) {
            return
        }

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

    private fun scanSelectedFolder() {

        val selectedFolderText =
            folderText.text.toString()

        if (
            !selectedFolderText.startsWith(
                "Выбрано:"
            )
        ) {
            countText.text =
                "Сначала выбери папку"
            return
        }

        val uriString =
            selectedFolderText
                .substringAfter("\n")
                .trim()

        val folderUri =
            Uri.parse(uriString)

        countText.text =
            "Сканирование..."

        tracksContainer.removeAllViews()

        Thread {

            val tracks =
                mutableListOf<TrackInfo>()

            scanFolder(
                folderUri,
                tracks
            )

            saveTracksToDatabase(
                tracks
            )

            runOnUiThread {

                countText.text =
                    "Найдено треков: ${tracks.size}"

                if (tracks.isEmpty()) {

                    val emptyText =
                        TextView(this).apply {

                            this.text =
                                "Музыкальные файлы не найдены"

                            textSize = 16f

                            setPadding(
                                0,
                                20,
                                0,
                                20
                            )
                        }

                    tracksContainer.addView(
                        emptyText
                    )

                } else {

                    tracks.forEach { track ->

                        val titleText =
                            track.title
                                ?: track.fileName

                        val artistText =
                            track.artist
                                ?: "Неизвестный исполнитель"

                        val formatText =
                            track.format
                                ?.uppercase(
                                    Locale.getDefault()
                                )
                                ?: "UNKNOWN"

                        val qualityText =
                            track.quality

                        val trackText =
                            TextView(this).apply {

                                this.text =
                                    if (
                                        qualityText != null
                                    ) {

                                        "$titleText\n" +
                                            "$artistText\n" +
                                            "$formatText · " +
                                            qualityText

                                    } else {

                                        "$titleText\n" +
                                            "$artistText\n" +
                                            formatText
                                    }

                                textSize = 16f

                                setTextColor(
                                    Color.BLACK
                                )

                                setPadding(
                                    8,
                                    14,
                                    8,
                                    14
                                )
                            }

                        tracksContainer.addView(
                            trackText
                        )
                    }
                }
            }

        }.start()
    }

    private fun scanFolder(
        folderUri: Uri,
        tracks: MutableList<TrackInfo>
    ) {

        val documentId =
            DocumentsContract
                .getTreeDocumentId(
                    folderUri
                )

        val childrenUri =
            DocumentsContract
                .buildChildDocumentsUriUsingTree(
                    folderUri,
                    documentId
                )

        val projection =
            arrayOf(
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

            val idIndex =
                cursor.getColumnIndex(
                    DocumentsContract.Document
                        .COLUMN_DOCUMENT_ID
                )

            val nameIndex =
                cursor.getColumnIndex(
                    DocumentsContract.Document
                        .COLUMN_DISPLAY_NAME
                )

            val mimeIndex =
                cursor.getColumnIndex(
                    DocumentsContract.Document
                        .COLUMN_MIME_TYPE
                )

            while (cursor.moveToNext()) {

                val id =
                    cursor.getString(
                        idIndex
                    )

                val name =
                    cursor.getString(
                        nameIndex
                    )

                val mime =
                    cursor.getString(
                        mimeIndex
                    )

                if (
                    mime ==
                    DocumentsContract.Document
                        .MIME_TYPE_DIR
                ) {

                    val childUri =
                        DocumentsContract
                            .buildDocumentUriUsingTree(
                                folderUri,
                                id
                            )

                    scanFolder(
                        childUri,
                        tracks
                    )

                } else {

                    val extension =
                        name.substringAfterLast(
                            '.',
                            ""
                        ).lowercase()

                    if (
                        extension in
                        MUSIC_EXTENSIONS
                    ) {

                        val fileUri =
                            DocumentsContract
                                .buildDocumentUriUsingTree(
                                    folderUri,
                                    id
                                )

                        val track =
                            readMetadata(
                                fileUri,
                                name
                            )

                        tracks.add(track)
                    }
                }
            }
        }
    }

    private fun readMetadata(
        uri: Uri,
        fileName: String
    ): TrackInfo {

        val format =
            fileName.substringAfterLast(
                '.',
                ""
            ).lowercase()

        val retriever =
            MediaMetadataRetriever()

        return try {

            retriever.setDataSource(
                this,
                uri
            )

            val title =
                retriever.extractMetadata(
                    MediaMetadataRetriever
                        .METADATA_KEY_TITLE
                )

            val artist =
                retriever.extractMetadata(
                    MediaMetadataRetriever
                        .METADATA_KEY_ARTIST
                )

            val album =
                retriever.extractMetadata(
                    MediaMetadataRetriever
                        .METADATA_KEY_ALBUM
                )

            val genre =
                retriever.extractMetadata(
                    MediaMetadataRetriever
                        .METADATA_KEY_GENRE
                )

            val comment =
                retriever.extractMetadata(
                    MediaMetadataRetriever
                        .METADATA_KEY_CD_TRACK_NUMBER
                )

            val bitrate =
                retriever.extractMetadata(
                    MediaMetadataRetriever
                        .METADATA_KEY_BITRATE
                )

            val sampleRate =
                retriever.extractMetadata(
                    MediaMetadataRetriever
                        .METADATA_KEY_SAMPLERATE
                )

            val quality =
                buildQuality(
                    format,
                    bitrate,
                    sampleRate
                )

            TrackInfo(
                uri = uri.toString(),
                fileName = fileName,
                title = title,
                artist = artist,
                album = album,
                genre = genre,
                format = format,
                quality = quality
            )

        } catch (_: Exception) {

            TrackInfo(
                uri = uri.toString(),
                fileName = fileName,
                title = null,
                artist = null,
                album = null,
                genre = null,
                format = format,
                quality = null
            )

        } finally {

            try {
                retriever.release()
            } catch (_: Exception) {
            }
        }
    }

    private fun buildQuality(
        format: String,
        bitrate: String?,
        sampleRate: String?
    ): String? {

        val rate =
            sampleRate?.toLongOrNull()

        val bit =
            bitrate?.toLongOrNull()

        if (
            format == "flac" &&
            rate != null
        ) {
            return "${rate / 1000} kHz"
        }

        if (bit != null) {
            return "${bit / 1000} kbps"
        }

        if (rate != null) {
            return "${rate / 1000} kHz"
        }

        return null
    }

    private fun saveTracksToDatabase(
        tracks: List<TrackInfo>
    ) {

        val db =
            database.writableDatabase

        for (track in tracks) {

            val values =
                ContentValues()

            values.put(
                "uri",
                track.uri
            )

            values.put(
                "file_name",
                track.fileName
            )

            values.put(
                "title",
                track.title
            )

            values.put(
                "artist",
                track.artist
            )

            values.put(
                "album",
                track.album
            )

            values.put(
                "genre",
                track.genre
            )

            values.put(
                "format",
                track.format
            )

            values.put(
                "quality",
                track.quality
            )

            values.put(
                "date_added",
                System.currentTimeMillis()
            )

            db.insertWithOnConflict(
                "tracks",
                null,
                values,
                android.database.sqlite.SQLiteDatabase
                    .CONFLICT_IGNORE
            )
        }
    }
}
