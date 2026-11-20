package com.example.tagtune2

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.Outline
import android.graphics.drawable.GradientDrawable
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.LruCache
import android.view.View
import android.view.ViewGroup
import android.view.ViewOutlineProvider
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.MultiAutoCompleteTextView
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.TextView
import androidx.documentfile.provider.DocumentFile
import com.mpatric.mp3agic.ID3v24Tag
import com.mpatric.mp3agic.Mp3File
import com.example.tagtune2.utils.TagParser
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.util.concurrent.Executors

/* ---------- потоковая раскладка с лимитом строк ---------- */
class FlowLayout(context: Context) : ViewGroup(context) {
    var maxRows = 0
    var overflowCount = 0

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val width = MeasureSpec.getSize(widthMeasureSpec)
        overflowCount = 0
        var posX = paddingLeft; var posY = paddingTop; var rowH = 0; var rowNum = 1
        for (i in 0 until childCount) {
            val c = getChildAt(i)
            if (c.visibility == GONE) continue
            measureChild(c, widthMeasureSpec, heightMeasureSpec)
            val lp = c.layoutParams as MarginLayoutParams
            val cw = c.measuredWidth + lp.leftMargin + lp.rightMargin
            val ch = c.measuredHeight + lp.topMargin + lp.bottomMargin
            if (posX + cw > width - paddingRight && posX > paddingLeft) {
                rowNum++
                if (maxRows > 0 && rowNum > maxRows) { c.visibility = GONE; overflowCount++; continue }
                posX = paddingLeft; posY += rowH; rowH = 0
            }
            posX += cw; rowH = maxOf(rowH, ch)
        }
        setMeasuredDimension(width, posY + rowH + paddingBottom)
    }

    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        val width = r - l
        var posX = paddingLeft; var posY = paddingTop; var rowH = 0
        for (i in 0 until childCount) {
            val c = getChildAt(i)
            if (c.visibility == GONE) continue
            val lp = c.layoutParams as MarginLayoutParams
            val cw = c.measuredWidth + lp.leftMargin + lp.rightMargin
            val ch = c.measuredHeight + lp.topMargin + lp.bottomMargin
            if (posX + cw > width - paddingRight && posX > paddingLeft) { posX = paddingLeft; posY += rowH; rowH = 0 }
            c.layout(posX + lp.leftMargin, posY + lp.topMargin, posX + lp.leftMargin + c.measuredWidth, posY + lp.topMargin + c.measuredHeight)
            posX += cw; rowH = maxOf(rowH, ch)
        }
    }

    override fun generateLayoutParams(attrs: android.util.AttributeSet?): LayoutParams = MarginLayoutParams(context, attrs)
    override fun generateDefaultLayoutParams(): LayoutParams = MarginLayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT)
    override fun generateLayoutParams(p: LayoutParams?): LayoutParams = MarginLayoutParams(p)
    override fun checkLayoutParams(p: LayoutParams?): Boolean = p is MarginLayoutParams
}

/* ---------- читает только ID3-шапку: теги, обложка, метаданные, текст ---------- */
object Id3Reader {
    data class Result(
        val comment: String?, val cover: ByteArray?,
        val title: String?, val artist: String?, val album: String?,
        val genre: String?, val durationMs: Long, val lyrics: String?
    )

    fun read(ins: InputStream): Result? {
        val hdr = ByteArray(10)
        if (ins.read(hdr) != 10) return null
        if (hdr[0].toInt().toChar() != 'I' || hdr[1].toInt().toChar() != 'D' || hdr[2].toInt().toChar() != '3') return null
        val ver = hdr[3].toInt() and 0xFF
        val size = synch(hdr[6], hdr[7], hdr[8], hdr[9])
        if (size <= 0 || size > 32 * 1024 * 1024) return null
        val tag = ByteArray(size)
        var off = 0
        while (off < size) {
            val r = ins.read(tag, off, size - off)
            if (r <= 0) break
            off += r
        }
        var comment: String? = null; var cover: ByteArray? = null
        var title: String? = null; var artist: String? = null
        var album: String? = null; var genre: String? = null
        var lyrics: String? = null
        var dur = 0L
        var p = 0
        while (p + 10 <= size) {
            if (ver >= 3) {
                val id = String(tag, p, 4, Charsets.ISO_8859_1)
                if (!id[0].isLetterOrDigit()) break
                val fsz = if (ver == 4) synch(tag[p + 4], tag[p + 5], tag[p + 6], tag[p + 7])
                          else int4(tag[p + 4], tag[p + 5], tag[p + 6], tag[p + 7])
                p += 10
                if (fsz <= 0 || p + fsz > size) break
                val body = tag.copyOfRange(p, p + fsz)
                p += fsz
                when (id) {
                    "COMM" -> if (comment == null) comment = decodeComm(body)
                    "APIC" -> if (cover == null) cover = decodeApic(body, false)
                    "USLT" -> if (lyrics == null) lyrics = decodeComm(body)
                    "TIT2" -> if (title == null) title = decodeTextFrame(body)
                    "TPE1" -> if (artist == null) artist = decodeTextFrame(body)
                    "TALB" -> if (album == null) album = decodeTextFrame(body)
                    "TCON" -> if (genre == null) genre = decodeTextFrame(body)
                    "TLEN" -> decodeTextFrame(body)?.toLongOrNull()?.let { dur = it }
                }
            } else if (ver == 2) {
                val id = String(tag, p, 3, Charsets.ISO_8859_1)
                if (!id[0].isLetterOrDigit()) break
                val fsz = ((tag[p + 3].toInt() and 0xFF) shl 16) or ((tag[p + 4].toInt() and 0xFF) shl 8) or (tag[p + 5].toInt() and 0xFF)
                p += 6
                if (fsz <= 0 || p + fsz > size) break
                val body = tag.copyOfRange(p, p + fsz)
                p += fsz
                when (id) {
                    "COM" -> if (comment == null) comment = decodeComm(body)
                    "PIC" -> if (cover == null) cover = decodeApic(body, true)
                    "TT2" -> if (title == null) title = decodeTextFrame(body)
                    "TP1" -> if (artist == null) artist = decodeTextFrame(body)
                    "TAL" -> if (album == null) album = decodeTextFrame(body)
                    "TCO" -> if (genre == null) genre = decodeTextFrame(body)
                }
            } else break
        }
        return Result(comment, cover, title, artist, album, genre, dur, lyrics)
    }

