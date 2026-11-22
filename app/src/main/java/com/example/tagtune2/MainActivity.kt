package com.example.tagtune2

import android.app.Activity
import android.app.AlertDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorFilter
import android.graphics.Outline
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.StateListDrawable
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.text.Editable
import android.text.SpannableString
import android.text.Spanned
import android.text.TextUtils
import android.text.TextWatcher
import android.text.style.BackgroundColorSpan
import android.util.LruCache
import android.view.GestureDetector
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.ViewOutlineProvider
import android.view.WindowManager
import android.view.inputmethod.InputMethodManager
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.MultiAutoCompleteTextView
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.documentfile.provider.DocumentFile
import com.example.tagtune2.utils.TagParser
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.io.PrintWriter
import java.io.StringWriter
import java.util.Date
import java.util.concurrent.Executors
import kotlin.math.abs

class PressCircleDrawable(private val color: Int) : Drawable() {
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    init { p.color = color }
    override fun draw(c: Canvas) {
        val r = minOf(bounds.width(), bounds.height()) / 2f
        c.drawCircle(bounds.exactCenterX(), bounds.exactCenterY(), r, p)
    }
    override fun setAlpha(alpha: Int) { p.alpha = alpha }
    override fun setColorFilter(cf: ColorFilter?) { p.colorFilter = cf }
    override fun getOpacity(): Int = PixelFormat.TRANSLUCENT
}

class FlowLayout(context: android.content.Context) : ViewGroup(context) {
    var maxRows = 0
    var overflowCount = 0
    var centerRows = false
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
        val hMode = MeasureSpec.getMode(heightMeasureSpec)
        val hSize = MeasureSpec.getSize(heightMeasureSpec)
        val height = if (hMode == MeasureSpec.EXACTLY) hSize else posY + rowH + paddingBottom
        setMeasuredDimension(width, height)
    }
    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        val width = r - l
        val rows = mutableListOf<MutableList<View>>()
        var cur = mutableListOf<View>()
        var posX = paddingLeft
        for (i in 0 until childCount) {
            val c = getChildAt(i)
            if (c.visibility == GONE) continue
            val lp = c.layoutParams as MarginLayoutParams
            val cw = c.measuredWidth + lp.leftMargin + lp.rightMargin
            if (posX + cw > width - paddingRight && posX > paddingLeft) {
                rows.add(cur); cur = mutableListOf(); posX = paddingLeft
            }
            cur.add(c); posX += cw
        }
        rows.add(cur)
        var posY = paddingTop
        for (row in rows) {
            var rowW = 0; var rowH = 0
            for (c in row) {
                val lp = c.layoutParams as MarginLayoutParams
                rowW += c.measuredWidth + lp.leftMargin + lp.rightMargin
                rowH = maxOf(rowH, c.measuredHeight + lp.topMargin + lp.bottomMargin)
            }
            var cx = if (centerRows) maxOf(paddingLeft, (width - rowW) / 2) else paddingLeft
            for (c in row) {
                val lp = c.layoutParams as MarginLayoutParams
                val top = posY + lp.topMargin
                c.layout(cx + lp.leftMargin, top, cx + lp.leftMargin + c.measuredWidth, top + c.measuredHeight)
                cx += c.measuredWidth + lp.leftMargin + lp.rightMargin
            }
            posY += rowH
        }
    }
    override fun generateLayoutParams(attrs: android.util.AttributeSet?): LayoutParams = MarginLayoutParams(context, attrs)
    override fun generateDefaultLayoutParams(): LayoutParams = MarginLayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT)
    override fun generateLayoutParams(p: LayoutParams?): LayoutParams = MarginLayoutParams(p)
    override fun checkLayoutParams(p: LayoutParams?): Boolean = p is MarginLayoutParams
}

