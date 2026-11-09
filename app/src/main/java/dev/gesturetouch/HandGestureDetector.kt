package dev.gesturetouch

import com.google.mediapipe.tasks.vision.gesturerecognizer.GestureRecognizerResult
import kotlin.math.abs

/**
 * Руки. Позы приходят готовыми из MediaPipe GestureRecognizer (кулак, палец вверх,
 * большой палец вверх/вниз, знак мира). Горизонтальные взмахи считаются по смещению
 * центра открытой ладони (точка 9) за ~0.45 с.
 */
class HandGestureDetector(
    onGesture: (Gesture) -> Unit,
    private val onPresence: () -> Unit
) {
    private val emit = onGesture
    private val tracker = HoldTracker(onGesture)

    private val poses = mapOf(
        "Closed_Fist" to Gesture.HAND_FIST,
        "Pointing_Up" to Gesture.HAND_POINT_UP,
        "Thumb_Up" to Gesture.HAND_THUMB_UP,
        "Thumb_Down" to Gesture.HAND_THUMB_DOWN,
        "Victory" to Gesture.HAND_VICTORY
    )

    private class Pt(val t: Long, val x: Float, val y: Float)
    private val hist = ArrayDeque<Pt>()
    private var lastSwipe = -1_000_000L

    fun process(result: GestureRecognizerResult) {
        val now = result.timestampMs()
        val lms = result.landmarks()
        if (lms.isEmpty()) {
            poses.values.forEach { tracker.update(it, false, now) }
            hist.clear()
            return
        }
        onPresence()

        val top = result.gestures().firstOrNull()?.maxByOrNull { it.score() }
        val confident = top != null && top.score() >= 0.6f
        val current = if (confident) poses[top!!.categoryName()] else null

        for (g in poses.values) tracker.update(g, g == current, now)

        // взмах: только открытой ладонью (поза не из списка выше)
        if (current == null) {
            val c = lms[0][9]
            hist.addLast(Pt(now, c.x(), c.y()))
            while (hist.isNotEmpty() && hist.first().t < now - 450) hist.removeFirst()
            detectSwipe(now)
        } else {
            hist.clear()
        }
    }

    private fun detectSwipe(now: Long) {
        if (hist.size < 3) return
        val cfg = Config.cfg
        val rawDx = hist.last().x - hist.first().x
        val dx = if (cfg.settings.mirror) -rawDx else rawDx   // с точки зрения пользователя
        val dy = hist.last().y - hist.first().y
        val th = cfg.params.getValue(Gesture.HAND_SWIPE_LEFT).th
        if (abs(dx) < th || abs(dx) < 1.5f * abs(dy)) return

        val g = if (dx > 0) Gesture.HAND_SWIPE_RIGHT else Gesture.HAND_SWIPE_LEFT
        if (now - lastSwipe < cfg.params.getValue(g).cooldown) return
        lastSwipe = now
        hist.clear()
        emit(g)
    }
}
