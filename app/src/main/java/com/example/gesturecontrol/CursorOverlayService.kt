package com.example.gesturecontrol

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.Point
import android.media.AudioManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log
import android.view.Gravity
import android.view.WindowManager
import android.widget.ImageView
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleService
import com.google.mediapipe.framework.image.BitmapImageBuilder
import com.google.mediapipe.tasks.components.containers.NormalizedLandmark
import com.google.mediapipe.tasks.core.BaseOptions
import com.google.mediapipe.tasks.vision.core.RunningMode
import com.google.mediapipe.tasks.vision.handlandmarker.HandLandmarker
import com.google.mediapipe.tasks.vision.handlandmarker.HandLandmarkerResult
import java.util.concurrent.Executors
import kotlin.math.sqrt

class CursorOverlayService : LifecycleService() {

    private lateinit var windowManager: WindowManager
    private lateinit var cursorView: ImageView
    private var cursorX = 540f
    private var cursorY = 960f
    private val cursorParams by lazy {
        WindowManager.LayoutParams(
            60, 60,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            else
                WindowManager.LayoutParams.TYPE_PHONE,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
            WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = cursorX.toInt()
            y = cursorY.toInt()
        }
    }

    private var handLandmarker: HandLandmarker? = null
    private var cameraProvider: ProcessCameraProvider? = null
    private val cameraExecutor = Executors.newSingleThreadExecutor()
    private val mainHandler = Handler(Looper.getMainLooper())

    private var lastGesture = Gesture.NONE
    private var gestureStartTime = 0L
    private var lastActionTime = 0L
    private val cooldownMs = 500L

    private val xHistory = ArrayDeque<Float>(5)
    private val yHistory = ArrayDeque<Float>(5)

    private var screenWidth = 1080
    private var screenHeight = 1920

    enum class Gesture { NONE, POINTER, PINCH, PEACE, FIST }

    override fun onCreate() {
        super.onCreate()
        startAsForeground()

        windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val size = Point()
        @Suppress("DEPRECATION")
        windowManager.defaultDisplay.getRealSize(size)
        screenWidth = size.x
        screenHeight = size.y

        setupCursor()
        initHandLandmarker()
    }

