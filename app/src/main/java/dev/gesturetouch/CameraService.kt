package dev.gesturetouch

import android.Manifest
import android.app.KeyguardManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Matrix
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.util.Size
import android.widget.Toast
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleService
import com.google.mediapipe.framework.image.BitmapImageBuilder
import com.google.mediapipe.tasks.core.BaseOptions
import com.google.mediapipe.tasks.core.Delegate
import com.google.mediapipe.tasks.vision.core.RunningMode
import com.google.mediapipe.tasks.vision.facelandmarker.FaceLandmarker
import com.google.mediapipe.tasks.vision.gesturerecognizer.GestureRecognizer
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

/**
 * Всё "тяжёлое" живёт здесь, и только пока сервис запущен (кнопка Старт).
 * Нажал Стоп → сервис уничтожен → камера, модели и микрофон освобождены, потребление ~0.
 *
 * Экономия, пока сервис работает:
 *  - камера отвязывается при выключенном экране или на экране блокировки;
 *  - нет лица/руки 8 с → FPS падает до fpsIdle (по умолчанию 5), в кадре снова кто-то → сразу fpsActive;
 *  - пауза жестов → не считается лицо, только рука (ловит жест "продолжить") на 4 FPS;
 *  - маленький кадр (320x240), GPU-делегат, рука считается не в каждом кадре.
 */
class CameraService : LifecycleService() {

    companion object {
        const val ACTION_STOP = "dev.gesturetouch.STOP"
        const val ACTION_LISTEN = "dev.gesturetouch.LISTEN"
        @Volatile var instance: CameraService? = null
    }

    private val main = Handler(Looper.getMainLooper())
    private lateinit var executor: ExecutorService

    @Volatile private var face: FaceLandmarker? = null
    @Volatile private var hands: GestureRecognizer? = null

    private var provider: ProcessCameraProvider? = null
    private var bound = false

    // поле analyzer-потока
    private var buffer: Bitmap? = null
    private var rotated: Bitmap? = null
    private var rotatedCanvas: Canvas? = null
    private val rotMatrix = Matrix()
    private var lastFrame = 0L
    private var handCounter = 0
    @Volatile private var lastSeen = 0L
    @Volatile private var lastHandSeen = 0L

    // диагностика FPS для главного экрана
    private val faceCount = AtomicInteger()
    private val handCount = AtomicInteger()
    private var faceDelegate = "?"
    private var handDelegate = "?"
    private var lastUsedGpu = false
    private val ticker = object : Runnable {
        override fun run() {
            State.faceFps = faceCount.getAndSet(0)
            State.handFps = handCount.getAndSet(0)
            State.delegates = "лицо: $faceDelegate, руки: $handDelegate"
            main.postDelayed(this, 1000)
        }
    }

    private var recognizer: SpeechRecognizer? = null
    private var listening = false
    private val listenTimeout = Runnable {
        recognizer?.cancel()
        listening = false
    }

    private val faceDetector = FaceGestureDetector(
        onGesture = { GestureAccessibilityService.instance?.handle(it) },
        onPresence = { lastSeen = SystemClock.uptimeMillis() }
    )
    private val handDetector = HandGestureDetector(
        onGesture = { GestureAccessibilityService.instance?.handle(it) },
        onPresence = {
            val t = SystemClock.uptimeMillis()
            lastSeen = t
            lastHandSeen = t
        }
    )

