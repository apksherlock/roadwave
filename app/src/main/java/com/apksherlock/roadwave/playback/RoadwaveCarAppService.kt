package com.apksherlock.roadwave.playback

import android.content.ComponentName
import android.os.SystemClock
import android.support.v4.media.session.MediaSessionCompat
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
        carLogI("onCreateSession() — CarAppService reached by a host")
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
                carLogD("RoadwaveCarSession lifecycle event=$event")
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

        carLogI("===== Roadwave car host diagnostics =====")
        carLogI("host package        : ${runCatching { carContext.hostInfo?.packageName }.getOrNull()}")
        carLogI("host uid            : ${runCatching { carContext.hostInfo?.uid }.getOrNull()}")
        carLogI("carAppApiLevel      : $actualLevel")
        carLogI("manifest minCarApi  : $declaredMin")
        carLogI(
            "MediaPlaybackTemplate supported: " +
                "${actualLevel >= MEDIA_PLAYBACK_TEMPLATE_API_LEVEL} (needs >= $MEDIA_PLAYBACK_TEMPLATE_API_LEVEL)"
        )
        if (actualLevel in 0 until MEDIA_PLAYBACK_TEMPLATE_API_LEVEL) {
            carLogW(
                "Host is below level $MEDIA_PLAYBACK_TEMPLATE_API_LEVEL — PlaybackScreen will use the ListTemplate fallback."
            )
        }
        carLogI("========================================")
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
        val start = SystemClock.elapsedRealtime()
        val existing = PlaybackService.mediaSessionToken
        if (existing != null) {
            registerToken(existing, "static field (service already running)")
            return
        }

        carLogD("Media session token not ready — binding a MediaController to start PlaybackService")
        val sessionToken = SessionToken(carContext, ComponentName(carContext, PlaybackService::class.java))
        val future = MediaController.Builder(carContext, sessionToken).buildAsync()
        tokenControllerFuture = future
        future.addListener({
            val elapsed = SystemClock.elapsedRealtime() - start
            runCatching { future.get() }
                .onFailure {
                    carLogE("Failed to connect MediaController for token registration (${elapsed}ms)", it)
                }
                .onSuccess {
                    val token = PlaybackService.mediaSessionToken
                    if (token != null) {
                        carLogD("MediaController connected in ${elapsed}ms")
                        registerToken(token, "MediaController connection")
                    } else {
                        carLogE(
                            "PlaybackService connected (${elapsed}ms) but mediaSessionToken is still null — " +
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
            carLogI("registerMediaPlaybackToken() succeeded via $source")
        }.onFailure {
            carLogE("registerMediaPlaybackToken() failed via $source", it)
        }
    }

    override fun onCreateScreen(intent: android.content.Intent): Screen {
        carLogD("onCreateScreen action=${intent.action}")
        val rootScreen = MainCarScreen(carContext)
        handlePlaybackIntent(intent)
        return rootScreen
    }

    override fun onNewIntent(intent: android.content.Intent) {
        carLogD("onNewIntent action=${intent.action}")
        handlePlaybackIntent(intent)
    }

    private fun handlePlaybackIntent(intent: android.content.Intent) {
        val action = intent.action
        if (isShowPlaybackAction(action)) {
            carLogD("handlePlaybackIntent: recognized action=$action, pushing PlaybackScreen")
            val screenManager = carContext.getCarService(ScreenManager::class.java)
            if (screenManager.top !is PlaybackScreen) {
                screenManager.push(PlaybackScreen(carContext))
            }
        } else {
            carLogD("handlePlaybackIntent: unrecognized action=$action, no-op")
        }
    }

    /**
     * Real hosts have been observed sending "MEDIA_SHOW_PLAYBACK_VIEW", which matches
     * none of the three documented action strings verbatim — falls back to a substring
     * check on "SHOW_MEDIA_PLAYBACK"/"PLAYBACK_VIEW" so host variants like that one are
     * still recognized instead of silently falling through.
     */
    private fun isShowPlaybackAction(action: String?): Boolean {
        if (action == null) return false
        val recognizedActions = setOf(
            "androidx.car.app.media.action.SHOW_MEDIA_PLAYBACK",
            "android.intent.action.VIEW",
            "android.media.action.DISPLAY_AUDIO_CONTROL"
        )
        return action in recognizedActions ||
            action.contains("SHOW_MEDIA_PLAYBACK") ||
            action.contains("PLAYBACK_VIEW")
    }
}

class MainCarScreen(carContext: CarContext) : Screen(carContext) {
    init {
        lifecycle.addObserver(
            LifecycleEventObserver { _, event -> carLogD("MainCarScreen lifecycle event=$event") }
        )
    }

    override fun onGetTemplate(): Template {
        carLogD("MainCarScreen.onGetTemplate() called")
        val listBuilder = ItemList.Builder()
            .addItem(
                Row.Builder()
                    .setTitle("All Songs")
                    .addText("Browse your library")
                    .setOnClickListener {
                        carLogD("MainCarScreen: All Songs tapped")
                        screenManager.push(SongsScreen(carContext))
                    }
                    .build()
            )
            .addItem(
                Row.Builder()
                    .setTitle("Playlists")
                    .addText("Your collections")
                    .setOnClickListener {
                        carLogD("MainCarScreen: Playlists tapped")
                        screenManager.push(PlaylistsScreen(carContext))
                    }
                    .build()
            )
            .addItem(
                Row.Builder()
                    .setTitle("Search")
                    .addText("Find a song")
                    .setOnClickListener {
                        carLogD("MainCarScreen: Search tapped")
                        screenManager.push(SearchScreen(carContext))
                    }
                    .build()
            )

        return ListTemplate.Builder()
            .setSingleList(listBuilder.build())
            .setHeader(Header.Builder().setTitle("Roadwave").setStartHeaderAction(Action.APP_ICON).build())
            .build()
    }
}

class SearchScreen(carContext: CarContext) : Screen(carContext), SearchTemplate.SearchCallback {
    private val repository = SongRepository(carContext)
    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private var allSongs: List<com.apksherlock.roadwave.model.Song> = emptyList()
    private var results: List<com.apksherlock.roadwave.model.Song> = emptyList()
    private var controllerFuture: ListenableFuture<MediaController>? = null

    init {
        carLogD("SearchScreen created")
        lifecycle.addObserver(
            LifecycleEventObserver { _, event -> carLogD("SearchScreen lifecycle event=$event") }
        )
        val sessionToken = SessionToken(carContext, ComponentName(carContext, PlaybackService::class.java))
        controllerFuture = MediaController.Builder(carContext, sessionToken).buildAsync()

        scope.launch {
            allSongs = repository.getSongs()
            carLogD("SearchScreen: loaded ${allSongs.size} song(s) to search over")
        }
    }

    override fun onSearchTextChanged(searchText: String) {
        results = if (searchText.isBlank()) {
            emptyList()
        } else {
            allSongs.filter {
                it.title.contains(searchText, ignoreCase = true) ||
                    it.artist.contains(searchText, ignoreCase = true)
            }
        }
        carLogD("SearchScreen: query=\"$searchText\" -> ${results.size} match(es)")
        invalidate()
    }

    override fun onGetTemplate(): Template {
        val listBuilder = ItemList.Builder()
        if (results.isEmpty()) {
            listBuilder.addItem(Row.Builder().setTitle("No matches").build())
        } else {
            results.forEachIndexed { index, song ->
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

        carLogD("SearchScreen: onGetTemplate() building SearchTemplate with ${results.size} result(s)")
        return SearchTemplate.Builder(this)
            .setHeaderAction(Action.BACK)
            .setSearchHint("Search songs")
            .setShowKeyboardByDefault(true)
            .setItemList(listBuilder.build())
            .build()
    }

    private fun playMediaItem(startIndex: Int) {
        carLogD("SearchScreen.playMediaItem($startIndex): waiting on controller")
        controllerFuture?.addListener({
            val controller = controllerFuture?.get()
            carLogD("SearchScreen.playMediaItem: controller ready")
            val mediaItems = results.map { song ->
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

class SongsScreen(carContext: CarContext) : Screen(carContext) {
    private val repository = SongRepository(carContext)
    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private var songs: List<com.apksherlock.roadwave.model.Song> = emptyList()
    private var controllerFuture: ListenableFuture<MediaController>? = null

    init {
        carLogD("SongsScreen created")
        lifecycle.addObserver(
            LifecycleEventObserver { _, event -> carLogD("SongsScreen lifecycle event=$event") }
        )
        val sessionToken = SessionToken(carContext, ComponentName(carContext, PlaybackService::class.java))
        controllerFuture = MediaController.Builder(carContext, sessionToken).buildAsync()

        scope.launch {
            songs = repository.getSongs()
            carLogD("SongsScreen: loaded ${songs.size} song(s), invalidating")
            invalidate()
        }
    }

    override fun onGetTemplate(): Template {
        carLogD("SongsScreen.onGetTemplate() called, songs.size=${songs.size}")
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
        val start = SystemClock.elapsedRealtime()
        carLogD("SongsScreen.playMediaItem($startIndex): waiting on controller")
        controllerFuture?.addListener({
            val controller = controllerFuture?.get()
            carLogD("SongsScreen.playMediaItem: controller ready in ${SystemClock.elapsedRealtime() - start}ms")
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
        carLogD("PlaylistsScreen created")
        lifecycle.addObserver(
            LifecycleEventObserver { _, event -> carLogD("PlaylistsScreen lifecycle event=$event") }
        )
        scope.launch {
            playlists = repository.getPlaylists()
            carLogD("PlaylistsScreen: loaded ${playlists.size} playlist(s), invalidating")
            invalidate()
        }
    }

    override fun onGetTemplate(): Template {
        carLogD("PlaylistsScreen.onGetTemplate() called, playlists.size=${playlists.size}")
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
        carLogD("PlaylistDetailScreen created for playlist=${playlist.name}")
        lifecycle.addObserver(
            LifecycleEventObserver { _, event -> carLogD("PlaylistDetailScreen lifecycle event=$event") }
        )
        val sessionToken = SessionToken(carContext, ComponentName(carContext, PlaybackService::class.java))
        controllerFuture = MediaController.Builder(carContext, sessionToken).buildAsync()

        scope.launch {
            val allSongs = repository.getSongs()
            playlistSongs = playlist.songIds.mapNotNull { id -> allSongs.find { it.id == id } }
            carLogD("PlaylistDetailScreen: loaded ${playlistSongs.size} song(s), invalidating")
            invalidate()
        }
    }

    override fun onGetTemplate(): Template {
        carLogD("PlaylistDetailScreen.onGetTemplate() called, playlistSongs.size=${playlistSongs.size}")
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
        val start = SystemClock.elapsedRealtime()
        carLogD("PlaylistDetailScreen.playMediaItem($startIndex): waiting on controller")
        controllerFuture?.addListener({
            val controller = controllerFuture?.get()
            carLogD(
                "PlaylistDetailScreen.playMediaItem: controller ready in ${SystemClock.elapsedRealtime() - start}ms"
            )
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
        carLogI("PlaybackScreen: carAppApiLevel=$level supportsMediaPlaybackTemplate=$supported")
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
                    .onFailure { carLogE("PlaybackScreen: MediaController connect failed", it) }
            }, MoreExecutors.directExecutor())
        }

        lifecycle.addObserver(
            LifecycleEventObserver { _, event ->
                carLogD("PlaybackScreen lifecycle event=$event")
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
        carLogD("PlaybackScreen.onGetTemplate() called, supportsMediaPlaybackTemplate=$supportsMediaPlaybackTemplate")
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
