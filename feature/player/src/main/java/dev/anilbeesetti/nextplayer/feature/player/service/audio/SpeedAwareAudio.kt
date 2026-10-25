package dev.anilbeesetti.nextplayer.feature.player.service.audio

import android.content.Context
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioManager
import android.media.MediaCodecInfo
import android.media.MediaCodecList
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
import androidx.media3.exoplayer.DefaultRenderersFactory
import io.github.anilbeesetti.nextlib.media3ext.ffdecoder.DecoderMode
import io.github.anilbeesetti.nextlib.media3ext.ffdecoder.NextRenderersFactory
import java.io.File
import java.nio.ByteBuffer
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.CopyOnWriteArraySet

/**
 * 音频管线诊断日志。
 *
 * 为什么需要写文件：目标机（TCL m7642 / Android 9）的 logcat 环形缓冲区几乎不产出内容
 * （清空后数分钟只有十几行，`logcat -G 4M` 也无效），release 包又经过 R8，
 * 应用日志基本取不到。所以把关键决策落盘到
 * `/sdcard/Android/data/<pkg>/files/diag/audio.log`，用 adb pull 取回。
 *
 * 只在关键事件写（管线配置、折叠决策、闸门切换、重选与恢复），不做逐帧记录，
 * 文件上限 [MAX_BYTES]，超出即整体截断重写，避免无限增长。
 */
internal object AudioDiag {

    private const val MAX_BYTES = 512 * 1024L
    private const val FILE_NAME = "audio.log"

    @Volatile
    private var file: File? = null

