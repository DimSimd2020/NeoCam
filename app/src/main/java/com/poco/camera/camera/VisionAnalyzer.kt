package com.poco.camera.camera

import android.graphics.Bitmap
import android.graphics.Rect
import android.graphics.RectF
import android.os.SystemClock
import androidx.annotation.OptIn
import androidx.camera.core.ExperimentalGetImage
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import com.google.android.gms.tasks.Task
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.vision.barcode.BarcodeScanner
import com.google.mlkit.vision.barcode.BarcodeScannerOptions
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.objects.DetectedObject
import com.google.mlkit.vision.objects.ObjectDetection
import com.google.mlkit.vision.objects.ObjectDetector
import com.google.mlkit.vision.objects.defaults.ObjectDetectorOptions
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * Анализ кадров:
 * 1) Подсветка резких краёв в фокусе (Focus Peaking по высокочастотному Лапласиану/Собелю Y-канала)
 * 2) Отслеживание объектов (ML Kit STREAM_MODE) со сглаживанием координат
 * 3) Сканирование QR-кодов
 */
class VisionAnalyzer(
    private val flags: () -> Flags,
    private val onObjects: (List<TrackedObj>) -> Unit,
    private val onPeaking: (Bitmap?) -> Unit,
    private val onQr: (QrHit) -> Unit,
) : ImageAnalysis.Analyzer {

    data class Flags(
        val objects: Boolean,
        val qr: Boolean,
        val peaking: Boolean,
        val mirror: Boolean,
        val viewAspect: Float,
        val focusRegion: RectF? = null,
    )

    private val detector: ObjectDetector = ObjectDetection.getClient(
        ObjectDetectorOptions.Builder()
            .setDetectorMode(ObjectDetectorOptions.STREAM_MODE)
            .enableMultipleObjects()
            .enableClassification()
            .build()
    )
    private val scanner: BarcodeScanner = BarcodeScanning.getClient(
        BarcodeScannerOptions.Builder()
            .setBarcodeFormats(Barcode.FORMAT_QR_CODE, Barcode.FORMAT_AZTEC, Barcode.FORMAT_DATA_MATRIX)
            .build()
    )
    private var frameNo = 0L
    private var lastTrackedRects = mutableListOf<TrackedObj>()

    @OptIn(ExperimentalGetImage::class)
    override fun analyze(proxy: ImageProxy) {
        val media = proxy.image
        val f = flags()
        if (media == null || (!f.objects && !f.qr && !f.peaking)) {
            if (!f.peaking) onPeaking(null)
            proxy.close()
            return
        }
        frameNo++
        val rotation = proxy.imageInfo.rotationDegrees
        val mapper = Mapper(proxy.width, proxy.height, rotation, f.mirror, f.viewAspect)

        // 1. Быстрая подсветка резких контуров объекта в фокусе (Focus Peaking) по Y-каналу
        if (f.peaking) {
            val bmp = runCatching {
                extractFocusPeakingBitmap(proxy, rotation, f.mirror, f.focusRegion ?: lastTrackedRects.firstOrNull()?.rect)
            }.getOrNull()
            onPeaking(bmp)
        } else {
            onPeaking(null)
        }

        // 2. ML Kit распознавание объектов и QR
        val input = InputImage.fromMediaImage(media, rotation)
        val objTask = if (f.objects) detector.process(input) else null
        val qrTask = if (f.qr && frameNo % 4 == 0L) scanner.process(input) else null
        val tasks = listOfNotNull<Task<*>>(objTask, qrTask)
        if (tasks.isEmpty()) {
            proxy.close()
            return
        }

        Tasks.whenAllComplete(tasks).addOnCompleteListener {
            try {
                if (objTask != null && objTask.isSuccessful) {
                    val mapped = objTask.result.mapIndexedNotNull { i, o ->
                        val r = mapper.map(o.boundingBox)
                        if (r.width() < 0.04f || r.height() < 0.04f) null
                        else TrackedObj(o.trackingId ?: (-1 - i), r, labelOf(o))
                    }
                    lastTrackedRects.clear()
                    lastTrackedRects.addAll(mapped)
                    onObjects(mapped)
                }
                if (qrTask != null && qrTask.isSuccessful) {
                    qrTask.result.firstOrNull { !it.rawValue.isNullOrBlank() }?.let { b ->
                        val url = b.url?.url
                        onQr(QrHit(url ?: b.rawValue!!, url != null || b.valueType == Barcode.TYPE_URL, SystemClock.elapsedRealtime()))
                    }
                }
            } finally {
                proxy.close()
            }
        }
    }

    /**
     * Вычисляет карту контуров в резком фокусе (Focus Peaking) из Y-плоскости кадра.
     * Подсвечивает только реально резкие края (высокий градиент + Лапласиан), как в профессиональных камерах.
     */
    private fun extractFocusPeakingBitmap(
        proxy: ImageProxy,
        rotation: Int,
        mirror: Boolean,
        roi: RectF?,
    ): Bitmap? {
        val plane = proxy.planes.firstOrNull() ?: return null
        val buf = plane.buffer
        val rowStride = plane.rowStride
        val pixelStride = plane.pixelStride
        val srcW = proxy.width
        val srcH = proxy.height
        if (srcW < 32 || srcH < 32) return null

        val uprightW = if (rotation % 180 == 0) srcW else srcH
        val uprightH = if (rotation % 180 == 0) srcH else srcW
        val step = 3
        val outW = uprightW / step
        val outH = uprightH / step
        if (outW < 16 || outH < 16) return null

        val pixels = IntArray(outW * outH)
        val limit = buf.limit()

        fun sampleY(ux: Int, uy: Int): Int {
            val sx: Int
            val sy: Int
            when (rotation) {
                90 -> {
                    sx = uy
                    sy = srcH - 1 - ux
                }
                180 -> {
                    sx = srcW - 1 - ux
                    sy = srcH - 1 - uy
                }
                270 -> {
                    sx = srcW - 1 - uy
                    sy = ux
                }
                else -> {
                    sx = ux
                    sy = uy
                }
            }
            val idx = sy * rowStride + sx * pixelStride
            return if (idx in 0 until limit) (buf.get(idx).toInt() and 0xFF) else 0
        }

        // Если есть выбранный объект или область фокуса — усиливаем чувствительность внутри неё
        val hasRoi = roi != null && roi.width() > 0.05f && roi.height() > 0.05f

        for (oy in 1 until outH - 1) {
            val uy = oy * step
            val ny = oy.toFloat() / outH
            for (ox in 1 until outW - 1) {
                val ux = ox * step
                val drawX = if (mirror) (outW - 1 - ox) else ox
                val nx = drawX.toFloat() / outW

                val inRoi = !hasRoi || (nx >= roi!!.left - 0.08f && nx <= roi.right + 0.08f &&
                    ny >= roi.top - 0.08f && ny <= roi.bottom + 0.08f)

                val c = sampleY(ux, uy)
                val l = sampleY(ux - step, uy)
                val r = sampleY(ux + step, uy)
                val t = sampleY(ux, uy - step)
                val b = sampleY(ux, uy + step)

                val gx = abs(r - l)
                val gy = abs(b - t)
                val lap = abs(4 * c - l - r - t - b)
                val edgeEnergy = (gx + gy) + (lap shr 1)

                val threshold = if (inRoi) 62 else 105
                if (edgeEnergy > threshold) {
                    val alpha = ((edgeEnergy - threshold) * 3).coerceIn(70, 235)
                    val color = if (edgeEnergy > threshold + 45) {
                        (alpha shl 24) or 0x0000E5FF // Яркий сине-голубой неон на пике резкости
                    } else {
                        (alpha shl 24) or 0x002979FF // Глубокий синий контур
                    }
                    pixels[oy * outW + drawX] = color
                }
            }
        }
        return Bitmap.createBitmap(pixels, outW, outH, Bitmap.Config.ARGB_8888)
    }

    fun close() {
        detector.close()
        scanner.close()
    }

    private fun labelOf(o: DetectedObject): String? {
        val best = o.labels.maxByOrNull { it.confidence } ?: return null
        if (best.confidence < 0.55f) return null
        return when (best.text) {
            "Fashion good" -> "Одежда"
            "Food" -> "Еда"
            "Home good" -> "Предмет"
            "Place" -> "Сцена"
            "Plant" -> "Растение"
            else -> null
        }
    }

    /** Перевод из координат выпрямленного кадра анализа в нормированные координаты PreviewView (0..1). */
    private class Mapper(
        w: Int,
        h: Int,
        rot: Int,
        private val mirror: Boolean,
        viewAspect: Float,
    ) {
        private val c: RectF

        init {
            val uw = (if (rot % 180 == 0) w else h).toFloat().coerceAtLeast(1f)
            val uh = (if (rot % 180 == 0) h else w).toFloat().coerceAtLeast(1f)
            val imgAspect = uw / uh
            c = if (imgAspect > viewAspect) {
                val cw = uh * viewAspect
                RectF((uw - cw) / 2f, 0f, (uw + cw) / 2f, uh)
            } else {
                val ch = uw / viewAspect
                RectF(0f, (uh - ch) / 2f, uw, (uh + ch) / 2f)
            }
        }

        fun map(b: Rect): RectF {
            val cw = c.width().coerceAtLeast(1f)
            val ch = c.height().coerceAtLeast(1f)
            var l = (b.left - c.left) / cw
            var r = (b.right - c.left) / cw
            val t = (b.top - c.top) / ch
            val bt = (b.bottom - c.top) / ch
            if (mirror) {
                val nl = 1f - r
                r = 1f - l
                l = nl
            }
            val cl = min(l, r).coerceIn(0.02f, 0.94f)
            val ct = min(t, bt).coerceIn(0.02f, 0.94f)
            val cr = max(l, r).coerceIn(cl + 0.04f, 0.98f)
            val cb = max(t, bt).coerceIn(ct + 0.04f, 0.98f)
            return RectF(cl, ct, cr, cb)
        }
    }
}
