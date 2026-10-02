package com.example.moodsync

object SpotifyArtworkCache {
    private val memory = mutableMapOf<String, String>()

    fun get(key: String): String? = memory[key]

    fun put(key: String, url: String) {
        if (url.isNotBlank()) {
            memory[key] = url
        }
    }

    fun makeKey(title: String, artist: String): String {
        return "${title.trim().lowercase()}|${artist.trim().lowercase()}"
    }
}