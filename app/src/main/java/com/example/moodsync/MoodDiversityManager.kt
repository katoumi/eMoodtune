package com.example.moodsync

import java.util.Locale

object MoodDiversityManager {

    private val usedSongsByMood = mutableMapOf<String, MutableSet<String>>()

    private fun key(title: String, artist: String): String {
        return "${title.trim().lowercase(Locale.getDefault())}|${artist.trim().lowercase(Locale.getDefault())}"
    }

    fun registerMoodSongs(mood: String, songs: List<HomeActivity.Song>) {
        val moodKey = mood.trim().lowercase(Locale.getDefault())
        val set = usedSongsByMood.getOrPut(moodKey) { mutableSetOf() }

        songs.forEach { song ->
            set.add(key(song.title, song.artist))
        }
    }

    fun filterForMood(
        mood: String,
        songs: List<HomeActivity.Song>,
        minimumKeep: Int = 8
    ): List<HomeActivity.Song> {
        val moodKey = mood.trim().lowercase(Locale.getDefault())

        val otherMoodUsedSongs = usedSongsByMood
            .filterKeys { it != moodKey }
            .values
            .flatten()
            .toSet()

        val uniqueSongs = songs.filter { song ->
            key(song.title, song.artist) !in otherMoodUsedSongs
        }

        return if (uniqueSongs.size >= minimumKeep) {
            uniqueSongs
        } else {
            songs
        }
    }

    fun clearMood(mood: String) {
        usedSongsByMood.remove(mood.trim().lowercase(Locale.getDefault()))
    }

    fun clearAll() {
        usedSongsByMood.clear()
    }
}