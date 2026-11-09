package dev.gesturetouch

/** Что делать. Все действия описываются в config.json (поле "type"). Координаты: доли экрана 0..1. */
sealed class Action {
    data class Swipe(val x1: Float, val y1: Float, val x2: Float, val y2: Float, val ms: Long) : Action()
    data class Tap(val x: Float, val y: Float) : Action()
    data class DoubleTap(val x: Float, val y: Float) : Action()
    data class LongPress(val x: Float, val y: Float, val ms: Long) : Action()
    data class OpenApp(val pkg: String) : Action()
    data class ClickText(val texts: List<String>) : Action()  // нажать элемент, в тексте/описании которого есть подстрока
    data class SetText(val text: String) : Action()           // вставить текст в поле ввода
    data class Wait(val ms: Long) : Action()
    data class Seq(val steps: List<Action>) : Action()        // цепочка действий
    object Back : Action()
    object Home : Action()
    object Recents : Action()
    object Notifications : Action()
    object Pause : Action()   // вкл/выкл все жесты
    object Listen : Action()  // записать голосовую команду

    fun label(): String = when (this) {
        is Swipe -> "свайп"
        is Tap -> "тап"
        is DoubleTap -> "двойной тап"
        is LongPress -> "долгое нажатие"
        is OpenApp -> "открыть $pkg"
        is ClickText -> "нажать «${texts.firstOrNull() ?: ""}»"
        is SetText -> "вставить «$text»"
        is Wait -> "ждать ${ms} мс"
        is Seq -> "цепочка из ${steps.size} шагов"
        Back -> "назад"
        Home -> "домой"
        Recents -> "недавние"
        Notifications -> "шторка"
        Pause -> "пауза/продолжить жесты"
        Listen -> "голосовая команда"
    }
}
