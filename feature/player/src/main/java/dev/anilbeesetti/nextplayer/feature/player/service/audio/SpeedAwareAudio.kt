package dev.anilbeesetti.nextplayer.feature.player.service.audio

import android.content.Context
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioManager
import android.os.Build
import android.util.Log
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.BaseAudioProcessor
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.audio.AudioCapabilities
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.DefaultAudioSink
import androidx.media3.exoplayer.audio.ForwardingAudioSink
import io.github.anilbeesetti.nextlib.media3ext.ffdecoder.NextRenderersFactory
import java.nio.ByteBuffer
import java.util.concurrent.CopyOnWriteArraySet

/**
 * 播放速度对音频输出方式的要求：1 倍速与其它速度必须走不同管线。
 *
 * - 1 倍速：压缩音轨（AC3 / E-AC3）直通给功放，由功放解码，5.1 得以保留；
 * - 非 1 倍速：关闭直通，解码成 PCM 并折叠成 2 声道。
 *
 * 为什么必须这样分（TCL m7642 / 雷鸟 R635C 实测，见 `ffalcon-r635c-arc-audio-report.md`）：
 * - 该机 ARC 口（HDMI 1）只注册了 `AUDIO_FORMAT_AC3` 与 `AUDIO_FORMAT_E_AC3` 两个 profile，
 *   **没有任何 PCM profile**；ARC 带宽约 1 Mbps，物理上只够 48k/16bit 两声道 PCM。
 *   6 声道 PCM 交给系统后会被 AudioFlinger 简单折成立体声，且平台折混不留余量，容易削顶。
 * - 压缩码流没有应用层可拉伸的时间基准，变速必须解码成 PCM。
 *   所以「ARC + 5.1 + 变速」三者不可兼得，只能按速度切换管线。
 *
 * 状态由 [AudioSpeedPcmMode.forceDecode] 表示，由 PlayerService 在播放速度变化时更新。
 */
@UnstableApi
class AudioSpeedPcmMode {
    /** 由 PlayerService 在播放速度跨过 1 倍时更新。 */
    @Volatile
    var forceDecode: Boolean = false
}

/**
 * 直通闸门 + 真实直通状态反馈。
 *
 * media3 判断"要不要直通"的入口是 `MediaCodecAudioRenderer.shouldUseBypass`，
 * 它最终落到 `audioSink.supportsFormat(format)`（已用 1.11.1 字节码确认）。
 * 所以拦 [supportsFormat] 才能真的拦住直通；[getFormatSupport] 只决定这条轨要不要保留。
 *
 * 两者语义不同，缺一不可：
 * - [getFormatSupport] 在拒绝时返回 `SINK_FORMAT_SUPPORTED_WITH_TRANSCODING`（"解码后我能接"），
 *   而不是 `SINK_FORMAT_UNSUPPORTED`（"没有任何渲染器能处理"）——后者会让 media3
 *   直接丢弃音轨变成静音（only_player 项目踩过这个坑）。
 * - [supportsFormat] 在拒绝时返回 false，阻断 bypass 分支。
 *
 * [isPassthrough] 是**实际状态**，不是期望值：在 [configure] 时看这条轨的 mime 是不是
 * `audio/raw` 得出。压缩 mime 意味着 media3 已经把压缩码流原样交给 AudioTrack（真直通），
 * 只有这种情况才算。状态机（PlayerService）用它判断切换有没有真的生效。
 */
