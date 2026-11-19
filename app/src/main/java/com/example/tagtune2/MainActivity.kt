package com.example.tagtune2

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.MultiAutoCompleteTextView
import android.widget.ScrollView
import android.widget.TextView
import androidx.documentfile.provider.DocumentFile
import com.mpatric.mp3agic.ID3v24Tag
import com.mpatric.mp3agic.Mp3File
import com.example.tagtune2.utils.TagParser
import java.io.File
import java.io.FileOutputStream

class MainActivity : Activity() {

    private val REQ_TREE = 202
    private lateinit var libScreen: LinearLayout
    private lateinit var playerScreen: LinearLayout
    private lateinit var status: TextView
    private lateinit var pickBtn: Button
    private lateinit var chipsRow: LinearLayout
    private lateinit var listBox: LinearLayout
    private lateinit var nbCover: ImageView
    private lateinit var nbText: TextView
    private lateinit var nbPlay: Button
    private lateinit var npCover: ImageView
    private lateinit var pTitle: TextView
    private lateinit var pArtist: TextView
    private lateinit var pTagsRow: LinearLayout
    private lateinit var tCur: TextView
    private lateinit var tDur: TextView
    private lateinit var pPlay: Button

    data class Track(val uri: String, val title: String, val artist: String, val tags: List<String>, val coverPath: String? = null)

    private var tracks = listOf<Track>()
    private val selected = mutableSetOf<String>()
    private var shown = listOf<Track>()
    private var bigCoverBm: Bitmap? = null
    private var bigCoverUri: String? = null
    private val handler = Handler(Looper.getMainLooper())
    private val listener: () -> Unit = { runOnUiThread { refreshNow(); highlightCurrent() } }
    private val poll = object : Runnable {
        override fun run() {
            val p = PlayerService.instance
            if (p != null && PlayerState.uri != null) {
                val pos = p.currentPosition(); val dur = PlayerState.durationMs
                tCur.text = fmt(pos.toLong()); tDur.text = fmt(dur.toLong())
            }
            handler.postDelayed(this, 400)
        }
    }

    private fun dp(n: Int) = (resources.displayMetrics.density * n).toInt()
    private fun prefs() = getSharedPreferences("tagtune2", MODE_PRIVATE)
    private fun savedTree(): String? = prefs().getString("tree_uri", null)
    private fun allTags(): List<String> = tracks.flatMap { it.tags }.distinct().sorted()
    private fun fmt(ms: Long): String { val s = ms / 1000; return "${s / 60}:${(s % 60).toString().padStart(2, '0')}" }
    private fun curTrack(): Track? = tracks.find { it.uri == PlayerState.uri }

