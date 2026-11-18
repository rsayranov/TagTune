package com.example.tagtune2

import android.app.Activity
import android.content.pm.PackageManager
import android.graphics.Color
import android.media.MediaPlayer
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import android.widget.Button
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.mpatric.mp3agic.Mp3File
import com.example.tagtune2.utils.TagParser
import java.io.File

class MainActivity : Activity() {

    private val REQ = 101
    private lateinit var status: TextView
    private lateinit var chipsRow: LinearLayout
    private lateinit var nowText: TextView
    private lateinit var playBtn: Button
    private lateinit var listBox: LinearLayout

    data class Track(val path: String, val title: String, val artist: String, val tags: List<String>)

    private var tracks = listOf<Track>()
    private val selected = mutableSetOf<String>()
    private var shown = listOf<Track>()
    private var player: MediaPlayer? = null
    private var current: Track? = null

    private fun dp(n: Int) = (resources.displayMetrics.density * n).toInt()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val root = LinearLayout(this)
        root.orientation = LinearLayout.VERTICAL
        root.setPadding(dp(14), dp(14), dp(14), dp(14))

        status = TextView(this)
        status.text = "TagTune2: запускаюсь..."
        root.addView(status)

        val chipScroll = HorizontalScrollView(this)
        chipsRow = LinearLayout(this)
        chipsRow.orientation = LinearLayout.HORIZONTAL
        chipScroll.addView(chipsRow)
        root.addView(chipScroll)

        nowText = TextView(this)
        nowText.text = "Ничего не играет. Нажми на трек ниже."
        nowText.setPadding(0, dp(10), 0, dp(6))
        root.addView(nowText)

        playBtn = Button(this)
        playBtn.text = "▶ Играть / ⏸ Пауза"
        playBtn.setOnClickListener { toggle() }
        root.addView(playBtn)

