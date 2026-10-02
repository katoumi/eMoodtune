package com.example.moodsync

import android.Manifest
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.content.pm.PackageManager
import android.graphics.Rect
import android.graphics.RectF
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.CameraSelector
import androidx.camera.core.ExperimentalGetImage
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.face.Face
import com.google.mlkit.vision.face.FaceDetection
import com.google.mlkit.vision.face.FaceDetector
import com.google.mlkit.vision.face.FaceDetectorOptions
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

@ExperimentalGetImage
class ScanActivity : AppCompatActivity(), SensorEventListener {

    private lateinit var scanRoot: View
    private lateinit var scanContent: View
    private lateinit var tvAppName: TextView
    private lateinit var tvFaceMoodTitle: TextView
    private lateinit var cameraContainer: View

    private lateinit var faceOverlay: FaceBoxOverlayView
    private lateinit var imgProfile: ImageView
    private lateinit var tabHome: TextView
    private lateinit var tabHistory: TextView
    private lateinit var tabPlaylist: TextView

    private lateinit var navHome: LinearLayout
    private lateinit var navScan: LinearLayout
    private lateinit var navProfile: LinearLayout

    private lateinit var previewView: PreviewView
    private lateinit var scannerGroup: FrameLayout
    private lateinit var btnAnalyze: ImageButton
    private lateinit var tvStatus: TextView

    private val handler = Handler(Looper.getMainLooper())

    private lateinit var cameraExecutor: ExecutorService
    private lateinit var faceDetector: FaceDetector
    private lateinit var moodFaceLandmarkerHelper: MoodFaceLandmarkerHelper

    private lateinit var sensorManager: SensorManager
    private var lightSensor: Sensor? = null
    private var isLowLight = false
    private var isFlashActive = false

    private var isScanning = false
    private var analysisEnabled = false
    private var hasProcessedResult = false

    private var lastMappedFaceRect: RectF? = null
    private var latestDetectedFace: Face? = null
    private var finalDetectedMood: String? = null

    private enum class ScanState {
        IDLE,
        REQUESTING_PERMISSION,
        OPENING_CAMERA,
        DETECTING_FACE,
        ANALYZING_EMOTION,
        RESULT_READY,
        NO_FACE_FOUND,
        ERROR
    }

