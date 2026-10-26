package dev.anilbeesetti.nextplayer.feature.player.service

import android.app.PendingIntent
import android.content.ContentResolver
import android.content.Intent
import android.media.audiofx.LoudnessEnhancer
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import androidx.annotation.OptIn
import androidx.core.net.toUri
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.common.Player.DISCONTINUITY_REASON_AUTO_TRANSITION
import androidx.media3.common.Player.DISCONTINUITY_REASON_REMOVE
import androidx.media3.common.Player.DISCONTINUITY_REASON_SEEK
import androidx.media3.common.Timeline
import androidx.media3.common.TrackSelectionParameters
import androidx.media3.common.Tracks
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlaybackException
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.analytics.AnalyticsListener
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.trackselection.DefaultTrackSelector
import androidx.media3.session.CommandButton
import androidx.media3.session.CommandButton.ICON_UNDEFINED
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionError
import androidx.media3.session.SessionResult
import coil3.ImageLoader
import coil3.request.ImageRequest
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import dev.anilbeesetti.nextplayer.core.common.Logger
import dev.anilbeesetti.nextplayer.core.common.extensions.deleteFiles
import dev.anilbeesetti.nextplayer.core.common.extensions.getFilenameFromUri
import dev.anilbeesetti.nextplayer.core.common.extensions.getLocalSubtitles
import dev.anilbeesetti.nextplayer.core.common.extensions.getPath
import dev.anilbeesetti.nextplayer.core.common.extensions.subtitleCacheDir
import dev.anilbeesetti.nextplayer.core.data.repository.MediaRepository
import dev.anilbeesetti.nextplayer.core.data.repository.PreferencesRepository
import dev.anilbeesetti.nextplayer.core.media.network.NetworkUri
import dev.anilbeesetti.nextplayer.core.media.network.datasource.NextDataSourceFactory
import dev.anilbeesetti.nextplayer.core.model.LoopMode
import dev.anilbeesetti.nextplayer.core.model.PlayerPreferences
import dev.anilbeesetti.nextplayer.core.model.Resume
import dev.anilbeesetti.nextplayer.core.ui.R as coreUiR
import dev.anilbeesetti.nextplayer.feature.player.PlayerActivity
import dev.anilbeesetti.nextplayer.feature.player.R
import dev.anilbeesetti.nextplayer.feature.player.extensions.addAdditionalSubtitleConfiguration
import dev.anilbeesetti.nextplayer.feature.player.extensions.audioDecoderMode
import dev.anilbeesetti.nextplayer.feature.player.extensions.audioTrackIndex
import dev.anilbeesetti.nextplayer.feature.player.extensions.copy
import dev.anilbeesetti.nextplayer.feature.player.extensions.externalAudio
import dev.anilbeesetti.nextplayer.feature.player.extensions.externalAudioIndex
import dev.anilbeesetti.nextplayer.feature.player.extensions.getManuallySelectedTrackIndex
import dev.anilbeesetti.nextplayer.feature.player.extensions.positionMs
import dev.anilbeesetti.nextplayer.feature.player.extensions.setExtras
import dev.anilbeesetti.nextplayer.feature.player.extensions.setIsScrubbingModeEnabled
import dev.anilbeesetti.nextplayer.feature.player.extensions.subtitleDelayMilliseconds
import dev.anilbeesetti.nextplayer.feature.player.extensions.subtitleSpeed
import dev.anilbeesetti.nextplayer.feature.player.extensions.subtitleTrackIndex
import dev.anilbeesetti.nextplayer.feature.player.extensions.switchTrack
import dev.anilbeesetti.nextplayer.feature.player.extensions.uriToSubtitleConfiguration
import dev.anilbeesetti.nextplayer.feature.player.extensions.videoDecoderMode
import dev.anilbeesetti.nextplayer.feature.player.extensions.videoZoom
import dev.anilbeesetti.nextplayer.feature.player.extensions.withExternalAudio
import dev.anilbeesetti.nextplayer.feature.player.model.DecoderTrackType
import dev.anilbeesetti.nextplayer.feature.player.service.audio.AudioDiag
import dev.anilbeesetti.nextplayer.feature.player.service.audio.SpeedAwareRenderersFactory
import dev.anilbeesetti.nextplayer.feature.player.service.audio.canDecodeAudioMime
import dev.anilbeesetti.nextplayer.feature.player.utils.isExplicitPlaylistItem
import io.github.anilbeesetti.nextlib.media3ext.ffdecoder.DecoderManager
import io.github.anilbeesetti.nextlib.media3ext.ffdecoder.DecoderMode
import io.github.anilbeesetti.nextlib.media3ext.renderer.subtitleDelayMilliseconds
import io.github.anilbeesetti.nextlib.media3ext.renderer.subtitleSpeed
import io.github.anilbeesetti.nextlib.mediainfo.MediaInfoBuilder
import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancel
import kotlinx.coroutines.guava.future
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.withContext
import org.koin.android.ext.android.inject
import kotlin.math.abs

@OptIn(UnstableApi::class)
class PlayerService : MediaSessionService() {

    private val serviceScope: CoroutineScope = CoroutineScope(
        context =
            Dispatchers.Main + SupervisorJob(),
    )
    private var mediaSession: MediaSession? = null
    private var artworkLoadJob: Job? = null

    private val preferencesRepository: PreferencesRepository by inject()

    private val dataSourceFactory: NextDataSourceFactory by inject()

    private val mediaRepository: MediaRepository by inject()

    private val imageLoader: ImageLoader by inject()

    private val playerPreferences: PlayerPreferences
        get() = preferencesRepository.playerPreferences.value

    private val customCommands = CustomCommands.asSessionCommands()

    private var isMediaItemReady = false
    private var pendingAudioSelection: Pair<String, Uri>? = null

    private var loudnessEnhancer: LoudnessEnhancer? = null
    private var currentVolumeGain: Int = 0

    /** [LoudnessEnhancer] 当前挂在哪个 audio session 上（0 = 未挂）。 */
    private var enhancerSessionId: Int = 0

    private lateinit var decoderManager: DecoderManager
    private lateinit var trackSelector: DefaultTrackSelector
    private var speedAudioFactory: SpeedAwareRenderersFactory? = null
    private val reselectHandler = Handler(Looper.getMainLooper())
    private val decoderRecoveryManager = DecoderRecoveryManager()

    // 音频管线切换（直通 <-> 解码）的状态。
    // 重选 = 临时禁用音轨类型，随后**必定**恢复（见 updateSpeedAudioMode 注释：
    // 恢复不能依赖 onTracksChanged，否则会永久静音）。
    private var audioModeRetries = 0
    private var audioReselectAtMs = 0L
    private var audioReselectScheduledAtMs = 0L
    private var audioRestartInFlight = false

    /**
     * 音轨是**我们自己**禁用掉的，且还没恢复。
     *
     * 关键：判断依据必须是这个本地标志，**绝不能读 `player.trackSelectionParameters`
     * 反推**。实测（TCL m7642 / Android 9 / media3 1.11.1）恢复后读回来的
     * `disabledTrackTypes` 仍可能含 audio，于是"幂等恢复"变成每 [AUDIO_WATCHDOG_INTERVAL_MS]
     * 触发一次 disable/enable 死循环：每次都要重建 audio renderer、重新 configure sink，
     * 表现为 1 倍速直通断续吞字、非 1 倍速没声音，最后 mediacodec 被反复抢光，
     * 视频解码器也建不出来，弹「没有支持此媒体的解码器」。
     */
    private var audioTrackDisabledByUs = false
    private var audioTrackDisabledAtMs = 0L

    /**
     * 设置里的「调速时降级为 2 声道 PCM」。
     *
     * 建 player 时读取（sink 的输出配置构建时就定死了），改设置要重开播放器才生效。
     * true：调速时把音轨降级成 2 声道 PCM，音画同步（Sonic 变速，音调保持）。
     * false（默认）：全程直通，功放一直是真正的 5.1 / Atmos，
     * 代价是调速时音频恒 1 倍速（音画不同步，上游原本的行为）。
     */
    private var decodeToStereoWhenSpeeding = false
    /** 本片源内已经重选过几次。超过 [AUDIO_RESELECT_MAX_PER_ITEM] 就放弃切换，保声音优先。 */
    private var audioReselectCount = 0

    /** [enforceAudioMode] 上次纠正的时间，防止看门狗与事件回调互相触发形成抖动。 */
    private var audioEnforceAtMs = 0L

    /**
     * 本片源的音轨在"强制解码"下被判为不支持过一次。
     *
     * 典型情形：用户把音频解码器钉在「软解+」而本机 E-AC3 只有厂商硬件解码器，
     * 非 1 倍速时闸门拦掉直通 → 没有可用解码器 → 音轨被判不支持 → 弹解码器错误且静音。
     * 置位后本片源不再强制解码（速度对直通轨不生效，但至少有声音），换片源时复位。
     */
    private var decodeBlockedForItem = false