    private fun synch(a: Byte, b: Byte, c: Byte, d: Byte) =
        ((a.toInt() and 0x7F) shl 21) or ((b.toInt() and 0x7F) shl 14) or ((c.toInt() and 0x7F) shl 7) or (d.toInt() and 0x7F)

    private fun int4(a: Byte, b: Byte, c: Byte, d: Byte) =
        ((a.toInt() and 0xFF) shl 24) or ((b.toInt() and 0xFF) shl 16) or ((c.toInt() and 0xFF) shl 8) or (d.toInt() and 0xFF)

    private fun skipTerminator(b: ByteArray, from: Int, enc: Int): Int {
        if (enc == 1 || enc == 2) {
            var i = from
            while (i + 1 < b.size) { if (b[i].toInt() == 0 && b[i + 1].toInt() == 0) return i + 2; i += 2 }
            return b.size
        }
        var i = from
        while (i < b.size) { if (b[i].toInt() == 0) return i + 1; i++ }
        return b.size
    }

    private fun decodeText(b: ByteArray, from: Int, to: Int, enc: Int): String = try {
        val cs = when (enc) { 1 -> Charsets.UTF_16; 2 -> Charsets.UTF_16BE; 3 -> Charsets.UTF_8; else -> Charsets.ISO_8859_1 }
        String(b, from, maxOf(0, to - from), cs).trimEnd(' ')
    } catch (e: Exception) { "" }

    private fun decodeTextFrame(body: ByteArray): String? {
        if (body.isEmpty()) return null
        return decodeText(body, 1, body.size, body[0].toInt() and 0xFF)
    }

    private fun decodeComm(body: ByteArray): String? {
        if (body.size < 5) return null
        val enc = body[0].toInt() and 0xFF
        return decodeText(body, skipTerminator(body, 4, enc), body.size, enc)
    }

    private fun decodeApic(body: ByteArray, v2: Boolean): ByteArray? {
        if (body.size < 4) return null
        val enc = body[0].toInt() and 0xFF
        var i = 1
        if (v2) i += 3 else { while (i < body.size && body[i].toInt() != 0) i++; i++ }
        i += 1
        i = skipTerminator(body, i, enc)
        if (i >= body.size) return null
        return body.copyOfRange(i, body.size)
    }
}

class MainActivity : Activity() {

    private val REQ_TREE = 202
    private val ACCENT = Color.parseColor("#FF4D6D")
    private val GRAY = Color.parseColor("#8D81A3")
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
    private lateinit var pAlbum: TextView
    private lateinit var pFormat: TextView
    private lateinit var pTagsFlow: FlowLayout
    private lateinit var seekBar: SeekBar
    private lateinit var tCur: TextView
    private lateinit var tDur: TextView
    private lateinit var pPlay: Button
    private lateinit var shufBtn: TextView
    private lateinit var repBtn: TextView
    private lateinit var queueCounter: TextView

    data class Track(
        val uri: String, val title: String, val artist: String,
        val tags: List<String>, val coverPath: String? = null,
        val album: String = "Неизвестный альбом", val genre: String = "",
        val durationMs: Long = 0, val bitrateKbps: Int = 0, val lyrics: String? = null
    )

