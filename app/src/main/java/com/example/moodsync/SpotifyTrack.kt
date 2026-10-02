package com.example.moodsync

data class SpotifyTrack(
    val id: String,
    val name: String,
    val artist: String,
    val albumName: String,
    val albumImageUrl: String,
    val uri: String,
    val spotifyUrl: String,
    val popularity: Int = 0
)