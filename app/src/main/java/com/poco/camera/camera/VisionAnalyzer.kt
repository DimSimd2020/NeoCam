package com.poco.camera.camera

import android.graphics.PointF
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
 * Гибридный анализатор кадра в стиле GCam:
 * 1) Находит объекты через ML Kit ObjectDetection и уточняет их реальные границы по градиенту яркости (Y-плоскость).
 * 2) Если ML Kit не распознал мелкий/нестандартный предмет в центре или в точке касания — находит контур предмета по локальному контрасту краёв.
 * 3) Сканирует QR-коды.
 */
class VisionAnalyzer(
    private val flags: () -> Flags,
    private val onObjects: (List<TrackedObj>) -> Unit,
    private val onQr: (QrHit) -> Unit,
) : ImageAnalysis.Analyzer {

    data class Flags(
        val objects: Boolean,
        val qr: Boolean,
        val mirror: Boolean,
        val viewAspect: Float,
        val anchorPoint: PointF? = null,
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

    @OptIn(ExperimentalGetImage::class)
    override fun analyze(proxy: ImageProxy) {
        val media = proxy.image
        val f = flags()
        if (media == null || (!f.objects && !f.qr)) {
            proxy.close()
            return
        }
        frameNo++
        val rotation = proxy.imageInfo.rotationDegrees
        val mapper = Mapper(proxy.width, proxy.height, rotation, f.mirror, f.viewAspect)

        // Строим компактную карту градиентов резкости 80x60 из Y-канала для точной привязки к краям предмета
        val edgeGrid = if (f.objects) buildEdgeGrid(proxy, rotation, f.mirror) else null

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
                    val mlObjs = objTask.result.mapIndexedNotNull { i, o ->
                        val rawRect = mapper.map(o.boundingBox)
                        if (rawRect.width() < 0.05f || rawRect.height() < 0.05f) {
                            null
                        } else {
                            val tight = edgeGrid?.tighten(rawRect) ?: rawRect
                            TrackedObj(o.trackingId ?: (-1 - i), tight, labelOf(o))
                        }
                    }.toMutableList()

                    // Если пользователь тапнул по предмету (или держит предмет в центре), который ML Kit пропустил,
                    // находим границы этого предмета по карте контрастных краёв
                    val anchor = f.anchorPoint ?: PointF(0.5f, 0.5f)
                    val coveredByMl = mlObjs.any { it.rect.contains(anchor.x, anchor.y) }
                    if (!coveredByMl && edgeGrid != null) {
                        edgeGrid.findSalientObjectAt(anchor.x, anchor.y)?.let { salientRect ->
                            mlObjs.add(0, TrackedObj(id = -99, rect = salientRect, label = null))
                        }
                    }

                    onObjects(mlObjs)
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
     * Компактная сетка градиентов резкости (72 x 96) для точного облегания контуров объекта.
     */
    private class EdgeGrid(val gw: Int, val gh: Int, val energy: IntArray) {
        /** Поджимает рамку ML Kit к реальным контрастным краям предмета внутри неё. */
        fun tighten(r: RectF): RectF {
            val x0 = (r.left * gw).toInt().coerceIn(1, gw - 2)
            val x1 = (r.right * gw).toInt().coerceIn(x0 + 1, gw - 1)
            val y0 = (r.top * gh).toInt().coerceIn(1, gh - 2)
            val y1 = (r.bottom * gh).toInt().coerceIn(y0 + 1, gh - 1)

            var sum = 0L
            var count = 0
            for (y in y0..y1) {
                val row = y * gw
                for (x in x0..x1) {
                    sum += energy[row + x]
                    count++
                }
            }
            if (count == 0) return r
            val thresh = max(38, ((sum / count) * 14 / 10).toInt())

            var minX = x1
            var maxX = x0
            var minY = y1
            var maxY = y0
            var hits = 0
            for (y in y0..y1) {
                val row = y * gw
                for (x in x0..x1) {
                    if (energy[row + x] >= thresh) {
                        if (x < minX) minX = x
                        if (x > maxX) maxX = x
                        if (y < minY) minY = y
                        if (y > maxY) maxY = y
                        hits++
                    }
                }
            }
            if (hits < 8 || maxX - minX < 4 || maxY - minY < 4) return r

            val padX = 0.015f
            val padY = 0.015f
            val nl = (minX.toFloat() / gw - padX).coerceIn(r.left, r.right - 0.06f)
            val nt = (minY.toFloat() / gh - padY).coerceIn(r.top, r.bottom - 0.06f)
            val nr = (maxX.toFloat() / gw + padX).coerceIn(nl + 0.06f, r.right)
            val nb = (maxY.toFloat() / gh + padY).coerceIn(nt + 0.06f, r.bottom)
            return RectF(nl, nt, nr, nb)
        }

        /** Находит контрастный объект вокруг точки (ax, ay), если ML Kit его не классифицировал. */
        fun findSalientObjectAt(ax: Float, ay: Float): RectF? {
            val cx = (ax * gw).toInt().coerceIn(4, gw - 5)
            val cy = (ay * gh).toInt().coerceIn(4, gh - 5)
            val rx = (gw * 0.20f).toInt().coerceAtLeast(6)
            val ry = (gh * 0.18f).toInt().coerceAtLeast(6)

            val x0 = (cx - rx).coerceAtLeast(1)
            val x1 = (cx + rx).coerceAtMost(gw - 2)
            val y0 = (cy - ry).coerceAtLeast(1)
            val y1 = (cy + ry).coerceAtMost(gh - 2)

            var sumX = 0f
            var sumY = 0f
            var totalW = 0f
            for (y in y0..y1) {
                val row = y * gw
                for (x in x0..x1) {
                    val e = energy[row + x]
                    if (e > 48) {
                        val w = (e - 48).toFloat()
                        sumX += x * w
                        sumY += y * w
                        totalW += w
                    }
                }
            }
            if (totalW < 220f) return null

            val meanX = sumX / totalW
            val meanY = sumY / totalW
            var varX = 0f
            var varY = 0f
            for (y in y0..y1) {
                val row = y * gw
                for (x in x0..x1) {
                    val e = energy[row + x]
                    if (e > 48) {
                        val w = (e - 48).toFloat()
                        varX += w * (x - meanX) * (x - meanX)
                        varY += w * (y - meanY) * (y - meanY)
                    }
                }
            }
            val stdX = kotlin.math.sqrt((varX / totalW).toDouble()).toFloat() * 1.9f
            val stdY = kotlin.math.sqrt((varY / totalW).toDouble()).toFloat() * 1.9f
            val halfW = (stdX / gw).coerceIn(0.06f, 0.22f)
            val halfH = (stdY / gh).coerceIn(0.06f, 0.22f)
            val ncx = (meanX / gw).coerceIn(0.10f, 0.90f)
            val ncy = (meanY / gh).coerceIn(0.10f, 0.90f)
            return RectF(
                (ncx - halfW).coerceIn(0.03f, 0.88f),
                (ncy - halfH).coerceIn(0.03f, 0.88f),
                (ncx + halfW).coerceIn(0.12f, 0.97f),
                (ncy + halfH).coerceIn(0.12f, 0.97f),
            )
        }
    }

    private fun buildEdgeGrid(proxy: ImageProxy, rotation: Int, mirror: Boolean): EdgeGrid? {
        return runCatching {
            val plane = proxy.planes.firstOrNull() ?: return null
            val buf = plane.buffer
            val rowStride = plane.rowStride
            val pixelStride = plane.pixelStride
            val srcW = proxy.width
            val srcH = proxy.height
            val uprightW = if (rotation % 180 == 0) srcW else srcH
            val uprightH = if (rotation % 180 == 0) srcH else srcW
            val step = 8
            val gw = (uprightW / step).coerceAtLeast(16)
            val gh = (uprightH / step).coerceAtLeast(16)
            val limit = buf.limit()

            fun sampleY(ux: Int, uy: Int): Int {
                val sx: Int
                val sy: Int
                when (rotation) {
                    90 -> { sx = uy; sy = srcH - 1 - ux }
                    180 -> { sx = srcW - 1 - ux; sy = srcH - 1 - uy }
                    270 -> { sx = srcW - 1 - uy; sy = ux }
                    else -> { sx = ux; sy = uy }
                }
                val idx = sy * rowStride + sx * pixelStride
                return if (idx in 0 until limit) (buf.get(idx).toInt() and 0xFF) else 0
            }

            val energy = IntArray(gw * gh)
            for (gy in 1 until gh - 1) {
                val uy = gy * step
                for (gx in 1 until gw - 1) {
                    val ux = gx * step
                    val dx = if (mirror) (gw - 1 - gx) else gx
                    val l = sampleY(ux - step, uy)
                    val r = sampleY(ux + step, uy)
                    val t = sampleY(ux, uy - step)
                    val b = sampleY(ux, uy + step)
                    energy[gy * gw + dx] = abs(r - l) + abs(b - t)
                }
            }
            EdgeGrid(gw, gh, energy)
        }.getOrNull()
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
