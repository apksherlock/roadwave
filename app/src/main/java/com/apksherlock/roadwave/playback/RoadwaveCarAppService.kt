package com.apksherlock.roadwave.playback

import android.content.ComponentName
import android.support.v4.media.session.MediaSessionCompat
import android.util.Log
import androidx.car.app.CarAppService
import androidx.car.app.CarContext
import androidx.car.app.Screen
import androidx.car.app.ScreenManager
import androidx.car.app.Session
import androidx.car.app.annotations.ExperimentalCarApi
import androidx.car.app.media.MediaPlaybackManager
import androidx.car.app.model.*
import androidx.car.app.validation.HostValidator
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.apksherlock.roadwave.data.SongRepository
import com.google.common.util.concurrent.ListenableFuture
import com.google.common.util.concurrent.MoreExecutors
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Minimum Car App API level at which [androidx.car.app.media.model.MediaPlaybackTemplate]
 * is available. Below this the host will reject the template, so [PlaybackScreen]
 * falls back to a plain [ListTemplate].
 */
const val MEDIA_PLAYBACK_TEMPLATE_API_LEVEL = 8

/** Single tag so a whole session can be filtered with `adb logcat -s RoadwaveCar`. */
const val CAR_LOG_TAG = "RoadwaveCar"

class RoadwaveCarAppService : CarAppService() {
    @Suppress("PrivateResource") // deliberate: see the allowlist branch below
    override fun createHostValidator(): HostValidator {
        // ALLOW_ALL_HOSTS_VALIDATOR only satisfies a real host's binding check when the
        // app is debuggable — a production Android Auto host (a real car) refuses to bind
        // to a release build that declares it, which looks like the app just hanging. Use
        // the car-app library's bundled allowlist (signatures for Google's official hosts:
        // the Android Auto phone app, Android Automotive, and the Desktop Head Unit) for
        // release builds instead, and keep ALLOW_ALL only for debug convenience.
        return if ((applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE) != 0) {
            HostValidator.ALLOW_ALL_HOSTS_VALIDATOR
        } else {
            HostValidator.Builder(applicationContext)
                .addAllowedHosts(androidx.car.app.R.array.hosts_allowlist_sample)
                .build()
        }
    }

    @androidx.annotation.OptIn(UnstableApi::class)
    @OptIn(ExperimentalCarApi::class, UnstableApi::class)
    override fun onCreateSession(): Session {
        Log.i(CAR_LOG_TAG, "onCreateSession() — CarAppService reached by a host")
        return RoadwaveCarSession()
    }
}

@androidx.annotation.OptIn(ExperimentalCarApi::class, UnstableApi::class)
@OptIn(ExperimentalCarApi::class, UnstableApi::class)
class RoadwaveCarSession : Session() {

    private var tokenControllerFuture: ListenableFuture<MediaController>? = null

    init {
        lifecycle.addObserver(
            LifecycleEventObserver { _, event ->
                when (event) {
                    Lifecycle.Event.ON_CREATE -> {
                        logHostDiagnostics()
                        registerPlaybackToken()
                    }
                    Lifecycle.Event.ON_DESTROY -> {
                        tokenControllerFuture?.let { MediaController.releaseFuture(it) }
                        tokenControllerFuture = null
                    }
                    else -> Unit
                }
            }
        )
    }

