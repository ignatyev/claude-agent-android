package com.example.claudeagent

import android.graphics.Rect
import android.view.accessibility.AccessibilityNodeInfo
import kotlinx.serialization.Serializable

@Serializable
data class UiElement(
    val id: Int,
    val text: String?,
    val description: String?,
    val className: String?,
    val resourceId: String?,
    val clickable: Boolean,
    val scrollable: Boolean,
    val editable: Boolean,
    val focused: Boolean,
    val bounds: BoundsBox
)

@Serializable
data class BoundsBox(val left: Int, val top: Int, val right: Int, val bottom: Int) {
    val centerX get() = (left + right) / 2
    val centerY get() = (top + bottom) / 2
}

@Serializable
data class AppInfo(val label: String, val packageName: String)

@Serializable
data class ScreenSnapshot(
    val packageName: String?,
    val elements: List<UiElement>,
    val screenWidth: Int,
    val screenHeight: Int
)

object ScreenScanner {
    /**
     * Обходит дерево accessibility-нод и возвращает список значимых элементов.
     * Берём только видимые на экране и потенциально интерактивные узлы,
     * чтобы не раздувать контекст для LLM.
     */
    fun snapshot(root: AccessibilityNodeInfo?, screenW: Int, screenH: Int): ScreenSnapshot {
        val elements = mutableListOf<UiElement>()
        if (root != null) walk(root, elements, screenW, screenH)
        return ScreenSnapshot(
            packageName = root?.packageName?.toString(),
            elements = elements,
            screenWidth = screenW,
            screenHeight = screenH
        )
    }

    private fun walk(
        node: AccessibilityNodeInfo,
        out: MutableList<UiElement>,
        screenW: Int,
        screenH: Int
    ) {
        val bounds = Rect()
        node.getBoundsInScreen(bounds)
        val visible = bounds.width() > 0 && bounds.height() > 0 &&
            bounds.right > 0 && bounds.bottom > 0 &&
            bounds.left < screenW && bounds.top < screenH

        val text = node.text?.toString()?.takeIf { it.isNotBlank() }
        val desc = node.contentDescription?.toString()?.takeIf { it.isNotBlank() }
        val significant = node.isClickable || node.isLongClickable || node.isScrollable ||
            node.isEditable || text != null || desc != null

        if (visible && significant) {
            out.add(
                UiElement(
                    id = out.size,
                    text = text,
                    description = desc,
                    className = node.className?.toString()?.substringAfterLast('.'),
                    resourceId = node.viewIdResourceName,
                    clickable = node.isClickable,
                    scrollable = node.isScrollable,
                    editable = node.isEditable,
                    focused = node.isFocused,
                    bounds = BoundsBox(bounds.left, bounds.top, bounds.right, bounds.bottom)
                )
            )
        }

        for (i in 0 until node.childCount) {
            node.getChild(i)?.let { walk(it, out, screenW, screenH) }
        }
    }
}
