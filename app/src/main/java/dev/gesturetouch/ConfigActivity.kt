package dev.gesturetouch

import android.app.Activity
import android.content.Intent
import android.graphics.Typeface
import android.os.Bundle
import android.text.InputType
import android.view.Gravity
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.Toast

/** Редактор config.json прямо в приложении + импорт/экспорт файла (чтобы править на компьютере). */
class ConfigActivity : Activity() {

    private lateinit var edit: EditText

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Config.ensureLoaded(this)

        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val bar = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        fun btn(t: String, f: () -> Unit) = bar.addView(
            Button(this).apply { text = t; isAllCaps = false; textSize = 12f; setOnClickListener { f() } },
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        )

        btn("Сохранить") { save() }
        btn("Сброс") {
            Config.reset(this)
            edit.setText(Config.currentText(this))
            toast("Сброшено к стандартным")
        }
        btn("Импорт") {
            startActivityForResult(
                Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("*/*"), 11
            )
        }
        btn("Экспорт") {
            startActivityForResult(
                Intent(Intent.ACTION_CREATE_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE)
                    .setType("application/json").putExtra(Intent.EXTRA_TITLE, "gesturetouch-config.json"), 12
            )
        }
        root.addView(bar)

        edit = EditText(this).apply {
            typeface = Typeface.MONOSPACE
            textSize = 11f
            gravity = Gravity.TOP
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or
                InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            setText(Config.currentText(this@ConfigActivity))
        }
        root.addView(edit, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        setContentView(root)
    }

    private fun save() {
        val err = Config.save(this, edit.text.toString())
        toast(if (err == null) "Сохранено ✅ (пороги и жесты применяются сразу)" else "Ошибка: $err")
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        val uri = data?.data ?: return
        if (resultCode != RESULT_OK) return
        runCatching {
            when (requestCode) {
                11 -> edit.setText(contentResolver.openInputStream(uri)!!.bufferedReader().use { it.readText() })
                12 -> contentResolver.openOutputStream(uri)!!.bufferedWriter().use { it.write(edit.text.toString()) }
            }
        }.onFailure { toast("Ошибка файла: ${it.message}") }
    }

    private fun toast(m: String) = Toast.makeText(this, m, Toast.LENGTH_LONG).show()
}
