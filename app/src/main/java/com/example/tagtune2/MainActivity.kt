package com.example.tagtune2

import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.media.MediaMetadataRetriever
import android.media.MediaPlayer
import android.net.Uri
import android.os.Bundle
import android.widget.Button
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.documentfile.provider.DocumentFile
import com.mpatric.mp3agic.Mp3File
import com.example.tagtune2.utils.TagParser
import java.io.File
import java.io.FileOutputStream

class MainActivity : Activity() {

    private val REQ_TREE = 202
    private lateinit var status: TextView
    private lateinit var pickBtn: Button
    private lateinit var chipsRow: LinearLayout
    private lateinit var nowText: TextView
    private lateinit var playBtn: Button
    private lateinit var listBox: LinearLayout

    data class Track(val uri: String, val title: String, val artist: String, val tags: List<String>)

    private var tracks = listOf<Track>()
    private val selected = mutableSetOf<String>()
    private var shown = listOf<Track>()
    private var player: MediaPlayer? = null
    private var current: Track? = null

    private fun dp(n: Int) = (resources.displayMetrics.density * n).toInt()
    private fun prefs() = getSharedPreferences("tagtune2", MODE_PRIVATE)
    private fun savedTree(): String? = prefs().getString("tree_uri", null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val root = LinearLayout(this)
        root.orientation = LinearLayout.VERTICAL
        root.setPadding(dp(14), dp(14), dp(14), dp(14))

        status = TextView(this)
        status.text = "TagTune2: привет!"
        root.addView(status)

        pickBtn = Button(this)
        pickBtn.text = "📁 Выбрать папку с музыкой"
        pickBtn.setOnClickListener { pickFolder() }
        root.addView(pickBtn)

        val chipScroll = HorizontalScrollView(this)
        chipsRow = LinearLayout(this)
        chipsRow.orientation = LinearLayout.HORIZONTAL
        chipScroll.addView(chipsRow)
        root.addView(chipScroll)

        nowText = TextView(this)
        nowText.text = "Ничего не играет."
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

        if (savedTree() != null) startScan()
        else status.text = "TagTune2: выбери папку, где лежат твои MP3.\nЗаписи разговоров и прочий мусор останутся за бортом."
    }

    private fun pickFolder() {
        val i = Intent(Intent.ACTION_OPEN_DOCUMENT_TREE)
        i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)
        startActivityForResult(i, REQ_TREE)
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQ_TREE && resultCode == RESULT_OK && data?.data != null) {
            val uri = data.data!!
            try {
                contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            } catch (e: Exception) { /* некоторые провайдеры не дают persist — не страшно */ }
            prefs().edit().putString("tree_uri", uri.toString()).apply()
            startScan()
        }
    }

    private fun startScan() {
        status.text = "TagTune2: сканирую выбранную папку..."
        Thread {
            try {
                val tree = DocumentFile.fromTreeUri(this, Uri.parse(savedTree()!!))
                val files = mutableListOf<DocumentFile>()
                walk(tree, files, 0)
                val out = mutableListOf<Track>()
                for (f in files.take(300)) out.add(readTrack(f))
                tracks = out
                runOnUiThread {
                    rebuildChips(); rebuildList()
                    status.text = "🎵 В папке найдено MP3: ${tracks.size}. Теги — фильтр, трек — игра."
                }
            } catch (t: Throwable) {
                val err = android.util.Log.getStackTraceString(t)
                runOnUiThread { status.text = "ОЙ! Споткнулись о камень:\n\n$err" }
            }
        }.start()
    }

    private fun walk(dir: DocumentFile?, out: MutableList<DocumentFile>, depth: Int) {
        if (dir == null || depth > 6 || out.size > 300) return
        for (f in dir.listFiles()) {
            if (f.isDirectory) walk(f, out, depth + 1)
            else if (f.name?.endsWith(".mp3", true) == true) out.add(f)
        }
    }

    private fun readTrack(f: DocumentFile): Track {
        var title = f.name?.removeSuffix(".mp3") ?: "Без названия"
        var artist = "Неизвестный исполнитель"
        try {
            val mmr = MediaMetadataRetriever()
            mmr.setDataSource(this, f.uri)
            mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_TITLE)?.let { title = it }
            mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ARTIST)?.let { artist = it }
            mmr.release()
        } catch (e: Exception) { }
        var tags = emptyList<String>()
        try {
            // mp3agic умеет читать только файлы: делаем временную копию потока
            val tmp = File(cacheDir, "tagprobe.mp3")
            contentResolver.openInputStream(f.uri)?.use { ins ->
                FileOutputStream(tmp).use { ins.copyTo(it) }
            }
            val mp3 = Mp3File(tmp.absolutePath)
            if (mp3.hasId3v2Tag()) tags = TagParser.extractTags(mp3.id3v2Tag.comment)
            tmp.delete()
        } catch (e: Exception) { }
        return Track(f.uri.toString(), title, artist, tags)
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
        val filtered = tracks.filter { t -> selected.all { g -> t.tags.contains(g) } }
        shown = filtered.shuffled()
        listBox.removeAllViews()
        if (shown.isEmpty()) {
            val empty = TextView(this)
            empty.text = if (tracks.isEmpty()) "В выбранной папке нет MP3. Подложи файлы с тегами и нажми «📁 Папка» → выбери её снова (или перезапусти)."
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
            row.setBackgroundColor(if (current?.uri == t.uri) Color.parseColor("#3A2A5A") else Color.parseColor("#1E1E2A"))
            row.setTextColor(Color.parseColor("#EEEEF5"))
            val lp = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
            lp.setMargins(0, 0, 0, dp(8))
            row.layoutParams = lp
            row.setOnClickListener { play(t) }
            listBox.addView(row)
        }
    }

    private fun play(t: Track) {
        current = t
        nowText.text = "▶ Играет: ${t.title} — ${t.artist}"
        try {
            player?.release()
            player = MediaPlayer().apply {
                setDataSource(this@MainActivity, Uri.parse(t.uri))
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
        val i = shown.indexOfFirst { it.uri == current?.uri }
        play(shown[(i + 1) % shown.size])
    }

    override fun onDestroy() {
        super.onDestroy()
        player?.release()
        player = null
    }
}
