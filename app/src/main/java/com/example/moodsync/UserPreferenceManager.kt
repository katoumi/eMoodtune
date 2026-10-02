package com.example.moodsync

import android.content.Context
import android.util.Log
import org.json.JSONObject

object UserPreferenceManager {
    private const val PREF_NAME = "user_preferences"
    private const val KEY_GENRE_COUNTS = "genre_counts"
    private const val KEY_ARTIST_COUNTS = "artist_counts"
    private const val KEY_REGIONAL_RATIO = "regional_ratio" // opm vs international
    private const val MAX_SEEDS = 5

    fun trackArtistPlay(context: Context, artist: String) {
        if (artist.isBlank() || artist == "eMoodtune AI") return
        updateCount(context, KEY_ARTIST_COUNTS, artist)
    }

    fun trackGenreInterest(context: Context, genre: String) {
        if (genre.isBlank()) return
        updateCount(context, KEY_GENRE_COUNTS, genre.lowercase())
    }

    fun getPreferredGenres(context: Context): List<String> {
        return getTopItems(context, KEY_GENRE_COUNTS, 2)
    }

    fun getPreferredArtists(context: Context): List<String> {
        return getTopItems(context, KEY_ARTIST_COUNTS, 2)
    }

    fun trackRegionInterest(context: Context, isOPM: Boolean) {
        val key = if (isOPM) "opm" else "intl"
        updateCount(context, KEY_REGIONAL_RATIO, key)
    }

    /**
     * Returns a float between 0.0 and 1.0 representing the OPM preference ratio.
     * 0.0 = 100% International, 1.0 = 100% OPM, 0.5 = Perfectly balanced.
     */
    fun getRegionalRatio(context: Context): Float {
        val counts = getRegionalCounts(context)
        val opmCount = counts["opm"] ?: 0
        val intlCount = counts["intl"] ?: 0
        val total = opmCount + intlCount
        
        if (total == 0) return 0.5f // Default to 50/50 for new users
        return opmCount.toFloat() / total.toFloat()
    }

    /**
     * Returns true if user prefers OPM, false for International.
     * Defaults to true (local) if data is even.
     */
    fun prefersOPM(context: Context): Boolean {
        return getRegionalRatio(context) >= 0.5f
    }

    private fun getRegionalCounts(context: Context): Map<String, Int> {
        val prefs = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
        val jsonStr = prefs.getString(KEY_REGIONAL_RATIO, "{}") ?: "{}"
        val map = mutableMapOf<String, Int>()
        try {
            val json = JSONObject(jsonStr)
            val keys = json.keys()
            while (keys.hasNext()) {
                val k = keys.next()
                map[k] = json.getInt(k)
            }
        } catch (_: Exception) {}
        return map
    }

    private fun updateCount(context: Context, key: String, item: String) {
        val prefs = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
        val jsonStr = prefs.getString(key, "{}") ?: "{}"
        try {
            val json = JSONObject(jsonStr)
            val currentCount = json.optInt(item, 0)
            json.put(item, currentCount + 1)
            prefs.edit().putString(key, json.toString()).apply()
            Log.d("UserPref", "Updated $key for $item: ${currentCount + 1}")
        } catch (e: Exception) {
            Log.e("UserPref", "Failed to update count", e)
        }
    }

    private fun getTopItems(context: Context, key: String, limit: Int): List<String> {
        val prefs = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
        val jsonStr = prefs.getString(key, "{}") ?: "{}"
        return try {
            val json = JSONObject(jsonStr)
            val items = mutableListOf<Pair<String, Int>>()
            val keys = json.keys()
            while (keys.hasNext()) {
                val k = keys.next()
                items.add(k to json.getInt(k))
            }
            items.sortedByDescending { it.second }.take(limit).map { it.first }
        } catch (e: Exception) {
            emptyList()
        }
    }
}
