package com.example.moodsync

object NowPlayingState {
    var currentTrack: SpotifyTrack? = null
    var isPlaying: Boolean = false
    var playbackPositionMs: Long = 0L
    var durationMs: Long = 0L

    // Global tracking to prevent duplicate history entries across activities
    var lastLoggedUri: String = ""
    var lastLoggedTimeMs: Long = 0L
    
    // Track-specific session state to prevent multiple logs of the SAME play session
    var hasLoggedCurrentTrack: Boolean = false
    var currentTrackMood: String? = null
    var activeSessionId: String = ""
    
    // Context for background logging
    var moodBefore: String = "calm"
    var source: String = "scan"
}
