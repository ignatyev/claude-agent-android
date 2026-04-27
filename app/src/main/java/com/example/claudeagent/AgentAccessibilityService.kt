package com.example.claudeagent

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream

/**
 * Этот сервис должен быть включён вручную в:
 *   Настройки → Спец. возможности → Установленные службы → Claude Agent → Включить
 *
 * После включения он становится глобальным "глазом и рукой" приложения.
 */
class AgentAccessibilityService : AccessibilityService(), ActionExecutor {

    companion object {
        @Volatile
        var instance: AgentAccessibilityService? = null
            private set

        private const val TAG = "AgentA11y"
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        Log.i(TAG, "Accessibility service connected")
    }

    override fun onUnbind(intent: android.content.Intent?): Boolean {
        instance = null
        return super.onUnbind(intent)
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // События нам не нужны — мы опрашиваем экран по запросу.
    }

    override fun onInterrupt() {}

    // ─────────── ActionExecutor ───────────

    override fun captureScreen(): ScreenSnapshot {
        val root = rootInActiveWindow
        val metrics = resources.displayMetrics
        return ScreenScanner.snapshot(root, metrics.widthPixels, metrics.heightPixels)
    }

    override fun captureScreenshot(): ByteArray? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return null
        val deferred = CompletableDeferred<ByteArray?>()
        try {
            takeScreenshot(
                android.view.Display.DEFAULT_DISPLAY,
                mainExecutor,
                object : TakeScreenshotCallback {
                    override fun onSuccess(screenshot: ScreenshotResult) {
                        try {
                            val bmp = android.graphics.Bitmap.wrapHardwareBuffer(
                                screenshot.hardwareBuffer, screenshot.colorSpace
                            )
                            val out = ByteArrayOutputStream()
                            // Для уменьшения трафика — масштабируем до ~1280px по большей стороне
                            val scaled = if (bmp != null && maxOf(bmp.width, bmp.height) > 1280) {
                                val ratio = 1280f / maxOf(bmp.width, bmp.height)
                                android.graphics.Bitmap.createScaledBitmap(
                                    bmp,
                                    (bmp.width * ratio).toInt(),
                                    (bmp.height * ratio).toInt(),
                                    true
                                )
                            } else bmp
                            scaled?.compress(android.graphics.Bitmap.CompressFormat.PNG, 90, out)
                            screenshot.hardwareBuffer.close()
                            deferred.complete(out.toByteArray())
                        } catch (e: Exception) {
                            Log.e(TAG, "Screenshot encode failed", e)
                            deferred.complete(null)
                        }
                    }

                    override fun onFailure(errorCode: Int) {
                        Log.w(TAG, "Screenshot failed: $errorCode")
                        deferred.complete(null)
                    }
                }
            )
        } catch (e: Exception) {
            Log.e(TAG, "takeScreenshot threw", e)
            return null
        }

        // Блокирующее ожидание (вызов идёт из IO-диспетчера в AgentLoop)
        return runCatching {
            kotlinx.coroutines.runBlocking { deferred.await() }
        }.getOrNull()
    }

    override suspend fun execute(decision: AgentDecision, snapshot: ScreenSnapshot): Boolean {
        return when (decision.action) {
            AgentActionType.TAP -> tapElement(decision.targetElementId, snapshot, longPress = false)
            AgentActionType.LONG_TAP -> tapElement(decision.targetElementId, snapshot, longPress = true)
            AgentActionType.INPUT_TEXT -> inputText(decision.targetElementId, decision.text.orEmpty())
            AgentActionType.SWIPE -> swipe(decision.direction ?: "up", snapshot)
            AgentActionType.BACK -> performGlobalAction(GLOBAL_ACTION_BACK)
            AgentActionType.HOME -> performGlobalAction(GLOBAL_ACTION_HOME)
            AgentActionType.OPEN_APP -> openApp(decision.packageName)
            AgentActionType.WAIT -> { delay(1000); true }
            AgentActionType.DONE -> true
        }
    }

    private fun openApp(packageName: String?): Boolean {
        if (packageName.isNullOrBlank()) return false
        val intent = packageManager.getLaunchIntentForPackage(packageName) ?: return false
        intent.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
        startActivity(intent)
        return true
    }

    // ─────────── жесты ───────────

    private suspend fun tapElement(
        elementId: Int?,
        snapshot: ScreenSnapshot,
        longPress: Boolean
    ): Boolean {
        val el = snapshot.elements.firstOrNull { it.id == elementId } ?: return false
        return gesture(
            startX = el.bounds.centerX.toFloat(),
            startY = el.bounds.centerY.toFloat(),
            endX = el.bounds.centerX.toFloat(),
            endY = el.bounds.centerY.toFloat(),
            duration = if (longPress) 800L else 80L
        )
    }

    private fun inputText(elementId: Int?, text: String): Boolean {
        // Ищем сфокусированное редактируемое поле, либо то, на которое указали
        val target = findEditableTarget(elementId) ?: return false
        val args = Bundle().apply {
            putCharSequence(
                AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE,
                text
            )
        }
        return target.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
    }

    private fun findEditableTarget(elementId: Int?): AccessibilityNodeInfo? {
        val root = rootInActiveWindow ?: return null
        // Сначала ищем сфокусированное поле
        root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)?.let { return it }
        // Иначе обходим и ищем editable
        return findEditable(root)
    }

    private fun findEditable(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        if (node.isEditable) return node
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            findEditable(child)?.let { return it }
        }
        return null
    }

    private suspend fun swipe(direction: String, snapshot: ScreenSnapshot): Boolean {
        val w = snapshot.screenWidth.toFloat()
        val h = snapshot.screenHeight.toFloat()
        val cx = w / 2f
        val cy = h / 2f
        val dx = w * 0.35f
        val dy = h * 0.35f
        return when (direction.lowercase()) {
            "up" -> gesture(cx, cy + dy, cx, cy - dy, 300)
            "down" -> gesture(cx, cy - dy, cx, cy + dy, 300)
            "left" -> gesture(cx + dx, cy, cx - dx, cy, 300)
            "right" -> gesture(cx - dx, cy, cx + dx, cy, 300)
            else -> false
        }
    }

    private suspend fun gesture(
        startX: Float, startY: Float,
        endX: Float, endY: Float,
        duration: Long
    ): Boolean {
        val path = Path().apply {
            moveTo(startX, startY)
            if (startX != endX || startY != endY) lineTo(endX, endY)
        }
        val stroke = GestureDescription.StrokeDescription(path, 0, duration)
        val gestureDesc = GestureDescription.Builder().addStroke(stroke).build()

        val deferred = CompletableDeferred<Boolean>()
        val ok = dispatchGesture(
            gestureDesc,
            object : GestureResultCallback() {
                override fun onCompleted(g: GestureDescription?) {
                    deferred.complete(true)
                }
                override fun onCancelled(g: GestureDescription?) {
                    deferred.complete(false)
                }
            },
            null
        )
        if (!ok) return false
        return deferred.await()
    }
}