    private val timeFormat = SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.US)
    private val lock = Any()

    fun init(context: Context) {
        if (file != null) return
        val dir = runCatching { context.getExternalFilesDir("diag") }.getOrNull() ?: return
        runCatching {
            if (!dir.exists()) dir.mkdirs()
            val target = File(dir, FILE_NAME)
            if (target.exists() && target.length() > MAX_BYTES) target.delete()
            file = target
        }
    }

    fun log(message: String) {
        Log.i(TAG, message)
        val target = file ?: return
        val line = "${timeFormat.format(Date())} $message\n"
        synchronized(lock) {
            runCatching {
                target.appendText(line)
            }
        }
    }
}

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

    /** 最近一次 configure 收到的 mime：判"这条音轨本来能不能直通"只看实测值。 */
    @Volatile
    var lastConfiguredMime: String? = null
        private set

    /**
     * 这条片源是否出现过压缩格式的 configure。
     *
     * 有 = 它本来能被直通，调速度时才值得重选音轨。
     * 没有 = AAC / FLAC / Opus 这类本来就是 PCM 出去的片源，变速由 Sonic 完成，
     * 重选只会白重建渲染器。
     *
     * 不能用 `currentTracks` 的 mime 判断：实测在重选、渲染器重建期间它会是 null 或
     * 已被改成 audio/raw，据此判断会把闸门自己关掉，表现为"调速度功放完全不变"。
     */
    @Volatile
    var passthroughEverSeen: Boolean = false
        private set

    /** 闸门当前期望值（true = 强制解码）。调用方据此判断是否已对齐，不必依赖增量回调。 */
    val forceDecode: Boolean
        get() = mode.forceDecode

    override fun configure(audioSinkConfig: AudioSink.AudioSinkConfig) {
        val format = audioSinkConfig.format
        val mimeType = format.sampleMimeType
        isPassthrough = mimeType != null && mimeType != MimeTypes.AUDIO_RAW
        lastConfiguredMime = mimeType
        if (isPassthrough) passthroughEverSeen = true
        AudioDiag.log(
            "sink.configure: mime=$mimeType ch=${format.channelCount} " +
                "rate=${format.sampleRate} pcm=${format.pcmEncoding} " +
                "passthrough=$isPassthrough forceDecode=${mode.forceDecode}",
        )
        super.configure(audioSinkConfig)
    }

    /**
     * 更新闸门，返回状态是否真的变了（未变则调用方不必触发音轨重选）。
     */
    fun setForceDecode(forceDecode: Boolean): Boolean {
        if (mode.forceDecode == forceDecode) return false
        mode.forceDecode = forceDecode
        AudioDiag.log("gate: forceDecode=$forceDecode")
        return true
    }

    /**
     * 把某个 mime 拉黑：之后不再尝试直通，退回解码。
     * 用于直通初始化失败的兜底（本机 ARC 不支持 DTS / TrueHD，失败后不应反复重试）。
     */
    fun revoke(mimeType: String?) {
        if (mimeType.isNullOrEmpty()) return
        if (deniedMimes.add(mimeType)) {
            AudioDiag.log("passthrough revoked for $mimeType, falling back to decode")
        }
    }

    /** 换片源时清空拉黑记录：上一部片源失败的格式不代表这一部也失败。 */
    fun clearDenylist() {
        if (deniedMimes.isNotEmpty()) {
            AudioDiag.log("passthrough denylist cleared: $deniedMimes")
            deniedMimes.clear()
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
            AudioDiag.log("downmix skipped: route accepts $routePcmChannels ch (input $channels ch)")
            return AudioProcessor.AudioFormat.NOT_SET
        }

        val matrix = layoutFor(channels)
            ?: if (channels > MAX_KNOWN_LAYOUT) widen(channels) else return AudioProcessor.AudioFormat.NOT_SET
        gains = matrix
        inputChannels = channels
        AudioDiag.log(
            "downmix: folding $channels ch -> 2 ch at ${inputAudioFormat.sampleRate}Hz " +
                "(encoding=$encoding, route pcm channels=$routePcmChannels)",
        )
        return AudioProcessor.AudioFormat(inputAudioFormat.sampleRate, STEREO_CHANNEL_COUNT, encoding)
    }

    override fun queueInput(inputBuffer: ByteBuffer) {
        if (!inputBuffer.hasRemaining()) return
        val matrix = gains ?: return
        val channels = inputChannels
        if (channels <= 0) return

        val isFloat = inputAudioFormat.encoding == C.ENCODING_PCM_FLOAT
        val scale = if (isFloat) 1.0f else PCM16_SCALE
        val bytesPerSample = if (isFloat) FLOAT_BYTES else PCM16_BYTES
        val frameBytes = bytesPerSample * channels
        val frames = inputBuffer.remaining() / frameBytes
        if (frames <= 0) {
            // 连一帧都凑不齐：整段丢弃，避免残留字节把后续声道相位带偏
            inputBuffer.position(inputBuffer.limit())
            return
        }

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
        // 末尾不足一帧的残字节直接丢弃：留在 buffer 里会让下一批数据的声道相位错位
        inputBuffer.position(inputBuffer.limit())
        out.flip()
    }

    /** 软限幅：0.75 以下线性通过，以上平滑压到 ≤1.0。 */
    private fun bend(value: Float): Float {
        val magnitude = kotlin.math.abs(value)
        if (magnitude <= KNEE) return value
        if (!limited) {
            limited = true
            AudioDiag.log("downmix: the fold reached the limiter, this mix is mastered at full scale")
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

        /**
         * LFE 折叠增益 -9dB。
         *
         * Dolby 规范里 2.0 折叠默认丢弃 LFE；但实测丢弃后整体听感明显发薄，
         * 所以保留一点点低频能量。取值足够低，配合 [HEADROOM] 与 [bend] 不会过载。
         */
        const val LFE_GAIN = 0.354f

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
                floatArrayOf(SURROUND_GAIN, 0f),
                floatArrayOf(0f, SURROUND_GAIN),
            )
            5 -> arrayOf( // FL FR FC BL BR
                floatArrayOf(1f, 0f),
                floatArrayOf(0f, 1f),
                floatArrayOf(CENTER_GAIN, CENTER_GAIN),
                floatArrayOf(SURROUND_GAIN, 0f),
                floatArrayOf(0f, SURROUND_GAIN),
            )
            6 -> arrayOf( // FL FR FC LFE BL BR —— 5.1，本机主力片源就是这个
                floatArrayOf(1f, 0f),
                floatArrayOf(0f, 1f),
                floatArrayOf(CENTER_GAIN, CENTER_GAIN),
                floatArrayOf(LFE_GAIN, LFE_GAIN),
                floatArrayOf(SURROUND_GAIN, 0f),
                floatArrayOf(0f, SURROUND_GAIN),
            )
            7 -> arrayOf( // FL FR FC LFE BC SL SR —— 6.1
                floatArrayOf(1f, 0f),
                floatArrayOf(0f, 1f),
                floatArrayOf(CENTER_GAIN, CENTER_GAIN),
                floatArrayOf(LFE_GAIN, LFE_GAIN),
                floatArrayOf(BACK_GAIN, BACK_GAIN),
                floatArrayOf(SURROUND_GAIN, 0f),
                floatArrayOf(0f, SURROUND_GAIN),
            )
            8 -> arrayOf( // FL FR FC LFE BL BR SL SR —— 7.1
                floatArrayOf(1f, 0f),
                floatArrayOf(0f, 1f),
                floatArrayOf(CENTER_GAIN, CENTER_GAIN),
                floatArrayOf(LFE_GAIN, LFE_GAIN),
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
                    floatArrayOf(SURROUND_GAIN, 0f)
                } else {
                    floatArrayOf(0f, SURROUND_GAIN)
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

    /**
     * 非 1 倍速时是否把变速交给平台（AudioTrack），即"倍速也保持直通"。
     *
     * 从 [dev.anilbeesetti.nextplayer.core.model.PlayerPreferences.passthroughAtAnySpeed] 同步过来，
     * **只在建 player 时读取**，改设置后要重开播放器才生效（sink 的输出配置在构建时就定了）。
     *
     * 为什么必须显式打开：media3 1.11.1 的 `AudioTrackAudioOutputProvider#getOutputConfig`
     * 在直通分支上写死了
     * `usePlaybackParameters = formatConfig.enablePlaybackParameters`，注释原文
     * "Passthrough only supports audio output playback parameters, but we only enable it
     * this was specifically requested by the app"；而这个 `enablePlaybackParameters` 就是
     * `DefaultAudioSink.Builder#setEnableAudioOutputPlaybackParameters` 的值（默认 false）。
     * 也就是说：**默认的直通路径根本不接受变速**，不是"交给 AudioTrack 重采样"。
     *
     * 打开之后 `AudioTrackAudioOutput#setPlaybackParameters` 会用
     * `PlaybackParams.setAudioFallbackMode(AUDIO_FALLBACK_MODE_FAIL)` 提交，平台做不了就抛
     * `IllegalArgumentException` 并被 media3 吞掉（日志 `Failed to set playback params`），
     * 实际速率退回 1.0 —— 用 `AudioSink#getPlaybackParameters` 能读回真实值。
     */
    @Volatile
    var passthroughAtAnySpeed: Boolean = false

    var gateSink: PassthroughGateAudioSink? = null
        private set

    init {
        AudioDiag.init(context)
        AudioDiag.log(
            "factory ready: routePcmChannels=$routePcmChannels " +
                "capabilities=${forcedAudioCapabilities(context)}",
        )
        // FFmpeg 扩展渲染器默认不加载（extensionRendererMode 默认 OFF），
        // 选「软解+」时 DecoderMediaCodecSelector 返回空列表、FfmpegVideoRenderer 又不在
        // 渲染器列表里，必然报「没有支持此媒体的解码器可用」。这里打开它，让软解真能用。
        setExtensionRendererMode(DefaultRenderersFactory.EXTENSION_RENDERER_MODE_ON)
        // 注意：Dolby Vision **必须**交给系统 DV 解码器（OMX.MS.DOLBY_VISION.Decoder）。
        // 曾试过把 video/dolby-vision 改投 HEVC 解码器，结果画面严重偏绿/发灰 ——
        // DV Profile 5 的码流是 IPTPQc2 色彩编码，只有 DV 解码器会做颜色转换，
        // 普通 HEVC 解码器按 YCbCr 解释必然偏色。已回滚，不要再改。
    }

    override fun buildAudioSink(
        context: Context,
        enableFloatOutput: Boolean,
        enableAudioOutputPlaybackParameters: Boolean,
    ): AudioSink? {
        sinkBuildCount++
        // 通道 B（倍速保持直通）：把 sink 的变速责任交给 AudioTrack，直通分支才会接受速度。
        val askPlatformSpeed = enableAudioOutputPlaybackParameters || passthroughAtAnySpeed
        AudioDiag.log(
            "buildAudioSink #$sinkBuildCount (float=$enableFloatOutput " +
                "playbackParams=$askPlatformSpeed anySpeedPassthrough=$passthroughAtAnySpeed)",
        )
        val sink = DefaultAudioSink.Builder(context)
            .setAudioCapabilities(forcedAudioCapabilities(context))
            .setEnableFloatOutput(enableFloatOutput)
            .setEnableAudioOutputPlaybackParameters(askPlatformSpeed)
            .setAudioProcessorChain(
                DefaultAudioSink.DefaultAudioProcessorChain(
                    DownmixToStereoAudioProcessor(routePcmChannels),
                ),
            )
            .build()
        return PassthroughGateAudioSink(speedPcmMode, sink).also { gateSink = it }
    }

    private var sinkBuildCount = 0
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
    AudioDiag.log("route pcm channels=$result (any=$anyChannels hdmi=$hdmiChannels sawHdmi=$sawHdmi)")
    return result
}

/**
 * 当前音频解码器模式能否解出 [mimeType]。
 *
 * 非 1 倍速时必须解码压缩音轨才能变速。若用户把音频解码器钉在「软解+」
 * （`DecoderMode.SOFTWARE`，只用 `c2.android.*` / `OMX.google.*`）而本机
 * E-AC3 只有厂商硬件解码器，就会陷入：闸门拦掉直通 → 解码器不存在 →
 * 音轨被判不支持 → 弹「没有支持此媒体的解码器」且静音。
 * 所以切换前先问一句；解不了就不拦直通（宁可速度不生效，也不能没声音）。
 *
 * `FFMPEG` 模式由 nextlib 的 FFmpegAudioRenderer 兜底，视为总能解码。
 */
@UnstableApi
fun canDecodeAudioMime(mimeType: String?, mode: DecoderMode): Boolean {
    if (mimeType.isNullOrEmpty()) return true
    if (mimeType == MimeTypes.AUDIO_RAW) return true
    if (mode == DecoderMode.FFMPEG) return true

    val requireHardware = mode == DecoderMode.HARDWARE
    val requireSoftware = mode == DecoderMode.SOFTWARE
    val codecList = runCatching { MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos }
        .getOrDefault(emptyArray())
    val found = codecList.any { info ->
        if (info.isEncoder) return@any false
        if (info.supportedTypes.none { it.equals(mimeType, ignoreCase = true) }) return@any false
        val hardware = isHardwareCodec(info)
        when {
            requireHardware -> hardware
            requireSoftware -> !hardware
            else -> true
        }
    }
    if (!found) {
        AudioDiag.log("no decoder for $mimeType under mode=$mode, keeping pass-through")
    }
    return found
}

/** `MediaCodecInfo.isHardwareAccelerated` 是 API 29，旧机型只能按名字前缀判断。 */
private fun isHardwareCodec(info: MediaCodecInfo): Boolean {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
        return runCatching { info.isHardwareAccelerated }.getOrDefault(true)
    }
    val name = info.name
    val software = name.startsWith("OMX.google.") ||
        name.startsWith("OMX.ffmpeg.") ||
        name.startsWith("c2.android.") ||
        name.contains("software", ignoreCase = true)
    return !software
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
    AudioDiag.log("audio capabilities: $capabilities (system reported $base)")
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
