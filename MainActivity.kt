package com.vedika.phoneagent

import android.app.Activity
import android.content.Intent
import android.graphics.Typeface
import android.os.Bundle
import android.provider.Settings
import android.text.InputType
import android.view.ViewGroup
import android.widget.*
import java.io.File

class MainActivity : Activity() {
    private lateinit var logView: TextView
    private lateinit var status: TextView
    private lateinit var goal: EditText
    private lateinit var spinner: Spinner
    private val PICK = 11
    private val prefs by lazy { getSharedPreferences("p", MODE_PRIVATE) }
    private val brains = listOf(
        "Local model (phone par)" to "local",
        "ChatGPT app" to "chatgpt",
        "Gemini app" to "gemini",
        "Claude app" to "claude"
    )

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun button(label: String, onClick: () -> Unit) = Button(this).apply {
        text = label; isAllCaps = false
        setOnClickListener { onClick() }
    }

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(24), dp(16), dp(16))
        }
        root.addView(TextView(this).apply {
            text = "📱 Phone Agent"; textSize = 22f; setTypeface(null, Typeface.BOLD)
        })
        root.addView(button("1. Accessibility ON karo") {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
            Toast.makeText(this, "Phone Agent chuno → ON", Toast.LENGTH_LONG).show()
        })
        root.addView(button("2. Local model (.task) chuno") {
            startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                addCategory(Intent.CATEGORY_OPENABLE); type = "*/*"
            }, PICK)
        })
        status = TextView(this)
        root.addView(status)

        spinner = Spinner(this).apply {
            adapter = ArrayAdapter(this@MainActivity, android.R.layout.simple_spinner_dropdown_item, brains.map { it.first })
        }
        root.addView(TextView(this).apply { text = "Brain (kaun sochega):" })
        root.addView(spinner)

        goal = EditText(this).apply {
            hint = "Kaam batao, e.g. Chrome kholo aur aaj ka gold rate dekho"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
            minLines = 2
        }
        root.addView(goal)

        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        row.addView(button("▶ Start") {
            val svc = AgentService.instance
            if (svc == null) {
                Toast.makeText(this, "Pehle Accessibility ON karo", Toast.LENGTH_LONG).show()
            } else if (goal.text.isBlank()) {
                Toast.makeText(this, "Kaam likho", Toast.LENGTH_SHORT).show()
            } else {
                svc.startAgent(goal.text.toString(), brains[spinner.selectedItemPosition].second, prefs.getString("model", null))
                moveTaskToBack(true)
            }
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        row.addView(button("⏹ Stop") { AgentService.instance?.stopAgent() },
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        root.addView(row)

        logView = TextView(this).apply { textSize = 13f; setTextIsSelectable(true) }
        root.addView(logView)
        setContentView(ScrollView(this).apply { addView(root) })
    }

    override fun onResume() {
        super.onResume()
        AgentService.sink = { t -> runOnUiThread { logView.text = t } }
        logView.text = AgentService.allLogs()
        val saved = prefs.getString("model", null)
        status.text = (if (AgentService.instance != null) "Accessibility: ON ✅" else "Accessibility: OFF ❌") +
            "\n" + (if (saved != null) "Model: ready ✅" else "Model: nahi chuna")
    }

    override fun onPause() { super.onPause(); AgentService.sink = null }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(r: Int, c: Int, d: Intent?) {
        super.onActivityResult(r, c, d)
        val uri = d?.data
        if (r == PICK && c == RESULT_OK && uri != null) {
            status.text = "Model copy ho raha hai... (thoda time lagega)"
            Thread {
                try {
                    val out = File(filesDir, "model.task")
                    contentResolver.openInputStream(uri)!!.use { i -> out.outputStream().use { o -> i.copyTo(o) } }
                    prefs.edit().putString("model", out.absolutePath).apply()
                    runOnUiThread { status.text = "Model ready ✅ (${out.length() / 1_000_000} MB)" }
                } catch (e: Exception) {
                    runOnUiThread { status.text = "Error: ${e.message}" }
                }
            }.start()
        }
    }
}
