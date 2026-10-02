package com.example.moodsync

import org.json.JSONObject
import java.io.BufferedReader
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

object SpotifyRepository {

    fun searchBestTrack(
        accessToken: String,
        songTitle: String,
        artist: String? = null
    ): SpotifyTrack? {
        if (accessToken.isBlank() || songTitle.isBlank()) {
            return null
        }

        val strictQuery = if (artist.isNullOrBlank()) {
            "track:$songTitle"
        } else {
            "track:$songTitle artist:$artist"
        }

        val strictResult = searchTrack(accessToken, strictQuery)
        if (strictResult != null) return strictResult

        return searchTrack(accessToken, songTitle)
    }

    fun getCurrentUserProfile(accessToken: String): SpotifyUserProfile? {
        if (accessToken.isBlank()) return null

        val connection = (URL("https://api.spotify.com/v1/me").openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            setRequestProperty("Authorization", "Bearer $accessToken")
            setRequestProperty("Accept", "application/json")
            connectTimeout = 10000
            readTimeout = 10000
        }

        return try {
            val responseCode = connection.responseCode
            val responseText = if (responseCode in 200..299) {
                connection.inputStream.bufferedReader().use(BufferedReader::readText)
            } else {
                connection.errorStream?.bufferedReader()?.use(BufferedReader::readText)
                    ?: return null
            }

            if (responseCode !in 200..299) return null

            val json = JSONObject(responseText)
            val images = json.optJSONArray("images")

            val imageUrl = if (images != null && images.length() > 0) {
                images.getJSONObject(0).optString("url", "")
            } else {
                ""
            }

            SpotifyUserProfile(
                id = json.optString("id", ""),
                displayName = json.optString("display_name", "Spotify User"),
                email = json.optString("email", ""),
                imageUrl = imageUrl
            )
        } catch (_: Exception) {
            null
        } finally {
            connection.disconnect()
        }
    }

    private fun searchTrack(
        accessToken: String,
        query: String
    ): SpotifyTrack? {
        if (accessToken.isBlank() || query.isBlank()) return null

        val encodedQuery = URLEncoder.encode(query, "UTF-8")
        val endpoint = "https://api.spotify.com/v1/search?q=$encodedQuery&type=track&limit=1&market=PH"

        val connection = (URL(endpoint).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            setRequestProperty("Authorization", "Bearer $accessToken")
            setRequestProperty("Accept", "application/json")
            connectTimeout = 5000 // Reduced from 10s
            readTimeout = 5000
        }

        return try {
            val responseCode = connection.responseCode
            val responseText = if (responseCode in 200..299) {
                connection.inputStream.bufferedReader().use(BufferedReader::readText)
            } else {
                connection.errorStream?.bufferedReader()?.use(BufferedReader::readText)
                    ?: return null
            }

            if (responseCode !in 200..299) return null

            val root = JSONObject(responseText)
            val items = root.getJSONObject("tracks").getJSONArray("items")

            if (items.length() == 0) return null

            val track = items.getJSONObject(0)
            val artists = track.getJSONArray("artists")
            val firstArtist = if (artists.length() > 0) artists.getJSONObject(0) else null

            val album = track.getJSONObject("album")
            val images = album.getJSONArray("images")

            val imageUrl = if (images.length() > 0) {
                images.getJSONObject(0).optString("url", "")
            } else {
                ""
            }

            SpotifyTrack(
                id = track.optString("id", ""),
                name = track.optString("name", ""),
                artist = firstArtist?.optString("name", "Unknown Artist") ?: "Unknown Artist",
                albumName = album.optString("name", ""),
                albumImageUrl = imageUrl,
                uri = track.optString("uri", ""),
                spotifyUrl = track.optJSONObject("external_urls")?.optString("spotify", "").orEmpty()
            )
        } catch (_: Exception) {
            null
        } finally {
            connection.disconnect()
        }
    }

    fun getAudioFeatures(accessToken: String, trackId: String): Pair<Double, Double>? {
        if (accessToken.isBlank() || trackId.isBlank()) return null

        val id = if (trackId.contains(":")) trackId.split(":").last() else trackId
        val endpoint = "https://api.spotify.com/v1/audio-features/$id"

        val connection = (URL(endpoint).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            setRequestProperty("Authorization", "Bearer $accessToken")
            setRequestProperty("Accept", "application/json")
            connectTimeout = 5000
            readTimeout = 5000
        }

        return try {
            val responseCode = connection.responseCode
            if (responseCode !in 200..299) return null

            val text = connection.inputStream.bufferedReader().use(BufferedReader::readText)
            val json = JSONObject(text)

            val valence = json.optDouble("valence", 0.5)
            val energy = json.optDouble("energy", 0.5)

            Pair(valence, energy)
        } catch (_: Exception) {
            null
        } finally {
            connection.disconnect()
        }
    }
}