    private fun loadBitmap(path: String?, target: Int): Bitmap? {
        if (path == null) return null
        val f = File(path)
        if (!f.exists()) return null
        return try {
            val opts = BitmapFactory.Options()
            opts.inJustDecodeBounds = true
            BitmapFactory.decodeFile(path, opts)
            var s = 1
            while (opts.outWidth / (s * 2) >= target) s *= 2
            opts.inJustDecodeBounds = false
            opts.inSampleSize = s
            BitmapFactory.decodeFile(path, opts)
        } catch (e: Exception) { null }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(android.Manifest.permission.POST_NOTIFICATIONS), 303)
        }
        val root = LinearLayout(this)
        root.orientation = LinearLayout.VERTICAL

        /* ---------- ЭКРАН 1: МЕДИАТЕКА ---------- */
        libScreen = LinearLayout(this)
        libScreen.orientation = LinearLayout.VERTICAL
        libScreen.setPadding(dp(14), dp(14), dp(14), dp(10))

        status = TextView(this); status.text = "TagTune2: привет!"; libScreen.addView(status)

        pickBtn = Button(this); pickBtn.text = "📁 Выбрать папку с музыкой"
        pickBtn.setOnClickListener { pickFolder() }; libScreen.addView(pickBtn)

        val chipScroll = HorizontalScrollView(this)
        chipsRow = LinearLayout(this); chipsRow.orientation = LinearLayout.HORIZONTAL
        chipScroll.addView(chipsRow); libScreen.addView(chipScroll)

        val listScroll = ScrollView(this)
        listBox = LinearLayout(this); listBox.orientation = LinearLayout.VERTICAL
        listScroll.addView(listBox)
        libScreen.addView(listScroll, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))

        val nowBar = LinearLayout(this)
        nowBar.orientation = LinearLayout.HORIZONTAL
        nowBar.setBackgroundColor(Color.parseColor("#241638"))
        nowBar.setPadding(dp(8), dp(8), dp(8), dp(8))
        nowBar.setOnClickListener { showPlayer() }
        nbCover = ImageView(this); nbCover.scaleType = ImageView.ScaleType.CENTER_CROP
        nbCover.setBackgroundColor(Color.parseColor("#2A2A3A"))
        nowBar.addView(nbCover, LinearLayout.LayoutParams(dp(46), dp(46)))
        nbText = TextView(this); nbText.text = "Ничего не играет"
        nbText.setTextColor(Color.parseColor("#EEEEF5")); nbText.maxLines = 2
        nbText.setPadding(dp(10), 0, 0, 0)
        nowBar.addView(nbText, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        nbPlay = Button(this); nbPlay.text = "▶"
        nbPlay.setOnClickListener { PlayerService.cmd(this, "toggle") }
        nowBar.addView(nbPlay)
        val nbOpen = Button(this); nbOpen.text = "⏫"
        nbOpen.setOnClickListener { showPlayer() }
        nowBar.addView(nbOpen)
        libScreen.addView(nowBar)

        root.addView(libScreen, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.MATCH_PARENT))

        /* ---------- ЭКРАН 2: ПЛЕЕР ---------- */
        playerScreen = LinearLayout(this)
        playerScreen.orientation = LinearLayout.VERTICAL
        playerScreen.setPadding(dp(14), dp(14), dp(14), dp(14))
        playerScreen.visibility = View.GONE

        val topRow = LinearLayout(this); topRow.orientation = LinearLayout.HORIZONTAL
        val backBtn = Button(this); backBtn.text = "⬅ Медиатека"
        backBtn.setOnClickListener { showLibrary() }
        topRow.addView(backBtn)
        playerScreen.addView(topRow)

        npCover = ImageView(this)
        npCover.scaleType = ImageView.ScaleType.CENTER_CROP
        npCover.setBackgroundColor(Color.parseColor("#1E1E2A"))
        playerScreen.addView(npCover, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(220)))

        pTitle = TextView(this); pTitle.text = "Ничего не играет"
        pTitle.textSize = 20f; pTitle.setTextColor(Color.parseColor("#EEEEF5"))
        pTitle.setPadding(0, dp(10), 0, 0)
        playerScreen.addView(pTitle)
        pArtist = TextView(this); pArtist.setTextColor(Color.parseColor("#BBBBCC"))
        playerScreen.addView(pArtist)

        val pTagsScroll = HorizontalScrollView(this)
        pTagsRow = LinearLayout(this); pTagsRow.orientation = LinearLayout.HORIZONTAL
        pTagsScroll.addView(pTagsRow); playerScreen.addView(pTagsScroll)

        val times = LinearLayout(this); times.orientation = LinearLayout.HORIZONTAL
        tCur = TextView(this); tCur.text = "0:00"
        val spacer = TextView(this)
        tDur = TextView(this); tDur.text = "0:00"
        times.addView(tCur); times.addView(spacer, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)); times.addView(tDur)
        playerScreen.addView(times)

        val transport = LinearLayout(this); transport.orientation = LinearLayout.HORIZONTAL
        val prevBtn = Button(this); prevBtn.text = "⏮"; prevBtn.setOnClickListener { PlayerService.cmd(this, "prev") }
        pPlay = Button(this); pPlay.text = "▶"; pPlay.setOnClickListener { PlayerService.cmd(this, "toggle") }
        val nextBtn = Button(this); nextBtn.text = "⏭"; nextBtn.setOnClickListener { PlayerService.cmd(this, "next") }
        val stopBtn = Button(this); stopBtn.text = "⏹"; stopBtn.setOnClickListener { PlayerService.cmd(this, "stop") }
        transport.addView(prevBtn); transport.addView(pPlay, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)); transport.addView(nextBtn); transport.addView(stopBtn)
        playerScreen.addView(transport)

        root.addView(playerScreen, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.MATCH_PARENT))

        setContentView(root)
        if (savedTree() != null) startScan()
        else status.text = "TagTune2: выбери папку, где лежат твои MP3."
    }

    private fun showPlayer() {
        libScreen.visibility = View.GONE
        playerScreen.visibility = View.VISIBLE
        val uri = PlayerState.uri
        if (uri != null && bigCoverUri != uri) loadBigCover(uri)
        refreshNow()
    }

    private fun showLibrary() {
        playerScreen.visibility = View.GONE
        libScreen.visibility = View.VISIBLE
    }

    override fun onBackPressed() {
        if (playerScreen.visibility == View.VISIBLE) showLibrary() else super.onBackPressed()
    }

    private fun loadBigCover(uri: String) {
        bigCoverUri = uri
        val path = curTrack()?.coverPath
        Thread {
            val bm = loadBitmap(path, 640)
            runOnUiThread {
                bigCoverBm = bm
                if (bigCoverUri == uri && playerScreen.visibility == View.VISIBLE) {
                    if (bm != null) npCover.setImageBitmap(bm) else npCover.setImageResource(0)
                }
            }
        }.start()
    }

    override fun onResume() {
        super.onResume()
        PlayerState.listeners.add(listener)
        handler.post(poll)
        refreshNow(); highlightCurrent()
    }

    override fun onPause() {
        super.onPause()
        PlayerState.listeners.remove(listener)
        handler.removeCallbacks(poll)
    }

    private fun refreshNow() {
        val playing = PlayerState.playing
        val label = when {
            PlayerState.error != null -> "⚠ " + PlayerState.error
            PlayerState.uri == null -> "Ничего не играет"
            else -> PlayerState.title + " — " + PlayerState.artist
        }
        nbText.text = label
        nbPlay.text = if (playing) "⏸" else "▶"
        pTitle.text = if (PlayerState.error != null) "⚠ " + PlayerState.error else PlayerState.title.ifEmpty { "Ничего не играет" }
        pArtist.text = PlayerState.artist
        pPlay.text = if (playing) "⏸" else "▶"
        if (PlayerState.uri == null) { nbCover.setImageResource(0); npCover.setImageResource(0); bigCoverUri = null }
        syncPlayerTags()
    }

    private fun syncPlayerTags() {
        val t = curTrack()
        pTagsRow.removeAllViews()
        if (t == null) return
        for (g in t.tags) {
            val chip = TextView(this)
            chip.text = "#$g"
            chip.setPadding(dp(12), dp(6), dp(12), dp(6))
            val lp = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT)
            lp.setMargins(0, 0, dp(8), 0); chip.layoutParams = lp
            chip.setBackgroundColor(Color.parseColor("#2A2A3A"))
            chip.setTextColor(Color.parseColor("#BBBBCC"))
            pTagsRow.addView(chip)
        }
    }

    private fun highlightCurrent() {
        if (listBox.childCount != shown.size) return
        for (i in shown.indices) {
            listBox.getChildAt(i)?.setBackgroundColor(
                if (shown[i].uri == PlayerState.uri) Color.parseColor("#3A2A5A") else Color.parseColor("#1E1E2A")
            )
        }
    }

    private fun pickFolder() {
        val i = Intent(Intent.ACTION_OPEN_DOCUMENT_TREE)
        i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION or Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)
        startActivityForResult(i, REQ_TREE)
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQ_TREE && resultCode == RESULT_OK && data?.data != null) {
            val uri = data.data!!
            try {
                contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
            } catch (e: Exception) { }
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
                    status.text = "🎵 MP3 в папке: ${tracks.size}. Тап — игра, долгое нажатие — редактор тегов."
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
        var coverPath: String? = null
        try {
            val tmp = File(cacheDir, "tagprobe.mp3")
            contentResolver.openInputStream(f.uri)?.use { ins -> FileOutputStream(tmp).use { ins.copyTo(it) } }
            val mp3 = Mp3File(tmp.absolutePath)
            if (mp3.hasId3v2Tag()) {
                tags = TagParser.extractTags(mp3.id3v2Tag.comment)
                val img = mp3.id3v2Tag.albumImage
                if (img != null && img.isNotEmpty()) {
                    val cf = File(cacheDir, "cov_" + Integer.toHexString(f.uri.toString().hashCode()) + ".img")
                    cf.writeBytes(img)
                    coverPath = cf.absolutePath
                }
            }
            tmp.delete()
        } catch (e: Exception) { }
        return Track(f.uri.toString(), title, artist, tags, coverPath)
    }

    /* ---------- РЕДАКТОР ТЕГОВ ---------- */

    private fun openEditor(t: Track) {
        val box = LinearLayout(this)
        box.orientation = LinearLayout.VERTICAL
        box.setPadding(dp(16), dp(8), dp(16), 0)
        val info = TextView(this); box.addView(info)
        val scroll = HorizontalScrollView(this)
        val row = LinearLayout(this); row.orientation = LinearLayout.HORIZONTAL
        scroll.addView(row); box.addView(scroll)
        val input = MultiAutoCompleteTextView(this)
        input.hint = "#"; input.threshold = 1
        input.setTokenizer(object : MultiAutoCompleteTextView.Tokenizer {
            override fun findTokenStart(text: CharSequence, cursor: Int): Int {
                var i = cursor
                while (i > 0 && !text[i - 1].isWhitespace() && text[i - 1] != ',') i--
                return i
            }
            override fun findTokenEnd(text: CharSequence, cursor: Int): Int {
                var i = cursor
                while (i < text.length && !text[i].isWhitespace() && text[i] != ',') i++
                return i
            }
            override fun terminateToken(text: CharSequence): CharSequence = "$text "
        })
        box.addView(input)
        val dialog = AlertDialog.Builder(this)
            .setTitle(t.title).setView(box)
            .setPositiveButton("＋ Добавить", null).setNegativeButton("Закрыть", null).create()
        fun refresh() {
            val cur = tracks.find { it.uri == t.uri } ?: t
            row.removeAllViews()
            for (g in cur.tags) {
                val chip = TextView(this)
                chip.text = "#$g ✕"
                chip.setPadding(dp(12), dp(8), dp(12), dp(8))
                val lp = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT)
                lp.setMargins(0, 0, dp(8), 0); chip.layoutParams = lp
                chip.setBackgroundColor(Color.parseColor("#3A2A5A")); chip.setTextColor(Color.WHITE)
                chip.setOnClickListener {
                    info.text = "Убираю #$g…"
                    writeTag(t, g, false) { ok, msg ->
                        info.text = if (ok) "Готово: #$g убран." else msg
                        refresh(); rebuildChips(); rebuildList(); refreshNow()
                    }
                }
                row.addView(chip)
            }
            input.setAdapter(ArrayAdapter(this, android.R.layout.simple_dropdown_item_1line, allTags()))
        }
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val tags = input.text.toString().split(Regex("[\\s,]+"))
                    .map { it.trim().removePrefix("#").lowercase() }.filter { it.isNotEmpty() }.distinct()
                if (tags.isEmpty()) return@setOnClickListener
                info.text = "Записываю: " + tags.joinToString(" ") { "#$it" }
                writeTags(t, tags, true) { ok, msg ->
                    info.text = if (ok) "Готово: " + tags.joinToString(" ") { "#$it" } else msg
                    if (ok) input.setText("")
                    refresh(); rebuildChips(); rebuildList(); refreshNow()
                }
            }
        }
        dialog.show(); refresh()
    }

    private fun writeTag(t: Track, tag: String, add: Boolean, cb: (Boolean, String) -> Unit) =
        writeTags(t, listOf(tag), add, cb)

    private fun writeTags(t: Track, tags: List<String>, add: Boolean, cb: (Boolean, String) -> Unit) {
        Thread {
            var ok = false; var msg = "ок"; var newTags: List<String>? = null
            try {
                val uri = Uri.parse(t.uri)
                val inF = File(cacheDir, "edit_in.mp3"); val outF = File(cacheDir, "edit_out.mp3")
                contentResolver.openInputStream(uri)!!.use { ins -> FileOutputStream(inF).use { ins.copyTo(it) } }
                val mp3 = Mp3File(inF.absolutePath)
                if (!mp3.hasId3v2Tag()) mp3.id3v2Tag = ID3v24Tag()
                val old = mp3.id3v2Tag.comment ?: ""
                val new = if (add) tags.fold(old) { acc, g -> TagParser.addTagToComment(acc, g) }
                          else old.split("\n").filter { line -> tags.none { g -> line.trim().lowercase() == "#$g" } }.joinToString("\n")
                mp3.id3v2Tag.comment = new
                mp3.save(outF.absolutePath)
                contentResolver.openOutputStream(uri, "wt")!!.use { os -> outF.inputStream().use { it.copyTo(os) } }
                newTags = TagParser.extractTags(new)
                inF.delete(); outF.delete(); ok = true
            } catch (e: Exception) {
                msg = "Не записалось: ${e.javaClass.simpleName}: ${e.message}\nЕсли SecurityException — нажми «📁 Папка» и выбери папку заново (нужно право записи)."
            }
            runOnUiThread {
                if (ok && newTags != null) {
                    val nt = newTags!!
                    tracks = tracks.map { if (it.uri == t.uri) it.copy(tags = nt) else it }
                }
                cb(ok, msg)
            }
        }.start()
    }

    /* ---------- СПИСОК И ФИЛЬТРЫ ---------- */

    private fun rebuildChips() {
        chipsRow.removeAllViews()
        val counts = mutableMapOf<String, Int>()
        for (t in tracks) for (g in t.tags) counts[g] = (counts[g] ?: 0) + 1
        for ((tag, n) in counts.entries.sortedByDescending { it.value }) {
            val chip = TextView(this)
            chip.text = "#$tag ($n)"
            chip.setPadding(dp(12), dp(8), dp(12), dp(8))
            val lp = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT)
            lp.setMargins(0, 0, dp(8), 0); chip.layoutParams = lp
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
            empty.text = if (tracks.isEmpty()) "В выбранной папке нет MP3."
                         else "Пересечение пусто: ни один трек не несёт все выбранные теги. Сними тег."
            empty.setPadding(0, dp(16), 0, 0)
            listBox.addView(empty)
            return
        }
        for (t in shown) {
            val rowL = LinearLayout(this)
            rowL.orientation = LinearLayout.HORIZONTAL
            rowL.setPadding(dp(10), dp(8), dp(10), dp(8))
            rowL.setBackgroundColor(Color.parseColor("#1E1E2A"))

            val iv = ImageView(this)
            iv.scaleType = ImageView.ScaleType.CENTER_CROP
            iv.setBackgroundColor(Color.parseColor("#2A2A3A"))
            val bm = loadBitmap(t.coverPath, 96)
            if (bm != null) iv.setImageBitmap(bm)
            rowL.addView(iv, LinearLayout.LayoutParams(dp(46), dp(46)))

            val txt = TextView(this)
            txt.text = "🎵 ${t.title}\n👤 ${t.artist}\n🏷 " +
                (if (t.tags.isEmpty()) "нет тегов" else t.tags.joinToString(" ") { "#$it" })
            txt.setPadding(dp(10), dp(2), 0, dp(2))
            txt.setTextColor(Color.parseColor("#EEEEF5"))
            rowL.addView(txt, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))

            val lp = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
            lp.setMargins(0, 0, 0, dp(8)); rowL.layoutParams = lp
            rowL.setOnClickListener { play(t) }
            rowL.setOnLongClickListener { openEditor(t); true }
            listBox.addView(rowL)
        }
        highlightCurrent()
    }

    /* ---------- ПЛЕЕР: команды сервису ---------- */

    private fun play(t: Track) {
        val items = ArrayList(shown.map { QItem(it.uri, it.title, it.artist) })
        PlayerService.cmd(this, "play", items, shown.indexOf(t))
        bigCoverUri = null; bigCoverBm = null
        Thread {
            val small = loadBitmap(t.coverPath, 96)
            runOnUiThread { nbCover.setImageBitmap(small) }
        }.start()
        if (playerScreen.visibility == View.VISIBLE) loadBigCover(t.uri)
        highlightCurrent()
    }
}
