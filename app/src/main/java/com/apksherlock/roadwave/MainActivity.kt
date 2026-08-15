package com.apksherlock.roadwave

import android.content.Context
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.LibraryMusic
import androidx.media3.common.Player
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.apksherlock.roadwave.model.Playlist
import com.apksherlock.roadwave.model.Song
import com.apksherlock.roadwave.ui.main.MainViewModel
import com.apksherlock.roadwave.ui.theme.*
import kotlinx.coroutines.delay

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            RoadwaveTheme {
                val viewModel: MainViewModel = viewModel()
                val showSplash by viewModel.showSplash.collectAsState()

                if (showSplash) {
                    RoadwaveSplashScreen { viewModel.dismissSplash() }
                } else {
                    MainScreen(viewModel)
                }
            }
        }
    }
}

/** A single continuous stroke, no gradient — the app's one recurring mark. */
@Composable
fun WaveformMark(
    modifier: Modifier = Modifier,
    color: Color = MaterialTheme.colorScheme.onBackground,
    strokeWidthFraction: Float = 0.09f
) {
    Canvas(modifier = modifier) {
        val w = size.width
        val midY = size.height * 0.55f
        val amp = size.height * 0.4f
        val path = Path().apply {
            moveTo(0f, midY)
            cubicTo(w * 0.12f, midY - amp, w * 0.12f, midY + amp, w * 0.25f, midY)
            cubicTo(w * 0.37f, midY - amp * 1.3f, w * 0.37f, midY + amp * 1.3f, w * 0.5f, midY)
            cubicTo(w * 0.62f, midY - amp * 1.6f, w * 0.62f, midY + amp * 1.6f, w * 0.75f, midY)
            cubicTo(w * 0.87f, midY - amp, w * 0.87f, midY + amp, w, midY)
        }
        drawPath(
            path = path,
            color = color,
            style = Stroke(width = w * strokeWidthFraction, cap = StrokeCap.Round, join = StrokeJoin.Round)
        )
    }
}

