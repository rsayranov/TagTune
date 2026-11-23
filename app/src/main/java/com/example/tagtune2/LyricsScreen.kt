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
import android.media.AudioManager
import android.os.Build
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.View
import android.view.ViewOutlineProvider
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.TextView
import java.io.File
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/* ---------- строка LRC с временем ---------- */
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

/* ---------- полноэкранный текст в стиле Apple Music ---------- */
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
    private var durMs = 0
    private var seeking = false
    private val lineViews = mutableListOf<TextView>()
    private val am = ctx.getSystemService(Context.AUDIO_SERVICE) as AudioManager

    private val scroll = ScrollView(ctx)
    private val listBox = LinearLayout(ctx)
    private val headerCover = ImageView(ctx)
    private val headerTitle = TextView(ctx)
    private val headerArtist = TextView(ctx)
    private val seek = SeekBar(ctx)
    private val tCur = TextView(ctx)
    private val tLeft = TextView(ctx)
    private val fmtTv = TextView(ctx)
    private val playI = IconView(ctx)
    private val volSeek = SeekBar(ctx)

    private fun dp(n: Int) = (resources.displayMetrics.density * n).toInt()
    private fun fmt(ms: Long): String {
        val s = max(0L, ms) / 1000
        return "${s / 60}:${(s % 60).toString().padStart(2, '0')}"
    }

    init {
        orientation = VERTICAL
        visibility = GONE
        setPadding(dp(20), dp(10), dp(20), dp(14))

        /* ручка-шеврон сверху */
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

        /* шапка: обложка + название + исполнитель */
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

        /* лента строк */
        scroll.isVerticalScrollBarEnabled = false
        listBox.orientation = VERTICAL
        listBox.setPadding(0, dp(30), 0, dp(60))
        scroll.addView(listBox, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT))
        addView(scroll, LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, 0, 1f))

        /* прогресс */
        seek.max = 100
        seek.progressDrawable = GradientDrawable().apply {
            setColor(Color.argb(70, 255, 255, 255)); cornerRadius = dp(2).toFloat()
        }
        seek.thumb = GradientDrawable().apply {
            setColor(Color.WHITE); cornerRadius = dp(2).toFloat()
            setSize(dp(28), dp(5))
        }
        seek.setPadding(0, dp(6), 0, dp(2))
        seek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar?, p: Int, fromUser: Boolean) {
                if (fromUser) tCur.text = fmt(p.toLong())
            }
            override fun onStartTrackingTouch(sb: SeekBar?) { seeking = true }
            override fun onStopTrackingTouch(sb: SeekBar?) {
                seeking = false
                onSeek?.invoke(sb?.progress ?: 0)
            }
        })
        addView(seek, LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))

        /* время + формат + остаток */
        val times = LinearLayout(ctx)
        times.orientation = HORIZONTAL
        times.gravity = Gravity.CENTER_VERTICAL
        tCur.textSize = 12f; tCur.setTextColor(Color.argb(170, 255, 255, 255))
        tLeft.textSize = 12f; tLeft.setTextColor(Color.argb(170, 255, 255, 255))
        tLeft.gravity = Gravity.END
        fmtTv.textSize = 12f; fmtTv.setTextColor(Color.argb(150, 255, 255, 255))
        fmtTv.gravity = Gravity.CENTER
        times.addView(tCur, LinearLayout.LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f))
        times.addView(fmtTv, LinearLayout.LayoutParams(0, LayoutParams.WRAP_CONTENT, 2f))
        times.addView(tLeft, LinearLayout.LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f))
        addView(times, LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))

        /* транспорт */
        val tr = LinearLayout(ctx)
        tr.orientation = HORIZONTAL
        tr.gravity = Gravity.CENTER_VERTICAL
        tr.setPadding(0, dp(14), 0, dp(6))
        val prev = IconView(ctx); prev.icon = "prev"; prev.setColor(Color.WHITE)
        prev.setOnClickListener { performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK); onPrev?.invoke() }
        tr.addView(prev, LinearLayout.LayoutParams(0, dp(44), 1f))
        playI.icon = "play"; playI.setColor(Color.WHITE)
        playI.setOnClickListener { performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK); onToggle?.invoke() }
        tr.addView(playI, LinearLayout.LayoutParams(0, dp(56), 1f))
        val next = IconView(ctx); next.icon = "next"; next.setColor(Color.WHITE)
        next.setOnClickListener { performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK); onNext?.invoke() }
        tr.addView(next, LinearLayout.LayoutParams(0, dp(44), 1f))
        addView(tr, LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))

        /* громкость */
        val vol = LinearLayout(ctx)
        vol.orientation = HORIZONTAL
        vol.gravity = Gravity.CENTER_VERTICAL
        vol.setPadding(0, dp(10), 0, dp(6))
        val vl = IconView(ctx); vl.icon = "volLow"; vl.setColor(Color.argb(170, 255, 255, 255))
        vol.addView(vl, LinearLayout.LayoutParams(dp(22), dp(22)))
        volSeek.max = am.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
        volSeek.progress = am.getStreamVolume(AudioManager.STREAM_MUSIC)
        volSeek.progressDrawable = GradientDrawable().apply {
            setColor(Color.argb(70, 255, 255, 255)); cornerRadius = dp(2).toFloat()
        }
        volSeek.thumb = GradientDrawable().apply {
            setColor(Color.WHITE); cornerRadius = dp(2).toFloat()
            setSize(dp(24), dp(5))
        }
        volSeek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar?, p: Int, fromUser: Boolean) {
                if (fromUser) am.setStreamVolume(AudioManager.STREAM_MUSIC, p, 0)
            }
            override fun onStartTrackingTouch(sb: SeekBar?) { }
            override fun onStopTrackingTouch(sb: SeekBar?) { }
        })
        val vlp = LinearLayout.LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f)
        vlp.setMargins(dp(10), 0, dp(10), 0)
        vol.addView(volSeek, vlp)
        val vh = IconView(ctx); vh.icon = "volHigh"; vh.setColor(Color.argb(170, 255, 255, 255))
        vol.addView(vh, LinearLayout.LayoutParams(dp(22), dp(22)))
        addView(vol, LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))

        /* нижняя панель: текст / очередь / теги */
        val bottom = LinearLayout(ctx)
        bottom.orientation = HORIZONTAL
        bottom.gravity = Gravity.CENTER_VERTICAL
        bottom.setPadding(0, dp(10), 0, 0)
        val bLyrics = IconView(ctx); bLyrics.icon = "lyrics"; bLyrics.setColor(Color.WHITE)
        bLyrics.background = GradientDrawable().apply {
            setColor(Color.argb(60, 255, 255, 255)); shape = GradientDrawable.OVAL
        }
        bLyrics.setOnClickListener { performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK); recenter() }
        val bQueue = IconView(ctx); bQueue.icon = "queue"; bQueue.setColor(Color.argb(200, 255, 255, 255))
        bQueue.setOnClickListener { performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK); onQueue?.invoke() }
        val bTags = IconView(ctx); bTags.icon = "tags"; bTags.setColor(Color.argb(200, 255, 255, 255))
        bTags.setOnClickListener { performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK); onEditor?.invoke() }
        bottom.addView(View(ctx), LinearLayout.LayoutParams(0, dp(40), 1f))
        bottom.addView(bLyrics, LinearLayout.LayoutParams(0, dp(44), 1f))
        bottom.addView(bQueue, LinearLayout.LayoutParams(0, dp(40), 1f))
        bottom.addView(bTags, LinearLayout.LayoutParams(0, dp(40), 1f))
        bottom.addView(View(ctx), LinearLayout.LayoutParams(0, dp(40), 1f))
        addView(bottom, LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
    }

    /* ---------- открытие под трек ---------- */

    fun open(t: MainActivity.Track?, coverPath: String?) {
        val bm = decodeSmall(coverPath)
        headerCover.setImageBitmap(bm)
        headerTitle.text = t?.title ?: "Ничего не играет"
        headerArtist.text = t?.artist ?: ""
        background = GradientDrawable(
            GradientDrawable.Orientation.TOP_BOTTOM,
            intArrayOf(tint(bm, 0.46f), tint(bm, 0.20f))
        )
        val raw = t?.lyrics
        lines = if (raw != null) LrcParser.parse(raw) else emptyList()
        timed = lines.isNotEmpty()
        listBox.removeAllViews()
        lineViews.clear()
        curIdx = -1
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
                val idx = lineViews.size
                tv.setOnClickListener {
                    performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                    onSeek?.invoke(l.timeMs.toInt())
                }
                listBox.addView(tv)
                lineViews.add(tv)
            }
        }
        fmtTv.text = if (t != null) "${t.format} · ${specs(t).replace("/", " · ")}" else ""
        volSeek.max = am.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
        volSeek.progress = am.getStreamVolume(AudioManager.STREAM_MUSIC)
        scroll.post { scroll.scrollTo(0, 0) }
        visibility = VISIBLE
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
            tv.background = ripple()
        }
        return tv
    }

    private fun ripple(): android.graphics.drawable.Drawable {
        val r = android.graphics.drawable.RippleDrawable(
            android.content.res.ColorStateList.valueOf(Color.argb(60, 255, 255, 255)),
            null, null
        )
        return r
    }

    /* ---------- тик плеера ---------- */

    fun tick(posMs: Int, durMs: Int, playing: Boolean) {
        this.durMs = durMs
        seek.max = max(1, durMs)
        if (!seeking) seek.progress = posMs
        tCur.text = fmt(posMs.toLong())
        tLeft.text = "-" + fmt((durMs - posMs).toLong())
        playI.icon = if (playing) "pause" else "play"
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

    /* ---------- цвет фона из обложки ---------- */

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
