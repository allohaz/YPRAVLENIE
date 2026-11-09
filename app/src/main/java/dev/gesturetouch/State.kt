package dev.gesturetouch

import android.content.Context
import android.os.VibrationEffect
import android.os.Vibrator

/** Общее состояние между службами (все поля volatile, потоки разные). */
object State {
    @Volatile var paused = false
    @Volatile var currentPackage = ""
    // задел под аналоговое управление (джойстик головой): поворот и наклон, -0.5..0.5 и градусы
    @Volatile var headYaw = 0f
    @Volatile var headRoll = 0f
    // диагностика для главного экрана
    @Volatile var faceFps = 0
    @Volatile var handFps = 0
    @Volatile var delegates = ""
}

object Feedback {
    fun buzz(ctx: Context, ms: Long = 25) {
        if (!Config.cfg.settings.vibrate) return
        val v = ctx.getSystemService(Vibrator::class.java) ?: return
        v.vibrate(VibrationEffect.createOneShot(ms, VibrationEffect.DEFAULT_AMPLITUDE))
    }
}
