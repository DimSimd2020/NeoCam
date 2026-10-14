package com.poco.camera.data

import android.app.RecoverableSecurityException
import android.content.ContentUris
import android.content.Context
import android.content.IntentSender
import android.database.ContentObserver
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import androidx.exifinterface.media.ExifInterface
import com.poco.camera.camera.MediaSaver
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.roundToInt

data class MediaItem(
    val id: Long,
    val uri: Uri,
    val name: String,
    val mime: String,
    val isVideo: Boolean,
    val dateAddedSec: Long,
    val sizeBytes: Long,
    val width: Int,
    val height: Int,
    val durationMs: Long,
    val relativePath: String,
    /** Мгновенный Bitmap в памяти для 0 мс просмотра сразу после нажатия затвора. */
    val previewBitmap: Bitmap? = null,
) {
    val inPocoAlbum: Boolean
        get() = relativePath.contains("NeoCam", ignoreCase = true) ||
            relativePath.contains("PocoCam", ignoreCase = true)
}

data class MediaDetails(
    val name: String,
    val dateFormatted: String,
    val path: String,
    val resolution: String,
    val megapixels: String?,
    val sizeFormatted: String,
    val durationFormatted: String?,
    val cameraModel: String?,
    val exposureInfo: String?,
)

sealed interface DeleteOutcome {
    data object Deleted : DeleteOutcome
    data class NeedsConfirmation(val intentSender: IntentSender) : DeleteOutcome
    data object Failed : DeleteOutcome
}

