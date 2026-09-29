package com.tagtune.app

import android.app.Activity
import android.database.sqlite.SQLiteDatabase
import android.graphics.Color
import android.os.Bundle
import android.view.Gravity
import android.widget.LinearLayout
import android.widget.TextView

class LibraryActivity : Activity() {

    private lateinit var database: MusicDatabase
    private lateinit var tracksContainer: LinearLayout

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        database = MusicDatabase(this)

        showLibrary()
    }

    override fun onResume() {
        super.onResume()

        if (::tracksContainer.isInitialized) {
            loadTracks()
        }
    }

    private fun showLibrary() {

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(24, 40, 24, 16)
            setBackgroundColor(Color.WHITE)
        }

        val title = TextView(this).apply {
            text = "Медиатека"
            textSize = 28f
            setTextColor(Color.BLACK)
            gravity = Gravity.CENTER
            setPadding(0, 0, 0, 20)
        }

        val categories = TextView(this).apply {
            text = "Треки    Исполнители    Альбомы    Жанры    Теги"
            textSize = 15f
            setTextColor(Color.DKGRAY)
            gravity = Gravity.CENTER
            setPadding(0, 0, 0, 20)
        }

        tracksContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }

        root.addView(title)
        root.addView(categories)
        root.addView(tracksContainer)

        setContentView(root)

        loadTracks()
    }

    private fun loadTracks() {

        tracksContainer.removeAllViews()

        val db = database.readableDatabase

        val cursor = db.query(
            "tracks",
            arrayOf(
                "title",
                "artist",
                "album",
                "format",
                "quality"
            ),
            null,
            null,
            null,
            null,
            "date_added DESC"
        )

        cursor.use {

            if (!it.moveToFirst()) {

                val empty = TextView(this).apply {
                    text = "Медиатека пуста\n\nСначала просканируй папку с музыкой."
                    textSize = 17f
                    setTextColor(Color.DKGRAY)
                    gravity = Gravity.CENTER
                    setPadding(0, 40, 0, 40)
                }

                tracksContainer.addView(empty)
                return
            }

            do {

                val title =
                    it.getString(
                        it.getColumnIndexOrThrow("title")
                    )

                val artist =
                    it.getString(
                        it.getColumnIndexOrThrow("artist")
                    )

                val album =
                    it.getString(
                        it.getColumnIndexOrThrow("album")
                    )

                val format =
                    it.getString(
                        it.getColumnIndexOrThrow("format")
                    )

                val quality =
                    it.getString(
                        it.getColumnIndexOrThrow("quality")
                    )

                val track = TextView(this).apply {

                    val titleText =
                        title ?: "Без названия"

                    val artistText =
                        artist ?: "Неизвестный исполнитель"

                    val albumText =
                        album ?: ""

                    val formatText =
                        format
                            ?.uppercase()
                            ?: ""

                    val qualityText =
                        quality ?: ""

                    text =
                        "$titleText\n" +
                        "$artistText" +
                        if (albumText.isNotEmpty()) {
                            " · $albumText"
                        } else {
                            ""
                        } +
                        "\n$formatText" +
                        if (qualityText.isNotEmpty()) {
                            " · $qualityText"
                        } else {
                            ""
                        }

                    textSize = 16f
                    setTextColor(Color.BLACK)
                    setPadding(8, 16, 8, 16)
                }

                tracksContainer.addView(track)

            } while (it.moveToNext())
        }
    }
}
