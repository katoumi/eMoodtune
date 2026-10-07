package com.example.moodsync

import android.content.Context
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken

object MoodRecommendationCache {

    private const val PREF_NAME = "mood_recommendation_cache"
    private const val KEY_CACHE_DATA = "cached_results"

    data class CachedResult(
        val songs: List<HomeActivity.Song>,
        val trackCache: Map<String, SpotifyTrack>,
        val timestamp: Long = System.currentTimeMillis()
    )

    private var cache = mutableMapOf<String, CachedResult>()
    private val loading = mutableSetOf<String>()
    private val gson = Gson()

    fun init(context: Context) {
        try {
            val prefs = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
            val json = prefs.getString(KEY_CACHE_DATA, null)
            if (!json.isNullOrBlank()) {
                val type = object : TypeToken<MutableMap<String, CachedResult>>() {}.type
                val restored: MutableMap<String, CachedResult>? = gson.fromJson(json, type)
                if (restored != null) {
                    val now = System.currentTimeMillis()
                    cache = restored.filter { now - it.value.timestamp < 86_400_000L }.toMutableMap()
                }
            }
        } catch (_: Exception) {
            cache = mutableMapOf()
        }
    }

    private fun persist(context: Context) {
        try {
            val prefs = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
            val json = gson.toJson(cache)
            prefs.edit().putString(KEY_CACHE_DATA, json).apply()
        } catch (_: Exception) {}
    }

    fun get(mood: String): CachedResult? {
        return cache[mood]
    }

    fun has(mood: String): Boolean {
        return cache[mood] != null
    }

    fun set(context: Context, mood: String, result: CachedResult) {
        cache[mood] = result
        persist(context)
    }

    fun isLoading(mood: String): Boolean {
        return loading.contains(mood)
    }

    fun setLoading(mood: String, value: Boolean) {
        if (value) loading.add(mood) else loading.remove(mood)
    }

    fun remove(context: Context, mood: String) {
        cache.remove(mood)
        persist(context)
    }

    fun clear(context: Context) {
        cache.clear()
        loading.clear()
        context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE).edit().clear().apply()
    }
}