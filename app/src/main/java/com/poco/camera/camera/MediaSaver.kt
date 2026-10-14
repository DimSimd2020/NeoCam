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
import androidx.exifinterface.media.ExifInterface
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

object MediaSaver {
    const val ALBUM = "DCIM/NeoCam"
    const val AUTHOR = "DimSimd"
    const val SOFTWARE = "NeoCam by DimSimd"

    fun newName(prefix: String): String =
        prefix + SimpleDateFormat("yyyyMMdd_HHmmss_SSS", Locale.US).format(Date())

    fun imageValues(name: String, mime: String = "image/jpeg") = ContentValues().apply {
        put(MediaStore.MediaColumns.DISPLAY_NAME, name)
        put(MediaStore.MediaColumns.MIME_TYPE, mime)
        put(MediaStore.MediaColumns.RELATIVE_PATH, ALBUM)
    }

    fun videoValues(name: String) = ContentValues().apply {
        put(MediaStore.MediaColumns.DISPLAY_NAME, name)
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
     * Цифровой «супер-зум» (вплоть до 100x Space Zoom) и/или квадратный кроп 1:1:
     * декодируем только нужную центральную область через BitmapRegionDecoder (чтобы не держать 200 МП в RAM),
     * поворачиваем по EXIF и масштабируем до оптимального размера.
     */
    fun saveProcessed(
        context: Context,
        src: File,
        factor: Float,
        squareCrop: Boolean,
        name: String,
    ): Uri? {
        val exif = ExifInterface(src)
        val orientation = exif.getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(src.path, bounds)
        val w = bounds.outWidth
        val h = bounds.outHeight
        if (w <= 0 || h <= 0) return null

        val baseW = if (squareCrop) min(w, h) else w
        val baseH = if (squareCrop) min(w, h) else h
        val z = factor.coerceAtLeast(1f)
        val cw = (baseW / z).roundToInt().coerceAtLeast(16)
        val ch = (baseH / z).roundToInt().coerceAtLeast(16)
        val left = (w - cw) / 2
        val top = (h - ch) / 2

        val region = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            BitmapRegionDecoder.newInstance(src.path)
        } else {
            @Suppress("DEPRECATION")
            BitmapRegionDecoder.newInstance(src.path, false)
        } ?: return null

        val cropped = try {
            region.decodeRegion(Rect(left, top, left + cw, top + ch), null)
        } finally {
            region.recycle()
        } ?: return null

        val targetLong = min(max(baseW, baseH), 4000)
        val scale = (targetLong.toFloat() / max(cw, ch)).coerceIn(1f, z.coerceAtMost(8f))
        val m = orientationMatrix(orientation).apply { preScale(scale, scale) }
        val out = Bitmap.createBitmap(cropped, 0, 0, cropped.width, cropped.height, m, true)
        if (out !== cropped) cropped.recycle()

        val tmpOut = File(context.cacheDir, "$name-out.jpg")
        try {
            tmpOut.outputStream().use { out.compress(Bitmap.CompressFormat.JPEG, 94, it) }
            out.recycle()
            val outExif = ExifInterface(tmpOut)
            exifToCopy.forEach { tag -> exif.getAttribute(tag)?.let { outExif.setAttribute(tag, it) } }
            outExif.setAttribute(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL.toString())
            outExif.setAttribute(ExifInterface.TAG_ARTIST, AUTHOR)
            outExif.setAttribute(ExifInterface.TAG_SOFTWARE, SOFTWARE)
            outExif.saveAttributes()
            return insertFile(context, tmpOut, imageValues(name))
        } finally {
            tmpOut.delete()
        }
    }

    /** Копирует файл в альбом PocoCam через MediaStore. */
    fun insertFile(context: Context, file: File, values: ContentValues): Uri? {
        val resolver = context.contentResolver
        values.put(MediaStore.MediaColumns.IS_PENDING, 1)
        val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values) ?: return null
        resolver.openOutputStream(uri)?.use { os -> file.inputStream().use { it.copyTo(os) } }
        resolver.update(uri, ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }, null, null)
        return uri
    }

    /** Сохраняет копию любого медиа (фото или видео) в альбом PocoCam. */
    fun copyToAlbum(context: Context, source: Uri, isVideo: Boolean, mime: String): Uri? {
        val resolver = context.contentResolver
        val ext = if (isVideo) "mp4" else when (mime) {
            "image/png" -> "png"; "image/webp" -> "webp"; "image/heic" -> "heic"; else -> "jpg"
        }
        val name = newName(if (isVideo) "VID_COPY_" else "IMG_COPY_") + "." + ext
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