@UnstableApi
class PassthroughGateAudioSink(
    private val mode: AudioSpeedPcmMode,
    delegate: AudioSink,
) : ForwardingAudioSink(delegate) {

    private val deniedMimes = CopyOnWriteArraySet<String>()

    /** 当前 AudioTrack 是否在以压缩码流直通。 */
    @Volatile
    var isPassthrough: Boolean = false
        private set

    override fun configure(audioSinkConfig: AudioSink.AudioSinkConfig) {
        val mimeType = audioSinkConfig.format.sampleMimeType
        isPassthrough = mimeType != null && mimeType != MimeTypes.AUDIO_RAW
        super.configure(audioSinkConfig)
    }

    /**
     * 更新闸门，返回状态是否真的变了（未变则调用方不必触发音轨重选）。
     */
    fun setForceDecode(forceDecode: Boolean): Boolean {
        if (mode.forceDecode == forceDecode) return false
        mode.forceDecode = forceDecode
        return true
    }

    /**
     * 把某个 mime 拉黑：之后不再尝试直通，退回解码。
     * 用于直通初始化失败的兜底（本机 ARC 不支持 DTS / TrueHD，失败后不应反复重试）。
     */
    fun revoke(mimeType: String?) {
        if (mimeType.isNullOrEmpty()) return
        if (deniedMimes.add(mimeType)) {
            Log.w(TAG, "passthrough revoked for $mimeType, falling back to decode")
        }
    }

    override fun getFormatSupport(format: Format): Int = if (refused(format)) {
        AudioSink.SINK_FORMAT_SUPPORTED_WITH_TRANSCODING
    } else {
        super.getFormatSupport(format)
    }

    override fun supportsFormat(format: Format): Boolean =
        if (refused(format)) false else super.supportsFormat(format)

    private fun refused(format: Format): Boolean {
        val mimeType = format.sampleMimeType
        if (mimeType != null && deniedMimes.contains(mimeType)) return true
        return mode.forceDecode && isCompressed(format)
    }

    private fun isCompressed(format: Format): Boolean {
        val mimeType = format.sampleMimeType ?: return false
        return mimeType != MimeTypes.AUDIO_RAW
    }
}

/**
 * 把多声道 PCM 折叠成 2 声道。
 *
 * 只在必要时生效：
 * - 编码必须是 PCM16 / PCM_FLOAT（压缩码流根本不走处理器链，直通时本处理器不会被调用）；
 * - 声道数 > 2；
 * - 且输出路由确实吃不下多声道（[routePcmChannels] 小于输入声道数）。
 *   路由能吃下就原样放行，不做无谓折叠。
 *
 * 本机 `pcmChannelsOfRoute()` 返回 2（ARC 口没有 PCM profile），所以 5.1 会被折叠。
 *
 * 两个音质细节（照 JustPlus.Player 的做法，报告第 7.1 节也点名要求）：
 * 1. 输出前乘 [HEADROOM]（= -6dB）。5.1 满幅求和即使系数取 0.707 也能到 1.2 倍满刻度，
 *    不留余量会削顶，听感就是破音。
 * 2. 再过一个软限幅器 [bend]：0.75 以下线性，以上按有理函数压到 ≤1.0。
 *    这样偶发过载只是被轻微压缩，而不是硬削。
 */
