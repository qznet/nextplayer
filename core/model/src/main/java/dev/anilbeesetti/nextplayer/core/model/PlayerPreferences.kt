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
    @Deprecated(
        message =
            "Use individual enableVolumeSwipeGesture and enableBrightnessSwipeGesture instead",
    )
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
     * 直通状态下是否**禁止**音频变速。
     *
     * - false（默认）：倍速时音频仍然是压缩码流直通（功放继续显示 5.1 / Atmos），
     *   并且音频跟着画面一起变速 —— 音调会变（码流无法保音调地拉伸），
     *   但音画是同步的。
     * - true：直通时音频保持 1 倍速，**倍数失效**，画面快声音慢（音画不同步）。
     *   这是上游原本的默认行为。
     *
     * 换句话说：调速时不再把音轨降级成 2 声道 PCM（那条路在实测里功放显示始终是
     * Atmos、听感也仍是环绕声，等于没换），改为一路直通到底。
     *
     * 只影响压缩音轨（AC3/EAC3/DTS…）；PCM 音源本来就绕开这套逻辑。
     * 建 player 时读取，改设置后要重开播放器。
     */
    val disableSpeedWhenPassthrough: Boolean = false,

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
