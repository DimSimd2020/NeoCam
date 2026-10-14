package com.poco.camera.camera

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.BitmapRegionDecoder
import android.graphics.Matrix
import android.graphics.Rect
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import android.util.Log
import androidx.exifinterface.media.ExifInterface
import java.io.ByteArrayInputStream
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

object MediaSaver {
    const val ALBUM = "DCIM/NeoCam"
    private val seq = AtomicInteger(0)

    fun newBaseName(prefix: String): String {
        val ts = SimpleDateFormat("yyyyMMdd_HHmmss_SSS", Locale.US).format(Date())
        val n = (seq.incrementAndGet() % 1000)
        return "${prefix}${ts}_$n"
    }

    fun imageValues(fileNameWithExt: String, mime: String = "image/jpeg") = ContentValues().apply {
        val clean = if (fileNameWithExt.contains('.')) fileNameWithExt else "$fileNameWithExt.jpg"
        put(MediaStore.MediaColumns.DISPLAY_NAME, clean)
        put(MediaStore.MediaColumns.MIME_TYPE, mime)
        put(MediaStore.MediaColumns.RELATIVE_PATH, ALBUM)
    }

    fun videoValues(fileNameWithExt: String) = ContentValues().apply {
        val clean = if (fileNameWithExt.endsWith(".mp4", true)) fileNameWithExt else "$fileNameWithExt.mp4"
        put(MediaStore.MediaColumns.DISPLAY_NAME, clean)
        put(MediaStore.MediaColumns.MIME_TYPE, "video/mp4")
        put(MediaStore.MediaColumns.RELATIVE_PATH, ALBUM)
    }

    private val exifToCopy = listOf(
        ExifInterface.TAG_DATETIME, ExifInterface.TAG_DATETIME_ORIGINAL, ExifInterface.TAG_DATETIME_DIGITIZED,
        ExifInterface.TAG_OFFSET_TIME, ExifInterface.TAG_OFFSET_TIME_ORIGINAL,
        ExifInterface.TAG_MAKE, ExifInterface.TAG_MODEL, ExifInterface.TAG_F_NUMBER,
        ExifInterface.TAG_EXPOSURE_TIME, ExifInterface.TAG_PHOTOGRAPHIC_SENSITIVITY,
        ExifInterface.TAG_FOCAL_LENGTH, ExifInterface.TAG_FLASH, ExifInterface.TAG_WHITE_BALANCE,
    )

    /**
     * Быстрое декодирование уменьшенной копии реального аппаратного JPEG в RAM (для мгновенного превью без мыла).
     */
    fun decodeFastPreviewBitmap(
        jpegBytes: ByteArray,
        rotationDegrees: Int,
        mirrorHoriz: Boolean,
        extraZoom: Float,
        squareCrop: Boolean,
    ): Bitmap? = runCatching {
        val exif = ExifInterface(ByteArrayInputStream(jpegBytes))
        val exifOri = exif.getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_UNDEFINED)
        val opts = BitmapFactory.Options().apply {
            inSampleSize = 2
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        val raw = BitmapFactory.decodeByteArray(jpegBytes, 0, jpegBytes.size, opts) ?: return null
        val m = if (exifOri != ExifInterface.ORIENTATION_UNDEFINED && exifOri != ExifInterface.ORIENTATION_NORMAL) {
            orientationMatrix(exifOri)
        } else {
            Matrix().apply { if (rotationDegrees != 0) postRotate(rotationDegrees.toFloat()) }
        }
        if (mirrorHoriz) m.postScale(-1f, 1f)

        val w = raw.width
        val h = raw.height
        val baseW = if (squareCrop) min(w, h) else w
        val baseH = if (squareCrop) min(w, h) else h
        val z = extraZoom.coerceAtLeast(1f)
        val cw = (baseW / z).roundToInt().coerceIn(16, w)
        val ch = (baseH / z).roundToInt().coerceIn(16, h)
        val left = ((w - cw) / 2).coerceAtLeast(0)
        val top = ((h - ch) / 2).coerceAtLeast(0)

        val out = Bitmap.createBitmap(raw, left, top, cw, ch, m, true)
        if (out !== raw) raw.recycle()
        out
    }.getOrNull()

