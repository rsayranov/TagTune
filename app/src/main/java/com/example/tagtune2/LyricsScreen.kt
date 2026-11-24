package com.example.tagtune2

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.Outline
import android.graphics.RenderEffect
import android.graphics.Shader
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.content.res.ColorStateList
import android.net.Uri
import android.os.Build
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.View
import android.view.ViewOutlineProvider
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import org.jaudiotagger.audio.AudioFileIO
import org.jaudiotagger.tag.FieldKey
import java.io.File
import java.io.FileOutputStream
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

data class LrcLine(val timeMs: Long, val text: String)

object LrcParser {
    private val timeRe = Regex("\\[(\\d{1,2}):(\\d{2})(?:[.:](\\d{1,3}))?]")
    private val wordRe = Regex("<\\d{1,2}:\\d{2}(?:[.:]\\d{1,3})?>")
    private val voiceRe = Regex("^\\s*v\\d+\\s*:", RegexOption.IGNORE_CASE)

    fun parse(raw: String): List<LrcLine> {
        val out = mutableListOf<LrcLine>()
        for (rawLine in raw.lines()) {
            val stamps = timeRe.findAll(rawLine).toList()
            if (stamps.isEmpty()) continue
            var text = rawLine.substring(stamps.last().range.last + 1)
            text = wordRe.replace(text, "")
            text = voiceRe.replace(text, "")
            text = text.trim()
            if (text.isEmpty()) text = "♪"
            for (m in stamps) {
                val min = m.groupValues[1].toLongOrNull() ?: 0L
                val sec = m.groupValues[2].toLongOrNull() ?: 0L
                val fs = m.groupValues[3]
                val frac = when (fs.length) {
                    1 -> (fs.toLongOrNull() ?: 0L) * 100
                    2 -> (fs.toLongOrNull() ?: 0L) * 10
                    3 -> (fs.toLongOrNull() ?: 0L)
                    else -> 0L
                }
                out.add(LrcLine(min * 60000 + sec * 1000 + frac, text))
            }
        }
        return out.sortedBy { it.timeMs }
    }
}

class LyricsScreen(ctx: Context) : LinearLayout(ctx) {

    var onClose: (() -> Unit)? = null
    var onSeek: ((Int) -> Unit)? = null
    var onPrev: (() -> Unit)? = null
    var onNext: (() -> Unit)? = null
    var onToggle: (() -> Unit)? = null
    var onQueue: (() -> Unit)? = null
    var onEditor: (() -> Unit)? = null

    private var lines = listOf<LrcLine>()
    private var timed = false
    private var curIdx = -1
    private var openUri: String? = null
    private val lineViews = mutableListOf<TextView>()

    private val accents = intArrayOf(
        Color.parseColor("#FF4D6D"), Color.parseColor("#7C4DFF"), Color.parseColor("#3DDC84"),
        Color.parseColor("#FF9800"), Color.parseColor("#40C4FF"), Color.parseColor("#C2185B")
    )
    private fun accent(): Int = accents[
        context.getSharedPreferences("tagtune2", Context.MODE_PRIVATE)
            .getInt("accent_idx", 0).coerceIn(0, accents.size - 1)
    ]

    private val scroll = ScrollView(ctx)
    private val listBox = LinearLayout(ctx)
    private val headerCover = ImageView(ctx)
    private val headerTitle = TextView(ctx)
    private val headerArtist = TextView(ctx)
    private val wave = WaveformView(ctx)
    private val tCur = TextView(ctx)
    private val tLeft = TextView(ctx)
    private val fmtTv = TextView(ctx)
    private val shufI = IconView(ctx)
    private val playI = IconView(ctx)
    private val repI = IconView(ctx)
    private val bLyrics = IconView(ctx)

    private fun dp(n: Int) = (resources.displayMetrics.density * n).toInt()
    private fun fmt(ms: Long): String {
        val s = max(0L, ms) / 1000
        return "${s / 60}:${(s % 60).toString().padStart(2, '0')}"
    }

