package com.apksherlock.roadwave.ui.main

import android.app.Application
import android.content.ComponentName
import android.net.Uri
import androidx.core.net.toUri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.apksherlock.roadwave.data.SongRepository
import com.apksherlock.roadwave.model.Song
import com.apksherlock.roadwave.playback.PlaybackService
import com.google.common.util.concurrent.ListenableFuture
import com.google.common.util.concurrent.MoreExecutors
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

class MainViewModel(application: Application) : AndroidViewModel(application) {

    private val repository = SongRepository(application)
    private var controllerFuture: ListenableFuture<MediaController>? = null
    private val controller: MediaController? get() = if (controllerFuture?.isDone == true) controllerFuture?.get() else null

    private val _songs = MutableStateFlow<List<Song>>(emptyList())
    val songs: StateFlow<List<Song>> = _songs

    private val _playlists = MutableStateFlow<List<com.apksherlock.roadwave.model.Playlist>>(emptyList())
    val playlists: StateFlow<List<com.apksherlock.roadwave.model.Playlist>> = _playlists

    private val _currentSong = MutableStateFlow<Song?>(null)
    val currentSong: StateFlow<Song?> = _currentSong

    private val _isPlaying = MutableStateFlow(false)
    val isPlaying: StateFlow<Boolean> = _isPlaying

    private val _playbackPosition = MutableStateFlow(0L)
    val playbackPosition: StateFlow<Long> = _playbackPosition

    private val _duration = MutableStateFlow(0L)
    val duration: StateFlow<Long> = _duration

    private val _repeatMode = MutableStateFlow(Player.REPEAT_MODE_OFF)
    val repeatMode: StateFlow<Int> = _repeatMode

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error

    private val _showSplash = MutableStateFlow(true)
    val showSplash: StateFlow<Boolean> = _showSplash

    init {
        loadSongs()
        loadPlaylists()
        setupController()
    }

    private fun setupController() {
        val sessionToken = SessionToken(getApplication(), ComponentName(getApplication(), PlaybackService::class.java))
        controllerFuture = MediaController.Builder(getApplication(), sessionToken).buildAsync()
        controllerFuture?.addListener({
            controller?.let { player ->
                player.addListener(object : Player.Listener {
                    override fun onIsPlayingChanged(isPlaying: Boolean) {
                        _isPlaying.value = isPlaying
                        if (isPlaying) startProgressUpdate()
                    }

                    override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                        _currentSong.value = _songs.value.find { it.id == mediaItem?.mediaId }
                        _duration.value = player.duration.coerceAtLeast(0L)
                    }

                    override fun onRepeatModeChanged(repeatMode: Int) {
                        _repeatMode.value = repeatMode
                    }

                    override fun onPlaybackStateChanged(playbackState: Int) {
                        if (playbackState == Player.STATE_READY) {
                            _duration.value = player.duration.coerceAtLeast(0L)
                        }
                    }
                })
                // Initial states
                _isPlaying.value = player.isPlaying
                if (player.isPlaying) startProgressUpdate()
                _repeatMode.value = player.repeatMode
                _currentSong.value = _songs.value.find { it.id == player.currentMediaItem?.mediaId }
            }
        }, MoreExecutors.directExecutor())
    }

    private fun startProgressUpdate() {
        viewModelScope.launch {
            while (isPlaying.value) {
                controller?.let {
                    _playbackPosition.value = it.currentPosition
                    _duration.value = it.duration.coerceAtLeast(0L)
                }
                kotlinx.coroutines.delay(1000)
            }
        }
    }

    private fun loadSongs() {
        viewModelScope.launch {
            _songs.value = repository.getSongs()
        }
    }

    private fun loadPlaylists() {
        viewModelScope.launch {
            _playlists.value = repository.getPlaylists()
        }
    }

    fun addSong(uri: Uri, title: String, artist: String, duration: Long) {
        viewModelScope.launch {
            val result = repository.addSong(uri, title, artist, duration)
            result.onSuccess {
                loadSongs()
            }.onFailure {
                _error.value = it.message ?: "Failed to add song"
            }
        }
    }

    fun clearError() {
        _error.value = null
    }

    fun dismissSplash() {
        _showSplash.value = false
    }

    fun createPlaylist(name: String) {
        viewModelScope.launch {
            repository.createPlaylist(name)
            loadPlaylists()
        }
    }

    fun addSongToPlaylist(song: Song, playlist: com.apksherlock.roadwave.model.Playlist) {
        viewModelScope.launch {
            repository.addSongToPlaylist(song.id, playlist.id)
            loadPlaylists()
        }
    }

    fun removeSongFromPlaylist(song: Song, playlist: com.apksherlock.roadwave.model.Playlist) {
        viewModelScope.launch {
            repository.removeSongFromPlaylist(song.id, playlist.id)
            loadPlaylists()
        }
    }

    fun deleteSongGlobally(song: Song) {
        viewModelScope.launch {
            repository.deleteSong(song)
            loadSongs()
            loadPlaylists()
            if (currentSong.value?.id == song.id) {
                controller?.stop()
                _currentSong.value = null
            }
        }
    }

    fun playPlaylist(playlist: com.apksherlock.roadwave.model.Playlist) {
        val playlistSongs = playlist.songIds.mapNotNull { id -> _songs.value.find { it.id == id } }
        if (playlistSongs.isNotEmpty()) {
            playSong(playlistSongs.first(), playlist)
        }
    }

    fun playSong(song: Song, playlist: com.apksherlock.roadwave.model.Playlist? = null) {
        val controller = this.controller ?: return

        val songList = if (playlist != null) {
            playlist.songIds.mapNotNull { id -> _songs.value.find { it.id == id } }
        } else {
            _songs.value
        }

        val mediaItems = songList.map {
            MediaItem.Builder()
                .setMediaId(it.id)
                .setUri(it.mediaPath.toUri())
                .setMediaMetadata(
                    androidx.media3.common.MediaMetadata.Builder()
                        .setTitle(it.title)
                        .setArtist(it.artist)
                        .setIsBrowsable(false)
                        .setIsPlayable(true)
                        .build()
                )
                .build()
        }
        val startIndex = songList.indexOf(song).coerceAtLeast(0)

        controller.setMediaItems(mediaItems)
        controller.seekTo(startIndex, 0L)
        controller.prepare()
        controller.play()
    }

    fun togglePlayPause() {
        if (isPlaying.value) {
            controller?.pause()
        } else {
            controller?.play()
        }
    }

    fun skipNext() {
        controller?.seekToNext()
    }

    fun skipPrevious() {
        controller?.seekToPrevious()
    }

    fun seekTo(position: Long) {
        _playbackPosition.value = position
        controller?.seekTo(position)
    }

    fun toggleRepeatMode() {
        // Never land on REPEAT_MODE_OFF: at the last track that disables
        // COMMAND_SEEK_TO_NEXT, and the media/Android Auto controls substitute the
        // repeat custom-layout button into the now-empty Next slot. Mirrors the
        // ONE<->ALL-only cycle PlaybackService uses for the car app's repeat button.
        val nextMode = when (repeatMode.value) {
            Player.REPEAT_MODE_ONE -> Player.REPEAT_MODE_ALL
            else -> Player.REPEAT_MODE_ONE
        }
        controller?.repeatMode = nextMode
    }

    override fun onCleared() {
        controllerFuture?.let {
            MediaController.releaseFuture(it)
        }
    }
}
