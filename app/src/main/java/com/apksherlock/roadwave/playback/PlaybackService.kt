package com.apksherlock.roadwave.playback

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.support.v4.media.session.MediaSessionCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.net.toUri
import androidx.media3.common.AudioAttributes
import androidx.media3.common.ForwardingPlayer
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.CommandButton
import androidx.media3.session.LibraryResult
import androidx.media3.session.MediaLibraryService
import androidx.media3.session.MediaSession
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionError
import androidx.media3.session.SessionResult
import com.apksherlock.roadwave.MainActivity
import com.apksherlock.roadwave.R
import com.apksherlock.roadwave.data.SongRepository
import com.google.common.collect.ImmutableList
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import com.google.common.util.concurrent.SettableFuture
import kotlinx.coroutines.*
import java.io.File

class PlaybackService : MediaLibraryService() {

    private var mediaSession: MediaLibrarySession? = null
    private lateinit var repository: SongRepository
    private val serviceScope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    private val repeatCommand = SessionCommand("ACTION_REPEAT", Bundle.EMPTY)

    companion object {
        var mediaSessionToken: MediaSessionCompat.Token? = null
        private const val NOTIFICATION_CHANNEL_ID = "roadwave_playback"
        private const val NOTIFICATION_ID = 1
    }

    /**
     * Promotes this service to foreground before any song is playing, not just once
     * playback starts. Android Auto binds this service during session negotiation —
     * before the user has picked a track — and without foreground-service protection
     * during that window, OEM background-process management can evict the process
     * under memory pressure regardless of battery-optimization settings, causing the
     * connection to time out. This mirrors how other car-integrated apps (e.g. Waze's
     * CarAppService, which declares foregroundServiceType="location") stay alive.
     *
     * Wrapped defensively: Android 12+ can refuse a foreground-service start from a
     * background trigger. If that happens here, the service simply continues without
     * the extra protection rather than crashing onCreate().
     */
    // Intentionally broad: see the catch below — the point is to survive and name any
    // refusal, not to enumerate a set that has changed with every platform release.
    @Suppress("TooGenericExceptionCaught")
    private fun promoteToForegroundImmediately() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                NOTIFICATION_CHANNEL_ID,
                "Playback",
                NotificationManager.IMPORTANCE_LOW
            )
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }

        val notification = NotificationCompat.Builder(this, NOTIFICATION_CHANNEL_ID)
            .setContentTitle(getString(R.string.app_name))
            .setContentText("Ready")
            .setSmallIcon(R.drawable.ic_attribution)
            .setOngoing(true)
            .build()

        // Catching Exception rather than IllegalStateException alone: the background-start
        // restrictions have grown a family of refusals across releases and not all of them
        // are ISE subclasses (Android 14+ can raise SecurityException for a foreground
        // service type it considers unjustified). Any of them is survivable here — the
        // service just runs without the extra protection — but losing which one fired
        // costs another drive, so the exception class is logged explicitly.
        try {
            ServiceCompat.startForeground(
                this,
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK
            )
            carLogI("PlaybackService promoted to foreground in onCreate()")
        } catch (e: Exception) {
            carLogE("startForeground() refused (${e.javaClass.name}) — service continues unprotected", e)
            carLogFlush("foreground promotion refused")
        }
    }

    /**
     * Every media browser and every MediaController connection lands here first, so this
     * distinguishes "Android Auto never asked for the library" from "it asked and we
     * answered wrongly" — indistinguishable from the car's error screen alone.
     */
    override fun onBind(intent: Intent?): android.os.IBinder? {
        carLogI("PlaybackService.onBind ${describeIntent(intent)}")
        return super.onBind(intent)
    }

    override fun onUnbind(intent: Intent?): Boolean {
        carLogW("PlaybackService.onUnbind ${describeIntent(intent)}")
        return super.onUnbind(intent)
    }

    @androidx.annotation.OptIn(UnstableApi::class)
    @OptIn(UnstableApi::class)
    override fun onCreate() {
        val start = SystemClock.elapsedRealtime()
        super.onCreate()
        promoteToForegroundImmediately()
        repository = SongRepository(this)
        val exoPlayer = ExoPlayer.Builder(this)
            .setAudioAttributes(AudioAttributes.DEFAULT, true)
            .setHandleAudioBecomingNoisy(true)
            .build()

        // Default: repeat the whole queue so Next remains available at the end.
        exoPlayer.repeatMode = Player.REPEAT_MODE_ALL

        exoPlayer.addListener(object : Player.Listener {
            override fun onEvents(player: Player, events: Player.Events) {
                if (events.containsAny(
                        Player.EVENT_REPEAT_MODE_CHANGED,
                        Player.EVENT_TIMELINE_CHANGED
                    )
                ) {
                    updateRepeatButton()
                }
            }

            // Not currently logged anywhere else — if a track fails to decode/load (a real
            // possibility if file access behaves differently in the car's process context),
            // this would previously fail completely silently from our own diagnostics' view.
            override fun onPlayerError(error: androidx.media3.common.PlaybackException) {
                carLogE("ExoPlayer error: ${error.errorCodeName}", error)
            }

            override fun onPlaybackStateChanged(playbackState: Int) {
                val stateName = when (playbackState) {
                    Player.STATE_IDLE -> "IDLE"
                    Player.STATE_BUFFERING -> "BUFFERING"
                    Player.STATE_READY -> "READY"
                    Player.STATE_ENDED -> "ENDED"
                    else -> "UNKNOWN($playbackState)"
                }
                carLogD("ExoPlayer playbackState=$stateName")
            }
        })

        // ExoPlayer's own seekToNext()/getAvailableCommands() treat REPEAT_MODE_ONE the
        // same as REPEAT_MODE_OFF for navigation purposes (it only auto-loops the current
        // item, it doesn't count as a wrap target for a manual "next"). At the last track
        // with REPEAT_MODE_ONE that makes COMMAND_SEEK_TO_NEXT unavailable, and the
        // notification/Android Auto controls then promote the repeat custom-layout button
        // into the now-empty Next slot. Wrap the queue manually here so Next/Previous stay
        // available regardless of repeatMode — repeatMode itself is untouched, so the UI,
        // the Android Auto repeat button and updateRepeatButton() all keep working as-is.
        val player = object : ForwardingPlayer(exoPlayer) {
            override fun getAvailableCommands(): Player.Commands {
                val commands = super.getAvailableCommands()
                if (mediaItemCount <= 1) return commands
                return commands.buildUpon()
                    .add(COMMAND_SEEK_TO_NEXT)
                    .add(COMMAND_SEEK_TO_NEXT_MEDIA_ITEM)
                    .add(COMMAND_SEEK_TO_PREVIOUS)
                    .add(COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM)
                    .build()
            }

            override fun isCommandAvailable(command: Int): Boolean =
                getAvailableCommands().contains(command)

            override fun seekToNext() = seekToNextMediaItem()

            override fun seekToNextMediaItem() {
                if (mediaItemCount == 0) return
                seekTo((currentMediaItemIndex + 1) % mediaItemCount, 0L)
            }

            override fun seekToPrevious() = seekToPreviousMediaItem()

            override fun seekToPreviousMediaItem() {
                if (mediaItemCount == 0) return
                seekTo((currentMediaItemIndex - 1 + mediaItemCount) % mediaItemCount, 0L)
            }
        }

        val intent = Intent(this, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        mediaSession = MediaLibrarySession.Builder(this, player, LibraryCallback())
            .setSessionActivity(pendingIntent)
            .build()

        // getSessionCompatToken() is deprecated in favour of getPlatformToken(), but the
        // replacement is not equivalent here. MediaPlaybackManager.registerMediaPlaybackToken()
        // takes a MediaSessionCompat.Token, and rebuilding one from the platform token
        // (MediaSessionCompat.Token.fromToken(getPlatformToken())) drops the extra binder that
        // the compat token carries for legacy controllers — media3 derives the platform token by
        // unwrapping the compat one, so the round trip is lossy. Android Auto's media surface is
        // exactly the legacy consumer that binder serves, so the deprecated call is the correct
        // one until the car-app library accepts a platform token.
        @Suppress("DEPRECATION")
        mediaSessionToken = mediaSession?.getSessionCompatToken()
        updateRepeatButton()
        carLogI(
            "PlaybackService.onCreate() done in ${SystemClock.elapsedRealtime() - start}ms, " +
                "sessionToken=${if (mediaSessionToken != null) "ready" else "NULL"}"
        )
        // An empty library renders as "No songs found" in the car, which looks identical
        // to a load failure from the driver's seat. Record the count once so the two can
        // be told apart without guessing.
        serviceScope.launch {
            runCatching { repository.getSongs().size to repository.getPlaylists().size }
                .onSuccess { (songs, playlists) -> carLogI("Library: $songs song(s), $playlists playlist(s)") }
                .onFailure { carLogE("Library probe failed", it) }
        }
    }

    @androidx.annotation.OptIn(UnstableApi::class)
    @OptIn(UnstableApi::class)
    private fun updateRepeatButton() {
        val player = mediaSession?.player ?: return
        val isRepeatOne = player.repeatMode == Player.REPEAT_MODE_ONE

        // CommandButton.Builder() + setIconResId(customVectorRes) asks the host (Android
        // Auto) to resolve and rasterize our own vector drawable across process boundaries,
        // which isn't reliable — it showed up in the car as a generic fallback glyph
        // instead of the actual icon. CommandButton's built-in ICON_REPEAT_ALL/ONE
        // constants use the host's own native, correctly-themed icon assets instead, so
        // there's nothing to cross-process-load in the first place.
        val repeatButton = CommandButton.Builder(
            if (isRepeatOne) CommandButton.ICON_REPEAT_ONE else CommandButton.ICON_REPEAT_ALL
        )
            .setSessionCommand(repeatCommand)
            .setDisplayName(if (isRepeatOne) "Repeat Track" else "Repeat List")
            .setEnabled(true)
            .build()

        mediaSession?.setCustomLayout(
            ImmutableList.of(repeatButton)
        )
    }

    private inner class LibraryCallback : MediaLibrarySession.Callback {
        @UnstableApi
        override fun onConnect(
            session: MediaSession,
            controller: MediaSession.ControllerInfo
        ): MediaSession.ConnectionResult {
            carLogI(
                "onConnect from package=${controller.packageName} uid=${controller.uid} " +
                    "controllerVersion=${controller.controllerVersion}"
            )
            val sessionCommands =
                MediaSession.ConnectionResult.DEFAULT_SESSION_COMMANDS
                    .buildUpon()
                    .add(repeatCommand)
                    .build()

            val playerCommands =
                MediaSession.ConnectionResult.DEFAULT_PLAYER_COMMANDS.buildUpon()
                    .add(Player.COMMAND_SEEK_TO_NEXT)
                    .add(Player.COMMAND_SEEK_TO_PREVIOUS)
                    .build()

            return MediaSession.ConnectionResult.accept(sessionCommands, playerCommands)
        }

        @UnstableApi
        override fun onCustomCommand(
            session: MediaSession,
            controller: MediaSession.ControllerInfo,
            customCommand: SessionCommand,
            args: Bundle
        ): ListenableFuture<SessionResult> {
            if (customCommand.customAction == "ACTION_REPEAT") {
                val player = session.player

                player.repeatMode = when (player.repeatMode) {
                    Player.REPEAT_MODE_ONE -> Player.REPEAT_MODE_ALL
                    else -> Player.REPEAT_MODE_ONE
                }

                updateRepeatButton()

                return Futures.immediateFuture(
                    SessionResult(SessionResult.RESULT_SUCCESS)
                )
            }

            return Futures.immediateFuture(
                SessionResult(SessionError.ERROR_NOT_SUPPORTED)
            )
        }

        override fun onGetLibraryRoot(
            session: MediaLibrarySession,
            browser: MediaSession.ControllerInfo,
            params: LibraryParams?
        ): ListenableFuture<LibraryResult<MediaItem>> {
            carLogI("onGetLibraryRoot from package=${browser.packageName} params=${params?.extras?.keySet()}")
            val rootItem = MediaItem.Builder()
                .setMediaId("root")
                .setMediaMetadata(
                    MediaMetadata.Builder()
                        .setIsBrowsable(true)
                        .setIsPlayable(false)
                        .setTitle("Root")
                        .build()
                )
                .build()
            return Futures.immediateFuture(LibraryResult.ofItem(rootItem, params))
        }

        override fun onAddMediaItems(
            mediaSession: MediaSession,
            controller: MediaSession.ControllerInfo,
            mediaItems: MutableList<MediaItem>
        ): ListenableFuture<MutableList<MediaItem>> {
            val start = SystemClock.elapsedRealtime()
            carLogD("onAddMediaItems: resolving ${mediaItems.size} item(s)")
            val future = SettableFuture.create<MutableList<MediaItem>>()
            serviceScope.launch {
                // One getSongs() call for the whole batch, not one per item — the previous
                // per-item runBlocking() call synchronously stalled the session callback
                // thread once per track, which is cheap on a fast device and potentially
                // very much not on constrained real head-unit hardware.
                val songs = repository.getSongs()
                val updatedItems = mediaItems.map { item ->
                    val song = songs.find { it.id == item.mediaId }
                    if (song != null) {
                        item.buildUpon()
                            .setUri(Uri.fromFile(File(song.mediaPath)))
                            .setMediaMetadata(
                                MediaMetadata.Builder()
                                    .setTitle(song.title)
                                    .setArtist(song.artist)
                                    .setIsBrowsable(false)
                                    .setIsPlayable(true)
                                    .setArtworkUri(
                                        "android.resource://com.apksherlock.roadwave/drawable/art_placeholder".toUri()
                                    )
                                    .build()
                            )
                            .build()
                    } else {
                        item
                    }
                }.toMutableList()
                carLogD("onAddMediaItems: resolved in ${SystemClock.elapsedRealtime() - start}ms")
                future.set(updatedItems)
            }
            return future
        }

        override fun onGetChildren(
            session: MediaLibrarySession,
            browser: MediaSession.ControllerInfo,
            parentId: String,
            page: Int,
            pageSize: Int,
            params: LibraryParams?
        ): ListenableFuture<LibraryResult<ImmutableList<MediaItem>>> {
            val start = SystemClock.elapsedRealtime()
            carLogD("onGetChildren: parentId=$parentId")
            val future = SettableFuture.create<LibraryResult<ImmutableList<MediaItem>>>()
            serviceScope.launch {
                val mediaItems = when (parentId) {
                    "root" -> {
                        listOf(
                            MediaItem.Builder()
                                .setMediaId("all_songs")
                                .setMediaMetadata(
                                    MediaMetadata.Builder()
                                        .setIsBrowsable(true)
                                        .setIsPlayable(false)
                                        .setTitle("All Songs")
                                        .build()
                                )
                                .build(),
                            MediaItem.Builder()
                                .setMediaId("playlists")
                                .setMediaMetadata(
                                    MediaMetadata.Builder()
                                        .setIsBrowsable(true)
                                        .setIsPlayable(false)
                                        .setTitle("Playlists")
                                        .build()
                                )
                                .build()
                        )
                    }
                    "all_songs" -> {
                        repository.getSongs().map { song ->
                            MediaItem.Builder()
                                .setMediaId(song.id)
                                .setUri(Uri.fromFile(File(song.mediaPath)))
                                .setMediaMetadata(
                                    MediaMetadata.Builder()
                                        .setIsBrowsable(false)
                                        .setIsPlayable(true)
                                        .setTitle(song.title)
                                        .setArtist(song.artist)
                                        .setArtworkUri(
                                            "android.resource://com.apksherlock.roadwave/drawable/art_placeholder".toUri()
                                        )
                                        .build()
                                )
                                .build()
                        }
                    }
                    "playlists" -> {
                        repository.getPlaylists().map { playlist ->
                            MediaItem.Builder()
                                .setMediaId("playlist_${playlist.id}")
                                .setMediaMetadata(
                                    MediaMetadata.Builder()
                                        .setIsBrowsable(true)
                                        .setIsPlayable(false)
                                        .setTitle(playlist.name)
                                        .build()
                                )
                                .build()
                        }
                    }
                    else -> {
                        if (parentId.startsWith("playlist_")) {
                            val playlistId = parentId.removePrefix("playlist_")
                            val playlist = repository.getPlaylists().find { it.id == playlistId }
                            val allSongs = repository.getSongs()
                            playlist?.songIds?.mapNotNull { songId ->
                                allSongs.find { it.id == songId }?.let { song ->
                                    MediaItem.Builder()
                                        .setMediaId(song.id)
                                        .setUri(Uri.fromFile(File(song.mediaPath)))
                                        .setMediaMetadata(
                                            MediaMetadata.Builder()
                                                .setIsBrowsable(false)
                                                .setIsPlayable(true)
                                                .setTitle(song.title)
                                                .setArtist(song.artist)
                                                .setArtworkUri("android.resource://com.apksherlock.roadwave/drawable/art_placeholder".toUri())
                                                .build()
                                        )
                                        .build()
                                }
                            } ?: emptyList()
                        } else {
                            emptyList()
                        }
                    }
                }
                carLogD(
                    "onGetChildren: parentId=$parentId resolved ${mediaItems.size} item(s) in " +
                        "${SystemClock.elapsedRealtime() - start}ms"
                )
                future.set(LibraryResult.ofItemList(mediaItems, params))
            }
            return future
        }
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaLibrarySession? {
        return mediaSession
    }

    override fun onDestroy() {
        // If this fires shortly after onCreate() with no user action in between, that's the
        // process being evicted — the exact failure mode the foreground-service fix targets.
        carLogW("PlaybackService.onDestroy()")
        serviceScope.cancel()
        mediaSession?.run {
            player.release()
            release()
            mediaSession = null
        }
        super.onDestroy()
    }
}
