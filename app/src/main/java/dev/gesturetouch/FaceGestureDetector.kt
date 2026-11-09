package dev.gesturetouch

import com.google.mediapipe.tasks.vision.facelandmarker.FaceLandmarkerResult
import kotlin.math.abs
import kotlin.math.atan2

/**
 * Голова и мимика. Источник: 52 blendshape-коэффициента + 478 точек лица от MediaPipe.
 *
 * Наклон головы считается ОТНОСИТЕЛЬНО нейтрального положения, которое медленно
 * подстраивается (около 4 с). Поэтому слегка склонённая голова, лёжа на боку или камера
 * под углом не вызывают ложных наклонов.
 *
 * Стороны считаются от лица пользователя. Если наклоны перепутаны, поставь swapSides = true.
 */
class FaceGestureDetector(
    onGesture: (Gesture) -> Unit,
    private val onPresence: () -> Unit
) {
    private val tracker = HoldTracker(onGesture)
    private val faceGestures = Gesture.values().filter { it.isFace }

    private var rollBase = Float.NaN
    private var lastT = 0L

    fun process(result: FaceLandmarkerResult) {
        val now = result.timestampMs()
        val faces = result.faceLandmarks()
        val blend = result.faceBlendshapes()
        if (faces.isEmpty() || !blend.isPresent || blend.get().isEmpty()) {
            faceGestures.forEach { tracker.update(it, false, now) }
            rollBase = Float.NaN   // вернулся в кадр, нейтраль определится заново
            return
        }
        onPresence()

        val cfg = Config.cfg
        fun th(g: Gesture) = cfg.params.getValue(g).th

        val bs = HashMap<String, Float>()
        for (c in blend.get()[0]) bs[c.categoryName()] = c.score()
        fun v(n: String) = bs[n] ?: 0f

        val brows = v("browInnerUp") > th(Gesture.BROWS_UP)
        val mouth = v("jawOpen") > th(Gesture.MOUTH_OPEN)

        // поза головы по точкам: внешние уголки глаз 33/263, щёки 234/454, кончик носа 1
        val lm = faces[0]
        val roll = Math.toDegrees(
            atan2((lm[263].y() - lm[33].y()).toDouble(), (lm[263].x() - lm[33].x()).toDouble())
        ).toFloat()

        if (rollBase.isNaN()) { rollBase = roll; lastT = now }
        val rel = roll - rollBase                       // < 0 → наклон к правому плечу пользователя
        val dt = (now - lastT).coerceIn(0L, 500L).toFloat()
        lastT = now
        val tc = if (abs(rel) < th(Gesture.TILT_RIGHT)) 4000f else 20000f
        rollBase += rel * (dt / tc)

        val w = lm[454].x() - lm[234].x()
        val ratio = if (w > 0.01f) (lm[1].x() - lm[234].x()) / w else 0.5f
        State.headYaw = 0.5f - ratio                    // задел под джойстик
        State.headRoll = rel

        var tiltR = rel < -th(Gesture.TILT_RIGHT)
        var tiltL = rel > th(Gesture.TILT_LEFT)
        if (cfg.settings.swapSides) tiltR = tiltL.also { tiltL = tiltR }

        tracker.update(Gesture.TILT_RIGHT, tiltR, now)
        tracker.update(Gesture.TILT_LEFT, tiltL, now)
        tracker.update(Gesture.BROWS_UP, brows, now)
        tracker.update(Gesture.MOUTH_OPEN, mouth, now)
    }
}
