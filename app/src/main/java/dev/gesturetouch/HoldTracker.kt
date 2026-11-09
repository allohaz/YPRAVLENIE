package dev.gesturetouch

/**
 * Защита от ложных срабатываний БЕЗ потери скорости:
 *  - hold: жест должен держаться столько мс;
 *  - grace: короткие пропуски распознавания (мерцание, 1-2 кадра) НЕ сбрасывают удержание.
 *    Раньше любой пропущенный кадр обнулял отсчёт, из-за этого жесты рук срабатывали через раз;
 *  - после срабатывания жест нужно по-настоящему "отпустить" (пропуск дольше grace);
 *  - cooldown: минимальная пауза между срабатываниями.
 */
class HoldTracker(private val grace: Long, private val fire: (Gesture) -> Unit) {
    private class S { var since = 0L; var lastActive = 0L; var armed = true; var last = -1_000_000L }
    private val states = HashMap<Gesture, S>()

    fun update(g: Gesture, active: Boolean, now: Long) {
        val s = states.getOrPut(g) { S() }
        if (!active) {
            if (now - s.lastActive > grace) { s.since = 0L; s.armed = true }
            return
        }
        s.lastActive = now
        if (s.since == 0L) s.since = now
        val p = Config.cfg.params.getValue(g)
        if (s.armed && now - s.since >= p.hold && now - s.last >= p.cooldown) {
            s.armed = false
            s.last = now
            fire(g)
        }
    }
}