object Id3Reader {
    data class Result(
        val comment: String?, val cover: ByteArray?,
        val title: String?, val artist: String?, val album: String?,
        val genre: String?, val durationMs: Long, val lyrics: String?, val year: String?
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
        var lyrics: String? = null; var year: String? = null
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
                val body = tag.copyOfRange(p, p + fsz); p += fsz
                when (id) {
                    "COMM" -> if (comment == null) comment = decodeComm(body)
                    "APIC" -> if (cover == null) cover = decodeApic(body, false)
                    "USLT" -> if (lyrics == null) lyrics = decodeComm(body)
                    "TIT2" -> if (title == null) title = decodeTextFrame(body)
                    "TPE1" -> if (artist == null) artist = decodeTextFrame(body)
                    "TALB" -> if (album == null) album = decodeTextFrame(body)
                    "TCON" -> if (genre == null) genre = decodeTextFrame(body)
                    "TDRC", "TYER", "TDRL" -> if (year == null) year = decodeTextFrame(body)?.take(4)
                    "TLEN" -> decodeTextFrame(body)?.toLongOrNull()?.let { dur = it }
                }
            } else if (ver == 2) {
                val id = String(tag, p, 3, Charsets.ISO_8859_1)
                if (!id[0].isLetterOrDigit()) break
                val fsz = ((tag[p + 3].toInt() and 0xFF) shl 16) or ((tag[p + 4].toInt() and 0xFF) shl 8) or (tag[p + 5].toInt() and 0xFF)
                p += 6
                if (fsz <= 0 || p + fsz > size) break
                val body = tag.copyOfRange(p, p + fsz); p += fsz
                when (id) {
                    "COM" -> if (comment == null) comment = decodeComm(body)
                    "PIC" -> if (cover == null) cover = decodeApic(body, true)
                    "TT2" -> if (title == null) title = decodeTextFrame(body)
                    "TP1" -> if (artist == null) artist = decodeTextFrame(body)
                    "TAL" -> if (album == null) album = decodeTextFrame(body)
                    "TCO" -> if (genre == null) genre = decodeTextFrame(body)
                    "TYE" -> if (year == null) year = decodeTextFrame(body)?.take(4)
                }
            } else break
        }
        return Result(comment, cover, title, artist, album, genre, dur, lyrics, year)
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
    private val ACCENTS = intArrayOf(
        Color.parseColor("#FF4D6D"), Color.parseColor("#7C4DFF"), Color.parseColor("#3DDC84"),
        Color.parseColor("#FF9800"), Color.parseColor("#40C4FF"), Color.parseColor("#C2185B")
    )
    private val ACCENT_NAMES = arrayOf("Розовый", "Фиолетовый", "Зелёный", "Оранжевый", "Голубой", "Малиновый")
    private var ACCENT = Color.parseColor("#FF4D6D")
    private val GRAY = Color.parseColor("#8D81A3")
    private val WHITE = Color.parseColor("#EEEEF5")
    private val CARD_BG = Color.parseColor("#1E1E2A")
    private val BUBBLE_BG = Color.argb(51, 160, 160, 160)
    private val LOSSLESS_GREEN = Color.parseColor("#3DDC84")
    private val LOSSY_ORANGE = Color.parseColor("#FF9800")
    private val CACHE_VER = 4
    private val HEAD_BYTES = 1024 * 1024
    private val AUDIO_EXTS = setOf("mp3", "flac", "ogg", "oga", "opus")
    private val SEARCH_PULL_DP = 44
    private val DISMISS_FLING_DP = 34
    private val PLAYER_FLING_DP = 40
    private lateinit var libScreen: LinearLayout
    private lateinit var playerScreen: LinearLayout
    private lateinit var settingsScreen: LinearLayout
    private lateinit var headTitle: TextView
    private lateinit var sortBtn: IconView
    private lateinit var status: TextView
    private lateinit var contentBox: LinearLayout
    private lateinit var rowsBox: LinearLayout
    private lateinit var contentScroll: ScrollView
    private lateinit var searchInput: EditText
    private lateinit var foldersBox: LinearLayout
    private lateinit var nbBar: LinearLayout
    private lateinit var nbCover: ImageView
    private lateinit var nbTitle: TextView
    private lateinit var nbArtist: TextView
    private lateinit var nbFormat: TextView
    private lateinit var nbSpecs: TextView
    private lateinit var nbPlay: IconView
    private lateinit var nbNext: IconView
    private lateinit var npCover: ImageView
    private lateinit var npCover2: ImageView
    private var frontIsA = true
    private lateinit var pTitle: TextView
    private lateinit var pArtist: TextView
    private lateinit var pAlbum: TextView
    private lateinit var pFormat: TextView
    private lateinit var pSpecs: TextView
    private lateinit var tagsScroll: HorizontalScrollView
    private lateinit var tagsRow: LinearLayout
    private lateinit var waveView: WaveformView
    private lateinit var tCur: TextView
    private lateinit var tDur: TextView
    private lateinit var shufI: IconView
    private lateinit var prevI: IconView
    private lateinit var playI: IconView
    private lateinit var nextI: IconView
    private lateinit var repI: IconView
    private lateinit var queueI: IconView
    private lateinit var lyricsI: IconView
    private lateinit var tagsI: IconView

    data class Track(
        val uri: String, val title: String, val artist: String,
        val tags: List<String>, val coverPath: String? = null,
        val album: String = "Неизвестный альбом", val genre: String = "",
        val durationMs: Long = 0, val bitrateKbps: Int = 0, val lyrics: String? = null,
        val year: String = "", val format: String = "MP3",
        val sampleRateHz: Int = 0, val bitDepth: Int = 0,
        val fileSize: Long = 0, val fileMod: Long = 0
    )

    private var tracks = listOf<Track>()
    private val selected = mutableSetOf<String>()
    private var shown = listOf<Track>()
    private var currentTab = "menu"
    private var subType: String? = null
    private var subValue: String? = null
    private var trees = mutableSetOf<String>()
    private var visibleLimit = 100
    private var pageTotal = 0
    private var bigCoverBm: Bitmap? = null
    private var bigCoverUri: String? = null
    private var lastPlayerUri: String? = null
    private var scanRunning = false
    private var pullDownY = 0f
    private var searchShown = false
    private var searchQuery = ""
    private var updatingSearch = false
    private val thumbCache = object : LruCache<String, Bitmap>(80) {}
    private val thumbPool = Executors.newFixedThreadPool(2)
    private val handler = Handler(Looper.getMainLooper())
    private val marqueeHandler = Handler(Looper.getMainLooper())
    private val scrollDirs = HashMap<HorizontalScrollView, Int>()
    private val scrollTouch = HashMap<HorizontalScrollView, Long>()
    private val listener: () -> Unit = { runOnUiThread { refreshNow(); highlightCurrent() } }
    private val poll = object : Runnable {
        override fun run() {
            val p = PlayerService.instance
            if (p != null && PlayerState.uri != null) {
                val pos = p.currentPosition(); val dur = PlayerState.durationMs
                tCur.text = fmt(pos.toLong()); tDur.text = fmt(dur.toLong())
                if (dur > 0) waveView.setProgress(pos.toFloat() / dur)
                if (PlayerState.playing) nbCover.rotation = (nbCover.rotation + 0.5f) % 360f
            }
            val pm = getSystemService(POWER_SERVICE) as PowerManager
            handler.postDelayed(this, if (pm.isInteractive) 100 else 1000)
        }
    }

    private fun dp(n: Int) = (resources.displayMetrics.density * n).toInt()
    private fun prefs() = getSharedPreferences("tagtune2", MODE_PRIVATE)
    private fun allTags(): List<String> = tracks.flatMap { it.tags }.distinct().sorted()
    private fun fmt(ms: Long): String { val s = ms / 1000; return "${s / 60}:${(s % 60).toString().padStart(2, '0')}" }
    private fun curTrack(): Track? = tracks.find { it.uri == PlayerState.uri }
    private fun cacheFile() = File(filesDir, "library_cache.json")
    private fun crashFile() = File(filesDir, "crash_log.txt")
    private fun accentAlpha(a: Int): Int = (ACCENT and 0x00FFFFFF) or (a shl 24)
    private fun bubbleColor(format: String): Int = if (format == "FLAC") LOSSLESS_GREEN else LOSSY_ORANGE
    private fun extOfUri(uri: String): String =
        (Uri.parse(uri).lastPathSegment?.substringAfterLast('.', "") ?: "").lowercase()

    private fun khzStr(hz: Int): String {
        if (hz <= 0) return ""
        val d = hz / 1000.0
        return if (d == d.toLong().toDouble()) "${d.toLong()}kHz" else "${d}kHz"
    }

    private fun specsString(t: Track): String {
        val khz = khzStr(t.sampleRateHz)
        return if (t.format == "FLAC") {
            val bits = if (t.bitDepth > 0) "${t.bitDepth}-bit" else null
            listOfNotNull(bits, khz.takeIf { it.isNotEmpty() }).joinToString("/")
        } else {
            listOfNotNull(khz.takeIf { it.isNotEmpty() }, if (t.bitrateKbps > 0) "${t.bitrateKbps}kbps" else null).joinToString("/")
        }
    }

    private fun matches(t: Track): Boolean {
        val q = searchQuery.trim()
        if (q.isEmpty()) return true
        return t.title.contains(q, true) || t.artist.contains(q, true) ||
            t.album.contains(q, true) || t.tags.any { it.contains(q, true) }
    }

    private fun highlight(text: String): CharSequence {
        val q = searchQuery.trim()
        if (q.isEmpty()) return text
        val sp = SpannableString(text)
        var idx = text.indexOf(q, 0, true)
        while (idx >= 0) {
            sp.setSpan(BackgroundColorSpan(accentAlpha(51)), idx, idx + q.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            idx = text.indexOf(q, idx + q.length, true)
        }
        return sp
    }

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
            val bm = loadBitmap(path, 192)
            if (bm != null) thumbCache.put(path, bm)
            runOnUiThread { if (iv.tag as? String == path) iv.setImageBitmap(bm) }
        }
    }

    private fun pillBg(color: Int, radiusDp: Int): GradientDrawable =
        GradientDrawable().apply { setColor(color); cornerRadius = dp(radiusDp).toFloat() }

    private fun roundCorners(v: View, rDp: Int) {
        v.clipToOutline = true
        v.outlineProvider = object : ViewOutlineProvider() {
            override fun getOutline(vv: View, o: Outline) {
                o.setRoundRect(0, 0, vv.width, vv.height, dp(rDp).toFloat())
            }
        }
    }

    private fun rowBg(active: Boolean): Drawable? =
        if (active) GradientDrawable().apply {
            setColor(Color.TRANSPARENT)
            setStroke(dp(1), ACCENT)
            cornerRadius = dp(12).toFloat()
        } else null

    private fun pressCircle(): StateListDrawable {
        val sld = StateListDrawable()
        sld.addState(intArrayOf(android.R.attr.state_pressed), PressCircleDrawable(ACCENT))
        sld.addState(intArrayOf(), PressCircleDrawable(Color.TRANSPARENT))
        return sld
    }

    private fun hclick(v: View, action: () -> Unit) {
        v.setOnClickListener {
            v.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
            action()
        }
    }

    private fun settingsRow(text: String, click: () -> Unit): Button {
        val b = Button(this)
        b.text = text
        b.isAllCaps = false
        b.textSize = 15f
        b.setTextColor(WHITE)
        b.gravity = Gravity.CENTER
        b.background = pillBg(CARD_BG, 12)
        b.setOnClickListener { click() }
        val lp = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(48))
        lp.setMargins(0, dp(8), 0, 0)
        b.layoutParams = lp
        return b
    }

    private fun attachAutoScroll(hsv: HorizontalScrollView) {
        scrollDirs[hsv] = 1
        scrollTouch[hsv] = 0L
        hsv.setOnTouchListener { _, ev ->
            scrollTouch[hsv] = SystemClock.uptimeMillis()
            false
        }
        val tick = object : Runnable {
            override fun run() {
                if (hsv.isShown && hsv.width > 0 && hsv.childCount > 0) {
                    val child = hsv.getChildAt(0)
                    val max = child.width - hsv.width
                    val last = scrollTouch[hsv] ?: 0L
                    if (max > 0 && SystemClock.uptimeMillis() - last > 2500) {
                        var d = scrollDirs[hsv] ?: 1
                        var x = hsv.scrollX + d
                        if (x >= max) { x = max; d = -1; scrollTouch[hsv] = SystemClock.uptimeMillis() }
                        else if (x <= 0) { x = 0; d = 1; scrollTouch[hsv] = SystemClock.uptimeMillis() }
                        hsv.scrollTo(x, 0)
                        scrollDirs[hsv] = d
                    }
                }
                marqueeHandler.postDelayed(this, 40)
            }
        }
        marqueeHandler.post(tick)
    }

    private fun marqueeText(size: Float, color: Int, gravity: Int): Pair<HorizontalScrollView, TextView> {
        val hsv = HorizontalScrollView(this)
        hsv.isHorizontalScrollBarEnabled = false
        hsv.setFillViewport(true)
        val tv = TextView(this)
        tv.textSize = size
        tv.setTextColor(color)
        tv.setSingleLine(true)
        tv.gravity = gravity
        tv.includeFontPadding = false
        hsv.addView(tv, FrameLayout.LayoutParams(FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT, gravity))
        attachAutoScroll(hsv)
        return Pair(hsv, tv)
    }

    private fun installCrashHandler() {
        val default = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { t, e ->
            try {
                val sw = StringWriter()
                e.printStackTrace(PrintWriter(sw))
                val entry = "=== ${Date()} | поток: ${t.name} ===\n$sw\n"
                val old = if (crashFile().exists()) crashFile().readText() else ""
                crashFile().writeText((entry + old).take(200_000))
            } catch (ignored: Exception) { }
            default?.uncaughtException(t, e)
        }
    }

    private fun openCrashLogDialog() {
        val text = if (crashFile().exists()) crashFile().readText() else "Крашей не зафиксировано."
        val scroll = ScrollView(this)
        val tv = TextView(this)
        tv.text = text
        tv.typeface = Typeface.MONOSPACE
        tv.textSize = 11f
        tv.setTextColor(WHITE)
        tv.setTextIsSelectable(true)
        tv.setPadding(dp(16), dp(8), dp(16), dp(16))
        scroll.addView(tv)
        AlertDialog.Builder(this)
            .setTitle("Логи ошибок")
            .setView(scroll)
            .setPositiveButton("Скопировать") { _, _ ->
                val cm = getSystemService(CLIPBOARD_SERVICE) as ClipboardManager
                cm.setPrimaryClip(ClipData.newPlainText("tagtune crash log", text))
                Toast.makeText(this, "Скопировано в буфер", Toast.LENGTH_SHORT).show()
            }
            .setNeutralButton("Очистить") { _, _ ->
                crashFile().delete()
                Toast.makeText(this, "Лог очищен", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("Закрыть", null)
            .show()
    }

    private fun openAccentDialog() {
        val current = prefs().getInt("accent_idx", 0)
        val row = LinearLayout(this)
        row.orientation = LinearLayout.HORIZONTAL
        row.gravity = Gravity.CENTER
        row.setPadding(dp(16), dp(16), dp(16), dp(16))
        val dialog = AlertDialog.Builder(this)
            .setTitle("Акцентный цвет")
            .setView(row)
            .setNegativeButton("Отмена", null)
            .create()
        for (i in ACCENTS.indices) {
            val sw = View(this)
            sw.background = GradientDrawable().apply {
                setColor(ACCENTS[i])
                shape = GradientDrawable.OVAL
                if (i == current) setStroke(dp(3), WHITE)
            }
            sw.setOnClickListener {
                prefs().edit().putInt("accent_idx", i).apply()
                dialog.dismiss()
                recreate()
            }
            val lp = LinearLayout.LayoutParams(dp(44), dp(44))
            lp.setMargins(dp(6), 0, dp(6), 0)
            row.addView(sw, lp)
        }
        dialog.show()
    }

    private fun openSortDialog(section: String) {
        val isList = section in listOf("tracks", "recent", "top")
        val options = if (isList) {
            arrayOf("По названию", "По году", "По длительности", "По битрейту")
        } else {
            arrayOf("По имени", "По количеству")
        }
        val key = "sort_$section"
        val current = prefs().getInt(key, 0).coerceIn(0, options.size - 1)
        AlertDialog.Builder(this)
            .setTitle("Сортировка")
            .setSingleChoiceItems(options, current) { d, which ->
                prefs().edit().putInt(key, which).apply()
                d.dismiss()
                rebuildContent(false)
            }
            .setNegativeButton("Отмена", null)
            .show()
    }

    private fun openDuplicatesDialog() {
        val groups = tracks.groupBy { "${it.title.lowercase()}|${it.artist.lowercase()}" }
            .filter { it.value.size > 1 }
            .values.sortedByDescending { it.size }
        if (groups.isEmpty()) {
            Toast.makeText(this, "Дубликатов не найдено", Toast.LENGTH_SHORT).show()
            return
        }
        val scroll = ScrollView(this)
        val box = LinearLayout(this); box.orientation = LinearLayout.VERTICAL
        box.setPadding(dp(16), dp(8), dp(16), dp(8))
        for (g in groups) {
            val header = TextView(this)
            header.text = "${g.first().title} — ${g.first().artist} (${g.size})"
            header.setTextColor(ACCENT)
            header.textSize = 14f
            header.setPadding(0, dp(8), 0, dp(4))
            box.addView(header)
            for (t in g) {
                val row = LinearLayout(this)
                row.orientation = LinearLayout.HORIZONTAL
                row.gravity = Gravity.CENTER_VERTICAL
                val tv = TextView(this)
                tv.text = "${t.format} · ${specsString(t)} · ${t.album}"
                tv.setTextColor(WHITE)
                tv.textSize = 12f
                row.addView(tv, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
                val del = Button(this)
                del.text = "🗑"
                del.setTextColor(Color.parseColor("#FF6B6B"))
                del.background = pressCircle()
                del.setOnClickListener {
                    AlertDialog.Builder(this)
                        .setTitle("Удалить файл?")
                        .setMessage("${t.title}\n${t.artist}")
                        .setPositiveButton("Удалить") { _, _ ->
                            Thread {
                                val df = DocumentFile.fromSingleUri(this, Uri.parse(t.uri))
                                val ok = df?.delete() == true
                                runOnUiThread {
                                    if (ok) {
                                        tracks = tracks.filter { it.uri != t.uri }
                                        saveLibraryCache()
                                        rebuildContent()
                                        Toast.makeText(this, "Удалено", Toast.LENGTH_SHORT).show()
                                    } else {
                                        Toast.makeText(this, "Не удалось удалить", Toast.LENGTH_SHORT).show()
                                    }
                                }
                            }.start()
                        }
                        .setNegativeButton("Отмена", null)
                        .show()
                }
                row.addView(del, LinearLayout.LayoutParams(dp(36), dp(36)))
                box.addView(row)
            }
        }
        scroll.addView(box)
        AlertDialog.Builder(this)
            .setTitle("Дубликаты")
            .setView(scroll)
            .setNegativeButton("Закрыть", null)
            .show()
    }

    private fun saveLibraryCache() {
        try {
            val arr = JSONArray()
            for (t in tracks) {
                val o = JSONObject()
                o.put("uri", t.uri); o.put("title", t.title); o.put("artist", t.artist)
                o.put("album", t.album); o.put("genre", t.genre); o.put("dur", t.durationMs)
                o.put("br", t.bitrateKbps); o.put("yr", t.year); o.put("fmt", t.format)
                o.put("sr", t.sampleRateHz); o.put("bd", t.bitDepth)
                o.put("sz", t.fileSize); o.put("mod", t.fileMod)
                if (t.lyrics != null) o.put("lyr", t.lyrics) else o.put("lyr", JSONObject.NULL)
                o.put("tags", JSONArray(t.tags))
                if (t.coverPath != null) o.put("cover", t.coverPath) else o.put("cover", JSONObject.NULL)
                arr.put(o)
            }
            cacheFile().writeText(arr.toString())
            prefs().edit().putInt("cache_ver", CACHE_VER).apply()
        } catch (e: Exception) { }
    }

    private fun loadLibraryCache(): List<Track> {
        if (prefs().getInt("cache_ver", 0) != CACHE_VER) return emptyList()
        return try {
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
                    o.optLong("dur", 0), o.optInt("br", 0), lyr, o.optString("yr", ""),
                    o.optString("fmt", "MP3"), o.optInt("sr", 0), o.optInt("bd", 0),
                    o.optLong("sz", 0), o.optLong("mod", 0)
                ))
            }
            out
        } catch (e: Exception) { emptyList() }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        installCrashHandler()
        ACCENT = ACCENTS[prefs().getInt("accent_idx", 0).coerceIn(0, ACCENTS.size - 1)]
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(android.Manifest.permission.POST_NOTIFICATIONS), 303)
        }
        val oldTree = prefs().getString("tree_uri", null)
        trees = prefs().getStringSet("trees", null)?.toMutableSet() ?: mutableSetOf()
        if (oldTree != null && trees.isEmpty()) { trees.add(oldTree); prefs().edit().putStringSet("trees", trees).apply() }

        val root = LinearLayout(this)
        root.orientation = LinearLayout.VERTICAL
        root.setBackgroundColor(Color.parseColor("#0D0A12"))

        libScreen = LinearLayout(this)
        libScreen.orientation = LinearLayout.VERTICAL
        libScreen.setPadding(dp(14), dp(14), dp(14), dp(10))

        val topRow = LinearLayout(this); topRow.orientation = LinearLayout.HORIZONTAL
        topRow.gravity = Gravity.CENTER_VERTICAL
        headTitle = TextView(this); headTitle.text = "Медиатека"
        headTitle.textSize = 26f; headTitle.setTextColor(WHITE)
        headTitle.setSingleLine(true); headTitle.ellipsize = TextUtils.TruncateAt.END
        topRow.addView(headTitle, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        sortBtn = IconView(this); sortBtn.icon = "queue"; sortBtn.setColor(WHITE)
        sortBtn.background = pressCircle()
        sortBtn.setOnClickListener { openSortForCurrent() }
        sortBtn.visibility = View.GONE
        topRow.addView(sortBtn, LinearLayout.LayoutParams(dp(30), dp(30)))
        val settingsBtn = IconView(this); settingsBtn.icon = "sliders"; settingsBtn.setColor(WHITE)
        settingsBtn.background = pressCircle()
        settingsBtn.setOnClickListener { showSettings() }
        topRow.addView(settingsBtn, LinearLayout.LayoutParams(dp(30), dp(30)))
        libScreen.addView(topRow)

        status = TextView(this); status.textSize = 13f
        status.setTextColor(Color.parseColor("#BBBBCC"))
        status.visibility = View.GONE
        libScreen.addView(status)

        searchInput = EditText(this)
        searchInput.hint = "Поиск по медиатеке…"
        searchInput.setSingleLine(true)
        searchInput.textSize = 14f
        searchInput.setTextColor(WHITE)
        searchInput.setHintTextColor(GRAY)
        searchInput.background = GradientDrawable().apply {
            setColor(CARD_BG)
            cornerRadius = dp(12).toFloat()
        }
        searchInput.setPadding(dp(12), dp(10), dp(12), dp(10))
        searchInput.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) { }
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) { }
            override fun afterTextChanged(e: Editable?) {
                val s = e?.toString() ?: ""
                if (!updatingSearch && s != searchQuery) {
                    searchQuery = s
                    rebuildRows(false)
                }
            }
        })

        rowsBox = LinearLayout(this); rowsBox.orientation = LinearLayout.VERTICAL
        rowsBox.setPadding(0, dp(12), 0, 0)
        contentScroll = ScrollView(this)
        contentBox = LinearLayout(this); contentBox.orientation = LinearLayout.VERTICAL
        contentScroll.addView(contentBox)
        libScreen.addView(contentScroll, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))

        contentScroll.setOnTouchListener { _, ev ->
            when (ev.action) {
                MotionEvent.ACTION_DOWN -> pullDownY = ev.y
                MotionEvent.ACTION_MOVE -> {
                    if (contentScroll.scrollY == 0 && ev.y - pullDownY > dp(SEARCH_PULL_DP)) {
                        if (currentTab == "menu") {
                            if (!scanRunning) startScan()
                        } else if (!searchShown) {
                            revealSearch()
                        }
                    }
                }
            }
            false
        }

        contentScroll.setOnScrollChangeListener { _, _, scrollY, _, _ ->
            if (visibleLimit < pageTotal &&
                scrollY + contentScroll.height >= contentBox.height - dp(240)) {
                visibleLimit += 100
                rebuildRows(false)
            }
        }

        nbBar = LinearLayout(this)
        nbBar.orientation = LinearLayout.HORIZONTAL
        nbBar.gravity = Gravity.CENTER_VERTICAL
        nbBar.background = GradientDrawable().apply {
            setColor(Color.BLACK)
            setStroke(dp(1), ACCENT)
            cornerRadius = dp(999).toFloat()
        }
        nbBar.setPadding(dp(14), dp(10), dp(14), dp(10))

        val nbGd = GestureDetector(this, object : GestureDetector.SimpleOnGestureListener() {
            override fun onDown(e: MotionEvent): Boolean = true
            override fun onSingleTapUp(e: MotionEvent): Boolean { showPlayer(); return true }
            override fun onFling(e1: MotionEvent?, e2: MotionEvent, vx: Float, vy: Float): Boolean {
                val dx = e2.x - (e1?.x ?: e2.x)
                val dy = e2.y - (e1?.y ?: e2.y)
                if (!PlayerState.playing && abs(dx) > abs(dy) && abs(dx) > dp(DISMISS_FLING_DP)) {
                    nbBar.visibility = View.GONE
                    return true
                }
                return false
            }
        })
        nbBar.setOnTouchListener { _, ev -> nbGd.onTouchEvent(ev) }

        nbCover = ImageView(this)
        nbCover.scaleType = ImageView.ScaleType.CENTER_CROP
        nbCover.setBackgroundColor(Color.parseColor("#2A2A3A"))
        nbCover.clipToOutline = true
        nbCover.outlineProvider = object : ViewOutlineProvider() {
            override fun getOutline(v: View, o: Outline) { o.setOval(0, 0, v.width, v.height) }
        }
        nbBar.addView(nbCover, LinearLayout.LayoutParams(dp(72), dp(72)))

        val nbInfo = LinearLayout(this); nbInfo.orientation = LinearLayout.VERTICAL
        nbInfo.setPadding(dp(12), 0, dp(8), 0)
        val nbTitlePair = marqueeText(15f, WHITE, Gravity.START); nbTitle = nbTitlePair.second
        nbTitle.text = "Ничего не играет"
        nbInfo.addView(nbTitlePair.first, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        val nbArtistPair = marqueeText(13f, ACCENT, Gravity.START); nbArtist = nbArtistPair.second
        nbInfo.addView(nbArtistPair.first, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        val nbFmtRow = LinearLayout(this); nbFmtRow.orientation = LinearLayout.HORIZONTAL
        nbFmtRow.gravity = Gravity.CENTER_VERTICAL
        nbFmtRow.setPadding(0, dp(3), 0, 0)
        nbFormat = TextView(this); nbFormat.textSize = 11f
        nbFormat.setTypeface(Typeface.DEFAULT_BOLD)
        nbFormat.setTextColor(WHITE)
        nbFormat.setPadding(dp(8), dp(2), dp(8), dp(2))
        nbSpecs = TextView(this); nbSpecs.textSize = 11f
        nbSpecs.setTextColor(WHITE)
        nbSpecs.setPadding(dp(6), 0, 0, 0)
        nbFmtRow.addView(nbFormat); nbFmtRow.addView(nbSpecs)
        nbInfo.addView(nbFmtRow, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        nbBar.addView(nbInfo, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))

        nbPlay = IconView(this); nbPlay.icon = "play"; nbPlay.setColor(WHITE)
        nbPlay.glyphScale = 1f
        nbPlay.background = pressCircle()
        hclick(nbPlay) { PlayerService.cmd(this, "toggle") }
        nbBar.addView(nbPlay, LinearLayout.LayoutParams(dp(40), dp(40)))
        nbNext = IconView(this); nbNext.icon = "next"; nbNext.setColor(WHITE)
        nbNext.glyphScale = 0.775f
        nbNext.background = pressCircle()
        hclick(nbNext) { PlayerService.cmd(this, "next") }
        val nbNextLp = LinearLayout.LayoutParams(dp(40), dp(40))
        nbNextLp.setMargins(dp(4), 0, 0, 0)
        nbBar.addView(nbNext, nbNextLp)

        val nbLp = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
        nbLp.setMargins(0, dp(10), 0, dp(8))
        libScreen.addView(nbBar, nbLp)

        root.addView(libScreen, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.MATCH_PARENT))

        playerScreen = LinearLayout(this)
        playerScreen.orientation = LinearLayout.VERTICAL
        playerScreen.setPadding(dp(14), dp(10), dp(14), dp(14))
        playerScreen.visibility = View.GONE

        val spTop = View(this)
        playerScreen.addView(spTop, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))

        val gestureZone = LinearLayout(this)
        gestureZone.orientation = LinearLayout.VERTICAL
        val gd = GestureDetector(this, object : GestureDetector.SimpleOnGestureListener() {
            override fun onDown(e: MotionEvent): Boolean = true
            override fun onFling(e1: MotionEvent?, e2: MotionEvent, vx: Float, vy: Float): Boolean {
                val dx = e2.x - (e1?.x ?: e2.x)
                val dy = e2.y - (e1?.y ?: e2.y)
                if (maxOf(abs(dx), abs(dy)) < dp(PLAYER_FLING_DP)) return false
                if (abs(dy) > abs(dx)) {
                    if (dy < 0) openQueueDialog() else showLibrary()
                } else {
                    if (dx < 0) PlayerService.cmd(this@MainActivity, "next")
                    else PlayerService.cmd(this@MainActivity, "prev")
                }
                return true
            }
        })
        gestureZone.setOnTouchListener { _, ev -> gd.onTouchEvent(ev) }

        val coverFrame = FrameLayout(this)
        val roundProvider = object : ViewOutlineProvider() {
            override fun getOutline(v: View, o: Outline) {
                o.setRoundRect(0, 0, v.width, v.height, dp(16).toFloat())
            }
        }
        npCover = ImageView(this)
        npCover.scaleType = ImageView.ScaleType.CENTER_CROP
        npCover.setBackgroundColor(Color.parseColor("#1E1E2A"))
        npCover.clipToOutline = true
        npCover.outlineProvider = roundProvider
        npCover2 = ImageView(this)
        npCover2.scaleType = ImageView.ScaleType.CENTER_CROP
        npCover2.setBackgroundColor(Color.parseColor("#1E1E2A"))
        npCover2.clipToOutline = true
        npCover2.outlineProvider = roundProvider
        npCover2.alpha = 0f
        npCover2.visibility = View.INVISIBLE
        coverFrame.addView(npCover, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
        coverFrame.addView(npCover2, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
        val covSize = resources.displayMetrics.widthPixels - dp(28) - dp(40)
        val covLp = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, covSize)
        covLp.setMargins(dp(20), dp(6), dp(20), dp(6))
        gestureZone.addView(coverFrame, covLp)

        val titlePair = marqueeText(22f, WHITE, Gravity.CENTER); pTitle = titlePair.second
        val titleLp = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
        titleLp.topMargin = dp(12)
        gestureZone.addView(titlePair.first, titleLp)

        val artistPair = marqueeText(17f, ACCENT, Gravity.CENTER); pArtist = artistPair.second
        gestureZone.addView(artistPair.first)

        val albumPair = marqueeText(15f, Color.parseColor("#9A93A8"), Gravity.CENTER); pAlbum = albumPair.second
        gestureZone.addView(albumPair.first)

        tagsScroll = HorizontalScrollView(this)
        tagsScroll.isHorizontalScrollBarEnabled = false
        tagsScroll.setFillViewport(true)
        tagsRow = LinearLayout(this); tagsRow.orientation = LinearLayout.HORIZONTAL
        tagsRow.gravity = Gravity.CENTER
        tagsRow.setPadding(dp(6), dp(4), dp(6), 0)
        tagsScroll.addView(tagsRow)
        attachAutoScroll(tagsScroll)
        val tagsLp = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(36))
        tagsLp.topMargin = dp(12)
        gestureZone.addView(tagsScroll, tagsLp)

        playerScreen.addView(gestureZone)

        waveView = WaveformView(this)
        waveView.setAccent(ACCENT)
        waveView.onSeek = { f ->
            val d = PlayerState.durationMs
            if (d > 0) PlayerService.cmd(this, "seek", seekMs = (f * d).toInt())
        }
        val waveLp = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(56))
        waveLp.topMargin = dp(12)
        playerScreen.addView(waveView, waveLp)

        val times = LinearLayout(this); times.orientation = LinearLayout.HORIZONTAL
        times.gravity = Gravity.CENTER_VERTICAL
        val timesLp = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
        timesLp.topMargin = dp(12)
        tCur = TextView(this); tCur.text = "0:00"; tCur.textSize = 13f
        tCur.setTextColor(Color.parseColor("#9A93A8"))
        tCur.gravity = Gravity.START
        val fmtGroup = LinearLayout(this); fmtGroup.orientation = LinearLayout.HORIZONTAL
        fmtGroup.gravity = Gravity.CENTER
        pFormat = TextView(this); pFormat.textSize = 13f
        pFormat.setTypeface(Typeface.DEFAULT_BOLD)
        pFormat.setTextColor(WHITE)
        pFormat.setPadding(dp(10), dp(3), dp(10), dp(3))
        pSpecs = TextView(this); pSpecs.textSize = 13f
        pSpecs.setTextColor(WHITE)
        pSpecs.setPadding(dp(6), 0, 0, 0)
        fmtGroup.addView(pFormat)
        fmtGroup.addView(pSpecs)
        tDur = TextView(this); tDur.text = "0:00"; tDur.textSize = 13f
        tDur.setTextColor(Color.parseColor("#9A93A8"))
        tDur.gravity = Gravity.END
        times.addView(tCur, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        times.addView(fmtGroup, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        times.addView(tDur, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        playerScreen.addView(times, timesLp)

        val transport = LinearLayout(this); transport.orientation = LinearLayout.HORIZONTAL
        transport.gravity = Gravity.CENTER_VERTICAL
        transport.setPadding(0, dp(20), 0, 0)
        shufI = IconView(this); shufI.icon = "shuffle"; shufI.setColor(GRAY)
        shufI.background = pressCircle()
        hclick(shufI) { PlayerService.cmd(this, "toggleShuffle") }
        transport.addView(shufI, LinearLayout.LayoutParams(0, dp(36), 1f))
        prevI = IconView(this); prevI.icon = "prev"; prevI.setColor(WHITE)
        prevI.background = pressCircle()
        hclick(prevI) { PlayerService.cmd(this, "prev") }
        transport.addView(prevI, LinearLayout.LayoutParams(0, dp(44), 1f))
        playI = IconView(this); playI.icon = "play"; playI.setColor(WHITE)
        playI.background = GradientDrawable().apply { setColor(ACCENT); shape = GradientDrawable.OVAL }
        hclick(playI) { PlayerService.cmd(this, "toggle") }
        val playLp = LinearLayout.LayoutParams(dp(64), dp(64))
        playLp.setMargins(dp(8), 0, dp(8), 0)
        transport.addView(playI, playLp)
        nextI = IconView(this); nextI.icon = "next"; nextI.setColor(WHITE)
        nextI.background = pressCircle()
        hclick(nextI) { PlayerService.cmd(this, "next") }
        transport.addView(nextI, LinearLayout.LayoutParams(0, dp(44), 1f))
        repI = IconView(this); repI.icon = "repeat"; repI.setColor(GRAY)
        repI.background = pressCircle()
        hclick(repI) { PlayerService.cmd(this, "toggleRepeat") }
        transport.addView(repI, LinearLayout.LayoutParams(0, dp(36), 1f))
        playerScreen.addView(transport)

        val extraRow = LinearLayout(this); extraRow.orientation = LinearLayout.HORIZONTAL
        extraRow.gravity = Gravity.CENTER_VERTICAL
        extraRow.setPadding(0, dp(28), 0, 0)
        val sp1 = View(this)
        extraRow.addView(sp1, LinearLayout.LayoutParams(0, dp(40), 1f))
        queueI = IconView(this); queueI.icon = "queue"; queueI.setColor(WHITE)
        queueI.background = pressCircle()
        hclick(queueI) { openQueueDialog() }
        extraRow.addView(queueI, LinearLayout.LayoutParams(0, dp(40), 1f))
        lyricsI = IconView(this); lyricsI.icon = "lyrics"; lyricsI.setColor(WHITE)
        lyricsI.background = pressCircle()
        hclick(lyricsI) { openLyricsDialog() }
        val lyrLp = LinearLayout.LayoutParams(dp(64), dp(40))
        lyrLp.setMargins(dp(8), 0, dp(8), 0)
        extraRow.addView(lyricsI, lyrLp)
        tagsI = IconView(this); tagsI.icon = "tags"; tagsI.setColor(WHITE)
        tagsI.background = pressCircle()
        hclick(tagsI) { curTrack()?.let { openEditor(it) } }
        extraRow.addView(tagsI, LinearLayout.LayoutParams(0, dp(40), 1f))
        val sp2 = View(this)
        extraRow.addView(sp2, LinearLayout.LayoutParams(0, dp(40), 1f))
        playerScreen.addView(extraRow)

        val spBottom = View(this)
        playerScreen.addView(spBottom, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))

        root.addView(playerScreen, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.MATCH_PARENT))

        settingsScreen = LinearLayout(this)
        settingsScreen.orientation = LinearLayout.VERTICAL
        settingsScreen.setPadding(dp(14), dp(14), dp(14), dp(14))
        settingsScreen.visibility = View.GONE

        val sTitle = TextView(this); sTitle.text = "Настройки"
        sTitle.textSize = 26f; sTitle.setTextColor(WHITE)
        sTitle.setSingleLine(true); sTitle.ellipsize = TextUtils.TruncateAt.END
        settingsScreen.addView(sTitle)

        val sScroll = ScrollView(this)
        val sBox = LinearLayout(this); sBox.orientation = LinearLayout.VERTICAL
        sBox.setPadding(0, dp(12), 0, 0)
        sScroll.addView(sBox)
        settingsScreen.addView(sScroll, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))

        val sLabel = TextView(this); sLabel.text = "Папки с музыкой:"
        sLabel.setTextColor(Color.parseColor("#BBBBCC"))
        sBox.addView(sLabel)
        foldersBox = LinearLayout(this); foldersBox.orientation = LinearLayout.VERTICAL
        sBox.addView(foldersBox)
        sBox.addView(settingsRow("＋ Добавить папку") { pickFolder() })
        sBox.addView(settingsRow("🔄 Пересканировать") { startScan() })
        sBox.addView(settingsRow("🎨 Акцент: " + ACCENT_NAMES[prefs().getInt("accent_idx", 0).coerceIn(0, ACCENT_NAMES.size - 1)]) { openAccentDialog() })
        sBox.addView(settingsRow("📋 Логи ошибок") { openCrashLogDialog() })
        sBox.addView(settingsRow("🗑 Найти дубликаты") { openDuplicatesDialog() })

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

    private fun openSortForCurrent() {
        val section = when (currentTab) {
            "tracks", "recent", "top" -> currentTab
            "albums" -> if (subType == "album") "tracks" else "albums"
            "artists" -> if (subType == "artist") "tracks" else "artists"
            "genres" -> if (subType == "genre") "tracks" else "genres"
            else -> null
        }
        if (section != null) openSortDialog(section)
    }

    private fun showCoverBig(bm: Bitmap?) {
        if (bm == null) {
            npCover.setImageResource(0); npCover2.setImageResource(0)
            npCover.alpha = 1f; npCover2.alpha = 0f
            npCover2.visibility = View.INVISIBLE
            frontIsA = true
            return
        }
        val back = if (frontIsA) npCover2 else npCover
        val front = if (frontIsA) npCover else npCover2
        back.setImageBitmap(bm)
        back.visibility = View.VISIBLE
        back.alpha = 0f
        back.animate().alpha(1f).setDuration(250).start()
        front.animate().alpha(0f).setDuration(250).withEndAction { front.visibility = View.INVISIBLE }.start()
        frontIsA = !frontIsA
    }

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
            libScreen.visibility == View.VISIBLE && currentTab != "menu" -> goTab("menu")
            else -> super.onBackPressed()
        }
    }

    private fun goTab(key: String) {
        currentTab = key
        subType = null; subValue = null
        searchShown = false
        searchQuery = ""
        updatingSearch = true
        searchInput.setText("")
        updatingSearch = false
        rebuildContent()
    }

    private fun revealSearch() {
        searchShown = true
        updatingSearch = true
        searchInput.setText(searchQuery)
        updatingSearch = false
        rebuildContent()
        handler.post {
            searchInput.requestFocus()
            val imm = getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager
            imm.showSoftInput(searchInput, InputMethodManager.SHOW_IMPLICIT)
        }
    }

    override fun onDestroy() {
        marqueeHandler.removeCallbacksAndMessages(null)
        handler.removeCallbacksAndMessages(null)
        super.onDestroy()
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
        } else if (requestCode == TagEditorDialog.REQ_COVER && resultCode == RESULT_OK && data?.data != null) {
            TagEditorDialog.applyCoverFromUri(this, data.data!!)
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
            row.setTextColor(if (isCur) ACCENT else WHITE)
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
        tv.setTextColor(WHITE)
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
            none.setPadding(0, dp(6), 0, dp(6))
            foldersBox.addView(none)
            return
        }
        for (u in trees) {
            val row = LinearLayout(this); row.orientation = LinearLayout.HORIZONTAL
            row.gravity = Gravity.CENTER_VERTICAL
            row.background = pillBg(CARD_BG, 12)
            row.setPadding(dp(12), dp(10), dp(6), dp(10))
            val name = DocumentFile.fromTreeUri(this, Uri.parse(u))?.name ?: u
            val tv = TextView(this); tv.text = name
            tv.setTextColor(WHITE)
            tv.setSingleLine(true); tv.ellipsize = TextUtils.TruncateAt.MIDDLE
            row.addView(tv, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            val del = Button(this); del.text = "✕"
            del.setTextColor(GRAY)
            del.background = pressCircle()
            del.setOnClickListener {
                trees.remove(u)
                prefs().edit().putStringSet("trees", trees).apply()
                try { contentResolver.releasePersistableUriPermission(Uri.parse(u), Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION) } catch (e: Exception) { }
                renderFolders(); startScan()
            }
            row.addView(del, LinearLayout.LayoutParams(dp(36), dp(36)))
            val lp = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
            lp.setMargins(0, dp(8), 0, 0); row.layoutParams = lp
            foldersBox.addView(row)
        }
    }

    private fun pickFolder() {
        val i = Intent(Intent.ACTION_OPEN_DOCUMENT_TREE)
        i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION or Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)
        startActivityForResult(i, REQ_TREE)
    }

    private fun startScan() {
        if (scanRunning) return
        scanRunning = true
        status.visibility = View.VISIBLE
        status.text = "Сканирую папки..."
        Thread {
            try {
                val files = mutableListOf<DocumentFile>()
                for (u in trees) {
                    val tree = DocumentFile.fromTreeUri(this, Uri.parse(u)) ?: continue
                    walk(tree, files, 0)
                }
                val total = minOf(files.size, 5000)
                runOnUiThread { status.text = "Сканирую: 0/$total" }
                val seen = mutableSetOf<String>()
                val out = mutableListOf<Track>()
                var i = 0
                for (f in files) {
                    val key = f.uri.toString()
                    if (seen.add(key) && out.size < 5000) out.add(readTrack(f))
                    i++
                    if (i % 8 == 0) {
                        val done = i
                        runOnUiThread { status.text = "Сканирую: $done/$total" }
                    }
                }
                tracks = out
                saveLibraryCache()
                runOnUiThread {
                    status.visibility = View.GONE
                    scanRunning = false
                    rebuildContent()
                }
            } catch (ex: Throwable) {
                val err = android.util.Log.getStackTraceString(ex)
                runOnUiThread {
                    scanRunning = false
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
            else {
                val ext = (f.name?.substringAfterLast('.', "") ?: "").lowercase()
                if (AUDIO_EXTS.contains(ext)) out.add(f)
            }
        }
    }

    private fun readTrack(f: DocumentFile): Track {
        val ext = (f.name?.substringAfterLast('.', "") ?: "").lowercase()
        val formatLabel = when (ext) {
            "flac" -> "FLAC"
            "ogg", "oga" -> "OGG"
            "opus" -> "OPUS"
            else -> "MP3"
        }
        var title = f.name?.removeSuffix(".${f.name?.substringAfterLast('.')}") ?: f.name ?: "Без названия"
        var artist = "Неизвестный исполнитель"
        var album = "Неизвестный альбом"
        var genre = ""
        var durationMs = 0L
        var tags = emptyList<String>()
        var coverPath: String? = null
        var lyrics: String? = null
        var year = ""
        var sampleRate = 0
        var bitDepth = 0
        var streamBitrate = 0
        val fileSize = runCatching { f.length() }.getOrDefault(0L)
        val fileMod = runCatching { f.lastModified() }.getOrDefault(0L)

        if (ext == "mp3") {
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
                        year = r.year ?: ""
                        tags = r.comment?.let { TagParser.extractTags(it) } ?: emptyList()
                        r.cover?.let { bytes ->
                            if (bytes.isNotEmpty()) {
                                val cf = File(cacheDir, "cov_" + Integer.toHexString(f.uri.toString().hashCode()) + "_" + fileMod + ".img")
                                cf.writeBytes(bytes)
                                coverPath = cf.absolutePath
                            }
                        }
                    }
                }
            } catch (e: Exception) { }
        } else {
            try {
                val ex = MediaExtractor()
                ex.setDataSource(this, f.uri, null)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                    val mmr = MediaMetadataRetriever()
                    mmr.setDataSource(this, f.uri)
                    mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_TITLE)?.let { if (it.isNotBlank()) title = it }
                    mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ARTIST)?.let { if (it.isNotBlank()) artist = it }
                    mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ALBUM)?.let { if (it.isNotBlank()) album = it }
                    mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_GENRE)?.let { if (it.isNotBlank()) genre = it }
                    mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DATE)?.let { year = it.take(4) }
                    mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull()?.let { durationMs = it }
                    val pic = mmr.embeddedPicture
                    if (pic != null && pic.isNotEmpty()) {
                        val cf = File(cacheDir, "cov_" + Integer.toHexString(f.uri.toString().hashCode()) + "_" + fileMod + ".img")
                        cf.writeBytes(pic)
                        coverPath = cf.absolutePath
                    }
                    mmr.release()
                } else {
                    for (i in 0 until ex.trackCount) {
                        val mf = ex.getTrackFormat(i)
                        if (mf.containsKey(MediaFormat.KEY_DURATION)) {
                            durationMs = mf.getLong(MediaFormat.KEY_DURATION) / 1000
                            break
                        }
                    }
                }
                ex.release()
            } catch (e: Exception) { }
            try {
                val tmp = File(cacheDir, "head_" + Integer.toHexString(f.uri.toString().hashCode()) + "_" + fileMod + "." + ext)
                contentResolver.openInputStream(f.uri)?.use { ins ->
                    FileOutputStream(tmp).use { out ->
                        val buf = ByteArray(64 * 1024)
                        var left = HEAD_BYTES
                        while (left > 0) {
                            val r = ins.read(buf, 0, minOf(buf.size, left))
                            if (r <= 0) break
                            out.write(buf, 0, r)
                            left -= r
                        }
                    }
                }
                val af = AudioFileIO.read(tmp)
                val cmt = af.tag?.getFirst(FieldKey.COMMENT) ?: ""
                tags = TagParser.extractTags(cmt)
                tmp.delete()
            } catch (e: Exception) { }
        }

        if (durationMs == 0L) {
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                    val mmr = MediaMetadataRetriever()
                    mmr.setDataSource(this, f.uri)
                    mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull()?.let { durationMs = it }
                    mmr.release()
                }
            } catch (e: Exception) { }
        }

        try {
            val ex = MediaExtractor()
            ex.setDataSource(this, f.uri, null)
            for (i in 0 until ex.trackCount) {
                val mf = ex.getTrackFormat(i)
                val mime = mf.getString(MediaFormat.KEY_MIME) ?: continue
                if (!mime.startsWith("audio/")) continue
                if (mf.containsKey(MediaFormat.KEY_SAMPLE_RATE)) sampleRate = mf.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                if (mf.containsKey(MediaFormat.KEY_PCM_ENCODING)) {
                    bitDepth = when (mf.getInteger(MediaFormat.KEY_PCM_ENCODING)) {
                        2 -> 16; 5 -> 24; 6 -> 32; else -> 0
                    }
                }
                if (mf.containsKey(MediaFormat.KEY_BIT_RATE)) streamBitrate = mf.getInteger(MediaFormat.KEY_BIT_RATE) / 1000
                break
            }
            ex.release()
        } catch (e: Exception) { }

        var bitrate = streamBitrate
        if (bitrate <= 0 && durationMs > 0) {
            if (fileSize > 0) bitrate = (fileSize * 8 / durationMs).toInt()
        }
        return Track(f.uri.toString(), title, artist, tags, coverPath, album, genre, durationMs, bitrate, lyrics, year, formatLabel, sampleRate, bitDepth, fileSize, fileMod)
    }

    private fun rebuildContent(resetLimit: Boolean = true) {
        headTitle.text = when {
            currentTab == "menu" -> "Медиатека"
            currentTab == "recent" -> "Недавно играло"
            currentTab == "top" -> "Топ треков"
            subType != null -> subValue ?: ""
            currentTab == "tracks" -> "Треки"
            currentTab == "albums" -> "Альбомы"
            currentTab == "artists" -> "Исполнители"
            currentTab == "genres" -> "Жанры"
            else -> "Теги"
        }
        sortBtn.visibility = if (currentTab in listOf("tracks", "albums", "artists", "genres", "recent", "top")) View.VISIBLE else View.GONE
        contentBox.removeAllViews()
        if (searchShown) {
            (searchInput.parent as? ViewGroup)?.removeView(searchInput)
            val slp = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
            slp.setMargins(0, dp(4), 0, dp(10))
            contentBox.addView(searchInput, slp)
        }
        contentBox.addView(rowsBox, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        rebuildRows(resetLimit)
    }

    private fun applySort(list: List<Track>, section: String): List<Track> {
        val sortKey = prefs().getInt("sort_$section", 0)
        return when (section) {
            "tracks", "recent", "top" -> when (sortKey) {
                1 -> list.sortedBy { it.year }
                2 -> list.sortedByDescending { it.durationMs }
                3 -> list.sortedByDescending { it.bitrateKbps }
                else -> list.sortedBy { it.title }
            }
            "albums", "artists", "genres" -> when (sortKey) {
                1 -> list.sortedByDescending { it.durationMs }
                else -> list.sortedBy { it.title }
            }
            else -> list
        }
    }

    private fun rebuildRows(resetLimit: Boolean = true) {
        if (resetLimit) visibleLimit = 100
        rowsBox.removeAllViews()
        pageTotal = 0
        val q = searchQuery.trim()
        when (currentTab) {
            "menu" -> addMenuRows()
            "recent" -> {
                val stats = Stats.load(this)
                shown = applySort(
                    tracks.filter { stats.containsKey(it.uri) && matches(it) }
                        .sortedByDescending { stats[it.uri]?.second ?: 0L }
                        .take(50),
                    "recent"
                )
                pageTotal = shown.size
                addTrackRows(shown)
            }
            "top" -> {
                val stats = Stats.load(this)
                shown = applySort(
                    tracks.filter { stats.containsKey(it.uri) && matches(it) }
                        .sortedByDescending { stats[it.uri]?.first ?: 0 }
                        .take(50),
                    "top"
                )
                pageTotal = shown.size
                addTrackRows(shown)
            }
            "tracks" -> {
                shown = applySort(tracks.filter { matches(it) }, "tracks")
                pageTotal = shown.size
                addTrackRows(shown)
            }
            "albums" -> {
                if (subType == "album") {
                    shown = subTracks { it.album == subValue && matches(it) }
                    pageTotal = shown.size
                    addTrackRows(shown)
                } else {
                    val groups = tracks.groupBy { it.album }.toSortedMap()
                        .filter { (name, list) -> q.isEmpty() || name.contains(q, true) || list.any { matches(it) } }
                    pageTotal = groups.size
                    addAlbumRows(groups)
                }
            }
            "artists" -> {
                if (subType == "artist") {
                    shown = subTracks { it.artist == subValue && matches(it) }
                    pageTotal = shown.size
                    addTrackRows(shown)
                } else {
                    val groups = tracks.groupBy { it.artist }.toSortedMap()
                        .filter { (name, list) -> q.isEmpty() || name.contains(q, true) || list.any { matches(it) } }
                    pageTotal = groups.size
                    addNameRows(groups, "artist")
                }
            }
            "genres" -> {
                if (subType == "genre") {
                    shown = subTracks { (it.genre.ifEmpty { "(без жанра)" }) == subValue && matches(it) }
                    pageTotal = shown.size
                    addTrackRows(shown)
                } else {
                    val groups = tracks.groupBy { it.genre.ifEmpty { "(без жанра)" } }.toSortedMap()
                        .filter { (name, list) -> q.isEmpty() || name.contains(q, true) || list.any { matches(it) } }
                    pageTotal = groups.size
                    addNameRows(groups, "genre")
                }
            }
            "tags" -> addTagList()
        }
        highlightCurrent()
    }

    private fun addPaged(list: List<Track>) {
        for (t in list.take(visibleLimit)) addTrackRow(t)
    }

    private fun addMenuRows() {
        val totalMin = tracks.sumOf { it.durationMs } / 60000
        val durStr = if (totalMin >= 60) "${totalMin / 60} ч ${totalMin % 60} мин" else "$totalMin мин"
        val tagCount = tracks.flatMap { it.tags }.distinct().size
        val stats = Stats.load(this)
        val recentCount = stats.size
        val topCount = stats.values.count { it.first > 1 }

        val items = listOf(
            Triple("tracks", "Треки", tracks.size),
            Triple("albums", "Альбомы", tracks.map { it.album }.distinct().size),
            Triple("artists", "Исполнители", tracks.map { it.artist }.distinct().size),
            Triple("genres", "Жанры", tracks.map { it.genre.ifEmpty { "(без жанра)" } }.distinct().size),
            Triple("recent", "Недавно играло", recentCount),
            Triple("top", "Топ треков", topCount),
            Triple("tags", "Теги", tagCount)
        )
        for ((key, lbl, count) in items) {
            val row = LinearLayout(this)
            row.orientation = LinearLayout.HORIZONTAL
            row.gravity = Gravity.CENTER_VERTICAL
            row.setPadding(dp(8), dp(8), dp(8), dp(8))
            row.background = null
            val cover = TextView(this)
            cover.text = count.toString()
            cover.textSize = 22f
            cover.setTypeface(Typeface.DEFAULT_BOLD)
            cover.setTextColor(WHITE)
            cover.gravity = Gravity.CENTER
            cover.background = pillBg(ACCENT, 10)
            roundCorners(cover, 10)
            row.addView(cover, LinearLayout.LayoutParams(dp(72), dp(72)))
            val name = TextView(this); name.text = lbl
            name.textSize = 26f; name.setTextColor(WHITE)
            name.setSingleLine(true); name.ellipsize = TextUtils.TruncateAt.END
            name.setPadding(dp(12), 0, dp(6), 0)
            row.addView(name, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            val lp = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
            lp.setMargins(0, 0, 0, dp(8)); row.layoutParams = lp
            row.tag = "header"
            row.setOnClickListener { goTab(key) }
            rowsBox.addView(row)
        }

        val untagged = tracks.filter { it.tags.isEmpty() }.sortedBy { it.title }
        shown = untagged
        pageTotal = untagged.size
        if (untagged.isNotEmpty()) {
            val h = TextView(this)
            h.text = "Без тегов: ${untagged.size}"
            h.setTextColor(Color.parseColor("#BBBBCC"))
            h.setPadding(0, dp(6), 0, dp(8))
            h.tag = "header"
            rowsBox.addView(h)
            addPaged(untagged)
        }

        val passport = TextView(this)
        passport.text = "Всего: ${tracks.size} треков · $durStr · папок: ${trees.size} · тегов: $tagCount"
        passport.textSize = 12f
        passport.setTextColor(Color.parseColor("#8D81A3"))
        passport.setPadding(dp(4), dp(6), dp(4), 0)
        passport.tag = "header"
        rowsBox.addView(passport)
    }

    private fun subTracks(pred: (Track) -> Boolean): List<Track> = tracks.filter(pred).sortedBy { it.title }

    private fun addTrackRow(t: Track) {
        val rowL = LinearLayout(this)
        rowL.orientation = LinearLayout.HORIZONTAL
        rowL.gravity = Gravity.CENTER_VERTICAL
        rowL.setPadding(dp(8), dp(8), dp(4), dp(8))
        rowL.background = rowBg(t.uri == PlayerState.uri)
        rowL.tag = "track:" + t.uri

        val iv = ImageView(this)
        iv.scaleType = ImageView.ScaleType.CENTER_CROP
        iv.setBackgroundColor(Color.parseColor("#2A2A3A"))
        roundCorners(iv, 10)
        bindThumb(iv, t.coverPath)
        rowL.addView(iv, LinearLayout.LayoutParams(dp(72), dp(72)))

        val col = LinearLayout(this); col.orientation = LinearLayout.VERTICAL
        col.setPadding(dp(10), 0, dp(6), 0)
        val l1 = TextView(this); l1.text = highlight(t.title); l1.textSize = 15f; l1.setTextColor(WHITE)
        l1.setSingleLine(true); l1.ellipsize = TextUtils.TruncateAt.END
        val l2 = TextView(this); l2.text = highlight(t.artist); l2.textSize = 13f; l2.setTextColor(Color.parseColor("#BBBBCC"))
        l2.setSingleLine(true); l2.ellipsize = TextUtils.TruncateAt.END
        val l3 = TextView(this); l3.text = highlight(t.album + (if (t.year.isNotEmpty()) " · ${t.year}" else ""))
        l3.textSize = 12f; l3.setTextColor(Color.parseColor("#9A93A8"))
        l3.setSingleLine(true); l3.ellipsize = TextUtils.TruncateAt.END
        val l4 = LinearLayout(this); l4.orientation = LinearLayout.HORIZONTAL; l4.gravity = Gravity.CENTER_VERTICAL
        val fmtB = TextView(this); fmtB.text = t.format; fmtB.textSize = 11f
        fmtB.setTypeface(Typeface.DEFAULT_BOLD)
        fmtB.setTextColor(WHITE)
        fmtB.background = pillBg(bubbleColor(t.format), 99)
        fmtB.setPadding(dp(8), dp(2), dp(8), dp(2))
        val specTv = TextView(this); specTv.text = specsString(t); specTv.textSize = 11f
        specTv.setTextColor(WHITE)
        specTv.setPadding(dp(6), 0, 0, 0)
        val tagsTv = TextView(this)
        tagsTv.text = highlight(t.tags.joinToString(" ") { "#$it" })
        tagsTv.textSize = 11f; tagsTv.setTextColor(ACCENT)
        tagsTv.setSingleLine(true); tagsTv.ellipsize = TextUtils.TruncateAt.END
        tagsTv.setPadding(dp(6), 0, 0, 0)
        l4.addView(fmtB)
        if (specTv.text.isNotEmpty()) l4.addView(specTv)
        l4.addView(tagsTv, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        col.addView(l1); col.addView(l2); col.addView(l3); col.addView(l4)
        rowL.addView(col, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))

        val editBtn = IconView(this); editBtn.icon = "tags"; editBtn.setColor(GRAY)
        editBtn.background = pressCircle()
        editBtn.setOnClickListener { openEditor(t) }
        rowL.addView(editBtn, LinearLayout.LayoutParams(dp(30), dp(30)))

        val lp = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
        lp.setMargins(0, 0, 0, dp(8)); rowL.layoutParams = lp
        rowL.setOnClickListener { play(t) }
        rowL.setOnLongClickListener { openEditor(t); true }
        rowsBox.addView(rowL)
    }

    private fun addTrackRows(list: List<Track>) {
        shown = list
        if (list.isEmpty()) { emptyMsg("Ничего не найдено."); return }
        addPaged(list)
    }

    private fun addAlbumRows(groups: Map<String, List<Track>>) {
        shown = emptyList()
        if (groups.isEmpty()) { emptyMsg("Ничего не найдено."); return }
        for ((name, list) in groups) {
            val first = list.first()
            val rowL = LinearLayout(this)
            rowL.orientation = LinearLayout.HORIZONTAL
            rowL.gravity = Gravity.CENTER_VERTICAL
            rowL.setPadding(dp(8), dp(8), dp(4), dp(8))
            rowL.background = null
            val iv = ImageView(this)
            iv.scaleType = ImageView.ScaleType.CENTER_CROP
            iv.setBackgroundColor(Color.parseColor("#2A2A3A"))
            roundCorners(iv, 10)
            bindThumb(iv, first.coverPath)
            rowL.addView(iv, LinearLayout.LayoutParams(dp(72), dp(72)))
            val col = LinearLayout(this); col.orientation = LinearLayout.VERTICAL
            col.setPadding(dp(10), 0, dp(6), 0)
            val l1 = TextView(this); l1.text = highlight(name); l1.textSize = 15f; l1.setTextColor(WHITE)
            l1.setSingleLine(true); l1.ellipsize = TextUtils.TruncateAt.END
            val artists = list.map { it.artist }.distinct()
            val l2 = TextView(this); l2.text = highlight(if (artists.size == 1) artists[0] else "Разные исполнители")
            l2.textSize = 13f; l2.setTextColor(Color.parseColor("#BBBBCC"))
            l2.setSingleLine(true); l2.ellipsize = TextUtils.TruncateAt.END
            val yr = list.map { it.year }.firstOrNull { it.isNotEmpty() } ?: ""
            val l3 = TextView(this); l3.text = if (yr.isNotEmpty()) yr else "Год не указан"
            l3.textSize = 12f; l3.setTextColor(Color.parseColor("#9A93A8"))
            val l4 = LinearLayout(this); l4.orientation = LinearLayout.HORIZONTAL; l4.gravity = Gravity.CENTER_VERTICAL
            val fmtB = TextView(this); fmtB.text = first.format; fmtB.textSize = 11f
            fmtB.setTypeface(Typeface.DEFAULT_BOLD)
            fmtB.setTextColor(WHITE)
            fmtB.background = pillBg(bubbleColor(first.format), 99)
            fmtB.setPadding(dp(8), dp(2), dp(8), dp(2))
            val specTv = TextView(this); specTv.text = specsString(first); specTv.textSize = 11f
            specTv.setTextColor(WHITE)
            specTv.setPadding(dp(6), 0, 0, 0)
            val cnt = TextView(this); cnt.text = "${list.size} треков"; cnt.textSize = 11f
            cnt.setTextColor(Color.parseColor("#9A93A8")); cnt.setPadding(dp(6), 0, 0, 0)
            l4.addView(fmtB)
            if (specTv.text.isNotEmpty()) l4.addView(specTv)
            l4.addView(cnt)
            col.addView(l1); col.addView(l2); col.addView(l3); col.addView(l4)
            rowL.addView(col, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            val lp = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
            lp.setMargins(0, 0, 0, dp(8)); rowL.layoutParams = lp
            rowL.setOnClickListener {
                subType = "album"; subValue = name
                searchShown = false; searchQuery = ""
                updatingSearch = true; searchInput.setText(""); updatingSearch = false
                rebuildContent()
            }
            rowsBox.addView(rowL)
        }
    }

    private fun addNameRows(groups: Map<String, List<Track>>, type: String) {
        shown = emptyList()
        if (groups.isEmpty()) { emptyMsg("Ничего не найдено."); return }
        for ((name, list) in groups) {
            val row = TextView(this)
            row.text = highlight("$name  (${list.size})")
            row.setPadding(dp(12), dp(12), dp(12), dp(12))
            row.background = null
            row.setTextColor(WHITE)
            val lp = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
            lp.setMargins(0, 0, 0, dp(8)); row.layoutParams = lp
            row.tag = "group"
            row.setOnClickListener {
                subType = type; subValue = name
                searchShown = false; searchQuery = ""
                updatingSearch = true; searchInput.setText(""); updatingSearch = false
                rebuildContent()
            }
            rowsBox.addView(row)
        }
    }

    private fun addTagList() {
        val base = tracks.filter { t -> selected.all { g -> t.tags.contains(g) } }
        val counts = mutableMapOf<String, Int>()
        for (t in base) for (g in t.tags) counts[g] = (counts[g] ?: 0) + 1
        for (g in selected) if (!counts.containsKey(g)) counts[g] = 0
        val q = searchQuery.trim()
        val visibleTags = counts.keys.filter { q.isEmpty() || it.contains(q, true) }

        if (visibleTags.isEmpty()) {
            shown = emptyList()
            emptyMsg(if (counts.isEmpty()) "Тегов пока нет. Кнопка бирки на карточке трека откроет редактор." else "Ничего не найдено.")
            return
        }

        val ordered = selected.filter { visibleTags.contains(it) } + visibleTags.sorted().filter { !selected.contains(it) }
        val flow = FlowLayout(this)
        flow.centerRows = true
        flow.tag = "header"
        for (tag in ordered) {
            val chip = TextView(this)
            chip.text = highlight("#$tag (${counts[tag]})")
            chip.setPadding(dp(12), dp(8), dp(12), dp(8))
            val on = selected.contains(tag)
            chip.background = pillBg(if (on) ACCENT else BUBBLE_BG, 99)
            chip.setTextColor(WHITE)
            val lp = ViewGroup.MarginLayoutParams(ViewGroup.MarginLayoutParams.WRAP_CONTENT, ViewGroup.MarginLayoutParams.WRAP_CONTENT)
            lp.setMargins(0, 0, dp(6), dp(6)); chip.layoutParams = lp
            chip.tag = "header"
            chip.setOnClickListener {
                if (selected.contains(tag)) selected.remove(tag) else selected.add(tag)
                rebuildContent()
            }
            flow.addView(chip)
        }
        rowsBox.addView(flow)

        shown = base.filter { matches(it) }.shuffled()
        pageTotal = shown.size
        val h = TextView(this)
        h.text = if (selected.isEmpty()) "Плейлист: все треки (${shown.size}), шафл"
                 else "Пересечение ${selected.joinToString(" ∩ ") { "#$it" }}: ${shown.size}, шафл"
        h.setTextColor(Color.parseColor("#BBBBCC"))
        h.setPadding(0, dp(8), 0, dp(8))
        h.tag = "header"
        rowsBox.addView(h)
        addPaged(shown)
        if (shown.isEmpty()) emptyMsg("Пересечение пусто: сними часть тегов.")
    }

    private fun emptyMsg(msg: String) {
        val empty = TextView(this)
        empty.text = msg
        empty.setPadding(0, dp(16), 0, 0)
        empty.tag = "header"
        rowsBox.addView(empty)
    }

    private fun highlightCurrent() {
        val cur = "track:" + PlayerState.uri
        for (i in 0 until rowsBox.childCount) {
            val v = rowsBox.getChildAt(i)
            val tag = v.tag as? String ?: continue
            if (tag.startsWith("track:")) {
                v.background = rowBg(tag == cur)
            }
        }
    }

    private fun openEditor(t: Track) {
        TagEditorDialog.show(this, t) { draft ->
            TagEditorDialog.write(this, t, draft) { ok, msg ->
                Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
                if (ok) {
                    val df = DocumentFile.fromSingleUri(this, Uri.parse(t.uri))
                    if (df != null) {
                        val newTrack = readTrack(df)
                        tracks = tracks.map { if (it.uri == t.uri) newTrack else it }
                        saveLibraryCache()
                        rebuildContent()
                        refreshNow()
                    }
                }
            }
        }
    }

    private fun rebuildPlayerMeta() {
        val t = curTrack()
        PlayerState.coverPath = t?.coverPath
        pTitle.text = if (PlayerState.error != null) "⚠ " + PlayerState.error else PlayerState.title.ifEmpty { "Ничего не играет" }
        pArtist.text = PlayerState.artist
        pAlbum.text = t?.album ?: ""
        val fmtLabel = t?.format ?: ""
        pFormat.text = fmtLabel
        pFormat.background = pillBg(bubbleColor(fmtLabel), 99)
        val specs = t?.let { specsString(it) } ?: ""
        pSpecs.text = specs
        pSpecs.visibility = if (specs.isEmpty()) View.GONE else View.VISIBLE
        tagsRow.removeAllViews()
        val tags = t?.tags ?: emptyList()
        for (g in tags) {
            val chip = TextView(this)
            chip.text = "#$g"
            chip.setPadding(dp(12), dp(6), dp(12), dp(6))
            chip.background = pillBg(BUBBLE_BG, 99)
            chip.setTextColor(Color.WHITE)
            val lp = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT)
            lp.setMargins(0, 0, dp(6), 0); chip.layoutParams = lp
            chip.setOnClickListener {
                selected.clear(); selected.add(g)
                currentTab = "tags"; subType = null; subValue = null
                showLibrary(); rebuildContent()
            }
            tagsRow.addView(chip)
        }
        waveView.setPeaks(null)
        waveView.setProgress(0f)
        val uri = PlayerState.uri
        val durForWave = t?.durationMs ?: 0L
        if (uri != null) {
            Thread {
                val peaks = WaveformView.getPeaks(this, uri, durForWave)
                runOnUiThread { if (PlayerState.uri == uri) waveView.setPeaks(peaks) }
            }.start()
        }
        if (uri != null) loadBigCover(uri)
    }

    private fun loadBigCover(uri: String) {
        bigCoverUri = uri
        val path = curTrack()?.coverPath
        Thread {
            val bm = loadBitmap(path, 720)
            runOnUiThread {
                bigCoverBm = bm
                if (bigCoverUri == uri && playerScreen.visibility == View.VISIBLE) {
                    showCoverBig(bm)
                }
            }
        }.start()
    }

    private fun refreshNow() {
        val playing = PlayerState.playing
        val t = curTrack()
        nbTitle.text = when {
            PlayerState.error != null -> "⚠ " + PlayerState.error
            PlayerState.uri == null -> "Ничего не играет"
            else -> PlayerState.title
        }
        nbArtist.text = PlayerState.artist
        val fmtLabel = t?.format ?: ""
        nbFormat.text = fmtLabel
        nbFormat.background = pillBg(bubbleColor(fmtLabel), 99)
        val nspecs = t?.let { specsString(it) } ?: ""
        nbSpecs.text = nspecs
        nbSpecs.visibility = if (nspecs.isEmpty()) View.GONE else View.VISIBLE
        nbPlay.icon = if (playing) "pause" else "play"
        if (playing) nbBar.visibility = View.VISIBLE
        playI.icon = if (playing) "pause" else "play"
        shufI.icon = if (PlayerState.shuffleOn) "shuffle" else "shuffleOff"
        shufI.setColor(if (PlayerState.shuffleOn) ACCENT else GRAY)
        repI.icon = if (PlayerState.repeatMode == 2) "repeat1" else "repeat"
        repI.setColor(if (PlayerState.repeatMode > 0) ACCENT else GRAY)
        if (PlayerState.uri == null) {
            PlayerState.coverPath = null
            nbCover.setImageResource(0); nbCover.rotation = 0f
            showCoverBig(null)
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
        PlayerState.coverPath = t.coverPath
        PlayerService.cmd(this, "play", items, shown.indexOf(t))
        nbBar.visibility = View.VISIBLE
        bigCoverUri = null; bigCoverBm = null
        Thread {
            val small = loadBitmap(t.coverPath, 192)
            runOnUiThread { nbCover.setImageBitmap(small) }
        }.start()
        if (playerScreen.visibility == View.VISIBLE) { lastPlayerUri = null; refreshNow() }
        highlightCurrent()
    }
}
