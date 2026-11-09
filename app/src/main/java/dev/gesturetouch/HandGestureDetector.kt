package dev.gesturetouch

import com.google.mediapipe.tasks.vision.gesturerecognizer.GestureRecognizerResult

/**
 * Руки: готовые позы MediaPipe GestureRecognizer (кулак, палец вверх, большой палец
 * вверх/вниз, знак мира). Минимальная уверенность каждой позы задаётся в config (th).
 * Мерцание распознавания гасится в HoldTracker (grace 200 мс).
 */
class HandGestureDetector(
    onGesture: (Gesture) -> Unit,
    private val onPresence: () -> Unit
) {
    private val tracker = HoldTracker(200, onGesture)

    private val poses = mapOf(
        "Closed_Fist" to Gesture.HAND_FIST,
        "Pointing_Up" to Gesture.HAND_POINT_UP,
        "Thumb_Up" to Gesture.HAND_THUMB_UP,
        "Thumb_Down" to Gesture.HAND_THUMB_DOWN,
        "Victory" to Gesture.HAND_VICTORY
    )

    fun process(result: GestureRecognizerResult) {
        val now = result.timestampMs()
        if (result.landmarks().isEmpty()) {
            poses.values.forEach { tracker.update(it, false, now) }
            return
        }
        onPresence()

        val top = result.gestures().firstOrNull()?.maxByOrNull { it.score() }
        val current = top?.let { c ->
            poses[c.categoryName()]?.takeIf { c.score() >= Config.cfg.params.getValue(it).th }
        }
        for (p in poses.values) tracker.update(p, p == current, now)
    }
}
