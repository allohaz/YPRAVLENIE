package dev.gesturetouch

private const val F = 0 // голова и мимика
private const val H = 1 // поза руки

/**
 * hold      — сколько мс держать жест (меньше = быстрее, но больше ложных срабатываний)
 * cooldown  — пауза до следующего срабатывания
 * th        — порог: мимика 0..1, наклон головы в градусах, поза руки = минимальная уверенность 0..1
 * Всё переопределяется в config.json.
 */
enum class Gesture(val label: String, val hold: Long, val cooldown: Long, val th: Float, private val kind: Int) {
    TILT_RIGHT("Наклон головы вправо", 120, 350, 10f, F),
    TILT_LEFT("Наклон головы влево", 120, 350, 10f, F),
    HAND_THUMB_UP("Большой палец вверх", 150, 700, 0.50f, H),
    HAND_THUMB_DOWN("Большой палец вниз", 150, 700, 0.50f, H),
    BROWS_UP("Брови вверх", 150, 700, 0.45f, F),
    BROWS_DOWN("Брови вниз (нахмуриться)", 200, 700, 0.45f, F),
    MOUTH_OPEN("Открытый рот", 150, 700, 0.55f, F),
    HAND_FIST("Кулак", 200, 800, 0.50f, H),
    HAND_POINT_UP("Указательный палец вверх", 250, 1500, 0.50f, H),
    HAND_VICTORY("Знак мира (держать)", 700, 1500, 0.50f, H);

    val isFace get() = kind == F
    val isHand get() = kind == H
}
