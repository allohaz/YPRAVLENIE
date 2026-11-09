package dev.gesturetouch

/**
 * Защита от ложных срабатываний: жест должен держаться hold мс, после срабатывания
 * его надо "отпустить" (armed), и есть пауза cooldown. Параметры берутся из конфига.
 */
class HoldTracker(private val fire: (Gesture) -> Unit) {
    private class S { var since = 0L; var armed = true; var last = -1_000_000L }
    private val states = HashMap<Gesture, S>()

    fun update(g: Gesture, active: Boolean, now: Long) {
        val s = states.getOrPut(g) { S() }
        if (!active) { s.since = 0L; s.armed = true; return }
        if (s.since == 0L) s.since = now
        val p = Config.cfg.params.getValue(g)
        if (s.armed && now - s.since >= p.hold && now - s.last >= p.cooldown) {
            s.armed = false
            s.last = now
            fire(g)
        }
    }
}