    private var tracks = listOf<Track>()
    private val selected = mutableSetOf<String>()
    private var shown = listOf<Track>()
    private var currentTab = "menu"
    private var subType: String? = null
    private var subValue: String? = null
    private var trees = mutableSetOf<String>()
    private var visibleLimit = 100
    private var bigCoverBm: Bitmap? = null
    private var bigCoverUri: String? = null
    private var lastPlayerUri: String? = null
    private var seeking = false
    private val thumbCache = object : LruCache<String, Bitmap>(80) {}
    private val thumbPool = Executors.newFixedThreadPool(2)
    private val handler = Handler(Looper.getMainLooper())
    private val listener: () -> Unit = { runOnUiThread { refreshNow(); highlightCurrent() } }
    private val poll = object : Runnable {
        override fun run() {
            val p = PlayerService.instance
            if (p != null && PlayerState.uri != null) {
                val pos = p.currentPosition(); val dur = PlayerState.durationMs
                tCur.text = fmt(pos.toLong()); tDur.text = fmt(dur.toLong())
                if (!seeking && dur > 0) seekBar.progress = (pos / 1000).coerceIn(0, dur.toInt() / 1000)
            }
            handler.postDelayed(this, 400)
        }
    }

    private fun dp(n: Int) = (resources.displayMetrics.density * n).toInt()
    private fun prefs() = getSharedPreferences("tagtune2", MODE_PRIVATE)
    private fun allTags(): List<String> = tracks.flatMap { it.tags }.distinct().sorted()
    private fun fmt(ms: Long): String { val s = ms / 1000; return "${s / 60}:${(s % 60).toString().padStart(2, '0')}" }
    private fun curTrack(): Track? = tracks.find { it.uri == PlayerState.uri }
    private fun cacheFile() = File(filesDir, "library_cache.json")

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

    private fun bindThumb(iv: ImageView, path: String?) {
        if (path == null) { iv.setImageBitmap(null); iv.setBackgroundColor(Color.parseColor("#2A2A3A")); return }
        val cached = thumbCache.get(path)
        if (cached != null) { iv.setImageBitmap(cached); return }
        iv.setImageBitmap(null); iv.setBackgroundColor(Color.parseColor("#2A2A3A")); iv.tag = path
        thumbPool.execute {
            val bm = loadBitmap(path, 96)
            if (bm != null) thumbCache.put(path, bm)
            runOnUiThread { if (iv.tag as? String == path) iv.setImageBitmap(bm) }
        }
    }

    private fun pillBg(color: Int, radiusDp: Int): GradientDrawable =
        GradientDrawable().apply { setColor(color); cornerRadius = dp(radiusDp).toFloat() }

    /* ---------- кэш библиотеки ---------- */

    private fun saveLibraryCache() {
        try {
            val arr = JSONArray()
            for (t in tracks) {
                val o = JSONObject()
                o.put("uri", t.uri); o.put("title", t.title); o.put("artist", t.artist)
                o.put("album", t.album); o.put("genre", t.genre); o.put("dur", t.durationMs)
                o.put("br", t.bitrateKbps)
                if (t.lyrics != null) o.put("lyr", t.lyrics) else o.put("lyr", JSONObject.NULL)
                o.put("tags", JSONArray(t.tags))
                if (t.coverPath != null) o.put("cover", t.coverPath) else o.put("cover", JSONObject.NULL)
                arr.put(o)
            }
            cacheFile().writeText(arr.toString())
        } catch (e: Exception) { }
    }