    init {
        orientation = VERTICAL
        visibility = GONE
        setPadding(dp(20), dp(10), dp(20), dp(14))

        val handleRow = LinearLayout(ctx)
        handleRow.orientation = VERTICAL
        handleRow.gravity = Gravity.CENTER_HORIZONTAL
        val handle = View(ctx)
        handle.background = GradientDrawable().apply {
            setColor(Color.argb(90, 255, 255, 255))
            cornerRadius = dp(2).toFloat()
        }
        handleRow.addView(handle, LinearLayout.LayoutParams(dp(40), dp(4)))
        val chev = IconView(ctx)
        chev.icon = "chevronDown"; chev.setColor(Color.argb(160, 255, 255, 255))
        chev.setOnClickListener { performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK); onClose?.invoke() }
        handleRow.addView(chev, LinearLayout.LayoutParams(dp(30), dp(22)))
        handle.setOnClickListener { onClose?.invoke() }
        addView(handleRow, LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))

        val head = LinearLayout(ctx)
        head.orientation = HORIZONTAL
        head.gravity = Gravity.CENTER_VERTICAL
        head.setPadding(0, dp(10), 0, dp(6))
        headerCover.scaleType = ImageView.ScaleType.CENTER_CROP
        headerCover.setBackgroundColor(Color.parseColor("#2A2A3A"))
        headerCover.clipToOutline = true
        headerCover.outlineProvider = object : ViewOutlineProvider() {
            override fun getOutline(v: View, o: Outline) { o.setRoundRect(0, 0, v.width, v.height, dp(8).toFloat()) }
        }
        head.addView(headerCover, LinearLayout.LayoutParams(dp(52), dp(52)))
        val ht = LinearLayout(ctx); ht.orientation = VERTICAL
        ht.setPadding(dp(12), 0, 0, 0)
        headerTitle.textSize = 17f; headerTitle.setTextColor(Color.WHITE)
        headerTitle.setTypeface(Typeface.DEFAULT_BOLD)
        headerTitle.setSingleLine(true); headerTitle.ellipsize = android.text.TextUtils.TruncateAt.END
        headerArtist.textSize = 14f; headerArtist.setTextColor(Color.argb(170, 255, 255, 255))
        headerArtist.setSingleLine(true); headerArtist.ellipsize = android.text.TextUtils.TruncateAt.END
        ht.addView(headerTitle, LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
        ht.addView(headerArtist, LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
        head.addView(ht, LinearLayout.LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f))
        addView(head, LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))

        scroll.isVerticalScrollBarEnabled = false
        listBox.orientation = VERTICAL
        listBox.setPadding(0, dp(30), 0, dp(60))
        scroll.addView(listBox, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT))
        addView(scroll, LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, 0, 1f))

        /* волна остаётся, как в большом плеере */
        wave.setAccent(accent())
        wave.onSeek = { f ->
            val d = PlayerState.durationMs
            if (d > 0) onSeek?.invoke((f * d).toInt())
        }
        val waveLp = LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, dp(40))
        waveLp.topMargin = dp(8)
        addView(wave, waveLp)

        val times = LinearLayout(ctx)
        times.orientation = HORIZONTAL
        times.gravity = Gravity.CENTER_VERTICAL
        val timesLp = LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT)
        timesLp.topMargin = dp(6)
        tCur.textSize = 12f; tCur.setTextColor(Color.argb(170, 255, 255, 255))
        tLeft.textSize = 12f; tLeft.setTextColor(Color.argb(170, 255, 255, 255))
        tLeft.gravity = Gravity.END
        fmtTv.textSize = 12f; fmtTv.setTextColor(Color.argb(150, 255, 255, 255))
        fmtTv.gravity = Gravity.CENTER
        times.addView(tCur, LinearLayout.LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f))
        times.addView(fmtTv, LinearLayout.LayoutParams(0, LayoutParams.WRAP_CONTENT, 2f))
        times.addView(tLeft, LinearLayout.LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f))
        addView(times, timesLp)

        /* транспорт: шафл, назад, play, вперёд, повтор — как в большом плеере */
        val tr = LinearLayout(ctx)
        tr.orientation = HORIZONTAL
        tr.gravity = Gravity.CENTER_VERTICAL
        tr.setPadding(0, dp(14), 0, dp(6))
        shufI.icon = "shuffle"; shufI.setColor(Color.argb(170, 255, 255, 255))
        shufI.setOnClickListener {
            performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
            PlayerService.cmd(context, "toggleShuffle")
        }
        tr.addView(shufI, LinearLayout.LayoutParams(0, dp(32), 1f))
        val prev = IconView(ctx); prev.icon = "prev"; prev.setColor(Color.WHITE)
        prev.setOnClickListener { performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK); onPrev?.invoke() }
        tr.addView(prev, LinearLayout.LayoutParams(0, dp(44), 1f))
        playI.icon = "play"; playI.setColor(Color.WHITE)
        playI.setOnClickListener { performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK); onToggle?.invoke() }
        tr.addView(playI, LinearLayout.LayoutParams(0, dp(56), 1f))
        val next = IconView(ctx); next.icon = "next"; next.setColor(Color.WHITE)
        next.setOnClickListener { performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK); onNext?.invoke() }
        tr.addView(next, LinearLayout.LayoutParams(0, dp(44), 1f))
        repI.icon = "repeat"; repI.setColor(Color.argb(170, 255, 255, 255))
        repI.setOnClickListener {
            performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
            PlayerService.cmd(context, "toggleRepeat")
        }
        tr.addView(repI, LinearLayout.LayoutParams(0, dp(32), 1f))
        addView(tr, LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))

        /* нижний ряд: очередь, текст, теги — порядок как в большом плеере */
        val bottom = LinearLayout(ctx)
        bottom.orientation = HORIZONTAL
        bottom.gravity = Gravity.CENTER_VERTICAL
        bottom.setPadding(0, dp(10), 0, 0)
        val bQueue = IconView(ctx); bQueue.icon = "queue"; bQueue.setColor(Color.argb(200, 255, 255, 255))
        bQueue.setOnClickListener { performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK); onQueue?.invoke() }
        bLyrics.icon = "lyrics"; bLyrics.setColor(accent())
        bLyrics.setOnClickListener { performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK); recenter() }
        val bTags = IconView(ctx); bTags.icon = "tags"; bTags.setColor(Color.argb(200, 255, 255, 255))
        bTags.setOnClickListener { performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK); onEditor?.invoke() }
        bottom.addView(View(ctx), LinearLayout.LayoutParams(0, dp(40), 1f))
        bottom.addView(bQueue, LinearLayout.LayoutParams(0, dp(40), 1f))
        bottom.addView(bLyrics, LinearLayout.LayoutParams(0, dp(44), 1f))
        bottom.addView(bTags, LinearLayout.LayoutParams(0, dp(40), 1f))
        bottom.addView(View(ctx), LinearLayout.LayoutParams(0, dp(40), 1f))
        addView(bottom, LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
    }

    fun open(t: MainActivity.Track?, coverPath: String?) {
        openUri = t?.uri
        val bm = decodeSmall(coverPath)
        headerCover.setImageBitmap(bm)
        headerTitle.text = t?.title ?: "Ничего не играет"
        headerArtist.text = t?.artist ?: ""
        background = GradientDrawable(
            GradientDrawable.Orientation.TOP_BOTTOM,
            intArrayOf(tint(bm, 0.46f), tint(bm, 0.20f))
        )
        wave.setAccent(accent())
        bLyrics.setColor(accent())
        wave.setPeaks(null)
        wave.setProgress(0f)
        listBox.removeAllViews()
        lineViews.clear()
        lines = emptyList()
        timed = false
        curIdx = -1
        val loading = TextView(context)
        loading.text = "Загружаю текст…"
        loading.textSize = 16f
        loading.setTextColor(Color.argb(160, 255, 255, 255))
        loading.gravity = Gravity.CENTER
        loading.setPadding(dp(20), dp(60), dp(20), dp(20))
        listBox.addView(loading, LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
        fmtTv.text = if (t != null) "${t.format} · ${specs(t).replace("/", " · ")}" else ""
        scroll.post { scroll.scrollTo(0, 0) }
        visibility = VISIBLE

        if (t != null) {
            val uri = t.uri
            val dur = PlayerState.durationMs.toLong()
            Thread {
                val peaks = WaveformView.getPeaks(context, uri, dur)
                post { if (openUri == uri) wave.setPeaks(peaks) }
            }.start()
            fetchLyrics(t) { raw ->
                if (openUri == uri) render(raw)
            }
        } else {
            render(null)
        }
    }

    /* текст может лежать не только в USLT mp3: берём LYRICS из тегов любого формата */
    private fun fetchLyrics(t: MainActivity.Track, cb: (String?) -> Unit) {
        if (!t.lyrics.isNullOrBlank()) { cb(t.lyrics); return }
        Thread {
            var res: String? = null
            try {
                val uri = Uri.parse(t.uri)
                val ext = (uri.lastPathSegment?.substringAfterLast('.', "") ?: "").lowercase()
                val tmp = File(context.cacheDir, "lyr_head_" + Integer.toHexString(t.uri.hashCode()) + "." + ext)
                context.contentResolver.openInputStream(uri)?.use { ins ->
                    FileOutputStream(tmp).use { out ->
                        val buf = ByteArray(64 * 1024)
                        var left = 1024 * 1024
                        while (left > 0) {
                            val r = ins.read(buf, 0, minOf(buf.size, left))
                            if (r <= 0) break
                            out.write(buf, 0, r)
                            left -= r
                        }
                    }
                }
                try {
                    val af = AudioFileIO.read(tmp)
                    res = af.tag?.getFirst(FieldKey.LYRICS)?.takeIf { it.isNotBlank() }
                } catch (e: Exception) {
                    if (ext == "m4a") {
                        val full = File(context.cacheDir, "lyr_full_" + Integer.toHexString(t.uri.hashCode()) + ".m4a")
                        context.contentResolver.openInputStream(uri)?.use { ins ->
                            FileOutputStream(full).use { out -> ins.copyTo(out) }
                        }
                        res = AudioFileIO.read(full).tag?.getFirst(FieldKey.LYRICS)?.takeIf { it.isNotBlank() }
                        full.delete()
                    }
                } finally {
                    tmp.delete()
                }
            } catch (_: Exception) { }
            post { cb(res) }
        }.start()
    }

    private fun render(raw: String?) {
        listBox.removeAllViews()
        lineViews.clear()
        curIdx = -1
        lines = if (raw != null) LrcParser.parse(raw) else emptyList()
        timed = lines.isNotEmpty()
        if (raw == null || raw.isBlank()) {
            val none = TextView(context)
            none.text = "Текст не найден в метаданных файла."
            none.textSize = 16f
            none.setTextColor(Color.argb(160, 255, 255, 255))
            none.gravity = Gravity.CENTER
            none.setPadding(dp(20), dp(60), dp(20), dp(20))
            listBox.addView(none, LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
        } else if (!timed) {
            for (l in raw.lines().filter { it.isNotBlank() }) {
                val tv = lineView(l.trim(), false)
                tv.alpha = 0.85f
                listBox.addView(tv)
                lineViews.add(tv)
            }
        } else {
            for (l in lines) {
                val tv = lineView(l.text, true)
                tv.setOnClickListener {
                    performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                    onSeek?.invoke(l.timeMs.toInt())
                }
                listBox.addView(tv)
                lineViews.add(tv)
            }
        }
        scroll.post { scroll.scrollTo(0, 0) }
    }

    private fun specs(t: MainActivity.Track): String {
        val khz = if (t.sampleRateHz > 0) {
            val d = t.sampleRateHz / 1000.0
            if (d == d.toLong().toDouble()) "${d.toLong()}kHz" else "${d}kHz"
        } else ""
        return if (t.format == "FLAC" || t.format == "ALAC") {
            val bits = if (t.bitDepth > 0) "${t.bitDepth}-bit" else null
            listOfNotNull(bits, khz.takeIf { it.isNotEmpty() }).joinToString("/")
        } else {
            listOfNotNull(khz.takeIf { it.isNotEmpty() }, if (t.bitrateKbps > 0) "${t.bitrateKbps}kbps" else null).joinToString("/")
        }
    }

    private fun lineView(text: String, clickable: Boolean): TextView {
        val tv = TextView(context)
        tv.text = text
        tv.textSize = 22f
        tv.setTypeface(Typeface.DEFAULT_BOLD)
        tv.setTextColor(Color.WHITE)
        tv.alpha = 0.35f
        tv.setPadding(dp(6), dp(14), dp(6), dp(14))
        tv.setLineSpacing(dp(4).toFloat(), 1f)
        if (clickable) {
            tv.isClickable = true
            tv.background = RippleDrawable(ColorStateList.valueOf(Color.argb(60, 255, 255, 255)), null, null)
        }
        return tv
    }

    fun tick(posMs: Int, durMs: Int, playing: Boolean) {
        tCur.text = fmt(posMs.toLong())
        tLeft.text = "-" + fmt((durMs - posMs).toLong())
        playI.icon = if (playing) "pause" else "play"
        shufI.icon = if (PlayerState.shuffleOn) "shuffle" else "shuffleOff"
        shufI.setColor(if (PlayerState.shuffleOn) accent() else Color.argb(170, 255, 255, 255))
        repI.icon = if (PlayerState.repeatMode == 2) "repeat1" else "repeat"
        repI.setColor(if (PlayerState.repeatMode > 0) accent() else Color.argb(170, 255, 255, 255))
        if (durMs > 0) wave.setProgress(posMs.toFloat() / durMs)
        if (!timed || lineViews.isEmpty()) return
        var idx = -1
        for (i in lines.indices) {
            if (lines[i].timeMs <= posMs + 200) idx = i else break
        }
        if (idx != curIdx) {
            curIdx = idx
            applyStyles()
            recenter()
        }
    }

    private fun applyStyles() {
        for (i in lineViews.indices) {
            val tv = lineViews[i]
            val d = if (curIdx < 0) 3 else abs(i - curIdx)
            if (d == 0) {
                tv.alpha = 1f
                tv.textSize = 26f
                if (Build.VERSION.SDK_INT >= 31) tv.setRenderEffect(null)
            } else {
                tv.alpha = if (d == 1) 0.55f else 0.32f
                tv.textSize = 22f
                if (Build.VERSION.SDK_INT >= 31) {
                    val r = min(16f, 4f + d * 3f)
                    tv.setRenderEffect(RenderEffect.createBlurEffect(r, r, Shader.TileMode.CLAMP))
                }
            }
        }
    }

    private fun recenter() {
        if (curIdx < 0 || curIdx >= lineViews.size) return
        val tv = lineViews[curIdx]
        scroll.post {
            val target = tv.top - scroll.height / 2 + tv.height / 2
            scroll.smoothScrollTo(0, max(0, target))
        }
    }

    private fun decodeSmall(path: String?): Bitmap? {
        if (path == null) return null
        val f = File(path)
        if (!f.exists()) return null
        return try {
            val o = BitmapFactory.Options()
            o.inJustDecodeBounds = true
            BitmapFactory.decodeFile(path, o)
            var s = 1
            while (o.outWidth / (s * 2) >= 64) s *= 2
            o.inJustDecodeBounds = false
            o.inSampleSize = s
            BitmapFactory.decodeFile(path, o)
        } catch (e: Exception) { null }
    }

    private fun tint(bm: Bitmap?, value: Float): Int {
        if (bm == null) return Color.HSVToColor(floatArrayOf(280f, 0.25f, value))
        val small = Bitmap.createScaledBitmap(bm, 16, 16, true)
        var r = 0L; var g = 0L; var b = 0L
        for (y in 0 until 16) for (x in 0 until 16) {
            val c = small.getPixel(x, y)
            r += Color.red(c); g += Color.green(c); b += Color.blue(c)
        }
        val n = 256L
        val hsv = FloatArray(3)
        Color.RGBToHSV((r / n).toInt(), (g / n).toInt(), (b / n).toInt(), hsv)
        hsv[1] = min(1f, hsv[1] * 1.15f + 0.12f)
        hsv[2] = value
        if (bm !== small) small.recycle()
        return Color.HSVToColor(hsv)
    }
}