@Composable
fun RoadwaveSplashScreen(onFinished: () -> Unit) {
    val alpha = remember { Animatable(0f) }

    LaunchedEffect(Unit) {
        alpha.animateTo(1f, animationSpec = tween(500, easing = FastOutSlowInEasing))
        delay(1400)
        onFinished()
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.alpha(alpha.value)
        ) {
            WaveformMark(
                modifier = Modifier.size(width = 72.dp, height = 40.dp),
                color = MaterialTheme.colorScheme.onBackground
            )
            Spacer(Modifier.height(18.dp))
            Text(
                "ROADWAVE",
                style = MaterialTheme.typography.titleLarge.copy(
                    fontWeight = FontWeight.SemiBold,
                    letterSpacing = 1.sp,
                    color = MaterialTheme.colorScheme.onBackground
                )
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(viewModel: MainViewModel) {
    val songs by viewModel.songs.collectAsState()
    val playlists by viewModel.playlists.collectAsState()
    val currentSong by viewModel.currentSong.collectAsState()
    val isPlaying by viewModel.isPlaying.collectAsState()
    val playbackPosition by viewModel.playbackPosition.collectAsState()
    val duration by viewModel.duration.collectAsState()
    val repeatMode by viewModel.repeatMode.collectAsState()
    val error by viewModel.error.collectAsState()

    var selectedPlaylist by remember { mutableStateOf<Playlist?>(null) }
    var selectedTab by remember { mutableIntStateOf(0) }
    var showAddPlaylistDialog by remember { mutableStateOf(false) }
    var showAddSongsToPlaylistDialog by remember { mutableStateOf(false) }

    BackHandler(enabled = selectedTab != 0) {
        if (selectedTab == 2) {
            selectedTab = 1
            selectedPlaylist = null
        } else if (selectedTab == 1) {
            selectedTab = 0
        }
    }

    val pickerLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        uri?.let {
            val name = it.getFileName(viewModel.getApplication())
            viewModel.addSong(it, name, "Unknown Artist", 0)
        }
    }

    val snackbarHostState = remember { SnackbarHostState() }

    LaunchedEffect(error) {
        error?.let {
            snackbarHostState.showSnackbar(it)
            viewModel.clearError()
        }
    }

    val headerTitle = when (selectedTab) {
        0 -> "Songs"
        1 -> "Playlists"
        else -> playlists.find { it.id == selectedPlaylist?.id }?.name ?: "Playlist"
    }

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        containerColor = MaterialTheme.colorScheme.background,
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        headerTitle,
                        fontWeight = FontWeight.SemiBold,
                        fontSize = 22.sp,
                        color = MaterialTheme.colorScheme.onBackground
                    )
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background
                )
            )
        },
        floatingActionButton = {
            if (selectedTab == 0 || selectedTab == 1 || selectedTab == 2) {
                FloatingActionButton(
                    onClick = {
                        when (selectedTab) {
                            0 -> pickerLauncher.launch(arrayOf("audio/*"))
                            1 -> showAddPlaylistDialog = true
                            2 -> showAddSongsToPlaylistDialog = true
                        }
                    },
                    containerColor = MaterialTheme.colorScheme.inverseSurface,
                    contentColor = MaterialTheme.colorScheme.inverseOnSurface,
                    shape = CircleShape,
                    elevation = FloatingActionButtonDefaults.elevation(0.dp, 0.dp, 0.dp, 0.dp)
                ) {
                    Icon(
                        when (selectedTab) {
                            0 -> Icons.Default.Add
                            1 -> Icons.Default.PlaylistAdd
                            else -> Icons.Default.LibraryAdd
                        },
                        contentDescription = null
                    )
                }
            }
        },
        bottomBar = {
            Column(modifier = Modifier.background(MaterialTheme.colorScheme.background)) {
                HorizontalDivider(color = MaterialTheme.colorScheme.outline)
                currentSong?.let { song ->
                    MiniPlayer(
                        song = song,
                        isPlaying = isPlaying,
                        playbackPosition = playbackPosition,
                        duration = duration,
                        repeatMode = repeatMode,
                        onTogglePlay = { viewModel.togglePlayPause() },
                        onSkipNext = { viewModel.skipNext() },
                        onSkipPrevious = { viewModel.skipPrevious() },
                        onSeek = { viewModel.seekTo(it) },
                        onToggleRepeat = { viewModel.toggleRepeatMode() }
                    )
                    HorizontalDivider(color = MaterialTheme.colorScheme.outline)
                }
                NavigationBar(
                    containerColor = MaterialTheme.colorScheme.background,
                    tonalElevation = 0.dp
                ) {
                    NavigationBarItem(
                        selected = selectedTab == 0,
                        onClick = {
                            selectedTab = 0
                            selectedPlaylist = null
                        },
                        icon = { Icon(Icons.Default.MusicNote, null) },
                        label = { Text("Songs") },
                        colors = NavigationBarItemDefaults.colors(
                            selectedIconColor = MaterialTheme.colorScheme.onBackground,
                            selectedTextColor = MaterialTheme.colorScheme.onBackground,
                            unselectedIconColor = MaterialTheme.colorScheme.onSurfaceVariant,
                            unselectedTextColor = MaterialTheme.colorScheme.onSurfaceVariant,
                            indicatorColor = Color.Transparent
                        )
                    )
                    NavigationBarItem(
                        selected = selectedTab == 1 || selectedTab == 2,
                        onClick = {
                            selectedTab = 1
                            selectedPlaylist = null
                        },
                        icon = { Icon(Icons.Outlined.LibraryMusic, null) },
                        label = { Text("Playlists") },
                        colors = NavigationBarItemDefaults.colors(
                            selectedIconColor = MaterialTheme.colorScheme.onBackground,
                            selectedTextColor = MaterialTheme.colorScheme.onBackground,
                            unselectedIconColor = MaterialTheme.colorScheme.onSurfaceVariant,
                            unselectedTextColor = MaterialTheme.colorScheme.onSurfaceVariant,
                            indicatorColor = Color.Transparent
                        )
                    )
                }
            }
        }
    )
{ innerPadding ->
        Box(modifier = Modifier.padding(innerPadding)) {
            when (selectedTab) {
                0 -> SongsList(
                    songs = songs,
                    playlists = playlists,
                    currentSongId = currentSong?.id,
                    onSongClick = { viewModel.playSong(it) },
                    onAddToPlaylist = { s, p -> viewModel.addSongToPlaylist(s, p) },
                    onDeleteSong = { viewModel.deleteSongGlobally(it) }
                )
                1 -> PlaylistsList(playlists, {
                    selectedPlaylist = it
                    selectedTab = 2
                })
                2 -> {
                    val currentPlaylist = playlists.find { it.id == selectedPlaylist?.id }
                    currentPlaylist?.let { playlist ->
                        PlaylistDetail(
                            playlist = playlist,
                            allSongs = songs,
                            currentSongId = currentSong?.id,
                            onSongClick = { viewModel.playSong(it, playlist) },
                            onRemoveClick = { viewModel.removeSongFromPlaylist(it, playlist) }
                        )
                    }
                }
            }
        }
    }

    if (showAddPlaylistDialog) {
        AddPlaylistDialog(
            onDismiss = { showAddPlaylistDialog = false },
            onConfirm = { name ->
                viewModel.createPlaylist(name)
                showAddPlaylistDialog = false
            }
        )
    }

    if (showAddSongsToPlaylistDialog && selectedPlaylist != null) {
        val currentPlaylist = playlists.find { it.id == selectedPlaylist?.id }
        if (currentPlaylist != null) {
            AddSongsToPlaylistDialog(
                allSongs = songs,
                playlist = currentPlaylist,
                onDismiss = { showAddSongsToPlaylistDialog = false },
                onAddSong = { song ->
                    viewModel.addSongToPlaylist(song, currentPlaylist)
                }
            )
        }
    }
}