    private val cameraPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) {
                startCameraPreviewThenScan()
            } else {
                updateScanState(ScanState.ERROR, "Camera permission denied")
                resetToIdleAfterDelay()
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_scan)

        NotificationNavHelper.setup(this)

        bindViews()
        setupTopTabs()
        setupBottomNav()
        setupProfileButton()
        setupLightSensor()

        cameraExecutor = Executors.newSingleThreadExecutor()
        setupFaceDetector()

        moodFaceLandmarkerHelper = MoodFaceLandmarkerHelper(
            context = this,
            onMoodResult = { result ->
                runOnUiThread {
                    if (!hasProcessedResult && analysisEnabled) {
                        finalizeScanWithMood(result.mood.lowercase())
                    }
                }
            },
            onError = { error ->
                runOnUiThread {
                    if (!hasProcessedResult && analysisEnabled) {
                        val fallbackFace = latestDetectedFace
                        if (fallbackFace != null) {
                            finalizeScanWithMood(detectEmotionFromFace(fallbackFace))
                        } else {
                            updateScanState(ScanState.ERROR, error)
                            resetToIdleAfterDelay()
                        }
                    }
                }
            }
        )

        showIdleUI()
        ProfileImageLoader.load(imgProfile)

        btnAnalyze.setOnClickListener {
            if (!isScanning) {
                openCameraAndScan()
            }
        }
    }

    override fun onResume() {
        super.onResume()
        ProfileImageLoader.load(imgProfile)
        lightSensor?.let {
            sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_UI)
        }
    }

    override fun onPause() {
        super.onPause()
        sensorManager.unregisterListener(this)
    }

    private fun bindViews() {
        scanRoot = findViewById(R.id.scanRoot)
        scanContent = findViewById(R.id.scanContent)
        tvAppName = findViewById(R.id.tvAppName)
        tvFaceMoodTitle = findViewById(R.id.tvFaceMoodTitle)
        cameraContainer = findViewById(R.id.cameraContainer)

        imgProfile = findViewById(R.id.imgProfile)

        tabHome = findViewById(R.id.tabHome)
        tabHistory = findViewById(R.id.tabHistory)
        tabPlaylist = findViewById(R.id.tabPlaylist)

        navHome = findViewById(R.id.navHome)
        navScan = findViewById(R.id.navScan)
        navProfile = findViewById(R.id.navProfile)

        previewView = findViewById(R.id.previewView)
        scannerGroup = findViewById(R.id.scannerGroup)
        btnAnalyze = findViewById(R.id.btnAnalyze)
        tvStatus = findViewById(R.id.tvStatus)
        faceOverlay = findViewById(R.id.faceOverlay)
    }

    private fun setupProfileButton() {
        imgProfile.setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
        }
    }

    private fun setupTopTabs() {
        tabHome.setOnClickListener {
            val intent = Intent(this, HomeActivity::class.java)
            intent.addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
            startActivity(intent)
        }

        tabHistory.setOnClickListener {
            val intent = Intent(this, HistoryActivity::class.java)
            intent.addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
            startActivity(intent)
        }

        tabPlaylist.setOnClickListener {
            val intent = Intent(this, PlaylistActivity::class.java)
            intent.addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
            startActivity(intent)
        }
    }

    private fun setupBottomNav() {
        navHome.setOnClickListener {
            val intent = Intent(this, HomeActivity::class.java)
            intent.addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
            startActivity(intent)
        }

        navScan.setOnClickListener {
            // already here
        }

        navProfile.setOnClickListener {
            val intent = Intent(this, SettingsActivity::class.java)
            intent.addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
            startActivity(intent)
        }
    }

    private fun setupFaceDetector() {
        val options = FaceDetectorOptions.Builder()
            .setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_ACCURATE)
            .setClassificationMode(FaceDetectorOptions.CLASSIFICATION_MODE_ALL)
            .enableTracking()
            .build()

        faceDetector = FaceDetection.getClient(options)
    }

    private fun setupLightSensor() {
        sensorManager = getSystemService(Context.SENSOR_SERVICE) as SensorManager
        lightSensor = sensorManager.getDefaultSensor(Sensor.TYPE_LIGHT)
    }

    override fun onSensorChanged(event: SensorEvent?) {
        if (event != null && event.sensor.type == Sensor.TYPE_LIGHT) {
            val lux = event.values[0]
            val roomIsDark = lux < 15f
            
            // LATCH LOGIC: If we are scanning and the room IS dark, turn on the flash.
            // Once the flash is on, we KEEP it on regardless of sensor readings 
            // (since the screen light itself will brighten the sensor).
            if (isScanning && roomIsDark && !isFlashActive) {
                isLowLight = true
                isFlashActive = true
                applyLowLightTheming(true)
            }
        }
    }

    private fun applyLowLightTheming(enabled: Boolean) {
        if (enabled) {
            // LIGHT MODE (When Dark) - Use screen as light source
            scanRoot.setBackgroundColor(Color.WHITE)
            scanContent.setBackgroundColor(Color.WHITE)
            cameraContainer.setBackgroundColor(Color.WHITE)
            
            tvAppName.setTextColor(Color.BLACK)
            tvFaceMoodTitle.setTextColor(Color.BLACK)
            tvStatus.setTextColor(Color.BLACK)
            
            tabHome.setTextColor(Color.DKGRAY)
            tabHistory.setTextColor(Color.DKGRAY)
            tabPlaylist.setTextColor(Color.DKGRAY)

            // Maximize brightness for optimal lighting
            val lp = window.attributes
            lp.screenBrightness = 1.0f
            window.attributes = lp
        } else {
            // DARK MODE (Standard)
            scanRoot.setBackgroundColor(Color.parseColor("#090312"))
            scanContent.setBackgroundColor(Color.TRANSPARENT)
            cameraContainer.setBackgroundResource(R.drawable.bg_scan_curved_container)
            
            tvAppName.setTextColor(Color.WHITE)
            tvFaceMoodTitle.setTextColor(Color.parseColor("#E9E1FF"))
            tvStatus.setTextColor(Color.parseColor("#E9E1FF"))
            
            tabHome.setTextColor(Color.parseColor("#D3CFF0"))
            tabHistory.setTextColor(Color.parseColor("#D3CFF0"))
            tabPlaylist.setTextColor(Color.parseColor("#D3CFF0"))

            // Revert brightness to system default
            val lp = window.attributes
            lp.screenBrightness = -1.0f
            window.attributes = lp
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}

    private fun showIdleUI() {
        isScanning = false
        isFlashActive = false
        applyLowLightTheming(false)

        analysisEnabled = false
        hasProcessedResult = false
        lastMappedFaceRect = null
        latestDetectedFace = null
        finalDetectedMood = null

        previewView.visibility = View.GONE
        scannerGroup.visibility = View.VISIBLE
        btnAnalyze.isEnabled = true

        faceOverlay.visibility = View.GONE
        faceOverlay.setFaceRect(null)

        tvStatus.text = "Tap the camera button to scan your mood"
    }

    private fun openCameraAndScan() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
            == PackageManager.PERMISSION_GRANTED
        ) {
            startCameraPreviewThenScan()
        } else {
            updateScanState(ScanState.REQUESTING_PERMISSION, "Requesting camera permission...")
            cameraPermissionLauncher.launch(Manifest.permission.CAMERA)
        }
    }

    private fun startCameraPreviewThenScan() {
        isScanning = true
        analysisEnabled = false
        hasProcessedResult = false
        lastMappedFaceRect = null
        latestDetectedFace = null
        finalDetectedMood = null

        moodFaceLandmarkerHelper.resetSession()

        faceOverlay.visibility = View.VISIBLE
        faceOverlay.setFaceRect(null)

        previewView.visibility = View.VISIBLE
        scannerGroup.visibility = View.GONE
        btnAnalyze.isEnabled = false

        updateScanState(ScanState.OPENING_CAMERA, "Opening camera...")

        startCamera()

        handler.postDelayed({
            if (!hasProcessedResult) {
                analysisEnabled = true
                updateScanState(ScanState.DETECTING_FACE, "Detecting face...")
            }
        }, 800)

        handler.postDelayed({
            if (!hasProcessedResult) {
                analysisEnabled = false

                val fallbackFace = latestDetectedFace
                if (fallbackFace != null) {
                    finalizeScanWithMood(detectEmotionFromFace(fallbackFace))
                } else {
                    updateScanState(ScanState.NO_FACE_FOUND, "No stable mood detected. Try again.")
                    resetToIdleAfterDelay()
                }
            }
        }, 5000)
    }

    private fun startCamera() {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(this)

        cameraProviderFuture.addListener({
            val cameraProvider = cameraProviderFuture.get()

            val preview = Preview.Builder().build().also {
                it.setSurfaceProvider(previewView.surfaceProvider)
            }

            val imageAnalysis = ImageAnalysis.Builder()
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .build()

            imageAnalysis.setAnalyzer(cameraExecutor) { imageProxy ->
                analyzeFace(imageProxy)
            }

            val cameraSelector = CameraSelector.DEFAULT_FRONT_CAMERA

            try {
                cameraProvider.unbindAll()
                cameraProvider.bindToLifecycle(
                    this,
                    cameraSelector,
                    preview,
                    imageAnalysis
                )
            } catch (e: Exception) {
                updateScanState(ScanState.ERROR, "Failed to start camera")
                showIdleUI()
            }
        }, ContextCompat.getMainExecutor(this))
    }

    private fun analyzeFace(imageProxy: ImageProxy) {
        if (!analysisEnabled || hasProcessedResult) {
            imageProxy.close()
            return
        }

        val mediaImage = imageProxy.image
        if (mediaImage == null) {
            imageProxy.close()
            return
        }

        val inputImage = InputImage.fromMediaImage(
            mediaImage,
            imageProxy.imageInfo.rotationDegrees
        )

        faceDetector.process(inputImage)
            .addOnSuccessListener { faces ->
                if (faces.isNotEmpty()) {
                    val face = faces.first()
                    latestDetectedFace = face

                    runOnUiThread {
                        val rotation = imageProxy.imageInfo.rotationDegrees

                        val mappedRect = if (rotation == 90 || rotation == 270) {
                            mapFaceRectToOverlay(
                                face.boundingBox,
                                imageProxy.height,
                                imageProxy.width,
                                faceOverlay.width,
                                faceOverlay.height
                            )
                        } else {
                            mapFaceRectToOverlay(
                                face.boundingBox,
                                imageProxy.width,
                                imageProxy.height,
                                faceOverlay.width,
                                faceOverlay.height
                            )
                        }

                        lastMappedFaceRect = mappedRect
                        faceOverlay.setFaceRect(mappedRect)
                    }

                    if (analysisEnabled && !hasProcessedResult) {
                        collectEmotionPrediction()
                    }
                } else {
                    latestDetectedFace = null
                    runOnUiThread {
                        lastMappedFaceRect = null
                        faceOverlay.setFaceRect(null)
                    }
                }
            }
            .addOnFailureListener {
                if (!hasProcessedResult) {
                    updateScanState(ScanState.ERROR, "Face detection failed")
                }
            }
            .addOnCompleteListener {
                imageProxy.close()
            }
    }

    private fun collectEmotionPrediction() {
        if (hasProcessedResult || !analysisEnabled) return

        val fullBitmap = previewView.bitmap ?: return
        val rectF = lastMappedFaceRect ?: return

        val left = rectF.left.toInt().coerceAtLeast(0)
        val top = rectF.top.toInt().coerceAtLeast(0)
        val right = rectF.right.toInt().coerceAtMost(fullBitmap.width)
        val bottom = rectF.bottom.toInt().coerceAtMost(fullBitmap.height)

        if (right <= left || bottom <= top) return

        val faceBitmap = try {
            BitmapCropper.crop(fullBitmap, Rect(left, top, right, bottom))
        } catch (_: Exception) {
            null
        } ?: fullBitmap

        runOnUiThread {
            updateScanState(ScanState.ANALYZING_EMOTION, "Analyzing emotion...")
        }

        moodFaceLandmarkerHelper.analyzeBitmap(faceBitmap)
    }

    private fun finalizeScanWithMood(mood: String) {
        if (hasProcessedResult) return

        hasProcessedResult = true
        analysisEnabled = false
        isScanning = false
        isFlashActive = false
        applyLowLightTheming(false)

        finalDetectedMood = mood

        updateScanState(
            ScanState.RESULT_READY,
            "Detected: ${mood.replaceFirstChar { it.uppercase() }}"
        )

        handler.postDelayed({
            lastMappedFaceRect = null
            faceOverlay.setFaceRect(null)
            goToHomeWithEmotion(mood)
        }, 700)
    }

    private fun mapFaceRectToOverlay(
        boundingBox: Rect,
        imageWidth: Int,
        imageHeight: Int,
        overlayWidth: Int,
        overlayHeight: Int
    ): RectF {
        val scaleX = overlayWidth.toFloat() / imageWidth.toFloat()
        val scaleY = overlayHeight.toFloat() / imageHeight.toFloat()
        val scale = maxOf(scaleX, scaleY)

        val scaledWidth = imageWidth * scale
        val scaledHeight = imageHeight * scale

        val offsetX = (scaledWidth - overlayWidth) / 2f
        val offsetY = (scaledHeight - overlayHeight) / 2f

        val left = boundingBox.left * scale - offsetX
        val top = boundingBox.top * scale - offsetY
        val right = boundingBox.right * scale - offsetX
        val bottom = boundingBox.bottom * scale - offsetY

        return RectF(
            overlayWidth - right,
            top,
            overlayWidth - left,
            bottom
        )
    }

    private fun detectEmotionFromFace(face: Face): String {
        val smile = face.smilingProbability ?: 0f
        val leftEye = face.leftEyeOpenProbability ?: 0f
        val rightEye = face.rightEyeOpenProbability ?: 0f
        val eyeOpen = (leftEye + rightEye) / 2f

        return when {
            smile > 0.80f && eyeOpen > 0.45f -> "happy"
            // Extremely high bar for sad/angry in fallback to favor calm
            smile < 0.05f && eyeOpen < 0.20f -> "sad" 
            smile < 0.04f && eyeOpen > 0.90f -> "angry"
            else -> "calm"
        }
    }

    private fun goToHomeWithEmotion(detectedEmotion: String) {
        val previousSessionId = intent.getStringExtra("previous_session_id")

        val intent = Intent(this, HomeActivity::class.java).apply {
            putExtra("emotion", detectedEmotion)

            if (!previousSessionId.isNullOrBlank()) {
                putExtra("previous_session_id", previousSessionId)
            }
        }

        startActivity(intent)
        finish()
    }

    private fun updateScanState(state: ScanState, message: String) {
        tvStatus.text = message

        when (state) {
            ScanState.IDLE -> btnAnalyze.isEnabled = true
            ScanState.REQUESTING_PERMISSION,
            ScanState.OPENING_CAMERA,
            ScanState.DETECTING_FACE,
            ScanState.ANALYZING_EMOTION,
            ScanState.RESULT_READY,
            ScanState.NO_FACE_FOUND,
            ScanState.ERROR -> btnAnalyze.isEnabled = false
        }
    }

    private fun resetToIdleAfterDelay(delayMillis: Long = 1400) {
        handler.postDelayed({
            showIdleUI()
        }, delayMillis)
    }

    override fun onDestroy() {
        super.onDestroy()
        handler.removeCallbacksAndMessages(null)

        if (::faceDetector.isInitialized) {
            faceDetector.close()
        }

        if (::cameraExecutor.isInitialized) {
            cameraExecutor.shutdown()
        }

        if (::moodFaceLandmarkerHelper.isInitialized) {
            moodFaceLandmarkerHelper.close()
        }
    }
}

/**
 * Small helper so we don't repeat Bitmap.createBitmap try/catch inline.
 */
private object BitmapCropper {
    fun crop(bitmap: android.graphics.Bitmap, rect: Rect): android.graphics.Bitmap {
        return android.graphics.Bitmap.createBitmap(
            bitmap,
            rect.left,
            rect.top,
            rect.width(),
            rect.height()
        )
    }
}