package com.example.moodsync

import android.util.Log
import kotlinx.coroutines.delay
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import kotlin.random.Random

object SpotifyMoodSearchRepository {

    private const val TAG = "SpotifySearch"
    private const val SEARCH_BASE_URL = "https://api.spotify.com/v1/search"
    private const val RECO_BASE_URL = "https://api.spotify.com/v1/recommendations"

    private val memoryCache = ConcurrentHashMap<String, List<SpotifyMoodCandidate>>()
    private val lastRequestTime = AtomicLong(0)

    private suspend fun throttleRequests() {
        while (true) {
            val now = System.currentTimeMillis()
            val last = lastRequestTime.get()
            val nextAvailableSlot = last + 150L // 150ms balance between speed and rate safety
            val waitTime = nextAvailableSlot - now

            if (waitTime <= 0) {
                if (lastRequestTime.compareAndSet(last, now)) return
            } else {
                delay(waitTime)
            }
        }
    }

    suspend fun searchTracks(
        accessToken: String,
        query: String,
        limit: Int = 10,
        market: String? = "PH"
    ): List<SpotifyMoodCandidate> {
        val safeQuery = query.trim()
        val safeLimit = limit.coerceIn(1, 25)
        val safeMarket = market?.trim()?.takeIf { it.isNotBlank() }

        if (accessToken.isBlank() || safeQuery.isBlank()) {
            return emptyList()
        }

        val cacheKey = buildCacheKey(safeQuery, safeLimit, safeMarket)

        memoryCache[cacheKey]?.let {
            Log.d(TAG, "Cache hit query=$safeQuery limit=$safeLimit market=$safeMarket")
            return it
        }

        throttleRequests()

        var results = runSearchInternal(
            accessToken = accessToken,
            query = safeQuery,
            limit = safeLimit,
            market = safeMarket,
            hasRetriedAfterRateLimit = false
        )

        if (results.isEmpty() && !safeMarket.isNullOrBlank()) {
            Log.d(TAG, "No results for market=$safeMarket, retrying without market...")

            results = runSearchInternal(
                accessToken = accessToken,
                query = safeQuery,
                limit = safeLimit,
                market = null,
                hasRetriedAfterRateLimit = false
            )
        }

        if (results.isNotEmpty()) {
            memoryCache[cacheKey] = results
        }

        return results
    }

    suspend fun getRecommendationsByFeatures(
        accessToken: String,
        targetValence: Double,
        targetEnergy: Double,
        seedGenres: String,
        limit: Int = 10
    ): List<SpotifyMoodCandidate> {
        throttleRequests()

        val safeLimit = limit.coerceIn(1, 25)
        val url = "$RECO_BASE_URL?limit=$safeLimit&seed_genres=$seedGenres" +
                "&target_valence=$targetValence&target_energy=$targetEnergy&market=PH"

        Log.d(TAG, "Fetching recommendations: $url")
        
        var connection: HttpURLConnection? = null
        return try {
            connection = URL(url).openConnection() as HttpURLConnection
            connection.connectTimeout = 5000
            connection.readTimeout = 5000
            connection.requestMethod = "GET"
            connection.setRequestProperty("Authorization", "Bearer $accessToken")
            
            if (connection.responseCode !in 200..299) return emptyList()

            val body = connection.inputStream.bufferedReader().use { it.readText() }
            parseRecommendationResults(body)
        } catch (e: Exception) {
            Log.e(TAG, "Reco failed", e)
            emptyList()
        } finally {
            connection?.disconnect()
        }
    }

    private fun parseRecommendationResults(json: String): List<SpotifyMoodCandidate> {
        val root = JSONObject(json)
        val items = root.optJSONArray("tracks") ?: return emptyList()
        
        val results = mutableListOf<SpotifyMoodCandidate>()
        for (i in 0 until items.length()) {
            val track = items.optJSONObject(i) ?: continue
            val name = track.optString("name", "")
            val uri = track.optString("uri", "")
            val durationMs = track.optLong("duration_ms", 0L)
            val popularity = track.optInt("popularity", 0)
            val artistName = track.optJSONArray("artists")?.optJSONObject(0)?.optString("name", "") ?: ""
            val albumImageUrl = track.optJSONObject("album")?.optJSONArray("images")?.optJSONObject(0)?.optString("url", "") ?: ""

            if (name.isNotBlank() && artistName.isNotBlank() && uri.isNotBlank()) {
                results.add(SpotifyMoodCandidate(name, artistName, uri, durationMs, albumImageUrl, popularity))
            }
        }
        return results
    }

