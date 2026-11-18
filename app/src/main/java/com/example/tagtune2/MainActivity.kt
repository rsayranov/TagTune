package com.example.tagtune2

import android.app.Activity
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import android.widget.ScrollView
import android.widget.TextView
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.mpatric.mp3agic.Mp3File
import com.example.tagtune2.utils.TagParser
import java.io.File

class MainActivity : Activity() {

    private val REQUEST_CODE_PERMISSION = 101
    private lateinit var textView: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val scrollView = ScrollView(this)
        textView = TextView(this)
        textView.setPadding(24, 24, 24, 24)
        textView.text = "TagTune2: запускаюсь..."
        scrollView.addView(textView)
        setContentView(scrollView)
        checkPermissionAndScan()
    }

    private fun neededPermission(): String =
        if (Build.VERSION.SDK_INT >= 33) android.Manifest.permission.READ_MEDIA_AUDIO
        else android.Manifest.permission.READ_EXTERNAL_STORAGE

    private fun checkPermissionAndScan() {
        if (ContextCompat.checkSelfPermission(this, neededPermission()) == PackageManager.PERMISSION_GRANTED) {
            startScan()
        } else {
            textView.text = "TagTune2: жду разрешение на чтение музыки..."
            ActivityCompat.requestPermissions(this, arrayOf(neededPermission()), REQUEST_CODE_PERMISSION)
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQUEST_CODE_PERMISSION) {
            if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                startScan() // Без перезапуска экрана: сразу сканируем!
            } else {
                textView.text = "Разрешение не дано.\nДай его в настройках приложения и перезапусти плеер."
            }
        }
    }

    private fun startScan() {
        textView.text = "TagTune2: сканирую музыку..."
        // Сканируем в фоне, чтобы экран не зависал
        Thread {
            val report = StringBuilder()
            try {
                report.append(scanLibrary())
            } catch (t: Throwable) {
                // Наш "рентген": показываем камень на экране вместо вылета
                report.append("ОЙ! Споткнулись о камень:\n\n")
                report.append(t.toString())
                report.append("\n\n")
                report.append(android.util.Log.getStackTraceString(t))
            }
            runOnUiThread { textView.text = report.toString() }
        }.start()
    }

    private fun scanLibrary(): String {
        val result = StringBuilder("🎵 Моя библиотека (20 свежих треков):\n\n")
        var count = 0
        val projection = arrayOf(
            MediaStore.Audio.Media.DATA,
            MediaStore.Audio.Media.TITLE,
            MediaStore.Audio.Media.ARTIST
        )
        contentResolver.query(
            MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
            projection,
            null,
            null,
            MediaStore.Audio.Media.DATE_ADDED + " DESC"
        )?.use { cursor ->
            val dataColumn = cursor.getColumnIndex(MediaStore.Audio.Media.DATA)
            val titleColumn = cursor.getColumnIndex(MediaStore.Audio.Media.TITLE)
            val artistColumn = cursor.getColumnIndex(MediaStore.Audio.Media.ARTIST)
            while (cursor.moveToNext() && count < 20) {
                val path = if (dataColumn >= 0) cursor.getString(dataColumn) else null
                val title = (if (titleColumn >= 0) cursor.getString(titleColumn) else null) ?: "Неизвестный трек"
                val artist = (if (artistColumn >= 0) cursor.getString(artistColumn) else null) ?: "Неизвестный исполнитель"
                var tagsText = "нет тегов"
                if (path != null && path.endsWith(".mp3", true) && File(path).exists()) {
                    tagsText = try {
                        val mp3 = Mp3File(path)
                        if (mp3.hasId3v2Tag()) {
                            val tags = TagParser.extractTags(mp3.id3v2Tag.comment)
                            if (tags.isNotEmpty()) tags.joinToString(" ") { "#$it" } else "нет тегов"
                        } else "нет ID3v2"
                    } catch (t: Throwable) {
                        "ошибка: ${t.javaClass.simpleName}"
                    }
                }
                result.append("🎵 $title\n👤 $artist\n🏷 $tagsText\n──────────\n")
                count++
            }
        }
        if (count == 0) return "Музыка не найдена. Закинь MP3 в память телефона!"
        return result.toString()
    }
}
