package com.example.tagtune2

import android.app.Activity
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.mpatric.mp3agic.Mp3agic
import com.example.tagtune2.utils.TagParser
import java.io.File

class MainActivity : Activity() {
    private val REQUEST_CODE_PERMISSION = 101

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Создаем прокручиваемый экран и текст
        val scrollView = ScrollView(this)
        val textView = TextView(this)
        textView.text = "TagTune2: Ожидание разрешения на чтение музыки..."
        scrollView.addView(textView)
        setContentView(scrollView)

        // Запускаем проверку разрешений
        checkPermissionAndScan(textView)
    }

    private fun checkPermissionAndScan(textView: TextView) {
        // В новых Андроидах нужно спец. разрешение на аудио, в старых - на все файлы
        val permission = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            android.Manifest.permission.READ_MEDIA_AUDIO
        } else {
            android.Manifest.permission.READ_EXTERNAL_STORAGE
        }

        if (ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED) {
            // Разрешение есть, начинаем поиск
            scanMusic(textView)
        } else {
            // Разрешения нет, просим у пользователя
            ActivityCompat.requestPermissions(this, arrayOf(permission), REQUEST_CODE_PERMISSION)
        }
    }

    // Сюда Android вернет ответ: дал пользователь разрешение или нет
    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQUEST_CODE_PERMISSION && grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
            // Дали разрешение! Перезапускаем экран, чтобы начался поиск
            recreate()
        } else {
            Toast.makeText(this, "Без доступа к памяти плеер не сможет играть!", Toast.LENGTH_LONG).show()
        }
    }

    private fun scanMusic(textView: TextView) {
        val result = StringBuilder("🎵 Моя библиотека (последние 20 треков):\n\n")
        var count = 0

        // Что именно мы хотим узнать о файлах
        val projection = arrayOf(
            MediaStore.Audio.Media.DATA,   // Путь к файлу
            MediaStore.Audio.Media.TITLE,   // Название
            MediaStore.Audio.Media.ARTIST   // Исполнитель
        )

        // Обращаемся к базе данных Андроида с музыкой
        contentResolver.query(
            MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
            projection,
            null,
            null,
            MediaStore.Audio.Media.DATE_ADDED + " DESC LIMIT 20" // Берем только 20 самых свежих, чтобы не тормозило
        )?.use { cursor ->
            val dataColumn = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.DATA)
            val titleColumn = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.TITLE)
            val artistColumn = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.ARTIST)

            while (cursor.moveToNext()) {
                val path = cursor.getString(dataColumn)
                val title = cursor.getString(titleColumn) ?: "Неизвестный трек"
                val artist = cursor.getString(artistColumn) ?: "Неизвестный исполнитель"
                val file = File(path)

                var tagsText = "Нет тегов"
                
                // Если это MP3 файл, пытаемся залезть ему "в мозги" (ID3 теги)
                if (file.exists() && path.endsWith(".mp3", ignoreCase = true)) {
                    try {
                        val mp3 = Mp3agic(file)
                        if (mp3.hasId3v2Tag()) {
                            val comment = mp3.id3v2Tag.comment
                            // Используем наш мозг (TagParser), чтобы достать хештеги
                            val tags = TagParser.extractTags(comment)
                            if (tags.isNotEmpty()) {
                                tagsText = tags.joinToString(" ") { "#$it" }
                            }
                        }
                    } catch (e: Exception) {
                        tagsText = "Ошибка чтения тега"
                    }
                }

                // Красиво выводим на экран
                result.append("🎵 $title\n")
                result.append("👤 $artist\n")
                result.append("🏷 Теги: $tagsText\n")
                result.append("─────────────────\n")
                count++
            }
        }

        if (count == 0) {
            textView.text = "Музыка не найдена. Закинь пару MP3 файлов в память телефона!"
        } else {
            textView.text = result.toString()
        }
    }
}