class GalleryRepository(
    private val context: Context,
    private val scope: CoroutineScope,
) {
    private val resolver = context.contentResolver

    private val _albumItems = MutableStateFlow<List<MediaItem>>(emptyList())
    val albumItems: StateFlow<List<MediaItem>> = _albumItems.asStateFlow()

    private val _allItems = MutableStateFlow<List<MediaItem>>(emptyList())
    val allItems: StateFlow<List<MediaItem>> = _allItems.asStateFlow()

    private val _latestUri = MutableStateFlow<Uri?>(null)
    val latestUri: StateFlow<Uri?> = _latestUri.asStateFlow()

    private val _latestBitmap = MutableStateFlow<Bitmap?>(null)
    val latestBitmap: StateFlow<Bitmap?> = _latestBitmap.asStateFlow()

    // Кэш мгновенных кадров по shotId и по сохраненному Uri (чтобы при переходе в галерею не было декодирования с диска)
    private val pendingShots = ConcurrentHashMap<Long, MediaItem>()
    private val uriBitmapCache = ConcurrentHashMap<Uri, Bitmap>()

    private var refreshJob: Job? = null

    private val observer = object : ContentObserver(Handler(Looper.getMainLooper())) {
        override fun onChange(selfChange: Boolean) {
            scheduleRefresh(350L)
        }
    }

    fun startObserving() {
        runCatching {
            resolver.registerContentObserver(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, true, observer)
            resolver.registerContentObserver(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, true, observer)
        }
        refresh()
    }

    fun stopObserving() {
        runCatching { resolver.unregisterContentObserver(observer) }
    }

    /**
     * Вызывается в ту же миллисекунду, когда пользователь нажал на кнопку затвора.
     * Снимок СРАЗУ доступен в миниатюре и полноэкранной галерее из RAM (0 мс ожидания записи на диск).
     */
    fun onInstantCaptured(shotId: Long, bitmap: Bitmap, name: String) {
        _latestBitmap.value = bitmap
        val nowSec = System.currentTimeMillis() / 1000L
        val instantItem = MediaItem(
            id = -shotId,
            uri = Uri.EMPTY,
            name = name,
            mime = "image/jpeg",
            isVideo = false,
            dateAddedSec = nowSec,
            sizeBytes = 0L,
            width = bitmap.width,
            height = bitmap.height,
            durationMs = 0L,
            relativePath = MediaSaver.ALBUM,
            previewBitmap = bitmap,
        )
        pendingShots[shotId] = instantItem
        _albumItems.value = listOf(instantItem) + _albumItems.value.filterNot { it.id == -shotId }
        _allItems.value = listOf(instantItem) + _allItems.value.filterNot { it.id == -shotId }
    }

    /**
     * Вызывается, когда фоновый поток завершил сохранение JPEG/MP4 в MediaStore.
     * Бесшовно привязывает реальный Uri к мгновенному снимку без мерцания.
     */
    fun onNewSavedUri(shotId: Long, uri: Uri, isVideo: Boolean) {
        _latestUri.value = uri
        val pending = pendingShots.remove(shotId)
        if (pending?.previewBitmap != null) {
            uriBitmapCache[uri] = pending.previewBitmap
            // Ограничиваем размер кэша в памяти последними 12 снимками
            if (uriBitmapCache.size > 12) {
                uriBitmapCache.keys.firstOrNull()?.let { uriBitmapCache.remove(it) }
            }
        }
        val realId = runCatching { ContentUris.parseId(uri) }.getOrDefault(-shotId)
        if (pending != null) {
            val upgraded = pending.copy(id = realId, uri = uri)
            _albumItems.value = _albumItems.value.map { if (it.id == -shotId) upgraded else it }
            _allItems.value = _allItems.value.map { if (it.id == -shotId) upgraded else it }
        }
        scheduleRefresh(300L)
    }

    private fun scheduleRefresh(delayMs: Long) {
        refreshJob?.cancel()
        refreshJob = scope.launch {
            delay(delayMs)
            refreshNow()
        }
    }

    fun refresh() {
        refreshJob?.cancel()
        refreshJob = scope.launch {
            refreshNow()
        }
    }

    private suspend fun refreshNow() = withContext(Dispatchers.IO) {
        val images = queryCollection(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, isVideo = false)
        val videos = queryCollection(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, isVideo = true)
        val pendingList = pendingShots.values.sortedByDescending { it.dateAddedSec }
        val queriedAll = (images + videos).sortedByDescending { it.dateAddedSec }
        val all = pendingList + queriedAll
        val album = pendingList + queriedAll.filter { it.inPocoAlbum }
        _allItems.value = all
        _albumItems.value = album
        val newest = album.firstOrNull { it.uri != Uri.EMPTY }?.uri
            ?: all.firstOrNull { it.uri != Uri.EMPTY }?.uri
        if (newest != null || _latestUri.value == null) {
            _latestUri.value = newest
        }
    }

    private fun queryCollection(collection: Uri, isVideo: Boolean): List<MediaItem> {
        val projection = buildList {
            add(MediaStore.MediaColumns._ID)
            add(MediaStore.MediaColumns.DISPLAY_NAME)
            add(MediaStore.MediaColumns.MIME_TYPE)
            add(MediaStore.MediaColumns.DATE_ADDED)
            add(MediaStore.MediaColumns.SIZE)
            add(MediaStore.MediaColumns.WIDTH)
            add(MediaStore.MediaColumns.HEIGHT)
            add(MediaStore.MediaColumns.RELATIVE_PATH)
            if (isVideo) add(MediaStore.Video.VideoColumns.DURATION)
        }.toTypedArray()

        val sortOrder = "${MediaStore.MediaColumns.DATE_ADDED} DESC"
        val list = ArrayList<MediaItem>(128)
        runCatching {
            resolver.query(collection, projection, null, null, sortOrder)?.use { c ->
                val idCol = c.getColumnIndexOrThrow(MediaStore.MediaColumns._ID)
                val nameCol = c.getColumnIndexOrThrow(MediaStore.MediaColumns.DISPLAY_NAME)
                val mimeCol = c.getColumnIndexOrThrow(MediaStore.MediaColumns.MIME_TYPE)
                val dateCol = c.getColumnIndexOrThrow(MediaStore.MediaColumns.DATE_ADDED)
                val sizeCol = c.getColumnIndexOrThrow(MediaStore.MediaColumns.SIZE)
                val wCol = c.getColumnIndexOrThrow(MediaStore.MediaColumns.WIDTH)
                val hCol = c.getColumnIndexOrThrow(MediaStore.MediaColumns.HEIGHT)
                val pathCol = c.getColumnIndexOrThrow(MediaStore.MediaColumns.RELATIVE_PATH)
                val durCol = if (isVideo) c.getColumnIndex(MediaStore.Video.VideoColumns.DURATION) else -1

                var count = 0
                while (c.moveToNext() && count < 400) {
                    val id = c.getLong(idCol)
                    val uri = ContentUris.withAppendedId(collection, id)
                    val name = c.getString(nameCol) ?: if (isVideo) "VID_$id.mp4" else "IMG_$id.jpg"
                    val mime = c.getString(mimeCol) ?: if (isVideo) "video/mp4" else "image/jpeg"
                    val date = c.getLong(dateCol)
                    val size = c.getLong(sizeCol)
                    val w = c.getInt(wCol)
                    val h = c.getInt(hCol)
                    val relPath = c.getString(pathCol) ?: ""
                    val dur = if (durCol >= 0) c.getLong(durCol) else 0L
                    list += MediaItem(
                        id = id,
                        uri = uri,
                        name = name,
                        mime = mime,
                        isVideo = isVideo,
                        dateAddedSec = date,
                        sizeBytes = size,
                        width = w,
                        height = h,
                        durationMs = dur,
                        relativePath = relPath,
                        previewBitmap = uriBitmapCache[uri],
                    )
                    count++
                }
            }
        }
        return list
    }

    suspend fun copyToPocoAlbum(item: MediaItem): Uri? = withContext(Dispatchers.IO) {
        if (item.uri == Uri.EMPTY) return@withContext null
        val saved = MediaSaver.copyToAlbum(context, item.uri, item.isVideo, item.mime)
        if (saved != null) refreshNow()
        saved
    }

    suspend fun importExternalUri(uri: Uri): Uri? = withContext(Dispatchers.IO) {
        val mime = resolver.getType(uri) ?: "image/jpeg"
        val isVideo = mime.startsWith("video/")
        val saved = MediaSaver.copyToAlbum(context, uri, isVideo, mime)
        if (saved != null) {
            _latestUri.value = saved
            refreshNow()
        }
        saved
    }

    suspend fun deleteItem(item: MediaItem): DeleteOutcome = withContext(Dispatchers.IO) {
        if (item.id < 0) {
            pendingShots.remove(-item.id)
            _albumItems.value = _albumItems.value.filterNot { it.id == item.id }
            _allItems.value = _allItems.value.filterNot { it.id == item.id }
            return@withContext DeleteOutcome.Deleted
        }
        try {
            val rows = resolver.delete(item.uri, null, null)
            if (rows > 0) {
                uriBitmapCache.remove(item.uri)
                if (_latestUri.value == item.uri) _latestBitmap.value = null
                refreshNow()
                DeleteOutcome.Deleted
            } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                val pi = MediaStore.createDeleteRequest(resolver, listOf(item.uri))
                DeleteOutcome.NeedsConfirmation(pi.intentSender)
            } else {
                DeleteOutcome.Failed
            }
        } catch (sec: SecurityException) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                runCatching {
                    val pi = MediaStore.createDeleteRequest(resolver, listOf(item.uri))
                    DeleteOutcome.NeedsConfirmation(pi.intentSender)
                }.getOrDefault(DeleteOutcome.Failed)
            } else {
                val rse = sec as? RecoverableSecurityException
                if (rse != null) {
                    DeleteOutcome.NeedsConfirmation(rse.userAction.actionIntent.intentSender)
                } else {
                    DeleteOutcome.Failed
                }
            }
        } catch (_: Exception) {
            DeleteOutcome.Failed
        }
    }

    suspend fun loadDetails(item: MediaItem): MediaDetails = withContext(Dispatchers.IO) {
        val dateStr = SimpleDateFormat("dd.MM.yyyy HH:mm:ss", Locale.getDefault())
            .format(Date(item.dateAddedSec * 1000L))
        val sizeMb = item.sizeBytes / (1024.0 * 1024.0)
        val sizeStr = when {
            item.sizeBytes <= 0L -> "Сохраняется в полном разрешении…"
            sizeMb >= 1.0 -> String.format(Locale.US, "%.2f МБ", sizeMb)
            else -> String.format(Locale.US, "%d КБ", (item.sizeBytes / 1024L).coerceAtLeast(1L))
        }

        var w = item.width
        var h = item.height
        var model: String? = null
        var exposure: String? = null

        if (!item.isVideo && item.uri != Uri.EMPTY) {
            runCatching {
                resolver.openInputStream(item.uri)?.use { stream ->
                    val exif = ExifInterface(stream)
                    if (w <= 0 || h <= 0) {
                        w = exif.getAttributeInt(ExifInterface.TAG_IMAGE_WIDTH, w)
                        h = exif.getAttributeInt(ExifInterface.TAG_IMAGE_LENGTH, h)
                    }
                    val make = exif.getAttribute(ExifInterface.TAG_MAKE)?.trim()
                    val mod = exif.getAttribute(ExifInterface.TAG_MODEL)?.trim()
                    model = listOfNotNull(make, mod).distinct().joinToString(" ").ifBlank { null }

                    val parts = mutableListOf<String>()
                    exif.getAttribute(ExifInterface.TAG_F_NUMBER)?.toDoubleOrNull()?.let {
                        parts += String.format(Locale.US, "f/%.1f", it)
                    }
                    exif.getAttribute(ExifInterface.TAG_EXPOSURE_TIME)?.toDoubleOrNull()?.let { exp ->
                        parts += if (exp >= 0.5) {
                            String.format(Locale.US, "%.1f с", exp)
                        } else if (exp > 0) {
                            "1/${(1.0 / exp).roundToInt()} с"
                        } else ""
                    }
                    exif.getAttribute(ExifInterface.TAG_PHOTOGRAPHIC_SENSITIVITY)?.let { iso ->
                        parts += "ISO $iso"
                    }
                    exif.getAttributeDouble(ExifInterface.TAG_FOCAL_LENGTH, 0.0).takeIf { it > 0.0 }?.let { fl ->
                        parts += String.format(Locale.US, "%.1f мм", fl)
                    }
                    exposure = parts.filter { it.isNotBlank() }.joinToString("  •  ").ifBlank { null }
                }
            }
        }

        val resStr = if (w > 0 && h > 0) "$w × $h" else "Неизвестно"
        val mp = if (!item.isVideo && w > 0 && h > 0) {
            val m = (w.toLong() * h.toLong()) / 1_000_000.0
            if (m >= 0.1) String.format(Locale.US, "%.1f МП", m) else null
        } else null

        val durStr = if (item.isVideo && item.durationMs > 0) formatDuration(item.durationMs) else null

        MediaDetails(
            name = item.name,
            dateFormatted = dateStr,
            path = item.relativePath.ifBlank { MediaSaver.ALBUM },
            resolution = resStr,
            megapixels = mp,
            sizeFormatted = sizeStr,
            durationFormatted = durStr,
            cameraModel = model,
            exposureInfo = exposure,
        )
    }
}

fun formatDuration(ms: Long): String {
    val totalSec = (ms / 1000L).coerceAtLeast(0L)
    val hrs = totalSec / 3600L
    val mins = (totalSec % 3600L) / 60L
    val secs = totalSec % 60L
    return if (hrs > 0) {
        String.format(Locale.US, "%d:%02d:%02d", hrs, mins, secs)
    } else {
        String.format(Locale.US, "%02d:%02d", mins, secs)
    }
}
