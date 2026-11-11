package com.vedika.phoneagent

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.Intent
import android.graphics.Path
import android.graphics.Rect
import android.os.Build
import android.os.Bundle
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import kotlinx.coroutines.*

class AgentService : AccessibilityService() {

    companion object {
        @Volatile var instance: AgentService? = null
        private val logs = StringBuilder()
        @Volatile var sink: ((String) -> Unit)? = null

        @Synchronized fun log(s: String) {
            logs.appendLine(s)
            sink?.invoke(logs.toString())
        }
        @Synchronized fun allLogs() = logs.toString()
    }

    class El(
        val id: Int,
        val text: String,
        val clickable: Boolean,
        val editable: Boolean,
        val node: AccessibilityNodeInfo
    )

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var job: Job? = null
    private var local: LocalBrain? = null

    val running get() = job?.isActive == true

    override fun onServiceConnected() { instance = this; log("Accessibility service ON ✅") }
    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}
    override fun onInterrupt() {}
    override fun onUnbind(intent: Intent?): Boolean {
        instance = null; stopAgent(); local?.close(); local = null
        return super.onUnbind(intent)
    }

    // ---------- Agent control ----------
    fun startAgent(goal: String, brainKey: String, modelPath: String?) {
        stopAgent()
        job = scope.launch {
            try {
                val brain: Brain = if (brainKey == "local") {
                    if (modelPath == null) { log("Pehle model file chuno"); return@launch }
                    if (local?.path != modelPath) {
                        log("Model load ho raha hai...")
                        local?.close()
                        local = withContext(Dispatchers.Default) { LocalBrain(this@AgentService, modelPath) }
                    }
                    local!!
                } else ChatAppBrain(this@AgentService, Apps.pkg(brainKey))
                Agent(this@AgentService, brain) { log(it) }.run(goal)
            } catch (e: CancellationException) {
                log("⏹ Stop")
            } catch (e: Throwable) {
                log("❌ Error: ${e.message}")
            }
        }
    }

    fun stopAgent() { job?.cancel() }

    // ---------- Screen reading ----------
    fun currentPackage(): String? = rootInActiveWindow?.packageName?.toString()

    fun elements(): List<El> {
        val out = ArrayList<El>()
        val root = rootInActiveWindow ?: return out
        fun walk(n: AccessibilityNodeInfo, depth: Int, parentClickable: Boolean) {
            if (depth > 40 || out.size > 150) return
            val t = (n.text ?: n.contentDescription)?.toString()?.trim().orEmpty()
            val c = n.isClickable || parentClickable
            if (n.isVisibleToUser && (t.isNotEmpty() || n.isEditable))
                out.add(El(out.size + 1, t, c, n.isEditable, n))
            for (i in 0 until n.childCount) n.getChild(i)?.let { walk(it, depth + 1, c) }
        }
        walk(root, 0, false)
        return out
    }

    fun describe(els: List<El>): String {
        val sb = StringBuilder()
        for (e in els.take(60)) {
            val type = if (e.editable) "INPUT" else if (e.clickable) "BTN" else "TXT"
            sb.appendLine("[${e.id}] $type ${e.text.take(100).replace("\n", " ")}")
            if (sb.length > 2500) break
        }
        return sb.toString()
    }

    // ---------- Actions ----------
    fun openApp(target: String): Boolean {
        val pm = packageManager
        val q = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val list = pm.queryIntentActivities(q, 0)
        val hit = list.firstOrNull { it.activityInfo.packageName.equals(target, true) }
            ?: list.firstOrNull { it.loadLabel(pm).toString().contains(target, true) }
        val launch = hit?.let { pm.getLaunchIntentForPackage(it.activityInfo.packageName) } ?: return false
        launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        startActivity(launch)
        return true
    }

    fun tap(el: El): Boolean {
        var n: AccessibilityNodeInfo? = el.node
        while (n != null && !n.isClickable) n = n.parent
        if (n != null && n.performAction(AccessibilityNodeInfo.ACTION_CLICK)) return true
        val r = Rect(); el.node.getBoundsInScreen(r)
        return gesture(r.centerX().toFloat(), r.centerY().toFloat(), r.centerX().toFloat(), r.centerY().toFloat(), 60)
    }

    fun setText(el: El, text: String): Boolean {
        el.node.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
        val b = Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
        }
        return el.node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, b)
    }

    fun pressEnter(el: El) {
        if (Build.VERSION.SDK_INT >= 30)
            el.node.performAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_IME_ENTER.id)
    }

    fun scroll(dir: String): Boolean {
        val m = resources.displayMetrics
        val w = m.widthPixels.toFloat(); val h = m.heightPixels.toFloat()
        return if (dir == "up") gesture(w / 2, h * 0.3f, w / 2, h * 0.7f, 300)
        else gesture(w / 2, h * 0.7f, w / 2, h * 0.3f, 300)
    }

    private fun gesture(x1: Float, y1: Float, x2: Float, y2: Float, ms: Long): Boolean {
        val p = Path().apply {
            moveTo(x1, y1)
            if (x1 != x2 || y1 != y2) lineTo(x2, y2)
        }
        val g = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(p, 0, ms)).build()
        return dispatchGesture(g, null, null)
    }
}
