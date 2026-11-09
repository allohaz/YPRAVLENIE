package dev.gesturetouch

private const val F = 0 // голова и мимика
private const val H = 1 // поза руки
private const val S = 2 // взмах рукой

/**
 * Только лёгкие и надёжные жесты, в порядке "частота × лёгкость".
 * hold      — сколько мс держать жест (меньше = быстрее, но больше ложных срабатываний)
 * cooldown  — пауза до следующего срабатывания
 * th        — порог: мимика 0..1, наклон головы в градусах, взмах как доля кадра
 * Всё переопределяется в config.json.
 */
enum class Gesture(val label: String, val hold: Long, val cooldown: Long, val th: Float, private val kind: Int) {
    TILT_RIGHT("Наклон головы вправо", 250, 500, 15f, F),
    HAND_THUMB_UP("Большой палец вверх", 250, 800, 0.60f, H),
    TILT_LEFT("Наклон головы влево", 250, 500, 15f, F),
    HAND_FIST("Кулак", 250, 800, 0.60f, H),
    BROWS_UP("Брови вверх", 200, 700, 0.55f, F),
    HAND_SWIPE_LEFT("Взмах рукой влево", 0, 700, 0.22f, S),
    MOUTH_OPEN("Открытый рот", 200, 800, 0.50f, F),
    HAND_POINT_UP("Указательный палец вверх", 300, 1500, 0.60f, H),
    HAND_VICTORY("Знак мира (держать)", 800, 1500, 0.60f, H),
    HAND_THUMB_DOWN("Большой палец вниз", 250, 800, 0.60f, H),
    HAND_SWIPE_RIGHT("Взмах рукой вправо", 0, 700, 0.22f, S);

    val isFace get() = kind == F
    val isHand get() = kind == H
    val isSwipe get() = kind == S
}
