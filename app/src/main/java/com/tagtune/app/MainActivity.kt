package com.tagtune.app

import android.app.Activity
import android.os.Bundle
import android.graphics.Color
import android.view.Gravity
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView

class MainActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(40, 60, 40, 20)
            setBackgroundColor(Color.WHITE)
        }

        val title = TextView(this).apply {
            text = "Привет! Это TagTune"
            textSize = 28f
            setTextColor(Color.BLACK)
            gravity = Gravity.CENTER
        }

        val subtitle = TextView(this).apply {
            text = "Офлайн-плеер для твоей музыкальной библиотеки"
            textSize = 16f
            setTextColor(Color.DKGRAY)
            gravity = Gravity.CENTER
            setPadding(0, 20, 0, 30)
        }

        val foldersButton = Button(this).apply {
            text = "Выбрать папки"
        }

        root.addView(
            title,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        )

        root.addView(
            subtitle,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        )

        root.addView(
            foldersButton,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        )

        val spacer = TextView(this).apply {
            text = ""
        }

        root.addView(
            spacer,
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
            setPadding(0, 20, 0, 20)
        }

        root.addView(navigation)

        setContentView(root)
    }
}
