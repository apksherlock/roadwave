package com.apksherlock.roadwave.playback

import android.content.ComponentName
import android.os.Handler
import android.os.Looper
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

/**
 * Minimum Car App API level for [androidx.car.app.model.ListTemplate.Builder.setHeader],
 * which every list screen in this file uses. Only used for diagnostics — the annotation
 * is not enforced at runtime, so a lower host silently receives a template it cannot
 * render.
 */
const val LIST_TEMPLATE_HEADER_API_LEVEL = 7

/**
 * How long we wait for the token-registration [MediaController] to connect before
 * logging a warning. Not a real timeout (there's no way to cancel and recover — the
 * host owns the retry/give-up decision), just an early warning that we're on the path
 * to the host's own "not responding" screen.
 */
private const val TOKEN_WATCHDOG_TIMEOUT_MS = 5000L

class RoadwaveCarAppService : CarAppService() {
    override fun onCreate() {
        super.onCreate()
        // The earliest possible evidence that a host bound us at all. If a failing drive
        // produces no line at this level, the problem is upstream of the app entirely
        // (app not listed, host rejected the manifest, unknown-sources gating) and no
        // amount of instrumentation further in will show anything.
        carLogI("RoadwaveCarAppService.onCreate() — bound by a host")
        carLogFlush("car app service created")
    }

    override fun onDestroy() {
        carLogW("RoadwaveCarAppService.onDestroy()")
        carLogFlush("car app service destroyed")
        super.onDestroy()
    }

    @Suppress("PrivateResource") // deliberate: see the allowlist branch below
    override fun createHostValidator(): HostValidator {
        // ALLOW_ALL_HOSTS_VALIDATOR only satisfies a real host's binding check when the
        // app is debuggable — a production Android Auto host (a real car) refuses to bind
        // to a release build that declares it, which looks like the app just hanging. Use
        // the car-app library's bundled allowlist (signatures for Google's official hosts:
        // the Android Auto phone app, Android Automotive, and the Desktop Head Unit) for
        // release builds instead, and keep ALLOW_ALL only for debug convenience.
        val debuggable = (applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE) != 0
        val validator = if (debuggable) {
            HostValidator.ALLOW_ALL_HOSTS_VALIDATOR
        } else {
            HostValidator.Builder(applicationContext)
                .addAllowedHosts(androidx.car.app.R.array.hosts_allowlist_sample)
                .build()
        }
        // A rejected host never reaches onCreateSession(), so pairing this line with the
        // absence of the next one is how a validation failure is identified after the
        // fact. The allowed set is logged so a host package missing from it is visible
        // without having to decompile the library's allowlist resource.
        carLogI(
            "createHostValidator() debuggable=$debuggable allowAll=$debuggable " +
                "allowedHosts=${runCatching { validator.allowedHosts.keys }.getOrNull()}"
        )
        return validator
    }