    private val screenReceiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) {
            when (i.action) {
                Intent.ACTION_SCREEN_OFF -> unbindCamera()
                Intent.ACTION_SCREEN_ON -> if (screenReady()) bindCamera()
                Intent.ACTION_USER_PRESENT -> bindCamera()
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        instance = this
        Config.ensureLoaded(this)
        startAsForeground()
        executor = Executors.newSingleThreadExecutor()
        main.postDelayed(ticker, 1000)

        registerReceiver(screenReceiver, IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_OFF)
            addAction(Intent.ACTION_SCREEN_ON)
            addAction(Intent.ACTION_USER_PRESENT)
        })

        executor.execute {
            initModels()
            main.post { if (screenReady()) bindCamera() }
        }
    }

    // ---------- модели ----------

    private fun initModels() {
        val s = Config.cfg.settings
        face = create("face_landmarker.task", s.useGpu) { base ->
            FaceLandmarker.createFromOptions(
                this,
                FaceLandmarker.FaceLandmarkerOptions.builder()
                    .setBaseOptions(base)
                    .setRunningMode(RunningMode.LIVE_STREAM)
                    .setNumFaces(1)
                    .setOutputFaceBlendshapes(true)
                    .setResultListener { r, _ -> faceCount.incrementAndGet(); faceDetector.process(r) }
                    .setErrorListener { it.printStackTrace() }
                    .build()
            )
        }
        faceDelegate = if (lastUsedGpu) "GPU" else "CPU"
        hands = create("gesture_recognizer.task", s.useGpu) { base ->
            GestureRecognizer.createFromOptions(
                this,
                GestureRecognizer.GestureRecognizerOptions.builder()
                    .setBaseOptions(base)
                    .setRunningMode(RunningMode.LIVE_STREAM)
                    .setNumHands(1)
                    .setMinHandDetectionConfidence(0.4f)
                    .setMinHandPresenceConfidence(0.4f)
                    .setMinTrackingConfidence(0.4f)
                    .setResultListener { r, _ -> handCount.incrementAndGet(); handDetector.process(r) }
                    .setErrorListener { it.printStackTrace() }
                    .build()
            )
        }
        handDelegate = if (lastUsedGpu) "GPU" else "CPU"
    }

    /** Сначала GPU, при ошибке CPU. */
    private fun <T> create(model: String, gpu: Boolean, build: (BaseOptions) -> T): T? {
        fun base(useGpu: Boolean): BaseOptions {
            val b = BaseOptions.builder().setModelAssetPath(model)
            if (useGpu) b.setDelegate(Delegate.GPU)
            return b.build()
        }
        if (gpu) {
            val r = runCatching { build(base(true)) }
            if (r.isSuccess) { lastUsedGpu = true; return r.getOrNull() }
        }
        lastUsedGpu = false
        return runCatching { build(base(false)) }.getOrElse {
            toast("Не загрузилась модель $model: ${it.message}")
            null
        }
    }

    // ---------- камера ----------

    private fun screenReady(): Boolean {
        val pm = getSystemService(PowerManager::class.java)
        val km = getSystemService(KeyguardManager::class.java)
        return pm.isInteractive && !km.isKeyguardLocked
    }

    private fun bindCamera() {
        if (bound) return
        bound = true
        val future = ProcessCameraProvider.getInstance(this)
        future.addListener({
            if (!bound) return@addListener
            val p = future.get()
            provider = p
            p.unbindAll()
            p.bindToLifecycle(this, CameraSelector.DEFAULT_FRONT_CAMERA, buildAnalysis())
        }, ContextCompat.getMainExecutor(this))
    }

    private fun unbindCamera() {
        bound = false
        provider?.unbindAll()
    }

    private fun buildAnalysis(): ImageAnalysis {
        val s = Config.cfg.settings
        val analysis = ImageAnalysis.Builder()
            .setResolutionSelector(
                ResolutionSelector.Builder()
                    .setResolutionStrategy(
                        ResolutionStrategy(Size(s.frameW, s.frameH), ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER)
                    ).build()
            )
            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
            .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888)
            .build()

        analysis.setAnalyzer(executor) { proxy ->
            val now = SystemClock.uptimeMillis()
            val cfg = Config.cfg.settings
            val paused = State.paused
            val idle = now - lastSeen > cfg.idleAfterMs

            val fps = when {
                paused -> 4
                idle -> cfg.fpsIdle
                else -> cfg.fpsActive
            }
            // 0.9 — запас на дрожание интервала между кадрами
            if (now - lastFrame < 900L / fps) { proxy.close(); return@setAnalyzer }
            lastFrame = now

            val rotation = proxy.imageInfo.rotationDegrees.toFloat()
            var buf = buffer
            if (buf == null || buf.width != proxy.width || buf.height != proxy.height) {
                buf = Bitmap.createBitmap(proxy.width, proxy.height, Bitmap.Config.ARGB_8888)
                buffer = buf
            }
            proxy.use { buf.copyPixelsFromBuffer(it.planes[0].buffer) }
            val frame: Bitmap = if (rotation == 0f) buf else {
                val quarter = rotation == 90f || rotation == 270f
                val w = if (quarter) buf.height else buf.width
                val h = if (quarter) buf.width else buf.height
                var r = rotated
                if (r == null || r.width != w || r.height != h) {
                    r = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
                    rotated = r
                    rotatedCanvas = Canvas(r)
                }
                rotMatrix.reset()
                rotMatrix.postTranslate(-buf.width / 2f, -buf.height / 2f)
                rotMatrix.postRotate(rotation)
                rotMatrix.postTranslate(w / 2f, h / 2f)
                rotatedCanvas!!.drawBitmap(buf, rotMatrix, null)
                r
            }

            val img = BitmapImageBuilder(frame).build()
            if (!paused) face?.detectAsync(img, now)

            val wantHands = paused || cfg.hands
            // рука в кадре или недавно была: считаем каждый кадр (отслеживание дешёвое);
            // руки нет: ищем реже, чтобы экономить батарею
            val handRecent = now - lastHandSeen < 1500
            val every = if (idle || paused || handRecent) 1 else cfg.handEvery
            if (wantHands && (++handCounter % every == 0)) hands?.recognizeAsync(img, now)
        }
        return analysis
    }

    // ---------- голос ----------

    /** Одна голосовая команда по запросу (жест "Рога", кнопка в уведомлении или в приложении). */
    fun listen() {
        main.post {
            if (listening) return@post
            if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
                toast("Нет разрешения на микрофон"); return@post
            }
            if (!SpeechRecognizer.isRecognitionAvailable(this)) {
                toast("На телефоне нет службы распознавания речи (нужно приложение Google)"); return@post
            }
            listening = true
            recognizer?.destroy()
            recognizer = SpeechRecognizer.createSpeechRecognizer(this).also { r ->
                r.setRecognitionListener(recListener)
                r.startListening(Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                    putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                    putExtra(RecognizerIntent.EXTRA_LANGUAGE, Config.cfg.settings.voiceLang)
                    putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
                    putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 3)
                })
            }
            main.removeCallbacks(listenTimeout)
            main.postDelayed(listenTimeout, 10_000)
        }
    }

    private val recListener = object : RecognitionListener {
        override fun onReadyForSpeech(p: Bundle?) { Feedback.buzz(this@CameraService, 60); toast("🎤 Говори…") }
        override fun onBeginningOfSpeech() {}
        override fun onRmsChanged(v: Float) {}
        override fun onBufferReceived(b: ByteArray?) {}
        override fun onEndOfSpeech() {}
        override fun onError(e: Int) {
            main.removeCallbacks(listenTimeout)
            listening = false
            toast("Не расслышал (код $e)")
        }
        override fun onResults(b: Bundle?) {
            main.removeCallbacks(listenTimeout)
            listening = false
            val list = b?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION) ?: arrayListOf()
            if (!VoiceRouter.route(this@CameraService, list)) toast("Не понял: ${list.firstOrNull() ?: ""}")
        }
        override fun onPartialResults(b: Bundle?) {}
        override fun onEvent(t: Int, b: Bundle?) {}
    }

    // ---------- сервис ----------

    private fun startAsForeground() {
        getSystemService(NotificationManager::class.java)
            .createNotificationChannel(NotificationChannel("gt", "GestureTouch", NotificationManager.IMPORTANCE_LOW))

        fun pi(action: String, code: Int) = PendingIntent.getService(
            this, code, Intent(this, CameraService::class.java).setAction(action),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        @Suppress("DEPRECATION")
        val n = Notification.Builder(this, "gt")
            .setContentTitle("GestureTouch работает")
            .setContentText("Камера следит за жестами")
            .setSmallIcon(android.R.drawable.ic_menu_camera)
            .setOngoing(true)
            .addAction(android.R.drawable.ic_btn_speak_now, "Говорить", pi(ACTION_LISTEN, 1))
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Стоп", pi(ACTION_STOP, 2))
            .build()

        var type = 0
        if (android.os.Build.VERSION.SDK_INT >= 30) {
            type = ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA
            if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED)
                type = type or ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
        }
        ServiceCompat.startForeground(this, 1, n, type)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        when (intent?.action) {
            ACTION_STOP -> stopSelf()
            ACTION_LISTEN -> listen()
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        instance = null
        runCatching { unregisterReceiver(screenReceiver) }
        unbindCamera()
        main.removeCallbacks(listenTimeout)
        main.removeCallbacks(ticker)
        recognizer?.destroy()
        executor.execute {
            runCatching { face?.close() }
            runCatching { hands?.close() }
        }
        executor.shutdown()
        super.onDestroy()
    }

    private fun toast(msg: String) {
        main.post { Toast.makeText(this, msg, Toast.LENGTH_SHORT).show() }
    }
}
