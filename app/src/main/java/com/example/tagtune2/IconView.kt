package com.example.tagtune2

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.view.View

/* ---------- пиктограммы в оптике YouTube Music: тонкие режимы, залитые стрелки ---------- */
class IconView(context: Context) : View(context) {
    var icon: String = "play"
        set(value) {
            field = value
            invalidate()
        }
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)

    fun setColor(color: Int) { paint.color = color; invalidate() }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        var s = minOf(width, height).toFloat()
        if (s <= 0f) return
        s *= when (icon) {
            "shuffle", "shuffleOff", "repeat", "repeat1" -> 0.72f
            "prev", "next" -> 0.80f
            "play", "pause" -> 0.62f
            "queue", "lyrics", "tags" -> 0.66f
            else -> 1f
        }
        val c = width / 2f; val cy = height / 2f
        paint.strokeWidth = s * 0.09f
        paint.strokeCap = Paint.Cap.ROUND
        paint.strokeJoin = Paint.Join.ROUND
        when (icon) {
            "play" -> {
                paint.style = Paint.Style.FILL
                val p = Path()
                p.moveTo(c - s * 0.12f, cy - s * 0.24f)
                p.lineTo(c - s * 0.12f, cy + s * 0.24f)
                p.lineTo(c + s * 0.30f, cy)
                p.close()
                canvas.drawPath(p, paint)
            }
            "pause" -> {
                paint.style = Paint.Style.FILL
                canvas.drawRoundRect(RectF(c - s * 0.16f, cy - s * 0.24f, c - s * 0.06f, cy + s * 0.24f), s * 0.03f, s * 0.03f, paint)
                canvas.drawRoundRect(RectF(c + s * 0.06f, cy - s * 0.24f, c + s * 0.16f, cy + s * 0.24f), s * 0.03f, s * 0.03f, paint)
            }
            "prev" -> {
                paint.style = Paint.Style.FILL
                canvas.drawRoundRect(RectF(c - s * 0.30f, cy - s * 0.26f, c - s * 0.23f, cy + s * 0.26f), s * 0.03f, s * 0.03f, paint)
                val p = Path()
                p.moveTo(c + s * 0.26f, cy - s * 0.26f); p.lineTo(c + s * 0.26f, cy + s * 0.26f); p.lineTo(c - s * 0.14f, cy); p.close()
                canvas.drawPath(p, paint)
            }
            "next" -> {
                paint.style = Paint.Style.FILL
                canvas.drawRoundRect(RectF(c + s * 0.23f, cy - s * 0.26f, c + s * 0.30f, cy + s * 0.26f), s * 0.03f, s * 0.03f, paint)
                val p = Path()
                p.moveTo(c - s * 0.26f, cy - s * 0.26f); p.lineTo(c - s * 0.26f, cy + s * 0.26f); p.lineTo(c + s * 0.14f, cy); p.close()
                canvas.drawPath(p, paint)
            }
            "shuffle", "shuffleOff" -> {
                paint.style = Paint.Style.STROKE
                val p = Path()
                p.moveTo(c - s * 0.32f, cy + s * 0.14f); p.lineTo(c - s * 0.12f, cy + s * 0.14f); p.lineTo(c + s * 0.12f, cy - s * 0.14f); p.lineTo(c + s * 0.20f, cy - s * 0.14f)
                p.moveTo(c - s * 0.32f, cy - s * 0.14f); p.lineTo(c - s * 0.12f, cy - s * 0.14f); p.lineTo(c + s * 0.12f, cy + s * 0.14f); p.lineTo(c + s * 0.20f, cy + s * 0.14f)
                canvas.drawPath(p, paint)
                paint.style = Paint.Style.FILL
                val h1 = Path()
                h1.moveTo(c + s * 0.19f, cy - s * 0.20f); h1.lineTo(c + s * 0.19f, cy - s * 0.08f); h1.lineTo(c + s * 0.32f, cy - s * 0.14f); h1.close()
                canvas.drawPath(h1, paint)
                val h2 = Path()
                h2.moveTo(c + s * 0.19f, cy + s * 0.08f); h2.lineTo(c + s * 0.19f, cy + s * 0.20f); h2.lineTo(c + s * 0.32f, cy + s * 0.14f); h2.close()
                canvas.drawPath(h2, paint)
                if (icon == "shuffleOff") {
                    paint.style = Paint.Style.STROKE
                    canvas.drawLine(c - s * 0.30f, cy - s * 0.30f, c + s * 0.30f, cy + s * 0.30f, paint)
                }
            }
            "repeat", "repeat1" -> {
                paint.style = Paint.Style.STROKE
                val p1 = Path()
                p1.moveTo(c - s * 0.14f, cy - s * 0.16f)
                p1.lineTo(c + s * 0.18f, cy - s * 0.16f)
                p1.quadTo(c + s * 0.28f, cy - s * 0.16f, c + s * 0.28f, cy - s * 0.06f)
                p1.lineTo(c + s * 0.28f, cy + s * 0.04f)
                canvas.drawPath(p1, paint)
                val p2 = Path()
                p2.moveTo(c + s * 0.14f, cy + s * 0.16f)
                p2.lineTo(c - s * 0.18f, cy + s * 0.16f)
                p2.quadTo(c - s * 0.28f, cy + s * 0.16f, c - s * 0.28f, cy + s * 0.06f)
                p2.lineTo(c - s * 0.28f, cy - s * 0.04f)
                canvas.drawPath(p2, paint)
                paint.style = Paint.Style.FILL
                val a1 = Path()
                a1.moveTo(c + s * 0.21f, cy + s * 0.02f); a1.lineTo(c + s * 0.35f, cy + s * 0.02f); a1.lineTo(c + s * 0.28f, cy + s * 0.16f); a1.close()
                canvas.drawPath(a1, paint)
                val a2 = Path()
                a2.moveTo(c - s * 0.35f, cy - s * 0.02f); a2.lineTo(c - s * 0.21f, cy - s * 0.02f); a2.lineTo(c - s * 0.28f, cy - s * 0.16f); a2.close()
                canvas.drawPath(a2, paint)
                if (icon == "repeat1") {
                    paint.textSize = s * 0.26f
                    paint.textAlign = Paint.Align.CENTER
                    canvas.drawText("1", c, cy + s * 0.09f, paint)
                    paint.textAlign = Paint.Align.LEFT
                }
            }
            "queue" -> {
                paint.style = Paint.Style.STROKE
                for (k in -1..1) {
                    val y = cy + k * s * 0.20f
                    canvas.drawLine(c - s * 0.08f, y, c + s * 0.30f, y, paint)
                }
                paint.style = Paint.Style.FILL
                for (k in -1..1) {
                    canvas.drawCircle(c - s * 0.26f, cy + k * s * 0.20f, s * 0.06f, paint)
                }
            }
            "lyrics" -> {
                paint.style = Paint.Style.STROKE
                val bubble = RectF(c - s * 0.30f, cy - s * 0.30f, c + s * 0.30f, cy + s * 0.12f)
                canvas.drawRoundRect(bubble, s * 0.12f, s * 0.12f, paint)
                paint.style = Paint.Style.FILL
                val tail = Path()
                tail.moveTo(c - s * 0.16f, cy + s * 0.08f)
                tail.lineTo(c - s * 0.22f, cy + s * 0.32f)
                tail.lineTo(c - s * 0.02f, cy + s * 0.11f)
                tail.close()
                canvas.drawPath(tail, paint)
                for (k in 0..1) {
                    val qx = c + (if (k == 0) -s * 0.11f else s * 0.11f)
                    val qy = cy - s * 0.12f
                    canvas.drawCircle(qx, qy, s * 0.07f, paint)
                    val q = Path()
                    q.moveTo(qx - s * 0.07f, qy + s * 0.02f)
                    q.lineTo(qx - s * 0.01f, qy + s * 0.17f)
                    q.lineTo(qx + s * 0.07f, qy + s * 0.04f)
                    q.close()
                    canvas.drawPath(q, paint)
                }
            }
            "tags" -> {
                canvas.save()
                canvas.rotate(45f, c, cy)
                paint.style = Paint.Style.FILL
                val p = Path()
                p.moveTo(c - s * 0.32f, cy)
                p.lineTo(c - s * 0.12f, cy - s * 0.20f)
                p.lineTo(c + s * 0.28f, cy - s * 0.20f)
                p.lineTo(c + s * 0.28f, cy + s * 0.20f)
                p.lineTo(c - s * 0.12f, cy + s * 0.20f)
                p.close()
                p.addCircle(c - s * 0.13f, cy, s * 0.10f, Path.Direction.CCW)
                p.fillType = Path.FillType.EVEN_ODD
                canvas.drawPath(p, paint)
                paint.style = Paint.Style.STROKE
                paint.strokeWidth = s * 0.09f
                canvas.drawPath(p, paint)
                canvas.restore()
            }
        }
    }
}