    /**
     * Dumps everything needed to tell whether the host is the culprit. Compare
     * `carAppApiLevel` here against `androidx.car.app.minCarApiLevel` in the manifest.
     *
     * Both the Desktop Head Unit and a real head unit are driven by the *same*
     * Android Auto host app on the phone, so this value is expected to be identical
     * in both cases. If it is, API-level filtering is not why the app is missing
     * from the car and the cause lies elsewhere (developer mode / unknown sources,
     * template beta gating, or manifest completeness).
     */
    private fun logHostDiagnostics() {
        val declaredMin = runCatching {
            val appInfo = carContext.packageManager.getApplicationInfo(
                carContext.packageName,
                android.content.pm.PackageManager.GET_META_DATA
            )
            appInfo.metaData?.getInt("androidx.car.app.minCarApiLevel", -1) ?: -1
        }.getOrDefault(-1)

        val actualLevel = runCatching { carContext.carAppApiLevel }.getOrDefault(-1)

        Log.i(CAR_LOG_TAG, "===== Roadwave car host diagnostics =====")
        Log.i(CAR_LOG_TAG, "host package        : ${runCatching { carContext.hostInfo?.packageName }.getOrNull()}")
        Log.i(CAR_LOG_TAG, "host uid            : ${runCatching { carContext.hostInfo?.uid }.getOrNull()}")
        Log.i(CAR_LOG_TAG, "carAppApiLevel      : $actualLevel")
        Log.i(CAR_LOG_TAG, "manifest minCarApi  : $declaredMin")
        Log.i(
            CAR_LOG_TAG,
            "MediaPlaybackTemplate supported: " +
                "${actualLevel >= MEDIA_PLAYBACK_TEMPLATE_API_LEVEL} (needs >= $MEDIA_PLAYBACK_TEMPLATE_API_LEVEL)"
        )
        if (actualLevel in 0 until MEDIA_PLAYBACK_TEMPLATE_API_LEVEL) {
            Log.w(
                CAR_LOG_TAG,
                "Host is below level $MEDIA_PLAYBACK_TEMPLATE_API_LEVEL — PlaybackScreen will use the ListTemplate fallback."
            )
        }
        Log.i(CAR_LOG_TAG, "========================================")
    }

    /**
     * Registers the media session token with the host.
     *
     * The token must exist before a [androidx.car.app.media.model.MediaPlaybackTemplate]
     * is sent, otherwise the host renders an error. Reading
     * [PlaybackService.mediaSessionToken] directly is unreliable because the service
     * may not have been created yet at this point in the session lifecycle — so we
     * bind a [MediaController] first, which forces the service through `onCreate()`,
     * and only then read the token.
     */
    private fun registerPlaybackToken() {
        val existing = PlaybackService.mediaSessionToken
        if (existing != null) {
            registerToken(existing, "static field (service already running)")
            return
        }

        Log.d(CAR_LOG_TAG, "Media session token not ready — binding a MediaController to start PlaybackService")
        val sessionToken = SessionToken(carContext, ComponentName(carContext, PlaybackService::class.java))
        val future = MediaController.Builder(carContext, sessionToken).buildAsync()
        tokenControllerFuture = future
        future.addListener({
            runCatching { future.get() }
                .onFailure { Log.e(CAR_LOG_TAG, "Failed to connect MediaController for token registration", it) }
                .onSuccess {
                    val token = PlaybackService.mediaSessionToken
                    if (token != null) {
                        registerToken(token, "MediaController connection")
                    } else {
                        Log.e(
                            CAR_LOG_TAG,
                            "PlaybackService connected but mediaSessionToken is still null — " +
                                "MediaPlaybackTemplate will show an error in the car."
                        )
                    }
                }
        }, MoreExecutors.directExecutor())
    }

    private fun registerToken(token: MediaSessionCompat.Token, source: String) {
        runCatching {
            @OptIn(ExperimentalCarApi::class)
            (carContext.getCarService(CarContext.MEDIA_PLAYBACK_SERVICE) as MediaPlaybackManager)
                .registerMediaPlaybackToken(token)
        }.onSuccess {
            Log.i(CAR_LOG_TAG, "registerMediaPlaybackToken() succeeded via $source")
        }.onFailure {
            Log.e(CAR_LOG_TAG, "registerMediaPlaybackToken() failed via $source", it)
        }
    }

    override fun onCreateScreen(intent: android.content.Intent): Screen {
        Log.d(CAR_LOG_TAG, "onCreateScreen action=${intent.action}")
        val rootScreen = MainCarScreen(carContext)
        handlePlaybackIntent(intent)
        return rootScreen
    }