@Composable
fun SongsList(
    songs: List<Song>,
    playlists: List<Playlist>,
    currentSongId: String?,
    onSongClick: (Song) -> Unit,
    onAddToPlaylist: (Song, Playlist) -> Unit,
    onDeleteSong: (Song) -> Unit
) {
    if (songs.isEmpty()) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text("No songs yet. Add some music!", color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    } else {
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(horizontal = 20.dp)
        ) {
            items(songs) { song ->
                CustomSongCard(
                    song = song,
                    playlists = playlists,
                    isPlaying = song.id == currentSongId,
                    onClick = { onSongClick(song) },
                    onAddToPlaylist = { onAddToPlaylist(song, it) },
                    onDeleteSong = { onDeleteSong(song) }
                )
                HorizontalDivider(color = MaterialTheme.colorScheme.outline)
            }
        }
    }
}

@Composable
fun CustomSongCard(
    song: Song,
    playlists: List<Playlist>,
    isPlaying: Boolean,
    onClick: () -> Unit,
    onAddToPlaylist: (Playlist) -> Unit,
    onDeleteSong: () -> Unit,
    deleteLabel: String = "Delete from Device"
) {
    var showMenu by remember { mutableStateOf(false) }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onClick() }
            .padding(vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(34.dp)
                .clip(CircleShape)
                .background(
                    if (isPlaying) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.surfaceVariant
                )
                .then(
                    if (!isPlaying) Modifier.border(1.dp, MaterialTheme.colorScheme.outline, CircleShape)
                    else Modifier
                ),
            contentAlignment = Alignment.Center
        ) {
            Text(
                song.title.take(1).uppercase(),
                fontWeight = FontWeight.SemiBold,
                fontSize = 13.sp,
                color = if (isPlaying) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Spacer(Modifier.width(13.dp))
        Column(Modifier.weight(1f)) {
            Text(
                song.title,
                fontWeight = if (isPlaying) FontWeight.SemiBold else FontWeight.Medium,
                fontSize = 14.5.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                color = if (isPlaying) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onBackground
            )
            Text(
                song.artist,
                style = MaterialTheme.typography.bodySmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Box {
            IconButton(onClick = { showMenu = true }, modifier = Modifier.size(28.dp)) {
                Icon(Icons.Default.MoreVert, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
            }

            DropdownMenu(
                expanded = showMenu,
                onDismissRequest = { showMenu = false },
                modifier = Modifier.background(MaterialTheme.colorScheme.surface)
            ) {
                DropdownMenuItem(
                    text = { Text(deleteLabel, color = MaterialTheme.colorScheme.error) },
                    onClick = { onDeleteSong(); showMenu = false },
                    leadingIcon = { Icon(Icons.Default.Delete, null, tint = MaterialTheme.colorScheme.error) }
                )
                if (playlists.isNotEmpty()) {
                    HorizontalDivider(color = MaterialTheme.colorScheme.outline)
                    playlists.forEach { playlist ->
                        DropdownMenuItem(
                            text = { Text("Add to ${playlist.name}") },
                            onClick = { onAddToPlaylist(playlist); showMenu = false }
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun PlaylistsList(playlists: List<Playlist>, onPlaylistClick: (Playlist) -> Unit) {
    if (playlists.isEmpty()) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text("No playlists yet. Create one!", color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    } else {
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(horizontal = 20.dp)
        ) {
            items(playlists) { playlist ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onPlaylistClick(playlist) }
                        .padding(vertical = 15.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(
                            playlist.name,
                            fontWeight = FontWeight.Medium,
                            fontSize = 14.5.sp,
                            color = MaterialTheme.colorScheme.onBackground
                        )
                        Text(
                            "${playlist.songIds.size} tracks",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Icon(
                        Icons.Default.ChevronRight,
                        null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(20.dp)
                    )
                }
                HorizontalDivider(color = MaterialTheme.colorScheme.outline)
            }
        }
    }
}

@Composable
fun PlaylistDetail(
    playlist: Playlist,
    allSongs: List<Song>,
    currentSongId: String?,
    onSongClick: (Song) -> Unit,
    onRemoveClick: (Song) -> Unit
) {
    val playlistSongs = playlist.songIds.mapNotNull { id -> allSongs.find { it.id == id } }

    if (playlistSongs.isEmpty()) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text("No songs in this playlist.", color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    } else {
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(horizontal = 20.dp)
        ) {
            items(playlistSongs) { song ->
                CustomSongCard(
                    song = song,
                    playlists = emptyList(),
                    isPlaying = song.id == currentSongId,
                    onClick = { onSongClick(song) },
                    onAddToPlaylist = {},
                    onDeleteSong = { onRemoveClick(song) },
                    deleteLabel = "Remove from Playlist"
                )
                HorizontalDivider(color = MaterialTheme.colorScheme.outline)
            }
        }
    }
}

/** A flat line + moving dot, tap or drag to seek — no track/thumb chrome. */
@Composable
fun MinimalProgressBar(
    progress: Float,
    onSeek: (Float) -> Unit,
    modifier: Modifier = Modifier
) {
    var dragProgress by remember { mutableStateOf<Float?>(null) }
    val shown = (dragProgress ?: progress).coerceIn(0f, 1f)
    val trackColor = MaterialTheme.colorScheme.outline
    val fillColor = MaterialTheme.colorScheme.onBackground

    Canvas(
        modifier = modifier
            .fillMaxWidth()
            .height(20.dp)
            .pointerInput(Unit) {
                detectHorizontalDragGestures(
                    onDragStart = { offset -> dragProgress = (offset.x / size.width).coerceIn(0f, 1f) },
                    onDragEnd = { dragProgress?.let(onSeek); dragProgress = null },
                    onDragCancel = { dragProgress = null }
                ) { change, _ -> dragProgress = (change.position.x / size.width).coerceIn(0f, 1f) }
            }
            .pointerInput(Unit) {
                detectTapGestures { offset -> onSeek((offset.x / size.width).coerceIn(0f, 1f)) }
            }
    ) {
        val midY = size.height / 2f
        drawLine(trackColor, Offset(0f, midY), Offset(size.width, midY), strokeWidth = 4f, cap = StrokeCap.Round)
        val fillX = size.width * shown
        if (fillX > 0f) {
            drawLine(fillColor, Offset(0f, midY), Offset(fillX, midY), strokeWidth = 4f, cap = StrokeCap.Round)
        }
        drawCircle(fillColor, radius = 9f, center = Offset(fillX, midY))
    }
}

@Composable
fun MiniPlayer(
    song: Song,
    isPlaying: Boolean,
    playbackPosition: Long,
    duration: Long,
    repeatMode: Int,
    onTogglePlay: () -> Unit,
    onSkipNext: () -> Unit,
    onSkipPrevious: () -> Unit,
    onSeek: (Long) -> Unit,
    onToggleRepeat: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.background)
            .padding(horizontal = 20.dp, vertical = 10.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(
                song.title,
                fontWeight = FontWeight.Medium,
                fontSize = 14.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                color = MaterialTheme.colorScheme.onBackground,
                modifier = Modifier.weight(1f, fill = false)
            )
            Spacer(Modifier.width(8.dp))
            Text(
                song.artist,
                fontSize = 12.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        val progress = if (duration > 0) playbackPosition.toFloat() / duration.toFloat() else 0f
        MinimalProgressBar(
            progress = progress,
            onSeek = { fraction -> onSeek((fraction * duration).toLong()) }
        )

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = onToggleRepeat, modifier = Modifier.size(32.dp)) {
                val icon = if (repeatMode == Player.REPEAT_MODE_ONE) Icons.Default.RepeatOne else Icons.Default.Repeat
                Icon(
                    icon,
                    null,
                    tint = if (repeatMode == Player.REPEAT_MODE_ONE) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            IconButton(onClick = onSkipPrevious, modifier = Modifier.size(32.dp)) {
                Icon(Icons.Default.SkipPrevious, null, tint = MaterialTheme.colorScheme.onBackground)
            }
            Box(
                modifier = Modifier
                    .size(42.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.inverseSurface)
                    .clickable { onTogglePlay() },
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                    null,
                    tint = MaterialTheme.colorScheme.inverseOnSurface
                )
            }
            IconButton(onClick = onSkipNext, modifier = Modifier.size(32.dp)) {
                Icon(Icons.Default.SkipNext, null, tint = MaterialTheme.colorScheme.onBackground)
            }
            Spacer(Modifier.size(32.dp))
        }
    }
}

@Composable
fun AddSongsToPlaylistDialog(
    allSongs: List<Song>,
    playlist: Playlist,
    onDismiss: () -> Unit,
    onAddSong: (Song) -> Unit
) {
    val availableSongs = allSongs.filter { song -> !playlist.songIds.contains(song.id) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Add Songs to ${playlist.name}") },
        text = {
            if (availableSongs.isEmpty()) {
                Text("All songs are already in this playlist.")
            } else {
                LazyColumn(modifier = Modifier.heightIn(max = 400.dp)) {
                    items(availableSongs) { song ->
                        ListItem(
                            headlineContent = { Text(song.title) },
                            supportingContent = { Text(song.artist) },
                            modifier = Modifier.clickable {
                                onAddSong(song)
                            }
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text("DONE")
            }
        }
    )
}
@Composable
fun AddPlaylistDialog(onDismiss: () -> Unit, onConfirm: (String) -> Unit) {
    var name by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = MaterialTheme.colorScheme.surface,
        title = { Text("New Playlist", fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSurface) },
        text = {
            TextField(
                value = name,
                onValueChange = { name = it },
                placeholder = { Text("Playlist Name") },
                colors = TextFieldDefaults.colors(
                    unfocusedContainerColor = MaterialTheme.colorScheme.background,
                    focusedContainerColor = MaterialTheme.colorScheme.background
                )
            )
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(name) }, enabled = name.isNotBlank()) {
                Text("CREATE", color = MaterialTheme.colorScheme.primary)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("CANCEL", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    )
}

fun Uri.getFileName(context: Context): String {
    var result: String? = null
    if (scheme == "content") {
        val cursor = context.contentResolver.query(this, null, null, null, null)
        try {
            if (cursor != null && cursor.moveToFirst()) {
                val index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (index != -1) result = cursor.getString(index)
            }
        } finally {
            cursor?.close()
        }
    }
    if (result == null) {
        result = path
        val cut = result?.lastIndexOf('/') ?: -1
        if (cut != -1) {
            result = result?.substring(cut + 1)
        }
    }
    return result ?: "Unknown"
}
