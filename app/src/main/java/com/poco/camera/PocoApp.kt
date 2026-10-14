package com.poco.camera

import android.app.Application
import coil.ImageLoader
import coil.ImageLoaderFactory
import coil.decode.VideoFrameDecoder
import com.poco.camera.camera.CameraEngine
import com.poco.camera.camera.Feedback
import com.poco.camera.data.GalleryRepository
import com.poco.camera.data.SettingsStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

class PocoApp : Application(), ImageLoaderFactory {
    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    val settingsStore by lazy { SettingsStore(this) }
    val engine by lazy { CameraEngine(this) }
    val gallery by lazy { GalleryRepository(this, appScope) }
    val feedback by lazy { Feedback(this) { settingsStore.state.value } }

    override fun newImageLoader(): ImageLoader = ImageLoader.Builder(this)
        .components { add(VideoFrameDecoder.Factory()) }
        .crossfade(true)
        .build()
}
