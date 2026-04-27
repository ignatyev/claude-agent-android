package com.example.claudeagent

interface ActionExecutor {
    /** Снимок UI-дерева текущего экрана */
    fun captureScreen(): ScreenSnapshot

    /** Скриншот в PNG. Может быть null, если права не выданы. */
    fun captureScreenshot(): ByteArray?

    /** Список установленных приложений, которые можно запустить */
    fun listLaunchableApps(): List<AppInfo>

    /** Запустить приложение напрямую по package name */
    fun launchApp(packageName: String): Boolean

    /** Выполнить решение агента. Возвращает true при успехе. */
    suspend fun execute(decision: AgentDecision, snapshot: ScreenSnapshot): Boolean
}
