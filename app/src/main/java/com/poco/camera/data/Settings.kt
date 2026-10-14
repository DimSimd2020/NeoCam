package com.poco.camera.data

import android.content.Context
import android.os.Build
import com.poco.camera.BuildConfig
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class DeviceProfile(
    val title: String,
    val shortBadge: String,
    val maxSuperZoom: Float,
    val supportsFlexCam: Boolean,
    val supportsSpaceZoomMap: Boolean,
) {
    PIXEL_8(
        title = "Google Pixel 8",
        shortBadge = "PIXEL 8",
        maxSuperZoom = 30f,
        supportsFlexCam = false,
        supportsSpaceZoomMap = true,
    ),
    S25_ULTRA(
        title = "Samsung Galaxy S25 Ultra",
        shortBadge = "S25 ULTRA",
        maxSuperZoom = 100f,
        supportsFlexCam = false,
        supportsSpaceZoomMap = true,
    ),
    Z_FLIP_7(
        title = "Samsung Galaxy Z Flip 7",
        shortBadge = "Z FLIP 7",
        maxSuperZoom = 30f,
        supportsFlexCam = true,
        supportsSpaceZoomMap = false,
    ),
    POCO_X8_PRO(
        title = "Poco X8 Pro",
        shortBadge = "POCO X8",
        maxSuperZoom = 30f,
        supportsFlexCam = false,
        supportsSpaceZoomMap = false,
    );

    companion object {
        fun detectDefault(): DeviceProfile {
            val m = "${Build.MANUFACTURER} ${Build.MODEL} ${Build.DEVICE}".uppercase()
            return when {
                "PIXEL" in m || "SHIBA" in m || "HUSKY" in m || "GOOGLE" in m -> PIXEL_8
                "FLIP" in m || "SM-F7" in m -> Z_FLIP_7
                "POCO" in m || "X8" in m -> POCO_X8_PRO
                "S25" in m || "ULTRA" in m || "SM-S938" in m || "SM-S928" in m -> S25_ULTRA
                else -> runCatching { valueOf(BuildConfig.DEFAULT_PROFILE) }.getOrDefault(PIXEL_8)
            }
        }
    }
}

enum class Aspect(val label: String) {
    R4_3("4:3"),
    R16_9("16:9"),
    R1_1("1:1"),
    FULL("FULL"),
}

enum class FlashSetting { OFF, AUTO, ON }
enum class VolumeAction(val label: String) { SHUTTER("Затвор"), ZOOM("Зум") }
enum class VideoRes(val label: String) { UHD("4K"), FHD("1080p"), HD("720p") }

data class CamSettings(
    val profile: DeviceProfile = DeviceProfile.detectDefault(),
    val aspect: Aspect = Aspect.R4_3,
    val flash: FlashSetting = FlashSetting.OFF,
    val timer: Int = 0,
    val grid: Boolean = true,
    val level: Boolean = true,
    val highRes: Boolean = false,
    val zeroLag: Boolean = true,
    val instantReview: Boolean = true,
    val flexModeManual: Boolean = false,
    val mirrorFront: Boolean = true,
    val shutterSound: Boolean = true,
    val haptics: Boolean = true,
    val qr: Boolean = true,
    val aim: Boolean = false,
    val aimDelay: Int = 2,
    val volume: VolumeAction = VolumeAction.SHUTTER,
    val videoRes: VideoRes = VideoRes.FHD,
    val fps60: Boolean = false,
    val stabilization: Boolean = true,
)

class SettingsStore(context: Context) {
    private val prefs = context.getSharedPreferences("poco_cam", Context.MODE_PRIVATE)
    private val _state = MutableStateFlow(load())
    val state: StateFlow<CamSettings> = _state.asStateFlow()

    fun update(block: (CamSettings) -> CamSettings) {
        val next = block(_state.value)
        _state.value = next
        save(next)
    }

    private fun load(): CamSettings {
        val d = CamSettings()
        return CamSettings(
            profile = enumOr(prefs.getString("profile", null), d.profile),
            aspect = enumOr(prefs.getString("aspect", null), d.aspect),
            flash = enumOr(prefs.getString("flash", null), d.flash),
            timer = prefs.getInt("timer", d.timer),
            grid = prefs.getBoolean("grid", d.grid),
            level = prefs.getBoolean("level", d.level),
            highRes = prefs.getBoolean("highRes", d.highRes),
            zeroLag = prefs.getBoolean("zeroLag", d.zeroLag),
            instantReview = prefs.getBoolean("instantReview", d.instantReview),
            flexModeManual = prefs.getBoolean("flexModeManual", d.flexModeManual),
            mirrorFront = prefs.getBoolean("mirrorFront", d.mirrorFront),
            shutterSound = prefs.getBoolean("shutterSound", d.shutterSound),
            haptics = prefs.getBoolean("haptics", d.haptics),
            qr = prefs.getBoolean("qr", d.qr),
            aim = prefs.getBoolean("aim", d.aim),
            aimDelay = prefs.getInt("aimDelay", d.aimDelay),
            volume = enumOr(prefs.getString("volume", null), d.volume),
            videoRes = enumOr(prefs.getString("videoRes", null), d.videoRes),
            fps60 = prefs.getBoolean("fps60", d.fps60),
            stabilization = prefs.getBoolean("stabilization", d.stabilization),
        )
    }

    private fun save(s: CamSettings) {
        prefs.edit()
            .putString("profile", s.profile.name)
            .putString("aspect", s.aspect.name)
            .putString("flash", s.flash.name)
            .putInt("timer", s.timer)
            .putBoolean("grid", s.grid)
            .putBoolean("level", s.level)
            .putBoolean("highRes", s.highRes)
            .putBoolean("zeroLag", s.zeroLag)
            .putBoolean("instantReview", s.instantReview)
            .putBoolean("flexModeManual", s.flexModeManual)
            .putBoolean("mirrorFront", s.mirrorFront)
            .putBoolean("shutterSound", s.shutterSound)
            .putBoolean("haptics", s.haptics)
            .putBoolean("qr", s.qr)
            .putBoolean("aim", s.aim)
            .putInt("aimDelay", s.aimDelay)
            .putString("volume", s.volume.name)
            .putString("videoRes", s.videoRes.name)
            .putBoolean("fps60", s.fps60)
            .putBoolean("stabilization", s.stabilization)
            .apply()
    }
}

private inline fun <reified T : Enum<T>> enumOr(name: String?, default: T): T =
    name?.let { runCatching { enumValueOf<T>(it) }.getOrNull() } ?: default