    @androidx.annotation.OptIn(UnstableApi::class)
    @OptIn(ExperimentalCarApi::class, UnstableApi::class)
    override fun onCreateSession(): Session {
        // Reaching here means host validation passed and the handshake settled.
        carLogI("onCreateSession() — host validation passed, host=${runCatching { hostInfo }.getOrNull()}")
        // Ship immediately instead of waiting for Bugfender's normal upload cadence,
        // since if everything after this hangs, the user gives up and unplugs before a
        // natural flush would happen.
        carLogFlush("session created")
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
                // Stack depth alongside the event: a session that reaches ON_START with
                // an empty stack has failed to deliver a root screen, which is the shape
                // of the "app isn't working" failure rather than a plain teardown.
                carLogD("RoadwaveCarSession lifecycle event=$event screenStack=${screenStackDescription()}")
                when (event) {
                    Lifecycle.Event.ON_CREATE -> {
                        logHostDiagnostics()
                        registerPlaybackToken()
                    }
                    Lifecycle.Event.ON_RESUME -> carLogFlush("session resumed")
                    Lifecycle.Event.ON_DESTROY -> {
                        tokenControllerFuture?.let { MediaController.releaseFuture(it) }
                        tokenControllerFuture = null
                        carLogFlush("session destroyed")
                    }
                    else -> Unit
                }
            }
        )
    }

    override fun onCarConfigurationChanged(newConfiguration: android.content.res.Configuration) {
        carLogD("onCarConfigurationChanged: $newConfiguration")
    }

    /**
     * Top-first listing of the screen stack. Uses [ScreenManager.getScreenStack] rather
     * than [ScreenManager.getTop], which throws while the stack is empty — the very
     * failure this instrumentation exists to catch.
     */
    private fun screenStackDescription(): String = runCatching {
        val stack = carContext.getCarService(ScreenManager::class.java).screenStack
        if (stack.isEmpty()) "[empty]" else stack.joinToString(",") { it.javaClass.simpleName }
    }.getOrElse { "<unavailable: $it>" }

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
        carLogI("host version        : ${hostVersionName()}")
        carLogI("carAppApiLevel      : $actualLevel")
        carLogI("manifest minCarApi  : $declaredMin")
        carLogI("dark mode           : ${runCatching { carContext.isDarkMode }.getOrNull()}")
        carLogI(
            "MediaPlaybackTemplate supported: " +
                "${actualLevel >= MEDIA_PLAYBACK_TEMPLATE_API_LEVEL} (needs >= $MEDIA_PLAYBACK_TEMPLATE_API_LEVEL)"
        )
        if (actualLevel in 0 until MEDIA_PLAYBACK_TEMPLATE_API_LEVEL) {
            carLogW(
                "Host is below level $MEDIA_PLAYBACK_TEMPLATE_API_LEVEL — PlaybackScreen will use the ListTemplate fallback."
            )
        }
        // Every list screen calls ListTemplate.Builder.setHeader(), which the car-app
        // library annotates @RequiresCarApi(7). That annotation is lint-only — nothing
        // enforces it at runtime — so on a lower host the template goes out anyway and
        // is rejected on arrival, with no client-side exception to attribute it to.
        // Declaring minCarApiLevel=5 in the manifest means we can legitimately be
        // negotiated down that far, so flag it loudly rather than leave it silent.
        if (actualLevel in 0 until LIST_TEMPLATE_HEADER_API_LEVEL) {
            carLogW(
                "Host level $actualLevel is below $LIST_TEMPLATE_HEADER_API_LEVEL — " +
                    "ListTemplate.setHeader() is @RequiresCarApi($LIST_TEMPLATE_HEADER_API_LEVEL) and every list " +
                    "screen uses it. Expect the host to reject these templates; switch them to " +
                    "setTitle()/setHeaderAction() or raise minCarApiLevel."
            )
        }
        carLogI("========================================")
        carLogFlush("host diagnostics captured")
    }

    /** Host app version, to tell an old Android Auto build apart from an old head unit. */
    private fun hostVersionName(): String = runCatching {
        val pkg = carContext.hostInfo?.packageName ?: return "<no host info>"
        @Suppress("DEPRECATION")
        carContext.packageManager.getPackageInfo(pkg, 0).versionName ?: "<none>"
    }.getOrElse { "<unavailable: $it>" }

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

        // registerMediaPlaybackToken() feeds both the host's native now-playing widget
        // and our own PlaybackScreen — if this future never completes on a given head
        // unit, both surfaces stall with no visible error, which is exactly the failure
        // mode under investigation. Nothing upstream of this (host, binder) can time out
        // on our behalf, so we watch for it ourselves and flush a breadcrumb the moment
        // we notice, rather than waiting on Bugfender's normal upload cadence.
        val watchdog = Handler(Looper.getMainLooper())
        val watchdogRunnable = Runnable {
            carLogW(
                "MediaController for token registration still not connected after " +
                    "${SystemClock.elapsedRealtime() - start}ms — host may already be timing out"
            )
            carLogFlush("token controller watchdog fired")
        }
        watchdog.postDelayed(watchdogRunnable, TOKEN_WATCHDOG_TIMEOUT_MS)

        future.addListener({
            watchdog.removeCallbacks(watchdogRunnable)
            val elapsed = SystemClock.elapsedRealtime() - start
            runCatching { future.get() }
                .onFailure {
                    carLogE("Failed to connect MediaController for token registration (${elapsed}ms)", it)
                    carLogFlush("token controller connect failed")
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
                        carLogFlush("session token still null")
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

    /**
     * Builds the initial screen stack.
     *
     * The screen stack is still **empty** while this runs — the host pushes whatever we
     * return only after we return it. That makes [ScreenManager.getTop] unusable here:
     * it is documented to throw [NullPointerException] "if the method is called before a
     * Screen has been pushed to the stack (…) or returning a Screen from
     * Session#onCreateScreen", and it does exactly that (`requireNonNull(stack.peek())`).
     *
     * That NPE used to escape this method on every launch that carried a playback deep
     * link. It propagates out of the host's `onAppCreate` dispatch, which reports the
     * failure to the host and rethrows on the main thread — so no template is ever
     * delivered and the car sits on a spinner until the host gives up with "Roadwave
     * doesn't seem to be working right now". The Desktop Head Unit never reproduced it
     * because its template launcher starts the app with a plain intent, so the deep-link
     * branch was skipped; a real head unit's media launcher sends
     * SHOW_MEDIA_PLAYBACK/MEDIA_SHOW_PLAYBACK_VIEW and always took it.
     *
     * So the deep link is handled by *composing the stack in order* instead of querying
     * it: push the screen that belongs underneath, and return the one that belongs on top.
     * That also puts the two screens the right way round — the old code pushed
     * PlaybackScreen first, so the returned MainCarScreen landed on top of it and the
     * deep link had no visible effect anyway.
     */
    override fun onCreateScreen(intent: android.content.Intent): Screen {
        carLogD("onCreateScreen ${describeIntent(intent)}")
        carLogD("onCreateScreen: stack before returning root = ${screenStackDescription()}")
        val wantsPlayback = runCatching { isShowPlaybackAction(intent.action) }
            .onFailure { carLogE("onCreateScreen: failed to classify intent action", it) }
            .getOrDefault(false)

        if (!wantsPlayback) {
            carLogD("onCreateScreen: no playback deep link, starting at MainCarScreen")
            return MainCarScreen(carContext)
        }

        // Deliberately defensive: this branch only ever runs on a real head unit, so a
        // mistake here costs another drive to find. Falling back to the browse root is
        // always better than letting the host tear the session down.
        return runCatching {
            carContext.getCarService(ScreenManager::class.java).push(MainCarScreen(carContext))
            carLogD("onCreateScreen: playback deep link, stack seeded with MainCarScreen")
            PlaybackScreen(carContext) as Screen
        }.getOrElse {
            carLogE("onCreateScreen: failed to seed playback stack, falling back to MainCarScreen", it)
            carLogFlush("playback deep link failed")
            MainCarScreen(carContext)
        }
    }

    override fun onNewIntent(intent: android.content.Intent) {
        carLogD("onNewIntent ${describeIntent(intent)}")
        handlePlaybackIntent(intent)
    }

    /**
     * Deep-link handling for an *already running* session, where the stack is non-empty.
     *
     * Reads the top of the stack through [ScreenManager.getScreenStack] (a copy, empty
     * when the stack is) rather than [ScreenManager.getTop] (throws when the stack is
     * empty) so this stays safe if it is ever called earlier in the lifecycle again.
     */
    private fun handlePlaybackIntent(intent: android.content.Intent) {
        val action = intent.action
        if (!isShowPlaybackAction(action)) {
            carLogD("handlePlaybackIntent: unrecognized action=$action, no-op")
            return
        }
        val screenManager = carContext.getCarService(ScreenManager::class.java)
        val top = screenManager.screenStack.firstOrNull()
        carLogD("handlePlaybackIntent: recognized action=$action, top=${top?.javaClass?.simpleName}")
        if (top !is PlaybackScreen) {
            screenManager.push(PlaybackScreen(carContext))
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

/**
 * Wraps a screen's template construction with timing and outcome logging.
 *
 * A throwable escaping `onGetTemplate()` reaches the host as a failed dispatch and
 * produces the same "isn't working right now" screen as a session that never delivers
 * a template at all, so without this the two are indistinguishable after the fact. The
 * duration matters too: the host applies its own deadline, and a template that takes
 * seconds to build is a finding even when it eventually succeeds.
 */
private inline fun traceTemplate(screen: String, build: () -> Template): Template {
    val start = SystemClock.elapsedRealtime()
    return runCatching(build)
        .onSuccess {
            carLogD(
                "$screen.onGetTemplate() -> ${it.javaClass.simpleName} in " +
                    "${SystemClock.elapsedRealtime() - start}ms"
            )
        }
        .onFailure {
            carLogE("$screen.onGetTemplate() threw after ${SystemClock.elapsedRealtime() - start}ms", it)
            carLogFlush("template build failed in $screen")
        }
        .getOrThrow()
}

class MainCarScreen(carContext: CarContext) : Screen(carContext) {
    init {
        lifecycle.addObserver(
            LifecycleEventObserver { _, event -> carLogD("MainCarScreen lifecycle event=$event") }
        )
    }

    override fun onGetTemplate(): Template = traceTemplate("MainCarScreen") { buildTemplate() }

    private fun buildTemplate(): Template {
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

    override fun onGetTemplate(): Template = traceTemplate("SearchScreen") { buildTemplate() }

    private fun buildTemplate(): Template {
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

    override fun onGetTemplate(): Template = traceTemplate("SongsScreen") { buildTemplate() }

    private fun buildTemplate(): Template {
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

    override fun onGetTemplate(): Template = traceTemplate("PlaylistsScreen") { buildTemplate() }

    private fun buildTemplate(): Template {
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

    override fun onGetTemplate(): Template = traceTemplate("PlaylistDetailScreen") { buildTemplate() }

    private fun buildTemplate(): Template {
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

    override fun onGetTemplate(): Template = traceTemplate("PlaybackScreen") { buildTemplate() }

    @androidx.annotation.OptIn(ExperimentalCarApi::class)
    @OptIn(ExperimentalCarApi::class)
    private fun buildTemplate(): Template {
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

        // Transport controls are rows, not an ActionStrip.
        //
        // ListTemplate validates any action strip against ACTIONS_CONSTRAINTS_SIMPLE,
        // which allows maxActions=2 and maxCustomTitles=1. The three title-only actions
        // this screen wants (Prev / Play-Pause / Next) breach both limits, so
        // setActionStrip() threw IllegalArgumentException on the *second* action every
        // single time it ran — meaning onGetTemplate() always threw on this path and the
        // host showed its "isn't working right now" screen. Icons instead of titles would
        // still breach maxActions.
        //
        // Rows carry no such constraint, render on every Car App API level, and are the
        // easier touch target in a car anyway. This also drops the deprecated
        // setActionStrip() call.
        if (player != null) {
            listBuilder.addItem(
                Row.Builder()
                    .setTitle(if (player.isPlaying) "Pause" else "Play")
                    .setOnClickListener {
                        controller?.let { c -> if (c.isPlaying) c.pause() else c.play() }
                        invalidate()
                    }
                    .build()
            )
            listBuilder.addItem(
                Row.Builder()
                    .setTitle("Previous")
                    .setOnClickListener {
                        controller?.seekToPreviousMediaItem()
                        invalidate()
                    }
                    .build()
            )
            listBuilder.addItem(
                Row.Builder()
                    .setTitle("Next")
                    .setOnClickListener {
                        controller?.seekToNextMediaItem()
                        invalidate()
                    }
                    .build()
            )
        }

        return ListTemplate.Builder()
            .setSingleList(listBuilder.build())
            .setHeader(
                Header.Builder()
                    .setTitle("Now Playing")
                    .setStartHeaderAction(Action.BACK)
                    .build()
            )
            .build()
    }
}