    override fun onNewIntent(intent: android.content.Intent) {
        Log.d(CAR_LOG_TAG, "onNewIntent action=${intent.action}")
        handlePlaybackIntent(intent)
    }

    private fun handlePlaybackIntent(intent: android.content.Intent) {
        val action = intent.action
        if (action == "androidx.car.app.media.action.SHOW_MEDIA_PLAYBACK" ||
            action == "android.intent.action.VIEW" ||
            action == "android.media.action.DISPLAY_AUDIO_CONTROL"
        ) {
            val screenManager = carContext.getCarService(ScreenManager::class.java)
            if (screenManager.top !is PlaybackScreen) {
                screenManager.push(PlaybackScreen(carContext))
            }
        }
    }
}

class MainCarScreen(carContext: CarContext) : Screen(carContext) {
    override fun onGetTemplate(): Template {
        val listBuilder = ItemList.Builder()
            .addItem(
                Row.Builder()
                    .setTitle("All Songs")
                    .addText("Browse your library")
                    .setOnClickListener { screenManager.push(SongsScreen(carContext)) }
                    .build()
            )
            .addItem(
                Row.Builder()
                    .setTitle("Playlists")
                    .addText("Your collections")
                    .setOnClickListener { screenManager.push(PlaylistsScreen(carContext)) }
                    .build()
            )

        return ListTemplate.Builder()
            .setSingleList(listBuilder.build())
            .setHeader(Header.Builder().setTitle("Roadwave").setStartHeaderAction(Action.APP_ICON).build())
            .build()
    }
}

class SongsScreen(carContext: CarContext) : Screen(carContext) {
    private val repository = SongRepository(carContext)
    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private var songs: List<com.apksherlock.roadwave.model.Song> = emptyList()
    private var controllerFuture: ListenableFuture<MediaController>? = null

    init {
        val sessionToken = SessionToken(carContext, ComponentName(carContext, PlaybackService::class.java))
        controllerFuture = MediaController.Builder(carContext, sessionToken).buildAsync()

        scope.launch {
            songs = repository.getSongs()
            invalidate()
        }
    }

    override fun onGetTemplate(): Template {
        val listBuilder = ItemList.Builder()
        if (songs.isEmpty()) {
            listBuilder.addItem(Row.Builder().setTitle("No songs found").build())
        } else {
            songs.forEachIndexed { index, song ->
                listBuilder.addItem(
                    Row.Builder()
                        .setTitle(song.title)
                        .addText(song.artist)
                        .setOnClickListener {
                            playMediaItem(index)
                            screenManager.push(PlaybackScreen(carContext))
                        }
                        .build()
                )
            }
        }

        return ListTemplate.Builder()
            .setSingleList(listBuilder.build())
            .setHeader(Header.Builder().setTitle("All Songs").setStartHeaderAction(Action.BACK).build())
            .build()
    }

    private fun playMediaItem(startIndex: Int) {
        controllerFuture?.addListener({
            val controller = controllerFuture?.get()
            val mediaItems = songs.map { song ->
                androidx.media3.common.MediaItem.Builder()
                    .setMediaId(song.id)
                    .build()
            }
            controller?.setMediaItems(mediaItems, startIndex, 0L)
            controller?.prepare()
            controller?.play()
        }, MoreExecutors.directExecutor())
    }
}

class PlaylistsScreen(carContext: CarContext) : Screen(carContext) {
    private val repository = SongRepository(carContext)
    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private var playlists: List<com.apksherlock.roadwave.model.Playlist> = emptyList()

    init {
        scope.launch {
            playlists = repository.getPlaylists()
            invalidate()
        }
    }

