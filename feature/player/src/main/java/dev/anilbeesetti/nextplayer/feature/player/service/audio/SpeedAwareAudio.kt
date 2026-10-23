package dev.anilbeesetti.nextplayer.feature.player.service.audio

import android.content.Context
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.ChannelMixingAudioProcessor
import androidx.media3.common.audio.ChannelMixingMatrix
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.DefaultAudioSink
import androidx.media3.exoplayer.audio.ForwardingAudioSink
import io.github.anilbeesetti.nextlib.media3ext.ffdecoder.NextRenderersFactory
import java.nio.ByteBuffer

/**
 * 播放速度对音频输出方式的要求，1 倍速与其它速度必须走不同的管线：
 *
 * - 1 倍速：保持原样，压缩音轨（AC3/EAC3/DTS 等）继续直通透传给功放；
 * - 非 1 倍速：关闭直通，让渲染器解码成 PCM，并下混成 2 声道。
 *
 * 原因：直通时压缩码流是原样丢给 AudioTrack 的，`SonicAudioProcessor` 只能处理 PCM，
 * 所以透传状态下改播放速度对声音完全无效（视频变速、声音不变）。只有解码成 PCM，
 * 变速才真正作用于音频。多声道 PCM 在电视/ARC 上不一定被接受，故一路下混到 2 声道。
 */
@UnstableApi
class AudioSpeedPcmMode {
    /** 由 PlayerService 在播放速度变化时更新。 */
    @Volatile
    var forcePcm: Boolean = false
}

/**
 * 直通闸门。
 *
 * media3 判断"要不要直通"的入口是 `MediaCodecAudioRenderer.shouldUseBypass`，
 * 它最终落到 `audioSink.supportsFormat(format)`（已用 1.11.1 字节码确认）。
 * 所以拦 [supportsFormat] 才能真的拦住直通；[getFormatSupport] 只决定这条轨要不要保留。
 *
 * 两者语义不同，缺一不可：
 * - [getFormatSupport] 在拒绝时返回 `SINK_FORMAT_SUPPORTED_WITH_TRANSCODING`（"解码后我能接"），
 *   而不是 `SINK_FORMAT_UNSUPPORTED`（"没有任何渲染器能处理"）——后者会让 media3
 *   直接丢弃音轨变成静音。
 * - [supportsFormat] 在拒绝时返回 false，阻断 bypass 分支。
 *
 * 非压缩（PCM）格式永远放行，避免把已经解码的音频再判一次。
 */
@UnstableApi
class PassthroughGateAudioSink(
    private val mode: AudioSpeedPcmMode,
    delegate: AudioSink,
) : ForwardingAudioSink(delegate) {

    override fun getFormatSupport(format: Format): Int = if (refused(format)) {
        AudioSink.SINK_FORMAT_SUPPORTED_WITH_TRANSCODING
    } else {
        super.getFormatSupport(format)
    }

    override fun supportsFormat(format: Format): Boolean =
        if (refused(format)) false else super.supportsFormat(format)

    private fun refused(format: Format): Boolean = mode.forcePcm && isCompressed(format)

    private fun isCompressed(format: Format): Boolean {
        val mimeType = format.sampleMimeType ?: return false
        return mimeType != MimeTypes.AUDIO_RAW
    }
}

/**
 * 把多声道 PCM 下混成 2 声道。
 *
 * 只在需要时生效：`configure` 阶段读一次 [AudioSpeedPcmMode.forcePcm]，不满足就返回
 * `NOT_SET` 表示本处理器不参与（PCM 直通），因此 1 倍速下的 PCM 多声道源行为不变。
 * 速度跨过 1 倍时会触发音轨重选（见 PlayerService），sink 会重新 configure，
 * 闸门因此被重新求值。
 *
 * 用 media3 自带的 `ChannelMixingAudioProcessor` + `ChannelMixingMatrix.createForConstantGain`
 * 做下混（含中置/环绕折叠），而不是简单丢掉多余声道——只取前两声道会丢掉对白。
 */
