package com.poco.camera

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import android.provider.Settings
import android.view.KeyEvent
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.poco.camera.camera.CamMode
import com.poco.camera.camera.SensorTracker
import com.poco.camera.ui.CameraScreen
import com.poco.camera.ui.VolumeKey
import com.poco.camera.ui.theme.NeoCamLogo
import com.poco.camera.ui.theme.Pc
import com.poco.camera.ui.theme.PocoTheme
import com.poco.camera.ui.theme.glass
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow

class MainActivity : ComponentActivity() {
    private val app: PocoApp by lazy { application as PocoApp }
    private lateinit var sensorTracker: SensorTracker

    private val _volumeKeys = MutableSharedFlow<VolumeKey>(extraBufferCapacity = 8)
    private var hasCameraPermission by mutableStateOf(false)

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
        )
        super.onCreate(savedInstanceState)

        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        setShowWhenLocked(true)
        setTurnScreenOn(true)

        sensorTracker = SensorTracker(applicationContext) { rot -> app.engine.setTargetRotation(rot) }
        hasCameraPermission = checkCameraGranted()

        setContent {
            PocoTheme {
                val permLauncher = rememberLauncherForActivityResult(
                    contract = ActivityResultContracts.RequestMultiplePermissions(),
                ) {
                    hasCameraPermission = checkCameraGranted()
                    if (hasCameraPermission) {
                        app.gallery.refresh()
                    }
                }

                LaunchedEffect(Unit) {
                    if (!hasCameraPermission) {
                        permLauncher.launch(requiredPermissions())
                    }
                }

                LaunchedEffect(hasCameraPermission) {
                    if (hasCameraPermission) {
                        app.engine.init()
                        applyLaunchIntent(intent)
                    }
                }

                if (hasCameraPermission) {
                    CameraScreen(
                        engine = app.engine,
                        settingsStore = app.settingsStore,
                        gallery = app.gallery,
                        feedback = app.feedback,
                        sensorTracker = sensorTracker,
                        volumeKeys = _volumeKeys.asSharedFlow(),
                    )
                } else {
                    PermissionOnboarding(
                        onGrantClick = { permLauncher.launch(requiredPermissions()) },
                        onOpenSettingsClick = {
                            val i = Intent(
                                Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                                Uri.fromParts("package", packageName, null),
                            )
                            startActivity(i)
                        },
                    )
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        hasCameraPermission = checkCameraGranted()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        applyLaunchIntent(intent)
    }

    private fun applyLaunchIntent(intent: Intent?) {
        when (intent?.action) {
            MediaStore.INTENT_ACTION_VIDEO_CAMERA -> app.engine.setMode(CamMode.VIDEO)
            MediaStore.INTENT_ACTION_STILL_IMAGE_CAMERA -> app.engine.setMode(CamMode.PHOTO)
        }
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        if (hasCameraPermission) {
            when (keyCode) {
                KeyEvent.KEYCODE_VOLUME_UP -> {
                    if (event?.repeatCount == 0) _volumeKeys.tryEmit(VolumeKey.UP)
                    return true
                }
                KeyEvent.KEYCODE_VOLUME_DOWN -> {
                    if (event?.repeatCount == 0) _volumeKeys.tryEmit(VolumeKey.DOWN)
                    return true
                }
            }
        }
        return super.onKeyDown(keyCode, event)
    }

    override fun onKeyUp(keyCode: Int, event: KeyEvent?): Boolean {
        if (hasCameraPermission && (keyCode == KeyEvent.KEYCODE_VOLUME_UP || keyCode == KeyEvent.KEYCODE_VOLUME_DOWN)) {
            return true
        }
        return super.onKeyUp(keyCode, event)
    }

    override fun onDestroy() {
        super.onDestroy()
        sensorTracker.stop()
    }

    private fun checkCameraGranted(): Boolean =
        ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED

    private fun requiredPermissions(): Array<String> = buildList {
        add(Manifest.permission.CAMERA)
        add(Manifest.permission.RECORD_AUDIO)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            add(Manifest.permission.READ_MEDIA_IMAGES)
            add(Manifest.permission.READ_MEDIA_VIDEO)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                add(Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED)
            }
        } else {
            add(Manifest.permission.READ_EXTERNAL_STORAGE)
        }
    }.toTypedArray()
}

@Composable
private fun PermissionOnboarding(
    onGrantClick: () -> Unit,
    onOpenSettingsClick: () -> Unit,
) {
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .fillMaxSize()
            .background(Pc.Bg)
            .padding(28.dp),
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
            modifier = Modifier
                .glass(RoundedCornerShape(28.dp), strong = true)
                .padding(horizontal = 24.dp, vertical = 32.dp),
        ) {
            NeoCamLogo(size = 72.dp)
            Spacer(Modifier.height(18.dp))
            Text(
                text = "NeoCam",
                style = MaterialTheme.typography.titleLarge,
                color = Pc.Text,
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(8.dp))
            Text(
                text = "Для съёмки фото и видео необходим доступ к камере и микрофону.",
                style = MaterialTheme.typography.bodyMedium,
                color = Pc.TextDim,
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(24.dp))
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .clip(CircleShape)
                    .background(Pc.Accent)
                    .clickable(onClick = onGrantClick)
                    .padding(horizontal = 28.dp, vertical = 13.dp),
            ) {
                Text(
                    text = "Разрешить доступ",
                    color = Pc.OnAccent,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Bold,
                )
            }
            Spacer(Modifier.height(12.dp))
            Text(
                text = "Открыть системные настройки",
                color = Pc.TextDim,
                fontSize = 12.sp,
                modifier = Modifier
                    .clip(CircleShape)
                    .clickable(onClick = onOpenSettingsClick)
                    .padding(horizontal = 12.dp, vertical = 6.dp),
            )
        }
    }
}