@UnstableApi
internal class DownmixToStereoAudioProcessor(
    private val routePcmChannels: Int,
) : BaseAudioProcessor() {

    private var gains: Array<FloatArray>? = null
    private var inputChannels: Int = 0
    private var limited: Boolean = false

    override fun onConfigure(inputAudioFormat: AudioProcessor.AudioFormat): AudioProcessor.AudioFormat {
        gains = null
        inputChannels = 0
        limited = false

        val encoding = inputAudioFormat.encoding
        if (encoding != C.ENCODING_PCM_16BIT && encoding != C.ENCODING_PCM_FLOAT) {
            return AudioProcessor.AudioFormat.NOT_SET
        }
        val channels = inputAudioFormat.channelCount
        if (channels <= STEREO_CHANNEL_COUNT) return AudioProcessor.AudioFormat.NOT_SET
        // 路由自己就能承载这么多声道，不必折叠
        if (routePcmChannels in (STEREO_CHANNEL_COUNT + 1)..channels) {
            Log.i(TAG, "downmix skipped: route accepts $routePcmChannels ch (input $channels ch)")
            return AudioProcessor.AudioFormat.NOT_SET
        }

        val matrix = layoutFor(channels)
            ?: if (channels > MAX_KNOWN_LAYOUT) widen(channels) else return AudioProcessor.AudioFormat.NOT_SET
        gains = matrix
        inputChannels = channels
        Log.i(TAG, "downmix: folding $channels channels to stereo (route pcm channels=$routePcmChannels)")
        return AudioProcessor.AudioFormat(inputAudioFormat.sampleRate, STEREO_CHANNEL_COUNT, encoding)
    }

    override fun queueInput(inputBuffer: ByteBuffer) {
        if (!inputBuffer.hasRemaining) return
        val matrix = gains ?: return
        val channels = inputChannels
        if (channels <= 0) return

        val isFloat = inputAudioFormat.encoding == C.ENCODING_PCM_FLOAT
        val scale = if (isFloat) 1.0f else PCM16_SCALE
        val bytesPerSample = if (isFloat) FLOAT_BYTES else PCM16_BYTES
        val frames = inputBuffer.remaining() / (bytesPerSample * channels)
        if (frames <= 0) return

        val out = replaceOutputBuffer(frames * STEREO_CHANNEL_COUNT * bytesPerSample)
        for (frame in 0 until frames) {
            var left = 0.0f
            var right = 0.0f
            for (channel in 0 until channels) {
                val sample = if (isFloat) inputBuffer.float else inputBuffer.short * scale
                val gain = matrix[channel]
                if (gain[0] != 0f) left += gain[0] * sample
                if (gain[1] != 0f) right += gain[1] * sample
            }
            left = bend(left * HEADROOM)
            right = bend(right * HEADROOM)
            if (isFloat) {
                out.putFloat(left)
                out.putFloat(right)
            } else {
                out.putShort(quantize(left))
                out.putShort(quantize(right))
            }
        }
        out.flip()
    }

    /** 软限幅：0.75 以下线性通过，以上平滑压到 ≤1.0。 */
    private fun bend(value: Float): Float {
        val magnitude = kotlin.math.abs(value)
        if (magnitude <= KNEE) return value
        if (!limited) {
            limited = true
            Log.w(TAG, "downmix: the fold reached the limiter, this mix is mastered at full scale")
        }
        val excess = magnitude - KNEE
        val shaped = KNEE + (excess * SLOPE) / (excess + SLOPE)
        return if (value < 0f) -shaped else shaped
    }

    private companion object {
        const val STEREO_CHANNEL_COUNT = 2
        const val MAX_KNOWN_LAYOUT = 8
        const val PCM16_BYTES = 2
        const val FLOAT_BYTES = 4
        const val PCM16_SCALE = 3.0517578E-5f

        /** 中置（人声）折叠增益 -3dB。 */
        const val CENTER_GAIN = 0.7071f

        /** 后环绕增益 -6dB。 */
        const val BACK_GAIN = 0.5f

        /** 侧环绕增益 -3dB。 */
        const val SURROUND_GAIN = 0.7071f

        /** 输出余量 -6dB，给软限幅器留头。 */
        const val HEADROOM = 0.5f

        /** 软限幅拐点。 */
        const val KNEE = 0.75f

        /** 软限幅膝部斜率，膝部以上最多压到 1.0。 */
        const val SLOPE = 0.25f

        /**
         * 各声道布局的 2.0 折叠系数（[声道][0/1] = 左/右）。
         *
         * 顺序沿用 Android 解码器的默认输出布局：FL FR [FC] [LFE] [BL BR] [SL SR]。
         * LFE 一律丢弃（2.0 折叠不保留低频效果声道，否则会糊掉人声）。
         */
        fun layoutFor(channels: Int): Array<FloatArray>? = when (channels) {
            3 -> arrayOf( // FL FR FC
                floatArrayOf(1f, 0f),
                floatArrayOf(0f, 1f),
                floatArrayOf(CENTER_GAIN, CENTER_GAIN),
            )
            4 -> arrayOf( // FL FR BL BR
                floatArrayOf(1f, 0f),
                floatArrayOf(0f, 1f),
                floatArrayOf(BACK_GAIN, 0f),
                floatArrayOf(0f, BACK_GAIN),
            )
            5 -> arrayOf( // FL FR FC BL BR
                floatArrayOf(1f, 0f),
                floatArrayOf(0f, 1f),
                floatArrayOf(CENTER_GAIN, CENTER_GAIN),
                floatArrayOf(BACK_GAIN, 0f),
                floatArrayOf(0f, BACK_GAIN),
            )
            6 -> arrayOf( // FL FR FC LFE BL BR —— 5.1，本机主力片源就是这个
                floatArrayOf(1f, 0f),
                floatArrayOf(0f, 1f),
                floatArrayOf(CENTER_GAIN, CENTER_GAIN),
                floatArrayOf(0f, 0f),
                floatArrayOf(BACK_GAIN, 0f),
                floatArrayOf(0f, BACK_GAIN),
            )
            7 -> arrayOf( // FL FR FC LFE BC SL SR —— 6.1
                floatArrayOf(1f, 0f),
                floatArrayOf(0f, 1f),
                floatArrayOf(CENTER_GAIN, CENTER_GAIN),
                floatArrayOf(0f, 0f),
                floatArrayOf(0.5f, 0.5f),
                floatArrayOf(SURROUND_GAIN, 0f),
                floatArrayOf(0f, SURROUND_GAIN),
            )
            8 -> arrayOf( // FL FR FC LFE BL BR SL SR —— 7.1
                floatArrayOf(1f, 0f),
                floatArrayOf(0f, 1f),
                floatArrayOf(CENTER_GAIN, CENTER_GAIN),
                floatArrayOf(0f, 0f),
                floatArrayOf(BACK_GAIN, 0f),
                floatArrayOf(0f, BACK_GAIN),
                floatArrayOf(SURROUND_GAIN, 0f),
                floatArrayOf(0f, SURROUND_GAIN),
            )
            else -> null
        }

        /** 超过 8 声道：用 7.1 打底，多出来的声道按左右交替半增益补进来。 */
        fun widen(channels: Int): Array<FloatArray> {
            val base = layoutFor(MAX_KNOWN_LAYOUT)!!
            return Array(channels) { index ->
                if (index < base.size) {
                    base[index]
                } else if ((index - base.size) % 2 == 0) {
                    floatArrayOf(BACK_GAIN, 0f)
                } else {
                    floatArrayOf(0f, BACK_GAIN)
                }
            }
        }

        fun quantize(value: Float): Short {
            val scaled = Math.round(value * 32768f)
            return scaled.coerceIn(-32768, 32767).toShort()
        }
    }
}

