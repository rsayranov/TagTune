package com.example.tagtune2

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.view.View

/* ---------- собственный набор пиктограмм: единый штрих, без эмодзи ---------- */
class IconView(context: Context) : View(context) {
    var icon: String = "play"
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)

    fun setColor(color: Int) { paint.color = color; invalidate() }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        var s = minOf(width, height).toFloat()
        if (s <= 0f) return
        s *= when (icon) {
            "shuffle", "shuffleOff", "repeat", "repeat1" -> 0.80f
            "prev", "next" -> 0.92f
            "queue", "lyrics", "tags" -> 0.90f
            else -> 1f
        }
        val c = width / 2f; val cy = height / 2f
        paint.strokeWidth = s * 0.10f
        paint.strokeCap = Paint.Cap.ROUND
        paint.strokeJoin = Paint.Join.ROUND
        when (icon) {
            "play" -> {
                paint.style = Paint.Style.FILL
                val p = Path()
                p.moveTo(c - s * 0.14f, cy - s * 0.26f); p.lineTo(c - s * 0.14f, cy + s * 0.26f); p.lineTo(c + s * 0.32f, cy); p.close()
                canvas.drawPath(p, paint)
            }
            "pause" -> {
                paint.style = Paint.Style.FILL
                canvas.drawRoundRect(RectF(c - s * 0.22f, cy - s * 0.26f, c - s * 0.08f, cy + s * 0.26f), s * 0.05f, s * 0.05f, paint)
                canvas.drawRoundRect(RectF(c + s * 0.08f, cy - s * 0.26f, c + s * 0.22f, cy + s * 0.26f), s * 0.05f, s * 0.05f, paint)
            }
            "prev" -> {
                paint.style = Paint.Style.FILL
                canvas.drawRoundRect(RectF(c - s * 0.32f, cy - s * 0.26f, c - s * 0.24f, cy + s * 0.26f), s * 0.03f, s * 0.03f, paint)
                val p = Path()
                p.moveTo(c + s * 0.30f, cy - s * 0.26f); p.lineTo(c + s * 0.30f, cy + s * 0.26f); p.lineTo(c - s * 0.10f, cy); p.close()
                canvas.drawPath(p, paint)
            }
            "next" -> {
                paint.style = Paint.Style.FILL
                canvas.drawRoundRect(RectF(c + s * 0.24f, cy - s * 0.26f, c + s * 0.32f, cy + s * 0.26f), s * 0.03f, s * 0.03f, paint)
                val p = Path()
                p.moveTo(c - s * 0.30f, cy - s * 0.26f); p.lineTo(c - s * 0.30f, cy + s * 0.26f); p.lineTo(c + s * 0.10f, cy); p.close()
                canvas.drawPath(p, paint)
            }
            "shuffle", "shuffleOff" -> {
                paint.style = Paint.Style.STROKE
                val p = Path()
                p.moveTo(c - s * 0.30f, cy - s * 0.18f); p.lineTo(c - s * 0.10f, cy - s * 0.18f); p.lineTo(c + s * 0.10f, cy + s * 0.18f); p.lineTo(c + s * 0.18f, cy + s * 0.18f)
                p.moveTo(c - s * 0.30f, cy + s * 0.18f); p.lineTo(c - s * 0.10f, cy + s * 0.18f); p.lineTo(c + s * 0.10f, cy - s * 0.18f); p.lineTo(c + s * 0.18f, cy - s * 0.18f)
                canvas.drawPath(p, paint)
                arrowHead(canvas, c + s * 0.26f, cy + s * 0.18f, 1f, 0f, s)
                arrowHead(canvas, c + s * 0.26f, cy - s * 0.18f, 1f, 0f, s)
                if (icon == "shuffleOff") canvas.drawLine(c - s * 0.32f, cy - s * 0.32f, c + s * 0.32f, cy + s * 0.32f, paint)
            }
            "repeat", "repeat1" -> {
                paint.style = Paint.Style.STROKE
                val r = RectF(c - s * 0.26f, cy - s * 0.18f, c + s * 0.26f, cy + s * 0.18f)
                canvas.drawArc(r, 30f, 300f, false, paint)
                paint.style = Paint.Style.FILL
                arrowHead(canvas, c + s * 0.225f, cy - s * 0.09f, 0.5f, 0.866f, s)
                if (icon == "repeat1") {
                    paint.textSize = s * 0.28f
                    paint.textAlign = Paint.Align.CENTER
                    canvas.drawText("1", c, cy + s * 0.10f, paint)
                    paint.textAlign = Paint.Align.LEFT
                }
            }
            "queue" -> {
                /* список с буллитами, как на референсе */
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
                /* пузырь с кавычками, как на референсе */
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
                /* бирка с дыркой под 45°, как на референсе */
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

    private fun arrowHead(canvas: Canvas, x: Float, y: Float, dx: Float, dy: Float, s: Float) {
        val st = paint.style
        paint.style = Paint.Style.FILL
        val p = Path()
        p.moveTo(x + dx * s * 0.10f, y + dy * s * 0.10f)
        p.lineTo(x - dy * s * 0.07f, y + dx * s * 0.07f)
        p.lineTo(x + dy * s * 0.07f, y - dx * s * 0.07f)
        p.close()
        canvas.drawPath(p, paint)
        paint.style = st
    }
}
