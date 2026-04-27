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

    const val DEFAULT_MODEL = "anthropic/claude-sonnet-4.5"

    val PRESET_MODELS = listOf(
        "anthropic/claude-sonnet-4.5",
        "anthropic/claude-opus-4.1",
        "openai/gpt-4o",
        "openai/gpt-4o-mini",
        "google/gemini-2.5-pro",
        "google/gemini-2.5-flash",
        "meta-llama/llama-3.3-70b-instruct",
        "deepseek/deepseek-chat"
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
