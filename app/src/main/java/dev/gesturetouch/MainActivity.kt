package dev.gesturetouch

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Typeface
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast

class MainActivity : Activity() {

    private lateinit var status: TextView
    private lateinit var bindings: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Config.ensureLoaded(this)

        val pad = (16 * resources.displayMetrics.density).toInt()
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad, pad, pad)
        }
        status = TextView(this).apply { textSize = 15f }
        root.addView(status)

        fun button(label: String, onClick: () -> Unit) {
            root.addView(Button(this).apply {
                text = label
                isAllCaps = false
                setOnClickListener { onClick() }
            }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        }

        button("1. Включить службу в Спец. возможностях") {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }
        button("2. Разрешить «Поверх других окон» (запуск приложений жестом)") {
            startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
        }
        button("▶ Старт") { start() }
        button("■ Стоп (камера и микрофон освобождаются)") {
            stopService(Intent(this, CameraService::class.java))
            refresh()
        }
        button("🎤 Сказать команду") {
            CameraService.instance?.listen() ?: Toast.makeText(this, "Сначала Старт", Toast.LENGTH_SHORT).show()
        }
        button("⚙ Настройки жестов и команд (JSON)") {
            startActivityForResult(Intent(this, ConfigActivity::class.java), 1)
        }
        button("⏻ Полностью выключить (отключить службу спец. возможностей)") {
            stopService(Intent(this, CameraService::class.java))
            GestureAccessibilityService.instance?.disableSelf()
            refresh()
        }

        bindings = TextView(this).apply {
            typeface = Typeface.MONOSPACE
            textSize = 12f
            setPadding(0, pad, 0, 0)
        }
        root.addView(bindings)

        setContentView(ScrollView(this).apply { addView(root) })
    }

    override fun onResume() {
        super.onResume()
        refresh()
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        refresh()
    }

    private fun refresh() {
        val acc = GestureAccessibilityService.instance != null
        val run = CameraService.instance != null
        val overlay = Settings.canDrawOverlays(this)
        status.text = "Служба спец. возможностей: " + (if (acc) "включена ✅" else "выключена ❌") +
            "\nКамера и жесты: " + (if (run) "работают ✅" else "остановлены ⏹ (ничего не потребляют)") +
            "\nПоверх других окон: " + (if (overlay) "разрешено ✅" else "нет ❌")
        bindings.text = Config.describe()
    }

    private fun start() {
        val need = mutableListOf(Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO)
        if (Build.VERSION.SDK_INT >= 33) need += Manifest.permission.POST_NOTIFICATIONS
        val missing = need.filter { checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }
        if (missing.isNotEmpty()) requestPermissions(missing.toTypedArray(), 1) else launch()
    }

    private fun launch() {
        startForegroundService(Intent(this, CameraService::class.java))
        status.postDelayed({ refresh() }, 600)
    }

    override fun onRequestPermissionsResult(code: Int, perms: Array<out String>, results: IntArray) {
        super.onRequestPermissionsResult(code, perms, results)
        // камера обязательна, микрофон нужен только для голосовых команд
        if (checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) launch()
    }
}
