package com.apksherlock.roadwave.model

import kotlinx.serialization.Serializable

@Serializable
data class Song(
    val id: String,
    val title: String,
    val artist: String,
    val duration: Long,
    val mediaPath: String // Internal storage path
)
