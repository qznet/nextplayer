package dev.anilbeesetti.nextplayer.core.model

import kotlinx.serialization.Serializable

@Serializable
data class PlayerPreferences(
    val resume: Resume = Resume.YES,
    val rememberPlayerBrightness: Boolean = false,
    val playerBrightness: Float = 0.5f,
    val minDurationForFastSeek: Long = 120000L,
    val rememberSelections: Boolean = true,
    val playerScreenOrientation: ScreenOrientation = ScreenOrientation.VIDEO_ORIENTATION,
    val playerVideoZoom: VideoContentScale = VideoContentScale.BEST_FIT,
    val defaultPlaybackSpeed: Float = 1.0f,
    val autoplay: Boolean = true,
    val enableTunneledPlayback: Boolean = false,
    val autoPip: Boolean = true,
    val autoBackgroundPlay: Boolean = false,
    val loopMode: LoopMode = LoopMode.OFF,

    // Controls (Gestures)
    @Deprecated(message = "Use individual enableVolumeSwipeGesture and enableBrightnessSwipeGesture instead")
    val useSwipeControls: Boolean = true,
    val enableVolumeSwipeGesture: Boolean = true,
    val enableBrightnessSwipeGesture: Boolean = true,
    val useSeekControls: Boolean = true,
    val useZoomControls: Boolean = true,
    val enablePanGesture: Boolean = false,
    val doubleTapGesture: DoubleTapGesture = DoubleTapGesture.BOTH,
    val useLongPressControls: Boolean = false,
    val longPressControlsSpeed: Float = 2.0f,
    val seekIncrement: Int = DEFAULT_SEEK_INCREMENT,
    val seekSensitivity: Float = DEFAULT_SEEK_SENSITIVITY,
    val volumeGestureSensitivity: Float = DEFAULT_VOLUME_GESTURE_SENSITIVITY,
    val brightnessGestureSensitivity: Float = DEFAULT_BRIGHTNESS_GESTURE_SENSITIVITY,

    // Player Interface
    val controllerAutoHideTimeout: Int = DEFAULT_CONTROLLER_AUTO_HIDE_TIMEOUT,
    val controlButtonsPosition: ControlButtonsPosition = ControlButtonsPosition.LEFT,
    val hidePlayerButtonsBackground: Boolean = false,
    val useMaterialYouControls: Boolean = false,
    val showRemainingTime: Boolean = false,

    // Audio Preferences
    val preferredAudioLanguage: String = "",
    val pauseOnHeadsetDisconnect: Boolean = true,
    val requireAudioFocus: Boolean = true,
    val showSystemVolumePanel: Boolean = true,
    val enableVolumeBoost: Boolean = false,

    /**
     * 已停用，只为兼容旧设置文件保留（旧版本可能已把它存成 true）。
     *
     * 2026-10-25 实机结论：打开后 AudioTrack 会接受变速请求、功放保持最后一次显示，但
     * **完全静音** —— 这台电视（TCL/雷鸟 m7642，Android 9）的音频 HAL 对
     * `set_playback_rate_parameters(IEC61937)` 只"受理"不实现。该分支已从逻辑里摘除，
     * 无论存的是什么都按 false 处理，界面上也不再暴露。
     */
    val passthroughAtAnySpeed: Boolean = false,

    /**
     * 全程解码成 PCM 并折叠 2 声道，不论速度。
     *
     * - false（默认）：1 倍速保留压缩码流直通（真正的 5.1 / Atmos），非 1 倍速解码成 2 声道。
     *   代价是跨越 1 倍速时音频渲染器要重建一次：本机实测约 0.2 秒空档，加上功放重新锁定，
     *   听感/观感上就是"短暂切了一次立体声"。
     * - true：整场播放都用 PCM，来回改速度完全不重建、不中断，但 1 倍速也只剩 2 声道。
     *
     * 只影响压缩音轨（AC3/EAC3/DTS…）；PCM 音源本来就绕开这套逻辑。
     * 建 player 时读取，改设置后要重开播放器。
     */
    val alwaysDecodeToStereo: Boolean = false,

    // Subtitle Preferences
    val useSystemCaptionStyle: Boolean = false,
    val preferredSubtitleLanguage: String = "",
    val subtitleTextEncoding: String = "",
    val subtitleTextSize: Int = DEFAULT_SUBTITLE_TEXT_SIZE,
    val subtitleBackground: Boolean = false,
    val subtitleFont: Font = Font.DEFAULT,
    val subtitleTextBold: Boolean = true,
    val applyEmbeddedStyles: Boolean = true,
) {

    companion object {
        const val DEFAULT_SEEK_INCREMENT = 10
        const val DEFAULT_SEEK_SENSITIVITY = 0.50f
        const val DEFAULT_VOLUME_GESTURE_SENSITIVITY = 0.50f
        const val DEFAULT_BRIGHTNESS_GESTURE_SENSITIVITY = 0.50f
        const val DEFAULT_SUBTITLE_TEXT_SIZE = 20
        const val DEFAULT_CONTROLLER_AUTO_HIDE_TIMEOUT = 4
    }
}
