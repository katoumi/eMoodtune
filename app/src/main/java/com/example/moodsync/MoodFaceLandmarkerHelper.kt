package com.example.moodsync

import android.content.Context
import android.graphics.Bitmap
import android.os.SystemClock
import android.util.Log
import com.google.mediapipe.framework.image.BitmapImageBuilder
import com.google.mediapipe.framework.image.MPImage
import com.google.mediapipe.tasks.components.containers.Category
import com.google.mediapipe.tasks.core.BaseOptions
import com.google.mediapipe.tasks.vision.core.RunningMode
import com.google.mediapipe.tasks.vision.facelandmarker.FaceLandmarker
import com.google.mediapipe.tasks.vision.facelandmarker.FaceLandmarkerResult

class MoodFaceLandmarkerHelper(
    private val context: Context,
    private val onMoodResult: (MoodResult) -> Unit,
    private val onError: (String) -> Unit
) {

    data class MoodResult(
        val mood: String,
        val confidence: Float,
        val debugScores: Map<String, Float>
    )

    private data class FrameMood(
        val mood: String,
        val confidence: Float
    )

    companion object {
        private const val TAG = "MoodFaceDebug"
        private const val REQUIRED_FRAMES = 6
        private const val MAX_FRAMES = 12
        private const val STRONG_OVERRIDE_THRESHOLD = 0.35f
    }

    private var faceLandmarker: FaceLandmarker? = null
    private val frameMoods = mutableListOf<FrameMood>()

    var isCalibrationMode: Boolean = false
    var lastSignature: FaceCalibrationManager.FaceSignature? = null
    private var lastRawScores: Map<String, Float> = emptyMap()
    private var baselineScores: Map<String, Float> = emptyMap()
    private var ownerSignature: FaceCalibrationManager.FaceSignature? = null

    init {
        setup(context)
        baselineScores = FaceCalibrationManager.getBaselineBlendshapes(context)
        ownerSignature = FaceCalibrationManager.getFaceSignature(context)
    }

    private var centerSignature: FaceCalibrationManager.FaceSignature? = null
    private var leftSignature: FaceCalibrationManager.FaceSignature? = null
    private var rightSignature: FaceCalibrationManager.FaceSignature? = null

    fun saveCurrentAsCalibration(): Boolean {
        val sig = lastSignature
        val raw = lastRawScores
        if (sig != null && raw.isNotEmpty()) {
            val fullSig = FaceCalibrationManager.FaceSignature(
                centerVector = centerSignature?.centerVector ?: sig.centerVector,
                leftVector = leftSignature?.centerVector ?: sig.centerVector,
                rightVector = rightSignature?.centerVector ?: sig.centerVector
            )
            FaceCalibrationManager.saveCalibration(context, raw, fullSig)
            baselineScores = raw
            ownerSignature = fullSig
            return true
        }
        return false
    }

    fun captureAngle(stepIndex: Int): Boolean {
        val sig = lastSignature ?: return false
        when (stepIndex) {
            0 -> centerSignature = sig
            1 -> leftSignature = sig
            2 -> rightSignature = sig
        }
        return true
    }

    private fun setup(context: Context) {
        try {
            val baseOptions = BaseOptions.builder()
                .setModelAssetPath("face_landmarker.task")
                .build()

            val options = FaceLandmarker.FaceLandmarkerOptions.builder()
                .setBaseOptions(baseOptions)
                .setRunningMode(RunningMode.LIVE_STREAM)
                .setNumFaces(1)
                .setMinFaceDetectionConfidence(0.5f)
                .setMinFacePresenceConfidence(0.5f)
                .setMinTrackingConfidence(0.5f)
                .setOutputFaceBlendshapes(true)
                .setResultListener(this::handleResult)
                .setErrorListener {
                    onError(it.message ?: "Face Landmarker error")
                }
                .build()

            faceLandmarker = FaceLandmarker.createFromOptions(context, options)
            Log.d(TAG, "Face Landmarker initialized")
        } catch (e: Exception) {
            onError(e.message ?: "Initialization failed")
        }
    }

    fun analyzeBitmap(bitmap: Bitmap) {
        val mpImage = BitmapImageBuilder(bitmap).build()
        faceLandmarker?.detectAsync(mpImage, SystemClock.uptimeMillis())
    }

    fun close() {
        faceLandmarker?.close()
        faceLandmarker = null
    }

    fun resetSession() {
        frameMoods.clear()
        baselineScores = FaceCalibrationManager.getBaselineBlendshapes(context)
        ownerSignature = FaceCalibrationManager.getFaceSignature(context)
    }

    private fun extractGeometricSignature(landmarks: List<com.google.mediapipe.tasks.components.containers.NormalizedLandmark>?): FaceCalibrationManager.FaceSignature {
        if (landmarks == null || landmarks.isEmpty()) {
            return FaceCalibrationManager.FaceSignature(FloatArray(0))
        }

        // Landmark 1 is Nose Tip
        val nose = landmarks.getOrNull(1) ?: landmarks[0]
        val noseX = nose.x()
        val noseY = nose.y()
        val noseZ = nose.z()

        // Landmark 159 (Left pupil) and 386 (Right pupil)
        val leftEye = landmarks.getOrNull(159) ?: landmarks[0]
        val rightEye = landmarks.getOrNull(386) ?: landmarks[0]
        val eyeDist = Math.hypot((leftEye.x() - rightEye.x()).toDouble(), (leftEye.y() - rightEye.y()).toDouble()).toFloat().coerceAtLeast(0.001f)

        val vector = FloatArray(landmarks.size * 3)
        var idx = 0
        for (lm in landmarks) {
            // Translate origin to nose tip, then scale by eye distance for scale & translation invariance
            vector[idx++] = (lm.x() - noseX) / eyeDist
            vector[idx++] = (lm.y() - noseY) / eyeDist
            vector[idx++] = (lm.z() - noseZ) / eyeDist
        }

        return FaceCalibrationManager.FaceSignature(vector)
    }

    private fun handleResult(result: FaceLandmarkerResult, inputImage: MPImage) {
        val blendshapeOptional = result.faceBlendshapes()
        if (!blendshapeOptional.isPresent) return

        val face = blendshapeOptional.get().firstOrNull() ?: return
        
        // 1. Identity Verification
        val landmarks = result.faceLandmarks()
        if (!landmarks.isNullOrEmpty() && landmarks[0].isNotEmpty()) {
            val liveSignature = extractGeometricSignature(landmarks[0])
            lastSignature = liveSignature
            
            if (!isCalibrationMode && ownerSignature != null) {
                val isOwner = FaceCalibrationManager.verifyIdentity(liveSignature, ownerSignature!!)
                if (!isOwner) {
                    onError("Face mismatch. Only the owner can use the scanner.")
                    return // Block processing
                }
            }
        }

        val rawMap = mutableMapOf<String, Float>()
        val scores = mutableMapOf<String, Float>()
        for (c in face) {
            val rawScore = c.score()
            rawMap[c.categoryName()] = rawScore
            
            val baseScore = if (isCalibrationMode) 0f else (baselineScores[c.categoryName()] ?: 0f)
            // Baseline normalization: Subtract resting face muscle tension
            val adjustedScore = Math.max(0f, rawScore - baseScore)
            scores[c.categoryName()] = adjustedScore
        }

        lastRawScores = rawMap

        logBlendshapes(scores)

        val mood = mapBlendshapesToMood(scores)

        frameMoods.add(FrameMood(mood.mood, mood.confidence))
        if (frameMoods.size > MAX_FRAMES) frameMoods.removeAt(0)

        if (frameMoods.size >= REQUIRED_FRAMES) {
            val final = getVotedMood(mood)
            Log.d(
                TAG,
                "FINAL_VOTED_MOOD mood=${final.mood}, confidence=${"%.3f".format(final.confidence)}"
            )
            onMoodResult(final)
            frameMoods.clear()
        }
    }

    private fun mapBlendshapesToMood(scores: Map<String, Float>): MoodResult {
        fun s(name: String): Float = scores[name] ?: 0f

        val smileLeft = s("mouthSmileLeft")
        val smileRight = s("mouthSmileRight")
        val frownLeft = s("mouthFrownLeft")
        val frownRight = s("mouthFrownRight")
        val browDownLeft = s("browDownLeft")
        val browDownRight = s("browDownRight")
        val browInnerUp = s("browInnerUp")
        val eyeWideLeft = s("eyeWideLeft")
        val eyeWideRight = s("eyeWideRight")
        val jawOpen = s("jawOpen")

        val smile = smileLeft + smileRight
        val frown = frownLeft + frownRight
        val browDown = browDownLeft + browDownRight
        val eyeWide = eyeWideLeft + eyeWideRight

        var happy = smile * 2.2f
        
        // 1. MOUTH DEAD ZONE: Ignore minor resting mouth downturns
        val cleanFrown = if (frown < 0.18f) 0f else frown

        // 2. BROW-MOUTH LOCK: True sadness almost always involves raised inner brows.
        // If brows aren't moving, we assume it's just your resting face.
        val browSignal = if (browInnerUp < 0.15f) 0f else (browInnerUp * 2.0f)
        
        var sad = (cleanFrown * 1.2f) + browSignal
        
        // Strict Lock: If brows are still, wipe out the sad score entirely
        if (browInnerUp < 0.12f) sad = 0f

        // Easier angry
        var angry = (browDown * 2.5f) + (frown * 0.9f) + (eyeWide * 0.55f) + (jawOpen * 0.25f)

        if (smile < 0.20f) happy = 0f
        
        // 3. ELITE THRESHOLD: Require a very deliberate expression to trigger "Sad"
        if (sad < 0.68f) sad = 0f
        if (angry < 0.28f) angry = 0f

        // 4. DOMINANT CALM: Strong baseline for neutral state
        var calm = 0.50f + (0.90f * (1f - (happy + sad + angry)))
        if (calm < 0.15f) calm = 0.15f

        // Very cautious suppression for Calm
        if (sad > 0.75f && sad > calm * 0.90f) {
            calm *= 0.85f
        }
        if (angry > 0.22f && angry > calm * 0.78f) {
            calm *= 0.70f
        }

        val total = happy + sad + angry + calm

        val finalScores = linkedMapOf(
            "Happy" to (happy / total),
            "Sad" to (sad / total),
            "Angry" to (angry / total),
            "Calm" to (calm / total)
        )

        val bestEntry = finalScores.entries.maxByOrNull { it.value }
        val bestMood = bestEntry?.key ?: "Calm"
        val bestConfidence = bestEntry?.value ?: finalScores["Calm"] ?: 1f

        Log.d(
            TAG,
            "MOOD_SCORES " +
                    "happy=${"%.3f".format(finalScores["Happy"] ?: 0f)} " +
                    "sad=${"%.3f".format(finalScores["Sad"] ?: 0f)} " +
                    "angry=${"%.3f".format(finalScores["Angry"] ?: 0f)} " +
                    "calm=${"%.3f".format(finalScores["Calm"] ?: 0f)} " +
                    "=> best=$bestMood (${"%.3f".format(bestConfidence)})"
        )

        return MoodResult(
            mood = bestMood,
            confidence = bestConfidence,
            debugScores = finalScores
        )
    }

    private fun getVotedMood(last: MoodResult): MoodResult {
        val grouped = frameMoods.groupBy { it.mood }

        var bestMood = last.mood
        var bestScore = Float.MIN_VALUE
        var bestConfidence = last.confidence

        for ((mood, values) in grouped) {
            val count = values.size.toFloat()
            val avg = values.map { it.confidence }.average().toFloat()
            val score = count * avg

            Log.d(
                TAG,
                "VOTE mood=$mood count=$count avg=${"%.3f".format(avg)} score=${"%.3f".format(score)}"
            )

            if (score > bestScore) {
                bestScore = score
                bestMood = mood
                bestConfidence = avg
            }
        }

        val strong = frameMoods
            .filter { it.mood != "Calm" }
            .maxByOrNull { it.confidence }

        if (strong != null && strong.confidence > STRONG_OVERRIDE_THRESHOLD) {
            Log.d(TAG, "OVERRIDE → Strong emotion detected: ${strong.mood}")
            bestMood = strong.mood
            bestConfidence = strong.confidence
        }

        return MoodResult(bestMood, bestConfidence, last.debugScores)
    }

    private fun logBlendshapes(scores: Map<String, Float>) {
        fun s(n: String) = scores[n] ?: 0f

        Log.d(
            TAG,
            "BLENDSHAPES " +
                    "smileL=${"%.3f".format(s("mouthSmileLeft"))} " +
                    "smileR=${"%.3f".format(s("mouthSmileRight"))} " +
                    "frownL=${"%.3f".format(s("mouthFrownLeft"))} " +
                    "frownR=${"%.3f".format(s("mouthFrownRight"))} " +
                    "browDownL=${"%.3f".format(s("browDownLeft"))} " +
                    "browDownR=${"%.3f".format(s("browDownRight"))} " +
                    "browInnerUp=${"%.3f".format(s("browInnerUp"))} " +
                    "eyeWideL=${"%.3f".format(s("eyeWideLeft"))} " +
                    "eyeWideR=${"%.3f".format(s("eyeWideRight"))} " +
                    "jawOpen=${"%.3f".format(s("jawOpen"))}"
        )
    }
}