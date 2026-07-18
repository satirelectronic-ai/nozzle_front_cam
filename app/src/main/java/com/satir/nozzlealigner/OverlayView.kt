package com.satir.nozzlealigner

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.util.AttributeSet
import android.view.View
import kotlin.math.min

/** Kamera önizlemesinin üzerine nozul çemberi, artı imleç, ışın noktası ve yön okunu çizer. */
class OverlayView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null
) : View(context, attrs) {

    private var result: NozzleProcessor.Result? = null

    /** Ön kamera önizlemesi aynalı gösterildiğinden, çizimi de yatay aynala. */
    var mirror: Boolean = false

    private val nozzlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeWidth = 4f; color = Color.WHITE
    }
    private val crossPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeWidth = 2f; color = Color.argb(180, 255, 255, 255)
    }
    private val beamPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL; color = Color.RED
    }
    private val arrowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeWidth = 6f
    }

    fun update(r: NozzleProcessor.Result?) { result = r; postInvalidate() }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val r = result ?: return
        if (r.frameW == 0 || r.frameH == 0) return

        val saveCount = canvas.save()
        if (mirror) {
            canvas.translate(width.toFloat(), 0f)
            canvas.scale(-1f, 1f)
        }

        // Kare koordinatlarını görünüme ölçekle (fill-center varsayımı).
        val sx = width.toFloat() / r.frameW
        val sy = height.toFloat() / r.frameH
        val s = min(sx, sy)
        val offX = (width - r.frameW * s) / 2f
        val offY = (height - r.frameH * s) / 2f
        fun mapX(x: Double) = offX + x.toFloat() * s
        fun mapY(y: Double) = offY + y.toFloat() * s

        val color = when (r.state) {
            NozzleProcessor.State.GREEN -> Color.rgb(0, 200, 0)
            NozzleProcessor.State.YELLOW -> Color.rgb(230, 180, 0)
            NozzleProcessor.State.RED -> Color.rgb(220, 40, 40)
            NozzleProcessor.State.NONE -> Color.GRAY
        }
        nozzlePaint.color = color
        arrowPaint.color = color

        r.nozzleCenter?.let { nc ->
            val cxp = mapX(nc.x); val cyp = mapY(nc.y)
            canvas.drawCircle(cxp, cyp, (r.nozzleRadiusPx * s).toFloat(), nozzlePaint)
            // artı imleç (nozul merkezi = hedef)
            val cl = 26f
            canvas.drawLine(cxp - cl, cyp, cxp + cl, cyp, crossPaint)
            canvas.drawLine(cxp, cyp - cl, cxp, cyp + cl, crossPaint)

            r.beamCenter?.let { bc ->
                val bxp = mapX(bc.x); val byp = mapY(bc.y)
                canvas.drawCircle(bxp, byp, 10f, beamPaint)
                // nozul merkezinden ışına doğru ok (hareket yönü göstergesi)
                if (r.state != NozzleProcessor.State.GREEN) {
                    canvas.drawLine(cxp, cyp, bxp, byp, arrowPaint)
                }
            }
        }
        canvas.restoreToCount(saveCount)
    }
}
