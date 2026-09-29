package com.tagtune.app

import android.app.Activity
import android.graphics.Color
import android.os.Bundle
import android.view.Gravity
import android.view.Window
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import java.util.Locale

class LibraryActivity : Activity() {

    private lateinit var database: MusicDatabase
    private lateinit var contentContainer: LinearLayout
    private lateinit var categoryContainer: LinearLayout

    private var selectedCategory = "Треки"

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        window.setStatusBarColor(Color.WHITE)
        window.decorView.systemUiVisibility =
            android.view.View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR

        database = MusicDatabase(this)

        showLibrary()
    }

    override fun onResume() {
        super.onResume()

        if (::contentContainer.isInitialized) {
            loadCategory()
        }
    }

    private fun showLibrary() {

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(24, 16, 24, 16)
            setBackgroundColor(Color.WHITE)
        }

        val title = TextView(this).apply {
            text = "Медиатека"
            textSize = 28f
            setTextColor(Color.BLACK)
            gravity = Gravity.CENTER
            setPadding(0, 8, 0, 20)
        }

        categoryContainer = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
        }

        val categoryScroll = HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            addView(categoryContainer)
        }

        root.addView(categoryScroll)

        val sortText = TextView(this).apply {
            text = "Недавно добавленные"
            textSize = 14f
            setTextColor(Color.DKGRAY)
            setPadding(8, 16, 8, 16)
        }

        root.addView(sortText)

        contentContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }

        val scrollView = ScrollView(this)
        scrollView.addView(contentContainer)

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

        createCategories()
        loadCategory()
    }

    private fun createCategories() {

        categoryContainer.removeAllViews()

        val categories = listOf(
            "Треки",
            "Исполнители",
            "Альбомы",
            "Жанры",
            "Теги"
        )

        categories.forEach { category ->

            val categoryText = TextView(this).apply {
                text = category
                textSize = 15f
                setPadding(18, 10, 18, 10)

                if (category == selectedCategory) {
                    setTextColor(Color.BLACK)
                    setTypeface(
                        null,
                        android.graphics.Typeface.BOLD
                    )
                } else {
                    setTextColor(Color.GRAY)
                }

                setOnClickListener {
                    selectedCategory = category
                    createCategories()
                    loadCategory()
                }
            }

            categoryContainer.addView(categoryText)
        }
    }

    private fun loadCategory() {

        contentContainer.removeAllViews()

        when (selectedCategory) {
            "Треки" -> loadTracks()
            "Исполнители" -> loadUniqueValues("artist")
            "Альбомы" -> loadUniqueValues("album")
            "Жанры" -> loadUniqueValues("genre")
            "Теги" -> loadTags()
        }
    }

    private fun loadTracks() {

        val db = database.readableDatabase

        val cursor = db.query(
            "tracks",
            arrayOf(
                "title",
                "artist",
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
                showEmpty()
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

                val row = LinearLayout(this).apply {
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
                        format?.uppercase(Locale.getDefault()) ?: ""

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

                row.addView(titleText)
                row.addView(artistText)
                row.addView(qualityText)

                contentContainer.addView(row)
                addDivider()

            } while (it.moveToNext())
        }
    }

    private fun loadUniqueValues(column: String) {

        val db = database.readableDatabase

        val cursor = db.rawQuery(
            """
            SELECT $column, COUNT(*) AS track_count
            FROM tracks
            WHERE $column IS NOT NULL
              AND TRIM($column) != ''
            GROUP BY $column
            ORDER BY $column COLLATE NOCASE ASC
            """.trimIndent(),
            null
        )

        cursor.use {

            if (!it.moveToFirst()) {
                showEmpty()
                return
            }

            do {

                val value =
                    it.getString(
                        it.getColumnIndexOrThrow(column)
                    )

                val count =
                    it.getInt(
                        it.getColumnIndexOrThrow("track_count")
                    )

                val row = TextView(this).apply {
                    text = "$value\n$count треков"
                    textSize = 17f
                    setTextColor(Color.BLACK)
                    setPadding(8, 16, 8, 16)
                }

                contentContainer.addView(row)
                addDivider()

            } while (it.moveToNext())
        }
    }

    private fun loadTags() {

        val db = database.readableDatabase

        val cursor = db.query(
            "tracks",
            arrayOf("tags"),
            "tags IS NOT NULL AND TRIM(tags) != ''",
            null,
            null,
            null,
            "tags COLLATE NOCASE ASC"
        )

        val tagCounts = linkedMapOf<String, Int>()

        cursor.use {

            while (it.moveToNext()) {

                val tags =
                    it.getString(
                        it.getColumnIndexOrThrow("tags")
                    )

                tags.split(
                    ",",
                    "\n",
                    ";"
                ).forEach { rawTag ->

                    val tag = rawTag.trim()

                    if (tag.isNotEmpty()) {
                        tagCounts[tag] =
                            (tagCounts[tag] ?: 0) + 1
                    }
                }
            }
        }

        if (tagCounts.isEmpty()) {
            showEmpty()
            return
        }

        tagCounts
            .toList()
            .sortedBy {
                it.first.lowercase(Locale.getDefault())
            }
            .forEach { (tag, count) ->

                val row = TextView(this).apply {
                    text = "$tag\n$count треков"
                    textSize = 17f
                    setTextColor(Color.BLACK)
                    setPadding(8, 16, 8, 16)
                }

                contentContainer.addView(row)
                addDivider()
            }
    }

    private fun addDivider() {

        val divider = TextView(this).apply {
            text = ""
            setBackgroundColor(Color.LTGRAY)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                1
            )
        }

        contentContainer.addView(divider)
    }

    private fun showEmpty() {

        val empty = TextView(this).apply {
            text = "Медиатека пуста\n\nСначала просканируй папку с музыкой."
            textSize = 17f
            setTextColor(Color.DKGRAY)
            gravity = Gravity.CENTER
            setPadding(0, 40, 0, 40)
        }

        contentContainer.addView(empty)
    }
}
