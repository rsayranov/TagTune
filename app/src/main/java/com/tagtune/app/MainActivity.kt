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
import org.jaudiotagger.audio.AudioFileIO
import org.jaudiotagger.tag.FieldKey
import java.io.File
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

                        val tagsText =
                            track.tags

                        val trackText =
                            TextView(this).apply {

                                this.text =
                                    buildString {

                                        append(
                                            titleText
                                        )

                                        append("\n")

                                        append(
                                            artistText
                                        )

                                        append("\n")

                                        if (
                                            qualityText != null
                                        ) {

                                            append(
                                                "$formatText · " +
                                                    qualityText
                                            )

                                        } else {

                                            append(
                                                formatText
                                            )
                                        }

                                        if (
                                            !tagsText.isNullOrBlank()
                                        ) {

                                            append("\n")
                                            append(tagsText)
                                        }
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

        var title: String? = null
        var artist: String? = null
        var album: String? = null
        var genre: String? = null
        var bitrate: String? = null
        var sampleRate: String? = null

        try {

            retriever.setDataSource(
                this,
                uri
            )

            title =
                retriever.extractMetadata(
                    MediaMetadataRetriever
                        .METADATA_KEY_TITLE
                )

            artist =
                retriever.extractMetadata(
                    MediaMetadataRetriever
                        .METADATA_KEY_ARTIST
                )

            album =
                retriever.extractMetadata(
                    MediaMetadataRetriever
                        .METADATA_KEY_ALBUM
                )

            genre =
                retriever.extractMetadata(
                    MediaMetadataRetriever
                        .METADATA_KEY_GENRE
                )

            bitrate =
                retriever.extractMetadata(
                    MediaMetadataRetriever
                        .METADATA_KEY_BITRATE
                )

            sampleRate =
                retriever.extractMetadata(
                    MediaMetadataRetriever
                        .METADATA_KEY_SAMPLERATE
                )

        } catch (_: Exception) {
        } finally {

            try {
                retriever.release()
            } catch (_: Exception) {
            }
        }

        val tags =
            readTagsFromComment(
                uri
            )

        val quality =
            buildQuality(
                format,
                bitrate,
                sampleRate
            )

        return TrackInfo(
            uri = uri.toString(),
            fileName = fileName,
            title = title,
            artist = artist,
            album = album,
            genre = genre,
            format = format,
            quality = quality,
            tags = tags
        )
    }

    private fun readTagsFromComment(
        uri: Uri
    ): String? {

        val temporaryFile =
            File.createTempFile(
                "tagtune_",
                ".audio",
                cacheDir
            )

        return try {

            contentResolver
                .openInputStream(uri)
                ?.use { input ->

                    temporaryFile
                        .outputStream()
                        .use { output ->

                            input.copyTo(
                                output
                            )
                        }
                }
                ?: return null

            val audioFile =
                AudioFileIO.read(
                    temporaryFile
                )

            val tag =
                audioFile.tag
                    ?: return null

            val comment =
                tag.getFirst(
                    FieldKey.COMMENT
                )

            if (
                comment.isNullOrBlank()
            ) {
                return null
            }

            extractHashtagTags(
                comment
            )

        } catch (_: Exception) {

            null

        } finally {

            try {
                temporaryFile.delete()
            } catch (_: Exception) {
            }
        }
    }

    private fun extractHashtagTags(
        comment: String
    ): String? {

        val tags =
            Regex(
                """#[^\s#]+"""
            )
                .findAll(comment)
                .map {
                    it.value.trim()
                }
                .filter {
                    it.length <= 16
                }
                .distinctBy {
                    it.lowercase(
                        Locale.getDefault()
                    )
                }
                .toList()

        if (tags.isEmpty()) {
            return null
        }

        return tags.joinToString(
            separator = "\n"
        )
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
                "tags",
                track.tags
            )

            values.put(
                "date_added",
                System.currentTimeMillis()
            )

            val updated =
                db.update(
                    "tracks",
                    values,
                    "uri = ?",
                    arrayOf(track.uri)
                )

            if (updated == 0) {

                db.insert(
                    "tracks",
                    null,
                    values
                )
            }
        }
    }
}