    private val decoderAnalyticsListener = object : AnalyticsListener {
        override fun onEvents(player: Player, events: AnalyticsListener.Events) {
            publishDecoderState()
        }

        override fun onVideoDecoderInitialized(
            eventTime: AnalyticsListener.EventTime,
            decoderName: String,
            initializedTimestampMs: Long,
            initializationDurationMs: Long,
        ) {
            Logger.logInfo(
                tag = DECODER_LOG_TAG,
                message = "Video decoder initialized with requested=${decoderManager.videoMode} " +
                    "as ${decoderManager.activeVideoMode}: $decoderName",
            )
            decoderRecoveryManager.onDecoderInitialized(DecoderTrackType.VIDEO)
        }

        override fun onAudioDecoderInitialized(
            eventTime: AnalyticsListener.EventTime,
            decoderName: String,
            initializedTimestampMs: Long,
            initializationDurationMs: Long,
        ) {
            Logger.logInfo(
                tag = DECODER_LOG_TAG,
                message = "Audio decoder initialized with requested=${decoderManager.audioMode} " +
                    "as ${decoderManager.activeAudioMode}: $decoderName",
            )
            decoderRecoveryManager.onDecoderInitialized(DecoderTrackType.AUDIO)
        }

        override fun onTracksChanged(eventTime: AnalyticsListener.EventTime, tracks: Tracks) {
            val videoTracks = tracks.groups
                .filter { it.type == C.TRACK_TYPE_VIDEO }
                .joinToString { group ->
                    val mimeType = group.mediaTrackGroup.getFormat(0).sampleMimeType
                    "$mimeType(supported=${group.isSupported(true)}, selected=${group.isSelected})"
                }
            Logger.logInfo(
                tag = DECODER_LOG_TAG,
                message = "Video tracks: ${videoTracks.ifEmpty { "none" }}, " +
                    "unmapped=${trackSelector.unmappedTrackCount(C.TRACK_TYPE_VIDEO)}",
            )
            // 音频轨的选择结果必须落盘：出现"有画面没声音"时，第一件事就是看这条音轨
            // 到底有没有被选中（supported=false / selected=false 与"选中了但 sink 没 configure"
            // 是完全不同的两种故障）。
            val audioTracks = tracks.groups
                .filter { it.type == C.TRACK_TYPE_AUDIO }
                .joinToString { group ->
                    val format = group.mediaTrackGroup.getFormat(0)
                    "${format.sampleMimeType}(${format.channelCount}ch/" +
                        "${format.sampleRate}Hz supported=${group.isSupported(true)}" +
                        " selected=${group.isSelected})"
                }
            val sink = speedAudioFactory?.gateSink
            AudioDiag.log(
                "tracks: audio=${audioTracks.ifEmpty { "none" }} " +
                    "unmapped=${trackSelector.unmappedTrackCount(C.TRACK_TYPE_AUDIO)} " +
                    "gate=${sink?.forceDecode} passthrough=${sink?.isPassthrough} " +
                    "decodeToStereoWhenSpeeding=$decodeToStereoWhenSpeeding",
            )
        }

        override fun onRenderedFirstFrame(
            eventTime: AnalyticsListener.EventTime,
            output: Any,
            renderTimeMs: Long,
        ) {
            Logger.logInfo(
                tag = DECODER_LOG_TAG,
                message = "Rendered first frame with video=${decoderManager.activeVideoMode}",
            )
        }

        override fun onPlayerError(
            eventTime: AnalyticsListener.EventTime,
            error: PlaybackException,
        ) {
            Logger.logError(
                tag = DECODER_LOG_TAG,
                message = "Player error with requestedVideo=${decoderManager.videoMode}, " +
                    "activeVideo=${decoderManager.activeVideoMode}, " +
                    "requestedAudio=${decoderManager.audioMode}, " +
                    "activeAudio=${decoderManager.activeAudioMode}: ${error.message}",
            )
            // 本机 logcat 取不到应用日志，错误必须落盘，否则只能看到最终 UI 文案
            AudioDiag.log(
                "player error: code=${error.errorCodeName} track=${error.decoderTrackType()} " +
                    "gate=${speedAudioFactory?.gateSink?.forceDecode} " +
                    "actual=${speedAudioFactory?.gateSink?.isPassthrough} " +
                    "requestedVideo=${decoderManager.videoMode} " +
                    "activeVideo=${decoderManager.activeVideoMode} " +
                    "requestedAudio=${decoderManager.audioMode} " +
                    "reselects=$audioReselectCount speed=${mediaSession?.player?.playbackParameters?.speed}",
            )
            var cause: Throwable? = error.cause
            var depth = 0
            while (cause != null && depth < 5) {
                AudioDiag.log("player error cause[$depth] ${cause.javaClass.simpleName}: ${cause.message}")
                cause = cause.cause
                depth++
            }
        }
    }

    private val playbackStateListener = object : Player.Listener {
        override fun onPlaybackParametersChanged(playbackParameters: PlaybackParameters) {
            super.onPlaybackParametersChanged(playbackParameters)
            updateSpeedAudioMode(playbackParameters.speed)
        }

        override fun onTimelineChanged(timeline: Timeline, reason: Int) {
            val player = mediaSession?.player as? ExoPlayer ?: return
            // Autoplay controls the implicit folder queue; explicitly chosen playlists play in full.
            player.pauseAtEndOfMediaItems = !playerPreferences.autoplay &&
                player.currentMediaItem?.isExplicitPlaylistItem != true
        }

        override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
            super.onMediaItemTransition(
                mediaItem,
                reason,
            )
            if (reason == Player.MEDIA_ITEM_TRANSITION_REASON_REPEAT) return
            // 新片源重新评估直通：上一部片源失败过的格式（DTS / TrueHD）不代表这部也失败
            speedAudioFactory?.gateSink?.clearDenylist()
            decodeBlockedForItem = false
            audioReselectCount = 0
            audioModeRetries = 0
            audioTrackDisabledByUs = false
            restoreDecoderChoices(mediaItem = mediaItem)
            isMediaItemReady = false
            loadArtworkForCurrentMediaItem()
            mediaItem?.mediaMetadata?.let { metadata ->
                mediaSession?.player?.run {
                    playerSpecificSubtitleDelayMilliseconds =
                        metadata.subtitleDelayMilliseconds ?: 0L
                    playerSpecificSubtitleSpeed = metadata.subtitleSpeed ?: 1f
                }

                metadata.positionMs?.takeIf { playerPreferences.resume == Resume.YES }?.let {
                    mediaSession?.player?.seekTo(it)
                }
            }
        }

        override fun onPositionDiscontinuity(
            oldPosition: Player.PositionInfo,
            newPosition: Player.PositionInfo,
            reason: Int,
        ) {
            super.onPositionDiscontinuity(
                oldPosition,
                newPosition,
                reason,
            )
            val oldMediaItem = oldPosition.mediaItem ?: return

            when (reason) {
                DISCONTINUITY_REASON_SEEK,
                DISCONTINUITY_REASON_AUTO_TRANSITION,
                -> {
                    if (newPosition.mediaItem == null || oldMediaItem == newPosition.mediaItem) {
                        return
                    }

                    val updatedPosition =
                        oldPosition.positionMs.takeIf { reason == DISCONTINUITY_REASON_SEEK }
                            ?: C.TIME_UNSET
                    mediaSession?.player?.replaceMediaItem(
                        oldPosition.mediaItemIndex,
                        oldMediaItem.copy(positionMs = updatedPosition),
                    )
                    serviceScope.launch {
                        mediaRepository.updateMediumPosition(
                            uri = oldMediaItem.mediaId,
                            position = updatedPosition,
                        )
                    }
                }

                DISCONTINUITY_REASON_REMOVE -> {
                    serviceScope.launch {
                        val durationMs = oldMediaItem.mediaMetadata.durationMs
                        val isAtEnd =
                            durationMs != null && oldPosition.positionMs >= durationMs - 1000
                        mediaRepository.updateMediumPosition(
                            uri = oldMediaItem.mediaId,
                            position = if (isAtEnd) C.TIME_UNSET else oldPosition.positionMs,
                        )
                    }
                }

                else -> return
            }
        }

        override fun onTracksChanged(tracks: Tracks) {
            super.onTracksChanged(tracks)
            // 我们自己禁用音轨引发的轨道变化：立刻恢复（幂等）并只安排事后校验。
            // 这次变化不代表用户换了片源，也不能拿它去判断"解码器不支持"——
            // 音轨刚被我们禁用时是 unselected 的，判成不支持会误报解码失败。
            if (audioRestartInFlight) {
                audioRestartInFlight = false
                restoreAudioTrackType("tracks changed")
                reselectHandler.removeCallbacks(restoreAudioTrackRunnable)
                reselectHandler.removeCallbacks(audioModeVerifyRunnable)
                reselectHandler.postDelayed(audioModeVerifyRunnable, AUDIO_MODE_VERIFY_DELAY_MS)
                return
            }
            serviceScope.launch {
                mediaSession?.player?.currentTracks?.let(block = ::handleUnsupportedTracks)
            }
            if (!isMediaItemReady && tracks.groups.isNotEmpty()) {
                isMediaItemReady = true

                val player = mediaSession?.player ?: return
                val pending = pendingAudioSelection
                pendingAudioSelection = null
                if (pending != null && pending.first == player.currentMediaItem?.mediaId) {
                    val audioIndex =
                        player.currentMediaItem?.mediaMetadata?.externalAudio.orEmpty().indexOf(
                            pending.second,
                        )
                    val trackIndex = tracks.groups.filter { it.type == C.TRACK_TYPE_AUDIO }
                        .indexOfFirst { it.mediaTrackGroup.externalAudioIndex == audioIndex }
                    if (audioIndex >= 0 && trackIndex >= 0) {
                        player.switchTrack(
                            C.TRACK_TYPE_AUDIO,
                            trackIndex = trackIndex,
                        )
                    }
                }
                if (!playerPreferences.rememberSelections) return
                mediaSession?.player?.mediaMetadata?.audioTrackIndex?.takeIf {
                    pending == null
                }?.let {
                    mediaSession?.player?.switchTrack(
                        C.TRACK_TYPE_AUDIO,
                        trackIndex = it,
                    )
                }
                mediaSession?.player?.mediaMetadata?.subtitleTrackIndex?.let {
                    mediaSession?.player?.switchTrack(
                        C.TRACK_TYPE_TEXT,
                        trackIndex = it,
                    )
                }
            }
        }

        override fun onTrackSelectionParametersChanged(parameters: TrackSelectionParameters) {
            super.onTrackSelectionParametersChanged(parameters)
            val player = mediaSession?.player ?: return
            val currentMediaItem = player.currentMediaItem ?: return

            val audioTrackIndex = player.getManuallySelectedTrackIndex(C.TRACK_TYPE_AUDIO)
            val subtitleTrackIndex = player.getManuallySelectedTrackIndex(C.TRACK_TYPE_TEXT)

            if (audioTrackIndex != null) {
                serviceScope.launch {
                    mediaRepository.updateMediumAudioTrack(
                        uri = currentMediaItem.mediaId,
                        audioTrackIndex = audioTrackIndex,
                    )
                }
            }

            if (subtitleTrackIndex != null) {
                serviceScope.launch {
                    mediaRepository.updateMediumSubtitleTrack(
                        uri = currentMediaItem.mediaId,
                        subtitleTrackIndex = subtitleTrackIndex,
                    )
                }
            }

            player.replaceMediaItem(
                player.currentMediaItemIndex,
                currentMediaItem.copy(
                    audioTrackIndex = audioTrackIndex,
                    subtitleTrackIndex = subtitleTrackIndex,
                ),
            )
        }

