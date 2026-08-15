package com.apksherlock.roadwave.data

import android.content.Context
import android.net.Uri
import android.os.StatFs
import com.apksherlock.roadwave.model.Playlist
import com.apksherlock.roadwave.model.Song
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.util.UUID

class SongRepository(private val context: Context) {

    private val songsFile = File(context.filesDir, "songs.json")
    private val playlistsFile = File(context.filesDir, "playlists.json")
    private val mediaDir = File(context.filesDir, "media").apply { mkdirs() }

    suspend fun getSongs(): List<Song> = withContext(Dispatchers.IO) {
        if (!songsFile.exists()) return@withContext emptyList()
        try {
            Json.decodeFromString(songsFile.readText())
        } catch (e: Exception) {
            emptyList()
        }
    }

    suspend fun getPlaylists(): List<Playlist> = withContext(Dispatchers.IO) {
        if (!playlistsFile.exists()) return@withContext emptyList()
        try {
            Json.decodeFromString(playlistsFile.readText())
        } catch (e: Exception) {
            emptyList()
        }
    }

    suspend fun addSong(uri: Uri, title: String, artist: String, duration: Long): Result<Song> = withContext(
        Dispatchers.IO
    ) {
        val currentSongs = getSongs().toMutableList()

        // Check for duplicates based on title
        if (currentSongs.any { it.title.equals(title, ignoreCase = true) }) {
            return@withContext Result.failure(Exception("Song already exists: $title"))
        }

        val id = UUID.randomUUID().toString()
        val extension = context.contentResolver.getType(uri)?.substringAfterLast("/") ?: "mp3"
        val fileName = "$id.$extension"
        val destFile = File(mediaDir, fileName)

        val fileSize = context.contentResolver.openAssetFileDescriptor(uri, "r")?.use { it.length } ?: 0L
        if (!hasEnoughSpace(fileSize)) {
            return@withContext Result.failure(Exception("Not enough storage space"))
        }

        try {
            context.contentResolver.openInputStream(uri)?.use { input ->
                destFile.outputStream().use { output ->
                    input.copyTo(output)
                }
            }
            val newSong = Song(id, title, artist, duration, destFile.absolutePath)
            val currentSongs = getSongs().toMutableList()
            currentSongs.add(newSong)
            songsFile.writeText(Json.encodeToString(currentSongs))
            Result.success(newSong)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun createPlaylist(name: String) = withContext(Dispatchers.IO) {
        val playlists = getPlaylists().toMutableList()
        playlists.add(Playlist(UUID.randomUUID().toString(), name))
        playlistsFile.writeText(Json.encodeToString(playlists))
    }

    suspend fun addSongToPlaylist(songId: String, playlistId: String) = withContext(Dispatchers.IO) {
        val playlists = getPlaylists().map {
            if (it.id == playlistId) {
                if (it.songIds.contains(songId)) {
                    it // Don't add if already exists
                } else {
                    it.copy(songIds = it.songIds + songId)
                }
            } else {
                it
            }
        }
        playlistsFile.writeText(Json.encodeToString(playlists))
    }

    suspend fun removeSongFromPlaylist(songId: String, playlistId: String) = withContext(Dispatchers.IO) {
        val playlists = getPlaylists().map {
            if (it.id == playlistId) {
                it.copy(songIds = it.songIds.filter { id -> id != songId })
            } else {
                it
            }
        }
        playlistsFile.writeText(Json.encodeToString(playlists))
    }

    private fun hasEnoughSpace(requiredBytes: Long): Boolean {
        val stat = StatFs(context.filesDir.path)
        val availableBytes = stat.availableBlocksLong * stat.blockSizeLong
        return availableBytes > requiredBytes + 10 * 1024 * 1024
    }

    suspend fun deleteSong(song: Song) = withContext(Dispatchers.IO) {
        val currentSongs = getSongs().toMutableList()
        currentSongs.removeIf { it.id == song.id }
        songsFile.writeText(Json.encodeToString(currentSongs))

        // Also remove from all playlists
        val currentPlaylists = getPlaylists().map { playlist ->
            playlist.copy(songIds = playlist.songIds.filter { it != song.id })
        }
        playlistsFile.writeText(Json.encodeToString(currentPlaylists))

        File(song.mediaPath).delete()
    }
}
