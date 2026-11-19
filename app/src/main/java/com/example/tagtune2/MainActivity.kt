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
    private lateinit var settingsScreen: LinearLayout
    private lateinit var status: TextView
    private lateinit var contentBox: LinearLayout
    private lateinit var foldersBox: LinearLayout
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

    data class Track(
        val uri: String, val title: String, val artist: String,
        val tags: List<String>, val coverPath: String? = null,
        val album: String = "Неизвестный альбом", val genre: String = ""
    )

    private var tracks = listOf<Track>()
    private val selected = mutableSetOf<String>()
    private var shown = listOf<Track>()
    private var currentTab = "menu"
    private var subType: String? = null
    private var subValue: String? = null
    private var trees = mutableSetOf<String>()
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
        val old = prefs().getString("tree_uri", null)
        trees = prefs().getStringSet("trees", null)?.toMutableSet() ?: mutableSetOf()
        if (old != null && trees.isEmpty()) { trees.add(old); prefs().edit().putStringSet("trees", trees).apply() }

        val root = LinearLayout(this)
        root.orientation = LinearLayout.VERTICAL

        /* ---------- ЭКРАН: МЕДИАТЕКА ---------- */
        libScreen = LinearLayout(this)
        libScreen.orientation = LinearLayout.VERTICAL
        libScreen.setPadding(dp(14), dp(14), dp(14), dp(10))

        val topRow = LinearLayout(this); topRow.orientation = LinearLayout.HORIZONTAL
        status = TextView(this); status.text = "TagTune2"
        status.setTextColor(Color.parseColor("#BBBBCC"))
        topRow.addView(status, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        val settingsBtn = Button(this); settingsBtn.text = "⚙"
        settingsBtn.setOnClickListener { showSettings() }
        topRow.addView(settingsBtn)
        libScreen.addView(topRow)

        val contentScroll = ScrollView(this)
        contentBox = LinearLayout(this); contentBox.orientation = LinearLayout.VERTICAL
        contentScroll.addView(contentBox)
        libScreen.addView(contentScroll, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))

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

        /* ---------- ЭКРАН: ПЛЕЕР ---------- */
        playerScreen = LinearLayout(this)
        playerScreen.orientation = LinearLayout.VERTICAL
        playerScreen.setPadding(dp(14), dp(14), dp(14), dp(14))
        playerScreen.visibility = View.GONE

        val backBtn = Button(this); backBtn.text = "⬅ Медиатека"
        backBtn.setOnClickListener { showLibrary() }
        playerScreen.addView(backBtn)

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

        /* ---------- ЭКРАН: НАСТРОЙКИ ---------- */
        settingsScreen = LinearLayout(this)
        settingsScreen.orientation = LinearLayout.VERTICAL
        settingsScreen.setPadding(dp(14), dp(14), dp(14), dp(14))
        settingsScreen.visibility = View.GONE

        val sBack = Button(this); sBack.text = "⬅ Медиатека"
        sBack.setOnClickListener { showLibrary() }
        settingsScreen.addView(sBack)
        val sTitle = TextView(this); sTitle.text = "⚙ Настройки"
        sTitle.textSize = 20f; sTitle.setTextColor(Color.parseColor("#EEEEF5"))
        sTitle.setPadding(0, dp(8), 0, dp(8))
        settingsScreen.addView(sTitle)
        val sLabel = TextView(this); sLabel.text = "Папки с музыкой:"
        sLabel.setTextColor(Color.parseColor("#BBBBCC"))
        settingsScreen.addView(sLabel)
        foldersBox = LinearLayout(this); foldersBox.orientation = LinearLayout.VERTICAL
        settingsScreen.addView(foldersBox)
        val addBtn = Button(this); addBtn.text = "＋ Добавить папку"
        addBtn.setOnClickListener { pickFolder() }
        settingsScreen.addView(addBtn)
        val rescanBtn = Button(this); rescanBtn.text = "🔄 Пересканировать"
        rescanBtn.setOnClickListener { startScan() }
        settingsScreen.addView(rescanBtn)

        root.addView(settingsScreen, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.MATCH_PARENT))

        setContentView(root)

        if (trees.isEmpty()) {
            status.text = "Первый запуск: выбери папку с музыкой"
            pickFolder()
        } else startScan()
    }

    /* ---------- навигация ---------- */

    private fun showPlayer() {
        libScreen.visibility = View.GONE
        settingsScreen.visibility = View.GONE
        playerScreen.visibility = View.VISIBLE
        val uri = PlayerState.uri
        if (uri != null && bigCoverUri != uri) loadBigCover(uri)
        refreshNow()
    }

    private fun showSettings() {
        libScreen.visibility = View.GONE
        playerScreen.visibility = View.GONE
        settingsScreen.visibility = View.VISIBLE
        renderFolders()
    }

    private fun showLibrary() {
        playerScreen.visibility = View.GONE
        settingsScreen.visibility = View.GONE
        libScreen.visibility = View.VISIBLE
    }

    override fun onBackPressed() {
        when {
            playerScreen.visibility == View.VISIBLE -> showLibrary()
            settingsScreen.visibility == View.VISIBLE -> showLibrary()
            libScreen.visibility == View.VISIBLE && currentTab != "menu" -> {
                currentTab = "menu"; subType = null; subValue = null; rebuildContent()
            }
            else -> super.onBackPressed()
        }
    }

    private fun renderFolders() {
        foldersBox.removeAllViews()
        if (trees.isEmpty()) {
            val none = TextView(this); none.text = "Папок пока нет — добавь первую."
            none.setTextColor(Color.parseColor("#BBBBCC"))
            foldersBox.addView(none)
            return
        }
        for (u in trees) {
            val row = LinearLayout(this); row.orientation = LinearLayout.HORIZONTAL
            val name = DocumentFile.fromTreeUri(this, Uri.parse(u))?.name ?: u
            val tv = TextView(this); tv.text = "📁 $name"
            tv.setTextColor(Color.parseColor("#EEEEF5"))
            tv.setPadding(0, dp(6), 0, dp(6))
            row.addView(tv, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            val del = Button(this); del.text = "✕"
            del.setOnClickListener {
                trees.remove(u)
                prefs().edit().putStringSet("trees", trees).apply()
                try { contentResolver.releasePersistableUriPermission(Uri.parse(u), Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION) } catch (e: Exception) { }
                renderFolders(); startScan()
            }
            row.addView(del)
            foldersBox.addView(row)
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
            trees.add(uri.toString())
            prefs().edit().putStringSet("trees", trees).apply()
            if (settingsScreen.visibility == View.VISIBLE) renderFolders()
            startScan()
        }
    }

    /* ---------- сканирование ---------- */

    private fun startScan() {
        status.text = "Сканирую папки..."
        Thread {
            try {
                val files = mutableListOf<DocumentFile>()
                for (u in trees) {
                    val tree = DocumentFile.fromTreeUri(this, Uri.parse(u)) ?: continue
                    walk(tree, files, 0)
                }
                val seen = mutableSetOf<String>()
                val out = mutableListOf<Track>()
                for (f in files) {
                    val key = f.uri.toString()
                    if (seen.add(key) && out.size < 500) out.add(readTrack(f))
                }
                tracks = out
                runOnUiThread {
                    rebuildContent()
                    status.text = "🎵 Треков: ${tracks.size} · папок: ${trees.size}"
                }
            } catch (t: Throwable) {
                val err = android.util.Log.getStackTraceString(t)
                runOnUiThread { status.text = "ОЙ! Споткнулись о камень:\n\n$err" }
            }
        }.start()
    }

    private fun walk(dir: DocumentFile?, out: MutableList<DocumentFile>, depth: Int) {
        if (dir == null || depth > 6 || out.size > 500) return
        for (f in dir.listFiles()) {
            if (f.isDirectory) walk(f, out, depth + 1)
            else if (f.name?.endsWith(".mp3", true) == true) out.add(f)
        }
    }

    private fun readTrack(f: DocumentFile): Track {
        var title = f.name?.removeSuffix(".mp3") ?: "Без названия"
        var artist = "Неизвестный исполнитель"
        var album = "Неизвестный альбом"
        var genre = ""
        try {
            val mmr = MediaMetadataRetriever()
            mmr.setDataSource(this, f.uri)
            mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_TITLE)?.let { title = it }
            mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ARTIST)?.let { artist = it }
            mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ALBUM)?.let { album = it }
            mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_GENRE)?.let { genre = it }
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
        return Track(f.uri.toString(), title, artist, tags, coverPath, album, genre)
    }

    /* ---------- контент медиатеки: меню списком ---------- */

    private fun rebuildContent() {
        contentBox.removeAllViews()
        if (currentTab == "menu") { addMenuRows(); highlightCurrent(); return }
        val back = Button(this); back.text = "⬅ Меню"
        back.tag = "header"
        back.setOnClickListener { currentTab = "menu"; subType = null; subValue = null; rebuildContent() }
        contentBox.addView(back)
        when (currentTab) {
            "tracks" -> { shown = tracks.sortedBy { it.title }; addTrackRows(shown, "Все треки") }
            "albums" -> if (subType == "album") addTrackRows(subTracks { it.album == subValue }, "Альбом: $subValue") else addGroups(tracks.groupBy { it.album }, "album", "💿")
            "artists" -> if (subType == "artist") addTrackRows(subTracks { it.artist == subValue }, "Исполнитель: $subValue") else addGroups(tracks.groupBy { it.artist }, "artist", "👤")
            "genres" -> if (subType == "genre") addTrackRows(subTracks { (it.genre.ifEmpty { "(без жанра)" }) == subValue }, "Жанр: $subValue") else addGroups(tracks.groupBy { it.genre.ifEmpty { "(без жанра)" } }, "genre", "🎼")
            "tags" -> addTagList()
        }
        highlightCurrent()
    }

    private fun addMenuRows() {
        shown = emptyList()
        val items = listOf(
            "tracks" to "🎵 Треки  (${tracks.size})",
            "albums" to "💿 Альбомы  (${tracks.map { it.album }.distinct().size})",
            "artists" to "👤 Исполнители  (${tracks.map { it.artist }.distinct().size})",
            "genres" to "🎼 Жанры  (${tracks.map { it.genre.ifEmpty { "(без жанра)" } }.distinct().size})",
            "tags" to "🏷 Теги  (${tracks.flatMap { it.tags }.distinct().size})"
        )
        for ((key, label) in items) {
            val row = TextView(this)
            row.text = label
            row.textSize = 17f
            row.setPadding(dp(14), dp(16), dp(14), dp(16))
            row.setBackgroundColor(Color.parseColor("#1E1E2A"))
            row.setTextColor(Color.parseColor("#EEEEF5"))
            val lp = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
            lp.setMargins(0, 0, 0, dp(8)); row.layoutParams = lp
            row.tag = "header"
            row.setOnClickListener { currentTab = key; subType = null; subValue = null; rebuildContent() }
            contentBox.addView(row)
        }
    }

    private fun subTracks(pred: (Track) -> Boolean): List<Track> = tracks.filter(pred).sortedBy { it.title }

    private fun addGroups(groups: Map<String, List<Track>>, type: String, icon: String) {
        shown = emptyList()
        if (groups.isEmpty()) { emptyMsg("Пока пусто."); return }
        for ((name, list) in groups.toSortedMap()) {
            val row = TextView(this)
            row.text = "$icon $name  (${list.size})"
            row.setPadding(dp(12), dp(12), dp(12), dp(12))
            row.setBackgroundColor(Color.parseColor("#1E1E2A"))
            row.setTextColor(Color.parseColor("#EEEEF5"))
            val lp = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
            lp.setMargins(0, 0, 0, dp(8)); row.layoutParams = lp
            row.tag = "group"
            row.setOnClickListener { subType = type; subValue = name; rebuildContent() }
            contentBox.addView(row)
        }
    }

    private fun addTagList() {
        val counts = mutableMapOf<String, Int>()
        for (t in tracks) for (g in t.tags) counts[g] = (counts[g] ?: 0) + 1
        if (counts.isEmpty()) {
            shown = emptyList()
            emptyMsg("Тегов пока нет. Долгое нажатие на трек — добавить теги.")
            return
        }
        // Алфавитный список тегов, без всяких облаков
        for (tag in counts.keys.sorted()) {
            val row = TextView(this)
            row.text = "#$tag  (${counts[tag]})"
            row.setPadding(dp(12), dp(12), dp(12), dp(12))
            val on = selected.contains(tag)
            row.setBackgroundColor(if (on) Color.parseColor("#3A2A5A") else Color.parseColor("#1E1E2A"))
            row.setTextColor(if (on) Color.parseColor("#EEEEF5") else Color.parseColor("#BBBBCC"))
            val lp = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
            lp.setMargins(0, 0, 0, dp(8)); row.layoutParams = lp
            row.tag = "header"
            row.setOnClickListener {
                if (selected.contains(tag)) selected.remove(tag) else selected.add(tag)
                rebuildContent()
            }
            contentBox.addView(row)
        }
        val filtered = tracks.filter { t -> selected.all { g -> t.tags.contains(g) } }
        shown = filtered.shuffled()
        val h = TextView(this)
        h.text = if (selected.isEmpty()) "Плейлист: все треки (${shown.size}), шафл"
                 else "Пересечение ${selected.joinToString(" ∩ ") { "#$it" }}: ${shown.size}, шафл"
        h.setTextColor(Color.parseColor("#BBBBCC"))
        h.setPadding(0, dp(8), 0, dp(8))
        h.tag = "header"
        contentBox.addView(h)
        for (t in shown) addTrackRow(t)
        if (shown.isEmpty()) emptyMsg("Пересечение пусто: сними часть тегов.")
    }

    private fun addTrackRows(list: List<Track>, header: String) {
        shown = list
        val h = TextView(this); h.text = header
        h.setTextColor(Color.parseColor("#BBBBCC"))
        h.setPadding(0, dp(4), 0, dp(8))
        h.tag = "header"
        contentBox.addView(h)
        if (subType != null) {
            val back = Button(this); back.text = "⬅ Ко всем разделам"
            back.tag = "header"
            back.setOnClickListener { subType = null; subValue = null; rebuildContent() }
            contentBox.addView(back)
        }
        if (list.isEmpty()) { emptyMsg("Здесь пусто."); return }
        for (t in list) addTrackRow(t)
    }

    private fun emptyMsg(msg: String) {
        val empty = TextView(this)
        empty.text = msg
        empty.setPadding(0, dp(16), 0, 0)
        empty.tag = "header"
        contentBox.addView(empty)
    }

    private fun addTrackRow(t: Track) {
        val rowL = LinearLayout(this)
        rowL.orientation = LinearLayout.HORIZONTAL
        rowL.setPadding(dp(10), dp(8), dp(10), dp(8))
        rowL.setBackgroundColor(Color.parseColor("#1E1E2A"))
        rowL.tag = "track:" + t.uri

        val iv = ImageView(this)
        iv.scaleType = ImageView.ScaleType.CENTER_CROP
        iv.setBackgroundColor(Color.parseColor("#2A2A3A"))
        val bm = loadBitmap(t.coverPath, 96)
        if (bm != null) iv.setImageBitmap(bm)
        rowL.addView(iv, LinearLayout.LayoutParams(dp(46), dp(46)))

        val txt = TextView(this)
        txt.text = "🎵 ${t.title}\n👤 ${t.artist} · 💿 ${t.album}\n🏷 " +
            (if (t.tags.isEmpty()) "нет тегов" else t.tags.joinToString(" ") { "#$it" })
        txt.setPadding(dp(10), dp(2), 0, dp(2))
        txt.setTextColor(Color.parseColor("#EEEEF5"))
        rowL.addView(txt, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))

        val lp = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
        lp.setMargins(0, 0, 0, dp(8)); rowL.layoutParams = lp
        rowL.setOnClickListener { play(t) }
        rowL.setOnLongClickListener { openEditor(t); true }
        contentBox.addView(rowL)
    }

    private fun highlightCurrent() {
        val cur = "track:" + PlayerState.uri
        for (i in 0 until contentBox.childCount) {
            val v = contentBox.getChildAt(i)
            val tag = v.tag as? String ?: continue
            if (tag.startsWith("track:")) {
                v.setBackgroundColor(if (tag == cur) Color.parseColor("#3A2A5A") else Color.parseColor("#1E1E2A"))
            }
        }
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
                        refresh(); rebuildContent(); refreshNow()
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
                    refresh(); rebuildContent(); refreshNow()
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
                msg = "Не записалось: ${e.javaClass.simpleName}: ${e.message}\nЕсли SecurityException — добавь папку заново в ⚙ Настройках (нужно право записи)."
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

    /* ---------- плеер ---------- */

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
