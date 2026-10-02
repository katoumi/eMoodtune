package com.example.moodsync

object SpotifyConfig {
    val CLIENT_ID: String = BuildConfig.SPOTIFY_CLIENT_ID
    const val REDIRECT_URI = "moodsync://callback"

    const val SCOPES =
        "app-remote-control " +
                "streaming " +
                "user-read-email " +
                "user-read-private " +
                "user-read-playback-state " +
                "user-read-currently-playing " +
                "user-modify-playback-state"
}