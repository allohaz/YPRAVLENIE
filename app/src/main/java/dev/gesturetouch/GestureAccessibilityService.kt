package dev.gesturetouch

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Path
import android.graphics.Rect
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.Toast

/**
 * Исполнитель. Не потребляет ресурсов, пока ничего не происходит: слушает только смену окна.
 * Что делает:
 *  - узнаёт, какое приложение на экране, и выбирает профиль из config.json;
 *  - превращает жест в действие: свайп/тап, нажатие по тексту кнопки, ввод текста,
 *    запуск приложения, Назад/Домой/Недавние, цепочки действий.
 */
class GestureAccessibilityService : AccessibilityService() {

    companion object {
        @Volatile var instance: GestureAccessibilityService? = null
    }

    private val main = Handler(Looper.getMainLooper())

    private val launcherPkg: String? by lazy {
        packageManager.resolveActivity(
            Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME),
            PackageManager.MATCH_DEFAULT_ONLY
        )?.activityInfo?.packageName
    }

    override fun onServiceConnected() {
        instance = this
        Config.ensureLoaded(this)
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent) {
        if (event.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return
        val pkg = event.packageName?.toString() ?: return
        if (pkg == "com.android.systemui" || pkg == "android" || pkg.contains("inputmethod")) return
        State.currentPackage = pkg
    }

    override fun onInterrupt() {}

    override fun onUnbind(intent: Intent?): Boolean {
        instance = null
        return super.onUnbind(intent)
    }

    // ---------- жесты → действия ----------

    fun handle(g: Gesture) {
        main.post {
            val cfg = Config.cfg
            val pkg = State.currentPackage
            val profile = cfg.profiles.firstOrNull { p ->
                p.packages.any { it == pkg || (it == "@home" && pkg == launcherPkg) }
            }
            val action = profile?.actions?.get(g) ?: cfg.global[g] ?: return@post
            // на паузе работают только "пауза" и "слушать"
            if (State.paused && action != Action.Pause && action != Action.Listen) return@post
            Feedback.buzz(this)
            exec(action)
        }
    }

    fun run(a: Action) {
        if (Looper.myLooper() == Looper.getMainLooper()) exec(a) else main.post { exec(a) }
    }

    private fun exec(a: Action) {
        val dm = resources.displayMetrics
        val w = dm.widthPixels.toFloat()
        val h = dm.heightPixels.toFloat()
        when (a) {
            is Action.Swipe -> stroke(a.x1 * w, a.y1 * h, a.x2 * w, a.y2 * h, a.ms)
            is Action.Tap -> stroke(a.x * w, a.y * h, a.x * w, a.y * h, 50)
            is Action.LongPress -> stroke(a.x * w, a.y * h, a.x * w, a.y * h, a.ms)
            is Action.DoubleTap -> {
                stroke(a.x * w, a.y * h, a.x * w, a.y * h, 40)
                main.postDelayed({ stroke(a.x * w, a.y * h, a.x * w, a.y * h, 40) }, 110)
            }
            is Action.OpenApp -> openApp(a.pkg)
            is Action.ClickText -> if (!clickText(a.texts)) toast("Не нашёл «${a.texts.firstOrNull()}» на экране")
            is Action.SetText -> if (!setText(a.text)) toast("Нет поля ввода")
            is Action.Wait -> {}
            is Action.Seq -> runSteps(a.steps, 0)
            Action.Back -> performGlobalAction(GLOBAL_ACTION_BACK)
            Action.Home -> performGlobalAction(GLOBAL_ACTION_HOME)
            Action.Recents -> performGlobalAction(GLOBAL_ACTION_RECENTS)
            Action.Notifications -> performGlobalAction(GLOBAL_ACTION_NOTIFICATIONS)
            Action.Pause -> {
                State.paused = !State.paused
                toast(if (State.paused) "Жесты: пауза" else "Жесты: включены")
            }
            Action.Listen -> CameraService.instance?.listen() ?: toast("Сначала нажми «Старт» в приложении")
        }
    }

    private fun runSteps(steps: List<Action>, i: Int) {
        if (i >= steps.size) return
        val a = steps[i]
        exec(a)
        main.postDelayed({ runSteps(steps, i + 1) }, if (a is Action.Wait) a.ms else 150L)
    }

    private fun stroke(x1: Float, y1: Float, x2: Float, y2: Float, ms: Long) {
        val path = Path().apply { moveTo(x1, y1); if (x1 != x2 || y1 != y2) lineTo(x2, y2) }
        val g = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, ms.coerceAtLeast(1)))
            .build()
        dispatchGesture(g, null, null)
    }

    private fun openApp(pkg: String) {
        val i = packageManager.getLaunchIntentForPackage(pkg)
        if (i == null) { toast("Приложение не найдено: $pkg"); return }
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        runCatching { startActivity(i) }.onFailure { toast("Не удалось открыть: ${it.message}") }
    }

    // ---------- работа с элементами экрана ----------

    /** Ищет видимый элемент, в text/contentDescription которого есть подстрока; берёт самый "точный" (короткий). */
    private fun clickText(texts: List<String>): Boolean {
        val root = rootInActiveWindow ?: return false
        val needles = texts.map { it.lowercase() }
        val hits = ArrayList<Pair<AccessibilityNodeInfo, Int>>()
        collect(root, needles, 0, hits)
        val hit = hits.minByOrNull { it.second }?.first ?: return false

        var n: AccessibilityNodeInfo? = hit
        while (n != null) {
            if (n.isClickable && n.performAction(AccessibilityNodeInfo.ACTION_CLICK)) return true
            n = n.parent
        }
        val r = Rect()
        hit.getBoundsInScreen(r)
        stroke(r.exactCenterX(), r.exactCenterY(), r.exactCenterX(), r.exactCenterY(), 50)
        return true
    }

    private fun collect(
        n: AccessibilityNodeInfo, needles: List<String>, depth: Int,
        out: MutableList<Pair<AccessibilityNodeInfo, Int>>
    ) {
        if (depth > 40) return
        val t = (n.text?.toString() ?: "") + " " + (n.contentDescription?.toString() ?: "")
        val lt = t.lowercase()
        if (n.isVisibleToUser && needles.any { lt.contains(it) }) out += n to t.length
        for (i in 0 until n.childCount) {
            val c = n.getChild(i) ?: continue
            collect(c, needles, depth + 1, out)
        }
    }

    private fun setText(text: String): Boolean {
        val root = rootInActiveWindow ?: return false
        val target = root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT) ?: findEditable(root, 0) ?: return false
        val args = Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
        }
        return target.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
    }

    private fun findEditable(n: AccessibilityNodeInfo, depth: Int): AccessibilityNodeInfo? {
        if (depth > 40) return null
        if (n.isEditable && n.isVisibleToUser) return n
        for (i in 0 until n.childCount) {
            val c = n.getChild(i) ?: continue
            findEditable(c, depth + 1)?.let { return it }
        }
        return null
    }

    private fun toast(msg: String) {
        main.post { Toast.makeText(this, msg, Toast.LENGTH_SHORT).show() }
    }
}
