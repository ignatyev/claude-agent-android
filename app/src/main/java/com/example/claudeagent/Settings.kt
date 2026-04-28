package com.example.claudeagent

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.dataStore by preferencesDataStore(name = "agent_settings")

object Settings {
    private val KEY_API = stringPreferencesKey("openrouter_api_key")
    private val KEY_MODEL = stringPreferencesKey("model_id")

    const val DEFAULT_MODEL = "google/gemini-2.0-flash-exp:free"

    val PRESET_MODELS = listOf(
        "google/gemini-2.0-flash-exp:free",
        "google/gemini-2.5-pro-exp-03-25:free",
        "deepseek/deepseek-r1:free",
        "deepseek/deepseek-chat-v3-0324:free",
        "meta-llama/llama-3.3-70b-instruct:free",
        "qwen/qwq-32b:free",
        "mistralai/mistral-small-3.1-24b-instruct:free",
        "microsoft/phi-4-multimodal-instruct:free"
    )

    fun apiKey(ctx: Context): Flow<String> =
        ctx.dataStore.data.map { it[KEY_API] ?: "" }

    fun model(ctx: Context): Flow<String> =
        ctx.dataStore.data.map { it[KEY_MODEL] ?: DEFAULT_MODEL }

    suspend fun setApiKey(ctx: Context, value: String) {
        ctx.dataStore.edit { it[KEY_API] = value }
    }

    suspend fun setModel(ctx: Context, value: String) {
        ctx.dataStore.edit { it[KEY_MODEL] = value }
    }
}
