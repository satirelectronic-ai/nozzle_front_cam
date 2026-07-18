package com.satir.nozzlealigner

import org.opencv.core.*
import org.opencv.core.Core
import org.opencv.imgproc.Imgproc
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

/**
 * Nozul merkezleme görüntü işleme.
 *
 * Girdi: kameradan NV21 kare.
 * Çıktı: nozul çemberi merkezi, kırmızı ışın noktası merkezi, ofset (piksel + mm),
 *        yön metni ve durum (yeşil/sarı/kırmızı).
 *
 * Prensip:
 *  - Nozul deliği görüntüde bir daire/halka olarak görünür  -> HoughCircles / kontur ile merkez.
 *  - Kırmızı kılavuz ışın parlak bir nokta -> eşikleme + moment (centroid) ile alt-piksel merkez.
 *  - Ofset = ışın merkezi - nozul merkezi. Kalibrasyon ölçeğiyle mm'ye çevrilir.
 */
class NozzleProcessor {

    data class Result(
        val ok: Boolean,
        val frameW: Int,
        val frameH: Int,
        val nozzleCenter: Point?,
        val nozzleRadiusPx: Double,
        val beamCenter: Point?,
        val offsetPx: Double,
        val offsetMm: Double,
        val dx: Double,          // ışın - nozul (piksel), +x sağ
        val dy: Double,          // +y aşağı
        val direction: String,   // TR yön metni
        val state: State
    )

    enum class State { GREEN, YELLOW, RED, NONE }

    // --- Ayarlanabilir parametreler (UI'dan değiştirilebilir) ---
    @Volatile var beamThreshold = 210     // ışın noktası parlaklık eşiği (0-255)
    @Volatile var minNozzleRadius = 40    // piksel
    @Volatile var maxNozzleRadius = 400   // piksel
    @Volatile var mmPerPx = 0.0           // kalibrasyon; 0 ise mm gösterilmez
    @Volatile var greenThreshMm = 0.03    // yeşil sınır
    @Volatile var yellowThreshMm = 0.10   // sarı sınır
    // mm bilinmiyorsa piksel eşiği kullan:
    @Volatile var greenThreshPx = 3.0
    @Volatile var yellowThreshPx = 10.0

    // Reusable Mat'ler (GC baskısını azaltmak için)
    private var yuv: Mat? = null
    private var gray: Mat? = null
    private var gRot: Mat? = null
    private var work: Mat? = null

    /**
     * @param rotationDegrees kareyi dik konuma getirmek için 0/90/180/270.
     *   USB kamera için 0; telefon kamerası için ImageProxy.rotationDegrees verilir.
     */
    fun process(data: ByteArray, width: Int, height: Int, rotationDegrees: Int = 0): Result {
        val yuvMat = ensure(yuv, height + height / 2, width, CvType.CV_8UC1).also { yuv = it }
        yuvMat.put(0, 0, data)
        var g = gray ?: Mat().also { gray = it }
        Imgproc.cvtColor(yuvMat, g, Imgproc.COLOR_YUV2GRAY_NV21)

        var w = width; var h = height
        if (rotationDegrees != 0) {
            val gr = gRot ?: Mat().also { gRot = it }
            when (rotationDegrees) {
                90 -> { Core.rotate(g, gr, Core.ROTATE_90_CLOCKWISE); w = height; h = width }
                180 -> { Core.rotate(g, gr, Core.ROTATE_180) }
                270 -> { Core.rotate(g, gr, Core.ROTATE_90_COUNTERCLOCKWISE); w = height; h = width }
                else -> gr.release()
            }
            if (rotationDegrees == 90 || rotationDegrees == 180 || rotationDegrees == 270) g = gr
        }

        val nozzle = detectNozzle(g, w, h)
        val beam = detectBeam(g)

        if (nozzle == null || beam == null) {
            return Result(false, w, h, nozzle?.first, nozzle?.second ?: 0.0,
                beam, 0.0, 0.0, 0.0, 0.0, "Hedef bulunamadı", State.NONE)
        }

        val nc = nozzle.first
        val dx = beam.x - nc.x
        val dy = beam.y - nc.y
        val offPx = hypot(dx, dy)
        val offMm = if (mmPerPx > 0) offPx * mmPerPx else 0.0

        val state = classify(offPx, offMm)
        val dir = directionText(dx, dy, state)

        return Result(
            ok = true, frameW = w, frameH = h,
            nozzleCenter = nc, nozzleRadiusPx = nozzle.second,
            beamCenter = beam, offsetPx = offPx, offsetMm = offMm,
            dx = dx, dy = dy, direction = dir, state = state
        )
    }

