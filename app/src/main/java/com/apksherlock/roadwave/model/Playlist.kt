package com.apksherlock.roadwave.model

import kotlinx.serialization.Serializable

@Serializable
data class Playlist(
    val id: String,
    val name: String,
    val songIds: List<String> = emptyList()
)