    private fun loadLibraryCache(): List<Track> = try {
        val arr = JSONArray(cacheFile().readText())
        val out = mutableListOf<Track>()
        for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i)
            val tags = mutableListOf<String>()
            val ta = o.optJSONArray("tags")
            if (ta != null) for (j in 0 until ta.length()) tags.add(ta.getString(j))
            val cover = if (o.isNull("cover")) null else o.getString("cover")
            val lyr = if (o.isNull("lyr")) null else o.getString("lyr")
            out.add(Track(
                o.getString("uri"), o.getString("title"), o.getString("artist"), tags, cover,
                o.optString("album", "Неизвестный альбом"), o.optString("genre", ""),
                o.optLong("dur", 0), o.optInt("br", 0), lyr
            ))
        }
        out
    } catch (e: Exception) { emptyList() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(android.Manifest.permission.POST_NOTIFICATIONS), 303)
        }
        val oldTree = prefs().getString("tree_uri", null)
        trees = prefs().getStringSet("trees", null)?.toMutableSet() ?: mutableSetOf()
        if (oldTree != null && trees.isEmpty()) { trees.add(oldTree); prefs().edit().putStringSet("trees", trees).apply() }

        val root = LinearLayout(this)
        root.orientation = LinearLayout.VERTICAL
        root.setBackgroundColor(Color.parseColor("#0D0A12"))

        /* ---------- ЭКРАН: МЕДИАТЕКА ---------- */
        libScreen = LinearLayout(this)
        libScreen.orientation = LinearLayout.VERTICAL
        libScreen.setPadding(dp(14), dp(14), dp(14), dp(10))

        val topRow = LinearLayout(this); topRow.orientation = LinearLayout.HORIZONTAL
        val headTitle = TextView(this); headTitle.text = "Медиатека"
        headTitle.textSize = 26f; headTitle.setTextColor(Color.parseColor("#EEEEF5"))
        topRow.addView(headTitle, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        val settingsBtn = Button(this); settingsBtn.text = "⚙"
        settingsBtn.setOnClickListener { showSettings() }
        topRow.addView(settingsBtn)
        libScreen.addView(topRow)

        status = TextView(this); status.textSize = 13f
        status.setTextColor(Color.parseColor("#BBBBCC"))
        status.visibility = View.GONE
        libScreen.addView(status)

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
        playerScreen.setPadding(dp(14), dp(10), dp(14), dp(14))
        playerScreen.visibility = View.GONE

        val pTop = LinearLayout(this); pTop.orientation = LinearLayout.HORIZONTAL
        pTop.gravity = android.view.Gravity.CENTER_VERTICAL
        val pBack = TextView(this); pBack.text = "←"; pBack.textSize = 20f
        pBack.setTextColor(Color.parseColor("#EEEEF5"))
        pBack.gravity = android.view.Gravity.CENTER
        pBack.background = pillBg(Color.parseColor("#241638"), 99)
        pBack.setPadding(dp(14), dp(8), dp(14), dp(8))
        pBack.setOnClickListener { showLibrary() }
        pTop.addView(pBack)
        queueCounter = TextView(this); queueCounter.textSize = 13f
        queueCounter.setTextColor(GRAY)
        queueCounter.gravity = android.view.Gravity.CENTER
        pTop.addView(queueCounter, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        val pSpacer = TextView(this); pSpacer.text = "←"; pSpacer.textSize = 20f
        pSpacer.visibility = View.INVISIBLE
        pSpacer.setPadding(dp(14), dp(8), dp(14), dp(8))
        pTop.addView(pSpacer)
        playerScreen.addView(pTop)

        npCover = ImageView(this)
        npCover.scaleType = ImageView.ScaleType.CENTER_CROP
        npCover.setBackgroundColor(Color.parseColor("#1E1E2A"))
        npCover.clipToOutline = true
        npCover.outlineProvider = object : ViewOutlineProvider() {
            override fun getOutline(v: View, o: Outline) {
                o.setRoundRect(0, 0, v.width, v.height, dp(16).toFloat())
            }
        }
        val covSize = resources.displayMetrics.widthPixels - dp(28) - dp(40)
        val covLp = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, covSize)
        covLp.setMargins(dp(20), dp(6), dp(20), dp(6))
        npCover.layoutParams = covLp
        playerScreen.addView(npCover)

        pTitle = TextView(this); pTitle.text = "Ничего не играет"
        pTitle.textSize = 22f; pTitle.setTextColor(Color.parseColor("#EEEEF5"))
        pTitle.gravity = android.view.Gravity.CENTER
        pTitle.setPadding(0, dp(12), 0, 0)
        playerScreen.addView(pTitle)

        pArtist = TextView(this); pArtist.setTextColor(ACCENT)
        pArtist.textSize = 17f; pArtist.gravity = android.view.Gravity.CENTER
        playerScreen.addView(pArtist)

        pAlbum = TextView(this); pAlbum.setTextColor(Color.parseColor("#9A93A8"))
        pAlbum.textSize = 15f; pAlbum.gravity = android.view.Gravity.CENTER
        playerScreen.addView(pAlbum)

        val fmtRow = LinearLayout(this); fmtRow.orientation = LinearLayout.HORIZONTAL
        fmtRow.gravity = android.view.Gravity.CENTER
        fmtRow.setPadding(0, dp(8), 0, 0)
        pFormat = TextView(this); pFormat.textSize = 13f
        pFormat.setTextColor(Color.parseColor("#9A93A8"))
        fmtRow.addView(pFormat)
        playerScreen.addView(fmtRow)

        pTagsFlow = FlowLayout(this)
        pTagsFlow.maxRows = 2
        pTagsFlow.setPadding(dp(6), dp(10), dp(6), 0)
        playerScreen.addView(pTagsFlow)

        seekBar = SeekBar(this)
        seekBar.progressTintList = ColorStateList.valueOf(ACCENT)
        seekBar.thumbTintList = ColorStateList.valueOf(ACCENT)
        seekBar.setPadding(dp(4), dp(14), dp(4), 0)
        seekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar?, progress: Int, fromUser: Boolean) {
                if (fromUser) tCur.text = fmt(progress * 1000L)
            }
            override fun onStartTrackingTouch(sb: SeekBar?) { seeking = true }
            override fun onStopTrackingTouch(sb: SeekBar?) {
                seeking = false
                PlayerService.cmd(this@MainActivity, "seek", seekMs = sb!!.progress * 1000)
            }
        })
        playerScreen.addView(seekBar)

        val times = LinearLayout(this); times.orientation = LinearLayout.HORIZONTAL
        tCur = TextView(this); tCur.text = "0:00"; tCur.setTextColor(Color.parseColor("#9A93A8"))
        val timeSpacer = TextView(this)
        tDur = TextView(this); tDur.text = "0:00"; tDur.setTextColor(Color.parseColor("#9A93A8"))
        times.addView(tCur); times.addView(timeSpacer, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)); times.addView(tDur)
        playerScreen.addView(times)

        val transport = LinearLayout(this); transport.orientation = LinearLayout.HORIZONTAL
        transport.gravity = android.view.Gravity.CENTER_VERTICAL
        transport.setPadding(0, dp(10), 0, 0)
        shufBtn = TextView(this); shufBtn.text = "⇆"; shufBtn.textSize = 22f
        shufBtn.gravity = android.view.Gravity.CENTER
        shufBtn.setOnClickListener { PlayerService.cmd(this, "toggleShuffle") }
        transport.addView(shufBtn, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        val prevBtn = TextView(this); prevBtn.text = "⏮"; prevBtn.textSize = 24f
        prevBtn.setTextColor(Color.parseColor("#EEEEF5"))
        prevBtn.gravity = android.view.Gravity.CENTER
        prevBtn.setOnClickListener { PlayerService.cmd(this, "prev") }
        transport.addView(prevBtn, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        pPlay = Button(this); pPlay.text = "▶"
        pPlay.setTextColor(Color.WHITE); pPlay.textSize = 24f
        pPlay.background = GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT, intArrayOf(ACCENT, Color.parseColor("#7C4DFF"))).apply { shape = GradientDrawable.OVAL }
        pPlay.setOnClickListener { PlayerService.cmd(this, "toggle") }
        val playLp = LinearLayout.LayoutParams(dp(76), dp(76))
        playLp.setMargins(dp(10), 0, dp(10), 0)
        transport.addView(pPlay, playLp)
        val nextBtn = TextView(this); nextBtn.text = "⏭"; nextBtn.textSize = 24f
        nextBtn.setTextColor(Color.parseColor("#EEEEF5"))
        nextBtn.gravity = android.view.Gravity.CENTER
        nextBtn.setOnClickListener { PlayerService.cmd(this, "next") }
        transport.addView(nextBtn, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        repBtn = TextView(this); repBtn.text = "⟳"; repBtn.textSize = 22f
        repBtn.gravity = android.view.Gravity.CENTER
        repBtn.setOnClickListener { PlayerService.cmd(this, "toggleRepeat") }
        transport.addView(repBtn, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        playerScreen.addView(transport)

        val extraRow = LinearLayout(this); extraRow.orientation = LinearLayout.HORIZONTAL
        extraRow.setPadding(0, dp(14), 0, 0)
        val qBtn = Button(this); qBtn.text = "☰ Список"; qBtn.setTextColor(Color.parseColor("#EEEEF5"))
        qBtn.isAllCaps = false; qBtn.textSize = 14f
        qBtn.background = pillBg(Color.parseColor("#241638"), 10)
        qBtn.setOnClickListener { openQueueDialog() }
        val qLp = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f); qLp.setMargins(dp(3), 0, dp(3), 0)
        extraRow.addView(qBtn, qLp)
        val lBtn = Button(this); lBtn.text = "❝ Текст"; lBtn.setTextColor(Color.parseColor("#EEEEF5"))
        lBtn.isAllCaps = false; lBtn.textSize = 14f
        lBtn.background = pillBg(Color.parseColor("#241638"), 10)
        lBtn.setOnClickListener { openLyricsDialog() }
        val lLp = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f); lLp.setMargins(dp(3), 0, dp(3), 0)
        extraRow.addView(lBtn, lLp)
        val tgBtn = Button(this); tgBtn.text = "# Теги"; tgBtn.setTextColor(Color.parseColor("#EEEEF5"))
        tgBtn.isAllCaps = false; tgBtn.textSize = 14f
        tgBtn.background = pillBg(Color.parseColor("#241638"), 10)
        tgBtn.setOnClickListener { curTrack()?.let { openEditor(it) } }
        val tLp = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f); tLp.setMargins(dp(3), 0, dp(3), 0)
        extraRow.addView(tgBtn, tLp)
        playerScreen.addView(extraRow)

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

        tracks = loadLibraryCache()
        if (tracks.isNotEmpty()) rebuildContent()

        if (trees.isEmpty()) {
            status.visibility = View.VISIBLE
            status.text = "Первый запуск: выбери папку с музыкой"
            pickFolder()
        } else startScan()
    }

    /* ---------- навигация ---------- */

    private fun showPlayer() {
        libScreen.visibility = View.GONE
        settingsScreen.visibility = View.GONE
        playerScreen.visibility = View.VISIBLE
        lastPlayerUri = null
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

    private fun openQueueDialog() {
        val scroll = ScrollView(this)
        val box = LinearLayout(this); box.orientation = LinearLayout.VERTICAL
        box.setPadding(dp(16), dp(8), dp(16), dp(8))
        scroll.addView(box)
        val items = PlayerState.queueItems
        if (items.isEmpty()) {
            val none = TextView(this); none.text = "Очередь пуста."
            box.addView(none)
        }
        for ((pos, q) in items.withIndex()) {
            val row = TextView(this)
            val isCur = pos + 1 == PlayerState.queuePos
            row.text = "${pos + 1}. ${q.title} — ${q.artist}" + (if (isCur) "  ▶" else "")
            row.setTextColor(if (isCur) ACCENT else Color.parseColor("#EEEEF5"))
            row.setPadding(dp(6), dp(10), dp(6), dp(10))
            row.setOnClickListener { PlayerService.cmd(this, "play", ArrayList(items), pos) }
            box.addView(row)
        }
        AlertDialog.Builder(this)
            .setTitle("Список воспроизведения")
            .setView(scroll)
            .setNegativeButton("Закрыть", null)
            .show()
    }

    private fun openLyricsDialog() {
        val scroll = ScrollView(this)
        val tv = TextView(this)
        tv.text = curTrack()?.lyrics ?: "Текст не найден в метаданных файла."
        tv.setTextColor(Color.parseColor("#EEEEF5"))
        tv.setPadding(dp(16), dp(8), dp(16), dp(16))
        tv.setLineSpacing(dp(4).toFloat(), 1f)
        scroll.addView(tv)
        AlertDialog.Builder(this)
            .setTitle("Текст: " + PlayerState.title)
            .setView(scroll)
            .setNegativeButton("Закрыть", null)
            .show()
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
        status.visibility = View.VISIBLE
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
                    if (seen.add(key) && out.size < 5000) out.add(readTrack(f))
                }
                tracks = out
                saveLibraryCache()
                runOnUiThread {
                    status.visibility = View.GONE
                    rebuildContent()
                }
            } catch (ex: Throwable) {
                val err = android.util.Log.getStackTraceString(ex)
                runOnUiThread {
                    status.visibility = View.VISIBLE
                    status.text = "ОЙ! Споткнулись о камень:\n\n$err"
                }
            }
        }.start()
    }

    private fun walk(dir: DocumentFile?, out: MutableList<DocumentFile>, depth: Int) {
        if (dir == null || depth > 8 || out.size > 5000) return
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
        var durationMs = 0L
        var tags = emptyList<String>()
        var coverPath: String? = null
        var lyrics: String? = null
        try {
            contentResolver.openInputStream(f.uri)?.use { ins ->
                val r = Id3Reader.read(ins)
                if (r != null) {
                    r.title?.let { if (it.isNotBlank()) title = it }
                    r.artist?.let { if (it.isNotBlank()) artist = it }
                    r.album?.let { if (it.isNotBlank()) album = it }
                    r.genre?.let { if (it.isNotBlank()) genre = it }
                    durationMs = r.durationMs
                    lyrics = r.lyrics
                    tags = r.comment?.let { TagParser.extractTags(it) } ?: emptyList()
                    r.cover?.let { bytes ->
                        if (bytes.isNotEmpty()) {
                            val cf = File(cacheDir, "cov_" + Integer.toHexString(f.uri.toString().hashCode()) + ".img")
                            cf.writeBytes(bytes)
                            coverPath = cf.absolutePath
                        }
                    }
                }
            }
        } catch (e: Exception) { }
        if (durationMs == 0L) {
            try {
                val mmr = MediaMetadataRetriever()
                mmr.setDataSource(this, f.uri)
                mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull()?.let { durationMs = it }
                mmr.release()
            } catch (e: Exception) { }
        }
        var bitrate = 0
        if (durationMs > 0) {
            val len = runCatching { f.length() }.getOrDefault(0L)
            if (len > 0) bitrate = (len * 8 / durationMs).toInt()
        }
        return Track(f.uri.toString(), title, artist, tags, coverPath, album, genre, durationMs, bitrate, lyrics)
    }

    /* ---------- контент медиатеки ---------- */

    private fun rebuildContent(resetLimit: Boolean = true) {
        if (resetLimit) visibleLimit = 100
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

    private fun addPaged(list: List<Track>) {
        for (t in list.take(visibleLimit)) addTrackRow(t)
        if (list.size > visibleLimit) {
            val more = Button(this)
            more.text = "Показать ещё (${list.size - visibleLimit} осталось)"
            more.tag = "header"
            more.setOnClickListener { visibleLimit += 200; rebuildContent(false) }
            contentBox.addView(more)
        }
    }

    private fun addMenuRows() {
        val totalMin = tracks.sumOf { it.durationMs } / 60000
        val durStr = if (totalMin >= 60) "${totalMin / 60} ч ${totalMin % 60} мин" else "$totalMin мин"
        val tagCount = tracks.flatMap { it.tags }.distinct().size

        val items = listOf(
            Triple("tracks", "🎵 Треки", "${tracks.size} треков · $durStr"),
            Triple("albums", "💿 Альбомы", "${tracks.map { it.album }.distinct().size} альбомов"),
            Triple("artists", "👤 Исполнители", "${tracks.map { it.artist }.distinct().size} исполнителей"),
            Triple("genres", "🎼 Жанры", "${tracks.map { it.genre.ifEmpty { "(без жанра)" } }.distinct().size} жанров"),
            Triple("tags", "🏷 Теги", "$tagCount тегов" + (if (selected.isNotEmpty()) " · выбрано: ${selected.size}" else ""))
        )
        for ((key, lbl, sub) in items) {
            val row = LinearLayout(this)
            row.orientation = LinearLayout.VERTICAL
            row.setPadding(dp(16), dp(18), dp(16), dp(18))
            row.setBackgroundColor(Color.parseColor("#1E1E2A"))
            val t1 = TextView(this); t1.text = lbl
            t1.textSize = 19f; t1.setTextColor(Color.parseColor("#EEEEF5"))
            val t2 = TextView(this); t2.text = sub
            t2.setTextColor(Color.parseColor("#BBBBCC"))
            t2.setPadding(0, dp(4), 0, 0)
            row.addView(t1); row.addView(t2)
            val lp = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
            lp.setMargins(0, 0, 0, dp(10)); row.layoutParams = lp
            row.tag = "header"
            row.setOnClickListener { currentTab = key; subType = null; subValue = null; rebuildContent() }
            contentBox.addView(row)
        }

        val untagged = tracks.filter { it.tags.isEmpty() }.sortedBy { it.title }
        shown = untagged
        if (untagged.isNotEmpty()) {
            val h = TextView(this)
            h.text = "🧹 Без тегов (${untagged.size}) — долгое нажатие, чтобы повесить хештеги:"
            h.setTextColor(Color.parseColor("#BBBBCC"))
            h.setPadding(0, dp(6), 0, dp(8))
            h.tag = "header"
            contentBox.addView(h)
            addPaged(untagged)
        }

        val passport = TextView(this)
        passport.text = "Всего: ${tracks.size} треков · $durStr · папок: ${trees.size} · тегов: $tagCount"
        passport.textSize = 12f
        passport.setTextColor(Color.parseColor("#8D81A3"))
        passport.setPadding(dp(4), dp(6), dp(4), 0)
        passport.tag = "header"
        contentBox.addView(passport)
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
        val base = tracks.filter { t -> selected.all { g -> t.tags.contains(g) } }
        val counts = mutableMapOf<String, Int>()
        for (t in base) for (g in t.tags) counts[g] = (counts[g] ?: 0) + 1
        for (g in selected) if (!counts.containsKey(g)) counts[g] = 0

        if (counts.isEmpty()) {
            shown = emptyList()
            emptyMsg("Тегов пока нет. Долгое нажатие на трек — добавить теги.")
            return
        }

        val ordered = selected.toList() + counts.keys.sorted().filter { !selected.contains(it) }
        val flow = FlowLayout(this)
        flow.tag = "header"
        for (tag in ordered) {
            val chip = TextView(this)
            chip.text = "#$tag (${counts[tag]})"
            chip.setPadding(dp(10), dp(8), dp(10), dp(8))
            val on = selected.contains(tag)
            chip.setBackgroundColor(if (on) Color.parseColor("#7C4DFF") else Color.parseColor("#2A2A3A"))
            chip.setTextColor(if (on) Color.WHITE else Color.parseColor("#BBBBCC"))
            val lp = ViewGroup.MarginLayoutParams(ViewGroup.MarginLayoutParams.WRAP_CONTENT, ViewGroup.MarginLayoutParams.WRAP_CONTENT)
            lp.setMargins(0, 0, dp(6), dp(6)); chip.layoutParams = lp
            chip.tag = "header"
            chip.setOnClickListener {
                if (selected.contains(tag)) selected.remove(tag) else selected.add(tag)
                rebuildContent()
            }
            flow.addView(chip)
        }
        contentBox.addView(flow)

        shown = base.shuffled()
        val h = TextView(this)
        h.text = if (selected.isEmpty()) "Плейлист: все треки (${shown.size}), шафл"
                 else "Пересечение ${selected.joinToString(" ∩ ") { "#$it" }}: ${shown.size}, шафл"
        h.setTextColor(Color.parseColor("#BBBBCC"))
        h.setPadding(0, dp(8), 0, dp(8))
        h.tag = "header"
        contentBox.addView(h)
        addPaged(shown)
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
        addPaged(list)
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
        bindThumb(iv, t.coverPath)
        rowL.addView(iv, LinearLayout.LayoutParams(dp(46), dp(46)))

        val txt = TextView(this)
        txt.text = "🎵 ${t.title}\n👤 ${t.artist} · 💿 ${t.album}\n🏷 " +
            (if (t.tags.isEmpty()) "нет тегов" else t.tags.joinToString(" ") { "#$it" }) + " · ⏱ ${fmt(t.durationMs)}"
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

        val flow = FlowLayout(this)
        box.addView(flow)

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
            val curT = tracks.find { it.uri == t.uri } ?: t
            flow.removeAllViews()
            if (curT.tags.isEmpty()) {
                val none = TextView(this); none.text = "тегов нет"
                none.setTextColor(Color.parseColor("#8D81A3"))
                flow.addView(none)
            }
            for (g in curT.tags) {
                val chip = TextView(this)
                chip.text = "#$g ✕"
                chip.setPadding(dp(10), dp(8), dp(10), dp(8))
                val lp = ViewGroup.MarginLayoutParams(ViewGroup.MarginLayoutParams.WRAP_CONTENT, ViewGroup.MarginLayoutParams.WRAP_CONTENT)
                lp.setMargins(0, 0, dp(6), dp(6)); chip.layoutParams = lp
                chip.setBackgroundColor(Color.parseColor("#3A2A5A")); chip.setTextColor(Color.WHITE)
                chip.setOnClickListener {
                    info.text = "Убираю #$g…"
                    writeTag(t, g, false) { ok, msg ->
                        info.text = if (ok) "Готово: #$g убран." else msg
                        refresh(); rebuildContent(); refreshNow()
                    }
                }
                flow.addView(chip)
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
                val currentTags = TagParser.extractTags(old)
                val nonTagLines = old.split("\n").map { it.trimEnd() }
                    .filter { it.trim().isNotEmpty() && !it.trim().startsWith("#") }
                val resulting = if (add) (currentTags + tags).distinct()
                                else currentTags.filter { !tags.contains(it) }
                val new = (nonTagLines + resulting.map { "#$it" }).joinToString("\n")
                mp3.id3v2Tag.comment = new
                mp3.save(outF.absolutePath)
                contentResolver.openOutputStream(uri, "wt")!!.use { os -> outF.inputStream().use { it.copyTo(os) } }
                newTags = TagParser.extractTags(new)
                inF.delete(); outF.delete(); ok = true
                saveLibraryCache()
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

    /* ---------- плеер: мета и обновление ---------- */

    private fun rebuildPlayerMeta() {
        val t = curTrack()
        pTitle.text = if (PlayerState.error != null) "⚠ " + PlayerState.error else PlayerState.title.ifEmpty { "Ничего не играет" }
        pArtist.text = PlayerState.artist
        pAlbum.text = t?.album ?: ""
        pFormat.text = if (t != null && t.bitrateKbps > 0) "MP3 · ${t.bitrateKbps} kbps · ${fmt(t.durationMs)}"
                       else if (t != null) "MP3 · ${fmt(t.durationMs)}" else ""
        pTagsFlow.removeAllViews()
        val tags = t?.tags ?: emptyList()
        for (g in tags) {
            val chip = TextView(this)
            chip.text = "#$g"
            chip.setPadding(dp(12), dp(7), dp(12), dp(7))
            chip.background = pillBg(Color.parseColor("#241638"), 99)
            chip.setTextColor(Color.parseColor("#FF9FB0"))
            val lp = ViewGroup.MarginLayoutParams(ViewGroup.MarginLayoutParams.WRAP_CONTENT, ViewGroup.MarginLayoutParams.WRAP_CONTENT)
            lp.setMargins(0, 0, dp(6), dp(6)); chip.layoutParams = lp
            chip.setOnClickListener {
                selected.clear(); selected.add(g)
                currentTab = "tags"; subType = null; subValue = null
                showLibrary(); rebuildContent()
            }
            pTagsFlow.addView(chip)
        }
        val moreChip = TextView(this)
        moreChip.text = "…"
        moreChip.setPadding(dp(10), dp(7), dp(10), dp(7))
        moreChip.setTextColor(Color.parseColor("#9A93A8"))
        pTagsFlow.addView(moreChip)
        pTagsFlow.post {
            if (pTagsFlow.overflowCount == 0) { moreChip.visibility = View.GONE; pTagsFlow.requestLayout() }
            else moreChip.text = "+${pTagsFlow.overflowCount}"
        }
        val dur = PlayerState.durationMs
        seekBar.max = (dur.toInt() / 1000).coerceAtLeast(1)
        if (PlayerState.uri != null) loadBigCover(PlayerState.uri!!)
    }

    private fun loadBigCover(uri: String) {
        bigCoverUri = uri
        val path = curTrack()?.coverPath
        Thread {
            val bm = loadBitmap(path, 720)
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
        pPlay.text = if (playing) "⏸" else "▶"
        shufBtn.setTextColor(if (PlayerState.shuffleOn) ACCENT else GRAY)
        repBtn.text = if (PlayerState.repeatMode == 2) "⟳¹" else "⟳"
        repBtn.setTextColor(if (PlayerState.repeatMode > 0) ACCENT else GRAY)
        queueCounter.text = if (PlayerState.queueSize > 0)
            "Трек ${PlayerState.queuePos} из ${PlayerState.queueSize}" + (if (PlayerState.shuffleOn) " · шафл" else "") else ""
        if (PlayerState.uri == null) {
            nbCover.setImageResource(0); npCover.setImageResource(0)
            bigCoverUri = null; lastPlayerUri = null
        }
        if (PlayerState.uri != lastPlayerUri) {
            lastPlayerUri = PlayerState.uri
            rebuildPlayerMeta()
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
        if (playerScreen.visibility == View.VISIBLE) { lastPlayerUri = null; refreshNow() }
        highlightCurrent()
    }
}