    private fun startAsForeground() {
        val channelId = "gesture_control"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(channelId, "Gesture Control", NotificationManager.IMPORTANCE_LOW)
            (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).createNotificationChannel(channel)
        }
        val notification = NotificationCompat.Builder(this, channelId)
            .setContentTitle("Gesture Control Active")
            .setContentText("Controlling device with hand gestures")
            .setSmallIcon(android.R.drawable.ic_menu_camera)
            .build()
        startForeground(1, notification)
    }

    private fun setupCursor() {
        cursorView = ImageView(this).apply {
            setImageResource(android.R.drawable.ic_menu_add)
            setColorFilter(android.graphics.Color.GREEN)
        }
        try {
            windowManager.addView(cursorView, cursorParams)
        } catch (e: Exception) {
            Log.e("GestureControl", "Cursor overlay failed", e)
        }
    }

    private fun initHandLandmarker() {
        try {
            val baseOptions = BaseOptions.builder()
                .setModelAssetPath("hand_landmarker.task")
                .build()

            val options = HandLandmarker.HandLandmarkerOptions.builder()
                .setBaseOptions(baseOptions)
                .setRunningMode(RunningMode.LIVE_STREAM)
                .setNumHands(1)
                .setMinHandDetectionConfidence(0.5f)
                .setMinHandPresenceConfidence(0.5f)
                .setMinTrackingConfidence(0.5f)
                .setResultListener(this::handleResult)
                .setErrorListener { e -> Log.e("HandLandmarker", "Error", e) }
                .build()

            handLandmarker = HandLandmarker.createFromOptions(this, options)
            startCamera()
        } catch (e: Exception) {
            Log.e("GestureControl", "Model load failed", e)
        }
    }

    private fun startCamera() {
        val future = ProcessCameraProvider.getInstance(this)
        future.addListener({
            cameraProvider = future.get()

            val analysis = ImageAnalysis.Builder()
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888)
                .build()

            analysis.setAnalyzer(cameraExecutor) { proxy ->
                processFrame(proxy)
            }

            try {
                cameraProvider?.unbindAll()
                cameraProvider?.bindToLifecycle(this, CameraSelector.DEFAULT_FRONT_CAMERA, analysis)
            } catch (e: Exception) {
                Log.e("GestureControl", "Camera failed", e)
            }
        }, ContextCompat.getMainExecutor(this))
    }

    private fun processFrame(imageProxy: ImageProxy) {
        val bitmap = imageProxy.toBitmap()
        imageProxy.close()
        val mpImage = BitmapImageBuilder(bitmap).build()
        handLandmarker?.detectAsync(mpImage, System.currentTimeMillis())
    }

    private fun ImageProxy.toBitmap(): android.graphics.Bitmap {
        val bitmap = android.graphics.Bitmap.createBitmap(width, height, android.graphics.Bitmap.Config.ARGB_8888)
        bitmap.copyPixelsFromBuffer(planes[0].buffer)
        return bitmap
    }

    private fun handleResult(result: HandLandmarkerResult, image: com.google.mediapipe.framework.image.MPImage) {
        if (result.landmarks().isEmpty()) return

        val landmarks = result.landmarks()[0]
        val gesture = recognizeGesture(landmarks)

        val indexTip = landmarks[8]
        val rawX = (1f - indexTip.x()) * screenWidth
        val rawY = indexTip.y() * screenHeight

        xHistory.addLast(rawX)
        yHistory.addLast(rawY)
        if (xHistory.size > 5) { xHistory.removeFirst(); yHistory.removeFirst() }

        cursorX = xHistory.average().toFloat().coerceIn(0f, screenWidth - 60f)
        cursorY = yHistory.average().toFloat().coerceIn(0f, screenHeight - 60f)

        mainHandler.post {
            updateCursor()
            executeGesture(gesture)
        }
    }

    private fun updateCursor() {
        cursorParams.x = cursorX.toInt()
        cursorParams.y = cursorY.toInt()
        try { windowManager.updateViewLayout(cursorView, cursorParams) } catch (_: Exception) {}
    }

    private fun recognizeGesture(landmarks: List<NormalizedLandmark>): Gesture {
        val wrist = landmarks[0]
        val thumbTip = landmarks[4]
        val indexTip = landmarks[8]
        val middleTip = landmarks[12]
        val ringTip = landmarks[16]
        val pinkyTip = landmarks[20]

        val indexMcp = landmarks[5]
        val middleMcp = landmarks[9]
        val ringMcp = landmarks[13]
        val pinkyMcp = landmarks[17]

        fun dist(a: NormalizedLandmark, b: NormalizedLandmark) =
            sqrt((a.x()-b.x())*(a.x()-b.x()) + (a.y()-b.y())*(a.y()-b.y()))

        fun isExtended(tip: NormalizedLandmark, mcp: NormalizedLandmark) =
            dist(tip, wrist) > dist(mcp, wrist) * 1.3f

        val indexOn = isExtended(indexTip, indexMcp)
        val middleOn = isExtended(middleTip, middleMcp)
        val ringOn = isExtended(ringTip, ringMcp)
        val pinkyOn = isExtended(pinkyTip, pinkyMcp)
        val pinch = dist(thumbTip, indexTip) < 0.06f

        return when {
            pinch && indexOn -> Gesture.PINCH
            indexOn && middleOn && !ringOn && !pinkyOn -> Gesture.PEACE
            !indexOn && !middleOn && !ringOn && !pinkyOn -> Gesture.FIST
            indexOn && !middleOn && !ringOn && !pinkyOn -> Gesture.POINTER
            else -> Gesture.NONE
        }
    }

    private fun executeGesture(gesture: Gesture) {
        val now = System.currentTimeMillis()
        if (gesture == lastGesture && now - gestureStartTime < cooldownMs) return
        if (gesture != lastGesture) gestureStartTime = now
        lastGesture = gesture

        if (gesture != Gesture.POINTER && now - lastActionTime < cooldownMs) return
        if (gesture != Gesture.POINTER) lastActionTime = now

        val svc = GestureAccessibilityService.instance ?: return
        val cx = cursorX + 30
        val cy = cursorY + 30

        when (gesture) {
            Gesture.PINCH -> {
                val path = Path().apply { moveTo(cx, cy) }
                val g = android.accessibilityservice.GestureDescription.Builder()
                    .addStroke(android.accessibilityservice.GestureDescription.StrokeDescription(path, 0, 100))
                    .build()
                svc.dispatchGesture(g, null, null)
            }
            Gesture.PEACE -> {
                val path = Path().apply { moveTo(cx, cy); lineTo(cx, cy - 400) }
                val g = android.accessibilityservice.GestureDescription.Builder()
                    .addStroke(android.accessibilityservice.GestureDescription.StrokeDescription(path, 0, 400))
                    .build()
                svc.dispatchGesture(g, null, null)
            }
            Gesture.FIST -> {
                val audio = getSystemService(Context.AUDIO_SERVICE) as AudioManager
                audio.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_TOGGLE_MUTE, 0)
            }
            else -> {}
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        cameraProvider?.unbindAll()
        handLandmarker?.close()
        cameraExecutor.shutdown()
        try { windowManager.removeView(cursorView) } catch (_: Exception) {}
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
