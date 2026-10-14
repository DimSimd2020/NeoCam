package com.poco.camera.ui

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.DriveFileMove
import androidx.compose.material.icons.outlined.AddPhotoAlternate
import androidx.compose.material.icons.outlined.Collections
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.GridView
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.MediaItem as ExoMediaItem
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import coil.compose.AsyncImage
import com.poco.camera.data.DeleteOutcome
import com.poco.camera.data.GalleryRepository
import com.poco.camera.data.MediaDetails
import com.poco.camera.data.MediaItem
import com.poco.camera.data.formatDuration
import com.poco.camera.ui.theme.Pc
import com.poco.camera.ui.theme.glass
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GalleryScreen(
    gallery: GalleryRepository,
    onClose: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    val albumItems by gallery.albumItems.collectAsState()
    val allItems by gallery.allItems.collectAsState()

    // 0 = Альбом камеры, 1 = Все медиа устройства
    var tab by remember { mutableIntStateOf(if (albumItems.isNotEmpty()) 0 else 1) }
    val activeItems = if (tab == 0) albumItems else allItems

    // При открытии сразу показываем последний снимок в полноэкранном режиме (0 мс из RAM)
    var viewerIndex by remember {
        mutableStateOf<Int?>(if (activeItems.isNotEmpty()) 0 else null)
    }
    var detailsItem by remember { mutableStateOf<MediaDetails?>(null) }

    val pickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickMultipleVisualMedia(10),
    ) { uris ->
        if (uris.isNotEmpty()) {
            scope.launch {
                var imported = 0
                for (u in uris) {
                    if (gallery.importExternalUri(u) != null) imported++
                }
                if (imported > 0) {
                    tab = 0
                    Toast.makeText(context, "Добавлено в альбом: $imported", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    val deleteLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartIntentSenderForResult(),
    ) { res ->
        if (res.resultCode == Activity.RESULT_OK) {
            gallery.refresh()
            Toast.makeText(context, "Файл удалён", Toast.LENGTH_SHORT).show()
        }
    }

    BackHandler {
        onClose()
    }

    // Следим, чтобы индекс полноэкранного просмотра оставался в пределах списка
    LaunchedEffect(activeItems.size) {
        val idx = viewerIndex
        if (idx != null) {
            if (activeItems.isEmpty()) viewerIndex = null
            else if (idx >= activeItems.size) viewerIndex = activeItems.lastIndex
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Pc.Bg),
    ) {
        val currentIdx = viewerIndex
        if (currentIdx != null && activeItems.isNotEmpty()) {
            FullScreenViewer(
                items = activeItems,
                initialIndex = currentIdx.coerceIn(0, activeItems.lastIndex),
                onBackToGrid = { viewerIndex = null },
                onCloseGallery = onClose,
                onShowDetails = { item ->
                    scope.launch {
                        detailsItem = gallery.loadDetails(item)
                    }
                },
                onCopyToAlbum = { item ->
                    scope.launch {
                        val uri = gallery.copyToPocoAlbum(item)
                        Toast.makeText(
                            context,
                            if (uri != null) "Сохранено в альбом камеры" else "Не удалось скопировать",
                            Toast.LENGTH_SHORT,
                        ).show()
                    }
                },
                onDelete = { item ->
                    scope.launch {
                        when (val out = gallery.deleteItem(item)) {
                            DeleteOutcome.Deleted ->
                                Toast.makeText(context, "Удалено", Toast.LENGTH_SHORT).show()
                            is DeleteOutcome.NeedsConfirmation ->
                                deleteLauncher.launch(IntentSenderRequest.Builder(out.intentSender).build())
                            DeleteOutcome.Failed ->
                                Toast.makeText(context, "Не удалось удалить файл", Toast.LENGTH_SHORT).show()
                        }
                    }
                },
            )
        } else {
            GalleryGridContent(
                tab = tab,
                albumCount = albumItems.size,
                allCount = allItems.size,
                items = activeItems,
                onSelectTab = { tab = it },
                onItemClick = { idx -> viewerIndex = idx },
                onImportClick = {
                    pickerLauncher.launch(
                        PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageAndVideo)
                    )
                },
                onClose = onClose,
            )
        }
    }

    val info = detailsItem
    if (info != null) {
        ModalBottomSheet(
            onDismissRequest = { detailsItem = null },
            containerColor = Pc.Surface,
            contentColor = Pc.Text,
        ) {
            Column(
                verticalArrangement = Arrangement.spacedBy(10.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp)
                    .padding(bottom = 32.dp),
            ) {
                Text(
                    text = info.name,
                    style = MaterialTheme.typography.titleMedium,
                    color = Pc.Text,
                )
                info.exposureInfo?.let { exp ->
                    Text(
                        text = exp,
                        color = Pc.Accent,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.SemiBold,
                    )
                }
                DetailRow("Дата съёмки", info.dateFormatted)
                DetailRow(
                    "Разрешение",
                    buildString {
                        append(info.resolution)
                        info.megapixels?.let { append(" ($it)") }
                    },
                )
                DetailRow("Размер файла", info.sizeFormatted)
                info.durationFormatted?.let { DetailRow("Длительность", it) }
                info.cameraModel?.let { DetailRow("Устройство", it) }
                DetailRow("Папка", info.path)
            }
        }
    }
}

@Composable
private fun DetailRow(label: String, value: String) {
    Row(
        horizontalArrangement = Arrangement.SpaceBetween,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Text(text = label, color = Pc.TextDim, fontSize = 13.sp)
        Text(text = value, color = Pc.Text, fontSize = 13.sp, fontWeight = FontWeight.Medium)
    }
}

@Composable
private fun GalleryGridContent(
    tab: Int,
    albumCount: Int,
    allCount: Int,
    items: List<MediaItem>,
    onSelectTab: (Int) -> Unit,
    onItemClick: (Int) -> Unit,
    onImportClick: () -> Unit,
    onClose: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .statusBarsPadding()
            .navigationBarsPadding(),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 10.dp),
        ) {
            IconButton(onClick = onClose) {
                Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = "К камере", tint = Pc.Text)
            }
            Text(
                text = "Галерея",
                style = MaterialTheme.typography.titleMedium,
                color = Pc.Text,
                modifier = Modifier.weight(1f).padding(start = 4.dp),
            )
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .clip(CircleShape)
                    .background(Pc.SurfaceHi)
                    .border(1.dp, Pc.Accent.copy(alpha = 0.45f), CircleShape)
                    .clickable(onClick = onImportClick)
                    .padding(horizontal = 12.dp, vertical = 7.dp),
            ) {
                Icon(
                    imageVector = Icons.Outlined.AddPhotoAlternate,
                    contentDescription = null,
                    tint = Pc.Accent,
                    modifier = Modifier.size(16.dp),
                )
                Spacer(Modifier.width(6.dp))
                Text(
                    text = "Импорт",
                    color = Pc.Accent,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.SemiBold,
                )
            }
        }

        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 6.dp),
        ) {
            GalleryTabChip(
                text = "Камера ($albumCount)",
                selected = tab == 0,
                onClick = { onSelectTab(0) },
                modifier = Modifier.weight(1f),
            )
            GalleryTabChip(
                text = "Все ($allCount)",
                selected = tab == 1,
                onClick = { onSelectTab(1) },
                modifier = Modifier.weight(1f),
            )
        }

        if (items.isEmpty()) {
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .fillMaxSize()
                    .padding(32.dp),
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(
                        imageVector = Icons.Outlined.Collections,
                        contentDescription = null,
                        tint = Pc.TextDim,
                        modifier = Modifier.size(56.dp),
                    )
                    Spacer(Modifier.height(12.dp))
                    Text(
                        text = "Нет фото и видео",
                        color = Pc.Text,
                        style = MaterialTheme.typography.titleMedium,
                    )
                }
            }
        } else {
            LazyVerticalGrid(
                columns = GridCells.Fixed(3),
                contentPadding = PaddingValues(12.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
                modifier = Modifier.fillMaxSize(),
            ) {
                itemsIndexed(items, key = { _, it -> "${it.isVideo}_${it.name}_${it.id}" }) { index, item ->
                    Box(
                        modifier = Modifier
                            .aspectRatio(1f)
                            .clip(RoundedCornerShape(12.dp))
                            .background(Pc.SurfaceHi)
                            .clickable { onItemClick(index) },
                    ) {
                        val bmp = item.previewBitmap
                        if (bmp != null) {
                            val imgBmp = remember(bmp) { bmp.asImageBitmap() }
                            Image(
                                bitmap = imgBmp,
                                contentDescription = item.name,
                                contentScale = ContentScale.Crop,
                                modifier = Modifier.fillMaxSize(),
                            )
                        }
                        if (item.uri != Uri.EMPTY) {
                            AsyncImage(
                                model = item.uri,
                                contentDescription = item.name,
                                contentScale = ContentScale.Crop,
                                modifier = Modifier.fillMaxSize(),
                            )
                        }
                        if (item.isVideo) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier
                                    .align(Alignment.BottomEnd)
                                    .padding(6.dp)
                                    .background(Color(0x99000000), CircleShape)
                                    .padding(horizontal = 6.dp, vertical = 2.dp),
                            ) {
                                Icon(
                                    imageVector = Icons.Outlined.PlayArrow,
                                    contentDescription = null,
                                    tint = Color.White,
                                    modifier = Modifier.size(12.dp),
                                )
                                Spacer(Modifier.width(2.dp))
                                Text(
                                    text = formatDuration(item.durationMs),
                                    color = Color.White,
                                    fontSize = 10.sp,
                                    fontWeight = FontWeight.SemiBold,
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun GalleryTabChip(
    text: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(
        contentAlignment = Alignment.Center,
        modifier = modifier
            .clip(CircleShape)
            .background(if (selected) Pc.Accent else Pc.SurfaceHi)
            .clickable(onClick = onClick)
            .padding(vertical = 9.dp, horizontal = 12.dp),
    ) {
        Text(
            text = text,
            color = if (selected) Pc.OnAccent else Pc.Text,
            fontSize = 12.sp,
            fontWeight = FontWeight.Bold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun FullScreenViewer(
    items: List<MediaItem>,
    initialIndex: Int,
    onBackToGrid: () -> Unit,
    onCloseGallery: () -> Unit,
    onShowDetails: (MediaItem) -> Unit,
    onCopyToAlbum: (MediaItem) -> Unit,
    onDelete: (MediaItem) -> Unit,
) {
    val context = LocalContext.current
    val pagerState = rememberPagerState(
        initialPage = initialIndex.coerceIn(0, items.lastIndex),
        pageCount = { items.size },
    )
    var chromeVisible by remember { mutableStateOf(true) }
    var pageZoomed by remember { mutableStateOf(false) }

    val currentItem = items.getOrNull(pagerState.currentPage) ?: return

    Box(modifier = Modifier.fillMaxSize().background(Color.Black)) {
        HorizontalPager(
            state = pagerState,
            userScrollEnabled = !pageZoomed,
            key = { idx -> items.getOrNull(idx)?.let { "${it.isVideo}_${it.name}" } ?: idx },
            modifier = Modifier.fillMaxSize(),
        ) { page ->
            val item = items.getOrNull(page) ?: return@HorizontalPager
            if (item.isVideo) {
                VideoPagePlayer(
                    item = item,
                    isCurrentPage = pagerState.currentPage == page,
                )
            } else {
                ZoomablePhotoPage(
                    item = item,
                    isCurrentPage = pagerState.currentPage == page,
                    onZoomChanged = { z ->
                        if (pagerState.currentPage == page) pageZoomed = z > 1.03f
                    },
                    onSingleTap = { chromeVisible = !chromeVisible },
                )
            }
        }

        if (chromeVisible) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .fillMaxWidth()
                    .statusBarsPadding()
                    .padding(horizontal = 12.dp, vertical = 10.dp)
                    .glass(CircleShape, strong = true)
                    .padding(horizontal = 6.dp, vertical = 2.dp),
            ) {
                IconButton(onClick = onCloseGallery) {
                    Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = "К камере", tint = Pc.Text)
                }
                Column(modifier = Modifier.weight(1f).padding(horizontal = 6.dp)) {
                    Text(
                        text = currentItem.name,
                        color = Pc.Text,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        text = "${pagerState.currentPage + 1} / ${items.size}",
                        color = Pc.TextDim,
                        fontSize = 11.sp,
                    )
                }
                IconButton(onClick = onBackToGrid) {
                    Icon(Icons.Outlined.GridView, contentDescription = "Все фото", tint = Pc.Accent)
                }
            }

            Row(
                horizontalArrangement = Arrangement.SpaceEvenly,
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .navigationBarsPadding()
                    .padding(horizontal = 16.dp, vertical = 14.dp)
                    .glass(RoundedCornerShape(24.dp), strong = true)
                    .padding(vertical = 8.dp, horizontal = 8.dp),
            ) {
                ViewerActionButton(
                    icon = Icons.Outlined.Info,
                    label = "EXIF",
                    onClick = { onShowDetails(currentItem) },
                )
                if (currentItem.uri != Uri.EMPTY) {
                    ViewerActionButton(
                        icon = Icons.Outlined.Share,
                        label = "Поделиться",
                        onClick = {
                            val send = Intent(Intent.ACTION_SEND).apply {
                                type = currentItem.mime
                                putExtra(Intent.EXTRA_STREAM, currentItem.uri)
                                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                            }
                            context.startActivity(Intent.createChooser(send, "Поделиться"))
                        },
                    )
                }
                if (!currentItem.inPocoAlbum && currentItem.uri != Uri.EMPTY) {
                    ViewerActionButton(
                        icon = Icons.AutoMirrored.Outlined.DriveFileMove,
                        label = "В альбом",
                        tint = Pc.Accent,
                        onClick = { onCopyToAlbum(currentItem) },
                    )
                }
                ViewerActionButton(
                    icon = Icons.Outlined.Delete,
                    label = "Удалить",
                    tint = Pc.Record,
                    onClick = { onDelete(currentItem) },
                )
            }
        }
    }
}

@Composable
private fun ViewerActionButton(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    tint: Color = Pc.Text,
    onClick: () -> Unit,
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier
            .clip(RoundedCornerShape(14.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 4.dp),
    ) {
        Icon(icon, contentDescription = label, tint = tint, modifier = Modifier.size(21.dp))
        Spacer(Modifier.height(2.dp))
        Text(text = label, color = tint, fontSize = 11.sp, fontWeight = FontWeight.Medium)
    }
}

@Composable
private fun ZoomablePhotoPage(
    item: MediaItem,
    isCurrentPage: Boolean,
    onZoomChanged: (Float) -> Unit,
    onSingleTap: () -> Unit,
) {
    var scale by remember(item.name) { mutableFloatStateOf(1f) }
    var offset by remember(item.name) { mutableStateOf(Offset.Zero) }

    LaunchedEffect(isCurrentPage) {
        if (!isCurrentPage) {
            scale = 1f
            offset = Offset.Zero
            onZoomChanged(1f)
        }
    }

    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .fillMaxSize()
            .pointerInput(item.name) {
                detectTapGestures(
                    onTap = { onSingleTap() },
                    onDoubleTap = {
                        if (scale > 1.05f) {
                            scale = 1f
                            offset = Offset.Zero
                        } else {
                            scale = 2.6f
                        }
                        onZoomChanged(scale)
                    },
                )
            }
            .let { base ->
                if (scale > 1.02f) {
                    base.pointerInput(item.name) {
                        detectTransformGestures { _, pan, zoom, _ ->
                            val newScale = (scale * zoom).coerceIn(1f, 6f)
                            scale = newScale
                            onZoomChanged(newScale)
                            if (newScale <= 1.02f) {
                                offset = Offset.Zero
                            } else {
                                val maxX = (size.width * (newScale - 1f)) / 2f
                                val maxY = (size.height * (newScale - 1f)) / 2f
                                offset = Offset(
                                    x = (offset.x + pan.x).coerceIn(-maxX, maxX),
                                    y = (offset.y + pan.y).coerceIn(-maxY, maxY),
                                )
                            }
                        }
                    }
                } else {
                    base
                }
            },
    ) {
        val zoomLayer = Modifier
            .fillMaxSize()
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
                translationX = offset.x
                translationY = offset.y
            }

        // Базовый слой из RAM (показывается за 0 мс без ожидания декодирования файла с диска)
        val bmp = item.previewBitmap
        if (bmp != null) {
            val imgBmp = remember(bmp) { bmp.asImageBitmap() }
            Image(
                bitmap = imgBmp,
                contentDescription = item.name,
                contentScale = ContentScale.Fit,
                modifier = zoomLayer,
            )
        }

        // Полноразмерный кадр из MediaStore поверх (когда готов)
        if (item.uri != Uri.EMPTY) {
            AsyncImage(
                model = item.uri,
                contentDescription = item.name,
                contentScale = ContentScale.Fit,
                modifier = zoomLayer,
            )
        }
    }
}

@Composable
private fun VideoPagePlayer(
    item: MediaItem,
    isCurrentPage: Boolean,
) {
    val context = LocalContext.current
    val player = remember(item.uri) {
        ExoPlayer.Builder(context).build().apply {
            setMediaItem(ExoMediaItem.fromUri(item.uri))
            prepare()
        }
    }

    LaunchedEffect(isCurrentPage) {
        if (!isCurrentPage && player.isPlaying) {
            player.pause()
        }
    }

    DisposableEffect(player) {
        onDispose {
            player.release()
        }
    }

    AndroidView(
        factory = { ctx ->
            PlayerView(ctx).apply {
                this.player = player
                useController = true
            }
        },
        update = { view ->
            if (view.player !== player) view.player = player
        },
        modifier = Modifier.fillMaxSize(),
    )
}
