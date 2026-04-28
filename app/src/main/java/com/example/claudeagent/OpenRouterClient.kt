package com.example.claudeagent

import android.util.Base64
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.jsonArray
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit

/**
 * Клиент для OpenRouter API (OpenAI-совместимый /chat/completions).
 * Документация: https://openrouter.ai/docs
 */
class OpenRouterClient(private val apiKey: String, private val model: String) {

    private val http = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .build()

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = false
    }

    /**
     * Отправляет диалог с системным промптом, историей и картинкой текущего экрана.
     * Возвращает текстовый ответ модели (ожидаем JSON с действием).
     */
    fun chat(
        systemPrompt: String,
        userText: String,
        screenshotPng: ByteArray?
    ): String {
        val messages = buildJsonArray {
            add(buildJsonObject {
                put("role", "system")
                put("content", systemPrompt)
            })
            add(buildJsonObject {
                put("role", "user")
                put("content", buildJsonArray {
                    add(buildJsonObject {
                        put("type", "text")
                        put("text", userText)
                    })
                    if (screenshotPng != null) {
                        val b64 = Base64.encodeToString(screenshotPng, Base64.NO_WRAP)
                        add(buildJsonObject {
                            put("type", "image_url")
                            put("image_url", buildJsonObject {
                                put("url", "data:image/png;base64,$b64")
                            })
                        })
                    }
                })
            })
        }

        val body = buildJsonObject {
            put("model", model)
            put("messages", messages)
            put("temperature", 0.2)
            put("max_tokens", 1024)
        }

        val req = Request.Builder()
            .url("https://openrouter.ai/api/v1/chat/completions")
            .addHeader("Authorization", "Bearer $apiKey")
            .addHeader("Content-Type", "application/json")
            .addHeader("HTTP-Referer", "https://github.com/claude-agent-android")
            .addHeader("X-Title", "Claude Agent Android")
            .post(json.encodeToString(JsonObject.serializer(), body)
                .toRequestBody("application/json".toMediaType()))
            .build()

        http.newCall(req).execute().use { resp ->
            val raw = resp.body?.string().orEmpty()
            if (!resp.isSuccessful) {
                if (resp.code == 429) {
                    val resetMs = resp.header("X-RateLimit-Reset")?.toLongOrNull()
                        ?: runCatching {
                            json.parseToJsonElement(raw).jsonObject["error"]
                                ?.jsonObject?.get("metadata")
                                ?.jsonObject?.get("headers")
                                ?.jsonObject?.get("X-RateLimit-Reset")
                                ?.jsonPrimitive?.content?.toLong()
                        }.getOrNull()
                    val resetStr = resetMs?.let {
                        java.text.SimpleDateFormat("dd MMM, HH:mm", java.util.Locale.getDefault())
                            .format(java.util.Date(it))
                    } ?: "неизвестно"
                    throw RuntimeException("Лимит бесплатных запросов исчерпан. Сброс: $resetStr")
                }
                throw RuntimeException("OpenRouter ${resp.code}: $raw")
            }
            val parsed = json.parseToJsonElement(raw).jsonObject
            val choices = parsed["choices"]?.jsonArray
                ?: throw RuntimeException("Нет choices в ответе: $raw")
            val message = choices[0].jsonObject["message"]?.jsonObject
                ?: throw RuntimeException("Нет message в ответе: $raw")
            return message["content"]?.jsonPrimitive?.content
                ?: throw RuntimeException("Нет content в ответе: $raw")
        }
    }
}