        override fun onPlaybackStateChanged(playbackState: Int) {
            super.onPlaybackStateChanged(playbackState)

            val player = mediaSession?.player
            val shouldResetTrackSelectionParameters = playbackState == Player.STATE_ENDED ||
                (
                    playbackState == Player.STATE_IDLE &&
                        player?.mediaItemCount == 0
                    )
            if (shouldResetTrackSelectionParameters) {
                mediaSession?.player?.trackSelectionParameters = TrackSelectionParameters.DEFAULT
            }

            if (playbackState == Player.STATE_READY) {
                // 切片或重播就绪后按当前速度重新对齐闸门：速度回调可能压根没来过
                reselectHandler.postDelayed(
                    { enforceAudioMode("state ready") },
                    AUDIO_MODE_ALIGN_DELAY_MS,
                )
            }
        }

        override fun onPlayerError(error: PlaybackException) {
            if (!error.isDecoderFailure) {
                decoderRecoveryManager.onNonDecoderError()
                return
            }

            val trackType = error.decoderTrackType() ?: run {
                decoderRecoveryManager.onNonDecoderError()
                return
            }
            handleDecoderFailure(trackType = trackType)
        }

        override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
            super.onPlayWhenReadyChanged(
                playWhenReady,
                reason,
            )

            if (reason == Player.PLAY_WHEN_READY_CHANGE_REASON_END_OF_MEDIA_ITEM) {
                if (mediaSession?.player?.repeatMode != Player.REPEAT_MODE_OFF) {
                    mediaSession?.player?.seekTo(0)
                    mediaSession?.player?.play()
                    return
                }
                mediaSession?.run {
                    player.clearMediaItems()
                    player.stop()
                }
                stopSelf()
            }
        }

        override fun onEvents(player: Player, events: Player.Events) {
            // Consecutive items can reuse the renderer without another first-frame callback.
            if (player.playbackState != Player.STATE_READY ||
                !events.containsAny(
                    Player.EVENT_MEDIA_ITEM_TRANSITION,
                    Player.EVENT_PLAYBACK_STATE_CHANGED,
                )
            ) {
                return
            }
            val currentMediaItem = player.currentMediaItem ?: return
            val duration = player.duration.validDurationOrNull()
            // Update the media metadata duration so that it will be used later in position discontinuity handling
            player.replaceMediaItem(
                player.currentMediaItemIndex,
                currentMediaItem.copy(durationMs = duration ?: 0),
            )

            serviceScope.launch {
                mediaRepository.updateMediumLastPlayedTime(
                    uri = currentMediaItem.mediaId,
                    lastPlayedTime = System.currentTimeMillis(),
                    duration = duration,
                )
            }
        }

        override fun onIsPlayingChanged(isPlaying: Boolean) {
            super.onIsPlayingChanged(isPlaying)
            if (isPlaying) enforceAudioMode("is playing")
            mediaSession?.run {
                serviceScope.launch {
                    mediaRepository.updateMediumPosition(
                        uri = player.currentMediaItem?.mediaId ?: return@launch,
                        position = player.currentPosition,
                    )
                }
            }
        }

        override fun onRepeatModeChanged(repeatMode: Int) {
            super.onRepeatModeChanged(repeatMode)
            serviceScope.launch {
                preferencesRepository.updatePlayerPreferences {
                    it.copy(
                        loopMode = when (repeatMode) {
                            Player.REPEAT_MODE_OFF -> LoopMode.OFF
                            Player.REPEAT_MODE_ONE -> LoopMode.ONE
                            Player.REPEAT_MODE_ALL -> LoopMode.ALL
                            else -> LoopMode.OFF
                        },
                    )
                }
            }
        }

        override fun onAudioSessionIdChanged(audioSessionId: Int) {
            super.onAudioSessionIdChanged(audioSessionId)
            if (audioSessionId == C.AUDIO_SESSION_ID_UNSET) return
            // 只在「音量增强」打开时才挂这个 effect。
            // 它原本是为了让功放显示跟随 PCM 声道数（见 ensureLoudnessEnhancer 的说明），
            // 但 2026-10-26 定案走直通优先后不再有 PCM 路径，而直通时音频在 offload
            // 轨道上，挂 AudioEffect 有干扰 AudioTrack 初始化的风险（会变成无声），
            // 所以恢复成"只有要 boost 时才挂"。
            if (!playerPreferences.enableVolumeBoost) return
            ensureLoudnessEnhancer(audioSessionId)
        }
    }

    /**
     * 确保 [LoudnessEnhancer] 挂在当前 audio session 上（幂等）。
     *
     * 为什么要挂：实测（2026-10-26，同机同片源对比 just-plus-player）两者送进 HAL 的
     * 音频流逐位相同（per-track flags / format / chn mask / srate 全一致），唯一差别是
     * just+ 的 MIXER 线程上有 1 条 Effect Chain：
     *   Effect ID 19  UUID fa415329-…  name: Loudness Enhancer（AOSP 内置）
     * 而我们没有这条 chain —— 旧代码在 `enableVolumeBoost` 为 false 时直接 return，
     * 从来没创建过这个 effect。电视的 Dolby 链在 bypass=0 时会把 2ch PCM 升混重编码成
     * 杜比，功放于是永远显示"透传"；有这条 effect chain 时走的是 MIXER+effect 路径，
     * 这很可能就是"功放显示能否跟随 PCM 声道数"的分水岭。
     *
     * 必须在**切到 PCM 之后**也能重建：1.0 倍速直通时音频走 offload 线程，
     * offload 通道不允许挂 effect，创建会抛异常；切回 PCM 时 session 不变，
     * 不会再次触发 [onAudioSessionIdChanged]，所以切速时要主动调一次。
     */
    private fun ensureLoudnessEnhancer(audioSessionId: Int = 0) {
        val player = mediaSession?.player ?: return
        val sessionId = if (audioSessionId != 0) audioSessionId else player.audioSessionId
        if (sessionId == C.AUDIO_SESSION_ID_UNSET) return
        if (loudnessEnhancer != null && enhancerSessionId == sessionId) return
        try {
            loudnessEnhancer?.release()
            loudnessEnhancer = LoudnessEnhancer(sessionId)
            enhancerSessionId = sessionId
            // 关闭"音量增强"时用 gain=0（不提升音量，只让 effect 留在链上），
            // 与 just-plus-player 的行为一致。
            val gain = if (playerPreferences.enableVolumeBoost) currentVolumeGain else 0
            setEnhancerTargetGain(gain = gain)
            AudioDiag.log(
                "loudness enhancer: session=$sessionId gain=$gain " +
                    "boost=${playerPreferences.enableVolumeBoost}",
            )
        } catch (e: Exception) {
            AudioDiag.log("loudness enhancer failed: $e")
            loudnessEnhancer = null
            enhancerSessionId = 0
        }
    }

    private fun setEnhancerTargetGain(gain: Int) {
        val enhancer = loudnessEnhancer ?: return

        try {
            enhancer.setTargetGain(gain)
            // 一直保持 enabled：LoudnessEnhancer 只要挂在 session 上，AudioFlinger 就会
            // 为这条轨建立 Effect Chain（MIXER 路径）。just-plus-player 也是这么做的
            // ——它创建完 LoudnessEnhancer 后没有再关掉它。
            // gain=0 表示不做任何增益，只是让 effect 留在链上。
            enhancer.enabled = true
            currentVolumeGain = enhancer.targetGain.toInt()
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private val mediaSessionCallback = object : MediaSession.Callback {
        override fun onConnectAsync(
            session: MediaSession,
            controller: MediaSession.ControllerInfo,
        ): ListenableFuture<MediaSession.ConnectionResult> = Futures.immediateFuture(
            MediaSession.ConnectionResult.accept(
                MediaSession.ConnectionResult.DEFAULT_SESSION_COMMANDS
                    .buildUpon()
                    .addSessionCommands(customCommands)
                    .build(),
                MediaSession.ConnectionResult.DEFAULT_PLAYER_COMMANDS,
            ),
        )

        override fun onSetMediaItems(
            mediaSession: MediaSession,
            controller: MediaSession.ControllerInfo,
            mediaItems: MutableList<MediaItem>,
            startIndex: Int,
            startPositionMs: Long,
        ): ListenableFuture<MediaSession.MediaItemsWithStartPosition> =
            serviceScope.future(Dispatchers.Default) {
                val updatedMediaItems = updatedMediaItemsWithMetadata(mediaItems = mediaItems)
                return@future MediaSession.MediaItemsWithStartPosition(
                    updatedMediaItems,
                    startIndex,
                    startPositionMs,
                )
            }

        override fun onAddMediaItems(
            mediaSession: MediaSession,
            controller: MediaSession.ControllerInfo,
            mediaItems: MutableList<MediaItem>,
        ): ListenableFuture<MutableList<MediaItem>> = serviceScope.future(Dispatchers.Default) {
            val updatedMediaItems = updatedMediaItemsWithMetadata(mediaItems = mediaItems)
            return@future updatedMediaItems.toMutableList()
        }

        override fun onCustomCommand(
            session: MediaSession,
            controller: MediaSession.ControllerInfo,
            customCommand: SessionCommand,
            args: Bundle,
        ): ListenableFuture<SessionResult> = serviceScope.future {
            val command = CustomCommands.fromSessionCommand(sessionCommand = customCommand)
                ?: return@future SessionResult(SessionError.ERROR_BAD_VALUE)

            when (command) {
                CustomCommands.ADD_SUBTITLE_TRACK -> {
                    val subtitleUri = args.getString(CustomCommands.SUBTITLE_TRACK_URI_KEY)?.toUri()
                        ?: return@future SessionResult(SessionError.ERROR_BAD_VALUE)

                    val newSubConfiguration = uriToSubtitleConfiguration(
                        uri = subtitleUri,
                        subtitleEncoding = playerPreferences.subtitleTextEncoding,
                    )
                    mediaSession?.player?.let { player ->
                        val currentMediaItem = player.currentMediaItem ?: return@let
                        val textTracks = player.currentTracks.groups.filter {
                            it.type == C.TRACK_TYPE_TEXT && it.isSupported
                        }

                        mediaRepository.updateMediumPosition(
                            uri = currentMediaItem.mediaId,
                            position = player.currentPosition,
                        )
                        mediaRepository.updateMediumSubtitleTrack(
                            uri = currentMediaItem.mediaId,
                            subtitleTrackIndex = textTracks.size,
                        )
                        mediaRepository.addExternalSubtitleToMedium(
                            uri = currentMediaItem.mediaId,
                            subtitleUri = subtitleUri,
                        )
                        player.addAdditionalSubtitleConfiguration(subtitle = newSubConfiguration)
                    }
                    return@future SessionResult(SessionResult.RESULT_SUCCESS)
                }

                CustomCommands.ADD_AUDIO_TRACK -> {
                    val uri = args.getString(CustomCommands.AUDIO_TRACK_URI_KEY)?.toUri()
                        ?.takeIf {
                            it.scheme == ContentResolver.SCHEME_CONTENT ||
                                it.scheme == ContentResolver.SCHEME_FILE
                        }
                        ?: return@future SessionResult(SessionError.ERROR_BAD_VALUE)
                    val player = session.player
                    val mediaItem = player.currentMediaItem
                        ?: return@future SessionResult(SessionError.ERROR_INVALID_STATE)
                    val hasAudio = withContext(Dispatchers.IO) {
                        runCatching {
                            contentResolver.openFileDescriptor(
                                uri,
                                "r",
                            )?.use { descriptor ->
                                MediaInfoBuilder().from(
                                    descriptor = descriptor,
                                ).build()?.let { info ->
                                    try {
                                        info.audioStreams.isNotEmpty()
                                    } finally {
                                        info.release()
                                    }
                                }
                            } == true
                        }.getOrDefault(defaultValue = false)
                    }
                    if (!hasAudio) return@future SessionResult(SessionError.ERROR_BAD_VALUE)
                    if (player.currentMediaItem?.mediaId != mediaItem.mediaId) {
                        return@future SessionResult(SessionError.ERROR_INVALID_STATE)
                    }
                    mediaRepository.addExternalAudioToMedium(
                        mediaItem.mediaId,
                        audioUri = uri,
                    )
                    val currentItem =
                        player.currentMediaItem?.takeIf { it.mediaId == mediaItem.mediaId }
                            ?: return@future SessionResult(SessionError.ERROR_INVALID_STATE)
                    val audio = (currentItem.mediaMetadata.externalAudio + uri).distinct()
                    pendingAudioSelection = currentItem.mediaId to uri
                    val updatedItem = currentItem.withExternalAudio(
                        uris = audio,
                    ).copy(positionMs = player.currentPosition)
                    val index = player.currentMediaItemIndex
                    player.addMediaItem(
                        index + 1,
                        updatedItem,
                    )
                    player.seekTo(
                        index + 1,
                        player.currentPosition,
                    )
                    player.removeMediaItem(index)
                    return@future SessionResult(SessionResult.RESULT_SUCCESS)
                }

                CustomCommands.SET_SKIP_SILENCE_ENABLED -> {
                    val enabled = args.getBoolean(CustomCommands.SKIP_SILENCE_ENABLED_KEY)
                    mediaSession?.player?.playerSpecificSkipSilenceEnabled = enabled
                    mediaSession?.sessionExtras =
                        Bundle(mediaSession?.sessionExtras ?: Bundle.EMPTY).apply {
                            putBoolean(
                                CustomCommands.SKIP_SILENCE_ENABLED_KEY,
                                enabled,
                            )
                        }
                    return@future SessionResult(SessionResult.RESULT_SUCCESS)
                }

                CustomCommands.GET_SKIP_SILENCE_ENABLED -> {
                    val enabled = mediaSession?.player?.playerSpecificSkipSilenceEnabled ?: false
                    return@future SessionResult(
                        SessionResult.RESULT_SUCCESS,
                        Bundle().apply {
                            putBoolean(
                                CustomCommands.SKIP_SILENCE_ENABLED_KEY,
                                enabled,
                            )
                        },
                    )
                }

                CustomCommands.SET_IS_SCRUBBING_MODE_ENABLED -> {
                    val enabled = args.getBoolean(CustomCommands.IS_SCRUBBING_MODE_ENABLED_KEY)
                    mediaSession?.player?.setIsScrubbingModeEnabled(enabled = enabled)
                    return@future SessionResult(SessionResult.RESULT_SUCCESS)
                }

                CustomCommands.IS_LOUDNESS_GAIN_SUPPORTED -> {
                    val isSupported = loudnessEnhancer != null
                    return@future SessionResult(
                        SessionResult.RESULT_SUCCESS,
                        Bundle().apply {
                            putBoolean(
                                CustomCommands.IS_LOUDNESS_GAIN_SUPPORTED_KEY,
                                isSupported,
                            )
                        },
                    )
                }

                CustomCommands.SET_LOUDNESS_GAIN -> {
                    val gain = args.getInt(
                        CustomCommands.LOUDNESS_GAIN_KEY,
                        0,
                    )
                    setEnhancerTargetGain(gain = gain)
                    return@future SessionResult(SessionResult.RESULT_SUCCESS)
                }

                CustomCommands.GET_LOUDNESS_GAIN -> {
                    return@future SessionResult(
                        SessionResult.RESULT_SUCCESS,
                        Bundle().apply {
                            putInt(
                                CustomCommands.LOUDNESS_GAIN_KEY,
                                currentVolumeGain,
                            )
                        },
                    )
                }

                CustomCommands.SET_VIDEO_DECODER_MODE -> {
                    val mode = args.decoderMode(CustomCommands.VIDEO_DECODER_MODE_KEY)
                        ?: return@future SessionResult(SessionResult.RESULT_ERROR_BAD_VALUE)
                    val player = mediaSession?.player as? ExoPlayer
                        ?: return@future SessionResult(SessionResult.RESULT_ERROR_INVALID_STATE)
                    decoderRecoveryManager.onUserSelection(
                        DecoderTrackType.VIDEO,
                        mode = mode,
                    )
                    selectDecoder(
                        DecoderTrackType.VIDEO,
                        mode = mode,
                    )
                    serviceScope.launch {
                        handleUnsupportedTrack(
                            player.currentTracks,
                            DecoderTrackType.VIDEO,
                        )
                    }
                    return@future SessionResult(SessionResult.RESULT_SUCCESS)
                }

                CustomCommands.SET_AUDIO_DECODER_MODE -> {
                    val mode = args.decoderMode(CustomCommands.AUDIO_DECODER_MODE_KEY)
                        ?: return@future SessionResult(SessionResult.RESULT_ERROR_BAD_VALUE)
                    val player = mediaSession?.player as? ExoPlayer
                        ?: return@future SessionResult(SessionResult.RESULT_ERROR_INVALID_STATE)
                    decoderRecoveryManager.onUserSelection(
                        DecoderTrackType.AUDIO,
                        mode = mode,
                    )
                    selectDecoder(
                        DecoderTrackType.AUDIO,
                        mode = mode,
                    )
                    serviceScope.launch {
                        handleUnsupportedTrack(
                            player.currentTracks,
                            DecoderTrackType.AUDIO,
                        )
                    }
                    return@future SessionResult(SessionResult.RESULT_SUCCESS)
                }

                CustomCommands.TRY_DECODER_FALLBACK -> {
                    val retry = decoderRecoveryManager.confirmFallback()
                        ?: return@future SessionResult(SessionResult.RESULT_ERROR_INVALID_STATE)
                    if (!retryDecoderWith(retry = retry)) {
                        decoderRecoveryManager.onNonDecoderError()
                        return@future SessionResult(SessionResult.RESULT_ERROR_INVALID_STATE)
                    }
                    return@future SessionResult(SessionResult.RESULT_SUCCESS)
                }

                CustomCommands.GET_SUBTITLE_DELAY -> {
                    val subtitleDelay =
                        mediaSession?.player?.playerSpecificSubtitleDelayMilliseconds ?: 0
                    return@future SessionResult(
                        SessionResult.RESULT_SUCCESS,
                        Bundle().apply {
                            putLong(
                                CustomCommands.SUBTITLE_DELAY_KEY,
                                subtitleDelay,
                            )
                        },
                    )
                }

                CustomCommands.SET_SUBTITLE_DELAY -> {
                    val subtitleDelay = args.getLong(CustomCommands.SUBTITLE_DELAY_KEY)
                    mediaSession?.player?.playerSpecificSubtitleDelayMilliseconds = subtitleDelay
                    return@future SessionResult(SessionResult.RESULT_SUCCESS)
                }

                CustomCommands.GET_SUBTITLE_SPEED -> {
                    val subtitleSpeed = mediaSession?.player?.playerSpecificSubtitleSpeed ?: 0f
                    return@future SessionResult(
                        SessionResult.RESULT_SUCCESS,
                        Bundle().apply {
                            putFloat(
                                CustomCommands.SUBTITLE_SPEED_KEY,
                                subtitleSpeed,
                            )
                        },
                    )
                }

                CustomCommands.SET_SUBTITLE_SPEED -> {
                    val subtitleSpeed = args.getFloat(CustomCommands.SUBTITLE_SPEED_KEY)
                    mediaSession?.player?.playerSpecificSubtitleSpeed = subtitleSpeed
                    return@future SessionResult(SessionResult.RESULT_SUCCESS)
                }

                CustomCommands.STOP_PLAYER_SESSION -> {
                    savePositionAndStopSession()
                    return@future SessionResult(SessionResult.RESULT_SUCCESS)
                }
            }
        }
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? =
        mediaSession

    /**
     * 按播放速度对齐音频管线：1 倍速直通压缩音轨（AC3 / E-AC3），其它速度解码 + 折叠 2 声道。
     *
     * 光改闸门不够。media3 不会因为变速重新决策"直通还是解码"——
     * `MediaCodecAudioRenderer.setPlaybackParameters` 只是把参数转发给 `AudioSink`
     * （已用 1.11.1 字节码确认），渲染器不会重建解码器。必须让音频渲染器重新选轨，
     * `shouldUseBypass` 才会按新闸门重算。
     *
     * 重选手段是临时禁用音轨类型。**恢复绝不能依赖 `onTracksChanged`**：
     * 目标机（TCL m7642 / Android 9）实测出现过回调不来、`currentTracks` 的 selected
     * 标志也没及时更新，结果音轨永久停在 disabled —— 表现就是"画面在走、没有声音，
     * 1 倍速也一直是立体声 PCM"。所以这里改成三道保险，且全部幂等：
     * 1. 禁用后固定 [AUDIO_RESELECT_RESTORE_MS] 无条件恢复；
     * 2. `onTracksChanged` 来了就提前恢复；
     * 3. [audioTrackWatchdog] 每 [AUDIO_WATCHDOG_INTERVAL_MS] 兜底检查。
     *
     * 另外速度回调本身也可能漏（播放器重建后 speed 回到 1.0 而闸门还停在解码态），
     * 所以还有 [enforceAudioMode] 在关键时机按当前 speed 强制对齐。
     */

    /**
     * 闸门此刻**应该**处于的状态——唯一判据。
     *
     * - 「调速时降级为 2 声道 PCM」打开：非 1 倍速且这条音轨能解码 → true
     *   （解不了就别拦，宁可速度不生效也不能静音）。
     * - 关闭（默认）：恒为 false —— 全程直通，调速不动音频通路。
     */
    private fun desiredGateDecode(): Boolean {
        if (!decodeToStereoWhenSpeeding) return false
        val player = mediaSession?.player ?: return false
        val speed = player.playbackParameters.speed
        return abs(speed - 1f) > SPEED_UNITY_EPSILON && canDecodeSelectedAudio()
    }

    private fun updateSpeedAudioMode(speed: Float, force: Boolean = false) {
        val sink = speedAudioFactory?.gateSink ?: return

        // 直通优先（2026-10-26 定案）：调速**不**再把音轨降级成 2 声道 PCM。
        //
        // 降级那条路（OFFLOAD/E_AC3 6ch → MIXER/PCM_16_BIT 2ch）在 flinger 层与
        // just-plus-player 逐位一致，但电视的 Dolby 链（Dolby bypass: 0）仍把 PCM
        // 升混重编码成杜比：功放照样显示 Atmos、听感也仍是环绕声，等于没换，
        // 还要多付一次音频渲染器重建（跨越 1 倍速时那声"闪一下立体声"）。
        //
        // 现在压缩码流一路直通到底：功放一直是真正的 5.1 / Atmos，
        // 变速交给 AudioTrack（sink 的 askPlatformSpeed）——码流无法保音调地拉伸，
        // 所以音调会变，但音画是同步的。
        //
        // 这里只负责把"上一轮遗留的 PCM 态"收回直通。
        if (sink.forceDecode) {
            AudioDiag.log("audio mode: back to pass-through at $speed")
            sink.setForceDecode(false)
            requestAudioReselect("back to passthrough")
        }
    }

    /** 事件驱动对齐：任何时刻发现闸门与当前速度不符就纠正，不依赖速度回调是否来过。 */
    private fun enforceAudioMode(reason: String) {
        val sink = speedAudioFactory?.gateSink ?: return
        val player = mediaSession?.player ?: return
        // 重选进行中不要抢跑：此刻闸门本来就可能与渲染器状态错位，纠正只会打架
        if (audioTrackDisabledByUs || audioRestartInFlight) {
            // 但卡住时必须能自愈：音轨被我们禁用后若一直没恢复，整场播放都会静音
            // （实测 22:43 那次会话音频 sink 一次都没 configure，ARC 从 22:43 起再无写入）。
            if (audioTrackDisabledByUs &&
                SystemClock.uptimeMillis() - audioTrackDisabledAtMs > AUDIO_WATCHDOG_GRACE_MS
            ) {
                restoreAudioTrackType("enforce-stuck")
            }
            return
        }
        // 「调速时降级为 2 声道 PCM」关闭（默认）：闸门不该开，只把遗留的 PCM 态收回直通。
        // 打开：按当前速度纠正闸门（这是唯一的判据，避免多个入口互相覆盖）。
        val wantDecode = desiredGateDecode()
        if (sink.forceDecode == wantDecode) return
        val now = SystemClock.uptimeMillis()
        if (now - audioEnforceAtMs < AUDIO_ENFORCE_MIN_INTERVAL_MS) return
        audioEnforceAtMs = now
        AudioDiag.log("audio mode: out of sync ($reason) gate=${sink.forceDecode}")
        updateSpeedAudioMode(player.playbackParameters.speed, force = true)
    }

    private fun requestAudioReselect(reason: String) {
        val player = mediaSession?.player as? ExoPlayer ?: return
        if (player.playbackState != Player.STATE_READY) {
            // 不能丢请求：用户常在缓冲/暂停时切速度，丢了就变成"这次调速没反应"
            AudioDiag.log("reselect deferred (state=${player.playbackState}): $reason")
            reselectHandler.removeCallbacks(audioReselectRunnable)
            reselectHandler.postDelayed(audioReselectRunnable, AUDIO_RESELECT_RETRY_MS)
            return
        }
        val now = SystemClock.uptimeMillis()
        val sinceLast = now - audioReselectScheduledAtMs
        if (sinceLast in 0 until AUDIO_MODE_MIN_INTERVAL_MS) {
            val delay = AUDIO_MODE_MIN_INTERVAL_MS - sinceLast
            reselectHandler.removeCallbacks(audioReselectRunnable)
            reselectHandler.postDelayed(audioReselectRunnable, delay)
            AudioDiag.log("reselect deferred ${delay}ms: $reason")
            return
        }
        doAudioReselect(reason)
    }

    /**
     * 延迟到期的重选：**执行前再确认一次意图**。
     *
     * 排队期间用户可能已经把速度切回去了（1.0 → 1.1 → 1.0）。若不加这一步，
     * 就会看到"先切成立体声、马上又切回透传"这种延迟一拍的怪现象。
     */
    private val audioReselectRunnable = Runnable {
        val sink = speedAudioFactory?.gateSink ?: return@Runnable
        val player = mediaSession?.player ?: return@Runnable
        val wantDecode = abs(player.playbackParameters.speed - 1f) > SPEED_UNITY_EPSILON
        if (sink.forceDecode == wantDecode && sink.isPassthrough != wantDecode) {
            AudioDiag.log("reselect dropped: already satisfied (gate=$wantDecode)")
            return@Runnable
        }
        requestAudioReselect("deferred")
    }

    /** 禁用音轨类型逼渲染器重新选轨，并安排无条件恢复与事后校验。 */
    private fun doAudioReselect(reason: String) {
        val player = mediaSession?.player as? ExoPlayer ?: return
        // 已经在重选中：再发一次只会让渲染器白重建一轮
        if (audioTrackDisabledByUs) return
        if (audioReselectCount >= AUDIO_RESELECT_MAX_PER_ITEM) {
            AudioDiag.log("audio reselect: giving up after $audioReselectCount tries ($reason)")
            return
        }
        audioReselectCount++
        val now = SystemClock.uptimeMillis()
        audioRestartInFlight = true
        audioReselectScheduledAtMs = now
        audioReselectAtMs = now
        audioTrackDisabledAtMs = now
        audioTrackDisabledByUs = true
        AudioDiag.log("audio reselect #$audioReselectCount: disable audio track ($reason)")
        player.trackSelectionParameters = player.trackSelectionParameters.buildUpon()
            .setTrackTypeDisabled(C.TRACK_TYPE_AUDIO, true)
            .build()
        reselectHandler.removeCallbacks(restoreAudioTrackRunnable)
        reselectHandler.postDelayed(restoreAudioTrackRunnable, AUDIO_RESELECT_RESTORE_MS)
        reselectHandler.removeCallbacks(audioModeVerifyRunnable)
        reselectHandler.postDelayed(audioModeVerifyRunnable, AUDIO_MODE_VERIFY_DELAY_MS)
    }

    private val restoreAudioTrackRunnable = Runnable { restoreAudioTrackType("timeout") }

    /**
     * 恢复音轨。**只认本地标志**，绝不读 player 读回来判断
     * （原因见 [audioTrackDisabledByUs]：读回值不可信，会变成 disable/enable 死循环）。
     */
    private fun restoreAudioTrackType(why: String) {
        if (!audioTrackDisabledByUs) return
        audioTrackDisabledByUs = false
        val player = mediaSession?.player as? ExoPlayer ?: return
        AudioDiag.log("audio track restored ($why)")
        player.trackSelectionParameters = player.trackSelectionParameters.buildUpon()
            .setTrackTypeDisabled(C.TRACK_TYPE_AUDIO, false)
            .build()
    }

    /** 1 倍速稳住之后才切回码流，见 [updateSpeedAudioMode]。 */
    // 显式标注 Runnable：lambda 内部要引用自己（重试排队），不标类型会触发
    // "Type checking has run into a recursive problem"。
    private val bitstreamRestoreRunnable: Runnable = Runnable {
        val sink = speedAudioFactory?.gateSink ?: return@Runnable
        val player = mediaSession?.player ?: return@Runnable
        // 排队期间用户又提速了：保持解码，什么都不做
        if (abs(player.playbackParameters.speed - 1f) > SPEED_UNITY_EPSILON) return@Runnable
        if (!sink.forceDecode) return@Runnable
        if (audioTrackDisabledByUs || audioRestartInFlight) {
            // 正卡在另一次重选里，等它落地后再由 enforceAudioMode 收尾
            reselectHandler.postDelayed(bitstreamRestoreRunnable, AUDIO_RESELECT_RESTORE_MS)
            return@Runnable
        }
        AudioDiag.log("audio mode: 1.0 稳住，切回码流")
        updateSpeedAudioMode(player.playbackParameters.speed, force = true)
    }

    /**
     * 事后校验：实际管线与速度要求不符就重试，最多 [AUDIO_MODE_MAX_RETRIES] 次。
     * 无论结论如何，先把音轨恢复掉，避免静音。
     */
    private val audioModeVerifyRunnable = Runnable {
        val sink = speedAudioFactory?.gateSink ?: return@Runnable
        val player = mediaSession?.player ?: return@Runnable
        audioRestartInFlight = false
        restoreAudioTrackType("verify")

        if (!shouldManageAudioPipeline()) return@Runnable
        if (!player.isPlaying) return@Runnable

        val speed = player.playbackParameters.speed
        val wantPassthrough = abs(speed - 1f) <= SPEED_UNITY_EPSILON
        if (sink.isPassthrough == wantPassthrough) {
            audioModeRetries = 0
            // sink speed 是音频管线**自己**认的速率：解码态由 Sonic 落地，直通态恒为 1.0。
            // 它和 player speed 不一致，就说明声音没跟上画面（音画会持续漂移）。
            AudioDiag.log(
                "audio mode: verified ${audioModeName(wantPassthrough)} at $speed " +
                    "(sink speed=${sink.playbackParameters.speed} mime=${sink.lastConfiguredMime})",
            )
            return@Runnable
        }
        if (audioModeRetries >= AUDIO_MODE_MAX_RETRIES) {
            AudioDiag.log(
                "audio mode: still ${audioModeName(sink.isPassthrough)} at $speed after " +
                    "$audioModeRetries retries, leaving it",
            )
            return@Runnable
        }
        audioModeRetries++
        AudioDiag.log(
            "audio mode: still ${audioModeName(sink.isPassthrough)} at $speed, " +
                "retry $audioModeRetries",
        )
        doAudioReselect("verify retry")
    }

    /**
     * 兜底看门狗：音轨被禁用超过 [AUDIO_WATCHDOG_GRACE_MS] 就恢复，
     * 顺带把闸门与速度重新对齐。这是"没声音"那类故障的最终保险。
     */
    private val audioTrackWatchdog = object : Runnable {
        override fun run() {
            // 只在"我们自己禁用了且超时没恢复"时动手 —— 无条件恢复会变成死循环
            if (audioTrackDisabledByUs &&
                SystemClock.uptimeMillis() - audioTrackDisabledAtMs > AUDIO_WATCHDOG_GRACE_MS
            ) {
                restoreAudioTrackType("watchdog")
            }
            // 卡死自愈：音轨组还在、也还 supported，但一条都没被选中。
            // 这种情况下音频渲染器不会 configure，整场没声音：ARC 线程 Standby、
            // Last write 停在几百秒前（实测 2026-10-26 11:21 会话，日志最后一行是
            // `tracks: audio=audio/eac3 supported=true selected=false`，之后再无 configure）。
            // 判据用 supported 排除"解码器真的不支持"那种情况——那种重选也救不回来。
            if (!audioTrackDisabledByUs && !audioRestartInFlight) {
                recoverStuckAudioTrack()
            }
            // 重选过程中不要抢跑对齐，否则闸门被来回改
            if (!audioTrackDisabledByUs && !audioRestartInFlight) {
                enforceAudioMode("watchdog")
            }
            reselectHandler.postDelayed(this, AUDIO_WATCHDOG_INTERVAL_MS)
        }
    }

    /** 音轨存在却没被选中 → 强制重选一次（限次，避免变成死循环）。 */
    private fun recoverStuckAudioTrack() {
        val player = mediaSession?.player ?: return
        if (player.playbackState != Player.STATE_READY) return
        if (audioReselectCount >= AUDIO_RESELECT_MAX_PER_ITEM) return
        val tracks = player.currentTracks ?: return
        val audioGroups = tracks.groups.filter { it.type == C.TRACK_TYPE_AUDIO }
        if (audioGroups.isEmpty()) return
        if (audioGroups.any { it.isSelected }) return
        val supported = audioGroups.any { group ->
            (0 until group.length).any { group.isTrackSupported(it) }
        }
        if (!supported) return
        AudioDiag.log("audio stuck: 音轨存在且 supported 但未被选中，强制重选")
        requestAudioReselect("stuck-audio-track")
    }

    /**
     * 当前音频轨的**源**格式 mime。
     *
     * 优先取选中轨；`isSelected` 在音轨重选、渲染器重建的瞬间可能是 false，
     * 这时退回"第一个音频组"，避免把"暂时读不到"误判成"这不是压缩音轨"。
     */
    private fun selectedAudioMime(): String? {
        val groups = mediaSession?.player?.currentTracks?.groups
            ?.filter { it.type == C.TRACK_TYPE_AUDIO }
            .orEmpty()
        if (groups.isEmpty()) return null
        val group = groups.firstOrNull { it.isSelected } ?: groups.first()
        return group.mediaTrackGroup.getFormat(0).sampleMimeType
    }

    /**
     * 这条片源是否值得按速度在「直通 / 解码折叠」之间切换。
     *
     * 判据以**实测值为主、源格式为辅**：
     * 1. 音频 sink 曾经以压缩格式 configure 过（`passthroughEverSeen`）
     *    或此刻正在直通 —— 说明这条片源本来能直通；
     * 2. 否则看源音轨 mime 是否压缩格式；读不到就**当作不需要**，绝不当成"必须关闸门"。
     *
     * 之前用 `currentTracks` 的 mime 单点判断，在重选期间读到 null 就把闸门关掉，
     * 表现就是"调速度功放完全不变"；反过来若读到 audio/raw 则永远不切。
     */
    private fun shouldManageAudioPipeline(): Boolean {
        val sink = speedAudioFactory?.gateSink ?: return false
        if (sink.passthroughEverSeen || sink.isPassthrough) return true
        val mime = selectedAudioMime() ?: return false
        return mime in PASSTHROUGH_MIME_TYPES
    }

    /** 当前解码器模式下能否解出这条音轨；解不了就不要拦直通（宁可速度不生效也不能静音）。 */
    private fun canDecodeSelectedAudio(): Boolean {
        if (decodeBlockedForItem) return false
        val mime = selectedAudioMime() ?: return true
        return canDecodeAudioMime(mime, decoderManager.audioMode)
    }

    private fun audioModeName(passthrough: Boolean): String =
        if (passthrough) "pass-through" else "decoded"

    override fun onCreate() {
        super.onCreate()
        decoderManager = DecoderManager()
        // SpeedAwareRenderersFactory 本身继承上游的 NextRenderersFactory，
        // 这里只是多挂了音频闸门 / 下混 / 解码器模式三样，不覆盖上游对渲染器的改动。
        val renderersFactory = SpeedAwareRenderersFactory(applicationContext).apply {
            setDecoderManager(decoderManager = decoderManager)
            // sink 的输出配置在建 player 时定死，所以这里是唯一能设进去的时机。
            // 改设置后重开播放器生效（设置页里已写明）。
            decodeToStereoWhenSpeeding = playerPreferences.decodeToStereoWhenSpeeding
            // 闸门只拦「真能解码」的格式：本机 ARC 不认 DTS/TrueHD，平台也没有对应解码器，
            // 拦掉它们等于把这条音轨变成静音（开关启用后有的视频没声音就是这么来的）。
            decodeCheck = { mime -> mime == null || canDecodeAudioMime(mime, decoderManager.audioMode) }
            AudioDiag.log(
                "speed audio mode: decodeToStereoWhenSpeeding=$decodeToStereoWhenSpeeding",
            )
        }
        this@PlayerService.decodeToStereoWhenSpeeding = playerPreferences.decodeToStereoWhenSpeeding
        speedAudioFactory = renderersFactory

        trackSelector = DefaultTrackSelector(applicationContext).apply {
            setParameters(
                buildUponParameters()
                    .setTunnelingEnabled(playerPreferences.enableTunneledPlayback)
                    .setPreferredAudioLanguage(playerPreferences.preferredAudioLanguage)
                    .setPreferredTextLanguage(playerPreferences.preferredSubtitleLanguage),
            )
        }

        val player = ExoPlayer.Builder(applicationContext)
            .setRenderersFactory(renderersFactory)
            .setTrackSelector(trackSelector)
            .setMediaSourceFactory(
                ExternalAudioMediaSourceFactory(
                    DefaultMediaSourceFactory(
                        applicationContext,
                    ).setDataSourceFactory(dataSourceFactory),
                ),
            )
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(C.USAGE_MEDIA)
                    .setContentType(C.AUDIO_CONTENT_TYPE_MOVIE)
                    .build(),
                playerPreferences.requireAudioFocus,
            )
            .setHandleAudioBecomingNoisy(playerPreferences.pauseOnHeadsetDisconnect)
            .build()
            .also {
                it.setPlaybackSpeed(playerPreferences.defaultPlaybackSpeed)
                it.addListener(playbackStateListener)
                it.pauseAtEndOfMediaItems = !playerPreferences.autoplay
                it.repeatMode = when (playerPreferences.loopMode) {
                    LoopMode.OFF -> Player.REPEAT_MODE_OFF
                    LoopMode.ONE -> Player.REPEAT_MODE_ONE
                    LoopMode.ALL -> Player.REPEAT_MODE_ALL
                }
            }

        decoderManager.attach(player = player)
        player.addAnalyticsListener(decoderAnalyticsListener)

        try {
            mediaSession = MediaSession.Builder(
                this,
                player,
            ).apply {
                setSessionActivity(
                    PendingIntent.getActivity(
                        this@PlayerService,
                        0,
                        Intent(
                            this@PlayerService,
                            PlayerActivity::class.java,
                        ),
                        PendingIntent.FLAG_IMMUTABLE,
                    ),
                )
                setCallback(mediaSessionCallback)
                setCustomLayout(
                    listOf(
                        CommandButton.Builder(ICON_UNDEFINED)
                            .setCustomIconResId(coreUiR.drawable.ic_close)
                            .setDisplayName(getString(coreUiR.string.stop_player_session))
                            .setSessionCommand(CustomCommands.STOP_PLAYER_SESSION.sessionCommand)
                            .setEnabled(true)
                            .build(),
                    ),
                )
            }.build()
        } catch (e: Exception) {
            e.printStackTrace()
        }

        // 音频管线看门狗：兜底恢复被我们禁用的音轨、纠正闸门与速度不一致
        reselectHandler.removeCallbacks(audioTrackWatchdog)
        reselectHandler.postDelayed(audioTrackWatchdog, AUDIO_WATCHDOG_INTERVAL_MS)
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        val player = mediaSession?.player ?: return
        if (!player.playWhenReady || player.mediaItemCount == 0 ||
            player.playbackState == Player.STATE_ENDED
        ) {
            serviceScope.launch { savePositionAndStopSession() }
        }
    }

    private suspend fun savePositionAndStopSession() {
        mediaSession?.run {
            player.currentMediaItem?.let { mediaItem ->
                mediaRepository.updateMediumPosition(
                    uri = mediaItem.mediaId,
                    position = player.currentPosition,
                )
            }
            player.clearMediaItems()
            player.stop()
            // 这里一度调过 clearVideoSurface()，想让 4K 硬解器早点释放，但本机固件上
            // MediaCodecVideoRenderer 等不到 surface 解绑（实测 22:34 在 1.4 倍速下抛
            // ERROR_CODE_TIMEOUT "Detaching surface timed out." 直接弹播放错误面），
            // 而紧接着的 onDestroy → player.release() 本来就会释放解码器，所以删掉。
        }
        stopSelf()
    }

    override fun onDestroy() {
        super.onDestroy()
        artworkLoadJob?.cancel()
        reselectHandler.removeCallbacksAndMessages(null)
        loudnessEnhancer?.release()
        loudnessEnhancer = null
        mediaSession?.run {
            player.clearMediaItems()
            player.stop()
            player.removeListener(playbackStateListener)
            decoderManager.detach()
            player.release()
            release()
            mediaSession = null
        }
        subtitleCacheDir.deleteFiles()
        // Drop the network connection before the scope dies, so a share isn't held open for the
        // lifetime of the process after playback ends.
        runBlocking { dataSourceFactory.release() }
        serviceScope.cancel()
    }

    private fun Long.validDurationOrNull(): Long? = takeIf { it != C.TIME_UNSET && it >= 0 }

    private suspend fun updatedMediaItemsWithMetadata(
        mediaItems: List<MediaItem>,
    ): List<MediaItem> = supervisorScope {
        mediaItems.map { mediaItem ->
            async {
                val uri = mediaItem.mediaId.toUri()
                val isNetwork = mediaItem.isNetworkMediaItem()

                val video = if (isNetwork) {
                    null
                } else {
                    mediaRepository.getVideoByUri(
                        uri = mediaItem.mediaId,
                    )
                }
                val videoState = mediaRepository.getVideoState(uri = mediaItem.mediaId)

                val externalSubs = videoState?.externalSubs ?: emptyList()
                val savedAudio = videoState?.externalAudio.orEmpty()
                val externalAudio = withContext(Dispatchers.IO) {
                    savedAudio.filter { uri ->
                        runCatching {
                            contentResolver.openAssetFileDescriptor(
                                uri,
                                "r",
                            )?.use { true } == true
                        }
                            .getOrDefault(defaultValue = false)
                    }
                }
                val localSubs = if (!isNetwork) {
                    (videoState?.path ?: getPath(uri = uri))?.let {
                        File(it).getLocalSubtitles(
                            context = this@PlayerService,
                            excludeSubsList = externalSubs,
                        )
                    } ?: emptyList()
                } else {
                    emptyList()
                }

                val existingSubConfigurations =
                    mediaItem.localConfiguration?.subtitleConfigurations ?: emptyList()
                val subConfigurations = (localSubs + externalSubs).map { subtitleUri ->
                    uriToSubtitleConfiguration(
                        uri = subtitleUri,
                        subtitleEncoding = playerPreferences.subtitleTextEncoding,
                    )
                }

                // Local items get a placeholder now and their real artwork in the background;
                // network items have no thumbnail to extract, so keep any supplied artwork.
                val artworkUri = if (isNetwork) {
                    mediaItem.mediaMetadata.artworkUri ?: getDefaultArtworkUri()
                } else {
                    getDefaultArtworkUri()
                }

                val title =
                    mediaItem.mediaMetadata.title ?: video?.nameWithExtension
                        ?: getFilenameFromUri(uri = uri)
                val positionMs = mediaItem.mediaMetadata.positionMs ?: videoState?.position
                val videoScale = mediaItem.mediaMetadata.videoZoom ?: videoState?.videoScale
                val audioTrackIndex =
                    mediaItem.mediaMetadata.audioTrackIndex ?: videoState?.audioTrackIndex
                val subtitleTrackIndex =
                    mediaItem.mediaMetadata.subtitleTrackIndex ?: videoState?.subtitleTrackIndex
                val subtitleDelay =
                    mediaItem.mediaMetadata.subtitleDelayMilliseconds
                        ?: videoState?.subtitleDelayMilliseconds
                val subtitleSpeed =
                    mediaItem.mediaMetadata.subtitleSpeed ?: videoState?.subtitleSpeed

                mediaItem.buildUpon().apply {
                    setSubtitleConfigurations(existingSubConfigurations + subConfigurations)
                    setMediaMetadata(
                        MediaMetadata.Builder().apply {
                            setTitle(title)
                            setArtworkUri(artworkUri)
                            setExtras(
                                positionMs = positionMs,
                                videoScale = videoScale,
                                audioTrackIndex = audioTrackIndex.takeIf {
                                    externalAudio == savedAudio
                                },
                                subtitleTrackIndex = subtitleTrackIndex,
                                subtitleDelayMilliseconds = subtitleDelay,
                                subtitleSpeed = subtitleSpeed,
                                videoDecoderMode = mediaItem.mediaMetadata.videoDecoderMode,
                                audioDecoderMode = mediaItem.mediaMetadata.audioDecoderMode,
                            )
                        }.build(),
                    )
                }.build().withExternalAudio(uris = externalAudio)
            }
        }.awaitAll()
    }

    private fun publishDecoderState() {
        val session = mediaSession ?: return
        val recoveryState = decoderRecoveryManager.state
        session.sessionExtras = Bundle(session.sessionExtras).apply {
            putString(
                CustomCommands.VIDEO_DECODER_MODE_KEY,
                decoderManager.activeVideoMode?.name,
            )
            putString(
                CustomCommands.AUDIO_DECODER_MODE_KEY,
                decoderManager.activeAudioMode?.name,
            )
            putString(
                CustomCommands.DECODER_RECOVERY_STATUS_KEY,
                recoveryState.status.name,
            )
            putString(
                CustomCommands.DECODER_RECOVERY_TRACK_TYPE_KEY,
                recoveryState.trackType?.name,
            )
            putString(
                CustomCommands.UNSUPPORTED_DECODER_MODE_KEY,
                recoveryState.unsupportedMode?.name,
            )
        }
    }

    private fun retryDecoderWith(retry: DecoderRetry): Boolean {
        val player = mediaSession?.player as? ExoPlayer ?: return false
        val shouldPrepare = player.playerError != null
        // 自动降级不写回片源，避免一次偶发失败被永久保存
        selectDecoder(retry.trackType, retry.mode, persist = false)
        if (shouldPrepare && player.mediaItemCount > 0) {
            player.prepare()
        }
        return true
    }

    private fun handleUnsupportedTracks(tracks: Tracks) {
        handleUnsupportedTrack(
            tracks = tracks,
            DecoderTrackType.VIDEO,
        )
        handleUnsupportedTrack(
            tracks = tracks,
            DecoderTrackType.AUDIO,
        )
    }

    private fun handleUnsupportedTrack(tracks: Tracks, trackType: DecoderTrackType) {
        val mediaTrackType = trackType.mediaTrackType
        val groups = tracks.groups.filter { it.type == mediaTrackType }
        val hasTrack =
            groups.isNotEmpty() || trackSelector.unmappedTrackCount(trackType = mediaTrackType) > 0
        if (!hasTrack || groups.any { it.isSupported(true) }) return

        // 本机 logcat 拿不到应用日志：把"为什么判不支持"落盘，否则只能看到最终 UI 文案
        AudioDiag.log(
            "unsupported track: type=$trackType " +
                "requestedVideo=${decoderManager.videoMode} activeVideo=${decoderManager.activeVideoMode} " +
                "requestedAudio=${decoderManager.audioMode} activeAudio=${decoderManager.activeAudioMode} " +
                "unmapped=${trackSelector.unmappedTrackCount(mediaTrackType)}",
        )
        for (group in groups) {
            for (index in 0 until group.length) {
                val format = group.getTrackFormat(index)
                AudioDiag.log(
                    "  track[$index] mime=${format.sampleMimeType} codecs=${format.codecs} " +
                        "${format.width}x${format.height} " +
                        "${format.channelCount}ch/${format.sampleRate}Hz " +
                        "supported=${group.isTrackSupported(index)} selected=${group.isTrackSelected(index)}",
                )
            }
        }

        // 音频轨"全部不支持"很可能是我们自己造成的：非 1 倍速时闸门拦掉了直通，
        // 而当前解码器模式（例如「软解+」）根本解不出这个压缩格式。
        // 这时先放开闸门（宁可速度不生效）并记下来别再试，不要弹"没有支持此媒体的解码器"。
        if (trackType == DecoderTrackType.AUDIO) {
            val sink = speedAudioFactory?.gateSink
            if (sink != null && sink.forceDecode) {
                AudioDiag.log(
                    "audio track unsupported while forced to decode, releasing the gate " +
                        "(decoder mode=${decoderManager.audioMode})",
                )
                decodeBlockedForItem = true
                sink.setForceDecode(false)
                requestAudioReselect("unsupported fallback")
                return
            }
        }

        handleDecoderFailure(trackType = trackType)
    }

    private fun handleDecoderFailure(trackType: DecoderTrackType) {
        val mode = when (trackType) {
            DecoderTrackType.VIDEO -> decoderManager.videoMode
            DecoderTrackType.AUDIO -> decoderManager.audioMode
        }
        val retry = decoderRecoveryManager.onDecoderFailure(
            trackType = trackType,
            mode = mode,
        )
        publishDecoderState()
        if (retry == null) return
        serviceScope.launch {
            if (!retryDecoderWith(retry = retry)) decoderRecoveryManager.onNonDecoderError()
        }
    }

    /**
     * 切换解码模式。
     *
     * [persist] 为 false 时只改 nextlib 侧的解码器选择，不把模式写回 MediaItem。
     * 自动降级必须走这条路径：写回的话，一次偶发失败就会把「软解+」永久钉在这部片源上，
     * 用户下次打开直接失败，看起来就是「第一次能播、之后再也播不了」。
     */
    private fun selectDecoder(
        trackType: DecoderTrackType,
        mode: DecoderMode,
        persist: Boolean = true,
    ) {
        when (trackType) {
            DecoderTrackType.VIDEO -> decoderManager.selectVideoDecoder(mode = mode)
            DecoderTrackType.AUDIO -> decoderManager.selectAudioDecoder(mode = mode)
        }
        if (persist) {
            val player = mediaSession?.player
            val mediaItem = player?.currentMediaItem
            if (mediaItem != null) {
                val updatedItem = when (trackType) {
                    DecoderTrackType.VIDEO -> mediaItem.copy(videoDecoderMode = mode)
                    DecoderTrackType.AUDIO -> mediaItem.copy(audioDecoderMode = mode)
                }
                player.replaceMediaItem(player.currentMediaItemIndex, updatedItem)
            }
        } else {
            AudioDiag.log("decoder fallback: $trackType -> $mode (not persisted)")
        }
        publishDecoderState()
    }

    private fun restoreDecoderChoices(mediaItem: MediaItem?) {
        val videoMode = mediaItem?.mediaMetadata?.videoDecoderMode ?: DecoderMode.AUTO
        val audioMode = mediaItem?.mediaMetadata?.audioDecoderMode ?: DecoderMode.AUTO
        decoderRecoveryManager.onUserSelection(
            DecoderTrackType.VIDEO,
            mode = videoMode,
        )
        decoderRecoveryManager.onUserSelection(
            DecoderTrackType.AUDIO,
            mode = audioMode,
        )
        selectDecoder(
            DecoderTrackType.VIDEO,
            mode = videoMode,
        )
        selectDecoder(
            DecoderTrackType.AUDIO,
            mode = audioMode,
        )
    }

    private fun DefaultTrackSelector.unmappedTrackCount(trackType: Int): Int {
        val trackGroups = currentMappedTrackInfo?.unmappedTrackGroups ?: return 0
        return (0 until trackGroups.length).count { index -> trackGroups[index].type == trackType }
    }

    private fun PlaybackException.decoderTrackType(): DecoderTrackType? {
        val playbackError = this as? ExoPlaybackException ?: return null
        val formatTrackType = MimeTypes.getTrackType(playbackError.rendererFormat?.sampleMimeType)
        val mediaTrackType = formatTrackType.takeIf {
            it == C.TRACK_TYPE_VIDEO || it == C.TRACK_TYPE_AUDIO
        } ?: trackSelector.currentMappedTrackInfo
            ?.takeIf { playbackError.rendererIndex in 0 until it.rendererCount }
            ?.getRendererType(playbackError.rendererIndex)

        return when (mediaTrackType) {
            C.TRACK_TYPE_VIDEO -> DecoderTrackType.VIDEO
            C.TRACK_TYPE_AUDIO -> DecoderTrackType.AUDIO
            else -> null
        }
    }

    private fun getDefaultArtworkUri(): Uri = Uri.Builder().apply {
        val defaultArtwork = R.drawable.artwork_default
        scheme(ContentResolver.SCHEME_ANDROID_RESOURCE)
        authority(resources.getResourcePackageName(defaultArtwork))
        appendPath(resources.getResourceTypeName(defaultArtwork))
        appendPath(resources.getResourceEntryName(defaultArtwork))
    }.build()

    private fun loadArtworkForCurrentMediaItem() {
        artworkLoadJob?.cancel()
        artworkLoadJob = serviceScope.launch(Dispatchers.Main) {
            val player = mediaSession?.player ?: return@launch
            val currentMediaItem = player.currentMediaItem ?: return@launch
            if (currentMediaItem.mediaMetadata.artworkData != null) return@launch

            val artworkUri = loadArtworkForMediaItem(mediaItem = currentMediaItem)
                ?: getDefaultArtworkUri()

            val updatedPlayer = mediaSession?.player ?: return@launch
            val updatedMediaItem = updatedPlayer.currentMediaItem ?: return@launch
            if (updatedMediaItem.mediaId != currentMediaItem.mediaId) return@launch

            updatedPlayer.replaceMediaItem(
                updatedPlayer.currentMediaItemIndex,
                updatedMediaItem.withArtwork(uri = artworkUri),
            )
        }
    }
    private suspend fun loadArtworkForMediaItem(mediaItem: MediaItem): Uri? =
        withContext(Dispatchers.IO) {
            val defaultArtwork = getDefaultArtworkUri()
            val uri = mediaItem.mediaMetadata.artworkUri
                ?.takeUnless { it == defaultArtwork }
                ?: if (mediaItem.isNetworkMediaItem()) {
                    return@withContext null
                } else {
                    mediaItem.mediaId.toUri()
                }
            return@withContext try {
                val request = ImageRequest.Builder(context = this@PlayerService)
                    .data(data = uri)
                    .size(
                        width = 512,
                        height = 512,
                    )
                    .build()
                imageLoader.execute(request = request)
                val diskCache = imageLoader.diskCache ?: return@withContext null
                return@withContext diskCache.openSnapshot(uri.toString())?.use { snapshot ->
                    snapshot.data.toFile().toUri()
                }
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (_: Exception) {
                null
            }
        }

    private fun MediaItem.isNetworkMediaItem(): Boolean {
        val uri = localConfiguration?.uri ?: return false
        return uri.scheme?.lowercase() in REMOTE_SCHEMES || NetworkUri.isNetworkUri(uri = uri)
    }

    private fun MediaItem.withArtwork(uri: Uri): MediaItem = buildUpon()
        .setMediaMetadata(
            mediaMetadata.buildUpon()
                .setArtworkUri(uri)
                .build(),
        )
        .build()
}

internal val PlaybackException.isDecoderFailure: Boolean
    get() = when (errorCode) {
        PlaybackException.ERROR_CODE_DECODER_INIT_FAILED,
        PlaybackException.ERROR_CODE_DECODER_QUERY_FAILED,
        PlaybackException.ERROR_CODE_DECODING_FAILED,
        PlaybackException.ERROR_CODE_DECODING_FORMAT_EXCEEDS_CAPABILITIES,
        PlaybackException.ERROR_CODE_DECODING_FORMAT_UNSUPPORTED,
        PlaybackException.ERROR_CODE_DECODING_RESOURCES_RECLAIMED,
        -> true

        else -> false
    }

private val DecoderTrackType.mediaTrackType: Int
    get() = when (this) {
        DecoderTrackType.VIDEO -> C.TRACK_TYPE_VIDEO
        DecoderTrackType.AUDIO -> C.TRACK_TYPE_AUDIO
    }

private const val DECODER_LOG_TAG = "Decoder"

/** 判定"还在 1 倍速"的容差；与 JustPlus.Player 取值一致。 */
private const val SPEED_UNITY_EPSILON = 0.005f

/** 两次管线切换之间的最小间隔，避免连按上下键时反复重选打断声音。 */
private const val AUDIO_MODE_MIN_INTERVAL_MS = 500L

/** 播放器还没到 READY 时，重选请求延后这么久再试（不能直接丢弃）。 */
private const val AUDIO_RESELECT_RETRY_MS = 400L

/**
 * 禁用音轨后多久**无条件**恢复。
 *
 * 这是"没声音"故障的核心保险：目标机上 `onTracksChanged` 有时不来、
 * `currentTracks` 的 selected 标志也不更新，靠回调恢复会让音轨永久 disabled。
 */
private const val AUDIO_RESELECT_RESTORE_MS = 250L

/** 重选完成后等多久校验实际状态（给渲染器、AudioTrack 留出初始化时间）。 */
private const val AUDIO_MODE_VERIFY_DELAY_MS = 700L

/**
 * 回到 1 倍速后等多久才切回压缩码流。
 *
 * 切回码流要重建音频渲染器（本机实测约 0.2 秒空档），功放还会重新锁定格式。
 * 用户在 1.0~1.x 之间连按速度时，每次往返都切一次就是连续的"闪一下立体声"。
 * 缓 3 秒再切：连按期间一次都不切，只在真正停下 1 倍速时才恢复 5.1 直通。
 */
private const val BITSTREAM_RESTORE_DELAY_MS = 3000L

/** 校验不通过时最多重试几次（重试会再断一次声音，不宜多）。 */
private const val AUDIO_MODE_MAX_RETRIES = 1

/** 就绪/开播后延迟多久做一次闸门对齐，等渲染器与轨道信息稳定。 */
private const val AUDIO_MODE_ALIGN_DELAY_MS = 600L

/** 看门狗轮询间隔。 */
private const val AUDIO_WATCHDOG_INTERVAL_MS = 3000L

/** 看门狗宽限：距上次重选超过这个时间仍处于禁用/错位状态就强行纠正。 */
private const val AUDIO_WATCHDOG_GRACE_MS = 2500L

/**
 * 同一片源内最多重选几次。
 *
 * 每次重选都要销毁再重建 audio renderer（连带 mediacodec 抢占）。本机实测
 * 两次以上就足以把 4K 视频解码器挤到初始化失败，弹出「没有支持此媒体的解码器」。
 * 超过上限就放弃速度切换：宁可速度不生效，也要保证画面和声音都在。
 */
private const val AUDIO_RESELECT_MAX_PER_ITEM = 6

/** [enforceAudioMode] 两次纠正之间的最小间隔，防止与看门狗互相触发形成抖动。 */
private const val AUDIO_ENFORCE_MIN_INTERVAL_MS = 1500L

/**
 * 会被直通的压缩音频格式。
 *
 * 用字面量而不是 `MimeTypes` 常量：`AUDIO_E_AC3_JOC`、`AUDIO_AC4` 等常量在部分
 * media3 版本里缺失或改名，写死字符串（与 mp4/mkv 提取器实际给出的 mime 一致）最稳。
 */
private val PASSTHROUGH_MIME_TYPES = setOf(
    "audio/ac3",
    "audio/eac3",
    "audio/eac3-joc",
    "audio/vnd.dts",
    "audio/vnd.dts.hd",
    "audio/true-hd",
    "audio/ac4",
)

@get:UnstableApi
@set:UnstableApi
private var Player.playerSpecificSkipSilenceEnabled: Boolean
    @OptIn(UnstableApi::class)
    get() = when (this) {
        is ExoPlayer -> this.skipSilenceEnabled
        else -> false
    }
    set(value) {
        when (this) {
            is ExoPlayer -> this.skipSilenceEnabled = value
        }
    }

@get:UnstableApi
@set:UnstableApi
private var Player.playerSpecificSubtitleDelayMilliseconds: Long
    @OptIn(UnstableApi::class)
    get() = when (this) {
        is ExoPlayer -> this.subtitleDelayMilliseconds
        else -> 0L
    }
    set(value) {
        when (this) {
            is ExoPlayer -> this.subtitleDelayMilliseconds = value
        }
    }

@get:UnstableApi
@set:UnstableApi
private var Player.playerSpecificSubtitleSpeed: Float
    @OptIn(UnstableApi::class)
    get() = when (this) {
        is ExoPlayer -> this.subtitleSpeed
        else -> 0f
    }
    set(value) {
        when (this) {
            is ExoPlayer -> this.subtitleSpeed = value
        }
    }

/** Schemes Media3 streams over the network itself, as opposed to reading from local storage. */
private val REMOTE_SCHEMES = setOf(
    "http",
    "https",
    "rtsp",
)
