package dev.gesturetouch

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

data class AppSettings(
    val fpsActive: Int,
    val fpsIdle: Int,
    val idleAfterMs: Long,
    val frameW: Int,
    val frameH: Int,
    val useGpu: Boolean,
    val hands: Boolean,
    val handEvery: Int,
    val mirror: Boolean,
    val swapSides: Boolean,
    val voiceLang: String,
    val vibrate: Boolean
)

data class Params(val hold: Long, val cooldown: Long, val th: Float)
data class Profile(val name: String, val packages: List<String>, val actions: Map<Gesture, Action>)
data class VoiceCommand(val say: List<String>, val inApp: String?, val action: Action)

class Cfg(
    val settings: AppSettings,
    val params: Map<Gesture, Params>,
    val global: Map<Gesture, Action>,
    val profiles: List<Profile>,
    val apps: Map<String, String>,
    val voice: List<VoiceCommand>
)

/** Конфиг лежит в filesDir/config.json; при первом запуске берётся assets/default_config.json. */
object Config {
    @Volatile lateinit var cfg: Cfg
    private const val FILE = "config.json"

    fun ensureLoaded(ctx: Context) { if (!::cfg.isInitialized) load(ctx) }

    fun load(ctx: Context) {
        cfg = runCatching { parse(currentText(ctx)) }.getOrElse { parse(defaultText(ctx)) }
    }

    fun defaultText(ctx: Context): String =
        ctx.assets.open("default_config.json").bufferedReader().use { it.readText() }

    fun currentText(ctx: Context): String {
        val f = File(ctx.filesDir, FILE)
        return if (f.exists()) f.readText() else defaultText(ctx)
    }

    /** Возвращает текст ошибки или null, если сохранено. */
    fun save(ctx: Context, text: String): String? = try {
        parse(text)
        File(ctx.filesDir, FILE).writeText(text)
        load(ctx)
        null
    } catch (e: Exception) {
        e.message ?: e.toString()
    }

    fun reset(ctx: Context) {
        File(ctx.filesDir, FILE).delete()
        load(ctx)
    }

    // ---------- разбор ----------

    private fun JSONArray?.strings(): List<String> =
        if (this == null) emptyList() else (0 until length()).map { getString(it) }

    private fun gesture(name: String): Gesture =
        runCatching { Gesture.valueOf(name) }.getOrElse { throw IllegalArgumentException("Неизвестный жест: $name") }

    private fun f(o: JSONObject, k: String) = o.getDouble(k).toFloat()

    fun parseAction(o: JSONObject): Action = when (val t = o.getString("type")) {
        "swipe" -> Action.Swipe(f(o, "x1"), f(o, "y1"), f(o, "x2"), f(o, "y2"), o.optLong("ms", 180))
        "tap" -> Action.Tap(f(o, "x"), f(o, "y"))
        "doubleTap" -> Action.DoubleTap(f(o, "x"), f(o, "y"))
        "longPress" -> Action.LongPress(f(o, "x"), f(o, "y"), o.optLong("ms", 700))
        "openApp" -> Action.OpenApp(o.getString("package"))
        "clickText" -> {
            val v = o.get("text")
            Action.ClickText(if (v is JSONArray) v.strings() else listOf(v.toString()))
        }
        "setText" -> Action.SetText(o.getString("text"))
        "wait" -> Action.Wait(o.optLong("ms", 500))
        "seq" -> {
            val arr = o.getJSONArray("steps")
            val steps = (0 until arr.length()).map { parseAction(arr.getJSONObject(it)) }
            Action.Seq(steps.flatMap { if (it is Action.Seq) it.steps else listOf(it) })
        }
        "back" -> Action.Back
        "home" -> Action.Home
        "recents" -> Action.Recents
        "notifications" -> Action.Notifications
        "pause" -> Action.Pause
        "listen" -> Action.Listen
        else -> throw IllegalArgumentException("Неизвестное действие: $t")
    }

    private fun bindings(o: JSONObject?): Map<Gesture, Action> {
        val m = LinkedHashMap<Gesture, Action>()
        if (o == null) return m
        for (k in o.keys()) m[gesture(k)] = parseAction(o.getJSONObject(k))
        return m
    }

    private fun parse(text: String): Cfg {
        val root = JSONObject(text)
        val s = root.optJSONObject("settings") ?: JSONObject()
        val frame = s.optJSONArray("frame")
        val settings = AppSettings(
            fpsActive = s.optInt("fpsActive", 30).coerceIn(1, 60),
            fpsIdle = s.optInt("fpsIdle", 5).coerceIn(1, 30),
            idleAfterMs = s.optLong("idleAfterSec", 8) * 1000L,
            frameW = frame?.optInt(0, 640) ?: 640,
            frameH = frame?.optInt(1, 480) ?: 480,
            useGpu = s.optBoolean("useGpu", true),
            hands = s.optBoolean("hands", true),
            handEvery = s.optInt("handEvery", 3).coerceAtLeast(1),
            mirror = s.optBoolean("mirror", true),
            swapSides = s.optBoolean("swapSides", false),
            voiceLang = s.optString("voiceLang", "ru-RU"),
            vibrate = s.optBoolean("vibrate", true)
        )

        val params = Gesture.values().associateWith { Params(it.hold, it.cooldown, it.th) }.toMutableMap()
        root.optJSONObject("gestures")?.let { g ->
            for (k in g.keys()) {
                val gg = gesture(k)
                val o = g.getJSONObject(k)
                params[gg] = Params(
                    o.optLong("hold", gg.hold),
                    o.optLong("cooldown", gg.cooldown),
                    o.optDouble("th", gg.th.toDouble()).toFloat()
                )
            }
        }

        val profiles = ArrayList<Profile>()
        root.optJSONObject("profiles")?.let { p ->
            for (name in p.keys()) {
                val o = p.getJSONObject(name)
                profiles += Profile(name, o.optJSONArray("packages").strings(), bindings(o.optJSONObject("actions")))
            }
        }

        val apps = HashMap<String, String>()
        root.optJSONObject("apps")?.let { a -> for (k in a.keys()) apps[VoiceRouter.norm(k)] = a.getString(k) }

        val voice = ArrayList<VoiceCommand>()
        root.optJSONArray("voice")?.let { arr ->
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                voice += VoiceCommand(
                    o.getJSONArray("say").strings(),
                    if (o.has("inApp")) o.getString("inApp") else null,
                    parseAction(o.getJSONObject("action"))
                )
            }
        }

        return Cfg(settings, params, bindings(root.optJSONObject("global")), profiles, apps, voice)
    }

    /** Текст для экрана "что сейчас назначено". */
    fun describe(): String {
        val c = cfg
        val sb = StringBuilder()
        fun block(title: String, m: Map<Gesture, Action>) {
            if (m.isEmpty()) return
            sb.append(title).append('\n')
            for ((g, a) in m) sb.append("  ${g.label} → ${a.label()}\n")
            sb.append('\n')
        }
        block("Везде", c.global)
        for (p in c.profiles) block("Профиль «${p.name}»", p.actions)
        if (c.voice.isNotEmpty()) {
            sb.append("Голосовые команды (после жеста «Рога» или кнопки 🎤):\n")
            for (v in c.voice) sb.append("  «${v.say.firstOrNull() ?: ""}» → ${v.action.label()}\n")
            sb.append("  «открой <название приложения>»\n")
        }
        return sb.toString()
    }
}