@UnstableApi
internal class DownmixToStereoAudioProcessor(
    private val mode: AudioSpeedPcmMode,
) : AudioProcessor {

    private var delegate: ChannelMixingAudioProcessor? = null
    private var active = false

    override fun configure(inputAudioFormat: AudioProcessor.AudioFormat): AudioProcessor.AudioFormat {
        delegate = null
        active = false
        if (!mode.forcePcm) return AudioProcessor.AudioFormat.NOT_SET
        val channelCount = inputAudioFormat.channelCount
        if (channelCount <= STEREO_CHANNEL_COUNT) return AudioProcessor.AudioFormat.NOT_SET

        val processor = ChannelMixingAudioProcessor()
        val matrix = runCatching {
            ChannelMixingMatrix.createForConstantGain(channelCount, STEREO_CHANNEL_COUNT)
        }.getOrNull() ?: return AudioProcessor.AudioFormat.NOT_SET
        processor.putChannelMixingMatrix(matrix)

        // 下混矩阵可能是恒等/不适用，交给它自己判断；任何异常都退化为不做下混，保证还有声音。
        val outputFormat = runCatching { processor.configure(inputAudioFormat) }.getOrNull()
            ?: return AudioProcessor.AudioFormat.NOT_SET
        if (!processor.isActive) return AudioProcessor.AudioFormat.NOT_SET

        delegate = processor
        active = true
        return outputFormat
    }

    override fun isActive(): Boolean = active

    override fun queueInput(inputBuffer: ByteBuffer) {
        delegate?.queueInput(inputBuffer)
    }

    override fun queueEndOfStream() {
        delegate?.queueEndOfStream()
    }

    override fun getOutput(): ByteBuffer = delegate?.output ?: AudioProcessor.EMPTY_BUFFER

    override fun isEnded(): Boolean = delegate?.isEnded ?: false

    override fun flush() {
        delegate?.flush()
    }

    override fun reset() {
        delegate?.reset()
        delegate = null
        active = false
    }

    private companion object {
        const val STEREO_CHANNEL_COUNT = 2
    }
}

/**
 * 在 [NextRenderersFactory] 之上只改音频 sink：
 *
 * `DefaultRenderersFactory.buildAudioSink` 的实现就是
 * `DefaultAudioSink.Builder(context).setEnableFloatOutput(...).setEnableAudioOutputPlaybackParameters(...).build()`，
 * 这里原样保留这两项，只额外挂上处理器链。处理器链用 `DefaultAudioProcessorChain`，
 * 它会把 `SonicAudioProcessor` 追加在末尾，所以变速能力不受影响。
 *
 * 必须用真实的 `DefaultAudioSink`（而不是在 `ForwardingAudioSink` 里改 format）：
 * 声道数的改变只能靠音频处理器完成，直接改 format 的声道数会让 AudioTrack 的声道布局
 * 与实际 PCM 数据不匹配。
 */
@UnstableApi
class SpeedAwareRenderersFactory(context: Context) : NextRenderersFactory(context) {

    val speedPcmMode = AudioSpeedPcmMode()

    var gateSink: PassthroughGateAudioSink? = null
        private set

    override fun buildAudioSink(
        context: Context,
        enableFloatOutput: Boolean,
        enableAudioOutputPlaybackParameters: Boolean,
    ): AudioSink? {
        val sink = DefaultAudioSink.Builder(context)
            .setEnableFloatOutput(enableFloatOutput)
            .setEnableAudioOutputPlaybackParameters(enableAudioOutputPlaybackParameters)
            .setAudioProcessorChain(
                DefaultAudioSink.DefaultAudioProcessorChain(
                    DownmixToStereoAudioProcessor(speedPcmMode),
                ),
            )
            .build()
        return PassthroughGateAudioSink(speedPcmMode, sink).also { gateSink = it }
    }
}
