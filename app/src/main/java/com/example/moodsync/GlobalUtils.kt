package com.example.moodsync

fun globalSongKey(title: String, artist: String): String {
    return "${title.trim().lowercase()}|${artist.trim().lowercase()}"
}