        val listScroll = ScrollView(this)
        listBox = LinearLayout(this)
        listBox.orientation = LinearLayout.VERTICAL
        listScroll.addView(listBox)
        root.addView(listScroll, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))

        setContentView(root)
        checkPermissionAndScan()
    }

    private fun neededPermission(): String =
        if (Build.VERSION.SDK_INT >= 33) android.Manifest.permission.READ_MEDIA_AUDIO
        else android.Manifest.permission.READ_EXTERNAL_STORAGE

    private fun checkPermissionAndScan() {
        if (ContextCompat.checkSelfPermission(this, neededPermission()) == PackageManager.PERMISSION_GRANTED) {
            startScan()
        } else {
            status.text = "TagTune2: жду разрешение на чтение музыки..."
            ActivityCompat.requestPermissions(this, arrayOf(neededPermission()), REQ)
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQ) {
            if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) startScan()
            else status.text = "Разрешение не дано. Дай его в настройках приложения и перезапусти."
        }
    }

    private fun startScan() {
        status.text = "TagTune2: сканирую музыку..."
        Thread {
            val report = StringBuilder()
            try {
                tracks = scanLibrary()
                runOnUiThread {
                    rebuildChips()
                    rebuildList()
                    status.text = "🎵 Найдено треков: ${tracks.size}. Нажми на тег — фильтр, на трек — игра."
                }
            } catch (t: Throwable) {
                report.append("ОЙ! Споткнулись о камень:\n\n").append(android.util.Log.getStackTraceString(t))
                runOnUiThread { status.text = report.toString() }
            }
        }.start()
    }

    private fun scanLibrary(): List<Track> {
        val out = mutableListOf<Track>()
        val projection = arrayOf(
            MediaStore.Audio.Media.DATA,
            MediaStore.Audio.Media.TITLE,
            MediaStore.Audio.Media.ARTIST
        )
        contentResolver.query(
            MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, projection, null, null,
            MediaStore.Audio.Media.DATE_ADDED + " DESC"
        )?.use { cursor ->
            val dC = cursor.getColumnIndex(MediaStore.Audio.Media.DATA)
            val tC = cursor.getColumnIndex(MediaStore.Audio.Media.TITLE)
            val aC = cursor.getColumnIndex(MediaStore.Audio.Media.ARTIST)
            while (cursor.moveToNext() && out.size < 200) {
                val path = if (dC >= 0) cursor.getString(dC) else null
                val title = (if (tC >= 0) cursor.getString(tC) else null) ?: "Неизвестный трек"
                val artist = (if (aC >= 0) cursor.getString(aC) else null) ?: "Неизвестный исполнитель"
                var tags = emptyList<String>()
                if (path != null && path.endsWith(".mp3", true) && File(path).exists()) {
                    tags = try {
                        val mp3 = Mp3File(path)
                        if (mp3.hasId3v2Tag()) TagParser.extractTags(mp3.id3v2Tag.comment) else emptyList()
                    } catch (t: Throwable) { emptyList() }
                }
                out.add(Track(path ?: "", title, artist, tags))
            }
        }
        return out
    }

    private fun rebuildChips() {
        chipsRow.removeAllViews()
        val counts = mutableMapOf<String, Int>()
        for (t in tracks) for (g in t.tags) counts[g] = (counts[g] ?: 0) + 1
        for ((tag, n) in counts.entries.sortedByDescending { it.value }) {
            val chip = TextView(this)
            chip.text = "#$tag ($n)"
            chip.setPadding(dp(12), dp(8), dp(12), dp(8))
            val lp = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT)
            lp.setMargins(0, 0, dp(8), 0)
            chip.layoutParams = lp
            val on = selected.contains(tag)
            chip.setBackgroundColor(if (on) Color.parseColor("#7C4DFF") else Color.parseColor("#2A2A3A"))
            chip.setTextColor(if (on) Color.WHITE else Color.parseColor("#BBBBCC"))
            chip.setOnClickListener {
                if (on) selected.remove(tag) else selected.add(tag)
                rebuildChips(); rebuildList()
            }
            chipsRow.addView(chip)
        }
    }

    private fun rebuildList() {
        // Логика И: трек попадает в список, только если несёт ВСЕ выбранные теги
        val filtered = tracks.filter { t -> selected.all { g -> t.tags.contains(g) } }
        shown = filtered.shuffled() // по ТЗ: случайный порядок по умолчанию
        listBox.removeAllViews()
        if (shown.isEmpty()) {
            val empty = TextView(this)
            empty.text = if (tracks.isEmpty()) "Музыка не найдена. Закинь MP3 в память телефона!"
                         else "Пересечение пусто: ни один трек не несёт все выбранные теги. Сними тег."
            empty.setPadding(0, dp(16), 0, 0)
            listBox.addView(empty)
            return
        }
        for (t in shown) {
            val row = TextView(this)
            row.text = "🎵 ${t.title}\n👤 ${t.artist}\n🏷 " +
                (if (t.tags.isEmpty()) "нет тегов" else t.tags.joinToString(" ") { "#$it" })
            row.setPadding(dp(12), dp(10), dp(12), dp(10))
            row.setBackgroundColor(if (current?.path == t.path) Color.parseColor("#3A2A5A") else Color.parseColor("#1E1E2A"))
            row.setTextColor(Color.parseColor("#EEEEF5"))
            val lp = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
            lp.setMargins(0, 0, 0, dp(8))
            row.layoutParams = lp
            row.setOnClickListener { play(t) }
            listBox.addView(row)
        }
    }

    private fun play(t: Track) {
        if (t.path.isEmpty()) { nowText.text = "У трека нет пути к файлу."; return }
        current = t
        nowText.text = "▶ Играет: ${t.title} — ${t.artist}"
        try {
            player?.release()
            player = MediaPlayer().apply {
                setDataSource(t.path)
                setOnCompletionListener { next() }
                prepare()
                start()
            }
            playBtn.text = "⏸ Пауза"
        } catch (e: Exception) {
            nowText.text = "Не удалось играть: ${e.message}"
        }
        rebuildList()
    }

    private fun toggle() {
        val p = player
        if (p == null) { shown.firstOrNull()?.let { play(it) }; return }
        if (p.isPlaying) { p.pause(); playBtn.text = "▶ Играть" }
        else { p.start(); playBtn.text = "⏸ Пауза" }
    }

    private fun next() {
        if (shown.isEmpty()) return
        val i = shown.indexOfFirst { it.path == current?.path }
        play(shown[(i + 1) % shown.size])
    }

    override fun onDestroy() {
        super.onDestroy()
        player?.release()
        player = null
    }
}
