package com.tagtune.app

import android.app.Activity
import android.graphics.Color
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.WindowInsets
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

        window.statusBarColor = Color.WHITE
        window.decorView.systemUiVisibility =
            View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR

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

        val root = LinearLayout(this)

        root.orientation = LinearLayout.VERTICAL
        root.setBackgroundColor(Color.WHITE)

        root.setOnApplyWindowInsetsListener { view, insets ->

            val statusBarHeight =
                insets.getInsets(WindowInsets.Type.statusBars()).top

            val navigationBarHeight =
                insets.getInsets(WindowInsets.Type.navigationBars()).bottom

            view.setPadding(
                24,
                statusBarHeight + 16,
                24,
                navigationBarHeight + 16
            )

            insets
        }

        val title = TextView(this)

        title.text = "Медиатека"
        title.textSize = 28f
        title.setTextColor(Color.BLACK)
        title.gravity = Gravity.CENTER
        title.setPadding(0, 8, 0, 20)

        root.addView(title)

        categoryContainer = LinearLayout(this)

        categoryContainer.orientation =
            LinearLayout.HORIZONTAL

        val categoryScroll =
            HorizontalScrollView(this)

        categoryScroll.isHorizontalScrollBarEnabled = false
        categoryScroll.addView(categoryContainer)

        root.addView(categoryScroll)

        val sortText = TextView(this)

        sortText.text = "Недавно добавленные"
        sortText.textSize = 14f
        sortText.setTextColor(Color.DKGRAY)
        sortText.setPadding(8, 16, 8, 16)

        root.addView(sortText)

        contentContainer = LinearLayout(this)

        contentContainer.orientation =
            LinearLayout.VERTICAL

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

        val navigation = TextView(this)

        navigation.text =
            "Медиатека        Теги        Поиск        Настройки"

        navigation.textSize = 14f
        navigation.setTextColor(Color.DKGRAY)
        navigation.gravity = Gravity.CENTER
        navigation.setPadding(0, 16, 0, 8)

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

        for (category in categories) {

            val categoryText = TextView(this)

            categoryText.text = category
            categoryText.textSize = 15f
            categoryText.setPadding(18, 10, 18, 10)

            if (category == selectedCategory) {

                categoryText.setTextColor(Color.BLACK)

                categoryText.setTypeface(
                    null,
                    android.graphics.Typeface.BOLD
                )

            } else {

                categoryText.setTextColor(Color.GRAY)
            }

            categoryText.setOnClickListener {

                selectedCategory = category

                createCategories()
                loadCategory()
            }

            categoryContainer.addView(categoryText)
        }
    }

    private fun loadCategory() {

        contentContainer.removeAllViews()

        when (selectedCategory) {

            "Треки" -> {
                loadTracks()
            }

            "Исполнители" -> {
                loadUniqueValues("artist")
            }

            "Альбомы" -> {
                loadUniqueValues("album")
            }

            "Жанры" -> {
                loadUniqueValues("genre")
            }

            "Теги" -> {
                loadTags()
            }
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

                val row = LinearLayout(this)

                row.orientation =
                    LinearLayout.VERTICAL

                row.setPadding(
                    8,
                    14,
                    8,
                    14
                )

                val titleText = TextView(this)

                titleText.text =
                    title ?: "Без названия"

                titleText.textSize = 17f
                titleText.setTextColor(Color.BLACK)

                row.addView(titleText)

                val artistText = TextView(this)

                artistText.text =
                    artist ?: "Неизвестный исполнитель"

                artistText.textSize = 15f
                artistText.setTextColor(Color.DKGRAY)
                artistText.setPadding(0, 4, 0, 0)

                row.addView(artistText)

                val qualityText = TextView(this)

                val formatValue =
                    format?.uppercase(Locale.getDefault()) ?: ""

                val qualityValue =
                    quality ?: ""

                qualityText.text =
                    if (qualityValue.isNotEmpty()) {
                        "$formatValue · $qualityValue"
                    } else {
                        formatValue
                    }

                qualityText.textSize = 13f
                qualityText.setTextColor(Color.GRAY)
                qualityText.setPadding(0, 4, 0, 0)

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

                val row = TextView(this)

                row.text =
                    "$value\n$count треков"

                row.textSize = 17f
                row.setTextColor(Color.BLACK)
                row.setPadding(
                    8,
                    16,
                    8,
                    16
                )

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

        val tagCounts =
            linkedMapOf<String, Int>()

        cursor.use {

            while (it.moveToNext()) {

                val tags =
                    it.getString(
                        it.getColumnIndexOrThrow("tags")
                    )

                val parts =
                    tags.split(
                        ",",
                        "\n",
                        ";"
                    )

                for (rawTag in parts) {

                    val tag =
                        rawTag.trim()

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

        val sortedTags =
            tagCounts.toList().sortedBy {
                it.first.lowercase(
                    Locale.getDefault()
                )
            }

        for ((tag, count) in sortedTags) {

            val row = TextView(this)

            row.text =
                "$tag\n$count треков"

            row.textSize = 17f
            row.setTextColor(Color.BLACK)
            row.setPadding(
                8,
                16,
                8,
                16
            )

            contentContainer.addView(row)

            addDivider()
        }
    }

    private fun addDivider() {

        val divider = View(this)

        divider.setBackgroundColor(Color.LTGRAY)

        val params =
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                1
            )

        divider.layoutParams = params

        contentContainer.addView(divider)
    }

    private fun showEmpty() {

        val empty = TextView(this)

        empty.text =
            "Медиатека пуста\n\nСначала просканируй папку с музыкой."

        empty.textSize = 17f
        empty.setTextColor(Color.DKGRAY)
        empty.gravity = Gravity.CENTER
        empty.setPadding(
            0,
            40,
            0,
            40
        )

        contentContainer.addView(empty)
    }
}
