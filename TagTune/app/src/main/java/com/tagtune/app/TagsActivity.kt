package com.tagtune.app

import android.app.Activity
import android.graphics.Color
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.WindowInsets
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import java.util.Locale

class TagsActivity : Activity() {

    private lateinit var database: MusicDatabase
    private lateinit var contentContainer: LinearLayout

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        window.statusBarColor = Color.WHITE
        window.decorView.systemUiVisibility =
            View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR

        database = MusicDatabase(this)

        showTagsScreen()
    }

    override fun onResume() {
        super.onResume()

        if (::contentContainer.isInitialized) {
            loadTags()
        }
    }

    private fun showTagsScreen() {

        val root = LinearLayout(this)

        root.orientation = LinearLayout.VERTICAL
        root.setBackgroundColor(Color.WHITE)

        root.setOnApplyWindowInsetsListener { view, insets ->

            val statusBarHeight =
                insets.getInsets(
                    WindowInsets.Type.statusBars()
                ).top

            val navigationBarHeight =
                insets.getInsets(
                    WindowInsets.Type.navigationBars()
                ).bottom

            view.setPadding(
                24,
                statusBarHeight + 16,
                24,
                navigationBarHeight + 8
            )

            insets
        }

        val title = TextView(this)

        title.text = "Теги"
        title.textSize = 28f
        title.setTextColor(Color.BLACK)
        title.gravity = Gravity.CENTER
        title.setPadding(0, 8, 0, 24)

        root.addView(title)

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

        val navigation = LinearLayout(this)

        navigation.orientation =
            LinearLayout.HORIZONTAL

        navigation.gravity =
            Gravity.CENTER

        val libraryButton =
            createNavigationButton("Медиатека")

        val tagsButton =
            createNavigationButton("Теги")

        val searchButton =
            createNavigationButton("Поиск")

        val settingsButton =
            createNavigationButton("Настройки")

        libraryButton.setOnClickListener {
            finish()
        }

        tagsButton.setOnClickListener {
            loadTags()
        }

        searchButton.setOnClickListener {
            android.widget.Toast.makeText(
                this,
                "Поиск — следующий экран",
                android.widget.Toast.LENGTH_SHORT
            ).show()
        }

        settingsButton.setOnClickListener {
            android.widget.Toast.makeText(
                this,
                "Настройки — следующий экран",
                android.widget.Toast.LENGTH_SHORT
            ).show()
        }

        navigation.addView(libraryButton)
        navigation.addView(tagsButton)
        navigation.addView(searchButton)
        navigation.addView(settingsButton)

        root.addView(navigation)

        setContentView(root)

        loadTags()
    }

    private fun createNavigationButton(
        textValue: String
    ): android.widget.Button {

        val button =
            android.widget.Button(this)

        button.text = textValue
        button.textSize = 12f
        button.setTextColor(Color.DKGRAY)

        button.setPadding(
            4,
            4,
            4,
            4
        )

        button.layoutParams =
            LinearLayout.LayoutParams(
                0,
                LinearLayout.LayoutParams.WRAP_CONTENT,
                1f
            )

        return button
    }

    private fun loadTags() {

        contentContainer.removeAllViews()

        val db = database.readableDatabase

        val cursor = db.query(
            "tracks",
            arrayOf("tags"),
            "tags IS NOT NULL AND TRIM(tags) != ''",
            null,
            null,
            null,
            null
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

                        val existing =
                            tagCounts[tag] ?: 0

                        tagCounts[tag] =
                            existing + 1
                    }
                }
            }
        }

        if (tagCounts.isEmpty()) {

            val empty = TextView(this)

            empty.text =
                "Тегов пока нет\n\nДобавленные теги появятся здесь."

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

            return
        }

        val sortedTags =
            tagCounts.toList().sortedBy {

                it.first.lowercase(
                    Locale.getDefault()
                )
            }

        for ((tag, count) in sortedTags) {

            val row = LinearLayout(this)

            row.orientation =
                LinearLayout.HORIZONTAL

            row.gravity =
                Gravity.CENTER_VERTICAL

            row.setPadding(
                8,
                16,
                8,
                16
            )

            val tagText = TextView(this)

            tagText.text = tag
            tagText.textSize = 17f
            tagText.setTextColor(Color.BLACK)

            tagText.layoutParams =
                LinearLayout.LayoutParams(
                    0,
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    1f
                )

            row.addView(tagText)

            val countText = TextView(this)

            countText.text =
                "$count треков"

            countText.textSize = 14f
            countText.setTextColor(Color.GRAY)
            countText.gravity = Gravity.CENTER_VERTICAL

            row.addView(countText)

            row.setOnClickListener {

                android.widget.Toast.makeText(
                    this,
                    "Тег: $tag",
                    android.widget.Toast.LENGTH_SHORT
                ).show()
            }

            contentContainer.addView(row)

            addDivider()
        }
    }

    private fun addDivider() {

        val divider = View(this)

        divider.setBackgroundColor(
            Color.LTGRAY
        )

        divider.layoutParams =
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                1
            )

        contentContainer.addView(divider)
    }
}
