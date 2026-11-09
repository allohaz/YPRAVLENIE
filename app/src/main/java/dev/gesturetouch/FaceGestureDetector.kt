package dev.gesturetouch

import com.google.mediapipe.tasks.vision.facelandmarker.FaceLandmarkerResult
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.max

/**
 * Голова и мимика (MediaPipe: 52 blendshape-коэффициента + точки лица).
 *
 * Наклон головы:
 *  - считается от нейтрали, которая сама подстраивается (лёжа на боку ложных наклонов нет);
 *  - лёгкое сглаживание убирает дрожание точек, но почти не добавляет задержки;
 *  - гистерезис: срабатывает при наклоне th, "отпускается" только когда голова вернулась
 *    ближе чем th/2. Нет дребезга на границе, и повторный наклон ловится быстро.
 *
 * Стороны считаются от лица пользователя. Если наклоны перепутаны, swapSides = true.
 */
class FaceGestureDetector(
    onGesture: (Gesture) -> Unit,
    private val onPresence: () -> Unit
) {
    private val tracker = HoldTracker(80, onGesture)
    private val faceGestures = Gesture.values().filter { it.isFace }

    private var rollBase = Float.NaN
    private var rollSmooth = 0f
    private var lastT = 0L
    private var tiltRightOn = false
    private var tiltLeftOn = false

    fun process(result: FaceLandmarkerResult) {
        val now = result.timestampMs()
        val faces = result.faceLandmarks()
        val blend = result.faceBlendshapes()
        if (faces.isEmpty() || !blend.isPresent || blend.get().isEmpty()) {
            faceGestures.forEach { tracker.update(it, false, now) }
            rollBase = Float.NaN
            tiltRightOn = false
            tiltLeftOn = false
            return
        }
        onPresence()

        val cfg = Config.cfg
        fun th(g: Gesture) = cfg.params.getValue(g).th

        val bs = HashMap<String, Float>()
        for (c in blend.get()[0]) bs[c.categoryName()] = c.score()
        fun v(n: String) = bs[n] ?: 0f

        val browsUp = max(v("browInnerUp"), (v("browOuterUpLeft") + v("browOuterUpRight")) / 2f) > th(Gesture.BROWS_UP)
        val browsDown = (v("browDownLeft") + v("browDownRight")) / 2f > th(Gesture.BROWS_DOWN)
        val mouth = v("jawOpen") > th(Gesture.MOUTH_OPEN)

        // наклон: внешние уголки глаз 33 и 263
        val lm = faces[0]
        val roll = Math.toDegrees(
            atan2((lm[263].y() - lm[33].y()).toDouble(), (lm[263].x() - lm[33].x()).toDouble())
        ).toFloat()

        if (rollBase.isNaN()) { rollBase = roll; rollSmooth = 0f; lastT = now }
        val rel = roll - rollBase                       // < 0 → наклон к правому плечу пользователя
        rollSmooth += 0.6f * (rel - rollSmooth)
        val dt = (now - lastT).coerceIn(0L, 500L).toFloat()
        lastT = now
        val tc = if (abs(rollSmooth) < th(Gesture.TILT_RIGHT)) 4000f else 25000f
        rollBase += rel * (dt / tc)

        val thR = th(Gesture.TILT_RIGHT)
        val thL = th(Gesture.TILT_LEFT)
        tiltRightOn = if (tiltRightOn) rollSmooth < -thR * 0.5f else rollSmooth < -thR
        tiltLeftOn = if (tiltLeftOn) rollSmooth > thL * 0.5f else rollSmooth > thL
        val swap = cfg.settings.swapSides

        // поворот головы нужен только как задел под джойстик
        val w = lm[454].x() - lm[234].x()
        val ratio = if (w > 0.01f) (lm[1].x() - lm[234].x()) / w else 0.5f
        State.headYaw = 0.5f - ratio
        State.headRoll = rollSmooth

        tracker.update(Gesture.TILT_RIGHT, if (swap) tiltLeftOn else tiltRightOn, now)
        tracker.update(Gesture.TILT_LEFT, if (swap) tiltRightOn else tiltLeftOn, now)
        tracker.update(Gesture.BROWS_UP, browsUp, now)
        tracker.update(Gesture.BROWS_DOWN, browsDown, now)
        tracker.update(Gesture.MOUTH_OPEN, mouth, now)
    }
}
