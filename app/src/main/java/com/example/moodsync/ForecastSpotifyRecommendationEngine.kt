package com.example.moodsync

import android.content.Context
import android.util.Log
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withTimeoutOrNull

class ForecastSpotifyRecommendationEngine(
    private val context: Context
) {

    suspend fun getRecommendationsForTimeline(
        timeline: List<ForecastTimelineItem>,
        limitPerPoint: Int = 1
    ): List<FutureRecommendationItem> = coroutineScope {
        val token = SpotifySessionManager.getValidAccessToken(context)

        if (token.isNullOrBlank()) {
            Log.e("ForecastEngine", "No valid Spotify token found")
            return@coroutineScope emptyList<FutureRecommendationItem>()
        }

        if (timeline.isEmpty()) {
            return@coroutineScope emptyList<FutureRecommendationItem>()
        }

        val safeLimitPerPoint = limitPerPoint.coerceIn(1, 10)
        
        // MOOD BATCHING: Group by mood to avoid redundant API calls
        val groupedByMood = timeline.take(6).groupBy { it.predictedMood.lowercase() }
        val finalResults = mutableListOf<FutureRecommendationItem>()

        val tasks = groupedByMood.map { (mood, points) ->
            async {
                // Fetch once per unique mood in the timeline
                val queries = SpotifyMoodQueryBuilder.getFallbackQueries(context, mood, points[0].label)
                val query = queries.random() 

                try {
                    val candidates = withTimeoutOrNull(5000) {
                        SpotifyMoodSearchRepository.searchTracks(
                            accessToken = token,
                            query = query,
                            limit = (points.size * safeLimitPerPoint).coerceIn(5, 20),
                            market = null 
                        )
                    } ?: emptyList<SpotifyMoodCandidate>()

                    // Distribute fetched candidates across all points with this mood
                    points.mapIndexed { index, point ->
                        val track = candidates.getOrNull(index % candidates.size)
                        if (track != null) {
                            FutureRecommendationItem(
                                timelineLabel = point.label,
                                predictedMood = mood,
                                confidence = point.confidence,
                                reason = point.reason,
                                songTitle = track.name,
                                artist = track.artist,
                                score = (point.confidence * 0.7) + (track.popularity * 0.3),
                                whyThisFits = "Matches your predicted $mood mood.",
                                spotifyUri = track.uri,
                                albumArtUrl = track.albumImageUrl
                            )
                        } else null
                    }.filterNotNull()
                } catch (e: Exception) {
                    Log.e("ForecastEngine", "Batch fetch failed for mood=$mood", e)
                    emptyList<FutureRecommendationItem>()
                }
            }
        }

        tasks.awaitAll().forEach { finalResults.addAll(it) }
        finalResults.sortedByDescending { it.score }
    }
}