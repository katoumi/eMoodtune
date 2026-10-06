package com.example.moodsync

import android.content.Context
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject

object FaceCalibrationManager {

    private const val PREF_NAME = "face_calibration_prefs"
    private const val KEY_BASELINE_BLENDSHAPES = "baseline_blendshapes"
    private const val KEY_FACE_SIGNATURE = "face_signature"
    private const val KEY_IS_CALIBRATED = "is_calibrated"

    data class FaceSignature(
        val vector: FloatArray // 1434-element 3D normalized spatial landmark vector
    ) {
        fun toJson(): String {
            val jsonArray = JSONArray()
            for (v in vector) {
                jsonArray.put(v.toDouble())
            }
            return JSONObject().apply {
                put("vector", jsonArray)
            }.toString()
        }

        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (javaClass != other?.javaClass) return false
            other as FaceSignature
            return vector.contentEquals(other.vector)
        }

        override fun hashCode(): Int {
            return vector.contentHashCode()
        }

        companion object {
            fun fromJson(jsonStr: String): FaceSignature? {
                return try {
                    val json = JSONObject(jsonStr)
                    val jsonArray = json.getJSONArray("vector")
                    val array = FloatArray(jsonArray.length())
                    for (i in 0 until jsonArray.length()) {
                        array[i] = jsonArray.getDouble(i).toFloat()
                    }
                    FaceSignature(array)
                } catch (e: Exception) {
                    null
                }
            }
        }
    }

    fun isCalibrated(context: Context): Boolean {
        return context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
            .getBoolean(KEY_IS_CALIBRATED, false)
    }

    fun saveCalibration(
        context: Context,
        baselineScores: Map<String, Float>,
        signature: FaceSignature
    ) {
        val prefs = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
        
        val jsonBlendshapes = JSONObject()
        baselineScores.forEach { (key, value) -> jsonBlendshapes.put(key, value) }

        prefs.edit()
            .putString(KEY_BASELINE_BLENDSHAPES, jsonBlendshapes.toString())
            .putString(KEY_FACE_SIGNATURE, signature.toJson())
            .putBoolean(KEY_IS_CALIBRATED, true)
            .apply()
    }

    fun getBaselineBlendshapes(context: Context): Map<String, Float> {
        val prefs = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
        val jsonStr = prefs.getString(KEY_BASELINE_BLENDSHAPES, "{}") ?: "{}"
        
        val map = mutableMapOf<String, Float>()
        try {
            val json = JSONObject(jsonStr)
            val keys = json.keys()
            while (keys.hasNext()) {
                val key = keys.next()
                map[key] = json.getDouble(key).toFloat()
            }
        } catch (_: Exception) {}
        return map
    }

    fun getFaceSignature(context: Context): FaceSignature? {
        val prefs = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
        val jsonStr = prefs.getString(KEY_FACE_SIGNATURE, "") ?: ""
        if (jsonStr.isBlank()) return null
        return FaceSignature.fromJson(jsonStr)
    }

    fun clearCalibration(context: Context) {
        context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE).edit().clear().apply()
    }

    /**
     * Calculates 1434-dimensional Cosine Similarity between live and owner facial vectors.
     * Returns true if similarity >= 0.965f (96.5% 3D mesh match).
     */
    fun verifyIdentity(liveSignature: FaceSignature, ownerSignature: FaceSignature): Boolean {
        val similarity = calculateCosineSimilarity(liveSignature.vector, ownerSignature.vector)
        Log.d("FaceVerification", "Live vs Owner 1434D Cosine Similarity: ${"%.4f".format(similarity)} (Threshold: 0.965)")
        return similarity >= 0.965f
    }

    fun calculateCosineSimilarity(v1: FloatArray, v2: FloatArray): Float {
        if (v1.size != v2.size || v1.isEmpty()) return 0f
        var dotProduct = 0.0
        var normA = 0.0
        var normB = 0.0
        for (i in v1.indices) {
            val a = v1[i].toDouble()
            val b = v2[i].toDouble()
            dotProduct += a * b
            normA += a * a
            normB += b * b
        }
        val denominator = Math.sqrt(normA) * Math.sqrt(normB)
        if (denominator == 0.0) return 0f
        return (dotProduct / denominator).toFloat()
    }
}
