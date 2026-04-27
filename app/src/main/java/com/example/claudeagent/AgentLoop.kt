package com.example.claudeagent

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json

/**
 * Основной цикл агента. Не запускается напрямую — используется AccessibilityService'ом,
 * который умеет получать root-узел и выполнять жесты.
 */
class AgentLoop(
    private val client: OpenRouterClient,
    private val executor: ActionExecutor,
    private val onLog: (String) -> Unit,
    private val maxSteps: Int = 20
) {

    private val json = Json { ignoreUnknownKeys = true }
    private val history = StringBuilder()

    suspend fun run(task: String) = withContext(Dispatchers.IO) {
        onLog("▶ Задача: $task")
        history.append("Задача пользователя: $task\n\n")

        val installedApps = executor.listLaunchableApps()
            .joinToString("\n") { "  ${it.label} — ${it.packageName}" }

        // Уходим с UI самого приложения, чтобы агент не взаимодействовал с самим собой
        executor.execute(
            AgentDecision(thought = "go home before starting", action = AgentActionType.HOME),
            executor.captureScreen()
        )
        delay(1200)

        for (step in 1..maxSteps) {
            onLog("─── Шаг $step ───")

            // 1. ВОСПРИЯТИЕ
            val snapshot = executor.captureScreen()
            val screenshot = executor.captureScreenshot()
            val elementsJson = json.encodeToString(
                kotlinx.serialization.builtins.ListSerializer(UiElement.serializer()),
                snapshot.elements.take(80) // ограничиваем чтобы не раздуть промпт
            )

            val userMsg = buildString {
                appendLine("Текущее приложение: ${snapshot.packageName ?: "unknown"}")
                appendLine("Размер экрана: ${snapshot.screenWidth}x${snapshot.screenHeight}")
                appendLine()
                appendLine("Установленные приложения (для OPEN_APP используй packageName):")
                appendLine(installedApps)
                appendLine()
                appendLine("История действий:")
                appendLine(history.toString())
                appendLine()
                appendLine("UI-элементы на экране (JSON, поле id используй для targetElementId):")
                appendLine(elementsJson)
                appendLine()
                appendLine("Скриншот экрана прикреплён. Решай следующий шаг.")
            }

            // 2. РАССУЖДЕНИЕ
            val raw = try {
                client.chat(SYSTEM_PROMPT, userMsg, screenshot)
            } catch (e: Exception) {
                onLog("⚠ Ошибка API: ${e.message}")
                return@withContext
            }

            val decision = parseDecision(raw)
            if (decision == null) {
                onLog("⚠ Не удалось распарсить ответ модели:\n$raw")
                return@withContext
            }

            onLog("💭 ${decision.thought}")
            onLog("➡ ${decision.action}${decision.targetElementId?.let { " #$it" } ?: ""}" +
                (decision.text?.let { " text=\"$it\"" } ?: "") +
                (decision.direction?.let { " dir=$it" } ?: ""))

            history.append("Шаг $step: ${decision.action} — ${decision.thought}\n")

            // 3. ДЕЙСТВИЕ
            if (decision.action == AgentActionType.DONE) {
                onLog("✅ Готово: ${decision.finalAnswer ?: "задача выполнена"}")
                return@withContext
            }

            val ok = executor.execute(decision, snapshot)
            if (!ok) onLog("⚠ Действие не удалось выполнить")

            // 4. Пауза, чтобы UI успел отреагировать
            delay(800)
        }

        onLog("⛔ Превышен лимит шагов ($maxSteps)")
    }

    private fun parseDecision(raw: String): AgentDecision? {
        // Модели иногда оборачивают JSON в ```json ... ```
        val cleaned = raw
            .replace(Regex("```json\\s*"), "")
            .replace(Regex("```\\s*$"), "")
            .trim()

        // Берём всё от первой { до последней }
        val start = cleaned.indexOf('{')
        val end = cleaned.lastIndexOf('}')
        if (start < 0 || end <= start) return null
        val jsonStr = cleaned.substring(start, end + 1)

        return try {
            json.decodeFromString(AgentDecision.serializer(), jsonStr)
        } catch (e: Exception) {
            Log.e("AgentLoop", "Parse failed: ${e.message}\nRaw: $raw")
            null
        }
    }

    companion object {
        const val SYSTEM_PROMPT = """
Ты — ИИ-агент, управляющий Android-телефоном пользователя через accessibility API.

На каждом шаге ты получаешь:
1. Скриншот текущего экрана (PNG)
2. Список UI-элементов в JSON с координатами и свойствами
3. Историю своих предыдущих действий

Твоя задача — выбрать СЛЕДУЮЩЕЕ ОДНО действие, чтобы продвинуться к выполнению задачи пользователя.

Отвечай СТРОГО в формате JSON, без пояснений до или после:
{
  "thought": "краткое рассуждение, что ты видишь и почему выбираешь это действие",
  "action": "TAP|LONG_TAP|INPUT_TEXT|SWIPE|BACK|HOME|OPEN_APP|WAIT|DONE",
  "targetElementId": <int, id элемента из списка, для TAP/LONG_TAP/INPUT_TEXT>,
  "text": "<строка для INPUT_TEXT>",
  "direction": "up|down|left|right (для SWIPE)",
  "packageName": "<package name приложения, только для OPEN_APP>",
  "finalAnswer": "<строка, только если action=DONE>"
}

Правила:
- Используй только id из переданного списка элементов.
- INPUT_TEXT работает по сфокусированному полю; если поле не сфокусировано — сначала TAP по нему.
- SWIPE up прокручивает контент вверх (показывает то, что было ниже).
- OPEN_APP запускает приложение напрямую по package name (например "com.android.calculator2"). Используй это вместо поиска иконки на экране.
- После OPEN_APP всегда делай WAIT, чтобы приложение успело открыться.
- Если задача выполнена — верни DONE с finalAnswer.
- Если застрял или цель невозможна — верни DONE с объяснением в finalAnswer.
- Думай пошагово, не пытайся выполнить всё сразу.
"""
    }
}
