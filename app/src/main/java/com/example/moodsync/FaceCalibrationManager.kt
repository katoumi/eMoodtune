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
        val centerVector: FloatArray,
        val leftVector: FloatArray = FloatArray(0),
        val rightVector: FloatArray = FloatArray(0)
    ) {
        // Backwards compatibility getter
        val vector: FloatArray get() = centerVector

        fun toJson(): String {
            val centerArray = JSONArray()
            for (v in centerVector) centerArray.put(v.toDouble())

            val leftArray = JSONArray()
            for (v in leftVector) leftArray.put(v.toDouble())

            val rightArray = JSONArray()
            for (v in rightVector) rightArray.put(v.toDouble())

            return JSONObject().apply {
                put("centerVector", centerArray)
                put("leftVector", leftArray)
                put("rightVector", rightArray)
            }.toString()
        }

        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (javaClass != other?.javaClass) return false
            other as FaceSignature
            return centerVector.contentEquals(other.centerVector)
        }

        override fun hashCode(): Int {
            return centerVector.contentHashCode()
        }

        companion object {
            fun fromJson(jsonStr: String): FaceSignature? {
                return try {
                    val json = JSONObject(jsonStr)
                    
                    // Support new format or fallback to single vector
                    if (json.has("centerVector")) {
                        val centerArr = json.getJSONArray("centerVector")
                        val center = FloatArray(centerArr.length()) { i -> centerArr.getDouble(i).toFloat() }

                        val leftArr = json.optJSONArray("leftVector")
                        val left = if (leftArr != null) FloatArray(leftArr.length()) { i -> leftArr.getDouble(i).toFloat() } else FloatArray(0)

                        val rightArr = json.optJSONArray("rightVector")
                        val right = if (rightArr != null) FloatArray(rightArr.length()) { i -> rightArr.getDouble(i).toFloat() } else FloatArray(0)

                        FaceSignature(center, left, right)
                    } else if (json.has("vector")) {
                        val arr = json.getJSONArray("vector")
                        val center = FloatArray(arr.length()) { i -> arr.getDouble(i).toFloat() }
                        FaceSignature(center)
                    } else null
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
     * Calculates Minimum Mean Absolute Landmark Distance (MAD) across registered angles.
     * Same Person: Error < 0.038. Stranger/Mother: Error >= 0.055+.
     * Returns true if min error < 0.042f.
     */
    fun verifyIdentity(liveSignature: FaceSignature, ownerSignature: FaceSignature): Boolean {
        val errCenter = calculateMeanAbsoluteDistance(liveSignature.centerVector, ownerSignature.centerVector)
        val errLeft = if (ownerSignature.leftVector.isNotEmpty()) calculateMeanAbsoluteDistance(liveSignature.centerVector, ownerSignature.leftVector) else errCenter
        val errRight = if (ownerSignature.rightVector.isNotEmpty()) calculateMeanAbsoluteDistance(liveSignature.centerVector, ownerSignature.rightVector) else errCenter

        val minError = minOf(errCenter, errLeft, errRight)
        Log.d("FaceVerification", "Live vs Owner Min MAD Error: ${"%.4f".format(minError)} (Threshold: 0.042)")
        
        return minError < 0.042f
    }

    fun calculateMeanAbsoluteDistance(v1: FloatArray, v2: FloatArray): Float {
        if (v1.size != v2.size || v1.isEmpty()) return 1.0f
        var totalDiff = 0.0
        for (i in v1.indices) {
            totalDiff += Math.abs((v1[i] - v2[i]).toDouble())
        }
        return (totalDiff / v1.size).toFloat()
    }
}
