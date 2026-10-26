package dev.anilbeesetti.nextplayer.feature.player.service

import dev.anilbeesetti.nextplayer.feature.player.model.DecoderRecoveryState
import dev.anilbeesetti.nextplayer.feature.player.model.DecoderRecoveryStatus
import dev.anilbeesetti.nextplayer.feature.player.model.DecoderTrackType
import io.github.anilbeesetti.nextlib.media3ext.ffdecoder.DecoderMode

/** Keeps NextPlayer's decoder fallback policy separate from nextlib's decoder switching. */
internal class DecoderRecoveryManager {

    private val videoRecovery = TrackRecovery()
    private val audioRecovery = TrackRecovery()

    // Terminal errors and confirmation take precedence over background recovery.
    val state: DecoderRecoveryState
        get() = listOf(
            videoRecovery.state,
            audioRecovery.state,
        ).maxBy { it.status }

    fun onUserSelection(trackType: DecoderTrackType, mode: DecoderMode) {
        recoveryFor(trackType = trackType).apply {
            reset()
            requiresConfirmation = mode != DecoderMode.AUTO
        }
    }

    fun onDecoderFailure(trackType: DecoderTrackType, mode: DecoderMode): DecoderRetry? {
        val recovery = recoveryFor(trackType = trackType)
        if (recovery.failedMode == mode) {
            return null
        }

        recovery.failedMode = mode
        if (recovery.fallbackModes == null) {
            recovery.fallbackModes = ArrayDeque(mode.fallbackModes())
        }
        if (recovery.fallbackModes.isNullOrEmpty()) {
            recovery.state = DecoderRecoveryState(
                status = DecoderRecoveryStatus.FAILED,
                trackType = trackType,
            )
            return null
        }

        if (recovery.requiresConfirmation) {
            recovery.state = DecoderRecoveryState(
                status = DecoderRecoveryStatus.AWAITING_CONFIRMATION,
                trackType = trackType,
                unsupportedMode = mode,
            )
            return null
        }

        val fallbackMode = checkNotNull(recovery.fallbackModes).removeFirst()
        recovery.state = DecoderRecoveryState(
            status = DecoderRecoveryStatus.RECOVERING,
            trackType = trackType,
        )
        return DecoderRetry(
            trackType = trackType,
            mode = fallbackMode,
        )
    }

    fun confirmFallback(): DecoderRetry? {
        if (state.status != DecoderRecoveryStatus.AWAITING_CONFIRMATION) return null
        val trackType = state.trackType ?: return null
        val recovery = recoveryFor(trackType = trackType)
        if (recovery.fallbackModes.isNullOrEmpty()) return null
        val fallbackMode = checkNotNull(recovery.fallbackModes).removeFirst()

        recovery.requiresConfirmation = false
        recovery.state = DecoderRecoveryState(
            status = DecoderRecoveryStatus.RECOVERING,
            trackType = trackType,
        )
        return DecoderRetry(
            trackType = trackType,
            mode = fallbackMode,
        )
    }

    fun onDecoderInitialized(trackType: DecoderTrackType) {
        val recovery = recoveryFor(trackType = trackType)
        if (recovery.state.status == DecoderRecoveryStatus.RECOVERING) {
            recovery.state = DecoderRecoveryState()
        }
    }

    fun onNonDecoderError() {
        videoRecovery.state = DecoderRecoveryState()
        audioRecovery.state = DecoderRecoveryState()
    }

    private fun recoveryFor(trackType: DecoderTrackType): TrackRecovery = when (trackType) {
        DecoderTrackType.VIDEO -> videoRecovery
        DecoderTrackType.AUDIO -> audioRecovery
    }
}

internal data class DecoderRetry(val trackType: DecoderTrackType, val mode: DecoderMode)

private class TrackRecovery {
    var state = DecoderRecoveryState()
    var requiresConfirmation = false
    var fallbackModes: ArrayDeque<DecoderMode>? = null
    var failedMode: DecoderMode? = null

    fun reset() {
        state = DecoderRecoveryState()
        requiresConfirmation = false
        fallbackModes = null
        failedMode = null
    }
}

/**
 * 解码失败后的降级顺序：**一次只降一级**。
 *
 * 历史教训（TCL m7642 / Android 9 实测）：原来 AUTO 会一口气连试
 * HARDWARE → SOFTWARE → FFMPEG，同一部 4K 片源在 3 秒内被重建 3 次解码器。
 * 本机的 4K 硬解组件（`OMX.MS.DOLBY_VISION.Decoder` / `OMX.MS.HEVC.Decoder`）
 * 连续 configure/start 几次之后会彻底起不来 —— 之后连第一次播放都黑屏，
 * 只能重启电视恢复。
 *
 * 所以：AUTO 失败后直接给 SOFTWARE（AUTO 本来就包含硬解，再降 HARDWARE 等于
 * 对同一个硬解器再打一枪），每个片源最多两次尝试。宁可让用户手动换解码器，
 * 也不能把系统解码器打废。
 */
private fun DecoderMode.fallbackModes(): List<DecoderMode> {
    return when (this) {
        DecoderMode.AUTO -> listOf(DecoderMode.SOFTWARE)
        DecoderMode.HARDWARE -> listOf(DecoderMode.SOFTWARE)
        DecoderMode.SOFTWARE -> listOf(DecoderMode.FFMPEG)
        DecoderMode.FFMPEG -> emptyList()
    }
}