    /** Nozul çemberi: önce Hough, olmazsa en büyük dairemsi kontur. Merkeze en yakını seçilir. */
    private fun detectNozzle(g: Mat, w: Int, h: Int): Pair<Point, Double>? {
        val blur = work ?: Mat().also { work = it }
        Imgproc.medianBlur(g, blur, 5)
        val circles = Mat()
        Imgproc.HoughCircles(
            blur, circles, Imgproc.HOUGH_GRADIENT, 1.0,
            (h / 8.0).coerceAtLeast(20.0),
            120.0, 30.0, minNozzleRadius, maxNozzleRadius
        )
        val cx = w / 2.0; val cy = h / 2.0
        if (circles.cols() > 0) {
            var best: Pair<Point, Double>? = null
            var bestDist = Double.MAX_VALUE
            for (i in 0 until circles.cols()) {
                val c = circles.get(0, i) ?: continue
                val p = Point(c[0], c[1]); val r = c[2]
                val d = hypot(p.x - cx, p.y - cy)
                if (d < bestDist) { bestDist = d; best = Pair(p, r) }
            }
            circles.release()
            if (best != null) return best
        }
        circles.release()

        // Fallback: koyu delik -> eşik + kontur
        val bin = Mat()
        Imgproc.threshold(blur, bin, 0.0, 255.0, Imgproc.THRESH_BINARY_INV or Imgproc.THRESH_OTSU)
        val contours = ArrayList<MatOfPoint>()
        Imgproc.findContours(bin, contours, Mat(), Imgproc.RETR_EXTERNAL, Imgproc.CHAIN_APPROX_SIMPLE)
        var best: Pair<Point, Double>? = null
        var bestScore = 0.0
        for (c in contours) {
            val area = Imgproc.contourArea(c)
            if (area < Math.PI * minNozzleRadius * minNozzleRadius) continue
            val center = Point(); val radius = FloatArray(1)
            val c2f = MatOfPoint2f(*c.toArray())
            Imgproc.minEnclosingCircle(c2f, center, radius)
            val circleArea = Math.PI * radius[0] * radius[0]
            val circularity = if (circleArea > 0) area / circleArea else 0.0   // 1'e yakın = daire
            if (circularity > 0.6 && circleArea > bestScore) {
                bestScore = circleArea
                best = Pair(center, radius[0].toDouble())
            }
        }
        bin.release()
        return best
    }

    /** Kırmızı ışın noktası: parlaklık eşiği + en büyük blob'un ağırlık merkezi (alt-piksel). */
    private fun detectBeam(g: Mat): Point? {
        val bin = Mat()
        Imgproc.threshold(g, bin, beamThreshold.toDouble(), 255.0, Imgproc.THRESH_BINARY)
        val k = Imgproc.getStructuringElement(Imgproc.MORPH_ELLIPSE, Size(5.0, 5.0))
        Imgproc.morphologyEx(bin, bin, Imgproc.MORPH_OPEN, k)
        val contours = ArrayList<MatOfPoint>()
        Imgproc.findContours(bin, contours, Mat(), Imgproc.RETR_EXTERNAL, Imgproc.CHAIN_APPROX_SIMPLE)
        var best: MatOfPoint? = null
        var bestArea = 4.0
        for (c in contours) {
            val a = Imgproc.contourArea(c)
            if (a > bestArea) { bestArea = a; best = c }
        }
        val center = best?.let {
            val m = Imgproc.moments(it)
            if (m.m00 != 0.0) Point(m.m10 / m.m00, m.m01 / m.m00) else null
        }
        bin.release()
        return center
    }

    private fun classify(offPx: Double, offMm: Double): State {
        return if (mmPerPx > 0) {
            when {
                offMm <= greenThreshMm -> State.GREEN
                offMm <= yellowThreshMm -> State.YELLOW
                else -> State.RED
            }
        } else {
            when {
                offPx <= greenThreshPx -> State.GREEN
                offPx <= yellowThreshPx -> State.YELLOW
                else -> State.RED
            }
        }
    }

    /** Işın merkeze göre nerede -> kafayı HANGİ yöne itmek gerektiği metni.
     *  Not: eksen yönü kamera montajına bağlı; gerekirse invertX/invertY ile ters çevir. */
    var invertX = false
    var invertY = false
    private fun directionText(dxIn: Double, dyIn: Double, state: State): String {
        if (state == State.GREEN) return "MERKEZDE ✓"
        val dx = if (invertX) -dxIn else dxIn
        val dy = if (invertY) -dyIn else dyIn
        val parts = ArrayList<String>()
        val dead = 2.0
        // Işın nozul merkezinin sağındaysa, merkezi sağa taşımak için "Sağa" öner:
        if (dx > dead) parts.add("Sağa")
        else if (dx < -dead) parts.add("Sola")
        if (dy > dead) parts.add("Aşağı")
        else if (dy < -dead) parts.add("Yukarı")
        return if (parts.isEmpty()) "Az bir tık" else parts.joinToString(" + ")
    }

    private fun ensure(m: Mat?, rows: Int, cols: Int, type: Int): Mat {
        if (m != null && m.rows() == rows && m.cols() == cols && m.type() == type) return m
        m?.release()
        return Mat(rows, cols, type)
    }

    fun release() {
        yuv?.release(); gray?.release(); gRot?.release(); work?.release()
        yuv = null; gray = null; gRot = null; work = null
    }
}
