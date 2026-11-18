package com.example.tagtune2

import android.content.Context
import android.graphics.Canvas
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

class WaveformView(context: Context) : View(context) {

    private var peaks: FloatArray? = null
    private var progress = 0f
    var onSeek: ((Float) -> Unit)? = null
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)

    fun setPeaks(p: FloatArray?) { peaks = p; invalidate() }
    fun setProgress(f: Float) { progress = f.coerceIn(0f, 1f); invalidate() }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat(); val h = height.toFloat()
        val p = peaks
        if (p == null || p.isEmpty()) {
            paint.color = 0xFF3A3A4A.toInt()
            canvas.drawRect(0f, h / 2 - 1, w, h / 2 + 1, paint)
            paint.color = 0xFF8D81A3.toInt()
            paint.textSize = h * 0.22f
            canvas.drawText("волна строится…", 8f, h / 2 - 6, paint)
            return
        }
        val n = p.size; val bw = w / n
        for (i in 0 until n) {
            val bh = maxOf(2f, p[i] * (h - 6))
            val played = (i + 0.5f) / n <= progress
            paint.color = if (played) 0xFF7C4DFF.toInt() else 0xFF3A3A4A.toInt()
            canvas.drawRect(i * bw + bw * 0.15f, (h - bh) / 2, i * bw + bw * 0.85f, (h + bh) / 2, paint)
        }
        paint.color = 0xFF37E6FF.toInt()
        canvas.drawRect(progress * w - 1, 0f, progress * w + 1, h, paint)
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
        private val cache = HashMap<String, FloatArray>()

        fun getPeaks(ctx: Context, uri: String): FloatArray? {
            cache[uri]?.let { return it }
            val f = File(ctx.cacheDir, "wave_" + Integer.toHexString(uri.hashCode()) + ".bin")
            if (f.exists()) {
                try {
                    val fb = ByteBuffer.wrap(f.readBytes()).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer()
                    val a = FloatArray(fb.remaining()); fb.get(a)
                    cache[uri] = a; return a
                } catch (e: Exception) { }
            }
            val a = decode(ctx, Uri.parse(uri), 400) ?: return null
            cache[uri] = a
            try {
                val bb = ByteBuffer.allocate(a.size * 4).order(ByteOrder.LITTLE_ENDIAN)
                a.forEach { bb.putFloat(it) }
                f.writeBytes(bb.array())
            } catch (e: Exception) { }
            return a
        }

        private fun decode(ctx: Context, uri: Uri, buckets: Int): FloatArray? {
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
                val durUs = if (fmt.containsKey(MediaFormat.KEY_DURATION)) fmt.getLong(MediaFormat.KEY_DURATION) else 0L
                codec = MediaCodec.createDecoderByType(mime)
                codec.configure(fmt, null, null, 0)
                codec.start()
                val peaks = FloatArray(buckets)
                val chunk = ArrayList<Float>()
                val info = MediaCodec.BufferInfo()
                var inEos = false; var outEos = false
                while (!outEos) {
                    if (!inEos) {
                        val ii = codec.dequeueInputBuffer(10000)
                        if (ii >= 0) {
                            val b = codec.getInputBuffer(ii)!!
                            val n = extractor.readSampleData(b, 0)
                            if (n < 0) { codec.queueInputBuffer(ii, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM); inEos = true }
                            else { codec.queueInputBuffer(ii, 0, n, extractor.sampleTime, 0); extractor.advance() }
                        }
                    }
                    val oi = codec.dequeueOutputBuffer(info, 10000)
                    if (oi >= 0) {
                        if (info.size > 0) {
                            val ob = codec.getOutputBuffer(oi)!!
                            ob.position(info.offset); ob.limit(info.offset + info.size)
                            val of = codec.getOutputFormat(oi)
                            val isFloat = of.containsKey(MediaFormat.KEY_PCM_ENCODING) &&
                                of.getInteger(MediaFormat.KEY_PCM_ENCODING) == AudioFormat.ENCODING_PCM_FLOAT
                            val ch = if (of.containsKey(MediaFormat.KEY_CHANNEL_COUNT)) of.getInteger(MediaFormat.KEY_CHANNEL_COUNT) else 1
                            val frames = info.size / ((if (isFloat) 4 else 2) * ch)
                            var max = 0f
                            if (isFloat) {
                                val fb = ob.asFloatBuffer()
                                for (fr in 0 until frames) for (c in 0 until ch) { val v = abs(fb.get()); if (v > max) max = v }
                            } else {
                                val sb = ob.asShortBuffer()
                                for (fr in 0 until frames) for (c in 0 until ch) { val v = abs(sb.get().toInt()) / 32768f; if (v > max) max = v }
                            }
                            if (durUs > 0) {
                                val bIdx = ((info.presentationTimeUs.toDouble() / durUs) * buckets).toInt().coerceIn(0, buckets - 1)
                                if (max > peaks[bIdx]) peaks[bIdx] = max
                            } else chunk.add(max)
                        }
                        codec.releaseOutputBuffer(oi, false)
                        if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) outEos = true
                    }
                }
                val src = if (durUs > 0) peaks else FloatArray(chunk.size) { chunk[it] }
                if (src.isEmpty()) return null
                val out = FloatArray(buckets); var mx = 0f
                for (i in 0 until buckets) {
                    val v = src[(i.toLong() * src.size / buckets).toInt().coerceAtMost(src.size - 1)]
                    out[i] = v; if (v > mx) mx = v
                }
                if (mx > 0) for (i in 0 until buckets) out[i] = out[i] / mx
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
