package com.example.moodsync

import android.content.Context
import java.util.Locale

class SpotifySearchManager(
    private val context: Context
) {

    data class Result(
        val songs: List<HomeActivity.Song>,
        val trackCache: Map<String, SpotifyTrack>
    )

    suspend fun search(
        query: String,
        limit: Int = 10,
        market: String? = "PH"
    ): Result {
        val safeQuery = query.trim()
        val safeLimit = limit.coerceIn(1, 10)
        val accessToken = SpotifySessionManager.getValidAccessToken(context)

        if (accessToken.isNullOrBlank() || safeQuery.isBlank()) {
            return Result(emptyList(), emptyMap())
        }

        val results = SpotifyMoodSearchRepository.searchTracks(
            accessToken = accessToken,
            query = safeQuery,
            limit = safeLimit,
            market = market
        )

        val songs = results.map { candidate ->
            HomeActivity.Song(
                title = candidate.name,
                artist = candidate.artist,
                duration = candidate.durationText()
            )
        }

        val cache = mutableMapOf<String, SpotifyTrack>()

        results.forEach { candidate ->
            val track = candidate.toSpotifyTrack()

            cache[candidate.cacheKey()] = track
            cache[globalSongKey(track.name, track.artist)] = track
            cache[track.uri] = track
        }

        return Result(
            songs = songs,
            trackCache = cache
        )
    }

    private fun globalSongKey(title: String, artist: String): String {
        return "${title.trim().lowercase(Locale.getDefault())}|${artist.trim().lowercase(Locale.getDefault())}"
    }
}