package com.poco.camera.data

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class Aspect(val label: String) {
    R4_3("4:3"),
    R16_9("16:9"),
    R1_1("1:1"),
    FULL("Полный"),
}

enum class FlashSetting { OFF, AUTO, ON }
enum class VolumeAction(val label: String) { SHUTTER("Затвор"), ZOOM("Зум") }
enum class VideoRes(val label: String) { UHD("4K"), FHD("1080p"), HD("720p") }

data class CamSettings(
    val aspect: Aspect = Aspect.R4_3,
    val flash: FlashSetting = FlashSetting.OFF,
    val timer: Int = 0,
    val astroExposureSec: Int = 2,
    val grid: Boolean = true,
    val level: Boolean = true,
    val focusPeaking: Boolean = true,
    val highRes: Boolean = false,
    val mirrorFront: Boolean = true,
    val shutterSound: Boolean = true,
    val haptics: Boolean = true,
    val qr: Boolean = true,
    val autoTrack: Boolean = true,
    val autoShoot: Boolean = false,
    val aimDelay: Int = 2,
    val volume: VolumeAction = VolumeAction.SHUTTER,
    val videoRes: VideoRes = VideoRes.FHD,
    val fps60: Boolean = false,
    val stabilization: Boolean = true,
)

class SettingsStore(context: Context) {
    private val prefs = context.getSharedPreferences("neocam_prefs", Context.MODE_PRIVATE)
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
            aspect = enumOr(prefs.getString("aspect", null), d.aspect),
            flash = enumOr(prefs.getString("flash", null), d.flash),
            timer = prefs.getInt("timer", d.timer),
            astroExposureSec = prefs.getInt("astroExposureSec", d.astroExposureSec).coerceIn(1, 30),
            grid = prefs.getBoolean("grid", d.grid),
            level = prefs.getBoolean("level", d.level),
            focusPeaking = prefs.getBoolean("focusPeaking", d.focusPeaking),
            highRes = prefs.getBoolean("highRes", d.highRes),
            mirrorFront = prefs.getBoolean("mirrorFront", d.mirrorFront),
            shutterSound = prefs.getBoolean("shutterSound", d.shutterSound),
            haptics = prefs.getBoolean("haptics", d.haptics),
            qr = prefs.getBoolean("qr", d.qr),
            autoTrack = prefs.getBoolean("autoTrack", d.autoTrack),
            autoShoot = prefs.getBoolean("autoShoot", d.autoShoot),
            aimDelay = prefs.getInt("aimDelay", d.aimDelay),
            volume = enumOr(prefs.getString("volume", null), d.volume),
            videoRes = enumOr(prefs.getString("videoRes", null), d.videoRes),
            fps60 = prefs.getBoolean("fps60", d.fps60),
            stabilization = prefs.getBoolean("stabilization", d.stabilization),
        )
    }

    private fun save(s: CamSettings) {
        prefs.edit()
            .putString("aspect", s.aspect.name)
            .putString("flash", s.flash.name)
            .putInt("timer", s.timer)
            .putInt("astroExposureSec", s.astroExposureSec)
            .putBoolean("grid", s.grid)
            .putBoolean("level", s.level)
            .putBoolean("focusPeaking", s.focusPeaking)
            .putBoolean("highRes", s.highRes)
            .putBoolean("mirrorFront", s.mirrorFront)
            .putBoolean("shutterSound", s.shutterSound)
            .putBoolean("haptics", s.haptics)
            .putBoolean("qr", s.qr)
            .putBoolean("autoTrack", s.autoTrack)
            .putBoolean("autoShoot", s.autoShoot)
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
