package com.example.claudeagent

import kotlinx.serialization.Serializable

/**
 * Структурированное действие, которое возвращает LLM.
 * Парсится из JSON в ответе модели.
 */
@Serializable
data class AgentDecision(
    val thought: String,
    val action: AgentActionType,
    val targetElementId: Int? = null,
    val text: String? = null,
    val direction: String? = null,
    val packageName: String? = null,
    val finalAnswer: String? = null
)

@Serializable
enum class AgentActionType {
    TAP,        // тап по элементу (по targetElementId)
    LONG_TAP,   // длинный тап
    INPUT_TEXT, // ввод текста в сфокусированный/указанный элемент
    SWIPE,      // свайп: direction = "up"|"down"|"left"|"right"
    BACK,       // системная кнопка "назад"
    HOME,       // на домашний экран
    OPEN_APP,   // запустить приложение по packageName
    WAIT,       // подождать секунду (после долгих переходов)
    DONE        // задача выполнена; finalAnswer — что сообщить пользователю
}