    /**
     * Сохраняет аппаратный JPEG-буфер напрямую в MediaStore (без пережатия при 1x, либо с региональным кропом при зуме).
     */
    fun saveJpegBytes(
        context: Context,
        jpegBytes: ByteArray,
        rotationDegrees: Int,
        mirrorHoriz: Boolean,
        factor: Float,
        squareCrop: Boolean,
        baseName: String,
    ): Uri? {
        if (jpegBytes.isEmpty()) return null

        // Если нет цифрового добора, квадратного кропа и зеркалирования — пишем оригинальный аппаратный JPEG напрямую в MediaStore за несколько миллисекунд!
        if (factor <= 1.02f && !squareCrop && !mirrorHoriz) {
            val exif = runCatching { ExifInterface(ByteArrayInputStream(jpegBytes)) }.getOrNull()
            val hasExifOri = exif?.getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_UNDEFINED)
                ?.let { it != ExifInterface.ORIENTATION_UNDEFINED } == true
            if (hasExifOri || rotationDegrees == 0) {
                return insertJpegBytesDirect(context, jpegBytes, "$baseName.jpg")
            }
        }

        val tmpSrc = File(context.cacheDir, "$baseName-src.jpg")
        try {
            tmpSrc.writeBytes(jpegBytes)
            val exif = ExifInterface(tmpSrc)
            val curOri = exif.getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_UNDEFINED)
            if (curOri == ExifInterface.ORIENTATION_UNDEFINED || curOri == ExifInterface.ORIENTATION_NORMAL) {
                val mappedOri = when ((rotationDegrees % 360 + 360) % 360) {
                    90 -> ExifInterface.ORIENTATION_ROTATE_90
                    180 -> ExifInterface.ORIENTATION_ROTATE_180
                    270 -> ExifInterface.ORIENTATION_ROTATE_270
                    else -> ExifInterface.ORIENTATION_NORMAL
                }
                if (mappedOri != ExifInterface.ORIENTATION_NORMAL) {
                    exif.setAttribute(ExifInterface.TAG_ORIENTATION, mappedOri.toString())
                    exif.saveAttributes()
                }
            }
            if (factor <= 1.02f && !squareCrop && !mirrorHoriz) {
                return insertImageFile(context, tmpSrc, "$baseName.jpg")
            }
            return saveProcessed(context, tmpSrc, factor, squareCrop, mirrorHoriz, baseName)
        } finally {
            tmpSrc.delete()
        }
    }

    fun insertJpegBytesDirect(context: Context, bytes: ByteArray, fileName: String): Uri? {
        val resolver = context.contentResolver
        val values = imageValues(fileName).apply {
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
        val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values) ?: return null
        return try {
            val outStream = resolver.openOutputStream(uri) ?: error("openOutputStream null")
            outStream.use { it.write(bytes) }
            resolver.update(uri, ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }, null, null)
            uri
        } catch (e: Exception) {
            Log.e("NeoCam", "insertJpegBytesDirect failed", e)
            runCatching { resolver.delete(uri, null, null) }
            null
        }
    }

    fun saveProcessed(
        context: Context,
        src: File,
        factor: Float,
        squareCrop: Boolean,
        mirrorHoriz: Boolean,
        baseName: String,
    ): Uri? {
        if (!src.exists() || src.length() == 0L) return null
        return try {
            val exif = ExifInterface(src)
            val orientation = exif.getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(src.path, bounds)
            val w = bounds.outWidth
            val h = bounds.outHeight
            if (w <= 0 || h <= 0) return insertImageFile(context, src, "$baseName.jpg")

            val baseW = if (squareCrop) min(w, h) else w
            val baseH = if (squareCrop) min(w, h) else h
            val z = factor.coerceAtLeast(1f)
            val cw = (baseW / z).roundToInt().coerceIn(16, w)
            val ch = (baseH / z).roundToInt().coerceIn(16, h)
            val left = ((w - cw) / 2).coerceAtLeast(0)
            val top = ((h - ch) / 2).coerceAtLeast(0)

            val region = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                BitmapRegionDecoder.newInstance(src.path)
            } else {
                @Suppress("DEPRECATION")
                BitmapRegionDecoder.newInstance(src.path, false)
            } ?: return insertImageFile(context, src, "$baseName.jpg")

            val cropped = try {
                region.decodeRegion(Rect(left, top, left + cw, top + ch), null)
            } finally {
                region.recycle()
            } ?: return insertImageFile(context, src, "$baseName.jpg")

            val targetLong = min(max(baseW, baseH), 4000)
            val scale = (targetLong.toFloat() / max(cw, ch)).coerceIn(1f, z.coerceAtMost(6f))
            val m = orientationMatrix(orientation).apply {
                preScale(scale, scale)
                if (mirrorHoriz) postScale(-1f, 1f)
            }
            val out = Bitmap.createBitmap(cropped, 0, 0, cropped.width, cropped.height, m, true)
            if (out !== cropped) cropped.recycle()

            val tmpOut = File(context.cacheDir, "$baseName-out.jpg")
            try {
                tmpOut.outputStream().use { out.compress(Bitmap.CompressFormat.JPEG, 95, it) }
                out.recycle()
                val outExif = ExifInterface(tmpOut)
                exifToCopy.forEach { tag -> exif.getAttribute(tag)?.let { outExif.setAttribute(tag, it) } }
                outExif.setAttribute(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL.toString())
                outExif.saveAttributes()
                insertImageFile(context, tmpOut, "$baseName.jpg")
            } finally {
                tmpOut.delete()
            }
        } catch (e: Exception) {
            Log.e("NeoCam", "saveProcessed fallback to raw file", e)
            insertImageFile(context, src, "$baseName.jpg")
        }
    }

    fun insertImageFile(context: Context, file: File, fileName: String): Uri? {
        if (!file.exists() || file.length() == 0L) return null
        val resolver = context.contentResolver
        val values = imageValues(fileName).apply {
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
        val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values) ?: return null
        return try {
            val outStream = resolver.openOutputStream(uri) ?: error("openOutputStream null")
            outStream.use { os -> file.inputStream().use { it.copyTo(os) } }
            resolver.update(uri, ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }, null, null)
            uri
        } catch (e: Exception) {
            Log.e("NeoCam", "insertImageFile failed", e)
            runCatching { resolver.delete(uri, null, null) }
            null
        }
    }

    fun insertVideoFile(context: Context, file: File, fileName: String): Uri? {
        if (!file.exists() || file.length() == 0L) return null
        val resolver = context.contentResolver
        val values = videoValues(fileName).apply {
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
        val uri = resolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values) ?: return null
        return try {
            val outStream = resolver.openOutputStream(uri) ?: error("openOutputStream null")
            outStream.use { os -> file.inputStream().use { it.copyTo(os) } }
            resolver.update(uri, ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }, null, null)
            uri
        } catch (e: Exception) {
            Log.e("NeoCam", "insertVideoFile failed", e)
            runCatching { resolver.delete(uri, null, null) }
            null
        }
    }

    fun copyToAlbum(context: Context, source: Uri, isVideo: Boolean, mime: String): Uri? {
        val resolver = context.contentResolver
        val ext = if (isVideo) "mp4" else when (mime) {
            "image/png" -> "png"; "image/webp" -> "webp"; "image/heic" -> "heic"; else -> "jpg"
        }
        val name = newBaseName(if (isVideo) "VID_COPY_" else "IMG_COPY_") + "." + ext
        val values = (if (isVideo) videoValues(name) else imageValues(name, mime)).apply {
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
        val collection = if (isVideo) MediaStore.Video.Media.EXTERNAL_CONTENT_URI else MediaStore.Images.Media.EXTERNAL_CONTENT_URI
        val target = resolver.insert(collection, values) ?: return null
        try {
            resolver.openInputStream(source)?.use { input ->
                resolver.openOutputStream(target)?.use { input.copyTo(it) }
            }
            resolver.update(target, ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }, null, null)
        } catch (e: Exception) {
            resolver.delete(target, null, null)
            return null
        }
        return target
    }

    private fun orientationMatrix(orientation: Int) = Matrix().apply {
        when (orientation) {
            ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> setScale(-1f, 1f)
            ExifInterface.ORIENTATION_ROTATE_180 -> setRotate(180f)
            ExifInterface.ORIENTATION_FLIP_VERTICAL -> { setRotate(180f); postScale(-1f, 1f) }
            ExifInterface.ORIENTATION_TRANSPOSE -> { setRotate(90f); postScale(-1f, 1f) }
            ExifInterface.ORIENTATION_ROTATE_90 -> setRotate(90f)
            ExifInterface.ORIENTATION_TRANSVERSE -> { setRotate(-90f); postScale(-1f, 1f) }
            ExifInterface.ORIENTATION_ROTATE_270 -> setRotate(-90f)
        }
    }
}
