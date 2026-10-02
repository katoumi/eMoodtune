package com.example.moodsync

object MoodSongRepository {

    fun getSongs(): List<MoodSong> {
        return listOf(
            MoodSong(
                title = "Weightless",
                artist = "Marconi Union",
                moods = listOf("calm", "neutral"),
                energy = 25,
                tags = listOf("night", "evening", "calm")
            ),
            MoodSong(
                title = "Here Comes the Sun",
                artist = "The Beatles",
                moods = listOf("happy", "calm"),
                energy = 72,
                tags = listOf("morning", "afternoon", "uplift")
            ),
            MoodSong(
                title = "Fix You",
                artist = "Coldplay",
                moods = listOf("sad", "calm"),
                energy = 48,
                tags = listOf("night", "recovery", "comfort")
            ),
            MoodSong(
                title = "Blinding Lights",
                artist = "The Weeknd",
                moods = listOf("happy", "neutral"),
                energy = 88,
                tags = listOf("evening", "night", "friday")
            ),
            MoodSong(
                title = "Someone Like You",
                artist = "Adele",
                moods = listOf("sad"),
                energy = 38,
                tags = listOf("night", "emotion", "recovery")
            ),
            MoodSong(
                title = "Sunflower",
                artist = "Post Malone",
                moods = listOf("happy", "calm", "neutral"),
                energy = 65,
                tags = listOf("afternoon", "easy-listening")
            ),
            MoodSong(
                title = "Let Her Go",
                artist = "Passenger",
                moods = listOf("sad", "neutral"),
                energy = 40,
                tags = listOf("night", "comfort")
            ),
            MoodSong(
                title = "Lovely Day",
                artist = "Bill Withers",
                moods = listOf("happy", "calm"),
                energy = 70,
                tags = listOf("morning", "sunday", "uplift")
            ),
            MoodSong(
                title = "Ocean Eyes",
                artist = "Billie Eilish",
                moods = listOf("calm", "sad"),
                energy = 32,
                tags = listOf("night", "calm", "quiet")
            ),
            MoodSong(
                title = "Fight Song",
                artist = "Rachel Platten",
                moods = listOf("angry", "sad", "happy"),
                energy = 85,
                tags = listOf("recovery", "motivation", "morning")
            )
        )
    }
}