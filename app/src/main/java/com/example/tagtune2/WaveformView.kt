package com.example.tagtune2

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import android.view.MotionEvent
import android.view.View
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.abs

/* ---------- волна: зеркальные бары, плавный прогресс внутри бара ---------- */
class WaveformView(context: Context) : View(context) {

    private var peaks: FloatArray? = null
    private var target = 0f
    private var shown = 0f
    var onSeek: ((Float) -> Unit)? = null
    private val barPaint = Paint(Paint.ANTI_ALIAS_FLAG)

    fun setPeaks(p: FloatArray?) { peaks = p; invalidate() }
    fun setProgress(f: Float) { target = f.coerceIn(0f, 1f); invalidate() }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat(); val h = height.toFloat()
        val p = peaks
        if (p == null || p.isEmpty()) {
            barPaint.color = Color.parseColor("#3E3B47")
            canvas.drawRect(0f, h / 2 - 1f, w, h / 2 + 1f, barPaint)
            return
        }
        /* плавное догоняние цели: без ступенек между опросами позиции */
        if (abs(target - shown) > 0.0005f) {
            shown += (target - shown) * 0.35f
            postInvalidateOnAnimation()
        } else shown = target

        val n = p.size
        val slot = w / n
        val bw = slot * 0.55f
        for (i in 0 until n) {
            val bh = maxOf(2f, p[i] * h * 0.92f)
            val x = i * slot + (slot - bw) / 2
            val top = (h - bh) / 2
            val bottom = (h + bh) / 2
            barPaint.color = Color.parseColor("#4A4653")
            canvas.drawRoundRect(x, top, x + bw, bottom, bw / 2, bw / 2, barPaint)
            val frac = (shown * n - i).coerceIn(0f, 1f)
            if (frac > 0f) {
                val wp = bw * frac
                barPaint.color = Color.parseColor("#FF4D6D")
                val r = minOf(bw / 2, wp / 2)
                canvas.drawRoundRect(x, top, x + wp, bottom, r, r, barPaint)
            }
        }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.action) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_MOVE -> {
                onSeek?.invoke((event.x / width).coerceIn(0f, 1f))
                return true
            }
        }
        return super.onTouchEvent(event)
    }

    companion object {
        private val memCache = HashMap<String, FloatArray>()

        fun getPeaks(ctx: Context, uri: String, durationMs: Long): FloatArray? {
            memCache[uri]?.let { return it }
            val f = File(ctx.cacheDir, "wave2_" + Integer.toHexString(uri.hashCode()) + ".bin")
            if (f.exists()) {
                try {
                    val fb = ByteBuffer.wrap(f.readBytes()).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer()
                    val a = FloatArray(fb.remaining()); fb.get(a)
                    memCache[uri] = a
                    return a
                } catch (e: Exception) { }
            }
            val a = fastPeaks(ctx, Uri.parse(uri), durationMs, 72) ?: return null
            memCache[uri] = a
            try {
                val bb = ByteBuffer.allocate(a.size * 4).order(ByteOrder.LITTLE_ENDIAN)
                a.forEach { bb.putFloat(it) }
                f.writeBytes(bb.array())
            } catch (e: Exception) { }
            return a
        }

        /* скорость важнее точности: декодируем только короткие окна с прыжками по синк-точкам */
        private fun fastPeaks(ctx: Context, uri: Uri, durationMs: Long, buckets: Int): FloatArray? {
            if (durationMs <= 0) return null
            val extractor = MediaExtractor()
            var codec: MediaCodec? = null
            try {
                extractor.setDataSource(ctx, uri, null)
                var ti = -1; var fmt: MediaFormat? = null
                for (i in 0 until extractor.trackCount) {
                    val ff = extractor.getTrackFormat(i)
                    val m = ff.getString(MediaFormat.KEY_MIME) ?: ""
                    if (m.startsWith("audio/")) { ti = i; fmt = ff; break }
                }
                if (ti < 0 || fmt == null) return null
                extractor.selectTrack(ti)
                val mime = fmt.getString(MediaFormat.KEY_MIME)!!
                codec = MediaCodec.createDecoderByType(mime)
                codec.configure(fmt, null, null, 0)
                codec.start()
                val out = FloatArray(buckets)
                val spanUs = durationMs * 1000L / buckets
                val windowUs = minOf(spanUs, 450_000L)
                val info = MediaCodec.BufferInfo()
                for (b in 0 until buckets) {
                    val tUs = b * spanUs
                    extractor.seekTo(tUs, MediaExtractor.SEEK_TO_PREVIOUS_SYNC)
                    codec.flush()
                    var max = 0f
                    var inEos = false
                    val deadline = tUs + windowUs
                    var guard = 0
                    while (guard++ < 64) {
                        if (!inEos) {
                            val ii = codec.dequeueInputBuffer(5000)
                            if (ii >= 0) {
                                val buf = codec.getInputBuffer(ii)!!
                                val n = extractor.readSampleData(buf, 0)
                                if (n < 0) { codec.queueInputBuffer(ii, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM); inEos = true }
                                else { codec.queueInputBuffer(ii, 0, n, extractor.sampleTime, 0); extractor.advance() }
                            }
                        }
                        val oi = codec.dequeueOutputBuffer(info, 5000)
                        if (oi >= 0) {
                            if (info.size > 0 && info.presentationTimeUs in tUs..deadline) {
                                val ob = codec.getOutputBuffer(oi)!!
                                ob.position(info.offset); ob.limit(info.offset + info.size)
                                val of = codec.getOutputFormat(oi)
                                val isFloat = of.containsKey(MediaFormat.KEY_PCM_ENCODING) &&
                                    of.getInteger(MediaFormat.KEY_PCM_ENCODING) == AudioFormat.ENCODING_PCM_FLOAT
                                val ch = if (of.containsKey(MediaFormat.KEY_CHANNEL_COUNT)) of.getInteger(MediaFormat.KEY_CHANNEL_COUNT) else 1
                                val frames = info.size / ((if (isFloat) 4 else 2) * ch)
                                if (isFloat) {
                                    val fb2 = ob.asFloatBuffer()
                                    for (fr in 0 until frames) for (cc in 0 until ch) { val v = abs(fb2.get()); if (v > max) max = v }
                                } else {
                                    val sb = ob.asShortBuffer()
                                    for (fr in 0 until frames) for (cc in 0 until ch) { val v = abs(sb.get().toInt()) / 32768f; if (v > max) max = v }
                                }
                            }
                            codec.releaseOutputBuffer(oi, false)
                            if (info.presentationTimeUs > deadline || (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) break
                        }
                    }
                    out[b] = max
                }
                var mx = 0f
                for (v in out) if (v > mx) mx = v
                if (mx > 0) for (i in 0 until buckets) out[i] = (out[i] / mx).coerceIn(0.06f, 1f)
                return out
            } catch (e: Exception) {
                return null
            } finally {
                try { codec?.stop() } catch (e: Exception) { }
                try { codec?.release() } catch (e: Exception) { }
                extractor.release()
            }
        }
    }
}