/**
 * 在 [NextRenderersFactory] 之上只改音频 sink。
 *
 * 与上游 `DefaultRenderersFactory.buildAudioSink` 的差别只有两处：
 * 1. 显式设置 [AudioCapabilities]，否则永远直通不了。media3 的
 *    `AudioCapabilities.getCapabilities()` 依赖 `external_surround_sound_enabled` +
 *    `HDMI_AUDIO_PLUG` 广播来决定是否声明 AC3/E-AC3；电视是 HDMI 链路的 source，
 *    永远收不到那条广播，所以系统恒不声明，`DEFAULT_AUDIO_CAPABILITIES` 只有 PCM 16bit。
 * 2. 挂上 [DownmixToStereoAudioProcessor]（保持 `DefaultAudioProcessorChain`，
 *    它会把 `SonicAudioProcessor` 追加在末尾，变速能力不受影响）。
 *
 * 必须用真实的 `DefaultAudioSink`（而不是在 `ForwardingAudioSink` 里改 format）：
 * 声道数的改变只能靠音频处理器完成，直接改 format 的声道数会让 AudioTrack 的声道布局
 * 与实际 PCM 数据不匹配。
 */
@UnstableApi
class SpeedAwareRenderersFactory(context: Context) : NextRenderersFactory(context) {

    val speedPcmMode = AudioSpeedPcmMode()

    /** 本机输出路由能承载的 PCM 声道数（启动时探测一次，播放中不会变）。 */
    val routePcmChannels: Int = pcmChannelsOfRoute(context)

    var gateSink: PassthroughGateAudioSink? = null
        private set

    override fun buildAudioSink(
        context: Context,
        enableFloatOutput: Boolean,
        enableAudioOutputPlaybackParameters: Boolean,
    ): AudioSink? {
        val sink = DefaultAudioSink.Builder(context)
            .setAudioCapabilities(forcedAudioCapabilities(context))
            .setEnableFloatOutput(enableFloatOutput)
            .setEnableAudioOutputPlaybackParameters(enableAudioOutputPlaybackParameters)
            .setAudioProcessorChain(
                DefaultAudioSink.DefaultAudioProcessorChain(
                    DownmixToStereoAudioProcessor(routePcmChannels),
                ),
            )
            .build()
        return PassthroughGateAudioSink(speedPcmMode, sink).also { gateSink = it }
    }
}

/**
 * 探测输出路由实际能承载几声道 PCM。
 *
 * **不能用 `AudioCapabilities.getMaxChannelCount()`**：在 Android 9 电视上它走
 * `getAudioProfiles({PCM_16BIT}, 10)`，默认值就是 10；电视作为 HDMI source 收不到
 * `HDMI_AUDIO_PLUG`，这个值恒为 10（等于"什么都能放"），会误导下混判断。
 *
 * 正确做法：遍历输出设备，只统计**声明了 PCM 编码**的 sink 的 `getChannelCounts()`，
 * 优先采信 HDMI 类设备；HDMI 存在但没报任何 PCM 声道数 → 按 2 处理（本机就是这种）。
 */
