package dev.gesturetouch

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager

/**
 * Разбор распознанной фразы:
 *  1) команды из config.json ("voice"), побеждает самая длинная подходящая фраза;
 *  2) "открой <приложение>": сначала алиасы из "apps", потом названия установленных приложений.
 */
object VoiceRouter {

    fun norm(s: String): String = s.lowercase()
        .replace('ё', 'е')
        .replace(Regex("[^\\p{L}\\p{N}]+"), " ")
        .trim()

    fun route(ctx: Context, phrases: List<String>): Boolean {
        for (p in phrases) {
            val a = match(ctx, norm(p)) ?: continue
            GestureAccessibilityService.instance?.run(a)
            return true
        }
        return false
    }

    private fun match(ctx: Context, t: String): Action? {
        if (t.isEmpty()) return null
        val cfg = Config.cfg

        var best: VoiceCommand? = null
        var bestLen = 0
        for (c in cfg.voice) for (s in c.say) {
            val ns = norm(s)
            if (ns.isNotEmpty() && t.contains(ns) && ns.length > bestLen) { best = c; bestLen = ns.length }
        }
        if (best != null) {
            val inApp = best.inApp
            return if (inApp != null && State.currentPackage != inApp)
                Action.Seq(listOf(Action.OpenApp(inApp), Action.Wait(1800), best.action))
            else best.action
        }

        val m = Regex("^(открой приложение|открой|открыть|запусти|запустить|включи)\\s+(.+)$").find(t) ?: return null
        val name = m.groupValues[2]
        val pkg = findApp(ctx, name) ?: return null
        return Action.OpenApp(pkg)
    }

    private fun findApp(ctx: Context, name: String): String? {
        Config.cfg.apps.entries.firstOrNull { name == it.key || name.contains(it.key) }?.let { return it.value }

        val pm = ctx.packageManager
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        for (ri in pm.queryIntentActivities(intent, PackageManager.MATCH_ALL)) {
            val label = norm(ri.loadLabel(pm).toString())
            if (label.length >= 3 && (label == name || label.contains(name) || name.contains(label)))
                return ri.activityInfo.packageName
        }
        return null
    }
}