    private fun buildCacheKey(query: String, limit: Int, market: String?): String {
        return listOf(
            query.trim().lowercase(Locale.getDefault()),
            limit.toString(),
            market?.trim()?.uppercase(Locale.getDefault()).orEmpty()
        ).joinToString("|")
    }

    private fun buildSearchUrl(query: String, limit: Int, market: String?): String {
        val encodedQuery = URLEncoder.encode(query, "UTF-8")
        val sb = StringBuilder("$SEARCH_BASE_URL?q=$encodedQuery&type=track&limit=$limit")

        if (!market.isNullOrBlank()) {
            sb.append("&market=${URLEncoder.encode(market, "UTF-8")}")
        }

        return sb.toString()
    }

    private suspend fun runSearchInternal(
        accessToken: String,
        query: String,
        limit: Int,
        market: String?,
        hasRetriedAfterRateLimit: Boolean
    ): List<SpotifyMoodCandidate> {
        val safeLimit = limit.coerceIn(1, 10)
        val requestUrl = buildSearchUrl(query, safeLimit, market)

        Log.d(TAG, "Executing search: $requestUrl")

        var connection: HttpURLConnection? = null

        return try {
            connection = URL(requestUrl).openConnection() as HttpURLConnection
            connection.connectTimeout = 5000
            connection.readTimeout = 5000
            connection.requestMethod = "GET"
            connection.setRequestProperty("Authorization", "Bearer $accessToken")
            connection.setRequestProperty("Accept", "application/json")

            val responseCode = connection.responseCode

            if (responseCode == 429) {
                val retryAfter = connection.getHeaderField("Retry-After")?.toLongOrNull() ?: 10L
                Log.e(TAG, "Rate limited. Waiting $retryAfter seconds...")

                if (!hasRetriedAfterRateLimit) {
                    delay(retryAfter * 1000L)

                    return runSearchInternal(
                        accessToken = accessToken,
                        query = query,
                        limit = safeLimit,
                        market = market,
                        hasRetriedAfterRateLimit = true
                    )
                }

                return emptyList()
            }

            if (responseCode !in 200..299) {
                val errorBody = try {
                    BufferedReader(InputStreamReader(connection.errorStream)).use { it.readText() }
                } catch (_: Exception) {
                    "Unknown error"
                }

                Log.e(TAG, "Search failed HTTP $responseCode body=$errorBody")
                return emptyList()
            }

            val responseBody = BufferedReader(
                InputStreamReader(connection.inputStream)
            ).use { it.readText() }

            val parsed = parseTrackResults(responseBody)

            Log.d(TAG, "Search OK query=$query count=${parsed.size}")

            parsed
        } catch (e: Exception) {
            Log.e(TAG, "Search exception", e)
            emptyList()
        } finally {
            connection?.disconnect()
        }
    }

    private fun parseTrackResults(json: String): List<SpotifyMoodCandidate> {
        val root = JSONObject(json)
        val tracksObject = root.optJSONObject("tracks") ?: return emptyList()
        val items = tracksObject.optJSONArray("items") ?: return emptyList()

        val results = mutableListOf<SpotifyMoodCandidate>()

        for (i in 0 until items.length()) {
            val track = items.optJSONObject(i) ?: continue

            val name = track.optString("name", "").trim()
            val uri = track.optString("uri", "").trim()
            val durationMs = track.optLong("duration_ms", 0L)
            val popularity = track.optInt("popularity", 0)

            val artistName = track.optJSONArray("artists")
                ?.optJSONObject(0)
                ?.optString("name", "")
                ?.trim()
                .orEmpty()

            val albumImageUrl = track.optJSONObject("album")
                ?.optJSONArray("images")
                ?.optJSONObject(0)
                ?.optString("url", "")
                ?.trim()
                .orEmpty()

            if (name.isBlank() || artistName.isBlank() || uri.isBlank()) continue

            results.add(
                SpotifyMoodCandidate(
                    name = name,
                    artist = artistName,
                    uri = uri,
                    durationMs = durationMs,
                    albumImageUrl = albumImageUrl,
                    popularity = popularity
                )
            )
        }

        return results
    }
}