    override fun onGetTemplate(): Template {
        val listBuilder = ItemList.Builder()
        if (playlists.isEmpty()) {
            listBuilder.addItem(Row.Builder().setTitle("No playlists found").build())
        } else {
            playlists.forEach { playlist ->
                listBuilder.addItem(
                    Row.Builder()
                        .setTitle(playlist.name)
                        .addText("${playlist.songIds.size} songs")
                        .setOnClickListener {
                            screenManager.push(PlaylistDetailScreen(carContext, playlist))
                        }
                        .build()
                )
            }
        }

        return ListTemplate.Builder()
            .setSingleList(listBuilder.build())
            .setHeader(Header.Builder().setTitle("Playlists").setStartHeaderAction(Action.BACK).build())
            .build()
    }
}

class PlaylistDetailScreen(carContext: CarContext, private val playlist: com.apksherlock.roadwave.model.Playlist) : Screen(
    carContext
) {
    private val repository = SongRepository(carContext)
    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private var playlistSongs: List<com.apksherlock.roadwave.model.Song> = emptyList()
    private var controllerFuture: ListenableFuture<MediaController>? = null

    init {
        val sessionToken = SessionToken(carContext, ComponentName(carContext, PlaybackService::class.java))
        controllerFuture = MediaController.Builder(carContext, sessionToken).buildAsync()

        scope.launch {
            val allSongs = repository.getSongs()
            playlistSongs = playlist.songIds.mapNotNull { id -> allSongs.find { it.id == id } }
            invalidate()
        }
    }

    override fun onGetTemplate(): Template {
        val listBuilder = ItemList.Builder()
        if (playlistSongs.isEmpty()) {
            listBuilder.addItem(Row.Builder().setTitle("Playlist is empty").build())
        } else {
            playlistSongs.forEachIndexed { index, song ->
                listBuilder.addItem(
                    Row.Builder()
                        .setTitle(song.title)
                        .addText(song.artist)
                        .setOnClickListener {
                            playMediaItem(index)
                            screenManager.push(PlaybackScreen(carContext))
                        }
                        .build()
                )
            }
        }

        return ListTemplate.Builder()
            .setSingleList(listBuilder.build())
            .setHeader(Header.Builder().setTitle(playlist.name).setStartHeaderAction(Action.BACK).build())
            .build()
    }

    private fun playMediaItem(startIndex: Int) {
        controllerFuture?.addListener({
            val controller = controllerFuture?.get()
            val mediaItems = playlistSongs.map { song ->
                androidx.media3.common.MediaItem.Builder()
                    .setMediaId(song.id)
                    .build()
            }
            controller?.setMediaItems(mediaItems, startIndex, 0L)
            controller?.prepare()
            controller?.play()
        }, MoreExecutors.directExecutor())
    }
}

/**
 * Now-playing screen.
 *
 * [androidx.car.app.media.model.MediaPlaybackTemplate] is only available from Car App
 * API level [MEDIA_PLAYBACK_TEMPLATE_API_LEVEL]. Rather than hard-requiring that level
 * in the manifest — which makes the whole app ineligible on any host that negotiates
 * lower — this screen probes the level the host actually reported at runtime and falls
 * back to a self-rendered [ListTemplate] built from the [MediaController] state.
 */
@androidx.annotation.OptIn(ExperimentalCarApi::class, UnstableApi::class)
@OptIn(ExperimentalCarApi::class, UnstableApi::class)
class PlaybackScreen(carContext: CarContext) : Screen(carContext) {

    private var controllerFuture: ListenableFuture<MediaController>? = null
    private var controller: MediaController? = null

    /**
     * True when the connected host can render MediaPlaybackTemplate. Resolved once,
     * defensively: if `carAppApiLevel` throws (it can on a partially-initialised
     * CarContext) we assume unsupported and take the fallback path rather than crash.
     */
    private val supportsMediaPlaybackTemplate: Boolean by lazy {
        val level = runCatching { carContext.carAppApiLevel }.getOrDefault(-1)
        val supported = level >= MEDIA_PLAYBACK_TEMPLATE_API_LEVEL
        Log.i(
            CAR_LOG_TAG,
            "PlaybackScreen: carAppApiLevel=$level supportsMediaPlaybackTemplate=$supported"
        )
        supported
    }

