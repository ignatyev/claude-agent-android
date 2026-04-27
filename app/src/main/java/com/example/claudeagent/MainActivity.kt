package com.example.claudeagent

import android.content.Intent
import android.os.Bundle
import android.provider.Settings as AndroidSettings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    AgentScreen()
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AgentScreen() {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()

    var apiKey by remember { mutableStateOf("") }
    var model by remember { mutableStateOf(Settings.DEFAULT_MODEL) }
    var task by rememberSaveable { mutableStateOf("") }
    var logLines by remember { mutableStateOf(listOf<String>()) }
    var running by remember { mutableStateOf(false) }
    var settingsOpen by remember { mutableStateOf(false) }
    var modelMenuOpen by remember { mutableStateOf(false) }

    // Подгружаем настройки один раз
    LaunchedEffect(Unit) {
        apiKey = Settings.apiKey(ctx).first()
        model = Settings.model(ctx).first()
    }

    val a11yEnabled by produceState(initialValue = false, ctx) {
        // Простая проверка — есть ли активный сервис.
        // Точнее — через AccessibilityManager, но для UI достаточно instance.
        while (true) {
            value = AgentAccessibilityService.instance != null
            kotlinx.coroutines.delay(1000)
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Claude Agent") },
                actions = {
                    IconButton(onClick = { settingsOpen = true }) {
                        Icon(Icons.Default.Settings, contentDescription = "Настройки")
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .padding(16.dp)
                .fillMaxSize(),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // Статус accessibility
            Card {
                Column(Modifier.padding(12.dp)) {
                    Text(
                        "Accessibility: " + if (a11yEnabled) "✓ включён" else "✗ выключен",
                        style = MaterialTheme.typography.bodyMedium
                    )
                    if (!a11yEnabled) {
                        Spacer(Modifier.height(8.dp))
                        Text(
                            "Для работы агента включите службу в настройках Android.",
                            style = MaterialTheme.typography.bodySmall
                        )
                        Spacer(Modifier.height(8.dp))
                        Button(onClick = {
                            ctx.startActivity(
                                Intent(AndroidSettings.ACTION_ACCESSIBILITY_SETTINGS)
                                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                            )
                        }) { Text("Открыть настройки") }
                    }
                }
            }

            // Поле задачи
            OutlinedTextField(
                value = task,
                onValueChange = { task = it },
                label = { Text("Что должен сделать агент?") },
                placeholder = { Text("Напр.: Открой Калькулятор и посчитай 17 * 23") },
                modifier = Modifier.fillMaxWidth(),
                minLines = 2,
                maxLines = 4
            )

            // Запуск
            Button(
                onClick = {
                    val service = AgentAccessibilityService.instance
                    if (service == null) {
                        logLines = logLines + "⚠ Включите Accessibility-службу"
                        return@Button
                    }
                    if (apiKey.isBlank()) {
                        logLines = logLines + "⚠ Не задан API-ключ OpenRouter (см. Настройки)"
                        return@Button
                    }
                    running = true
                    logLines = emptyList()
                    scope.launch {
                        val client = OpenRouterClient(apiKey, model)
                        val loop = AgentLoop(
                            client = client,
                            executor = service,
                            onLog = { line ->
                                logLines = logLines + line
                            }
                        )
                        try {
                            loop.run(task.trim())
                        } catch (e: Exception) {
                            logLines = logLines + "⚠ Исключение: ${e.message}"
                        } finally {
                            running = false
                        }
                    }
                },
                enabled = !running && task.isNotBlank(),
                modifier = Modifier.fillMaxWidth()
            ) {
                Icon(Icons.Default.PlayArrow, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text(if (running) "Работаю…" else "Запустить агента")
            }

            // Лог
            Text("Лог", style = MaterialTheme.typography.titleMedium)
            Card(modifier = Modifier.weight(1f).fillMaxWidth()) {
                SelectionContainer {
                    Column(
                        modifier = Modifier
                            .padding(12.dp)
                            .fillMaxSize()
                            .verticalScroll(rememberScrollState())
                    ) {
                        if (logLines.isEmpty()) {
                            Text(
                                "Здесь появится пошаговый лог рассуждений и действий агента.",
                                style = MaterialTheme.typography.bodySmall
                            )
                        } else {
                            logLines.forEach { line ->
                                Text(
                                    line,
                                    style = MaterialTheme.typography.bodySmall.copy(
                                        fontFamily = FontFamily.Monospace
                                    )
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    // Диалог настроек
    if (settingsOpen) {
        AlertDialog(
            onDismissRequest = { settingsOpen = false },
            title = { Text("Настройки") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    OutlinedTextField(
                        value = apiKey,
                        onValueChange = { apiKey = it },
                        label = { Text("OpenRouter API key") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )

                    ExposedDropdownMenuBox(
                        expanded = modelMenuOpen,
                        onExpandedChange = { modelMenuOpen = it }
                    ) {
                        OutlinedTextField(
                            value = model,
                            onValueChange = { model = it },
                            label = { Text("Модель") },
                            trailingIcon = {
                                Icon(Icons.Default.ArrowDropDown, contentDescription = null)
                            },
                            modifier = Modifier
                                .fillMaxWidth()
                                .menuAnchor()
                        )
                        ExposedDropdownMenu(
                            expanded = modelMenuOpen,
                            onDismissRequest = { modelMenuOpen = false }
                        ) {
                            Settings.PRESET_MODELS.forEach { m ->
                                DropdownMenuItem(
                                    text = { Text(m) },
                                    onClick = {
                                        model = m
                                        modelMenuOpen = false
                                    }
                                )
                            }
                        }
                    }
                    Text(
                        "Можно вписать любую модель из openrouter.ai вручную.",
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    scope.launch {
                        Settings.setApiKey(ctx, apiKey.trim())
                        Settings.setModel(ctx, model.trim())
                    }
                    settingsOpen = false
                }) { Text("Сохранить") }
            },
            dismissButton = {
                TextButton(onClick = { settingsOpen = false }) { Text("Отмена") }
            }
        )
    }
}
