package com.vedika.phoneagent

import android.content.Context
import com.google.mediapipe.tasks.genai.llminference.LlmInference
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

interface Brain {
    suspend fun think(prompt: String): String
}

object Apps {
    fun pkg(key: String) = when (key.lowercase()) {
        "chatgpt" -> "com.openai.chatgpt"
        "gemini" -> "com.google.android.apps.bard"
        "claude" -> "com.anthropic.claude"
        else -> key
    }
}

/** Phone par hi chalne wala model (MediaPipe + Gemma .task file). */
class LocalBrain(ctx: Context, val path: String) : Brain {
    private val llm: LlmInference = LlmInference.createFromOptions(
        ctx,
        LlmInference.LlmInferenceOptions.builder()
            .setModelPath(path)
            .setMaxTokens(2048)
            .build()
    )

    override suspend fun think(prompt: String): String = withContext(Dispatchers.Default) {
        llm.generateResponse("<start_of_turn>user\n$prompt<end_of_turn>\n<start_of_turn>model\n")
    }

    fun close() = llm.close()
}

/** Installed ChatGPT / Gemini / Claude app ko insaan ki tarah use karta hai. */
class ChatAppBrain(private val svc: AgentService, private val pkg: String) : Brain {
    override suspend fun think(prompt: String): String {
        if (!svc.openApp(pkg)) return "ERROR: app install nahi hai ($pkg)"
        delay(3000)

        var els = svc.elements()
        var tries = 0
        while (els.none { it.editable } && tries++ < 15) {
            delay(1000); els = svc.elements()
        }
        val input = els.firstOrNull { it.editable } ?: return "ERROR: chat input box nahi mila"
        val before = els.map { it.text }.toSet()

        svc.setText(input, prompt)
        delay(700)
        val send = svc.elements().firstOrNull { it.clickable && it.text.contains("send", true) }
        if (send != null) svc.tap(send) else svc.pressEnter(input)

        // Jawab complete hone ka wait: screen stable + "stop" button gayab
        val start = System.currentTimeMillis()
        var last = ""
        var same = 0
        delay(4000)
        while (System.currentTimeMillis() - start < 150_000) {
            val now = svc.elements()
            val sig = now.joinToString("|") { it.text }
            val generating = now.any { it.clickable && it.text.contains("stop", true) }
            if (sig == last && !generating) same++ else { same = 0; last = sig }
            if (same >= 3) break
            delay(1500)
        }

        val head = prompt.take(40)
        return svc.elements().map { it.text }.filter {
            it.length > 1 && it !in before && !it.startsWith(head) && !head.startsWith(it)
        }.joinToString("\n")
    }
}
