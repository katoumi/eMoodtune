package com.example.moodsync

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Rect
import android.util.Log
import org.tensorflow.lite.Interpreter
import java.io.BufferedReader
import java.io.InputStreamReader
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min

class EmotionClassifier(private val context: Context) {

    data class Prediction(
        val label: String,
        val confidence: Float,
        val allScores: Map<String, Float>
    )

    private var interpreter: Interpreter? = null
    private var labels: List<String> = defaultLabels()

    companion object {
        private const val TAG = "EmotionClassifier"

        private const val MODEL_FILE = "emotion_model.tflite"
        private const val LABEL_FILE = "emotion_labels.txt"

        // For the model we are using now
        private const val INPUT_WIDTH = 64
        private const val INPUT_HEIGHT = 64
        private const val INPUT_CHANNELS = 3
        private const val OUTPUT_CLASSES = 7
    }

    init {
        loadLabels()
        loadModel()
    }

    private fun defaultLabels(): List<String> {
        return listOf(
            "angry",
            "disgust",
            "fear",
            "happy",
            "neutral",
            "sad",
            "surprise"
        )
    }

    private fun loadLabels() {
        try {
            context.assets.open(LABEL_FILE).use { inputStream ->
                BufferedReader(InputStreamReader(inputStream)).use { reader ->
                    val loadedLabels = reader.readLines()
                        .map { it.trim() }
                        .filter { it.isNotEmpty() }

                    if (loadedLabels.isNotEmpty()) {
                        labels = loadedLabels
                        Log.d(TAG, "Labels loaded: $labels")
                    } else {
                        Log.w(TAG, "emotion_labels.txt is empty. Using default labels.")
                    }
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "No emotion_labels.txt found. Using default labels.")
        }
    }

    private fun loadModel() {
        try {
            val modelBytes = context.assets.open(MODEL_FILE).readBytes()
            val modelBuffer = ByteBuffer.allocateDirect(modelBytes.size).order(ByteOrder.nativeOrder())
            modelBuffer.put(modelBytes)
            modelBuffer.rewind()

            val options = Interpreter.Options().apply {
                setNumThreads(4)
            }

            interpreter = Interpreter(modelBuffer, options)
            Log.d(TAG, "TFLite emotion model loaded successfully.")
        } catch (e: Exception) {
            interpreter = null
            Log.e(TAG, "Failed to load TFLite model: ${e.message}", e)
        }
    }

    fun isModelReady(): Boolean {
        return interpreter != null
    }
    fun close() {
        interpreter?.close()
        interpreter = null
    }

    fun classifyFace(sourceBitmap: Bitmap, faceRect: Rect): Prediction? {
        val tfLite = interpreter ?: return null

        return try {
            val croppedFace = cropFace(sourceBitmap, faceRect) ?: return null
            val resizedFace = Bitmap.createScaledBitmap(croppedFace, INPUT_WIDTH, INPUT_HEIGHT, true)
            val inputBuffer = convertBitmapToRgbBuffer(resizedFace)

            val output = Array(1) { FloatArray(OUTPUT_CLASSES) }
            tfLite.run(inputBuffer, output)

            val probabilities = softmax(output[0])

            val resultMap = mutableMapOf<String, Float>()
            for (i in labels.indices) {
                if (i < probabilities.size) {
                    resultMap[labels[i]] = probabilities[i]
                }
            }

            val bestIndex = probabilities.indices.maxByOrNull { probabilities[it] } ?: 0
            val bestLabel = labels.getOrElse(bestIndex) { "neutral" }
            val bestConfidence = probabilities.getOrElse(bestIndex) { 0f }

            Prediction(
                label = bestLabel,
                confidence = bestConfidence,
                allScores = resultMap
            )
        } catch (e: Exception) {
            Log.e(TAG, "Emotion classification failed: ${e.message}", e)
            null
        }
    }

    private fun cropFace(bitmap: Bitmap, faceRect: Rect): Bitmap? {
        if (bitmap.width <= 0 || bitmap.height <= 0) return null

        val paddingX = (faceRect.width() * 0.18f).toInt()
        val paddingY = (faceRect.height() * 0.18f).toInt()

        val left = max(0, faceRect.left - paddingX)
        val top = max(0, faceRect.top - paddingY)
        val right = min(bitmap.width, faceRect.right + paddingX)
        val bottom = min(bitmap.height, faceRect.bottom + paddingY)

        val width = right - left
        val height = bottom - top

        if (width <= 1 || height <= 1) return null

        return Bitmap.createBitmap(bitmap, left, top, width, height)
    }

    private fun convertBitmapToRgbBuffer(bitmap: Bitmap): ByteBuffer {
        val buffer = ByteBuffer.allocateDirect(4 * INPUT_WIDTH * INPUT_HEIGHT * INPUT_CHANNELS)
        buffer.order(ByteOrder.nativeOrder())

        val pixels = IntArray(INPUT_WIDTH * INPUT_HEIGHT)
        bitmap.getPixels(pixels, 0, INPUT_WIDTH, 0, 0, INPUT_WIDTH, INPUT_HEIGHT)

        for (pixel in pixels) {
            val r = Color.red(pixel) / 255f
            val g = Color.green(pixel) / 255f
            val b = Color.blue(pixel) / 255f

            buffer.putFloat(r)
            buffer.putFloat(g)
            buffer.putFloat(b)
        }

        buffer.rewind()
        return buffer
    }

    private fun softmax(logits: FloatArray): FloatArray {
        if (logits.isEmpty()) return floatArrayOf()

        val maxLogit = logits.maxOrNull() ?: 0f
        val exps = FloatArray(logits.size)
        var sum = 0f

        for (i in logits.indices) {
            exps[i] = exp(logits[i] - maxLogit)
            sum += exps[i]
        }

        if (sum == 0f) return FloatArray(logits.size) { 0f }

        for (i in exps.indices) {
            exps[i] = exps[i] / sum
        }

        return exps
    }

    fun mapModelLabelToAppMood(modelLabel: String): String {
        return when (modelLabel.lowercase()) {
            "happy", "surprise" -> "Happy"
            "sad", "fear" -> "Sad"
            "angry", "disgust" -> "Angry"
            "neutral" -> "Calm"
            else -> "Calm"
        }
    }
}