package com.poco.camera.camera

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

/**
 * Анализ кадров: ML Kit находит объекты (для «аимбота») и QR-коды.
 * Координаты переводятся в систему PreviewView (0..1), с учётом поворота, кропа и зеркала фронталки.
 */
class VisionAnalyzer(
    private val flags: () -> Flags,
    private val onObjects: (List<TrackedObj>) -> Unit,
    private val onQr: (QrHit) -> Unit,
) : ImageAnalysis.Analyzer {

    data class Flags(val objects: Boolean, val qr: Boolean, val mirror: Boolean, val viewAspect: Float)

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
            proxy.close(); return
        }
        frameNo++
        val rotation = proxy.imageInfo.rotationDegrees
        val input = InputImage.fromMediaImage(media, rotation)
        val objTask = if (f.objects) detector.process(input) else null
        val qrTask = if (f.qr && frameNo % 3 == 0L) scanner.process(input) else null
        val tasks = listOfNotNull<Task<*>>(objTask, qrTask)
        if (tasks.isEmpty()) {
            proxy.close(); return
        }
        val mapper = Mapper(proxy.width, proxy.height, rotation, proxy.cropRect, f.mirror, f.viewAspect)
        Tasks.whenAllComplete(tasks).addOnCompleteListener {
            try {
                if (objTask != null && objTask.isSuccessful) {
                    onObjects(objTask.result.mapIndexed { i, o ->
                        TrackedObj(o.trackingId ?: (-1 - i), mapper.map(o.boundingBox), labelOf(o))
                    })
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

    fun close() {
        detector.close()
        scanner.close()
    }

    private fun labelOf(o: DetectedObject): String? {
        val best = o.labels.maxByOrNull { it.confidence } ?: return null
        if (best.confidence < 0.45f) return null
        return when (best.text) {
            "Fashion good" -> "Одежда"
            "Food" -> "Еда"
            "Home good" -> "Предмет"
            "Place" -> "Место"
            "Plant" -> "Растение"
            else -> null
        }
    }

    /** Перевод из координат «выпрямленного» кадра анализа в нормированные координаты PreviewView. */
    private class Mapper(w: Int, h: Int, private val rot: Int, crop: Rect, private val mirror: Boolean, viewAspect: Float) {
        private val c: RectF

        init {
            val uw = if (rot % 180 == 0) w else h
            val uh = if (rot % 180 == 0) h else w
            val full = crop.left == 0 && crop.top == 0 && crop.width() == w && crop.height() == h
            c = if (!full) {
                val r = RectF(crop)
                when (rot) {
                    90 -> RectF(h - r.bottom, r.left, h - r.top, r.right)
                    180 -> RectF(w - r.right, h - r.bottom, w - r.left, h - r.top)
                    270 -> RectF(r.top, w - r.right, r.bottom, w - r.left)
                    else -> r
                }
            } else {
                // ViewPort не применился — считаем центр-кроп как у FILL_CENTER.
                val imgAspect = uw.toFloat() / uh
                if (imgAspect > viewAspect) {
                    val cw = uh * viewAspect
                    RectF((uw - cw) / 2f, 0f, (uw + cw) / 2f, uh.toFloat())
                } else {
                    val ch = uw / viewAspect
                    RectF(0f, (uh - ch) / 2f, uw.toFloat(), (uh + ch) / 2f)
                }
            }
        }

        fun map(b: Rect): RectF {
            var l = (b.left - c.left) / c.width()
            var r = (b.right - c.left) / c.width()
            val t = (b.top - c.top) / c.height()
            val bt = (b.bottom - c.top) / c.height()
            if (mirror) {
                val nl = 1f - r
                r = 1f - l
                l = nl
            }
            return RectF(l, t, r, bt)
        }
    }
}
