package com.vedika.phoneagent

import android.accessibilityservice.AccessibilityService
import kotlinx.coroutines.delay
import org.json.JSONObject

class Agent(
    private val svc: AgentService,
    private val brain: Brain,
    private val log: (String) -> Unit
) {
    private var lastAi = ""

    suspend fun run(goal: String, maxSteps: Int = 20) {
        val history = ArrayList<String>()
        log("🎯 Goal: $goal")
        for (step in 1..maxSteps) {
            val pkg = svc.currentPackage()
            val els = svc.elements()
            log("Step $step: soch raha hai...")
            val raw = brain.think(prompt(goal, svc.describe(els), history))
            backTo(pkg)

            val json = Regex("\\{[\\s\\S]*\\}").find(raw)?.value
            val o = try { JSONObject(json ?: "") } catch (e: Exception) { null }
            if (o == null) {
                log("⚠️ Samajh nahi aaya: ${raw.take(80)}")
                history.add("$step: invalid output. Reply with JSON only.")
                continue
            }

            val action = o.optString("action")
            log("→ $action ${o.optString("id")}${o.optString("target")}")
            var note = "ok"
            when (action) {
                "done" -> { log("✅ ${o.optString("result")}"); return }
                "open_app" -> {
                    note = if (svc.openApp(o.optString("target"))) "ok" else "app not found"
                    delay(2000)
                }
                "tap" -> {
                    val el = resolve(els, o.optInt("id"))
                    note = if (el != null && svc.tap(el)) "ok" else "failed"
                }
                "type" -> {
                    var text = o.optString("text")
                    if (text == "{{last_ai}}") text = lastAi
                    val id = o.optInt("id", 0)
                    val el = if (id > 0) resolve(els, id) else svc.elements().firstOrNull { it.editable }
                    note = if (el != null && svc.setText(el, text)) "ok" else "failed"
                }
                "scroll" -> svc.scroll(o.optString("dir", "down"))
                "back" -> svc.performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK)
                "home" -> svc.performGlobalAction(AccessibilityService.GLOBAL_ACTION_HOME)
                "wait" -> delay(2000)
                "ask_ai" -> {
                    val app = Apps.pkg(o.optString("app", "chatgpt"))
                    val ans = ChatAppBrain(svc, app).think(o.optString("prompt"))
                    lastAi = ans
                    note = "answer: ${ans.take(500)}"
                    log("🤖 AI jawab: ${ans.take(120)}")
                    backTo(pkg)
                }
                else -> note = "unknown action"
            }
            history.add("$step: ${o.toString().take(200)} -> $note")
            delay(1500)
        }
        log("Max steps khatam")
    }

    private suspend fun backTo(pkg: String?) {
        if (pkg == null || pkg == svc.currentPackage()) return
        if (!svc.openApp(pkg)) svc.performGlobalAction(AccessibilityService.GLOBAL_ACTION_HOME)
        delay(2000)
    }

    private fun resolve(old: List<AgentService.El>, id: Int): AgentService.El? {
        val o = old.getOrNull(id - 1) ?: return null
        val fresh = svc.elements()
        return fresh.firstOrNull { it.text == o.text && it.editable == o.editable } ?: fresh.getOrNull(id - 1)
    }

    private fun prompt(goal: String, screen: String, history: List<String>): String = """
You control an Android phone to achieve a goal.
GOAL: $goal

CURRENT SCREEN (id, type, text):
$screen
HISTORY (latest last):
${history.takeLast(6).joinToString("\n")}

Reply with ONLY one JSON object, nothing else. Choices:
{"action":"open_app","target":"app name"}
{"action":"tap","id":N}
{"action":"type","id":N,"text":"..."}   (N = an INPUT id; text may be "{{last_ai}}" to paste the last AI answer)
{"action":"scroll","dir":"down"}
{"action":"back"}
{"action":"home"}
{"action":"wait"}
{"action":"ask_ai","app":"chatgpt","prompt":"..."}   (app: chatgpt, gemini or claude; use for hard thinking or writing)
{"action":"done","result":"short summary"}
""".trimIndent()
}
