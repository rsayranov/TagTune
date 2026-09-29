package com.tagtune.app

import android.app.Activity
import android.graphics.Color
import android.os.Bundle
import android.view.Gravity
import android.widget.LinearLayout
import android.widget.ScrollView
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

        val sortText = TextView(this).apply {
            text = "Недавно добавленные ▼"
            textSize = 14f
            setTextColor(Color.DKGRAY)
            setPadding(8, 0, 8, 16)
        }

        tracksContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }

        val scrollView = ScrollView(this)
        scrollView.addView(tracksContainer)

        root.addView(title)
        root.addView(categories)
        root.addView(sortText)

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
            setPadding(0, 16, 0, 8)
        }

        root.addView(navigation)

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

                val format =
                    it.getString(
                        it.getColumnIndexOrThrow("format")
                    )

                val quality =
                    it.getString(
                        it.getColumnIndexOrThrow("quality")
                    )

                val trackRow = LinearLayout(this).apply {
                    orientation = LinearLayout.VERTICAL
                    setPadding(8, 14, 8, 14)
                }

                val titleText = TextView(this).apply {
                    text = title ?: "Без названия"
                    textSize = 17f
                    setTextColor(Color.BLACK)
                }

                val artistText = TextView(this).apply {
                    text = artist ?: "Неизвестный исполнитель"
                    textSize = 15f
                    setTextColor(Color.DKGRAY)
                    setPadding(0, 4, 0, 0)
                }

                val qualityText = TextView(this).apply {

                    val formatValue =
                        format
                            ?.uppercase()
                            ?: ""

                    val qualityValue =
                        quality ?: ""

                    text =
                        if (qualityValue.isNotEmpty()) {
                            "$formatValue · $qualityValue"
                        } else {
                            formatValue
                        }

                    textSize = 13f
                    setTextColor(Color.GRAY)
                    setPadding(0, 4, 0, 0)
                }

                trackRow.addView(titleText)
                trackRow.addView(artistText)
                trackRow.addView(qualityText)

                tracksContainer.addView(trackRow)

                val divider = TextView(this).apply {
                    text = ""
                    setBackgroundColor(Color.LTGRAY)
                    layoutParams = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        1
                    )
                }

                tracksContainer.addView(divider)

            } while (it.moveToNext())
        }
    }
}