@UnstableApi
fun pcmChannelsOfRoute(context: Context): Int {
    val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager ?: return 0
    var anyChannels = 0
    var hdmiChannels = 0
    var sawHdmi = false
    for (device in audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS)) {
        if (!device.isSink) continue
        val type = device.type
        val isHdmi = type == AudioDeviceInfo.TYPE_HDMI ||
            type == AudioDeviceInfo.TYPE_HDMI_ARC ||
            (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && type == AudioDeviceInfo.TYPE_HDMI_EARC)
        if (isHdmi) sawHdmi = true
        // 只看声明了 PCM 编码的设备；压缩编码的声道数不代表 PCM 能出几路
        val supportsPcm = device.encodings.any {
            it == AudioFormat.ENCODING_PCM_16BIT || it == AudioFormat.ENCODING_PCM_FLOAT
        }
        if (!supportsPcm) continue
        val max = device.channelCounts.maxOrNull() ?: 0
        if (max > anyChannels) anyChannels = max
        if (isHdmi && max > hdmiChannels) hdmiChannels = max
    }
    val result = when {
        // 有 HDMI 链路：以 HDMI 为准；HDMI 没报 PCM 声道数 → ARC 只能立体声
        sawHdmi -> if (hdmiChannels > 0) hdmiChannels else STEREO_ROUTE_CHANNELS
        else -> anyChannels
    }
    Log.i(TAG, "route pcm channels=$result (any=$anyChannels hdmi=$hdmiChannels sawHdmi=$sawHdmi)")
    return result
}

/**
 * 伪造 [AudioCapabilities]，只为让 media3 肯发起直通。
 *
 * 构成：
 * - 始终声明 PCM16（否则连普通播放都会被判不支持）；
 * - 系统自己认得的一律保留（某些机型 / 接了功放时是真的会报）；
 * - 再强行加入 **AC3 与 E-AC3**——这是本机 ARC 端口物理支持的全部压缩格式
 *   （`/vendor/etc/audio_policy_configuration.xml` 里 HDMI ARC 只注册了这两个 profile）。
 *
 * 刻意**不声明** DTS / DTS-HD / TrueHD / AC-4：本机 ARC 端口不认这些格式，声明了
 * 只会让 media3 去尝试直通、AudioTrack 初始化失败后报播放错误。不声明则 media3
 * 自动解码成 PCM 再折叠成 2 声道，反而是可用的降级路径。
 */
@UnstableApi
fun forcedAudioCapabilities(context: Context): AudioCapabilities {
    val base = AudioCapabilities.getCapabilities(context)
    val encodings = LinkedHashSet<Int>()
    encodings.add(C.ENCODING_PCM_16BIT)
    for (encoding in REPORTABLE_ENCODINGS) {
        if (base.supportsEncoding(encoding)) encodings.add(encoding)
    }
    for (encoding in PASSTHROUGH_ENCODINGS) encodings.add(encoding)
    val capabilities = AudioCapabilities(
        encodings.toIntArray(),
        maxOf(base.maxChannelCount, PASSTHROUGH_MAX_CHANNEL_COUNT),
    )
    Log.i(TAG, "audio capabilities: $capabilities")
    return capabilities
}

private const val TAG = "SpeedAwareAudio"

/** HDMI 链路存在但没报 PCM 声道数时，按立体声处理。 */
private const val STEREO_ROUTE_CHANNELS = 2

/** 声道数上限：ARC 声明到 7.1，取 8 兜住。 */
private const val PASSTHROUGH_MAX_CHANNEL_COUNT = 8

/** 系统若自己认得这些编码就保留，不额外强加。 */
private val REPORTABLE_ENCODINGS = intArrayOf(
    AudioFormat.ENCODING_AC3,
    AudioFormat.ENCODING_E_AC3,
    AudioFormat.ENCODING_E_AC3_JOC,
    AudioFormat.ENCODING_DTS,
    AudioFormat.ENCODING_DTS_HD,
    AudioFormat.ENCODING_DOLBY_TRUEHD,
)

/** 本机 ARC 端口物理支持、必须强行声明的编码。 */
private val PASSTHROUGH_ENCODINGS = intArrayOf(
    AudioFormat.ENCODING_AC3,
    AudioFormat.ENCODING_E_AC3,
)
