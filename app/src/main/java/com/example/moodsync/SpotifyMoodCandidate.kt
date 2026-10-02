package com.example.moodsync

import java.util.Locale

data class SpotifyMoodCandidate(
    val name: String,
    val artist: String,
    val uri: String,
    val durationMs: Long,
    val albumImageUrl: String,
    val popularity: Int = 0
) {
    fun durationText(): String {
        val totalSeconds = (durationMs / 1000L).toInt()
        val mins = totalSeconds / 60
        val secs = totalSeconds % 60
        return String.format(Locale.getDefault(), "%d:%02d", mins, secs)
    }

    fun cacheKey(): String {
        return "${name.trim().lowercase(Locale.getDefault())}|${artist.trim().lowercase(Locale.getDefault())}"
    }

    fun toSpotifyTrack(): SpotifyTrack {
        return SpotifyTrack(
            id = uri,
            name = name,
            artist = artist,
            albumName = "",
            albumImageUrl = albumImageUrl,
            spotifyUrl = uri,
            uri = uri,
            popularity = popularity
        )
    }
}