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
import dev.anilbeesetti.nextplayer.feature.player.service.audio.SpeedAwareRenderersFactory
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

    private val serviceScope: CoroutineScope = CoroutineScope(Dispatchers.Main + SupervisorJob())
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

    private lateinit var decoderManager: DecoderManager
    private lateinit var trackSelector: DefaultTrackSelector
    private var speedAudioFactory: SpeedAwareRenderersFactory? = null
    private val reselectHandler = Handler(Looper.getMainLooper())
    private val decoderRecoveryManager = DecoderRecoveryManager()

    // 音频管线切换（直通 <-> 解码）的状态。切换是一次异步重选：禁用音轨 -> 等
    // onTracksChanged -> 恢复音轨 -> 延迟校验实际状态，不成立就重试。
    private var audioModePending = false
    private var audioModePendingSpeed = 1f
    private var audioModeRetries = 0
    private var audioModeSwitchMs = 0L
    private var audioRestartInFlight = false

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
                DECODER_LOG_TAG,
                "Video decoder initialized with requested=${decoderManager.videoMode} " +
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
                DECODER_LOG_TAG,
                "Audio decoder initialized with requested=${decoderManager.audioMode} " +
                    "as ${decoderManager.activeAudioMode}: $decoderName",
            )
            decoderRecoveryManager.onDecoderInitialized(DecoderTrackType.AUDIO)
        }

        override fun onTracksChanged(
            eventTime: AnalyticsListener.EventTime,
            tracks: Tracks,
        ) {
            val videoTracks = tracks.groups
                .filter { it.type == C.TRACK_TYPE_VIDEO }
                .joinToString { group ->
                    val mimeType = group.mediaTrackGroup.getFormat(0).sampleMimeType
                    "$mimeType(supported=${group.isSupported(true)}, selected=${group.isSelected})"
                }
            Logger.logInfo(
                DECODER_LOG_TAG,
                "Video tracks: ${videoTracks.ifEmpty { "none" }}, " +
                    "unmapped=${trackSelector.unmappedTrackCount(C.TRACK_TYPE_VIDEO)}",
            )
        }

        override fun onRenderedFirstFrame(
            eventTime: AnalyticsListener.EventTime,
            output: Any,
            renderTimeMs: Long,
        ) {
            Logger.logInfo(
                DECODER_LOG_TAG,
                "Rendered first frame with video=${decoderManager.activeVideoMode}",
            )
        }

        override fun onPlayerError(
            eventTime: AnalyticsListener.EventTime,
            error: PlaybackException,
        ) {
            Logger.logError(
                DECODER_LOG_TAG,
                "Player error with requestedVideo=${decoderManager.videoMode}, " +
                    "activeVideo=${decoderManager.activeVideoMode}, " +
                    "requestedAudio=${decoderManager.audioMode}, " +
                    "activeAudio=${decoderManager.activeAudioMode}: ${error.message}",
            )
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
            super.onMediaItemTransition(mediaItem, reason)
            if (reason == Player.MEDIA_ITEM_TRANSITION_REASON_REPEAT) return
            restoreDecoderChoices(mediaItem)
            isMediaItemReady = false
            loadArtworkForCurrentMediaItem()
            mediaItem?.mediaMetadata?.let { metadata ->
                mediaSession?.player?.run {
                    playerSpecificSubtitleDelayMilliseconds = metadata.subtitleDelayMilliseconds ?: 0L
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
            super.onPositionDiscontinuity(oldPosition, newPosition, reason)
            val oldMediaItem = oldPosition.mediaItem ?: return

            when (reason) {
                DISCONTINUITY_REASON_SEEK,
                DISCONTINUITY_REASON_AUTO_TRANSITION,
                -> {
                    if (newPosition.mediaItem == null || oldMediaItem == newPosition.mediaItem) return

                    val updatedPosition = oldPosition.positionMs.takeIf { reason == DISCONTINUITY_REASON_SEEK } ?: C.TIME_UNSET
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
                        val isAtEnd = durationMs != null && oldPosition.positionMs >= durationMs - 1000
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
            // 我们自己禁用音轨引发的轨道变化：恢复音轨、补上速度、安排校验。
            // 这一次变化不代表用户换了片源，所以不再往下走其余逻辑。
            if (handleAudioReselectTracksChanged()) return
            serviceScope.launch {
                mediaSession?.player?.currentTracks?.let(::handleUnsupportedTracks)
            }
            if (!isMediaItemReady && tracks.groups.isNotEmpty()) {
                isMediaItemReady = true

                val player = mediaSession?.player ?: return
                val pending = pendingAudioSelection
                pendingAudioSelection = null
                if (pending != null && pending.first == player.currentMediaItem?.mediaId) {
                    val audioIndex = player.currentMediaItem?.mediaMetadata?.externalAudio.orEmpty().indexOf(pending.second)
                    val trackIndex = tracks.groups.filter { it.type == C.TRACK_TYPE_AUDIO }
                        .indexOfFirst { it.mediaTrackGroup.externalAudioIndex == audioIndex }
                    if (audioIndex >= 0 && trackIndex >= 0) player.switchTrack(C.TRACK_TYPE_AUDIO, trackIndex)
                }
                if (!playerPreferences.rememberSelections) return
                mediaSession?.player?.mediaMetadata?.audioTrackIndex?.takeIf { pending == null }?.let {
                    mediaSession?.player?.switchTrack(C.TRACK_TYPE_AUDIO, it)
                }
                mediaSession?.player?.mediaMetadata?.subtitleTrackIndex?.let {
                    mediaSession?.player?.switchTrack(C.TRACK_TYPE_TEXT, it)
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
            handleDecoderFailure(trackType)
        }

        override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
            super.onPlayWhenReadyChanged(playWhenReady, reason)

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
                !events.containsAny(Player.EVENT_MEDIA_ITEM_TRANSITION, Player.EVENT_PLAYBACK_STATE_CHANGED)
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
            if (!playerPreferences.enableVolumeBoost) return
            if (audioSessionId == C.AUDIO_SESSION_ID_UNSET) return
            try {
                loudnessEnhancer?.release()
                loudnessEnhancer = LoudnessEnhancer(audioSessionId)
                if (currentVolumeGain > 0) {
                    setEnhancerTargetGain(currentVolumeGain)
                }
            } catch (e: Exception) {
                e.printStackTrace()
                loudnessEnhancer = null
            }
        }
    }

    private fun setEnhancerTargetGain(gain: Int) {
        val enhancer = loudnessEnhancer ?: return

        try {
            enhancer.setTargetGain(gain)
            enhancer.enabled = gain > 0
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
        ): ListenableFuture<MediaSession.MediaItemsWithStartPosition> = serviceScope.future(Dispatchers.Default) {
            val updatedMediaItems = updatedMediaItemsWithMetadata(mediaItems)
            return@future MediaSession.MediaItemsWithStartPosition(updatedMediaItems, startIndex, startPositionMs)
        }

        override fun onAddMediaItems(
            mediaSession: MediaSession,
            controller: MediaSession.ControllerInfo,
            mediaItems: MutableList<MediaItem>,
        ): ListenableFuture<MutableList<MediaItem>> = serviceScope.future(Dispatchers.Default) {
            val updatedMediaItems = updatedMediaItemsWithMetadata(mediaItems)
            return@future updatedMediaItems.toMutableList()
        }

        override fun onCustomCommand(
            session: MediaSession,
            controller: MediaSession.ControllerInfo,
            customCommand: SessionCommand,
            args: Bundle,
        ): ListenableFuture<SessionResult> = serviceScope.future {
            val command = CustomCommands.fromSessionCommand(customCommand)
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
                        player.addAdditionalSubtitleConfiguration(newSubConfiguration)
                    }
                    return@future SessionResult(SessionResult.RESULT_SUCCESS)
                }

                CustomCommands.ADD_AUDIO_TRACK -> {
                    val uri = args.getString(CustomCommands.AUDIO_TRACK_URI_KEY)?.toUri()
                        ?.takeIf { it.scheme == ContentResolver.SCHEME_CONTENT || it.scheme == ContentResolver.SCHEME_FILE }
                        ?: return@future SessionResult(SessionError.ERROR_BAD_VALUE)
                    val player = session.player
                    val mediaItem = player.currentMediaItem
                        ?: return@future SessionResult(SessionError.ERROR_INVALID_STATE)
                    val hasAudio = withContext(Dispatchers.IO) {
                        runCatching {
                            contentResolver.openFileDescriptor(uri, "r")?.use { descriptor ->
                                MediaInfoBuilder().from(descriptor).build()?.let { info ->
                                    try {
                                        info.audioStreams.isNotEmpty()
                                    } finally {
                                        info.release()
                                    }
                                }
                            } == true
                        }.getOrDefault(false)
                    }
                    if (!hasAudio) return@future SessionResult(SessionError.ERROR_BAD_VALUE)
                    if (player.currentMediaItem?.mediaId != mediaItem.mediaId) {
                        return@future SessionResult(SessionError.ERROR_INVALID_STATE)
                    }
                    mediaRepository.addExternalAudioToMedium(mediaItem.mediaId, uri)
                    val currentItem = player.currentMediaItem?.takeIf { it.mediaId == mediaItem.mediaId }
                        ?: return@future SessionResult(SessionError.ERROR_INVALID_STATE)
                    val audio = (currentItem.mediaMetadata.externalAudio + uri).distinct()
                    pendingAudioSelection = currentItem.mediaId to uri
                    val updatedItem = currentItem.withExternalAudio(audio).copy(positionMs = player.currentPosition)
                    val index = player.currentMediaItemIndex
                    player.addMediaItem(index + 1, updatedItem)
                    player.seekTo(index + 1, player.currentPosition)
                    player.removeMediaItem(index)
                    return@future SessionResult(SessionResult.RESULT_SUCCESS)
                }

                CustomCommands.SET_SKIP_SILENCE_ENABLED -> {
                    val enabled = args.getBoolean(CustomCommands.SKIP_SILENCE_ENABLED_KEY)
                    mediaSession?.player?.playerSpecificSkipSilenceEnabled = enabled
                    mediaSession?.sessionExtras = Bundle(mediaSession?.sessionExtras ?: Bundle.EMPTY).apply {
                        putBoolean(CustomCommands.SKIP_SILENCE_ENABLED_KEY, enabled)
                    }
                    return@future SessionResult(SessionResult.RESULT_SUCCESS)
                }

                CustomCommands.GET_SKIP_SILENCE_ENABLED -> {
                    val enabled = mediaSession?.player?.playerSpecificSkipSilenceEnabled ?: false
                    return@future SessionResult(
                        SessionResult.RESULT_SUCCESS,
                        Bundle().apply {
                            putBoolean(CustomCommands.SKIP_SILENCE_ENABLED_KEY, enabled)
                        },
                    )
                }

                CustomCommands.SET_IS_SCRUBBING_MODE_ENABLED -> {
                    val enabled = args.getBoolean(CustomCommands.IS_SCRUBBING_MODE_ENABLED_KEY)
                    mediaSession?.player?.setIsScrubbingModeEnabled(enabled)
                    return@future SessionResult(SessionResult.RESULT_SUCCESS)
                }

                CustomCommands.IS_LOUDNESS_GAIN_SUPPORTED -> {
                    val isSupported = loudnessEnhancer != null
                    return@future SessionResult(
                        SessionResult.RESULT_SUCCESS,
                        Bundle().apply {
                            putBoolean(CustomCommands.IS_LOUDNESS_GAIN_SUPPORTED_KEY, isSupported)
                        },
                    )
                }

                CustomCommands.SET_LOUDNESS_GAIN -> {
                    val gain = args.getInt(CustomCommands.LOUDNESS_GAIN_KEY, 0)
                    setEnhancerTargetGain(gain)
                    return@future SessionResult(SessionResult.RESULT_SUCCESS)
                }

                CustomCommands.GET_LOUDNESS_GAIN -> {
                    return@future SessionResult(
                        SessionResult.RESULT_SUCCESS,
                        Bundle().apply {
                            putInt(CustomCommands.LOUDNESS_GAIN_KEY, currentVolumeGain)
                        },
                    )
                }

                CustomCommands.SET_VIDEO_DECODER_MODE -> {
                    val mode = args.decoderMode(CustomCommands.VIDEO_DECODER_MODE_KEY)
                        ?: return@future SessionResult(SessionResult.RESULT_ERROR_BAD_VALUE)
                    val player = mediaSession?.player as? ExoPlayer
                        ?: return@future SessionResult(SessionResult.RESULT_ERROR_INVALID_STATE)
                    decoderRecoveryManager.onUserSelection(DecoderTrackType.VIDEO, mode)
                    selectDecoder(DecoderTrackType.VIDEO, mode)
                    serviceScope.launch { handleUnsupportedTrack(player.currentTracks, DecoderTrackType.VIDEO) }
                    return@future SessionResult(SessionResult.RESULT_SUCCESS)
                }

                CustomCommands.SET_AUDIO_DECODER_MODE -> {
                    val mode = args.decoderMode(CustomCommands.AUDIO_DECODER_MODE_KEY)
                        ?: return@future SessionResult(SessionResult.RESULT_ERROR_BAD_VALUE)
                    val player = mediaSession?.player as? ExoPlayer
                        ?: return@future SessionResult(SessionResult.RESULT_ERROR_INVALID_STATE)
                    decoderRecoveryManager.onUserSelection(DecoderTrackType.AUDIO, mode)
                    selectDecoder(DecoderTrackType.AUDIO, mode)
                    serviceScope.launch { handleUnsupportedTrack(player.currentTracks, DecoderTrackType.AUDIO) }
                    return@future SessionResult(SessionResult.RESULT_SUCCESS)
                }

                CustomCommands.TRY_DECODER_FALLBACK -> {
                    val retry = decoderRecoveryManager.confirmFallback()
                        ?: return@future SessionResult(SessionResult.RESULT_ERROR_INVALID_STATE)
                    if (!retryDecoderWith(retry)) {
                        decoderRecoveryManager.onNonDecoderError()
                        return@future SessionResult(SessionResult.RESULT_ERROR_INVALID_STATE)
                    }
                    return@future SessionResult(SessionResult.RESULT_SUCCESS)
                }

                CustomCommands.GET_SUBTITLE_DELAY -> {
                    val subtitleDelay = mediaSession?.player?.playerSpecificSubtitleDelayMilliseconds ?: 0
                    return@future SessionResult(
                        SessionResult.RESULT_SUCCESS,
                        Bundle().apply {
                            putLong(CustomCommands.SUBTITLE_DELAY_KEY, subtitleDelay)
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
                            putFloat(CustomCommands.SUBTITLE_SPEED_KEY, subtitleSpeed)
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

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? = mediaSession

    /**
     * 播放速度跨过 1 倍时切换音频管线：
     * 1 倍速把压缩音轨（AC3 / E-AC3）直通给功放，其它速度解码成 PCM 并折叠 2 声道。
     *
     * 光改闸门不够。media3 不会因为变速重新决策"直通还是解码"——
     * `MediaCodecAudioRenderer.setPlaybackParameters` 只是把参数转发给 `AudioSink`
     * （已用 1.11.1 字节码确认），渲染器不会重建解码器。所以必须把音轨类型禁用再恢复，
     * 让音频渲染器重新选轨、`shouldUseBypass` 按新闸门重算。
     *
     * 这里用 [Player.Listener.onTracksChanged] 当"重选完成"的信号，而不是定时盲等；
     * 完成后 [AUDIO_MODE_VERIFY_DELAY_MS] 再校验一次**实际**状态
     * （[PassthroughGateAudioSink.isPassthrough]），不符就重试，最多 [AUDIO_MODE_MAX_RETRIES] 次。
     * 这套闭环照 JustPlus.Player（com.brouken.player.PlayerActivity）的做法，比开环等待可靠。
     */
    private fun updateSpeedAudioMode(speed: Float) {
        val sink = speedAudioFactory?.gateSink ?: return
        val player = mediaSession?.player as? ExoPlayer ?: return

        val wantPassthrough = abs(speed - 1f) <= SPEED_UNITY_EPSILON
        // 闸门同值说明还停在同一侧，管线不用动
        if (!sink.setForceDecode(!wantPassthrough)) return
        // 实际状态已经等于目标态（例如片源本身是 PCM），不必重选
        if (sink.isPassthrough == wantPassthrough) {
            audioModePending = false
            Logger.logInfo(
                AUDIO_LOG_TAG,
                "audio mode: already ${audioModeName(wantPassthrough)} at $speed",
            )
            return
        }

        audioModeRetries = 0
        audioModePending = true
        audioModePendingSpeed = speed
        Logger.logInfo(
            AUDIO_LOG_TAG,
            "audio mode: switching to ${audioModeName(wantPassthrough)} at $speed " +
                "(playing=${player.isPlaying})",
        )

        // 没在播放：等真正开播时的 onTracksChanged 再补速度，避免停着反复重选
        if (!player.isPlaying) return

        val now = SystemClock.uptimeMillis()
        val sinceLast = now - audioModeSwitchMs
        if (sinceLast < AUDIO_MODE_MIN_INTERVAL_MS) {
            // 连按上下键时把重选延后到最小间隔之后，避免一路重选把声音打断
            val delay = AUDIO_MODE_MIN_INTERVAL_MS - sinceLast
            reselectHandler.removeCallbacks(audioModeReselectRunnable)
            reselectHandler.postDelayed(audioModeReselectRunnable, delay)
            Logger.logInfo(AUDIO_LOG_TAG, "audio mode: reselect in ${delay}ms")
            return
        }
        reselectAudioTrackForMode()
    }

    private val audioModeReselectRunnable = Runnable { reselectAudioTrackForMode() }

    /** 禁用音轨类型逼渲染器重新选轨；结果由 [handleAudioReselectTracksChanged] 接手。 */
    private fun reselectAudioTrackForMode() {
        if (audioRestartInFlight) return
        val player = mediaSession?.player as? ExoPlayer ?: return
        audioRestartInFlight = true
        audioModeSwitchMs = SystemClock.uptimeMillis()
        Logger.logInfo(AUDIO_LOG_TAG, "audio mode: reselect")
        player.trackSelectionParameters = player.trackSelectionParameters.buildUpon()
            .setTrackTypeDisabled(C.TRACK_TYPE_AUDIO, true)
            .build()
    }

    /**
     * @return true 表示这次轨道变化是我们自己引起的重选，调用方应跳过其余处理。
     */
    private fun handleAudioReselectTracksChanged(): Boolean {
        if (!audioRestartInFlight) return false
        audioRestartInFlight = false
        val player = mediaSession?.player as? ExoPlayer ?: return true
        Logger.logInfo(AUDIO_LOG_TAG, "audio reselect: restore")
        player.trackSelectionParameters = player.trackSelectionParameters.buildUpon()
            .setTrackTypeDisabled(C.TRACK_TYPE_AUDIO, false)
            .build()
        if (audioModePending) {
            audioModePending = false
            Logger.logInfo(AUDIO_LOG_TAG, "audio mode: rate $audioModePendingSpeed after reselect")
            player.playbackParameters = PlaybackParameters(audioModePendingSpeed)
        }
        reselectHandler.removeCallbacks(audioModeVerifyRunnable)
        reselectHandler.postDelayed(audioModeVerifyRunnable, AUDIO_MODE_VERIFY_DELAY_MS)
        return true
    }

    /**
     * 校验实际音频管线是否符合当前速度的要求，不符则重试。
     *
     * 只在选中音轨是压缩格式时才有意义：PCM 片源永远不会直通，
     * 拿它去比对会白重试两次（JustPlus 同样先看 selected mime）。
     */
    private val audioModeVerifyRunnable = Runnable {
        val sink = speedAudioFactory?.gateSink ?: return@Runnable
        val player = mediaSession?.player ?: return@Runnable

        if (audioRestartInFlight) {
            // onTracksChanged 一直没来，音轨可能还挂在被禁用的状态，强行恢复再试一次
            Logger.logInfo(AUDIO_LOG_TAG, "audio mode: reselect never reported, releasing it")
            audioRestartInFlight = false
            if (!player.currentTracks.isTypeSelected(C.TRACK_TYPE_AUDIO)) {
                (player as? ExoPlayer)?.let { exoPlayer ->
                    exoPlayer.trackSelectionParameters = exoPlayer.trackSelectionParameters
                        .buildUpon()
                        .setTrackTypeDisabled(C.TRACK_TYPE_AUDIO, false)
                        .build()
                }
            }
            reselectHandler.removeCallbacks(audioModeReselectRunnable)
            reselectHandler.postDelayed(audioModeReselectRunnable, AUDIO_MODE_VERIFY_DELAY_MS)
            return@Runnable
        }

        val selectedMime = player.currentTracks.groups
            .filter { it.type == C.TRACK_TYPE_AUDIO }
            .firstOrNull { it.isSelected }
            ?.mediaTrackGroup?.getFormat(0)?.sampleMimeType
        if (selectedMime == null || selectedMime == MimeTypes.AUDIO_RAW) return@Runnable

        val speed = player.playbackParameters.speed
        val wantPassthrough = abs(speed - 1f) <= SPEED_UNITY_EPSILON
        if (sink.isPassthrough == wantPassthrough || !player.isPlaying) return@Runnable

        if (audioModeRetries >= AUDIO_MODE_MAX_RETRIES) {
            Logger.logInfo(
                AUDIO_LOG_TAG,
                "audio mode: still ${audioModeName(!wantPassthrough)} at $speed after " +
                    "$audioModeRetries retries, leaving it",
            )
            return@Runnable
        }
        audioModeRetries++
        Logger.logInfo(
            AUDIO_LOG_TAG,
            "audio mode: still ${audioModeName(!wantPassthrough)} at $speed, retry $audioModeRetries",
        )
        audioModePending = true
        audioModePendingSpeed = speed
        audioModeSwitchMs = SystemClock.uptimeMillis()
        reselectAudioTrackForMode()
    }

    private fun audioModeName(passthrough: Boolean): String =
        if (passthrough) "pass-through" else "decoded"

    override fun onCreate() {
        super.onCreate()
        decoderManager = DecoderManager()
        val renderersFactory = SpeedAwareRenderersFactory(applicationContext).apply {
            setDecoderManager(decoderManager)
        }
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
                    DefaultMediaSourceFactory(applicationContext).setDataSourceFactory(dataSourceFactory),
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

        decoderManager.attach(player)
        player.addAnalyticsListener(decoderAnalyticsListener)

        try {
            mediaSession = MediaSession.Builder(this, player).apply {
                setSessionActivity(
                    PendingIntent.getActivity(
                        this@PlayerService,
                        0,
                        Intent(this@PlayerService, PlayerActivity::class.java),
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
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        val player = mediaSession?.player ?: return
        if (!player.playWhenReady || player.mediaItemCount == 0 || player.playbackState == Player.STATE_ENDED) {
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
        }
        stopSelf()
    }

    override fun onDestroy() {
        super.onDestroy()
        artworkLoadJob?.cancel()
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

                val video = if (isNetwork) null else mediaRepository.getVideoByUri(uri = mediaItem.mediaId)
                val videoState = mediaRepository.getVideoState(uri = mediaItem.mediaId)

                val externalSubs = videoState?.externalSubs ?: emptyList()
                val savedAudio = videoState?.externalAudio.orEmpty()
                val externalAudio = withContext(Dispatchers.IO) {
                    savedAudio.filter { uri ->
                        runCatching { contentResolver.openAssetFileDescriptor(uri, "r")?.use { true } == true }
                            .getOrDefault(false)
                    }
                }
                val localSubs = if (!isNetwork) {
                    (videoState?.path ?: getPath(uri))?.let {
                        File(it).getLocalSubtitles(
                            context = this@PlayerService,
                            excludeSubsList = externalSubs,
                        )
                    } ?: emptyList()
                } else {
                    emptyList()
                }

                val existingSubConfigurations = mediaItem.localConfiguration?.subtitleConfigurations ?: emptyList()
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

                val title = mediaItem.mediaMetadata.title ?: video?.nameWithExtension ?: getFilenameFromUri(uri)
                val positionMs = mediaItem.mediaMetadata.positionMs ?: videoState?.position
                val videoScale = mediaItem.mediaMetadata.videoZoom ?: videoState?.videoScale
                val audioTrackIndex = mediaItem.mediaMetadata.audioTrackIndex ?: videoState?.audioTrackIndex
                val subtitleTrackIndex = mediaItem.mediaMetadata.subtitleTrackIndex ?: videoState?.subtitleTrackIndex
                val subtitleDelay = mediaItem.mediaMetadata.subtitleDelayMilliseconds ?: videoState?.subtitleDelayMilliseconds
                val subtitleSpeed = mediaItem.mediaMetadata.subtitleSpeed ?: videoState?.subtitleSpeed

                mediaItem.buildUpon().apply {
                    setSubtitleConfigurations(existingSubConfigurations + subConfigurations)
                    setMediaMetadata(
                        MediaMetadata.Builder().apply {
                            setTitle(title)
                            setArtworkUri(artworkUri)
                            setExtras(
                                positionMs = positionMs,
                                videoScale = videoScale,
                                audioTrackIndex = audioTrackIndex.takeIf { externalAudio == savedAudio },
                                subtitleTrackIndex = subtitleTrackIndex,
                                subtitleDelayMilliseconds = subtitleDelay,
                                subtitleSpeed = subtitleSpeed,
                                videoDecoderMode = mediaItem.mediaMetadata.videoDecoderMode,
                                audioDecoderMode = mediaItem.mediaMetadata.audioDecoderMode,
                            )
                        }.build(),
                    )
                }.build().withExternalAudio(externalAudio)
            }
        }.awaitAll()
    }

    private fun publishDecoderState() {
        val session = mediaSession ?: return
        val recoveryState = decoderRecoveryManager.state
        session.sessionExtras = Bundle(session.sessionExtras).apply {
            putString(CustomCommands.VIDEO_DECODER_MODE_KEY, decoderManager.activeVideoMode?.name)
            putString(CustomCommands.AUDIO_DECODER_MODE_KEY, decoderManager.activeAudioMode?.name)
            putString(CustomCommands.DECODER_RECOVERY_STATUS_KEY, recoveryState.status.name)
            putString(CustomCommands.DECODER_RECOVERY_TRACK_TYPE_KEY, recoveryState.trackType?.name)
            putString(CustomCommands.UNSUPPORTED_DECODER_MODE_KEY, recoveryState.unsupportedMode?.name)
        }
    }

    private fun retryDecoderWith(retry: DecoderRetry): Boolean {
        val player = mediaSession?.player as? ExoPlayer ?: return false
        val shouldPrepare = player.playerError != null
        selectDecoder(retry.trackType, retry.mode)
        if (shouldPrepare && player.mediaItemCount > 0) {
            player.prepare()
        }
        return true
    }

    private fun handleUnsupportedTracks(tracks: Tracks) {
        handleUnsupportedTrack(tracks, DecoderTrackType.VIDEO)
        handleUnsupportedTrack(tracks, DecoderTrackType.AUDIO)
    }

    private fun handleUnsupportedTrack(tracks: Tracks, trackType: DecoderTrackType) {
        val mediaTrackType = trackType.mediaTrackType
        val groups = tracks.groups.filter { it.type == mediaTrackType }
        val hasTrack = groups.isNotEmpty() || trackSelector.unmappedTrackCount(mediaTrackType) > 0
        if (!hasTrack || groups.any { it.isSupported(true) }) return

        handleDecoderFailure(trackType)
    }

    private fun handleDecoderFailure(trackType: DecoderTrackType) {
        val mode = when (trackType) {
            DecoderTrackType.VIDEO -> decoderManager.videoMode
            DecoderTrackType.AUDIO -> decoderManager.audioMode
        }
        val retry = decoderRecoveryManager.onDecoderFailure(trackType, mode)
        publishDecoderState()
        if (retry == null) return
        serviceScope.launch {
            if (!retryDecoderWith(retry)) decoderRecoveryManager.onNonDecoderError()
        }
    }

    private fun selectDecoder(trackType: DecoderTrackType, mode: DecoderMode) {
        when (trackType) {
            DecoderTrackType.VIDEO -> decoderManager.selectVideoDecoder(mode)
            DecoderTrackType.AUDIO -> decoderManager.selectAudioDecoder(mode)
        }
        val player = mediaSession?.player
        val mediaItem = player?.currentMediaItem
        if (mediaItem != null) {
            val updatedItem = when (trackType) {
                DecoderTrackType.VIDEO -> mediaItem.copy(videoDecoderMode = mode)
                DecoderTrackType.AUDIO -> mediaItem.copy(audioDecoderMode = mode)
            }
            player.replaceMediaItem(player.currentMediaItemIndex, updatedItem)
        }
        publishDecoderState()
    }

    private fun restoreDecoderChoices(mediaItem: MediaItem?) {
        val videoMode = mediaItem?.mediaMetadata?.videoDecoderMode ?: DecoderMode.AUTO
        val audioMode = mediaItem?.mediaMetadata?.audioDecoderMode ?: DecoderMode.AUTO
        decoderRecoveryManager.onUserSelection(DecoderTrackType.VIDEO, videoMode)
        decoderRecoveryManager.onUserSelection(DecoderTrackType.AUDIO, audioMode)
        selectDecoder(DecoderTrackType.VIDEO, videoMode)
        selectDecoder(DecoderTrackType.AUDIO, audioMode)
    }

    private fun DefaultTrackSelector.unmappedTrackCount(trackType: Int): Int {
        val trackGroups = currentMappedTrackInfo?.unmappedTrackGroups ?: return 0
        return (0 until trackGroups.length).count { index ->
            trackGroups[index].type == trackType
        }
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

            val artworkUri = loadArtworkForMediaItem(currentMediaItem)
                ?: getDefaultArtworkUri()

            val updatedPlayer = mediaSession?.player ?: return@launch
            val updatedMediaItem = updatedPlayer.currentMediaItem ?: return@launch
            if (updatedMediaItem.mediaId != currentMediaItem.mediaId) return@launch

            updatedPlayer.replaceMediaItem(
                updatedPlayer.currentMediaItemIndex,
                updatedMediaItem.withArtwork(artworkUri),
            )
        }
    }
    private suspend fun loadArtworkForMediaItem(mediaItem: MediaItem): Uri? = withContext(Dispatchers.IO) {
        val defaultArtwork = getDefaultArtworkUri()
        val uri = mediaItem.mediaMetadata.artworkUri
            ?.takeUnless { it == defaultArtwork }
            ?: if (mediaItem.isNetworkMediaItem()) {
                return@withContext null
            } else {
                mediaItem.mediaId.toUri()
            }
        return@withContext try {
            val request = ImageRequest.Builder(this@PlayerService)
                .data(uri)
                .size(512, 512)
                .build()
            imageLoader.execute(request)
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
        return uri.scheme?.lowercase() in REMOTE_SCHEMES || NetworkUri.isNetworkUri(uri)
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

private const val AUDIO_LOG_TAG = "AudioMode"

/** 判定"还在 1 倍速"的容差；与 JustPlus.Player 取值一致。 */
private const val SPEED_UNITY_EPSILON = 0.005f

/** 两次管线切换之间的最小间隔，避免连按上下键时反复重选打断声音。 */
private const val AUDIO_MODE_MIN_INTERVAL_MS = 900L

/** 重选完成后等多久校验实际状态（给渲染器、AudioTrack 留出初始化时间）。 */
private const val AUDIO_MODE_VERIFY_DELAY_MS = 700L

/** 校验不通过时最多重试几次。 */
private const val AUDIO_MODE_MAX_RETRIES = 2

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
private val REMOTE_SCHEMES = setOf("http", "https", "rtsp")

/**
 * 变速切换音频管线时，禁用音轨与恢复音轨之间的间隔。
 * 需要让 ExoPlayer 的播放线程把两次 TrackSelectionParameters 变更分两轮处理，
 * 否则会被合并成一次，音轨从未真正被禁用，解码器也就不会重建。
 */