    init {
        // Only needed to drive the fallback UI; the host populates MediaPlaybackTemplate
        // itself from the registered session token.
        if (!supportsMediaPlaybackTemplate) {
            val sessionToken = SessionToken(carContext, ComponentName(carContext, PlaybackService::class.java))
            val future = MediaController.Builder(carContext, sessionToken).buildAsync()
            controllerFuture = future
            future.addListener({
                runCatching { future.get() }
                    .onSuccess {
                        controller = it
                        it.addListener(object : androidx.media3.common.Player.Listener {
                            override fun onEvents(
                                player: androidx.media3.common.Player,
                                events: androidx.media3.common.Player.Events
                            ) {
                                invalidate()
                            }
                        })
                        invalidate()
                    }
                    .onFailure { Log.e(CAR_LOG_TAG, "PlaybackScreen: MediaController connect failed", it) }
            }, MoreExecutors.directExecutor())
        }

        lifecycle.addObserver(
            LifecycleEventObserver { _, event ->
                if (event == Lifecycle.Event.ON_DESTROY) {
                    controllerFuture?.let { MediaController.releaseFuture(it) }
                    controllerFuture = null
                    controller = null
                }
            }
        )
    }

    @androidx.annotation.OptIn(ExperimentalCarApi::class)
    @OptIn(ExperimentalCarApi::class)
    override fun onGetTemplate(): Template {
        return if (supportsMediaPlaybackTemplate) {
            // No setStartHeaderAction(Action.BACK) here: the host already renders its
            // own back affordance for MediaPlaybackTemplate's "Now Playing" chrome.
            // Adding one ourselves produces two back icons. The ListTemplate fallback
            // below has no such host-provided chrome, so it still needs Action.BACK.
            androidx.car.app.media.model.MediaPlaybackTemplate.Builder()
                .setHeader(
                    Header.Builder()
                        .setTitle("Now Playing")
                        .build()
                )
                .build()
        } else {
            buildFallbackTemplate()
        }
    }

    /**
     * Level-1-compatible now-playing view: track metadata as rows plus transport
     * controls in the action strip. Not as rich as MediaPlaybackTemplate, but it
     * renders on every host instead of disqualifying the app entirely.
     */
    private fun buildFallbackTemplate(): Template {
        val player = controller
        val metadata = player?.mediaMetadata

        val listBuilder = ItemList.Builder()
        if (player == null) {
            listBuilder.addItem(Row.Builder().setTitle("Connecting…").build())
        } else {
            listBuilder.addItem(
                Row.Builder()
                    .setTitle(metadata?.title?.toString() ?: "Nothing playing")
                    .addText(metadata?.artist?.toString() ?: "—")
                    .build()
            )
            listBuilder.addItem(
                Row.Builder()
                    .setTitle(if (player.isPlaying) "Playing" else "Paused")
                    .addText("Track ${player.currentMediaItemIndex + 1} of ${player.mediaItemCount}")
                    .build()
            )
        }

        val actionStrip = ActionStrip.Builder()
            .addAction(
                Action.Builder()
                    .setTitle("Prev")
                    .setOnClickListener {
                        controller?.seekToPreviousMediaItem()
                        invalidate()
                    }
                    .build()
            )
            .addAction(
                Action.Builder()
                    .setTitle(if (player?.isPlaying == true) "Pause" else "Play")
                    .setOnClickListener {
                        controller?.let { c -> if (c.isPlaying) c.pause() else c.play() }
                        invalidate()
                    }
                    .build()
            )
            .addAction(
                Action.Builder()
                    .setTitle("Next")
                    .setOnClickListener {
                        controller?.seekToNextMediaItem()
                        invalidate()
                    }
                    .build()
            )
            .build()

        return ListTemplate.Builder()
            .setSingleList(listBuilder.build())
            .setHeader(
                Header.Builder()
                    .setTitle("Now Playing")
                    .setStartHeaderAction(Action.BACK)
                    .build()
            )
            .setActionStrip(actionStrip)
            .build()
    }
}
